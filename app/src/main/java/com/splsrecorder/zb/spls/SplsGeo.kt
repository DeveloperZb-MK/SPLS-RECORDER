package com.splsrecorder.zb.spls

import android.location.Location
import kotlin.math.cos
import kotlin.math.sin

object SplsGeo {

    private const val EARTH = 6378137.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0].toDouble()
    }

    /** Points of a circle with the given radius in meters around a position (for drawing it on the map). */
    fun circle(lat: Double, lon: Double, radiusM: Double, steps: Int = 72): List<Pair<Double, Double>> {
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val out = ArrayList<Pair<Double, Double>>(steps)
        for (k in 0 until steps) {
            val a = 2.0 * Math.PI * k / steps
            out.add(
                Pair(
                    lat + Math.toDegrees(radiusM * sin(a) / EARTH),
                    lon + Math.toDegrees(radiusM * cos(a) / (EARTH * cosLat))
                )
            )
        }
        return out
    }
}
