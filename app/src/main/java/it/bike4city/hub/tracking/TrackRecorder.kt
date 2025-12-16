package it.bike4city.hub.tracking

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TrackRecorder {

    enum class Phase { IDLE, RECORDING, PAUSED, STOPPED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val startedAt: Long = 0L,
        val stoppedAt: Long = 0L,
        val points: List<LatLng> = emptyList(),
        val distanceMeters: Double = 0.0,
        val pausedTotalSec: Long = 0L,
        val pausedAt: Long = 0L,
        val skipNextPoint: Boolean = false // ✅ miglioria: ignora 1 fix dopo resume
    ) {
        val isRecording: Boolean get() = phase == Phase.RECORDING
        val isPaused: Boolean get() = phase == Phase.PAUSED
        val hasStopped: Boolean get() = phase == Phase.STOPPED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun startNew(startedAt: Long) {
        _state.value = State(
            phase = Phase.RECORDING,
            startedAt = startedAt
        )
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
            skipNextPoint = true // ✅ ignora il primo fix appena riparti
        )
    }

    fun stop(stoppedAt: Long) {
        val s = _state.value

        // se stai stoppando da PAUSED, conteggia anche l’ultima pausa
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
    }

    fun appendPoints(newPoints: List<LatLng>) {
        val s = _state.value
        if (s.phase != Phase.RECORDING) return
        if (newPoints.isEmpty()) return

        // ✅ se ho appena fatto resume, ignoro 1 fix (evita “strappo” iniziale)
        if (s.skipNextPoint) {
            _state.value = s.copy(skipNextPoint = false)
            return
        }

        val old = s.points
        val merged = old + newPoints

        var added = 0.0
        val all = merged
        val startIdx = (all.size - newPoints.size - 1).coerceAtLeast(0)

        for (i in (startIdx + 1) until all.size) {
            val a = all[i - 1]
            val b = all[i]
            val res = FloatArray(1)
            android.location.Location.distanceBetween(
                a.latitude, a.longitude,
                b.latitude, b.longitude,
                res
            )

            // filtro anti-“teletrasporto”: se > 150m in 2s (o salto GPS), non aggiungo distanza
            if (res[0] <= 150f) added += res[0].toDouble()
        }

        _state.value = s.copy(
            points = merged,
            distanceMeters = s.distanceMeters + added
        )
    }
}
