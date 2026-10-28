package org.evergreenlabs.robin.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBridgeRateLimiterTest {
    @Test fun `envelope flood is bounded before JSON parsing`() {
        val limiter = WebBridgeRateLimiter()
        repeat(30) { assertTrue(limiter.acceptEnvelope(1_000)) }
        assertFalse(limiter.acceptEnvelope(1_999))
        assertTrue(limiter.acceptEnvelope(2_000))
        assertFalse(limiter.acceptEnvelope(999))
        assertFalse(limiter.acceptEnvelope(-1))
    }

    @Test fun `scroll spam cannot starve other accepted UI channels`() {
        val limiter = WebBridgeRateLimiter()
        assertTrue(limiter.accept(WebMessageKind.SCROLL, 10_000))
        assertFalse(limiter.accept(WebMessageKind.SCROLL, 10_001))
        assertTrue(limiter.accept(WebMessageKind.BOOT_READY, 10_001))
        assertTrue(limiter.accept(WebMessageKind.SCROLL, 10_075))
        assertFalse(limiter.accept(WebMessageKind.SCROLL, 9_000))
    }

    @Test fun `fact-check requests have both cooldown and rolling minute limits`() {
        val limiter = WebBridgeRateLimiter()
        assertTrue(limiter.accept(WebMessageKind.FACT_CHECK, 1_000))
        assertFalse(limiter.accept(WebMessageKind.FACT_CHECK, 3_999))
        for (time in listOf(4_000L, 7_000L, 10_000L, 13_000L)) {
            assertTrue(limiter.accept(WebMessageKind.FACT_CHECK, time))
        }
        assertFalse(limiter.accept(WebMessageKind.FACT_CHECK, 16_000))
        assertFalse(limiter.accept(WebMessageKind.FACT_CHECK, 60_999))
        assertTrue(limiter.accept(WebMessageKind.FACT_CHECK, 61_000))
    }
}
