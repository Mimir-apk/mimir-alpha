package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [carouselTouchDrag] on its own, the way every carousel mounts it: on the row, with the tiles' own taps beneath it and a
 * vertical scroll above it. It takes nothing until the travel leaves the touch slop mostly along the row, reports the
 * travel beyond the slop so the row does not jump, ends exactly once with the finger's speed, cancels a tile's tap by
 * consuming, and lets everything else go to whoever else is listening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarouselTouchDragTest {

    @get:Rule
    val compose = createComposeRule()

    private var starts = 0
    private val drags = mutableListOf<Float>()
    private val ends = mutableListOf<Float>()
    private var taps = 0
    private var verticalTravel = 0f
    private var slopPx = 0f
    private val enabled = mutableStateOf(true)

    /** The identity of what the drag moves; a new value is a row that was made afresh. */
    private val identity = mutableStateOf(0)

    /** The tile sits in the top-left of the row: (0,0) to (200,100). */
    private val onTile = Offset(100f, 50f)

    private fun host(region: ((Offset) -> Boolean)? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            slopPx = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier
                    .size(400.dp, 300.dp)
                    .draggable(rememberDraggableState { delta -> verticalTravel += delta }, Orientation.Vertical),
            ) {
                Box(
                    Modifier
                        .size(400.dp, 200.dp)
                        .carouselTouchDrag(
                            gestureKey = identity.value,
                            enabled = enabled.value,
                            startRegion = region,
                            onDragStart = { starts++ },
                            onDrag = { dx -> drags += dx },
                            onDragEnd = { velocity -> ends += velocity },
                        ),
                ) {
                    Box(Modifier.size(200.dp, 100.dp).clickable { taps++ })
                }
            }
        }
        compose.touchFrames(2)
    }

    @Test
    fun `a tap reaches the tile and starts no drag`() {
        host()

        compose.touchTap(onTile)

        assertEquals("the tile's own tap", 1, taps)
        assertEquals(0, starts)
        assertTrue(drags.isEmpty())
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `a horizontal drag starts once, reports the travel beyond the slop, ends once, and cancels the tile's tap`() {
        host()

        compose.touchDown(onTile)
        repeat(10) { compose.touchMove(-10f) }
        compose.touchRest()
        compose.touchUp()

        assertEquals(1, starts)
        assertEquals("the row follows the finger less the slop", -(100f - slopPx), drags.sum(), 0.01f)
        assertEquals("one end", 1, ends.size)
        assertTrue("a finger at rest lets go with no speed: ${ends.single()}", abs(ends.single()) < 50f)
        assertEquals("a drag is not a tap", 0, taps)
    }

    @Test
    fun `a flick lets go with the speed of the finger, leftwards negative`() {
        host()

        compose.touchDown(onTile)
        repeat(5) { compose.touchMove(-40f) }
        compose.touchUp()

        assertEquals(1, ends.size)
        // 40 px a 16 ms frame is 2500 px a second; the framework's tracker reports its own estimate of it, which is what a
        // row settles with. It must be fast and leftwards, and far above the near-zero of a finger at rest.
        assertTrue("the speed at the lift: ${ends.single()}", ends.single() < -600f && ends.single() > -4_000f)
    }

    @Test
    fun `a drag to the right reports positive travel and speed`() {
        host()

        compose.touchDown(onTile)
        repeat(5) { compose.touchMove(40f) }
        compose.touchUp()

        assertEquals(1, starts)
        assertTrue("travel: ${drags.sum()}", drags.sum() > 100f)
        assertTrue("speed: ${ends.single()}", ends.single() > 600f && ends.single() < 4_000f)
    }

    @Test
    fun `a vertical drag is never taken and the scroll above gets it whole`() {
        host()

        compose.touchDown(onTile)
        repeat(10) { compose.touchMove(0f, -10f) }
        compose.touchUp()

        assertEquals(0, starts)
        assertTrue(drags.isEmpty())
        assertTrue(ends.isEmpty())
        assertTrue("the scroll got it: $verticalTravel", verticalTravel < -50f)
    }

    @Test
    fun `a gesture that leaves the slop vertical stays the scroll's even if it turns sideways`() {
        host()

        compose.touchDown(onTile)
        compose.touchMove(0f, -(slopPx + 6f))
        repeat(5) { compose.touchMove(-40f) }
        compose.touchUp()

        assertEquals("locked to the scroll at the slop, so a later turn is not ours", 0, starts)
        assertTrue(drags.isEmpty())
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `a disabled drag takes nothing`() {
        host()
        enabled.value = false
        compose.touchFrames(2)

        compose.touchDown(onTile)
        repeat(10) { compose.touchMove(-10f) }
        compose.touchUp()

        assertEquals(0, starts)
        assertTrue(drags.isEmpty())
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `a touch that begins outside the start region takes nothing, and one inside takes it`() {
        host(region = { down -> down.y < 60f })

        compose.touchDown(Offset(100f, 150f))
        repeat(10) { compose.touchMove(-10f) }
        compose.touchUp()
        assertEquals("outside the region", 0, starts)

        compose.touchDown(onTile)
        repeat(10) { compose.touchMove(-10f) }
        compose.touchUp()
        assertEquals("inside the region", 1, starts)
        assertEquals(1, ends.size)
    }

    @Test
    fun `a gesture whose row is replaced under the finger is abandoned, with nothing reported for the rest of the touch`() {
        host()

        compose.touchDown(onTile)
        repeat(4) { compose.touchMove(-10f) }
        assertEquals(1, starts)
        val seen = drags.size

        identity.value = identity.value + 1
        compose.touchFrames(2)
        repeat(4) { compose.touchMove(-10f) }
        compose.touchUp()

        assertEquals("no more travel is reported", seen, drags.size)
        assertTrue("and no end, because the callbacks now belong to another row", ends.isEmpty())
        assertEquals("nor a second start from the same finger", 1, starts)
    }

    @Test
    fun `a gesture that is lost ends once, so the row settles instead of staying held`() {
        host()

        compose.touchDown(onTile)
        repeat(6) { compose.touchMove(-10f) }
        assertEquals(1, starts)
        compose.touchCancel()

        assertEquals("the end is reported exactly once", 1, ends.size)
    }

    private fun abs(value: Float): Float = kotlin.math.abs(value)
}
