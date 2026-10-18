package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.model.CompanionHomeShelfDetail
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSurfaceTimingTest {
    // The route title card is retired, along with the tests of its removed behavior. Four tests are gone
    // rather than re-aimed a second time: two pinned `companionHomeDetailIdentity`, the identity of a
    // detail card that no longer exists, and two pinned route-versus-payload precedence, which cannot
    // be tested now that `CompanionSceneTarget.Route` is retired and every destination resolves to a
    // payload. Re-pointing a test whose subject has been deleted produces a test that asserts nothing;
    // deleting it and saying so is the honest option.
    @Test
    fun `scene handoff is the page tier, with no second exposure and no private clock`() {
        // Thor Bottom revamp B2: the scene transition is core-ui's MimirPageHandoff on Main's page-tier clocks. The 140 ms outgoing
        // fade from alpha 0.5 (a second exposure of the departing scene over the arriving one) and the 170 ms glide are retired
        // with their constants; the paused-clock behaviour is pinned in the app's CompanionSceneHandoffTest.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt").readText()
        val sceneMarker = "fun <T> CompanionSceneTransition("
        val sceneStart = source.indexOf(sceneMarker)
        assertTrue("companion scene transition declaration must exist", sceneStart >= 0)
        val progressStart = source.indexOf("private fun CompanionJobProgressBar(", startIndex = sceneStart)
        assertTrue("job progress declaration must follow scene transition", progressStart > sceneStart)
        val sceneEnd = source.lastIndexOf("@Composable", startIndex = progressStart)
        assertTrue("job progress annotation must follow scene transition", sceneEnd > sceneStart)
        val scene = source.substring(sceneStart + sceneMarker.length, sceneEnd)
        assertTrue("it is a call to the shared handoff", scene.contains("MimirPageHandoff("))
        assertTrue("the arrival is the overlay arrive spec: the lead, then the slow clock", scene.contains("mimirOverlayArriveSpec("))
        assertTrue("a return leaves on the return clock", scene.contains("MimirPageMotion.ReturnMs") && scene.contains("MimirPageMotion.ExitMs"))
        assertTrue("touches wait for the first painted frame", scene.contains("shieldTouches = { shielding }"))
        assertTrue("reduced motion snaps", scene.contains("departs = !reducedMotion"))
        assertFalse("no pad gate: the Bottom is touch-only", scene.contains("MimirTransitionInputGate"))
        listOf("CompanionOutgoingFadeMs", "CompanionOutgoingStartAlpha", "CompanionWorkspaceGlideMs", "ModeTransitionEasing").forEach {
            assertFalse("`$it` is retired", source.contains(it))
        }
        assertTrue("the receipts keep their names", scene.contains("\"scene.crossfade\"") && scene.contains("\"scene.glide\""))
        assertEquals("scene.glide", companionSceneEntryTransition(hasOutgoingLayer = true, glides = true))
        assertEquals("scene.crossfade", companionSceneEntryTransition(hasOutgoingLayer = true, glides = false))
        assertEquals(null, companionSceneEntryTransition(hasOutgoingLayer = false, glides = false))
        // **The route title card retirement changed this behavior.** SETTINGS + a HOME payload used to yield the
        // rail, because the route title card was covering HOME and HOME's own quick-launch footer was
        // therefore not on screen. With the card retired, HOME actually renders in that situation and
        // draws its own footer -- so the shared rail correctly stands down. Quick launch did not
        // disappear; it moved to the surface that owns it.
                // **The HOME exception was removed on 2026-08-31**: the rail is now on every surface. The layout uses the 86 dp because the alternative was the
        // instruments switch living in HOME's footer beside Create Tome — two different kinds of
        // thing at equal weight, in a row where it slid sideways on every cursor move. These
        // assertions are INVERTED rather than deleted, because "HOME is not special here" is now
        // the rule and it deserves a pin as much as the exception did.
        assertTrue(companionQuickLaunchAvailable(CompanionPrimaryRoute("SETTINGS", 2L), CompanionMode.HOME))
        assertTrue(companionQuickLaunchAvailable(CompanionPrimaryRoute("LIBRARY", 3L), CompanionMode.NOW_PLAYING))
        assertTrue(companionQuickLaunchAvailable(CompanionPrimaryRoute("LIBRARY", 4L), CompanionMode.NOW_PLAYING))
        assertTrue(companionQuickLaunchAvailable(CompanionPrimaryRoute("HOME", 5L), CompanionMode.IDLE))
        assertTrue(companionQuickLaunchAvailable(CompanionPrimaryRoute("HOME", 6L), CompanionMode.NOW_PLAYING))
        assertTrue(companionQuickLaunchAvailable(CompanionPrimaryRoute("HOME", 7L), CompanionMode.HOME))
        assertTrue(companionQuickLaunchAvailable(CompanionPrimaryRoute("LIBRARY", 8L), CompanionMode.HOME))
    }

    @Test
    fun `quicklaunch geometry follows the immediate destination`() {
        val home = CompanionSceneTarget.Payload(CompanionMode.HOME)
        val detail = CompanionSceneTarget.Payload(CompanionMode.NOW_PLAYING)
        // Was `CompanionSceneTarget.Route("SETTINGS")`. The route title card retirement removed the Route target, so SETTINGS now
        // resolves to a payload like every other destination -- and the property under test was never
        // about routes, it is "leaving HOME makes the rail appear in the same logical frame".
        val settings = CompanionSceneTarget.Payload(CompanionMode.IDLE)

        // Home -> non-Home: the destination rail appears in the same logical frame.
        assertTrue("HOME is no longer excepted", companionQuickLaunchAvailable(home))
        assertTrue(companionQuickLaunchAvailable(settings))

        // Non-Home -> Home: Home owns the full height immediately; no alpha-zero cutover is required.
        assertTrue(companionQuickLaunchAvailable(detail))
        assertTrue("HOME is no longer excepted", companionQuickLaunchAvailable(home))
    }

    @Test
    fun dwellDelayUsesTheOriginalDeadlineWithoutPollingDrift() {
        val enteredAt = 10_000L

        assertEquals(750L, remainingCompanionDwellDelayMillis(enteredAt, 750L, enteredAt))
        assertEquals(1L, remainingCompanionDwellDelayMillis(enteredAt, 750L, enteredAt + 749L))
        assertEquals(0L, remainingCompanionDwellDelayMillis(enteredAt, 750L, enteredAt + 750L))
        assertEquals(0L, remainingCompanionDwellDelayMillis(enteredAt, 750L, enteredAt + 900L))

        assertEquals(1_000L, remainingCompanionDwellDelayMillis(enteredAt, 1_000L, enteredAt - 5L))
        assertEquals(250L, remainingCompanionDwellDelayMillis(enteredAt, 1_000L, enteredAt + 750L))
        assertEquals(0L, remainingCompanionDwellDelayMillis(enteredAt, 1_000L, enteredAt + 1_000L))
    }

    @Test
    fun dragSettleTargetRequiresAKnownRectOnTheSettledPage() {
        val target = Rect(10f, 20f, 110f, 120f)

        assertEquals(target, companionDragSettleTarget(2, 0, mapOf(2 to target)))
        assertEquals(null, companionDragSettleTarget(9, 0, mapOf(9 to target)))
        assertEquals(null, companionDragSettleTarget(2, 1, mapOf(2 to target)))
        // Q6 : the Bottom's drag clocks are the house tokens; the settle is FocusMs on MimirMotion.Easing.
        assertEquals(MimirMotion.FocusMs, CompanionDragSettleMillis)
    }
}
