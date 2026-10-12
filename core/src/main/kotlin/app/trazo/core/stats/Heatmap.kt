package app.trazo.core.stats

import app.trazo.core.geo.LatLng
import kotlin.math.floor

/**
 * Bins route points into a grid so the accumulated-routes heatmap stays fast with years of
 * history: the map renders at most one weighted point per occupied cell.
 */
object Heatmap {
    data class Cell(val center: LatLng, val weight: Int)

    fun bin(points: Sequence<LatLng>, cellDeg: Double = 0.0015): List<Cell> {
        val counts = HashMap<Long, Int>()
        for (p in points) {
            val ix = floor(p.lng / cellDeg).toLong()
            val iy = floor(p.lat / cellDeg).toLong()
            val key = (ix shl 32) xor (iy and 0xffffffffL)
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.map { (k, w) ->
            val ix = k shr 32
            val iy = (k and 0xffffffffL).toInt().toLong()
            Cell(LatLng(((iy + 0.5) * cellDeg).coerceIn(-90.0, 90.0), ((ix + 0.5) * cellDeg).coerceIn(-180.0, 180.0)), w)
        }
    }
}
