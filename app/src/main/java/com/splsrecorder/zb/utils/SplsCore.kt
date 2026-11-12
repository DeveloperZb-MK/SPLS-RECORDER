package com.splsrecorder.zb.utils

/**
 * The 12 accuracy ranges ("bins") that SPLS counts samples in. A record is counted in the range of
 * the accuracy of its own provider (a network record by network accuracy, a GPS record by GPS accuracy, ...).
 */
object SplsBlocks {
    const val COUNT = 12
    const val MIN_TOTAL = 300

    val LABELS = listOf(
        "0 to 5 m", "5 to 10 m", "10 to 20 m", "20 to 30 m", "30 to 40 m", "40 to 50 m",
        "50 to 60 m", "60 to 80 m", "80 to under 100 m", "Exactly 100 m",
        "Over 100 to under 130 m", "130 m and over"
    )

    // minimum samples per range; 0 = the range is optional
    private val NETWORK_MIN = intArrayOf(10, 10, 15, 15, 15, 15, 15, 15, 15, 10, 10, 10)
    private val OTHER_MIN = intArrayOf(10, 10, 15, 15, 15, 0, 0, 0, 0, 0, 0, 0)

    fun index(acc: Double): Int = when {
        acc <= 5.0 -> 0
        acc <= 10.0 -> 1
        acc <= 20.0 -> 2
        acc <= 30.0 -> 3
        acc <= 40.0 -> 4
        acc <= 50.0 -> 5
        acc <= 60.0 -> 6
        acc <= 80.0 -> 7
        acc < 100.0 -> 8
        acc == 100.0 -> 9
        acc < 130.0 -> 10
        else -> 11
    }

    /** Minimum samples for a range of a provider ("network", "gps" or "fused"); 0 = optional. */
    fun minSamples(provider: String, bin: Int): Int = if (provider == "network") NETWORK_MIN[bin] else OTHER_MIN[bin]

    /** A provider is complete with at least [MIN_TOTAL] records and every compulsory range filled. */
    fun isComplete(provider: String, total: Int, bins: IntArray): Boolean {
        if (total < MIN_TOTAL) return false
        for (i in 0 until COUNT) if (bins[i] < minSamples(provider, i)) return false
        return true
    }
}

/** One recorded location (one line of the CSV file). */
data class SplsRow(
    val timeMs: Long,
    val provider: String,
    val lat: Double,
    val lon: Double,
    val hAcc: Double,
    /** accuracy of the network position at that moment (for a network row: its own accuracy) */
    val netAcc: Double,
    val vAcc: Double?,
    val alt: Double?,
    val msl: Double?,
    val speed: Double,
    /** direction of travel in degrees from true north (0..360); null when Android reports none */
    val bearing: Double? = null,
    /** accuracy of the bearing in degrees; null when not reported */
    val bearingAcc: Double? = null,
    /** jitter: distance in meters from the previous fix of the same provider (null for the first fix) */
    val jitter: Double? = null,
    /** drift: distance in meters from the average position of the last fixes of the same provider */
    val drift: Double? = null
)
