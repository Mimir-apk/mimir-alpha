package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V7 of the Thor Bottom revamp, rendered: the Bottom's one choice dialog  on the REAL composables, at the
 * Bottom's own 538 x 468 dp and at font scales 1.0 and 1.3, under Robolectric's native graphics with real text shaping.
 *
 * What is pinned: the game display chooser (the "Opens on" dialog, with and without "Ask next time") and PAUSE GAME. Each is on the menu
 * grammar (the eyebrow, the 18 sp overlay-title heading, the Support paragraph, a beam above each action, each action a row at least 48 dp
 * tall); PAUSE GAME's 176-character paragraph wraps whole, with no ellipsis and no visual overflow, and the panel stays inside the screen;
 * the copy says "Bottom screen". Every number is printed with the tag `V7-RENDER`. Pixel values are the JVM's; the pixels on a handheld are
 * device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionChoiceDialogRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var density = 0f
    private val chosen = mutableListOf<CompanionQuickLaunchDestination>()
    private var dismissed = 0
    private var continued = 0

    private fun mount(fontScale: Float, enabled: Boolean = true, content: @Composable (Boolean) -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, fontScale)) {
                density = LocalDensity.current.density
                MimirTheme {
                    Box(Modifier.requiredSize(BottomWidthDp.dp, BottomHeightDp.dp)) { content(enabled) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
    }

    private fun opensOn(fontScale: Float, editing: Boolean, enabled: Boolean = true, label: String = "Super Mario World") =
        mount(fontScale, enabled) { live ->
            CompanionOpensOnDialog(
                label = label,
                editing = editing,
                enabled = live,
                onSelect = { chosen += it },
                onDismiss = { dismissed++ },
            )
        }

    private fun pauseGame(fontScale: Float, label: String? = "Pokemon") =
        mount(fontScale) { live ->
            CompanionPauseGameDialog(label = label, enabled = live, onContinue = { continued++ }, onDismiss = { dismissed++ })
        }

    private fun layoutOf(tag: String): TextLayoutResult {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) { "$tag has no layout action" }
        check(action(layouts)) { "$tag has no text layout yet" }
        return layouts.single()
    }

    private fun boundsDp(tag: String): FloatArray {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return floatArrayOf(b.left / density, b.top / density, b.width / density, b.height / density)
    }

    private fun actionBoundsDp(): List<FloatArray> =
        compose.onAllNodesWithTag(CompanionChoiceActionTag).fetchSemanticsNodes().map {
            val b = it.boundsInRoot
            floatArrayOf(b.left / density, b.top / density, b.width / density, b.height / density)
        }

    private fun actionLabels(): List<String?> =
        compose.onAllNodesWithTag(CompanionChoiceActionTag).fetchSemanticsNodes().map {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        }

    private fun assertMenuGrammar(fontScale: Float, actions: Int, heading: Boolean, body: Boolean) {
        val panel = boundsDp(CompanionChoicePanelTag)
        val rows = actionBoundsDp()
        println(
            "V7-RENDER dialog fontScale=$fontScale size=${BottomWidthDp}x${BottomHeightDp}dp panel=${panel[0]},${panel[1]} ${panel[2]}x${panel[3]} " +
                "actions=${rows.map { "${it[3]}dp" }}",
        )
        assertEquals("the menu's actions", actions, rows.size)
        rows.forEachIndexed { index, row ->
            assertTrue("action $index is at least 48 dp tall at font scale $fontScale, was ${row[3]}", row[3] >= 47.9f)
            assertTrue("action $index is at least 48 dp wide, was ${row[2]}", row[2] >= 47.9f)
        }
        assertEquals("a beam above each action", actions, compose.onAllNodesWithTag(CompanionChoiceSeparatorTag).fetchSemanticsNodes().size)
        compose.onNodeWithTag(CompanionChoiceEyebrowTag).assertExists()
        assertEquals("the overlay-title heading is there only when the dialog has one", heading, compose.onAllNodesWithTag(CompanionChoiceHeadingTag).fetchSemanticsNodes().isNotEmpty())
        assertEquals("the paragraph is there only when the dialog has one", body, compose.onAllNodesWithTag(CompanionChoiceBodyTag).fetchSemanticsNodes().isNotEmpty())
        // The panel is a wrap-content column inside the screen: nothing is pushed off it, whatever the font scale.
        assertTrue("the panel stays inside the Bottom: top ${panel[1]} bottom ${panel[1] + panel[3]} of $BottomHeightDp", panel[1] >= 0f && panel[1] + panel[3] <= BottomHeightDp + 0.5f)
        assertEquals("the panel is three quarters of the width", BottomWidthDp * 0.74f, panel[2], 1f)
        // The actions stand in order, one under the other, none overlapping the next.
        rows.zipWithNext { upper, lower ->
            assertTrue("actions do not overlap: ${upper.toList()} ${lower.toList()}", upper[1] + upper[3] <= lower[1] + 0.5f)
        }
        if (heading) {
            val title = layoutOf(CompanionChoiceHeadingTag)
            assertEquals("the heading is the 18 sp overlay title, not the 22 sp title", MimirTypeScale.OverlayTitle.fontSize, title.layoutInput.style.fontSize)
            assertEquals(18f, title.layoutInput.style.fontSize.value, 0.001f)
        }
    }

    private fun checkOpensOn(fontScale: Float) {
        opensOn(fontScale, editing = false)
        assertMenuGrammar(fontScale, actions = 3, heading = true, body = false)
        assertEquals("the heading is the game", "Super Mario World", layoutOf(CompanionChoiceHeadingTag).layoutInput.text.text)
        assertFalse("a short heading is not cut", layoutOf(CompanionChoiceHeadingTag).hasVisualOverflow)
        assertEquals(listOf("Main screen", "Bottom screen", "Cancel"), actionLabels())
    }

    private fun checkOpensOnEditing(fontScale: Float) {
        opensOn(fontScale, editing = true)
        assertMenuGrammar(fontScale, actions = 4, heading = true, body = false)
        assertEquals(listOf("Main screen", "Bottom screen", "Ask next time", "Cancel"), actionLabels())
    }

    private fun checkPauseGame(fontScale: Float) {
        pauseGame(fontScale)
        assertMenuGrammar(fontScale, actions = 2, heading = false, body = true)
        val paragraph = layoutOf(CompanionChoiceBodyTag)
        val text = paragraph.layoutInput.text.text
        println(
            "V7-RENDER pause fontScale=$fontScale chars=${text.length} lines=${paragraph.lineCount} ellipsized=${(0 until paragraph.lineCount).any { paragraph.isLineEllipsized(it) }} " +
                "visualOverflow=${paragraph.hasVisualOverflow} overflowH=${paragraph.didOverflowHeight}",
        )
        assertEquals("the paragraph's words", companionPauseGameCopy("Pokemon"), text)
        assertTrue("the T10 case is a long paragraph: ${text.length} characters", text.length in 170..190)
        assertTrue("it says Bottom screen", "opens on the Bottom screen" in text)
        assertFalse("it no longer says lower screen", "lower screen" in text)
        assertTrue("the last sentence is in it, whole", text.endsWith("save first if you can."))
        assertEquals("the paragraph is the Support role, not the title's type", MimirTypeScale.Support.fontSize, paragraph.layoutInput.style.fontSize)
        assertEquals(14f, paragraph.layoutInput.style.fontSize.value, 0.001f)
        assertFalse("no line is ellipsized", (0 until paragraph.lineCount).any { paragraph.isLineEllipsized(it) })
        assertFalse("the paragraph does not overflow visually", paragraph.hasVisualOverflow)
        assertFalse("the paragraph does not overflow its height", paragraph.didOverflowHeight)
        assertTrue("it wraps to several lines, not one, and not more than nine: ${paragraph.lineCount}", paragraph.lineCount in 3..9)
        assertEquals(listOf("Continue", "Cancel"), actionLabels())
    }

    @Test
    fun `the Opens on dialog is the menu grammar at 538 x 468 dp at font scale 1_0`() = checkOpensOn(1.0f)

    @Test
    fun `the Opens on dialog is the menu grammar at 538 x 468 dp at font scale 1_3`() = checkOpensOn(1.3f)

    @Test
    fun `the editing Opens on dialog adds Ask next time and fits at font scale 1_0`() = checkOpensOnEditing(1.0f)

    @Test
    fun `the editing Opens on dialog adds Ask next time and fits at font scale 1_3`() = checkOpensOnEditing(1.3f)

    @Test
    fun `an Opens on heading too long for two lines is cut, and the actions still fit`() {
        opensOn(1.3f, editing = true, label = "A very long game title that runs on and on and on and on and on and on and on and on and on")
        assertMenuGrammar(1.3f, actions = 4, heading = true, body = false)
    }

    @Test
    fun `PAUSE GAME wraps its whole paragraph in the Support role with no ellipsis at font scale 1_0`() = checkPauseGame(1.0f)

    @Test
    fun `PAUSE GAME wraps its whole paragraph in the Support role with no ellipsis at font scale 1_3`() = checkPauseGame(1.3f)

    @Test
    fun `PAUSE GAME names the app, and says This app when it has no name`() {
        assertEquals(
            "Maps opens on the Bottom screen. The game pauses behind Mimir; Return to game brings it back. " +
                "Android may close a paused game after about ten minutes; save first if you can.",
            companionPauseGameCopy("Maps"),
        )
        assertTrue(companionPauseGameCopy(null).startsWith("This app opens on the Bottom screen."))
    }

    @Test
    fun `a tap on an action answers, a tap outside the panel dismisses, and a tap on the panel glass is absorbed`() {
        chosen.clear()
        dismissed = 0
        opensOn(1.0f, editing = true)
        val actions = compose.onAllNodesWithTag(CompanionChoiceActionTag)
        actions[1].performClick()
        assertEquals(listOf(CompanionQuickLaunchDestination.BOTTOM), chosen)
        actions[2].performClick()
        assertEquals(listOf(CompanionQuickLaunchDestination.BOTTOM, CompanionQuickLaunchDestination.ASK_NEXT_TIME), chosen)
        actions[3].performClick()
        assertEquals("Cancel dismisses", 1, dismissed)
        // The panel's own glass, off every row: absorbed.
        val panel = compose.onNodeWithTag(CompanionChoicePanelTag).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(panel.left + 6f, panel.top + 6f)) }
        assertEquals("a tap on the panel's glass never reaches the scrim", 1, dismissed)
        // Outside the panel: the scrim.
        compose.onRoot().performTouchInput { click(Offset(4f, 4f)) }
        assertEquals("a tap on the scrim dismisses", 2, dismissed)
    }

    @Test
    fun `a dialog that is leaving or stale takes no tap`() {
        chosen.clear()
        dismissed = 0
        opensOn(1.0f, editing = false, enabled = false)
        for (node in compose.onAllNodesWithTag(CompanionChoiceActionTag).fetchSemanticsNodes()) {
            assertFalse("a stale action offers no click", node.config.contains(SemanticsActions.OnClick))
        }
        val row = compose.onAllNodesWithTag(CompanionChoiceActionTag).fetchSemanticsNodes()[0].boundsInRoot
        compose.onRoot().performTouchInput { click(row.center) }
        compose.onRoot().performTouchInput { click(Offset(4f, 4f)) }
        assertTrue("nothing answered", chosen.isEmpty())
        assertEquals("nothing dismissed", 0, dismissed)
    }

    @Test
    fun `PAUSE GAME Continue goes forward`() {
        continued = 0
        pauseGame(1.0f)
        compose.onAllNodesWithTag(CompanionChoiceActionTag)[0].performClick()
        assertEquals(1, continued)
    }

    @Test
    fun `the dialog is built from the menu pieces and no clickable`() {
        val source = File(repoRoot(), "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionChoiceDialog.kt").readText()
        val code = source.lineSequence().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue("GlassMenuCapsule with the flare on", "GlassMenuCapsule(focused = action.lit && enabled, identity = action.label, flare = true, armed = armed)" in code)
        assertTrue("PlainMenuSeparator between actions", "PlainMenuSeparator()" in code)
        assertTrue("OverlayTitle heading", "MimirTypeScale.OverlayTitle" in code)
        assertTrue("Support paragraph", "MimirTypeScale.Support" in code)
        assertTrue("MenuAction capsule label", "MimirTypeScale.MenuAction" in code)
        assertFalse("never clickable (a focus target beside the one FocusAuthority)", Regex("""\bclickable\b|focusable\(|focusTarget""").containsMatchIn(code))
        assertFalse("no maxLines on the paragraph's Text", code.substringAfter("if (body != null)").substringBefore("actions.forEach").contains("maxLines"))
        assertFalse("no hex in a feature module", Regex("""Color\(0x""").containsMatchIn(code))
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "core-ui").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val BottomWidthDp = 538f
        const val BottomHeightDp = 468f
    }
}
