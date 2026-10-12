package app.trazo.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.trazo.AppGraph
import app.trazo.R
import app.trazo.ui.components.ErrorBanner
import app.trazo.ui.theme.Brand
import app.trazo.util.userMessage
import kotlinx.coroutines.launch

private enum class AuthMode { LOGIN, REGISTER, RECOVER }

@Composable
fun AuthScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AuthMode.LOGIN) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        error = null; info = null; busy = true
        scope.launch {
            runCatching { block() }.onFailure { error = it.userMessage() }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Graphite900, Brand.Graphite800)))
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(110.dp))
        Text("Trazo", style = MaterialTheme.typography.displaySmall, color = Brand.SoftWhite)
        Spacer(Modifier.height(24.dp))

        if (mode != AuthMode.RECOVER) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(mode == AuthMode.LOGIN, { mode = AuthMode.LOGIN; error = null }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Entrar") }
                SegmentedButton(mode == AuthMode.REGISTER, { mode = AuthMode.REGISTER; error = null }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Crear cuenta") }
            }
            Spacer(Modifier.height(20.dp))
        } else {
            Text("Recuperar la cuenta", style = MaterialTheme.typography.titleLarge, color = Brand.SoftWhite)
            Spacer(Modifier.height(8.dp))
            Text(
                if (!codeSent) "Te enviaremos un código de 6 cifras a tu correo." else "Escribe el código que te hemos enviado y tu nueva contraseña.",
                style = MaterialTheme.typography.bodyMedium, color = Brand.Mist,
            )
            Spacer(Modifier.height(16.dp))
        }

        if (mode == AuthMode.REGISTER) {
            OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Tu nombre") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Correo electrónico") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        if (mode == AuthMode.RECOVER && codeSent) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                code, { code = it.filter(Char::isDigit).take(10) }, Modifier.fillMaxWidth(), label = { Text("Código") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }
        if (mode != AuthMode.RECOVER || codeSent) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text(if (mode == AuthMode.RECOVER) "Nueva contraseña" else "Contraseña") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (mode != AuthMode.LOGIN) ({ Text("Mínimo 8 caracteres") }) else null,
            )
        }
        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it); Spacer(Modifier.height(12.dp)) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(12.dp)) }

        val valid = email.contains("@") && when (mode) {
            AuthMode.LOGIN -> password.isNotEmpty()
            AuthMode.REGISTER -> name.isNotBlank() && password.length >= 8
            AuthMode.RECOVER -> !codeSent || (code.length >= 6 && password.length >= 8)
        }
        Button(
            enabled = valid && !busy,
            onClick = {
                when (mode) {
                    AuthMode.LOGIN -> run { graph.auth.signIn(email, password) }
                    AuthMode.REGISTER -> run {
                        val ready = graph.auth.signUp(name, email, password)
                        if (!ready) { info = "Cuenta creada. Confirma tu correo con el enlace que te hemos enviado y luego entra."; mode = AuthMode.LOGIN }
                    }
                    AuthMode.RECOVER -> run {
                        if (!codeSent) { graph.auth.sendRecovery(email); codeSent = true; info = "Código enviado. Revisa tu correo (y la carpeta de spam)." }
                        else graph.auth.completeRecovery(email, code, password)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text(when (mode) {
                AuthMode.LOGIN -> "Entrar"
                AuthMode.REGISTER -> "Crear cuenta gratis"
                AuthMode.RECOVER -> if (codeSent) "Cambiar contraseña" else "Enviar código"
            })
        }
        Spacer(Modifier.height(8.dp))
        Row {
            if (mode == AuthMode.RECOVER) TextButton({ mode = AuthMode.LOGIN; codeSent = false; error = null }) { Text("Volver") }
            else TextButton({ mode = AuthMode.RECOVER; codeSent = false; error = null; info = null }) { Text("¿Has olvidado la contraseña?") }
        }
        Spacer(Modifier.width(1.dp))
    }
}
