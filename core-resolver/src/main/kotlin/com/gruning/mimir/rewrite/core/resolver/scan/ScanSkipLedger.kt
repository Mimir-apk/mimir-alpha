package com.gruning.mimir.rewrite.core.resolver.scan

/**
 * Per-reason tally of the files a scan pass dropped.
 *
 * The scan already counted skips as a bare total, which cannot answer the only question that matters
 * when a user's games do not appear: *why* were they dropped. This ledger keeps the [SkipReason] that
 * [ScanRecognizer] already produced instead of discarding it.
 *
 * **Instrumentation only.** Recording a skip never influences classification — the ledger is written
 * after [RecognitionResult] is decided and is never read back by the scan.
 *
 * Hot-loop cost: one [IntArray] slot increment per skipped file, indexed by [SkipReason.ordinal]. No
 * allocation, no boxing, no map lookup per file — a scan may walk tens of thousands of entries. Map
 * projection happens once per pass, at the end.
 *
 * Not thread-safe: one ledger belongs to one sequential recognition loop.
 */
internal class ScanSkipLedger {

    private val counts = IntArray(REASONS.size)

    /** Total files dropped — identical to the bare counter this ledger replaces. */
    var total: Int = 0
        private set

    /** Tally one dropped file under [reason]. */
    fun record(reason: SkipReason) {
        counts[reason.ordinal]++
        total++
    }

    /** Count recorded for [reason]. */
    fun count(reason: SkipReason): Int = counts[reason.ordinal]

    /**
     * Reasons that actually fired, in declaration order. Zero-count reasons are omitted so a summary
     * names only what really happened rather than a wall of zeroes.
     */
    fun toMap(): Map<SkipReason, Int> {
        if (total == 0) return emptyMap()
        val out = LinkedHashMap<SkipReason, Int>()
        for (reason in REASONS) {
            val c = counts[reason.ordinal]
            if (c > 0) out[reason] = c
        }
        return out
    }

    /**
     * [toMap] keyed by [SkipReason.name], for carrying on `ScanResult` — which lives in `core-model`
     * and therefore cannot reference this module's enum.
     */
    fun toNameMap(): Map<String, Int> {
        if (total == 0) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (reason in REASONS) {
            val c = counts[reason.ordinal]
            if (c > 0) out[reason.name] = c
        }
        return out
    }

    /**
     * One-line diagnostic, e.g. `AMBIGUOUS_PLATFORM=3 SIDECAR_EXTENSION=1`.
     *
     * Reasons and counts only — never a file name, path, or title. Library contents are private and
     * must not reach a log.
     */
    fun summary(): String =
        if (total == 0) "none" else toMap().entries.joinToString(" ") { "${it.key.name}=${it.value}" }

    private companion object {
        /** Snapshot once: `entries` allocates a view per call, and this is read in the hot loop path. */
        val REASONS: List<SkipReason> = SkipReason.entries
    }
}
