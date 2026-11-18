package io.github.jqssun.gpssetter.spls

import io.github.jqssun.gpssetter.utils.PrefManager

/**
 * Per-provider recording control: each provider (network, gps, fused) can be paused or stopped on its own,
 * and can have its own maximum number of records. Both are kept in the app settings, so they survive a restart.
 */
object SplsProviders {

    val ALL = listOf("network", "gps", "fused")

    const val RECORDING = "recording"
    const val PAUSED = "paused"
    const val STOPPED = "stopped"

    private fun stateKey(p: String) = "spls_prov_state_$p"
    private fun maxKey(p: String) = "spls_prov_max_$p"

    fun label(p: String): String = when (p) {
        "gps" -> "GPS"
        "network" -> "Network"
        else -> "Fused"
    }

    fun state(p: String): String = PrefManager.getRaw(stateKey(p)) ?: RECORDING

    fun setState(p: String, s: String) = PrefManager.setRaw(stateKey(p), if (s == RECORDING) null else s)

    /** True when the provider may record now (not paused, not stopped). */
    fun isRecording(p: String): Boolean = state(p) == RECORDING

    /** Maximum records of one provider in the recorded location. 0 means no limit. */
    fun max(p: String): Int = PrefManager.getRaw(maxKey(p))?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    fun setMax(p: String, value: Int) = PrefManager.setRaw(maxKey(p), if (value > 0) value.toString() else null)

    /** True when the provider already has as many records as its maximum allows. */
    fun limitReached(p: String, count: Int): Boolean {
        val m = max(p)
        return m > 0 && count >= m
    }

    /** A new recording starts with all three providers recording again. The maximums stay as they are. */
    fun resetStates() {
        for (p in ALL) PrefManager.setRaw(stateKey(p), null)
    }

    /** Compact text of everything that affects the pop-up, so it can tell when it must redraw. */
    fun signature(): String = ALL.joinToString("|") { "${state(it)}:${max(it)}" }
}
