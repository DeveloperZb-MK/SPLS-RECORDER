package io.github.jqssun.gpssetter.spls

import android.content.Context
import io.github.jqssun.gpssetter.utils.PrefKeys as K
import io.github.jqssun.gpssetter.utils.PrefManager

/** The recorder runs one record at a time; any number of other records may remain paused. */
object SplsSession {

    fun isOn(): Boolean = PrefManager.getRaw(K.SPLS_ENABLED) == "true"
    fun isFeatureEnabled(): Boolean = PrefManager.getRaw(K.SPLS_FEATURE_ENABLED) == "true"
    fun setFeatureEnabled(enabled: Boolean) = PrefManager.setRaw(K.SPLS_FEATURE_ENABLED, if (enabled) "true" else null)
    fun isPaused(): Boolean = PrefManager.getRaw(K.SPLS_PAUSED) == "true"
    fun activeId(): String? = PrefManager.getRaw(K.SPLS_ACTIVE_ID)
    fun active(ctx: Context): SplsLocation? = activeId()?.let { SplsStore.find(ctx, it) }

    private fun pausedRecordIds(): MutableSet<String> =
        PrefManager.getRaw(K.SPLS_PAUSED_RECORD_IDS)
            ?.split("|")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()

    private fun setRecordPaused(id: String, paused: Boolean) {
        val ids = pausedRecordIds()
        if (paused) ids.add(id) else ids.remove(id)
        PrefManager.setRaw(K.SPLS_PAUSED_RECORD_IDS, ids.takeIf { it.isNotEmpty() }?.joinToString("|"))
    }

    fun isPausedRecord(id: String): Boolean =
        pausedRecordIds().contains(id) || (isOn() && activeId() == id && isPaused())

    fun isRecording(id: String): Boolean = isOn() && activeId() == id && !isPaused()

    private fun blockedAutoResumeIds(): MutableSet<String> =
        PrefManager.getRaw(K.SPLS_AUTO_RESUME_BLOCKED_IDS)
            ?.split("|")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()

    fun isAutoResumeBlocked(id: String): Boolean = blockedAutoResumeIds().contains(id)

    private fun setAutoResumeBlocked(id: String, blocked: Boolean) {
        val ids = blockedAutoResumeIds()
        if (blocked) ids.add(id) else ids.remove(id)
        PrefManager.setRaw(K.SPLS_AUTO_RESUME_BLOCKED_IDS, ids.takeIf { it.isNotEmpty() }?.joinToString("|"))
    }

    fun start(ctx: Context, loc: SplsLocation) {
        setAutoResumeBlocked(loc.id, false)
        setRecordPaused(loc.id, false)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, loc.id)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        SplsProviders.resetStates()
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        setFeatureEnabled(true)
        SplsDiagnostics.record(ctx, "RECORDING_STARTED", "record=${loc.id}")
        SplsRecorderService.start(ctx.applicationContext)
    }

    /** Starts/resumes an area automatically when the feature is enabled and the user enters its radius. */
    fun autoStart(ctx: Context, loc: SplsLocation) {
        val previous = activeId()
        if (isOn() && previous != null && previous != loc.id && !isPaused()) {
            setRecordPaused(previous, true)
        }
        setRecordPaused(loc.id, false)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, loc.id)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        SplsDiagnostics.record(ctx, "AREA_AUTO_RESUMED", "record=${loc.id}")
    }

    /** Ends only the current area session; its record remains paused and eligible for area re-entry. */
    fun clearForAreaExit() {
        activeId()?.let { setRecordPaused(it, true) }
        PrefManager.setRaw(K.SPLS_ENABLED, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
    }

    /** Switches to another area while preserving the previous record as paused. */
    fun switchTo(id: String) {
        val previous = activeId()
        if (previous != null && previous != id) setRecordPaused(previous, true)
        setRecordPaused(id, false)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, id)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
    }

    fun pause() {
        activeId()?.let { setRecordPaused(it, true) }
        PrefManager.setRaw(K.SPLS_PAUSED, "true")
        val ctx = io.github.jqssun.gpssetter.gsApp.applicationContext
        SplsDiagnostics.record(ctx, "RECORDING_PAUSED")
        SplsDiagnostics.record(ctx, "FINAL_REBUILD_REQUESTED_ON_PAUSE")
        SplsStore.rebuildAsync(ctx)
    }

    fun resume() {
        activeId()?.let { setRecordPaused(it, false) }
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        SplsDiagnostics.record(io.github.jqssun.gpssetter.gsApp, "RECORDING_RESUMED")
    }

    /** Stops a paused record without disturbing whichever other record is currently running. */
    fun stopPausedRecord(ctx: Context, id: String, blockAutoResume: Boolean = true) {
        setRecordPaused(id, false)
        if (blockAutoResume) setAutoResumeBlocked(id, true)
        SplsDiagnostics.record(ctx, "RECORDING_STOPPED", "record=$id")
        SplsStore.rebuildAsync(ctx)
    }

    /** Ends the current session but keeps its data, so it can be resumed later. */
    fun stopForNow(ctx: Context, blockAutoResumeFor: String? = null) {
        val stoppedId = activeId()
        blockAutoResumeFor?.let {
            setAutoResumeBlocked(it, true)
            setRecordPaused(it, false)
        }
        if (stoppedId != null && stoppedId != blockAutoResumeFor && isPaused()) {
            setRecordPaused(stoppedId, true)
        }
        PrefManager.setRaw(K.SPLS_ENABLED, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
        SplsDiagnostics.record(ctx, "RECORDING_STOPPED")
        SplsProviders.resetStates()
        if (!isFeatureEnabled()) SplsRecorderService.stop(ctx.applicationContext)
        SplsStore.rebuildAsync(ctx)
    }
}
