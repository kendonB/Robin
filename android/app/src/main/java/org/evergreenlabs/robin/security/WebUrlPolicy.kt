package org.evergreenlabs.robin.security

import java.net.URI
import java.util.Locale

/**
 * One strict parser for browser destinations and source links.
 *
 * This validates URL syntax, not DNS resolution. In particular, accepting a DNS
 * name is not a claim that it cannot resolve to a private network address.
 */
object WebUrlPolicy {
    const val MAX_URL_CHARS = 2048

    val feedOrigins: Set<String> = setOf(
        "https://x.com",
        "https://www.x.com",
        "https://mobile.x.com",
        "https://twitter.com",
        "https://www.twitter.com",
        "https://mobile.twitter.com",
    )

    /** Authentication documents only; a provider's CDN is not an auth origin. */
    val authOrigins: Set<String> = setOf(
        "https://accounts.google.com",
        "https://accounts.youtube.com",
        "https://appleid.apple.com",
    )

    private val hostLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val encodedControls = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f|5c)", RegexOption.IGNORE_CASE)
    private val localSuffixes = setOf("localhost", "local", "internal", "lan", "home", "invalid", "test")

    fun parseHttps(raw: String): URI? {
        if (raw.isEmpty() || raw.length > MAX_URL_CHARS) return null
        // Do not trim: browser/parser disagreement must not turn a rejected URL
        // into a valid one after this security check.
        if (raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it == '\\' }) return null
        if (encodedControls.containsMatchIn(raw)) return null
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque) return null
        if (uri.rawUserInfo != null || uri.rawAuthority.isNullOrEmpty()) return null
        // Encoded authority characters and backslashes are interpreted
        // differently by some URL parsers. Never pass them through to WebView.
        if (uri.rawAuthority.any { it == '%' || it == '@' || it == '\\' }) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.length > 253 || host.endsWith('.')) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { !hostLabel.matches(it) }) return null
        // Reject IP literals (including shortened numeric forms), local-only
        // names, and non-ASCII authority spellings. IDNs must use punycode.
        val last = labels.last()
        if (last.all { it.isDigit() } || last in localSuffixes) return null
        if (last.length < 2 || (!last.startsWith("xn--") && last.any { it !in 'a'..'z' })) return null
        return try {
            // Lowercase only scheme/host. Preserve raw path/query/fragment so
            // signed URLs and escaped components are not decoded or rewritten.
            val authority = host + if (uri.port == 443) ":443" else ""
            URI("https://" + authority +
                (uri.rawPath ?: "") +
                (uri.rawQuery?.let { "?$it" } ?: "") +
                (uri.rawFragment?.let { "#$it" } ?: ""))
        } catch (_: Exception) {
            null
        }
    }

    fun safeHttpsUrl(raw: String): String? = parseHttps(raw)?.toASCIIString()?.takeIf { it.length <= MAX_URL_CHARS }

    fun origin(raw: String?): String? {
        val uri = raw?.let(::parseHttps) ?: return null
        return "https://${uri.host}"
    }

    fun isFeedUrl(raw: String?): Boolean = origin(raw)?.let(feedOrigins::contains) == true

    fun isAuthUrl(raw: String?): Boolean = origin(raw)?.let(authOrigins::contains) == true

    fun isTrustedMainFrameUrl(raw: String?): Boolean = isFeedUrl(raw) || isAuthUrl(raw)

    /** A callback origin must be an origin, not a path-shaped URL supplied by JS. */
    fun isBridgeOrigin(raw: String?, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return false
        val uri = raw?.let(::parseHttps) ?: return false
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        return "https://${uri.host}" in feedOrigins
    }
}
