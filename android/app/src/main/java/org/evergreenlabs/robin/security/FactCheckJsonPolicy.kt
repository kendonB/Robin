package org.evergreenlabs.robin.security

/** Prevent a small but deeply nested remote JSON document from exhausting the parser stack. */
object FactCheckJsonPolicy {
    const val MAX_DEPTH = 16

    // This is a resource guard, not a JSON parser. Serialization still checks syntax and types.
    fun acceptsNesting(text: String): Boolean = acceptsStructure(text, maxArrayItems = null)

    /** Count array entries before deserializing them into an allocating List. */
    fun acceptsArray(text: String, maxItems: Int): Boolean =
        maxItems >= 0 && acceptsStructure(text, maxArrayItems = maxItems)

    private fun acceptsStructure(text: String, maxArrayItems: Int?): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        var arrayStarted = false
        var atArrayItemStart = false
        var arrayItems = 0
        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                if (maxArrayItems != null) {
                    if (depth == 0) {
                        if (character.isJsonWhitespace()) continue
                        // Require one array document, including when it is empty.
                        if (arrayStarted || character != '[') return false
                        arrayStarted = true
                        atArrayItemStart = true
                    } else if (depth == 1) {
                        if (character == ',') {
                            atArrayItemStart = true
                        } else if (character != ']' && !character.isJsonWhitespace() && atArrayItemStart) {
                            if (++arrayItems > maxArrayItems) return false
                            atArrayItemStart = false
                        }
                    }
                }
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString && (maxArrayItems == null || arrayStarted)
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'
}
