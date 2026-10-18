package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
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
 * The equalizer editor's in-place motion on a paused clock : a preset moves many bands at once and they
 * glide to it, while one band moved by a step or a finger snaps (direct manipulation never lags the hand), and reduced motion snaps
 * both. Intermediate frame asserted, then the settled end state. A state write under `autoAdvance = false` needs
 * [Snapshot.sendApplyNotifications].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerEditorMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val flat = EqualizerSettings(enabled = true)
    private val rock = flat.withPreset(EqualizerPresetId.ROCK)

    private var settings by mutableStateOf(flat)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun install() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                EqualizerEditorOverlay(
                    settings = settings,
                    focused = null,
                    authority = null,
                    onToggle = {},
                    onPresetStep = {},
                    onGainRequested = { _, _ -> },
                    onRequestFocus = {},
                    onDismiss = {},
                )
            }
        }
        // Past the editor's own entry.
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun write(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    /** The slider's drawn position, as its range semantics report it. */
    private fun shown(control: EqualizerEditorControl): Float =
        compose.onNode(
            hasContentDescription(equalizerControlLabel(control)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    /** The slider that a flat-to-rock change moves furthest. */
    private val widest: EqualizerEditorControl =
        EqualizerEditorControl.entries.filter { it.isSlider }
            .maxByOrNull { kotlin.math.abs(equalizerGainOf(rock, it) - equalizerGainOf(flat, it)) }!!

    @Test
    fun `a preset glides the bands to it on the overlay clock, and they settle exactly on the preset`() {
        assertTrue("rock moves many bands", equalizerBandsMoved(flat, rock) > 1)
        install()
        val start = shown(widest)
        write { settings = rock }
        compose.mainClock.advanceTimeBy(80)
        val mid = shown(widest)
        compose.mainClock.advanceTimeBy(1_000)
        val end = shown(widest)
        assertEquals("the settled position is the preset's", EqGain.fractionForDb(equalizerGainOf(rock, widest)), end, 0.05f)
        assertTrue("it moved ($start -> $end)", kotlin.math.abs(end - start) > 0.05f)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        assertTrue("mid-glide is strictly between ($start, $mid, $end)", mid > lo + 0.001f && mid < hi - 0.001f)
    }

    @Test
    fun `one band moved by a step or a finger snaps, with no glide`() {
        install()
        val band = EqualizerEditorControl.BAND_3
        val moved = flat.withBand(3, 6f)
        assertEquals(1, equalizerBandsMoved(flat, moved))
        write { settings = moved }
        compose.mainClock.advanceTimeBy(16)
        assertEquals("already at the new value one frame later", EqGain.fractionForDb(6f), shown(band), 0.05f)
    }

    @Test
    fun `reduced motion snaps a preset`() {
        setAnimatorScale(0f)
        try {
            install()
            write { settings = rock }
            compose.mainClock.advanceTimeBy(16)
            assertEquals(EqGain.fractionForDb(equalizerGainOf(rock, widest)), shown(widest), 0.05f)
        } finally {
            setAnimatorScale(1f)
        }
    }

    @Test
    fun `the curve morph is the two responses in proportion, and a new length stands`() {
        val from = floatArrayOf(0f, 0f, 0f, 0f)
        val to = floatArrayOf(4f, 8f, -4f, 0f)
        assertEquals(listOf(2f, 4f, -2f, 0f), lerpCurve(from, to, 0.5f).toList())
        assertEquals(from.toList(), lerpCurve(from, to, 0f).toList())
        assertEquals("at 1 it is the target itself", to.toList(), lerpCurve(from, to, 1f).toList())
        assertEquals(listOf(1f, 2f), lerpCurve(floatArrayOf(0f), floatArrayOf(1f, 2f), 0.5f).toList())
        // The card is still the one card, through the glide.
        install()
        write { settings = rock }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag(EqualizerEditorCardTestTag, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `switching the equalizer off dims the controls on the overlay clock and on brightens them, and reduced motion jumps`() {
        var enabled by mutableStateOf(true)
        var shown = 1f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val dim by utilityDisabledAlpha(enabled)
            shown = dim
            androidx.compose.material3.Text("probe")
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("on is full", 1f, shown, 0f)

        write { enabled = false }
        compose.mainClock.advanceTimeBy(80)
        val dimming = shown
        assertTrue("mid-dim is strictly between 0.48 and 1 ($dimming)", dimming > 0.49f && dimming < 0.99f)
        compose.mainClock.advanceTimeBy(600)
        assertEquals("off settles on the dim", 0.48f, shown, 0.001f)

        write { enabled = true }
        compose.mainClock.advanceTimeBy(80)
        val brightening = shown
        assertTrue("mid-brighten is strictly between ($brightening)", brightening > 0.49f && brightening < 0.99f)
        compose.mainClock.advanceTimeBy(600)
        assertEquals("on settles at full", 1f, shown, 0.001f)
    }

    @Test
    fun `reduced motion jumps the dim`() {
        setAnimatorScale(0f)
        try {
            var enabled by mutableStateOf(true)
            var shown = 1f
            compose.mainClock.autoAdvance = false
            compose.setContent {
                val dim by utilityDisabledAlpha(enabled)
                shown = dim
                androidx.compose.material3.Text("probe")
            }
            compose.mainClock.advanceTimeByFrame()
            write { enabled = false }
            compose.mainClock.advanceTimeBy(48)
            assertEquals(0.48f, shown, 0.001f)
        } finally {
            setAnimatorScale(1f)
        }
    }
}
