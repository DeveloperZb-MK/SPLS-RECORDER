package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.spls.SplsLocation
import io.github.jqssun.gpssetter.spls.SplsSession
import io.github.jqssun.gpssetter.spls.SplsStore
import io.github.jqssun.gpssetter.utils.PrefKeys as K
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.SplsBlocks

/** The rules of the SPLS lists: what a record may do, and the warnings shown when a rule blocks an action. */
object SplsRules {

    /** Minimum records each provider needs before a record can be injected. */
    const val MIN_RECORDS = SplsBlocks.MIN_TOTAL

    /** True when the record is the one SPLS is recording into (running or paused). */
    fun isActive(l: SplsLocation): Boolean = SplsSession.isRecording(l.id) || SplsSession.isPausedRecord(l.id)

    /** All three providers have their records and every compulsory accuracy block is filled. */
    fun dataComplete(ctx: Context, id: String): Boolean {
        val c = SplsStore.countsOfLocation(ctx, id)
        return listOf("network", "gps", "fused").all { p ->
            val total = when (p) { "gps" -> c.gps; "network" -> c.network; else -> c.fused }
            SplsBlocks.isComplete(p, total, c.bins.getValue(p))
        }
    }

    /**
     * One line per provider, showing what is still missing.
     * Ready providers are listed too, so the user sees the whole picture at once.
     */
    fun incompleteReport(ctx: Context, id: String): List<String> {
        val c = SplsStore.countsOfLocation(ctx, id)
        return listOf("network", "gps", "fused").map { p ->
            val total = when (p) { "gps" -> c.gps; "network" -> c.network; else -> c.fused }
            val label = when (p) { "gps" -> "GPS"; "network" -> "Network"; else -> "Fused" }
            when {
                SplsBlocks.isComplete(p, total, c.bins.getValue(p)) ->
                    "\u2022 $label: ready ($total records)"
                total < MIN_RECORDS ->
                    "\u2022 $label: $total/$MIN_RECORDS records \u2014 ${MIN_RECORDS - total} more needed"
                else ->
                    "\u2022 $label: $total records, but compulsory accuracy blocks are still empty"
            }
        }
    }

    /** A record can be exported when it is complete and not running (paused or stopped is fine). */
    fun canExport(ctx: Context, l: SplsLocation): Boolean =
        dataComplete(ctx, l.id) && !SplsSession.isRecording(l.id)

    /** The record whose learned data is injected into the fake location, or null. */
    fun deployed(ctx: Context): SplsLocation? {
        if (PrefManager.getRaw(K.SPLS_SELECT_ON) != "true") return null
        val id = PrefManager.getRaw(K.SPLS_SELECTED_ID) ?: return null
        return SplsStore.find(ctx, id)
    }

    fun statusOf(l: SplsLocation): String = when {
        SplsSession.isRecording(l.id) -> "Recording"
        SplsSession.isPausedRecord(l.id) -> "Paused"
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
            "SPLS still needs its permissions. Open Settings, then Smart provider learning system, and switch on Enable SPLS recording once to allow them."
        } else null
    }

    // ------------------------------------------------------------ warnings

    /** The record is not ready to be injected (missing records or empty compulsory blocks). */
    fun warnIncomplete(ctx: Context, l: SplsLocation) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Cannot inject this record yet")
            .setMessage(
                "\"${l.name}\" is not ready to be injected into your fake location.\n\n" +
                    "Every provider needs at least $MIN_RECORDS records and every compulsory accuracy block must be filled:\n\n" +
                    incompleteReport(ctx, l.id).joinToString("\n") +
                    "\n\nKeep recording this record until all three providers are ready, then tap inject again."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** Another record is currently recording, so a second one cannot record simultaneously. */
    fun warnOtherRecordActive(ctx: Context, wanted: String?) {
        val a = SplsSession.active(ctx)
        val recordName = a?.name ?: "-"
        val number = a?.let { active ->
            SplsStore.locations(ctx).indexOfFirst { it.id == active.id }.let { if (it >= 0) it + 1 else 0 }
        } ?: 0
        val target = if (wanted != null) "\"$wanted\"" else "a new record"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Another record is active")
            .setMessage(
                "The record numbered $number (\"$recordName\") is currently active. Only one record can record at a time.\n\n" +
                    "Please pause/stop \"$recordName\" first, then start $target."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** Another record is already injected, so a second one cannot be activated. */
    fun warnOtherDeployed(ctx: Context, current: String, wanted: String) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Another record is injected")
            .setMessage(
                "The learned data of \"$current\" is already injected into your fake location. Only one record can be injected at a time.\n\n" +
                    "Please deactivate \"$current\" first, then activate \"$wanted\"."
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
        // A paused record does not own the recorder. Starting another record is safe:
        // its rows remain stored, and it stays eligible for automatic area resumption
        // unless the user explicitly stopped it (auto-resume blocked).
        startNew(l)
    }

    fun stop(ctx: Context, l: SplsLocation, say: (String) -> Unit, changed: () -> Unit) {
        if (!SplsRules.isActive(l)) return

        fun finishStop() {
            if (SplsSession.activeId() == l.id && SplsSession.isOn()) SplsSession.stopForNow(ctx, blockAutoResumeFor = l.id) else SplsSession.stopPausedRecord(ctx, l.id, blockAutoResume = true)
            say("Recording stopped. \"${l.name}\" will not resume automatically; tap Play to start it again.")
            changed()
        }

        // A continuous (unbounded) record has no saved-area auto-resume behaviour to explain.
        if (!l.isCircle || PrefManager.getRaw(K.SPLS_STOP_WARNING_HIDDEN) == "true") {
            finishStop()
            return
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * ctx.resources.displayMetrics.density).toInt(),
                (8 * ctx.resources.displayMetrics.density).toInt(),
                (24 * ctx.resources.displayMetrics.density).toInt(),
                0
            )
        }
        val remember = CheckBox(ctx).apply {
            text = "Don't show this message again"
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

    fun activate(ctx: Context, l: SplsLocation, say: (String) -> Unit, changed: () -> Unit) {
        // a record can be injected only when every provider has its MIN_RECORDS records
        // and all compulsory blocks are filled — show a pop-up instead of a toast
        if (!SplsRules.dataComplete(ctx, l.id)) {
            SplsRules.warnIncomplete(ctx, l)
            return
        }
        val current = SplsRules.deployed(ctx)
        if (current != null && current.id != l.id) {
            SplsRules.warnOtherDeployed(ctx, current.name, l.name)
            return
        }
        PrefManager.setRaw(K.SPLS_SELECTED_ID, l.id)
        PrefManager.setRaw(K.SPLS_SELECT_ON, "true")
        SplsStore.rebuild(ctx)
        say("\"${l.name}\" is now injected into your fake location.")
        changed()
    }

    fun deactivate(ctx: Context, l: SplsLocation, say: (String) -> Unit, changed: () -> Unit) {
        PrefManager.setRaw(K.SPLS_SELECTED_ID, null)
        SplsStore.rebuild(ctx)
        say("\"${l.name}\" is no longer injected. The linked settings are switched off.")
        changed()
    }
}
