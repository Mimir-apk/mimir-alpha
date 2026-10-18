package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Returning from a Tome could briefly send the focus ring to tile one before it disappeared.
 *
 * **This is not an animation defect and must not be fixed as one.** Nothing here animates badly. The
 * ring is animated *correctly*, to a *wrong* position -- so easing it harder, or shortening it, only
 * makes the wrong movement prettier. What produced the wrong position is one habit repeated in three
 * places: the integer `0` used to mean "this selection has not resolved yet". On a shelf, `0` is not a
 * neutral value; it is the first tile. And all three fallbacks feed motion, so "unknown" did not render
 * as "nothing highlighted", it rendered as "the player is on tile 1" -- and the surface then travelled
 * there and back.
 *
 * The three sites, and what each did:
 *  1. `HomeShelfScreen`'s emphasis seat -- read straight into an `animateFloatAsState`, so an unresolved
 *     selection produced a fully-committed eased glide to seat 0 and an equally eased glide back.
 *  2. `HomeShelfScreen`'s window anchor -- worse than mis-anchoring for a frame: `homeShelfWindowStart`'s
 *     `focus < start -> focus` branch returns 0 outright and the `.also` writes that 0 back into the
 *     holder, so the surface FORGOT where it had been and had to scroll all the way back.
 *  3. `HomeTomeFolio`'s ring seat -- `ringColumn`/`ringRow` are read into `graphicsLayer` with no tween
 *     of their own, so the ring TELEPORTED to seat 0 and only then ran its fade-out. Both artifacts came from one fallback.
 *
 * The unresolved window itself is real and is NOT being removed: focus legitimately has no answer for a
 * frame or two while a scope is pushed/popped and the incoming ring re-registers (see
 * `DefaultFocusAuthority`'s `pushScope` landing, and `homeTomeFolioAuthoritativeSeatIndex` returning
 * null on three separate routes). The fix is that the surfaces hold their last resolved position through
 * that window instead of inventing one.
 *
 * **Instrument, stated plainly.** These are source-text pins. All three sites are inside private
 * `@Composable`s and this module runs plain JUnit with no Compose harness, which is the same constraint
 * and the same established fallback `FileBrowserSupportDimRenderTest` and
 * `CredentialFlowGlassContinuityTest` document. They prove the fallbacks are gone and that a holder
 * feeds each painted value. **They do not prove device ring stability; that needs device evidence.**
 */
class HomeFocusRingUnresolvedSeatTest {

    // The window and the emphasis seat moved from HomeShelfScreen.kt into the shared strip (GameShelf.kt, 2026-10-10).
    private val shelf: String by lazy { source("GameShelf.kt") }
    private val homeShelf: String by lazy { source("HomeShelfScreen.kt") }
    private val folio: String by lazy { source("HomeTomeFolio.kt") }

    @Test
    fun `the shelf emphasis seat holds its last resolved slot instead of falling back to seat zero`() {
        val block = shelf.sliceBetween(
            after = "val selectedSlotOffset = visibleSlots",
            before = "val visualSelectedSlotOffset",
        )
        assertFalse(
            "the emphasis seat must not fall back to `?: 0` -- it is read into animateFloatAsState " +
                "immediately below, so an unresolved selection does not hide the ring, it DRIVES it to " +
                "the first tile. Found: <${block.trim()}>",
            block.contains("?: 0"),
        )
        assertTrue(
            "the emphasis seat must fall back to the remembered last-resolved holder",
            block.contains("emphasisSeatHolder"),
        )
        assertTrue(
            "the holder must exist and be keyed on surfaceEpoch and windowMemoryKey, matching the animatable's own " +
                "key(surfaceEpoch, windowMemoryKey) -- otherwise a genuine surface change resets one and not the other, " +
                "and the ring travels across a switch it should have started settled on",
            shelf.contains("val emphasisSeatHolder = remember(surfaceEpoch, windowMemoryKey)") &&
                shelf.contains("val visualSelectedSlotOffset = key(surfaceEpoch, windowMemoryKey) {"),
        )
    }

    @Test
    fun `the shelf window holds its last resolved start instead of re-anchoring on index zero`() {
        val block = shelf.sliceBetween(
            after = "val windowStart =",
            before = "val visibleSlots =",
        )
        assertTrue(
            "the window must be anchored on selectedIndex itself, not on a `?: 0` stand-in that " +
                "homeShelfWindowStart then resolves to 0 AND writes back into windowStartHolder",
            block.contains("selectedIndex?.let"),
        )
        assertTrue(
            "an unresolved selection must leave the window where it was (windowStartHolder.value), not " +
                "send it to 0. Found: <${block.trim()}>",
            block.contains("?: windowStartHolder.value"),
        )
        assertFalse(
            "the windowAnchor stand-in must be gone entirely -- keeping it beside the fix is how the " +
                "old behaviour comes back the next time someone needs an Int here",
            shelf.contains("val windowAnchor =") || homeShelf.contains("val windowAnchor ="),
        )
    }

    @Test
    fun `the folio ring fades out where it was rather than teleporting to seat zero first`() {
        val block = folio.sliceBetween(
            after = "val ringSeatIndex = pendingDropDestination?.seatIndex ?: authoritySeatIndex",
            before = "val ringStripLeftPx",
        )
        assertFalse(
            "ringColumn/ringRow must not fall back to 0: they are read into graphicsLayer with no tween " +
                "of their own, so the ring JUMPS to the first tile and only then runs its fade. " +
                "Found: <${block.trim()}>",
            block.contains("?: 0"),
        )
        assertTrue(
            "the painted seat must come from a remembered last-resolved holder",
            block.contains("lastPaintedRingSeat"),
        )
        assertTrue(
            "ringVisible must still be keyed on the LIVE ringSeatIndex, not on the held one -- the fix " +
                "changes where the ring is while it hides, never whether or when it hides",
            folio.contains("val ringVisible = ringSeatIndex != null"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
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
