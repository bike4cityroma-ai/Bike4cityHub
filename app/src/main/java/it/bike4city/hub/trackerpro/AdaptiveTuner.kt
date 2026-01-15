package it.bike4city.hub.trackerpro

internal class AdaptiveTuner(private val base: ProTrackerConfig) {

    data class LiveThresholds(
        val minDistanceM: Double,
        val minBearingDeg: Float,
        val maxAccuracyM: Float,
        val mode: RecordingProfile
    )

    private var stopCountWindow = 0
    private var samplesWindow = 0
    private var lastSpeed: Float? = null
    private var avgAccuracy: Float = base.maxAccuracyMeters
    private var accSamples = 0

    fun onSample(speedMps: Float?, accuracyM: Float?) {
        samplesWindow++
        if (accuracyM != null && accuracyM.isFinite()) {
            accSamples++
            avgAccuracy = (avgAccuracy * (accSamples - 1) + accuracyM) / accSamples
        }

        if (speedMps != null) {
            val prev = lastSpeed
            lastSpeed = speedMps
            if (prev != null && prev > 1.2f && speedMps < 0.5f) stopCountWindow++
        }

        if (samplesWindow >= 45) {
            samplesWindow = (samplesWindow * 0.6).toInt()
            stopCountWindow = (stopCountWindow * 0.6).toInt()
        }
    }

    fun thresholds(): LiveThresholds {
        val p = base.profile
        if (p != RecordingProfile.MIXED_ADAPTIVE) {
            return when (p) {
                RecordingProfile.CITY -> LiveThresholds(3.5, 9f, 30f, RecordingProfile.CITY)
                RecordingProfile.TRAIL -> LiveThresholds(7.0, 14f, 45f, RecordingProfile.TRAIL)
                RecordingProfile.MIXED_ADAPTIVE -> LiveThresholds(base.minDistanceMeters, base.minBearingDeltaDeg, base.maxAccuracyMeters, RecordingProfile.MIXED_ADAPTIVE)
            }
        }

        val looksCity = stopCountWindow >= 3 && (avgAccuracy <= 35f)
        val looksTrail = avgAccuracy >= 28f && stopCountWindow <= 1

        val mode =
            when {
                looksCity -> RecordingProfile.CITY
                looksTrail -> RecordingProfile.TRAIL
                else -> RecordingProfile.MIXED_ADAPTIVE
            }

        return when (mode) {
            RecordingProfile.CITY -> LiveThresholds(3.5, 9f, 30f, RecordingProfile.CITY)
            RecordingProfile.TRAIL -> LiveThresholds(7.0, 14f, 45f, RecordingProfile.TRAIL)
            RecordingProfile.MIXED_ADAPTIVE -> LiveThresholds(base.minDistanceMeters, base.minBearingDeltaDeg, base.maxAccuracyMeters, RecordingProfile.MIXED_ADAPTIVE)
        }
    }
}
