package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.text.MeasuredText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W2d: Main's music panel is pixel-identical to what it drew before the Bottom panel reused its pieces.
 *
 * The Bottom's panel shares `MusicPanelSources`, `MusicPanelLevels` and the transport buttons with Main's single-display panel, through parameters whose
 * defaults reproduce Main exactly (art 96 dp, no press response, the pad gate held by the card swap). This test renders Main's own `MusicPanelOverlay` at
 * a phone-sized window with those defaults, in five states that between them draw every part the Bottom touched (the art and its fallback, the switch row, the
 * transport's filled primary and its ring, both sliders with the cap marker and line, the equalizer row, the foot hint, and the stopped card), reads the pixels
 * back under Robolectric's native graphics, and compares them with what these same cases drew at HEAD 11e96973a, before Main's panel was shared. A case that
 * drew nothing fails its own ink check. The clock is paused and each case is read exactly 2 s after it mounted, so one case is one reproducible frame.
 *
 * What is compared is the host-independent part of that frame. The glyphs are the one thing Robolectric's native Skia draws differently per operating
 * system (the first Linux run, 2026-10-13, failed all five cases against the hashes a Windows run recorded, while every shape-only pixel test, the
 * MovedPiecesPixelIdentityTest, matched on both), so the frame is drawn onto a canvas that swallows every text draw ([GlyphlessCanvas]) and the SHA-256 is over
 * every other ARGB pixel of the container: the card, the art, the transport, the sliders, the rings, the scrim. One pixel's difference fails it. What the
 * swallowed draws said is pinned beside it as text: each run's string, size, colour and baseline in its own text layout, which is what a copy change, a
 * restyle or a re-wrap shows as. (A run's x is left out: where a centred line starts follows the font's advances.) Both comparisons are made before either
 * fails, so a change that moves pixels and words reports both.
 *
 * The goldens are the glyph-free frames and the text records of the five cases at the current tree, whose full frames, glyphs included, still hashed to
 * exactly what this test pinned before (printed as `full=` beside each case; a Windows run of 2026-10-13 matched all five): that is how the chain back to
 * HEAD 11e96973a, through the two re-strikes below, is kept without storing a hash that only one operating system can reproduce.
 *
 * The two cases that draw the equalizer row (yours-alone-hint-eq, yours-cap-line-ring-on-play) were re-struck on 2026-10-10: the row's one line changed
 * from "Music on this device only." to "Shapes all sound on this device." when the Equalizer became one switch for all sound, and nothing else in them moved.
 *
 * Pixel values are the JVM's (Skia through Robolectric); the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPanelMainPixelIdentityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF101418)

    private val art: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(40, 90, 150))
        val paint = Paint().apply { color = AndroidColor.rgb(230, 190, 60) }
        canvas.drawCircle(24f, 24f, 14f, paint)
        paint.color = AndroidColor.rgb(180, 50, 80)
        canvas.drawRect(30f, 36f, 58f, 58f, paint)
    }

    private val yours = YourMusicState(title = "Dreams of the Hedgehog", isPlaying = true, canPrevious = true, canNext = true)
    private val pausedYours = YourMusicState(title = "Dreams of the Hedgehog", isPlaying = false, canPrevious = true, canNext = true)
    private fun spotify(artwork: Bitmap?, playing: Boolean = true) = OtherPlayerSnapshot(
        title = "Track Name",
        artist = "An Artist",
        appLabel = "Spotify",
        artwork = artwork,
        isPlaying = playing,
        canPrevious = true,
        canNext = true,
    )

    private fun model(
        control: MusicControlState,
        stopped: Boolean = false,
        cappedByMedia: Boolean = false,
        capPushed: Boolean = false,
        equalizerShown: Boolean = false,
        mediaVolume: Float = 0.7f,
        musicVolume: Float = 0.4f,
    ) = musicPanelModel(
        control = control,
        stopped = stopped,
        musicVolume = musicVolume,
        mediaVolume = mediaVolume,
        mediaAvailable = true,
        mediaFixed = false,
        cappedByMedia = cappedByMedia,
        capPushed = capPushed,
        equalizer = EqualizerSettings(enabled = true, presetId = EqualizerPresetId.FLAT),
        equalizerShown = equalizerShown,
    )

    /**
     * A canvas that draws everything but the glyphs, and writes down what it was asked to write: each run's string, size, colour and baseline (its x is
     * not kept, see the class note). Every text entry point of [Canvas] is here, so no glyph reaches the bitmap.
     */
    private class GlyphlessCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val runs = mutableListOf<String>()

        private fun note(text: CharSequence, y: Float, paint: Paint) {
            runs += "'$text' size=${paint.textSize.toInt()} color=${Integer.toHexString(paint.color)} y=${y.toInt()}"
        }

        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) = note(String(text, index, count), y, paint)
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) = note(text, y, paint)
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) = note(text.substring(start, end), y, paint)
        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) = note(text.subSequence(start, end), y, paint)
        override fun drawTextRun(text: CharArray, index: Int, count: Int, contextIndex: Int, contextCount: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) =
            note(String(text, index, count), y, paint)

        override fun drawTextRun(text: CharSequence, start: Int, end: Int, contextStart: Int, contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) =
            note(text.subSequence(start, end), y, paint)

        override fun drawTextRun(text: MeasuredText, start: Int, end: Int, contextStart: Int, contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) =
            note("<measured $start-$end>", y, paint)
    }

    /** What one case drew: its full frame (glyphs included, for the log), the same frame without glyphs, and what the glyphs said. */
    private class Frame(val fullHash: String, val glyphlessHash: String, val inked: Int, val runs: List<String>)

    private fun hashOf(crop: Bitmap): Pair<String, Int> {
        val map = crop.asImageBitmap().toPixelMap()
        val pixels = map.buffer.copyOf(map.width * map.height)
        val bytes = ByteBuffer.allocate(pixels.size * 4).also { b -> pixels.forEach(b::putInt) }.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // The scrim covers the whole host, so "ink" is what differs from the scrimmed ground at the container's corner, which the card never reaches.
        return digest to pixels.count { it != pixels[0] }
    }

    private fun frameOf(focused: MusicPanelControl?, panelModel: MusicPanelModel): Frame {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(400.dp, 760.dp).background(ground).testTag(Tag), contentAlignment = Alignment.Center) {
                    MainPanel(focused, panelModel)
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val bounds = compose.onNodeWithTag(Tag).fetchSemanticsNode().boundsInWindow
        fun crop(full: Bitmap) = Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        val withGlyphs = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(withGlyphs))
        val withoutGlyphs = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = GlyphlessCanvas(withoutGlyphs)
        decor.draw(canvas)
        val (fullHash, _) = hashOf(crop(withGlyphs))
        val (glyphlessHash, inked) = hashOf(crop(withoutGlyphs))
        return Frame(fullHash, glyphlessHash, inked, canvas.runs.sorted())
    }

    @Composable
    private fun MainPanel(focused: MusicPanelControl?, panelModel: MusicPanelModel) {
        // Exactly the arguments Main's host passes, the new parameters left at their defaults.
        MusicPanelOverlay(
            model = panelModel,
            focused = focused,
            authority = null,
            anchor = null,
            onDismiss = {},
            onPlayPause = { _ -> },
            onPrevious = { _ -> },
            onNext = { _ -> },
            onMediaRequested = {},
            onMusicRequested = {},
            onMusicInteractionEnd = {},
            onRequestFocus = {},
        )
    }

    private fun check(name: String, frame: Frame) {
        println("PIXEL-HASH $name glyphless=${frame.glyphlessHash} full=${frame.fullHash} inked=${frame.inked} runs=${frame.runs.size}")
        frame.runs.forEach { println("TEXT-RUN $name $it") }
        assertTrue("$name drew nothing, so a match would prove nothing", frame.inked > 0)
        assertTrue("$name wrote no text, so its text record would prove nothing", frame.runs.isNotEmpty())
        assertNotEquals("$name: leaving the glyphs out changed no pixel, so they were not drawn on the canvas this test reads", frame.fullHash, frame.glyphlessHash)
        val pin = Golden.getValue(name)
        val broken = buildList {
            if (pin.glyphless != frame.glyphlessHash) {
                add("$name is not pixel-identical, glyphs aside, to what Main's panel drew before the Bottom shared it: expected ${pin.glyphless} but was ${frame.glyphlessHash}")
            }
            if (pin.text != frame.runs) {
                add("$name writes other text than Main's panel did:\n  expected ${pin.text.joinToString("\n    ")}\n  but was  ${frame.runs.joinToString("\n    ")}")
            }
        }
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun yoursAloneWithTheFootHintAndTheEqualizer() {
        val control = MusicControlState(yours = yours, otherAccess = false)
        check("yours-alone-hint-eq", frameOf(null, model(control, equalizerShown = true)))
    }

    @Test
    fun otherPlayersCardWithArtAndTheSwitchRowAndARing() {
        val control = MusicControlState(yours = pausedYours, other = spotify(art), otherAccess = true)
        check("other-art-switch-ring-on-art", frameOf(MusicPanelControl.OTHER_ART, model(control)))
    }

    @Test
    fun otherPlayersCardWithTheFallbackArtAndTheRingOnTheSwitchRow() {
        val control = MusicControlState(yours = pausedYours, other = spotify(null), otherAccess = true)
        check("other-fallback-ring-on-switch", frameOf(MusicPanelControl.SWITCH, model(control)))
    }

    @Test
    fun yoursWithTheCapMarkerTheCapLineAndTheRingOnPlay() {
        val control = MusicControlState(yours = yours, other = spotify(art, playing = false), otherAccess = true)
        check(
            "yours-cap-line-ring-on-play",
            frameOf(MusicPanelControl.YOURS_PLAY_PAUSE, model(control, cappedByMedia = true, capPushed = true, mediaVolume = 0.3f, musicVolume = 0.3f, equalizerShown = true)),
        )
    }

    @Test
    fun theStoppedCardWithTheTransportDisabled() {
        val control = MusicControlState(yours = yours, otherAccess = true)
        check("stopped", frameOf(MusicPanelControl.MEDIA_VOLUME, model(control, stopped = true)))
    }

    /** A case's pin: the SHA-256 of its frame with the glyphs left out, and the text the glyphs wrote (sorted; see the class note). */
    private class Pin(val glyphless: String, val text: List<String>)

    private companion object {
        const val Tag = "music-main-pixel-host"

        /**
         * Recorded 2026-10-13 from the five cases at cb66ce276 on a Windows JVM, whose full frames matched the hashes this test pinned until then. The
         * text records are sorted, and are the cases' own words, sizes (px at xhdpi), colours and baselines in each text layout.
         */
        val Golden: Map<String, Pin> = mapOf(
            "yours-alone-hint-eq" to Pin(
                "e35c316edc9e106fb59758ff09708841c222642db7fa523df3ec70c44b063eaf",
                listOf(
                    "'40%' size=18 color=ffaab6d4 y=18",
                    "'70%' size=18 color=ffaab6d4 y=18",
                    "'DEVICE.' size=22 color=ff7a90c0 y=52",
                    "'EQUALIZER' size=22 color=ffeef2ff y=23",
                    "'FLAT' size=18 color=ffaab6d4 y=18",
                    "'GAME & MEDIA' size=22 color=ffeef2ff y=23",
                    "'MUSIC ON THIS DEVICE' size=22 color=ffd4af69 y=23",
                    "'MUSIC' size=22 color=ffeef2ff y=23",
                    "'PLAYING — Dreams of the Hedgehog' size=26 color=ffe8c57e y=27",
                    "'SEE OTHER PLAYERS HERE — ALLOW IN SETTINGS › MUSIC' size=18 color=ff7a90c0 y=18",
                    "'SHAPES ALL SOUND ON THIS' size=22 color=ff7a90c0 y=23",
                    "'‹' size=22 color=ffe8c57e y=23",
                    "'›' size=22 color=ffe8c57e y=23",
                ),
            ),
            "other-art-switch-ring-on-art" to Pin(
                "15d1c11e77d2d7c5bcbe31dbce95937cb101f3334d89a29a21dada6b2218bfee",
                listOf(
                    "'40%' size=18 color=ffaab6d4 y=18",
                    "'70%' size=18 color=ffaab6d4 y=18",
                    "'An Artist' size=18 color=ffaab6d4 y=18",
                    "'Dreams of the Hedgehog' size=22 color=ff7a90c0 y=23",
                    "'GAME & MEDIA' size=22 color=ffeef2ff y=23",
                    "'MUSIC ON THIS DEVICE' size=22 color=ffeef2ff y=23",
                    "'MUSIC' size=22 color=ffeef2ff y=23",
                    "'NOW PLAYING' size=22 color=ffd4af69 y=23",
                    "'PLAY' size=18 color=ffaab6d4 y=18",
                    "'PLAYING — Track Name' size=26 color=ffe8c57e y=27",
                ),
            ),
            "other-fallback-ring-on-switch" to Pin(
                "1507f044520fefe69f4fb29ae07313ae5664d96e22f2bcf2abacb0dcbe0adaf8",
                listOf(
                    "'40%' size=18 color=ffaab6d4 y=18",
                    "'70%' size=18 color=ffaab6d4 y=18",
                    "'An Artist' size=18 color=ffaab6d4 y=18",
                    "'Dreams of the Hedgehog' size=22 color=ff7a90c0 y=23",
                    "'GAME & MEDIA' size=22 color=ffeef2ff y=23",
                    "'MUSIC ON THIS DEVICE' size=22 color=ffeef2ff y=23",
                    "'MUSIC' size=22 color=ffeef2ff y=23",
                    "'NOW PLAYING' size=22 color=ffd4af69 y=23",
                    "'Name' size=24 color=fffff3c8 y=48",
                    "'PLAY' size=18 color=ffe8c57e y=18",
                    "'PLAYING — Track Name' size=26 color=ffe8c57e y=27",
                    "'Track' size=24 color=fffff3c8 y=22",
                ),
            ),
            "yours-cap-line-ring-on-play" to Pin(
                "c22a7f3707dfd4f3ab576a5e84a55a19cd642cb8348549d8240aa1665c33c452",
                listOf(
                    "'30%' size=18 color=ffaab6d4 y=18",
                    "'30%' size=18 color=ffaab6d4 y=18",
                    "'DEVICE.' size=22 color=ff7a90c0 y=52",
                    "'EQUALIZER' size=22 color=ffeef2ff y=23",
                    "'FLAT' size=18 color=ffaab6d4 y=18",
                    "'GAME & MEDIA' size=22 color=ffeef2ff y=23",
                    "'MUSIC CAN'T BE LOUDER THAN GAME & MEDIA.' size=18 color=ffaab6d4 y=18",
                    "'MUSIC ON THIS DEVICE' size=22 color=ffd4af69 y=23",
                    "'MUSIC' size=22 color=ffeef2ff y=23",
                    "'PLAY' size=18 color=ffaab6d4 y=18",
                    "'PLAYING — Dreams of the Hedgehog' size=26 color=ffe8c57e y=27",
                    "'SHAPES ALL SOUND ON THIS' size=22 color=ff7a90c0 y=23",
                    "'SPOTIFY' size=22 color=ffeef2ff y=23",
                    "'Track Name' size=22 color=ff7a90c0 y=23",
                    "'‹' size=22 color=ffe8c57e y=23",
                    "'›' size=22 color=ffe8c57e y=23",
                ),
            ),
            "stopped" to Pin(
                "79e3f14594e64e9b6808128c4f59b0d3c286af2b12a1ac05316ad35e9d24c581",
                listOf(
                    "'40%' size=18 color=ffaab6d4 y=18",
                    "'70%' size=18 color=ffe8c57e y=18",
                    "'GAME & MEDIA' size=22 color=ffeef2ff y=23",
                    "'MUSIC ON THIS DEVICE' size=22 color=ffd4af69 y=23",
                    "'MUSIC' size=22 color=ffeef2ff y=23",
                    "'STOPPED — Dreams of the Hedgehog' size=26 color=ffaab6d4 y=27",
                ),
            ),
        )
    }
}
