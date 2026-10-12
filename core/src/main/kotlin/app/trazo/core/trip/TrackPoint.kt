package app.trazo.core.trip

import app.trazo.core.location.LocationSample
import app.trazo.core.location.PointFlag
import kotlinx.serialization.Serializable

/** A stored fix of a trip: the untouched raw sample plus the filter's verdict. */
@Serializable
data class TrackPoint(
    val seq: Int,
    val sample: LocationSample,
    val flag: PointFlag,
    val segmentStart: Boolean,
) {
    val usable: Boolean get() = flag == PointFlag.ACCEPTED || flag == PointFlag.STATIONARY_JITTER
}
