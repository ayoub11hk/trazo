package app.trazo.map

import android.graphics.Bitmap
import app.trazo.core.geo.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

private fun LatLng.pt(): Point = Point.fromLngLat(lng, lat)

/** Colours are ARGB ints so this file stays independent of Compose. */
data class RouteColors(val measured: Int, val smoothed: Int, val gap: Int, val snapped: Int, val progress: Int, val casing: Int)

object MapLayers {
    // ------------------------------------------------------------------ routes

    fun ensureRoute(style: Style, id: String, colors: RouteColors, widthPx: Float = 5f) {
        if (style.getSource("$id-measured") != null) return
        listOf("measured", "gaps", "snapped", "progress", "ends", "rejected").forEach {
            style.addSource(GeoJsonSource("$id-$it", FeatureCollection.fromFeatures(emptyList())))
        }
        style.addLayer(LineLayer("$id-casing", "$id-measured").withProperties(
            PropertyFactory.lineColor(colors.casing), PropertyFactory.lineWidth(widthPx + 3f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.55f),
        ))
        style.addLayer(LineLayer("$id-measured-line", "$id-measured").withProperties(
            PropertyFactory.lineColor(colors.measured), PropertyFactory.lineWidth(widthPx),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addLayer(LineLayer("$id-snapped-line", "$id-snapped").withProperties(
            PropertyFactory.lineColor(colors.snapped), PropertyFactory.lineWidth(widthPx * 0.7f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1f)), PropertyFactory.lineOpacity(0.9f),
        ))
        style.addLayer(LineLayer("$id-gaps-line", "$id-gaps").withProperties(
            PropertyFactory.lineColor(colors.gap), PropertyFactory.lineWidth(widthPx * 0.6f),
            PropertyFactory.lineDasharray(arrayOf(0.5f, 2f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("$id-progress-line", "$id-progress").withProperties(
            PropertyFactory.lineColor(colors.progress), PropertyFactory.lineWidth(widthPx + 1.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addLayer(CircleLayer("$id-rejected-dots", "$id-rejected").withProperties(
            PropertyFactory.circleRadius(2.5f), PropertyFactory.circleColor(colors.gap), PropertyFactory.circleOpacity(0.7f),
        ))
        style.addLayer(CircleLayer("$id-ends-dots", "$id-ends").withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
            PropertyFactory.circleColor(Expression.match(Expression.get("kind"), Expression.color(colors.measured),
                Expression.stop("start", Expression.color(0xFF3DDC84.toInt())),
                Expression.stop("end", Expression.color(0xFFFF5C6C.toInt())))),
        ))
    }

    fun setRoute(
        style: Style, id: String, measured: List<List<LatLng>>, gaps: List<Pair<LatLng, LatLng>>,
        start: LatLng?, end: LatLng?, rejected: List<LatLng> = emptyList(), showRejected: Boolean = false,
    ) {
        source(style, "$id-measured")?.setGeoJson(
            Feature.fromGeometry(MultiLineString.fromLngLats(measured.filter { it.size >= 2 }.map { seg -> seg.map { it.pt() } })),
        )
        source(style, "$id-gaps")?.setGeoJson(FeatureCollection.fromFeatures(gaps.map { (a, b) ->
            Feature.fromGeometry(LineString.fromLngLats(listOf(a.pt(), b.pt())))
        }))
        source(style, "$id-ends")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(
            start?.let { Feature.fromGeometry(it.pt()).apply { addStringProperty("kind", "start") } },
            end?.let { Feature.fromGeometry(it.pt()).apply { addStringProperty("kind", "end") } },
        )))
        source(style, "$id-rejected")?.setGeoJson(FeatureCollection.fromFeatures(
            if (showRejected) rejected.map { Feature.fromGeometry(it.pt()) } else emptyList(),
        ))
    }

    fun setSnapped(style: Style, id: String, line: List<LatLng>?) {
        source(style, "$id-snapped")?.setGeoJson(
            if (line == null || line.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line.map { it.pt() }))),
        )
    }

    /** Played part of a route; one line per measured segment so signal gaps are never bridged. */
    fun setProgress(style: Style, id: String, segments: List<List<LatLng>>) {
        val lines = segments.filter { it.size >= 2 }
        source(style, "$id-progress")?.setGeoJson(
            if (lines.isEmpty()) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(MultiLineString.fromLngLats(lines.map { seg -> seg.map { it.pt() } }))),
        )
    }

    fun setMeasuredVisible(style: Style, id: String, visible: Boolean) {
        val v = if (visible) Property.VISIBLE else Property.NONE
        style.getLayer("$id-measured-line")?.setProperties(PropertyFactory.visibility(v))
        style.getLayer("$id-casing")?.setProperties(PropertyFactory.visibility(v))
    }

    // ------------------------------------------------------------------ people / vehicles

    data class Marker(
        val id: String,
        val position: LatLng,
        val icon: String,
        val bearing: Double?,
        val label: String,
        /** "live", "recent", "stale" or "paused". */
        val freshness: String,
        val selected: Boolean,
    )

    fun ensureMarkers(style: Style, id: String, labelColor: Int, haloColor: Int, clusterColor: Int, cluster: Boolean = true) {
        if (style.getSource(id) != null) return
        style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()),
            GeoJsonOptions().withCluster(cluster).withClusterRadius(48).withClusterMaxZoom(13)))
        style.addLayer(CircleLayer("$id-clusters", id).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.circleColor(clusterColor), PropertyFactory.circleRadius(18f),
            PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor(haloColor),
        ))
        style.addLayer(SymbolLayer("$id-cluster-count", id).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
            PropertyFactory.textSize(13f), PropertyFactory.textColor(0xFF0E1014.toInt()),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
        ))
        style.addLayer(SymbolLayer("$id-symbols", id).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconRotate(Expression.coalesce(Expression.get("bearing"), Expression.literal(0f))),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(Expression.switchCase(Expression.get("selected"), Expression.literal(1.25f), Expression.literal(1f))),
            PropertyFactory.iconOpacity(Expression.match(Expression.get("fresh"), Expression.literal(1f),
                Expression.stop("stale", Expression.literal(0.45f)),
                Expression.stop("paused", Expression.literal(0.55f)))),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 2.4f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor(labelColor),
            PropertyFactory.textHaloColor(haloColor),
            PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textOptional(true),
        ))
    }

    fun addImageIfMissing(style: Style, name: String, bmp: () -> Bitmap) {
        if (style.getImage(name) == null) style.addImage(name, bmp())
    }

    fun setMarkers(style: Style, id: String, markers: List<Marker>) {
        source(style, id)?.setGeoJson(FeatureCollection.fromFeatures(markers.map { m ->
            Feature.fromGeometry(m.position.pt(), null, m.id).apply {
                addStringProperty("uid", m.id)
                addStringProperty("icon", m.icon)
                m.bearing?.let { addNumberProperty("bearing", it) }
                addStringProperty("label", m.label)
                addStringProperty("fresh", m.freshness)
                addBooleanProperty("selected", m.selected)
            }
        }))
    }

    // ------------------------------------------------------------------ heatmap

    fun ensureHeatmap(style: Style, id: String) {
        if (style.getSource(id) != null) return
        style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(HeatmapLayer("$id-heat", id).withProperties(
            PropertyFactory.heatmapWeight(Expression.interpolate(Expression.linear(), Expression.get("w"),
                Expression.stop(1, 0.2f), Expression.stop(50, 1f))),
            PropertyFactory.heatmapIntensity(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(5, 0.6f), Expression.stop(15, 2.2f))),
            PropertyFactory.heatmapRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(5, 4f), Expression.stop(15, 18f))),
            PropertyFactory.heatmapColor(Expression.interpolate(Expression.linear(), Expression.heatmapDensity(),
                Expression.stop(0, Expression.rgba(0, 0, 0, 0)),
                Expression.stop(0.2, Expression.rgb(14, 70, 110)),
                Expression.stop(0.45, Expression.rgb(57, 198, 255)),
                Expression.stop(0.75, Expression.rgb(184, 255, 60)),
                Expression.stop(1, Expression.rgb(255, 255, 255)))),
            PropertyFactory.heatmapOpacity(0.85f),
        ))
    }

    fun setHeatmap(style: Style, id: String, cells: List<Pair<LatLng, Int>>) {
        source(style, id)?.setGeoJson(FeatureCollection.fromFeatures(cells.map { (p, w) ->
            Feature.fromGeometry(p.pt()).apply { addNumberProperty("w", w) }
        }))
    }

    private fun source(style: Style, id: String): GeoJsonSource? = style.getSourceAs(id)
}
