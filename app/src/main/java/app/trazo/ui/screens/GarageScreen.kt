package app.trazo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.trazo.AppGraph
import app.trazo.data.local.VehicleEntity
import app.trazo.ui.components.BodyType
import app.trazo.ui.components.EmptyState
import app.trazo.ui.components.ErrorBanner
import app.trazo.ui.components.SettingCard
import app.trazo.ui.components.VehicleArt
import app.trazo.ui.components.VehicleCatalog
import app.trazo.ui.components.VehicleColors
import app.trazo.ui.components.parseHex
import app.trazo.ui.theme.Brand
import app.trazo.util.Format

@Composable
fun GarageScreen(graph: AppGraph) {
    val vehicles by graph.vehicles.observe().collectAsState(initial = null)
    val trips by graph.trips.observeTrips().collectAsState(initial = emptyList())
    val profile by graph.profile.profile.collectAsState()
    val settings by graph.settings.settings.collectAsState(initial = null)
    val runner = rememberRunner()
    var editing by remember { mutableStateOf<VehicleEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<VehicleEntity?>(null) }
    val activeId = settings?.defaultVehicleId ?: profile?.activeVehicleId

    fun activate(id: String?) = runner.run {
        graph.settings.setDefaultVehicle(id)
        graph.vehicles.sync() // the server only accepts an active vehicle that it already knows
        graph.profile.setActiveVehicle(id)
    }

    Scaffold(
        topBar = { TabTopBar("Garaje") },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Añadir vehículo") })
        },
    ) { pad ->
        val list = vehicles ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item { runner.error?.let { ErrorBanner(it, Modifier.padding(bottom = 8.dp)) } }
            if (list.isEmpty()) item {
                EmptyState(Icons.Outlined.DirectionsCar, "Tu garaje está vacío",
                    "Añade tus coches o motos, sin límite. El activo aparece en el mapa de tus amigos y se asigna a cada viaje.")
            }
            items(list, key = { it.id }) { v ->
                val ofVehicle = trips.filter { it.vehicleId == v.id && !it.simulated }
                SettingCard(Modifier.padding(vertical = 6.dp)) {
                    Column(Modifier.clickable { editing = v }.padding(16.dp)) {
                        Box {
                            VehicleArt(BodyType.from(v.bodyType), parseHex(v.color), Modifier.fillMaxWidth())
                            if (v.id == activeId) Row(
                                Modifier.align(Alignment.TopEnd).background(Brand.Volt, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = Brand.Graphite900)
                                Spacer(Modifier.width(4.dp))
                                Text("Activo", style = MaterialTheme.typography.labelMedium, color = Brand.Graphite900)
                            }
                        }
                        Text(v.name, style = MaterialTheme.typography.titleLarge)
                        val desc = listOfNotNull(v.make, v.model, v.year?.toString()).joinToString(" ")
                        Text(
                            (if (desc.isNotBlank()) "$desc · " else "") + BodyType.from(v.bodyType).label,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${ofVehicle.size} viajes · ${Format.distance(ofVehicle.sumOf { it.distanceM })} · ${Format.duration(ofVehicle.sumOf { it.movingMs })} al volante",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (v.id != activeId) Button({ activate(v.id) }) { Text("Usar este") }
                            else OutlinedButton({ activate(null) }) { Text("Dejar de usar") }
                            TextButton({ confirmDelete = v }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    if (creating || editing != null) {
        VehicleEditor(
            initial = editing,
            onDismiss = { creating = false; editing = null },
        ) { name, make, model, year, color, body ->
            val wasEmpty = vehicles.isNullOrEmpty()
            runner.run {
                val id = graph.vehicles.save(editing?.id, name, make, model, year, color, body)
                creating = false; editing = null
                graph.sync.requestSync()
                if (wasEmpty) activate(id)
            }
        }
    }
    confirmDelete?.let { v ->
        ConfirmDialog(
            "¿Eliminar ${v.name}?", "Sus viajes se conservan, pero quedarán sin vehículo asignado.", "Eliminar", destructive = true,
            onDismiss = { confirmDelete = null },
        ) {
            runner.run {
                if (v.id == activeId) { graph.settings.setDefaultVehicle(null); runCatching { graph.profile.setActiveVehicle(null) } }
                graph.vehicles.delete(v.id)
                graph.sync.requestSync()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VehicleEditor(
    initial: VehicleEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, make: String?, model: String?, year: Int?, color: String, body: String) -> Unit,
) {
    val ctx = LocalContext.current
    val catalog = remember { VehicleCatalog.load(ctx) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var make by remember { mutableStateOf(initial?.make ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var year by remember { mutableStateOf(initial?.year?.toString() ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: VehicleColors[3]) }
    var body by remember { mutableStateOf(BodyType.from(initial?.bodyType)) }
    var makeOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val makeMatches = remember(make) { catalog.makes.filter { it.name.contains(make.trim(), ignoreCase = true) }.take(8) }
    val selectedMake = catalog.makes.firstOrNull { it.name.equals(make.trim(), ignoreCase = true) }
    val modelMatches = remember(selectedMake, model) {
        selectedMake?.models?.filter { it.name.contains(model.trim(), ignoreCase = true) }?.take(10).orEmpty()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).imePadding().verticalScroll(rememberScrollState())) {
            Text(if (initial == null) "Nuevo vehículo" else "Editar vehículo", style = MaterialTheme.typography.titleLarge)
            VehicleArt(body, parseHex(color), Modifier.fillMaxWidth().padding(vertical = 8.dp))

            ExposedDropdownMenuBox(makeOpen && makeMatches.isNotEmpty(), { makeOpen = it }) {
                OutlinedTextField(make, { make = it.take(30); makeOpen = true }, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                    label = { Text("Marca") }, singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(makeOpen) })
                ExposedDropdownMenu(makeOpen && makeMatches.isNotEmpty(), { makeOpen = false }) {
                    makeMatches.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = { make = m.name; makeOpen = false; model = "" })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(modelOpen && modelMatches.isNotEmpty(), { modelOpen = it }) {
                OutlinedTextField(model, { model = it.take(40); modelOpen = true }, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                    label = { Text("Modelo") }, singleLine = true,
                    supportingText = { Text(if (selectedMake != null) "${selectedMake.models.size} modelos de ${selectedMake.name} en el catálogo" else "Catálogo: ${catalog.makes.size} marcas, ${catalog.modelCount} modelos. También puedes escribir otro.") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelOpen) })
                ExposedDropdownMenu(modelOpen && modelMatches.isNotEmpty(), { modelOpen = false }) {
                    modelMatches.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = {
                            model = m.name; modelOpen = false; body = BodyType.from(m.body)
                            if (name.isBlank()) name = "${selectedMake?.name ?: ""} ${m.name}".trim()
                        })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, Modifier.weight(2f), label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Año") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("Carrocería", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BodyType.entries.forEach { b -> FilterChip(body == b, { body = b }, label = { Text(b.label) }) }
            }
            Text("Color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VehicleColors.forEach { hex ->
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(parseHex(hex))
                            .border(if (hex == color) 3.dp else 1.dp, if (hex == color) Brand.Volt else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { color = hex },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            val y = year.toIntOrNull()
            val validYear = year.isEmpty() || (y != null && y in 1900..2100)
            Button(
                { onSave(name.trim(), make.trim().ifEmpty { null }, model.trim().ifEmpty { null }, y, color, body.name.lowercase()) },
                Modifier.fillMaxWidth().height(52.dp), enabled = name.isNotBlank() && validYear,
            ) { Text("Guardar") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
