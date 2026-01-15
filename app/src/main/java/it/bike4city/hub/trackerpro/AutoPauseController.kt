package it.bike4city.hub.trackerpro

internal class AutoPauseController(private val cfg: AutoPauseConfig) {

    private var lastMovingTimeMs: Long = 0L
    private var lastPosLat: Double? = null
    private var lastPosLon: Double? = null
    private var autoPaused = false

    fun reset(nowMs: Long) {
        lastMovingTimeMs = nowMs
        lastPosLat = null
        lastPosLon = null
        autoPaused = false
    }

    fun update(
        nowMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float?,
        mode: RecordingProfile
    ): Boolean {
        if (!cfg.enabled) {
            autoPaused = false
            return false
        }

        val still = (speedMps == null) || (speedMps.toDouble() < cfg.stillSpeedMps)

        val movedByDistance =
            if (lastPosLat != null && lastPosLon != null) {
                Geo.haversineMeters(lastPosLat!!, lastPosLon!!, lat, lon) > cfg.resumeDistanceM
            } else false

        lastPosLat = lat
        lastPosLon = lon

        if (!still) {
            lastMovingTimeMs = nowMs
            autoPaused = false
            return false
        }

        val stillSeconds = if (mode == RecordingProfile.CITY) cfg.cityStillSeconds else cfg.trailStillSeconds
        val elapsedStill = (nowMs - lastMovingTimeMs) / 1000L

        val shouldResume = movedByDistance || (speedMps != null && speedMps.toDouble() > cfg.resumeSpeedMps)
        if (shouldResume) {
            lastMovingTimeMs = nowMs
            autoPaused = false
            return false
        }

        autoPaused = elapsedStill >= stillSeconds
        return autoPaused
    }

    fun isAutoPaused(): Boolean = autoPaused
}
