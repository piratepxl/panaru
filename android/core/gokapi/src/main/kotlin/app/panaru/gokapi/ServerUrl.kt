package app.panaru.gokapi

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validazione e normalizzazione dell'indirizzo di un server Gokapi.
 *
 * - senza schema si assume `https://`;
 * - `http://` è accettato **solo** per indirizzi di rete locale (LAN, localhost, `.local`):
 *   su Internet la chiave API viaggerebbe in chiaro;
 * - credenziali nell'URL, query e frammenti vengono rifiutati/rimossi;
 * - i suffissi copiati dal browser (`/admin`, `/login`, `/api`, `/d?id=…`) vengono rimossi.
 */
object ServerUrl {
    sealed interface Result {
        data class Valid(val url: HttpUrl, val isInsecureLocal: Boolean) : Result {
            /** Forma canonica salvata nel database, senza slash finale. */
            val canonical: String get() = url.toString().trimEnd('/')
        }
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { EMPTY, MALFORMED, UNSUPPORTED_SCHEME, CREDENTIALS_IN_URL, INSECURE_PUBLIC_HOST }

    private val STRIP_SUFFIXES = listOf("/api", "/admin", "/login", "/d", "/uploadRequest", "/apiKeys", "/users", "/logs")

    fun parse(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://").find(trimmed)
        val withScheme = when {
            schemeMatch == null -> "https://$trimmed"
            schemeMatch.groupValues[1].lowercase() in setOf("http", "https") -> trimmed
            else -> return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
        }
        val url = withScheme.toHttpUrlOrNull() ?: return Result.Invalid(Reason.MALFORMED)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Result.Invalid(Reason.CREDENTIALS_IN_URL)
        if (url.host.isBlank()) return Result.Invalid(Reason.MALFORMED)

        var path = url.encodedPath.trimEnd('/')
        var changed: Boolean
        do {
            changed = false
            for (suffix in STRIP_SUFFIXES) {
                if (path.endsWith(suffix, ignoreCase = true)) {
                    path = path.dropLast(suffix.length).trimEnd('/')
                    changed = true
                }
            }
        } while (changed)
        val normalized = url.newBuilder()
            .encodedPath(if (path.isEmpty()) "/" else "$path/")
            .query(null)
            .fragment(null)
            .build()

        val insecure = !normalized.isHttps
        if (insecure && !isLocalHost(normalized.host)) return Result.Invalid(Reason.INSECURE_PUBLIC_HOST)
        return Result.Valid(normalized, insecure)
    }

    /** Host appartenenti a reti private/locali (RFC 1918, loopback, link-local, ULA IPv6, mDNS). */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")
        ) return true
        val v4 = h.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size == 4 && h.count { it == '.' } == 3 && v4.all { it in 0..255 }) {
            val (a, b) = v4
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127) // include CGNAT/Tailscale
        }
        if (':' in h) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }
        return false
    }
}
