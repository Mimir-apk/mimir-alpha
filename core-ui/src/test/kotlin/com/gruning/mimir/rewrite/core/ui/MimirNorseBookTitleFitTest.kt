package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * **Observed title fitting defect:** at a 1.3x font the Files folio's tome set "Audiobooks" as "Audiobook" / "s" and the stray letter landed on the embossed Mannaz.
 * [MimirNorseBookCover] now takes `fitTitle`: the title comes down from the cover's size to the type scale's floor until no line ends inside a word, in the cover's two
 * lines, and ends above the Mannaz (or the plate's safe band). Driven here at the Files tile's own geometry (the 4-column Main folio, a 117 dp tome at 1 : 1.14) on the
 * Thor's Main density (369 dpi), at the font scales 1.0 and 1.3, with long single-word fixture titles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirNorseBookTitleFitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tomeWidthDp = 117f
    private val tomeAspect = 1f / 1.14f

    private val words = listOf("Audiobooks", "Documents", "Screenshots", "Notifications")

    private class Shown(val layout: TextLayoutResult, val titleBottomDp: Float, val markTopDp: Float, val sizeSp: Float)

    private class Setup(val title: String, val fontScale: Float, val fit: Boolean, val plate: Boolean)

    private var setup by mutableStateOf(Setup("Alarms", 1f, fit = true, plate = false))
    private var mounted = false

    private fun mount(title: String, fontScale: Float, fit: Boolean, plate: Boolean = false): Shown {
        if (!mounted) {
            mounted = true
            compose.setContent {
                val now = setup
                CompositionLocalProvider(LocalDensity provides Density(369f / 160f, now.fontScale)) {
                    MimirTheme {
                        Box(Modifier.width(tomeWidthDp.dp).testTag("book")) {
                            MimirNorseBookCover(
                                focused = false,
                                eyebrow = "Folder",
                                title = now.title,
                                aspectRatio = tomeAspect,
                                fitTitle = now.fit,
                                plate = if (now.plate) ({ Box(Modifier.testTag("plate")) }) else null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        compose.runOnIdle { setup = Setup(title, fontScale, fit, plate) }
        compose.waitForIdle()
        val titleNode = compose.onNodeWithText(title, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        titleNode.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.first()
        val book = compose.onNodeWithTag("book").getBoundsInRoot()
        val bookHeightDp = (book.bottom - book.top).value
        val spineDp = tomeWidthDp * 0.085f
        val safeTopDp = mimirNorseBookPlateSafeTopDp(tomeWidthDp, bookHeightDp, tomeAspect, compact = false)
        val markTop = mimirNorseBookMannazBox(
            bookHeight = bookHeightDp,
            leafWidth = tomeWidthDp - 2 * spineDp,
            widthFraction = MimirNorseBookEmptyMarkFraction,
            maxWidth = MimirNorseBookEmptyMarkMaxDp,
            centerYFraction = (safeTopDp + bookHeightDp * MimirNorseBookPlateSafeBottomFraction) / (2f * bookHeightDp),
        ).top
        val limit = if (plate) safeTopDp else markTop
        val titleBounds = titleNode.getBoundsInRoot()
        return Shown(layout, titleBounds.bottom.value - book.top.value, limit, layout.layoutInput.style.fontSize.value)
    }

    private fun lines(layout: TextLayoutResult) = (0 until layout.lineCount).map {
        layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
    }

    @Test
    fun `the long single words are never split and end above the Mannaz at 1x and 1_3x`() {
        for (scale in listOf(1.0f, 1.3f)) {
            for (word in words) {
                val shown = mount(word, scale, fit = true)
                println("NORSEFIT x$scale '$word' -> ${shown.sizeSp}sp lines=${lines(shown.layout)} bottom=${shown.titleBottomDp}dp markTop=${shown.markTopDp}dp")
                assertEquals("x$scale '$word': no line ends inside a word (lines ${lines(shown.layout)})", -1, mimirBookTitleFirstCutLine(shown.layout))
                assertFalse("x$scale '$word': drops no text without an ellipsis", shown.layout.didOverflowHeight)
                assertTrue(
                    "x$scale '$word': the title ends at ${shown.titleBottomDp} dp, above the Mannaz at ${shown.markTopDp} dp",
                    shown.titleBottomDp <= shown.markTopDp + 0.01f,
                )
                assertTrue("x$scale '$word': never below the type scale's floor as drawn, was ${shown.sizeSp * scale}", shown.sizeSp * scale >= MimirTypeStep.Micro.value - 0.001f)
                // The whole word stands, or the one line ends in the ellipsis: no third answer.
                val ellipsized = (0 until shown.layout.lineCount).any(shown.layout::isLineEllipsized)
                if (!ellipsized) assertEquals("x$scale '$word' stands whole", listOf(word), lines(shown.layout))
            }
        }
    }

    @Test
    fun `a name that already stands whole keeps the cover's own size`() {
        for (scale in listOf(1.0f, 1.3f)) {
            for (name in listOf("Alarms", "DCIM", "Movies")) {
                val fitted = mount(name, scale, fit = true)
                val plain = mount(name, scale, fit = false)
                println("NORSEFIT x$scale '$name' fitted=${fitted.sizeSp}sp plain=${plain.sizeSp}sp")
                assertEquals("x$scale '$name' is not shrunk when it fits", plain.sizeSp, fitted.sizeSp, 0.001f)
                assertEquals(lines(plain.layout), lines(fitted.layout))
            }
        }
    }

    @Test
    fun `the raw cover splits a long word at 1_3x, which is the bug the fit closes`() {
        val split = words.filter { word -> mimirBookTitleFirstCutLine(mount(word, 1.3f, fit = false).layout) >= 0 }
        println("NORSEFIT raw 1.3x split words: $split")
        assertTrue("without fitTitle at least one of $words is cut inside the word at 1.3x", split.isNotEmpty())
    }

    @Test
    fun `a title of several words wraps at its spaces and stays above the Mannaz`() {
        for (scale in listOf(1.0f, 1.3f)) {
            val shown = mount("Super Nintendo ROMs", scale, fit = true)
            println("NORSEFIT x$scale multi -> ${shown.sizeSp}sp lines=${lines(shown.layout)} bottom=${shown.titleBottomDp} markTop=${shown.markTopDp}")
            assertEquals(-1, mimirBookTitleFirstCutLine(shown.layout))
            assertTrue(shown.titleBottomDp <= shown.markTopDp + 0.01f)
        }
    }

    @Test
    fun `a title with a platform plate ends above the plate's safe band`() {
        for (scale in listOf(1.0f, 1.3f)) {
            for (word in words) {
                val shown = mount(word, scale, fit = true, plate = true)
                println("NORSEFIT x$scale plate '$word' -> ${shown.sizeSp}sp lines=${lines(shown.layout)} bottom=${shown.titleBottomDp} safeTop=${shown.markTopDp}")
                assertEquals(-1, mimirBookTitleFirstCutLine(shown.layout))
                // The band's top is where the plate MAY begin: the plate itself is centred in the band, so a title that cannot stand wholly above it (one line at the floor at 1.3x,
                // 0.4 dp over the band's edge) still stands clear of the plate's ink; 1 dp is the most the band's own air allows.
                assertTrue("x$scale '$word' ends above the plate band (${shown.titleBottomDp} vs ${shown.markTopDp})", shown.titleBottomDp <= shown.markTopDp + 1f)
            }
        }
    }

    // ── four folders all read "Cert Artist..." at 1.3x ──────────────────────────────────────────────────────────────────────────────────────────────

    private val folderNames = listOf("_edge", "Album 1", "Cert Artist Alpha", "Cert Artist Beta", "Cert Artist Gamma", "Cert Artist Delta", "nomedia-test", "pc_games")

    private val certNames = listOf("Cert Artist Alpha", "Cert Artist Beta", "Cert Artist Gamma", "Cert Artist Delta")

    /** The fitting order at every font scale: shrink to the floor, then wrap at word boundaries, then (only then) ellipsize. */
    @Test
    fun `several-word folder names are told apart at 1x and 1_3x - shrunk to the floor, then wrapped whole at their spaces, never ellipsized`() {
        for (scale in listOf(1.0f, 1.3f)) {
            val seen = mutableListOf<String>()
            for (name in certNames) {
                val shown = mount(name, scale, fit = true)
                val shownLines = lines(shown.layout)
                println("NORSEFIT x$scale '$name' -> ${shown.sizeSp}sp (${shown.sizeSp * scale} dp) lines=$shownLines bottom=${shown.titleBottomDp} markTop=${shown.markTopDp}")
                assertEquals("x$scale '$name': no line ends inside a word", -1, mimirBookTitleFirstCutLine(shown.layout))
                assertFalse("x$scale '$name': not ellipsized (lines $shownLines)", (0 until shown.layout.lineCount).any(shown.layout::isLineEllipsized))
                assertEquals("x$scale '$name': every word is there", name, shownLines.joinToString(" "))
                assertTrue("x$scale '$name': ends above the Mannaz (${shown.titleBottomDp} vs ${shown.markTopDp})", shown.titleBottomDp <= shown.markTopDp + 0.01f)
                assertTrue("x$scale '$name': never below the floor's rendered size (${shown.sizeSp * scale} dp)", shown.sizeSp * scale >= MimirTypeStep.Micro.value - 0.001f)
                seen += shownLines.joinToString(" ")
            }
            assertEquals("x$scale: the four folders stay distinguishable", certNames, seen)
        }
    }

    @Test
    fun `a hyphenated folder name breaks after its hyphen and stands whole at 1x and 1_3x`() {
        for (scale in listOf(1.0f, 1.3f)) {
            val shown = mount("nomedia-test", scale, fit = true)
            val shownLines = lines(shown.layout)
            println("NORSEFIT x$scale 'nomedia-test' -> ${shown.sizeSp}sp lines=$shownLines")
            assertFalse("x$scale: not ellipsized (lines $shownLines)", (0 until shown.layout.lineCount).any(shown.layout::isLineEllipsized))
            assertEquals("x$scale: every letter is there", "nomedia-test", shownLines.joinToString(""))
            assertEquals(-1, mimirBookTitleFirstCutLine(shown.layout))
        }
    }

    @Test
    fun `a single word too wide even at the floor still ends in an ellipsis and is never split (the open R5 decision is untouched)`() {
        for (scale in listOf(1.0f, 1.3f)) {
            val shown = mount("Earthboundsandmore", scale, fit = true)
            println("NORSEFIT x$scale long single word -> ${shown.sizeSp}sp lines=${lines(shown.layout)}")
            assertEquals(-1, mimirBookTitleFirstCutLine(shown.layout))
            assertTrue("x$scale: ellipsized, not split", (0 until shown.layout.lineCount).any(shown.layout::isLineEllipsized))
        }
    }

    /** The folio's eight covers as the Files grid draws them (117 dp tiles), saved as a picture at each font scale (one composition per test). */
    private fun pictured(scale: Float) {
        val shot = sheetBitmap(scale)
        val dir = File("build/test-captures/sweep28b").apply { if (!isDirectory) mkdirs() }
        val out = File(dir, "f6-folder-covers-$scale.png")
        out.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("NORSEFIT wrote ${out.absolutePath} ${shot.width}x${shot.height}")
        assertTrue(out.isFile && out.length() > 1_000L)
    }

    @Test
    fun `the folio's folder covers at 1x are pictured`() = pictured(1.0f)

    @Test
    fun `the folio's folder covers at 1_3x are pictured`() = pictured(1.3f)

    private fun sheetBitmap(fontScale: Float): Bitmap {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale)) {
                MimirTheme {
                    // The Files grid's four columns, two rows of them.
                    Column(Modifier.testTag("sheet").background(LocalMimirPalette.current.surfaceBase).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        folderNames.chunked(4).forEach { rowNames ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowNames.forEach { name ->
                                    Box(Modifier.width(tomeWidthDp.dp)) {
                                        MimirNorseBookCover(
                                            focused = false,
                                            eyebrow = "Folder",
                                            title = name,
                                            aspectRatio = tomeAspect,
                                            fitTitle = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // The host's pixels, drawn by the view hierarchy itself (captureToImage waits on a window draw that never comes under Robolectric's software canvas).
        val view = compose.activity.window.decorView
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(full))
        val bounds = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot
        return Bitmap.createBitmap(
            full,
            bounds.left.toInt().coerceAtLeast(0),
            bounds.top.toInt().coerceAtLeast(0),
            bounds.width.toInt().coerceAtMost(full.width - bounds.left.toInt()),
            bounds.height.toInt().coerceAtMost(full.height - bounds.top.toInt()),
        )
    }
}
