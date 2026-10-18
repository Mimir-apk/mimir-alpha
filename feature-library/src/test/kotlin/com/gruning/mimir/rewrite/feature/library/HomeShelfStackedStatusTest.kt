package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The compact title block's stacked status chip (2026-10-11). The block is a fixed 60 dp band (an eyebrow lane and one title line),
 * and the chip used to take a THIRD line under the title: the band could not hold it and the chip was cut at the bottom on the RG's
 * square, on Home and on the Library alike. Stacked, the chip now sits at the end of the eyebrow row, so the block stays two lines and
 * the chip is whole at its natural size; a long eyebrow is what gives way, at a word. The eyebrow row is as tall as the chip with or without
 * one, so the title holds its place when focus moves between a game with a status and one with none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShelfStackedStatusTest {

    @get:Rule
    val compose = createComposeRule()

    private val compactGeometry = solveHomeShelfGeometry(564.7f, 540.7f, compact = true)

    private val chipState = mutableStateOf<GameStatusChip?>(GameStatusChip("Ready", true))
    private val tomeMarkState = mutableStateOf<String?>(null)

    private fun mount(eyebrow: String?, stack: Boolean = true, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MimirTheme {
                    Box(Modifier.size(564.dp, 540.dp)) {
                        HomeShelfTitle(
                            title = "Ledge Cover",
                            geometry = compactGeometry,
                            eyebrow = eyebrow,
                            statusChip = chipState.value,
                            tomeMark = tomeMarkState.value,
                            stackStatus = stack,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun chip() = compose.onNodeWithTag(HomeShelfStatusTestTag).fetchSemanticsNode().boundsInRoot

    private fun band() = compose.onNodeWithTag(HomeShelfTitleTestTag).fetchSemanticsNode().boundsInRoot

    private fun textBounds(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    @Test
    fun `the stacked chip stands in the eyebrow row, whole and inside the 60 dp band`() {
        mount("Platformer · GBA")
        val chip = chip()
        val band = band()
        assertEquals("the band is the solved 60 dp", 60f, band.height, 0.5f)
        assertTrue("the chip (${chip.top}..${chip.bottom}) is inside the band (${band.top}..${band.bottom})", chip.top >= band.top - 0.5f && chip.bottom <= band.bottom + 0.5f)
        assertTrue("a pill of its natural height, not a clipped sliver (${chip.height} dp)", chip.height >= 16f)
        val title = textBounds("Ledge Cover")
        assertTrue("the chip (bottom ${chip.bottom}) is on the eyebrow's line, above the title (top ${title.top})", chip.bottom <= title.top + 1f)
        val eyebrow = textBounds("Platformer", substring = true)
        assertTrue("the eyebrow stands beside it (${eyebrow.right} <= ${chip.left})", eyebrow.right <= chip.left + 0.5f)
    }

    @Test
    fun `the chip stays whole at font scale 1_3, still inside the band`() {
        mount("Platformer · GBA", fontScale = 1.3f)
        val chip = chip()
        val band = band()
        assertTrue("the chip is whole at 1.3 (${chip.height} dp)", chip.height >= 20f)
        assertTrue("and inside the band (${chip.bottom} <= ${band.bottom})", chip.bottom <= band.bottom + 0.5f)
    }

    @Test
    fun `a long eyebrow gives way to the chip at a word, and the chip is never narrowed`() {
        mount("Platformer · Dolphin Emulator · Last played three days ago on the handheld")
        val chip = chip()
        val band = band()
        assertTrue("the chip is whole (${chip.width} wide) and inside the band's right edge", chip.width >= 60f && chip.right <= band.right + 0.5f)
        val eyebrowNode = compose.onAllNodes(hasText("Platformer", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first()
        val shown = eyebrowNode.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("the eyebrow was shortened: <$shown>", shown.endsWith("…"))
        val head = shown.removeSuffix("…")
        val full = "Platformer · Dolphin Emulator · Last played three days ago on the handheld"
        assertTrue("and cut between two words, not inside one: <$shown>", full.startsWith(head) && full[head.length].isWhitespace())
        assertTrue("clear of the chip (${eyebrowNode.boundsInRoot.right} <= ${chip.left})", eyebrowNode.boundsInRoot.right <= chip.left + 0.5f)
    }

    /** The title's top with a status chip, with none, and with only the Tome mark (which is shorter than the chip): all one place. */
    private fun assertTitleHoldsStill(eyebrow: String?, fontScale: Float) {
        mount(eyebrow, fontScale = fontScale)
        val withChip = textBounds("Ledge Cover").top
        assertTrue("the chip is seated (the check would be vacuous without one)", compose.onNodeWithTag(HomeShelfStatusTestTag).fetchSemanticsNode().boundsInRoot.height > 0f)
        chipState.value = null
        compose.waitForIdle()
        assertEquals("no chip at all: the title's top stays put (font scale $fontScale)", withChip, textBounds("Ledge Cover").top, 0.01f)
        tomeMarkState.value = "Eok"
        compose.waitForIdle()
        assertEquals("the Tome mark alone: the title's top stays put (font scale $fontScale)", withChip, textBounds("Ledge Cover").top, 0.01f)
        chipState.value = GameStatusChip("Ready", true)
        tomeMarkState.value = null
        compose.waitForIdle()
        assertEquals("the chip back: the title's top is where it began (font scale $fontScale)", withChip, textBounds("Ledge Cover").top, 0.01f)
    }

    @Test
    fun `the title holds its place whether or not the game has a status, at font scale 1_0`() =
        assertTitleHoldsStill("Platformer · GBA", fontScale = 1f)

    @Test
    fun `the title holds its place whether or not the game has a status, at font scale 1_3`() =
        assertTitleHoldsStill("Platformer · GBA", fontScale = 1.3f)

    @Test
    fun `a game with no eyebrow and no status still holds the title where a chip puts it`() =
        assertTitleHoldsStill(null, fontScale = 1.3f)

    @Test
    fun `a standard screen keeps the chip beside the title as it was`() {
        mount("Platformer · GBA", stack = false)
        val chip = chip()
        val title = textBounds("Ledge Cover")
        assertTrue("beside the title, on its line (${chip.left} >= ${title.right})", chip.left >= title.right - 0.5f)
        assertTrue("on the title's line, not the eyebrow's", chip.top >= textBounds("Platformer", substring = true).bottom - 1f)
    }
}
