package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-47 / RC-48 (audit 2026-10-14): the Home and Library draw-phase reads, pinned at the source because the hosts they sit in
 * cannot be driven on the JVM (a TextureView clip never reports its first frame; the hint swap's clip is a GPU buffer).
 *
 * Every pin names the failure it prevents: an animated value read in COMPOSITION recomposes its whole scope on each frame of the
 * animation, and `Modifier.alpha(a)` is a different modifier chain at exactly 1.0, so its layer is destroyed on the last frame.
 * The recomposition COUNTS are measured where a harness exists ([GameShelfRingGlideRecompositionTest]: it fails on the old
 * code); these pin the rest. The Folio is pinned here and NOT counted: a Robolectric recomposer-change-count harness over the
 * Folio read zero recompositions per frame on the OLD code as well (2026-10-14), so it could not tell the two apart and was
 * dropped rather than kept as a test that proves nothing.
 */
class HomeDrawPhaseReadsSourcePinTest {

    private val video by lazy { source("VideoBackdrop.kt") }
    private val backdrop by lazy { source("HomeShelfBackdrop.kt") }
    private val shelf by lazy { source("GameShelf.kt") }
    private val homeScreen by lazy { source("HomeShelfScreen.kt") }
    private val folio by lazy { source("HomeTomeFolio.kt") }
    private val launchCard by lazy { source("HomeLaunchCard.kt") }

    @Test
    fun `the video clip fades in its layer, on the house curve, and its bed volume ramp recomposes nothing`() {
        assertFalse("the clip's alpha is a graphicsLayer, not Modifier.alpha", video.contains(".alpha(videoAlpha)"))
        assertEquals(
            "both surfaces (animated image and video) fade through the layer lambda",
            2,
            Regex("""graphicsLayer \{ alpha = videoAlpha\.value \}""").findAll(video).count(),
        )
        assertTrue("videoAlpha stays a State", video.contains("val videoAlpha = animateFloatAsState("))
        assertFalse("never `by`: that reads it in composition", video.contains("val videoAlpha by"))

        assertTrue("the bed volume ramp is a State too", video.contains("val bedVolume = animateFloatAsState("))
        assertTrue("and VideoSurface takes the State", video.contains("bedVolume: State<Float>,"))
        assertTrue(
            "it reaches the player from a snapshotFlow, so the 200 ms ramp is not a composition read",
            video.contains("snapshotFlow { bedVolume.value }.collect { controller.setBedVolume(it) }"),
        )

        val handoffTweens = Regex("""tween\(durationMillis = BifrostVisualHandoffMs[^)]*\)""").findAll(video).map { it.value }.toList()
        assertEquals("the two handoff fades (video frame, still cover)", 2, handoffTweens.size)
        handoffTweens.forEach {
            assertTrue("a fade on the house curve, never the default FastOutSlowIn: $it", it.contains("easing = MimirMotion.Easing"))
        }
    }

    @Test
    fun `the Home backdrop reads its idle fade and the hero seat only in layer lambdas`() {
        assertTrue(
            "the runtime carries the fade as a State",
            backdrop.contains("val alpha: State<Float>,") && backdrop.contains("alpha = alpha.asState(),"),
        )
        assertFalse(
            "handing the host a Float recomposed it and its VideoBackdrop on every frame of the idle fade",
            backdrop.contains("alpha = alpha.value,"),
        )
        assertEquals("both hosts read it inside their graphicsLayer", 2, Regex("""runtime\.alpha\.value""").findAll(backdrop).count())

        assertEquals("both hosts take the hero seat as a lambda", 2, Regex("""heroSlotOffset: \(\) -> Float,""").findAll(backdrop).count())
        assertFalse("never multiplied in composition", backdrop.contains("(heroSlotOffset * geometry.slotStride).dp"))
        assertEquals("three pool layers read it inside their lambda", 3, Regex("""heroSlotOffset\(\) \* geometry\.slotStride""").findAll(backdrop).count())
    }

    @Test
    fun `the shelf hands out the eased ring seat as a State and places the ring in a layout lambda`() {
        assertTrue(shelf.contains("val visualSelectedSeat: State<Float>,"))
        val build = shelf.substringAfter("val visualSelectedSlotOffset = key(surfaceEpoch, windowMemoryKey) {")
            .substringBefore("return GameShelfState(")
        assertFalse("rememberGameShelfState must not read the animation's value: that is the caller's composition", build.contains(".value"))
        assertEquals(
            "both rings (game, Tome) are placed by an offset lambda that reads the seat",
            2,
            Regex("""\.offset \{\s+IntOffset\(\s+x = \(geometry\.contentLeft \+ visualSlotOffset\(\) \* geometry\.slotStride\)""").findAll(shelf).count(),
        )
        assertEquals("both ring composables take the seat as a lambda", 2, Regex("""visualSlotOffset: \(\) -> Float,""").findAll(shelf).count())

        assertEquals(
            "Home hands both backdrops the seat as a lambda over the State",
            2,
            Regex("""\{ visualSelectedSlotOffset\.value \}""").findAll(homeScreen).count(),
        )
        assertFalse("and never reads it in its own composition", homeScreen.contains("heroSlotOffset = visualSelectedSlotOffset"))
    }

    @Test
    fun `the tile focus hint gates on a discrete flag and fades on a layer wider than the swap`() {
        val hint = homeScreen.substringAfter("private fun HomeTileFocusHint(").substringBefore("private fun HomeTileFocusHintBadge(")
        assertFalse("the animated alpha is not compared in composition", hint.contains("if (alpha <= 0.01f) return"))
        assertTrue(
            "the gate is a derived discrete flag, so composition re-runs when the pill appears or finishes fading, not per frame",
            hint.contains("val visibleNow by remember { derivedStateOf { alpha.value > 0.01f } }") && hint.contains("if (!visibleNow) return"),
        )
        // The swap draws a wider departing frame past the arriving one's bounds ("never inside a clip"); an Auto layer below
        // alpha 1 is a buffer cut to its node's bounds. So the fade rides a rail-wide parent and the swap carries no layer.
        val layerAt = hint.indexOf(".graphicsLayer { this.alpha = alpha.value }")
        val swapAt = hint.indexOf("MimirHintSwap(")
        val wideAt = hint.indexOf(".fillMaxWidth()")
        assertTrue("the alpha layer is on a parent that spans the rail, and it comes before the swap", wideAt in 0 until layerAt && layerAt in 0 until swapAt)
        val swapCall = hint.substring(swapAt, hint.indexOf(") {", swapAt))
        assertFalse("the swap itself carries no alpha layer", swapCall.contains("graphicsLayer"))
    }

    @Test
    fun `the Folio reads its cell glides and its ring in layout and draw lambdas`() {
        assertFalse("a gliding cell is placed by an offset lambda", folio.contains(".offset(x = animatedX") || folio.contains(".offset(x = carriedX"))
        assertEquals(
            "the two shelf cells and the carried cell",
            3,
            Regex("""\.offset \{ IntOffset\((animatedX|carriedX)\.value\.roundToPx\(\), (animatedY|carriedY)\.value\.roundToPx\(\)\) \}""").findAll(folio).count(),
        )
        assertFalse("the glides are States, never `by`", Regex("""val (animatedX|animatedY|carriedX|carriedY) by """).containsMatchIn(folio))
        assertFalse("the ring's width is measured, not composed", folio.contains(".size(width = ringWidth"))
        assertTrue("the ring's width is a State read in a layout lambda", folio.contains("val ringWidth = animateDpAsState(") && folio.contains("ringWidth.value.roundToPx()"))
        assertFalse("never `by`", Regex("""val (ringWidth|ringAnimatedProgress|animatedFocusProgress) by """).containsMatchIn(folio))
    }

    @Test
    fun `the launch card's chip and rename glyph read their animations out of composition, on the house curve`() {
        assertFalse("the chip's fade is not compared in composition", launchCard.contains("shown > 0f"))
        assertTrue(launchCard.contains("val stillFading by remember { derivedStateOf { shown.value > 0f } }"))
        assertTrue(launchCard.contains(".graphicsLayer { alpha = shown.value }"))
        val glyph = launchCard.substringAfter("val inkState = animateFloatAsState(").substringBefore("label = \"home-launch-title-edit-glyph\"")
        assertTrue("the glyph's ink fade names the house easing", glyph.contains("tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)"))
        assertTrue("the highlight behind the rune is drawn from the State", launchCard.contains(".drawBehind { drawRect(palette.runeMark.copy(alpha = inkState.value * 0.16f)) }"))
    }

    /**
     * Motion clocks use the tokens (FocusMs 140, OverlayMs 180) and
     * [com.gruning.mimir.rewrite.core.ui.MimirMotion.Easing]. The exceptions (the shelf's 210 ms ring seat, the coverflow
     * seat spring, the idle fade) are not in this list and stay.
     */
    @Test
    fun `the motion clocks in these files are on the tokens`() {
        assertFalse("the Folio's sheet pan is no longer a spring of its own", folio.contains("spring(") || folio.contains("Spring."))
        assertTrue(
            "it is the overlay clock on the house easing",
            Regex("""anchorAnim\.animateTo\(\s+targetValue = target,\s+animationSpec = tween\(MimirMotion\.OverlayMs, easing = MimirMotion\.Easing\),""").containsMatchIn(folio),
        )
        assertTrue(
            "the About description's page scroll is the overlay clock on the house easing, not the default spring",
            launchCard.contains("scrollState.animateScrollTo(target, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))"),
        )
        assertTrue(
            "the video handoff is named from the focus token rather than restating 140",
            video.contains("internal const val BifrostVisualHandoffMs = MimirMotion.FocusMs"),
        )
    }

    @Test
    fun `the About viewport is outdented by its own inset so its text starts on the heading's edge`() {
        assertTrue(launchCard.contains(".homeLaunchDescriptionOutdent()\n            .fillMaxWidth()"))
        val outdent = launchCard.substringAfter("private fun Modifier.homeLaunchDescriptionOutdent(): Modifier = layout {").substringBefore("\n}\n")
        assertTrue("widens by the inset it then places out by", outdent.contains("constraints.maxWidth + inset") && outdent.contains("placeable.placeRelative(-inset, 0)"))
        assertTrue("and reports the column's own width", outdent.contains("(placeable.width - inset)"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
