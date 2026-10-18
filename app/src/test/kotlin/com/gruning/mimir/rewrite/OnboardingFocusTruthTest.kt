package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFocusTruthTest {

    @Test
    fun profileModalOpenSuppressesHintBar() {
        val profile = OnboardingProfileState()
        assertTrue(onboardingProfileHintVisible(profile))
        profile.keyboardOpen = true
        assertFalse(onboardingProfileHintVisible(profile))
        profile.keyboardOpen = false
        profile.confirmOpen = true
        assertFalse(onboardingProfileHintVisible(profile))
    }

    @Test
    fun emptyNameConfirmGoldFollowsFocusedPill() {
        assertTrue(onboardingConfirmPillGold(keepFocused = true, continueFocused = false))
        assertFalse(onboardingConfirmPillGold(keepFocused = false, continueFocused = false))
        assertTrue(onboardingConfirmPillGold(keepFocused = false, continueFocused = true))
    }

    @Test
    fun profileConfirmVerticalNavigationUsesBothPills() {
        val keys = listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey)
        val next = onboardingFocusDestination(keys, OnboardingConfirmKeepKey, ControllerAction.NAV_DOWN)
        assertTrue(next == OnboardingConfirmContinueKey)
    }

    // -- Where a step opens --------------------------------------------------------------------

    /**
     *
     *
     * The key lists come from production, not from this file. Restating them here would let the shipped
     * list change shape while the test kept passing against a list nobody renders.
     *
     * METADATA is checked at BOTH its shapes, because connecting an art account removes a row -- and the
     * shorter one is what a returning player sees, so it is exactly where an off-by-one would hide.
     */
    @Test
    fun `a step opens on what it asks for, not on the way out`() {
        assertEquals(
            "PROFILE asks for a name; opening on Continue let one A commit an empty one",
            OnboardingNameFieldKey,
            onboardingEntryFocus(onboardingProfileFocusKeys(), OnboardingContinueKey),
        )
        assertEquals(
            "METADATA with no account connected opens on the account, not past it",
            OnboardingArtworkSetupKey,
            onboardingEntryFocus(
                onboardingMetadataFocusKeys(artworkAccountConnected = false),
                OnboardingMetadataContinueKey,
            ),
        )
        assertEquals(
            "and with one already connected, on the next thing it still asks for",
            OnboardingSteamGridDbKey,
            onboardingEntryFocus(
                onboardingMetadataFocusKeys(artworkAccountConnected = true),
                OnboardingMetadataContinueKey,
            ),
        )
    }

    /**
     * The exemption, as a test, because it is the half that makes the rule safe to apply everywhere: a
     * step that asks for nothing has nowhere else to go and must still land somewhere.
     */
    @Test
    fun `a step that asks nothing still opens on its only control`() {
        val onlyKey = FocusKey("onboarding/test/continue")
        assertEquals(onlyKey, onboardingEntryFocus(listOf(onlyKey), onlyKey))
    }

    /**
     * [onboardingEntryFocus]'s KDoc claims the ACCESS screens are already correct and so are deliberately
     * not routed through it. That is a claim about a different function, and an unchecked claim in a
     * comment is how the next reader concludes the rule is applied there when it is not.
     *
     * The Continue key is taken from ORIENTATION rather than named here: that screen asks for nothing, so
     * its single key IS the advance control, and deriving it means this test cannot disagree with the
     * source about which key that is.
     */
    @Test
    fun `every ACCESS screen already puts something ahead of Continue while it is ungranted`() {
        val pending = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = false,
            homeRoleAvailable = true,
            homeRoleHeld = false,
            filesGranted = false,
            // Explicit, because it DEFAULTS TO TRUE -- "nothing to grant", so that pre-existing fixtures
            // which predate the overlay page keep working. Leaving it at the default made this test read
            // OVERLAY as already granted, which is the one-key shape, and the screen looked like it opened
            // on Continue when it does not.
            overlayGranted = false,
            // The same, for the same reason, for the system-settings page added after run 10.
        )
        val continueKey =
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.ORIENTATION, pending).single()

        OnboardingAccessScreenId.entries
            .filterNot { it == OnboardingAccessScreenId.ORIENTATION }
            .forEach { id ->
                val keys = onboardingAccessScreenFocusKeys(id, pending)
                assertTrue("$id has no focusable keys at all", keys.isNotEmpty())
                assertTrue(
                    "$id opens on Continue, which is the defect this rule exists to prevent",
                    keys.first() != continueKey,
                )
                assertEquals(
                    "$id must open on its own action, not on the way past it",
                    keys.first(),
                    onboardingEntryFocus(keys, continueKey),
                )
            }
    }
}
