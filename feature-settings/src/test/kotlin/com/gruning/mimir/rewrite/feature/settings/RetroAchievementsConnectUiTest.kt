package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAchievementsConnectUiTest {
    @Test
    fun `validation and uncertain durable recovery block Back and scrim dismissal`() {
        assertFalse(retroAchievementsConnectMayClose(RetroAchievementsConnectUi.Validating("player")))
        assertFalse(
            retroAchievementsConnectMayClose(
                RetroAchievementsConnectUi.RecoveryUncertain("player"),
            ),
        )
        assertTrue(retroAchievementsConnectMayClose(RetroAchievementsConnectUi.EnterUsername()))
        assertTrue(retroAchievementsConnectMayClose(RetroAchievementsConnectUi.EnterKey("player")))
        assertTrue(retroAchievementsConnectMayClose(RetroAchievementsConnectUi.Accepted("player")))
        assertTrue(
            retroAchievementsConnectMayClose(
                RetroAchievementsConnectUi.Rejected(
                    "player",
                    RetroAchievementsConnectUi.RejectedField.API_KEY,
                ),
            ),
        )
        assertTrue(retroAchievementsConnectMayClose(RetroAchievementsConnectUi.Unreachable("player")))
        assertTrue(retroAchievementsConnectMayClose(RetroAchievementsConnectUi.SaveFailed("player")))
    }
}
