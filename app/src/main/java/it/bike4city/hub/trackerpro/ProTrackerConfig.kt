package it.bike4city.hub.trackerpro

data class ProTrackerConfig(
    val profile: RecordingProfile = RecordingProfile.MIXED_ADAPTIVE,

    val notificationTitle: String = "Registrazione in corso",
    val notificationText: String = "Sto registrando la traccia GPS…",
    val channelId: String = "pro_tracker_channel",
    val channelName: String = "Registrazione traccia",

    val updateIntervalMs: Long = 1000L,

    val minDistanceMeters: Double = 5.0,
    val minBearingDeltaDeg: Float = 12f,
    val maxAccuracyMeters: Float = 35f,
    val maxReasonableSpeedMps: Double = 20.0,

    val streaming: StreamingConfig = StreamingConfig(enabled = true),
    val autoPause: AutoPauseConfig = AutoPauseConfig(enabled = true),
    val smoothing: SmoothingConfig = SmoothingConfig(enabled = true),
)
