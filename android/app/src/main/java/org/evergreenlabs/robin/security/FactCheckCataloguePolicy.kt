package org.evergreenlabs.robin.security

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Bounds the one fixed catalogue download; it never fetches a post's article URL. */
object FactCheckCataloguePolicy {
    const val FEED_URL = "https://evergreen-labs.org/assets/factcheck.json"
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ROWS = 20_000
    const val MAX_FIELD_CHARS = 300
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 5_000
    const val TOTAL_TIMEOUT_MS = 12_000L

    fun acceptsResponse(status: Int, finalUrl: String, contentLength: Long): Boolean =
        status == 200 && finalUrl == FEED_URL && contentLength in -1L..MAX_BYTES.toLong()

    /** Reject allocation-heavy array/depth shapes before invoking a JSON decoder. */
    fun <T> decodeBounded(text: String, decode: (String) -> T): T {
        check(text.length <= MAX_BYTES && FactCheckJsonPolicy.acceptsArray(text, MAX_ROWS)) {
            "Unsupported catalogue structure."
        }
        return decode(text)
    }

    fun readBounded(
        input: InputStream,
        deadlineNanos: Long,
        nowNanos: () -> Long = System::nanoTime,
        checkActive: () -> Unit = {},
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            checkActive()
            check(nowNanos() < deadlineNanos) { "Catalogue request timed out." }
            val count = input.read(buffer)
            if (count < 0) break
            check(count <= MAX_BYTES - output.size()) { "Catalogue response is too large." }
            output.write(buffer, 0, count)
        }
        check(nowNanos() < deadlineNanos) { "Catalogue request timed out." }
        return output.toByteArray()
    }

    fun deadlineNanos(startNanos: Long): Long = startNanos + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS)
}
