package com.gruning.mimir.rewrite.core.audio

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mimir's equalizer curve as the DynamicsProcessing plan: which bands, at what gains, behind what preamp, under what limiter. Pure
 * numbers, so the mapping is checked here and the platform effect is only ever a fake.
 */
class SystemEqualizerPlanTest {
    private fun plan(settings: EqualizerSettings) = SystemEqPlans.forCurve(settings)

    private fun gains(settings: EqualizerSettings) = plan(settings).bands.map { it.gainDb }

    @Test
    fun `the ten in-app bands are the ten pre-EQ bands, each ending halfway to the next on a log axis`() {
        val bands = plan(EqualizerSettings()).bands
        assertEquals(Equalizer.BandCount, bands.size)
        val centres = Equalizer.BandFrequenciesHz
        for (i in 0 until centres.lastIndex) {
            val midpoint = sqrt(centres[i].toDouble() * centres[i + 1]).toFloat()
            assertEquals("band $i ends at the midpoint to ${centres[i + 1]} Hz", midpoint, bands[i].cutoffHz, 0.01f)
            assertTrue("band $i holds its own centre", bands[i].cutoffHz > centres[i])
            assertTrue("and stops before the next centre", bands[i].cutoffHz < centres[i + 1])
        }
        assertEquals("the last band runs to the top of the audible range", SystemEqPlans.TopHz, bands.last().cutoffHz, 0f)
        assertEquals("cutoffs ascend", bands.map { it.cutoffHz }.sorted(), bands.map { it.cutoffHz })
    }

    @Test
    fun `a flat curve is a plan of zeros with no preamp, the same whichever way it was reached`() {
        val flat = plan(EqualizerSettings())
        assertTrue(flat.bands.all { it.gainDb == 0f })
        assertEquals(0f, flat.inputGainDb, 0f)
        assertEquals("a reset curve is the fresh one", flat, plan(EqualizerSettings().withPreset(EqualizerPresetId.ROCK).reset()))
        // The bit pattern matters to equality: a negative zero would make an unchanged flat curve look changed.
        assertEquals(0, java.lang.Float.floatToRawIntBits(flat.inputGainDb))
        assertTrue(flat.bands.all { java.lang.Float.floatToRawIntBits(it.gainDb) == 0 })
    }

    @Test
    fun `one band raised is that band's gain at its centre, with its neighbours lifted by the overlap and the far bands left alone`() {
        val oneKilohertz = Equalizer.BandFrequenciesHz.indexOf(1_000)
        val g = gains(EqualizerSettings().withBand(oneKilohertz, 9f))
        assertEquals("the raised band takes its slider's gain", 9f, g[oneKilohertz], 0.01f)
        assertTrue("500 Hz is lifted by the skirt", g[oneKilohertz - 1] > 1f && g[oneKilohertz - 1] < 9f)
        assertTrue("2 kHz is lifted by the skirt", g[oneKilohertz + 1] > 1f && g[oneKilohertz + 1] < 9f)
        assertEquals("the lowest band is far away", 0f, g[0], 0.5f)
        assertEquals("and so is the highest", 0f, g.last(), 0.5f)
    }

    @Test
    fun `the bass and treble shelves are folded into the bands they cover`() {
        val bass = gains(EqualizerSettings().withBass(6f))
        assertTrue("31 Hz carries the bass shelf: ${bass[0]}", bass[0] in 4.5f..6.5f)
        assertTrue("62 Hz carries the bass shelf: ${bass[1]}", bass[1] in 4.5f..6.5f)
        assertEquals("16 kHz is not in it", 0f, bass.last(), 0.5f)

        val treble = gains(EqualizerSettings().withTreble(-6f))
        assertTrue("16 kHz carries the treble shelf: ${treble.last()}", treble.last() in -6.5f..-4f)
        assertEquals("31 Hz is not in it", 0f, treble[0], 0.5f)
    }

    @Test
    fun `every band is the in-app curve at its centre, the arithmetic the editor's preview draws`() {
        val rock = EqualizerSettings().withPreset(EqualizerPresetId.ROCK).withBass(2f).withTreble(-3f)
        val coefficients = EqualizerMath.coefficients(rock, EqualizerMath.ReferenceRateHz)
        val expected = Equalizer.BandFrequenciesHz.map {
            EqualizerMath.responseDb(coefficients, EqualizerMath.ReferenceRateHz, it.toDouble()).toFloat()
        }
        gains(rock).forEachIndexed { i, gain -> assertEquals("band $i", expected[i], gain, 0.01f) }
    }

    @Test
    fun `the in-app switch is not read, so the system equalizer applies the curve whether or not Your music's own is on`() {
        val rock = EqualizerSettings().withPreset(EqualizerPresetId.ROCK)
        assertEquals(plan(rock.withEnabled(true)), plan(rock.withEnabled(false)))
    }

    @Test
    fun `a boost takes the same headroom off as the Preset row prints, and a cut-only curve needs none`() {
        val bassBoost = EqualizerSettings().withPreset(EqualizerPresetId.BASS_BOOST)
        val headroom = bassBoost.copy(enabled = true).effectiveHeadroomDb
        assertTrue("a boost needs headroom", headroom > 3f)
        assertEquals(-headroom, plan(bassBoost).inputGainDb, 0.01f)
        assertEquals("however the switch stands", plan(bassBoost).inputGainDb, plan(bassBoost.withEnabled(true)).inputGainDb, 0f)

        val cutOnly = EqualizerSettings().withBand(5, -8f)
        assertEquals(0f, plan(cutOnly).inputGainDb, 0f)
    }

    @Test
    fun `the limiter is configured as a ceiling under full scale`() {
        val limiter = plan(EqualizerSettings().withPreset(EqualizerPresetId.LOUDNESS)).limiter
        assertEquals("the plan carries the one limiter", SystemEqPlans.Limiter, limiter)
        assertTrue("the ceiling is below 0 dBFS: ${limiter.thresholdDb}", limiter.thresholdDb < 0f && limiter.thresholdDb >= -3f)
        assertTrue("it catches fast and lets go slowly", limiter.attackMs in 0.1f..5f && limiter.releaseMs > limiter.attackMs * 10f)
        assertTrue("a high ratio is a limiter, not a compressor", limiter.ratio >= 10f)
        assertEquals("no makeup gain that would undo the ceiling", 0f, limiter.postGainDb, 0f)
    }

    @Test
    fun `the strongest curve the sliders can make stays inside the band range and the preamp covers it`() {
        val loudest = (0 until Equalizer.BandCount).fold(EqualizerSettings()) { s, i -> s.withBand(i, Equalizer.MaxDb) }
            .withBass(Equalizer.MaxDb).withTreble(Equalizer.MaxDb)
        val p = plan(loudest)
        assertTrue(p.bands.all { abs(it.gainDb) <= SystemEqPlans.MaxGainDb })
        assertTrue("the preamp comes down by the boost", p.inputGainDb < -10f)
        val quietest = (0 until Equalizer.BandCount).fold(EqualizerSettings()) { s, i -> s.withBand(i, Equalizer.MinDb) }
            .withBass(Equalizer.MinDb).withTreble(Equalizer.MinDb)
        assertTrue(plan(quietest).bands.all { abs(it.gainDb) <= SystemEqPlans.MaxGainDb })
        assertFalse("a cut needs no preamp", plan(quietest).inputGainDb < 0f)
    }
}
