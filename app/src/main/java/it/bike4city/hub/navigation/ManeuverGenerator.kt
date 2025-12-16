package it.bike4city.hub.navigation

import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs

object ManeuverGenerator {

    /**
     * Estrae “manovre” direttamente dalla geometria della traccia.
     * Non è perfetto come una vera navigazione stradale (mancano nomi vie, sensi unici, ecc.),
     * ma per seguire una traccia GPX è sorprendentemente utile.
     */
    fun generate(points: List<LatLng>): List<Maneuver> {
        if (points.size < 3) return emptyList()

        val maneuvers = ArrayList<Maneuver>()
        for (i in 1 until points.lastIndex) {
            val a = points[i - 1]
            val b = points[i]
            val c = points[i + 1]

            val b1 = Geo.bearingDeg(a, b)
            val b2 = Geo.bearingDeg(b, c)
            val delta = Geo.bearingDeltaDeg(b1, b2) // [-180..180]
            val ad = abs(delta)

            // soglie: tarate per bici (evita “rumore” da GPS o tracciati molto spezzati)
            val type = when {
                ad >= 150 -> TurnType.UTURN
                ad >= 100 -> if (delta > 0) TurnType.SHARP_RIGHT else TurnType.SHARP_LEFT
                ad >= 60 -> if (delta > 0) TurnType.RIGHT else TurnType.LEFT
                ad >= 35 -> if (delta > 0) TurnType.SLIGHT_RIGHT else TurnType.SLIGHT_LEFT
                else -> null
            }

            if (type != null) {
                // Dedup: se due manovre sono troppo vicine (traccia “seghettata”), tieni la più forte.
                val prev = maneuvers.lastOrNull()
                if (prev != null && (i - prev.index) <= 3) {
                    if (abs(prev.deltaDeg) < ad) {
                        maneuvers[maneuvers.lastIndex] = Maneuver(i, b, type, delta)
                    }
                } else {
                    maneuvers.add(Maneuver(i, b, type, delta))
                }
            }
        }
        return maneuvers
    }
}
