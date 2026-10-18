package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.homeLaunchCardActionTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On compact screens the launch card shows only the header, art and buttons.
 * The standard card is the control proving these assertions can detect the details.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeLaunchCardCompactMinimalTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Chrono Trigger",
            supportingLabel = "snes",
        ),
        platformLabel = "Super Nintendo",
        achievementProgress = "12 / 40",
        emulatorLabel = "RetroArch",
        lastPlayedLabel = "Yesterday",
    )

    @Test
    fun `the compact card shows the title and Play, and none of the specifics`() {
        setCard(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 565f, 565f), widthDp = 565f, heightDp = 565f)
        assertTrue("the title", count("Chrono Trigger") >= 1)
        compose.onNodeWithTag(homeLaunchCardActionTestTag(HomeLaunchCardPlayKey), useUnmergedTree = true)
            .assertExists()
        assertEquals("achievement progress", 0, count("12 / 40"))
        assertEquals("emulator fact", 0, count("RetroArch"))
        assertEquals("last played fact", 0, count("Yesterday"))
        assertEquals("About heading", 0, count("About"))
    }

    @Test
    fun `the standard card still shows the specifics (control)`() {
        setCard(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), widthDp = 832.5f, heightDp = 468.3f)
        assertEquals(1, count("RetroArch"))
        assertEquals(1, count("Yesterday"))
    }

    private fun count(text: String): Int =
        compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun setCard(profile: MimirViewportProfile, widthDp: Float, heightDp: Float) {
        val authority = DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1f),
                LocalMimirViewportProfile provides profile,
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        HomeLaunchCard(
                            state = state,
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
