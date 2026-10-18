package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirPlatformShelfmarkCode
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Tome folio cell that holds a game with no art used to
 * paint the empty slot's own plate, so a two-game Tome read as fifteen empty boxes. An occupied art-less cell now
 * draws the blank-book title lockup: the game's title, and the platform code as the book's eyebrow. The thirteen
 * empty slots stay plain.
 *
 * The harness is the app module's `HomeTomeFolioTouchGrammarTest`, copied: the real `HomeTomeFolio` under
 * Robolectric at the Thor's landscape viewport. Seat 0 holds focus, so the footer names only the first game; the
 * second game's title and its platform code can only come from its book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeTomeFolioTitleBookTest {

    @get:Rule
    val compose = createComposeRule()

    private val first = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("game-1")),
        title = "Chrono Trigger",
    )
    private val second = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("game-2")),
        title = "Secret of Mana",
        platformId = PlatformId.SNES,
    )

    private fun host() {
        val authority = DefaultFocusAuthority().apply { pushScope(HomeTomeFolioScope) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(832.5.dp).requiredHeight(468.3.dp)) {
                        HomeTomeFolio(
                            state = HomeTomeFolioUiState(
                                tome = HomeShelfTome(
                                    tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000013")),
                                    title = "Rpgs",
                                    previews = emptyList(),
                                    memberCount = 2,
                                ),
                                members = listOf(first, second),
                                focusedIndex = 0,
                                mode = HomeTomeFolioMode.BROWSE,
                                focusSettled = true,
                            ),
                            authority = authority,
                            onFocusMember = {},
                            onOpenMember = {},
                            onArrangeAction = {},
                            onFirstInteraction = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `each occupied cell without art draws a title book and no empty slot does`() {
        host()
        compose.onAllNodesWithTag(HomeTomeFolioTitleBookTestTag, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `the unfocused art-less game shows its own title on its book`() {
        host()
        compose.onNodeWithText(second.title, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the book carries the platform code as its badge`() {
        host()
        val code = checkNotNull(mimirPlatformShelfmarkCode(PlatformId.SNES.value)).uppercase()
        compose.onNodeWithText(code, useUnmergedTree = true).assertExists()
    }
}
