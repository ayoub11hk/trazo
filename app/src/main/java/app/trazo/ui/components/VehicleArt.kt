package app.trazo.ui.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Body styles. Every catalogue model maps to one; the illustration and the map marker are
 * generated from the style plus the owner's colour, so new models only need a catalogue entry.
 */
enum class BodyType(val label: String, val lengthM: Float, val widthM: Float) {
    HATCHBACK("Compacto", 4.1f, 1.8f),
    SEDAN("Berlina", 4.7f, 1.85f),
    WAGON("Familiar", 4.8f, 1.85f),
    SUV("SUV", 4.6f, 1.9f),
    COUPE("Coupé", 4.6f, 1.85f),
    SPORTS("Deportivo", 4.5f, 1.95f),
    CONVERTIBLE("Descapotable", 4.2f, 1.8f),
    PICKUP("Pick-up", 5.3f, 1.95f),
    VAN("Furgoneta / Monovolumen", 5.0f, 2.0f),
    MOTORBIKE("Moto", 2.1f, 0.8f);

    companion object {
        fun from(key: String?): BodyType = entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: SEDAN
    }
}

@Serializable data class CatalogModel(val name: String, val body: String)
@Serializable data class CatalogMake(val name: String, val models: List<CatalogModel>)
@Serializable data class VehicleCatalog(val version: Int, val makes: List<CatalogMake>) {
    val modelCount: Int get() = makes.sumOf { it.models.size }

    companion object {
        @Volatile private var cached: VehicleCatalog? = null
        fun load(ctx: Context): VehicleCatalog = cached ?: synchronized(this) {
            cached ?: Json { ignoreUnknownKeys = true }.decodeFromString(
                serializer(), ctx.assets.open("vehicles.json").bufferedReader().use { it.readText() },
            ).also { cached = it }
        }
    }
}

val VehicleColors = listOf(
    "#F2F4F7", "#1C1F24", "#8C94A3", "#C8D1DC", "#B8FF3C", "#39C6FF", "#FF5C6C", "#FFB020",
    "#2F6BFF", "#0F7B52", "#7A1F2B", "#6A4CFF", "#FF7AB6", "#D9C7A3", "#5C3B28", "#FF8A4C",
)

fun parseHex(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFFC8D1DC))

/** Side-view illustration used in the garage and profile cards. */
@Composable
fun VehicleArt(body: BodyType, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(2.2f)) {
        if (body == BodyType.MOTORBIKE) drawBike(color) else drawCar(body, color)
    }
}

private data class Profile(
    val roofStart: Float, val roofEnd: Float, val roofTop: Float, val beltLine: Float,
    val hoodStart: Float, val tailEnd: Float, val front: Float, val rear: Float, val bed: Boolean = false,
    val openTop: Boolean = false, val wheelR: Float = 0.105f,
)

private fun profileFor(b: BodyType) = when (b) {
    BodyType.HATCHBACK -> Profile(0.36f, 0.86f, 0.30f, 0.52f, 0.10f, 0.92f, 0.06f, 0.94f)
    BodyType.SEDAN -> Profile(0.34f, 0.72f, 0.33f, 0.53f, 0.06f, 0.95f, 0.03f, 0.97f)
    BodyType.WAGON -> Profile(0.33f, 0.90f, 0.31f, 0.53f, 0.06f, 0.95f, 0.03f, 0.97f)
    BodyType.SUV -> Profile(0.32f, 0.88f, 0.22f, 0.47f, 0.07f, 0.93f, 0.04f, 0.96f, wheelR = 0.12f)
    BodyType.COUPE -> Profile(0.38f, 0.74f, 0.36f, 0.55f, 0.05f, 0.96f, 0.02f, 0.98f)
    BodyType.SPORTS -> Profile(0.40f, 0.70f, 0.42f, 0.58f, 0.04f, 0.97f, 0.02f, 0.98f, wheelR = 0.11f)
    BodyType.CONVERTIBLE -> Profile(0.40f, 0.62f, 0.44f, 0.56f, 0.06f, 0.95f, 0.03f, 0.97f, openTop = true)
    BodyType.PICKUP -> Profile(0.30f, 0.55f, 0.22f, 0.48f, 0.06f, 0.96f, 0.03f, 0.97f, bed = true, wheelR = 0.12f)
    BodyType.VAN -> Profile(0.22f, 0.95f, 0.14f, 0.46f, 0.05f, 0.96f, 0.03f, 0.97f, wheelR = 0.10f)
    BodyType.MOTORBIKE -> Profile(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
}

private fun DrawScope.drawCar(body: BodyType, color: Color) {
    val w = size.width
    val h = size.height
    val p = profileFor(body)
    val ground = h * 0.86f
    val sill = h * 0.74f
    val light = lerp(color, Color.White, 0.35f)
    val dark = lerp(color, Color.Black, 0.45f)
    val glass = Color(0xFF1B2733)

    // shadow
    drawOval(Color.Black.copy(alpha = 0.25f), Offset(w * 0.06f, ground - h * 0.02f), Size(w * 0.88f, h * 0.06f))

    val shell = Path().apply {
        moveTo(w * p.front, sill)
        lineTo(w * p.front, h * (p.beltLine + 0.06f))
        quadraticTo(w * p.front, h * p.beltLine, w * (p.front + 0.06f), h * p.beltLine)
        lineTo(w * p.roofStart - w * 0.08f, h * p.beltLine)
        if (p.openTop) {
            lineTo(w * p.roofStart, h * (p.beltLine - 0.02f))
            lineTo(w * p.roofEnd, h * (p.beltLine - 0.02f))
        } else {
            lineTo(w * p.roofStart, h * p.roofTop)
            lineTo(w * p.roofEnd - w * 0.04f, h * p.roofTop)
            lineTo(w * p.roofEnd, h * p.beltLine - h * 0.02f)
        }
        if (p.bed) {
            lineTo(w * (p.roofEnd + 0.01f), h * p.beltLine)
            lineTo(w * p.rear, h * p.beltLine)
        } else {
            lineTo(w * p.rear - w * 0.03f, h * p.beltLine)
            quadraticTo(w * p.rear, h * p.beltLine, w * p.rear, h * (p.beltLine + 0.07f))
        }
        lineTo(w * p.rear, sill)
        close()
    }
    drawPath(shell, Brush.verticalGradient(listOf(light, color, dark), startY = h * p.roofTop, endY = sill))
    drawPath(shell, Color.Black.copy(alpha = 0.35f), style = Stroke(width = h * 0.012f))

    // windows
    if (!p.openTop) {
        val win = Path().apply {
            moveTo(w * p.roofStart + w * 0.012f, h * p.roofTop + h * 0.035f)
            lineTo(w * p.roofEnd - w * 0.045f, h * p.roofTop + h * 0.035f)
            lineTo(w * p.roofEnd - w * 0.012f, h * p.beltLine - h * 0.01f)
            lineTo(w * p.roofStart - w * 0.06f, h * p.beltLine - h * 0.01f)
            close()
        }
        drawPath(win, Brush.linearGradient(listOf(glass, Color(0xFF3B5568)), Offset(w * p.roofStart, h * p.roofTop), Offset(w * p.roofEnd, h * p.beltLine)))
        val pillar = w * ((p.roofStart + p.roofEnd) / 2f)
        drawLine(color, Offset(pillar, h * p.roofTop + h * 0.03f), Offset(pillar - w * 0.01f, h * p.beltLine), strokeWidth = w * 0.012f)
    }
    // belt highlight & lights
    drawLine(light.copy(alpha = 0.6f), Offset(w * (p.front + 0.05f), h * (p.beltLine + 0.08f)), Offset(w * (p.rear - 0.05f), h * (p.beltLine + 0.08f)), strokeWidth = h * 0.01f)
    drawRoundRect(Color(0xFFFFF4C2), Offset(w * p.front, h * (p.beltLine + 0.04f)), Size(w * 0.04f, h * 0.045f), CornerRadius(h * 0.02f))
    drawRoundRect(Color(0xFFFF3B4A), Offset(w * p.rear - w * 0.035f, h * (p.beltLine + 0.04f)), Size(w * 0.035f, h * 0.05f), CornerRadius(h * 0.02f))
    // wheels
    val r = h * p.wheelR * 2.2f
    listOf(0.21f, 0.79f).forEach { fx ->
        val c = Offset(w * fx, sill)
        drawCircle(Color(0xFF0B0C0E), r, c)
        drawCircle(Color(0xFF7D8591), r * 0.58f, c)
        drawCircle(Color(0xFF2A2E35), r * 0.22f, c)
    }
}

private fun DrawScope.drawBike(color: Color) {
    val w = size.width
    val h = size.height
    val ground = h * 0.86f
    val r = h * 0.24f
    drawOval(Color.Black.copy(alpha = 0.25f), Offset(w * 0.12f, ground - h * 0.02f), Size(w * 0.76f, h * 0.05f))
    val back = Offset(w * 0.25f, ground - r)
    val front = Offset(w * 0.75f, ground - r)
    listOf(back, front).forEach {
        drawCircle(Color(0xFF0B0C0E), r, it)
        drawCircle(Color(0xFF59606B), r * 0.72f, it, style = Stroke(h * 0.02f))
    }
    val frame = Path().apply {
        moveTo(back.x, back.y)
        lineTo(w * 0.42f, h * 0.42f)
        lineTo(w * 0.62f, h * 0.40f)
        lineTo(front.x, front.y)
    }
    drawPath(frame, Color(0xFF3A404B), style = Stroke(h * 0.035f))
    val tank = Path().apply {
        moveTo(w * 0.36f, h * 0.40f)
        quadraticTo(w * 0.48f, h * 0.24f, w * 0.64f, h * 0.36f)
        lineTo(w * 0.60f, h * 0.48f)
        lineTo(w * 0.40f, h * 0.50f)
        close()
    }
    drawPath(tank, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.3f), color, lerp(color, Color.Black, 0.4f))))
    drawLine(Color(0xFF1B1E23), Offset(w * 0.22f, h * 0.40f), Offset(w * 0.40f, h * 0.38f), strokeWidth = h * 0.05f)
    drawLine(Color(0xFF7D8591), Offset(w * 0.64f, h * 0.30f), Offset(front.x, front.y), strokeWidth = h * 0.025f)
}
