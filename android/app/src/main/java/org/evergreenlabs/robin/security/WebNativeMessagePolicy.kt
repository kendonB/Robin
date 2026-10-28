package org.evergreenlabs.robin.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class WebMessageKind { SCROLL, INTERACT, BOOT_READY, BOOT_RETRY, FACT_CHECK, BENCHMARK }

sealed class WebNativeMessage(val kind: WebMessageKind) {
    data class Scroll(val y: Int) : WebNativeMessage(WebMessageKind.SCROLL)
    data object Interact : WebNativeMessage(WebMessageKind.INTERACT)
    data class BootReady(val nonce: String) : WebNativeMessage(WebMessageKind.BOOT_READY)
    data class BootRetry(val nonce: String) : WebNativeMessage(WebMessageKind.BOOT_RETRY)
    data class FactCheck(val input: ValidatedFactCheckInput) : WebNativeMessage(WebMessageKind.FACT_CHECK)
    data class Benchmark(
        val initialLoadMs: Long,
        val scrollRenderCompletionMs: Long,
        val timestamp: Long,
        val bootReady: Boolean,
    ) : WebNativeMessage(WebMessageKind.BENCHMARK)
}

/** Parse only the small protocol implemented by the bundled Android shim. */
object WebNativeMessagePolicy {
    const val MAX_MESSAGE_BYTES = 65_536
    private const val MAX_JSON_DEPTH = 6
    private val noncePattern = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val json = Json { isLenient = false }

    fun parse(raw: String, aiEnabled: Boolean, debugEnabled: Boolean): WebNativeMessage? {
        if (!hasBoundedJsonShape(raw)) return null
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        val type = obj.string("type") ?: return null
        return when (type) {
            "scroll" -> {
                if (obj.keys != setOf("type", "y")) return null
                val value = obj["y"] as? JsonPrimitive ?: return null
                if (value.isString) return null
                val y = value.intOrNull?.takeIf { it in 0..10_000_000 } ?: return null
                WebNativeMessage.Scroll(y)
            }
            "interact" -> {
                if (obj.keys != setOf("type")) return null
                WebNativeMessage.Interact
            }
            "bootReady", "bootRetry" -> {
                if (obj.keys != setOf("type", "nonce")) return null
                val nonce = obj.string("nonce")?.takeIf(noncePattern::matches) ?: return null
                if (type == "bootReady") WebNativeMessage.BootReady(nonce) else WebNativeMessage.BootRetry(nonce)
            }
            "factCheck" -> {
                if (!aiEnabled || obj.keys != setOf("type", "payload")) return null
                val payload = obj["payload"] as? JsonObject ?: return null
                if (!setOf("text", "author", "url", "links").containsAll(payload.keys)) return null
                val text = payload.string("text") ?: return null
                val author = payload.optionalString("author") ?: return null
                val url = payload.optionalString("url") ?: return null
                val linksValue = payload["links"]
                val links = if (linksValue == null || linksValue is JsonNull) {
                    emptyList()
                } else {
                    val array = linksValue as? JsonArray ?: return null
                    if (array.size > FactCheckInputPolicy.MAX_LINKS) return null
                    array.map { element ->
                        val primitive = element as? JsonPrimitive ?: return null
                        if (!primitive.isString) return null
                        primitive.content
                    }
                }
                val input = FactCheckInputPolicy.validate(text, author.value, url.value, links) ?: return null
                WebNativeMessage.FactCheck(input)
            }
            "benchmark" -> {
                if (!debugEnabled || obj.keys != setOf("type", "payload")) return null
                val p = obj["payload"] as? JsonObject ?: return null
                if (p.keys != setOf("initialLoadMs", "scrollRenderCompletionMs", "timestamp", "bootReady")) return null
                val initial = p.number("initialLoadMs")?.takeIf { it in 0..3_600_000L } ?: return null
                val scroll = p.number("scrollRenderCompletionMs")?.takeIf { it in 0..3_600_000L } ?: return null
                val timestamp = p.number("timestamp")?.takeIf { it in 0..4_102_444_800_000L } ?: return null
                val ready = p["bootReady"] as? JsonPrimitive ?: return null
                if (ready.isString) return null
                WebNativeMessage.Benchmark(initial, scroll, timestamp, ready.booleanOrNull ?: return null)
            }
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    // A nullable wrapper distinguishes a missing optional string from a wrong
    // JSON type; silently coercing numbers/objects would widen the protocol.
    private data class OptionalString(val value: String?)
    private fun JsonObject.optionalString(key: String): OptionalString? = when (val value = get(key)) {
        null, JsonNull -> OptionalString(null)
        is JsonPrimitive -> if (value.isString) OptionalString(value.content) else null
        else -> null
    }

    private fun JsonObject.number(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    /** Bound nesting before invoking the JSON parser on untrusted text. */
    private fun hasBoundedJsonShape(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_MESSAGE_BYTES) return false
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in raw) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString && !escaped
    }
}

/** Per-channel limits use elapsed time, so changing the device clock cannot reset them. */
class WebBridgeRateLimiter {
    private val lastAccepted = mutableMapOf<WebMessageKind, Long>()
    private val recentFactChecks = ArrayDeque<Long>()
    private var envelopeWindowStart: Long? = null
    private var envelopesInWindow = 0

    /** Reject floods before parsing their payloads or queueing any UI work. */
    fun acceptEnvelope(elapsedMs: Long): Boolean {
        if (elapsedMs < 0) return false
        val start = envelopeWindowStart
        if (start != null && elapsedMs < start) return false
        if (start == null || elapsedMs - start >= 1_000L) {
            envelopeWindowStart = elapsedMs
            envelopesInWindow = 0
        }
        if (envelopesInWindow >= 30) return false
        envelopesInWindow++
        return true
    }

    fun accept(kind: WebMessageKind, elapsedMs: Long): Boolean {
        if (elapsedMs < 0) return false
        val interval = when (kind) {
            WebMessageKind.SCROLL -> 75L
            WebMessageKind.INTERACT -> 500L
            WebMessageKind.BOOT_READY -> 100L
            WebMessageKind.BOOT_RETRY -> 1_000L
            WebMessageKind.FACT_CHECK -> 3_000L
            WebMessageKind.BENCHMARK -> 1_000L
        }
        val previous = lastAccepted[kind]
        if (previous != null && (elapsedMs < previous || elapsedMs - previous < interval)) return false
        if (kind == WebMessageKind.FACT_CHECK) {
            while (recentFactChecks.isNotEmpty() && elapsedMs - recentFactChecks.first() >= 60_000L) {
                recentFactChecks.removeFirst()
            }
            if (recentFactChecks.size >= 5) return false
            recentFactChecks.addLast(elapsedMs)
        }
        lastAccepted[kind] = elapsedMs
        return true
    }
}

/** Keep compatibility with the shared filter without exposing legacy native objects. */
object WebBridgeScript {
    const val TRANSPORT_NAME = "RobinNativeMessage"

    fun source(aiEnabled: Boolean, debugEnabled: Boolean): String = """
        (function() {
          if (window.top !== window || window.__robinSecureBridgeInstalled) return;
          var transport = window.$TRANSPORT_NAME;
          if (!transport || typeof transport.postMessage !== 'function') return;
          window.__robinSecureBridgeInstalled = true;
          function send(message) {
            try {
              var encoded = JSON.stringify(message);
              if (encoded.length <= ${WebNativeMessagePolicy.MAX_MESSAGE_BYTES}) transport.postMessage(encoded);
            } catch (_) {}
          }
          function parsedPayload(raw) {
            if (typeof raw !== 'string' || raw.length > ${WebNativeMessagePolicy.MAX_MESSAGE_BYTES}) return null;
            try { return JSON.parse(raw); } catch (_) { return null; }
          }
          window.RobinScroll = { post: function(y) { send({type: 'scroll', y: y}); } };
          window.RobinInteract = { post: function() { send({type: 'interact'}); } };
          window.RobinBoot = {
            ready: function(nonce) { send({type: 'bootReady', nonce: nonce}); },
            retry: function(nonce) { send({type: 'bootRetry', nonce: nonce}); }
          };
          if ($aiEnabled) {
            window.RobinFactCheck = { factCheck: function(raw) {
              var payload = parsedPayload(raw);
              if (payload) send({type: 'factCheck', payload: payload});
            } };
          }
          if ($debugEnabled) {
            window.RobinBenchmark = { post: function(raw) {
              var payload = parsedPayload(raw);
              if (payload) send({type: 'benchmark', payload: payload});
            } };
          }
        })();
    """.trimIndent()
}
