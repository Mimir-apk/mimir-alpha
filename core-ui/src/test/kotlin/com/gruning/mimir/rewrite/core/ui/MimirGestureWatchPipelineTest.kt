package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MimirGestureWatch] through the REAL Compose pointer pipeline in front of a real `draggable`, which reports a stop with zero velocity for a lift and for a system cancel
 * alike. A cancel's synthetic release is consumed when it reaches the watch's Initial pass; a real release is not (ui 1.7.3 `onCancelPointerInput`, read in the AAR's
 * bytecode). So a cancel is classified as one whichever of the stop and that release is seen first, and a lift in the same millisecond as the last move is still a lift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirGestureWatchPipelineTest {
    @get:Rule
    val compose = createComposeRule()

    private val watch = MimirGestureWatch()
    private val stops = mutableListOf<Boolean>()

    private fun mount() {
        compose.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .background(Color.DarkGray)
                    .testTag(Tag)
                    .mimirGestureWatch(watch)
                    .draggable(rememberDraggableState { }, Orientation.Horizontal, onDragStopped = { stops += watch.takenAway }),
            )
        }
    }

    @Test
    fun `a lift in the same millisecond as the last move is a lift`() {
        mount()
        compose.onNodeWithTag(Tag).performTouchInput {
            down(Offset(20f, 50f))
            moveTo(Offset(120f, 50f), delayMillis = 16L)
            moveTo(Offset(140f, 50f), delayMillis = 0L)
            up()
        }
        compose.waitForIdle()
        assertEquals("one stop", 1, stops.size)
        assertFalse("the stop saw a lift", stops.single())
        assertFalse("and the watch still reads a lift once everything has been delivered", watch.takenAway)
    }

    @Test
    fun `a system cancel after a drag is a cancel at the stop and stays one once its release has been delivered`() {
        mount()
        compose.onNodeWithTag(Tag).performTouchInput {
            down(Offset(20f, 50f))
            moveTo(Offset(120f, 50f), delayMillis = 16L)
            moveTo(Offset(160f, 50f), delayMillis = 16L)
            cancel()
        }
        compose.waitForIdle()
        assertEquals("one stop", 1, stops.size)
        assertTrue("the stop saw a cancel", stops.single())
        assertTrue("and the cancel's own release, delivered before or after the stop, did not turn it into a lift", watch.takenAway)
    }

    @Test
    fun `a cancel with no drag in progress is read as one`() {
        mount()
        compose.onNodeWithTag(Tag).performTouchInput {
            down(Offset(20f, 50f))
            cancel()
        }
        compose.waitForIdle()
        assertTrue("the release that follows a cancel is the cancel's", watch.takenAway)
    }

    private companion object {
        const val Tag = "watch-pipeline"
    }
}
