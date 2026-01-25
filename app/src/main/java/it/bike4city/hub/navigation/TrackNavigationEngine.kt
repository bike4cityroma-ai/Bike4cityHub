package it.bike4city.hub.navigation

import org.maplibre.android.geometry.LatLng

data class NavigationUpdate(
    val onRoute: Boolean,
    val distanceToRouteMeters: Double,
    val nextInstruction: String,
    val distanceToInstructionMeters: Double,
    val progressIndex: Int,
    val remainingDistanceMeters: Double = 0.0
)

/**
 * “Navigator” minimalista per seguire una polilinea.
 */
class TrackNavigationEngine(
    private val points: List<LatLng>,
    private val maneuvers: List<Maneuver> = ManeuverGenerator.generate(points),
    private val offRouteThresholdMeters: Double = 40.0 // Leggermente aumentata per tolleranza GPS
) {

    private val cumulativeMeters: DoubleArray = buildCumulative(points)
    private val totalDistanceMeters: Double = cumulativeMeters.lastOrNull() ?: 0.0

    private var lastNearestIndex: Int = 0
    private var nextManeuverIdx: Int = 0

    // Buffer per evitare falsi positivi fuori traccia
    private var offRouteCount: Int = 0
    private val OFF_ROUTE_CONFIRMATION_COUNT = 3

    private var lastSpokenManeuverIndex: Int = -1
    private var lastOffRouteSpokenAt: Long = 0L

    fun reset() {
        lastNearestIndex = 0
        nextManeuverIdx = 0
        lastSpokenManeuverIndex = -1
        lastOffRouteSpokenAt = 0L
        offRouteCount = 0
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
        
        // Logica "smart" per fuori traccia: richiede conferme multiple
        if (distToRoute > offRouteThresholdMeters) {
            offRouteCount++
        } else {
            offRouteCount = 0
        }
        val onRoute = offRouteCount < OFF_ROUTE_CONFIRMATION_COUNT

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

        val remainingDist = (totalDistanceMeters - cumulativeMeters[lastNearestIndex]).coerceAtLeast(0.0)

        return NavigationUpdate(
            onRoute = onRoute,
            distanceToRouteMeters = distToRoute,
            nextInstruction = instr,
            distanceToInstructionMeters = distToInstr,
            progressIndex = lastNearestIndex,
            remainingDistanceMeters = remainingDist
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
