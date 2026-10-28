package org.evergreenlabs.robin.security

/** Deterministic limits; prompt instructions alone never authorise navigation. */
object FactCheckOutputPolicy {
    const val MAX_RESPONSE_CHARS = 65_536
    const val MAX_OUTPUT_TOKENS = 2_048
    const val MAX_SOURCES = 12
    const val MAX_TITLE_CHARS = 200
    const val MAX_VERDICT_CHARS = 200
    const val MAX_CLAIM_CHARS = 1_000
    const val MAX_NOTE_CHARS = 2_000
    const val MAX_BULLET_CHARS = 2_000

    fun acceptsResponse(text: String): Boolean = text.isNotBlank() && text.length <= MAX_RESPONSE_CHARS

    fun sourceUrl(raw: String): String? {
        val uri = WebUrlPolicy.parseHttps(raw) ?: return null
        val host = uri.host
        if (listOf("x.com", "twitter.com", "t.co").any { host == it || host.endsWith(".$it") }) return null
        return WebUrlPolicy.safeHttpsUrl(raw)
    }

    fun catalogueUrl(raw: String): String? {
        val uri = WebUrlPolicy.parseHttps(raw) ?: return null
        return if (uri.host == "mediabiasfactcheck.com" || uri.host == "www.mediabiasfactcheck.com") {
            WebUrlPolicy.safeHttpsUrl(raw)
        } else {
            null
        }
    }

    fun displayText(raw: String, maxChars: Int): String = raw
        .take(maxChars)
        .filterNot { (it.code < 0x20 && it != '\n' && it != '\t') || it.code == 0x7f }
        .trim()
}
