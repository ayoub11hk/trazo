package app.trazo.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    private val es = Locale.forLanguageTag("es-ES")
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", es)
    private val shortDayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", es)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", es)
    private val timeSecFmt = DateTimeFormatter.ofPattern("HH:mm:ss", es)

    fun distance(m: Double): String = when {
        m < 1000 -> "${m.roundToInt()} m"
        m < 100_000 -> String.format(es, "%.1f km", m / 1000)
        else -> String.format(es, "%,d km", (m / 1000).roundToInt())
    }

    fun km(m: Double, decimals: Int = 1): String = String.format(es, "%.${decimals}f", m / 1000)

    fun speed(mps: Double?): String = if (mps == null) "—" else "${(mps * 3.6).roundToInt()} km/h"
    fun speedValue(mps: Double): String = (mps * 3.6).roundToInt().toString()

    fun duration(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            ms < 60_000 -> "${(ms / 1000).coerceAtLeast(0)} s"
            h == 0L -> "$m min"
            else -> "$h h ${m.toString().padStart(2, '0')} min"
        }
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(es, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(es, "%d:%02d", s / 60, s % 60)
    }

    fun day(ms: Long): String = dayFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
        .replaceFirstChar { it.titlecase(es) }
    fun shortDay(ms: Long): String = shortDayFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun time(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun timeSec(ms: Long): String = timeSecFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun accuracy(m: Float?): String = if (m == null) "precisión desconocida" else "±${m.roundToInt()} m"

    fun percentChange(v: Double?): String = when {
        v == null -> "sin datos previos"
        v >= 0 -> "+${(v * 100).roundToInt()} %"
        else -> "${(v * 100).roundToInt()} %"
    }
}
