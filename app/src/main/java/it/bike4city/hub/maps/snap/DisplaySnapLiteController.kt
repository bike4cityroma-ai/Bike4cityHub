package it.bike4city.hub.maps.snap

import org.maplibre.android.geometry.LatLng
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Controller per ottenere una "vista" più pulita:
 * - snappa SOLO l'ultimo blocco (default 100 punti)
 * - non snappa troppo spesso
 * - accetta lo snap solo se lo spostamento è entro una soglia (anti-falso)
 */
class DisplaySnapLiteController(
    private val snapper: RoadsSnapperLite,
    private val snapIntervalMs: Long = 12_000L,
    private val chunkSize: Int = 100,
    private val maxShiftMeters: Double = 12.0
) {

    private var lastSnapAtMs: Long = 0L

    fun shouldSnap(nowMs: Long = System.currentTimeMillis(), pointsCount: Int): Boolean {
        if (!snapper.isEnabled()) return false
        if (pointsCount < 25) return false
        return (nowMs - lastSnapAtMs) >= snapIntervalMs
    }

    fun buildDisplayPoints(filteredPoints: List<LatLng>): List<LatLng> {
        if (!snapper.isEnabled()) return filteredPoints
        if (filteredPoints.size < 25) return filteredPoints

        lastSnapAtMs = System.currentTimeMillis()

        val head = if (filteredPoints.size > chunkSize) filteredPoints.dropLast(chunkSize) else emptyList()
        val tail = filteredPoints.takeLast(chunkSize)

        val snappedTail = snapper.snap(tail)
        if (snappedTail.size < 2) return filteredPoints

        val mergedTail = mergeSnapLight(original = tail, snapped = snappedTail)
        return head + mergedTail
    }

    private fun mergeSnapLight(original: List<LatLng>, snapped: List<LatLng>): List<LatLng> {
        if (snapped.size < 2) return original

        val out = ArrayList<LatLng>(snapped.size)
        val n = original.size
        val m = snapped.size

        for (i in 0 until m) {
            // mappa indice snapped -> indice originale stimato
            val oi = ((i.toDouble() / (m - 1).coerceAtLeast(1)) * (n - 1)).roundToInt()
            val from = (oi - 6).coerceAtLeast(0)
            val to = (oi + 6).coerceAtMost(n - 1)

            val s = snapped[i]
            var best = original[oi]
            var bestD = Double.MAX_VALUE

            for (k in from..to) {
                val d = haversineMeters(original[k], s)
                if (d < bestD) {
                    bestD = d
                    best = original[k]
                }
            }

            out += if (bestD <= maxShiftMeters) s else best
        }

        return out
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2.0)
        return 2 * R * asin(sqrt(h))
    }
}
