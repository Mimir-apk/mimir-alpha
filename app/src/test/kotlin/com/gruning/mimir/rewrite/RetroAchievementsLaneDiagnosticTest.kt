package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The MimirRA outcome line is structural: platform id and typed state, nothing about the player or the file. */
class RetroAchievementsLaneDiagnosticTest {

    @Test
    fun `each lane state has one stable structural line`() {
        assertEquals(
            "lane platform=snes unresolved=OPTED_OUT",
            mimirAchievementLaneDiagnostic("snes", retroAchievementsOptedOutState()),
        )
        assertEquals(
            "lane platform=ps2 unresolved=FILE_UNAVAILABLE",
            mimirAchievementLaneDiagnostic("ps2", retroAchievementsFileUnavailableState()),
        )
        assertEquals(
            "lane platform=gba noset=NOT_MATCHED",
            mimirAchievementLaneDiagnostic(
                "gba",
                MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.NOT_MATCHED),
            ),
        )
    }

    @Test
    fun `a loaded line carries counts but never the game or achievement titles`() {
        val line = mimirAchievementLaneDiagnostic(
            "genesis",
            MimirAchievementLaneState.Loaded(
                RetroAchievementsProgress(
                    gameId = 77,
                    title = "Secret Title",
                    achievements = listOf(
                        RetroAchievementsAchievement(1, "Hidden One", "d", 5, unlocked = true, unlockedHardcore = false),
                        RetroAchievementsAchievement(2, "Hidden Two", "d", 10, unlocked = false, unlockedHardcore = false),
                    ),
                ),
            ),
        )
        assertEquals("lane platform=genesis loaded=1/2", line)
        assertFalse(line.contains("Secret") || line.contains("Hidden"))
    }
}
