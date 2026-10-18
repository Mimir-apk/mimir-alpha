package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 * **Observed title fitting defect:** the Library list's preview book set its platform name on one line and cut it ("SUPER NINTE..."). [mimirBookEyebrowFit] sets the eyebrow to the
 * book's width: one line when the name stands whole on one, else two or three, wrapped at its spaces with every word whole; the size steps down to a floor only when no wrap holds a
 * word; and a name that still cannot stand ends in an ellipsis on one line. Driven with a real text measurer at the Thor Main's density (369 dpi) and the book the preview draws (116 dp
 * wide, its eyebrow 0.765 of that), at the font scales 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaArtBookEyebrowFitTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var measurer: TextMeasurer
    private lateinit var base: TextStyle
    private var fontScale = 1f

    private var mounted = false

    /** One measurer and one base style, taken once; the density of each measure below is handed to it, so the font scale can change between cases. */
    private fun capture(scale: Float) {
        fontScale = scale
        if (mounted) return
        mounted = true
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, 1f)) {
                MimirTheme {
                    measurer = rememberTextMeasurer()
                    base = LocalTextStyle.current
                }
            }
        }
        compose.waitForIdle()
    }

    private val bookWidthDp = 116f
    private val eyebrowSp = 7f
    private val widthPx get() = with(Density(369f / 160f, fontScale)) { (bookWidthDp * 0.765f).dp.toPx() }.toInt()

    private fun layoutAt(text: String, sizeSp: Float, lines: Int, width: Int = widthPx): TextLayoutResult = measurer.measure(
        text = AnnotatedString(text),
        style = base.merge(TextStyle(fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)),
        overflow = TextOverflow.Ellipsis,
        maxLines = lines,
        constraints = Constraints(maxWidth = width),
        density = Density(369f / 160f, fontScale),
    )

    private fun fit(text: String) = mimirBookEyebrowFit(
        maxSp = eyebrowSp,
        minSp = MimirBookEyebrowFloorSp,
        measure = { size, lines -> layoutAt(text, size, lines) },
    )

    private fun lines(layout: TextLayoutResult) = (0 until layout.lineCount).map {
        layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
    }

    private val names = listOf(
        "SUPER NINTENDO",
        "NINTENDO ENTERTAINMENT SYSTEM",
        "GENESIS / MEGA DRIVE",
        "PC ENGINE / TURBOGRAFX-16",
        "NEO GEO POCKET COLOR",
        "GAME BOY ADVANCE",
        "PLAYSTATION PORTABLE",
    )

    @Test
    fun `Super Nintendo stands whole on one line at the label's own size at 1x`() {
        capture(1.0f)
        val fit = fit("SUPER NINTENDO")
        println("EYEBROWFIT 1.0x 'SUPER NINTENDO' -> ${fit.sizeSp}sp ${fit.lines} line(s) fits=${fit.fits}")
        assertTrue(fit.fits)
        assertEquals(1, fit.lines)
        assertEquals("the label keeps its own size when it fits", eyebrowSp, fit.sizeSp, 0.001f)
    }

    @Test
    fun `every platform name stands whole in the book at 1x and 1_3x, wrapped only at a space`() {
        for (scale in listOf(1.0f, 1.3f)) {
            capture(scale)
            for (name in names) {
                val fit = fit(name)
                val layout = layoutAt(name, fit.sizeSp, fit.lines)
                println("EYEBROWFIT x$scale '$name' -> ${fit.sizeSp}sp ${fit.lines} line(s) fits=${fit.fits} ${lines(layout)}")
                assertTrue("x$scale '$name' is held whole", fit.fits)
                assertFalse("x$scale '$name': no ellipsis", (0 until layout.lineCount).any(layout::isLineEllipsized))
                assertEquals("x$scale '$name': no line ends inside a word", -1, mimirBookTitleFirstCutLine(layout))
                assertFalse("x$scale '$name': no hyphenated word is split across lines (${lines(layout)})", mimirBookEyebrowSplitsCompound(layout))
                assertEquals(name.replace(" ", ""), lines(layout).joinToString("").replace(" ", ""))
                assertTrue("never below the floor: ${fit.sizeSp}", fit.sizeSp >= MimirBookEyebrowFloorSp - 0.001f)
            }
        }
    }

    @Test
    fun `a word wider than the book at the floor ends in an ellipsis on one line, never split`() {
        capture(1.0f)
        val fit = mimirBookEyebrowFit(
            maxSp = eyebrowSp,
            minSp = MimirBookEyebrowFloorSp,
            measure = { size, lines -> layoutAt("SUPERCALIFRAGILISTICEXPIALIDOCIOUS", size, lines) },
        )
        println("EYEBROWFIT unbreakable -> ${fit.sizeSp}sp ${fit.lines} line(s) fits=${fit.fits}")
        assertFalse(fit.fits)
        assertEquals(MimirBookEyebrowFloorSp, fit.sizeSp, 0.001f)
        assertEquals(1, fit.lines)
    }

    @Test
    fun `the detector finds a line that ends on a hyphen joined to its word, and passes a slash set apart by a space`() {
        capture(1.0f)
        // The break is put where each case needs it with a hard line break, so the check does not depend on where this engine chooses to wrap (it does not always break after a
        // hyphen: on "TURBO-GRAFX" it splits the letters instead, which the cut-line check catches).
        val split = layoutAt("TURBO-\nGRAFX", 7f, 3, Constraints.Infinity)
        println("EYEBROWFIT detector lines=${lines(split)}")
        assertEquals(listOf("TURBO-", "GRAFX"), lines(split))
        assertTrue("a line that ends on a hyphen joined to its word is a split compound", mimirBookEyebrowSplitsCompound(split))

        val slashSplit = layoutAt("ENGINE/\nTURBOGRAFX", 7f, 3, Constraints.Infinity)
        assertTrue("a line that ends on a slash joined to its word is a split compound", mimirBookEyebrowSplitsCompound(slashSplit))

        val apart = layoutAt("PC ENGINE /\nTURBOGRAFX", 7f, 3, Constraints.Infinity)
        println("EYEBROWFIT detector slash lines=${lines(apart)}")
        assertEquals(listOf("PC ENGINE /", "TURBOGRAFX"), lines(apart))
        assertFalse("a slash standing apart is a word of its own: ${lines(apart)}", mimirBookEyebrowSplitsCompound(apart))

        val whole = layoutAt("TURBOGRAFX\nPC", 7f, 3, Constraints.Infinity)
        assertFalse("a line that ends on a letter is no split compound", mimirBookEyebrowSplitsCompound(whole))
    }
}
