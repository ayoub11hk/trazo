package app.trazo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.config.MapStyle
import app.trazo.core.geo.LatLng
import app.trazo.core.social.Freshness
import app.trazo.core.social.InviteCode
import app.trazo.core.social.LocationFreshness
import app.trazo.data.local.TripEntity
import app.trazo.data.remote.isoToMillis
import app.trazo.map.MapHandle
import app.trazo.map.MapLayers
import app.trazo.map.TrazoMapView
import app.trazo.map.VehicleMarkers
import app.trazo.ui.components.Avatar
import app.trazo.ui.components.BodyType
import app.trazo.ui.components.EmptyState
import app.trazo.ui.components.ErrorBanner
import app.trazo.ui.components.FreshnessBadge
import app.trazo.ui.components.Pill
import app.trazo.ui.components.SectionHeader
import app.trazo.ui.components.avatarColor
import app.trazo.ui.theme.Brand
import app.trazo.util.Format
import kotlinx.coroutines.delay

@Composable
fun ConvoysScreen(graph: AppGraph, nav: NavController) {
    val convoys by graph.convoys.convoys.collectAsState()
    val runner = rememberRunner()
    var create by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { graph.convoys.refresh() } }
    Scaffold(
        topBar = { BackTopBar("Convoyes", nav) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nuevo convoy") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                runner.error?.let { ErrorBanner(it) }
                Text(
                    "Un convoy es un viaje en grupo: mientras está activo, todos los miembros se ven en el mapa y, " +
                        "al terminar, podéis ver los recorridos de quienes los compartan.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (convoys.isEmpty()) item { EmptyState(Icons.Outlined.Groups, "Sin convoyes", "Crea uno o únete con un código desde Amigos.") }
            items(convoys, key = { it.convoy.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { nav.navigate("convoy/${c.convoy.id}") }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.convoy.name, null, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.convoy.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${c.members.count { it.leftAt == null }} miembros · " +
                                (isoToMillis(c.convoy.createdAt)?.let { Format.day(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (c.active) Pill("Activo", color = Brand.Live.copy(alpha = 0.2f)) else Pill("Terminado")
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
    if (create) TextInputDialog("Nuevo convoy", "Nombre (p. ej. Ruta a Gredos)", confirm = "Crear", onDismiss = { create = false }) { name ->
        runner.run { val r = graph.convoys.create(name); r.convoyId?.let { nav.navigate("convoy/$it") } }
    }
}

@Composable
fun ConvoyDetailScreen(graph: AppGraph, nav: NavController, convoyId: String) {
    val ctx = LocalContext.current
    val convoys by graph.convoys.convoys.collectAsState()
    val social by graph.social.state.collectAsState()
    val positions by graph.live.positions.collectAsState()
    val settings by graph.settings.settings.collectAsState(initial = null)
    val runner = rememberRunner()
    val info = convoys.firstOrNull { it.convoy.id == convoyId }
    val me = rememberMyId(graph)
    var code by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var trips by remember { mutableStateOf<List<TripEntity>>(emptyList()) }
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    androidx.compose.runtime.DisposableEffect(Unit) { graph.live.acquire(); onDispose { graph.live.release() } }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    LaunchedEffect(convoyId) {
        runCatching { graph.convoys.refresh() }
        runCatching { trips = graph.trips.convoyTrips(convoyId) }
    }
    LaunchedEffect(info?.active) { if (info?.active == true) runCatching { code = graph.convoys.code(convoyId) } }

    val members = info?.members.orEmpty().filter { it.leftAt == null }
    val pins = members.mapNotNull { m ->
        val l = positions[m.userId] ?: return@mapNotNull null
        if (l.lat == null || l.lng == null) return@mapNotNull null
        val t = isoToMillis(l.recordedAt) ?: isoToMillis(l.updatedAt)
        val fresh = LocationFreshness.classify(t, now, !l.sharing || l.state == "paused")
        Triple(m.userId, LatLng(l.lat, l.lng), fresh)
    }

    LaunchedEffect(handle, pins) {
        val h = handle ?: return@LaunchedEffect
        MapLayers.setMarkers(h.style, "convoy", pins.map { (uid, p, fresh) ->
            val profile = social.people[uid]
            val v = profile?.activeVehicleId?.let { social.vehicles[it] }
            val icon = if (v != null) VehicleMarkers.key(BodyType.from(v.bodyType), v.color) else "p-$uid"
            MapLayers.addImageIfMissing(h.style, icon) {
                if (v != null) VehicleMarkers.bitmap(BodyType.from(v.bodyType), v.color, h.density)
                else VehicleMarkers.personBitmap((profile?.displayName ?: "?").take(1).uppercase(), avatarColor(profile?.displayName ?: uid).toArgb(), h.density)
            }
            MapLayers.Marker(uid, p, icon, positions[uid]?.bearingDeg?.toDouble()?.takeIf { fresh == Freshness.LIVE },
                profile?.displayName?.split(" ")?.first() ?: "", if (fresh == Freshness.LIVE) "live" else "stale", false)
        })
    }
    LaunchedEffect(handle, pins.size) { handle?.let { h -> if (pins.isNotEmpty()) h.fit(pins.map { it.second }) } }

    Scaffold(topBar = { BackTopBar(info?.convoy?.name ?: "Convoy", nav) }) { pad ->
        if (info == null) {
            Column(Modifier.padding(pad).padding(16.dp)) { runner.error?.let { ErrorBanner(it) }; Text("Cargando convoy…") }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            if (info.active) Box(Modifier.fillMaxWidth().height(280.dp)) {
                val style = settings?.let { mapStyleNow(it.mapStyleDay, it.mapStyleNight, it.autoNightMap) } ?: MapStyle.DARK
                TrazoMapView(style.url, Modifier.fillMaxSize()) { h ->
                    MapLayers.ensureMarkers(h.style, "convoy", 0xFFFFFFFF.toInt(), Brand.Graphite900.toArgb(), Brand.Signal.toArgb(), cluster = false)
                    h.enableMyLocation(false)
                    handle = h
                }
            }
            Column(Modifier.padding(16.dp)) {
                runner.error?.let { ErrorBanner(it) }
                if (info.active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Código: ${code?.let { InviteCode.format(it) } ?: "…"}", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        TextButton({ code?.let { shareText(ctx, "Convoy de Trazo", inviteMessage(InviteCode.Kind.CONVOY, it, info.convoy.name)) } }) {
                            Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("Compartir")
                        }
                    }
                    Button({ inviting = true }, Modifier.fillMaxWidth()) { Text("Invitar amigos") }
                    val mine = info.members.firstOrNull { it.userId == me }
                    ToggleRow("Compartir mi recorrido del convoy", "Los demás miembros podrán ver tu viaje al terminar.", mine?.shareTrip ?: true) {
                        runner.run { graph.convoys.setShareTrip(convoyId, it) }
                    }
                }
                SectionHeader("Miembros (${members.size})")
                members.forEach { m ->
                    val p = social.people[m.userId]
                    val l = positions[m.userId]
                    val t = l?.let { isoToMillis(it.recordedAt) ?: isoToMillis(it.updatedAt) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p?.displayName ?: "?", p?.avatarUrl, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text((p?.displayName ?: "Miembro") + (if (m.userId == me) " (tú)" else "") + (if (m.userId == info.convoy.ownerId) " · organiza" else ""))
                            if (info.active && m.userId != me) FreshnessBadge(
                                if (l?.lat == null) Freshness.HIDDEN else LocationFreshness.classify(t, now, !l.sharing || l.state == "paused"),
                                t?.let { LocationFreshness.ageLabel(it, now) } ?: "sin posición",
                            )
                        }
                    }
                }
                if (trips.isNotEmpty()) {
                    SectionHeader("Recorridos compartidos")
                    trips.forEach { t ->
                        val owner = social.people[t.ownerId]?.displayName
                        TripRow(t.copy(title = t.title ?: owner?.let { "Viaje de $it" }), null) {
                            if (t.ownerId == me) nav.navigate("trip/${t.id}") else nav.navigate("friendtrip/${t.ownerId}/${t.id}")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (info.active) {
                    OutlinedButton({ confirm = "leave" }, Modifier.fillMaxWidth()) { Text("Salir del convoy") }
                    if (info.convoy.ownerId == me) TextButton({ confirm = "end" }, Modifier.fillMaxWidth()) {
                        Text("Terminar convoy para todos", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (inviting && info != null) {
        val inConvoy = members.map { it.userId }.toSet()
        AlertDialog(
            onDismissRequest = { inviting = false },
            title = { Text("Invitar al convoy") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val candidates = social.friends.filter { it.profile.id !in inConvoy }
                    if (candidates.isEmpty()) Text("Todos tus amigos ya están en el convoy, o aún no tienes amigos.")
                    candidates.forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(f.profile.displayName, f.profile.avatarUrl, 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(f.profile.displayName, Modifier.weight(1f))
                            TextButton({ runner.run { graph.convoys.invite(convoyId, f.profile.id); toast(ctx, "Invitación enviada a ${f.profile.displayName}") } }) {
                                Text("Invitar")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ inviting = false }) { Text("Hecho") } },
        )
    }
    when (confirm) {
        "leave" -> ConfirmDialog("¿Salir del convoy?", "Dejarás de ver a los miembros y ellos a ti.", "Salir", destructive = true,
            onDismiss = { confirm = null }) { runner.run { graph.convoys.leave(convoyId); nav.popBackStack() } }
        "end" -> ConfirmDialog("¿Terminar el convoy?", "Termina para todos. Los recorridos compartidos seguirán visibles.", "Terminar", destructive = true,
            onDismiss = { confirm = null }) { runner.run { graph.convoys.end(convoyId) } }
    }
}
