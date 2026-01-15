package it.bike4city.hub.trackerpro

enum class RecordingProfile {
    CITY, TRAIL, MIXED_ADAPTIVE
}

data class AutoPauseConfig(
    val enabled: Boolean = true,
    val cityStillSeconds: Int = 30,
    val trailStillSeconds: Int = 55,
    val stillSpeedMps: Double = 0.6,
    val resumeSpeedMps: Double = 1.0,
    val resumeDistanceM: Double = 9.0
)

data class SmoothingConfig(
    val enabled: Boolean = true,
    val alphaGood: Double = 0.15,
    val alphaMedium: Double = 0.35,
    val alphaBad: Double = 0.55,
    val goodAccM: Double = 12.0,
    val badAccM: Double = 25.0
)

data class StreamingConfig(
    val enabled: Boolean = true,
    val directoryName: String = "pro_tracker_stream"
)
