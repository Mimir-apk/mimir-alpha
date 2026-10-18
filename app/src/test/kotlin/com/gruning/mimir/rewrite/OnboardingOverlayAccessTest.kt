package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * The Draw over other apps permission has a dedicated onboarding page.
 *
 * These tests are written against [selectMButtonOverlayTarget] rather than against copy strings, on
 * purpose. The defect that made this ticket necessary was invisible for a month precisely because every
 * onboarding test asserted what the screens *said*; nothing asserted that the set of screens covered the
 * set of hard preconditions. A test that only pinned the new wording would re-create that blind spot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingOverlayAccessTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox, and the copy it reads can write Compose snapshot state outside any
     * composition. Apply those writes and run what they posted, or a later Compose test in the sandbox fails "Compose did not get
     * idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private val res = onboardingTestResources()

    private val pendingAccess = OnboardingAccessUi(
        usageAvailable = true,
        usageGranted = false,
        homeRoleAvailable = true,
        homeRoleHeld = false,
        filesGranted = false,
        overlayGranted = false,
        // Ungranted too, so the whole-surface CONFIRM check at the bottom of this file reads the
        // system-settings page's own two keys rather than its granted Continue.
    )

    /** Everything the floating M needs, except the one grant under test. */
    private fun eligibility(
        overlayPermissionGranted: Boolean,
        presentationDisplayId: Int? = null,
    ) = MButtonEligibility(
        presentationDisplayId = presentationDisplayId,
        primaryEnabled = true,
        usageAccessGranted = true,
        onboardingComplete = true,
        overlayPermissionGranted = overlayPermissionGranted,
    )

    @Test
    fun `the overlay grant is a hard gate on the floating M, which is why it earns a screen`() {
        // Ground truth for the whole ticket, asserted rather than cited: with every other precondition
        // satisfied, the missing overlay grant alone is enough to make the M button not exist.
        assertNull(
            "a missing overlay grant must suppress the M button outright",
            selectMButtonOverlayTarget(eligibility(overlayPermissionGranted = false)),
        )
        assertNotNull(
            "with the grant, the same eligibility must yield a target",
            selectMButtonOverlayTarget(eligibility(overlayPermissionGranted = true)),
        )

        // Therefore onboarding must ask for it. This is the assertion that would have failed for the whole
        // month the ticket sat owed.
        assertTrue(
            "the M button hard-gates on the overlay grant, so ACCESS must contain a screen that asks for it",
            OnboardingAccessScreenId.OVERLAY in onboardingAccessScreens(pendingAccess),
        )
    }

    @Test
    fun `the gate precedes the dual-display fork, so a two-screen handheld is not exempt`() {
        // The reason the small print says "on either screen". `selectMButtonOverlayTarget` returns on
        // `!overlayPermissionGranted` as its FIRST statement, before the presentation-display branch that
        // yields SECONDARY_COMPANION -- so the Thor's bottom-screen M is gated identically to a one-screen
        // device's. Contrast USAGE, which the secondary path genuinely does skip (see
        // OnboardingAccessScreenCopyTest.usageCopyNamesTheMButtonInExactlyOneString, whose refusal is scoped
        // to "a one-screen handheld" for exactly that reason). Overlay must NOT be scoped that way.
        assertNull(
            "a presentation display must not exempt the secondary M from the overlay grant",
            selectMButtonOverlayTarget(
                eligibility(overlayPermissionGranted = false, presentationDisplayId = 4),
            ),
        )
        assertEquals(
            CompanionOverlayRole.SECONDARY_COMPANION,
            selectMButtonOverlayTarget(
                eligibility(overlayPermissionGranted = true, presentationDisplayId = 4),
            )?.role,
        )

        val copy = onboardingAccessOverlayCopy(res)
        assertFalse(
            "overlay copy must not borrow USAGE's one-screen scoping -- this gate binds both topologies",
            (copy.why + copy.refusal).contains("one-screen") || (copy.why + copy.refusal).contains("handheld"),
        )
        // Its small print is now the instruction every player needs, and it
        // makes no screen-count claim at all, which is what keeps a Thor owner from reading it as a one-screen caveat.
        assertEquals("In Android's list, pick Mimir, then turn it on.", copy.refusal)
    }

    @Test
    fun `the screen is unconditional`() {
        // No device lacks this gate, so no device may silently skip the ask.
        assertTrue(OnboardingAccessScreenId.OVERLAY in onboardingAccessScreens(pendingAccess))
        assertTrue(
            "OVERLAY must still be asked for even when the grant is already held -- the sequence is a fixed " +
                "denominator, and the screen then renders its Granted state",
            OnboardingAccessScreenId.OVERLAY in
                onboardingAccessScreens(pendingAccess.copy(overlayGranted = true)),
        )
    }

    @Test
    fun `the screen resolves on the grant and on an explicit defer, and on nothing else`() {
        val review = OnboardingAccessReviewState()
        assertFalse(
            onboardingAccessScreenResolved(OnboardingAccessScreenId.OVERLAY, pendingAccess, review),
        )

        assertTrue(
            "holding the grant resolves the screen",
            onboardingAccessScreenResolved(
                OnboardingAccessScreenId.OVERLAY,
                pendingAccess.copy(overlayGranted = true),
                review,
            ),
        )

        review.deferOverlay()
        assertTrue(
            "an explicit Not now resolves the screen without the grant",
            onboardingAccessScreenResolved(OnboardingAccessScreenId.OVERLAY, pendingAccess, review),
        )
    }

    /**
     * The small print is one line. Neither of the Mimir button's two
     * screens sends a player down a Settings route (the real one, Settings › Controls › Mimir button permissions, is
     * ORIENTATION's fourth card's job), so the pair cannot drift apart. OVERLAY's line is the one instruction every
     * player needs in Android's own list; USAGE's is what Not now costs.
     */
    @Test
    fun `the Mimir button's two screens name no route, so they cannot point at different ones`() {
        val overlay = onboardingAccessOverlayCopy(res)
        val usage = onboardingAccessUsageCopy(res, pendingAccess)
        listOf(overlay, usage).forEach { copy ->
            assertFalse(copy.refusal, copy.refusal.contains("›"))
            assertFalse(copy.refusal, copy.refusal.contains("Special app access"))
        }
        assertEquals("In Android's list, pick Mimir, then turn it on.", overlay.refusal)
        assertTrue(usage.refusal, usage.refusal.contains("Mimir button"))
    }

    @Test
    fun `copy names the Mimir button and never softens the gate into a degradation`() {
        val copy = onboardingAccessOverlayCopy(res)

        assertEquals("Let Mimir display over other apps", copy.plainName)
        assertEquals("The Mimir button: an M you hold for a menu, even mid-game.", copy.why)
        // The M button is absent without this, not slower or lagging. That distinction is the one the
        // 2026-08-20 device walk reversed a ruling over; it must not be re-softened here.
        listOf("lag", "slower", "degrade", "may not").forEach { weasel ->
            assertFalse(
                "copy must not imply the M merely degrades: found \"$weasel\"",
                (copy.why + copy.refusal).contains(weasel),
            )
        }
    }

    /**
     * The guard for a missing focus-key route. QA-10 added OVERLAY's two focus keys and
     * wired only ONE of them into `dispatchOnboarding`'s CONFIRM branch: `OnboardingOverlayDeferKey` had no
     * case, so pressing A on "Not now" with a controller did nothing and the screen could only be left by
     * granting. Touch worked, which is why an emulator walk that pressed ALLOW never saw it.
     *
     * Written against the whole ACCESS surface rather than against OVERLAY, because the defect is structural:
     * any future screen cloned the same way can drop a key the same way. Source-characterization, the same
     * device `AppBrightnessCoordinatorDeclineFeedbackTest` uses -- the dispatch is a `when` over focus keys
     * inside a large composable file with no seam a unit test can call directly.
     */
    @Test
    fun `every focus key any ACCESS screen offers has a CONFIRM branch in the dispatch`() {
        val source = onboardingFlowSource()
        val confirmBranch = Regex("""^\s*(Onboarding\w+Key)\s*->""", RegexOption.MULTILINE)
        val dispatched = confirmBranch.findAll(source).map { it.groupValues[1] }.toSet()

        val offered = OnboardingAccessScreenId.entries
            .flatMap { id -> onboardingAccessScreenFocusKeys(id, pendingAccess) }
            .map { it.value }
            .toSet()

        // Focus keys are declared as `internal val Name = FocusKey("value")` / `private val ...`; map the
        // offered key VALUES back to the identifiers the dispatch branches on.
        val declaration = Regex("""val\s+(Onboarding\w+Key)\s*=\s*FocusKey\("([^"]+)"\)""")
        val nameByValue = declaration.findAll(source)
            .associate { it.groupValues[2] to it.groupValues[1] }

        val missing = offered.mapNotNull { value ->
            val name = nameByValue[value] ?: return@mapNotNull null
            name.takeUnless { it in dispatched || it == "OnboardingAccessContinueKey" }
        }.sorted()

        assertTrue(
            "these ACCESS focus keys are rendered but never handled by CONFIRM, so a controller cannot " +
                "activate them: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * The sibling test above proves a CONFIRM branch EXISTS for every ACCESS focus key. It does not prove
     * the branch DOES the same thing the touch path does, and on 2026-09-22 the Alpha pass found those two
     * paths had drifted apart in the opposite direction to the QA-10 bug this file was written for.
     *
     * Measured on emulator-5580: tapping "Not now" left the step counter on "2 OF 7" while `DPAD_RIGHT` +
     * `BUTTON_A` advanced it to "3 OF 7". Tapping "Allow" on the same screen worked, so touch dispatch was
     * fine — the defer path simply never moved the screen. A touch-only user could not get past the first
     * of seven permission screens, and the WELCOME screen advertises "Touch works too."
     *
     * The cause was that every `onDefer` closure flipped its deferred flag and stopped, while every CONFIRM
     * branch paired the identical `defer*()` call with `advance()`. The reason it was invisible on screen is
     * worth recording: `OnboardingAccessScreen`'s `LaunchedEffect` keys on the defer flags and re-lands focus
     * on `entryFocus` (the ACTION key, not the defer key), so the ring moved to "Not now" and snapped back
     * inside the same frame. Before/after screenshots were byte-identical, which made the lambda look like it
     * had never run at all.
     *
     * Source-characterisation, like its sibling, because `OnboardingAccessScreen` and
     * `OnboardingAccessCapabilityScreenBody` are file-private and offer no other seam a unit test can reach.
     * Structural rather than per-screen on purpose: the failure mode is a cloned screen dropping the call,
     * exactly as OVERLAY once dropped its CONFIRM branch.
     */
    @Test
    fun `every onDefer advances the screen, so touch and CONFIRM agree`() {
        val source = onboardingFlowSource()
        val onDeferBlocks = Regex("""onDefer\s*=\s*\{([\s\S]*?)\},\n""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "expected one onDefer per ACCESS capability screen; if a screen was added or removed, update " +
                "this count deliberately rather than loosening the assertion below",
            OnboardingAccessCapability.entries.size + NON_CAPABILITY_DEFER_SITES,
            onDeferBlocks.size,
        )

        val silent = onDeferBlocks.filterNot { it.contains("advanceScreen()") }
        assertTrue(
            "touch's onDefer must advance the screen, the way dispatchOnboarding's CONFIRM branch pairs " +
                "every defer*() call with advance(). These flip the flag and leave the user on the same " +
                "screen: $silent",
            silent.isEmpty(),
        )
    }

    /**
     * OVERLAY gets its own ACCESS screen and its own `onDefer`, but it is not an [OnboardingAccessCapability]
     * entry -- its deferred flag lives directly on the review state rather than in its capability list
     * (WRITE_SETTINGS and TWEAKER_USAGE were the other two until OR-12, 2026-09-29). Counted separately so that adding a capability and
     * adding a standalone screen both have to be acknowledged here deliberately.
     */
    private companion object {
        const val NON_CAPABILITY_DEFER_SITES = 1
    }

    private fun onboardingFlowSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("OnboardingFlow.kt not found from ${File(".").absolutePath}")
    }
}
