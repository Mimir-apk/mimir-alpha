package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.mimirAchievementCardOffersProfileSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product review 2026-09-28 (F3): a NEEDS SETUP achievement card used to be a dead end. It now shows one action,
 * drawn focused, and A opens Settings on the RetroAchievements row. Every other card keeps A inert.
 */
class AchievementCardSetupActionTest {

    private val needsSetup = listOf(
        MimirAchievementCopy.NoCredentials,
        MimirAchievementCopy.OptedOut,
        MimirAchievementCopy.CredentialsRejected,
    )

    @Test
    fun `A on a card that needs setup opens Settings`() {
        needsSetup.forEach { reason ->
            val state = MimirAchievementCardState.Unknown(reason)
            assertTrue(reason, mimirAchievementCardOffersProfileSetup(state))
            var opened = 0
            var closed = 0
            val handled = dispatchAchievementCard(
                action = ControllerAction.CONFIRM,
                onScroll = {},
                onBack = { closed += 1 },
                setupActionShown = mimirAchievementCardOffersProfileSetup(state),
                onOpenProfile = { opened += 1 },
            )
            assertTrue(handled)
            assertEquals(reason, 1, opened)
            assertEquals(reason, 0, closed)
        }
    }

    @Test
    fun `A stays inert on cards with nothing to set up`() {
        val others = listOf(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.Checking),
            MimirAchievementCardState.Unknown(MimirAchievementCopy.Unreachable),
            MimirAchievementCardState.Unknown(MimirAchievementCopy.SystemUnsupported),
            MimirAchievementCardState.NotMatched(MimirAchievementCopy.NoSetForGame),
            MimirAchievementCardState.Loaded(
                gameTitle = "Sample Game",
                unlocked = 1,
                total = 2,
                earnedPoints = 5,
                totalPoints = 10,
                rows = emptyList(),
            ),
        )
        others.forEach { state ->
            assertFalse(state.toString(), mimirAchievementCardOffersProfileSetup(state))
            var opened = 0
            assertTrue(
                dispatchAchievementCard(
                    action = ControllerAction.CONFIRM,
                    onScroll = {},
                    onBack = {},
                    setupActionShown = mimirAchievementCardOffersProfileSetup(state),
                    onOpenProfile = { opened += 1 },
                ),
            )
            assertEquals(state.toString(), 0, opened)
        }
    }

    @Test
    fun `B still closes a card that needs setup`() {
        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
            var opened = 0
            var closed = 0
            dispatchAchievementCard(
                action = action,
                onScroll = {},
                onBack = { closed += 1 },
                setupActionShown = true,
                onOpenProfile = { opened += 1 },
            )
            assertEquals(1, closed)
            assertEquals(0, opened)
        }
    }
}
