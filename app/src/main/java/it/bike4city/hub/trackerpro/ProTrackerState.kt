package it.bike4city.hub.trackerpro

data class ProTrackerState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isAutoPaused: Boolean = false,

    val pointsCount: Int = 0,

    val lastAccuracyM: Float? = null,
    val lastSpeedMps: Float? = null,
    val lastProvider: String? = null,

    val activeMode: String? = null,
    val streamGpxPath: String? = null,

    val lastMessage: String? = null
)
