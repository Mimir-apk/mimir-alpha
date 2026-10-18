package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The equalizer's choice: the preset table, what editing does to it, and the stored string. */
class EqualizerSettingsTest {
    @Test
    fun `the preset table lists every preset once, with ten bands inside the range, and Flat is zero`() {
        val presets = EqualizerPresets.all
        assertEquals(
            listOf("Flat", "Bass boost", "Treble boost", "Vocal", "Rock", "Pop", "Classical", "Electronic", "Loudness"),
            presets.map { it.label },
        )
        assertEquals(EqualizerPresetId.entries - EqualizerPresetId.CUSTOM, presets.map { it.id })
        presets.forEach { preset ->
            assertEquals(preset.label, Equalizer.BandCount, preset.bandsDb.size)
            preset.bandsDb.forEach {
                assertTrue("${preset.label} $it", it in Equalizer.MinDb..Equalizer.MaxDb)
            }
        }
        val flat = EqualizerPresets.byId(EqualizerPresetId.FLAT)!!
        assertTrue(flat.bandsDb.all { it == 0f })
        assertNull(EqualizerPresets.byId(EqualizerPresetId.CUSTOM))
    }

    @Test
    fun `the bands are the ten ISO octaves`() {
        assertEquals(listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000), Equalizer.BandFrequenciesHz)
    }

    @Test
    fun `the default is off and flat, with no headroom taken`() {
        val s = EqualizerSettings()
        assertEquals(false, s.enabled)
        assertTrue(s.isFlat)
        assertEquals(EqualizerPresetId.FLAT, s.presetId)
        assertEquals(0f, s.effectiveHeadroomDb, 0f)
    }

    @Test
    fun `applying a preset takes its ten bands and leaves the switch and both shelves`() {
        EqualizerPresets.all.forEach { preset ->
            val s = EqualizerSettings(enabled = true, bassDb = 3f, trebleDb = -2f).withPreset(preset.id)
            assertEquals(preset.bandsDb, s.bandsDb)
            assertEquals(3f, s.bassDb, 0f)
            assertEquals(-2f, s.trebleDb, 0f)
            assertEquals(preset.id, s.presetId)
            assertTrue(s.enabled)
        }
        assertEquals(false, EqualizerSettings().withPreset(EqualizerPresetId.ROCK).enabled)
    }

    @Test
    fun `editing a band leaves the preset for Custom and the shelves are tone controls that leave it alone`() {
        val rock = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK)
        assertEquals(EqualizerPresetId.CUSTOM, rock.withBand(3, 2f).presetId)
        assertEquals(EqualizerPresetId.ROCK, rock.withBass(1f).presetId)
        assertEquals(EqualizerPresetId.ROCK, rock.withTreble(1f).presetId)
        assertEquals(1f, rock.withBass(1f).bassDb, 0f)
        assertEquals(EqualizerPresetId.ROCK, rock.withEnabled(false).presetId)
        assertEquals(rock.bandsDb[0], rock.withBand(3, 2f).bandsDb[0], 0f)
        assertEquals(2f, rock.withBand(3, 2f).bandsDb[3], 0f)
    }

    @Test
    fun `a preset never erases the custom curve, and choosing Custom brings it back`() {
        val mine = EqualizerSettings(enabled = true).withBand(0, 7f).withBand(5, -4f)
        assertEquals(EqualizerPresetId.CUSTOM, mine.presetId)
        val onRock = mine.withPreset(EqualizerPresetId.ROCK)
        assertEquals(EqualizerPresets.byId(EqualizerPresetId.ROCK)!!.bandsDb, onRock.bandsDb)
        assertEquals(mine.bandsDb, onRock.customBandsDb)
        val back = onRock.withPreset(EqualizerPresetId.CUSTOM)
        assertEquals(EqualizerPresetId.CUSTOM, back.presetId)
        assertEquals(mine.bandsDb, back.bandsDb)
        // Walking through other presets and editing nothing keeps it too.
        val walked = EqualizerPresets.all.fold(mine) { s, p -> s.withPreset(p.id) }.withPreset(EqualizerPresetId.CUSTOM)
        assertEquals(mine.bandsDb, walked.bandsDb)
        // Reset is flat but leaves the custom curve for later.
        assertEquals(mine.bandsDb, mine.reset().customBandsDb)
    }

    @Test
    fun `choosing Custom with none ever set makes the current bands the custom curve`() {
        val onVocal = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.VOCAL)
        val custom = onVocal.withPreset(EqualizerPresetId.CUSTOM)
        assertEquals(EqualizerPresetId.CUSTOM, custom.presetId)
        assertEquals(onVocal.bandsDb, custom.bandsDb)
        assertEquals(onVocal.bandsDb, custom.customBandsDb)
        assertEquals(EqualizerPresets.label(EqualizerPresetId.CUSTOM), "Custom")
        assertEquals("Rock", EqualizerPresets.label(EqualizerPresetId.ROCK))
    }

    @Test
    fun `gains are held inside plus and minus twelve and a bad index changes nothing`() {
        val s = EqualizerSettings()
        assertEquals(12f, s.withBand(0, 99f).bandsDb[0], 0f)
        assertEquals(-12f, s.withBand(9, -99f).bandsDb[9], 0f)
        assertEquals(12f, s.withBass(13f).bassDb, 0f)
        assertEquals(-12f, s.withTreble(-13f).trebleDb, 0f)
        assertEquals(0f, s.withBand(1, Float.NaN).bandsDb[1], 0f)
        assertEquals(s, s.withBand(10, 3f))
        assertEquals(s, s.withBand(-1, 3f))
    }

    @Test
    fun `reset is flat again and keeps the switch`() {
        val s = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ELECTRONIC).withBass(4f).reset()
        assertEquals(EqualizerSettings(enabled = true), s)
    }

    @Test
    fun `headroom is the loudest total boost, zero for cuts and off`() {
        val one = EqualizerSettings(enabled = true).withBand(5, 6f)
        assertEquals(6f, one.effectiveHeadroomDb, 0.1f)
        assertEquals(0f, EqualizerSettings(enabled = true).withBand(5, -6f).effectiveHeadroomDb, 0.001f)
        assertEquals(0f, one.withEnabled(false).effectiveHeadroomDb, 0f)
        // Neighbouring bands add where they overlap, so two +6 dB neighbours need more than 6 dB.
        val two = one.withBand(6, 6f)
        assertTrue(two.effectiveHeadroomDb > one.effectiveHeadroomDb + 0.5f)
        val loudest = EqualizerSettings(enabled = true, bandsDb = List(10) { 12f }, bassDb = 12f, trebleDb = 12f)
        assertTrue(loudest.effectiveHeadroomDb > 12f)
    }

    @Test
    fun `every preset and a custom curve round-trip through the stored string`() {
        val custom = EqualizerSettings(enabled = true).withBand(2, -7.5f).withBand(9, 11.25f).withBass(-3.5f).withTreble(4.25f)
        val choices = EqualizerPresets.all.map { EqualizerSettings(enabled = true).withPreset(it.id) } +
            EqualizerSettings(enabled = false).withPreset(EqualizerPresetId.VOCAL) + custom
        choices.forEach { assertEquals(it, EqualizerSettingsCodec.decode(EqualizerSettingsCodec.encode(it))) }
        assertEquals(EqualizerPresetId.CUSTOM, EqualizerSettingsCodec.decode(EqualizerSettingsCodec.encode(custom)).presetId)
    }

    @Test
    fun `anything that is not a stored choice is the default`() {
        val default = EqualizerSettings()
        listOf(
            null,
            "",
            "garbage",
            "2;true;FLAT;0.0;0.0;0,0,0,0,0,0,0,0,0,0",
            "1;maybe;FLAT;0.0;0.0;0,0,0,0,0,0,0,0,0,0",
            "1;true;FLAT;x;0.0;0,0,0,0,0,0,0,0,0,0",
            "1;true;FLAT;0.0;0.0;0,0,0",
            "1;true;FLAT;0.0;0.0;0,0,0,0,0,0,0,0,0,q",
            "1;true;FLAT;0.0;0.0",
        ).forEach { assertEquals(it, default, EqualizerSettingsCodec.decode(it)) }
    }

    @Test
    fun `stored gains are clamped, an unknown preset is Custom, and a preset that no longer matches is Custom`() {
        val wild = EqualizerSettingsCodec.decode("1;true;NO_SUCH;40.0;-40.0;99,0,0,0,0,0,0,0,0,-99")
        assertEquals(EqualizerPresetId.CUSTOM, wild.presetId)
        assertEquals(12f, wild.bandsDb[0], 0f)
        assertEquals(-12f, wild.bandsDb[9], 0f)
        assertEquals(12f, wild.bassDb, 0f)
        assertEquals(-12f, wild.trebleDb, 0f)
        val edited = EqualizerSettingsCodec.decode("1;true;ROCK;0.0;0.0;0,0,0,0,0,0,0,0,0,1")
        assertEquals(EqualizerPresetId.CUSTOM, edited.presetId)
        assertNotEquals(EqualizerPresetId.ROCK, edited.presetId)
    }

    @Test
    fun `a preset with the player's own shelves reloads as that preset, and the custom curve survives the string`() {
        val rock = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK).withBass(3f).withTreble(-1.5f)
        assertEquals(EqualizerPresetId.ROCK, EqualizerSettingsCodec.decode(EqualizerSettingsCodec.encode(rock)).presetId)
        val withCustom = EqualizerSettings(enabled = true).withBand(4, 5f).withPreset(EqualizerPresetId.POP)
        val back = EqualizerSettingsCodec.decode(EqualizerSettingsCodec.encode(withCustom))
        assertEquals(withCustom, back)
        assertEquals(5f, back.customBandsDb!![4], 0f)
    }

    @Test
    fun `a stored choice from before the custom curve is read as having none, and a bad custom part is dropped`() {
        val flat = "0,0,0,0,0,0,0,0,0,0"
        val old = EqualizerSettingsCodec.decode("1;true;FLAT;0.0;0.0;$flat")
        assertEquals(EqualizerSettings(enabled = true), old)
        val bad = EqualizerSettingsCodec.decode("1;true;FLAT;0.0;0.0;$flat;1,2,x")
        assertEquals(EqualizerSettings(enabled = true), bad)
        val short = EqualizerSettingsCodec.decode("1;true;FLAT;0.0;0.0;$flat;1,2,3")
        assertEquals(EqualizerSettings(enabled = true), short)
    }

    @Test
    fun `the response curve is flat for a flat choice and shows a boost where the band is`() {
        val flat = MimirEqualizer.responseCurveDb(EqualizerSettings(enabled = true), 64)
        assertEquals(64, flat.size)
        assertTrue(flat.all { kotlin.math.abs(it) < 0.01f })
        val boosted = MimirEqualizer.responseCurveDb(EqualizerSettings(enabled = true).withBand(5, 6f), 128)
        val peak = boosted.indices.maxByOrNull { boosted[it] }!!
        val hz = MimirEqualizer.CurveLowHz * Math.pow((MimirEqualizer.CurveHighHz / MimirEqualizer.CurveLowHz).toDouble(), peak / 127.0).toFloat()
        assertTrue("peak near 1 kHz, at $hz", hz in 800f..1_250f)
        assertEquals(6f, boosted[peak], 0.2f)
    }
}
