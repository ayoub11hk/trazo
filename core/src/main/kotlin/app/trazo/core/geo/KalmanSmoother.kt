package app.trazo.core.geo

import app.trazo.core.location.LocationSample

/**
 * Display-only smoothing: a constant-position Kalman filter whose process noise grows with
 * speed and whose measurement noise is the reported accuracy. It removes jitter of a few
 * metres without cutting real corners, because at driving speed the filter trusts new fixes
 * almost completely. The output is labelled as smoothed and never replaces raw coordinates.
 */
class KalmanSmoother(private val processNoiseMps: Double = 3.0) {
    private var lat = 0.0
    private var lng = 0.0
    private var variance = -1.0
    private var lastMs = 0L

    fun reset() { variance = -1.0 }

    fun process(s: LocationSample): LatLng {
        val acc = (s.accuracyM ?: 10f).toDouble().coerceAtLeast(1.0)
        if (variance < 0) {
            lat = s.lat; lng = s.lng; variance = acc * acc; lastMs = s.elapsedRealtimeMs
            return LatLng(lat, lng)
        }
        val dt = (s.elapsedRealtimeMs - lastMs).coerceAtLeast(0) / 1000.0
        lastMs = s.elapsedRealtimeMs
        val q = processNoiseMps + (s.speedMps ?: 0f) // faster = less inertia
        variance += dt * q * q
        val k = variance / (variance + acc * acc)
        lat += k * (s.lat - lat)
        lng += k * (s.lng - lng)
        variance *= (1 - k)
        return LatLng(lat, lng)
    }

    companion object {
        fun smooth(samples: List<LocationSample>): List<LatLng> {
            val k = KalmanSmoother()
            return samples.map { k.process(it) }
        }
    }
}
