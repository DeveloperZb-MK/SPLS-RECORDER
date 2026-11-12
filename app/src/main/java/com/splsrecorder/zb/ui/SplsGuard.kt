package com.splsrecorder.zb.ui

import android.content.Context

/** In this app nothing can block recording (it never mocks a location), so the list of blockers is always empty. */
object SplsGuard {
    fun blockers(): List<String> = emptyList()
    fun showStopFirst(ctx: Context, items: List<String>) {}
}
