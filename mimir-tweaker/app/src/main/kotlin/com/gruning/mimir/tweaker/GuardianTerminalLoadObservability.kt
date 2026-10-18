package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam for **which** of [GuardianTerminalReceiptStore]'s load outcomes fired.
 *
 * The fourth instance of one defect class, and the narrowest: the daemon computes the answer and
 * drops it before logcat. [GuardianRestoreObservability] (restore reason),
 * [GuardianRepairObservability] (repair reason), and `RemoteGuardianProcessAuthority`'s
 * `restoreRequest outcome=` were the first three. Here the reference Nova sat at
 * `settlement=READY_RETAIN:null:false` — `GuardianTransactionReconciler.ready()` saying
 * `terminal !is GuardianTerminalLoad.Received` — while `requestRestore()` returned `true` with
 * zero refusals logged. The guardian reports success and the reconciler sees no terminal receipt,
 * and nothing in PRODUCTION says which of the store's branches produced that.
 *
 * **CORRECTED 2026-09-06 (guardian map §4.4): that diagnosis of the Nova's hold is superseded.**
 * By 2026-09-03 the receipt WAS `Received` (1362 bytes, decoded), and the refusal had moved
 * downstream into [GuardianTransactionReconciler.authenticTerminalDigest]'s scope/digest check --
 * without this log line changing, which is exactly why it kept reading as current. See
 * [GuardianTerminalDigestObservability], which already records this correction in its own KDoc.
 * The five causes below are still real and still fire on their own terms; they are simply no
 * longer the standing diagnosis for a `READY_RETAIN` hold once the receipt has been received.
 *
 * `GuardianTerminalReceiptStore.loadOnce()` has **five** exits, not the four that are visible as
 * `if`-statements:
 *
 * - [CAUSE_STAGING_PRESENT] — a `.tmp` sibling exists, so a write was interrupted. `Ambiguous`.
 *   Note this one poisons *every subsequent read* until the staging file is removed: `persist`
 *   deletes it only on its own next run.
 * - [CAUSE_RECORD_ABSENT] — no record file. `Unavailable`. This is what `clear()` leaves behind.
 * - [CAUSE_LENGTH_OUT_OF_RANGE] — present but outside `1..8192`. `Ambiguous`. Carries the observed
 *   length, because `0` (a truncated create) and `>8192` (a corrupt or wrong-schema frame) are
 *   different repairs.
 * - [CAUSE_DECODE_NULL] — `GuardianTerminalWireCodec.decode` refused the bytes. `Ambiguous`.
 * - [CAUSE_THREW] — the outer `runCatching` caught something, and before this seam that was
 *   indistinguishable from a decode refusal. A `SecurityException` from a permissions regression
 *   lands here and nowhere else; the `Guardian stock unconfirmed` field shipped earlier tonight
 *   exists because a value-only render would have shown a correct-looking number beside exactly
 *   that kind of uid/gid mismatch.
 *
 * [CAUSE_RECEIVED] is logged too, and that is deliberate — it is the negative control. A
 * failures-only instrument cannot tell "the read never happened" from "the read always succeeded",
 * so the instrument must be able to distinguish both outcomes.
 *
 * **Edge-deduplicated rather than rare-by-construction.** `restoreRequest outcome=` could log
 * unconditionally because a restore request is an explicitly-triggered event. This is not that:
 * on this (terminal) port, `loadUntil` has call sites in main source (fifteen, not the eleven this
 * sentence used to claim while enumerating only nine of them) --
 * `RemoteGuardianProcessAuthority` (192/218/317), `RestoreGuardianProcessService` (ten sites:
 * 125/240/362/377/396/450/454/535/549/814 -- the last, `terminalAvailable()`, is the read-back
 * called immediately after `exit()` triggers `persist()` for the EXITED receipt, at
 * `RestoreGuardianProcessService.kt:305-307`), and `RestoreGuardianController` (213). That last one
 * is source-present but NOT one of the fourteen production-live sites: `RestoreGuardianController`
 * has no construction site in `app/src/main` (only its own test constructs it -- see this file's
 * neighbour, `RestoreGuardianController.kt:5-9`'s KDoc), so its `loadUntil` call never executes on
 * a device. **CORRECTED 2026-09-06 (guardian map §4.5).** Re-grep `\.loadUntil(` before trusting
 * this count again rather than propagating it a second time. At least one of the fourteen live
 * sites sits under a retry observed firing every 2.5s. So [onLoadResolved] emits
 * only when the rendered line *changes* — the shape
 * [CapabilityCallCadenceWatch] already ships on this daemon's door — which makes a steady state
 * cost one line total and a flapping one legible instead of drowned. This cannot reintroduce the
 * once-a-second noise RC-47 removed.
 *
 * Reasons, sizes, and exception class names ONLY — never a path, package, receipt content, or any
 * private-library content. The record's own file name is a compile-time constant and is not repeated
 * here.
 *
 * [lastRecorded] is the test seam: it updates before the guarded [Log] call, so a host test can
 * assert the cause reached this point without depending on `Log` succeeding — wrapped in
 * `runCatching` because a diagnostic must never throw or change behaviour. The tag is its own
 * (`MimirGuardianTerminal`) because [GuardianTerminalReceiptStore] is constructed in **both**
 * processes — `MimirGuardianHost` and `MimirGuardianProcess` — and neither tag would be true in
 * the other; logcat still carries the pid that separates them.
 */
internal object GuardianTerminalLoadObservability {
    private const val TAG = "MimirGuardianTerminal"

    const val CAUSE_RECEIVED = "received"
    const val CAUSE_STAGING_PRESENT = "staging_present"
    const val CAUSE_RECORD_ABSENT = "record_absent"
    const val CAUSE_LENGTH_OUT_OF_RANGE = "length_out_of_range"
    const val CAUSE_DECODE_NULL = "decode_null"
    const val CAUSE_THREW = "threw"

    /** The most recent rendered line, set on every resolution — including suppressed duplicates. */
    @Volatile
    var lastRecorded: String? = null
        private set

    private var lastEmitted: String? = null

    /**
     * Records one resolved load and emits it at `warn` when the rendered line differs from the
     * previous one. Returns whether it emitted, so a host test can prove the dedup without
     * reading logcat.
     */
    @Synchronized
    fun onLoadResolved(cause: String, detail: String?): Boolean {
        val line = describe(cause, detail)
        lastRecorded = line
        if (line == lastEmitted) return false
        lastEmitted = line
        runCatching { Log.w(TAG, line) }
        return true
    }

    /** Pure formatting — cause code plus one discriminating datum. Unit-testable without [Log]. */
    fun describe(cause: String, detail: String?): String =
        "terminalReceipt load cause=$cause" + if (detail == null) "" else " detail=$detail"
}
