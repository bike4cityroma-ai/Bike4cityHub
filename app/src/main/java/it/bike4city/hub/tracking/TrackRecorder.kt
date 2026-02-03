package it.bike4city.hub.tracking

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import it.bike4city.hub.maps.signals.MapSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.math.*

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
    private var variance = -1.0 // -1 indica che il filtro deve essere inizializzato

    fun startNew(startedAt: Long) {
        _state.value = State(
            phase = Phase.RECORDING,
            startedAt = startedAt
        )
        variance = -1.0
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
    }

    /**
     * Aggiunge un segnale (POI o Criticità) alla posizione corrente.
     */
    fun addSignal(kind: String, category: String, title: String, description: String = "") {
        val s = _state.value
        if (!s.isRecording && !s.isPaused) return

        val lastPoint = s.points.lastOrNull() ?: return
        
        val newSignal = MapSignal(
            id = UUID.randomUUID().toString(),
            kind = kind,
            category = category,
            lat = lastPoint.latitude,
            lng = lastPoint.longitude,
            title = title,
            description = description,
            createdAt = System.currentTimeMillis()
        )

        _state.value = s.copy(signals = s.signals + newSignal)
    }

    /**
     * Filtro di Kalman semplificato per stabilizzare le coordinate GPS.
     */
    private fun kalmanFilter(lat: Double, lng: Double, accuracy: Float): LatLng {
        if (variance < 0) {
            lastLat = lat
            lastLng = lng
            variance = (accuracy * accuracy).toDouble()
            return LatLng(lat, lng)
        }

        val processNoise = 0.125 // Rumore del processo (costante di velocità)
        variance += processNoise
        val k = variance / (variance + (accuracy * accuracy))
        
        lastLat += k * (lat - lastLat)
        lastLng += k * (lng - lastLng)
        variance *= (1 - k)
        
        return LatLng(lastLat, lastLng)
    }

    fun appendPointWithExtra(loc: Location) {
        val s = _state.value
        if (s.phase != Phase.RECORDING) return

        if (s.skipNextPoint) {
            _state.value = s.copy(skipNextPoint = false)
            return
        }

        // Applichiamo il filtro di Kalman
        val filteredPoint = kalmanFilter(loc.latitude, loc.longitude, loc.accuracy.coerceAtLeast(5f))

        val oldPoints = s.points
        if (oldPoints.isNotEmpty()) {
            val lastPoint = oldPoints.last()
            val dist = distanceMeters(lastPoint, filteredPoint)

            // Filtro "anti-teletrasporto" e "anti-vibrazione":
            // Ignoriamo movimenti < 2m (fermo al semaforo) o > 250m (errore macroscopico GPS)
            if (dist < 2.0 || dist > 250.0) return

            val merged = oldPoints + filteredPoint
            _state.value = s.copy(
                points = merged,
                distanceMeters = s.distanceMeters + dist
            )
        } else {
            _state.value = s.copy(points = listOf(filteredPoint))
        }
    }

    private fun distanceMeters(p1: LatLng, p2: LatLng): Double {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, res)
        return res[0].toDouble()
    }
}
