package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lattice's gesture reader against a state machine whose page geometry is a model version behind when the 180 ms long-press deadline fires (an 8 to 9 tile
 * landing rebuilds the geometry cache): the machine stays Pressed, with no tile to lift. The reader used to leave the gesture there without watching the release, so a
 * finger that went up was never reported and the machine stayed Pressed with no finger down. It now keeps reading the pointer, does not lift or consume, and reports the
 * release, which returns the machine to idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionShelfGestureReaderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun model(version: Long, vararg ids: String) =
        CompanionDragModel(version = version, tiles = ids.map { CompanionDragTileSnapshot(it, false) })

    private fun geometry(model: CompanionDragModel) = CompanionDragGeometry(
        generation = model.version,
        modelVersion = model.version,
        pageCount = 1,
        settledPage = 0,
        railBounds = DragRect(0f, 0f, 300f, 300f),
        slotRects = (0 until 9).associateWith { slot ->
            DragRect((slot % 3) * 100f, (slot / 3) * 100f, (slot % 3 + 1) * 100f, (slot / 3 + 1) * 100f)
        },
    )

    private fun frames(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun `a press past the long-press deadline with stale geometry is still released, and the machine returns to idle`() {
        val oldModel = model(1L, "a", "b", "c")
        val newModel = model(2L, "new", "a", "b", "c")
        val stale = geometry(oldModel)
        // The model is already ahead of the geometry the reader hands the machine.
        var machine = CompanionDragMachine.initial(oldModel)
        machine = CompanionDragStateMachine.reduce(machine, CompanionDragEvent.ModelChanged(newModel)).machine
        val events = mutableListOf<CompanionDragEvent>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(
                    Modifier
                        .requiredSize(300.dp)
                        .testTag("box")
                        .pointerInput(Unit) {
                            detectCompanionShelfGesture(
                                canStart = { true },
                                geometry = { stale },
                                phase = { machine.phase },
                                toRoot = { DragPoint(it.x, it.y) },
                                uptimeMillis = { compose.mainClock.currentTime },
                                dispatch = { event ->
                                    events += event
                                    machine = CompanionDragStateMachine.reduce(machine, event).machine
                                },
                            )
                        },
                ) {}
            }
        }
        frames(100L)
        val box = compose.onNodeWithTag("box")
        box.performTouchInput { down(Offset(50f, 50f)) }
        frames(60L)
        assertTrue("Pressed with no tile yet", machine.phase is CompanionDragPhase.Pressed)
        // Past the 180 ms deadline, finger still down: the machine is still Pressed (nothing to lift).
        frames(600L)
        println("W2C-READER after deadline events=${events.map { it::class.simpleName }} phase=${machine.phase::class.simpleName}")
        assertTrue("the deadline fired", events.any { it is CompanionDragEvent.LongPressElapsed })
        assertTrue("still Pressed, no lift", machine.phase is CompanionDragPhase.Pressed)
        box.performTouchInput { up() }
        frames(100L)
        println("W2C-READER released events=${events.map { it::class.simpleName }} phase=${machine.phase::class.simpleName}")
        assertTrue("the release was reported to the machine", events.any { it is CompanionDragEvent.Up })
        assertTrue("and the machine is idle again, not left Pressed with no finger down: ${machine.phase}", machine.phase is CompanionDragPhase.Idle)
    }
}
