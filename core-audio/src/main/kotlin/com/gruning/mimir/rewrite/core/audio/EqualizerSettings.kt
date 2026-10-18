package com.gruning.mimir.rewrite.core.audio

/** The presets of the equalizer, and [CUSTOM] for the hand-set curve. The order is the order a picker lists them in. */
enum class EqualizerPresetId { FLAT, BASS_BOOST, TREBLE_BOOST, VOCAL, ROCK, POP, CLASSICAL, ELECTRONIC, LOUDNESS, CUSTOM }

/**
 * One preset: the ten band gains in dB (lowest band first, see [Equalizer.BandFrequenciesHz]). A preset sets the bands only: the
 * bass and treble shelves are the player's own tone controls and a preset leaves them where they are.
 */
data class EqualizerPreset(
    val id: EqualizerPresetId,
    val label: String,
    val bandsDb: List<Float>,
)

/** The equalizer's fixed layout: ten ISO octave peaking bands and a bass and a treble shelf, each within [MinDb]..[MaxDb]. */
object Equalizer {
    val BandFrequenciesHz: List<Int> = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    val BandCount: Int get() = BandFrequenciesHz.size
    const val BassShelfHz = 100
    const val TrebleShelfHz = 10_000
    const val MaxDb = 12f
    const val MinDb = -12f
}

/**
 * Every preset's band gains, in one table. Editing a band leaves a preset: the result is [EqualizerPresetId.CUSTOM]. Gains are
 * kept small: the automatic preamp ([EqualizerSettings.effectiveHeadroomDb]) takes the loudest boost off the whole signal, so the
 * bigger the boost the quieter the music.
 */
object EqualizerPresets {
    //                                                                       31   62  125  250  500   1k   2k   4k   8k  16k
    private val table: List<EqualizerPreset> = listOf(
        EqualizerPreset(EqualizerPresetId.FLAT, "Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqualizerPreset(EqualizerPresetId.BASS_BOOST, "Bass boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqualizerPreset(EqualizerPresetId.TREBLE_BOOST, "Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f)),
        EqualizerPreset(EqualizerPresetId.VOCAL, "Vocal", listOf(-3f, -2f, -1f, 1f, 3f, 4f, 4f, 3f, 1f, 0f)),
        EqualizerPreset(EqualizerPresetId.ROCK, "Rock", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
        EqualizerPreset(EqualizerPresetId.POP, "Pop", listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, -1f, -1f)),
        EqualizerPreset(EqualizerPresetId.CLASSICAL, "Classical", listOf(0f, 0f, 0f, 0f, 0f, 0f, -2f, -3f, -3f, -4f)),
        EqualizerPreset(EqualizerPresetId.ELECTRONIC, "Electronic", listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f)),
        // Loudness was 6/4/2 bands with a +2 bass and a +1 treble shelf on top; the shelves are the player's now, so their share
        // moved into the nearest bands and the curve is as before.
        EqualizerPreset(EqualizerPresetId.LOUDNESS, "Loudness", listOf(8f, 6f, 3f, 0f, 0f, 0f, 0f, 1f, 4f, 5f)),
    )

    /** The presets a picker offers, in order; [EqualizerPresetId.CUSTOM] is the hand-set curve, not an entry here. */
    val all: List<EqualizerPreset> get() = table

    fun byId(id: EqualizerPresetId): EqualizerPreset? = table.firstOrNull { it.id == id }

    /** The label a player reads for [id], [EqualizerPresetId.CUSTOM] included ("Custom"). */
    fun label(id: EqualizerPresetId): String = byId(id)?.label ?: "Custom"
}

/**
 * The player's equalizer choice. [bandsDb] has [Equalizer.BandCount] entries, lowest band first; every gain is within
 * [Equalizer.MinDb]..[Equalizer.MaxDb]. All the `with` functions return a new value and keep those invariants.
 *
 * The bands and the two shelves are separate controls. A preset ([withPreset]) sets the ten bands and leaves the shelves; editing
 * a band ([withBand]) makes the preset [EqualizerPresetId.CUSTOM]; the shelves ([withBass], [withTreble]) are tone controls on top
 * of whichever preset is chosen and do not change it.
 *
 * @property enabled the Equalizer's one switch (since 2026-10-10 there is no second one): off passes all sound untouched, bit for bit;
 *  on shapes it with this curve, through the global engine ([SystemEqualizer]) where that has the mix, or in Mimir's own music player
 *  where it does not ([equalizerReach]).
 * @property bassDb the 100 Hz low shelf.
 * @property trebleDb the 10 kHz high shelf.
 * @property presetId the preset the bands came from, or [EqualizerPresetId.CUSTOM] once a band was edited.
 * @property customBandsDb the bands the player last set by hand, or null if none were. A preset never erases them: choosing
 *  [EqualizerPresetId.CUSTOM] again ([withPreset]) brings them back.
 */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bandsDb: List<Float> = FlatBands,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val presetId: EqualizerPresetId = EqualizerPresetId.FLAT,
    val customBandsDb: List<Float>? = null,
) {
    /** Every gain is zero: nothing would change. */
    val isFlat: Boolean get() = bassDb == 0f && trebleDb == 0f && bandsDb.all { it == 0f }

    /**
     * How far the signal is turned down to leave room for the loudest boost: the largest positive total gain of the curve, in
     * dB (the shelves and neighbouring bands add where they overlap), as a number zero or above. Zero while the equalizer is
     * off or flat. The player applies the same figure as an automatic preamp, so the curve cannot clip. The figure comes from a
     * frequency sweep: read it once per settings value, not per frame.
     */
    val effectiveHeadroomDb: Float
        get() = if (!enabled || isFlat) 0f else EqualizerMath.headroomDb(this, EqualizerMath.ReferenceRateHz).toFloat()

    fun withEnabled(on: Boolean): EqualizerSettings = copy(enabled = on)

    /**
     * What Your music's own player applies, given whether the global engine has claimed the whole mix: the same choice with its
     * switch off while it has ([systemEqualizerActive]), so the music is not equalized twice. With the engine gone, refused or out of
     * control the choice is applied as it stands: the player's own stage is the fallback that still shapes Mimir's music. Off is a
     * byte copy, so the bypass costs nothing and loses nothing; the choice itself, bands and all, is untouched.
     */
    fun forInPlayer(systemEqualizerActive: Boolean): EqualizerSettings =
        if (systemEqualizerActive && enabled) copy(enabled = false) else this

    /** Band [index] set to [db]; the preset becomes [EqualizerPresetId.CUSTOM] and the edited bands are the player's custom curve. */
    fun withBand(index: Int, db: Float): EqualizerSettings {
        if (index !in bandsDb.indices) return this
        val next = bandsDb.toMutableList().also { it[index] = clampDb(db) }
        return copy(bandsDb = next, presetId = EqualizerPresetId.CUSTOM, customBandsDb = next)
    }

    fun withBass(db: Float): EqualizerSettings = copy(bassDb = clampDb(db))

    fun withTreble(db: Float): EqualizerSettings = copy(trebleDb = clampDb(db))

    /**
     * The preset's bands ([enabled], the shelves and the custom curve are kept). [EqualizerPresetId.CUSTOM] brings back the
     * bands the player last set by hand; with none ever set, the current bands become the custom curve.
     */
    fun withPreset(id: EqualizerPresetId): EqualizerSettings {
        if (id == EqualizerPresetId.CUSTOM) {
            val custom = customBandsDb ?: bandsDb
            return copy(bandsDb = custom, presetId = EqualizerPresetId.CUSTOM, customBandsDb = custom)
        }
        val preset = EqualizerPresets.byId(id) ?: return this
        return copy(bandsDb = preset.bandsDb, presetId = id)
    }

    /** Flat again, shelves included; [enabled] and the custom curve are kept. */
    fun reset(): EqualizerSettings =
        copy(bandsDb = FlatBands, bassDb = 0f, trebleDb = 0f, presetId = EqualizerPresetId.FLAT)

    companion object {
        val FlatBands: List<Float> = List(10) { 0f }

        fun clampDb(db: Float): Float = if (db.isNaN()) 0f else db.coerceIn(Equalizer.MinDb, Equalizer.MaxDb)
    }
}

/**
 * The stored shape of [EqualizerSettings]: one string, so a choice is written and read whole:
 * `1;enabled;preset;bass;treble;b0,...,b9;c0,...,c9` (the last part is the custom bands, empty when there are none; a string
 * without it is read as having none). Anything that is not that (a downgrade, a hand-edited file) is the default, never an error.
 */
internal object EqualizerSettingsCodec {
    private const val Version = "1"

    fun encode(settings: EqualizerSettings): String =
        listOf(
            Version,
            settings.enabled.toString(),
            settings.presetId.name,
            settings.bassDb.toString(),
            settings.trebleDb.toString(),
            settings.bandsDb.joinToString(","),
            settings.customBandsDb?.joinToString(",").orEmpty(),
        ).joinToString(";")

    fun decode(raw: String?): EqualizerSettings {
        val parts = raw?.split(';') ?: return EqualizerSettings()
        if ((parts.size != 6 && parts.size != 7) || parts[0] != Version) return EqualizerSettings()
        val enabled = parts[1].toBooleanStrictOrNull() ?: return EqualizerSettings()
        val bass = parts[3].toFloatOrNull() ?: return EqualizerSettings()
        val treble = parts[4].toFloatOrNull() ?: return EqualizerSettings()
        val bands = parts[5].split(',').map { it.toFloatOrNull() ?: return EqualizerSettings() }
        if (bands.size != Equalizer.BandCount) return EqualizerSettings()
        // A custom curve that does not parse is no custom curve; it never spoils the rest of the choice.
        val custom = parts.getOrNull(6)?.takeIf { it.isNotEmpty() }?.split(',')?.map { it.toFloatOrNull() }
            ?.takeIf { list -> list.size == Equalizer.BandCount && list.all { it != null } }
            ?.map { EqualizerSettings.clampDb(it!!) }
        val stored = EqualizerSettings(
            enabled = enabled,
            bandsDb = bands.map(EqualizerSettings::clampDb),
            bassDb = EqualizerSettings.clampDb(bass),
            trebleDb = EqualizerSettings.clampDb(treble),
            presetId = EqualizerPresetId.entries.firstOrNull { it.name == parts[2] } ?: EqualizerPresetId.CUSTOM,
            customBandsDb = custom,
        )
        // A preset id whose table no longer matches the stored bands (a changed table, a hand edit) is a custom curve.
        val preset = EqualizerPresets.byId(stored.presetId)
        val isCustom = preset == null || preset.bandsDb != stored.bandsDb
        return if (isCustom) {
            stored.copy(presetId = EqualizerPresetId.CUSTOM, customBandsDb = stored.customBandsDb ?: stored.bandsDb)
        } else {
            stored
        }
    }
}

/**
 * What the one Equalizer is doing for the sound right now: its switch ([EqualizerSettings.enabled]) read against what the global
 * engine honestly reports ([SystemEqStatus]). The table is the whole "who applies the curve" rule, and it never has two answers:
 *
 * | switch | engine status               | reach            | global engine | Mimir's own stage |
 * |--------|-----------------------------|------------------|---------------|-------------------|
 * | off    | any                         | [OFF]            | released      | off               |
 * | on     | [SystemEqStatus.ON]         | [ALL_SOUND]      | applies       | off               |
 * | on     | NO_CONTROL, UNAVAILABLE     | [MIMIR_ONLY]     | not applying  | applies           |
 * | on     | OFF (not answered yet)      | [STARTING]       | being made    | applies, until the engine claims the mix |
 *
 * Mimir's own stage is the fallback: with the engine refused or out of control the Equalizer still shapes Mimir's music, and the
 * editor says that is all it reaches right now.
 */
enum class EqualizerReach {
    /** The switch is off: nothing shapes any sound. */
    OFF,

    /** The global engine has the mix: your music, music apps and most games are shaped; Mimir's own stage stands down. */
    ALL_SOUND,

    /** The switch is on and the engine is not applying (another app holds it, or Android refused it): only Mimir's own music is shaped. */
    MIMIR_ONLY,

    /** The switch is on and the engine has not answered yet (a moment after the switch, or at process start). */
    STARTING,
    ;

    /** Mimir's own float stage applies the curve in this state. */
    val ownStageApplies: Boolean get() = this == MIMIR_ONLY || this == STARTING

    /** The global engine applies the curve in this state. */
    val engineApplies: Boolean get() = this == ALL_SOUND
}

/** The reach of the one Equalizer: [enabled] is its switch, [engine] what the global engine reports ([SystemEqualizerState.status]). */
fun equalizerReach(enabled: Boolean, engine: SystemEqStatus): EqualizerReach = when {
    !enabled -> EqualizerReach.OFF
    engine == SystemEqStatus.ON -> EqualizerReach.ALL_SOUND
    engine == SystemEqStatus.NO_CONTROL || engine == SystemEqStatus.UNAVAILABLE -> EqualizerReach.MIMIR_ONLY
    else -> EqualizerReach.STARTING
}

/**
 * The one-time merge of the two switches the Equalizer used to have (2026-10-10): the music-only Equalizer switch, stored inside the
 * [EqualizerSettings] string, and the System EQ switch, stored beside it as a boolean. Either one on is the one Equalizer on, with
 * the curve and preset already stored; both off stays off. Pure: [AudioPreferences] reads the legacy key, calls this, writes the
 * result and removes the key in one edit, so the merge runs once and a second run finds nothing to do.
 */
internal object EqualizerSwitchMigration {
    /** [legacySystemSwitch] is the stored System EQ switch, or null when the key is not there (nothing to migrate). */
    fun merge(stored: EqualizerSettings, legacySystemSwitch: Boolean?): EqualizerSettings =
        if (legacySystemSwitch == true && !stored.enabled) stored.copy(enabled = true) else stored
}
