package app.trazo.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trazo.core.social.Freshness
import app.trazo.ui.theme.StatNumber
import app.trazo.ui.theme.TrazoTheme
import coil.compose.AsyncImage

@Composable
fun StatTile(label: String, value: String, unit: String? = null, modifier: Modifier = Modifier, accent: Color? = null, hint: String? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = StatNumber, color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Cargando…") {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

@Composable
fun Avatar(name: String, url: String?, size: Dp = 40.dp, ring: Color? = null, modifier: Modifier = Modifier) {
    val m = modifier.size(size).clip(CircleShape)
        .let { if (ring != null) it.border(2.dp, ring, CircleShape) else it }
    if (url != null) {
        AsyncImage(model = url, contentDescription = name, modifier = m.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Box(m.background(avatarColor(name)), contentAlignment = Alignment.Center) {
            Text(
                name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" },
                color = Color(0xFF0E1014), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp,
            )
        }
    }
}

fun avatarColor(seed: String): Color {
    val palette = listOf(0xFFB8FF3C, 0xFF39C6FF, 0xFFFFB020, 0xFFFF7AB6, 0xFF9B8CFF, 0xFF3DDC84, 0xFFFF8A4C, 0xFF5CE1E6)
    return Color(palette[(seed.hashCode() and 0x7fffffff) % palette.size])
}

/** Coloured dot + label that never lets an old position look live. */
@Composable
fun FreshnessBadge(freshness: Freshness, label: String, modifier: Modifier = Modifier) {
    val c = TrazoTheme.colors
    val color = when (freshness) {
        Freshness.LIVE -> c.live
        Freshness.RECENT -> c.recent
        Freshness.STALE, Freshness.HIDDEN -> c.stale
        Freshness.PAUSED -> c.paused
    }
    val text = when (freshness) {
        Freshness.LIVE -> "En directo · $label"
        Freshness.RECENT -> "Última posición $label"
        Freshness.STALE -> "Sin actualizar · $label"
        Freshness.PAUSED -> "Ubicación en pausa"
        Freshness.HIDDEN -> "Ubicación no disponible"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PulseDot(color, pulsing = freshness == Freshness.LIVE)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PulseDot(color: Color, pulsing: Boolean, size: Dp = 8.dp) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        a
    } else 1f
    Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
         textColor: Color = MaterialTheme.colorScheme.onSurface, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Surface(
        color = color, shape = RoundedCornerShape(50),
        modifier = modifier.let { if (onClick != null) it.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else it },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = textColor); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, color = textColor, maxLines = 1)
        }
    }
}

@Composable
fun SettingCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, TrazoTheme.colors.cardStroke),
        shape = MaterialTheme.shapes.medium,
    ) { Column(Modifier.padding(PaddingValues(vertical = 4.dp))) { content() } }
}
