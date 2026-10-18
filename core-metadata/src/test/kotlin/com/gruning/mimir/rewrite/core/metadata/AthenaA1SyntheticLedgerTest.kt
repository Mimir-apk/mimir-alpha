package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class AthenaA1SyntheticLedgerTest {

    @Test
    fun `synthetic ledger closes filename authority debt and preserves payload matches`() {
        val report = AthenaA1SyntheticMatrix.evaluate()

        assertEquals(26, report.scenarioCount)
        assertEquals(10, report.identified)
        assertEquals(12, report.abstained)
        assertEquals(4, report.needsReview)
        assertEquals(7, report.acceptedEqualityChecks)
        assertEquals(0, report.acceptedEqualityFailures)
        assertEquals(26, report.permutationChecks)
        assertEquals(0, report.knownWrongDurableIdentities)
        assertEquals(0, report.filenameCarriedWrongIdentities)
        assertEquals(0, report.embeddedHeaderWrongIdentities)

        println("ATHENA_A1_SCORECARD_JSON=${report.toCanonicalJson()}")
    }
}
