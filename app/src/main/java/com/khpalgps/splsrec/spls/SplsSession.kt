package com.khpalgps.splsrec.spls

import android.content.Context
import com.khpalgps.splsrec.utils.PrefKeys as K
import com.khpalgps.splsrec.utils.PrefManager

/** The recording session: which location is recorded into, and whether it is running, paused or stopped. */
object SplsSession {

    fun isOn(): Boolean = PrefManager.getRaw(K.SPLS_ENABLED) == "true"
    fun isPaused(): Boolean = PrefManager.getRaw(K.SPLS_PAUSED) == "true"
    fun activeId(): String? = PrefManager.getRaw(K.SPLS_ACTIVE_ID)
    fun active(ctx: Context): SplsLocation? = activeId()?.let { SplsStore.find(ctx, it) }

    fun start(ctx: Context, loc: SplsLocation) {
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, loc.id)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ENABLED, "true")
        SplsRecorderService.start(ctx.applicationContext)
    }

    fun pause() = PrefManager.setRaw(K.SPLS_PAUSED, "true")

    fun resume() = PrefManager.setRaw(K.SPLS_PAUSED, null)

    /** Ends the session but keeps the record, so it can be resumed later. */
    fun stopForNow(ctx: Context) {
        PrefManager.setRaw(K.SPLS_ENABLED, null)
        PrefManager.setRaw(K.SPLS_PAUSED, null)
        PrefManager.setRaw(K.SPLS_ACTIVE_ID, null)
        SplsRecorderService.stop(ctx.applicationContext)
        SplsStore.rebuild(ctx)
    }
}
