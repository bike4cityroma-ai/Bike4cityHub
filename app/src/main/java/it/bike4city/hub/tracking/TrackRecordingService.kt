package it.bike4city.hub.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import it.bike4city.hub.MainActivity
import it.bike4city.hub.R

class TrackRecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "bike4city_recording"
        private const val NOTIF_ID = 1001

        private const val ACTION_START = "it.bike4city.hub.action.START_RECORDING"
        private const val ACTION_STOP = "it.bike4city.hub.action.STOP_RECORDING"
        private const val ACTION_PAUSE = "it.bike4city.hub.action.PAUSE_RECORDING"
        private const val ACTION_RESUME = "it.bike4city.hub.action.RESUME_RECORDING"

        fun start(ctx: Context) {
            val i = Intent(ctx, TrackRecordingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, TrackRecordingService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }

        fun pause(ctx: Context) {
            val i = Intent(ctx, TrackRecordingService::class.java).setAction(ACTION_PAUSE)
            ctx.startService(i)
        }

        fun resume(ctx: Context) {
            val i = Intent(ctx, TrackRecordingService::class.java).setAction(ACTION_RESUME)
            ctx.startService(i)
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (callback != null) return

        val s = TrackRecorder.state.value
        if (s.isRecording || s.isPaused) return

        val startedAt = System.currentTimeMillis()
        TrackRecorder.startNew(startedAt)

        startForeground(NOTIF_ID, buildNotification())
        startLocationUpdatesAgain()
    }

    private fun pauseRecording() {
        if (!TrackRecorder.state.value.isRecording) return

        TrackRecorder.pause(System.currentTimeMillis())

        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        updateNotification()
    }

    private fun resumeRecording() {
        if (!TrackRecorder.state.value.isPaused) return

        TrackRecorder.resume(System.currentTimeMillis())
        startLocationUpdatesAgain()
        updateNotification()
    }

    private fun stopRecording() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null

        TrackRecorder.stop(System.currentTimeMillis())

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startLocationUpdatesAgain() {
        if (callback != null) return

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { loc ->
                    TrackRecorder.appendPoints(listOf(com.google.android.gms.maps.model.LatLng(loc.latitude, loc.longitude)))
                }
            }
        }

        callback = cb
        try {
            fused.requestLocationUpdates(req, cb, mainLooper)
        } catch (e: SecurityException) {
            stopRecording()
        }
    }

    private fun buildNotification(): Notification {
        val s = TrackRecorder.state.value

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeAction = if (s.isRecording) ACTION_PAUSE else ACTION_RESUME
        val pauseOrResumeLabel = if (s.isRecording) "Pausa" else "Riprendi"

        val pauseResumeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TrackRecordingService::class.java).setAction(pauseOrResumeAction),
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            s.isRecording -> "Registrazione attiva"
            s.isPaused -> "In pausa"
            else -> "Registrazione"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bike4City Hub")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(s.phase == TrackRecorder.Phase.RECORDING || s.phase == TrackRecorder.Phase.PAUSED)
            .addAction(0, pauseOrResumeLabel, pauseResumeIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Registrazione traccia",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(ch)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
