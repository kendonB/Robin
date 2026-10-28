package org.evergreenlabs.robin.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactCheckOutputPolicyTest {
    @Test
    fun modelSourcesCannotLaunchPrivilegedSchemesOrLocalLiteralAddresses() {
        for (url in listOf(
            "javascript:alert(document.cookie)",
            "data:text/html,hello",
            "file:///etc/passwd",
            "content://example.provider/file",
            "intent://open#Intent;scheme=custom;end",
            "http://evidence.example.org/article",
            "https://evidence.example.org@evil.example/article",
            "https://[::1]/admin",
            "https://192.168.0.1/admin",
            "https://server.internal/admin",
            "https://evidence.example.org:8443/article",
        )) assertNull(url, FactCheckOutputPolicy.sourceUrl(url))
        assertEquals("https://evidence.example.org/article", FactCheckOutputPolicy.sourceUrl("HTTPS://EVIDENCE.EXAMPLE.ORG/article"))
    }

    @Test
    fun socialHostFilteringUsesDomainBoundariesNotSubstringMatching() {
        for (host in listOf("x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "t.co")) {
            assertNull(FactCheckOutputPolicy.sourceUrl("https://$host/article"))
        }
        assertNotNull(FactCheckOutputPolicy.sourceUrl("https://fox.com/article"))
        assertNotNull(FactCheckOutputPolicy.sourceUrl("https://evidence.example.org/x.com/article"))
    }

    @Test
    fun catalogueCannotReplaceTheMbfcButtonWithAnAttackerDestination() {
        assertEquals("https://mediabiasfactcheck.com/publisher/", FactCheckOutputPolicy.catalogueUrl("https://mediabiasfactcheck.com/publisher/"))
        assertNotNull(FactCheckOutputPolicy.catalogueUrl("https://www.mediabiasfactcheck.com/publisher/"))
        for (url in listOf(
            "https://mediabiasfactcheck.com.attacker.example/publisher",
            "https://attacker.example/mediabiasfactcheck.com/publisher",
            "https://unreviewed.mediabiasfactcheck.com/publisher",
            "https://mediabiasfactcheck.com@attacker.example/publisher",
            "http://mediabiasfactcheck.com/publisher",
            "javascript:alert(1)",
        )) assertNull(url, FactCheckOutputPolicy.catalogueUrl(url))
    }

    @Test
    fun responseSizeAndDisplayFieldsAreBoundedBeforeRendering() {
        assertFalse(FactCheckOutputPolicy.acceptsResponse(" \n "))
        assertTrue(FactCheckOutputPolicy.acceptsResponse("x".repeat(FactCheckOutputPolicy.MAX_RESPONSE_CHARS)))
        assertFalse(FactCheckOutputPolicy.acceptsResponse("x".repeat(FactCheckOutputPolicy.MAX_RESPONSE_CHARS + 1)))
        assertEquals("one\ntwo", FactCheckOutputPolicy.displayText("one\u0000\u001b\ntwo\u007f", 100))
        assertEquals("Title", FactCheckOutputPolicy.displayText("Title" + "x".repeat(10_000), 5))
    }

    @Test
    fun sourceUrlsRemainWithinTheLimitAfterUnicodePercentEncoding() {
        val longPath = "ā".repeat(350)
        assertNull(FactCheckOutputPolicy.sourceUrl("https://evidence.example.org/$longPath"))
        assertNull(FactCheckOutputPolicy.catalogueUrl("https://mediabiasfactcheck.com/$longPath"))
        assertEquals("https://evidence.example.org/m%C4%81tauranga", FactCheckOutputPolicy.sourceUrl("https://evidence.example.org/mātauranga"))
    }
}
