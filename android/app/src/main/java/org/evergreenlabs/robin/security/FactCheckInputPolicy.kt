package org.evergreenlabs.robin.security

/** A bounded snapshot of the post that the native confirmation screen can approve. */
data class ValidatedFactCheckInput(
    val text: String,
    val author: String?,
    val tweetURL: String?,
    val externalLinks: List<String>,
)

/** Pure validation, shared by the web-message boundary, native UI and AI service. */
object FactCheckInputPolicy {
    const val MAX_TEXT_CHARS = 12_000
    const val MAX_AUTHOR_CHARS = 200
    const val MAX_LINKS = 10
    const val MAX_URL_CHARS = 2_048
    const val MAX_BRIDGE_PAYLOAD_BYTES = 65_536

    private val permalinkPath = Regex("^/(?:i|[A-Za-z0-9_]{1,15})/status/[0-9]{1,30}/?$")

    fun validate(
        text: String,
        author: String? = null,
        tweetURL: String? = null,
        externalLinks: List<String> = emptyList(),
    ): ValidatedFactCheckInput? {
        // Check limits before trimming or copying untrusted values.
        if (text.length > MAX_TEXT_CHARS || text.hasUnsafeControls()) return null
        val cleanText = text.trim().takeIf { it.isNotEmpty() } ?: return null
        if (author != null && (author.length > MAX_AUTHOR_CHARS || author.hasUnsafeControls())) return null
        val cleanAuthor = author?.trim()?.takeIf { it.isNotEmpty() }
        val cleanPermalink = if (tweetURL.isNullOrBlank()) {
            if (tweetURL != null && tweetURL.length > MAX_URL_CHARS) return null
            null
        } else {
            val uri = WebUrlPolicy.parseHttps(tweetURL) ?: return null
            if (uri.host !in setOf("x.com", "twitter.com")) return null
            if (!permalinkPath.matches(uri.path.orEmpty())) return null
            // Query strings/fragments are not needed to identify the selected post.
            "https://${uri.host}${uri.path}"
        }
        if (externalLinks.size > MAX_LINKS) return null
        val cleanLinks = externalLinks.map { WebUrlPolicy.safeHttpsUrl(it) ?: return null }.distinct()
        return ValidatedFactCheckInput(cleanText, cleanAuthor, cleanPermalink, cleanLinks)
    }

    private fun String.hasUnsafeControls(): Boolean = any {
        (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') || it.code == 0x7f
    }
}
