package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirShatterMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Walk-32h, Phase 3: the LIST view and the Favorites list. Their rows are the real [GamelistRow], given what the Library's
 * own list gives them: the FOCUSED row draws through a capture layer and reports its bounds, and the stage's overlay is
 * played over the list. The row still leaves at the write as it always did (there is no departure here), so what has to
 * hold is that the stage always names the focused row and no other, that it lets go of a row that loses focus, and that
 * a job is played and cleared on the shatter's clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryShatterListPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val games = List(3) { index ->
        GameRow(
            gameId = GameId("g$index"),
            title = "Game $index",
            platformId = PlatformId.GBA,
            memberCount = 1,
            duplicateKind = null,
        )
    }
    private val authority = DefaultFocusAuthority()
    private val focused = mutableStateOf(games[0].gameId.value)
    private val stage = LibraryShatterStage()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(600.dp, 400.dp)) {
                    Column {
                        games.forEach { row ->
                            val isFocused = focused.value == row.gameId.value
                            GamelistRow(
                                row = row,
                                supportLabel = null,
                                badge = null,
                                launchStatusLine = null,
                                focused = isFocused,
                                authority = authority,
                                key = gameKey(row),
                                onActivate = {},
                                modifier = rememberLibraryShatterPanel(stage, active = isFocused, gameId = row.gameId.value),
                            )
                        }
                    }
                    LibraryShatterHost(stage)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100L)
    }

    private fun focusOn(game: GameRow) {
        focused.value = game.gameId.value
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48L)
    }

    private fun count() = compose.onAllNodesWithTag(LibraryShatterOverlayTag).fetchSemanticsNodes().size

    @Test
    fun `the focused row is the one the stage can break, with its picture source and its place, and no other`() {
        host()

        assertEquals("g0", stage.centreGameId)
        assertNotNull("a layer to read the picture from", stage.centreCapture)
        val first = stage.centreBounds
        assertNotNull("the live place of the row, asked for again when the picture is taken", stage.centreCoordinates)
        assertNotNull("where the row is, in the window", first)
        assertTrue("and it is a real row: ${first}", first!!.width > 0f && first.height > 0f)

        focusOn(games[1])

        assertEquals("focus moved, so the stage names the new row", "g1", stage.centreGameId)
        val second = stage.centreBounds
        assertNotNull(second)
        assertTrue("one row lower than the first: $first then $second", second!!.top > first.top)
        assertEquals("the same row height", first.height, second.height, 1f)
    }

    @Test
    fun `a row that loses focus withdraws from the stage, so a stale picture is never broken`() {
        host()
        assertNotNull(stage.centreCapture)

        // Nothing is focused: the stage holds nothing.
        focused.value = "nobody"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48L)

        assertNull(stage.centreCapture)
        assertNull(stage.centreBounds)
        assertNull(stage.centreCoordinates)
        assertNull(stage.centreGameId)
    }

    @Test
    fun `a job is played over the list and cleared when the last shard has faded`() {
        host()
        val job = LibraryShatterJob(ImageBitmap(64, 48), Rect(20f, 10f, 220f, 58f), "g0")
        stage.job = job
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96L)

        assertEquals("the overlay is on screen", 1, count())
        assertTrue("its clock is running: ${job.clock.value}", job.clock.value > 0f)

        compose.mainClock.advanceTimeBy(MimirShatterMotion.EndMs.toLong() + MimirMotion.OverlayMs)

        assertEquals("gone once the shards have faded", 0, count())
        assertNull("and its job is cleared", stage.job)
    }
}
