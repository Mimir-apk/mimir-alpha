package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * [MimirWordEllipsisText]: a line of text never ends inside a word. One line is one line; more wrap at their spaces; what still does not fit ends at a WORD with an ellipsis, and the whole
 * text stays the node's spoken name. The real composable at a 160 dp room (Settings' "your pa…" was Compose's own per-letter ellipsis at the end of line two).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirWordEllipsisTextTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sentence = "It does not read your screen, your typing or your passwords."
    private val style = TextStyle(fontSize = 14.sp)

    private fun mount(text: String, maxLines: Int, roomDp: Float = 160f, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                Box(Modifier.width(roomDp.dp)) {
                    MimirWordEllipsisText(text = text, style = style, color = Color.White, maxLines = maxLines)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(): String =
        compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config.contains(SemanticsProperties.Text) }.config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun layout(): TextLayoutResult {
        val node = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first { it.config.contains(SemanticsProperties.Text) }
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun assertNoWordBreak(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (end in 1 until text.length) assertTrue("line ${line + 1} ends inside a word: <${text.substring(0, end)}|${text.substring(end)}>", text[end - 1].isWhitespace() || text[end].isWhitespace())
        }
    }

    @Test
    fun `text that fits its lines is drawn whole`() {
        mount(sentence, maxLines = 6)
        assertEquals(sentence, shown())
        assertNoWordBreak(layout())
    }

    @Test
    fun `text that does not fit two lines ends at a word, never inside one`() {
        mount(sentence, maxLines = 2)
        val text = shown()
        assertTrue("shortened with an ellipsis: <$text>", text.endsWith("…"))
        val head = text.removeSuffix("…")
        assertTrue("<$head> is the start of the sentence", sentence.startsWith(head))
        assertTrue("and ends where a word ends: <$text>", sentence[head.length].isWhitespace())
        assertNoWordBreak(layout())
        assertFalse(layout().didOverflowHeight)
    }

    @Test
    fun `one line is one line`() {
        mount("Favorites · 2 games and a good deal more besides", maxLines = 1, roomDp = 150f)
        val text = shown()
        assertTrue(text.endsWith("…"))
        assertEquals(1, layout().lineCount)
    }

    @Test
    fun `the whole text stays the spoken name when it is shortened`() {
        mount(sentence, maxLines = 2)
        val node = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first { it.config.contains(SemanticsProperties.Text) }
        assertEquals(sentence, node.config[SemanticsProperties.ContentDescription].single())
    }

    @Test
    fun `a larger font gives a wrap that is still at words`() {
        mount(sentence, maxLines = 3, fontScale = 1.6f)
        assertNoWordBreak(layout())
    }
}
