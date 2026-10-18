package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeLaunchReturnTargetTest {
    @Test
    fun capturesExactIdentityRankAndFocusKey() {
        val row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("chrono/cross")),
            rank = 4_096L,
            addedAtMillis = 42L,
            title = "Chrono Cross",
        )

        val target = HomeLaunchReturnTarget.from(row)

        assertEquals(row.itemId, target.itemId)
        assertEquals(row.rank, target.rank)
        assertEquals(FocusKey("home/shelf/emulated/chrono%2Fcross"), target.focusKey)
    }

    @Test
    fun rejectsFocusKeyFromAnotherIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeLaunchReturnTarget(
                itemId = HomeShelfItemId.Emulated(GameId("right")),
                rank = 1_024L,
                focusKey = FocusKey("home/shelf/emulated/wrong"),
            )
        }
    }
}
