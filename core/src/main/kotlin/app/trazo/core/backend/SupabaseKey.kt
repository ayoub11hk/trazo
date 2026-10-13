package app.trazo.core.backend

import java.util.Base64

/** Tells public client keys (anon / publishable) apart from server secrets that must never reach the app. */
object SupabaseKey {
    enum class Kind { PUBLIC, SECRET, UNKNOWN }

    fun classify(raw: String): Kind {
        val key = raw.trim()
        if (key.startsWith("sb_secret_")) return Kind.SECRET
        if (key.startsWith("sb_publishable_")) return if (key.length > 20) Kind.PUBLIC else Kind.UNKNOWN
        val parts = key.split('.')
        if (parts.size != 3) return Kind.UNKNOWN
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1].trimEnd('='))) }.getOrNull()
            ?: return Kind.UNKNOWN
        val role = Regex(""""role"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
        return when (role) {
            "anon" -> Kind.PUBLIC
            null -> Kind.UNKNOWN
            else -> Kind.SECRET
        }
    }

    /** Accepts the address as the dashboard shows it, e.g. with a trailing /rest/v1/, and keeps only the origin. */
    fun normalizeProjectUrl(raw: String): String? =
        Regex("""(https://[A-Za-z0-9.-]+(:\d+)?)(/.*)?""").matchEntire(raw.trim())?.groupValues?.get(1)

    fun isProjectUrl(raw: String): Boolean = normalizeProjectUrl(raw) != null
}
