package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A card the hub opens from a badge tap shows that badge's detail on its FIRST frame (audit B-84a, RC-50). The selection used to be born null and set by an effect, so the first
 * frame said "Tap a badge" (or nothing) and the detail landed a frame later. The clock is paused and not advanced after the content is set, so no effect has run: what is on the
 * screen is the first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityPanelSelectionSeedTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f

    private val snapshot = CompanionActivityAchievementSnapshot(
        gameId = "g5",
        account = "me",
        unlocked = 2,
        total = 3,
        gameTitle = "Game 5",
        rowsKnown = false,
        raGameId = 5,
    )
    private val selection = ActivityBadgeSelection(
        raGameId = 5,
        achievementId = 77,
        detail = ActivityBadgeDetail("Green Hill Hero", "Reach the end of Green Hill", "5 points · Earned 2023-05-01"),
        serial = 1,
    )

    private fun firstFrameTexts(selection: ActivityBadgeSelection?): List<String> {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(300.dp, 400.dp)) {
                        ActivityAchievementPanel(
                            snapshot = snapshot,
                            detail = null,
                            artResolver = NoActivityAchievementArt,
                            badgeSource = NoActivityBadgeArt,
                            reducedMotion = true,
                            modifier = Modifier.size(300.dp, 400.dp),
                            badgeSelection = selection,
                        )
                    }
                }
            }
        }
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    @Test
    fun `a card opened from a badge tap shows that badge's detail on its first frame`() {
        val texts = firstFrameTexts(selection)
        assertTrue("the badge's title is on the first frame: $texts", "Green Hill Hero" in texts)
        assertTrue("and its meta line: $texts", texts.any { it.startsWith("5 points") })
    }

    @Test
    fun `a card nothing was asked of opens with no detail, as before`() {
        val texts = firstFrameTexts(null)
        assertTrue("no badge detail: $texts", "Green Hill Hero" !in texts)
    }

    @Test
    fun `a selection for another game seeds nothing`() {
        val texts = firstFrameTexts(selection.copy(raGameId = 6))
        assertTrue("another card's badge is not this card's detail: $texts", "Green Hill Hero" !in texts)
    }
}
