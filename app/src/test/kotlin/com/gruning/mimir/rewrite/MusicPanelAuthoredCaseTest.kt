package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.MediaSession
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.resolve
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Authored-text case contract: a track's title is authored text and is drawn in its own case on both screens. Main's music panel
 * capped it, against `MimirTextIntent`'s own contract; the Bottom's media tile has always drawn it in plain `Text`. What stays capped is chrome:
 * the state word, the accent eyebrow, the `MenuAction` source name, and any text the bridge synthesised ("Media unavailable") because the session
 * named no title.
 *
 * The render tests draw the panel's own line composables ([MusicPanelStatusLine], [MusicPanelArtistLine], [MusicPanelSwitchTrackLine], the ones the
 * panel calls) and compare their PIXELS with a plain `Text` of the exact string expected, on the same ground: a wrong rendering branch (a capped
 * title, an un-capped fallback) cannot pass. Pixel values are the JVM's (Skia through Robolectric); a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPanelAuthoredCaseTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF101418)
    private var current by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var mounted = false
    private var paletteSeen: com.gruning.mimir.rewrite.core.ui.MimirThemePalette = com.gruning.mimir.rewrite.core.ui.MimirThemePalette.Default

    /** The rendered pixels of [content] on [ground], in a 280 x 28 dp box (Main's card text lane is 280 dp). One `setContent` per rule: cases are swapped in. */
    private fun pixels(content: @Composable () -> Unit): IntArray {
        if (!mounted) {
            mounted = true
            current = content
            compose.setContent {
                MimirTheme {
                    val case = current
                    if (case != null) {
                        key(case) {
                            Box(Modifier.size(280.dp, 28.dp).background(ground).testTag(Tag), contentAlignment = Alignment.Center) { case() }
                        }
                    }
                }
            }
        } else {
            compose.runOnIdle { current = content }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val b = compose.onNodeWithTag(Tag).fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(full, b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
        val map = crop.asImageBitmap().toPixelMap()
        return map.buffer.copyOf(map.width * map.height)
    }

    private fun IntArray.digest(): String =
        MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(size * 4).also { b -> forEach(b::putInt) }.array()).joinToString("") { "%02x".format(it) }

    private fun IntArray.inked() = count { it != ground.toArgb() }

    private fun assertDrawsAs(what: String, drawn: IntArray, expected: IntArray, wrong: IntArray) {
        assertTrue("$what drew nothing, so a match would prove nothing", drawn.inked() > 0)
        assertEquals("$what is not drawn as the expected string", expected.digest(), drawn.digest())
        assertNotEquals("$what: the wrong-case control draws the same pixels, so this test cannot tell the cases apart", expected.digest(), wrong.digest())
    }

    /** The reference: a plain `Text` of exactly the string, in the style and colour the line wears, with the line's own geometry. */
    @Composable
    private fun Ref(text: String, style: TextStyle, color: Color, wide: Boolean) {
        Text(
            text = text,
            modifier = if (wide) Modifier.fillMaxWidth() else Modifier,
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (wide) TextAlign.Center else null,
        )
    }

    private val statusStyle = MimirTypeScale.MenuAction.copy(fontSize = (MimirTypeScale.MenuAction.fontSize.value + 2f).sp)

    private fun model(control: MusicControlState) = musicPanelModel(
        control = control,
        stopped = false,
        musicVolume = 0.4f,
        mediaVolume = 0.7f,
        mediaAvailable = true,
        mediaFixed = false,
        cappedByMedia = false,
        capPushed = false,
    )

    private fun other(title: String, authored: Boolean = true, playing: Boolean = true, artist: String? = "Some Artist") =
        OtherPlayerSnapshot(title, artist, "Spotify", artwork = null, isPlaying = playing, titleIsAuthored = authored)

    private fun yours(title: String?, playing: Boolean) = YourMusicState(title = title, isPlaying = playing, canPrevious = true, canNext = true)

    // ── what is drawn ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the status line draws the state word capped and a real title in its own case, and a synthesised title wholly capped`() {
        val real = model(MusicControlState(yours = yours("Song", false), other = other("Song One"), otherAccess = true)).top
        assertEquals("Playing — Song One", real.statusLine)
        assertTrue(real.titleIsAuthored)
        val fallback = model(MusicControlState(yours = yours("Song", false), other = other("Media unavailable", authored = false), otherAccess = true)).top
        assertEquals("Playing — Media unavailable", fallback.statusLine)
        assertFalse("the bridge's own copy is not an authored title", fallback.titleIsAuthored)

        val realDrawn = pixels { MusicPanelStatusLine(real.statusLine, real.titleIsAuthored, real.playing, LocalMimirPalette.current) }
        val realWanted = pixels { Ref("PLAYING — Song One", statusStyle, LocalMimirPalette.current.accentBright, wide = true) }
        val realWrong = pixels { Ref("PLAYING — SONG ONE", statusStyle, LocalMimirPalette.current.accentBright, wide = true) }
        assertDrawsAs("a real title's status line", realDrawn, realWanted, realWrong)

        val fallbackDrawn = pixels { MusicPanelStatusLine(fallback.statusLine, fallback.titleIsAuthored, fallback.playing, LocalMimirPalette.current) }
        val fallbackWanted = pixels { Ref("PLAYING — MEDIA UNAVAILABLE", statusStyle, LocalMimirPalette.current.accentBright, wide = true) }
        val fallbackWrong = pixels { Ref("PLAYING — Media unavailable", statusStyle, LocalMimirPalette.current.accentBright, wide = true) }
        assertDrawsAs("the fallback's status line", fallbackDrawn, fallbackWanted, fallbackWrong)

        // A title with no title at all is the state word alone, chrome whole, as it was.
        val bare = model(MusicControlState(yours = yours("Song", false), other = other(""), otherAccess = true)).top
        assertEquals("Playing", bare.statusLine)
        assertEquals("PLAYING", musicPanelStatusIntent(bare.statusLine, bare.titleIsAuthored).resolve().visualText)
    }

    @Test
    fun `the artist line draws another player's artist in its own case and Your music's source format capped`() {
        val artistDrawn = pixels { MusicPanelArtistLine("Some Artist", authored = true, palette = LocalMimirPalette.current) }
        val artistWanted = pixels { Ref("Some Artist", MimirTypeScale.ControlHintCompact, LocalMimirPalette.current.textSecondary, wide = true) }
        val artistWrong = pixels { Ref("SOME ARTIST", MimirTypeScale.ControlHintCompact, LocalMimirPalette.current.textSecondary, wide = true) }
        assertDrawsAs("another player's artist", artistDrawn, artistWanted, artistWrong)

        val formatDrawn = pixels { MusicPanelArtistLine("FLAC · 24/96 · 2,304 kbps", authored = false, palette = LocalMimirPalette.current) }
        val formatWanted = pixels { Ref("FLAC · 24/96 · 2,304 KBPS", MimirTypeScale.ControlHintCompact, LocalMimirPalette.current.textSecondary, wide = true) }
        val formatWrong = pixels { Ref("FLAC · 24/96 · 2,304 kbps", MimirTypeScale.ControlHintCompact, LocalMimirPalette.current.textSecondary, wide = true) }
        assertDrawsAs("Your music's source format", formatDrawn, formatWanted, formatWrong)
    }

    @Test
    fun `the switch row draws a real title in its own case, and the state word and a synthesised title capped`() {
        val titled = model(MusicControlState(yours = yours("Song One", false), other = other("Track"), otherAccess = true)).switchRow!!
        assertEquals("Song One", titled.track)
        assertTrue(titled.trackIsTitle)
        val untitled = model(MusicControlState(yours = yours(null, false), other = other("Track"), otherAccess = true)).switchRow!!
        assertEquals("Paused", untitled.track)
        assertFalse("the state word is chrome", untitled.trackIsTitle)
        val synthesised = model(MusicControlState(yours = yours("Song", true), other = other("Media unavailable", authored = false, playing = false), otherAccess = true)).switchRow!!
        assertEquals("Media unavailable", synthesised.track)
        assertFalse("the bridge's own copy is not an authored title", synthesised.trackIsTitle)

        val style = MimirTypeScale.SupportCompact
        val titledDrawn = pixels { MusicPanelSwitchTrackLine(titled, LocalMimirPalette.current) }
        val titledWanted = pixels { Ref("Song One", style, LocalMimirPalette.current.textMuted, wide = false) }
        val titledWrong = pixels { Ref("SONG ONE", style, LocalMimirPalette.current.textMuted, wide = false) }
        assertDrawsAs("a real title in the switch row", titledDrawn, titledWanted, titledWrong)

        val untitledDrawn = pixels { MusicPanelSwitchTrackLine(untitled, LocalMimirPalette.current) }
        val untitledWanted = pixels { Ref("PAUSED", style, LocalMimirPalette.current.textMuted, wide = false) }
        val untitledWrong = pixels { Ref("Paused", style, LocalMimirPalette.current.textMuted, wide = false) }
        assertDrawsAs("the state word in the switch row", untitledDrawn, untitledWanted, untitledWrong)

        val synthDrawn = pixels { MusicPanelSwitchTrackLine(synthesised, LocalMimirPalette.current) }
        val synthWanted = pixels { Ref("MEDIA UNAVAILABLE", style, LocalMimirPalette.current.textMuted, wide = false) }
        val synthWrong = pixels { Ref("Media unavailable", style, LocalMimirPalette.current.textMuted, wide = false) }
        assertDrawsAs("the fallback in the switch row", synthDrawn, synthWanted, synthWrong)
    }

    // ── where the fallback comes from ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun bridgeTrack(titleIsAuthored: Boolean): CompanionMediaTrack {
        val token = MediaSession(ApplicationProvider.getApplicationContext<Application>(), "r7-case").sessionToken
        return CompanionMediaTrack(
            title = if (titleIsAuthored) "Dreams of the Hedgehog" else "Media unavailable",
            artist = null,
            appLabel = "Player",
            packageName = "example.player",
            canPrevious = true,
            canPlayPause = true,
            canNext = true,
            sessionIdentity = token,
            trackIdentity = "t",
            playbackState = CompanionMediaPlaybackState.PLAYING,
            titleIsAuthored = titleIsAuthored,
        )
    }

    @Test
    fun `the bridge's flag reaches the panel, so a session with no title is chrome and one with a title is authored`() {
        assertTrue(otherPlayerSnapshotFor(CompanionMediaUiState.Active(bridgeTrack(true)))!!.titleIsAuthored)
        assertFalse(otherPlayerSnapshotFor(CompanionMediaUiState.Active(bridgeTrack(false)))!!.titleIsAuthored)
        assertTrue("a fixture that says nothing is a real title", bridgeTrack(true).copy().titleIsAuthored)
    }

    // ── the real widths ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Main's music card at its real size (320 dp on a display with room), one long title in each of its three authored lines, at font scale 1.0 and
     * 1.3. The text lane is the real laid-out node's width; the line is measured at that lane with the line's own style, cap and ellipsis, and the
     * node's own height must be one line of it (the card did not let it wrap). The numbers print as `R7-MAIN-WIDTH`.
     *
     * Ledger D28: what proves the card draws an ELLIPSIS line, not a Clip one, is the real line's own `TextLayoutResult` (heard through
     * [LocalMimirTextLayoutObserver], since the node's semantics hide it): its overflow is Ellipsis, it is one line, that line is ellipsised, its
     * visible end is short of the whole text, and the text is in the expected case. The rendered pixels are compared with an Ellipsis and a Clip
     * control as before, and printed, but they no longer decide the test: the two controls differ by only a few ink columns (3 to 6 on the status
     * and artist lines), which anti-aliasing can swallow.
     */
    @Test
    fun `a long authored title keeps one line and its ellipsis in Main's music card at 1 point 0 and 1 point 3`() {
        val long = "The Quick Brown Fox Jumps Over The Lazy Dog And Keeps On Running Far Beyond The Horizon"
        val longArtist = "Some Artist With A Very Long Name Indeed That Goes On And On And On Past Any Lane"
        val longSong = "Another Long Authored Song Title That Will Never Fit In The Switch Row Lane At All"
        val m = model(MusicControlState(yours = yours(longSong, false), other = other(long, artist = longArtist), otherAccess = true))
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val scale = mutableStateOf(1.0f)
        var measure: (String, TextStyle, Int) -> Triple<Boolean, Int, Int> = { _, _, _ -> Triple(false, 0, 0) }
        // The RENDERED proof: a control strip drawn after the panel (and only then), one reference line at a time, so Main's real pixels can be compared with an Ellipsis line
        // and a Clip line of the same text, style and lane. A panel whose overflow became Clip draws the Clip pixels and fails.
        val showRef = mutableStateOf(false)
        val refClip = mutableStateOf(false)
        val refCentered = mutableStateOf(true)
        val refText = mutableStateOf("")
        val refStyle = mutableStateOf(TextStyle.Default)
        val refColor = mutableStateOf(Color.White)
        val refLane = mutableStateOf(100)
        val refHeight = mutableStateOf(20)
        // The real lines' own layouts, by the spoken text they were laid out for, in the order they were laid out (a line may be laid out again by a crossfade).
        val heard = ConcurrentHashMap<String, MutableList<TextLayoutResult>>()
        val layoutObserver: (String, TextLayoutResult) -> Unit = { spoken, layout -> heard.getOrPut(spoken) { CopyOnWriteArrayList() }.add(layout) }
        compose.mainClock.autoAdvance = true
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = scale.value),
                LocalMimirTextLayoutObserver provides layoutObserver,
            ) {
                MimirTheme {
                    paletteSeen = LocalMimirPalette.current
                    val measurer = rememberTextMeasurer()
                    measure = { text, style, lane ->
                        val r = measurer.measure(AnnotatedString(text), style, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = lane))
                        Triple(r.lineCount == 1 && r.isLineEllipsized(0), r.size.height, r.lineCount)
                    }
                    Box(Modifier.requiredSize(900.dp, 700.dp)) {
                        MusicPanelOverlay(
                            model = m,
                            focused = null,
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
                    if (showRef.value) {
                        Box(Modifier.width(refLane.value.dp).height(refHeight.value.dp).background(ground).testTag(Tag)) {
                            Text(
                                text = refText.value,
                                modifier = if (refCentered.value) Modifier.fillMaxWidth() else Modifier,
                                color = refColor.value,
                                style = refStyle.value,
                                maxLines = 1,
                                overflow = if (refClip.value) TextOverflow.Clip else TextOverflow.Ellipsis,
                                textAlign = if (refCentered.value) TextAlign.Center else null,
                            )
                        }
                    }
                }
            }
        }
        fun window(): Bitmap {
            val decor = compose.activity.window.decorView
            return Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        /** Which columns of [node]'s box carry ink: a pixel is ink when it differs from the pixel just left of the box (the card's own ground there). */
        fun inkColumns(frame: Bitmap, left: Int, top: Int, width: Int, height: Int, ground: Int): BooleanArray {
            val out = BooleanArray(width)
            for (x in 0 until width) for (y in 0 until height) {
                val p = frame.getPixel(left + x, top + y)
                val d = abs(android.graphics.Color.red(p) - android.graphics.Color.red(ground)) +
                    abs(android.graphics.Color.green(p) - android.graphics.Color.green(ground)) +
                    abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(ground))
                if (d > 90) { out[x] = true; break }
            }
            return out
        }
        fun hamming(a: BooleanArray, b: BooleanArray) = a.indices.count { a[it] != b[it] }
        fun settle() {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
        }
        fun renderedMask(node: SemanticsNode, frame: Bitmap): BooleanArray {
            val b = node.boundsInWindow
            val groundPixel = frame.getPixel((b.left.toInt() - 2).coerceAtLeast(0), (b.top.toInt() + b.height.toInt() / 2))
            return inkColumns(frame, b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt(), groundPixel)
        }
        fun controlMask(text: String, style: TextStyle, color: Color, clip: Boolean, centered: Boolean, lane: Int, height: Int): BooleanArray {
            compose.runOnIdle {
                refText.value = text; refStyle.value = style; refColor.value = color; refClip.value = clip; refCentered.value = centered
                refLane.value = lane; refHeight.value = height; showRef.value = true
            }
            settle()
            val b = compose.onNodeWithTag(Tag).fetchSemanticsNode().boundsInWindow
            val mask = inkColumns(window(), b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt(), ground.toArgb())
            compose.runOnIdle { showRef.value = false }
            settle()
            return mask
        }
        listOf(1.0f, 1.3f).forEach { s ->
            // Only the layouts made at this scale count: a change of scale lays every line out again, so forget the last scale's first (the first pass keeps what mounting laid out).
            compose.runOnIdle { if (scale.value != s) heard.clear(); scale.value = s }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
            val frame = window()
            val palette = paletteSeen
            val lines = listOf(
                Triple("status line", "Playing — $long", statusStyle),
                Triple("artist line", longArtist, MimirTypeScale.ControlHintCompact),
                Triple("switch row track", longSong, MimirTypeScale.SupportCompact),
            )
            lines.forEach { (name, spoken, style) ->
                val nodes = compose.onAllNodes(hasContentDescription(spoken), useUnmergedTree = true).fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    failures += "$name not found at $s"
                    return@forEach
                }
                // The card's lines may also be mid-crossfade; the widest laid-out node is the line.
                val node = nodes.maxBy { it.size.width }
                val lane = node.size.width
                val (ellipsized, oneLineHeight, lineCount) = measure(spoken, style, lane)
                if (!ellipsized) failures += "$name at $s (lane $lane dp) is not one ellipsised line (lines=$lineCount)"
                if (node.size.height > oneLineHeight + 2) failures += "$name at $s is ${node.size.height} dp tall, taller than one line ($oneLineHeight dp): it wrapped"
                // Rendered: Main's own pixels for this line are the Ellipsis control's, and not the Clip control's.
                val visual = if (name == "status line") "PLAYING — $long" else spoken
                val color = when (name) {
                    "status line" -> palette.accentBright
                    "artist line" -> palette.textSecondary
                    else -> palette.textMuted
                }
                // The real line's own layout (D28): the one made at this lane, else the latest. Overflow, lines, the ellipsis and the case are read off it.
                val layouts = heard[spoken].orEmpty()
                val layout = layouts.lastOrNull { it.layoutInput.constraints.maxWidth == lane } ?: layouts.lastOrNull()
                if (layout == null) {
                    failures += "$name at $s: the real line reported no layout, so its overflow cannot be read"
                } else {
                    val shown = layout.layoutInput.text.text
                    val visibleEnd = layout.getLineEnd(0, visibleEnd = true)
                    if (layout.layoutInput.overflow != TextOverflow.Ellipsis) failures += "$name at $s overflows as ${layout.layoutInput.overflow}, not Ellipsis"
                    if (layout.layoutInput.maxLines != 1) failures += "$name at $s allows ${layout.layoutInput.maxLines} lines, not one"
                    if (layout.lineCount != 1) failures += "$name at $s took ${layout.lineCount} lines, not one"
                    if (!layout.isLineEllipsized(0)) failures += "$name at $s: its line is not ellipsised"
                    if (shown != visual) failures += "$name at $s is laid out as '$shown', not the expected case '$visual'"
                    if (visibleEnd >= shown.length) failures += "$name at $s: its visible end $visibleEnd is not short of the ${shown.length} characters, so nothing was cut"
                    if (layout.size.width > node.size.width) failures += "$name at $s: the layout is ${layout.size.width} wide in a ${node.size.width} lane"
                }
                // Rendered: Main's own pixels for this line against an Ellipsis and a Clip control. Printed; the layout above decides.
                val real = renderedMask(node, frame)
                val ellipsis = controlMask(visual, style, color, clip = false, centered = name != "switch row track", lane = lane, height = node.size.height)
                val clipped = controlMask(visual, style, color, clip = true, centered = name != "switch row track", lane = lane, height = node.size.height)
                val toEllipsis = hamming(real, ellipsis)
                val toClip = hamming(real, clipped)
                val controlsDiffer = hamming(ellipsis, clipped)
                if (real.none { it }) failures += "$name at $s: the rendered line drew no ink in its box"
                rows += "R7-MAIN-WIDTH fontScale=$s $name lane=${lane}dp height=${node.size.height}dp oneLine=${oneLineHeight}dp lines=$lineCount ellipsized=$ellipsized offEllipsis=$toEllipsis offClip=$toClip controlsDiffer=$controlsDiffer layoutOverflow=${layout?.layoutInput?.overflow} layoutEllipsized=${layout?.isLineEllipsized(0)} visibleEnd=${layout?.getLineEnd(0, visibleEnd = true)}"
            }
        }
        rows.forEach(::println)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals("three lines at two scales", 6, rows.size)
    }

    private companion object {
        const val Tag = "r7-case"
    }
}
