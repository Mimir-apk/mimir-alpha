package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression guard for a latched-suppression defect that made the M button permanently unreachable.
 *
 * Observed on device 2026-08-06: the "Mimir M button" overlay window was alive and drawing
 * (`isVisible=true`, `HAS_DRAWN`) but sized 1x1 at alpha 0 -- the idle-hidden representation -- with
 * no way to reveal it. Root cause is `MButtonRuntime.notePrimaryInteraction`, which returns early
 * while `primaryUiSuppressed` is true, so once suppression latches on, no touch or key press can
 * ever restore the button.
 *
 * Two defects in [PrimaryMButtonPanelHost] allowed the latch:
 *  1. UNRECOVERABLE GUARD (fixed) -- `hide()` released suppression only after
 *     `val current = mounted ?: return`. Once `mounted` was null, `hide()` bailed at the guard and
 *     the one call that would have cleared suppression never ran. Fixed by releasing unconditionally
 *     before the guard.
 *  2. SWALLOWED RELEASE (fixed) -- the revoke closure passed to `attachPrimaryPanel` released
 *     suppression LAST, after `candidate.dispose()`. `PanelLeaseOwner.revokeCurrent`
 *     (core-companion-native) runs that closure inside `runCatching`, so a throwing `dispose()`
 *     silently skipped the release with nothing mounted afterward to ever undo it. Fixed the same
 *     way as (1): release first, unconditionally, before anything that can throw.
 *
 * An earlier version of this suite also asserted a `mounted === candidate` guard on the attach
 * path's suppression claim, on the theory that the revoke callback could fire *during* the
 * candidate's own `attachPrimaryPanel` call ("attach race"). That theory does not hold:
 * `PanelLeaseOwner.attach` only registers a candidate's `onRevoked` (the closure above) AFTER
 * `add(resource)` succeeds, and returns immediately after -- so no revocation of that same candidate
 * can fire before `mounted = candidate` is reached. The only revocation reachable during
 * `attachPrimaryPanel` is of the PREVIOUS lease, fired by `attach()`'s own
 * `revokeCurrent(REPLACED)` before the new entry even exists -- it closes over the previous
 * candidate and cannot null out the new one's `mounted` assignment. The guard was dead code; the
 * claim is now unconditional.
 *
 * A behavioural test is not available: the app module's unit tests are plain JUnit with no
 * Robolectric harness, [PrimaryMButtonPanelHost] requires a real `Context` plus a main-looper
 * `Handler`, and `MButtonTopologyController` is a concrete class rather than an interface, so it
 * cannot be faked. These are therefore source-ordering assertions -- the same technique this project
 * already uses where a live harness does not exist. They are narrow by construction: they prove the
 * ordering that made the bug unrecoverable, not the full runtime behaviour, which is owed to device
 * verification.
 */
class PrimaryMButtonPanelSuppressionTest {

    private fun source(): String? {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/PrimaryMButtonPanelHost.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/PrimaryMButtonPanelHost.kt"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/PrimaryMButtonPanelHost.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }

    /**
     * Strip `//` comments before any ordering analysis.
     *
     * The first version of this test did not, and it failed against the CORRECT code: the fix's own
     * explanatory comment quotes `mounted ?: return`, and `indexOf` matched that prose rather than
     * the statement, placing the "guard" earlier than the release. A source-ordering assertion that
     * reads comments is measuring documentation, not behaviour.
     */
    private fun stripComments(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }

    private fun hideBody(text: String): String {
        val code = stripComments(text)
        val start = code.indexOf("fun hide()")
        assertTrue("hide() not found in PrimaryMButtonPanelHost", start >= 0)
        return code.substring(start)
    }

    /** The `onRevoked` closure passed as the trailing lambda to `overlay.attachPrimaryPanel`. */
    private fun revokeClosureBody(text: String): String {
        val code = stripComments(text)
        val start = code.indexOf("attachPrimaryPanel(view) { leaseId, _ ->")
        assertTrue("attachPrimaryPanel revoke closure not found in PrimaryMButtonPanelHost", start >= 0)
        val end = code.indexOf("}) {", start)
        assertTrue("attachPrimaryPanel revoke closure did not terminate as expected", end >= 0)
        return code.substring(start, end)
    }

    /** The `is PanelAttachResult.Attached ->` branch body of the `attachPrimaryPanel` `when`. */
    private fun attachedBranchBody(text: String): String {
        val code = stripComments(text)
        val start = code.indexOf("is PanelAttachResult.Attached ->")
        assertTrue("PanelAttachResult.Attached branch not found in PrimaryMButtonPanelHost", start >= 0)
        val end = code.indexOf("is PanelAttachResult.Rejected ->", start)
        assertTrue("PanelAttachResult.Rejected branch not found in PrimaryMButtonPanelHost", end >= 0)
        return code.substring(start, end)
    }

    @Test
    fun `hide releases primary UI suppression before the mounted-panel guard can return early`() {
        val text = source()
        assumeTrue("source not reachable from this working directory", text != null)
        val body = hideBody(text!!)

        val release = body.indexOf("setPrimaryUiSuppressed(false)")
        val guard = body.indexOf("mounted ?: return")

        assertTrue("hide() must release suppression", release >= 0)
        assertTrue("hide() must still have its mounted guard", guard >= 0)
        assertTrue(
            "hide() must release primaryUiSuppressed BEFORE `mounted ?: return`. If the guard runs " +
                "first, a leaked suppression can never be cleared and the M button is permanently " +
                "unreachable -- notePrimaryInteraction returns early while suppressed.",
            release < guard,
        )
    }

    @Test
    fun `attachPrimaryPanel revoke closure releases primary UI suppression before disposing the candidate`() {
        val text = source()
        assumeTrue("source not reachable from this working directory", text != null)
        val body = revokeClosureBody(text!!)

        val release = body.indexOf("setPrimaryUiSuppressed(false)")
        val dispose = body.indexOf("candidate.dispose()")

        assertTrue("the revoke closure must release suppression", release >= 0)
        assertTrue("the revoke closure must still dispose the candidate", dispose >= 0)
        assertTrue(
            "the attachPrimaryPanel revoke closure must release primaryUiSuppressed BEFORE " +
                "candidate.dispose(). PanelLeaseOwner.revokeCurrent (core-companion-native) runs " +
                "this closure inside runCatching, so a throwing dispose() silently skips whatever " +
                "runs after it -- if the release comes last, a throwing dispose leaves suppression " +
                "latched on with nothing mounted to ever clear it.",
            release < dispose,
        )
    }

    @Test
    fun `attach claims primary UI suppression unconditionally, not guarded on a same-candidate race`() {
        val text = source()
        assumeTrue("source not reachable from this working directory", text != null)
        val body = attachedBranchBody(text!!)

        val mount = body.indexOf("mounted = candidate")
        val claim = body.indexOf("topology.setPrimaryUiSuppressed(true)")

        assertTrue("the Attached branch must assign `mounted = candidate`", mount >= 0)
        assertTrue("the Attached branch must claim primary UI suppression", claim >= 0)
        assertTrue("suppression must be claimed after the candidate is mounted", mount < claim)
        assertTrue(
            "the claim must be unconditional, with no `if (mounted === candidate)` guard. " +
                "PanelLeaseOwner.attach only registers a candidate's onRevoked after add(resource) " +
                "succeeds and returns immediately after, so no revocation of THIS candidate can fire " +
                "between `mounted = candidate` and this claim -- the only revocation reachable " +
                "during attachPrimaryPanel is of the PREVIOUS lease, fired by attach()'s own " +
                "revokeCurrent(REPLACED) before the new entry (and this candidate) even exists. A " +
                "`mounted === candidate` guard here was therefore always true: dead code, not a race " +
                "guard.",
            !body.contains("if (mounted === candidate)"),
        )
    }
}
