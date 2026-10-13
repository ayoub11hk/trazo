package app.trazo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.trazo.AppGraph
import kotlinx.coroutines.launch

/**
 * Shown only when the APK was built without server settings. The URL and the *public* anon
 * key are safe to type here: data access is enforced by the database rules, not by the key.
 */
@Composable
fun BackendSetupScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://") }
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Conectar con tu servidor", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Esta copia de Trazo no trae la dirección del servidor. Copia la «Project URL» y la clave «anon public» " +
                "de tu proyecto de Supabase (Settings → API). No uses nunca la clave service_role.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Project URL") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("anon public key") }, minLines = 3)
        val kind = app.trazo.core.backend.SupabaseKey.classify(key)
        if (key.isNotEmpty() && kind != app.trazo.core.backend.SupabaseKey.Kind.PUBLIC) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (kind == app.trazo.core.backend.SupabaseKey.Kind.SECRET)
                    "Esa es una clave secreta del servidor. No la pongas en ninguna app: usa la «anon public» o «publishable»."
                else "No parece una clave anon public de Supabase. Cópiala entera desde Settings → API.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = app.trazo.core.backend.SupabaseKey.isProjectUrl(url) && kind == app.trazo.core.backend.SupabaseKey.Kind.PUBLIC,
            onClick = { scope.launch { graph.settings.setBackend(url, key) } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Guardar y continuar") }
    }
}
