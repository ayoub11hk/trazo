package app.trazo.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.os.BatteryManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.config.MapStyle
import app.trazo.config.SavedCamera
import app.trazo.core.geo.LatLng
import app.trazo.core.live.CameraPolicy
import app.trazo.core.live.CameraTarget
import app.trazo.core.live.MarkerMotion
import app.trazo.core.location.LocationSample
import app.trazo.core.policy.LocationRequestSpec
import app.trazo.core.policy.Priority
import app.trazo.core.social.Freshness
import app.trazo.core.social.LocationFreshness
import app.trazo.core.social.SignalQuality
import app.trazo.core.trip.DetectorState
import app.trazo.data.local.PointEntity
import app.trazo.data.remote.LiveLocationDto
import app.trazo.data.remote.isoToMillis
import app.trazo.data.repo.LinkState
import app.trazo.data.repo.SocialSnapshot
import app.trazo.map.MapHandle
import app.trazo.map.PeopleLayer
import app.trazo.map.TrazoMapView
import app.trazo.map.toML
import app.trazo.tracking.LocationSource
import app.trazo.ui.components.Avatar
import app.trazo.ui.components.BodyType
import app.trazo.ui.components.FreshnessBadge
import app.trazo.ui.components.Pill
import app.trazo.ui.components.PulseDot
import app.trazo.ui.components.avatarColor
import app.trazo.ui.theme.Brand
import app.trazo.ui.theme.TrazoTheme
import app.trazo.util.Format
import app.trazo.util.Permissions
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
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
    val vehicleBody: String?,
    val vehicleColor: String?,
)

fun mapStyleNow(day: MapStyle, night: MapStyle, autoNight: Boolean): MapStyle {
    if (!autoNight) return day
    val h = LocalTime.now().hour
    return if (h >= 21 || h < 7) night else day
}

/** Stable colour per person: their ring on the map and the colour of their live line. */
fun personColor(userId: String): Color = avatarColor(userId)

private const val ME = "me"
private const val DEFAULT_CAR = "#D7DCE4"

@Composable
fun MapScreen(graph: AppGraph, nav: NavController, focusUserId: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings by graph.settings.settings.collectAsState(initial = null)
    val status by graph.tracking.status.collectAsState()
    val positions by graph.live.positions.collectAsState()
    val link by graph.live.link.collectAsState()
    val social by graph.social.state.collectAsState()
    val serverSettings by graph.profile.settings.collectAsState()
    val profile by graph.profile.profile.collectAsState()
    val convoys by graph.convoys.convoys.collectAsState()
    val trailsVersion by graph.trails.version.collectAsState()
    val me = rememberMyId(graph)

    var handle by remember { mutableStateOf<MapHandle?>(null) }
    var selected by remember { mutableStateOf(focusUserId) }
    var styleMenu by remember { mutableStateOf(false) }
    var tilted by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var previewFix by remember { mutableStateOf<LocationSample?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var mapKey by remember { mutableIntStateOf(0) }
    var panelOpen by remember { mutableStateOf(false) }
    var askedPermission by remember { mutableStateOf(false) }
    var clickMap by remember { mutableStateOf<Any?>(null) }

    // Camera behaviour: open on my street, follow me until I pan away, never yank the map back.
    var follow by remember { mutableStateOf(focusUserId == null) }
    var userMoved by remember { mutableStateOf(focusUserId != null) }
    var centeredOnFresh by remember { mutableStateOf(focusUserId != null) }
    var cameraBusyUntil by remember { mutableLongStateOf(0L) }

    val hasLocation = remember(resumeTick) { Permissions.anyLocation(ctx) }
    val locationOn = remember(resumeTick) { Permissions.locationServicesEnabled(ctx) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedPermission = true
        resumeTick++
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_RESUME -> resumeTick++
                Lifecycle.Event.ON_STOP -> {
                    visible = false
                    saveCamera(graph, handle)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); saveCamera(graph, handle) }
    }

    // Friends' realtime feed is open only while the map is on screen (or tracking needs it).
    DisposableEffect(visible) {
        if (visible) graph.live.acquire()
        onDispose { if (visible) graph.live.release() }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }

    // My position while the tracking service is off: a foreground request only while visible.
    val battery = remember { ctx.getSystemService(BatteryManager::class.java) }
    DisposableEffect(hasLocation, status.serviceRunning, visible) {
        var src: LocationSource? = null
        if (hasLocation && !status.serviceRunning && visible) {
            src = LocationSource.best(ctx).also {
                it.start(LocationRequestSpec(Priority.HIGH_ACCURACY, 2_000, 1_000, 0f)) { s ->
                    previewFix = s
                    graph.foregroundPublisher.onFix(s, settings?.shareLive == true, battery)
                }
            }
        }
        onDispose { src?.stop() }
    }
    val myFix = if (status.serviceRunning) status.lastFix ?: previewFix else previewFix
    LaunchedEffect(myFix) { myFix?.let { graph.trails.ingestMine(it) } }

    // Last position the phone already knows: lets the map open on my street before the first new fix.
    var lastKnown by remember { mutableStateOf<LocationSample?>(null) }
    LaunchedEffect(hasLocation) { if (hasLocation) lastKnown = LocationSource.lastKnown(ctx) }

    val style = settings?.let { mapStyleNow(it.mapStyleDay, it.mapStyleNight, it.autoNightMap) } ?: MapStyle.TRAZO
    val labelColor = if (style.dark) Brand.SoftWhite.toArgb() else Brand.Graphite900.toArgb()
    val haloColor = if (style.dark) Brand.Graphite900.toArgb() else 0xFFFFFFFF.toInt()
    val pins = remember(positions, social, now) { buildPins(positions, social, me, now) }
    val hidden = settings?.hiddenTrails.orEmpty()

    // ------------------------------------------------------------------ photos for the markers
    val photos = remember { mutableStateMapOf<String, Bitmap>() }
    val wantedPhotos = remember(pins, profile) { (pins.mapNotNull { it.avatarUrl } + listOfNotNull(profile?.avatarUrl)).toSet() }
    LaunchedEffect(wantedPhotos) {
        for (url in wantedPhotos) {
            if (photos.containsKey(url)) continue
            // Coil keeps a memory and disk cache, so each photo is downloaded once.
            val req = ImageRequest.Builder(ctx).data(url).size(160).allowHardware(false).build()
            val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
            if (bmp != null) photos[url] = bmp
        }
    }

    // ------------------------------------------------------------------ own live line
    var myTripLine by remember { mutableStateOf<List<List<LatLng>>>(emptyList()) }
    LaunchedEffect(status.recordingTripId) {
        val id = status.recordingTripId
        if (id == null) { myTripLine = emptyList(); return@LaunchedEffect }
        graph.trips.observePoints(id).conflate().collect { pts ->
            myTripLine = tripSegments(pts, graph.trails.clearedBefore(null))
            delay(1_500) // a redraw every ~2 s is plenty for a line; saves battery
        }
    }

    // ------------------------------------------------------------------ animation + rendering
    val motion = remember { MarkerMotion() }
    val lastBearing = remember { HashMap<String, Double>() }
    var renderTick by remember { mutableIntStateOf(0) }
    val myColor = Brand.Volt.toArgb()

    LaunchedEffect(pins, myFix) {
        val t = System.currentTimeMillis()
        pins.forEach { p ->
            val b = p.bearingDeg?.toDouble()?.takeIf { p.freshness == Freshness.LIVE }
            b?.let { lastBearing[p.userId] = it }
            motion.setTarget(p.userId, p.position, b ?: lastBearing[p.userId], t)
        }
        myFix?.let { f ->
            val b = f.reliableBearingDeg()?.toDouble()
            b?.let { lastBearing[ME] = it }
            motion.setTarget(ME, LatLng(f.lat, f.lng), b ?: lastBearing[ME], t)
        }
        motion.retain(pins.map { it.userId }.toSet() + ME)
        renderTick++
    }

    fun render(h: MapHandle) {
        val t = System.currentTimeMillis()
        val d = h.density
        val friendPeople = pins.mapNotNull { p ->
            val pose = motion.pose(p.userId, t) ?: return@mapNotNull null
            val fresh = freshKey(p.freshness)
            val photo = p.avatarUrl?.let { photos[it] }
            val ring = personColor(p.userId).toArgb()
            val pinName = PeopleLayer.pinImageName(p.userId, if (photo != null) p.avatarUrl else null, ring, fresh)
            PeopleLayer.addImage(h.style, pinName) { PeopleLayer.pinBitmap(photo, p.name, ring, fresh, d) }
            val body = BodyType.from(p.vehicleBody ?: "sedan")
            val carColor = p.vehicleColor ?: DEFAULT_CAR
            val carName = PeopleLayer.carImageName(body, carColor)
            PeopleLayer.addImage(h.style, carName) { PeopleLayer.carBitmap(body, carColor, d) }
            PeopleLayer.Person(p.userId, pose.position, pose.bearingDeg, pinName, carName, p.name.split(" ").first(), fresh, p.userId == selected)
        }
        PeopleLayer.setPeople(h.style, PeopleLayer.FRIENDS, friendPeople)

        val mePose = motion.pose(ME, t)
        val fix = myFix
        if (mePose != null && fix != null) {
            val photo = profile?.avatarUrl?.let { photos[it] }
            val fresh = if (now - fix.timeMs > LocationFreshness.LIVE_MS) "recent" else "live"
            val pinName = PeopleLayer.pinImageName(ME, if (photo != null) profile?.avatarUrl else null, myColor, fresh)
            PeopleLayer.addImage(h.style, pinName) { PeopleLayer.pinBitmap(photo, profile?.displayName ?: "Tú", myColor, fresh, d) }
            val v = profile?.activeVehicleId?.let { social.vehicles[it] }
            val body = BodyType.from(v?.bodyType ?: "sedan")
            val carColor = v?.color ?: "#B8FF3C"
            val carName = PeopleLayer.carImageName(body, carColor)
            PeopleLayer.addImage(h.style, carName) { PeopleLayer.carBitmap(body, carColor, d) }
            PeopleLayer.setPeople(h.style, PeopleLayer.ME, listOf(PeopleLayer.Person(ME, mePose.position, mePose.bearingDeg, pinName, carName, "Tú", fresh, false)))
            PeopleLayer.setAccuracy(h.style, mePose.position, fix.accuracyM?.takeIf { it > 8f })
            if (follow && t > cameraBusyUntil) h.jumpTo(mePose.position)
        } else {
            PeopleLayer.setPeople(h.style, PeopleLayer.ME, emptyList())
            PeopleLayer.setAccuracy(h.style, null, null)
        }
    }

    val renderLatest = rememberUpdatedState<(MapHandle) -> Unit> { h -> render(h) }
    LaunchedEffect(handle, renderTick, photos.size, selected) {
        val h = handle ?: return@LaunchedEffect
        var last = 0L
        do {
            val frame = withFrameMillis { it }
            if (frame - last >= 33) { renderLatest.value(h); last = frame } // ~30 fps is smooth and half the work of 60
        } while (motion.isAnimating(System.currentTimeMillis()))
        renderLatest.value(h)
    }

    // Lines: redrawn only when a point is added, never every frame.
    LaunchedEffect(handle, trailsVersion, myTripLine, pins.map { it.userId }, hidden) {
        val h = handle ?: return@LaunchedEffect
        val friendTrails = pins.filter { it.userId !in hidden && it.freshness != Freshness.PAUSED }.map {
            PeopleLayer.Trail(it.userId, personColor(it.userId).toArgb(), graph.trails.segments(it.userId), mine = false)
        }
        val mine = if (ME in hidden) emptyList() else listOf(
            PeopleLayer.Trail(ME, myColor, if (status.recordingTripId != null) myTripLine else graph.trails.mySegments(), mine = true),
        )
        PeopleLayer.setTrails(h.style, friendTrails + mine)
    }

    // ------------------------------------------------------------------ camera
    fun recenterOn(fix: LocationSample, h: MapHandle, firstTime: Boolean) {
        val zoom = if (firstTime) CameraPolicy.zoomFor(fix.accuracyM) else maxOf(h.zoom, 16.5)
        h.moveTo(LatLng(fix.lat, fix.lng), zoom = zoom, animateMs = 900)
        cameraBusyUntil = System.currentTimeMillis() + 950
    }
    LaunchedEffect(handle, lastKnown) {
        val h = handle ?: return@LaunchedEffect
        val lk = lastKnown ?: return@LaunchedEffect
        // Only a hint: jump there instantly, the first fresh fix will refine it.
        if (!userMoved && !centeredOnFresh && myFix == null) {
            h.moveTo(LatLng(lk.lat, lk.lng), zoom = CameraPolicy.zoomFor(lk.accuracyM), animateMs = 0)
        }
    }
    LaunchedEffect(handle, myFix) {
        val h = handle ?: return@LaunchedEffect
        val f = myFix ?: return@LaunchedEffect
        if (!centeredOnFresh && CameraPolicy.shouldRecenter(follow, centeredOnFresh, userMoved)) {
            recenterOn(f, h, firstTime = true)
            centeredOnFresh = true
            follow = true
        }
    }
    LaunchedEffect(handle, focusUserId, pins.isNotEmpty()) {
        val h = handle ?: return@LaunchedEffect
        val p = pins.firstOrNull { it.userId == focusUserId } ?: return@LaunchedEffect
        follow = false
        h.moveTo(p.position, zoom = 16.0)
    }

    Box(Modifier.fillMaxSize().background(Brand.Graphite900)) {
        val s0 = settings
        if (s0 != null) {
            val initial = remember(mapKey) {
                val lf = status.lastFix
                CameraPolicy.initial(lf?.let { LatLng(it.lat, it.lng) }, lf?.accuracyM,
                    s0.lastCamera?.let { CameraTarget(LatLng(it.lat.coerceIn(-85.0, 85.0), it.lng.coerceIn(-180.0, 180.0)), it.zoom) })
                    ?.let { CameraPosition.Builder().target(it.center.toML()).zoom(it.zoom).build() }
            }
            androidx.compose.runtime.key(mapKey) {
                TrazoMapView(
                    styleUrl = style.url,
                    modifier = Modifier.fillMaxSize(),
                    initialCamera = initial,
                    attributionTopDp = 118,
                    onCameraMovedByUser = { follow = false; userMoved = true },
                    onLoadFailed = { mapError = it },
                ) { h ->
                    mapError = null
                    PeopleLayer.ensure(h.style, labelColor, haloColor, Brand.Volt.toArgb())
                    if (clickMap !== h.map) h.map.addOnMapClickListener { p ->
                        val pt = h.map.projection.toScreenLocation(p)
                        val f = h.map.queryRenderedFeatures(pt, *PeopleLayer.tappable).firstOrNull()
                        when {
                            f == null -> { selected = null; false }
                            f.hasProperty("point_count") -> {
                                follow = false; userMoved = true
                                h.moveTo(LatLng(p.latitude, p.longitude), zoom = h.zoom + 2.2)
                                true
                            }
                            else -> {
                                val uid = f.getStringProperty("uid")
                                selected = if (uid == ME) null else uid
                                true
                            }
                        }
                    }
                    clickMap = h.map
                    handle = h
                    renderTick++
                }
            }
        }

        // ---------------------------------------------------------------- top
        Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandStatusBar(status.serviceRunning, status.detectorState, myFix, status.lastFixReceivedMs, now, hasLocation, link)
                Spacer(Modifier.weight(1f))
                val requests = social.requests.count { it.incoming } + social.convoyInvites.size
                RoundButton(onClick = { nav.navigate("friends") { launchSingleTop = true } }) {
                    if (requests > 0) BadgedBox(badge = { Badge { Text("$requests") } }) { Icon(Icons.Filled.Group, "Amigos") }
                    else Icon(Icons.Filled.Group, "Amigos")
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(CircleShape).clickable { nav.navigate("profile") }) {
                    Avatar(profile?.displayName ?: "?", profile?.avatarUrl, 42.dp, ring = Brand.Volt)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ghost = serverSettings?.ghostMode == true
                val sharing = settings?.shareLive == true && !ghost
                Pill(
                    when { ghost -> "Modo fantasma"; sharing -> "Compartiendo"; else -> "Ubicación en pausa" },
                    icon = if (sharing) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    color = if (sharing) Brand.Volt.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                    onClick = {
                        scope.launch {
                            if (ghost) graph.profile.updateSettings(ghostMode = false)
                            else graph.settings.setShareLive(!(settings?.shareLive ?: false))
                            graph.tracking.reconfigure()
                        }
                    },
                )
                val activeConvoy = convoys.firstOrNull { it.active }
                if (activeConvoy != null) Pill("Convoy: ${activeConvoy.convoy.name}", onClick = { nav.navigate("convoy/${activeConvoy.convoy.id}") })
            }
            AnimatedVisibility(link == LinkState.OFFLINE, enter = fadeIn(), exit = fadeOut()) {
                Pill("Sin conexión · mostrando las últimas posiciones conocidas", Modifier.padding(top = 8.dp),
                    icon = Icons.Outlined.CloudOff, color = Brand.Amber.copy(alpha = 0.22f))
            }
            mapError?.let { err ->
                Spacer(Modifier.height(8.dp))
                InfoCard(Icons.Outlined.CloudOff, "No se pudo cargar el mapa",
                    if (err.contains("connect", true) || err.contains("host", true) || err.isBlank()) "Comprueba tu conexión a Internet." else err) {
                    TextButton({ mapError = null; handle = null; mapKey++ }) { Text("Reintentar") }
                }
            }
            if (!hasLocation) {
                Spacer(Modifier.height(8.dp))
                InfoCard(Icons.Outlined.LocationOff, "Activa la ubicación para verte en el mapa",
                    if (!askedPermission) "Trazo la usa para centrar el mapa en tu calle y, si quieres, compartirla con tus amigos."
                    else "Si no aparece el aviso, ve a Ajustes → Aplicaciones → Trazo → Permisos → Ubicación y elige «Permitir mientras se usa la app». Puedes seguir explorando el mapa.") {
                    if (!askedPermission) TextButton({
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Permitir") }
                    else TextButton({ ctx.startActivity(Permissions.appSettingsIntent(ctx)) }) { Text("Ajustes") }
                }
            } else if (!locationOn) {
                Spacer(Modifier.height(8.dp))
                InfoCard(Icons.Outlined.LocationOff, "La ubicación del teléfono está apagada",
                    "Enciéndela en los ajustes rápidos para que Trazo pueda encontrarte.") {
                    TextButton({ ctx.startActivity(Permissions.locationSettingsIntent()) }) { Text("Activar") }
                }
            } else if (myFix == null) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Buscando tu ubicación…", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            status.message?.let {
                Spacer(Modifier.height(8.dp))
                Pill(it, color = Brand.Amber.copy(alpha = 0.2f), onClick = { graph.tracking.update { s -> s.copy(message = null) } })
            }
        }

        // ---------------------------------------------------------------- bottom stack
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                    Box {
                        RoundButton(onClick = { styleMenu = true }) { Icon(Icons.Filled.Layers, "Estilo del mapa") }
                        DropdownMenu(styleMenu, { styleMenu = false }) {
                            MapStyle.entries.forEach { ms ->
                                DropdownMenuItem(text = { Text(ms.label + if (ms == style) "  ✓" else "") }, onClick = {
                                    styleMenu = false
                                    s0?.let { scope.launch { graph.settings.setMapStyles(day = ms, night = if (ms.dark) ms else it.mapStyleNight, autoNight = false) } }
                                })
                            }
                            DropdownMenuItem(text = { Text("Claro de día, oscuro de noche") }, onClick = {
                                styleMenu = false
                                scope.launch { graph.settings.setMapStyles(MapStyle.LIBERTY, MapStyle.TRAZO, autoNight = true) }
                            })
                        }
                    }
                    RoundButton(onClick = { tilted = !tilted; handle?.setTilt(if (tilted) 60.0 else 0.0) }) {
                        Icon(Icons.Filled.Landscape, if (tilted) "Vista plana" else "Vista 3D", tint = if (tilted) Brand.Volt else MaterialTheme.colorScheme.onSurface)
                    }
                    RoundButton(
                        big = true,
                        container = if (follow) Brand.Volt else MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = {
                            val f = myFix
                            when {
                                !hasLocation -> permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                f == null -> toast(ctx, "Buscando tu ubicación…")
                                else -> handle?.let { h -> follow = true; userMoved = false; centeredOnFresh = true; recenterOn(f, h, firstTime = false) }
                            }
                        },
                    ) {
                        Icon(if (follow) Icons.Filled.Navigation else Icons.Filled.MyLocation, "Centrar en mí",
                            tint = if (follow) Brand.Graphite900 else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val sel = pins.firstOrNull { it.userId == selected }
            AnimatedVisibility(sel != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                if (sel != null) FriendCard(
                    sel, now, trailShown = sel.userId !in hidden,
                    onClose = { selected = null },
                    onOpen = { nav.navigate("friend/${sel.userId}") },
                    onToggleTrail = { scope.launch { graph.settings.setTrailHidden(sel.userId, sel.userId !in hidden) } },
                    onCenter = { follow = false; userMoved = true; handle?.moveTo(sel.position, zoom = maxOf(handle?.zoom ?: 16.0, 16.0)) },
                )
            }
            if (sel != null) Spacer(Modifier.height(10.dp))
            if (status.recordingTripId != null) { RecordingBar(graph, status, now); Spacer(Modifier.height(10.dp)) }
            PeoplePanel(
                pins = pins, now = now, open = panelOpen, onToggle = { panelOpen = !panelOpen },
                hidden = hidden, recording = status.recordingTripId != null, hasPerm = hasLocation, myLineShown = ME !in hidden,
                onSelect = { p -> selected = p.userId; follow = false; userMoved = true; handle?.moveTo(p.position, zoom = maxOf(handle?.zoom ?: 16.0, 15.5)) },
                onToggleTrail = { id -> scope.launch { graph.settings.setTrailHidden(id, id !in hidden) } },
                onClearMine = { graph.trails.clear(null); renderTick++ },
                onStartTrip = { graph.tracking.startTrip() },
                onAddFriends = { nav.navigate("friends") { launchSingleTop = true } },
            )
        }
    }
}

private fun saveCamera(graph: AppGraph, h: MapHandle?) {
    val t = h?.target ?: return
    val z = h.zoom
    graph.appScope.launch { graph.settings.setLastCamera(SavedCamera(t.lat, t.lng, z)) }
}

private fun freshKey(f: Freshness) = when (f) {
    Freshness.LIVE -> "live"; Freshness.RECENT -> "recent"; Freshness.PAUSED -> "paused"; else -> "stale"
}

/** Accepted points of the trip being recorded, split where the recorder marked a new segment. */
private fun tripSegments(points: List<PointEntity>, clearedBeforeMs: Long): List<List<LatLng>> {
    val out = ArrayList<ArrayList<LatLng>>()
    var lastT = Long.MIN_VALUE
    for (p in points) {
        if (p.flag != 0 || p.timeMs <= clearedBeforeMs) continue
        if (out.isEmpty() || p.segmentStart || p.timeMs - lastT > 120_000) out += ArrayList<LatLng>()
        out.last() += LatLng(p.lat, p.lng)
        lastT = p.timeMs
    }
    return out
}

private fun buildPins(positions: Map<String, LiveLocationDto>, social: SocialSnapshot, me: String?, now: Long): List<FriendPin> =
    positions.values.mapNotNull { l ->
        if (l.userId == me) return@mapNotNull null
        val lat = l.lat ?: return@mapNotNull null
        val lng = l.lng ?: return@mapNotNull null
        val profile = social.people[l.userId] ?: return@mapNotNull null
        // recorded_at is when the phone measured the fix; updated_at only when it reached the server.
        val t = isoToMillis(l.recordedAt) ?: isoToMillis(l.updatedAt) ?: return@mapNotNull null
        val fresh = LocationFreshness.classify(t, now, paused = !l.sharing || l.state == "paused")
        if (fresh == Freshness.HIDDEN) return@mapNotNull null
        val vehicle = (l.vehicleId ?: profile.activeVehicleId)?.let { social.vehicles[it] }
        FriendPin(
            l.userId, profile.displayName, profile.avatarUrl, LatLng(lat, lng), l.accuracyM, l.speedMps, l.bearingDeg,
            fresh, t, l.state == "driving", vehicle?.name, vehicle?.bodyType, vehicle?.color,
        )
    }.sortedWith(compareBy<FriendPin> { it.freshness.ordinal }.thenBy { it.name.lowercase() })

// ---------------------------------------------------------------------- pieces

@Composable
private fun RoundButton(
    onClick: () -> Unit,
    big: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick, shape = CircleShape, color = container, shadowElevation = 6.dp,
        modifier = Modifier.size(if (big) 56.dp else 46.dp).border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape),
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

@Composable
private fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Brand.Amber)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
        }
    }
}

/** Compact top bar: the Trazo mark plus GPS and connection state in one pill. */
@Composable
private fun BrandStatusBar(
    running: Boolean, state: DetectorState, fix: LocationSample?, receivedMs: Long?, now: Long, hasPerm: Boolean, link: LinkState,
) {
    val c = TrazoTheme.colors
    val quality = SignalQuality.fromAccuracy(fix?.accuracyM)
    val old = receivedMs != null && now - receivedMs > 30_000
    val (color, text) = when {
        !hasPerm -> c.stale to "Sin permiso"
        fix == null -> c.stale to "Buscando GPS"
        old -> c.recent to "Sin señal · ${LocationFreshness.ageLabel(receivedMs!!, now)}"
        else -> when (quality) {
            SignalQuality.EXCELLENT, SignalQuality.GOOD -> c.live
            SignalQuality.FAIR -> c.recent
            else -> Brand.Coral
        } to Format.accuracy(fix.accuracyM)
    }
    val stateText = when {
        !running -> null
        state == DetectorState.DRIVING -> "Conduciendo"
        state == DetectorState.STOPPED -> "Detenido"
        state == DetectorState.ARMED -> "Detectando"
        else -> null
    }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f), shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("trazo", style = MaterialTheme.typography.titleMedium, color = Brand.Volt)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(width = 1.dp, height = 16.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.GpsNotFixed, null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (stateText != null) Text(" · $stateText", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            PulseDot(when (link) { LinkState.LIVE -> c.live; LinkState.CONNECTING -> c.recent; LinkState.OFFLINE -> Brand.Coral }, pulsing = link == LinkState.LIVE, size = 7.dp)
        }
    }
}

@Composable
private fun FriendCard(
    p: FriendPin, now: Long, trailShown: Boolean,
    onClose: () -> Unit, onOpen: () -> Unit, onToggleTrail: () -> Unit, onCenter: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable(onClick = onOpen)) {
                    Avatar(p.name, p.avatarUrl, 54.dp, ring = if (p.freshness == Freshness.LIVE) personColor(p.userId) else TrazoTheme.colors.stale)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable(onClick = onCenter)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FreshnessBadge(p.freshness, LocationFreshness.ageLabel(p.updatedAtMs, now))
                    val parts = buildList {
                        if (p.freshness == Freshness.LIVE && p.driving) add(p.speedMps?.let { Format.speed(it.toDouble()) } ?: "conduciendo")
                        else if (p.freshness == Freshness.PAUSED) add("ha pausado su ubicación")
                        else if (p.freshness != Freshness.LIVE) add("última posición conocida · ${Format.time(p.updatedAtMs)}")
                        add(Format.accuracy(p.accuracyM))
                        p.vehicleName?.let { add(it) }
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                IconButton(onClose) { Icon(Icons.Filled.Close, "Cerrar") }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onOpen, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Person, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Perfil")
                }
                OutlinedButton(onOpen, Modifier.weight(1f)) {
                    Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Viajes")
                }
                OutlinedButton(onToggleTrail, Modifier.weight(1f)) {
                    Icon(if (trailShown) Icons.Filled.Route else Icons.Outlined.Route, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (trailShown) "Ocultar" else "Trazo")
                }
            }
        }
    }
}

@Composable
private fun RecordingBar(graph: AppGraph, status: app.trazo.tracking.TrackingStatus, now: Long) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(Brand.Coral, pulsing = true, size = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (status.simulation) "Simulación en curso" else if (status.detectorState == DetectorState.STOPPED) "Viaje en pausa (parado)" else "Grabando viaje",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text("${Format.distance(status.tripDistanceM)} · ${Format.duration(now - (status.tripStartMs ?: now))}", style = MaterialTheme.typography.titleMedium)
            }
            Button(onClick = { graph.tracking.stopTrip() }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Brand.Coral, contentColor = Brand.Graphite900)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(4.dp)); Text("Terminar")
            }
        }
    }
}

/** Bottom panel with the people on the map; collapsed it is one row of faces. */
@Composable
private fun PeoplePanel(
    pins: List<FriendPin>, now: Long, open: Boolean, onToggle: () -> Unit, hidden: Set<String>,
    recording: Boolean, hasPerm: Boolean, myLineShown: Boolean,
    onSelect: (FriendPin) -> Unit, onToggleTrail: (String) -> Unit, onClearMine: () -> Unit,
    onStartTrip: () -> Unit, onAddFriends: () -> Unit,
) {
    val liveCount = pins.count { it.freshness == Freshness.LIVE }
    Surface(
        shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (pins.isEmpty()) "Tu mapa" else "Amigos en el mapa", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            pins.isEmpty() -> "Nadie comparte su ubicación contigo ahora mismo"
                            liveCount > 0 -> "$liveCount en directo · ${pins.size} visibles"
                            else -> "${pins.size} con última posición conocida"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!recording && hasPerm) {
                    FilledTonalButton(onStartTrip, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Viaje")
                    }
                }
                Icon(if (open) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp, if (open) "Plegar" else "Desplegar",
                    Modifier.padding(start = 6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pins.isEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onAddFriends) { Icon(Icons.Outlined.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Añadir amigos") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClearMine) { Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Limpiar mi línea") }
                }
            } else if (!open) {
                LazyRow(Modifier.padding(top = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pins, key = { it.userId }) { p ->
                        Column(Modifier.width(64.dp).clip(RoundedCornerShape(14.dp)).clickable { onSelect(p) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(p.name, p.avatarUrl, 46.dp, ring = ringFor(p))
                            Spacer(Modifier.height(4.dp))
                            Text(p.name.split(" ").first(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (p.freshness == Freshness.LIVE) "en directo" else LocationFreshness.ageLabel(p.updatedAtMs, now),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 4.dp)) {
                    item(key = "me") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Brand.Volt))
                            Spacer(Modifier.width(12.dp))
                            Text(if (recording) "Mi recorrido (viaje en curso)" else "Mi recorrido de la última hora", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton({ onToggleTrail(ME) }) { Icon(if (myLineShown) Icons.Filled.Route else Icons.Outlined.Route, if (myLineShown) "Ocultar mi línea" else "Mostrar mi línea") }
                            IconButton(onClearMine) { Icon(Icons.Outlined.CleaningServices, "Empezar la línea de nuevo") }
                        }
                    }
                    items(pins, key = { it.userId }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(p) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.name, p.avatarUrl, 42.dp, ring = ringFor(p))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                FreshnessBadge(p.freshness, LocationFreshness.ageLabel(p.updatedAtMs, now))
                            }
                            if (p.freshness == Freshness.LIVE && p.driving) {
                                Text(p.speedMps?.let { Format.speed(it.toDouble()) } ?: "en ruta", style = MaterialTheme.typography.labelLarge, color = Brand.Live)
                            }
                            val shown = p.userId !in hidden
                            IconButton({ onToggleTrail(p.userId) }) {
                                Icon(if (shown) Icons.Filled.Route else Icons.Outlined.Route, if (shown) "Ocultar su línea" else "Mostrar su línea",
                                    tint = if (shown) personColor(p.userId) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ringFor(p: FriendPin): Color = when (p.freshness) {
    Freshness.LIVE -> personColor(p.userId)
    Freshness.RECENT -> TrazoTheme.colors.recent
    else -> TrazoTheme.colors.stale
}
