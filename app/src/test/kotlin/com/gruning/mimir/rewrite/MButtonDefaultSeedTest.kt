package com.gruning.mimir.rewrite

import android.view.Display
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The ᛗ was on by default only on a chassis with a Bottom** — policy 2026-09-22, found on a
 * Retroid Pocket Nova where the button never appeared after onboarding.
 *
 * The seed that turns a fresh install's ᛗ on was gated on `presentationDisplayId != null`. The Thor has
 * two displays (confirmed on the device: HWC display 0 and display 3), so the gate always opened there
 * and the button appeared. The Nova has one, so the gate never opened, the policy stayed DISABLED, and
 * the ᛗ could only be turned on by hand — same build, opposite behaviour, decided purely by how many
 * screens the chassis has.
 */
class MButtonDefaultSeedTest {

    @Test
    fun `a fresh install is seeded whatever the chassis looks like`() {
        assertTrue(
            "the decision cannot see a display id any more -- that is the fix",
            shouldSeedDefaultMButtonPolicy(
                policy = MButtonVisibilityPolicy.DISABLED,
                defaultUninitialized = true,
            ),
        )
    }

    /** The marker, not an inference. A deliberate Disabled must survive every later launch. */
    @Test
    fun `an install that chose Disabled on purpose is never re-seeded`() {
        assertFalse(
            shouldSeedDefaultMButtonPolicy(
                policy = MButtonVisibilityPolicy.DISABLED,
                defaultUninitialized = false,
            ),
        )
    }

    @Test
    fun `an already-enabled policy is left alone`() {
        MButtonVisibilityPolicy.entries.filter { it.buttonEnabled }.forEach { policy ->
            assertFalse(
                "seeding over ${policy.name} would silently retarget a chosen timeout",
                shouldSeedDefaultMButtonPolicy(policy = policy, defaultUninitialized = true),
            )
        }
    }

    /**
     * The resolver is deliberately NOT the place this was fixed. It still answers DISABLED for an
     * unconfigured install, because it cannot tell "never configured" from "legacy explicit off" — only
     * the stored marker can. Changing the resolver instead would have re-flipped the second group.
     */
    @Test
    fun `the resolver still defaults to Disabled and still honours the legacy flag`() {
        assertEquals(
            MButtonVisibilityPolicy.DISABLED,
            resolveMButtonVisibilityPolicy(storedPolicyName = null, legacyEnabled = false),
        )
        assertEquals(
            MButtonVisibilityPolicy.ALWAYS_VISIBLE,
            resolveMButtonVisibilityPolicy(storedPolicyName = null, legacyEnabled = true),
        )
        assertEquals(
            "an explicit stored choice always wins",
            MButtonVisibilityPolicy.DISABLED,
            resolveMButtonVisibilityPolicy(storedPolicyName = "DISABLED", legacyEnabled = true),
        )
    }

    /**
     * The seeded value is the same 30-second policy the dual-screen path already used. Identical on both
     * paths is what makes the seed safe on a cold start: a Bottom that has not resolved yet reports a null
     * display id, and that can no longer seed a different policy than the settled topology would.
     */
    @Test
    fun `the seeded policy reaches a single-screen device as PRIMARY_SINGLE`() {
        val seeded = MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS
        assertTrue("the seed has to actually enable the button", seeded.buttonEnabled)

        val target = selectMButtonOverlayTarget(
            MButtonEligibility(
                presentationDisplayId = null,
                primaryEnabled = seeded.buttonEnabled,
                usageAccessGranted = true,
                onboardingComplete = true,
                overlayPermissionGranted = true,
            ),
        )
        assertEquals(CompanionOverlayRole.PRIMARY_SINGLE, target?.role)
        assertEquals(Display.DEFAULT_DISPLAY, target?.displayId)
    }

    /**
     * Why the missing collector mattered. PRIMARY_SINGLE is the only role that requires
     * `onboardingComplete`, so on a single-screen device the ᛗ becomes eligible at the exact moment
     * onboarding finishes — and finishing routes to HOME inside the same Activity resume, so the
     * resume-time reconcile had already run. A Bottom never needed that reconcile, which is why the
     * Thor hid the gap.
     */
    @Test
    fun `onboarding is the gate a single-screen button is waiting on`() {
        val eligibility = MButtonEligibility(
            presentationDisplayId = null,
            primaryEnabled = true,
            usageAccessGranted = true,
            onboardingComplete = false,
            overlayPermissionGranted = true,
        )
        assertNull("nothing shows until onboarding is done", selectMButtonOverlayTarget(eligibility))
        assertEquals(
            CompanionOverlayRole.PRIMARY_SINGLE,
            selectMButtonOverlayTarget(eligibility.copy(onboardingComplete = true))?.role,
        )
        assertEquals(
            "a Bottom never waited on onboarding at all",
            CompanionOverlayRole.SECONDARY_COMPANION,
            selectMButtonOverlayTarget(eligibility.copy(presentationDisplayId = 3))?.role,
        )
    }

    /**
     * Observed on Nova: both grants allowed, the policy seeded on, and still no button. The
     * step reaches DONE twice (FAVORITES moves to it while onboarding is incomplete, then markComplete publishes it
     * again), so a watcher keyed on `step == DONE` saw true -> true and dropped the real completion. The watcher
     * must key on completion itself.
     */
    @Test
    fun `the button is judged again when onboarding truly completes, not when its step first reads DONE`() {
        val source = listOf(
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt"),
        ).first(java.io.File::isFile).readText()
        val watcher = source.substringAfter("OnboardingStore.checkpoints(appContext)").substringBefore(".collect { reconcile() }")
        assertTrue("search window: $watcher", watcher.contains(".distinctUntilChanged()"))
        assertTrue("keys on completion itself", watcher.contains(".map { onboardingComplete() }"))
        assertFalse("never on the step, which reads DONE before onboarding is complete", watcher.contains("OnboardingStep.DONE"))
    }
}
