package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The L2/R2 departing page is the OLD platform's, the list/carousel toggle waits for its
 * covers like L2/R2, and the hero rides the switch.
 */
class LibraryGamelistSwitchFreezeTest {

    private val source = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `the L2 R2 page's content is keyed on the platform, so what departs is the old platform`() {
        val transition = source.substringAfter("internal fun GamelistDestinationPageTransition(")
            .substringBefore("/** Plain memory, not snapshot state")
        val content = transition.substringAfter("CompositionLocalProvider(LocalMimirPageSettling provides pageSettling) {")
        assertTrue(content, content.trimStart().let { it.contains("key(platformId) {") && it.indexOf("key(platformId) {") < it.indexOf("content()") })
    }

    @Test
    fun `the list carousel toggle holds the old view for its covers, capped, and covers in the hold snap in`() {
        val toggle = source.substringAfter("internal fun GamelistViewModeLandingTransition(")
            .substringBefore("internal fun GamelistDestinationPageTransition(")
        assertTrue(toggle.contains("withTimeoutOrNull(MimirPageMotion.SettleCapMs.toLong()) { currentSettle?.invoke() }"))
        assertTrue(toggle.contains("exitReleased = { departureReleased },"))
        assertTrue(toggle.contains("CompositionLocalProvider(LocalMimirPageSettling provides viewSettling) {"))
        assertTrue("reduced motion never holds", toggle.contains("val holdsForCovers = shouldAnimate && !reducedMotion && settle != null"))
        val call = source.substringAfter("GamelistViewModeLandingTransition(\n                            mode = primary.viewMode,")
            .substringBefore(") { presentedMode ->")
        assertTrue("the slice is the toggle's own call", source.contains("GamelistViewModeLandingTransition(\n                            mode = primary.viewMode,"))
        assertTrue(call, call.contains("settle = settleGamelistOpen,") && !call.contains("GamelistDestinationPageTransition("))
    }

    @Test
    fun `both gamelist handoffs bleed past their slot by what their content paints outside it, and put it back`() {
        // a fading page is composited offscreen at its handoff's bounds; the carousel's side cards paint into
        // the Column's padding, and L2/R2's layer carries its bounds along its glide.
        val toggle = source.substringAfter("internal fun GamelistViewModeLandingTransition(")
            .substringBefore("internal fun GamelistDestinationPageTransition(")
        assertTrue(toggle.contains("modifier = modifier.fillMaxSize().gamelistPageBleed(horizontal = horizontalBleed, vertical = LibraryGamelistViewTravelDp.dp),"))
        assertTrue(toggle.contains("Box(Modifier.fillMaxSize().padding(horizontal = horizontalBleed, vertical = LibraryGamelistViewTravelDp.dp)) {"))
        assertTrue("the toggle bleeds by the Column's own padding", source.contains("horizontalBleed = LibraryGamelistBodyPaddingDp,"))
        assertTrue(source.contains(".padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md),"))
        val switch = source.substringAfter("internal fun GamelistDestinationPageTransition(")
            .substringBefore("/** Plain memory, not snapshot state")
        assertTrue(switch.contains("modifier = modifier.gamelistPageBleed(horizontal = LibraryGamelistPageTravelDp.dp),"))
        assertTrue(switch.contains("Box(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistPageTravelDp.dp)) {"))
    }

    @Test
    fun `the hero holds through an L2 R2 hold, decided in the switch's own composition`() {
        assertTrue(source.contains("libraryGamelistPageMotionHolds(primary.openPlatform, gamelistPlatformSwitchMotion) &&"))
        assertTrue(source.contains("platformSwitchReleased.value != gamelistPlatformSwitchMotion?.sequence"))
        assertTrue(source.contains("val backdropHero = if (switchHoldsBackdrop) backdropMemory.hero else liveBackdropHero"))
        assertTrue(source.contains("releasedSequence = platformSwitchReleased,"))
        assertTrue("released with the departing list", source.contains("departureReleased = true\n            releasedSequence?.value = motion?.sequence"))
    }
}
