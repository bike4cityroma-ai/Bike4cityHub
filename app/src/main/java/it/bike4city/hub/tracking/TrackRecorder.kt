package it.bike4city.hub.tracking

import android.location.Location
import ch.hsr.geohash.GeoHash
import it.bike4city.hub.maps.signals.MapSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.geometry.LatLng
import java.util.UUID

object TrackRecorder {

    enum class Phase { IDLE, RECORDING, PAUSED, STOPPED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val startedAt: Long = 0L,
        val stoppedAt: Long = 0L,
        val points: List<LatLng> = emptyList(),
        val signals: List<MapSignal> = emptyList(),
        val distanceMeters: Double = 0.0,
        val pausedTotalSec: Long = 0L,
        val pausedAt: Long = 0L,
        val skipNextPoint: Boolean = false
    ) {
        val isRecording: Boolean get() = phase == Phase.RECORDING
        val isPaused: Boolean get() = phase == Phase.PAUSED
        val hasStopped: Boolean get() = phase == Phase.STOPPED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // Parametri per il filtro Kalman semplificato
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var lastAlt = 0.0
    private var variance = -1.0

    // --- Timing reale tra punti
    private var lastFixTimeMs: Long = 0L

    // --- Ultimo fix GREZZO (serve per segnalazioni "oneste")
    private var lastRawLat: Double? = null
    private var lastRawLon: Double? = null
    private var lastRawAlt: Double? = null
    private var lastRawTimeMs: Long = 0L
    private var lastRawAccM: Float = 999f

    // --- Ultimo punto SALVATO (per filtri dist/bearing)
    private var lastSavedTimeMs: Long = 0L
    private var lastSavedBearing: Float? = null

    // --- Stop&Go
    private var stillSinceMs: Long = 0L
    private var isStopped: Boolean = false

    fun startNew(startedAt: Long) {
        _state.value = State(
            phase = Phase.RECORDING,
            startedAt = startedAt
        )
        variance = -1.0
        lastFixTimeMs = 0L
        lastRawLat = null
        lastRawLon = null
        lastRawAlt = null
        lastRawTimeMs = 0L
        lastRawAccM = 999f
        lastSavedTimeMs = 0L
        lastSavedBearing = null
        stillSinceMs = 0L
        isStopped = false
    }

    fun pause(now: Long) {
        val s = _state.value
        if (s.phase != Phase.RECORDING) return
        _state.value = s.copy(phase = Phase.PAUSED, pausedAt = now)
    }

    fun resume(now: Long) {
        val s = _state.value
        if (s.phase != Phase.PAUSED) return
        val added = ((now - s.pausedAt) / 1000L).coerceAtLeast(0L)
        _state.value = s.copy(
            phase = Phase.RECORDING,
            pausedTotalSec = s.pausedTotalSec + added,
            pausedAt = 0L,
            skipNextPoint = true
        )
        variance = -1.0
        lastFixTimeMs = 0L
        lastRawLat = null
        lastRawLon = null
        lastRawAlt = null
        lastRawTimeMs = 0L
        lastRawAccM = 999f
        lastSavedTimeMs = 0L
        lastSavedBearing = null
        stillSinceMs = 0L
        isStopped = false
    }

    fun stop(stoppedAt: Long) {
        val s = _state.value
        val extra = if (s.phase == Phase.PAUSED && s.pausedAt > 0L)
            ((stoppedAt - s.pausedAt) / 1000L).coerceAtLeast(0L)
        else 0L

        _state.value = s.copy(
            phase = Phase.STOPPED,
            stoppedAt = stoppedAt,
            pausedTotalSec = s.pausedTotalSec + extra,
            pausedAt = 0L,
            skipNextPoint = false
        )
    }

    fun reset() {
        _state.value = State()
        variance = -1.0
        lastFixTimeMs = 0L
        lastRawLat = null
        lastRawLon = null
        lastRawAlt = null
        lastRawTimeMs = 0L
        lastRawAccM = 999f
        lastSavedTimeMs = 0L
        lastSavedBearing = null
        stillSinceMs = 0L
        isStopped = false
    }

    fun addSignal(kind: String, category: String, title: String, description: String = "") {
        val s = _state.value
        if (!s.isRecording && !s.isPaused) return

        // IMPORTANTISSIMO: le criticità devono restare "grezze".
        // Se abbiamo un ultimo fix GPS (anche se non l'abbiamo salvato nella traccia pulita), usiamo quello.
        val lat = lastRawLat ?: s.points.lastOrNull()?.latitude ?: return
        val lon = lastRawLon ?: s.points.lastOrNull()?.longitude ?: return

        // Generazione Geohash precisione 7 (~150m)
        val geohash7 = GeoHash.withCharacterPrecision(lat, lon, 7).toBase32()

        val newSignal = MapSignal(
            id = UUID.randomUUID().toString(),
            kind = kind,
            category = category,
            lat = lat,
            lng = lon,
            geohash = geohash7,
            title = title,
            description = description,
            createdAt = System.currentTimeMillis()
        )

        _state.value = s.copy(signals = s.signals + newSignal)
    }

    /**
     * Filtro di Kalman potenziato per includere l'altitudine.
     */
    private fun kalmanFilter(lat: Double, lng: Double, alt: Double, accuracy: Float): LatLng {
        if (variance < 0) {
            lastLat = lat
            lastLng = lng
            lastAlt = alt
            variance = (accuracy * accuracy).toDouble()
            return LatLng(lat, lng, alt)
        }

        val processNoise = 0.8 // Valore bilanciato per reattività e stabilità
        variance += processNoise
        val k = variance / (variance + (accuracy * accuracy))

        lastLat += k * (lat - lastLat)
        lastLng += k * (lng - lastLng)
        // L'altitudine GPS è meno precisa, usiamo un coefficiente di smoothing più forte (k/2)
        lastAlt += (k * 0.5) * (alt - lastAlt)
        variance *= (1 - k)

        return LatLng(lastLat, lastLng, lastAlt)
    }

    /**
     * Pipeline "artigiano evoluto":
     * - conserva sempre l'ultimo fix grezzo (per segnalazioni)
     * - scarta punti con accuracy pessima
     * - elimina jump/outlier usando velocità implicita tra fix
     * - stop detector (evita scarabocchi da fermo)
     * - smoothing (Kalman leggero)
     * - salva punti solo se distanza o variazione direzione lo giustifica
     */
    fun appendPointWithExtra(loc: Location) {
        val s = _state.value
        if (s.phase != Phase.RECORDING) return

        if (s.skipNextPoint) {
            _state.value = s.copy(skipNextPoint = false)
            return
        }

        // --- (A) aggiorna sempre il "grezzo" (per criticità e audit)
        lastRawLat = loc.latitude
        lastRawLon = loc.longitude
        lastRawAlt = loc.altitude
        lastRawTimeMs = loc.time
        lastRawAccM = loc.accuracy

        // --- (B) Quality gate: in città l'accuracy fuori scala crea i tagli nei palazzi
        val MAX_ACC_M = 25f
        if (loc.accuracy <= 0f || loc.accuracy > MAX_ACC_M) return

        // --- (C) Outlier/jump: velocità implicita tra fix grezzi
        val prevTime = lastFixTimeMs
        val curTime = loc.time
        if (prevTime > 0L) {
            val dt = ((curTime - prevTime).coerceAtLeast(1L)) / 1000.0
            val prevLat = lastLat.takeIf { variance >= 0 } ?: loc.latitude
            val prevLon = lastLng.takeIf { variance >= 0 } ?: loc.longitude
            val d = distanceMeters(prevLat, prevLon, loc.latitude, loc.longitude)
            val impliedSpeed = d / dt

            // Soglia "ciclista urbano": sopra ~50 km/h è quasi sempre errore, ma evitiamo falsi positivi.
            val MAX_IMPL_SPEED_MPS = 14.0
            if (impliedSpeed > MAX_IMPL_SPEED_MPS && loc.accuracy > 12f) return

            // salti secchi: anche senza speed, se fai 80m in pochi secondi è quasi sicuramente glitch
            if (d > 80.0 && dt < 4.0) return
        }

        lastFixTimeMs = curTime

        // --- (D) Stop detector: evita scarabocchi quando sei fermo
        val STOP_SPEED_MPS = 0.6
        val STOP_SECONDS = 12
        val speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null
        val moving = speedMps == null || speedMps >= STOP_SPEED_MPS

        if (!moving) {
            if (stillSinceMs == 0L) stillSinceMs = curTime
            val stillFor = (curTime - stillSinceMs) / 1000L
            if (stillFor >= STOP_SECONDS) {
                isStopped = true
                return
            }
        } else {
            stillSinceMs = 0L
            isStopped = false
        }

        // --- (E) smoothing (Kalman leggero). Accuracy minima per non "impazzire".
        val filteredPoint = kalmanFilter(loc.latitude, loc.longitude, loc.altitude, loc.accuracy.coerceAtLeast(5f))

        val oldPoints = s.points
        if (oldPoints.isEmpty()) {
            lastSavedTimeMs = curTime
            lastSavedBearing = if (loc.hasBearing()) loc.bearing else null
            _state.value = s.copy(points = listOf(filteredPoint))
            return
        }

        val lastPoint = oldPoints.last()
        val dist = distanceMeters(lastPoint, filteredPoint)

        // --- (F) filtro anti-jitter: micro-movimenti casuali
        val MIN_STEP_M = 4.0
        if (dist < MIN_STEP_M) {
            // però se stai cambiando direzione in modo netto (incrocio) salva comunque
            val prevB = lastSavedBearing
            val curB = if (loc.hasBearing()) loc.bearing else null
            if (prevB == null || curB == null) return
            val delta = bearingDeltaDeg(prevB, curB)
            if (delta < 18f) return
        }

        // --- (G) salva solo se ha senso (distanza o bearing)
        val MIN_DIST_SAVE_M = 7.0
        val MIN_BEARING_DELTA = 18f
        val shouldSave = (dist >= MIN_DIST_SAVE_M) || run {
            val prevB = lastSavedBearing
            val curB = if (loc.hasBearing()) loc.bearing else null
            if (prevB == null || curB == null) false else bearingDeltaDeg(prevB, curB) >= MIN_BEARING_DELTA
        }
        if (!shouldSave) return

        lastSavedTimeMs = curTime
        lastSavedBearing = if (loc.hasBearing()) loc.bearing else lastSavedBearing

        _state.value = s.copy(
            points = oldPoints + filteredPoint,
            distanceMeters = s.distanceMeters + dist
        )
    }

    /**
     * Alias mantenuto per compatibilità con TrackRecordingService.
     * Internamente usa la pipeline attuale.
     */
    fun appendPointSmart(loc: Location) = appendPointWithExtra(loc)

    private fun distanceMeters(p1: LatLng, p2: LatLng): Double {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, res)
        return res[0].toDouble()
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, res)
        return res[0].toDouble()
    }

    private fun bearingDeltaDeg(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d < -180f) d += 360f
        if (d > 180f) d -= 360f
        return kotlin.math.abs(d)
    }
}
