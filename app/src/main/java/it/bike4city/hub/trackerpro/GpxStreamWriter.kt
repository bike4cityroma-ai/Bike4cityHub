package it.bike4city.hub.trackerpro

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class GpxStreamWriter {

    private var writer: BufferedWriter? = null
    private var file: File? = null

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun start(outFile: File, trackName: String) {
        outFile.parentFile?.mkdirs()
        file = outFile
        writer = BufferedWriter(OutputStreamWriter(outFile.outputStream(), Charsets.UTF_8)).apply {
            write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            write("<gpx version=\"1.1\" creator=\"Bike4City Hub\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            write("<metadata><time>${iso.format(Date())}</time></metadata>\n")
            write("<trk><name>${escape(trackName)}</name><trkseg>\n")
            flush()
        }
    }

    fun appendPoint(
        lat: Double,
        lon: Double,
        timeMillis: Long,
        ele: Double?,
        accuracy: Float?,
        speed: Float?,
        bearing: Float?
    ) {
        val w = writer ?: return
        w.write("<trkpt lat=\"$lat\" lon=\"$lon\">")
        ele?.let { w.write("<ele>$it</ele>") }
        w.write("<time>${iso.format(Date(timeMillis))}</time>")
        if (accuracy != null || speed != null || bearing != null) {
            w.write("<extensions>")
            accuracy?.let { w.write("<accuracy>$it</accuracy>") }
            speed?.let { w.write("<speed>$it</speed>") }
            bearing?.let { w.write("<bearing>$it</bearing>") }
            w.write("</extensions>")
        }
        w.write("</trkpt>\n")
        w.flush()
    }

    fun finish() {
        val w = writer ?: return
        w.write("</trkseg></trk>\n</gpx>\n")
        w.flush()
        w.close()
        writer = null
    }

    fun getFile(): File? = file

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
