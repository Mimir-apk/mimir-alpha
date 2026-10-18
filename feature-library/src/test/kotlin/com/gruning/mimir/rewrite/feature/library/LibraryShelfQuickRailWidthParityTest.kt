package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.mimirQuickRailTargetWidth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIB-QR-1 (dock-size divergence surfaced by the viewport-aware quick rail,
 * commit `7a056f26`): the shelf Column in `LibraryScreen.kt` applied its own start/end padding
 * (`ShelfHorizontalGutter`, 20dp each side in the non-compact branch) BEFORE `ShelfQuickRailHost`, so the
 * rail's own `BoxWithConstraints` maxWidth there was the raw viewport minus 40dp, while Home's rail
 * (`HomeShelfScreen.kt`, out of this lane's fence) measures the FULL raw viewport with no side gutter --
 * its `BoxWithConstraints` carries no padding at all. Two different `mimirQuickRailTargetWidth` results on
 * the SAME device: the Nova (640dp) solved Home 368dp, Library 352dp. Exactly the class of "two dock
 * sizes on one device" defect already fixed for dock POSITION the same day
 * (`homeShelfQuickRailBottomGapDp`, pinned end-to-end in `HomeShelfGeometryTest.homeAndLibraryDocksShare
 * TheSameBottomGap`) -- this is that fix's WIDTH counterpart.
 *
 * **Correction to the brief's own premise, verified here, not silently absorbed:** the brief stated the
 * Odin (832.5dp) rail was "unscaled on both surfaces today." True for Home (832.5 - 2x136dp reserve =
 * 560.5dp target, comfortably above the 528dp preferred rail width -- `MimirFinalShelfPrimitivesTest`'s
 * own pin). FALSE for Library: its pre-fix effective width was 832.5 - 40dp = 792.5dp, solving a target of
 * 520.5dp -- BELOW 528dp, so it silently entered `mimirQuickRailSlotWidths`' uniform-scale branch (no
 * floor engages: Quick Launch scales to ~102.5dp against a 96dp floor, Files to ~70.9dp against a 64dp
 * floor) and rendered ~520.5dp, not 528dp. A ~7.5dp (1.4%) divergence, imperceptible next to the Nova's
 * 16dp gap but the same defect at the same viewport -- this fix repairs it too, landing Library on the
 * identical unscaled 528dp Home already had.
 *
 * Fixed by moving the rendered `ShelfQuickRailHost` out of the padded Column entirely: it now renders as
 * a Box sibling of the Column, at the Column's own un-padded ancestor level (matching Home's placement),
 * so its `fillMaxWidth()` sees the identical raw viewport Home's rail does. A `Spacer` of the same
 * `ShelfQuickRailLaneHeight` takes the host's old place inside the Column so the weighted coverflow Box
 * does not grow into the freed lane and re-center the coverflow -- and the sibling's explicit Y offset
 * (`viewportHeight - bottomPaddingDp - RailHeight`) reproduces the Column's old flush-bottom placement
 * exactly, so the vertical parity landed the same day (`homeShelfQuickRailBottomGapDp`) does not move.
 *
 * This module runs plain JUnit with no Compose harness (`feature-library`'s Compose tests live in
 * `androidTest`, which needs a device -- see `HomeTouchGrammarTest`'s own note, same split adopted here):
 *  - the NUMBERS are pinned below as plain arithmetic against the same `mimirQuickRailTargetWidth`
 *    function both surfaces call, at the widths the report and the brief both name;
 *  - the WIRING -- that the rail actually left the padded Column and the Spacer actually took its place --
 *    is pinned as comment-stripped source text, because a pure function has no opinion about where in the
 *    Compose tree it gets called from, and a future re-nesting would leave the numbers below green while
 *    silently reopening the bug.
 */
class LibraryShelfQuickRailWidthParityTest {

    // ── The numbers ──────────────────────────────────────────────────────────────────────────────

    /** Mirrors `LibraryScreen.kt`'s private `ShelfHorizontalGutter` -- the PRE-FIX subtraction only, kept
     *  here to document the delta this fix removes. Not read by production code. */
    private val PreFixLibraryHorizontalGutterDp = 20f

    @Test
    fun homeAndLibraryNowSolveTheSameRailTargetAtBothCertifiedWidths() {
        // Nova (640x480dp, a capture showed X · TOME against FILES): both surfaces hand the rail
        // the SAME raw 640dp post-fix -- Home always did (no Column, no padding, in HomeShelfScreen.kt);
        // Library now does too.
        val novaRaw = 640f
        assertEquals(368f, mimirQuickRailTargetWidth(novaRaw.dp).value, 0.01f)

        // Odin (832.5x468.3dp, OnboardingScreenFitTest's own fixture): both clear the 528dp preferred
        // rail width with real headroom (MimirFinalShelfPrimitivesTest's own pin) and land on the
        // identical unscaled 528dp rail.
        val odinRaw = 832.5f
        assertEquals(560.5f, mimirQuickRailTargetWidth(odinRaw.dp).value, 0.01f)
    }

    @Test
    fun documentsThePreFixLibraryDivergenceThisChangeRemoves() {
        val novaRaw = 640f
        val novaPreFixEffective = novaRaw - 2 * PreFixLibraryHorizontalGutterDp
        assertEquals(600f, novaPreFixEffective, 0.01f)
        // The report's own numbers, AS MEASURED AT THE TIME: Home 368dp vs pre-fix Library 352dp on the
        // identical Nova. Re-pinned 2026-08-21: MimirRunicQuickRailDefaults.MinTargetRailWidth was
        // re-derived from 352dp to 288dp alongside the equal-seat-width change (see its own KDoc), so
        // this SAME pure-function call now returns a different number for a reason unrelated to this
        // file's own fix -- 600dp's reserve-derived value (600 - 2x136 = 328dp) no longer needs the
        // structural floor to clamp it (328dp > 288dp), where it used to (328dp < the old 352dp floor).
        // The historical "352dp" the report measured is preserved in this comment and the class doc above
        // rather than silently renumbered.
        assertEquals(328f, mimirQuickRailTargetWidth(novaPreFixEffective.dp).value, 0.01f)
        assertEquals(368f, mimirQuickRailTargetWidth(novaRaw.dp).value, 0.01f)

        val odinRaw = 832.5f
        val odinPreFixEffective = odinRaw - 2 * PreFixLibraryHorizontalGutterDp
        assertEquals(792.5f, odinPreFixEffective, 0.01f)
        // Below 528dp preferred -- the rail was already scaled on Library; see this file's class doc for the full arithmetic.
        assertEquals(520.5f, mimirQuickRailTargetWidth(odinPreFixEffective.dp).value, 0.01f)
        assertTrue(520.5f < 528f)
        assertEquals(560.5f, mimirQuickRailTargetWidth(odinRaw.dp).value, 0.01f)
    }

    // ── The wiring ───────────────────────────────────────────────────────────────────────────────

    /**
     * A numeric pin alone cannot see WHERE in the Compose tree the rail is called from -- someone could
     * re-nest `ShelfQuickRailHost` back inside the padded Column and every assertion above would stay
     * green. This pins the shape: the padded Column's own text (sliced on its unique padding literal)
     * must not call the rail host, must still reserve the lane with a same-height Spacer, and the rail
     * host's one real call site must live outside that slice.
     *
     * **Differential-replayed against pre-fix source** (the LibraryScreen.kt content at the commit this
     * fix was written against, fetched via `git show`; that commit did not touch this file, so its
     * content is unchanged by later, unrelated commits landing on this branch): the load-bearing
     * assertion below is the Spacer/lane-reservation check and the tightened `laneGap` check, not the
     * first `assertFalse` -- that one is DELIBERATELY kept but is vacuous by construction (the slice it
     * inspects always ends at the first `ShelfQuickRailHost(`, so it can never contain that string,
     * pre-fix or post-fix). It stays for readability/documentation of intent; the `laneGap` assertion
     * right after it is what actually catches a re-nesting that keeps the Spacer too (something the
     * vacuous check and the call-site count below both miss). Confirmed by replaying this file's three
     * checks, via an equivalent Kotlin-comment-stripping harness (not this JUnit test itself, which only
     * runs against the current tree), against that pre-fix source: the Spacer/lane-reservation
     * `assertTrue` fails there (no Spacer exists yet), and the `laneGap` slice's own anchor (the Spacer
     * literal) is simply absent -- both fail loudly, for different, complementary reasons.
     */
    @Test
    fun theQuickRailHostRendersOutsideThePaddedColumnWithASpacerReservingItsOldLane() {
        val src = code("LibraryScreen.kt")

        // Unique to the shelf Column's own padding block (verified: the only "end = geometry.horizontal
        // GutterDp.dp," in the file). Slicing from here to the rail host's one real call site captures
        // exactly the padded Column's remaining body.
        val paddedColumnTail = src.sliceBetween(
            after = "end = geometry.horizontalGutterDp.dp,",
            before = "ShelfQuickRailHost(",
        )
        // Vacuous by construction (see class doc) -- kept only as a readable statement of intent; the
        // `laneGap` assertion below is the one that actually discriminates this regression.
        assertFalse(
            "the quick rail must not call back into the padded Column -- its own maxWidth there is the " +
                "viewport minus 2x the Column's horizontal gutter, diverging from Home's un-padded rail",
            paddedColumnTail.contains("ShelfQuickRailHost"),
        )
        assertTrue(
            "the Column must still reserve the rail's old vertical lane with a same-height Spacer, or " +
                "the weighted coverflow Box grows into it and re-centers the coverflow",
            paddedColumnTail.contains("Spacer(") && paddedColumnTail.contains("ShelfQuickRailLaneHeight"),
        )

        // The REAL regression catch: between the lane-reserving Spacer and the rail host's one real call
        // site there must be nothing but the Column's own closing brace. Unlike the vacuous check above,
        // this one fails on "re-nested the rail call AND kept the Spacer" too -- any code appearing here
        // (a second rail call, a stray Box, anything) is exactly this defect's shape.
        val laneGap = src.sliceBetween(
            after = "Spacer(Modifier.fillMaxWidth().height(ShelfQuickRailLaneHeight))",
            before = "ShelfQuickRailHost(",
        )
        assertEquals(
            "only the Column's closing brace may sit between the lane-reserving Spacer and the rail " +
                "host's real call site",
            "}",
            laneGap.trim(),
        )

        // Exactly one call site left: the sibling below the Column. (Two textual occurrences of
        // "ShelfQuickRailHost(" remain in the whole file -- this call and the `private fun` declaration
        // itself; `\s*content` tolerates either being written on one line or with `content` on its own
        // line, and is unique to a CALL either way since the declaration's own parameter is named
        // `content:`, never bare `content`.)
        val callSites = Regex("""ShelfQuickRailHost\(\s*content\s*=""").findAll(src).count()
        assertEquals("exactly one ShelfQuickRailHost call site is expected", 1, callSites)
    }

    // ── Source-pin plumbing (mirrors HomeTouchGrammarTest's own, same module) ───────────────────────

    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        val tail = substringAfter(after)
        assertTrue("closing anchor missing after '$after': $before", tail.contains(before))
        return tail.substringBefore(before)
    }

    private fun code(name: String): String = HomeTouchGrammarTest.stripKotlinComments(source(name))

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
