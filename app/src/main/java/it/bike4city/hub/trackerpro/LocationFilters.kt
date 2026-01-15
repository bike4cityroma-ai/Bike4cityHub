package it.bike4city.hub.trackerpro

import android.location.Location

internal object LocationFilters {

    fun isJump(prev: Location?, cur: Location, maxReasonableSpeedMps: Double, maxAccM: Float): Boolean {
        if (prev == null) return false
        val dtMs = (cur.time - prev.time).coerceAtLeast(1L)
        val dist = Geo.haversineMeters(prev.latitude, prev.longitude, cur.latitude, cur.longitude)
        val speed = dist / (dtMs / 1000.0)
        return (speed > maxReasonableSpeedMps && cur.accuracy > (maxAccM / 2f))
    }

    fun shouldSave(
        prevSavedLat: Double?,
        prevSavedLon: Double?,
        prevBearing: Float?,
        cur: Location,
        minDist: Double,
        minBearingDeltaDeg: Float
    ): Boolean {
        if (prevSavedLat == null || prevSavedLon == null) return true
        val dist = Geo.haversineMeters(prevSavedLat, prevSavedLon, cur.latitude, cur.longitude)
        if (dist >= minDist) return true

        val b1 = prevBearing
        val b2 = if (cur.hasBearing()) cur.bearing else null
        if (b1 == null || b2 == null) return false
        val delta = Geo.bearingDeltaDeg(b1, b2)
        return delta >= minBearingDeltaDeg
    }
}
