package com.gruning.mimir.rewrite.core.ui.select

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The select overlay's chrome. , choosing a gold title rule over the existing glass (the inset hairline that came with it is retired:
 * the panel has one edge, sweep 10-09).
 *
 * Source-contract tests: what is being asserted is Compose *drawing*, which has no JVM-reachable
 * observable — the same reason `SelectOverlayBackdropGlassTest` is written this way.
 */
class MimirSelectOverlayOrnamentTest {

    /**
     * Source with `//` comment bodies removed. Twice now a check has been defeated by the KDoc that
     * EXPLAINS the defect: the comment above the brackets quotes the very `.drawBehind { }` the code
     * must not contain. Strip comments, then assert on what actually compiles.
     */
    private val code: String by lazy {
        source.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    private val source: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/select/MimirSelectOverlay.kt")
        assertTrue("MimirSelectOverlay.kt must be readable from the module dir", file.isFile)
        file.readText()
    }

    /**
     * **The panel has ONE edge (sweep 10-09).** History: corner brackets shipped first (invisible, then removed), then a gold hairline inset 7 dp from the panel's glass
     * border (2026-08-29). On the Open with sheet the two stood side by side, an outer border and an inner
     * one with a gap between them, which produces a duplicate edge. The glass panel's own border (the one every glass panel wears) is the edge that stays; nothing is drawn inside it.
     */
    @Test
    fun thePanelHasOneEdgeAndNoInsetHairlineOrBookCorners() {
        val panel = code.substringAfter("MimirLiquidGlassPanel(", "").substringBefore("contentPadding", "")
        assertTrue("the panel call is found", panel.isNotEmpty())
        assertTrue("no inset frame is drawn inside the glass border", "drawRoundRect(" !in panel)
        assertTrue("no second outline of any kind is painted by this overlay's panel modifier", "drawWithContent {" !in panel && ".drawBehind {" !in panel && ".border(" !in panel)
        assertTrue("the gold ornament colour is gone", "goldOrnament" !in code)
        assertTrue("and its inset constant", "MimirSelectOrnamentInset" !in source)
        assertTrue(
            "the book corners stay gone from this surface",
            "drawMimirCornerBrackets" !in code,
        )
        assertTrue(
            "and their import with them",
            "import com.gruning.mimir.rewrite.core.ui.drawMimirCornerBrackets" !in source,
        )
    }

    /**
     *  Measured on the Odin the body ran (28,28,39) at the top to (10,12,17) at
     * the bottom — "Disc 2" sat on roughly half the ground luminance of "Play". Rows are peers; their
     * ground must not rank them.
     *
     * Requested per-call. `gradientBody` defaults TRUE so the Home launch card, Utility, Tweaker and the
     * Tome overlays keep the gradient, where it is the point.
     */
    @Test
    fun theListBodyIsOneFlatToneNotAGradient() {
        assertTrue("this panel must opt out of the cover gradient", "gradientBody = false" in code)
    }

    @Test
    fun theHeadCarriesAGoldRule() {
        val head = source.substringAfter("if (hasCounter)", "").substringBefore("// Options", "")
        assertTrue("a gold hairline must follow the head row", "MimirColors.Gold.copy(" in head)
    }

    /**
     * Density may never cost a committable row its touch target. These rows launch games; 48dp is
     * Android's minimum and is the floor gap tightening must preserve.
     */
    @Test
    fun aCommittableRowNeverDropsBelowTheMinimumTouchTarget() {
        assertEquals(48, MimirSelectRowMinHeight.value.toInt())
        assertTrue(
            "the row height must read from the named floor, not a literal",
            "min = if (option.enabled) MimirSelectRowMinHeight" in source,
        )
    }

    /**
     * The panel's body and border are the shared glass panel's own ([MimirLiquidGlassPanel] clips, paints the body and strokes the one border); the overlay adds no paint
     * to its modifier chain, so no surface layer can stand over or under the border as a second edge.
     */
    @Test
    fun theOverlayAddsNoPaintOverOrUnderThePanelsOwnBorder() {
        val panel = code.substringAfter("MimirLiquidGlassPanel(", "").substringBefore("contentPadding", "")
        assertTrue("the panel call is found", panel.isNotEmpty())
        assertTrue("drawWithContent would paint over the border", "drawWithContent {" !in panel)
        assertTrue("drawBehind would paint under it", ".drawBehind {" !in panel)
    }

    /**
     * One constant drives the clip and the shadow silhouette. They disagreed for months — shadow cast at
     * `radiusCard` (14dp) under a body clipped at 20dp — which put a squarer silhouette proud of a
     * rounder panel. Fixed at the CALL SITE: `radiusCard` has ~80 read sites, is pinned at 14f
     * elsewhere, and `SelectRow` reads it for the ROW corners inside this very file.
     */
    @Test
    fun theShadowSilhouetteMatchesThePanelClip() {
        assertTrue(
            "the shadow must be cast at the panel's own radius",
            "RoundedCornerShape(MimirSelectPanelRadius)" in source,
        )
        assertTrue(
            "and the panel must be clipped at that same constant",
            "radius = MimirSelectPanelRadius" in source,
        )
        assertTrue(
            "the shared 20dp token must not be edited to achieve it",
            "MimirGlass.panelRadius =" !in source,
        )
    }

    /**
     * An unfocused row painted `Color.Transparent` and drew a border only when focused, so a 48dp box
     * held nothing but a short label ("Disc 1" measures under a tenth of the row interior). The resting
     * ring is `mimirGlassRow`'s own, so a select row and a glass row speak one dialect of "resting".
     */
    @Test
    fun anUnfocusedCommittableRowHasABodyOfItsOwn() {
        // Ring migration (2026-09-28): the hairline is the selection ring's resting outline, which fades out as the
        // ring arrives, at the glass row's own colour.
        assertTrue(
            "an enabled unfocused row must carry the resting hairline",
            "restingOutline = option.enabled," in code,
        )
        assertTrue(
            "at the shared glass resting-ring colour",
            "outlineColor = palette.glassRestingOutline()" in code,
        )
    }

    /**
     * A DISABLED row stays bodiless. It cannot be committed, and giving it a live row's object
     * treatment would make it look pressable — the same reason it is allowed below the touch floor.
     */
    @Test
    fun aNonCommittableRowIsNotDressedAsAPressableOne() {
        // `restingOutline = option.enabled` is the whole rule: a disabled row draws no hairline, and takes no ring.
        val row = code.substringAfter("private fun SelectRow(", "")
        assertTrue("the hairline follows option.enabled", "restingOutline = option.enabled," in row)
        assertTrue("no unconditional hairline on a select row", "restingOutline = true" !in row)
    }

    /**
     * The menu width is capped at 480dp. 480dp is the ODIN-only half: the Nova sits below the narrow threshold and
     * is already clamped to its 438dp detail pane, so `min(480, 438)` leaves it untouched.
     */
    @Test
    fun thePanelIsCappedAtTheAuthoredWidth() {
        assertEquals(480f, MimirSelectPanelWidthCapDp)
        assertTrue(
            "the cap must be read from the named constant, not a literal",
            "authoredCapDp = MimirSelectPanelWidthCapDp" in code,
        )
    }
}
