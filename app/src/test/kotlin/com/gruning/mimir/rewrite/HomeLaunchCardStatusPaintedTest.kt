package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.homeLaunchCardActionTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeLaunchCardStatusPaintedTest {

    @get:Rule
    val compose = createComposeRule()

    private val row = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("game-1")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "Chrono Trigger",
        supportingLabel = "dreamcast",
    )

    @Test
    fun runtimeLaunchFeedbackIsPaintedWithoutRemovingPlay() {
        val runtimeLine = "No installed emulator for Dreamcast. Open Settings › Emulators to install one."
        val runtimeStatusLine = mutableStateOf<String?>(null)
        setCard(runtimeStatusLine = runtimeStatusLine)
        val play = compose.onNodeWithTag(
            homeLaunchCardActionTestTag(HomeLaunchCardPlayKey),
            useUnmergedTree = true,
        )
        val playSemantics = play.fetchSemanticsNode()
        val playPositionInRoot = playSemantics.positionInRoot
        val playSize = playSemantics.size

        runtimeStatusLine.value = runtimeLine
        compose.waitForIdle()

        compose.onNodeWithText(runtimeLine, useUnmergedTree = true).assertIsDisplayed()
        val playAfter = compose.onNodeWithTag(
            homeLaunchCardActionTestTag(HomeLaunchCardPlayKey),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        assertEquals(playPositionInRoot, playAfter.positionInRoot)
        assertEquals(playSize, playAfter.size)
    }

    /**
     * F7 (product review 2026-09-28, nova-9): PLAY on a game with no emulator moved the details block 31 dp,
     * because the feedback line only mounted once there was feedback. The band is reserved now, so the first
     * detail value sits where it sat before the line appeared.
     */
    @Test
    fun `launch feedback appearing does not move the details below it`() {
        val runtimeStatusLine = mutableStateOf<String?>(null)
        setCard(runtimeStatusLine = runtimeStatusLine)
        val before = compose.onNodeWithText("Automatic", useUnmergedTree = true)
            .fetchSemanticsNode()
            .positionInRoot

        runtimeStatusLine.value =
            "No installed emulator for Dreamcast. Open Settings › Emulators to install one."
        compose.waitForIdle()

        val after = compose.onNodeWithText("Automatic", useUnmergedTree = true)
            .fetchSemanticsNode()
            .positionInRoot
        assertEquals("the details block must not move when launch feedback appears", before, after)

        runtimeStatusLine.value = null
        compose.waitForIdle()
        assertEquals(
            "nor when it clears",
            before,
            compose.onNodeWithText("Automatic", useUnmergedTree = true).fetchSemanticsNode().positionInRoot,
        )
    }

    @Test
    fun readyStatusLineIsNotPaintedWithoutRuntimeFeedback() {
        setCard(statusLine = MimirCopy.StatusReady)

        assertTrue(
            compose.onAllNodesWithText(MimirCopy.StatusReady, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    private fun setCard(
        statusLine: String? = null,
        runtimeStatusLine: MutableState<String?> = mutableStateOf<String?>(null),
    ) {
        val authority = DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(modifier = androidx.compose.ui.Modifier.requiredWidth(832.5.dp).requiredHeight(468.3.dp)) {
                        HomeLaunchCard(
                            state = HomeLaunchCardUiState(
                                row = row,
                                platformLabel = "Dreamcast",
                                statusLine = statusLine,
                                runtimeStatusLine = runtimeStatusLine.value,
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
}
