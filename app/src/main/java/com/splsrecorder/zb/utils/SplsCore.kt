package com.splsrecorder.zb.utils

/**
 * The 12 accuracy ranges ("bins") that SPLS counts samples in. A record is counted in the range of
 * the accuracy of its own provider (a network record by network accuracy, a GPS record by GPS accuracy, ...).
 */
object SplsBlocks {
    const val COUNT = 12
    const val MIN_TOTAL = 500

    val LABELS = listOf(
        "1 to <5 m", "5 to <10 m", "10 to <20 m", "20 to <30 m", "30 to <40 m",
        "40 to <50 m", "50 to <60 m", "60 to <80 m", "80 to <100 m",
        "Exactly 100 m", ">100 to <130 m", "130 m and above"
    )

    // minimum samples per range; 0 = the range is optional
    private val NETWORK_MIN = intArrayOf(0, 0, 15, 15, 15, 15, 15, 15, 0, 0, 0, 0)
    private val GPS_MIN = intArrayOf(0, 0, 15, 15, 15, 0, 0, 0, 0, 0, 0, 0)
    private val FUSED_MIN = intArrayOf(10, 10, 15, 15, 15, 0, 0, 0, 0, 0, 0, 0)

    /** Half-open ranges: exactly 5.0 m belongs to "5 to <10 m". Below 1 m belongs to the first range. */
    fun index(acc: Double): Int = when {
        acc < 5.0 -> 0
        acc < 10.0 -> 1
        acc < 20.0 -> 2
        acc < 30.0 -> 3
        acc < 40.0 -> 4
        acc < 50.0 -> 5
        acc < 60.0 -> 6
        acc < 80.0 -> 7
        acc < 100.0 -> 8
        acc == 100.0 -> 9
        acc < 130.0 -> 10
        else -> 11
    }

    /** Minimum samples for a range of a provider ("network", "gps" or "fused"); 0 = optional. */
    fun minSamples(provider: String, bin: Int): Int = when (provider) {
        "network" -> NETWORK_MIN[bin]
        "gps" -> GPS_MIN[bin]
        else -> FUSED_MIN[bin]
    }

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
    val gpsHorizontalAccuracy: Double?,
    val networkHorizontalAccuracy: Double?,
    val fusedHorizontalAccuracy: Double?,
    val vAcc: Double?,
    val alt: Double?,
    val msl: Double?,
    /** null means Android did not supply a speed measurement. */
    val speed: Double?,
    /** direction of travel in degrees from true north (0..360); null when Android reports none */
    val bearing: Double? = null,
    /** accuracy of the bearing in degrees; null when not reported */
    val bearingAcc: Double? = null,
    /** jitter: distance in meters from the previous fix of the same provider (null for the first fix) */
    val jitter: Double? = null,
    /** drift: distance in meters from the average position of the last fixes of the same provider */
    val drift: Double? = null,
    /** MSL source: native Android Location data or GNSS/NMEA GGA data. */
    val mslSource: String? = null,
    /** GNSS fix timestamp supplied by Android with the NMEA callback, Unix epoch milliseconds. */
    val nmeaFixTimestampMs: Long? = null,
    /** Absolute difference between GPS Location.time and the NMEA GNSS fix timestamp. */
    val nmeaAgeMs: Long? = null
) {
    /** Returns only the horizontal accuracy recorded by this row's own provider. */
    fun providerHorizontalAccuracy(): Double? = when (provider) {
        "gps" -> gpsHorizontalAccuracy
        "network" -> networkHorizontalAccuracy
        "fused" -> fusedHorizontalAccuracy
        else -> null
    }
}
