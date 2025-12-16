package it.bike4city.hub.location

import com.google.android.gms.maps.model.LatLng
import kotlin.math.roundToInt

object PolylineCodec {
    fun encode(points: List<LatLng>): String {
        var lastLat = 0
        var lastLng = 0
        val result = StringBuilder()

        for (p in points) {
            val lat = (p.latitude * 1e5).roundToInt()
            val lng = (p.longitude * 1e5).roundToInt()
            val dLat = lat - lastLat
            val dLng = lng - lastLng
            encodeValue(dLat, result)
            encodeValue(dLng, result)
            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encodeValue(value: Int, out: StringBuilder) {
        var v = value shl 1
        if (value < 0) v = v.inv()
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        out.append((v + 63).toChar())
    }

    fun decode(polyline: String): List<LatLng> {
        val points = ArrayList<LatLng>()
        var index = 0
        val len = polyline.length
        var lat = 0
        var lng = 0

        while (index < len) {
            lat += decodeValue(polyline, ::nextChar, { index }, { index = it })
            lng += decodeValue(polyline, ::nextChar, { index }, { index = it })
            points.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return points
    }

    private fun nextChar(s: String, i: Int) = s[i].code

    private fun decodeValue(
        s: String,
        charAt: (String, Int) -> Int,
        getIndex: () -> Int,
        setIndex: (Int) -> Unit
    ): Int {
        var result = 0
        var shift = 0
        var b: Int
        var index = getIndex()

        do {
            b = charAt(s, index++) - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)

        setIndex(index)
        val d = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        return d
    }
}