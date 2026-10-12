package app.trazo.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.trazo.AppGraph
import app.trazo.MainActivity
import app.trazo.data.repo.AuthState
import app.trazo.ui.components.LoadingState
import app.trazo.ui.screens.AuthScreen
import app.trazo.ui.screens.BackendSetupScreen
import app.trazo.ui.screens.ConvoyDetailScreen
import app.trazo.ui.screens.ConvoysScreen
import app.trazo.ui.screens.FriendProfileScreen
import app.trazo.ui.screens.FriendsScreen
import app.trazo.ui.screens.GarageScreen
import app.trazo.ui.screens.HeatmapScreen
import app.trazo.ui.screens.MapScreen
import app.trazo.ui.screens.OnboardingScreen
import app.trazo.ui.screens.ProfileScreen
import app.trazo.ui.screens.SessionBootstrap
import app.trazo.ui.screens.StatsScreen
import app.trazo.ui.screens.TripDetailScreen
import app.trazo.ui.screens.TripsScreen
import app.trazo.ui.theme.TrazoTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("map", "Mapa", Icons.Filled.Map),
    Tab("friends", "Amigos", Icons.Filled.Group),
    Tab("trips", "Viajes", Icons.Filled.Route),
    Tab("stats", "Estadísticas", Icons.Filled.BarChart),
    Tab("garage", "Garaje", Icons.Filled.DirectionsCar),
)

@Composable
fun TrazoRoot(graph: AppGraph, activity: MainActivity) {
    val settings by graph.settings.settings.collectAsState(initial = null)
    val auth by graph.auth.state.collectAsState(initial = AuthState.Loading)
    val s = settings
    TrazoTheme(mode = s?.themeMode ?: app.trazo.config.ThemeMode.DARK) {
        Box(Modifier.fillMaxSize()) {
            when {
                s == null -> LoadingState()
                !s.onboardingDone -> OnboardingScreen(graph)
                auth is AuthState.NoBackend -> BackendSetupScreen(graph)
                auth is AuthState.Loading -> LoadingState(label = "Conectando…")
                auth is AuthState.SignedOut -> AuthScreen(graph)
                auth is AuthState.SignedIn -> {
                    SessionBootstrap(graph, (auth as AuthState.SignedIn).userId)
                    MainScaffold(graph, activity)
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(graph: AppGraph, activity: MainActivity) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val social by graph.social.state.collectAsState()
    val pendingRoute by activity.pendingRoute.collectAsState()
    val pendingLink by activity.pendingLink.collectAsState()

    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { r ->
            activity.pendingRoute.value = null
            when {
                r.startsWith("trip/") || r.startsWith("convoy/") -> nav.navigate(r)
                r.startsWith("map/") -> nav.navigate("map?focus=${r.removePrefix("map/")}") { launchSingleTop = true }
                else -> nav.navigate(r) { launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) nav.navigate("friends") { launchSingleTop = true }
    }

    Scaffold(
        bottomBar = {
            if (tabs.any { route?.startsWith(it.route) == true }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { t ->
                        val selected = route?.startsWith(t.route) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(t.route) },
                            icon = {
                                val badge = if (t.route == "friends") social.requests.count { it.incoming } + social.convoyInvites.size else 0
                                if (badge > 0) BadgedBox(badge = { Badge { Text("$badge") } }) { Icon(t.icon, t.label) }
                                else Icon(t.icon, t.label)
                            },
                            label = { Text(t.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "map?focus={focus}", modifier = Modifier.padding(padding)) {
            composable("map?focus={focus}", arguments = listOf(navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                MapScreen(graph, nav, focusUserId = it.arguments?.getString("focus"))
            }
            composable("friends") { FriendsScreen(graph, nav, activity) }
            composable("trips") { TripsScreen(graph, nav) }
            composable("stats") { StatsScreen(graph, nav) }
            composable("garage") { GarageScreen(graph) }
            composable("trip/{id}") { TripDetailScreen(graph, nav, tripId = it.arguments?.getString("id")!!, ownerId = null) }
            composable("friendtrip/{owner}/{id}") {
                TripDetailScreen(graph, nav, tripId = it.arguments?.getString("id")!!, ownerId = it.arguments?.getString("owner"))
            }
            composable("friend/{id}") { FriendProfileScreen(graph, nav, it.arguments?.getString("id")!!) }
            composable("convoys") { ConvoysScreen(graph, nav) }
            composable("convoy/{id}") { ConvoyDetailScreen(graph, nav, it.arguments?.getString("id")!!) }
            composable("profile") { ProfileScreen(graph, nav) }
            composable("heatmap") { HeatmapScreen(graph, nav) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
