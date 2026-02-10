package it.bike4city.hub.maps.snap

import android.net.Uri
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Snap "leggero" per la SOLA visualizzazione.
 *
 * - Usa Google Roads "Snap to Roads" con interpolate=false (non inventa tratti).
 * - Non salva nulla: tu continui a salvare la traccia filtrata/grezza.
 * - Se l'API fallisce o non restituisce risultati, ritorna i punti originali.
 *
 * NOTE:
 * - Roads API accetta max 100 punti per richiesta.
 */
class RoadsSnapperLite(private val apiKey: String) {

    fun isEnabled(): Boolean = apiKey.isNotBlank()

    fun snap(points: List<LatLng>): List<LatLng> {
        if (!isEnabled()) return points
        if (points.size < 2) return points

        // Google Roads: max 100 punti
        val chunks = points.chunked(100)
        val out = mutableListOf<LatLng>()

        for (chunk in chunks) {
            val snapped = snapChunk(chunk)
            if (snapped.isEmpty()) {
                // fallback: se un chunk fallisce, manteniamo il chunk originale
                out.addAll(chunk)
                continue
            }

            // merge chunk evitando duplicati
            if (out.isNotEmpty()) {
                val last = out.last()
                val first = snapped.first()
                if (haversineMeters(last, first) < 1.0) {
                    out.addAll(snapped.drop(1))
                } else {
                    out.addAll(snapped)
                }
            } else {
                out.addAll(snapped)
            }
        }

        return out
    }

    private fun snapChunk(points: List<LatLng>): List<LatLng> {
        return try {
            val path = points.joinToString("|") { "${it.latitude},${it.longitude}" }
            val url = Uri.parse("https://roads.googleapis.com/v1/snapToRoads").buildUpon()
                .appendQueryParameter("path", path)
                .appendQueryParameter("interpolate", "false")
                .appendQueryParameter("key", apiKey)
                .build()

            val conn = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return emptyList()
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            if (!json.has("snappedPoints")) return emptyList()

            val arr = json.getJSONArray("snappedPoints")
            val snapped = ArrayList<LatLng>(arr.length())
            for (i in 0 until arr.length()) {
                val sp = arr.getJSONObject(i)
                val loc = sp.getJSONObject("location")
                val lat = loc.getDouble("latitude")
                val lng = loc.getDouble("longitude")
                snapped.add(LatLng(lat, lng))
            }

            if (snapped.size < 2) emptyList() else snapped
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * R * kotlin.math.asin(kotlin.math.sqrt(h))
    }
}
