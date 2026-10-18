package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-42 (audit 2026-10-14, B-23): the Tome rename plate's glass is keyed on the rename session, not on the folder, so a close plays out on the
 * progress the open ran up. Keyed on the folder, the close changed the glass's identity (folder -> none), which is a fresh progress at 0: the plate
 * and the lattice's blur cut on the frame the rename closed.
 *
 * On the real [CompanionSurface] at the Thor Bottom's size: "+" makes a Tome and opens the plate on it ; the plate's own outside
 * tap closes it. One frame after that tap the plate is still composed, dissolving, and it is gone once its OverlayMs leave has run. The wiring
 * this replaced loses the plate on that first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionRenameGlassHoldTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val widthDp = 1240f / density369
    private val heightDp = 1080f / density369

    private var payload by mutableStateOf(
        CompanionPayload(
            mode = CompanionMode.HOME,
            statsLabel = "3 tiles",
            homeLanes = listOf(
                CompanionHomeLane(
                    id = "emulation",
                    title = "Emulation",
                    hint = "",
                    tiles = (1..3).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") },
                ),
            ),
        ),
    )
    private var tomes = 0

    private fun createTome(): String {
        val id = "tome.${++tomes}"
        val lane = payload.homeLanes.first()
        payload = payload.copy(homeLanes = listOf(lane.copy(tiles = lane.tiles + CompanionHomeTile(id, CompanionHomeTileKind.FOLDER, "Book"))))
        return id
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    CompanionSurface(
                        payload = payload,
                        cursorId = "p1",
                        restoredPage = 0,
                        onAppTap = { _, _ -> },
                        onWorkspaceDrag = { _: Float -> 0f },
                        onWorkspaceDragEnd = { _: Float -> false },
                        onWorkspaceDragCancel = {},
                        onCreateFolder = { createTome() },
                        onRenameFolder = { _, _ -> },
                        showActivitySwipeHint = false,
                        monotonicUptimeMillis = { compose.mainClock.currentTime },
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        frames(2_000L)
    }

    private fun frames(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun plateShowing(): Boolean = compose.onAllNodes(hasTestTag(CompanionRenamePlateTag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `closing the rename plate leaves it dissolving on the session's own glass, then gone`() {
        mount()
        compose.onNodeWithTag(CompanionCreateTomeFaceTag, useUnmergedTree = true).performClick()
        frames(2_000L)
        assertTrue("fixture: the plate opened on the new Tome", plateShowing())

        // The plate's own outside tap, clear of the keyboard centred on it.
        compose.onNodeWithTag(CompanionRenamePlateTag, useUnmergedTree = true).performTouchInput { click(Offset(6f, 6f)) }
        frames(16L)
        assertTrue("one frame after the close the plate is still composed, dissolving on the glass the open ran up", plateShowing())
        frames(MimirMotion.OverlayMs / 2L)
        assertTrue("half-way through its leave it is still there", plateShowing())
        frames(MimirMotion.OverlayMs + 200L)
        assertFalse("and gone once its leave has run", plateShowing())
    }
}
