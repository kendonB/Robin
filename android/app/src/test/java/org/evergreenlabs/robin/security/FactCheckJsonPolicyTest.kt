package org.evergreenlabs.robin.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactCheckJsonPolicyTest {
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
