package app.trazo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import app.trazo.config.MapStyle
import app.trazo.core.geo.LatLng
import app.trazo.core.stats.Heatmap
import app.trazo.map.MapHandle
import app.trazo.map.MapLayers
import app.trazo.map.TrazoMapView
import app.trazo.ui.components.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where I drive most, from the trips stored on this phone (simulations excluded). */
@Composable
fun HeatmapScreen(graph: AppGraph, nav: NavController) {
    val settings by graph.settings.settings.collectAsState(initial = null)
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    var cells by remember { mutableStateOf<List<Heatmap.Cell>?>(null) }
    LaunchedEffect(Unit) {
        cells = withContext(Dispatchers.Default) {
            val rows = graph.db.points().heatmapSample(every = 3)
            Heatmap.bin(rows.asSequence().map { LatLng(it.lat, it.lng) })
        }
    }
    LaunchedEffect(handle, cells) {
        val h = handle ?: return@LaunchedEffect
        val c = cells ?: return@LaunchedEffect
        MapLayers.setHeatmap(h.style, "heat", c.map { it.center to it.weight })
        if (c.isNotEmpty()) h.fit(c.sortedByDescending { it.weight }.take(400).map { it.center }, animateMs = 0)
    }
    Scaffold(topBar = { BackTopBar("Mapa de calor", nav) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val style = settings?.let { mapStyleNow(it.mapStyleDay, it.mapStyleNight, it.autoNightMap) } ?: MapStyle.DARK
            TrazoMapView(style.url, Modifier.fillMaxSize()) { h ->
                MapLayers.ensureHeatmap(h.style, "heat")
                handle = h
            }
            val c = cells
            Pill(
                when {
                    c == null -> "Calculando…"
                    c.isEmpty() -> "Sin recorridos en este teléfono todavía"
                    else -> "Basado en los viajes guardados en este teléfono"
                },
                Modifier.align(Alignment.TopCenter).padding(12.dp),
            )
        }
    }
}
