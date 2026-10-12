package app.trazo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trazo.config.ThemeMode

/** Trazo palette: graphite, soft white, "volt" electric lime and "signal" cyan for motion. */
object Brand {
    val Graphite900 = Color(0xFF0E1014)
    val Graphite800 = Color(0xFF15181E)
    val Graphite700 = Color(0xFF1D2128)
    val Graphite600 = Color(0xFF282D36)
    val Graphite500 = Color(0xFF3A404B)
    val Mist = Color(0xFF8C94A3)
    val SoftWhite = Color(0xFFF2F4F7)
    val Volt = Color(0xFFB8FF3C)
    val VoltDeep = Color(0xFF4F7A00)
    val Signal = Color(0xFF39C6FF)
    val SignalDeep = Color(0xFF0072A8)
    val Amber = Color(0xFFFFB020)
    val Coral = Color(0xFFFF5C6C)
    val Live = Color(0xFF3DDC84)
}

/** Semantic colours not covered by Material roles. */
@Immutable
data class TrazoColors(
    val live: Color,
    val recent: Color,
    val stale: Color,
    val paused: Color,
    val route: Color,
    val routeAlt: Color,
    val gap: Color,
    val cardStroke: Color,
)

val LocalTrazoColors = staticCompositionLocalOf {
    TrazoColors(Brand.Live, Brand.Amber, Brand.Mist, Brand.Signal, Brand.Volt, Brand.Signal, Brand.Amber, Brand.Graphite600)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Brand.Volt,
    onPrimary = Brand.Graphite900,
    primaryContainer = Color(0xFF2C3D0A),
    onPrimaryContainer = Brand.Volt,
    secondary = Brand.Signal,
    onSecondary = Brand.Graphite900,
    secondaryContainer = Color(0xFF0D3446),
    onSecondaryContainer = Color(0xFFBDEBFF),
    tertiary = Brand.Amber,
    onTertiary = Brand.Graphite900,
    background = Brand.Graphite900,
    onBackground = Brand.SoftWhite,
    surface = Brand.Graphite900,
    onSurface = Brand.SoftWhite,
    surfaceVariant = Brand.Graphite700,
    onSurfaceVariant = Color(0xFFB4BBC8),
    surfaceContainerLowest = Color(0xFF0A0C0F),
    surfaceContainerLow = Brand.Graphite800,
    surfaceContainer = Brand.Graphite800,
    surfaceContainerHigh = Brand.Graphite700,
    surfaceContainerHighest = Brand.Graphite600,
    outline = Brand.Graphite500,
    outlineVariant = Brand.Graphite600,
    error = Brand.Coral,
    onError = Brand.Graphite900,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Brand.VoltDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9FF9E),
    onPrimaryContainer = Color(0xFF1B2B00),
    secondary = Brand.SignalDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEBFF),
    onSecondaryContainer = Color(0xFF001E2E),
    tertiary = Color(0xFF8A5A00),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF14171C),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFE4E7EC),
    onSurfaceVariant = Color(0xFF454B56),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEBEEF2),
    surfaceContainerHigh = Color(0xFFE4E7EC),
    surfaceContainerHighest = Color(0xFFDDE1E7),
    outline = Color(0xFF9AA1AD),
    outlineVariant = Color(0xFFD3D7DE),
    error = Color(0xFFC62839),
)

private val Base = Typography()
private val numeric = "tnum"

val TrazoTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, fontFeatureSettings = numeric),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = numeric),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = numeric),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
)

/** Big tabular numbers for stats. */
val StatNumber = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, fontFeatureSettings = "tnum")

val TrazoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun TrazoTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val extra = if (dark) {
        TrazoColors(Brand.Live, Brand.Amber, Brand.Mist, Brand.Signal, Brand.Volt, Brand.Signal, Brand.Amber, Brand.Graphite600)
    } else {
        TrazoColors(Color(0xFF138A4B), Color(0xFFB36B00), Color(0xFF6B7280), Brand.SignalDeep, Color(0xFF5E9400), Brand.SignalDeep, Color(0xFFB36B00), Color(0xFFD3D7DE))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTrazoColors provides extra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = TrazoTypography,
            shapes = TrazoShapes,
            content = content,
        )
    }
}

object TrazoTheme {
    val colors: TrazoColors @Composable get() = LocalTrazoColors.current
}
