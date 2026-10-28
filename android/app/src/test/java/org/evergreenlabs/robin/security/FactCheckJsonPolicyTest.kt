package org.evergreenlabs.robin.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactCheckJsonPolicyTest {
    @Test
    fun catalogueRowsAreBoundedBeforeListDeserialization() {
        val maxRows = FactCheckCataloguePolicy.MAX_ROWS
        val allowed = "[" + "{},".repeat(maxRows - 1) + "{}]"
        val tooMany = "[" + "{},".repeat(maxRows) + "{}]"
        assertTrue(FactCheckJsonPolicy.acceptsArray(allowed, maxRows))
        assertFalse(FactCheckJsonPolicy.acceptsArray(tooMany, maxRows))

        // This response passes the download byte limit but previously attempted
        // to allocate 1.7 million row objects before checking MAX_ROWS.
        val allocationBomb = "[" + "{},".repeat(1_699_999) + "{}]"
        assertTrue(allocationBomb.length <= FactCheckCataloguePolicy.MAX_BYTES)
        assertFalse(FactCheckJsonPolicy.acceptsArray(allocationBomb, maxRows))
    }

    @Test
    fun arrayEntryCountingIgnoresNestedValuesAndEscapedStringContent() {
        val row = """{"Source":"a , ] } \\\" quote and \\\\ slash","unknown":[{"a":[1,2,3]},4]}"""
        assertTrue(FactCheckJsonPolicy.acceptsArray("[$row]", 1))
        assertFalse(FactCheckJsonPolicy.acceptsArray("[$row,$row]", 1))
        assertTrue(FactCheckJsonPolicy.acceptsArray("[\"comma, bracket ] and \\\"quote\",{},[1,2],null,true,3]", 6))
        assertFalse(FactCheckJsonPolicy.acceptsArray("[\"comma, bracket ] and \\\"quote\",{},[1,2],null,true,3]", 5))
    }

    @Test
    fun boundedArraysMustBeOneArrayDocumentAndRetainTheDepthLimit() {
        assertTrue(FactCheckJsonPolicy.acceptsArray(" \n[ \t ]\r ", 0))
        assertFalse(FactCheckJsonPolicy.acceptsArray("[{}]", 0))
        assertFalse(FactCheckJsonPolicy.acceptsArray("[]", -1))
        for (invalid in listOf("", " ", "{}", "null", "[] []", "[]{}", "[{}", "[\"unterminated]")) {
            assertFalse(invalid, FactCheckJsonPolicy.acceptsArray(invalid, 2))
        }
        val allowed = "[".repeat(FactCheckJsonPolicy.MAX_DEPTH) + "0" + "]".repeat(FactCheckJsonPolicy.MAX_DEPTH)
        assertTrue(FactCheckJsonPolicy.acceptsArray(allowed, 1))
        assertFalse(FactCheckJsonPolicy.acceptsArray("[$allowed]", 1))
    }

    @Test
    fun deeplyNestedUnknownFieldsAreRejectedBeforeTheParserSeesThem() {
        val allowed = "[".repeat(FactCheckJsonPolicy.MAX_DEPTH) + "0" + "]".repeat(FactCheckJsonPolicy.MAX_DEPTH)
        assertTrue(FactCheckJsonPolicy.acceptsNesting(allowed))
        assertFalse(FactCheckJsonPolicy.acceptsNesting("[$allowed]"))
        assertFalse(FactCheckJsonPolicy.acceptsNesting("[".repeat(30_000) + "0" + "]".repeat(30_000)))
    }

    @Test
    fun bracketCharactersAndEscapedQuotesInNormalTextAreNotStructuralNesting() {
        assertTrue(FactCheckJsonPolicy.acceptsNesting("""{"claim":"A \"quote\" with [[[[ {{{{ and a \\ backslash","sources":[]}"""))
        assertFalse(FactCheckJsonPolicy.acceptsNesting("{\"claim\":\"unterminated}"))
        assertFalse(FactCheckJsonPolicy.acceptsNesting("]"))
    }
}
