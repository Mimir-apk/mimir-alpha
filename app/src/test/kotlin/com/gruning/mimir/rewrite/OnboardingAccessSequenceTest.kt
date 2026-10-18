package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * O-2 : the ACCESS step split into a one-at-a-time sub-sequence
 * (Screen 0 orientation, then FILES / OVERLAY / USAGE / HOME_ROLE since OR-12, 2026-09-29, which moved WRITE_SETTINGS
 * to Utility and TWEAKER_USAGE to the Tweaker; the built-in controls screen went the same day). These tests cover the navigation/ordering/progress-cue machinery added for that split —
 * [OnboardingStep] itself is unchanged (still a single ACCESS value; see [OnboardingFlowPolicyTest]), so
 * none of this touches [OnboardingStore] persistence.
 *
 * The list is the same on every device. Several of these tests read as amendments: the last screen has been
 * KEYBOARD, TWEAKER_USAGE and now HOME_ROLE as screens came and went.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingAccessSequenceTest {
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
    )

    // ── Order  ──────────────────────────────────────────────────────────────

    @Test
    fun screensFollowTheRuledOrderWithTheHomeAppLast() {
        assertEquals(
            listOf(
                OnboardingAccessScreenId.ORIENTATION,
                OnboardingAccessScreenId.FILES,
                // OVERLAY names the Mimir button before the following screen uses its name.
                OnboardingAccessScreenId.OVERLAY,
                OnboardingAccessScreenId.USAGE,
                // WRITE_SETTINGS stood here from run 10 (2026-09-18) until OR-12 (2026-09-29).
                OnboardingAccessScreenId.HOME_ROLE,
                // The built-in controls screen and TWEAKER_USAGE stood here until 2026-09-29.
            ),
            onboardingAccessScreens(pendingAccess),
        )
    }

    // ── Progress cue: derived, not hardcoded ────────────

    @Test
    fun progressCueIsNullOnOrientationAndCountsTheFourAsks() {
        val screens = onboardingAccessScreens(pendingAccess)
        assertNull(onboardingAccessProgressLabel(res, screens, OnboardingAccessScreenId.ORIENTATION))
        // 2026-09-28: each count names the page Android opens, in Android's words.
        assertEquals(
            "1 of 4 · All files access",
            onboardingAccessProgressLabel(res, screens, OnboardingAccessScreenId.FILES),
        )
        // QA-10: OVERLAY and USAGE are the Mimir button's precondition pair; OVERLAY names it, so it comes first.
        assertEquals(
            "2 of 4 · Display over other apps",
            onboardingAccessProgressLabel(res, screens, OnboardingAccessScreenId.OVERLAY),
        )
        assertEquals("3 of 4 · Usage access", onboardingAccessProgressLabel(res, screens, OnboardingAccessScreenId.USAGE))
        // What the player reads is this string as it is, on one line (sentence case).
        // "Modify system settings" and the Tweaker's "Usage access" were 4 and 6 of 6.
        assertEquals("4 of 4 · Home app", onboardingAccessProgressLabel(res, screens, OnboardingAccessScreenId.HOME_ROLE))
        screens.filter { it != OnboardingAccessScreenId.ORIENTATION }.forEach { id ->
            assertTrue("$id names Android's page", onboardingAccessProgressLabel(res, screens, id)!!.contains(" · "))
        }
    }

    @Test
    fun progressCueDenominatorIsTheSameWhateverIsAlreadyGranted() {
        // A held grant renders its Allowed state; it never drops out of the count.
        val allHeld = pendingAccess.copy(filesGranted = true, usageGranted = true, homeRoleHeld = true)
        assertEquals(onboardingAccessScreens(pendingAccess), onboardingAccessScreens(allHeld))
        assertEquals(
            "4 of 4 · Home app",
            onboardingAccessProgressLabel(res, onboardingAccessScreens(allHeld), OnboardingAccessScreenId.HOME_ROLE),
        )
    }

    // ── Navigation: next / previous / current ────────────────────────────────────────────────────────

    @Test
    fun nextAndPreviousWalkTheOrderAndStopAtTheEnds() {
        val screens = onboardingAccessScreens(pendingAccess)

        assertEquals(
            OnboardingAccessScreenId.FILES,
            onboardingAccessNextScreen(screens, OnboardingAccessScreenId.ORIENTATION),
        )
        assertEquals(
            OnboardingAccessScreenId.HOME_ROLE,
            onboardingAccessNextScreen(screens, OnboardingAccessScreenId.USAGE),
        )
        assertNull(onboardingAccessNextScreen(screens, OnboardingAccessScreenId.HOME_ROLE))

        assertNull(onboardingAccessPreviousScreen(screens, OnboardingAccessScreenId.ORIENTATION))
        assertEquals(
            OnboardingAccessScreenId.FILES,
            onboardingAccessPreviousScreen(screens, OnboardingAccessScreenId.OVERLAY),
        )
        assertEquals(
            OnboardingAccessScreenId.OVERLAY,
            onboardingAccessPreviousScreen(screens, OnboardingAccessScreenId.USAGE),
        )
    }

    @Test
    fun currentScreenLandsOnFirstUnresolvedWhenNoAnchorIsStoredYet() {
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()

        // ORIENTATION resolves the instant it is left behind, not on its own — so with a totally fresh
        // review, "first unresolved" among the real capabilities starts the count at FILES, but ORIENTATION
        // (never visited, never granted) is genuinely first.
        assertEquals(
            OnboardingAccessScreenId.ORIENTATION,
            onboardingAccessCurrentScreen(screens, anchor = null, pendingAccess, review),
        )
    }

    // ── RC-13: stale-anchor recovery mid-sequence (a real relaunch, not the dead anchor-vanished branch) ──
    //
    // The HOME_ROLE grant relaunches
    // the app; OnboardingAccessReviewState is ephemeral by design and comes back with currentScreen == null,
    // exactly the fresh-review shape the test above exercises — except this player is NOT fresh, they already
    // passed ORIENTATION earlier in this same onboarding run. Without `orientationAlreadySeen`, the null-anchor
    // branch cannot tell the two cases apart and always re-picks ORIENTATION (it never resolves via
    // grant/defer). This is the actual defect; the anchor-vanished branch below never fires in production.

    @Test
    fun currentScreenNeverRegressesToOrientationOnARealRelaunchMidSequence() {
        // Mirrors the reported repro with the four-ask list (OR-12): FILES/USAGE already granted, HOME_ROLE still
        // outstanding when the process comes back.
        val access = pendingAccess.copy(filesGranted = true, usageGranted = true)
        val screens = onboardingAccessScreens(access)
        val review = OnboardingAccessReviewState() // fresh instance — the relaunch wiped the old one

        // The durable bit is unset: a genuinely fresh player, even one whose capabilities already read
        // granted from outside onboarding, still lands on the one-time preamble — unchanged from before
        // this ticket (see currentScreenLandsOnFirstUnresolvedWhenNoAnchorIsStoredYet above).
        assertEquals(
            OnboardingAccessScreenId.ORIENTATION,
            onboardingAccessCurrentScreen(screens, anchor = null, access, review, orientationAlreadySeen = false),
        )

        // The durable bit IS set (this player passed ORIENTATION earlier in this run): recovery must land on
        // the first still-outstanding REAL screen, never regress to ORIENTATION.
        val recovered = onboardingAccessCurrentScreen(
            screens, anchor = null, access, review, orientationAlreadySeen = true,
        )
        assertFalse(recovered == OnboardingAccessScreenId.ORIENTATION)
        assertEquals(OnboardingAccessScreenId.HOME_ROLE, recovered)
    }

    // ── Anchor-vanished recovery: a DEFENSIVE branch, now unreachable via onboardingAccessScreens ──────
    //
    // onboardingAccessScreens returns an INVARIANT list, so an anchor can no longer vanish from it in production
    // (onboardingAccessCurrentScreen's own doc says so). The recovery branch is still real, defensive code, so these
    // tests build a SYNTHETIC screens list missing the anchored screen, to keep proving the pure function's own
    // contract without implying production can reach it. The anchor was TWEAKER_USAGE until OR-12 removed that
    // screen (2026-09-29); OVERLAY plays its part now.

    private val screensWithoutOverlay = listOf(
        OnboardingAccessScreenId.ORIENTATION,
        OnboardingAccessScreenId.FILES,
        OnboardingAccessScreenId.USAGE,
        OnboardingAccessScreenId.HOME_ROLE,
    )

    @Test
    fun currentScreenSkipsAheadToFirstUnresolvedWhenRecoveringPastAlreadyResolvedScreens() {
        // Recovery (anchor no longer valid — modeled here the same way as the vanished-anchor test
        // below) must skip past already-resolved real capability screens, landing on the first one still
        // outstanding — but see the ORIENTATION-first test above for why a genuinely null anchor does NOT
        // skip ahead the same way.
        val access = pendingAccess.copy(filesGranted = true)
        val review = OnboardingAccessReviewState()

        assertEquals(
            OnboardingAccessScreenId.USAGE,
            onboardingAccessCurrentScreen(
                screensWithoutOverlay,
                anchor = OnboardingAccessScreenId.OVERLAY,
                access,
                review,
            ),
        )
    }

    @Test
    fun currentScreenSticksToAStoredAnchorEvenAfterItResolvesInTheBackground() {
        // THE hazard: a background refresh (the onResume reconciliation after Android's own page) must not
        // silently skip the screen the player is looking at once it resolves — that would violate
        // press-to-continue-on-grant. The anchor, once written, wins over "first unresolved" recomputation.
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()
        val grantedInBackground = pendingAccess.copy(homeRoleHeld = true)

        assertEquals(
            OnboardingAccessScreenId.HOME_ROLE,
            onboardingAccessCurrentScreen(
                screens,
                anchor = OnboardingAccessScreenId.HOME_ROLE,
                grantedInBackground,
                review,
            ),
        )
    }

    @Test
    fun currentScreenRecoversWhenTheStoredAnchorHasVanishedFromTheList() {
        // Modeled defensively (see the block comment above) — a screens list missing its anchor is not something
        // onboardingAccessScreens itself can produce, but onboardingAccessCurrentScreen must still recompute
        // rather than crash or point at a screen that isn't in `screens` at all.
        val access = pendingAccess
        val review = OnboardingAccessReviewState()

        assertEquals(
            OnboardingAccessScreenId.FILES,
            onboardingAccessCurrentScreen(
                screensWithoutOverlay,
                anchor = OnboardingAccessScreenId.OVERLAY,
                access,
                review,
            ),
        )
    }

    @Test
    fun currentScreenFallsBackToTheLastScreenOnceEverythingIsResolved() {
        val access = pendingAccess.copy(
            filesGranted = true,
            usageGranted = true,
            homeRoleHeld = true,
        )
        val review = OnboardingAccessReviewState()

        assertEquals(
            OnboardingAccessScreenId.HOME_ROLE,
            onboardingAccessCurrentScreen(
                screensWithoutOverlay,
                anchor = OnboardingAccessScreenId.OVERLAY,
                access,
                review,
            ),
        )
    }

    @Test
    fun currentScreenNeverRegressesToOrientationWhenRecoveringMidSequence() {
        // The bug this guards: a naive "first unresolved" recompute would see ORIENTATION as perpetually
        // unresolved (it has no grant/defer state of its own) and send an already-five-screens-deep player
        // all the way back to the one-time preamble the moment their anchor needed recovery.
        val access = pendingAccess.copy(filesGranted = true, usageGranted = true)
        val review = OnboardingAccessReviewState()

        val recovered = onboardingAccessCurrentScreen(
            screensWithoutOverlay,
            anchor = OnboardingAccessScreenId.OVERLAY,
            access,
            review,
        )

        assertFalse(recovered == OnboardingAccessScreenId.ORIENTATION)
        assertEquals(OnboardingAccessScreenId.HOME_ROLE, recovered)
    }

    // ── "Not now" auto-advances (spec §6, carried forward from the old row's focus-jump) ────────────────

    @Test
    fun deferringThenAdvancingMovesToTheNextScreenWithoutLeavingTheStep() {
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        var advancedOutOfAccess = false

        review.defer(OnboardingAccessCapability.FILES)
        onboardingAccessAdvance(
            screens = screens,
            current = OnboardingAccessScreenId.FILES,
            review = review,
            focusIntent = focusIntent,
            access = pendingAccess,
            onAdvance = { advancedOutOfAccess = true },
        )

        assertEquals(OnboardingAccessScreenId.OVERLAY, review.currentScreen)
        assertFalse(advancedOutOfAccess)
    }

    @Test
    fun deferringTheLastScreenLeavesAccessOnlyOnceEveryCapabilityIsResolved() {
        // HOME_ROLE is the last screen since OR-12 (2026-09-29). Exiting requires only the real capabilities
        // resolved, matching this test's original intent.
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        onboardingAccessCapabilities(pendingAccess).forEach(review::defer)
        var advancedOutOfAccess = false

        onboardingAccessAdvance(
            screens = screens,
            current = OnboardingAccessScreenId.HOME_ROLE,
            review = review,
            focusIntent = focusIntent,
            access = pendingAccess,
            onAdvance = { advancedOutOfAccess = true },
        )

        assertTrue(advancedOutOfAccess)
    }

    @Test
    fun deferringUsageAdvancesToTheHomeAppInsteadOfLeaving() {
        // USAGE is not the last screen: resolving it moves to HOME_ROLE, not out of ACCESS, even with every
        // capability but the Home app deferred.
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        listOf(
            OnboardingAccessCapability.FILES,
            OnboardingAccessCapability.USAGE,
        ).forEach(review::defer)
        var advancedOutOfAccess = false

        onboardingAccessAdvance(
            screens = screens,
            current = OnboardingAccessScreenId.USAGE,
            review = review,
            focusIntent = focusIntent,
            access = pendingAccess,
            onAdvance = { advancedOutOfAccess = true },
        )

        assertFalse(advancedOutOfAccess)
        assertEquals(OnboardingAccessScreenId.HOME_ROLE, review.currentScreen)
    }

    @Test
    fun advancingFromTheLastScreenDoesNothingUntilItIsActuallyResolved() {
        // Guards the per-row discipline: reaching the last screen is not itself enough to leave ACCESS —
        // every capability must be consciously granted or deferred first.
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        var advancedOutOfAccess = false

        onboardingAccessAdvance(
            screens = screens,
            current = OnboardingAccessScreenId.HOME_ROLE,
            review = review,
            focusIntent = focusIntent,
            access = pendingAccess,
            onAdvance = { advancedOutOfAccess = true },
        )

        assertFalse(advancedOutOfAccess)
        assertNull(review.currentScreen)
    }

    // ── Back: steps within the sub-sequence, swallowed on the first screen ──────────────────────────────

    @Test
    fun backStepsToThePreviousScreen() {
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        review.goToScreen(OnboardingAccessScreenId.USAGE)

        val moved = onboardingAccessGoBack(screens, OnboardingAccessScreenId.USAGE, review, focusIntent)

        assertTrue(moved)
        assertEquals(OnboardingAccessScreenId.OVERLAY, review.currentScreen)
    }

    @Test
    fun backIsSwallowedOnTheFirstScreen() {
        val screens = onboardingAccessScreens(pendingAccess)
        val review = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        review.goToScreen(OnboardingAccessScreenId.ORIENTATION)

        val moved = onboardingAccessGoBack(screens, OnboardingAccessScreenId.ORIENTATION, review, focusIntent)

        assertFalse(moved)
        assertEquals(OnboardingAccessScreenId.ORIENTATION, review.currentScreen)
    }

    @Test
    fun backThroughDispatchSwallowsOnTheFirstScreenAndNeverAdvancesOutOfAccess() {
        // Same coverage as OnboardingMetadataBackTest's metadataBackIsSwallowed, for the ACCESS branch: the
        // controller CANCEL/SYSTEM_BACK path through dispatchOnboarding, not the pure helper directly.
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        var advanced = false
        val handled = dispatchOnboarding(
            authority = authority,
            step = OnboardingStep.ACCESS,
            profile = OnboardingProfileState(),
            accessReview = OnboardingAccessReviewState(),
            focusIntent = OnboardingFocusIntentState(),
            library = OnboardingLibraryState(),
            access = pendingAccess,
            artworkAccount = null,
            settlement = OnboardingSettlementUi(
                phase = OnboardingSettlementPhase.READY,
                title = "Ready",
                detail = "Ready",
            ),
            action = ControllerAction.SYSTEM_BACK,
            onAdvance = { advanced = true },
            onCommitName = {},
            onRequestFilesAccess = {},
            onRequestUsageAccess = {},
            onRequestHomeRole = {},
            onChooseFolder = {},
            onOpenArtworkSetup = {},
            onOpenSteamGridDbKey = {},
            onRetrySettlement = {},
            onContinueLocally = {},
            onApplyTheme = {},
        )

        assertTrue(handled)
        assertFalse(advanced)
    }

    // ── Per-screen grant-or-defer discipline (screen rendering surface, not just the whole-set gate) ────

    @Test
    fun aGrantedScreenExposesOnlyTheTerminalContinueKey() {
        // OnboardingAccessContinueKey is file-private to OnboardingFlow.kt (same idiom OnboardingAccessReviewTest
        // and OnboardingFocusSequenceTest already use) — assert on the FocusKey's stable string identity.
        val access = pendingAccess.copy(filesGranted = true)
        assertEquals(
            listOf("onboarding/access/continue"),
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.FILES, access).map(FocusKey::value),
        )
    }

    @Test
    fun revisitingADeferredScreenReOffersTheAskInsteadOfGoingReadOnly() {
        // Orchestrator interpretation of the Back ruling ("revisit a decision"): a deferred-but-not-granted
        // screen renders identically whether it is a first visit or a Back revisit — same action+defer pair.
        // Deferring doesn't change what the FILES screen itself offers — only OnboardingAccessReviewState's
        // bookkeeping, which onboardingAccessScreenFocusKeys deliberately does not consult for anything
        // other than "granted" (via access, not review).
        assertEquals(
            listOf("onboarding/access/files", "onboarding/access/files/defer"),
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.FILES, pendingAccess).map(FocusKey::value),
        )
    }

    @Test
    fun anUnavailableCapabilityOffersOnlyNotNowNeverAnAction() {
        val access = pendingAccess.copy(usageAvailable = false)
        assertEquals(
            listOf("onboarding/access/usage/defer"),
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.USAGE, access).map(FocusKey::value),
        )
    }

    // ── Persisted mid-flow checkpoint still resolves (the hazard named in the brief) ─────────────────────

    @Test
    fun onboardingStepStillDeclaresASingleUnsplitAccessValue() {
        // The whole point of building the sub-sequence INSIDE OnboardingAccessReviewState rather than as
        // new OnboardingStep entries: OnboardingStore persists step by enum NAME (OnboardingStore.kt's
        // `enumValue` lookup), so a checkpoint written before this ticket with step == "ACCESS" only keeps
        // resolving if "ACCESS" stays a member with that exact name. It does — this ticket adds zero
        // OnboardingStep members.
        assertTrue(OnboardingStep.entries.any { it.name == "ACCESS" })
        assertEquals(OnboardingStep.LIBRARY, nextOnboardingStep(OnboardingStep.ACCESS))
    }

    @Test
    fun aCheckpointPersistedMidAccessRoundTripsThroughItsOwnDataClassUnaffectedByTheSplit() {
        val midFlight = OnboardingCheckpoint(step = OnboardingStep.ACCESS, selectedTreeUri = "content://tree")

        // beginScan (the only place OnboardingCheckpoint's own step field is mutated by production code)
        // still treats ACCESS as a completely ordinary step value — nothing about the sub-sequence living
        // inside OnboardingAccessReviewState (ephemeral, never persisted) touches this data class at all.
        val advancedToLibrary = midFlight.beginScan(
            treeUri = "content://tree",
            generation = "gen-1",
            writeAccessGranted = true,
        )

        assertEquals(OnboardingStep.LIBRARY, advancedToLibrary.step)
    }
}
