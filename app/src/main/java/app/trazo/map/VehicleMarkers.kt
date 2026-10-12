package app.trazo.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import app.trazo.ui.components.BodyType
import kotlin.math.roundToInt

/**
 * Top-down vehicle markers rendered once per (body, colour) and handed to MapLibre. They are
 * rotated by the driving direction and laid flat on the map, so with the camera tilted they
 * are seen in perspective. These are 2D sprites, not 3D models.
 */
object VehicleMarkers {
    private val cache = HashMap<String, Bitmap>()

    fun key(body: BodyType, colorHex: String) = "veh-${body.name}-${colorHex.removePrefix("#")}"

    fun bitmap(body: BodyType, colorHex: String, density: Float): Bitmap = cache.getOrPut(key(body, colorHex) + density) {
        val color = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.LTGRAY)
        val pxPerM = 9f * density
        val lw = (body.widthM * pxPerM).roundToInt().coerceAtLeast(12)
        val lh = (body.lengthM * pxPerM).roundToInt().coerceAtLeast(20)
        val pad = (6 * density).roundToInt()
        val bmp = Bitmap.createBitmap(lw + pad * 2, lh + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val body0 = RectF(pad.toFloat(), pad.toFloat(), (pad + lw).toFloat(), (pad + lh).toFloat())

        // soft shadow
        p.color = Color.argb(90, 0, 0, 0)
        c.drawRoundRect(RectF(body0.left + 2 * density, body0.top + 3 * density, body0.right + 2 * density, body0.bottom + 3 * density), lw * 0.35f, lw * 0.35f, p)

        if (body == BodyType.MOTORBIKE) {
            p.color = Color.rgb(20, 22, 26)
            c.drawRoundRect(RectF(body0.centerX() - lw * 0.18f, body0.top, body0.centerX() + lw * 0.18f, body0.bottom), lw * 0.2f, lw * 0.2f, p)
            p.color = color
            c.drawRoundRect(RectF(body0.left, body0.top + lh * 0.3f, body0.right, body0.top + lh * 0.62f), lw * 0.4f, lw * 0.4f, p)
            p.color = Color.rgb(230, 230, 230)
            c.drawRect(body0.left - pad * 0.5f, body0.top + lh * 0.18f, body0.right + pad * 0.5f, body0.top + lh * 0.22f, p)
            return@getOrPut bmp
        }

        p.shader = LinearGradient(body0.left, 0f, body0.right, 0f,
            intArrayOf(darken(color, 0.25f), lighten(color, 0.25f), darken(color, 0.25f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(body0, lw * 0.32f, lw * 0.32f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * density
        p.color = Color.argb(160, 0, 0, 0)
        c.drawRoundRect(body0, lw * 0.32f, lw * 0.32f, p)
        p.style = Paint.Style.FILL

        // glass: windscreen (front = top of bitmap), roof, rear window
        val glass = Color.rgb(28, 40, 52)
        val inset = lw * 0.12f
        val roofTop = body0.top + lh * when (body) { BodyType.VAN -> 0.14f; BodyType.SPORTS -> 0.34f; else -> 0.27f }
        val roofBottom = body0.top + lh * when (body) {
            BodyType.PICKUP -> 0.55f; BodyType.VAN, BodyType.WAGON, BodyType.SUV -> 0.86f; BodyType.HATCHBACK -> 0.84f; else -> 0.74f
        }
        p.color = glass
        c.drawRoundRect(RectF(body0.left + inset, roofTop, body0.right - inset, roofBottom), lw * 0.18f, lw * 0.18f, p)
        if (body != BodyType.CONVERTIBLE) {
            p.color = lighten(color, 0.1f)
            c.drawRoundRect(RectF(body0.left + inset * 1.25f, roofTop + lh * 0.09f, body0.right - inset * 1.25f, roofBottom - lh * 0.08f), lw * 0.12f, lw * 0.12f, p)
        }
        if (body == BodyType.PICKUP) {
            p.color = darken(color, 0.35f)
            c.drawRect(body0.left + inset, roofBottom + lh * 0.04f, body0.right - inset, body0.bottom - lh * 0.05f, p)
        }
        // headlights / tail lights
        p.color = Color.rgb(255, 246, 200)
        c.drawRoundRect(RectF(body0.left + inset * 0.5f, body0.top + 1.5f * density, body0.left + lw * 0.3f, body0.top + 4f * density), 2f, 2f, p)
        c.drawRoundRect(RectF(body0.right - lw * 0.3f, body0.top + 1.5f * density, body0.right - inset * 0.5f, body0.top + 4f * density), 2f, 2f, p)
        p.color = Color.rgb(255, 59, 74)
        c.drawRoundRect(RectF(body0.left + inset * 0.5f, body0.bottom - 4f * density, body0.left + lw * 0.3f, body0.bottom - 1.5f * density), 2f, 2f, p)
        c.drawRoundRect(RectF(body0.right - lw * 0.3f, body0.bottom - 4f * density, body0.right - inset * 0.5f, body0.bottom - 1.5f * density), 2f, 2f, p)
        bmp
    }

    /** Round avatar-style marker for people without a selected vehicle. */
    fun personBitmap(initials: String, bgColor: Int, density: Float): Bitmap = cache.getOrPut("p-$initials-$bgColor-$density") {
        val s = (34 * density).roundToInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = bgColor
        c.drawCircle(s / 2f, s / 2f, s / 2f - 2.5f * density, p)
        p.color = Color.rgb(14, 16, 20)
        p.textSize = 13 * density
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        c.drawText(initials, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        bmp
    }

    /** Arrow showing heading, drawn under the vehicle sprite. */
    fun headingBitmap(color: Int, density: Float): Bitmap = cache.getOrPut("h-$color-$density") {
        val s = (56 * density).roundToInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
        val path = android.graphics.Path().apply {
            moveTo(s / 2f, 0f); lineTo(s * 0.78f, s * 0.42f); lineTo(s * 0.22f, s * 0.42f); close()
        }
        c.drawPath(path, p)
        bmp
    }

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).roundToInt(), (Color.green(c) + (255 - Color.green(c)) * f).roundToInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).roundToInt(),
    )
    private fun darken(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * (1 - f)).roundToInt(), (Color.green(c) * (1 - f)).roundToInt(), (Color.blue(c) * (1 - f)).roundToInt(),
    )
}
