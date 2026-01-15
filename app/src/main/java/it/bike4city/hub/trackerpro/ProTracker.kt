package it.bike4city.hub.trackerpro

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

class ProTracker private constructor(
    private val appContext: Context,
    private val config: ProTrackerConfig
) {
    val state: StateFlow<ProTrackerState> = ProTrackingService.state

    fun start(trackName: String? = null) {
        val i = Intent(appContext, ProTrackingService::class.java).apply {
            action = ProTrackingService.ACTION_START
            putExtra(ProTrackingService.EXTRA_TRACK_NAME, trackName)
            ProTrackingService.putConfig(this, config)
        }
        ContextCompat.startForegroundService(appContext, i)
    }

    fun pause() = appContext.startService(Intent(appContext, ProTrackingService::class.java).apply {
        action = ProTrackingService.ACTION_PAUSE
    })

    fun resume() = appContext.startService(Intent(appContext, ProTrackingService::class.java).apply {
        action = ProTrackingService.ACTION_RESUME
    })

    fun stop() = appContext.startService(Intent(appContext, ProTrackingService::class.java).apply {
        action = ProTrackingService.ACTION_STOP
    })

    fun clearSession() = appContext.startService(Intent(appContext, ProTrackingService::class.java).apply {
        action = ProTrackingService.ACTION_CLEAR
    })

    companion object {
        @Volatile private var instance: ProTracker? = null

        fun get(context: Context, config: ProTrackerConfig = ProTrackerConfig()): ProTracker =
            instance ?: synchronized(this) {
                instance ?: ProTracker(context.applicationContext, config).also { instance = it }
            }
    }
}
