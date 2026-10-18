package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.OutputFormat
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The equalizer editor's pure side and its controller: the focus order, what is reachable while the equalizer is off, what each
 * press does (a dB step, the X reset, the preset cycle, A on the one switch, X on a refused engine, B), and that every step lands in
 * the engine at once. There is one switch: the global engine follows it and Mimir's own stage is its fallback.
 */
class EqualizerEditorControllerTest {
    private class FakeEngine(enabled: Boolean) : EqualizerEditorEngine {
        override val settings = MutableStateFlow(EqualizerSettings(enabled = enabled))
        override val systemEq = MutableStateFlow(SystemEqualizerState(wanted = enabled, status = if (enabled) SystemEqStatus.ON else SystemEqStatus.OFF))
        val calls = mutableListOf<String>()

        /** What the global engine makes of a switch that was turned on: the platform's answer, which a test chooses. */
        var systemEqOutcome = SystemEqStatus.ON

        /** What a second ask of the platform makes of it. */
        var retryOutcome = SystemEqStatus.ON

        override fun retrySystemEq() {
            calls += "retry"
            systemEq.value = SystemEqualizerState(wanted = true, status = retryOutcome)
        }

        override fun setEnabled(enabled: Boolean) {
            calls += "enabled=$enabled"
            settings.value = settings.value.withEnabled(enabled)
            systemEq.value = SystemEqualizerState(wanted = enabled, status = if (enabled) systemEqOutcome else SystemEqStatus.OFF)
        }

        override fun setBand(index: Int, db: Float) {
            calls += "band$index=$db"
            settings.value = settings.value.withBand(index, db)
        }

        override fun setBass(db: Float) {
            calls += "bass=$db"
            settings.value = settings.value.withBass(db)
        }

        override fun setTreble(db: Float) {
            calls += "treble=$db"
            settings.value = settings.value.withTreble(db)
        }

        override fun applyPreset(id: EqualizerPresetId) {
            calls += "preset=$id"
            settings.value = settings.value.withPreset(id)
        }
    }

    private lateinit var engine: FakeEngine
    private lateinit var authority: DefaultFocusAuthority
    private lateinit var controller: EqualizerEditorController
    private var closes = 0

    private fun open(enabled: Boolean) {
        engine = FakeEngine(enabled)
        authority = DefaultFocusAuthority()
        closes = 0
        controller = EqualizerEditorController(engine, authority, onClose = { closes++ })
        var top = 0f
        for (control in EqualizerEditorControl.entries) {
            authority.register(equalizerEditorFocusKey(control), EqualizerEditorScope, FocusBounds(0f, top, 100f, top + 10f))
            top += 20f
        }
        authority.requestFocus(equalizerEditorFocusKey(EqualizerEditorControl.TOGGLE))
    }

    @Before
    fun setUp() = open(enabled = true)

    @After
    fun tearDown() = controller.dispose()

    private fun focused() = controller.focusedControl()

    private fun press(action: ControllerAction) = controller.dispatch(action)

    private fun walkTo(target: EqualizerEditorControl) {
        while (focused() != target) press(ControllerAction.NAV_DOWN)
    }

    // ---- order and reach ----

    @Test
    fun `the controls are the one switch, the preset, Bass, Treble, then the ten bands lowest first`() {
        assertEquals(
            listOf("TOGGLE", "PRESET", "BASS", "TREBLE") + (0..9).map { "BAND_$it" },
            EqualizerEditorControl.entries.map { it.name },
        )
        assertEquals(EqualizerEditorControl.entries, equalizerEditorReachable(true))
        assertEquals(listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz"), (0..9).map(::equalizerBandLabel))
    }

    @Test
    fun `the ring opens on the switch and walks the controls in order without wrapping`() {
        assertEquals(EqualizerEditorControl.TOGGLE, focused())
        val seen = mutableListOf(focused())
        repeat(EqualizerEditorControl.entries.size - 1) {
            press(ControllerAction.NAV_DOWN)
            seen += focused()
        }
        assertEquals(EqualizerEditorControl.entries, seen)
        press(ControllerAction.NAV_DOWN)
        assertEquals("no wrap at the foot", EqualizerEditorControl.BAND_9, focused())
        repeat(EqualizerEditorControl.entries.size) { press(ControllerAction.NAV_UP) }
        assertEquals("no wrap at the head", EqualizerEditorControl.TOGGLE, focused())
    }

    @Test
    fun `off, only the switch is reachable and the D-pad, the steps and X touch nothing`() {
        open(enabled = false)
        assertEquals(listOf(EqualizerEditorControl.TOGGLE), equalizerEditorReachable(false))
        press(ControllerAction.NAV_DOWN)
        assertEquals("nothing below the switch is reachable", EqualizerEditorControl.TOGGLE, focused())
        press(ControllerAction.NAV_RIGHT)
        press(ControllerAction.NAV_LEFT)
        press(ControllerAction.SECONDARY)
        assertEquals(emptyList<String>(), engine.calls)
        // A touch on a dimmed row does not move the ring there and does not edit it.
        controller.requestFocus(EqualizerEditorControl.BAND_3)
        assertEquals(EqualizerEditorControl.TOGGLE, focused())
        controller.onGainRequested(EqualizerEditorControl.BAND_3, 1f)
        assertFalse(controller.onPresetStep(1))
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `A on the switch turns the equalizer on and the rest becomes reachable, and off again leaves the ring on the switch`() {
        open(enabled = false)
        press(ControllerAction.CONFIRM)
        assertTrue(engine.settings.value.enabled)
        press(ControllerAction.NAV_DOWN)
        assertEquals(EqualizerEditorControl.PRESET, focused())
        walkTo(EqualizerEditorControl.BAND_4)
        controller.onToggle()
        assertFalse(engine.settings.value.enabled)
        assertEquals(EqualizerEditorControl.TOGGLE, focused())
    }

    @Test
    fun `A on any control but the switch does nothing`() {
        walkTo(EqualizerEditorControl.PRESET)
        press(ControllerAction.CONFIRM)
        walkTo(EqualizerEditorControl.BAND_2)
        press(ControllerAction.CONFIRM)
        assertEquals(emptyList<String>(), engine.calls)
        assertEquals(0, closes)
    }

    // ---- steps, reset, presets ----

    @Test
    fun `left and right move a gain one dB and apply it at once, held at the ends`() {
        walkTo(EqualizerEditorControl.BAND_0)
        press(ControllerAction.NAV_RIGHT)
        press(ControllerAction.NAV_RIGHT)
        press(ControllerAction.NAV_LEFT)
        assertEquals(1f, engine.settings.value.bandsDb[0], 0f)
        assertEquals(listOf("band0=1.0", "band0=2.0", "band0=1.0"), engine.calls)
        engine.calls.clear()
        repeat(14) { press(ControllerAction.NAV_RIGHT) }
        assertEquals(12f, engine.settings.value.bandsDb[0], 0f)
        assertEquals("an edge press changes nothing and writes nothing", 11, engine.calls.size)
    }

    @Test
    fun `Bass and Treble step too, and leave the preset alone`() {
        walkTo(EqualizerEditorControl.PRESET)
        press(ControllerAction.NAV_RIGHT)
        assertEquals(EqualizerPresetId.BASS_BOOST, engine.settings.value.presetId)
        press(ControllerAction.NAV_DOWN)
        assertEquals(EqualizerEditorControl.BASS, focused())
        press(ControllerAction.NAV_RIGHT)
        press(ControllerAction.NAV_DOWN)
        press(ControllerAction.NAV_LEFT)
        assertEquals(1f, engine.settings.value.bassDb, 0f)
        assertEquals(-1f, engine.settings.value.trebleDb, 0f)
        assertEquals("the shelves are tone controls on top of the preset", EqualizerPresetId.BASS_BOOST, engine.settings.value.presetId)
    }

    @Test
    fun `X puts the focused slider back to 0 dB and does nothing on the switch, the preset or a slider already at zero`() {
        walkTo(EqualizerEditorControl.BAND_5)
        repeat(4) { press(ControllerAction.NAV_RIGHT) }
        assertEquals(4f, engine.settings.value.bandsDb[5], 0f)
        engine.calls.clear()
        press(ControllerAction.SECONDARY)
        assertEquals(0f, engine.settings.value.bandsDb[5], 0f)
        assertEquals(listOf("band5=0.0"), engine.calls)
        press(ControllerAction.SECONDARY)
        assertEquals("already zero: no second write", listOf("band5=0.0"), engine.calls)
        // Only the focused slider: its neighbour kept its gain.
        press(ControllerAction.NAV_DOWN)
        repeat(3) { press(ControllerAction.NAV_RIGHT) }
        press(ControllerAction.NAV_UP)
        press(ControllerAction.SECONDARY)
        assertEquals(3f, engine.settings.value.bandsDb[6], 0f)
        // The switch and the preset have nothing to reset.
        engine.calls.clear()
        walkTo(EqualizerEditorControl.BAND_9)
        repeat(EqualizerEditorControl.entries.size) { press(ControllerAction.NAV_UP) }
        press(ControllerAction.SECONDARY)
        press(ControllerAction.NAV_DOWN)
        press(ControllerAction.SECONDARY)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `the preset row cycles through every preset then Custom and wraps, and editing a band selects Custom`() {
        walkTo(EqualizerEditorControl.PRESET)
        assertEquals(EqualizerEditorControl.PRESET, focused())
        val walked = mutableListOf(engine.settings.value.presetId)
        repeat(EqualizerPresetCycle.size - 1) {
            press(ControllerAction.NAV_RIGHT)
            walked += engine.settings.value.presetId
        }
        assertEquals(EqualizerPresetCycle, walked)
        press(ControllerAction.NAV_RIGHT)
        assertEquals("wraps to Flat", EqualizerPresetId.FLAT, engine.settings.value.presetId)
        press(ControllerAction.NAV_LEFT)
        assertEquals("and back to Custom", EqualizerPresetId.CUSTOM, engine.settings.value.presetId)
        assertTrue("the switch is kept", engine.settings.value.enabled)

        // A band edit from a preset is Custom, and the preset after it never erases the custom curve.
        press(ControllerAction.NAV_RIGHT)
        press(ControllerAction.NAV_RIGHT)
        assertEquals(EqualizerPresetId.BASS_BOOST, engine.settings.value.presetId)
        walkTo(EqualizerEditorControl.BAND_9)
        press(ControllerAction.NAV_RIGHT)
        assertEquals(EqualizerPresetId.CUSTOM, engine.settings.value.presetId)
        val custom = engine.settings.value.bandsDb
        controller.requestFocus(EqualizerEditorControl.PRESET)
        press(ControllerAction.NAV_RIGHT)
        assertEquals(EqualizerPresetId.FLAT, engine.settings.value.presetId)
        repeat(EqualizerPresetCycle.size - 1) { press(ControllerAction.NAV_RIGHT) }
        assertEquals(EqualizerPresetId.CUSTOM, engine.settings.value.presetId)
        assertEquals("Custom came back as the player left it", custom, engine.settings.value.bandsDb)
    }

    @Test
    fun `touch sets a gain from a fraction of the track, one dB at a time`() {
        controller.onGainRequested(EqualizerEditorControl.BAND_2, 1f)
        assertEquals(12f, engine.settings.value.bandsDb[2], 0f)
        controller.onGainRequested(EqualizerEditorControl.BAND_2, 0f)
        assertEquals(-12f, engine.settings.value.bandsDb[2], 0f)
        controller.onGainRequested(EqualizerEditorControl.BAND_2, 0.5f)
        assertEquals(0f, engine.settings.value.bandsDb[2], 0f)
        controller.onGainRequested(EqualizerEditorControl.BASS, 0.75f)
        assertEquals(6f, engine.settings.value.bassDb, 0f)
        // The switch and the preset are not sliders.
        engine.calls.clear()
        controller.onGainRequested(EqualizerEditorControl.TOGGLE, 1f)
        controller.onGainRequested(EqualizerEditorControl.PRESET, 1f)
        assertEquals(emptyList<String>(), engine.calls)
    }

    // ---- the one switch and the global engine ----

    @Test
    fun `A on the switch asks for the Equalizer on, and A again asks for it off, and the engine follows the one switch`() {
        open(enabled = false)
        press(ControllerAction.CONFIRM)
        assertEquals(listOf("enabled=true"), engine.calls)
        assertTrue(engine.settings.value.enabled)
        assertTrue("the engine is wanted by the same switch", engine.systemEq.value.wanted)
        press(ControllerAction.CONFIRM)
        assertEquals(listOf("enabled=true", "enabled=false"), engine.calls)
        assertEquals(SystemEqStatus.OFF, engine.systemEq.value.status)
        assertFalse(engine.systemEq.value.wanted)
    }

    @Test
    fun `touch and A reach the same action`() {
        open(enabled = false)
        controller.onToggle()
        assertEquals(listOf("enabled=true"), engine.calls)
    }

    @Test
    fun `on, the curve is live whatever the engine says, because Mimir's own stage shapes Mimir's music when the engine cannot`() {
        for (outcome in SystemEqStatus.entries.filter { it != SystemEqStatus.OFF }) {
            open(enabled = false)
            engine.systemEqOutcome = outcome
            controller.onToggle()
            assertEquals(outcome, engine.systemEq.value.status)
            assertTrue("$outcome is live", equalizerEditorLive(engine.settings.value))
            assertEquals(EqualizerEditorControl.entries, equalizerEditorReachable(equalizerEditorLive(engine.settings.value)))
            controller.requestFocus(EqualizerEditorControl.BAND_3)
            assertEquals(EqualizerEditorControl.BAND_3, focused())
            press(ControllerAction.NAV_RIGHT)
            assertEquals(1f, engine.settings.value.bandsDb[3], 0f)
            assertTrue(controller.onPresetStep(1))
            controller.dispose()
        }
        open(enabled = false)
        assertFalse("off is the one state with a dead curve", equalizerEditorLive(engine.settings.value))
    }

    @Test
    fun `a switch pressed while another app has the engine switches the Equalizer off, which is how the player cancels it`() {
        open(enabled = false)
        engine.systemEqOutcome = SystemEqStatus.NO_CONTROL
        controller.onToggle()
        assertEquals(SystemEqStatus.NO_CONTROL, engine.systemEq.value.status)
        controller.onToggle()
        assertEquals(listOf("enabled=true", "enabled=false"), engine.calls)
        assertEquals(SystemEqStatus.OFF, engine.systemEq.value.status)
    }

    @Test
    fun `X on the switch asks Android again while the engine was refused, keeps the switch on, and does nothing in any other state`() {
        open(enabled = false)
        press(ControllerAction.SECONDARY)
        assertEquals("off: nothing to retry", emptyList<String>(), engine.calls)

        engine.systemEqOutcome = SystemEqStatus.NO_CONTROL
        press(ControllerAction.CONFIRM)
        press(ControllerAction.SECONDARY)
        assertEquals("another app has it: X is not a retry", listOf("enabled=true"), engine.calls)

        open(enabled = false)
        engine.systemEqOutcome = SystemEqStatus.UNAVAILABLE
        press(ControllerAction.CONFIRM)
        assertEquals(SystemEqStatus.UNAVAILABLE, engine.systemEq.value.status)
        // Still refused: X asks again, and the switch has not gone to off in between.
        engine.retryOutcome = SystemEqStatus.UNAVAILABLE
        press(ControllerAction.SECONDARY)
        assertEquals(listOf("enabled=true", "retry"), engine.calls)
        assertTrue(engine.settings.value.enabled)
        // The device takes it this time.
        engine.retryOutcome = SystemEqStatus.ON
        press(ControllerAction.SECONDARY)
        assertEquals(listOf("enabled=true", "retry", "retry"), engine.calls)
        assertTrue(engine.systemEq.value.on)
        // From on, X is not a retry and A is off as it always was.
        press(ControllerAction.SECONDARY)
        assertEquals(3, engine.calls.size)
        press(ControllerAction.CONFIRM)
        assertEquals("enabled=false", engine.calls.last())
    }

    @Test
    fun `X on a slider still resets it, and the retry is only on the switch`() {
        open(enabled = true)
        engine.systemEq.value = SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE)
        controller.requestFocus(EqualizerEditorControl.BAND_3)
        press(ControllerAction.NAV_RIGHT)
        engine.calls.clear()
        press(ControllerAction.SECONDARY)
        assertEquals("the slider went back to zero, and nothing was retried", listOf("band3=0.0"), engine.calls)
    }

    // ---- the foot ----

    @Test
    fun `the foot says X Reset on a live slider, X Try again on the switch while the engine is refused, and nothing else`() {
        val on = EqualizerSettings(enabled = true)
        val off = EqualizerSettings(enabled = false)
        val refused = SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE)
        val taken = SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL)
        val working = SystemEqualizerState(wanted = true, status = SystemEqStatus.ON)

        assertEquals(EqualizerFootHint("X", "Reset"), equalizerFootHint(on, working, EqualizerEditorControl.BAND_2))
        assertEquals(EqualizerFootHint("X", "Reset"), equalizerFootHint(on, refused, EqualizerEditorControl.BASS))
        assertNull("a dead slider has no reset", equalizerFootHint(off, SystemEqualizerState(), EqualizerEditorControl.BAND_2))
        assertEquals(EqualizerFootHint("X", "Try again"), equalizerFootHint(on, refused, EqualizerEditorControl.TOGGLE))
        assertNull("not refused: no retry", equalizerFootHint(on, taken, EqualizerEditorControl.TOGGLE))
        assertNull(equalizerFootHint(on, working, EqualizerEditorControl.TOGGLE))
        assertNull("off: nothing to retry", equalizerFootHint(off, refused, EqualizerEditorControl.TOGGLE))
        assertNull(equalizerFootHint(on, refused, EqualizerEditorControl.PRESET))
        assertNull(equalizerFootHint(on, refused, null))
    }

    // ---- the words ----

    @Test
    fun `the switch describes only the sound the Equalizer shapes`() {
        assertEquals(
            "Shapes all sound on this device: your music, music apps and most games. Games that use low-latency audio aren\u2019t reached.",
            EqualizerToggleSupporting,
        )
        assertEquals("Equalizer", EqualizerToggleLabel)
        assertEquals("Equalizer", EqualizerEditorHeading)
    }

    @Test
    fun `no equalizer copy names an emulator or a vendor app`() {
        val copy = listOf(
            EqualizerEditorHeading, EqualizerToggleLabel, EqualizerToggleSupporting, EqualizerPresetLabel, EqualizerBassLabel, EqualizerTrebleLabel,
            EqualizerNoHeadroomLine, equalizerHeadroomLine(6f), EqualizerResetFootHint.verb, EqualizerRetryFootHint.verb,
            MusicPanelEqualizerLabel, MusicPanelEqualizerSupporting,
        )
        val banned = Regex("\\b(azahar|melonds|retroarch|odin|thor|system eq)\\b", RegexOption.IGNORE_CASE)
        copy.forEach { line -> assertFalse("\"$line\" names an emulator, a vendor app or the old switch", banned.containsMatchIn(line)) }
    }

    // ---- closing ----

    @Test
    fun `B and Back close the editor and keep every change, and every action is consumed`() {
        walkTo(EqualizerEditorControl.BAND_1)
        press(ControllerAction.NAV_RIGHT)
        val kept = engine.settings.value
        assertTrue(press(ControllerAction.CANCEL))
        assertTrue(press(ControllerAction.SYSTEM_BACK))
        assertEquals(2, closes)
        assertEquals("nothing is reverted", kept, engine.settings.value)
        // A hard modal: a press it has no use for does not leak to the pane beneath.
        assertTrue(press(ControllerAction.TERTIARY))
        assertTrue(press(ControllerAction.PAGE_LEFT))
        assertEquals(2, closes)
    }

    // ---- the pure strings ----

    @Test
    fun `gain readouts use the typeset minus and whole dB`() {
        assertEquals("+3 dB", equalizerGainReadout(3f))
        assertEquals("0 dB", equalizerGainReadout(0f))
        assertEquals("−4 dB", equalizerGainReadout(-4f))
        assertEquals("+12 dB", equalizerGainReadout(12f))
        assertEquals("−12 dB", equalizerGainReadout(-12f))
    }

    @Test
    fun `the gain scale is twenty-five one-dB positions with 0 dB in the middle`() {
        assertEquals(25, EqGain.Positions)
        assertEquals(23, EqGain.Steps)
        assertEquals(0.5f, EqGain.fractionForDb(0f), 0f)
        assertEquals(0f, EqGain.fractionForDb(-12f), 0f)
        assertEquals(1f, EqGain.fractionForDb(12f), 0f)
        for (position in 0 until EqGain.Positions) {
            assertEquals(position, EqGain.positionFor(EqGain.fractionFor(position)))
            assertEquals(position - 12f, EqGain.dbFor(EqGain.fractionFor(position)), 0f)
        }
        assertEquals(12, EqGain.positionFor(Float.NaN))
        // The slider's own quantizer is this scale, so a touch and a step land on the same positions.
        assertEquals(EqGain.fractionFor(13), BrightnessMath.quantize(BrightnessTarget.EQ_GAIN, 0.53f), 0f)
        assertEquals(EqGain.fractionFor(14), BrightnessMath.step(BrightnessTarget.EQ_GAIN, EqGain.fractionFor(13), 1), 0f)
        assertEquals(EqGain.fractionFor(24), BrightnessMath.step(BrightnessTarget.EQ_GAIN, 1f, 1), 0f)
        assertEquals(EqGain.fractionFor(0), BrightnessMath.step(BrightnessTarget.EQ_GAIN, 0f, -1), 0f)
    }

    @Test
    fun `the preset row says what the automatic preamp is doing`() {
        assertEquals(EqualizerNoHeadroomLine, equalizerHeadroomLine(0f))
        assertEquals(EqualizerNoHeadroomLine, equalizerHeadroomLine(0.4f))
        assertEquals("Headroom −6 dB, so boosts never clip.", equalizerHeadroomLine(5.8f))
        val rock = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK)
        assertTrue(equalizerHeadroomLine(rock.effectiveHeadroomDb).startsWith("Headroom −"))
        assertEquals(EqualizerNoHeadroomLine, equalizerHeadroomLine(EqualizerSettings(enabled = true).effectiveHeadroomDb))
    }

    @Test
    fun `the header names the source and the output when known, and says nothing of bit depth beyond them`() {
        assertEquals(emptyList<String>(), equalizerHeaderLines(null))
        val flac = TrackFormat(
            codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304,
            output = OutputFormat(bitDepth = 16, sampleRateHz = 48_000),
        )
        // Natural case, as the Music panel's own "Output: 16-bit · 48 kHz": a unit is "kHz", never "KHZ".
        assertEquals(listOf("FLAC · 24/96 · 2,304 kbps", "Output: 16-bit · 48 kHz"), equalizerHeaderLines(flac))
        assertEquals(listOf("FLAC · 24/96 · 2,304 kbps"), equalizerHeaderLines(flac.copy(output = null)))
        // The output line is the player's own report, so float output reads as float and no second sentence has to be kept true.
        val float = flac.copy(output = OutputFormat(bitDepth = 32, isFloat = true, sampleRateHz = 48_000))
        assertTrue(equalizerHeaderLines(float).last().startsWith("Output: float"))
        assertEquals("1 kHz", equalizerControlLabel(EqualizerEditorControl.BAND_5))
        assertEquals("Bass", equalizerControlLabel(EqualizerEditorControl.BASS))
        assertEquals("Treble", equalizerControlLabel(EqualizerEditorControl.TREBLE))
    }
}
