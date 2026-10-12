package app.trazo.core.geo

import kotlin.math.roundToLong

/** Encoded polyline algorithm (precision 5 or 6), used for compact route previews. */
object Polyline {
    fun encode(points: List<LatLng>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0L
        var pLng = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lng = (p.lng * factor).roundToLong()
            encodeValue(lat - pLat, sb)
            encodeValue(lng - pLng, sb)
            pLat = lat
            pLng = lng
        }
        return sb.toString()
    }

    fun decode(encoded: String, precision: Int = 6): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0L
        var lng = 0L
        while (i < encoded.length) {
            val (dLat, n1) = decodeValue(encoded, i); i = n1
            val (dLng, n2) = decodeValue(encoded, i); i = n2
            lat += dLat
            lng += dLng
            out += LatLng(lat / factor, lng / factor)
        }
        return out
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
            x = x shr 5
        }
        sb.append((x + 63).toInt().toChar())
    }

    private fun decodeValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val v = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return v to i
    }
}
