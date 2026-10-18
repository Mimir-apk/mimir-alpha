package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam for **why** [GuardianTransactionReconciler.authenticTerminalDigest] refused.
 *
 * The fifth instance of one defect class, and the widest: the daemon computes the answer and drops it
 * before logcat. [GuardianRestoreObservability] (restore reason), [GuardianRepairObservability] (repair
 * reason), `RemoteGuardianProcessAuthority`'s `restoreRequest outcome=`, and
 * [GuardianTerminalLoadObservability] (which of the store's five load exits fired) were the first four.
 *
 * On
 * 2026-09-03 a Nova sat at `CROSS_STORE_RECONCILIATION_REQUIRED` and would not leave it — Mimir read
 * `posture=null profiles=[stock]` while the daemon's own identity line carried the full four profiles.
 * The device was recovered with `pm clear`, which fixed the unit and destroyed the reproducer. The
 * reason it needed a forensic session at all is that **ten distinct refusals all returned `null`**, and
 * the one line that survived to logcat — `settlement=READY_RETAIN:null:false` — could not even
 * distinguish "no terminal receipt" from "a receipt that failed to authenticate". The previous
 * diagnosis of record, in [GuardianTerminalLoadObservability]'s own KDoc, reads
 * `terminal !is GuardianTerminalLoad.Received`; by 2026-09-03 the receipt WAS received (1362 bytes,
 * decoded) and the failure had moved downstream without the log changing.
 *
 * The eleven, in the order they are tested. (An earlier comment said
 * "ten" and the conjunction below said "seven" when the object already declared eleven causes and
 * the conjunction already had eight terms -- this file is the primary reading aid for the exact
 * defect under investigation, so an off-by-one here is worth fixing on sight.)
 *
 * - [CAUSE_NO_EXITED_RECEIPT] — the stock receipt is present but the guardian never recorded an exit.
 * - [CAUSE_STOCK_UNVERIFIED] / [CAUSE_EXITED_UNVERIFIED] — a signature refused against this arm's
 *   session key and envelope, or the verify threw. A permissions regression lands here.
 * - The remaining eight are the conjunction that used to be one boolean named `chained`:
 *   [CAUSE_CHAIN_BROKEN], [CAUSE_READBACK_NOT_POSTDATED], [CAUSE_SETTLE_GAP_TOO_SHORT],
 *   [CAUSE_STOCK_ISSUED_BEFORE_READBACK], [CAUSE_EXIT_ISSUED_BEFORE_STOCK],
 *   [CAUSE_BOOT_IDENTITY_MISMATCH], [CAUSE_SCOPE_MISMATCH], [CAUSE_STOCK_ENTRY_SET_MISMATCH].
 *   An eight-term `&&` reports one `false`; each term is a different repair, and two of them
 *   (`recoveryBootIdentity`, `restoreScope`) are ordinary consequences of a reboot between arm and
 *   restore rather than corruption.
 *
 * [CAUSE_AUTHENTIC] is logged too, deliberately: a failures-only instrument cannot tell "the check
 * never ran" from "the check always passed", and four of this investigation's diagnoses have already
 * been retracted for reading an instrument that could not have shown the opposite.
 *
 * **Behaviour is unchanged.** This names a refusal; it never admits one. The fail-closed disposition
 * that retains rather than resumes is exactly as it was, deliberately — an unauthenticatable receipt
 * is not evidence, and a forged one must not become one because it is now better logged.
 *
 * Edge-deduplicated on the rendered line, the shape [GuardianTerminalLoadObservability] and
 * [CapabilityCallCadenceWatch] already ship, because reconciliation runs on every service restore and
 * a stuck device would otherwise emit the same line forever.
 *
 * Reasons and identifiers only — never a path, package, key, or receipt content.
 */
internal object GuardianTerminalDigestObservability {
    private const val TAG = "MimirGuardianDigest"

    const val CAUSE_AUTHENTIC = "authentic"
    const val CAUSE_NO_EXITED_RECEIPT = "no_exited_receipt"
    const val CAUSE_STOCK_UNVERIFIED = "stock_unverified"
    const val CAUSE_EXITED_UNVERIFIED = "exited_unverified"
    const val CAUSE_CHAIN_BROKEN = "chain_broken"
    const val CAUSE_READBACK_NOT_POSTDATED = "readback_not_postdated"
    const val CAUSE_SETTLE_GAP_TOO_SHORT = "settle_gap_too_short"
    const val CAUSE_STOCK_ISSUED_BEFORE_READBACK = "stock_issued_before_readback"
    const val CAUSE_EXIT_ISSUED_BEFORE_STOCK = "exit_issued_before_stock"
    const val CAUSE_BOOT_IDENTITY_MISMATCH = "boot_identity_mismatch"
    const val CAUSE_SCOPE_MISMATCH = "scope_mismatch"
    const val CAUSE_STOCK_ENTRY_SET_MISMATCH = "stock_entry_set_mismatch"

    /** The most recent rendered line, set on every resolution — including suppressed duplicates. */
    @Volatile
    var lastRecorded: String? = null
        private set

    private var lastEmitted: String? = null

    /**
     * Records one digest adjudication and emits it at `warn` when the rendered line differs from the
     * previous one. Returns whether it emitted, so a host test can prove the dedup without logcat.
     */
    @Synchronized
    fun onDigestResolved(cause: String, detail: String? = null): Boolean {
        val line = describe(cause, detail)
        lastRecorded = line
        if (line == lastEmitted) return false
        lastEmitted = line
        runCatching { Log.w(TAG, line) }
        return true
    }

    /** Pure formatting — cause code plus at most one discriminating datum. Testable without [Log]. */
    fun describe(cause: String, detail: String?): String =
        "terminalDigest cause=$cause" + if (detail == null) "" else " detail=$detail"

    /** Test seam: forget the dedup state so an assertion starts from a known point. */
    @Synchronized
    fun resetForTest() {
        lastRecorded = null
        lastEmitted = null
    }
}
