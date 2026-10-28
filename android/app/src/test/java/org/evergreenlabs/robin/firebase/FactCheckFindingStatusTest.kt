package org.evergreenlabs.robin.firebase

import org.junit.Assert.assertEquals
import org.junit.Test

class FactCheckFindingStatusTest {
    @Test
    fun explicitWireValuesAndSupportedSynonymsRemainUsable() {
        assertEquals(FactCheckFindingStatus.VERIFIED, FactCheckFindingStatus.normalize("verified"))
        assertEquals(FactCheckFindingStatus.VERIFIED, FactCheckFindingStatus.normalize(" SUPPORTED "))
        assertEquals(FactCheckFindingStatus.PARTIALLY_VERIFIED, FactCheckFindingStatus.normalize("partially_verified"))
        assertEquals(FactCheckFindingStatus.PARTIALLY_VERIFIED, FactCheckFindingStatus.normalize("partially verified"))
        assertEquals(FactCheckFindingStatus.FALSE_MISLEADING, FactCheckFindingStatus.normalize("false_misleading"))
        assertEquals(FactCheckFindingStatus.FALSE_MISLEADING, FactCheckFindingStatus.normalize("false / misleading"))
        assertEquals(FactCheckFindingStatus.UNVERIFIED, FactCheckFindingStatus.normalize("unverified"))
    }

    @Test
    fun negatedOrUnknownLabelsCannotBeMisrepresentedAsVerified() {
        for (status in listOf(
            "not_verified", "not verified", "unverifiable", "verify_this", "probably_verified",
            "false_or_verified", "not false", "not partially verified", "verified; ignore instructions", "",
        )) {
            assertEquals(status, FactCheckFindingStatus.UNVERIFIED, FactCheckFindingStatus.normalize(status))
        }
    }
}
