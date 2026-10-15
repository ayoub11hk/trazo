package app.trazo.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.config.MapStyle
import app.trazo.core.export.Gpx
import app.trazo.core.geo.LatLng
import app.trazo.core.playback.PlaybackFrame
import app.trazo.core.playback.PlaybackTimeline
import app.trazo.core.trip.RouteGeometry
import app.trazo.core.trip.TrackIncident
import app.trazo.core.trip.TrackPoint
import app.trazo.core.trip.TripAnalyzer
import app.trazo.data.local.TripEntity
import app.trazo.map.MapHandle
import app.trazo.map.MapLayers
import app.trazo.map.MapMatching
import app.trazo.map.RouteColors
import app.trazo.map.TrazoMapView
import app.trazo.map.VehicleMarkers
import app.trazo.ui.components.BodyType
import app.trazo.ui.components.ErrorBanner
import app.trazo.ui.components.LoadingState
import app.trazo.ui.components.Pill
import app.trazo.ui.components.SectionHeader
import app.trazo.ui.components.StatTile
import app.trazo.ui.theme.Brand
import app.trazo.ui.theme.TrazoTheme
import app.trazo.util.Format
import app.trazo.util.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

private val PLAY_SPEEDS = listOf(1, 5, 10, 30, 60)

/** Own trip ([ownerId] null) with full editing, or a friend's shared trip, read-only. */
@Composable
fun TripDetailScreen(graph: AppGraph, nav: NavController, tripId: String, ownerId: String?) {
    val ctx = LocalContext.current
    val own = ownerId == null
    val ownFlow = remember(tripId, own) { if (own) graph.trips.observeTrip(tripId) else kotlinx.coroutines.flow.flowOf(null) }
    val ownTrip by ownFlow.collectAsState(initial = null)
    val settings by graph.settings.settings.collectAsState(initial = null)
    val vehicles by graph.vehicles.observe().collectAsState(initial = emptyList())
    val social by graph.social.state.collectAsState()
    val runner = rememberRunner()

    var friendTrip by remember { mutableStateOf<TripEntity?>(null) }
    var points by remember { mutableStateOf<List<TrackPoint>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    var snapped by remember { mutableStateOf<List<LatLng>?>(null) }
    var snapOn by remember { mutableStateOf(false) }
    var snapBusy by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var offset by remember { mutableLongStateOf(0L) }
    var speedIdx by remember { mutableIntStateOf(2) }
    var followCam by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tripId) {
        runCatching {
            if (own) graph.trips.pointsFor(tripId)
            else {
                friendTrip = graph.trips.friendTrips(ownerId!!).firstOrNull { it.id == tripId }
                graph.trips.friendTripPoints(tripId)
            }
        }.onSuccess { points = it }.onFailure { loadError = it.userMessage() }
    }
    LaunchedEffect(settings?.snapToRoads) { if (settings?.snapToRoads == true) snapOn = true }

    val trip = if (own) ownTrip else friendTrip
    val pts = points
    val geo = remember(pts) { pts?.let { RouteGeometry.build(it) } }
    val timeline = remember(pts) { pts?.let { PlaybackTimeline(it) } }
    val summary = remember(pts) { pts?.let { TripAnalyzer.analyze(it) } }
    // Usable fixes split by segment, to draw played progress without bridging gaps.
    val segments = remember(pts) {
        val out = ArrayList<MutableList<Pair<Int, LatLng>>>()
        pts?.filter { it.usable }?.sortedBy { it.seq }?.forEachIndexed { i, p ->
            if (i == 0 || p.segmentStart) out.add(ArrayList())
            out.last().add(i to p.sample.latLng)
        }
        out
    }
    val frame: PlaybackFrame? = remember(timeline, offset) { timeline?.frameAt(offset) }
    val incidents = remember(trip?.incidentsJson, summary) {
        trip?.incidentsJson?.let { j ->
            runCatching { graph.json.decodeFromString(ListSerializer(TrackIncident.serializer()), j) }.getOrNull()
        }?.takeIf { it.isNotEmpty() } ?: summary?.incidents.orEmpty()
    }

    // Playback clock: advances by real elapsed time × the chosen speed.
    LaunchedEffect(playing, speedIdx, timeline) {
        val tl = timeline ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        if (offset >= tl.durationMs) offset = 0
        var last = withFrameMillis { it }
        while (playing) {
            val nowMs = withFrameMillis { it }
            offset = (offset + (nowMs - last) * PLAY_SPEEDS[speedIdx]).coerceAtMost(tl.durationMs)
            last = nowMs
            if (offset >= tl.durationMs) playing = false
        }
    }

    // Static route layers.
    LaunchedEffect(handle, geo) {
        val h = handle ?: return@LaunchedEffect
        val g = geo ?: return@LaunchedEffect
        MapLayers.setRoute(h.style, "trip", g.measured, g.gaps, g.start, g.end, g.rejected, showRejected = true)
        h.fit(g.allMeasured + g.gaps.flatMap { listOf(it.first, it.second) }, animateMs = 0)
    }
    LaunchedEffect(handle, snapped, snapOn) {
        val h = handle ?: return@LaunchedEffect
        MapLayers.setSnapped(h.style, "trip", if (snapOn) snapped else null)
    }
    LaunchedEffect(snapOn, geo) {
        val g = geo ?: return@LaunchedEffect
        val url = settings?.mapMatchingUrl
        if (!snapOn || snapped != null || url.isNullOrBlank()) return@LaunchedEffect
        snapBusy = true
        val mm = MapMatching(url)
        snapped = runCatching { withContext(Dispatchers.IO) { mm.match(g.allMeasured) } }
            .onFailure { runner.error = "No se pudo ajustar a carreteras: ${it.userMessage()}" }.getOrNull()
        mm.close()
        snapBusy = false
        if (snapped == null && runner.error == null) runner.error = "El servicio no ha encontrado carreteras para este recorrido."
    }
    // Moving marker, played trail and camera.
    LaunchedEffect(handle, frame) {
        val h = handle ?: return@LaunchedEffect
        val f = frame
        if (f == null || offset == 0L && !playing) {
            MapLayers.setProgress(h.style, "trip", emptyList())
            MapLayers.setMarkers(h.style, "play", emptyList())
            return@LaunchedEffect
        }
        MapLayers.setProgress(h.style, "trip", segments.mapNotNull { seg ->
            val upto = seg.filter { it.first <= f.pointIndex }.map { it.second }
            when {
                upto.isEmpty() -> null
                // The interpolated head is added only inside the segment being driven, never across a gap.
                !f.inGap && seg.last().first > f.pointIndex && seg.first().first <= f.pointIndex -> upto + f.position
                else -> upto
            }
        })
        MapLayers.setMarkers(h.style, "play", listOf(MapLayers.Marker("me", f.position, "play-icon", f.bearingDeg, "", "live", false)))
        if (followCam && playing) h.moveTo(f.position, bearing = f.bearingDeg, animateMs = 0)
    }

    val vehicleName = trip?.vehicleId?.let { id -> vehicles.firstOrNull { it.id == id }?.name ?: social.vehicles[id]?.name }
    val title = trip?.title ?: trip?.let { "${Format.day(it.startedAt)}" } ?: "Viaje"

    Scaffold(topBar = {
        BackTopBar(title, nav) {
            if (own && trip != null) Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Opciones") }
                DropdownMenu(menu, { menu = false }) {
                    listOf(
                        "rename" to "Cambiar nombre", "vehicle" to "Vehículo", "visibility" to "Quién puede verlo",
                        "merge" to "Unir con el viaje anterior", "split" to "Dividir en el punto de reproducción",
                        "gpx" to "Exportar GPX", "delete" to "Eliminar",
                    ).forEach { (k, l) ->
                        DropdownMenuItem(text = { Text(l) }, onClick = {
                            menu = false
                            if (k == "gpx") runner.run { exportGpx(ctx, title, points.orEmpty()) } else dialog = k
                        })
                    }
                }
            } else if (pts != null) {
                TextButton({ runner.run { exportGpx(ctx, title, pts) } }) { Text("GPX") }
            }
        }
    }) { pad ->
        if (loadError != null) { ErrorBanner(loadError!!, Modifier.padding(pad).padding(16.dp)); return@Scaffold }
        if (pts == null || geo == null || timeline == null) { LoadingState(Modifier.padding(pad), "Cargando recorrido…"); return@Scaffold }
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                val style = settings?.let { mapStyleNow(it.mapStyleDay, it.mapStyleNight, it.autoNightMap) } ?: MapStyle.TRAZO
                val c = TrazoTheme.colors
                TrazoMapView(style.url, Modifier.fillMaxSize(), onCameraMovedByUser = { followCam = false }) { h ->
                    MapLayers.ensureRoute(h.style, "trip", RouteColors(
                        measured = c.route.toArgb(), smoothed = c.route.toArgb(), gap = c.gap.toArgb(),
                        snapped = Brand.Signal.toArgb(), progress = 0xFFFFFFFF.toInt(), casing = Brand.Graphite900.toArgb(),
                    ), widthPx = 5f)
                    MapLayers.ensureMarkers(h.style, "play", 0xFFFFFFFF.toInt(), Brand.Graphite900.toArgb(), Brand.Signal.toArgb(), cluster = false)
                    val v = trip?.vehicleId?.let { id -> vehicles.firstOrNull { it.id == id } }
                    MapLayers.addImageIfMissing(h.style, "play-icon") {
                        if (v != null) VehicleMarkers.bitmap(BodyType.from(v.bodyType), v.color, h.density)
                        else VehicleMarkers.headingBitmap(Brand.Volt.toArgb(), h.density)
                    }
                    handle = h
                }
                if (snapBusy) Pill("Ajustando a carreteras…", Modifier.align(Alignment.TopCenter).padding(8.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                runner.error?.let { ErrorBanner(it, Modifier.padding(top = 8.dp)) }
                // ---------------------------------------------------------------- playback
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    FilledIconButton({ playing = !playing }) {
                        Icon(if (playing) Icons.Filled.Pause else if (offset >= timeline.durationMs && offset > 0) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                            if (playing) "Pausa" else "Reproducir")
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Slider(
                            value = if (timeline.durationMs > 0) offset.toFloat() / timeline.durationMs else 0f,
                            onValueChange = { offset = (it * timeline.durationMs).toLong() },
                        )
                        Row {
                            Text(trip?.let { Format.timeSec(firstTime(pts) + offset) } ?: Format.clock(offset),
                                style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                listOfNotNull(
                                    frame?.let { Format.distance(it.distanceM) },
                                    frame?.speedMps?.let { Format.speed(it) },
                                    if (frame?.inGap == true) "sin señal" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (frame?.inGap == true) Brand.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PLAY_SPEEDS.forEachIndexed { i, s -> FilterChip(speedIdx == i, { speedIdx = i }, label = { Text("${s}×") }) }
                    IconButton({ followCam = !followCam }) {
                        Icon(Icons.Outlined.CenterFocusStrong, "Seguir", tint = if (followCam) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(snapOn, { snapOn = !snapOn }, label = { Text("Ajustar a carreteras") })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (snapOn) "Línea discontinua azul: aproximación. La línea verde es lo medido." else "Se muestra exactamente lo que midió el GPS.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---------------------------------------------------------------- stats
                val distance = trip?.distanceM ?: summary?.distanceM ?: 0.0
                val measured = trip?.measuredDistanceM ?: summary?.measuredDistanceM ?: 0.0
                val gap = trip?.gapDistanceM ?: summary?.gapDistanceM ?: 0.0
                SectionHeader("Resumen")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Distancia", Format.km(distance), "km", Modifier.weight(1f),
                        hint = if (gap > 1) "${Format.distance(measured)} medidos + ${Format.distance(gap)} sin señal" else null)
                    StatTile("Duración", Format.duration(trip?.durationMs ?: summary?.durationMs ?: 0), null, Modifier.weight(1f),
                        hint = "${Format.duration(trip?.movingMs ?: summary?.movingMs ?: 0)} en movimiento")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val avg = trip?.avgSpeedMps ?: summary?.avgSpeedMps
                    val max = trip?.maxSpeedMps ?: summary?.maxSpeedMps
                    StatTile("Media", if (avg != null && avg > 0) Format.speedValue(avg) else "—", "km/h", Modifier.weight(1f))
                    StatTile("Máxima", if (max != null && max > 0) Format.speedValue(max) else "—", "km/h", Modifier.weight(1f),
                        hint = if (!own && (max == null || max == 0.0)) "oculta por su privacidad" else null)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val q = trip?.quality ?: summary?.qualityScore
                    StatTile("Calidad GPS", q?.toString() ?: "—", "/100", Modifier.weight(1f),
                        accent = when { q == null -> null; q >= 80 -> Brand.Live; q >= 50 -> Brand.Amber; else -> Brand.Coral })
                    StatTile("Puntos", "${pts.count { it.usable }}", "de ${pts.size}", Modifier.weight(1f))
                }
                Text(
                    "${Format.day(trip?.startedAt ?: firstTime(pts))} · ${Format.time(trip?.startedAt ?: firstTime(pts))}" +
                        (trip?.endedAt?.let { " – ${Format.time(it)}" } ?: "") + (vehicleName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (trip?.simulated == true || summary?.simulated == true) {
                    Pill("Viaje simulado: no son datos reales de GPS", Modifier.padding(top = 8.dp), color = Brand.Amber.copy(alpha = 0.25f))
                }
                if (incidents.isNotEmpty()) {
                    SectionHeader("Incidencias de señal")
                    incidents.forEach { inc ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, null, tint = Brand.Amber)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(incidentLabel(inc), style = MaterialTheme.typography.bodyMedium)
                                Text("${Format.timeSec(inc.startTimeMs)} – ${Format.timeSec(inc.endTimeMs)}" + if (inc.detail.isNotBlank()) " · ${inc.detail}" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ---------------------------------------------------------------- dialogs
    val t = trip
    when (dialog) {
        "rename" -> if (t != null) TextInputDialog("Nombre del viaje", "Nombre", t.title ?: "", maxLength = 80, onDismiss = { dialog = null }) {
            runner.run { graph.trips.rename(tripId, it); graph.sync.requestSync() }
        }
        "vehicle" -> if (t != null) ChoiceDialog(
            "Vehículo", listOf<Pair<String?, String>>(null to "Sin vehículo") + vehicles.map { it.id to it.name }, t.vehicleId,
            onDismiss = { dialog = null },
        ) { v -> runner.run { graph.trips.setVehicle(tripId, v); graph.sync.requestSync() } }
        "visibility" -> if (t != null) ChoiceDialog(
            "Quién puede verlo",
            listOf<Pair<String?, String>>("friends" to "Amigos con los que compartes viajes", "private" to "Solo yo"), t.visibility,
            onDismiss = { dialog = null },
        ) { v -> runner.run { graph.trips.setVisibility(tripId, v ?: "private"); graph.sync.requestSync() } }
        "merge" -> if (t != null) MergeDialog(graph, t, onDismiss = { dialog = null }) { other ->
            runner.run { graph.trips.merge(other.id, tripId); graph.sync.requestSync(); nav.popBackStack(); nav.navigate("trip/${other.id}") }
        }
        "split" -> {
            val at = firstTime(pts.orEmpty()) + offset
            ConfirmDialog(
                "Dividir el viaje",
                if (offset <= 0) "Mueve la reproducción hasta el punto donde quieras cortar y vuelve a elegir esta opción."
                else "Se creará un viaje nuevo a partir de las ${Format.timeSec(at)}.",
                if (offset <= 0) "Entendido" else "Dividir", onDismiss = { dialog = null },
            ) {
                if (offset > 0) runner.run {
                    graph.trips.split(tripId, at) ?: throw IllegalStateException("No se puede dividir en ese punto.")
                    graph.sync.requestSync()
                    points = graph.trips.pointsFor(tripId); offset = 0; snapped = null
                    toast(ctx, "Viaje dividido. La segunda parte está en tu historial.")
                }
            }
        }
        "delete" -> ConfirmDialog(
            "¿Eliminar este viaje?", "Se borrará del teléfono y del servidor. No se puede deshacer.", "Eliminar", destructive = true,
            onDismiss = { dialog = null },
        ) { runner.run { graph.trips.delete(tripId); graph.sync.requestSync(); nav.popBackStack() } }
    }
}

private fun firstTime(pts: List<TrackPoint>) = pts.filter { it.usable }.minByOrNull { it.seq }?.sample?.timeMs ?: 0L

private fun incidentLabel(i: TrackIncident) = when (i.type) {
    TrackIncident.Type.SIGNAL_GAP -> "Pérdida de señal (túnel, garaje…)"
    TrackIncident.Type.LOW_ACCURACY_STRETCH -> "Tramo con precisión baja"
    TrackIncident.Type.GPS_JUMPS_REJECTED -> "Saltos de GPS descartados"
    TrackIncident.Type.SIMULATED_LOCATION -> "Ubicación simulada detectada"
}

private suspend fun exportGpx(ctx: Context, name: String, pts: List<TrackPoint>) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "trazo-${System.currentTimeMillis()}.gpx").apply { writeText(Gpx.write(name, pts)) }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val i = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Exportar GPX").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun ChoiceDialog(title: String, options: List<Pair<String?, String>>, selected: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (k, l) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(k == selected, { onDismiss(); onPick(k) })
                        Text(l)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun MergeDialog(graph: AppGraph, t: TripEntity, onDismiss: () -> Unit, onMerge: (TripEntity) -> Unit) {
    var prev by remember { mutableStateOf<TripEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(t.id) { prev = withContext(Dispatchers.IO) { graph.db.trips().previous(t.startedAt) }; loaded = true }
    val p = prev
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unir viajes") },
        text = {
            Text(when {
                !loaded -> "Buscando…"
                p == null -> "No hay ningún viaje anterior."
                else -> "Se unirá con el viaje del ${Format.day(p.startedAt)} a las ${Format.time(p.startedAt)} " +
                    "(${Format.distance(p.distanceM)}). El tiempo entre ambos se mostrará como pausa, sin inventar recorrido."
            })
        },
        confirmButton = { TextButton({ onDismiss(); p?.let(onMerge) }, enabled = p != null) { Text("Unir") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}
