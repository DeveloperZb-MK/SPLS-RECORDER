package io.github.jqssun.gpssetter.spls

import android.content.Context

/** Rules about the saved areas (circles): which area a position is in, and whether two areas overlap. */
object SplsAreas {

    /** Number of fixes in a row that must be inside another saved area before the recording moves to it. */
    const val ENTER_FIXES = 2
    /** Consecutive clearly-outside fixes required before an area session is ended. */
    const val EXIT_FIXES = 2

    /** The saved area that contains the position (distance to its centre is not more than its radius), or null. */
    fun containing(list: List<SplsLocation>, lat: Double, lon: Double, accuracy: Double = 0.0): SplsLocation? {
        var best: SplsLocation? = null
        var bestRatio = Double.MAX_VALUE
        for (l in list) {
            if (!l.isCircle || l.completed) continue
            val d = SplsGeo.distanceM(lat, lon, l.lat, l.lon)
            if (d <= l.radius + accuracy.coerceAtLeast(0.0)) {
                val ratio = d / l.radius
                if (ratio < bestRatio) { best = l; bestRatio = ratio }
            }
        }
        return best
    }

    /** True when two circles overlap. Circles that only touch do not overlap. */
    fun overlaps(distanceBetweenCentersM: Double, r1: Double, r2: Double): Boolean =
        distanceBetweenCentersM < r1 + r2

    /** The saved area that a new or changed area would overlap, or null. [excludeId] is the area being changed. */
    fun findOverlap(ctx: Context, lat: Double, lon: Double, radius: Double, excludeId: String?): SplsLocation? {
        for (l in SplsStore.locations(ctx)) {
            if (!l.isCircle || l.id == excludeId) continue
            if (overlaps(SplsGeo.distanceM(lat, lon, l.lat, l.lon), radius, l.radius)) return l
        }
        return null
    }

    /** The warning text for an overlap, or null when the area is free. */
    fun overlapMessage(ctx: Context, lat: Double, lon: Double, radius: Double, excludeId: String?): String? {
        val o = findOverlap(ctx, lat, lon, radius, excludeId) ?: return null
        return "This area overlaps with \"${o.name}\". Choose a different place or a smaller radius."
    }
}
