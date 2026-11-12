package com.khpalgps.splsrec.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.khpalgps.splsrec.R
import com.khpalgps.splsrec.spls.SplsLocation
import com.khpalgps.splsrec.spls.SplsSession
import com.khpalgps.splsrec.spls.SplsStore
import com.khpalgps.splsrec.utils.PrefKeys as K
import com.khpalgps.splsrec.utils.PrefManager

/** What happens after SPLS recording was switched on: start a new recording or resume an old one. */
object SplsFlow {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun describe(l: SplsLocation): String =
        if (l.isCircle) "area ${SplsPrompts.fmt(l.radius)} m" else "no area limit"

    private fun taken(ctx: Context) = SplsStore.locations(ctx).map { it.name }

    fun chooseStart(ctx: Context, notify: (String) -> Unit, done: () -> Unit, canceled: () -> Unit, leave: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("How do you want to record?")
            .setMessage("Start a new recording, or resume one that you stopped earlier.")
            .setPositiveButton("Start new recording") { _, _ -> newRecording(ctx, notify, done, canceled, leave) }
            .setNeutralButton("Resume old recording") { _, _ -> resumeOld(ctx, done, canceled) }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> canceled() }
            .setOnCancelListener { canceled() }
            .show()
    }

    // ------------------------------------------------------------ new recording: three ways

    fun newRecording(ctx: Context, notify: (String) -> Unit, done: () -> Unit, canceled: () -> Unit, leave: () -> Unit) {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), 0)
        }
        var dialog: AlertDialog? = null
        fun row(label: String, help: String, onOn: () -> Unit) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 10), 0, 0)
            }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply { text = label; textSize = 15.5f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            texts.addView(TextView(ctx).apply { text = help; textSize = 12.5f; alpha = 0.7f })
            line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = MaterialSwitch(ctx)
            sw.setOnCheckedChangeListener { _, on ->
                if (on) {
                    dialog?.dismiss()
                    onOn()
                }
            }
            line.addView(sw)
            column.addView(line)
        }
        
        // 1. Current location
        row("Current location", "Records only while you are inside a circle around where you are now.") {
            startHere(ctx, notify, done, canceled)
        }
        
        // 2. Select another location
        row("Select another location", "Pick an area on the map. Records only while you are inside it.") {
            PrefManager.setRaw(K.SPLS_PICK_PENDING, "true")
            leave()
        }
        
        // 3. Continued recording without area limitation
        row("Continued recording without area limitation", "Records everywhere until you stop it.") {
            startContinuous(ctx, done, canceled)
        }

        val created = MaterialAlertDialogBuilder(ctx)
            .setTitle("Start new recording")
            .setView(column)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> canceled() }
            .setOnCancelListener { canceled() }
            .create()
        dialog = created
        created.show()
    }

    /** Saves the location and starts the recording, unless something blocks it. */
    fun begin(ctx: Context, loc: SplsLocation, done: () -> Unit, canceled: () -> Unit) {
        val blockers = SplsGuard.blockers()
        if (blockers.isNotEmpty()) {
            SplsGuard.showStopFirst(ctx, blockers)
            canceled()
            return
        }
        SplsSession.start(ctx, loc)
        done()
    }

    private fun startContinuous(ctx: Context, done: () -> Unit, canceled: () -> Unit) {
        SplsPrompts.askName(ctx, "Name this recording", "", taken(ctx), { name ->
            val loc = SplsLocation(SplsStore.newId(), name, SplsLocation.MODE_CONTINUOUS, 0.0, 0.0, SplsLocation.MIN_RADIUS, false, System.currentTimeMillis())
            SplsStore.addLocation(ctx, loc)
            begin(ctx, loc, done, canceled)
        }, canceled)
    }

    private fun startHere(ctx: Context, notify: (String) -> Unit, done: () -> Unit, canceled: () -> Unit) {
        val fix = currentFix(ctx)
        if (fix == null) {
            notify("No current location yet. Turn on location, wait a moment for a fix and try again.")
            canceled()
            return
        }
        SplsPrompts.askName(ctx, "Name this location", "", taken(ctx), { name ->
            SplsPrompts.askRadius(ctx, "Radius around you", SplsLocation.MIN_RADIUS, false, null, { radius ->
                val loc = SplsLocation(SplsStore.newId(), name, SplsLocation.MODE_CIRCLE, fix.latitude, fix.longitude, radius, false, System.currentTimeMillis())
                SplsStore.addLocation(ctx, loc)
                begin(ctx, loc, done, canceled)
            }, canceled)
        }, canceled)
    }

    @SuppressLint("MissingPermission")
    fun currentFix(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            if (best == null || l.elapsedRealtimeNanos > best.elapsedRealtimeNanos) best = l
        }
        return best
    }

    // ------------------------------------------------------------ resume an old recording

    private fun resumeOld(ctx: Context, done: () -> Unit, canceled: () -> Unit) {
        val open = SplsStore.locations(ctx)
        if (open.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Resume old recording")
                .setMessage("There is no earlier recording to resume. Start a new recording instead.")
                .setPositiveButton("OK") { _, _ -> canceled() }
                .setOnCancelListener { canceled() }
                .show()
            return
        }
        val labels = open.map { "${it.name}\n${SplsStore.rowCount(ctx, it.id)} records, ${describe(it)}" }.toTypedArray()
        var chosen = -1
        val d = MaterialAlertDialogBuilder(ctx)
            .setTitle("Resume old recording")
            .setSingleChoiceItems(labels, -1) { dlg, which ->
                chosen = which
                (dlg as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
            .setPositiveButton("Resume") { _, _ -> if (chosen >= 0) begin(ctx, open[chosen], done, canceled) }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> canceled() }
            .setOnCancelListener { canceled() }
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false }
        d.show()
    }
}
