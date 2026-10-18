package com.gruning.mimir.rewrite.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
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
 * Combined33 sweep, two P3s: text is never cut inside a word.
 *  - Settings > Library: the receipt row's label "Additional Library folders" ended "ADDITIONAL LIBRARY F…" at font 1.3 (one line, a fixed 200 dp column). It wraps at its spaces now, and a
 *    label whose longest word is wider than the column stacks over its value instead.
 *  - Settings > Controls: the Trackpad row's description ended "…your pa…" at font 1.0 (two lines). A supporting line wraps to a third line, and one that is still too long ends at a WORD.
 *
 * The real [ReceiptRow] and [SettingsRow] at the Settings pane's width on Main (a 585 dp row) and at the Nova's (437.5 dp), at font 1.0, 1.3 and 2.0, read back through the layouts Compose
 * drew. Frames are written to `build/test-captures/sweepfix32/round3/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsWordWrapRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2.306f
    private val layouts = mutableMapOf<String, TextLayoutResult>()

    private fun mount(widthDp: Float, fontScale: Float) {
        layouts.clear()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalMimirTextLayoutObserver provides { spoken, layout -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize((widthDp + 40f).dp, 420.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        Column(Modifier.testTag("pane").padding(20.dp).width(widthDp.dp), verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                            ReceiptRow(label = "Additional Library folders", value = "1 connected")
                            ReceiptRow(label = "Folder detection", value = "4 games · 2 platforms")
                            SettingsRow(
                                key = FocusKey("test/trackpad"),
                                scope = FocusScope("test"),
                                authority = DefaultFocusAuthority(),
                                title = "Trackpad access",
                                supporting = TrackpadAccessOffSupporting,
                                trailing = { RowAction("Turn on") },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layoutOf(startsWith: String): TextLayoutResult {
        val nodes = compose.onAllNodes(hasText(startsWith, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes()
        val node = nodes.firstOrNull { it.config[SemanticsProperties.Text].joinToString("") { text -> text.text }.startsWith(startsWith, ignoreCase = true) }
            ?: error("no text node starts with <$startsWith>; the ones that contain it: ${nodes.map { it.config[SemanticsProperties.Text].joinToString("") { text -> text.text } }}")
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("$startsWith exposes its layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun shown(startsWith: String): String =
        compose.onAllNodes(hasText(startsWith, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config[SemanticsProperties.Text].joinToString("") { text -> text.text }.startsWith(startsWith, ignoreCase = true) }
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    /** No line of [layout] ends inside a word: every soft wrap stands at whitespace. */
    private fun assertNoWordBreak(layout: TextLayoutResult, label: String) {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (end in 1 until text.length) {
                assertTrue("$label: line ${line + 1} ends inside a word: <${text.substring(0, end)}|${text.substring(end)}>", text[end - 1].isWhitespace() || text[end].isWhitespace())
            }
        }
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pane = compose.onNodeWithTag("pane").fetchSemanticsNode().boundsInWindow
        val pad = 20f * density
        val crop = Bitmap.createBitmap(
            bitmap,
            (pane.left - pad).toInt().coerceAtLeast(0),
            (pane.top - pad).toInt().coerceAtLeast(0),
            (pane.width + 2 * pad).toInt().coerceAtMost(bitmap.width - (pane.left - pad).toInt().coerceAtLeast(0)),
            (pane.height + 2 * pad).toInt().coerceAtMost(bitmap.height - (pane.top - pad).toInt().coerceAtLeast(0)),
        )
        val out = File(File("build/test-captures/sweepfix32/round3").apply { mkdirs() }, name)
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 1_000L)
    }

    private fun check(widthDp: Float, fontScale: Float, name: String, expectWholeTrackpad: Boolean) {
        mount(widthDp, fontScale)
        val label = "${widthDp.toInt()} dp pane, font $fontScale"
        val receipt = layouts["Additional Library folders"] ?: error("the receipt label drew no layout; drew: ${layouts.keys}")
        assertNoWordBreak(receipt, "$label: the receipt label")
        for (line in 0 until receipt.lineCount) assertFalse("$label: the receipt label's line ${line + 1} is ellipsized", receipt.isLineEllipsized(line))
        assertEquals("$label: every word of the receipt label is there", "ADDITIONAL LIBRARY FOLDERS", receipt.layoutInput.text.text.uppercase().trim())
        val support = shown("Turn on Mimir's Accessibility service")
        println("SETTINGSWRAP $label: receipt label on ${receipt.lineCount} line(s); trackpad text <${support.takeLast(40)}>")
        assertTrue("$label: the trackpad line is whole or ends at a word: <$support>", support == TrackpadAccessOffSupporting || (support.endsWith("…") && TrackpadAccessOffSupporting.startsWith(support.removeSuffix("…")) && TrackpadAccessOffSupporting[support.removeSuffix("…").length].isWhitespace()))
        if (expectWholeTrackpad) assertEquals("$label: the whole trackpad line stands", TrackpadAccessOffSupporting, support)
        assertNoWordBreak(layoutOf("Turn on Mimir's Accessibility service"), "$label: the trackpad line")
        save("settings-wrap-$name.png")
    }

    @Test fun `Main's pane at font 1_0 shows the receipt label on its lines and the trackpad line whole`() = check(585f, 1.0f, "main-1.0", expectWholeTrackpad = true)

    @Test fun `Main's pane at font 1_3 wraps the receipt label at its spaces`() = check(585f, 1.3f, "main-1.3", expectWholeTrackpad = false)

    @Test fun `Main's pane at font 2_0 stacks a label whose word is wider than its column, and cuts the line at a word`() = check(585f, 2.0f, "main-2.0", expectWholeTrackpad = false)

    @Test fun `the Nova's pane at font 1_3 stacks the receipt rows`() = check(437.5f, 1.3f, "nova-1.3", expectWholeTrackpad = false)
}
