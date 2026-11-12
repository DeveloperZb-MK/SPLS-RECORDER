package com.splsrecorder.zb.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.splsrecorder.zb.spls.SplsLocation
import com.splsrecorder.zb.spls.SplsSession
import com.splsrecorder.zb.spls.SplsStore
import com.splsrecorder.zb.utils.SplsBlocks

/** The rules of the SPLS lists: what a record may do, and the warnings shown when a rule blocks an action. */
object SplsRules {

    /** True when the record is the one SPLS is recording into (running or paused). */
    fun isActive(l: SplsLocation): Boolean = SplsSession.isOn() && SplsSession.activeId() == l.id

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
        isActive(l) && SplsSession.isPaused() -> "Paused"
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

    /** Another record is running or paused, so a second one cannot be started. */
    fun warnOtherRecordActive(ctx: Context, wanted: String?) {
        val a = SplsSession.active(ctx)
        val state = if (SplsSession.isPaused()) "paused" else "running"
        val target = if (wanted != null) "\"$wanted\"" else "a new record"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Another record is active")
            .setMessage(
                "The record \"${a?.name ?: "-"}\" is $state (active). Only one record can record at a time.\n\n" +
                    "Please stop \"${a?.name ?: "it"}\" first with its stop button, then start $target."
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
                    say("Something is blocking the recording.")
                } else {
                    SplsSession.resume()
                    say("Recording resumed.")
                }
            } else {
                SplsSession.pause()
                say("Recording paused. Tap play to continue.")
            }
            changed()
            return
        }
        if (SplsSession.isOn()) {
            SplsRules.warnOtherRecordActive(ctx, l.name)
            return
        }
        startNew(l)
    }

    fun stop(ctx: Context, l: SplsLocation, say: (String) -> Unit, changed: () -> Unit) {
        if (!SplsRules.isActive(l)) return
        SplsSession.stopForNow(ctx)
        say("Recording stopped. \"${l.name}\" is saved and can be started again with its play button.")
        changed()
    }
}
