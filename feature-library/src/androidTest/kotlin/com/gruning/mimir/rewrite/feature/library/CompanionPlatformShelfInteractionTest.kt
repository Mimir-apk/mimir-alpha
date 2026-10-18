package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionPlatformShelfInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playIsConsumedOnceAndInactiveRouteCannotBackOrReviveCard() {
        val game = GameRow(
            gameId = GameId("shelf-interaction-game"),
            title = "Shelf Interaction Game",
            platformId = PlatformId("test-platform"),
            memberCount = 1,
            duplicateKind = null,
        )
        val launches = mutableListOf<GameRow>()
        var backCalls = 0
        var active by mutableStateOf(true)
        val repository = SingleGameLibraryRepository(game)

        compose.setContent {
            MimirTheme {
                Box(Modifier.size(900.dp, 700.dp)) {
                    CompanionPlatformShelf(
                        platformIdValue = game.platformId.value,
                        label = "Test Platform",
                        repository = repository,
                        filedOrder = emptyList(),
                        onArranged = {},
                        onBack = { backCalls++ },
                        onLaunch = { launches += it },
                        active = active,
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithText(game.title).assertCountEquals(1)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(game.title).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 1L)
        compose.waitForIdle()
        compose.onAllNodesWithText(game.title).assertCountEquals(2)

        compose.onNodeWithText("PLAY").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(game), launches) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() / 2L)
        compose.waitForIdle()
        compose.onNodeWithText("PLAY").assertExists()
        compose.onNodeWithText("PLAY").performClick()
        compose.onNodeWithText("PLAY").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("repeat PLAY must not dispatch", 1, launches.size) }

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 1L)
        compose.waitForIdle()
        compose.onNodeWithText("PLAY").assertDoesNotExist()
        compose.onAllNodesWithText(game.title).assertCountEquals(1)
        compose.onNodeWithText(game.title).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 1L)
        compose.waitForIdle()
        compose.onAllNodesWithText(game.title).assertCountEquals(2)

        active = false
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("PLAY").performTouchInput { click(center) }
        compose.onNodeWithText("PLAY").performClick()
        compose.onNodeWithTag(CompanionShelfBackPillTag).performTouchInput { click(center) }
        compose.onNodeWithTag(CompanionShelfBackPillTag).performClick()
        compose.runOnIdle {
            assertEquals("inactive PLAY must not dispatch", 1, launches.size)
            assertEquals("inactive Back must not dispatch", 0, backCalls)
        }

        active = true
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 1L)
        compose.waitForIdle()
        compose.onNodeWithText("PLAY").assertDoesNotExist()
        compose.onAllNodesWithText(game.title).assertCountEquals(1)
    }

    private class SingleGameLibraryRepository(
        private val game: GameRow,
    ) : LibraryRepository by FakeLibraryRepository() {
        override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> = flowOf(listOf(game))
    }
}
