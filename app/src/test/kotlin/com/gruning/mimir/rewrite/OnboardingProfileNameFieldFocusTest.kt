package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  — the
 * PROFILE name field ([OnboardingNameField]) was touch-only by construction: no [FocusKey], no
 * `mimirFocusable`, and absent from PROFILE's directional keys list. These tests exercise the fix through
 * the real [dispatchOnboarding] PROFILE branch (not the generic `onboardingFocusDestination` algorithm,
 * already covered in [OnboardingFocusSequenceTest]), so a regression that re-registers the key but forgets
 * to add it to the keys list — "registered but unreachable", the actual shape of the original bug — fails
 * here, where a test that only asserted the key's existence would not have caught it.
 *
 * **Second report, live device, after the fix above landed (`638fb39c`, 2026-08-20)**: D-pad UP from either profile button must return focus to the name field The fix
 * above only made the field *reachable*, through the same flat 3-item list walk
 * ([field, Continue, Not now]) every other PROFILE control used — so UP from Not now still landed one list
 * step away, on Continue, not on the field two rows up. [dispatchOnboarding]'s PROFILE branch now routes
 * directional input through [onboardingProfileFocusDestination], a spatial model matching the actual
 * layout (field alone in a row above; Continue/Not now a horizontal pair below —
 * `OnboardingProfileScreen.kt`), not the flat list. The tests below were rewritten for that model: the old
 * `nameFieldIsReachableFromContinueEntryViaNavLeft` and `directionalRoundTripCoversFieldContinueAndNotNowInOrderWithEdgeClamp`
 * pinned the now-superseded flat sequence (LEFT from Continue reaching the field; DOWN walking
 * Continue → Not now) and would fail under the spatial model, so they're replaced rather than kept
 * alongside contradictory assertions.
 *
 * The `onboarding/profile/…` keys are file-private in `OnboardingFlow.kt`. [FocusKey] is a Kotlin value class
 * (equality by wrapped string), so the literals below match the production keys without needing a
 * visibility change on the production `private val`s.
 */
class OnboardingProfileNameFieldFocusTest {

    private val nameFieldKey = FocusKey("onboarding/profile/name")
    private val continueKey = FocusKey("onboarding/profile/continue")
    private val notNowKey = FocusKey("onboarding/profile/notNow")

    @Test
    fun nameFieldIsReachableFromContinueEntryViaNavUp() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        registerProfileControls(authority)
        val profile = OnboardingProfileState()
        val focusIntent = OnboardingFocusIntentState()

        assertEquals(continueKey, authority.state.value.focused) // documented entry point, before any input

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_UP)

        assertEquals(nameFieldKey, authority.state.value.focused)
    }

    @Test
    fun navUpFromEveryProfileKeyAlwaysReachesTheNameField() {
        // The reported navigation requirement: UP must land on the field from EVERY starting key, not
        // just the one list-step away. Three independent dispatch chains, one per starting key, each ending
        // on a fresh authority so a prior chain's landing can't leak into the next as the "current" focus.
        listOf(nameFieldKey, continueKey, notNowKey).forEach { startKey ->
            val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
            registerProfileControls(authority)
            authority.requestFocus(startKey)
            val profile = OnboardingProfileState()
            val focusIntent = OnboardingFocusIntentState()

            dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_UP)

            assertEquals(
                "UP from $startKey must land on the name field",
                nameFieldKey,
                authority.state.value.focused,
            )
        }
    }

    @Test
    fun navDownFromTheNameFieldLandsOnContinueTheRowsOwnEntryDefault() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        registerProfileControls(authority)
        val profile = OnboardingProfileState()
        val focusIntent = OnboardingFocusIntentState()

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_UP) // Continue -> field
        assertEquals(nameFieldKey, authority.state.value.focused)

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_DOWN)
        assertEquals(continueKey, authority.state.value.focused)
    }

    @Test
    fun navLeftAndRightMoveOnlyWithinTheButtonRowAndClampAtEachEnd() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        registerProfileControls(authority)
        val profile = OnboardingProfileState()
        val focusIntent = OnboardingFocusIntentState()

        assertEquals(continueKey, authority.state.value.focused) // entry point

        // LEFT off Continue (the row's own left end) is a no-op, never reaches the field.
        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_LEFT)
        assertEquals(continueKey, authority.state.value.focused)

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_RIGHT)
        assertEquals(notNowKey, authority.state.value.focused)

        // RIGHT off Not now (the row's own right end) clamps, never wraps to the field.
        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_RIGHT)
        assertEquals(notNowKey, authority.state.value.focused)

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_LEFT)
        assertEquals(continueKey, authority.state.value.focused)
    }

    @Test
    fun navLeftAndRightFromTheNameFieldAreNoOpsItHasNoHorizontalPeer() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        registerProfileControls(authority)
        val profile = OnboardingProfileState()
        val focusIntent = OnboardingFocusIntentState()

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_UP) // Continue -> field
        assertEquals(nameFieldKey, authority.state.value.focused)

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_LEFT)
        assertEquals(nameFieldKey, authority.state.value.focused)

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_RIGHT)
        assertEquals(nameFieldKey, authority.state.value.focused)
    }

    @Test
    fun confirmOnFocusedNameFieldOpensTheKeyboardJustLikeTap() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        registerProfileControls(authority)
        val profile = OnboardingProfileState()
        val focusIntent = OnboardingFocusIntentState()

        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_UP)
        assertEquals(nameFieldKey, authority.state.value.focused)
        assertFalse(profile.keyboardOpen)

        val handled = dispatchProfile(authority, profile, focusIntent, ControllerAction.CONFIRM)

        assertTrue(handled)
        assertTrue(profile.keyboardOpen)
        // Opening the keyboard must not silently touch the draft or raise the empty-name confirm.
        assertEquals("", profile.draft)
        assertFalse(profile.confirmOpen)
    }

    @Test
    fun confirmOnNotNowStillSkipsNameless() {
        // Regression guard: the CONFIRM branch changed from a two-way if/else to a three-way when() to add
        // the field case — Not now's own behavior must be byte-for-byte unchanged.
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        registerProfileControls(authority)
        val profile = OnboardingProfileState()
        val focusIntent = OnboardingFocusIntentState()
        var advanced = false

        // RIGHT, not the old DOWN: under the spatial model DOWN from Continue is a no-op (already in the
        // button row), and Continue -> Not now is a same-row move.
        dispatchProfile(authority, profile, focusIntent, ControllerAction.NAV_RIGHT) // Continue -> Not now
        assertEquals(notNowKey, authority.state.value.focused)

        dispatchProfile(authority, profile, focusIntent, ControllerAction.CONFIRM, onAdvance = { advanced = true })

        assertTrue(advanced)
        assertFalse(profile.keyboardOpen)
    }

    @Test
    fun confirmOnContinueWithADraftedNameStillCommitsAndAdvances() {
        // Regression guard: Continue's own behavior (the when's `else` branch) must be byte-for-byte unchanged.
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        registerProfileControls(authority)
        val profile = OnboardingProfileState().apply { draft = "Freya" }
        val focusIntent = OnboardingFocusIntentState()
        var committed: String? = null
        var advanced = false

        assertEquals(continueKey, authority.state.value.focused) // entry point, no nav needed

        dispatchProfile(
            authority,
            profile,
            focusIntent,
            ControllerAction.CONFIRM,
            onAdvance = { advanced = true },
            onCommitName = { committed = it },
        )

        assertEquals("Freya", committed)
        assertTrue(advanced)
    }

    /**
     * Registers all three PROFILE controls and explicitly lands focus on Continue — reproducing the state
     * [OnboardingProfileScreen]'s own `landFocus` effect puts the screen in on entry (entryFocus = Continue,
     * "Controller-first" KDoc) — rather than relying on [DefaultFocusAuthority]'s registration-order
     * auto-seed (`maybeSeedFocusOnRegister`), which would otherwise seed whichever key registers first.
     */
    private fun registerProfileControls(authority: DefaultFocusAuthority) {
        authority.register(nameFieldKey, OnboardingScope, FocusBounds(left = 0f, top = 0f, right = 300f, bottom = 60f))
        authority.register(continueKey, OnboardingScope, FocusBounds(left = 0f, top = 100f, right = 150f, bottom = 160f))
        authority.register(notNowKey, OnboardingScope, FocusBounds(left = 160f, top = 100f, right = 300f, bottom = 160f))
        authority.requestFocus(continueKey)
    }

    private fun dispatchProfile(
        authority: DefaultFocusAuthority,
        profile: OnboardingProfileState,
        focusIntent: OnboardingFocusIntentState,
        action: ControllerAction,
        onAdvance: () -> Unit = {},
        onCommitName: (String) -> Unit = {},
    ): Boolean = dispatchOnboarding(
        authority = authority,
        step = OnboardingStep.PROFILE,
        profile = profile,
        accessReview = OnboardingAccessReviewState(),
        focusIntent = focusIntent,
        access = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = true,
            homeRoleAvailable = true,
            homeRoleHeld = true,
        ),
        library = OnboardingLibraryState(),
        artworkAccount = null,
        settlement = OnboardingSettlementUi(
            phase = OnboardingSettlementPhase.READY,
            title = "Ready",
            detail = "Ready",
        ),
        action = action,
        onAdvance = onAdvance,
        onCommitName = onCommitName,
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
}
