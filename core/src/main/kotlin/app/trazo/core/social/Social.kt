package app.trazo.core.social

/** Invite codes: 8 characters of Crockford base-32 (no I, L, O, U), shown as XXXX-XXXX. */
object InviteCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8

    /** Normalises what a person typed: case, spaces, dashes and look-alike letters. */
    fun normalize(input: String): String = input.uppercase()
        .filter { it.isLetterOrDigit() }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; 'U' -> 'V'; else -> it } }
        .joinToString("")

    fun isValid(input: String): Boolean {
        val n = normalize(input)
        return n.length == LENGTH && n.all { it in ALPHABET }
    }

    fun format(code: String): String = normalize(code).let { if (it.length == LENGTH) it.take(4) + "-" + it.drop(4) else it }

    /** Extracts a code from an invitation link (trazo://invite/CODE or https://…/i/CODE) or raw text. */
    fun fromLinkOrText(text: String): String? {
        val trimmed = text.trim()
        val candidate = Regex("""(?:invite/|/i/|[?&]code=)([A-Za-z0-9-]{8,9})""").find(trimmed)?.groupValues?.get(1) ?: trimmed
        return normalize(candidate).takeIf { isValid(it) }
    }

    fun link(kind: Kind, code: String) = "trazo://${kind.path}/${normalize(code)}"

    enum class Kind(val path: String) { FRIEND("invite"), GROUP("group"), CONVOY("convoy") }
}

enum class Freshness { LIVE, RECENT, STALE, PAUSED, HIDDEN }

object LocationFreshness {
    const val LIVE_MS = 45_000L
    const val RECENT_MS = 10 * 60_000L

    /**
     * Classifies a friend's last position. A position older than [LIVE_MS] is never shown as
     * live; the UI always prints its age and accuracy.
     */
    fun classify(updatedAtMs: Long?, nowMs: Long, paused: Boolean): Freshness = when {
        paused -> Freshness.PAUSED
        updatedAtMs == null -> Freshness.HIDDEN
        nowMs - updatedAtMs <= LIVE_MS -> Freshness.LIVE
        nowMs - updatedAtMs <= RECENT_MS -> Freshness.RECENT
        else -> Freshness.STALE
    }

    /** Short Spanish age label such as "ahora", "hace 3 min", "hace 2 h", "hace 4 d". */
    fun ageLabel(updatedAtMs: Long, nowMs: Long): String {
        val s = ((nowMs - updatedAtMs) / 1000).coerceAtLeast(0)
        return when {
            s < 10 -> "ahora"
            s < 60 -> "hace $s s"
            s < 3600 -> "hace ${s / 60} min"
            s < 86_400 -> "hace ${s / 3600} h"
            else -> "hace ${s / 86_400} d"
        }
    }
}

/** Signal quality bucket from reported horizontal accuracy. */
enum class SignalQuality { EXCELLENT, GOOD, FAIR, POOR, UNKNOWN;
    companion object {
        fun fromAccuracy(accuracyM: Float?): SignalQuality = when {
            accuracyM == null -> UNKNOWN
            accuracyM <= 5f -> EXCELLENT
            accuracyM <= 12f -> GOOD
            accuracyM <= 30f -> FAIR
            else -> POOR
        }
    }
}
