package app.trazo.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import app.trazo.AppGraph
import app.trazo.BuildConfig
import app.trazo.config.ThemeMode
import app.trazo.core.trip.Sensitivity
import app.trazo.tracking.ActivityRecognitionManager
import app.trazo.ui.components.Avatar
import app.trazo.ui.components.ErrorBanner
import app.trazo.ui.components.SectionHeader
import app.trazo.ui.components.SettingCard
import app.trazo.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ProfileScreen(graph: AppGraph, nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by graph.profile.profile.collectAsState()
    val server by graph.profile.settings.collectAsState()
    val s by graph.settings.settings.collectAsState(initial = null)
    val auth by graph.auth.state.collectAsState(initial = null)
    val pending by graph.trips.observePendingUploads().collectAsState(initial = 0)
    val status by graph.tracking.status.collectAsState()
    val runner = rememberRunner()
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { graph.profile.refresh() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runner.run {
            val jpeg = withContext(Dispatchers.IO) { compressAvatar(ctx, uri) } ?: throw IllegalStateException("No se pudo leer la imagen.")
            graph.profile.uploadAvatar(jpeg)
        }
    }

    Scaffold(topBar = { BackTopBar("Perfil y ajustes", nav) }) { pad ->
        val st = s ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            runner.error?.let { ErrorBanner(it) }
            // ---------------------------------------------------------------- identity
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Box(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Avatar(profile?.displayName ?: "?", profile?.avatarUrl, 76.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile?.displayName ?: "…", style = MaterialTheme.typography.headlineSmall)
                    Text((auth as? app.trazo.data.repo.AuthState.SignedIn)?.email ?: "", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton({ dialog = "name" }) { Text("Cambiar nombre") }
                        TextButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Foto") }
                    }
                }
            }

            // ---------------------------------------------------------------- privacy
            SectionHeader("Privacidad")
            SettingCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ToggleRow("Modo fantasma", "Nadie te ve en el mapa, ni siquiera tu última posición. Se aplica en el servidor.",
                        server?.ghostMode == true, enabled = server != null) {
                        runner.run { graph.profile.updateSettings(ghostMode = it); graph.tracking.reconfigure() }
                    }
                    HorizontalDivider()
                    ToggleRow("Compartir ubicación en directo", "Con los amigos, grupos y convoyes que lo tengan permitido.", st.shareLive) {
                        scope.launch { graph.settings.setShareLive(it); graph.tracking.reconfigure() }
                    }
                    HorizontalDivider()
                    ToggleRow("Mostrar mi velocidad", "Si lo desactivas, el servidor la elimina antes de que nadie la vea.",
                        server?.shareSpeed != false, enabled = server != null) { runner.run { graph.profile.updateSettings(shareSpeed = it) } }
                    HorizontalDivider()
                    ToggleRow("Aparecer en clasificaciones", "Tus amigos verán tus totales (nunca velocidades).",
                        server?.statsVisible != false, enabled = server != null) { runner.run { graph.profile.updateSettings(statsVisible = it) } }
                    HorizontalDivider()
                    ToggleRow("Viajes nuevos visibles para amigos", "Puedes cambiarlo en cada viaje.",
                        server?.defaultTripVisibility != "private", enabled = server != null) {
                        runner.run { graph.profile.updateSettings(defaultTripVisibility = if (it) "friends" else "private") }
                    }
                    HorizontalDivider()
                    Text("Conservar mis viajes en el servidor", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf<Pair<Int?, String>>(null to "Siempre", 30 to "30 días", 90 to "90 días", 180 to "6 meses", 365 to "1 año").forEach { (d, l) ->
                            FilterChip(server?.retentionDays == d, {
                                runner.run { if (d == null) graph.profile.updateSettings(clearRetention = true) else graph.profile.updateSettings(retentionDays = d) }
                            }, label = { Text(l) })
                        }
                    }
                }
            }
            Text("Cada amigo, grupo y convoy tiene sus propios permisos en su ficha.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            // ---------------------------------------------------------------- tracking
            SectionHeader("Registro de viajes")
            SettingCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ToggleRow("Detectar viajes automáticamente", "Usa el reconocimiento de actividad de Android para empezar y terminar solo.", st.autoDetectTrips) { v ->
                        scope.launch {
                            graph.settings.setAutoDetect(v)
                            if (v && Permissions.activityRecognition(ctx)) ActivityRecognitionManager.register(ctx) else ActivityRecognitionManager.unregister(ctx)
                            graph.tracking.reconfigure()
                        }
                    }
                    HorizontalDivider()
                    Text("Sensibilidad de detección", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Sensitivity.LOW to "Baja", Sensitivity.NORMAL to "Normal", Sensitivity.HIGH to "Alta").forEach { (v, l) ->
                            FilterChip(st.sensitivity == v, { scope.launch { graph.settings.setSensitivity(v); graph.tracking.reconfigure() } }, label = { Text(l) })
                        }
                    }
                    Text("Alta empieza antes y aguanta más parado (atascos); baja evita viajes cortos falsos.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    ToggleRow("Vigilancia continua", "Mantiene el GPS en bajo consumo aunque Android no detecte movimiento. Gasta más batería.", st.continuousMonitoring) { v ->
                        scope.launch {
                            graph.settings.setContinuous(v)
                            if (v) graph.tracking.ensureServiceRunning("continuous") else graph.tracking.reconfigure()
                        }
                    }
                    HorizontalDivider()
                    var interval by remember(st.liveIntervalSec) { mutableFloatStateOf(st.liveIntervalSec.toFloat()) }
                    Text("Actualizar mi posición en directo cada ${interval.toInt()} s", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 10.dp))
                    Slider(interval, { interval = it }, valueRange = 1f..30f, steps = 28,
                        onValueChangeFinished = { scope.launch { graph.settings.setLiveInterval(interval.toInt()); graph.tracking.reconfigure() } })
                    Text("Más frecuente = más fluido para tus amigos y más datos móviles. El GPS registra igual de preciso en cualquier caso.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    ToggleRow("Ajustar recorridos a carreteras", "Muestra además una versión ajustada (servicio OSRM). Lo medido nunca se modifica.", st.snapToRoads) {
                        scope.launch { graph.settings.setSnapToRoads(it) }
                    }
                    TextButton({ dialog = "osrm" }) { Text("Servidor de ajuste: ${st.mapMatchingUrl.removePrefix("https://")}") }
                }
            }

            // ---------------------------------------------------------------- notifications
            SectionHeader("Notificaciones")
            SettingCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ToggleRow("Un amigo empieza a conducir", null, st.notifyFriendDriving) {
                        scope.launch { graph.settings.setNotifications(it, st.notifyRequests, st.notifyTripSummary) }
                    }
                    HorizontalDivider()
                    ToggleRow("Solicitudes e invitaciones", null, st.notifyRequests) {
                        scope.launch { graph.settings.setNotifications(st.notifyFriendDriving, it, st.notifyTripSummary) }
                    }
                    HorizontalDivider()
                    ToggleRow("Resumen al terminar un viaje", null, st.notifyTripSummary) {
                        scope.launch { graph.settings.setNotifications(st.notifyFriendDriving, st.notifyRequests, it) }
                    }
                }
            }
            Text("Llegan mientras Trazo está abierto o registrando en segundo plano. Esta versión no usa notificaciones push con la app cerrada.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            // ---------------------------------------------------------------- appearance
            SectionHeader("Apariencia")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ThemeMode.DARK to "Oscuro", ThemeMode.LIGHT to "Claro", ThemeMode.SYSTEM to "Sistema").forEach { (m, l) ->
                    FilterChip(st.themeMode == m, { scope.launch { graph.settings.setTheme(m) } }, label = { Text(l) })
                }
            }

            // ---------------------------------------------------------------- permissions
            SectionHeader("Permisos")
            PermissionChecklist()

            // ---------------------------------------------------------------- data
            SectionHeader("Tus datos")
            SettingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (pending > 0) "$pending puntos pendientes de subir." else "Todo sincronizado.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton({ graph.sync.requestSync() }, Modifier.fillMaxWidth()) { Text("Sincronizar ahora") }
                    OutlinedButton({ runner.run { exportAll(ctx, graph) } }, Modifier.fillMaxWidth()) { Text("Descargar todos mis datos (JSON)") }
                    OutlinedButton({ dialog = "delete-old" }, Modifier.fillMaxWidth()) { Text("Borrar viajes antiguos…") }
                    OutlinedButton({ dialog = "delete-all" }, Modifier.fillMaxWidth()) { Text("Borrar todo mi historial", color = MaterialTheme.colorScheme.error) }
                }
            }

            // ---------------------------------------------------------------- test drive
            SectionHeader("Probar sin conducir")
            Text("Reproduce un trayecto simulado por Madrid para probar el registro, la reproducción y lo que ven tus amigos. " +
                "Queda marcado como simulado y no cuenta en estadísticas ni clasificaciones.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(1, 5, 20).forEach { f ->
                    OutlinedButton({ graph.tracking.simulate(f); nav.popBackStack() },
                        enabled = status.recordingTripId == null) { Text("Simular ${f}×") }
                }
            }

            // ---------------------------------------------------------------- account
            SectionHeader("Cuenta")
            SettingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ dialog = "password" }, Modifier.fillMaxWidth()) { Text("Cambiar contraseña") }
                    OutlinedButton({ dialog = "signout" }, Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
                    TextButton({ dialog = "delete-account" }, Modifier.fillMaxWidth()) { Text("Eliminar mi cuenta", color = MaterialTheme.colorScheme.error) }
                }
            }

            // ---------------------------------------------------------------- about
            SectionHeader("Acerca de")
            Text("Trazo ${BuildConfig.VERSION_NAME} · gratis, sin anuncios ni suscripciones.", style = MaterialTheme.typography.bodyMedium)
            Text("Servidor: ${st.supabaseUrl.removePrefix("https://")}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Mapas © colaboradores de OpenStreetMap (ODbL), teselas de OpenFreeMap, motor MapLibre. " +
                    "Ajuste a carreteras con OSRM sobre datos de OpenStreetMap.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            TextButton({ dialog = "privacy" }) { Text("Política de privacidad") }
            Spacer(Modifier.height(40.dp))
        }
    }

    // ---------------------------------------------------------------- dialogs
    when (dialog) {
        "name" -> TextInputDialog("Tu nombre", "Nombre", profile?.displayName ?: "", onDismiss = { dialog = null }) {
            runner.run { graph.profile.updateDisplayName(it) }
        }
        "osrm" -> TextInputDialog(
            "Servidor de ajuste a carreteras", "URL (https://…)", s?.mapMatchingUrl ?: "", maxLength = 200,
            body = "El servidor público de demostración de OSRM tiene límites de uso. Puedes usar uno propio.",
            onDismiss = { dialog = null },
        ) { url ->
            if (url.startsWith("https://")) scope.launch { graph.settings.setMapMatchingUrl(url) } else runner.error = "La dirección debe empezar por https://"
        }
        "password" -> PasswordDialog(onDismiss = { dialog = null }) { pw -> runner.run { graph.auth.changePassword(pw); toast(ctx, "Contraseña cambiada") } }
        "signout" -> ConfirmDialog(
            "¿Cerrar sesión?", "Se intentará subir lo pendiente y después se borrarán de este teléfono tus viajes y vehículos (siguen en tu cuenta).",
            "Cerrar sesión", onDismiss = { dialog = null },
        ) { runner.run { signOutAndWipe(graph, ctx) } }
        "delete-old" -> DeleteOldDialog(onDismiss = { dialog = null }) { days ->
            runner.run {
                val before = LocalDate.now().minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val local = graph.trips.deleteAllBefore(before)
                graph.sync.requestSync()
                val remote = graph.profile.deleteServerTrips(before)
                toast(ctx, "Borrados $local viajes de este teléfono y $remote del servidor.")
            }
        }
        "delete-all" -> ConfirmDialog(
            "¿Borrar todo tu historial?", "Se eliminarán todos tus viajes, en este teléfono y en el servidor. No se puede deshacer.",
            "Borrar todo", destructive = true, onDismiss = { dialog = null },
        ) {
            runner.run {
                graph.trips.deleteAllBefore(Long.MAX_VALUE)
                val remote = graph.profile.deleteServerTrips(null)
                graph.sync.requestSync()
                toast(ctx, "Historial borrado ($remote viajes en el servidor).")
            }
        }
        "delete-account" -> ConfirmDialog(
            "¿Eliminar tu cuenta?",
            "Se borrarán de forma permanente tu perfil, foto, amistades, grupos que administras, viajes, vehículos y ubicaciones. " +
                "Te recomendamos descargar antes tus datos.",
            "Eliminar cuenta", destructive = true, onDismiss = { dialog = null },
        ) {
            runner.run {
                graph.tracking.shutdown()
                graph.profile.deleteAccount()
                signOutAndWipe(graph, ctx)
            }
        }
        "privacy" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Privacidad") },
            text = { Text(PRIVACY_SUMMARY, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton({ dialog = null }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva contraseña") },
        text = {
            OutlinedTextField(pw, { pw = it }, label = { Text("Mínimo 8 caracteres") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
        },
        confirmButton = { TextButton({ onDismiss(); onConfirm(pw) }, enabled = pw.length >= 8) { Text("Cambiar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun DeleteOldDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var days by remember { mutableStateOf(90) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Borrar viajes antiguos") },
        text = {
            Column {
                Text("Se borrarán los viajes anteriores a:")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "30 días", 90 to "3 meses", 180 to "6 meses", 365 to "1 año").forEach { (d, l) ->
                        FilterChip(days == d, { days = d }, label = { Text(l) })
                    }
                }
            }
        },
        confirmButton = { TextButton({ onDismiss(); onConfirm(days) }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

/** Downscales to 512 px on the long side and re-encodes as JPEG (also strips EXIF/GPS metadata). */
private fun compressAvatar(ctx: Context, uri: Uri): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    if (longSide <= 0) return null
    var sample = 1
    while (longSide / (sample * 2) >= 512) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val scale = 512f / maxOf(bmp.width, bmp.height)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    return ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
}

/** Full export: everything the server holds about me (GDPR art. 15/20), shared as a JSON file. */
private suspend fun exportAll(ctx: Context, graph: AppGraph) {
    val data = graph.profile.exportServerData()
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "trazo-mis-datos-${LocalDate.now()}.json").apply {
            writeText(graph.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), data))
        }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val i = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Exportar mis datos").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val PRIVACY_SUMMARY = """Trazo trata tu ubicación solo para registrar tus viajes y mostrarla a quien tú autorices.

• Responsable: quien opera el servidor de esta instalación de Trazo (ver «Acerca de»).
• Datos: correo, nombre, foto opcional, vehículos, viajes con sus puntos GPS, tu última posición y tus relaciones (amigos, grupos, convoyes).
• Base legal: ejecución del servicio que solicitas (art. 6.1.b RGPD) y tu consentimiento para la ubicación en segundo plano, que puedes retirar en cualquier momento.
• Quién lo ve: solo las personas a las que das permiso. Las reglas se aplican en el servidor; el modo fantasma y la pausa ocultan tu posición al instante.
• Conservación: mientras tengas cuenta, o el plazo que elijas en «Conservar mis viajes». Al borrar la cuenta se elimina todo.
• Tus derechos (RGPD y LOPDGDD): acceso y portabilidad («Descargar todos mis datos»), rectificación, supresión («Eliminar mi cuenta»), oposición y limitación. Puedes reclamar ante la Agencia Española de Protección de Datos (aepd.es).
• Sin publicidad, sin venta de datos y sin rastreadores de terceros.

El texto completo está en docs/PRIVACIDAD.md del proyecto."""
