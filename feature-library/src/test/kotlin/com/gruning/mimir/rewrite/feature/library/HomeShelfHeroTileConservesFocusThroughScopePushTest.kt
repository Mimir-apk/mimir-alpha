package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home shelf's half of core-input's **two-channel focus law**, pinned at the source.
 *
 * Rendered focus state must stay stable while the ring disappears until input returns. One predicate
 * previously drove both channels, keeping the ring lit on a surface without input. This file pins both:
 *
 * - **STATE conserved** ([`isRenderedFocusIn`]) — the hero tile's scale/alpha. Losing this is the
 *   original reported snap.
 * - **RING lit** ([`isLitFocusIn`]) — the stationary focus ring, the empty-shelf action, the dock seat.
 *   Conserving these is the ghost trail.
 *
 * No Compose harness exists in this module, so — matching this project's own precedent for the same gap
 * (`HomeLaunchCardTest.homeLaunchCardSource()`, `UtilityPrimaryActionFocusAffordanceTest`) — this isolates
 * `HomeShelfScreen`'s own source text and asserts its wiring directly, bounded on the next top-level
 * declaration (`homeShelfTitleTrailingReserve`, confirmed unique in the file) so a match can only come
 * from inside `HomeShelfScreen` itself.
 *
 * The mechanism the STATE half pins: `HomeShelfScreen`'s per-slot `focused` boolean used to be
 * `selected && focusState.scope == HomeShelfScope && focusState.focused == slot.item.focusKey` — which
 * goes false the instant ANY other scope becomes active, feeding `homeShelfTileTargetScale(focused, ...)`
 * into a `LaunchedEffect(targetScale) { if (targetScale < roleScale.value) roleScale.snapTo(targetScale)
 * }` (`HomeShelfScreen.kt`, "FUX-12n stabilization law") — an immediate, unanimated `snapTo`. That is the
 * reported snap, verified at the source before the fix, not inferred.
 *
 * The mechanism the RING half pins: nothing about the ring's own draw was ever broken — it is gated on a
 * plain `if`, and it hard-cuts in and out as it always has. What broke was the gate reading the conserved
 * key, which lights a ring in a scope that does not own input. Both directions of that are covered below,
 * because the ghost appears on the way out (tile keeps its ring in the dock) AND on the way back (dock
 * keeps its seat on the shelf).
 */
class HomeShelfHeroTileConservesFocusThroughScopePushTest {

    private fun homeShelfScreenSource(): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeShelfScreen.kt not found from ${File(".").absolutePath}")
    }

    /** Bounded on the next top-level declaration -- `homeShelfTitleTrailingReserve` occurs exactly once
     * in the file (as does `fun HomeShelfScreen(`), so this cannot pick up text from a different function. */
    private fun homeShelfScreenFunctionBody(): String = homeShelfScreenSource()
        .substringAfter("fun HomeShelfScreen(")
        .substringBefore("internal fun homeShelfTitleTrailingReserve(")

    /**
     * The tiles and the ring moved from `HomeShelfScreen` into the shared strip (GameShelf.kt, 2026-10-10). The strip reads
     * the scope it is handed, so the STATE and RING pins below are made on its body, and `HomeShelfScreen` is pinned to
     * hand it Home's scope. The empty-shelf action and the dock stay in the screen and are pinned there as before.
     */
    private fun gameShelfBody(): String = gameShelfStripBody()

    @Test
    fun `STATE channel - the hero tile's scale paints from renderedFocusIn, not a live-only scope check`() {
        val body = gameShelfBody()

        assertTrue(
            "Home's shelf must be given Home's scope, or the strip's rendered-focus read is not Home's",
            // Asked of the call itself: `HomeShelfScope` is also named by the screen's focus registration, which would satisfy the whole body.
            gameShelfCallInHome(homeShelfScreenFunctionBody()).contains("scope = HomeShelfScope,"),
        )
        assertTrue(
            "the hero tile's focused boolean must route through FocusAuthority.isRenderedFocusIn " +
                "so it keeps its scale and alpha while another scope (Utility, the dock, any modal) is " +
                "active -- focus conservation must survive the ring split",
            body.contains("authority.isRenderedFocusIn(scope, slot.item.focusKey, focusState)"),
        )
        assertFalse(
            "the pre-fix live-only comparison must be gone -- its presence means the tile still collapses " +
                "to its rest pose the instant scope != HomeShelfScope",
            body.contains("focused = selected && focusState.scope == scope"),
        )
    }

    @Test
    fun `RING channel - the stationary ring and the empty-shelf action go dark when the scope loses input`() {
        val body = homeShelfScreenFunctionBody()
        val strip = gameShelfBody()

        assertTrue(
            "the stationary focus ring's draw gate must read the LIT channel -- it is the literal thing " +
                "hidden until the scope regains input",
            strip.contains("authority.isLitFocusIn(scope, selectedItem.focusKey, focusState)"),
        )
        assertTrue(
            "HomeShelfEmptyAction's accent fill is focus indication, so it reads the LIT channel too",
            body.contains("authority.isLitFocusIn(HomeShelfScope, HomeShelfOpenLibraryKey, focusState)"),
        )
        assertFalse(
            "the ring must NOT be conserved -- a ring painted from the remembered key is the ghost trail " +
                "reported on device after conservation was introduced",
            strip.contains("authority.isRenderedFocusIn(scope, selectedItem.focusKey, focusState)"),
        )
        assertFalse(
            "the empty-shelf action must NOT be conserved either, for the same reason",
            body.contains("authority.isRenderedFocusIn(HomeShelfScope, HomeShelfOpenLibraryKey, focusState)"),
        )
    }

    @Test
    fun `RING channel - the Home dock's lit seat dies with the dock scope`() {
        val body = homeShelfScreenFunctionBody()

        assertTrue(
            "the dock's focusedAction must read the LIT channel: the dock is persistent visible chrome " +
                "next to the tiles, never a covered surface, so a seat left lit after focus returned to " +
                "the shelf is a second ring with no input behind it",
            body.contains("authority.litFocusIn(HomeShelfQuickRailScope, focusState)"),
        )
        assertFalse(
            "the conserved read that shipped for one build must be gone",
            body.contains("authority.renderedFocusIn(HomeShelfQuickRailScope, focusState)"),
        )
    }
}
