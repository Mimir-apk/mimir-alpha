package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tome header status must resize smoothly without reflowing its sibling, and replaced words
 * need enough time to read. The pin marker's opacity/scale animation is a separate task.
 *
 * A bare AnimatedContent next to a weight(1f) label changes the sibling's available width whenever
 * its status text width changes. A snap SizeTransform removes interpolation but not that end-state
 * width difference. The width(IntrinsicSize.Max) Box reserves the maximum width of every
 * HomeTomeLibraryHeaderStatusCandidates literal; the inner tween resizes from CenterEnd within
 * that fixed reservation, so the sibling never moves.
 *
 * The status uses HomeTomeStatusLegibilityMs (= MimirMotion.OverlayMs); the pin marker keeps
 * HomeTomeMutationAckMs because a marker is noticed while replaced words must be read.
 *
 * These source pins include comments, so naming the ack clock inside the sliced transition block
 * would fail the assertion just as referencing it in code would. The harness cannot reliably sample
 * AnimatedContent/Row layout mid-flight; it checks the real source's structural contract rather than
 * claiming exact device pixel displacement. A Row recalculates a weighted child's width from other
 * children's measured widths on every layout pass, regardless of their size-transition behavior.
 */
class HomeTomeLibraryHeaderStatusMotionTest {

    private val source: String by lazy { sourceFile("HomeTomeLibraryOverlay.kt") }

    private val headerStatusBlock: String by lazy {
        source.sliceBetween(
            "targetState = headerStatus,",
            "label = \"tome-library-header-status\",",
        )
    }

    @Test
    fun theHeaderStatusRunsOnItsOwnLegibilityClockAndNotThePinMarkersAckClock() {
        // The changing header status needs a longer reading interval than the pin marker. The previous contract --
        // asserted by this very test under its old name -- REQUIRED the header to reuse the marker's
        // HomeTomeMutationAckMs. That is what has to change, so the test changes with it, in both
        // directions: the header moves off the ack clock, and the marker stays on it.
        assertTrue(
            "the header status crossfade must run on HomeTomeStatusLegibilityMs -- reading a replaced " +
                "word is a slower task than noticing a marker appear, and 110ms is below the dwell a " +
                "reader needs",
            headerStatusBlock.contains("HomeTomeStatusLegibilityMs"),
        )
        assertFalse(
            "the header status must NOT reference HomeTomeMutationAckMs any more -- that constant is the " +
                "pin marker's approved reference standard, and re-coupling the two is exactly how this " +
                "surface stayed too fast through three fixes",
            headerStatusBlock.contains("HomeTomeMutationAckMs"),
        )
        assertTrue(headerStatusBlock.contains("MimirMotion.Easing"))
    }

    @Test
    fun theSplitActuallySlowsTheLabelAndLeavesTheApprovedMarkerClockAlone() {
        // The source-text pin above proves the header reads a DIFFERENT token; this proves the split was
        // made in the direction the request specified for. A future edit that "tidies" the two constants back
        // to the same number would satisfy the text pin and silently reinstate the defect.
        assertEquals(
            "HomeTomeMutationAckMs is the pin marker's clock and the reference standard " +
                "(\"as smooth as pinned to unpinned\") -- fixing a text surface must not retune it",
            110,
            HomeTomeMutationAckMs,
        )
        assertTrue(
            "HomeTomeStatusLegibilityMs (${HomeTomeStatusLegibilityMs}) must be strictly SLOWER than " +
                "HomeTomeMutationAckMs (${HomeTomeMutationAckMs}) -- the whole point of splitting them",
            HomeTomeStatusLegibilityMs > HomeTomeMutationAckMs,
        )
        assertEquals(
            "HomeTomeStatusLegibilityMs must BE MimirMotion.OverlayMs, not a literal that happens to " +
                "equal it today -- the ladder already classifies this surface (\"upper bound for " +
                "transient overlays, hints, and toast acknowledgement\"), and a shadow literal would " +
                "drift off it silently",
            MimirMotion.OverlayMs,
            HomeTomeStatusLegibilityMs,
        )
    }

    @Test
    fun theHeaderStatusSizeTransformTweensOnTheSharedMotionClockInsteadOfSnapping() {
        // The header status needs one smooth size-and-fade transition, not a snap. Together, snap() + a fixed-width outer
        // reservation left zero width motion anywhere. This asserts snap() is gone from the header status
        // transition, and that the SizeTransform's own animationSpec is a tween on the SAME clock as the
        // fade beside it -- one gesture, not two. Which clock that is, is the sibling test's job (as of
        // the fourth round it is the label's own HomeTomeStatusLegibilityMs, no longer the pin marker's);
        // what this test owns is that all three specs share whichever one it is.
        assertFalse(
            "snap() must not drive the header status SizeTransform any more -- it leaves zero width " +
                "motion, which is the reported snap",
            headerStatusBlock.contains("snap()"),
        )
        assertTrue(
            "the header status AnimatedContent must still supply its own SizeTransform (clip = false, " +
                "unchanged from PR #99) rather than falling back to AnimatedContent's default",
            headerStatusBlock.contains("SizeTransform(clip = false)"),
        )
        val tweenOnSharedClock = "tween(duration, easing = MimirMotion.Easing)"
        val occurrences = Regex(Regex.escape(tweenOnSharedClock)).findAll(headerStatusBlock).count()
        assertTrue(
            "expected the fade (fadeIn + fadeOut) AND the SizeTransform's size animation to all use the " +
                "literal $tweenOnSharedClock -- found $occurrences occurrence(s) in the transitionSpec, " +
                "expected at least 3 (fadeIn, fadeOut, size)",
            occurrences >= 3,
        )
    }

    @Test
    fun theTomePinIsTheGamelistsMannazPlateOnTheOwnersReferenceClock() {
        // Use the shared gamelist pin component to address reported low visibility.
        //
        // This test used to slice between "val pinMarkerPresence by animateFloatAsState(" and
        // ".rotate(45f)" and assert the marker's own alpha/scale contract. BOTH anchors are gone by
        // design: the bare rotated diamond was replaced by the gamelist's stamped MimirMannazPin, which
        // carries its own ground. That contract now lives in MimirMannazPinTest, next to the component it
        // describes, and this test guards the WIRING instead -- which is the part app/ actually owns.
        // A WINDOW, not sliceBetween(...,")"): the first ")" after the call belongs to
        // Modifier.align(Alignment.TopEnd), so a naive slice stops before the arguments this test exists
        // to check. That trap ate one iteration of this very test.
        val callIndex = source.indexOf("MimirMannazPin(")
        val markerBlock = if (callIndex < 0) "" else {
            source.substring(callIndex, minOf(callIndex + 600, source.length))
        }
        assertTrue(
            "the tome collection must render the shared component, not a local re-draw -- a " +
                "fourth copy of the Mannaz is how three drifting copies of one helper happened before",
            source.contains("MimirMannazPin("),
        )
        assertTrue(
            "the pin must be driven by the tome's pinned state",
            markerBlock.contains("present = entry.pinned"),
        )
        assertTrue(
            "HomeTomeMutationAckMs must be passed EXPLICITLY. It is the reference standard " +
                "(\"as smooth as pinned to unpinned\"); letting it fall back to MimirMannazPin's own " +
                "default would silently change the established acknowledgement timing",
            markerBlock.contains("durationMs = HomeTomeMutationAckMs"),
        )
        assertFalse(
            "the rejected treatment must not come back: a bare rotated square filled with an accent and " +
                "no ground of its own is exactly what he could not see",
            source.contains(".rotate(45f)"),
        )
        assertFalse(
            "The Tome's pinned mark keeps MimirMannazPin's default, without a mark gleam: sparkle is " +
                "for a GAME's favourite mark only " +
                "and does not extend to Tome marks. Extending sparkleOnMark here requires a design decision",
            markerBlock.contains("sparkleOnMark"),
        )
    }

    @Test
    fun theHeaderStatusReservesTheWidthOfItsWidestPossibleValueSoItsSiblingNeverReflows() {
        // Pin/unpin was reported to jump on device build 638158cb037a0eb0 -- AFTER the
        // SizeTransform-pinned-to-snap() fix already merged. snap() removes the smooth ramp between two
        // different measured sizes; it does not make the two sizes equal, so the weight(1f) sibling still
        // got reflowed every pin/unpin. This asserts the width-reservation wrapper that fixes that is
        // present, and that it sits AROUND (not after) the AnimatedContent it protects.
        assertTrue(
            "the header status must sit inside a Modifier.width(IntrinsicSize.Max) Box -- otherwise, even " +
                "with SizeTransform pinned to snap(), AnimatedContent's own measured width still " +
                "legitimately differs by content length on every pin/unpin, and the weight(1f) " +
                "\". TOME LIBRARY\" text beside it in the same Row still gets reflowed in the same layout " +
                "pass, exactly the residual jump device build 638158cb037a0eb0 reported after the " +
                "snap()-only fix",
            source.contains("Modifier.width(IntrinsicSize.Max)"),
        )
        assertTrue(
            "the width-reservation sizer must enumerate every literal headerStatus can produce " +
                "(HomeTomeLibraryHeaderStatusCandidates), or it reserves the wrong width",
            source.contains("HomeTomeLibraryHeaderStatusCandidates.forEach"),
        )
        val widthIndex = source.indexOf("Modifier.width(IntrinsicSize.Max)")
        val sizerIndex = source.indexOf("HomeTomeLibraryHeaderStatusCandidates.forEach")
        val animatedContentIndex = source.indexOf("targetState = headerStatus,")
        assertTrue(
            "the width-reservation Box must wrap the AnimatedContent, not sit after/beside it",
            widthIndex in 0 until animatedContentIndex,
        )
        assertTrue(
            "the invisible sizer enumerating every headerStatus literal must sit before AnimatedContent, " +
                "inside the same reservation Box",
            sizerIndex in 0 until animatedContentIndex,
        )
    }

    @Test
    fun theHeaderStatusCandidateListIsExhaustive() {
        // Every literal the `headerStatus` when-expression can produce must also appear in the sizer's
        // candidate list, or a future new/renamed status string silently isn't reserved for and the jump
        // returns for that one value specifically -- this is the same "no compiler support for a discard"
        // shape this codebase has hit before (N6's plumbing-hop gap), one layer up: a missed literal here
        // is not a compile error.
        val headerStatusBlock = source.sliceBetween(
            "val headerStatusRes = state.pinFeedback?.let {",
            "// fillMaxWidth(fraction)",
        )
        // The statuses are string resources now (localization step 3), so the pin reads their ids, not literals.
        val resourcePattern = Regex("R\\.string\\.(app_tome_library_status_[a-z_]+)")
        val resources = resourcePattern.findAll(headerStatusBlock).map { it.groupValues[1] }.toSet()
        assertTrue(
            "expected at least the 9 known header status strings from headerStatusRes's source, found $resources",
            resources.size >= 9,
        )
        val candidatesBlock = source.sliceBetween(
            "internal val HomeTomeLibraryHeaderStatusCandidates = listOf(",
            ")",
        )
        val candidates = resourcePattern.findAll(candidatesBlock).map { it.groupValues[1] }.toSet()
        assertEquals(
            "HomeTomeLibraryHeaderStatusCandidates must list EXACTLY the resources headerStatusRes's " +
                "when-expression can produce -- a mismatch means either a status string isn't reserved " +
                "for (jump returns) or the sizer carries a dead entry",
            resources,
            candidates,
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
