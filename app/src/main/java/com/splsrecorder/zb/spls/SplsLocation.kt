package com.splsrecorder.zb.spls

/** One named SPLS record (a place where SPLS records) with its own CSV file. */
data class SplsLocation(
    val id: String,
    val name: String,
    val mode: String,
    val lat: Double,
    val lon: Double,
    /** meters; only used when [mode] is [MODE_CIRCLE] */
    val radius: Double,
    /** kept for the stored file format; not used any more */
    val completed: Boolean,
    val createdAt: Long
) {
    val isCircle: Boolean get() = mode == MODE_CIRCLE

    companion object {
        const val MODE_CONTINUOUS = "continuous"
        const val MODE_CIRCLE = "circle"
        const val MIN_RADIUS = 10.0
        const val MAX_RADIUS = 10000.0
    }
}
