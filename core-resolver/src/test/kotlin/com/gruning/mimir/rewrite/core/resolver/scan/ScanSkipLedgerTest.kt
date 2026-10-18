package com.gruning.mimir.rewrite.core.resolver.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit cover for the per-reason skip tally. The ledger is pure bookkeeping — these tests pin that it
 * attributes to the right reason, keeps a total consistent with the bare counter it replaced, and
 * never leaks anything but reason names and counts.
 */
class ScanSkipLedgerTest {

    @Test
    fun `an empty ledger reports nothing rather than a wall of zeroes`() {
        val ledger = ScanSkipLedger()

        assertEquals(0, ledger.total)
        assertEquals(emptyMap<SkipReason, Int>(), ledger.toMap())
        assertEquals(emptyMap<String, Int>(), ledger.toNameMap())
        assertEquals("none", ledger.summary())
    }

    @Test
    fun `each reason is tallied under itself and never bleeds into another`() {
        val ledger = ScanSkipLedger()
        ledger.record(SkipReason.AMBIGUOUS_PLATFORM)
        ledger.record(SkipReason.AMBIGUOUS_PLATFORM)
        ledger.record(SkipReason.SIDECAR_EXTENSION)

        assertEquals(2, ledger.count(SkipReason.AMBIGUOUS_PLATFORM))
        assertEquals(1, ledger.count(SkipReason.SIDECAR_EXTENSION))
        // Every reason that was never recorded stays at zero — no off-by-one across ordinals.
        for (reason in SkipReason.entries) {
            if (reason == SkipReason.AMBIGUOUS_PLATFORM || reason == SkipReason.SIDECAR_EXTENSION) continue
            assertEquals(reason.name, 0, ledger.count(reason))
        }
    }

    @Test
    fun `the breakdown sums to the total for every reason`() {
        val ledger = ScanSkipLedger()
        // One of each, so a mis-indexed ordinal would show up as a missing or doubled key.
        SkipReason.entries.forEach(ledger::record)
        ledger.record(SkipReason.UNKNOWN_EXTENSION)

        assertEquals(SkipReason.entries.size + 1, ledger.total)
        assertEquals(ledger.total, ledger.toMap().values.sum())
        assertEquals(ledger.total, ledger.toNameMap().values.sum())
        assertEquals(SkipReason.entries.size, ledger.toMap().size)
        assertEquals(2, ledger.toMap()[SkipReason.UNKNOWN_EXTENSION])
    }

    @Test
    fun `zero-count reasons are omitted and survivors keep declaration order`() {
        val ledger = ScanSkipLedger()
        // Recorded out of declaration order on purpose: the projection must impose the enum's order,
        // not insertion order, so a summary reads the same way every scan.
        ledger.record(SkipReason.AMBIGUOUS_PLATFORM)
        ledger.record(SkipReason.HIDDEN_PATH)
        ledger.record(SkipReason.UNKNOWN_EXTENSION)

        assertEquals(
            listOf(SkipReason.UNKNOWN_EXTENSION, SkipReason.HIDDEN_PATH, SkipReason.AMBIGUOUS_PLATFORM),
            ledger.toMap().keys.toList(),
        )
        assertEquals(
            listOf("UNKNOWN_EXTENSION", "HIDDEN_PATH", "AMBIGUOUS_PLATFORM"),
            ledger.toNameMap().keys.toList(),
        )
    }

    @Test
    fun `the name map mirrors the typed map exactly`() {
        val ledger = ScanSkipLedger()
        ledger.record(SkipReason.SUPPORT_CONTENT)
        ledger.record(SkipReason.SUPPORT_CONTENT)
        ledger.record(SkipReason.EMPTY_FILE)

        assertEquals(
            ledger.toMap().mapKeys { it.key.name },
            ledger.toNameMap(),
        )
    }

    @Test
    fun `the summary carries reasons and counts only`() {
        val ledger = ScanSkipLedger()
        ledger.record(SkipReason.AMBIGUOUS_PLATFORM)
        ledger.record(SkipReason.AMBIGUOUS_PLATFORM)
        ledger.record(SkipReason.SIDECAR_EXTENSION)

        // Declaration order puts SIDECAR_EXTENSION ahead of AMBIGUOUS_PLATFORM.
        assertEquals("SIDECAR_EXTENSION=1 AMBIGUOUS_PLATFORM=2", ledger.summary())
        // Library contents are private: the summary is a closed vocabulary of enum names and digits.
        val vocabulary = SkipReason.entries.map(SkipReason::name).toSet()
        ledger.summary().split(" ").forEach { token ->
            val (name, count) = token.split("=")
            assertTrue("unexpected token $name", name in vocabulary)
            assertTrue("count must be a number", count.toIntOrNull() != null)
        }
    }
}
