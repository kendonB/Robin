package org.evergreenlabs.robin.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlPolicyTest {
    @Test fun `feed trust uses exact HTTPS origins`() {
        for (origin in WebUrlPolicy.feedOrigins) {
            assertTrue(origin, WebUrlPolicy.isFeedUrl("$origin/home?lang=en#feed"))
            assertTrue(origin, WebUrlPolicy.isBridgeOrigin(origin, isMainFrame = true))
            assertFalse(origin, WebUrlPolicy.isBridgeOrigin(origin, isMainFrame = false))
        }
        assertTrue(WebUrlPolicy.isFeedUrl("HTTPS://X.COM:443/home"))
        assertFalse(WebUrlPolicy.isFeedUrl("https://api.x.com/home"))
        assertFalse(WebUrlPolicy.isFeedUrl("https://untrusted.x.com/home"))
        assertFalse(WebUrlPolicy.isFeedUrl("https://x.com.example.com/home"))
        assertFalse(WebUrlPolicy.isFeedUrl("https://notx.com/home"))
    }

    @Test fun `authentication trust excludes public content hosting and guessed country domains`() {
        for (origin in WebUrlPolicy.authOrigins) {
            assertTrue(WebUrlPolicy.isAuthUrl("$origin/signin"))
            assertTrue(WebUrlPolicy.isTrustedMainFrameUrl("$origin/signin"))
            assertFalse(WebUrlPolicy.isBridgeOrigin(origin, isMainFrame = true))
        }
        for (url in listOf(
            "https://storage.googleapis.com/public-user-content/login.html",
            "https://some-project.googleapis.com/",
            "https://sites.google.com/view/untrusted",
            "https://accounts.google.zz/",
            "https://accounts.google.com.example.com/",
            "https://untrusted.apple.com/",
            "https://www.youtube.com/",
        )) {
            assertFalse(url, WebUrlPolicy.isTrustedMainFrameUrl(url))
        }
    }

    @Test fun `ambiguous authority schemes controls and local destinations are rejected`() {
        val rejected = listOf(
            "http://x.com/home",
            "javascript://x.com/%0aalert(1)",
            "data:text/html,<h1>test</h1>",
            "file:///data/data/org.evergreenlabs.robin/",
            "content://x.com/private",
            "intent://x.com/#Intent;scheme=https;end",
            "x-safari-https://x.com/home",
            "blob:https://x.com/document",
            "//x.com/home",
            "https:x.com/home",
            "https:///x.com/home",
            "https://x.com@evil.example/",
            "https://evil.example@x.com/",
            "https://x.com%40evil.example/",
            "https://%78.com/",
            "https://evil.example\\@x.com/",
            "https://x.com\\.evil.example/",
            "https://x.com./home",
            "https://x.com:444/home",
            "https://x.com:-1/home",
            " https://x.com/home",
            "https://x.com/home ",
            "https://x.com/\nother",
            "https://x.com/\tother",
            "https://x.com/%0d%0aHeader:value",
            "https://x.com/%5cother",
            "https://localhost/",
            "https://server.local/",
            "https://server.internal/",
            "https://127.0.0.1/",
            "https://127.1/",
            "https://2130706433/",
            "https://0x7f000001/",
            "https://[::1]/",
            "https://x..com/",
            "https://-x.com/",
            "https://x-.com/",
            "https://х.com/", // Cyrillic lookalike, not an ASCII authority.
        )
        for (url in rejected) {
            assertNull(url, WebUrlPolicy.parseHttps(url))
            assertFalse(url, WebUrlPolicy.isTrustedMainFrameUrl(url))
        }
    }

    @Test fun `external HTTPS URLs preserve escaping without becoming trusted documents`() {
        val url = "HTTPS://NEWS.EXAMPLE.COM:443/a%2Fb?q=one%20two&x=%26#part"
        assertEquals("https://news.example.com:443/a%2Fb?q=one%20two&x=%26#part", WebUrlPolicy.safeHttpsUrl(url))
        assertFalse(WebUrlPolicy.isTrustedMainFrameUrl(url))
        assertNotNull(WebUrlPolicy.safeHttpsUrl("https://xn--bcher-kva.example/article"))
        assertNotNull(WebUrlPolicy.safeHttpsUrl("https://example.com/te%20reo"))
    }

    @Test fun `bridge origin must be origin only and top frame`() {
        for (url in listOf(
            null, "null", "about:blank", "https://x.com/home", "https://x.com?x=1",
            "https://x.com#fragment", "https://accounts.google.com", "https://storage.googleapis.com",
        )) assertFalse(url, WebUrlPolicy.isBridgeOrigin(url, isMainFrame = true))
        assertTrue(WebUrlPolicy.isBridgeOrigin("https://x.com/", isMainFrame = true))
        assertTrue(WebUrlPolicy.isBridgeOrigin("https://x.com:443", isMainFrame = true))
    }

    @Test fun `length is bounded before and after URL ASCII encoding`() {
        assertNull(WebUrlPolicy.safeHttpsUrl("https://example.com/" + "a".repeat(WebUrlPolicy.MAX_URL_CHARS)))
        assertNull(WebUrlPolicy.safeHttpsUrl("https://example.com/" + "文".repeat(300)))
        assertNotNull(WebUrlPolicy.safeHttpsUrl("https://example.com/" + "a".repeat(100)))
    }
}
