package app.trazo.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.core.social.Freshness
import app.trazo.core.social.LocationFreshness
import app.trazo.data.local.TripEntity
import app.trazo.data.remote.isoToMillis
import app.trazo.ui.components.Avatar
import app.trazo.ui.components.BodyType
import app.trazo.ui.components.ErrorBanner
import app.trazo.ui.components.FreshnessBadge
import app.trazo.ui.components.LoadingState
import app.trazo.ui.components.SectionHeader
import app.trazo.ui.components.SettingCard
import app.trazo.ui.components.VehicleArt
import app.trazo.ui.components.parseHex
import app.trazo.util.userMessage

@Composable
fun FriendProfileScreen(graph: AppGraph, nav: NavController, friendId: String) {
    val social by graph.social.state.collectAsState()
    val positions by graph.live.positions.collectAsState()
    val runner = rememberRunner()
    val friend = social.friends.firstOrNull { it.profile.id == friendId }
    val profile = friend?.profile ?: social.people[friendId]
    var trips by remember { mutableStateOf<List<TripEntity>?>(null) }
    var tripsError by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val sharesTrips = friend?.theirs?.shareTrips == true

    LaunchedEffect(friendId, sharesTrips) {
        tripsError = null
        if (!sharesTrips) { trips = emptyList(); return@LaunchedEffect }
        runCatching { graph.trips.friendTrips(friendId) }
            .onSuccess { trips = it }.onFailure { tripsError = it.userMessage(); trips = emptyList() }
    }

    Scaffold(topBar = { BackTopBar(profile?.displayName ?: "Amigo", nav) }) { pad ->
        if (profile == null) { LoadingState(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                runner.error?.let { ErrorBanner(it) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Avatar(profile.displayName, profile.avatarUrl, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName, style = MaterialTheme.typography.headlineSmall)
                        val live = positions[friendId]
                        val t = live?.let { isoToMillis(it.recordedAt) ?: isoToMillis(it.updatedAt) }
                        val now = System.currentTimeMillis()
                        val fresh = if (live?.lat == null) Freshness.HIDDEN
                        else LocationFreshness.classify(t, now, !live.sharing || live.state == "paused")
                        FreshnessBadge(fresh, t?.let { LocationFreshness.ageLabel(it, now) } ?: "no comparte su ubicación contigo")
                    }
                }
                Button({ nav.navigate("map?focus=$friendId") }, Modifier.fillMaxWidth(), enabled = positions[friendId]?.lat != null) {
                    Icon(Icons.Filled.Map, null); Spacer(Modifier.width(8.dp)); Text("Ver en el mapa")
                }
                val v = friend?.vehicle ?: profile.activeVehicleId?.let { social.vehicles[it] }
                if (v != null) {
                    SectionHeader("Conduce")
                    SettingCard {
                        Column(Modifier.padding(16.dp)) {
                            VehicleArt(BodyType.from(v.bodyType), parseHex(v.color), Modifier.fillMaxWidth())
                            Text(v.name, style = MaterialTheme.typography.titleMedium)
                            listOfNotNull(v.make, v.model, v.year?.toString()).takeIf { it.isNotEmpty() }?.let {
                                Text(it.joinToString(" "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (friend != null) {
                    SectionHeader("Lo que compartes con ${profile.displayName.split(" ").first()}")
                    SettingCard {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            ToggleRow("Mi ubicación en directo", "Si lo desactivas, no te verá en el mapa.", friend.mine.shareLocation) {
                                runner.run { graph.social.setSharing(friendId, shareLocation = it) }
                            }
                            HorizontalDivider()
                            ToggleRow("Mis viajes", "Historial y recorridos que marques como visibles para amigos.", friend.mine.shareTrips) {
                                runner.run { graph.social.setSharing(friendId, shareTrips = it) }
                            }
                        }
                    }
                }
                SectionHeader("Sus viajes")
                when {
                    !sharesTrips -> Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("No comparte sus viajes contigo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    tripsError != null -> ErrorBanner(tripsError!!)
                    trips == null -> LoadingState(Modifier.height(120.dp))
                    trips!!.isEmpty() -> Text("Todavía no hay viajes visibles.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(trips.orEmpty(), key = { it.id }) { t ->
                TripRow(t, t.vehicleId?.let { social.vehicles[it]?.name }) { nav.navigate("friendtrip/$friendId/${t.id}") }
            }
            if (friend != null) item {
                Spacer(Modifier.height(24.dp))
                OutlinedButton({ confirmRemove = true }, Modifier.fillMaxWidth()) {
                    Text("Eliminar de amigos", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    if (confirmRemove) ConfirmDialog(
        "¿Eliminar a ${profile?.displayName}?",
        "Dejaréis de veros en el mapa y de ver vuestros viajes. Podréis volver a añadiros con un código.",
        "Eliminar", destructive = true, onDismiss = { confirmRemove = false },
    ) { runner.run(onDone = { nav.popBackStack() }) { graph.social.removeFriend(friendId) } }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}
