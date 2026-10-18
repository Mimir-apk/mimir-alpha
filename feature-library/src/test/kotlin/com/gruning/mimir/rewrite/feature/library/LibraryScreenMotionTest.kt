package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryScreenMotionTest {
    @Test
    fun libraryHeroBackdropAcceptsOnlyEnabledLocalHeroMedia() {
        val local = "file:///media/chrono-hero.webp#mv=4"

        assertEquals(local, libraryHeroBackdropUri(GamelistBackdropMode.BIFROST, local))
        assertEquals(local, libraryHeroBackdropUri(GamelistBackdropMode.BIFROST_BACKDROP, local))
        assertNull(libraryHeroBackdropUri(GamelistBackdropMode.AURORA, local))
        assertNull(libraryHeroBackdropUri(GamelistBackdropMode.OFF, local))
        assertNull(libraryHeroBackdropUri(GamelistBackdropMode.BIFROST, "https://example.invalid/hero.png"))
        assertNull(libraryHeroBackdropUri(GamelistBackdropMode.BIFROST, "  "))
    }

    @Test
    fun libraryHeroTitleChangeRequiresNeutralFieldBeforeDestinationReveal() {
        assertTrue(libraryHeroRequiresNeutralHandoff("file:///hero-a.webp", "file:///hero-b.webp"))
        assertTrue(!libraryHeroRequiresNeutralHandoff("file:///hero-a.webp", "file:///hero-a.webp"))
        assertTrue(!libraryHeroRequiresNeutralHandoff(null, "file:///hero-b.webp"))
        assertTrue(libraryHeroRequiresNeutralHandoff("file:///hero-a.webp", null))
        assertEquals(
            "file:///hero-a.webp",
            libraryHeroOwnedPresentationUri("file:///hero-a.webp", "file:///hero-a.webp"),
        )
        assertNull(libraryHeroOwnedPresentationUri("file:///hero-a.webp", "file:///hero-b.webp"))
        assertNull(libraryHeroOwnedPresentationUri("file:///hero-a.webp", null))
    }

    /**
     * The held outgoing hero — policy 2026-09-03, *"keep the outgoing hero"*.
     *
     * Measured before the change on the Thor: a list-view platform switch left the surface black for
     * 50–83 ms, and longer the bigger the destination list (62.5 ms mean into a 28-row list against
     * 29.2 ms out of one, ranges 50–83 vs 17–33, no overlap).
     *
     * This pins the decision table only. What it CANNOT see is the property that actually mattered —
     * that the decision is read in composition rather than from an effect — because a frame of latency
     * is invisible to a JVM test of a pure function. That half is device-verified, not pinned here, and
     * saying so is cheaper than a green test implying cover it does not give.
     */
    @Test
    fun theOutgoingHeroIsHeldOnlyWhileAHandoffIsActuallyInFlight() {
        val a = "file:///hero-a.webp"
        val b = "file:///hero-b.webp"

        // A handoff in flight: A keeps painting while B settles.
        assertEquals(a, libraryHeroHeldOutgoingUri(outgoingUri = a, requestedUri = b, holding = true))
        // Released: the bound expired, or the crossfade finished.
        assertNull(libraryHeroHeldOutgoingUri(outgoingUri = a, requestedUri = b, holding = false))
        // Not a handoff at all -- the outgoing IS the request, so holding it would double-draw the hero
        // that `libraryHeroOwnedPresentationUri` is already painting.
        assertNull(libraryHeroHeldOutgoingUri(outgoingUri = a, requestedUri = a, holding = true))
        // Nothing was presented before, so there is nothing to hold. This is the cold-mount case and it
        // must stay a plain reveal rather than acquiring a phantom outgoing layer.
        assertNull(libraryHeroHeldOutgoingUri(outgoingUri = null, requestedUri = b, holding = true))
        // Leaving for a game with NO art still holds, so the old hero fades out under the caller's
        // field fade instead of being cut. Dropping this case is how the fade silently becomes a cut.
        assertEquals(a, libraryHeroHeldOutgoingUri(outgoingUri = a, requestedUri = null, holding = true))
    }

    /**
     * The hold is a BOUND, not a duration: it exists so a stalled decode cannot strand A's hero under
     * B's title. Pinned against the page glide it is derived from — if the glide moves, this moves with
     * it, and if someone retunes the hold to a free-floating number this fails and asks why.
     */
    @Test
    fun theOutgoingHoldIsBoundedByOnePageGlide() {
        assertEquals(MimirMotion.ModeTransitionMs.toLong(), LibraryHeroHandoffHoldMs)
        assertTrue(LibraryHeroHandoffHoldMs > 0L)
    }

    @Test
    fun modeChangesRotateOneOutgoingSnapshotAndReversalsReplaceIt() {
        val firstChange = nextLibraryLayerFrame(
            currentPrimaryLayerIndex = 0,
            retainOutgoing = true,
        )
        assertEquals(1, firstChange.primaryLayerIndex)
        assertEquals(0, firstChange.outgoingLayerIndex)

        val reversal = nextLibraryLayerFrame(
            currentPrimaryLayerIndex = firstChange.primaryLayerIndex,
            retainOutgoing = true,
        )
        assertEquals(0, reversal.primaryLayerIndex)
        assertEquals(1, reversal.outgoingLayerIndex)

        val stable = nextLibraryLayerFrame(
            currentPrimaryLayerIndex = reversal.primaryLayerIndex,
            retainOutgoing = false,
        )
        assertNull(stable.outgoingLayerIndex)
    }

    @Test
    fun libraryOutgoingLayerUsesTheBoundedReferenceWindow() {
        assertTrue(LibraryOutgoingLayerDurationMs in 140..220)
        assertEquals(1f, LibraryOutgoingLayerStartAlpha, 0f)
    }

    @Test
    fun platformSwitchesKeepOneMountedGamelistPrimarySurface() {
        assertEquals(LibraryPrimarySurfaceKey.Shelf, libraryPrimarySurfaceKey(null))
        assertEquals(
            LibraryPrimarySurfaceKey.Gamelist,
            libraryPrimarySurfaceKey(com.gruning.mimir.rewrite.core.model.PlatformId.SNES),
        )
        assertEquals(
            libraryPrimarySurfaceKey(com.gruning.mimir.rewrite.core.model.PlatformId.SNES),
            libraryPrimarySurfaceKey(com.gruning.mimir.rewrite.core.model.PlatformId.GBA),
        )
    }

    /** "The destination's own rows are on screen" -- the condition the glide's contract names. */
    private fun pageMotionApplies(
        platformId: com.gruning.mimir.rewrite.core.model.PlatformId,
        motion: GamelistPlatformSwitchMotion?,
        rowsOnScreen: Boolean,
    ) = libraryGamelistPageMotionApplies(
        platformId = platformId,
        gamesLoaded = rowsOnScreen,
        gamesEmpty = !rowsOnScreen,
        withinSwitchGrace = false,
        motion = motion,
    )

    /**
     * The property, not just the cases: **the page never glides while the loader is showing.** Both
     * predicates answer "is the destination's page real yet", and they drifted apart once already --
     * the loader learned that `gamesLoaded` means "has the query returned", and the glide four lines
     * below it went on reading the same flag as "is there anything to show". Pinning the relationship
     * across the whole truth table is what stops that happening a third time.
     */
    @Test
    fun `the page never glides while the loader is showing`() {
        val gba = com.gruning.mimir.rewrite.core.model.PlatformId.GBA
        val motion = GamelistPlatformSwitchMotion(platformId = gba, direction = 1, sequence = 3L)
        var sawLoader = false
        var sawGlide = false
        for (loaded in listOf(false, true)) {
            for (empty in listOf(false, true)) {
                for (grace in listOf(false, true)) {
                    val loader = libraryGamelistShowsLoader(loaded, empty, grace)
                    val glides = libraryGamelistPageMotionApplies(gba, loaded, empty, grace, motion)
                    if (loader) sawLoader = true else sawGlide = sawGlide || glides
                    assertTrue(
                        "loader=$loader glides=$glides for loaded=$loaded empty=$empty grace=$grace",
                        !(loader && glides),
                    )
                }
            }
        }
        // Guard the guard: a predicate that never says yes would satisfy the property vacuously.
        assertTrue("no loader case exercised", sawLoader)
        assertTrue("no gliding case exercised", sawGlide)
    }

    @Test
    fun destinationPageMotionWaitsForMatchingLoadedPlatform() {
        val snes = com.gruning.mimir.rewrite.core.model.PlatformId.SNES
        val gba = com.gruning.mimir.rewrite.core.model.PlatformId.GBA
        val motion = GamelistPlatformSwitchMotion(platformId = gba, direction = 1, sequence = 7L)

        assertTrue(!pageMotionApplies(gba, motion, rowsOnScreen = false))
        assertTrue(!pageMotionApplies(snes, motion, rowsOnScreen = true))
        assertTrue(pageMotionApplies(gba, motion, rowsOnScreen = true))
        // Batch F: the page starts from nothing (the swap tier); only its travel is a number to check.
        assertTrue(LibraryGamelistPageTravelDp in 16f..32f)
    }

    @Test
    fun viewModeLandingAnimatesOnlyAnActualCarouselListChange() {
        assertTrue(
            !libraryGamelistViewLandingApplies(
                GamelistViewMode.CAROUSEL,
                GamelistViewMode.CAROUSEL,
            ),
        )
        assertTrue(
            libraryGamelistViewLandingApplies(
                GamelistViewMode.CAROUSEL,
                GamelistViewMode.LIST,
            ),
        )
        // Batch F: no scale in the family and no start alpha; the view change is a swap on 8 dp of Y.
        assertTrue(LibraryGamelistViewTravelDp in 6f..12f)
        assertEquals(MimirMotion.SwapTravelDp.toFloat(), LibraryGamelistViewTravelDp, 0f)
    }

    @Test
    fun rapidRetargetPreservesOnlyPixelsAlreadyOwnedByThatLayer() {
        assertEquals(
            0.42f,
            libraryIncomingLayerStartAlpha(
                target = "detail-a",
                cachedLayerTarget = "detail-a",
                cachedLayerAlpha = 0.42f,
                hasOutgoing = true,
            ),
            0f,
        )
        assertEquals(
            0f,
            libraryIncomingLayerStartAlpha(
                target = "detail-b",
                cachedLayerTarget = "detail-a",
                cachedLayerAlpha = 0.42f,
                hasOutgoing = true,
            ),
            0f,
        )
        assertEquals(
            1f,
            libraryIncomingLayerStartAlpha(
                target = "detail-b",
                cachedLayerTarget = null,
                cachedLayerAlpha = 0f,
                hasOutgoing = false,
            ),
            0f,
        )
    }

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    /** `GamelistDestinationPageTransition` from its signature down to the layer it drives. */
    private fun destinationPageTransitionBody(): String {
        val source = sourceOf("LibraryScreen.kt")
        val start = source.indexOf("internal fun GamelistDestinationPageTransition(")
        check(start >= 0) { "GamelistDestinationPageTransition not found -- assertions would be vacuous" }
        // Batch F: the body ends at the handoff that draws it, not at a layer of its own.
        val end = source.indexOf("MimirPageHandoff(", start)
        check(end > start) { "body window missed its own MimirPageHandoff -- assertions would be vacuous" }
        return source.substring(start, end)
    }

    @Test
    fun destinationHoldsItsStartPoseFromTheSwitchAndReleasesItWhenItsRowsLand() {
        val snes = com.gruning.mimir.rewrite.core.model.PlatformId.SNES
        val gba = com.gruning.mimir.rewrite.core.model.PlatformId.GBA
        val motion = GamelistPlatformSwitchMotion(platformId = gba, direction = 1, sequence = 9L)

        assertTrue(
            "the destination must wear the start pose the instant its id lands, before its rows do",
            libraryGamelistPageMotionHolds(gba, motion),
        )
        assertTrue(
            "the glide must not RUN until the destination's own rows are on screen",
            !pageMotionApplies(gba, motion, rowsOnScreen = false),
        )
        assertTrue(pageMotionApplies(gba, motion, rowsOnScreen = true))
        // The seam this used to get wrong: `gamesLoaded` is TRUE with nothing on screen, because the
        // stale-list guard drops `games` to empty while `gamesLoadedForPlatform` already names the
        // destination. Glide here and the 160ms runs over the LOADER, with the rows arriving after it.
        assertTrue(
            "the glide must not run over the loader",
            !libraryGamelistPageMotionApplies(
                platformId = gba,
                gamesLoaded = true,
                gamesEmpty = true,
                withinSwitchGrace = true,
                motion = motion,
            ),
        )

        assertTrue(!libraryGamelistPageMotionHolds(snes, motion))
        assertTrue(!pageMotionApplies(snes, motion, rowsOnScreen = true))
        assertTrue(!libraryGamelistPageMotionHolds(gba, motion = null))
    }

    @Test
    fun pageGlideIsNeverReArmedByItsOwnLoadFlagAndReducedMotionSettlesIt() {
        val body = destinationPageTransitionBody()

        val rememberKey = body.substringAfter("val progress = remember(").substringBefore(")")
        assertTrue(
            "the progress Animatable must NOT be keyed on gamesLoaded -- that re-creates it mid-arrival " +
                "and yanks an already-settled page backwards into the start pose; found remember($rememberKey)",
            !rememberKey.contains("gamesLoaded"),
        )
        assertTrue(
            "it must still be keyed on the destination and the switch sequence; found remember($rememberKey)",
            rememberKey.contains("platformId") && rememberKey.contains("motion?.sequence"),
        )
        assertTrue(
            "the initial pose must be chosen by the HOLD predicate, not by the release predicate",
            body.contains("Animatable(if (holds) 0f else 1f)"),
        )
        assertTrue(
            "travel direction must survive the whole hold -- gating it on shouldAnimate zeroes the offset " +
                "for the entire load and leaves the page nothing to glide from",
            body.contains("motion?.direction?.takeIf { holds }"),
        )

        assertTrue("reduced motion must be honoured explicitly", body.contains("if (reducedMotion) {"))
        assertTrue("reduced motion must SETTLE the page, not merely skip the glide", body.contains("progress.snapTo(1f)"))
        val reducedBranch = body.substringAfter("if (reducedMotion) {").substringBefore("return@LaunchedEffect")
        assertTrue(
            "reduced motion must CONSUME the switch token; an unconsumed token keeps holds true forever " +
                "and would strand the page at the start pose",
            reducedBranch.contains("onMotionSettled"),
        )
    }

    @Test
    fun `an L2 or R2 switch is a swap with a recorded departure, and presses wait for the new list`() {
        val body = destinationPageTransitionBody()
        assertTrue("the swap tier's arrival clock", body.contains("mimirSwapEnterSpec(reducedMotion)"))
        assertTrue(
            "the old list waits for the settle, capped like the page entry",
            body.contains("withTimeoutOrNull(MimirPageMotion.SettleCapMs.toLong())"),
        )
        assertTrue("presses wait until the new list shows", body.contains("MimirTransitionInputGate.hold(gateOwner)"))
        assertTrue(body.contains("MimirTransitionInputGate.release(gateOwner)"))
        val source = sourceOf("LibraryScreen.kt")
        assertTrue("no scale in the family", !source.contains("LibraryGamelistViewStartScale"))
        assertTrue("an arriving page starts from nothing", !source.contains("LibraryGamelistPageStartAlpha"))
        assertTrue("the view change rises into place", source.contains("vertical = true,"))
    }
}
