package com.gruning.mimir.rewrite.feature.companion

import android.animation.ValueAnimator
import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapEnterSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.core.ui.rememberMimirHeldGlassProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The progress timelines of the Bottom's scene-glass holds (audit 2026-10-14, RC-02 the scene overlay, RC-03 the Tome, RC-04 the platform shelf,
 * and the rename of the W2L7 fix round), each glass THE PRODUCTION FUNCTION its site calls (`rememberCompanionOverlayGlass` for the overlay,
 * core-ui's `rememberMimirHeldGlassProgress` for the rest; the call sites are pinned by source in `:app`'s AuditW2L7SourcePinTest and
 * BottomOverlayTiersSourcePinTest), each panel on the clock its site runs:
 *
 *  - On close, no frame has the panel's alpha in 0.05..0.95 while the scene's glass is below its peak: the glass holds through the panel's own
 *    dissolve, and the first frame on which it clears finds the panel gone.
 *  - On open, the glass softens the scene first: it is well on its way before the page's lead is over and the page begins to arrive.
 *
 * The shape this replaced, the glass fed by the raw request, is run beside each hold and breaks the close law, so the harness is known to bite.
 * Robolectric does not draw a blur; the pixels are device-owed (SWEEP TH1-TH3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomGlassHoldTimelineTest {

    @get:Rule
    val compose = createComposeRule()

    private var requested by mutableStateOf(true)

    /** Compose reads the animator duration scale as its motion scale; a test earlier in this JVM may have left it raised. Pinned at 1 here. */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Before
    fun pinAnimatorScale() = setAnimatorScale(1f)

    @After
    fun restoreAnimatorScale() = setAnimatorScale(1f)

    private class Sample(val heldGlass: Float, val rawGlass: Float, val panel: Float)

    private val latest = mutableStateOf<Sample?>(null)
    private val closing = mutableListOf<Sample>()

    /** One site's pair: the panel on its own clock, the glass as production wires it (through the hold) and straight from the request (the shape replaced). */
    @Composable
    private fun site(panelAlpha: @Composable (Boolean) -> Float, heldGlass: @Composable (Boolean) -> Float, rawGlass: @Composable (Boolean) -> Float) {
        val held = heldGlass(requested)
        val raw = rawGlass(requested)
        val panel = panelAlpha(requested)
        SideEffect { latest.value = Sample(held, raw, panel) }
    }

    /** RC-02: the scene overlay's glass, the production function itself (CompanionSurface.rememberCompanionOverlayGlass); the picker, prompt and dock leave on the swap tier's exit. */
    private val overlayGlass: @Composable (Boolean) -> Float = { up -> rememberCompanionOverlayGlass(up).value }

    /** The shape replaced: the same OverlayMs ramp straight from the request. */
    private val rawOverlayGlass: @Composable (Boolean) -> Float = { up ->
        animateFloatAsState(if (up) 1f else 0f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing), label = "overlay-raw").value
    }
    private val swapPanel: @Composable (Boolean) -> Float = { up ->
        animateFloatAsState(if (up) 1f else 0f, if (up) mimirSwapEnterSpec(false) else mimirSwapExitSpec(false), label = "swap-page").value
    }

    /** RC-03, RC-04 and the rename: core-ui's held progress, the production function every scene-side site calls (rememberMimirHeldGlassProgress). */
    private val heldProgressGlass: @Composable (Boolean) -> Float = { up -> rememberMimirHeldGlassProgress(visible = up)() }

    /** The shape replaced: the progress straight from the request. */
    private val rawProgressGlass: @Composable (Boolean) -> Float = { up -> rememberMimirGlassProgress(visible = up, identity = Unit)() }

    /** The Tome's page: CompanionSurface's `reveal` clocks, an arrival from rest and the OverlayMs leave. */
    private val tomePage: @Composable (Boolean) -> Float = { up ->
        animateFloatAsState(if (up) 1f else 0f, if (up) mimirOverlayArriveSpec(false) else mimirOverlayLeaveSpec(), label = "tome-page").value
    }
    /** The platform shelf's page: MimirCompanionPresentation's `shelfEnterAnimated` clocks (its `when` is pinned by source in `:app`), an arrival from rest and the ReturnMs return. */
    private val shelfPage: @Composable (Boolean) -> Float = { up ->
        animateFloatAsState(
            if (up) 1f else 0f,
            if (up) mimirOverlayArriveSpec(false) else tween(MimirPageMotion.ReturnMs, easing = MimirMotion.Easing),
            label = "shelf-page",
        ).value
    }

    /** The rename's keyboard plate: OverlayMs both ways (CompanionSurface's `renamePlate`). */
    private val renamePlate: @Composable (Boolean) -> Float = { up ->
        animateFloatAsState(if (up) 1f else 0f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing), label = "rename-plate").value
    }

    private fun frame() {
        compose.mainClock.advanceTimeBy(16L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount(panel: @Composable (Boolean) -> Float, heldGlass: @Composable (Boolean) -> Float, rawGlass: @Composable (Boolean) -> Float): List<Sample> {
        // Born closed, as the sites are (the lattice mounts with no Tome, shelf or panel up), then opened: an `animateFloatAsState` born at its target runs nothing.
        requested = false
        compose.mainClock.autoAdvance = false
        compose.setContent { site(panel, heldGlass, rawGlass) }
        repeat(10) { frame() }
        assertEquals("fixture: closed at birth", 0f, latest.value!!.panel, 0.001f)
        Snapshot.withMutableSnapshot { requested = true }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val opening = mutableListOf<Sample>()
        repeat(60) {
            frame()
            latest.value?.let(opening::add)
        }
        assertEquals("fixture: open and settled", 1f, latest.value!!.panel, 0.001f)
        assertEquals("fixture: the glass is at its peak", 1f, latest.value!!.heldGlass, 0.001f)
        Snapshot.withMutableSnapshot { requested = false }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        repeat(60) {
            frame()
            latest.value?.let(closing::add)
        }
        assertEquals("fixture: closed", 0f, latest.value!!.panel, 0.001f)
        assertEquals("fixture: cleared", 0f, latest.value!!.heldGlass, 0.001f)
        return opening
    }

    private fun midDissolve(s: Sample) = s.panel > 0.05f && s.panel < 0.95f

    private fun assertDissolveThenClear(site: String) {
        val violations = closing.filter { midDissolve(it) && it.heldGlass < 0.999f }
        assertTrue("$site: no frame has the panel mid-dissolve over a glass below its peak: ${violations.map { "%.2f/%.2f".format(it.panel, it.heldGlass) }}", violations.isEmpty())
        val firstClear = closing.first { it.heldGlass < 0.999f }
        assertTrue("$site: the frame the glass starts to clear finds the panel gone (panel %.3f)".format(firstClear.panel), firstClear.panel <= 0.05f)
        val rawViolations = closing.count { midDissolve(it) && it.rawGlass < 0.999f }
        assertTrue("$site: the shape replaced (glass on the raw request) breaks the law, so the harness bites", rawViolations > 0)
    }

    @Test
    fun `the scene overlay's glass holds through a panel's dissolve and clears after it (RC-02)`() {
        mount(swapPanel, overlayGlass, rawOverlayGlass)
        assertDissolveThenClear("overlay")
    }

    @Test
    fun `the lattice's glass under the Tome softens first and holds through the page's leave (RC-03)`() {
        val opening = mount(tomePage, heldProgressGlass, rawProgressGlass)
        assertDissolveThenClear("tome")
        assertSoftenFirst("tome", opening)
    }

    @Test
    fun `the lattice's glass under the platform shelf softens first and holds through the shelf's return (RC-04)`() {
        val opening = mount(shelfPage, heldProgressGlass, rawProgressGlass)
        assertDissolveThenClear("shelf")
        assertSoftenFirst("shelf", opening)
    }

    @Test
    fun `the rename's glass holds through the keyboard plate's dissolve and clears after it (W2L7 review 3)`() {
        mount(renamePlate, heldProgressGlass, rawProgressGlass)
        assertDissolveThenClear("rename")
    }

    private fun assertSoftenFirst(site: String, opening: List<Sample>) {
        val pageBegins = opening.indexOfFirst { it.panel > 0.05f }
        val glassBegins = opening.indexOfFirst { it.heldGlass > 0.05f }
        assertTrue("$site: fixture, both ran", pageBegins > 0 && glassBegins > 0)
        assertTrue("$site: the glass softens the scene before the page begins to arrive (glass frame $glassBegins, page frame $pageBegins)", glassBegins < pageBegins)
        assertTrue("$site: the glass is well on its way when the page's lead ends", opening[pageBegins].heldGlass > 0.5f)
    }
}
