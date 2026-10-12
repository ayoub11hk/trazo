package app.trazo.core.geo

import kotlin.math.sqrt

/**
 * Douglas–Peucker simplification in metres. Used only to render very long routes and to
 * store a compact preview; the full point list is always kept.
 */
object Simplify {
    fun douglasPeucker(points: List<LatLng>, toleranceM: Double): List<LatLng> {
        if (points.size < 3 || toleranceM <= 0) return points
        val proj = LocalProjection(points[0].lat, points[0].lng)
        val xs = DoubleArray(points.size) { proj.x(points[it].lng) }
        val ys = DoubleArray(points.size) { proj.y(points[it].lat) }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        // Iterative stack avoids deep recursion on routes with 100k+ points.
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        val tol2 = toleranceM * toleranceM
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            var maxD = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = segDist2(xs[i], ys[i], xs[s], ys[s], xs[e], ys[e])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tol2) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Squared distance from p to segment ab. */
    private fun segDist2(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    fun pathLengthM(points: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until points.size) d += GeoMath.distanceM(points[i - 1], points[i])
        return d
    }

    @Suppress("unused")
    private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)
}
