package it.bike4city.hub.navigation

import org.maplibre.android.geometry.LatLng
import kotlin.math.*

/**
 * Utility geografiche leggere (senza librerie esterne).
 */
object Geo {

    private const val R = 6_371_000.0 // raggio medio terrestre (metri)

    /** Distanza in metri (Haversine). */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)

        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** Bearing in gradi [0..360). */
    fun bearingDeg(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    /** Differenza angolare minima in gradi tra due bearing. Risultato in [-180..180]. */
    fun bearingDeltaDeg(a: Double, b: Double): Double {
        var d = (b - a + 540.0) % 360.0 - 180.0
        // normalizza -180 incluso
        if (d == -180.0) d = 180.0
        return d
    }

    /**
     * Trova l'indice del punto della traccia più vicino alla posizione attuale.
     * O(n) ma in pratica va bene (anche con qualche migliaio di punti).
     */
    fun nearestPointIndex(pos: LatLng, points: List<LatLng>): Int {
        var bestIdx = 0
        var best = Double.POSITIVE_INFINITY
        for (i in points.indices) {
            val d = distanceMeters(pos, points[i])
            if (d < best) {
                best = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    /** Distanza minima (approssimata) dalla posizione alla polilinea, in metri. */
    fun distanceToPolylineMeters(pos: LatLng, points: List<LatLng>): Double {
        if (points.isEmpty()) return Double.POSITIVE_INFINITY
        // Approccio semplice: distanza dal punto più vicino.
        // Per una traccia “bike” è accettabile; se vuoi più precisione, possiamo fare proiezione su segmento.
        val idx = nearestPointIndex(pos, points)
        return distanceMeters(pos, points[idx])
    }
}
