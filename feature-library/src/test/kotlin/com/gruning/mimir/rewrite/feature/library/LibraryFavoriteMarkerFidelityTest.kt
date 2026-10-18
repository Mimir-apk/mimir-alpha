package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMannazPinHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinHiddenScale
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tome collection reuses the Library gamelist Mannaz pin, so the gamelist marker's drawing moved to `core-ui` as `MimirMannazPin`,
 * where `app`'s Tome collection can reach it. `LibraryFavoriteMarker` stayed behind as a name.
 *
 * This file exists because the failure mode of a hoist is not a crash, it is a QUIET FORK: someone
 * re-inlines the drawing here to tweak the gamelist, both surfaces still compile, both still render
 * a gold rune, and "the same pin" is no longer true. That is not hypothetical in this tree --
 * `mimirPlatformShelfmarkCode`'s KDoc records three drifting copies of one helper, which is why this
 * one is pinned the moment it is created rather than after it drifts.
 *
 * `LibraryFavoriteMarkerTest` and `LibraryShelfStabilizationContractTest` are deliberately left
 * UNEDITED by the hoist: they assert 32 / 24 / 0.88f against the Library's own constant names, and
 * they still pass, which is the evidence that the gamelist's rendering is unchanged.
 */
class LibraryFavoriteMarkerFidelityTest {

    private val source: String by lazy { markerSource() }

    @Test
    fun `the marker delegates to the shared pin instead of drawing its own`() {
        // `sparkleOnMark = true` since 2026-09-30: the gamelist's mark is a GAME's mark, so it plays a focused
        // game's gleam on the rune. The Tome's pinned mark does not pass it; app's
        // HomeTomeLibraryHeaderStatusMotionTest pins that side.
        assertTrue(
            "LibraryFavoriteMarker must call MimirMannazPin -- it is the shared marker component -- and " +
                "pass sparkleOnMark, because this mark is a game's",
            source.contains("MimirMannazPin(present = favorite, modifier = modifier, sparkleOnMark = true)"),
        )
        for (geometry in listOf("drawLine(", "drawRoundRect(", "Brush.verticalGradient", "StrokeCap.Round")) {
            assertFalse(
                "LibraryFavoriteMarker must not carry its own copy of the rune geometry ($geometry). " +
                    "A second copy compiles, renders, and passes every value test in this module while " +
                    "silently making the Tome collection and the gamelist different pins -- exactly the " +
                    "drift mimirPlatformShelfmarkCode's KDoc records happening three times already. " +
                    "Add a parameter to MimirMannazPin instead.",
                source.contains(geometry),
            )
        }
        assertFalse(
            "the branded ink must be chosen once, inside MimirMannazPin, not re-picked here",
            source.contains("BrandGoldBright"),
        )
    }

    @Test
    fun `the marker overrides neither the height nor the clock, so the gamelist keeps every authored default`() {
        // The hoist is additive: MimirMannazPin takes `height` and `durationMs`, both defaulted to
        // what this file used to hardcode. Proving the gamelist is unchanged therefore means proving
        // this call site overrides NEITHER. If a future edit passes one here, the gamelist has moved
        // and its unchanged-defaults contract would no longer hold.
        // (`sparkleOnMark` is not an override of the resting pin: it adds the mark gleam.)
        //
        // The two assertFalse checks below CANNOT stand alone, and this was caught by differential:
        // reverting LibraryFavoriteMarker to its pre-hoist forked body -- no MimirMannazPin call at
        // all -- left this test GREEN, because a file that never calls the component also never
        // overrides its defaults. An absence-only assertion is satisfied most easily by deleting the
        // subject. So the call site is located first, and the overrides are checked inside it.
        val callSite = source.sliceBetween("MimirMannazPin(", ")")
        assertFalse(
            "the gamelist call site must not override the pin height",
            callSite.contains("height ="),
        )
        assertFalse(
            "the gamelist call site must not override the pin's presence clock",
            callSite.contains("durationMs"),
        )
        assertTrue(
            "the call site must pass the caller's own modifier through, or the gamelist loses the " +
                "TopStart alignment and corner inset it positions the mark with",
            callSite.contains("modifier = modifier"),
        )
    }

    @Test
    fun `the Library's constants are aliases of the shared ones, not re-typed literals`() {
        // Equal VALUES are not enough: two independently-typed 32s are equal today and diverge the
        // first time one of them is edited. Pinning the declaration text is what makes the drift
        // impossible rather than merely currently-absent.
        assertTrue(
            "LibraryFavoriteMarkerWidthDp must be declared as MimirMannazPinWidthDp",
            source.contains("LibraryFavoriteMarkerWidthDp = MimirMannazPinWidthDp"),
        )
        assertTrue(
            "LibraryFavoriteMarkerHeightDp must be declared as MimirMannazPinHeightDp",
            source.contains("LibraryFavoriteMarkerHeightDp = MimirMannazPinHeightDp"),
        )
        assertTrue(
            "LibraryFavoriteMarkerHiddenScale must be declared as MimirMannazPinHiddenScale",
            source.contains("LibraryFavoriteMarkerHiddenScale = MimirMannazPinHiddenScale"),
        )
        assertEquals(MimirMannazPinWidthDp, LibraryFavoriteMarkerWidthDp)
        assertEquals(MimirMannazPinHeightDp, LibraryFavoriteMarkerHeightDp)
        assertEquals(MimirMannazPinHiddenScale, LibraryFavoriteMarkerHiddenScale, 0f)
    }

    @Test
    fun `the gamelist still renders the envelope it rendered before the hoist`() {
        // The absolute numbers, restated against the shared constants. If the hoist had quietly
        // re-authored the pin while moving it, this fails even though every alias above still holds.
        assertEquals(32, LibraryFavoriteMarkerWidthDp)
        assertEquals(24, LibraryFavoriteMarkerHeightDp)
        assertEquals(0.88f, LibraryFavoriteMarkerHiddenScale, 0f)
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun markerSource(): String {
        val name = "LibraryFavoriteMarker.kt"
        val dir = "com/gruning/mimir/rewrite/feature/library"
        val candidates = listOf(
            java.io.File("feature-library/src/main/kotlin/$dir/$name"),
            java.io.File("src/main/kotlin/$dir/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
