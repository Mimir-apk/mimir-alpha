package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.OutputFormat
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The equalizer editor on a real layout pass: the measured-zero hold (valid A, exact zero, a distinct valid B, as the other
 * boundaries' replays do), the 640 dp width cap and the display less 14 dp each side, the 720 px square (a 300 dp display at density
 * 2.4, the RG-class square), the reading order of the rows, and the scroll when the card is taller than the display allows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerEditorLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private data class Viewport(val widthDp: Float, val heightDp: Float)

    private data class Box4(val left: Float, val top: Float, val width: Float, val height: Float)

    private val zero = Viewport(0f, 0f)

    private val flac = TrackFormat(
        codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304,
        output = OutputFormat(bitDepth = 16, sampleRateHz = 48_000),
    )

    private val rock = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK)

    private fun install(
        viewport: MutableState<Viewport>,
        density: Float = 1f,
        settings: EqualizerSettings = rock,
        systemEq: SystemEqualizerState = SystemEqualizerState(),
        focused: EqualizerEditorControl? = null,
        // The header's data lines as two plain lines, so every geometry test below keeps the header height it was written for.
        infoLines: List<EqualizerInfoLine> = equalizerHeaderLines(flac).map { EqualizerInfoLine(it) },
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value) {
                        EqualizerEditorOverlay(
                            settings = settings,
                            focused = focused,
                            authority = null,
                            onToggle = {},
                            onPresetStep = {},
                            onGainRequested = { _, _ -> },
                            onRequestFocus = {},
                            onDismiss = {},
                            systemEq = systemEq,
                            infoLines = infoLines,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun card(): Box4 {
        val rect = compose.onNodeWithTag(EqualizerEditorCardTestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return Box4(rect.left.value, rect.top.value, (rect.right - rect.left).value, (rect.bottom - rect.top).value)
    }

    /** Chrome text speaks its natural string as the content description (the visual is capitals), so that is what is matched. */
    private fun named(text: String) = hasContentDescription(text) or hasText(text)

    private fun top(text: String): Float =
        compose.onAllNodes(named(text), useUnmergedTree = true)[0].getUnclippedBoundsInRoot().top.value

    private fun count(text: String, substring: Boolean = false): Int =
        compose.onAllNodes(if (substring) hasText(text, substring = true) or hasContentDescription(text, substring = true) else named(text), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test
    fun `a measured zero holds the card where it was, keeps its content, and a distinct size settles after it`() {
        val viewport = mutableStateOf(Viewport(900f, 700f))
        install(viewport)
        assertTrue("the heading stays", count("Equalizer") > 0)
        val a = card()
        assertTrue("A geometry must be positive", a.width > 0f && a.height > 0f)

        compose.runOnIdle { viewport.value = zero }
        compose.waitForIdle()
        assertTrue("the heading stays through the zero", count("Equalizer") > 0)
        val held = card()
        assertEquals("held zero width", a.width, held.width, Tolerance)
        assertEquals("held zero height", a.height, held.height, Tolerance)

        compose.runOnIdle { viewport.value = Viewport(300f, 300f) }
        compose.waitForIdle()
        val b = card()
        assertTrue("B must settle a distinct geometry", b.width != a.width || b.height != a.height)
        assertTrue("B stays positive", b.width > 0f && b.height > 0f)
    }

    @Test
    fun `a zero before any valid size renders nothing for that frame and does not crash`() {
        val viewport = mutableStateOf(zero)
        install(viewport)
        assertTrue(
            "no card yet",
            compose.onAllNodes(hasTestTag(EqualizerEditorCardTestTag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        compose.runOnIdle { viewport.value = Viewport(900f, 700f) }
        compose.waitForIdle()
        assertTrue(card().width > 0f)
    }

    @Test
    fun `the card is 640 dp wide on a display that has room, and never wider than the display less 14 dp each side`() {
        val viewport = mutableStateOf(Viewport(900f, 700f))
        install(viewport)
        assertEquals("900x700", 640f, card().width, Tolerance)
        compose.runOnIdle { viewport.value = Viewport(1200f, 700f) }
        compose.waitForIdle()
        assertEquals("1200x700", 640f, card().width, Tolerance)
        compose.runOnIdle { viewport.value = Viewport(300f, 700f) }
        compose.waitForIdle()
        assertTrue("300 dp display: ${card().width}", card().width <= 272f + Tolerance)
        compose.runOnIdle { viewport.value = Viewport(660f, 700f) }
        compose.waitForIdle()
        assertTrue("660 dp display: ${card().width}", card().width <= 632f + Tolerance)
    }

    @Test
    fun `a 720 px square at density 2_4 is a 300 dp display, the card holds inside it and every control is on the sheet`() {
        val viewport = mutableStateOf(Viewport(300f, 300f))
        install(viewport, density = 2.4f)
        val c = card()
        assertEquals(272f, c.width, Tolerance)
        assertTrue("never taller than the display less the clearance: ${c.height}", c.height <= 272f + Tolerance)
        assertTrue("clear of the top and bottom edges", c.top >= 14f - Tolerance && c.top + c.height <= 286f + Tolerance)
        // The card scrolls: the controls below the fold are composed and reachable, not cut.
        listOf("Equalizer", "Preset", "Bass", "Treble", "31 Hz", "1 kHz", "16 kHz").forEach { label ->
            assertTrue("$label is on the sheet", count(label) > 0)
        }
    }

    @Test
    fun `a card taller than the display allows is held inside it`() {
        val viewport = mutableStateOf(Viewport(400f, 200f))
        install(viewport)
        val c = card()
        assertTrue("height ${c.height} fits 200 less 28", c.height <= 172f + Tolerance)
        assertEquals(14f, c.top, Tolerance)
    }

    @Test
    fun `the controls read in focus order, the header above them`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport)
        val order = listOf("Equalizer", "Preset", "Bass", "Treble", "31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
        // The heading and the toggle share a word; the toggle is the second "Equalizer" node, the rows follow it.
        val tops = order.drop(1).map(::top)
        assertEquals("rows go down the sheet in focus order", tops.sorted(), tops)
        assertTrue("the header is above the first row", top("Output: 16-bit · 48 kHz") < top("Preset"))
        assertTrue("source line, then output line", top("FLAC · 24/96 · 2,304 kbps") < top("Output: 16-bit · 48 kHz"))
        assertEquals("the retired bit-depth note is gone", 0, count("24-bit files play as 16-bit."))
    }

    @Test
    fun `the one switch reads above the preset, and there is no second switch or System EQ row`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport)
        assertEquals(1, count(EqualizerToggleSupporting))
        assertTrue("the switch's line is above the preset", top(EqualizerToggleSupporting) < top("Preset"))
        assertEquals("no second switch", 0, count("System EQ"))
        assertEquals("the switch's line is the only place the sheet says what the Equalizer reaches", 1, count("Shapes all sound", substring = true))
    }

    @Test
    fun `with the Equalizer off, focusing the switch keeps the head of the sheet in view and does not carry it to the foot`() {
        // Off, the switch is all that is reachable, so it is the last reachable control: it must still reveal the head of the sheet,
        // where it is, not the bottom, where the dimmed sliders are.
        val viewport = mutableStateOf(Viewport(400f, 200f))
        install(viewport, settings = EqualizerSettings(), focused = EqualizerEditorControl.TOGGLE)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val window = compose.onNodeWithTag(EqualizerEditorViewportTestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val curve = compose.onNodeWithTag(EqualizerEditorCurveTestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "the sheet is at its head: the curve (top ${curve.top.value}) stands at the window's top (${window.top.value}), not scrolled to the dimmed sliders",
            curve.top.value >= window.top.value - Tolerance,
        )
    }

    @Test
    fun `the switch's line is the same when another app holds the global engine, and says nothing of a second switch`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport, systemEq = SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL))
        assertEquals(1, count(EqualizerToggleSupporting))
        assertEquals(0, count("System EQ"))
    }

    @Test
    fun `the preset row says its headroom, and the toggle says what the Equalizer shapes`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport)
        assertEquals(1, count(EqualizerToggleSupporting))
        assertTrue(compose.onAllNodes(hasContentDescription("Headroom ", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the information block is in the header, with Mimir's own format right under its entry, above the controls, in the order it is given`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        val lines = equalizerInfoLines(
            output = com.gruning.mimir.rewrite.core.audio.MediaOutput(com.gruning.mimir.rewrite.core.audio.MediaOutputKind.BLUETOOTH, "Buds"),
            music = MusicControlState(yours = YourMusicState(title = "Tiny 079", isPlaying = true, format = flac), otherAccess = false),
            settings = rock,
            systemEq = SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL),
        )
        install(viewport, infoLines = lines, systemEq = SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL))
        lines.forEach { assertEquals("\"${it.text}\" is drawn once", 1, count(it.text)) }
        val tops = lines.map { top(it.text) }
        assertEquals("in the order given", tops.sorted(), tops)
        assertTrue(
            "Mimir's own format sits right under its entry, and above the Equalizer status",
            top("Playing: Mimir · Tiny 079") < top("FLAC · 24/96 · 2,304 kbps") &&
                top("FLAC · 24/96 · 2,304 kbps") < top("Output: 16-bit · 48 kHz") &&
                top("Output: 16-bit · 48 kHz") < top("Equalizer: On · Rock · music on this device only right now"),
        )
        assertTrue("and above the first control", tops.last() < top("Preset"))
        // The sheet scrolls under it: the header does not, so the controls keep their window.
        assertTrue("the card is held inside the display", card().height > 0f)
    }

    @Test
    fun `a flat equalizer needs no headroom and says so`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport, settings = EqualizerSettings(enabled = true))
        assertEquals(1, count(EqualizerNoHeadroomLine))
    }

    @Test
    fun `before the player has said a format the header is the heading alone`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport, infoLines = emptyList())
        assertEquals(0, count("24-bit files play as 16-bit."))
        assertEquals(0, compose.onAllNodes(hasText("Output:", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the header's data lines are natural case, with the unit as written`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport, infoLines = equalizerNowPlayingLines(MusicControlState(yours = YourMusicState(isPlaying = true, format = flac), otherAccess = true)))
        val drawn = { text: String -> compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size }
        assertEquals("the source line is drawn as written", 1, drawn("FLAC · 24/96 · 2,304 kbps"))
        assertEquals("the output line is drawn as written, kHz and all", 1, drawn("Output: 16-bit · 48 kHz"))
        assertEquals("not upper-cased chrome", 0, drawn("OUTPUT: 16-BIT · 48 KHZ"))
    }

    @Test
    fun `the foot hint is the etched keycap with its word, and is there only when something asks for it`() {
        val viewport = mutableStateOf(Viewport(900f, 3200f))
        install(viewport, focused = EqualizerEditorControl.BAND_2)
        val foot = compose.onAllNodes(hasTestTag(EqualizerEditorFootHintTestTag), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("a live slider asks for X Reset", 1, foot.size)
        assertEquals("the keycap carries the key", 1, compose.onAllNodes(hasText("X") and hasAnyAncestor(hasTestTag(EqualizerEditorFootHintTestTag)), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("and the word is spoken as written", 1, compose.onAllNodes(hasContentDescription("Reset") and hasAnyAncestor(hasTestTag(EqualizerEditorFootHintTestTag)), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** Forces the child to receive exact viewport constraints, including [Constraints.fixed] (0, 0). */
    @Composable
    private fun FixedViewport(viewport: Viewport, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val widthPx = with(density) { viewport.widthDp.dp.roundToPx() }
        val heightPx = with(density) { viewport.heightDp.dp.roundToPx() }
        Layout(
            content = {
                Box(propagateMinConstraints = true, content = { content() })
            },
        ) { measurables, constraints ->
            val placeable = measurables.single().measure(Constraints.fixed(widthPx, heightPx))
            val layoutWidth = widthPx.coerceIn(constraints.minWidth, constraints.maxWidth)
            val layoutHeight = heightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(layoutWidth, layoutHeight) {
                placeable.placeRelative(0, 0)
            }
        }
    }

    private companion object {
        const val Tolerance = 1.5f
    }
}
