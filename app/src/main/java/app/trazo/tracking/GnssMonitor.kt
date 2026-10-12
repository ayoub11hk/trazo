package app.trazo.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GnssInfo(val satellitesVisible: Int = 0, val satellitesUsed: Int = 0, val constellations: Set<String> = emptySet())

/** Satellite status from the GNSS chip, shown so people can judge fix quality themselves. */
class GnssMonitor(ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _info = MutableStateFlow(GnssInfo())
    val info: StateFlow<GnssInfo> = _info
    private var registered = false

    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val kinds = HashSet<String>()
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    kinds += when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> "GPS"
                        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                        else -> "Otros"
                    }
                }
            }
            _info.value = GnssInfo(status.satelliteCount, used, kinds)
        }

        override fun onStopped() {
            _info.value = GnssInfo()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        registered = runCatching { lm.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper())) }.getOrDefault(false)
    }

    fun stop() {
        if (registered) lm.unregisterGnssStatusCallback(callback)
        registered = false
    }
}
