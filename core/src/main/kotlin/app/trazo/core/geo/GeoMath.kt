package app.trazo.core.geo

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class LatLng(val lat: Double, val lng: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lng in -180.0..180.0) { "lng out of range: $lng" }
    }
}

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun toRad(deg: Double) = deg * PI / 180.0
    fun toDeg(rad: Double) = rad * 180.0 / PI

    /** Great-circle distance in metres (haversine; < 0.5 % error for any distance). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLng = toRad(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun distanceM(a: LatLng, b: LatLng) = distanceM(a.lat, a.lng, b.lat, b.lng)

    /** Initial bearing from a to b in degrees [0, 360). */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = toRad(lat1)
        val p2 = toRad(lat2)
        val dl = toRad(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(toDeg(atan2(y, x)))
    }

    fun normalizeDeg(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /** Smallest absolute angle between two headings, in degrees [0, 180]. */
    fun headingDelta(a: Double, b: Double): Double {
        val d = kotlin.math.abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180) 360 - d else d
    }

    /** Linear interpolation; adequate for the short segments between consecutive fixes. */
    fun lerp(a: LatLng, b: LatLng, f: Double): LatLng {
        var dLng = b.lng - a.lng
        if (dLng > 180) dLng -= 360 else if (dLng < -180) dLng += 360
        var lng = a.lng + dLng * f
        if (lng > 180) lng -= 360 else if (lng < -180) lng += 360
        return LatLng(a.lat + (b.lat - a.lat) * f, lng)
    }

    /** Interpolates headings along the shortest arc. */
    fun lerpHeading(a: Double, b: Double, f: Double): Double {
        var d = normalizeDeg(b) - normalizeDeg(a)
        if (d > 180) d -= 360 else if (d < -180) d += 360
        return normalizeDeg(a + d * f)
    }
}

/**
 * Local equirectangular projection around a reference latitude: converts to metres on a
 * plane. Accurate to well under 1 m over the extent of a single trip segment, which is
 * all simplification and smoothing need.
 */
class LocalProjection(refLat: Double, private val refLng: Double) {
    private val refLatRad = GeoMath.toRad(refLat)
    private val refLatDeg = refLat
    private val mPerDegLat = GeoMath.EARTH_RADIUS_M * PI / 180.0
    private val mPerDegLng = mPerDegLat * cos(refLatRad)

    fun x(lng: Double): Double {
        var d = lng - refLng
        if (d > 180) d -= 360 else if (d < -180) d += 360
        return d * mPerDegLng
    }
    fun y(lat: Double): Double = (lat - refLatDeg) * mPerDegLat
    fun lat(y: Double): Double = refLatDeg + y / mPerDegLat
    fun lng(x: Double): Double {
        var l = refLng + x / mPerDegLng
        if (l > 180) l -= 360 else if (l < -180) l += 360
        return l
    }
}
