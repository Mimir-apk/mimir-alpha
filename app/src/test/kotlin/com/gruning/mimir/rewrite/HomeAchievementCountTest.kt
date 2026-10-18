package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.core.ui.mimirAchievementProgressLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UXP-02 : the Home hero title renders `GAME TITLE   [12/40]   [READY]`.
 *
 * The count reuses the existing RA progress-label authority rather than reformatting anywhere, so these
 * pin the absence/presence rules the brief spells out — and, critically, that there is no way to render
 * a `0/0`, a spinner, a placeholder, or a stale value from the previously focused row.
 */
class HomeAchievementCountTest {

    @Test
    fun `a resolved set with a positive total renders the count`() {
        assertEquals("12/40", mimirAchievementProgressLabel(12, 40))
    }

    @Test
    fun `zero earned of a known set is a real state and still renders`() {
        // Earned-nothing is different from unknown: a game whose set the user has not started reads 0/40.
        assertEquals("0/40", mimirAchievementProgressLabel(0, 40))
    }

    @Test
    fun `an unresolved value on either side renders nothing at all`() {
        assertNull(mimirAchievementProgressLabel(null, 40))
        assertNull(mimirAchievementProgressLabel(12, null))
        assertNull(mimirAchievementProgressLabel(null, null))
    }

    @Test
    fun `a zero or negative total never renders — no 0 of 0 pill`() {
        assertNull("0/0 must never reach the title lane", mimirAchievementProgressLabel(0, 0))
        assertNull(mimirAchievementProgressLabel(3, 0))
        assertNull(mimirAchievementProgressLabel(0, -1))
    }

    @Test
    fun `the count is clamped so a provider overshoot cannot render nonsense`() {
        assertEquals("40/40", mimirAchievementProgressLabel(99, 40))
    }

    @Test
    fun `no lane state means no label, which is how a stale cross-row count is prevented`() {
        // The coordinator tags its single live answer with the game it is about, and
        // `mimirAchievementLaneLabel(null)` is what an untagged/mismatched row resolves to. A row whose
        // probe has not resolved therefore shows NOTHING rather than the previous row's count.
        assertNull(mimirAchievementLaneLabel(null))
    }

    @Test
    fun `unresolved and no-set lane states also render no count`() {
        assertNull(
            mimirAchievementLaneLabel(
                MimirAchievementLaneState.Unresolved(MimirAchievementUnresolvedReason.entries.first()),
            ),
        )
        assertNull(
            mimirAchievementLaneLabel(
                MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.entries.first()),
            ),
        )
    }

    @Test
    fun `dock focus keeps the remembered Home row as the hero and RA probe target`() {
        val row = FocusKey("home/shelf/emulated/game")
        val dock = FocusKey("home/rail/settings")

        assertEquals(
            row,
            homeContentFocusKey(FocusState(HomeShelfScope, row), rememberedHome = null),
        )
        assertEquals(
            row,
            homeContentFocusKey(FocusState(HomeShelfQuickRailScope, dock), rememberedHome = row),
        )
    }
}
