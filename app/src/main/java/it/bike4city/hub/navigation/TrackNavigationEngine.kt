package it.bike4city.hub.navigation

import com.google.android.gms.maps.model.LatLng

data class NavigationUpdate(
    val onRoute: Boolean,
    val distanceToRouteMeters: Double,
    val nextInstruction: String,
    val distanceToInstructionMeters: Double,
    val progressIndex: Int
)

/**
 * “Navigator” minimalista per seguire una polilinea.
 *
 * Cosa fa bene:
 * - camera che segue l’utente (già gestita dal MapLibre location component)
 * - prossima manovra “basata su geometria” (no nomi vie)
 * - avvisi vocali (distanza alla manovra, fuori traccia)
 *
 * Cosa NON fa (scelta consapevole):
 * - ricalcolo percorso “stradale” (serve un routing engine)
 */
class TrackNavigationEngine(
    private val points: List<LatLng>,
    private val maneuvers: List<Maneuver> = ManeuverGenerator.generate(points),
    private val offRouteThresholdMeters: Double = 30.0
) {

    private val cumulativeMeters: DoubleArray = buildCumulative(points)

    private var lastNearestIndex: Int = 0
    private var nextManeuverIdx: Int = 0

    // evita ripetizioni in loop quando l'utente balla intorno alla soglia
    private var lastSpokenManeuverIndex: Int = -1
    private var lastOffRouteSpokenAt: Long = 0L

    fun reset() {
        lastNearestIndex = 0
        nextManeuverIdx = 0
        lastSpokenManeuverIndex = -1
        lastOffRouteSpokenAt = 0L
    }

    /**
     * Aggiorna lo stato di navigazione con una nuova posizione.
     */
    fun update(pos: LatLng): NavigationUpdate {
        if (points.isEmpty()) {
            return NavigationUpdate(
                onRoute = false,
                distanceToRouteMeters = Double.POSITIVE_INFINITY,
                nextInstruction = "",
                distanceToInstructionMeters = Double.POSITIVE_INFINITY,
                progressIndex = 0
            )
        }

        val nearest = Geo.nearestPointIndex(pos, points)
        // monotonia soft: evita di “tornare indietro” per un fix rumoroso
        lastNearestIndex = maxOf(lastNearestIndex, nearest)

        val distToRoute = Geo.distanceToPolylineMeters(pos, points)
        val onRoute = distToRoute <= offRouteThresholdMeters

        // avanza manovra se abbiamo superato l'indice
        while (nextManeuverIdx < maneuvers.size && maneuvers[nextManeuverIdx].index <= lastNearestIndex) {
            nextManeuverIdx++
        }

        val (instr, distToInstr) = if (nextManeuverIdx < maneuvers.size) {
            val m = maneuvers[nextManeuverIdx]
            val d = distanceAlongRouteFromIndexTo(lastNearestIndex, m.index)
            m.italianInstruction() to d
        } else {
            "fine percorso" to 0.0
        }

        return NavigationUpdate(
            onRoute = onRoute,
            distanceToRouteMeters = distToRoute,
            nextInstruction = instr,
            distanceToInstructionMeters = distToInstr,
            progressIndex = lastNearestIndex
        )
    }

    fun shouldSpeakManeuver(update: NavigationUpdate, speakDistanceMeters: Double = 60.0): Boolean {
        if (nextManeuverIdx >= maneuvers.size) return false
        val m = maneuvers[nextManeuverIdx]
        if (m.index == lastSpokenManeuverIndex) return false
        return update.distanceToInstructionMeters in 1.0..speakDistanceMeters
    }

    fun markManeuverSpoken() {
        if (nextManeuverIdx < maneuvers.size) {
            lastSpokenManeuverIndex = maneuvers[nextManeuverIdx].index
        }
    }

    fun shouldSpeakOffRoute(nowMs: Long, update: NavigationUpdate, cooldownMs: Long = 20_000L): Boolean {
        if (update.onRoute) return false
        if (nowMs - lastOffRouteSpokenAt < cooldownMs) return false
        lastOffRouteSpokenAt = nowMs
        return true
    }

    private fun distanceAlongRouteFromIndexTo(fromIdx: Int, toIdx: Int): Double {
        val a = fromIdx.coerceIn(0, points.lastIndex)
        val b = toIdx.coerceIn(0, points.lastIndex)
        if (b <= a) return 0.0
        return cumulativeMeters[b] - cumulativeMeters[a]
    }

    private fun buildCumulative(points: List<LatLng>): DoubleArray {
        val out = DoubleArray(points.size)
        var sum = 0.0
        out[0] = 0.0
        for (i in 1 until points.size) {
            sum += Geo.distanceMeters(points[i - 1], points[i])
            out[i] = sum
        }
        return out
    }
}
