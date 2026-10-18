package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptTimer
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scene host reports Home and Activity from ONE call site, so a switch between them is the same receipt composable getting a
 * different surface. Thor 2026-10-02 showed those edges as `from=none` / `to=none` / `transition=none` while `scene.glide` ran;
 * what they must say is pinned here with a real composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomUiSceneSurfaceReceiptTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = ArrayList<String>()
    private val log = BottomUiReceiptLog(
        sink = { line, _ -> lines += line },
        nowMs = { 0L },
        timer = BottomUiReceiptTimer { _, _ -> {} },
    )

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun aSurfaceReplacedThroughASceneTransitionNamesTheTransitionAndItsNeighbourOnBothEdges() {
        var surface by mutableStateOf(BottomSurface.LATTICE)
        var transition by mutableStateOf<String?>(null)
        var from by mutableStateOf<String?>(null)
        compose.setContent {
            BottomUiSurfaceReceipt(surface = surface, visible = true, transition = transition, display = 4, from = from, log = log)
        }
        compose.waitForIdle()
        assertEquals(listOf("ev=surface.enter surface=lattice display=4 from=none transition=none"), lines)

        // Lattice -> Activity: the scene glides.
        lines.clear()
        surface = BottomSurface.ACTIVITY
        transition = "scene.glide"
        from = BottomSurface.LATTICE
        settle()
        assertEquals(
            listOf(
                "ev=surface.exit surface=lattice display=4 to=activity transition=scene.glide",
                "ev=surface.enter surface=activity display=4 from=lattice transition=scene.glide",
            ),
            lines,
        )

        // Activity -> lattice: the way back glides too.
        lines.clear()
        surface = BottomSurface.LATTICE
        from = BottomSurface.ACTIVITY
        settle()
        assertEquals(
            listOf(
                "ev=surface.exit surface=activity display=4 to=lattice transition=scene.glide",
                "ev=surface.enter surface=lattice display=4 from=activity transition=scene.glide",
            ),
            lines,
        )
    }

    @Test
    fun aSurfaceTornDownWithItsHostHasNoSuccessorAndSaysNone() {
        var show by mutableStateOf(true)
        var transition by mutableStateOf<String?>(null)
        compose.setContent {
            if (show) BottomUiSurfaceReceipt(surface = BottomSurface.ACTIVITY, visible = true, transition = transition, display = 4, log = log)
        }
        compose.waitForIdle()
        // Even a transition name the surface last carried is not claimed by a plain teardown.
        transition = "scene.glide"
        settle()
        lines.clear()
        show = false
        settle()
        assertEquals(listOf("ev=surface.exit surface=activity display=4 to=none transition=none"), lines)
    }
}
