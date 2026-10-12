package app.trazo.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.trazo.AppGraph
import app.trazo.R
import app.trazo.ui.components.SettingCard
import app.trazo.ui.theme.Brand
import app.trazo.util.Permissions
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Graphite900, Brand.Graphite800)))
            .statusBarsPadding().navigationBarsPadding().padding(24.dp),
    ) {
        if (step == 0) {
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(140.dp))
            Text("Trazo", style = MaterialTheme.typography.displayMedium, color = Brand.SoftWhite)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tus viajes, tus amigos y vuestras rutas en un mapa en directo. Todo incluido, sin suscripciones.",
                style = MaterialTheme.typography.titleMedium, color = Brand.Mist,
            )
            Spacer(Modifier.height(28.dp))
            Feature(Icons.Filled.DirectionsCar, "Registro automático", "Detecta cuándo conduces y guarda el recorrido con GPS de alta precisión.")
            Feature(Icons.Filled.MyLocation, "Amigos en directo", "Comparte tu ubicación solo con quien tú elijas y ponla en pausa cuando quieras.")
            Feature(Icons.Filled.CheckCircle, "Gratis y completo", "Historial, estadísticas, convoyes y garaje sin límites ni pagos.")
            Spacer(Modifier.weight(1f))
            Button(onClick = { step = 1 }, Modifier.fillMaxWidth().height(54.dp)) { Text("Empezar") }
        } else {
            Text("Permisos", style = MaterialTheme.typography.headlineMedium, color = Brand.SoftWhite)
            Spacer(Modifier.height(6.dp))
            Text(
                "Trazo necesita estos permisos para grabar viajes y compartir tu posición con tus amigos. Puedes cambiarlos cuando quieras.",
                style = MaterialTheme.typography.bodyMedium, color = Brand.Mist,
            )
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { PermissionChecklist() }
            Button(
                onClick = { scope.launch { graph.settings.setOnboardingDone() } },
                Modifier.fillMaxWidth().height(54.dp),
            ) { Text("Continuar") }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.Graphite700), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Brand.Volt)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.SoftWhite)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Brand.Mist)
        }
    }
}

/** Every permission with a plain explanation, its current state and a button to grant it. */
@Composable
fun PermissionChecklist() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val refresh = { tick++ }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    var askedBackground by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val t = tick

    SettingCard {
        PermissionRow(
            Icons.Filled.LocationOn, "Ubicación precisa",
            "Para dibujar tus recorridos con exactitud y mostrarte en el mapa.",
            Permissions.fineLocation(ctx),
        ) {
            fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PermissionRow(
            Icons.Filled.MyLocation, "Ubicación «Permitir siempre»",
            "Para seguir grabando el viaje con la pantalla apagada o el móvil en el soporte. Elige «Permitir todo el tiempo».",
            Permissions.backgroundLocation(ctx), enabled = Permissions.fineLocation(ctx),
        ) {
            if (!askedBackground) { askedBackground = true; singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            else ctx.startActivity(Permissions.appSettingsIntent(ctx))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PermissionRow(
            Icons.Filled.DirectionsCar, "Actividad física",
            "Para saber cuándo vas en coche y empezar a grabar sin que pulses nada. Apenas gasta batería.",
            Permissions.activityRecognition(ctx),
        ) { singleLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PermissionRow(
            Icons.Filled.Notifications, "Notificaciones",
            "Aviso visible mientras se graba o compartes ubicación, y solicitudes de amistad.",
            Permissions.notifications(ctx),
        ) { singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        PermissionRow(
            Icons.Filled.BatteryChargingFull, "Sin restricción de batería",
            "Evita que el sistema corte la grabación a mitad de viaje.",
            Permissions.ignoringBatteryOptimizations(ctx),
        ) { runCatching { ctx.startActivity(Permissions.batteryOptimizationIntent(ctx)) } }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, why: String, granted: Boolean, enabled: Boolean = true, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Icon(Icons.Filled.CheckCircle, "Concedido", tint = MaterialTheme.colorScheme.primary)
        } else if (enabled) {
            OutlinedButton(onClick = onGrant) { Text("Permitir") }
        } else {
            Icon(Icons.Outlined.Circle, "Pendiente", tint = MaterialTheme.colorScheme.outline)
        }
    }
}
