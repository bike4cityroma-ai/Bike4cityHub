package it.bike4city.hub.gpx

import org.maplibre.android.geometry.LatLng
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxParser {

    data class ParsedGpx(
        val name: String?,
        val points: List<LatLng>,
        val distanceMeters: Double
    )

    fun parse(gpxContent: String): ParsedGpx {
        return parseProperly(gpxContent)
    }

    private fun parseProperly(gpxContent: String): ParsedGpx {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(gpxContent))

        var event = parser.eventType
        var trackName: String? = null
        val pts = ArrayList<LatLng>()

        var insideTrk = false
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle: Double? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "trk" -> insideTrk = true
                        "trkpt" -> {
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentEle = null
                        }
                        "ele" -> {
                            try {
                                currentEle = parser.nextText()?.toDoubleOrNull()
                            } catch (e: Exception) {}
                        }
                        "name" -> if (insideTrk && trackName == null) {
                            try {
                                trackName = parser.nextText()?.take(60)
                            } catch (e: Exception) {}
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName == "trkpt") {
                        val lat = currentLat
                        val lon = currentLon
                        if (lat != null && lon != null) {
                            pts.add(LatLng(lat, lon, currentEle ?: 0.0))
                        }
                    }
                    if (tagName == "trk") insideTrk = false
                }
            }
            event = parser.next()
        }

        val simplified = simplifyByMinStepMeters(pts, minStepMeters = 5.0)
        val distance = calculateTotalDistance(simplified)

        return ParsedGpx(trackName, simplified, distance)
    }

    fun createGpx(points: List<LatLng>, name: String): String {
        val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Bike4city Hub\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <trk>\n")
            append("    <name>$name</name>\n")
            append("    <trkseg>\n")
            points.forEach { p ->
                val time = iso8601Format.format(Date())
                append("      <trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">\n")
                if (p.altitude != 0.0) {
                    append("        <ele>${p.altitude}</ele>\n")
                }
                append("        <time>$time</time>\n")
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
            append("  </trk>\n")
            append("</gpx>")
        }
        return gpx
    }

    private fun simplifyByMinStepMeters(points: List<LatLng>, minStepMeters: Double): List<LatLng> {
        if (points.size < 3) return points
        val out = ArrayList<LatLng>()
        out.add(points.first())
        var last = points.first()

        for (i in 1 until points.lastIndex) {
            val p = points[i]
            val d = distanceMeters(last, p)
            if (d >= minStepMeters) {
                out.add(p)
                last = p
            }
        }
        out.add(points.last())
        return out
    }

    private fun calculateTotalDistance(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += distanceMeters(points[i-1], points[i])
        }
        return total
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, res)
        return res[0].toDouble()
    }
}
