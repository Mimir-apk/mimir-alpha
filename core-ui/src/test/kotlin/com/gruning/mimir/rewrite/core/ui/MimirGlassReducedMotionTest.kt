package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * reduced motion is exempt from the no-cut standard, but the scene glass must be CONSISTENT with the
 * panels it sits behind. A host that collapses its tweens to 0 cuts its panel in and out; a glass that still ramps over
 * [MimirMotion.OverlayMs] beside it is the one moving thing on the screen. `rememberMimirGlassProgress` therefore has a reduced-motion
 * branch: the glass is a settled STATE (full when requested, clear when not), in both directions, after the frame beat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirGlassReducedMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(false)
    private val reduced = mutableStateOf(true)
    private var glass: () -> Float = { 0f }

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            glass = rememberMimirGlassProgress(visible = visible.value, reducedMotion = reduced.value)
            Box(Modifier.size(10.dp))
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun write(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    private fun frames(count: Int, each: () -> Unit = {}) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        each()
    }

    @Test
    fun `reduced motion opens the glass as a settled state - no frame between clear and full`() {
        host()
        write { visible.value = true }
        val seen = mutableListOf<Float>()
        frames(30) { seen += glass() }
        assertTrue("the glass reached full: $seen", seen.last() == 1f)
        assertTrue(
            "no partial frame: every value is 0 or 1, not a ramp: ${seen.distinct()}",
            seen.all { it == 0f || it == 1f },
        )
    }

    @Test
    fun `reduced motion clears the glass the same way - settled, not a 180 ms ramp`() {
        host()
        write { visible.value = true }
        frames(10)
        assertEquals(1f, glass(), 0f)
        write { visible.value = false }
        val seen = mutableListOf<Float>()
        frames(30) { seen += glass() }
        assertEquals(0f, seen.last(), 0f)
        assertTrue("no partial frame on the way out: ${seen.distinct()}", seen.all { it == 0f || it == 1f })
    }

    @Test
    fun `without reduced motion the glass still ramps on the overlay clock (the default is unchanged)`() {
        reduced.value = false
        host()
        write { visible.value = true }
        val seen = mutableListOf<Float>()
        frames(30) { seen += glass() }
        assertTrue("a ramp passes through partial values: ${seen.distinct().size}", seen.any { it in 0.05f..0.95f })
        assertEquals(1f, seen.last(), 0f)
    }
}
