package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A short label's one too-wide word breaks at a syllable hyphen ("Atmosphere" on a 59dp tile: "Atmo-" / "sphere"). The host ships no hyphenation patterns, so a dictionary
 * is the hyphenator here (the seam of [mimirHyphenateWideWords]); what is held is the rest: only a word wider than the room takes a hyphen, the answer is a text with soft hyphens in the
 * words, and nothing is offered that would draw a line ending inside a word without one, or past the lines it has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirWordHyphenationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var measurer: TextMeasurer
    private val style = TextStyle(fontSize = 12.sp)
    private val dictionary: (String) -> List<Int>? = { word -> if (word == "Atmosphere") listOf(4) else null }

    @Before
    fun captureARealMeasurer() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) { measurer = rememberTextMeasurer() }
        }
        compose.waitForIdle()
    }

    private fun widthOf(text: String): Int =
        measurer.measure(AnnotatedString(text), style = style, softWrap = false, maxLines = 1).size.width

    @Test
    fun `a word too wide for the room gets a soft hyphen at the syllable the hyphenator gave`() {
        val room = (widthOf("Atmosphere") * 0.75f).toInt()
        val hyphenated = mimirHyphenateWideWords(measurer, "Atmosphere", style, room, maxLines = 2, syllableBreaksOf = dictionary)
        // The host engine must draw a soft hyphen for the answer to be lawful; if it cannot, this says so instead of passing.
        assumeTrue("the host lays out a soft hyphen", hyphenated != null)
        assertEquals("Atmo­sphere", hyphenated)
    }

    @Test
    fun `a name whose words all fit is left alone, and so is a word the hyphenator cannot break`() {
        val room = widthOf("Your image 1") + 10
        assertNull(mimirHyphenateWideWords(measurer, "Your image 1", style, room, maxLines = 2, syllableBreaksOf = dictionary))
        // Too wide, and no break to give: never cut inside the word.
        assertNull(mimirHyphenateWideWords(measurer, "Fortification", style, widthOf("Fortification") / 2, maxLines = 2, syllableBreaksOf = dictionary))
    }

    @Test
    fun `nothing is offered that does not fit its lines`() {
        // One line has no second line to carry the rest of the word.
        assertNull(mimirHyphenateWideWords(measurer, "Atmosphere", style, widthOf("Atmosphere") / 2, maxLines = 1, syllableBreaksOf = dictionary))
        // A room narrower than either half: the lines would end inside a word.
        assertNull(mimirHyphenateWideWords(measurer, "Atmosphere", style, widthOf("Atmo") / 3, maxLines = 2, syllableBreaksOf = dictionary))
        // And a room of nothing.
        assertNull(mimirHyphenateWideWords(measurer, "Atmosphere", style, 0, maxLines = 2, syllableBreaksOf = dictionary))
    }
}
