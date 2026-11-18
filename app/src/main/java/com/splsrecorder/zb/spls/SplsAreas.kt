package com.splsrecorder.zb.spls

import android.content.Context

/** Rules for saved SPLS circles, including the no-overlap constraint. */
object SplsAreas {

    /** True when two circles overlap. Circles that only touch at their boundaries are allowed. */
    fun overlaps(distanceBetweenCentersM: Double, r1: Double, r2: Double): Boolean =
        distanceBetweenCentersM < r1 + r2

    /** Finds an existing circle overlapped by a proposed circle. [excludeId] is used when editing. */
    fun findOverlap(ctx: Context, lat: Double, lon: Double, radius: Double, excludeId: String? = null): SplsLocation? {
        for (location in SplsStore.locations(ctx)) {
            if (!location.isCircle || location.id == excludeId) continue
            val distance = SplsGeo.distanceM(lat, lon, location.lat, location.lon)
            if (overlaps(distance, radius, location.radius)) return location
        }
        return null
    }

    /** Returns the user-facing warning, or null when the proposed circle does not overlap. */
    fun overlapMessage(ctx: Context, lat: Double, lon: Double, radius: Double, excludeId: String? = null): String? {
        val overlap = findOverlap(ctx, lat, lon, radius, excludeId) ?: return null
        return "This area overlaps with \"${overlap.name}\". Choose a different place or a smaller radius."
    }
}
