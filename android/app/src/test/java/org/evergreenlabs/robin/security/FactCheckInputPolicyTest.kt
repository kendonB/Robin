package org.evergreenlabs.robin.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FactCheckInputPolicyTest {
    @Test
    fun ordinaryPostProducesTheExactBoundedSnapshotForConsent() {
        val input = FactCheckInputPolicy.validate(
            text = "  A claim with mātauranga\nand a second line.  ",
            author = "  A. Reader  ",
            tweetURL = "HTTPS://X.COM/Reader/status/123456789?s=20#tracking",
            externalLinks = listOf("https://news.example.org/story?a=1&b=2", "https://news.example.org/story?a=1&b=2"),
        )!!
        assertEquals("A claim with mātauranga\nand a second line.", input.text)
        assertEquals("A. Reader", input.author)
        assertEquals("https://x.com/Reader/status/123456789", input.tweetURL)
        assertEquals(listOf("https://news.example.org/story?a=1&b=2"), input.externalLinks)
    }

    @Test
    fun postAndAuthorLimitsAreEnforcedBeforeTrimming() {
        assertNotNull(FactCheckInputPolicy.validate("x".repeat(FactCheckInputPolicy.MAX_TEXT_CHARS)))
        assertNull(FactCheckInputPolicy.validate("x".repeat(FactCheckInputPolicy.MAX_TEXT_CHARS + 1)))
        assertNull(FactCheckInputPolicy.validate(" ".repeat(FactCheckInputPolicy.MAX_TEXT_CHARS) + "x"))
        assertNotNull(FactCheckInputPolicy.validate("claim", author = "a".repeat(FactCheckInputPolicy.MAX_AUTHOR_CHARS)))
        assertNull(FactCheckInputPolicy.validate("claim", author = "a".repeat(FactCheckInputPolicy.MAX_AUTHOR_CHARS + 1)))
        assertNull(FactCheckInputPolicy.validate(" \r\n\t "))
    }

    @Test
    fun unsafeControlCharactersCannotBeHiddenInTheConsentSnapshot() {
        for (control in listOf('\u0000', '\u0001', '\u001b', '\u007f')) {
            assertNull(FactCheckInputPolicy.validate("claim${control}text"))
            assertNull(FactCheckInputPolicy.validate("claim", author = "name${control}text"))
        }
        assertNotNull(FactCheckInputPolicy.validate("Line one\nLine two\twith a tab\r\n"))
    }

    @Test
    fun absentOptionalFieldsRemainAbsent() {
        val input = FactCheckInputPolicy.validate("claim", author = "  ", tweetURL = " ")!!
        assertNull(input.author)
        assertNull(input.tweetURL)
        assertEquals(emptyList<String>(), input.externalLinks)
    }

    @Test
    fun permalinksRequireAnExactXHostAndStatusPath() {
        val invalid = listOf(
            "http://x.com/person/status/123",
            "https://x.com.evil.example/person/status/123",
            "https://evil.example/x.com/person/status/123",
            "https://x.com@evil.example/person/status/123",
            "https://x.com/settings/password",
            "https://x.com/person/status/not-a-number",
            "https://x.com/person/status/123/extra",
            "https://x.com/person%2fextra/status/123",
            "https://x.com:8443/person/status/123",
            "javascript:alert(1)",
        )
        for (url in invalid) assertNull(url, FactCheckInputPolicy.validate("claim", tweetURL = url))
        assertNotNull(FactCheckInputPolicy.validate("claim", tweetURL = "https://twitter.com/i/status/123"))
    }

    @Test
    fun aSingleUnsafeLinkRejectsTheWholeRequest() {
        for (url in listOf(
            "http://news.example.org/story",
            "javascript:alert(1)",
            "intent://example#Intent;scheme=https;end",
            "file:///data/data/org.evergreenlabs.robin/shared_prefs/private.xml",
            "content://private.provider/secret",
            "https://user:password@news.example.org/story",
            "https://127.0.0.1/admin",
            "https://router.local/admin",
            "https://news.example.org/%0d%0aHeader:value",
        )) {
            assertNull(url, FactCheckInputPolicy.validate("claim", externalLinks = listOf("https://news.example.org/valid", url)))
        }
    }

    @Test
    fun linkLimitsApplyBeforeDeduplicationAndUntrustedListIsCopied() {
        val repeated = List(FactCheckInputPolicy.MAX_LINKS) { "https://news.example.org/story" }
        assertNotNull(FactCheckInputPolicy.validate("claim", externalLinks = repeated))
        assertNull(FactCheckInputPolicy.validate("claim", externalLinks = repeated + repeated.first()))
        assertNull(FactCheckInputPolicy.validate("claim", externalLinks = listOf("https://news.example.org/" + "a".repeat(2_048))))

        val supplied = mutableListOf("https://news.example.org/original")
        val approved = FactCheckInputPolicy.validate("claim", externalLinks = supplied)!!
        supplied[0] = "https://attacker.example/changed"
        assertEquals(listOf("https://news.example.org/original"), approved.externalLinks)
    }
}
