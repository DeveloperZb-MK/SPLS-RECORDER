package io.github.jqssun.gpssetter.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.utils.PrefKeys as K
import io.github.jqssun.gpssetter.utils.PrefManager

/** Prevents legacy location-mocking mode from contaminating SPLS recordings. */
object SplsGuard {

    /**
     * True whenever the SPLS feature is enabled, including while a record is paused,
     * stopped, or while the area monitor is waiting to start a record.
     * Legacy mocking mode must remain disabled for this entire period.
     */
    fun recording(): Boolean = PrefManager.getRaw(K.SPLS_FEATURE_ENABLED) == "true"

    /** Something that must be switched off before SPLS recording can start. Empty = nothing in the way. */
    fun blockers(): List<String> {
        val items = ArrayList<String>()
        if (PrefManager.isStarted) items.add("Pause mocking: tap the stop button on the map screen.")
        return items
    }

    fun showStopFirst(
        ctx: Context,
        items: List<String>,
        acknowledged: () -> Unit = {}
    ) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Switch these off first")
            .setMessage(
                "SPLS records how the location providers really behave, so the following must be off before it can start:\n\n" +
                    items.joinToString("\n\n") { "• $it" } +
                    "\n\nThen come back and turn on Enable SPLS recording again."
            )
            .setPositiveButton("OK") { _, _ -> acknowledged() }
            .setOnCancelListener { acknowledged() }
            .show()
    }

    /** Returns true (and explains why) when mocking must not start while SPLS is enabled. */
    fun blockMock(ctx: Context): Boolean {
        if (!recording()) return false
        showStopSplsFirst(ctx, "start mocking")
        return true
    }

    fun showStopSplsFirst(ctx: Context, what: String) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Stop SPLS recording first")
            .setMessage(
                "Enable SPLS recording is on, so you cannot $what now.\n\n" +
                    "1. Open Settings, then Smart provider learning system.\n" +
                    "2. Turn OFF Enable SPLS recording. Pausing or stopping an individual record is not enough.\n" +
                    "3. Come back and $what.\n\n" +
                    "Everything recorded so far is kept."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
