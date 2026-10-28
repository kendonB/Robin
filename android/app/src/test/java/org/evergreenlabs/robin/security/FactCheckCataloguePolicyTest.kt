package org.evergreenlabs.robin.security

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FactCheckCataloguePolicyTest {
    @Test
    fun onlySuccessfulResponsesFromTheFixedDownloadUrlAreAccepted() {
        val url = FactCheckCataloguePolicy.FEED_URL
        assertTrue(FactCheckCataloguePolicy.acceptsResponse(200, url, -1))
        assertTrue(FactCheckCataloguePolicy.acceptsResponse(200, url, FactCheckCataloguePolicy.MAX_BYTES.toLong()))
        for (status in listOf(201, 301, 302, 307, 308, 404, 500)) {
            assertFalse(FactCheckCataloguePolicy.acceptsResponse(status, url, -1))
        }
        assertFalse(FactCheckCataloguePolicy.acceptsResponse(200, "https://attacker.example/catalogue.json", -1))
        assertFalse(FactCheckCataloguePolicy.acceptsResponse(200, url.replace("https:", "http:"), -1))
        assertFalse(FactCheckCataloguePolicy.acceptsResponse(200, url, -2))
        assertFalse(FactCheckCataloguePolicy.acceptsResponse(200, url, FactCheckCataloguePolicy.MAX_BYTES.toLong() + 1))
    }

    @Test
    fun excessRowsAndDepthNeverReachTheAllocatingDecoder() {
        val tooManyRows = "[" + "{},".repeat(FactCheckCataloguePolicy.MAX_ROWS) + "{}]"
        val tooDeep = "[".repeat(FactCheckJsonPolicy.MAX_DEPTH + 1) + "0" +
            "]".repeat(FactCheckJsonPolicy.MAX_DEPTH + 1)
        for (document in listOf(tooManyRows, tooDeep, "{}", "[] []")) {
            assertThrows(IllegalStateException::class.java) {
                FactCheckCataloguePolicy.decodeBounded(document) {
                    throw AssertionError("The JSON decoder must not run for a rejected document")
                }
            }
        }
        assertTrue(FactCheckCataloguePolicy.decodeBounded("[{}]") { it == "[{}]" })
    }

    @Test
    fun anUnknownLengthResponseStillCannotExceedTheByteLimit() {
        val allowed = ByteArray(FactCheckCataloguePolicy.MAX_BYTES) { (it % 251).toByte() }
        assertArrayEquals(allowed, FactCheckCataloguePolicy.readBounded(ByteArrayInputStream(allowed), 100, nowNanos = { 0 }))
        assertThrows(IllegalStateException::class.java) {
            FactCheckCataloguePolicy.readBounded(ByteArrayInputStream(allowed + byteArrayOf(1)), 100, nowNanos = { 0 })
        }
    }

    @Test
    fun slowTricklesCannotExtendTheDeadlineIndefinitely() {
        var time = 0L
        val trickle = object : InputStream() {
            override fun read(): Int = 'a'.code
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                time += 10
                buffer[offset] = 'a'.code.toByte()
                return 1
            }
        }
        assertThrows(IllegalStateException::class.java) {
            FactCheckCataloguePolicy.readBounded(trickle, 30, nowNanos = { time })
        }
    }

    @Test
    fun alreadyExpiredOrCancelledRequestsNeverReadAnyBytes() {
        val forbiddenRead = object : InputStream() {
            override fun read(): Int = throw AssertionError("The input must not be read")
        }
        assertThrows(IllegalStateException::class.java) {
            FactCheckCataloguePolicy.readBounded(forbiddenRead, 10, nowNanos = { 10 })
        }
        assertThrows(CancellationException::class.java) {
            FactCheckCataloguePolicy.readBounded(forbiddenRead, 100, nowNanos = { 0 }, checkActive = {
                throw CancellationException("Native screen closed")
            })
        }
    }

    @Test
    fun deadlineIsCheckedEvenWhenTheLastReadOnlyReturnsEof() {
        var time = 0L
        val slowEof = object : InputStream() {
            override fun read(): Int {
                time = 200
                return -1
            }
        }
        assertThrows(IllegalStateException::class.java) {
            FactCheckCataloguePolicy.readBounded(slowEof, 100, nowNanos = { time })
        }
    }
}
