package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import java.io.File
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
 * The recent-app icon in the Bottom's upper-right corner briefly appeared when swiping to the lattice. The icon is
 * drawn by a surface layer of its own, translated by the Activity pane's position. That translation used to be "the strip's x less a width while the strip is
 * active, else 0": the landing on the lattice (and a settle back to it) lets go of the strip a frame BEFORE the surface's composition drops the icon, and for
 * that frame the layer read "not active" and drew the still-composed icon at 0, on the lattice. The translation is now a pure function of what is drawn
 * (the strip's position or its rest, and the page shown), read in the draw phase.
 *
 * The first half runs the real [CompanionWorkspaceStrip] through its landings on a plain JVM and asks the function where the pane is; the second half
 * composes the real modifier at the Bottom's 537.7 x 468.3 dp and reads where the icon is, step by step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionRecentAppPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private val lattice = CompanionWorkspacePage.SHELF
    private val activity = CompanionWorkspacePage.ACTIVITY
    private val density = 369f / 160f
    private val paneWidthPx = 1240f
    private val iconEndPx = 24f * density
    private val iconSizePx = 48f * density

    /** The rule this replaces: the strip's x less a width while it was active, else 0 whichever page was shown. */
    private fun oldTranslation(stripX: Float): Float = if (!stripX.isNaN()) stripX - paneWidthPx else 0f

    private fun strip() = CompanionWorkspaceStrip().also { it.widthPx = paneWidthPx }

    private fun stripX(s: CompanionWorkspaceStrip) = if (s.active) s.x else Float.NaN

    /** The icon sits [iconEndPx] inside the pane's right edge and is [iconSizePx] wide: on the canvas when any of it is right of 0. */
    private fun onCanvas(translation: Float) = translation + paneWidthPx - iconEndPx > 0f && translation + paneWidthPx - iconEndPx - iconSizePx < paneWidthPx

    private fun hiddenAt(stripX: Float, shown: CompanionWorkspacePage) = !onCanvas(companionActivityPaneTranslationX(stripX, shown, paneWidthPx))

    @Test
    fun `at rest the pane is at zero on Activity and a full width off the canvas on the lattice`() {
        assertEquals(0f, companionActivityPaneTranslationX(Float.NaN, activity, paneWidthPx), 0f)
        assertEquals(-paneWidthPx, companionActivityPaneTranslationX(Float.NaN, lattice, paneWidthPx), 0f)
        assertTrue("on the lattice at rest the icon is not on the canvas", hiddenAt(Float.NaN, lattice))
        assertFalse("on Activity at rest it is", hiddenAt(Float.NaN, activity))
    }

    @Test
    fun `off its rest the pane rides the strip whichever page the host shows`() {
        for (shown in listOf(lattice, activity)) {
            assertEquals(-paneWidthPx + 300f, companionActivityPaneTranslationX(300f, shown, paneWidthPx), 0f)
            assertEquals(0f, companionActivityPaneTranslationX(paneWidthPx, shown, paneWidthPx), 0f)
            assertEquals(-paneWidthPx, companionActivityPaneTranslationX(0f, shown, paneWidthPx), 0f)
        }
    }

    @Test
    fun `the frame after a settle back onto the lattice never draws the icon, though it is still composed`() {
        val s = strip()
        s.drag(lattice, 400f)
        val release = s.release(velocityPx = 0f, flingPx = 800f)!!
        assertEquals("a slow release short of half settles back on the lattice", lattice, release.target)
        s.moveTo(release, 0f)
        assertTrue("the strip is back at the lattice's rest: the icon is off the canvas", hiddenAt(stripX(s), lattice))
        // The settle ends: finish lets go of the strip (NaN) while the surface still holds the page it composed with (the icon composed).
        assertFalse(s.finish(release))
        assertFalse(s.active)
        assertTrue("the frame after the let-go draws nothing at the icon", hiddenAt(stripX(s), lattice))
        assertTrue("the old rule drew it there: translation 0 on the lattice", onCanvas(oldTranslation(stripX(s))))
    }

    @Test
    fun `the landing on the lattice, held until the host shows it, never draws the icon and neither does the frame after`() {
        val s = strip()
        // Get onto Activity first: a drag from the lattice, released past half, landed, shown.
        s.drag(lattice, paneWidthPx)
        val toActivity = s.release(velocityPx = 0f, flingPx = 800f)!!
        assertEquals(activity, toActivity.target)
        s.moveTo(toActivity, paneWidthPx)
        assertTrue(s.finish(toActivity))
        s.reconcile(activity)
        assertFalse(s.active)
        assertFalse("resting on Activity the icon is on the canvas", hiddenAt(stripX(s), activity))
        // The swipe back: Activity -> lattice.
        s.drag(activity, -700f)
        assertFalse("mid-pan the icon rides in the Activity pane", hiddenAt(stripX(s), activity))
        val back = s.release(velocityPx = -200f, flingPx = 800f)!!
        assertEquals(lattice, back.target)
        s.moveTo(back, 0f)
        assertTrue("it commits onto the lattice", s.finish(back))
        // Held at the lattice's rest while the host has not shown the lattice yet, then shown, then the strip lets go: each of these frames is off the canvas.
        assertTrue("held, host still on Activity", hiddenAt(stripX(s), activity))
        assertTrue("held, host shows the lattice", hiddenAt(stripX(s), lattice))
        s.reconcile(lattice)
        assertFalse(s.active)
        assertTrue("let go, host on the lattice", hiddenAt(stripX(s), lattice))
    }

    @Test
    fun `the Presentation draws the icon's pane with the one rule and keeps no translation of its own`() {
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertTrue("the icon's layer uses the shared rule", presentation.contains("companionActivityPane(stripX = workspaceStripX, shown = { workspacePage })"))
        assertFalse(
            "the old rule (the strip's x while active, else 0) is gone",
            presentation.contains("translationX = if (workspaceStrip.active) workspaceStrip.x - workspaceStrip.widthPx else 0f"),
        )
    }

    // ---- composed -------------------------------------------------------------------------------------------------------------------------------

    private var stripXState by mutableFloatStateOf(Float.NaN)
    private var shownState by mutableStateOf(lattice)

    private fun mount() {
        compose.setContent {
            Box(Modifier.requiredSize(537.7.dp, 468.3.dp)) {
                Box(Modifier.fillMaxSize().companionActivityPane(stripX = { stripXState }, shown = { shownState })) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 24.dp)
                            .size(48.dp)
                            .testTag("icon"),
                    )
                }
            }
        }
    }

    private fun set(stripX: Float, shown: CompanionWorkspacePage) {
        compose.runOnIdle {
            stripXState = stripX
            shownState = shown
        }
        compose.waitForIdle()
    }

    private fun iconLeft(): Float = compose.onNodeWithTag("icon").fetchSemanticsNode().positionInRoot.x

    private fun iconRightEdge(): Float = iconLeft() + compose.onNodeWithTag("icon").fetchSemanticsNode().size.width

    @Test
    fun `composed, the icon is on the canvas on Activity and off it on the lattice, at rest and through a landing`() {
        mount()
        val canvasWidth = compose.onRoot().fetchSemanticsNode().size.width.toFloat()

        set(Float.NaN, activity)
        val home = iconLeft()
        assertTrue("on Activity the icon is on the canvas, near its right end", home > canvasWidth / 2f && iconRightEdge() <= canvasWidth)

        set(Float.NaN, lattice)
        assertTrue("on the lattice at rest the icon is off the canvas", iconRightEdge() <= 0f)

        // The landing on the lattice: held at the lattice's rest while the host shows Activity, then the host shows the lattice, then the strip lets go.
        set(0f, activity)
        assertTrue("held at the lattice's rest", iconRightEdge() <= 0f)
        set(0f, lattice)
        assertTrue("the host shows the lattice, the strip still holds", iconRightEdge() <= 0f)
        set(Float.NaN, lattice)
        assertTrue("the strip lets go: still off the canvas", iconRightEdge() <= 0f)

        // A pan from the lattice to Activity: the icon rides in with the pane and lands exactly where it rests.
        set(canvasWidth / 2f, lattice)
        assertEquals("half way: half a pane to the left of home", home - canvasWidth / 2f, iconLeft(), 1f)
        set(canvasWidth, lattice)
        assertEquals("landed, the host has not shown Activity yet", home, iconLeft(), 1f)
        set(canvasWidth, activity)
        assertEquals("the host shows Activity", home, iconLeft(), 1f)
        set(Float.NaN, activity)
        assertEquals("the strip lets go: no jump", home, iconLeft(), 1f)
    }
}
