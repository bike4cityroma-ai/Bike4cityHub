package it.bike4city.hub.ui.route

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import it.bike4city.hub.MainActivity
import it.bike4city.hub.R
import it.bike4city.hub.data.FirebaseRepo
import it.bike4city.hub.data.Route
import it.bike4city.hub.gpx.GpxParser
import it.bike4city.hub.location.LocationUpdates
import it.bike4city.hub.maps.ThunderforestMapLibre
import it.bike4city.hub.maps.signals.MapSignal
import it.bike4city.hub.navigation.NavigationUpdate
import it.bike4city.hub.navigation.TrackNavigationEngine
import it.bike4city.hub.navigation.TtsCoach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Stato globale per la navigazione (Segui traccia).
 */
object NavigationState {
    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing

    private val _currentUpdate = MutableStateFlow<NavigationUpdate?>(null)
    val currentUpdate: StateFlow<NavigationUpdate?> = _currentUpdate

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    fun startFollowing() { _isFollowing.value = true }
    fun stopFollowing() { 
        _isFollowing.value = false 
        _currentUpdate.value = null
    }
    fun update(up: NavigationUpdate) { _currentUpdate.value = up }
    fun setMuted(muted: Boolean) { _isMuted.value = muted }
}

/**
 * Service per gestire la navigazione in background (Segui traccia).
 */
class NavigationService : Service() {
    companion object {
        private const val CHANNEL_ID = "bike4city_navigation"
        private const val NOTIF_ID = 1002
        private const val ACTION_STOP = "it.bike4city.hub.action.STOP_NAVIGATION"

        fun start(ctx: Context, routeId: String) {
            val i = Intent(ctx, NavigationService::class.java).apply {
                putExtra("routeId", routeId)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, NavigationService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private var engine: TrackNavigationEngine? = null
    private var tts: TtsCoach? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }

        val routeId = intent?.getStringExtra("routeId") ?: return START_NOT_STICKY
        
        serviceScope.launch {
            val route = FirebaseRepo.loadRoute(routeId)
            if (route != null) {
                val points = runCatching { 
                    GpxParser.parse(route.gpxText).points.map { LatLng(it.latitude, it.longitude) }
                }.getOrElse { emptyList() }
                
                if (points.size >= 2) {
                    engine = TrackNavigationEngine(points)
                    tts = TtsCoach(this@NavigationService)
                    startForeground(NOTIF_ID, buildNotification("Inizializzazione..."))
                    startNavigationLoop()
                } else {
                    stopSelf()
                }
            } else {
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startNavigationLoop() {
        NavigationState.startFollowing()
        serviceScope.launch {
            LocationUpdates.flow(this@NavigationService).collect { pos ->
                if (!NavigationState.isFollowing.value) {
                    stopNavigation()
                    return@collect
                }
                
                val mapLibreLatLng = LatLng(pos.latitude, pos.longitude)
                val up = engine?.update(mapLibreLatLng) ?: return@collect
                NavigationState.update(up)
                tts?.muted = NavigationState.isMuted.value

                // Notifica UI
                updateNotification(up.nextInstruction, up.distanceToInstructionMeters.toInt())

                // TTS
                if (engine?.shouldSpeakManeuver(up) == true) {
                    val d = up.distanceToInstructionMeters.toInt().coerceAtLeast(1)
                    tts?.speak("Tra $d metri, ${up.nextInstruction}")
                    engine?.markManeuverSpoken()
                }

                val now = System.currentTimeMillis()
                if (engine?.shouldSpeakOffRoute(now, up) == true) {
                    val d = up.distanceToRouteMeters.toInt().coerceAtLeast(1)
                    tts?.speak("Sei fuori traccia di circa $d metri.")
                }
            }
        }
    }

    private fun stopNavigation() {
        NavigationState.stopFollowing()
        tts?.shutdown()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun buildNotification(text: String): android.app.Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP), 
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bike4City Navigazione")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "Ferma", stopIntent)
            .build()
    }

    private fun updateNotification(instr: String, dist: Int) {
        val text = if (instr.isNotBlank()) "Tra $dist m: $instr" else "Segui il percorso"
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "Navigazione", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
    }

    override fun onBind(p0: Intent?): IBinder? = null
}

@Composable
fun ViewRouteScreen(routeId: String) {
    var route by remember { mutableStateOf<Route?>(null) }
    var routeSignals by remember { mutableStateOf<List<MapSignal>>(emptyList()) }
    val ctx = LocalContext.current
    val currentUid = FirebaseRepo.currentUser()?.uid

    val following by NavigationState.isFollowing.collectAsState()
    val navUpdate by NavigationState.currentUpdate.collectAsState()
    val muted by NavigationState.isMuted.collectAsState()

    var hasLocation by remember { mutableStateOf(false) }
    val requestPerms = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hasLocation = (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) || (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    DisposableEffect(Unit) {
        requestPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        onDispose { }
    }

    LaunchedEffect(routeId) {
        route = FirebaseRepo.loadRoute(routeId)
        // ✅ Carichiamo i segnali della traccia
        FirebaseRepo.observeRouteSignals(routeId).collect { list ->
            // Se sono il proprietario vedo tutto, altrimenti solo gli active
            routeSignals = if (route?.ownerUid == currentUid) {
                list.map { it.copy(status = "active") } // li forziamo active localmente per la mappa
            } else {
                list.filter { it.status == "active" }
            }
        }
    }

    if (route == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val mapLibrePoints = remember(route!!.gpxText) {
        runCatching { 
            GpxParser.parse(route!!.gpxText).points.map { LatLng(it.latitude, it.longitude) }
        }.getOrElse { emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (mapLibrePoints.isNotEmpty()) {
                val boundsBuilder = LatLngBounds.Builder()
                mapLibrePoints.forEach { boundsBuilder.include(it) }
                
                val start = mapLibrePoints.firstOrNull()
                val finish = mapLibrePoints.lastOrNull()
                
                val progressPoint = navUpdate?.let { up ->
                    mapLibrePoints.getOrNull(up.progressIndex)
                }

                ThunderforestMapLibre(
                    modifier = Modifier.fillMaxSize(),
                    points = mapLibrePoints,
                    signals = routeSignals, // ✅ Passiamo i segnali della traccia
                    startPoint = start,
                    finishPoint = finish,
                    progressPoint = progressPoint,
                    initialBounds = boundsBuilder.build(),
                    showMyLocation = hasLocation,
                    followMyLocation = following
                )
            }

            if (following && navUpdate != null) {
                val up = navUpdate!!
                NavigationCard(
                    instr = up.nextInstruction,
                    dist = up.distanceToInstructionMeters.roundToInt(),
                    onRoute = up.onRoute,
                    muted = muted,
                    onMuteClick = { NavigationState.setMuted(!muted) },
                    onStopClick = { NavigationService.stop(ctx) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        route!!.title.ifBlank { "Percorso" }, 
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold
                    )
                    if (route!!.b4cCategory != null) {
                        CategoryBadge(route!!.b4cCategory!!)
                    }
                }
                
                val km = ((route!!.distanceKm ?: 0.0) * 10).roundToInt() / 10.0
                val remainingKm = if (following && navUpdate != null) {
                    (navUpdate!!.remainingDistanceMeters / 1000.0 * 10).roundToInt() / 10.0
                } else null

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Distanza Totale", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$km km", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    if (remainingKm != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Mancano", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("$remainingKm km", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Difficoltà", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prettyDifficulty(route!!.difficulty), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (route!!.b4cCategory == "BIKE4CITY") {
                    Text("Percorso ufficiale Bike4City APS", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (hasLocation) NavigationService.start(ctx, routeId) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        enabled = hasLocation && mapLibrePoints.size >= 2 && !following
                    ) {
                        Text("AVVIA NAVIGAZIONE")
                    }
                    if (following) {
                        OutlinedButton(
                            onClick = { NavigationService.stop(ctx) },
                            modifier = Modifier.weight(0.4f).height(48.dp)
                        ) {
                            Text("ESCI")
                        }
                    }
                }

                if (!hasLocation) {
                    Text(
                        "Attiva la localizzazione per seguire il percorso.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun NavigationCard(
    instr: String,
    dist: Int,
    onRoute: Boolean,
    muted: Boolean,
    onMuteClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (onRoute) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (onRoute) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (onRoute) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                if (onRoute) {
                    Text(
                        text = if (dist > 0) "Tra $dist metri" else "Ora",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = instr.ifBlank { "Segui la traccia" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "FUORI PERCORSO",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Rientra sulla traccia evidenziata",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    onClick = onMuteClick,
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                        contentDescription = "Muto",
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onStopClick,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "STOP",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryBadge(category: String) {
    val (color, icon, label) = when (category.uppercase()) {
        "BIKE4CITY" -> Triple(Color(0xFF2E7D32), Icons.Default.Verified, "Ufficiale")
        "COMMUNITY" -> Triple(Color(0xFF0288D1), Icons.Default.Group, "Community")
        else -> Triple(Color.Gray, Icons.Outlined.Route, category)
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

fun prettyDifficulty(raw: String?): String {
    val v = raw?.trim()?.lowercase(Locale.ITALY).orEmpty()
    return when {
        v.isBlank() -> "—"
        v in setOf("easy", "facile") -> "Facile"
        v in setOf("medium", "media", "medio") -> "Medio"
        v in setOf("hard", "difficile") -> "Difficile"
        else -> raw!!.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALY) else it.toString() }
    }
}

fun prettyMeters(meters: Double?): String {
    if (meters == null) return "—"
    val m = meters.toInt()
    return "${m} m"
}

@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
