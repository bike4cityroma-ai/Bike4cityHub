package it.bike4city.hub.trackerpro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.*
import it.bike4city.hub.maps.signals.MapSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import org.maplibre.android.geometry.LatLng

class ProTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fused: FusedLocationProviderClient

    private var cfg: ProTrackerConfig = ProTrackerConfig()
    private var lastRaw: Location? = null
    private var lastSavedLat: Double? = null
    private var lastSavedLon: Double? = null
    private var lastSavedBearing: Float? = null

    private var tuner: AdaptiveTuner? = null
    private var autoPauseController: AutoPauseController? = null
    private var smoother: Smoother? = null
    private var streamWriter: GpxStreamWriter? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                cfg = readConfig(intent) ?: cfg
                NotificationFactory.ensureChannel(this, cfg)
                startForeground(NOTIF_ID, NotificationFactory.build(this, cfg))

                tuner = AdaptiveTuner(cfg)
                autoPauseController = AutoPauseController(cfg.autoPause)
                smoother = Smoother(cfg.smoothing)

                startNewTrack(intent.getStringExtra(EXTRA_TRACK_NAME))
                startLocationUpdates()
                updateState { it.copy(isRecording = true, isPaused = false, isAutoPaused = false, lastMessage = "Started") }
            }

            ACTION_PAUSE -> {
                stopLocationUpdates()
                updateState { it.copy(isPaused = true, lastMessage = "Paused") }
            }

            ACTION_RESUME -> {
                startLocationUpdates()
                updateState { it.copy(isPaused = false, lastMessage = "Resumed") }
            }

            ACTION_STOP -> {
                stopLocationUpdates()
                scope.launch {
                    streamWriter?.finish()
                    streamWriter = null
                    updateState { it.copy(isRecording = false, isPaused = false, isAutoPaused = false, lastMessage = "Stopped") }
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }

            ACTION_CLEAR -> {
                // Non cancelliamo il file: puliamo solo lo stato (utile dopo Salva/Scarta)
                updateState {
                    it.copy(
                        isRecording = false,
                        isPaused = false,
                        isAutoPaused = false,
                        pointsCount = 0,
                        lastAccuracyM = null,
                        lastSpeedMps = null,
                        lastProvider = null,
                        activeMode = cfg.profile.name,
                        // streamGpxPath lo teniamo: può essere un backup utile
                        lastMessage = "Cleared"
                    )
                }
            }
        }
        return START_STICKY
    }

    private fun startNewTrack(name: String?) {
        lastRaw = null
        lastSavedLat = null
        lastSavedLon = null
        lastSavedBearing = null
        smoother?.reset()

        // ✅ Reset UI buffers
        ProTrackingService._points.value = emptyList()
        ProTrackingService._signals.value = emptyList()
        ProTrackingService.lastRawLocation = null

        val trackName = name ?: "Track ${System.currentTimeMillis()}"

        val streamFilePath = if (cfg.streaming.enabled) {
            val dir = File(filesDir, cfg.streaming.directoryName)
            val file = File(dir, "track_${System.currentTimeMillis()}.gpx")
            val w = GpxStreamWriter()
            w.start(file, trackName)
            streamWriter = w
            file.absolutePath
        } else null

        autoPauseController?.reset(System.currentTimeMillis())

        updateState {
            it.copy(
                pointsCount = 0,
                streamGpxPath = streamFilePath,
                activeMode = cfg.profile.name,
                lastMessage = "Track ready"
            )
        }
    }

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, cfg.updateIntervalMs)
            .setMinUpdateIntervalMillis(cfg.updateIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .build()

        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            updateState { it.copy(lastMessage = "Missing location permission") }
        }
    }

    private fun stopLocationUpdates() { fused.removeLocationUpdates(callback) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            scope.launch { handleLocation(loc) }
        }
    }

    private suspend fun handleLocation(loc: Location) {
        tuner?.onSample(if (loc.hasSpeed()) loc.speed else null, loc.accuracy)
        val live = tuner?.thresholds()
        val mode = live?.mode ?: cfg.profile
        val minDist = live?.minDistanceM ?: cfg.minDistanceMeters
        val minBear = live?.minBearingDeg ?: cfg.minBearingDeltaDeg
        val maxAcc = live?.maxAccuracyM ?: cfg.maxAccuracyMeters

        updateState {
            it.copy(
                lastAccuracyM = loc.accuracy,
                lastSpeedMps = if (loc.hasSpeed()) loc.speed else null,
                lastProvider = loc.provider,
                activeMode = mode.name
            )
        }

        if (!loc.accuracy.isFinite() || loc.accuracy > maxAcc) return
        if (LocationFilters.isJump(lastRaw, loc, cfg.maxReasonableSpeedMps, maxAcc)) { lastRaw = loc; return }

        val (sLat, sLon) = smoother?.smooth(loc.latitude, loc.longitude, loc.accuracy) ?: (loc.latitude to loc.longitude)

        val autoPaused = autoPauseController?.update(
            nowMs = System.currentTimeMillis(),
            lat = sLat,
            lon = sLon,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            mode = mode
        ) ?: false

        updateState { it.copy(isAutoPaused = autoPaused) }
        if (autoPaused) { lastRaw = loc; return }

        val shouldSave = LocationFilters.shouldSave(
            prevSavedLat = lastSavedLat,
            prevSavedLon = lastSavedLon,
            prevBearing = lastSavedBearing,
            cur = loc,
            minDist = minDist,
            minBearingDeltaDeg = minBear
        )
        if (!shouldSave) { lastRaw = loc; return }

        streamWriter?.appendPoint(
            lat = sLat,
            lon = sLon,
            timeMillis = loc.time,
            ele = if (loc.hasAltitude()) loc.altitude else null,
            accuracy = loc.accuracy,
            speed = if (loc.hasSpeed()) loc.speed else null,
            bearing = if (loc.hasBearing()) loc.bearing else null
        )

        updateState { it.copy(pointsCount = it.pointsCount + 1) }

        // ✅ Manteniamo anche una lista punti in memoria per visualizzazione live.
        // Nota: per tracce lunghissime potresti voler inviare update ogni N punti.
        ProTrackingService.appendPointForUi(sLat, sLon)

        lastSavedLat = sLat
        lastSavedLon = sLon
        lastSavedBearing = if (loc.hasBearing()) loc.bearing else null
        lastRaw = loc
        lastRawLocation = loc
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    private fun updateState(block: (ProTrackerState) -> ProTrackerState) { _state.value = block(_state.value) }

    companion object {
        const val NOTIF_ID = 4042

        const val ACTION_START = "it.bike4city.hub.trackerpro.action.START"
        const val ACTION_PAUSE = "it.bike4city.hub.trackerpro.action.PAUSE"
        const val ACTION_RESUME = "it.bike4city.hub.trackerpro.action.RESUME"
        const val ACTION_STOP = "it.bike4city.hub.trackerpro.action.STOP"
        const val ACTION_CLEAR = "it.bike4city.hub.trackerpro.action.CLEAR"

        const val EXTRA_TRACK_NAME = "extra_track_name"
        private const val EXTRA_CFG = "extra_cfg"

        internal val _state = MutableStateFlow(ProTrackerState(activeMode = RecordingProfile.MIXED_ADAPTIVE.name))
        val state: StateFlow<ProTrackerState> = _state

        // ✅ Punti per la UI (breadcrumb)
        private val _points = MutableStateFlow<List<LatLng>>(emptyList())
        val points: StateFlow<List<LatLng>> = _points

        // ✅ Segnalazioni raccolte durante la registrazione
        private val _signals = MutableStateFlow<List<MapSignal>>(emptyList())
        val signals: StateFlow<List<MapSignal>> = _signals

        // Ultima posizione grezza (per agganciare segnalazioni al punto reale)
        @Volatile internal var lastRawLocation: Location? = null

        /** Helper "anti-errori" per avviare/pausare/riprendere/fermare dal codice UI. */
        fun start(ctx: Context, trackName: String? = null, cfg: ProTrackerConfig? = null) {
            val i = Intent(ctx, ProTrackingService::class.java).setAction(ACTION_START)
            if (trackName != null) i.putExtra(EXTRA_TRACK_NAME, trackName)
            if (cfg != null) putConfig(i, cfg)
            ctx.startForegroundService(i)
        }

        fun pause(ctx: Context) { ctx.startService(Intent(ctx, ProTrackingService::class.java).setAction(ACTION_PAUSE)) }
        fun resume(ctx: Context) { ctx.startService(Intent(ctx, ProTrackingService::class.java).setAction(ACTION_RESUME)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, ProTrackingService::class.java).setAction(ACTION_STOP)) }

        /** Dopo Salva/Scarta: pulisce stato + punti + segnali. */
        fun clear(ctx: Context) {
            _points.value = emptyList()
            _signals.value = emptyList()
            lastRawLocation = null
            ctx.startService(Intent(ctx, ProTrackingService::class.java).setAction(ACTION_CLEAR))
        }

        /** Aggiunge una segnalazione usando l'ultima posizione disponibile dal tracking. */
        fun addSignal(kind: String, category: String, title: String, description: String = "") {
            val loc = lastRawLocation ?: return
            val s = MapSignal(
                kind = kind,
                category = category,
                lat = loc.latitude,
                lng = loc.longitude,
                title = title,
                description = description,
                status = "pending"
            )
            _signals.value = _signals.value + s
        }

        internal fun appendPointForUi(lat: Double, lon: Double) {
            val cur = _points.value
            // Per sicurezza evitiamo di far crescere all'infinito (MVP: 20k punti max).
            val next = if (cur.size >= 20_000) cur.drop(cur.size - 19_999) + LatLng(lat, lon)
                       else cur + LatLng(lat, lon)
            _points.value = next
        }

        fun putConfig(intent: Intent, cfg: ProTrackerConfig) {
            intent.putExtra(EXTRA_CFG, ConfigParcel.from(cfg).toJson())
        }

        private fun readConfig(intent: Intent): ProTrackerConfig? {
            val json = intent.getStringExtra(EXTRA_CFG) ?: return null
            return ConfigParcel.fromJson(json)?.toConfig()
        }
    }
}

internal data class ConfigParcel(
    val profile: String,
    val notificationTitle: String,
    val notificationText: String,
    val channelId: String,
    val channelName: String,
    val updateIntervalMs: Long,
    val minDistanceMeters: Double,
    val minBearingDeltaDeg: Float,
    val maxAccuracyMeters: Float,
    val maxReasonableSpeedMps: Double,
    val streamingEnabled: Boolean,
    val streamingDir: String,
    val autoPauseEnabled: Boolean,
    val cityStillSeconds: Int,
    val trailStillSeconds: Int,
    val stillSpeedMps: Double,
    val resumeSpeedMps: Double,
    val resumeDistanceM: Double,
    val smoothingEnabled: Boolean,
    val alphaGood: Double,
    val alphaMedium: Double,
    val alphaBad: Double,
    val goodAccM: Double,
    val badAccM: Double
) {
    fun toJson(): String = org.json.JSONObject().apply {
        put("profile", profile)
        put("notificationTitle", notificationTitle)
        put("notificationText", notificationText)
        put("channelId", channelId)
        put("channelName", channelName)
        put("updateIntervalMs", updateIntervalMs)
        put("minDistanceMeters", minDistanceMeters)
        put("minBearingDeltaDeg", minBearingDeltaDeg)
        put("maxAccuracyMeters", maxAccuracyMeters)
        put("maxReasonableSpeedMps", maxReasonableSpeedMps)
        put("streamingEnabled", streamingEnabled)
        put("streamingDir", streamingDir)
        put("autoPauseEnabled", autoPauseEnabled)
        put("cityStillSeconds", cityStillSeconds)
        put("trailStillSeconds", trailStillSeconds)
        put("stillSpeedMps", stillSpeedMps)
        put("resumeSpeedMps", resumeSpeedMps)
        put("resumeDistanceM", resumeDistanceM)
        put("smoothingEnabled", smoothingEnabled)
        put("alphaGood", alphaGood)
        put("alphaMedium", alphaMedium)
        put("alphaBad", alphaBad)
        put("goodAccM", goodAccM)
        put("badAccM", badAccM)
    }.toString()

    fun toConfig(): ProTrackerConfig {
        return ProTrackerConfig(
            profile = RecordingProfile.valueOf(profile),
            notificationTitle = notificationTitle,
            notificationText = notificationText,
            channelId = channelId,
            channelName = channelName,
            updateIntervalMs = updateIntervalMs,
            minDistanceMeters = minDistanceMeters,
            minBearingDeltaDeg = minBearingDeltaDeg,
            maxAccuracyMeters = maxAccuracyMeters,
            maxReasonableSpeedMps = maxReasonableSpeedMps,
            streaming = StreamingConfig(streamingEnabled, streamingDir),
            autoPause = AutoPauseConfig(
                enabled = autoPauseEnabled,
                cityStillSeconds = cityStillSeconds,
                trailStillSeconds = trailStillSeconds,
                stillSpeedMps = stillSpeedMps,
                resumeSpeedMps = resumeSpeedMps,
                resumeDistanceM = resumeDistanceM
            ),
            smoothing = SmoothingConfig(
                enabled = smoothingEnabled,
                alphaGood = alphaGood,
                alphaMedium = alphaMedium,
                alphaBad = alphaBad,
                goodAccM = goodAccM,
                badAccM = badAccM
            )
        )
    }

    companion object {
        fun from(cfg: ProTrackerConfig): ConfigParcel = ConfigParcel(
            profile = cfg.profile.name,
            notificationTitle = cfg.notificationTitle,
            notificationText = cfg.notificationText,
            channelId = cfg.channelId,
            channelName = cfg.channelName,
            updateIntervalMs = cfg.updateIntervalMs,
            minDistanceMeters = cfg.minDistanceMeters,
            minBearingDeltaDeg = cfg.minBearingDeltaDeg,
            maxAccuracyMeters = cfg.maxAccuracyMeters,
            maxReasonableSpeedMps = cfg.maxReasonableSpeedMps,
            streamingEnabled = cfg.streaming.enabled,
            streamingDir = cfg.streaming.directoryName,
            autoPauseEnabled = cfg.autoPause.enabled,
            cityStillSeconds = cfg.autoPause.cityStillSeconds,
            trailStillSeconds = cfg.autoPause.trailStillSeconds,
            stillSpeedMps = cfg.autoPause.stillSpeedMps,
            resumeSpeedMps = cfg.autoPause.resumeSpeedMps,
            resumeDistanceM = cfg.autoPause.resumeDistanceM,
            smoothingEnabled = cfg.smoothing.enabled,
            alphaGood = cfg.smoothing.alphaGood,
            alphaMedium = cfg.smoothing.alphaMedium,
            alphaBad = cfg.smoothing.alphaBad,
            goodAccM = cfg.smoothing.goodAccM,
            badAccM = cfg.smoothing.badAccM
        )

        fun fromJson(json: String): ConfigParcel? = try {
            val o = org.json.JSONObject(json)
            ConfigParcel(
                profile = o.getString("profile"),
                notificationTitle = o.getString("notificationTitle"),
                notificationText = o.getString("notificationText"),
                channelId = o.getString("channelId"),
                channelName = o.getString("channelName"),
                updateIntervalMs = o.getLong("updateIntervalMs"),
                minDistanceMeters = o.getDouble("minDistanceMeters"),
                minBearingDeltaDeg = o.getDouble("minBearingDeltaDeg").toFloat(),
                maxAccuracyMeters = o.getDouble("maxAccuracyMeters").toFloat(),
                maxReasonableSpeedMps = o.getDouble("maxReasonableSpeedMps"),
                streamingEnabled = o.getBoolean("streamingEnabled"),
                streamingDir = o.getString("streamingDir"),
                autoPauseEnabled = o.getBoolean("autoPauseEnabled"),
                cityStillSeconds = o.getInt("cityStillSeconds"),
                trailStillSeconds = o.getInt("trailStillSeconds"),
                stillSpeedMps = o.getDouble("stillSpeedMps"),
                resumeSpeedMps = o.getDouble("resumeSpeedMps"),
                resumeDistanceM = o.getDouble("resumeDistanceM"),
                smoothingEnabled = o.getBoolean("smoothingEnabled"),
                alphaGood = o.getDouble("alphaGood"),
                alphaMedium = o.getDouble("alphaMedium"),
                alphaBad = o.getDouble("alphaBad"),
                goodAccM = o.getDouble("goodAccM"),
                badAccM = o.getDouble("badAccM")
            )
        } catch (_: Throwable) { null }
    }
}
