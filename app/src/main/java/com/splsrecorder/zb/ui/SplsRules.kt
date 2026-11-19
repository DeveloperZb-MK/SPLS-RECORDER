package com.splsrecorder.zb.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.splsrecorder.zb.spls.SplsLocation
import com.splsrecorder.zb.spls.SplsSession
import com.splsrecorder.zb.spls.SplsStore
import com.splsrecorder.zb.utils.SplsBlocks
import com.splsrecorder.zb.utils.PrefKeys as K
import com.splsrecorder.zb.utils.PrefManager

/** The rules of the SPLS lists: what a record may do, and the warnings shown when a rule blocks an action. */
object SplsRules {

    /** True when the record is the one SPLS is recording into (running or paused). */
    fun isActive(l: SplsLocation): Boolean =
        (SplsSession.isOn() && SplsSession.activeId() == l.id) || SplsSession.isPaused(l.id)

    /** All three providers have their records and every compulsory accuracy block is filled. */
    fun dataComplete(ctx: Context, id: String): Boolean {
        val c = SplsStore.countsOfLocation(ctx, id)
        return listOf("network", "gps", "fused").all { p ->
            val total = when (p) { "gps" -> c.gps; "network" -> c.network; else -> c.fused }
            SplsBlocks.isComplete(p, total, c.bins.getValue(p))
        }
    }

    /** A record can be exported when it is complete and not running (paused or stopped is fine). */
    fun canExport(ctx: Context, l: SplsLocation): Boolean =
        dataComplete(ctx, l.id) && !(isActive(l) && !SplsSession.isPaused())

    fun statusOf(l: SplsLocation): String = when {
        (isActive(l) && SplsSession.isPaused()) || SplsSession.isPaused(l.id) -> "Paused"
        isActive(l) -> "Recording"
        else -> "Stopped"
    }

    fun subtitle(ctx: Context, l: SplsLocation): String =
        "${SplsFlow.describe(l)} - ${SplsStore.rowCount(ctx, l.id)} records - ${statusOf(l)}"

    /** Null when SPLS may record; otherwise the text that explains what is missing (used where the setup steps are not available). */
    fun permissionProblem(ctx: Context): String? {
        val app = ctx.applicationContext
        fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val missing = !granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) ||
            (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) ||
            !pm.isIgnoringBatteryOptimizations(app.packageName)
        return if (missing) {
            "SPLS still needs its permissions. Switch on Enable SPLS recording once in the SPLS pop-up to allow them."
        } else null
    }

    // ------------------------------------------------------------ warnings

    /** A different record is actively recording, so a second active recording is not allowed. */
    fun warnOtherRecordActive(ctx: Context, wanted: String?) {
        val a = SplsSession.active(ctx)
        val target = if (wanted != null) "\"$wanted\"" else "a new record"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Another record is recording")
            .setMessage(
                "The record \"${a?.name ?: "-"}\" is currently recording. Only one record can collect data at a time.\n\n" +
                    "Pause or stop it first, then start $target. A paused record can remain saved and be resumed later."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}

/** The actions behind the buttons of the lists (the same in the SPLS pop-up and in the map's SPLS Locations). */
object SplsActions {

    /**
     * The play / pause button of a record.
     * Active and running: pause. Active and paused: resume. Another record is active: warning.
     * Otherwise [startNew] starts the recording (the caller adds its own permission steps).
     */
    fun playOrPause(ctx: Context, l: SplsLocation, say: (String) -> Unit, startNew: (SplsLocation) -> Unit, changed: () -> Unit) {
        if (SplsSession.isOn() && SplsSession.activeId() == l.id) {
            if (SplsSession.isPaused()) {
                val blockers = SplsGuard.blockers()
                if (blockers.isNotEmpty()) {
                    SplsGuard.showStopFirst(ctx, blockers)
                    say("Switch off the active hook or mocking first.")
                    changed()
                } else {
                    SplsChecklist.show(
                        ctx,
                        onReady = {
                            val currentBlockers = SplsGuard.blockers()
                            if (currentBlockers.isNotEmpty()) {
                                SplsGuard.showStopFirst(ctx, currentBlockers)
                                say("Switch off the active hook or mocking first.")
                            } else {
                                SplsSession.resume()
                                say("Recording resumed.")
                            }
                            changed()
                        },
                        onCancel = { changed() },
                        confirmReady = true
                    )
                }
            } else {
                SplsSession.pause()
                say("Recording paused. You can use mocking now. Tap play to continue.")
                changed()
            }
            return
        }
        if (SplsSession.isOn() && !SplsSession.isPaused()) {
            SplsRules.warnOtherRecordActive(ctx, l.name)
            return
        }
        // If the current record is paused, starting this one switches the single active
        // session while preserving the previous record's paused state and saved CSV data.
        startNew(l)
    }

    fun stop(ctx: Context, l: SplsLocation, say: (String) -> Unit, changed: () -> Unit) {
        if (!SplsRules.isActive(l)) return

        fun finishStop() {
            SplsSession.stopForNow(ctx)
            say("Recording stopped. \"" + l.name + "\" will not resume automatically; tap Play to start it again.")
            changed()
        }

        if (!l.isCircle || PrefManager.getRaw(K.SPLS_STOP_WARNING_HIDDEN) == "true") {
            finishStop()
            return
        }

        val content = LinearLayout(ctx).apply {
            val density = ctx.resources.displayMetrics.density
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        }
        val remember = CheckBox(ctx).apply {
            text = "Do not show this message again"
            isChecked = false
        }
        content.addView(remember)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Stop SPLS recording?")
            .setMessage(
                "Stopping ends automatic area-based resumption for this record. If you want this record to resume automatically when you re-enter its saved area while Enable SPLS recording is on, choose Pause instead.\n\n" +
                    "You can always start this record again manually with Play."
            )
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Stop") { _, _ ->
                if (remember.isChecked) PrefManager.setRaw(K.SPLS_STOP_WARNING_HIDDEN, "true")
                finishStop()
            }
            .show()
    }
}
