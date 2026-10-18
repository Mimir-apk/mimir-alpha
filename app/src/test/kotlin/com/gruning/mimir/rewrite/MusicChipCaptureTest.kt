package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.gruning.mimir.rewrite.feature.settings.DetailScope
import com.gruning.mimir.rewrite.feature.settings.MusicPlayerChipTag
import com.gruning.mimir.rewrite.feature.settings.MusicPlayerEnabledRowId
import com.gruning.mimir.rewrite.feature.settings.MusicReadModel
import com.gruning.mimir.rewrite.feature.settings.detailKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Music player card's chip in its three states (policy 2026-10-10, "Your choice plays"): Playing while Mimir's music sounds, Paused while it is on
 * but held or silent, Off while the switch is off. Drawn the way the music page is (Main frame, 1.0 and 1.3 type scale), and each state ASSERTED on the laid-out
 * line itself: the chip node (read by its tag, not by its text, because "Off" is also the word of other rows on the page) carries its word and neither of the
 * other two, and the word took one line and was not ellipsised. The fit check reads EVERY laid-out line with that word, the chip's and any other row's: an "Off" that
 * overflowed anywhere on the page would be a real finding too. The PNGs also land in `build/test-captures/keptgame/`
 * for a look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicChipCaptureTest : MusicCaptureSupport() {

    private val chipWords = listOf("Playing", "Paused", "Off")

    private fun chip(scale: Float, state: String, word: String, music: MusicReadModel) {
        val heard = mutableListOf<Pair<String, TextLayoutResult>>()
        textObserver = { spoken, layout -> heard += spoken to layout }
        mount(MainWidthDp, MainHeightDp, scale, music, detailKey(MusicPlayerEnabledRowId), DetailScope, shell = true)
        val name = "keptgame-chip-$state-${scaleName(scale)}.png"
        save(name)
        val mine = heard.filter { (spoken, _) -> spoken.equals(word, ignoreCase = true) }
        assertTrue("the chip says $word (heard: ${heard.map { it.first }.distinct().take(40)})", mine.isNotEmpty())
        // The chip itself says this state's word and not another's. It is read by its tag: the page has other "Off" texts, and the chip's word is spoken text.
        val chipNode = compose.onNodeWithTag(MusicPlayerChipTag, useUnmergedTree = true)
        chipNode.assertExists()
        fun says(chipWord: String) = hasAnyDescendant(hasContentDescription(chipWord, ignoreCase = true) or hasText(chipWord, ignoreCase = true))
        chipNode.assert(says(word))
        for (other in chipWords.filter { !it.equals(word, ignoreCase = true) }) {
            chipNode.assert(!says(other))
        }
        // It fits: one line, not cut.
        mine.forEach { (_, layout) ->
            assertEquals("$word is one line at ${scaleName(scale)}", 1, layout.lineCount)
            assertFalse("$word is not ellipsised at ${scaleName(scale)}", layout.hasVisualOverflow)
        }
        val shared = File("build/test-captures/keptgame").apply { mkdirs() }
        File("build/music273", name).copyTo(File(shared, name), overwrite = true)
    }

    private fun playing() = playingModel().copy(playing = true)
    private fun paused() = playingModel().copy(playing = false)
    private fun off() = playingModel(musicEnabled = false)

    @Test fun playingAt1x() = chip(1.0f, "playing", "Playing", playing())
    @Test fun playingAt13x() = chip(1.3f, "playing", "Playing", playing())
    @Test fun pausedAt1x() = chip(1.0f, "paused", "Paused", paused())
    @Test fun pausedAt13x() = chip(1.3f, "paused", "Paused", paused())
    @Test fun offAt1x() = chip(1.0f, "off", "Off", off())
    @Test fun offAt13x() = chip(1.3f, "off", "Off", off())
}
