package app.trazo.core.export

import app.trazo.core.trip.TrackPoint
import java.time.Instant

/**
 * GPX 1.1 export of the measured fixes only. Each measured segment (split by signal gaps)
 * becomes its own <trkseg>, so other apps never draw a line across a gap. Rejected fixes
 * (jumps, poor accuracy) are left out.
 */
object Gpx {
    fun write(name: String, points: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Trazo" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        var open = false
        for (p in points.filter { it.usable }.sortedBy { it.seq }) {
            if (!open || p.segmentStart) {
                if (open) sb.append("    </trkseg>\n")
                sb.append("    <trkseg>\n")
                open = true
            }
            val s = p.sample
            sb.append("      <trkpt lat=\"").append(fmt(s.lat)).append("\" lon=\"").append(fmt(s.lng)).append("\">")
            s.altitudeM?.let { sb.append("<ele>").append(String.format(java.util.Locale.ROOT, "%.1f", it)).append("</ele>") }
            sb.append("<time>").append(Instant.ofEpochMilli(s.timeMs)).append("</time>")
            s.accuracyM?.let { sb.append("<hdop>").append(String.format(java.util.Locale.ROOT, "%.1f", it / 5f)).append("</hdop>") }
            sb.append("</trkpt>\n")
        }
        if (open) sb.append("    </trkseg>\n")
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.7f", v)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
