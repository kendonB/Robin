package org.evergreenlabs.robin.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNativeMessagePolicyTest {
    private fun parse(raw: String, ai: Boolean = false, debug: Boolean = false) =
        WebNativeMessagePolicy.parse(raw, ai, debug)

    private fun factCheck(
        text: String = "A claim selected by the user.",
        url: String = "https://x.com/example/status/123456789",
        links: List<String> = listOf("https://example.com/evidence"),
    ): String = JsonObject(mapOf(
        "type" to JsonPrimitive("factCheck"),
        "payload" to JsonObject(mapOf(
            "text" to JsonPrimitive(text),
            "author" to JsonPrimitive("Example"),
            "url" to JsonPrimitive(url),
            "links" to JsonArray(links.map(::JsonPrimitive)),
        )),
    )).toString()

    @Test fun `only recognised bounded UI messages are accepted`() {
        assertEquals(WebNativeMessage.Scroll(120), parse("""{"type":"scroll","y":120}"""))
        assertEquals(WebNativeMessage.Interact, parse("""{"type":"interact"}"""))
        assertEquals(WebNativeMessage.BootReady("boot-a_1.2"), parse("""{"type":"bootReady","nonce":"boot-a_1.2"}"""))
        assertEquals(WebNativeMessage.BootRetry("abc"), parse("""{"type":"bootRetry","nonce":"abc"}"""))
        for (raw in listOf(
            """{"type":"scroll","y":-1}""",
            """{"type":"scroll","y":10000001}""",
            """{"type":"scroll","y":1.5}""",
            """{"type":"scroll","y":"120"}""",
            """{"type":"scroll","y":true}""",
            """{"type":"interact","url":"https://example.com"}""",
            """{"type":"bootReady","nonce":""}""",
            """{"type":"bootReady","nonce":"bad nonce"}""",
            """{"type":"sentry","message":"secret"}""",
            """{"type":"exec","command":"anything"}""",
            """{"type":null}""",
            "[]", "null", "not JSON", "{",
        )) assertNull(raw, parse(raw))
    }

    @Test fun `AI channel is off unless deliberately enabled`() {
        val payload = factCheck()
        assertNull(parse(payload, ai = false))
        val message = parse(payload, ai = true)
        assertTrue(message is WebNativeMessage.FactCheck)
        assertEquals("A claim selected by the user.", (message as WebNativeMessage.FactCheck).input.text)
    }

    @Test fun `AI messages validate source destination sizes and JSON types`() {
        assertNull(parse(factCheck(text = "x".repeat(FactCheckInputPolicy.MAX_TEXT_CHARS + 1)), ai = true))
        assertNull(parse(factCheck(url = "https://evil.example/status/123"), ai = true))
        assertNull(parse(factCheck(links = listOf("javascript:alert(1)")), ai = true))
        assertNull(parse(factCheck(links = List(FactCheckInputPolicy.MAX_LINKS + 1) { "https://example.com/$it" }), ai = true))
        assertNull(parse("""{"type":"factCheck","payload":{"text":"A claim","author":42}}""", ai = true))
        assertNull(parse("""{"type":"factCheck","payload":{"text":"A claim","links":[42]}}""", ai = true))
        assertNull(parse("""{"type":"factCheck","payload":{"text":"A claim","command":"extra"}}""", ai = true))
        assertNotNull(parse("""{"type":"factCheck","payload":{"text":"A claim","author":null,"url":null,"links":[]}}""", ai = true))
    }

    @Test fun `benchmark channel is debug only and accepts the shared filter integer payload`() {
        val payload = """{"type":"benchmark","payload":{"initialLoadMs":350,"scrollRenderCompletionMs":160,"timestamp":1793145600000,"bootReady":true}}"""
        assertNull(parse(payload, debug = false))
        assertEquals(WebNativeMessage.Benchmark(350, 160, 1793145600000L, true), parse(payload, debug = true))
        assertNull(parse(payload.replace("350", "-1"), debug = true))
        assertNull(parse(payload.replace("160", "3600001"), debug = true))
        assertNull(parse(payload.replace("true", "\"true\""), debug = true))
        assertNull(parse(payload.replace("350", "350.5"), debug = true))
    }

    @Test fun `malformed huge and deeply nested messages fail closed`() {
        assertNull(parse(" ".repeat(WebNativeMessagePolicy.MAX_MESSAGE_BYTES + 1)))
        assertNull(parse(factCheck(text = "文".repeat(23_000)), ai = true))
        assertNull(parse("[".repeat(50) + "0" + "]".repeat(50)))
        val longNonce = JsonObject(mapOf(
            "type" to JsonPrimitive("bootReady"),
            "nonce" to JsonPrimitive("a".repeat(129)),
        )).toString()
        assertNull(parse(longNonce))
        // Braces inside a JSON string must not be mistaken for structural depth.
        assertNotNull(parse(factCheck(text = "A literal {[ quote \\\" value is data."), ai = true))
    }
}
