package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.companion.CompanionPaneLiveScope
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fix 29 G2 (sweep vqa8, Thor, 1.0 and 1.3): the Activity page's PLAYING tile, for a track with no embedded picture, drew its coverless book with the title "Untit" / "led": a word cut
 * where the leaf ran out, with no hyphen and no ellipsis (a coverless title breaks at a syllable hyphen, else it is hyphenated and cut to the room ending in an
 * ellipsis, and a forced cut is never allowed). And the book read "Untitled" while the tile named the track "Two Hours Of Silence".
 *
 * Two causes. The art seat of the music panel ([MusicPanelArtwork]) drew the book without the fit ([com.gruning.mimir.rewrite.core.ui.MediaArtBookCover]'s `fitTitle`, off by default), so the
 * book kept its fixed size and its five lines and the engine cut the word. And Your music's card has no `artworkTitle` (only another player's does), so the book had an empty title and its own
 * empty-title word, "Untitled". The Bottom's tile and player now set the book with the fit and name it by the track the card names; Main's panel sets it with the fit too since round 2 of the 10-11 sweep fix ([UnfittedBookSitesRenderTest]).
 *
 * The tile is drawn at the Presentation's 369 dpi at the Activity page's tile width (256 dp), at font scales 1.0 and 1.3; the Bottom's full player too. The PNGs land in `build/test-captures/fix29a/`. The
 * host ships no hyphenation patterns, so a syllable break ("Si-" / "lence") is the handheld's; what is held here is that no line ends inside a word without a hyphen or the ellipsis.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.BottomQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityMediaTileBookTitleRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeEngine(initial: MusicControlState) : MusicPanelEngine {
        override val state = MutableStateFlow(initial)
        override val musicVolume = MutableStateFlow(0.4f)
        override val mediaVolume = MutableStateFlow(0.7f)
        override val cappedByMedia = MutableStateFlow(false)
        override val equalizer = MutableStateFlow(EqualizerSettings(enabled = true))

        override fun playPause(source: MusicSource) = Unit
        override fun next(source: MusicSource) = Unit
        override fun previous(source: MusicSource) = Unit
        override fun setMusicVolume(fraction: Float) = Unit
        override fun setMediaVolume(fraction: Float) = Unit
        override fun applyEqualizerPreset(id: EqualizerPresetId) = Unit
        override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean = false
    }

    private class FakeMedia : MediaVolumeGateway {
        override fun isAvailable() = true
        override fun isFixedVolume() = false
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float) = false
        override fun step(direction: Int): Float? = null
    }

    /** A library whose track has no embedded picture: the book is what the seat draws. */
    private object NoArt : YourMusicArtSource {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun hint(trackId: String) = EmbeddedArtHint.NO
        override fun cached(trackId: String, px: Int): Bitmap? = null
        override fun knownMissing(trackId: String, px: Int) = true
        override suspend fun load(context: Context, trackId: String, px: Int): Bitmap? = null
    }

    private fun stateFor(title: String?) = MusicControlState(
        yours = YourMusicState(
            title = title,
            isPlaying = true,
            canPrevious = true,
            canNext = true,
            trackId = "track-1",
            format = TrackFormat(codec = "MP3", lossless = false, bitrateKbps = 8, sampleRateHz = 8_000),
        ),
        otherAccess = true,
    )

    private fun draw(name: String, fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalBottomUiDisplay provides 4,
                LocalYourMusicArtSource provides NoArt,
            ) {
                MimirTheme { content() }
            }
        }
        Snapshot.sendApplyNotifications()
        repeat(4) {
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
        }
        val image = MusicRenderSupport.window(compose.activity.window.decorView)
        MusicRenderSupport.assertDrew(image, name)
        val file = File(File("build/test-captures/fix29a").apply { mkdirs() }, name)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("FIX29A wrote ${file.absolutePath} (${image.width}x${image.height} px)")
        assertTrue("the PNG was written: ${file.path}", file.isFile && file.length() > 1_000L)
    }

    private fun tile(name: String, title: String?, fontScale: Float) {
        val state = stateFor(title)
        val engine = FakeEngine(state)
        draw(name, fontScale) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                ActivityEngineMediaTile(
                    card = requireNotNull(musicTileCard(state)),
                    engine = engine,
                    navigationEnabled = true,
                    onOpenPanel = {},
                    modifier = Modifier.width(256.dp),
                )
            }
        }
    }

    private fun player(name: String, title: String?, fontScale: Float) {
        val engine = FakeEngine(stateFor(title))
        draw(name, fontScale) {
            Box(Modifier.fillMaxSize()) {
                CompanionPaneLiveScope(live = true) {
                    CompanionMusicPanelScene(engine = engine, mediaVolume = FakeMedia(), navigationEnabled = true, onBack = {})
                }
            }
        }
    }

    private fun bookLayout(): TextLayoutResult {
        val nodes = compose.onAllNodesWithTag(MediaArtBookTitleTag, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("exactly one coverless book is on the screen", 1, nodes.size)
        val results = mutableListOf<TextLayoutResult>()
        nodes.single().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun bookText(): String =
        compose.onAllNodesWithTag(MediaArtBookTitleTag, useUnmergedTree = true).fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString("") { it.text }

    /** The lines of the drawn title, with the soft hyphens shown, and the ellipsis where the last line ends in one. */
    private fun lines(layout: TextLayoutResult): List<String> = (0 until layout.lineCount).map { line ->
        val text = layout.layoutInput.text.text.substring(layout.getLineStart(line), layout.getLineEnd(line)).replace(MimirSoftHyphenForTest.toString(), "<SHY>")
        if (layout.isLineEllipsized(line)) "$text<ELLIPSIS>" else text
    }

    /** No line but the last ends inside a word: a line ends at a space, a hyphen, or right after a soft hyphen (which the engine draws as a hyphen). */
    private fun assertNoForcedCut(layout: TextLayoutResult, what: String) {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            val before = text[end - 1]
            val after = text.getOrNull(end)
            val forced = before != MimirSoftHyphenForTest && before.isLetterOrDigit() && after != null && after.isLetterOrDigit()
            assertTrue("$what: line $line ends inside a word with no hyphen: ${lines(layout)}", !forced)
        }
    }

    private fun check(title: String, tag: String, fontScale: Float) {
        tile("activity-tile-playing-$tag.png", title, fontScale)
        val layout = bookLayout()
        println("FIX29A tile $tag book lines=${lines(layout)} size=${layout.layoutInput.style.fontSize} maxLines=${layout.layoutInput.maxLines}")
        assertNoForcedCut(layout, "the Activity tile's book at font $fontScale")
        assertEquals("the book says the track's own name, as the tile does", title, bookText())
        compose.onNodeWithText("Untitled", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(title, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the tile's coverless book is set to the book and names the track, at font 1_0`() = check("Two Hours Of Silence", "f10", 1.0f)

    @Test
    fun `the tile's coverless book is set to the book and names the track, at font 1_3`() = check("Two Hours Of Silence", "f13", 1.3f)

    @Test
    fun `a title that is one long word is never cut inside the word without a hyphen or an ellipsis`() {
        tile("activity-tile-longword-f13.png", "Supercalifragilisticexpialidocious", 1.3f)
        val layout = bookLayout()
        println("FIX29A longword book lines=${lines(layout)} size=${layout.layoutInput.style.fontSize}")
        assertNoForcedCut(layout, "a one-word title at font 1.3")
        assertEquals("Supercalifragilisticexpialidocious", bookText())
    }

    @Test
    fun `the Bottom's own player draws its coverless book the same way`() {
        player("bottom-player-noart-f10.png", "Two Hours Of Silence", 1.0f)
        val layout = bookLayout()
        println("FIX29A player book lines=${lines(layout)} size=${layout.layoutInput.style.fontSize}")
        assertNoForcedCut(layout, "the Bottom player's book")
        assertEquals("Two Hours Of Silence", bookText())
    }
}

private const val MimirSoftHyphenForTest = '\u00AD'
