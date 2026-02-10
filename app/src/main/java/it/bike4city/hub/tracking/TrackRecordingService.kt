package it.bike4city.hub.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import it.bike4city.hub.MainActivity
import it.bike4city.hub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.roundToInt

class TrackRecordingService : Service() {

    companion object {
        private const val TAG = "TrackRecordingService"
        private const val CHANNEL_ID = "bike4city_recording"
        private const val NOTIF_ID = 1001

        private const val ACTION_START = "it.bike4city.hub.action.START_RECORDING"
        private const val ACTION_STOP = "it.bike4city.hub.action.STOP_RECORDING"
        private const val ACTION_PAUSE = "it.bike4city.hub.action.PAUSE_RECORDING"
        private const val ACTION_RESUME = "it.bike4city.hub.action.RESUME_RECORDING"

        fun start(ctx: Context) {
            Log.d(TAG, "Avvio richiesto")
            val i = Intent(ctx, TrackRecordingService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                Log.e(TAG, "Errore startService: ${e.message}")
                Toast.makeText(ctx, "Impossibile avviare registrazione: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creato")
        fused = LocationServices.getFusedLocationProviderClient(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (callback != null) {
            Log.d(TAG, "Registrazione già attiva, ignoro")
            return
        }

        // Forza lo stato di registrazione nel recorder
        TrackRecorder.startNew(System.currentTimeMillis())

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Log.d(TAG, "startForeground OK")
        } catch (e: Exception) {
            Log.e(TAG, "Errore startForeground: ${e.message}")
            // Se fallisce il foreground, dobbiamo fermare il servizio per evitare crash del sistema
            stopSelf()
            return
        }

        startLocationUpdatesAgain()
        
        // Monitora lo stato per aggiornare la distanza nella notifica
        TrackRecorder.state
            .onEach { updateNotification() }
            .launchIn(serviceScope)
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
        Log.d(TAG, "Fermo registrazione")
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
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val best = result.locations
                    .filter { it.accuracy > 0f }
                    .minByOrNull { it.accuracy }
                    ?: return

                if (best.accuracy <= 65f) {
                    TrackRecorder.appendPointSmart(best)
                }
            }
        }

        callback = cb
        try {
            fused.requestLocationUpdates(req, cb, mainLooper)
            Log.d(TAG, "Aggiornamenti GPS avviati")
        } catch (e: SecurityException) {
            Log.e(TAG, "Errore permessi GPS: ${e.message}")
            stopRecording()
        }
    }

    private fun buildNotification(): Notification {
        val s = TrackRecorder.state.value

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

        val km = (s.distanceMeters / 1000.0 * 10).roundToInt() / 10.0
        val status = when {
            s.isRecording -> "In movimento"
            s.isPaused -> "In pausa"
            else -> "Registrazione"
        }
        
        val text = "$status • $km km percorsi"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bike4City Hub")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, pauseOrResumeLabel, pauseResumeIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIF_ID, buildNotification())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Registrazione traccia",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr?.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service distrutto")
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
