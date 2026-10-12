package app.trazo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trazo.core.geo.LatLng
import app.trazo.core.geo.Polyline
import app.trazo.data.local.TripEntity
import app.trazo.ui.theme.Brand
import app.trazo.ui.theme.TrazoTheme
import app.trazo.util.Format
import kotlin.math.cos

/** Route thumbnail drawn from the stored preview polyline (no map tiles needed, works offline). */
@Composable
fun RouteThumb(polyline: String?, modifier: Modifier = Modifier, color: Color = TrazoTheme.colors.route) {
    val pts = remember(polyline) { polyline?.let { runCatching { Polyline.decode(it) }.getOrNull() }.orEmpty() }
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        drawRoundRect(bg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))
        if (pts.size < 2) return@Canvas
        val path = project(pts, size.width, size.height, 10.dp.toPx())
        drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun project(pts: List<LatLng>, w: Float, h: Float, pad: Float): Path {
    val k = cos(Math.toRadians(pts.map { it.lat }.average()))
    val xs = pts.map { it.lng * k }
    val ys = pts.map { it.lat }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val span = maxOf(maxX - minX, maxY - minY, 1e-6)
    val scale = minOf((w - 2 * pad) / span, (h - 2 * pad) / span)
    val ox = (w - (maxX - minX) * scale) / 2
    val oy = (h - (maxY - minY) * scale) / 2
    return Path().apply {
        pts.indices.forEach { i ->
            val o = Offset((ox + (xs[i] - minX) * scale).toFloat(), (h - oy - (ys[i] - minY) * scale).toFloat())
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
    }
}

@Composable
fun TripRow(t: TripEntity, vehicleName: String?, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteThumb(t.previewPolyline, Modifier.size(72.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title ?: "${Format.shortDay(t.startedAt)} · ${Format.time(t.startedAt)}",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.distance(t.distanceM)} · ${Format.duration(t.durationMs)}" +
                        (vehicleName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                Row(Modifier.padding(top = 4.dp)) {
                    if (t.title != null) Text("${Format.shortDay(t.startedAt)} ${Format.time(t.startedAt)}  ",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.simulated) Text("SIMULADO  ", style = MaterialTheme.typography.labelSmall, color = Brand.Amber)
                    if (t.gapDistanceM > 50) Text("con cortes de señal  ", style = MaterialTheme.typography.labelSmall, color = Brand.Amber)
                    if (t.dirty) Text("pendiente de subir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

