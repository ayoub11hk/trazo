package app.trazo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.core.stats.Bucket
import app.trazo.core.stats.Leaderboard
import app.trazo.core.stats.LeaderboardMetric
import app.trazo.core.stats.Period
import app.trazo.core.stats.PeriodRange
import app.trazo.core.stats.StatsCalculator
import app.trazo.core.stats.Totals
import app.trazo.data.remote.LeaderboardRow
import app.trazo.ui.components.Avatar
import app.trazo.ui.components.EmptyState
import app.trazo.ui.components.SectionHeader
import app.trazo.ui.components.SettingCard
import app.trazo.ui.components.StatTile
import app.trazo.ui.theme.Brand
import app.trazo.ui.theme.TrazoTheme
import app.trazo.util.Format
import app.trazo.util.userMessage
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES = Locale.forLanguageTag("es-ES")
private val monthFmt = DateTimeFormatter.ofPattern("LLLL yyyy", ES)
private val dayFmt = DateTimeFormatter.ofPattern("d MMM", ES)

private fun rangeLabel(r: PeriodRange): String = when (r.period) {
    Period.DAY -> dayFmt.format(r.start)
    Period.WEEK -> "${dayFmt.format(r.start)} – ${dayFmt.format(r.endExclusive.minusDays(1))}"
    Period.MONTH -> monthFmt.format(r.start).replaceFirstChar { it.uppercase() }
    Period.YEAR -> r.start.year.toString()
    Period.ALL -> "Desde el principio"
}

private fun shift(r: PeriodRange, steps: Long): PeriodRange = when (r.period) {
    Period.DAY -> PeriodRange.containing(r.period, r.start.plusDays(steps))
    Period.WEEK -> PeriodRange.containing(r.period, r.start.plusWeeks(steps))
    Period.MONTH -> PeriodRange.containing(r.period, r.start.plusMonths(steps))
    Period.YEAR -> PeriodRange.containing(r.period, r.start.plusYears(steps))
    Period.ALL -> r
}

@Composable
fun StatsScreen(graph: AppGraph, nav: NavController) {
    val zone = remember { ZoneId.systemDefault() }
    val calc = remember { StatsCalculator(zone) }
    val trips by graph.trips.observeTrips().collectAsState(initial = emptyList())
    val vehicles by graph.vehicles.observe().collectAsState(initial = emptyList())
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var anchor by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    val range = remember(period, anchor) { PeriodRange.containing(period, LocalDate.parse(anchor)) }
    // Simulated drives are kept in the history but never count towards statistics.
    val facts = remember(trips) { trips.filter { !it.simulated }.map { it.facts() } }
    val cmp = remember(facts, range) { calc.compare(facts, range) }
    val current = if (period == Period.ALL) calc.totals(facts) else cmp.current
    val series = remember(facts, range) { seriesFor(calc, facts, range) }
    val perVehicle = remember(facts, range) {
        calc.perVehicle(facts.filter { period == Period.ALL || range.contains(calc.dayOf(it)) })
    }
    val records = remember(facts) { calc.records(facts) }

    Scaffold(topBar = {
        TabTopBar("Estadísticas") { IconButton({ nav.navigate("heatmap") }) { Icon(Icons.Filled.Whatshot, "Mapa de calor") } }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val opts = listOf(Period.WEEK to "Semana", Period.MONTH to "Mes", Period.YEAR to "Año", Period.ALL to "Total")
                opts.forEachIndexed { i, (p, l) ->
                    SegmentedButton(period == p, { period = p; anchor = LocalDate.now(zone).toString() },
                        SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(l) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                if (period != Period.ALL) IconButton({ anchor = shift(range, -1).start.toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Anterior")
                }
                Text(rangeLabel(range), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (period != Period.ALL) IconButton(
                    { anchor = shift(range, 1).start.toString() },
                    enabled = range.endExclusive.isBefore(LocalDate.now(zone).plusDays(1)),
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Siguiente") }
            }
            if (facts.isEmpty()) {
                EmptyState(Icons.Filled.Whatshot, "Sin datos todavía", "Las estadísticas se calculan con tus viajes reales.")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Distancia", Format.km(current.distanceM), "km", Modifier.weight(1f),
                    accent = Brand.Volt, hint = if (period != Period.ALL) "${Format.percentChange(cmp.distanceChange)} vs. anterior" else null)
                StatTile("Viajes", "${current.trips}", null, Modifier.weight(1f),
                    hint = if (period != Period.ALL) "${cmp.previous.trips} en el periodo anterior" else null)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Al volante", Format.duration(current.movingMs), null, Modifier.weight(1f))
                StatTile("Media", if (current.movingMs > 0) Format.speedValue(current.avgMovingSpeedMps) else "—", "km/h", Modifier.weight(1f))
            }

            if (series.size > 1) {
                SectionHeader("Evolución")
                SettingCard { BarChart(series, Modifier.fillMaxWidth().height(160.dp).padding(16.dp)) }
                Text(chartCaption(period), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (perVehicle.size > 1 || perVehicle.keys.any { it != null }) {
                SectionHeader("Por vehículo")
                val names = vehicles.associate { it.id to it.name }
                perVehicle.entries.sortedByDescending { it.value.distanceM }.forEach { (vid, t) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(vid?.let { names[it] ?: "Vehículo eliminado" } ?: "Sin vehículo", Modifier.weight(1f))
                        Text("${Format.distance(t.distanceM)} · ${t.trips} viajes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionHeader("Récords")
            SettingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    records.longestTrip?.let { RecordRow("Viaje más largo", "${Format.distance(it.distanceM)} · ${Format.day(it.startTimeMs)}") }
                    records.longestDurationTrip?.let { RecordRow("Más tiempo en un viaje", "${Format.duration(it.durationMs)} · ${Format.day(it.startTimeMs)}") }
                    records.bestDay?.let { (d, m) -> RecordRow("Mejor día", "${Format.distance(m)} · ${dayFmt.format(d)} ${d.year}") }
                    RecordRow("Racha más larga", "${records.longestStreakDays} días seguidos conduciendo")
                }
            }
            Text("No hay récords de velocidad a propósito: Trazo no premia conducir rápido.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            LeaderboardSection(graph, range, period)
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun chartCaption(p: Period) = when (p) {
    Period.WEEK, Period.MONTH -> "Kilómetros por día"
    Period.YEAR -> "Kilómetros por mes"
    else -> "Kilómetros por año"
}

private fun seriesFor(calc: StatsCalculator, facts: List<app.trazo.core.stats.TripFacts>, r: PeriodRange): List<Bucket> {
    if (facts.isEmpty()) return emptyList()
    return when (r.period) {
        Period.DAY -> emptyList()
        Period.WEEK, Period.MONTH -> calc.series(facts, Period.DAY, r.start, r.endExclusive.minusDays(1))
        Period.YEAR -> calc.series(facts, Period.MONTH, r.start, r.endExclusive.minusDays(1))
        Period.ALL -> {
            val first = facts.minOf { calc.dayOf(it) }
            calc.series(facts, Period.YEAR, first, LocalDate.now())
        }
    }
}

@Composable
private fun BarChart(buckets: List<Bucket>, modifier: Modifier) {
    val color = TrazoTheme.colors.route
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = buckets.maxOf { it.totals.distanceM }.coerceAtLeast(1.0)
    Canvas(modifier) {
        val n = buckets.size
        val gap = size.width / n * 0.25f
        val w = size.width / n - gap
        buckets.forEachIndexed { i, b ->
            val h = (b.totals.distanceM / max * size.height).toFloat().coerceAtLeast(3f)
            drawRoundRect(
                if (b.totals.trips > 0) color else empty,
                topLeft = Offset(i * (w + gap) + gap / 2, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3, w / 3),
            )
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LeaderboardSection(graph: AppGraph, range: PeriodRange, period: Period) {
    val zone = ZoneId.systemDefault()
    var metric by rememberSaveable { mutableStateOf(LeaderboardMetric.DISTANCE) }
    var rows by remember { mutableStateOf<List<LeaderboardRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(range) {
        error = null
        val since = if (period == Period.ALL) 0L else range.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = if (period == Period.ALL) System.currentTimeMillis() + 60_000 else range.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        runCatching { graph.social.leaderboard(since, until) }.onSuccess { rows = it }.onFailure { error = it.userMessage() }
    }
    SectionHeader("Clasificación entre amigos")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            LeaderboardMetric.DISTANCE to "Distancia", LeaderboardMetric.TRIPS to "Viajes",
            LeaderboardMetric.DRIVING_TIME to "Tiempo", LeaderboardMetric.AVG_TRIP_DISTANCE to "Km por viaje",
        ).forEach { (m, l) -> FilterChip(metric == m, { metric = m }, label = { Text(l) }) }
    }
    val r = rows
    when {
        error != null -> Text("Sin conexión: la clasificación necesita el servidor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        r == null -> Text("Cargando…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val totals = r.associate { it.userId to Totals(it.trips.toInt(), it.distanceM, it.movingMs, it.movingMs, 0.0) }
            val byId = r.associateBy { it.userId }
            val me = graph.backend.userIdOrNull()
            Leaderboard.rank(totals, metric).forEach { e ->
                val row = byId[e.userId] ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.rank}", Modifier.width(28.dp), style = MaterialTheme.typography.titleMedium,
                        color = if (e.rank == 1) Brand.Volt else MaterialTheme.colorScheme.onSurfaceVariant)
                    Avatar(row.displayName, row.avatarUrl, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(row.displayName + if (row.userId == me) " (tú)" else "", Modifier.weight(1f))
                    Text(when (metric) {
                        LeaderboardMetric.DISTANCE -> Format.distance(e.value)
                        LeaderboardMetric.TRIPS -> "${e.value.toInt()}"
                        LeaderboardMetric.DRIVING_TIME -> Format.duration(e.value.toLong())
                        LeaderboardMetric.AVG_TRIP_DISTANCE -> Format.distance(e.value)
                    }, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (r.size <= 1) Text("Aparecerán tus amigos que compartan viajes y estadísticas contigo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
