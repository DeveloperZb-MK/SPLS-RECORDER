package com.splsrecorder.zb.spls

import android.content.Context
import com.splsrecorder.zb.utils.PrefKeys as K
import com.splsrecorder.zb.utils.PrefManager

/** The recording session: one location collects data at a time; paused locations remain resumable. */
object SplsSession {

    private const val PAUSED_IDS_KEY = "spls_paused_location_ids"

    fun isOn(): Boolean = PrefManager.getRaw(K.SPLS_ENABLED) == "true"
    fun isPaused(): Boolean = PrefManager.getRaw(K.SPLS_PAUSED) == "true"
    fun activeId(): String? = PrefManager.getRaw(K.SPLS_ACTIVE_ID)
    fun active(ctx: Context): SplsLocation? = activeId()?.let { SplsStore.find(ctx, it) }

    private fun pausedIds(): MutableSet<String> =
        PrefManager.getRaw(PAUSED_IDS_KEY).orEmpty()
            .split('|').filter { it.isNotBlank() }.toMutableSet()

    private fun savePausedIds(ids: Set<String>) {
        PrefManager.setRaw(PAUSED_IDS_KEY, ids.takeIf { it.isNotEmpty() }?.joinToString("|"))
    }

    /** True if this particular record was paused, including after another record became active. */
    fun isPaused(locationId: String): Boolean = locationId in pausedIds()

    fun start(ctx: Context, loc: SplsLocation) {
        // Switching away from a paused session preserves that record's paused state.
        val previousId = activeId()
        if (previousId != null && isPaused()) {
            val ids = pausedIds()
            ids.add(previousId)
            savePausedIds(ids)
        }
        // Resuming this record consumes its saved paused state.
        val ids = pausedIds()
        ids.remove(loc.id)
        savePausedIds(ids)

        PrefManager.setRaw(K.SPLS_ACTIVE_ID, loc.id)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        SplsProviders.resetStates()
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        SplsRecorderService.start(ctx.applicationContext)
    }

    fun pause() {
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.add(id)
            savePausedIds(ids)
        }
        // Pausing must never turn recording off or clear the active record.
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        PrefManager.setRaw(K.SPLS_PAUSED, "true")
    }

    fun resume() {
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.remove(id)
            savePausedIds(ids)
        }
        PrefManager.setRaw(K.SPLS_PAUSED, null)
    }

    /** Ends the active session but keeps its data and any other paused records. */
    fun stopForNow(ctx: Context) {
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.remove(id)
            savePausedIds(ids)
        }
        PrefManager.setRaw(K.SPLS_ENABLED, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
        SplsRecorderService.stop(ctx.applicationContext)
        SplsStore.rebuild(ctx)
    }
}
