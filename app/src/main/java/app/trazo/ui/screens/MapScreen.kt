package app.trazo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThreeDRotation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.config.MapStyle
import app.trazo.core.geo.LatLng
import app.trazo.core.location.LocationSample
import app.trazo.core.policy.LocationRequestSpec
import app.trazo.core.policy.Priority
import app.trazo.core.social.Freshness
import app.trazo.core.social.LocationFreshness
import app.trazo.core.social.SignalQuality
import app.trazo.core.trip.DetectorState
import app.trazo.data.remote.LiveLocationDto
import app.trazo.data.remote.isoToMillis
import app.trazo.data.repo.LinkState
import app.trazo.map.MapHandle
import app.trazo.map.MapLayers
import app.trazo.map.TrazoMapView
import app.trazo.map.VehicleMarkers
import app.trazo.tracking.LocationSource
import app.trazo.ui.components.Avatar
import app.trazo.ui.components.BodyType
import app.trazo.ui.components.FreshnessBadge
import app.trazo.ui.components.Pill
import app.trazo.ui.components.avatarColor
import app.trazo.ui.theme.Brand
import app.trazo.ui.theme.TrazoTheme
import app.trazo.util.Format
import app.trazo.util.Permissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

/** A friend's position as the map shows it: always with its age, never upgraded to "live". */
data class FriendPin(
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val position: LatLng,
    val accuracyM: Float?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val freshness: Freshness,
    val updatedAtMs: Long,
    val driving: Boolean,
    val vehicleName: String?,
    val icon: String,
)

fun mapStyleNow(day: MapStyle, night: MapStyle, autoNight: Boolean): MapStyle {
    if (!autoNight) return day
    val h = LocalTime.now().hour
    return if (h >= 21 || h < 7) night else day
}

@Composable
fun MapScreen(graph: AppGraph, nav: NavController, focusUserId: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState(initial = null)
    val status by graph.tracking.status.collectAsState()
    val positions by graph.live.positions.collectAsState()
    val link by graph.live.link.collectAsState()
    val social by graph.social.state.collectAsState()
    val serverSettings by graph.profile.settings.collectAsState()
    val convoys by graph.convoys.convoys.collectAsState()
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    var follow by remember { mutableStateOf(focusUserId == null) }
    var tilted by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(focusUserId) }
    var styleMenu by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var previewFix by remember { mutableStateOf<LocationSample?>(null) }
    var clickInstalled by remember { mutableStateOf(false) }
    var permTick by remember { mutableStateOf(0) }
    val hasLocation = remember(permTick) { Permissions.fineLocation(ctx) }

    // Friends' feed is only kept open while someone is looking at it (or tracking needs it).
    DisposableEffect(Unit) {
        graph.live.acquire()
        onDispose { graph.live.release() }
    }
    // Clock for freshness labels: ages keep counting even when no update arrives.
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }

    // When the tracking service is not running, a light foreground request shows where I am.
    DisposableEffect(hasLocation, status.serviceRunning) {
        var src: LocationSource? = null
        if (hasLocation && !status.serviceRunning) {
            src = LocationSource.best(ctx).also {
                it.start(LocationRequestSpec(Priority.HIGH_ACCURACY, 2_000, 1_000, 0f)) { s -> previewFix = s }
            }
        }
        onDispose { src?.stop() }
    }
    val myFix = if (status.serviceRunning) status.lastFix else previewFix

    val style = settings?.let { mapStyleNow(it.mapStyleDay, it.mapStyleNight, it.autoNightMap) } ?: MapStyle.DARK
    val colors = TrazoTheme.colors
    val labelColor = if (style.dark) Brand.SoftWhite.toArgb() else Brand.Graphite900.toArgb()
    val haloColor = if (style.dark) Brand.Graphite900.toArgb() else 0xFFFFFFFF.toInt()

    val me = graph.backend.userIdOrNull()
    val pins = remember(positions, social, now) { buildPins(positions, social, me, now, handle?.density ?: 3f) }

    Box(Modifier.fillMaxSize()) {
        TrazoMapView(
            styleUrl = style.url,
            modifier = Modifier.fillMaxSize(),
            onCameraMovedByUser = { follow = false; handle?.stopFollowing() },
        ) { h ->
            MapLayers.ensureMarkers(h.style, "friends", labelColor, haloColor, Brand.Signal.toArgb())
            if (!clickInstalled) h.map.addOnMapClickListener { p ->
                val pt = h.map.projection.toScreenLocation(p)
                val f = h.map.queryRenderedFeatures(pt, "friends-symbols").firstOrNull()
                selected = f?.getStringProperty("uid")
                f != null
            }
            clickInstalled = true
            h.enableMyLocation(follow)
            handle = h
        }

        // Keep markers in sync with the feed.
        LaunchedEffect(handle, pins, selected) {
            val h = handle ?: return@LaunchedEffect
            pins.forEach { p -> MapLayers.addImageIfMissing(h.style, p.icon) { iconBitmap(p, social, h.density) } }
            MapLayers.setMarkers(h.style, "friends", pins.map {
                MapLayers.Marker(
                    it.userId, it.position, it.icon,
                    bearing = it.bearingDeg?.toDouble()?.takeIf { _ -> it.freshness == Freshness.LIVE && it.driving },
                    label = it.name.split(" ").first(),
                    freshness = when (it.freshness) {
                        Freshness.LIVE -> "live"; Freshness.RECENT -> "recent"; Freshness.PAUSED -> "paused"; else -> "stale"
                    },
                    selected = it.userId == selected,
                )
            })
        }
        LaunchedEffect(handle, myFix) {
            val h = handle ?: return@LaunchedEffect
            myFix?.let { h.pushMyLocation(it) }
        }
        LaunchedEffect(handle, follow) { handle?.enableMyLocation(follow) }
        // Initial focus on a friend (from a notification or a profile).
        LaunchedEffect(handle, focusUserId, pins.isNotEmpty()) {
            val h = handle ?: return@LaunchedEffect
            val p = pins.firstOrNull { it.userId == focusUserId } ?: return@LaunchedEffect
            follow = false
            h.moveTo(p.position, zoom = 15.0)
        }

        // ---------------------------------------------------------------- top bar
        Column(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status.serviceRunning, status.detectorState, myFix, status.lastFixReceivedMs, now, hasLocation)
                Spacer(Modifier.weight(1f))
                val profile by graph.profile.profile.collectAsState()
                Box(Modifier.clickable { nav.navigate("profile") }) {
                    Avatar(profile?.displayName ?: "?", profile?.avatarUrl, 40.dp, ring = Brand.Volt)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ghost = serverSettings?.ghostMode == true
                val sharing = settings?.shareLive == true && !ghost
                Pill(
                    when {
                        ghost -> "Modo fantasma"
                        sharing -> "Compartiendo"
                        else -> "Ubicación en pausa"
                    },
                    icon = if (sharing) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    color = if (sharing) Brand.Volt.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    onClick = {
                        scope.launch {
                            if (ghost) graph.profile.updateSettings(ghostMode = false)
                            else graph.settings.setShareLive(!(settings?.shareLive ?: false))
                            graph.tracking.reconfigure()
                        }
                    },
                )
                if (link != LinkState.LIVE) Pill(
                    if (link == LinkState.CONNECTING) "Conectando…" else "Sin conexión: datos guardados",
                    color = Brand.Amber.copy(alpha = 0.2f),
                )
                val activeConvoy = convoys.firstOrNull { it.active }
                if (activeConvoy != null) Pill("Convoy: ${activeConvoy.convoy.name}", onClick = { nav.navigate("convoy/${activeConvoy.convoy.id}") })
            }
            if (!hasLocation) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LocationOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(10.dp))
                        Text("Sin permiso de ubicación. No se pueden registrar viajes.", Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton({ nav.navigate("profile"); permTick++ }) { Text("Revisar") }
                    }
                }
            }
            status.message?.let {
                Spacer(Modifier.height(8.dp))
                Pill(it, color = Brand.Amber.copy(alpha = 0.2f), onClick = { graph.tracking.update { s -> s.copy(message = null) } })
            }
        }

        // ---------------------------------------------------------------- right controls
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                SmallFloatingActionButton({ styleMenu = true }) { Icon(Icons.Filled.Layers, "Estilo del mapa") }
                DropdownMenu(styleMenu, { styleMenu = false }) {
                    val s0 = settings
                    DropdownMenuItem(text = { Text("Automático (día y noche)") }, onClick = {
                        styleMenu = false
                        if (s0 != null) scope.launch { graph.settings.setMapStyles(if (s0.mapStyleDay.dark) MapStyle.LIBERTY else s0.mapStyleDay, s0.mapStyleNight, autoNight = true) }
                    })
                    MapStyle.entries.forEach { ms ->
                        DropdownMenuItem(text = { Text(ms.label + if (ms.dark) " (noche)" else "") }, onClick = {
                            styleMenu = false
                            // A fixed choice is stored as the day style with auto-night off; dark picks also become the night style.
                            if (s0 != null) scope.launch {
                                graph.settings.setMapStyles(day = ms, night = if (ms.dark) ms else s0.mapStyleNight, autoNight = false)
                            }
                        })
                    }
                }
            }
            SmallFloatingActionButton({ tilted = !tilted; handle?.setTilt(if (tilted) 60.0 else 0.0) }) {
                Icon(Icons.Filled.ThreeDRotation, if (tilted) "Vista plana" else "Vista 3D")
            }
            SmallFloatingActionButton({
                follow = true
                myFix?.let { handle?.moveTo(LatLng(it.lat, it.lng), zoom = 16.0) }
            }) { Icon(if (follow) Icons.Filled.Navigation else Icons.Filled.MyLocation, "Centrar en mí") }
        }

        // ---------------------------------------------------------------- bottom
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            val sel = pins.firstOrNull { it.userId == selected }
            AnimatedVisibility(sel != null) {
                if (sel != null) FriendCard(sel, now, onClose = { selected = null }, onOpen = { nav.navigate("friend/${sel.userId}") }) {
                    follow = false
                    handle?.moveTo(sel.position, zoom = 16.0)
                }
            }
            Spacer(Modifier.height(10.dp))
            RecordingBar(graph, status, now, hasLocation)
        }
    }
}

private fun iconBitmap(p: FriendPin, social: app.trazo.data.repo.SocialSnapshot, density: Float): android.graphics.Bitmap {
    if (p.icon.startsWith("veh-")) {
        val v = social.people[p.userId]?.activeVehicleId?.let { social.vehicles[it] }
        if (v != null) return VehicleMarkers.bitmap(BodyType.from(v.bodyType), v.color, density)
    }
    val initials = p.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    return VehicleMarkers.personBitmap(initials.ifEmpty { "?" }, avatarColor(p.name).toArgb(), density)
}

private fun buildPins(
    positions: Map<String, LiveLocationDto>,
    social: app.trazo.data.repo.SocialSnapshot,
    me: String?,
    now: Long,
    density: Float,
): List<FriendPin> = positions.values.mapNotNull { l ->
    if (l.userId == me) return@mapNotNull null
    val lat = l.lat ?: return@mapNotNull null
    val lng = l.lng ?: return@mapNotNull null
    val profile = social.people[l.userId] ?: return@mapNotNull null
    // recorded_at is when the phone measured the fix; updated_at only when it reached the server.
    val t = isoToMillis(l.recordedAt) ?: isoToMillis(l.updatedAt) ?: return@mapNotNull null
    val paused = !l.sharing || l.state == "paused"
    val fresh = LocationFreshness.classify(t, now, paused)
    if (fresh == Freshness.HIDDEN) return@mapNotNull null
    val vehicle = (l.vehicleId ?: profile.activeVehicleId)?.let { social.vehicles[it] }
    val icon = if (vehicle != null) VehicleMarkers.key(BodyType.from(vehicle.bodyType), vehicle.color)
    else "p-${profile.id}"
    FriendPin(
        l.userId, profile.displayName, profile.avatarUrl, LatLng(lat, lng), l.accuracyM, l.speedMps, l.bearingDeg,
        fresh, t, l.state == "driving", vehicle?.name, icon,
    )
}.sortedBy { it.name }

@Composable
private fun StatusChip(running: Boolean, state: DetectorState, fix: LocationSample?, receivedMs: Long?, now: Long, hasPerm: Boolean) {
    val c = TrazoTheme.colors
    val quality = SignalQuality.fromAccuracy(fix?.accuracyM)
    val old = receivedMs != null && now - receivedMs > 30_000
    val (color, text) = when {
        !hasPerm -> c.stale to "Sin permiso"
        fix == null -> c.stale to "Buscando GPS…"
        old -> c.recent to "GPS sin señal · ${LocationFreshness.ageLabel(receivedMs!!, now)}"
        else -> when (quality) {
            SignalQuality.EXCELLENT, SignalQuality.GOOD -> c.live
            SignalQuality.FAIR -> c.recent
            else -> Brand.Coral
        } to "GPS ${Format.accuracy(fix.accuracyM)}"
    }
    val stateText = when {
        !running -> null
        state == DetectorState.DRIVING -> "Conduciendo"
        state == DetectorState.STOPPED -> "Detenido"
        state == DetectorState.ARMED -> "Detectando…"
        else -> "En espera"
    }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GpsNotFixed, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
            if (stateText != null) {
                Text("  ·  $stateText", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FriendCard(p: FriendPin, now: Long, onClose: () -> Unit, onOpen: () -> Unit, onCenter: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClick = onOpen)) { Avatar(p.name, p.avatarUrl, 48.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onCenter)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FreshnessBadge(p.freshness, LocationFreshness.ageLabel(p.updatedAtMs, now))
                val parts = buildList {
                    if (p.freshness == Freshness.LIVE && p.driving) add(p.speedMps?.let { Format.speed(it.toDouble()) } ?: "conduciendo")
                    else if (p.freshness == Freshness.PAUSED) add("ha pausado su ubicación")
                    else if (p.freshness != Freshness.LIVE) add("última posición conocida")
                    add(Format.accuracy(p.accuracyM))
                    p.vehicleName?.let { add(it) }
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onOpen) { Icon(Icons.Filled.Person, "Perfil") }
            IconButton(onClose) { Icon(Icons.Filled.Close, "Cerrar") }
        }
    }
}

@Composable
private fun RecordingBar(graph: AppGraph, status: app.trazo.tracking.TrackingStatus, now: Long, hasPerm: Boolean) {
    val recording = status.recordingTripId != null
    if (recording) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Brand.Coral, RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (status.simulation) "Simulación en curso" else if (status.detectorState == DetectorState.STOPPED) "Viaje en pausa (parado)" else "Grabando viaje",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "${Format.distance(status.tripDistanceM)} · ${Format.duration(now - (status.tripStartMs ?: now))}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = { graph.tracking.stopTrip() },
                    containerColor = Brand.Coral, contentColor = Brand.Graphite900,
                    icon = { Icon(Icons.Filled.Stop, null) }, text = { Text("Terminar") },
                )
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ExtendedFloatingActionButton(
                onClick = { if (hasPerm) graph.tracking.startTrip() },
                icon = { Icon(if (hasPerm) Icons.Filled.PlayArrow else Icons.Filled.PauseCircle, null) },
                text = { Text("Iniciar viaje") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
