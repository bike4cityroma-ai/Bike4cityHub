package it.bike4city.hub.trackerpro

import kotlin.math.*

internal object Geo {
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun bearingDeltaDeg(b1: Float, b2: Float): Float {
        val d = (b2 - b1 + 540f) % 360f - 180f
        return abs(d)
    }
}
