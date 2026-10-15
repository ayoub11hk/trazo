package app.trazo.core.live

import app.trazo.core.geo.LatLng

data class CameraTarget(val center: LatLng, val zoom: Double)

/**
 * Where the map opens and how close it zooms. The goal is to land on the user's street
 * (zoom ~17) as soon as a usable fix exists, without fighting the user once they pan away.
 */
object CameraPolicy {
    const val STREET_ZOOM = 17.0
    const val NEIGHBOURHOOD_ZOOM = 15.0
    /** A remembered fix older than this is only a hint; the map still waits for a fresh one. */
    const val FRESH_FIX_MS = 2 * 60_000L

    /** Zoom that matches how precise the fix is (a 300 m cell-tower fix should not zoom to one house). */
    fun zoomFor(accuracyM: Float?): Double = when {
        accuracyM == null -> 16.0
        accuracyM <= 30f -> STREET_ZOOM
        accuracyM <= 100f -> 16.0
        accuracyM <= 500f -> NEIGHBOURHOOD_ZOOM
        else -> 13.0
    }

    /**
     * First camera when the map is created, before any new fix: the last known position if
     * there is one, else the last place the user looked at, else null (show a neutral view
     * and a "finding you" state).
     */
    fun initial(lastFix: LatLng?, lastFixAccuracyM: Float?, savedCamera: CameraTarget?): CameraTarget? = when {
        lastFix != null -> CameraTarget(lastFix, zoomFor(lastFixAccuracyM))
        savedCamera != null -> savedCamera
        else -> null
    }

    /** Should a new fix move the camera? Only while following, or on the first usable fix. */
    fun shouldRecenter(following: Boolean, centeredOnFreshFix: Boolean, userMovedMap: Boolean): Boolean =
        following || (!centeredOnFreshFix && !userMovedMap)
}
