package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * The one Equalizer, for the settings and the music panel to drive. Every call is a change of [settings], which is stored at
 * once and reaches the global engine and the player's next buffer; none needs a Context or a thread. Its one switch shapes all normal
 * sound through the global engine (see [SystemEqualizer]); while the engine has the mix Your music's own stage is bypassed so the
 * music is not equalized twice, and when the engine cannot have it that stage shapes Mimir's music alone ([equalizerReach]).
 *
 * The curve is ten bands (see [Equalizer.BandFrequenciesHz]) and a bass and a treble shelf, each within
 * [Equalizer.MinDb]..[Equalizer.MaxDb]. Turning it on and off, and moving a gain, glide over 20 ms, so they make no click. While
 * it is on, the player turns the music down by [EqualizerSettings.effectiveHeadroomDb] so a boost cannot clip.
 */
object MimirEqualizer {
    /** The choice: on or off, the gains, and which preset they came from (or [EqualizerPresetId.CUSTOM]). */
    val settings: StateFlow<EqualizerSettings> get() = AudioPreferences.equalizerChoice

    /** The global engine: what the effect is honestly doing about the switch (applying only when it read back as on). */
    val systemEqualizer: StateFlow<SystemEqualizerState> get() = SystemEqualizer.state

    /** Asks the platform for the global engine again, from scratch: for a switch that is on and was refused. */
    fun retrySystemEqualizer() = SystemEqualizer.retry()

    /** The presets to list, in order. */
    val presets: List<EqualizerPreset> get() = EqualizerPresets.all

    /** The turn-down for the current curve, in dB (zero or above; zero while off or flat). Same as `settings.value.effectiveHeadroomDb`. */
    val effectiveHeadroomDb: Float get() = settings.value.effectiveHeadroomDb

    /** The Equalizer's one switch: the global engine is attached while it is on and released when it is off. */
    fun setEnabled(enabled: Boolean) = AudioPreferences.updateEqualizer { it.withEnabled(enabled) }

    /** Sets band [index] (0 is the lowest, 31 Hz) to [db]; the choice becomes [EqualizerPresetId.CUSTOM]. An index outside the bands does nothing. */
    fun setBand(index: Int, db: Float) = AudioPreferences.updateEqualizer { it.withBand(index, db) }

    fun setBass(db: Float) = AudioPreferences.updateEqualizer { it.withBass(db) }

    fun setTreble(db: Float) = AudioPreferences.updateEqualizer { it.withTreble(db) }

    /**
     * Takes a preset's ten bands: the on/off switch, the bass and treble shelves and the player's custom curve are kept.
     * [EqualizerPresetId.CUSTOM] brings back the bands the player last set by hand.
     */
    fun applyPreset(id: EqualizerPresetId) = AudioPreferences.updateEqualizer { it.withPreset(id) }

    /** Every gain, shelves included, back to zero ([EqualizerPresetId.FLAT]); the on/off switch is kept. */
    fun reset() = AudioPreferences.updateEqualizer { it.reset() }

    /** The curve preview's range: [CurveLowHz] to [CurveHighHz], log-spaced. */
    const val CurveLowHz = 20f
    const val CurveHighHz = 20_000f

    /**
     * The combined response of [settings] (ten bands and both shelves, the on/off switch not read) in dB at [points] log-spaced
     * frequencies from [CurveLowHz] to [CurveHighHz], first point lowest. The arithmetic of the filters the player runs, for a
     * read-only preview; compute it once per settings value.
     */
    fun responseCurveDb(settings: EqualizerSettings, points: Int = 96): FloatArray =
        EqualizerMath.curveDb(settings, points, CurveLowHz.toDouble(), CurveHighHz.toDouble())
}
