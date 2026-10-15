package app.trazo.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import app.trazo.core.geo.LatLng
import app.trazo.ui.components.BodyType
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * People on the main map: a round photo pin floating over a small car that sits exactly on
 * the measured position and turns with the direction of travel, plus each person's live line.
 */
object PeopleLayer {
    const val FRIENDS = "people"
    const val ME = "me"
    private const val TRAILS = "trails"
    private const val ACCURACY = "me-accuracy"

    data class Person(
        val id: String,
        val position: LatLng,
        val bearing: Double?,
        val pinImage: String,
        val carImage: String,
        val label: String,
        /** live, recent, stale or paused. */
        val freshness: String,
        val selected: Boolean,
    )

    data class Trail(val userId: String, val color: Int, val segments: List<List<LatLng>>, val mine: Boolean)

    // ------------------------------------------------------------------ layers

    fun ensure(style: Style, labelColor: Int, haloColor: Int, clusterColor: Int) {
        if (style.getSource(FRIENDS) != null) return
        // Lines go under the map labels so street names stay readable on top of them.
        style.addSource(GeoJsonSource(TRAILS, FeatureCollection.fromFeatures(emptyList())))
        val below = listOf("water-name", "road-label", "poi").firstOrNull { style.getLayer(it) != null }
        fun add(l: Layer) = if (below != null) style.addLayerBelow(l, below) else style.addLayer(l)
        add(LineLayer("$TRAILS-glow", TRAILS).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10, 6f), Expression.stop(16, 14f), Expression.stop(20, 26f))),
            PropertyFactory.lineBlur(6f),
            PropertyFactory.lineOpacity(0.28f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("$TRAILS-casing", TRAILS).withProperties(
            PropertyFactory.lineColor(0xFF05070A.toInt()),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10, 3.5f), Expression.stop(16, 7f), Expression.stop(20, 12f))),
            PropertyFactory.lineOpacity(0.8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("$TRAILS-line", TRAILS).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10, 2f), Expression.stop(16, 4.5f), Expression.stop(20, 8f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))

        style.addSource(GeoJsonSource(ACCURACY, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(CircleLayer("$ACCURACY-circle", ACCURACY).withProperties(
            // Radius in metres converted to pixels at each zoom (Web Mercator, base 2).
            PropertyFactory.circleRadius(Expression.interpolate(Expression.exponential(2f), Expression.zoom(),
                Expression.stop(0, Expression.division(Expression.get("m"), Expression.get("mpp0"))),
                Expression.stop(22, Expression.division(Expression.get("m"), Expression.get("mpp22"))))),
            PropertyFactory.circleColor(0xFFB8FF3C.toInt()),
            PropertyFactory.circleOpacity(0.10f),
            PropertyFactory.circleStrokeColor(0xFFB8FF3C.toInt()),
            PropertyFactory.circleStrokeOpacity(0.35f),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ))

        // Friends, grouped into a counter bubble when zoomed out so faces never pile up.
        style.addSource(GeoJsonSource(FRIENDS, FeatureCollection.fromFeatures(emptyList()),
            GeoJsonOptions().withCluster(true).withClusterRadius(56).withClusterMaxZoom(13)))
        style.addLayer(CircleLayer("$FRIENDS-cluster-halo", FRIENDS).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.circleColor(clusterColor), PropertyFactory.circleOpacity(0.25f), PropertyFactory.circleRadius(28f),
        ))
        style.addLayer(CircleLayer("$FRIENDS-clusters", FRIENDS).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.circleColor(0xFF15181E.toInt()), PropertyFactory.circleRadius(19f),
            PropertyFactory.circleStrokeWidth(2.5f), PropertyFactory.circleStrokeColor(clusterColor),
        ))
        style.addLayer(SymbolLayer("$FRIENDS-cluster-count", FRIENDS).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(14f), PropertyFactory.textColor(clusterColor),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
        ))
        addPersonLayers(style, FRIENDS, labelColor, haloColor, Expression.not(Expression.has("point_count")))
        style.addSource(GeoJsonSource(ME, FeatureCollection.fromFeatures(emptyList())))
        addPersonLayers(style, ME, labelColor, haloColor, null)
    }

    private fun addPersonLayers(style: Style, src: String, labelColor: Int, haloColor: Int, filter: Expression?) {
        val car = SymbolLayer("$src-car", src).withProperties(
            PropertyFactory.iconImage(Expression.get("car")),
            PropertyFactory.iconRotate(Expression.coalesce(Expression.get("bearing"), Expression.literal(0f))),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconOpacity(freshOpacity()),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(11.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
            PropertyFactory.textColor(labelColor),
            PropertyFactory.textHaloColor(haloColor),
            PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textOptional(true),
            PropertyFactory.textOpacity(freshOpacity()),
        )
        val pin = SymbolLayer("$src-pin", src).withProperties(
            PropertyFactory.iconImage(Expression.get("pin")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconOffset(arrayOf(0f, -9f)),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(Expression.switchCase(Expression.get("selected"), Expression.literal(1.15f), Expression.literal(1f))),
            PropertyFactory.symbolSortKey(Expression.switchCase(Expression.get("selected"), Expression.literal(2f), Expression.literal(1f))),
            PropertyFactory.iconOpacity(freshOpacity()),
        )
        if (filter != null) { car.setFilter(filter); pin.setFilter(filter) }
        style.addLayer(car)
        style.addLayer(pin)
    }

    private fun freshOpacity() = Expression.match(Expression.get("fresh"), Expression.literal(1f),
        Expression.stop("stale", Expression.literal(0.6f)),
        Expression.stop("paused", Expression.literal(0.7f)))

    /** Layer ids that react to taps. */
    val tappable = arrayOf("$FRIENDS-pin", "$FRIENDS-car", "$ME-pin", "$FRIENDS-clusters")

    // ------------------------------------------------------------------ data

    fun setPeople(style: Style, source: String, people: List<Person>) {
        style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(FeatureCollection.fromFeatures(people.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.position.lng, p.position.lat), null, p.id).apply {
                addStringProperty("uid", p.id)
                addStringProperty("pin", p.pinImage)
                addStringProperty("car", p.carImage)
                p.bearing?.let { addNumberProperty("bearing", it) }
                addStringProperty("label", p.label)
                addStringProperty("fresh", p.freshness)
                addBooleanProperty("selected", p.selected)
            }
        }))
    }

    fun setTrails(style: Style, trails: List<Trail>) {
        style.getSourceAs<GeoJsonSource>(TRAILS)?.setGeoJson(FeatureCollection.fromFeatures(trails.mapNotNull { t ->
            val lines = t.segments.filter { it.size >= 2 }
            if (lines.isEmpty()) return@mapNotNull null
            Feature.fromGeometry(MultiLineString.fromLngLats(lines.map { seg -> seg.map { Point.fromLngLat(it.lng, it.lat) } })).apply {
                addStringProperty("color", String.format("#%06X", 0xFFFFFF and t.color))
                addBooleanProperty("mine", t.mine)
            }
        }))
    }

    fun setAccuracy(style: Style, center: LatLng?, meters: Float?) {
        val src = style.getSourceAs<GeoJsonSource>(ACCURACY) ?: return
        if (center == null || meters == null || meters <= 0f) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val mpp0 = 156_543.03392 * cos(Math.toRadians(center.lat))
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(center.lng, center.lat)).apply {
            addNumberProperty("m", meters)
            addNumberProperty("mpp0", mpp0)
            addNumberProperty("mpp22", mpp0 / (1 shl 22))
        })
    }

    // ------------------------------------------------------------------ images

    fun addImage(style: Style, name: String, bmp: () -> Bitmap) {
        if (style.getImage(name) == null) style.addImage(name, bmp())
    }

    /** Small top-down car that sits under the photo; rotated on the map by the heading. */
    fun carImageName(body: BodyType, colorHex: String) = "car-${body.name}-${colorHex.removePrefix("#")}"
    fun carBitmap(body: BodyType, colorHex: String, density: Float): Bitmap = VehicleMarkers.bitmap(body, colorHex, density, scale = 0.62f)

    fun pinImageName(userId: String, photoKey: String?, ringColor: Int, freshness: String) =
        "pin-$userId-${photoKey?.hashCode() ?: 0}-$ringColor-$freshness"

    /**
     * Round photo (or initials) with a coloured ring and a pointer underneath. Stale positions
     * turn grey with a clock badge; paused sharing shows a pause badge.
     */
    fun pinBitmap(photo: Bitmap?, name: String, ringColor: Int, freshness: String, density: Float): Bitmap {
        val d = density
        val circle = 46 * d
        val ring = 3.2f * d
        val pointer = 9 * d
        val pad = 4 * d
        val w = (circle + pad * 2).roundToInt()
        val h = (circle + pointer + pad * 2).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val cy = pad + circle / 2f
        val r = circle / 2f
        val dim = freshness == "stale" || freshness == "paused"
        val ringCol = if (dim) Color.rgb(110, 116, 128) else ringColor

        // Shadow, ring and pointer as one shape.
        val shape = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.42f, cy + r * 0.82f)
            lineTo(cx, cy + r + pointer)
            lineTo(cx + r * 0.42f, cy + r * 0.82f)
            close()
        }
        p.color = ringCol
        p.setShadowLayer(4 * d, 0f, 1.5f * d, Color.argb(150, 0, 0, 0))
        c.drawPath(shape, p)
        p.clearShadowLayer()

        val inner = r - ring
        if (photo != null) {
            val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = (inner * 2) / minOf(photo.width, photo.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - photo.width * scale / 2, cy - photo.height * scale / 2)
            })
            p.shader = shader
            if (dim) p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            c.drawCircle(cx, cy, inner, p)
            p.shader = null
            p.colorFilter = null
        } else {
            p.color = if (dim) Color.rgb(58, 64, 75) else blend(ringColor, Color.rgb(14, 16, 20), 0.15f)
            c.drawCircle(cx, cy, inner, p)
            p.color = Color.rgb(14, 16, 20)
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 17 * d
            p.textAlign = Paint.Align.CENTER
            val initials = name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
            c.drawText(initials, cx, cy - (p.descent() + p.ascent()) / 2, p)
        }

        if (dim) {
            val br = 8.5f * d
            val bx = cx + r * 0.72f
            val by = cy - r * 0.72f
            p.color = Color.rgb(21, 24, 30)
            c.drawCircle(bx, by, br, p)
            p.color = Color.rgb(190, 196, 206)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.6f * d
            p.strokeCap = Paint.Cap.ROUND
            if (freshness == "paused") {
                c.drawLine(bx - 2.2f * d, by - 3.2f * d, bx - 2.2f * d, by + 3.2f * d, p)
                c.drawLine(bx + 2.2f * d, by - 3.2f * d, bx + 2.2f * d, by + 3.2f * d, p)
            } else {
                c.drawCircle(bx, by, 4.8f * d, p)
                c.drawLine(bx, by, bx, by - 3f * d, p)
                c.drawLine(bx, by, bx + 2.3f * d, by, p)
            }
            p.style = Paint.Style.FILL
        }
        return bmp
    }

    private fun blend(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) * (1 - f) + Color.red(b) * f).roundToInt(),
        (Color.green(a) * (1 - f) + Color.green(b) * f).roundToInt(),
        (Color.blue(a) * (1 - f) + Color.blue(b) * f).roundToInt(),
    )
}
