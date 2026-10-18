package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Bottom scene self-handoff regression: the Activity page must not hand over to itself when the Bottom's
 * payload mode changes under it.
 *
 * On the Thor the payload went NOW_PLAYING -> HOME about 300 ms after the game ended (`MimirShelfDiagnostic ... effectiveMode=HOME cause=mode`
 * at 16:15:52.052), and that target change alone restarted the scene host: the Activity page dimmed to the stage over ExitMs and arrived again
 * over the lead and ShineMs (`scene.crossfade` cancelled at 161 ms and begun again, the orphan end 670 ms later). Under an override the target's
 * mode selects nothing, so the surface now holds the scene host's target while the override is up.
 *
 * On the real [CompanionSurface] at the Thor Bottom's size: with the Activity page shown, a payload mode change writes no `scene.crossfade` and
 * the page stays placed; a change of the scene identity (the page itself changing) still hands over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionActivitySceneModeFlipTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369
    private val tiles = (1..9).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private val lanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles))
    private var payload by mutableStateOf(CompanionPayload(mode = CompanionMode.HOME, statsLabel = "9 platforms", homeLanes = lanes))

    private var stripX by mutableFloatStateOf(0f)
    private var panActive by mutableStateOf(false)
    private var activityShown by mutableStateOf(false)
    private var snap by mutableStateOf(false)
    private var density = 0f

    private val activity: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().testTag(ActivityTag))
    }

    private fun mount() {
        ShadowLog.clear()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, 1f), LocalBottomUiDisplay provides DISPLAY) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionSurface(
                        payload = payload,
                        latticePayload = payload,
                        cursorId = "p1",
                        restoredPage = 0,
                        sceneOverride = activity.takeIf { activityShown },
                        sceneTransitionIdentity = activityShown,
                        neighbourScene = activity,
                        scenePanActive = panActive,
                        sceneStripX = { stripX },
                        sceneTransitionSnap = snap,
                        onWorkspaceDrag = { delta: Float ->
                            val next = (stripX + delta).coerceIn(0f, widthDp * density)
                            val used = next - stripX
                            stripX = next
                            if (used != 0f) panActive = true
                            delta - used
                        },
                        onWorkspaceDragEnd = { _: Float -> true },
                        onWorkspaceDragCancel = {},
                        showActivitySwipeHint = false,
                        monotonicUptimeMillis = { compose.mainClock.currentTime },
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun frames(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** The host's strip carrying the Activity page in, as the Presentation's does: the pane lands, then the host swaps the shown scene with a snap. */
    private fun swipeToActivity() {
        stripX = widthDp * density * 0.5f
        panActive = true
        frames(100)
        stripX = widthDp * density
        frames(100)
        snap = true
        activityShown = true
        frames(100)
        panActive = false
        snap = false
        frames(100)
    }

    private fun crossfadeBegins(): List<String> =
        ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }
            .filter { "display=$DISPLAY" in it && it.startsWith("ev=transition.begin") && "name=scene.crossfade" in it }

    private fun activityPlaced(): Int =
        compose.onAllNodesWithTag(ActivityTag, useUnmergedTree = true).fetchSemanticsNodes().count { it.layoutInfo.isPlaced }

    @Test
    fun `a payload mode change under the Activity page is not a scene change`() {
        mount()
        swipeToActivity()
        assertEquals("fixture: the Activity page is placed", 1, activityPlaced())
        ShadowLog.clear()

        // The game runs: the Bottom's payload is NOW_PLAYING while the Activity page shows the takeover.
        payload = payload.copy(mode = CompanionMode.NOW_PLAYING)
        frames(200)
        // The game ended: the payload goes back to HOME while the Activity page is still the shown scene (the Thor's 52.052).
        payload = payload.copy(mode = CompanionMode.HOME)
        repeat(50) {
            frames(16)
            assertEquals("the Activity page stays placed through the mode change, frame $it", 1, activityPlaced())
        }
        assertEquals("no scene handoff was started by a mode change under the override", emptyList<String>(), crossfadeBegins())
    }

    @Test
    fun `a change of the scene identity still hands over (the swipe back to the lattice)`() {
        mount()
        swipeToActivity()
        ShadowLog.clear()
        // The host changes which scene is shown without the finger carrying it: a scene change on the page tier.
        activityShown = false
        frames(16)
        frames(700)
        assertTrue("the identity change hands over: ${crossfadeBegins()}", crossfadeBegins().isNotEmpty())
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val DISPLAY = 4
        const val ActivityTag = "mode-flip-activity"
    }
}
