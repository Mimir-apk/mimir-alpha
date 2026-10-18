package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.Equalizer
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerPresets
import com.gruning.mimir.rewrite.core.audio.EqualizerReach
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MediaOutput
import com.gruning.mimir.rewrite.core.audio.MediaOutputText
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.equalizerReach
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The equalizer editor's pure side (Settings › Music › Equalizer): which controls there are and in what order, which of them the
 * controller can reach, what each press does to the settings, and every string on the sheet. No Compose and no Android types, so
 * all of it runs on the JVM.
 *
 * Every step applies and is stored at once ([EqualizerEditorEngine]); there is no draft, so closing the editor keeps what the
 * player set.
 */

/** The editor's controls in focus order: the one switch, the preset, the two shelves, then the ten bands, lowest first. */
internal enum class EqualizerEditorControl(val bandIndex: Int? = null) {
    TOGGLE,
    PRESET,
    BASS,
    TREBLE,
    BAND_0(0),
    BAND_1(1),
    BAND_2(2),
    BAND_3(3),
    BAND_4(4),
    BAND_5(5),
    BAND_6(6),
    BAND_7(7),
    BAND_8(8),
    BAND_9(9),
    ;

    /** A gain slider: the two shelves and the ten bands. The switch and the preset are not. */
    val isSlider: Boolean get() = this != TOGGLE && this != PRESET
}

internal val EqualizerEditorScope = FocusScope("music/equalizer")

internal fun equalizerEditorFocusKey(control: EqualizerEditorControl): FocusKey =
    FocusKey("music/equalizer/${control.name.lowercase()}")

internal fun equalizerEditorControlFor(key: FocusKey?): EqualizerEditorControl? =
    EqualizerEditorControl.entries.firstOrNull { equalizerEditorFocusKey(it) == key }

// ---- copy ----

internal const val EqualizerEditorHeading = "Equalizer"
internal const val EqualizerToggleLabel = "Equalizer"

/**
 * The one switch's line: what the Equalizer shapes. It names no emulator and no vendor app ; a game that plays on
 * the device's low-latency path is the one thing it cannot reach, and the line says so in those words.
 */
internal const val EqualizerToggleSupporting =
    "Shapes all sound on this device: your music, music apps and most games. Games that use low-latency audio aren\u2019t reached."
internal const val EqualizerPresetLabel = "Preset"
internal const val EqualizerBassLabel = "Bass"
internal const val EqualizerTrebleLabel = "Treble"
internal const val EqualizerNoHeadroomLine = "No headroom needed."

/** The lead of the header's format line, as the Music panel's own line says it. */
internal const val EqualizerOutputLabel = "Output"

/** One foot hint: the factory token of the key (projected to the pad's own label at draw time) and its verb. */
internal data class EqualizerFootHint(val token: String, val verb: String)

internal val EqualizerResetFootHint = EqualizerFootHint("X", "Reset")

/** While the global engine was refused: X asks Android again. (A switches the Equalizer off and on like any switch, which also asks again.) */
internal val EqualizerRetryFootHint = EqualizerFootHint("X", "Try again")

/** U+2212, the minus sign: the sheet's readouts and the headroom line use it, as a typeset figure does. */
private const val Minus = "−"

/** "31 Hz", "1 kHz", "16 kHz": the band's label. */
internal fun equalizerBandLabel(index: Int): String {
    val hz = Equalizer.BandFrequenciesHz[index]
    return if (hz >= 1_000) "${hz / 1_000} kHz" else "$hz Hz"
}

internal fun equalizerControlLabel(control: EqualizerEditorControl): String = when (control) {
    EqualizerEditorControl.TOGGLE -> EqualizerToggleLabel
    EqualizerEditorControl.PRESET -> EqualizerPresetLabel
    EqualizerEditorControl.BASS -> EqualizerBassLabel
    EqualizerEditorControl.TREBLE -> EqualizerTrebleLabel
    else -> equalizerBandLabel(control.bandIndex!!)
}

/** The gain a slider shows: "+3 dB", "0 dB", "−4 dB" (a half step, "+3.5 dB", only for a value another build stored). */
internal fun equalizerGainReadout(db: Float): String {
    val rounded = (db * 2f).roundToInt() / 2f
    val magnitude = abs(rounded)
    val figure = if (magnitude % 1f == 0f) magnitude.toInt().toString() else magnitude.toString()
    return when {
        rounded > 0f -> "+$figure dB"
        rounded < 0f -> "$Minus$figure dB"
        else -> "0 dB"
    }
}

/** The preset row's line, from the figure the engine derives once per settings value ([EqualizerSettings.effectiveHeadroomDb]). */
internal fun equalizerHeadroomLine(headroomDb: Float): String {
    val whole = headroomDb.roundToInt()
    return if (whole <= 0) EqualizerNoHeadroomLine else "Headroom $Minus$whole dB, so boosts never clip."
}

/**
 * Mimir's own track's format lines, which [equalizerNowPlayingLines] puts directly under its "Playing/Paused: Mimir · …" entry and nowhere
 * else: the track's source line, then its output line. A line the player has not reported is left out. Both are natural-case data, in the
 * voice of the Music panel's own "Output: 16-bit · 48 kHz" (an upper-cased "48 KHZ" is not a unit).
 */
internal fun equalizerHeaderLines(format: TrackFormat?): List<String> = buildList {
    format?.sourceLine?.let(::add)
    format?.outputValue?.let { add("$EqualizerOutputLabel: $it") }
}

// ---- what the header says of the sound on the device ----

/** How a header line is inked: the facts at the ordinary tone, a state that needs the player's eye brighter, the standing footnote quieter. */
internal enum class EqualizerInfoTone { NORMAL, ATTENTION, QUIET }

/** One natural-case line of the header's information block (see [equalizerInfoLines]). */
internal data class EqualizerInfoLine(val text: String, val tone: EqualizerInfoTone = EqualizerInfoTone.NORMAL)

/** The lead of the line that says where media sound goes. */
internal const val EqualizerOutputDeviceLabel = "Plays on"

/** The app name Mimir's own music is shown under. */
internal const val EqualizerOwnAppLabel = "Mimir"

/** The name the theme bed (Settings › Music › Source: Mimir) is shown under while it sounds: it is Mimir's music, not Your music from a folder. */
internal const val EqualizerThemeAppLabel = "Mimir\u2019s music"

/** The app name another player is shown under when its session names none. */
internal const val EqualizerOtherAppLabel = "Another app"

internal const val EqualizerNothingPlayingLine = "Nothing is playing."

/** Without notification access Mimir cannot see other apps' playback, and says so, once, rather than guess. */
internal const val EqualizerNoAccessLine =
    "Other apps\u2019 playback isn\u2019t shown: allow notification access under Settings \u203A Music \u203A Behaviour \u203A Show other players."

/**
 * The standing footnote: the device's own equalizer, where it has one, is another effect that Mimir can neither see nor switch off, so it
 * applies as well as this one. It names no vendor and no app .
 */
internal const val EqualizerVendorLine = "The device\u2019s own equalizer, if it has one, applies as well."

/** "Plays on: Speaker", or null when the platform has not said where media goes (nothing is guessed). */
internal fun equalizerOutputDeviceLine(output: MediaOutput?): String? =
    MediaOutputText.label(output)?.let { "$EqualizerOutputDeviceLabel: $it" }

/**
 * What is playing, from any app, as the media-session reader the Activity tile uses already knows it ([MusicControlState]): "Playing: Mimir
 * · Track", "Paused: Spotify · Song". The player that is sounding is the one named (both, when both sound); with none sounding, the one
 * that sounded last. Without notification access other apps are invisible, so one line says that, after Mimir's own music if it is there.
 * A title the session did not name is left out, never replaced with a guess.
 *
 * Mimir's own track's format ([equalizerHeaderLines]) follows its entry directly, and only while that entry is shown: Your music stays
 * present while it is paused for another app, so a format held for it must never read as the format of the app that is named instead.
 *
 * The theme bed ([MusicControlState.bed], the default source) is named "Mimir's music" while it sounds, and has no format. Held silent (another
 * app, a call, the music hours) it is not named at all, and the block then says what is true of what remains, "Nothing is playing." included.
 */
internal fun equalizerNowPlayingLines(music: MusicControlState): List<EqualizerInfoLine> {
    data class Heard(val app: String, val title: String?, val playing: Boolean, val format: TrackFormat? = null)
    // Your music or the theme bed: one source at a time, so at most one of them is Mimir's own entry.
    val own = music.yours?.let { Heard(EqualizerOwnAppLabel, it.title?.takeIf(String::isNotBlank), it.isPlaying, it.format) }
        ?: music.bed?.takeIf { it.isPlaying }?.let { Heard(EqualizerThemeAppLabel, it.title.takeIf(String::isNotBlank), playing = true) }
    val other = music.other?.let { player ->
        Heard(
            app = player.appLabel?.takeIf(String::isNotBlank) ?: EqualizerOtherAppLabel,
            title = player.title.takeIf { player.titleIsAuthored }?.takeIf(String::isNotBlank),
            playing = player.isPlaying,
        )
    }
    val heard = listOfNotNull(own, other)
    val shown = when {
        heard.isEmpty() -> emptyList()
        heard.any { it.playing } -> heard.filter { it.playing }
        else -> listOf(if (music.lastPlayed == MusicSource.OTHER_PLAYER && other != null) other else own ?: other!!)
    }
    return buildList {
        shown.forEach { who ->
            add(EqualizerInfoLine("${if (who.playing) "Playing" else "Paused"}: ${listOfNotNull(who.app, who.title).joinToString(" · ")}"))
            // Only Mimir's own entry has a format to show, and it is shown right under it: no other app's entry gets one.
            equalizerHeaderLines(who.format).forEach { add(EqualizerInfoLine(it)) }
        }
        if (!music.otherAccess) add(EqualizerInfoLine(EqualizerNoAccessLine)) else if (shown.isEmpty()) add(EqualizerInfoLine(EqualizerNothingPlayingLine))
    }
}

/** Why the global engine is not applying the curve, in the player's words (null when it is, or was never asked for). */
internal fun equalizerNotActiveReason(status: SystemEqStatus): String? = when (status) {
    SystemEqStatus.NO_CONTROL -> "Not active: another app controls the system equalizer."
    SystemEqStatus.UNAVAILABLE -> "Not active: Android refused the system equalizer."
    else -> null
}

/**
 * Which Equalizer is active, and what it reaches: "Equalizer: On · Rock · all sound on this device" while the global engine has the mix; with
 * the engine out of reach (another app holds it, or Android refused it) the first line says only music on this device (Mimir's own player,
 * the fallback) is shaped right now, and a second, brighter line says why it is not active. Never claims more than the engine read back as
 * doing ([equalizerReach]).
 */
internal fun equalizerStatusLines(settings: EqualizerSettings, systemEq: SystemEqualizerState): List<EqualizerInfoLine> {
    val preset = EqualizerPresets.label(settings.presetId)
    return when (equalizerReach(settings.enabled, systemEq.status)) {
        EqualizerReach.OFF -> listOf(EqualizerInfoLine("Equalizer: Off"))
        EqualizerReach.ALL_SOUND -> listOf(EqualizerInfoLine("Equalizer: On \u00B7 $preset \u00B7 all sound on this device"))
        EqualizerReach.STARTING -> listOf(EqualizerInfoLine("Equalizer: On \u00B7 $preset \u00B7 starting"))
        EqualizerReach.MIMIR_ONLY -> listOf(
            EqualizerInfoLine("Equalizer: On \u00B7 $preset \u00B7 music on this device only right now"),
            EqualizerInfoLine(equalizerNotActiveReason(systemEq.status).orEmpty(), EqualizerInfoTone.ATTENTION),
        )
    }
}

/**
 * The header's information block, under the heading: where media sound goes ([output]), what is playing from any app ([music]; Mimir's own
 * track's format sits right under its entry), which Equalizer is active ([settings], [systemEq]), and the standing footnote about the
 * device's own equalizer. A format is never shown for another app: [equalizerHeaderLines] is Mimir's own track's, and only [equalizerNowPlayingLines]
 * places it, under Mimir's own entry.
 */
internal fun equalizerInfoLines(
    output: MediaOutput?,
    music: MusicControlState,
    settings: EqualizerSettings,
    systemEq: SystemEqualizerState,
): List<EqualizerInfoLine> = buildList {
    equalizerOutputDeviceLine(output)?.let { add(EqualizerInfoLine(it)) }
    addAll(equalizerNowPlayingLines(music))
    addAll(equalizerStatusLines(settings, systemEq))
    add(EqualizerInfoLine(EqualizerVendorLine, EqualizerInfoTone.QUIET))
}

// ---- what the foot says ----

/**
 * Whether the curve is audible anywhere, so its sliders mean something: the one switch is on. With the global engine out of reach the
 * curve still shapes Mimir's own music ([EqualizerReach.MIMIR_ONLY]), so On always means somebody hears it.
 */
internal fun equalizerEditorLive(settings: EqualizerSettings): Boolean = settings.enabled

/**
 * The hint on the foot of the card, or null (the seat is held empty, so a hint appearing moves nothing): X resets the focused slider;
 * X on the switch asks Android again while the global engine was refused.
 */
internal fun equalizerFootHint(
    settings: EqualizerSettings,
    systemEq: SystemEqualizerState,
    focused: EqualizerEditorControl?,
): EqualizerFootHint? = when {
    equalizerEditorLive(settings) && focused?.isSlider == true -> EqualizerResetFootHint
    focused == EqualizerEditorControl.TOGGLE && settings.enabled && systemEq.status == SystemEqStatus.UNAVAILABLE -> EqualizerRetryFootHint
    else -> null
}

// ---- the gain scale: 25 one-dB positions, 0 dB in the middle ----

internal object EqGain {
    const val Positions = 25
    const val Steps = Positions - 2
    const val CentreFraction = 0.5f
    private const val Span = (Positions - 1).toFloat()

    fun positionFor(fraction: Float): Int =
        if (fraction.isNaN()) Positions / 2 else (fraction.coerceIn(0f, 1f) * Span).roundToInt()

    fun fractionFor(position: Int): Float = position.coerceIn(0, Positions - 1) / Span

    fun dbFor(fraction: Float): Float = positionFor(fraction) + Equalizer.MinDb

    fun fractionForDb(db: Float): Float = fractionFor((EqualizerSettings.clampDb(db) - Equalizer.MinDb).roundToInt())
}

// ---- reach and order ----

/**
 * The controls the controller can reach, in order. While nothing is equalizing ([live] false, see [equalizerEditorLive]) only the
 * switch: the rest are dimmed and skipped.
 */
internal fun equalizerEditorReachable(live: Boolean): List<EqualizerEditorControl> =
    if (live) EqualizerEditorControl.entries else listOf(EqualizerEditorControl.TOGGLE)

/** Where UP and DOWN go from [from]: the next reachable control, no wrap. From nothing, the switch. */
internal fun equalizerEditorNeighbor(
    live: Boolean,
    from: EqualizerEditorControl?,
    action: ControllerAction,
): EqualizerEditorControl? {
    val rows = equalizerEditorReachable(live)
    val at = rows.indexOf(from)
    if (at < 0) return rows.first()
    return when (action) {
        ControllerAction.NAV_DOWN -> rows.getOrNull(at + 1)
        ControllerAction.NAV_UP -> rows.getOrNull(at - 1)
        else -> null
    }
}

// ---- the preset cycle ----

/** The order the preset row walks: every preset, then Custom. */
internal val EqualizerPresetCycle: List<EqualizerPresetId> = EqualizerPresets.all.map { it.id } + EqualizerPresetId.CUSTOM

/** The preset [direction] (-1 or 1) steps from [current]; the cycle wraps, so it is never at an end. */
internal fun equalizerCyclePreset(current: EqualizerPresetId, direction: Int): EqualizerPresetId {
    val at = EqualizerPresetCycle.indexOf(current).coerceAtLeast(0)
    val size = EqualizerPresetCycle.size
    return EqualizerPresetCycle[((at + direction) % size + size) % size]
}

// ---- the engine seam and what a press does ----

/** What the editor reads and drives. [MimirEqualizerEditorEngine] in production; a fake in tests. */
internal interface EqualizerEditorEngine {
    val settings: kotlinx.coroutines.flow.StateFlow<EqualizerSettings>

    /** The global engine: what the effect is honestly doing about the switch. */
    val systemEq: kotlinx.coroutines.flow.StateFlow<SystemEqualizerState>

    /** The one switch: the global engine follows it (attached on, released off). */
    fun setEnabled(enabled: Boolean)

    /** Asks the platform for the global engine again, from scratch: the switch is already on and was refused. */
    fun retrySystemEq()
    fun setBand(index: Int, db: Float)
    fun setBass(db: Float)
    fun setTreble(db: Float)
    fun applyPreset(id: EqualizerPresetId)
}

internal object MimirEqualizerEditorEngine : EqualizerEditorEngine {
    override val settings get() = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.settings
    override val systemEq get() = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.systemEqualizer
    override fun setEnabled(enabled: Boolean) = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.setEnabled(enabled)
    override fun retrySystemEq() = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.retrySystemEqualizer()
    override fun setBand(index: Int, db: Float) = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.setBand(index, db)
    override fun setBass(db: Float) = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.setBass(db)
    override fun setTreble(db: Float) = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.setTreble(db)
    override fun applyPreset(id: EqualizerPresetId) = com.gruning.mimir.rewrite.core.audio.MimirEqualizer.applyPreset(id)
}

/** The gain a slider shows now. */
internal fun equalizerGainOf(settings: EqualizerSettings, control: EqualizerEditorControl): Float = when {
    control == EqualizerEditorControl.BASS -> settings.bassDb
    control == EqualizerEditorControl.TREBLE -> settings.trebleDb
    control.bandIndex != null -> settings.bandsDb[control.bandIndex]
    else -> 0f
}

/** Sets [control]'s gain (a slider control) to [db] through [engine]; any other control does nothing. */
internal fun equalizerSetGain(engine: EqualizerEditorEngine, control: EqualizerEditorControl, db: Float) {
    when {
        control == EqualizerEditorControl.BASS -> engine.setBass(db)
        control == EqualizerEditorControl.TREBLE -> engine.setTreble(db)
        control.bandIndex != null -> engine.setBand(control.bandIndex, db)
    }
}

/**
 * One gain step for LEFT/RIGHT: [direction] one dB, held in range. Returns whether the gain moved (an edge press moves nothing).
 */
internal fun equalizerStepGain(engine: EqualizerEditorEngine, control: EqualizerEditorControl, direction: Int): Boolean {
    if (!control.isSlider) return false
    val now = equalizerGainOf(engine.settings.value, control)
    val position = EqGain.positionFor(EqGain.fractionForDb(now)) + direction.coerceIn(-1, 1)
    val next = EqGain.dbFor(EqGain.fractionFor(position))
    if (next == now) return false
    equalizerSetGain(engine, control, next)
    return true
}
