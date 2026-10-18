package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [rememberMimirFadeMount] is how a popover, a toast or a plate fades without cutting AND without reading its animated alpha in composition (observed: the popovers wrote
 * `if (open || menuAlpha > 0.001f)`, which recomposed the whole host on every frame of the fade). `mounted` is the composition read and changes twice per showing; `alpha`
 * is the draw read and moves every frame. The proof is a recomposition count against the number of frames the alpha moved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirFadeMountTest {

    @get:Rule
    val compose = createComposeRule()

    private var visible by mutableStateOf(false)
    private var reduced by mutableStateOf(false)
    private var restsWhole by mutableStateOf(false)
    private var bodies = 0
    private var fade: MimirFadeMount? = null

    private val mounted: Boolean get() = fade!!.mounted
    private val alpha: Float get() = fade!!.alpha

    @Composable
    private fun Host() {
        val held = rememberMimirFadeMount(visible = visible, reducedMotion = reduced, restsWhole = restsWhole)
        fade = held
        bodies += 1
        // The composition reads `mounted` and nothing else of the fade; the layer lambda reads `alpha`.
        if (visible || held.mounted) Box(Modifier.size(10.dp).graphicsLayer { this.alpha = held.alpha })
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Host() }
        step(0L)
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** [frames] frames of 16 ms, the alpha read after each. */
    private fun frames(frames: Int): List<Float> = List(frames) { step(16L); alpha }

    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    private val fadeFrames get() = MimirMotion.OverlayMs / 16 + 3

    @Test
    fun `a host starts unmounted and at nothing, mounts as it is asked for, and unmounts when its fade out has finished`() {
        mount()
        assertFalse("not mounted before it is asked for", mounted)
        assertEquals(0f, alpha, 0f)

        write { visible = true }
        step(16L)
        assertTrue("mounted from the frame it opens", mounted)
        frames(fadeFrames)
        assertEquals("whole once it has faded in", 1f, alpha, 0.001f)

        write { visible = false }
        step(16L)
        assertTrue("still mounted while it fades out", mounted)
        frames(MimirMotion.OverlayMs / 32)
        assertTrue("and halfway through", mounted)
        frames(fadeFrames)
        assertFalse("unmounted once the fade out has finished", mounted)
        assertEquals(0f, alpha, 0.001f)
    }

    @Test
    fun `composition sees the fade twice per showing, while the alpha moves on every frame`() {
        mount()
        val before = bodies
        write { visible = true }
        step(16L)
        val rising = frames(fadeFrames)
        write { visible = false }
        step(16L)
        val falling = frames(fadeFrames)
        val recomposed = bodies - before
        println("FADE-MOUNT host bodies run: $recomposed; alpha moved over ${rising.zipWithNext().count { (a, b) -> b != a }} rising and ${falling.zipWithNext().count { (a, b) -> b != a }} falling frames")
        assertTrue("the alpha rose on most frames: $rising", rising.zipWithNext().count { (a, b) -> b > a } >= 8)
        assertTrue("and fell on most frames: $falling", falling.zipWithNext().count { (a, b) -> b < a } >= 8)
        // open: the visible flip, then the mounted flip; close: the visible flip, then the unmount. Nothing per frame.
        assertTrue("the host recomposed $recomposed times for a whole showing, not once per frame", recomposed <= 6)
    }

    @Test
    fun `under reduced motion the host snaps in and out, with no fade`() {
        write { reduced = true }
        mount()
        write { visible = true }
        step(16L)
        step(16L)
        assertEquals("whole in a frame or two", 1f, alpha, 0.001f)
        write { visible = false }
        step(16L)
        step(16L)
        assertFalse("and gone in a frame or two", mounted)
    }

    @Test
    fun `a host that is already showing when first composed starts whole`() {
        write { visible = true }
        mount()
        assertTrue(mounted)
        assertEquals("nothing to fade in from", 1f, alpha, 0.001f)
    }

    /**
     * A host whose content enters on its own clock (the Activity pickers, folded into this primitive in the art-fade pass) adds no second fade on the way in: it is whole the frame it
     * is asked for, dissolves on the way out, and is whole again once it has unmounted, so a reopen is not faded under the panel's entry.
     */
    @Test
    fun `a host that rests whole is whole the frame it is asked for, fades out, and is whole again once unmounted`() {
        write { restsWhole = true }
        mount()
        assertFalse(mounted)
        assertEquals("whole while nothing is showing, so nothing is faded in on the way in", 1f, alpha, 0f)

        write { visible = true }
        step(16L)
        assertTrue(mounted)
        assertEquals("whole on the first frame it is asked for", 1f, alpha, 0f)
        assertTrue("and every frame after", frames(fadeFrames).all { it == 1f })

        write { visible = false }
        step(16L)
        val leaving = frames(fadeFrames)
        println("FADE-MOUNT rests-whole leaving alpha per 16 ms: $leaving")
        assertTrue("it dissolves on the way out: $leaving", leaving.any { it in 0.05f..0.95f })
        assertFalse("unmounted once it has gone", mounted)
        assertEquals("and whole again while nothing is composed", 1f, alpha, 0f)

        write { visible = true }
        step(0L)
        assertEquals("a reopen is whole from its first frame, not faded in a second time", 1f, alpha, 0f)
    }

    @Test
    fun `a host that rests whole and is asked for again while it leaves fades back from where it is`() {
        write { restsWhole = true; visible = true }
        mount()
        write { visible = false }
        step(16L)
        val falling = frames(MimirMotion.OverlayMs / 32)
        val low = falling.last()
        assertTrue("part way out: $falling", low in 0.05f..0.95f)
        write { visible = true }
        step(16L)
        // The ask lands a frame after the write, so "where it is" is read when the effect has restarted, not when it was written.
        val restart = alpha
        assertTrue("still part way when it was asked for again: $restart (was $low)", restart in 0.02f..low + 0.001f)
        val back = frames(fadeFrames)
        assertTrue("it rises again from where it was, never through nothing: $back", back.first() >= restart - 0.001f && back.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, back.last(), 0.001f)
        assertTrue(mounted)
    }
}
