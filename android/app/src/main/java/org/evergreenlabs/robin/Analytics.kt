package org.evergreenlabs.robin

/**
 * The personal Android build has no analytics or crash-reporting transport.
 * Keep call sites harmless when shared UI behaviour records a local event.
 * There is deliberately no configuration switch that can enable uploads.
 */
object Analytics {
    @Suppress("UNUSED_PARAMETER")
    fun track(event: String, props: Map<String, Any>? = null) = Unit
}
