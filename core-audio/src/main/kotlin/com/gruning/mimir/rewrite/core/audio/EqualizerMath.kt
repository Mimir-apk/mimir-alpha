package com.gruning.mimir.rewrite.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer's filter design, in double precision, with no Android and no Media3 in it: the processor and the headroom figure
 * the UI shows come from the same coefficients.
 *
 * The curve is twelve biquad sections in series: ten peaking bands (Q [PeakQ]), a 100 Hz low shelf and a 10 kHz high shelf
 * (slope 1), after the cookbook forms by R. Bristow-Johnson. [Coefficients] lays them out as `b0, b1, b2, a1, a2` per section,
 * already divided by `a0`. A section whose centre is at or above 0.45 of the sample rate, or whose gain is zero, is the identity.
 */
internal object EqualizerMath {
    const val SectionCount = 12
    const val CoefficientsPerSection = 5
    const val PeakQ = 1.41
    const val ReferenceRateHz = 48_000

    /** Sections above this fraction of the sample rate are left out: a peaking filter near Nyquist is warped past use. */
    private const val MaxCentreFraction = 0.45
    private const val SweepPoints = 480
    private const val SweepLowHz = 20.0

    /** One section as the processor and the sweep read it. */
    private enum class Kind { PEAKING, LOW_SHELF, HIGH_SHELF }

    /** The twelve gains of [settings] in section order: ten bands, then bass, then treble. */
    fun gainsDb(settings: EqualizerSettings): DoubleArray = DoubleArray(SectionCount) { index ->
        when {
            index < Equalizer.BandCount -> settings.bandsDb[index].toDouble()
            index == Equalizer.BandCount -> settings.bassDb.toDouble()
            else -> settings.trebleDb.toDouble()
        }
    }

    private fun centreHz(index: Int): Double = when {
        index < Equalizer.BandCount -> Equalizer.BandFrequenciesHz[index].toDouble()
        index == Equalizer.BandCount -> Equalizer.BassShelfHz.toDouble()
        else -> Equalizer.TrebleShelfHz.toDouble()
    }

    private fun kind(index: Int): Kind = when {
        index < Equalizer.BandCount -> Kind.PEAKING
        index == Equalizer.BandCount -> Kind.LOW_SHELF
        else -> Kind.HIGH_SHELF
    }

    /** The coefficients of every section of [settings] at [sampleRateHz]: [SectionCount] x [CoefficientsPerSection] numbers. */
    fun coefficients(settings: EqualizerSettings, sampleRateHz: Int): DoubleArray {
        val out = DoubleArray(SectionCount * CoefficientsPerSection)
        val gains = gainsDb(settings)
        for (section in 0 until SectionCount) design(out, section, gains[section], sampleRateHz)
        return out
    }

    fun identity(): DoubleArray = DoubleArray(SectionCount * CoefficientsPerSection).also { array ->
        for (section in 0 until SectionCount) array[section * CoefficientsPerSection] = 1.0
    }

    private fun design(out: DoubleArray, section: Int, gainDb: Double, sampleRateHz: Int) {
        val at = section * CoefficientsPerSection
        val f0 = centreHz(section)
        if (abs(gainDb) < 1e-9 || f0 >= MaxCentreFraction * sampleRateHz) {
            out[at] = 1.0
            out[at + 1] = 0.0
            out[at + 2] = 0.0
            out[at + 3] = 0.0
            out[at + 4] = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sampleRateHz
        val cosW = cos(w0)
        val sinW = sin(w0)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (kind(section)) {
            Kind.PEAKING -> {
                val alpha = sinW / (2.0 * PeakQ)
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW
                a2 = 1.0 - alpha / a
            }
            Kind.LOW_SHELF -> {
                val twoRootAAlpha = 2.0 * sqrt(a) * (sinW / 2.0 * sqrt(2.0))
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW + twoRootAAlpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW - twoRootAAlpha)
                a0 = (a + 1.0) + (a - 1.0) * cosW + twoRootAAlpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                a2 = (a + 1.0) + (a - 1.0) * cosW - twoRootAAlpha
            }
            Kind.HIGH_SHELF -> {
                val twoRootAAlpha = 2.0 * sqrt(a) * (sinW / 2.0 * sqrt(2.0))
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW + twoRootAAlpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW - twoRootAAlpha)
                a0 = (a + 1.0) - (a - 1.0) * cosW + twoRootAAlpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                a2 = (a + 1.0) - (a - 1.0) * cosW - twoRootAAlpha
            }
        }
        out[at] = b0 / a0
        out[at + 1] = b1 / a0
        out[at + 2] = b2 / a0
        out[at + 3] = a1 / a0
        out[at + 4] = a2 / a0
    }

    /** The whole chain's gain in dB at [frequencyHz], for coefficients made at [sampleRateHz]. */
    fun responseDb(coefficients: DoubleArray, sampleRateHz: Int, frequencyHz: Double): Double {
        val w = 2.0 * PI * frequencyHz / sampleRateHz
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        var sumDb = 0.0
        for (section in 0 until SectionCount) {
            val at = section * CoefficientsPerSection
            val numRe = coefficients[at] + coefficients[at + 1] * c1 + coefficients[at + 2] * c2
            val numIm = -(coefficients[at + 1] * s1 + coefficients[at + 2] * s2)
            val denRe = 1.0 + coefficients[at + 3] * c1 + coefficients[at + 4] * c2
            val denIm = -(coefficients[at + 3] * s1 + coefficients[at + 4] * s2)
            val num = numRe * numRe + numIm * numIm
            val den = denRe * denRe + denIm * denIm
            sumDb += 10.0 * kotlin.math.log10(num / den)
        }
        return sumDb
    }


    /**
     * The combined response of [settings]' gains (ten bands and both shelves; [EqualizerSettings.enabled] is not read) at
     * [points] log-spaced frequencies from [lowHz] to [highHz], in dB, for a 48 kHz stream. The editor's curve preview.
     */
    fun curveDb(settings: EqualizerSettings, points: Int, lowHz: Double, highHz: Double): FloatArray {
        val count = points.coerceAtLeast(2)
        val coefficients = coefficients(settings, ReferenceRateHz)
        val step = ln(highHz / lowHz) / (count - 1)
        return FloatArray(count) { i -> responseDb(coefficients, ReferenceRateHz, lowHz * kotlin.math.exp(step * i)).toFloat() }
    }

    /** The loudest positive gain of [coefficients] over 20 Hz to the top of the band, in dB; zero when nothing is boosted. */
    fun maxBoostDb(coefficients: DoubleArray, sampleRateHz: Int): Double {
        val top = minOf(20_000.0, 0.45 * sampleRateHz)
        if (top <= SweepLowHz) return 0.0
        val step = ln(top / SweepLowHz) / (SweepPoints - 1)
        var peak = 0.0
        for (i in 0 until SweepPoints) {
            peak = max(peak, responseDb(coefficients, sampleRateHz, SweepLowHz * kotlin.math.exp(step * i)))
        }
        return peak
    }

    /** What the automatic preamp turns the signal down by (a number zero or above) for [settings] at [sampleRateHz]. */
    fun headroomDb(settings: EqualizerSettings, sampleRateHz: Int): Double =
        maxBoostDb(coefficients(settings, sampleRateHz), sampleRateHz)

    /** The linear gain of [headroomDb] dB of attenuation. */
    fun preampLinear(headroomDb: Double): Double = 10.0.pow(-headroomDb / 20.0)
}
