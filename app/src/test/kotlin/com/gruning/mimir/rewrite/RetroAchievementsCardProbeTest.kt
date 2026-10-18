package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card opened for a game the gamelist never probed (Library search -> launch card -> Achievements) used to wait
 * forever on "no answer yet". It now owns a probe whenever nothing real has answered for its game.
 */
class RetroAchievementsCardProbeTest {

    @Test
    fun `no answer for this game means the card runs its own probe`() {
        assertTrue(achievementCardNeedsOwnProbe(null))
    }

    @Test
    fun `a Checking placeholder that may have been cancelled is re-probed`() {
        assertTrue(achievementCardNeedsOwnProbe(retroAchievementsCheckingState()))
    }

    @Test
    fun `a real answer is shown without a second request`() {
        assertFalse(achievementCardNeedsOwnProbe(retroAchievementsUnreachableState()))
        assertFalse(
            achievementCardNeedsOwnProbe(MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.NOT_MATCHED)),
        )
        assertFalse(
            achievementCardNeedsOwnProbe(
                MimirAchievementLaneState.Loaded(
                    RetroAchievementsProgress(
                        gameId = 1,
                        title = "Game",
                        achievements = listOf(
                            RetroAchievementsAchievement(1, "First", "", 5, unlocked = true, unlockedHardcore = false),
                        ),
                    ),
                ),
            ),
        )
    }
}
