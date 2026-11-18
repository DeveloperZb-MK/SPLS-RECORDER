package io.github.jqssun.gpssetter.utils

import io.github.jqssun.gpssetter.utils.PrefKeys as K

/**
 * Decides whether the "Network-Linked ..." toggles may be switched on.
 * Every provider must be complete: at least [SplsBlocks.MIN_TOTAL] records and the required samples
 * in each compulsory accuracy range.
 */
object SplsGate {

    const val MIN_RECORDS = SplsBlocks.MIN_TOTAL

    private fun total(provider: String): Int {
        val key = when (provider) {
            "gps" -> K.SPLS_N_GPS
            "network" -> K.SPLS_N_NETWORK
            else -> K.SPLS_N_FUSED
        }
        return PrefManager.getRaw(key)?.trim()?.toIntOrNull() ?: 0
    }

    private fun bins(provider: String): IntArray {
        val key = when (provider) {
            "gps" -> K.SPLS_BINS_GPS
            "network" -> K.SPLS_BINS_NETWORK
            else -> K.SPLS_BINS_FUSED
        }
        val parts = PrefManager.getRaw(key)?.split(',') ?: emptyList()
        return IntArray(SplsBlocks.COUNT) { i -> parts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0 }
    }

    fun isComplete(provider: String): Boolean = SplsBlocks.isComplete(provider, total(provider), bins(provider))

    fun allComplete(): Boolean = listOf("network", "gps", "fused").all { isComplete(it) }

    /** True when a record is activated in Learned Behaviour Deployment (the switch is on and a record is chosen). */
    fun hasDeployed(): Boolean =
        PrefManager.getRaw(K.SPLS_SELECT_ON) == "true" && !PrefManager.getRaw(K.SPLS_SELECTED_ID).isNullOrEmpty()

    const val NO_RECORD_MESSAGE = "Activate a record in Learned Behaviour Deployment first."

    /** null = allowed, otherwise the warning to show. */
    fun check(): String? {
        if (!hasDeployed()) return NO_RECORD_MESSAGE
        val notReady = listOf("network", "gps", "fused").filter { !isComplete(it) }
        if (notReady.isEmpty()) return null
        val names = mapOf("network" to "Network", "gps" to "GPS", "fused" to "Fused")
        return "There are not enough records for SPLS to function. Each provider needs at least $MIN_RECORDS records " +
            "and the required samples in every compulsory accuracy range. Not ready: " +
            notReady.joinToString(", ") { "${names[it]} (${total(it)} of $MIN_RECORDS)" } +
            ". Open Smart provider learning system to see what is missing."
    }

    /** MSL is not locked to any Android version: on older Android it is recorded from the GPS (NMEA) data. */
    fun checkMsl(): String? = check()

    // ------------------------------------------------------------ motion (jitter, drift, bearing)

    private var motionText: String? = null
    private var motionParsed: Map<String, SplsMotionProvider>? = null

    private fun motion(): Map<String, SplsMotionProvider>? {
        val t = PrefManager.getRaw(K.SPLS_MOTION_MODEL)
        if (t != motionText) {
            motionText = t
            motionParsed = SplsMotionModel.parse(t)
        }
        return motionParsed
    }

    private fun stillMessage(): String? {
        val m = motion()
        val names = mapOf("network" to "Network", "gps" to "GPS", "fused" to "Fused")
        val notReady = listOf("network", "gps", "fused").filter { (m?.get(it)?.still ?: 0) < SplsMotionModel.MIN_STILL }
        if (notReady.isEmpty()) return null
        return "Not enough still records. Record while standing still. Not ready: " +
            notReady.joinToString(", ") { "${names[it]} (${m?.get(it)?.still ?: 0} of ${SplsMotionModel.MIN_STILL})" }
    }

    fun checkJitter(): String? = check() ?: stillMessage()

    fun checkDrift(): String? {
        check()?.let { return it }
        stillMessage()?.let { return it }
        if (PrefManager.getRaw(K.JITTER_LINK) != "true") return "Switch on Position jitter first."
        return null
    }

    fun checkBearing(): String? {
        check()?.let { return it }
        val m = motion()
        val ok = m != null && listOf("network", "gps", "fused").all { m[it]?.bearingShare != null }
        return if (ok) null else "These records have no bearing data. Record again with SPLS Recorder."
    }
}
