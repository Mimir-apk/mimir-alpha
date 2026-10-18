package com.gruning.mimir.rewrite.feature.files

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
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
 * Sweep 10-09 (F6, font 1.3): the music folder picker's title read "ᛗ · CHOOSE A MUSIC FO..." because the header held it to a fixed 280 dp on one line. The fitting order is shrink to the
 * floor, then wrap at word boundaries, then ellipsize ([fileBrowserTitleFit]); this pins the order as a table and draws the real [FileBrowserHeader] at the Thor's Main geometry
 * (832.8 x 468.3 dp at 369 dpi) at font 1.0 and 1.3. The PNGs land in `build/test-captures/fix29b/`; the pixels are the JVM's, a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserHeaderTitleTest {
    private val english = filesEnglish()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ── the fitting order, as a table ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a title that fits at the ceiling is left at the ceiling`() {
        assertEquals(FileBrowserTitleFit(18f, 1), fileBrowserTitleFit(18f, 14f) { _, _ -> true })
    }

    @Test
    fun `shrink comes first - the largest size that stands on one line, never below the floor`() {
        val tried = mutableListOf<Pair<Float, Int>>()
        val fit = fileBrowserTitleFit(23.4f, 18f) { size, lines ->
            tried += size to lines
            lines == 1 && size <= 20f
        }
        assertEquals("one line, as large as the room allows", 1, fit.maxLines)
        assertTrue("the first size that fits is the largest one that does (${fit.sizeSp})", fit.sizeSp in 19.5f..20f)
        assertTrue("every size tried is on one line and at or above the floor", tried.all { it.second == 1 && it.first >= 18f })
        assertFalse("wrapping was not tried while shrinking could still work", tried.any { it.second == 2 })
    }

    @Test
    fun `wrap comes second - at the floor, onto two lines, once no size stands on one`() {
        val fit = fileBrowserTitleFit(23.4f, 18f) { _, lines -> lines == 2 }
        assertEquals(FileBrowserTitleFit(18f, 2), fit)
    }

    @Test
    fun `ellipsize comes last - one line at the floor when nothing else fits`() {
        assertEquals(FileBrowserTitleFit(18f, 1), fileBrowserTitleFit(23.4f, 18f) { _, _ -> false })
    }

    @Test
    fun `at a font scale of 1 the floor is the ceiling, so the title is tried once on one line, then wrapped`() {
        val tried = mutableListOf<Pair<Float, Int>>()
        fileBrowserTitleFit(18f, 18f) { size, lines ->
            tried += size to lines
            false
        }
        assertEquals(listOf(18f to 1, 18f to 2), tried)
    }

    // ── the real header ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun mount(fontScale: Float, title: String) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(832.8.dp, 468.3.dp).background(palette.surfaceBase), contentAlignment = Alignment.TopStart) {
                        FileBrowserHeader(
                            title = title,
                            rootPath = "/storage/emulated/0",
                            currentPath = "/storage/emulated/0/Music",
                            crumbLabels = mapOf("/storage/emulated/0" to "Internal Storage"),
                            rowCount = 2,
                            onCrumb = {},
                            searchAvailable = true,
                            searchActive = false,
                            onSearch = {},
                            queueAvailable = false,
                            queueCount = 0,
                            queueActive = false,
                            onQueue = {},
                            palette = palette,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun save(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        // The header and a little of the page under it: the picture is the strip, not the empty frame.
        val strip = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, (bitmap.height * 0.2f).toInt())
        val dir = File("build/test-captures/fix29b").apply { if (!isDirectory) mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("HEADERTITLE wrote ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the title '$text' is drawn", nodes.isNotEmpty())
        val layouts = mutableListOf<TextLayoutResult>()
        nodes[0].config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.first()
    }

    private fun lines(layout: TextLayoutResult) = (0 until layout.lineCount).map {
        layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
    }

    private fun assertWhole(fontScale: Float, title: String, fileName: String) {
        mount(fontScale, title)
        val layout = layoutOf(title)
        val shown = lines(layout)
        val sizeDp = layout.layoutInput.style.fontSize.value * fontScale
        println("HEADERTITLE x$fontScale '$title' -> ${layout.layoutInput.style.fontSize.value}sp ($sizeDp dp) lines=$shown")
        assertFalse("x$fontScale: not ellipsized ($shown)", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals("x$fontScale: one line", 1, layout.lineCount)
        assertEquals("x$fontScale: every word is there", title, shown.joinToString(" "))
        assertTrue("x$fontScale: never below the Heading as drawn at 1x ($sizeDp dp)", sizeDp >= MimirTypeStep.Heading.value - 0.01f)
        val bounds = compose.onAllNodesWithText(title, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertTrue("x$fontScale: inside the 280 dp the header gives it (${bounds.width.value})", bounds.width.value <= 280.5f)
        save(fileName)
    }

    @Test
    fun `the music folder picker's title reads whole at 1x`() =
        assertWhole(1.0f, fileBrowserHeaderTitle(english, FileBrowserMode.PICK_MUSIC_FOLDER), "h3-picker-title-1.0.png")

    @Test
    fun `the music folder picker's title reads whole at 1_3x - shrunk, not cut`() =
        assertWhole(1.3f, fileBrowserHeaderTitle(english, FileBrowserMode.PICK_MUSIC_FOLDER), "h3-picker-title-1.3.png")

    /** Too long for one line at any size: it wraps at its words onto two lines at the floor, whole, inside the 52 dp header (fitting order, step two). */
    private fun assertWraps(fontScale: Float, fileName: String) {
        val title = "ᛗ · CHOOSE A MUSIC FOLDER FOR THE LIBRARY"
        mount(fontScale, title)
        val layout = layoutOf(title)
        val shown = lines(layout)
        val sizeDp = layout.layoutInput.style.fontSize.value * fontScale
        println("HEADERTITLE wrapped x$fontScale '$title' -> ${layout.layoutInput.style.fontSize.value}sp ($sizeDp dp) lines=$shown height ${layout.size.height} px")
        assertEquals("x$fontScale: two lines", 2, layout.lineCount)
        assertFalse("x$fontScale: not ellipsized ($shown)", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertTrue("x$fontScale: no line ends inside a word ($shown)", fileBrowserTitleBreaksOnlyBetweenWords(title, layout))
        assertEquals("x$fontScale: every word is there", title, shown.joinToString(" "))
        assertTrue("x$fontScale: the floor is the Heading as drawn at 1x ($sizeDp dp)", Math.abs(sizeDp - MimirTypeStep.Heading.value) < 0.05f)
        assertTrue("x$fontScale: the two lines stand inside the 52 dp header (${layout.size.height} px)", layout.size.height <= 52 * 369f / 160f + 0.5f)
        save(fileName)
    }

    @Test
    fun `a title too long to shrink onto one line wraps at its words and no word is cut, at 1x`() = assertWraps(1.0f, "h3-picker-title-wrapped-1.0.png")

    @Test
    fun `a title too long to shrink onto one line wraps at its words and no word is cut, at 1_3x`() = assertWraps(1.3f, "h3-picker-title-wrapped-1.3.png")

    @Test
    fun `a single word too wide even at the floor is ellipsized and never split, at 1_3x`() {
        val title = "ᛗ · CHOOSEAMUSICFOLDERFORTHEWHOLELIBRARYPLEASE"
        mount(1.3f, title)
        val layout = layoutOf(title)
        println("HEADERTITLE long word -> lines=${lines(layout)}")
        assertTrue("ellipsized, not split", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertTrue("no line ends inside a word", fileBrowserTitleBreaksOnlyBetweenWords(title, layout))
        save("h3-picker-title-longword-1.3.png")
    }
}
