package app.trazo.map

import app.trazo.core.geo.LatLng
import app.trazo.core.geo.Simplify
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

/**
 * Optional "snap to roads" through an OSRM-compatible /match service. The result is drawn as
 * a separate, dashed, labelled layer; the recorded GPS track is never replaced by it.
 *
 * The public demo server (router.project-osrm.org) is free but rate-limited and not meant
 * for production; set your own OSRM instance in Ajustes for regular use.
 */
class MapMatching(private val baseUrl: String) {
    private val http = HttpClient(OkHttp)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun match(points: List<LatLng>): List<LatLng>? {
        if (points.size < 2) return null
        // The demo server accepts up to 100 coordinates per request: simplify, then chunk.
        val simplified = Simplify.douglasPeucker(points, 8.0)
        val out = ArrayList<LatLng>()
        for (chunk in simplified.chunked(90)) {
            if (chunk.size < 2) continue
            val coords = chunk.joinToString(";") { "${it.lng},${it.lat}" }
            val radiuses = chunk.joinToString(";") { "25" }
            val body = http.get("$baseUrl/match/v1/driving/$coords?overview=full&geometries=geojson&radiuses=$radiuses&tidy=true").bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            if (root["code"]?.jsonPrimitive?.content != "Ok") return null
            root["matchings"]?.jsonArray?.forEach { m ->
                m.jsonObject["geometry"]?.jsonObject?.get("coordinates")?.jsonArray?.forEach { c ->
                    val a = c.jsonArray
                    out += LatLng(a[1].jsonPrimitive.double, a[0].jsonPrimitive.double)
                }
            }
        }
        return out.takeIf { it.size >= 2 }
    }

    fun close() = http.close()
}
