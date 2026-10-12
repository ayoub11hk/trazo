package app.trazo.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.core.stats.Period
import app.trazo.core.stats.PeriodRange
import app.trazo.ui.components.EmptyState
import app.trazo.ui.components.LoadingState
import app.trazo.ui.components.Pill
import app.trazo.util.Format
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@Composable
fun TripsScreen(graph: AppGraph, nav: NavController) {
    val trips by graph.trips.observeTrips().collectAsState(initial = null)
    val vehicles by graph.vehicles.observe().collectAsState(initial = emptyList())
    val pending by graph.trips.observePendingUploads().collectAsState(initial = 0)
    var query by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(Period.ALL) }
    var vehicleId by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    val filtered = remember(trips, query, period, vehicleId, vehicles) {
        val range = if (period == Period.ALL) null else PeriodRange.containing(period, LocalDate.now(zone))
        val q = fold(query.trim())
        val names = vehicles.associate { it.id to it.name }
        trips.orEmpty().filter { t ->
            val day = Instant.ofEpochMilli(t.startedAt).atZone(zone).toLocalDate()
            (range == null || range.contains(day)) &&
                (vehicleId == null || t.vehicleId == vehicleId) &&
                (q.isEmpty() || fold(listOfNotNull(t.title, Format.day(t.startedAt), t.vehicleId?.let { names[it] }).joinToString(" ")).contains(q))
        }
    }

    Scaffold(topBar = {
        TabTopBar("Viajes") {
            IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "Buscar") }
            IconButton({ nav.navigate("heatmap") }) { Icon(Icons.Filled.Whatshot, "Mapa de calor") }
        }
    }) { pad ->
        val list = trips
        if (list == null) { LoadingState(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                if (searching) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Nombre, fecha («12 de octubre») o vehículo") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) })
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Period.ALL to "Todo", Period.WEEK to "Esta semana", Period.MONTH to "Este mes", Period.YEAR to "Este año").forEach { (p, l) ->
                        FilterChip(period == p, { period = p }, label = { Text(l) })
                    }
                    if (vehicles.size > 1) {
                        FilterChip(vehicleId == null, { vehicleId = null }, label = { Text("Todos los vehículos") })
                        vehicles.forEach { v -> FilterChip(vehicleId == v.id, { vehicleId = if (vehicleId == v.id) null else v.id }, label = { Text(v.name) }) }
                    }
                }
                if (pending > 0) {
                    Spacer(Modifier.height(8.dp))
                    Pill("$pending puntos pendientes de subir (se enviarán con conexión)", icon = Icons.Outlined.CloudUpload)
                }
                if (filtered.isNotEmpty()) {
                    val total = filtered.sumOf { it.distanceM }
                    Text(
                        "${filtered.size} viajes · ${Format.distance(total)}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            if (filtered.isEmpty()) item {
                if (list.isEmpty()) EmptyState(Icons.Outlined.Route, "Aún no hay viajes",
                    "Conduce con Trazo abierto o en segundo plano y tus recorridos aparecerán aquí. También puedes iniciar uno a mano desde el mapa.")
                else EmptyState(Icons.Filled.Search, "Nada coincide", "Prueba con otro filtro o búsqueda.")
            }
            val names = vehicles.associate { it.id to it.name }
            items(filtered, key = { it.id }) { t -> TripRow(t, t.vehicleId?.let { names[it] }) { nav.navigate("trip/${t.id}") } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
