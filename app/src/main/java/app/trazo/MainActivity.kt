package app.trazo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.trazo.ui.nav.TrazoRoot
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Invitation code or notification route waiting to be handled by the UI. */
    val pendingLink = MutableStateFlow<String?>(null)
    val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        val graph = (application as TrazoApp).graph
        setContent { TrazoRoot(graph, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.data?.takeIf { it.scheme == "trazo" }?.let { pendingLink.value = it.toString() }
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
