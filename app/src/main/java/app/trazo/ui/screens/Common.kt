package app.trazo.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.trazo.util.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Runs a user action, tracks "busy" and keeps a readable error for the screen to show. */
@Stable
class ActionRunner(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun run(onDone: (() -> Unit)? = null, block: suspend () -> Unit) {
        error = null
        busy = true
        scope.launch {
            val r = runCatching { block() }
            busy = false
            r.onFailure { error = it.userMessage() }.onSuccess { onDone?.invoke() }
        }
    }
}

@Composable
fun rememberRunner(): ActionRunner {
    val scope = rememberCoroutineScope()
    return remember { ActionRunner(scope) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, nav: NavController, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton({ onDismiss(); onConfirm() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "Guardar",
    maxLength: Int = 40,
    body: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (body != null) { Text(body, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(12.dp)) }
                OutlinedTextField(text, { text = it.take(maxLength) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onDismiss(); onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

fun shareText(ctx: Context, subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

/** Invitation message: the code works on its own; the link opens Trazo directly when installed. */
fun inviteMessage(kind: app.trazo.core.social.InviteCode.Kind, code: String, name: String?): String {
    val pretty = app.trazo.core.social.InviteCode.format(code)
    val link = app.trazo.core.social.InviteCode.link(kind, code)
    return when (kind) {
        app.trazo.core.social.InviteCode.Kind.FRIEND -> "Añádeme en Trazo con mi código $pretty o abre $link"
        app.trazo.core.social.InviteCode.Kind.GROUP -> "Únete al grupo «${name ?: ""}» en Trazo con el código $pretty o abre $link"
        app.trazo.core.social.InviteCode.Kind.CONVOY -> "Únete al convoy «${name ?: ""}» en Trazo con el código $pretty o abre $link"
    }
}

/** Parses trazo://invite|group|convoy/CODE links or a bare code (treated as a friend code first). */
fun parseInvite(text: String): Pair<app.trazo.core.social.InviteCode.Kind?, String>? {
    val t = text.trim()
    val m = Regex("""trazo://(invite|group|convoy)/([A-Za-z0-9-]{8,9})""").find(t)
    if (m != null) {
        val kind = app.trazo.core.social.InviteCode.Kind.entries.first { it.path == m.groupValues[1] }
        val code = app.trazo.core.social.InviteCode.normalize(m.groupValues[2])
        return if (app.trazo.core.social.InviteCode.isValid(code)) kind to code else null
    }
    val code = app.trazo.core.social.InviteCode.fromLinkOrText(t) ?: return null
    return null to code
}
