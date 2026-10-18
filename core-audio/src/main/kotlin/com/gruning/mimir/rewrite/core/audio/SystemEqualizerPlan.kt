package com.gruning.mimir.rewrite.core.audio

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One band of the system equalizer's pre-EQ stage. A DynamicsProcessing band runs from the band below's cutoff (the first from 0 Hz)
 * up to [cutoffHz], at [gainDb] throughout.
 */
internal data class SystemEqBand(val cutoffHz: Float, val gainDb: Float)

/**
 * The output stage's limiter: a hard ceiling under full scale, so a boost the preamp did not foresee (two bands adding where they
 * overlap, a loud peak) is held at [thresholdDb] and never reaches the mixer's clip.
 */
internal data class SystemEqLimiter(
    val attackMs: Float,
    val releaseMs: Float,
    val ratio: Float,
    val thresholdDb: Float,
    val postGainDb: Float,
)

/**
 * Everything the system equalizer asks of the DynamicsProcessing effect: the pre-EQ [bands], the [inputGainDb] preamp in front of
 * them, and the [limiter] after them. Plain numbers, so the mapping from Mimir's curve is tested on the JVM and the effect is a
 * fake in the behaviour tests.
 */
internal data class SystemEqPlan(
    val bands: List<SystemEqBand>,
    val inputGainDb: Float,
    val limiter: SystemEqLimiter,
)

/**
 * Mimir's equalizer curve ([EqualizerSettings]) as a [SystemEqPlan].
 *
 * **Bands.** The ten ISO bands of the in-app equalizer are the ten pre-EQ bands, in the same order. Band `i` ends at the geometric
 * midpoint between its centre and the next band's (the first starts at 0 Hz, the last ends at [TopHz]), so each centre sits in the
 * middle of its band on a log axis.
 *
 * **Gains.** Band `i` takes the in-app curve's combined response at its centre ([EqualizerMath], the arithmetic the editor's curve
 * preview draws): the band's own gain plus what its neighbours' overlap and the bass and treble shelves add there. A plan made from
 * the bare slider values would drop the shelves and the overlap and sound lighter than the curve on the sheet.
 *
 * **Preamp.** [SystemEqPlan.inputGainDb] is the same headroom the in-app equalizer takes off ([EqualizerSettings.effectiveHeadroomDb],
 * the figure the Preset row prints), so a boost makes the mix quieter instead of clipping. The limiter is the net under it.
 *
 * The in-app switch ([EqualizerSettings.enabled]) is not read: the system equalizer applies the curve whenever it is on.
 */
internal object SystemEqPlans {
    /** The mixer is stereo; DynamicsProcessing is configured per channel and both get the same plan. */
    const val ChannelCount = 2

    /** The top of the last band: the top of the audible range. */
    const val TopHz = 20_000f

    /** No band is asked for more than twice a slider's reach: overlap and a shelf can stack past a single slider's twelve. */
    const val MaxGainDb = 24f

    val Limiter = SystemEqLimiter(attackMs = 1f, releaseMs = 60f, ratio = 10f, thresholdDb = -1f, postGainDb = 0f)

    fun forCurve(settings: EqualizerSettings): SystemEqPlan {
        val centres = Equalizer.BandFrequenciesHz
        val coefficients = EqualizerMath.coefficients(settings, EqualizerMath.ReferenceRateHz)
        val bands = centres.mapIndexed { index, centre ->
            val cutoff = if (index == centres.lastIndex) TopHz else sqrt(centre.toDouble() * centres[index + 1]).toFloat()
            val gain = EqualizerMath.responseDb(coefficients, EqualizerMath.ReferenceRateHz, centre.toDouble()).toFloat()
            SystemEqBand(cutoffHz = cutoff, gainDb = tidy(gain.coerceIn(-MaxGainDb, MaxGainDb)))
        }
        val headroom = settings.copy(enabled = true).effectiveHeadroomDb
        return SystemEqPlan(bands = bands, inputGainDb = tidy(-headroom), limiter = Limiter)
    }

    /** Hundredths of a dB, and never a negative zero (it goes through an Int): a flat curve is the same plan whichever way it was reached. */
    private fun tidy(db: Float): Float = (db * 100f).roundToInt() / 100f
}
