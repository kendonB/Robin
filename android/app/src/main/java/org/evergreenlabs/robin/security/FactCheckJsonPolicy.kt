package org.evergreenlabs.robin.security

/** Prevent a small but deeply nested remote JSON document from exhausting the parser stack. */
object FactCheckJsonPolicy {
    const val MAX_DEPTH = 16

    // This is a resource guard, not a JSON parser. Serialization still checks syntax and types.
    fun acceptsNesting(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }
}
