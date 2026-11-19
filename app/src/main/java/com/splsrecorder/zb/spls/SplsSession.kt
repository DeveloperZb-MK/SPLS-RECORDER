package com.splsrecorder.zb.spls

import android.content.Context
import com.splsrecorder.zb.utils.PrefKeys as K
import com.splsrecorder.zb.utils.PrefManager

/** The recording session: one location collects data at a time; paused locations remain resumable. */
object SplsSession {

    private const val PAUSED_IDS_KEY = "spls_paused_location_ids"
    private const val MANUAL_PAUSE_KEY = "spls_manual_pause"
    private const val AUTO_RESUME_BLOCKED_KEY = "spls_auto_resume_blocked_ids"

0
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

        setAutoResumeBlocked(loc.id, false)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, loc.id)
        PrefManager.setRaw(MANUAL_PAUSE_KEY, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        SplsProviders.resetStates()
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        setFeatureEnabled(true)
        SplsRecorderService.start(ctx.applicationContext)
    }

    fun isManuallyPaused(): Boolean = PrefManager.getRaw(MANUAL_PAUSE_KEY) == "true"

    fun pause() {
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.add(id)
            savePausedIds(ids)
        }
        PrefManager.setRaw(MANUAL_PAUSE_KEY, "true")
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        PrefManager.setRaw(K.SPLS_PAUSED, "true")
    }

    /** Pauses on confirmed area exit but allows a later confirmed re-entry to resume. */
    fun pauseForAreaExit() {
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.add(id)
            savePausedIds(ids)
        }
        PrefManager.setRaw(MANUAL_PAUSE_KEY, null)
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        PrefManager.setRaw(K.SPLS_PAUSED, "true")
    }

    fun resume() {
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.remove(id)
            savePausedIds(ids)
        }
        PrefManager.setRaw(MANUAL_PAUSE_KEY, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
    }

    fun resumeFromArea() {
        if (isManuallyPaused()) return
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.remove(id)
            savePausedIds(ids)
        }
        PrefManager.setRaw(K.SPLS_PAUSED, null)
    }

    /** Switches to a different confirmed circle while preserving the previous record as paused. */
    fun switchToArea(loc: SplsLocation) {
        val previousId = activeId()
        val ids = pausedIds()
        if (previousId != null && previousId != loc.id) ids.add(previousId)
        ids.remove(loc.id)
        savePausedIds(ids)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, loc.id)
        PrefManager.setRaw(MANUAL_PAUSE_KEY, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
    }

    /** Starts a confirmed area when the master feature is enabled and no session is active. */
    fun autoStart(ctx: Context, loc: SplsLocation) {
        if (isAutoResumeBlocked(loc.id)) return
        val ids = pausedIds()
        ids.remove(loc.id)
        savePausedIds(ids)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, loc.id)
        PrefManager.setRaw(MANUAL_PAUSE_KEY, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        SplsProviders.resetStates()
    }

    /** Ends a record session without disabling the master area-monitoring switch. */
    fun stopForNow(ctx: Context, blockAutoResumeFor: String? = null) {
        val stoppedId = activeId()
        blockAutoResumeFor?.let {
            setAutoResumeBlocked(it, true)
            val ids = pausedIds()
            ids.remove(it)
            savePausedIds(ids)
        }
        if (stoppedId != null && stoppedId != blockAutoResumeFor && isPaused()) {
            val ids = pausedIds()
            ids.add(stoppedId)
            savePausedIds(ids)
        }
        PrefManager.setRaw(MANUAL_PAUSE_KEY, null)
        PrefManager.setRaw(K.SPLS_ENABLED, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
        if (!isFeatureEnabled()) SplsRecorderService.stop(ctx.applicationContext)
        SplsStore.rebuild(ctx)
    }

    /** Area exit ends only the current session; its record remains eligible for confirmed re-entry. */
    fun clearForAreaExit() {
        activeId()?.let { id ->
            val ids = pausedIds()
            ids.add(id)
            savePausedIds(ids)
        }
        PrefManager.setRaw(MANUAL_PAUSE_KEY, null)
        PrefManager.setRaw(K.SPLS_ENABLED, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
    }

    fun disableFeature(ctx: Context) {
        setFeatureEnabled(false)
        stopForNow(ctx)
        SplsRecorderService.stop(ctx.applicationContext)
    }
}
