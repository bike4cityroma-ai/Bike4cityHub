package it.bike4city.hub.gpx

import com.google.android.gms.maps.model.LatLng
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
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(gpxContent))

        var event = parser.eventType
        var trackName: String? = null
        val pts = ArrayList<LatLng>()

        var insideTrk = false
        var insideName = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "trk" -> insideTrk = true
                        "name" -> if (insideTrk) insideName = true
                        "trkpt" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) pts.add(LatLng(lat, lon))
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideName && trackName == null) {
                        val t = parser.text?.trim()
                        if (!t.isNullOrBlank()) trackName = t.take(60)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "name" -> insideName = false
                        "trk" -> insideTrk = false
                    }
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
