package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the Main launch card's SESSION TIME row shows the game's total once the elapsed store has one, in the row's own words,
 * beside LAST PLAYED. The label is the existing one (session time is elapsed time between dispatch and return). What the store holds, and when it
 * starts holding it for a game, is `GameSessionPlayTimeTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardPlayTimeRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mount(elapsedMillis: Long?, lastPlayed: String? = "Played today") {
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f),
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(832.5f.dp).requiredHeight(468.3f.dp)) {
                        HomeLaunchCard(
                            state = HomeLaunchCardUiState(
                                row = HomeShelfRow(
                                    itemId = HomeShelfItemId.Emulated(GameId("game-1")),
                                    rank = 0L,
                                    addedAtMillis = 0L,
                                    title = "Super Mario 64",
                                    supportingLabel = "n64",
                                    externalSessionElapsedMillis = elapsedMillis,
                                ),
                                platformLabel = "Nintendo 64",
                                emulatorLabel = "RetroArch",
                                lastPlayedLabel = lastPlayed,
                            ),
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String, ignoreCase: Boolean = true): Boolean =
        compose.onAllNodes(hasText(text, ignoreCase = ignoreCase) or hasContentDescription(text, ignoreCase = ignoreCase), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a game with a total shows it, in the row's words, beside last played`() {
        mount(elapsedMillis = 5_040_000L)

        assertEquals(true, shown("SESSION TIME"))
        assertEquals("the old label is gone", false, shown("EXTERNAL ELAPSED"))
        assertEquals("the plain total, as the Bottom writes it", true, shown("1 h 24 min", ignoreCase = false))
        assertEquals("last played keeps its place and its style", true, shown("Played today"))
        assertEquals("a total is never the old sentence", false, shown("NOT TRACKED"))
    }

    @Test
    fun `a few minutes read as minutes`() {
        mount(elapsedMillis = 4 * 60_000L)
        assertEquals(true, shown("4 min", ignoreCase = false))
    }

    @Test
    fun `a game that was launched and returned in no measured time says so`() {
        mount(elapsedMillis = 0L)
        assertEquals(true, shown("NO MEASURED ELAPSED"))
        assertEquals("a launched game is not 'not played yet'", false, shown("NOT PLAYED YET"))
    }

    @Test
    fun `a launched game with no store entry reads not tracked`() {
        mount(elapsedMillis = null)
        assertEquals(true, shown("NOT TRACKED"))
    }

    @Test
    fun `a game never launched reads not played yet with no entry or a zero`() {
        mount(elapsedMillis = null, lastPlayed = null)
        assertEquals(true, shown("NOT PLAYED YET"))
        assertEquals(false, shown("NOT TRACKED"))
    }

    @Test
    fun `a never-launched game holding a store zero reads not played yet, not no measured elapsed`() {
        mount(elapsedMillis = 0L, lastPlayed = null)
        assertEquals(true, shown("NOT PLAYED YET"))
        assertEquals(false, shown("NO MEASURED ELAPSED"))
    }
}
