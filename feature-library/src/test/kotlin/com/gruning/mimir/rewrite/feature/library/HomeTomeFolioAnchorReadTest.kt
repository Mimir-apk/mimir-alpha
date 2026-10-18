package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The Folio's anchor spring is read in DRAW and in a `derivedStateOf`, never in plain composition.**
 *
 *  Measured on the Thor before the fix,
 * `dumpsys gfxinfo` over the folio return: **45.76 % janked frames and a p50 of 15 ms against an
 * 8.33 ms budget** — the MEDIAN frame missed, which is what a sustained per-frame recomposition looks
 * like rather than one expensive spike.
 *
 * `anchorAnim` is the single vector Animatable that carries the sheet's travel. Three sites read it
 * during composition, so every frame of every glide re-entered the whole `BoxWithConstraints` content:
 *
 * 1. `rememberUpdatedState(anchorAnim.value.y)` — a snapshot taken purely so the touch lambdas could
 *    read the row axis, three lines from where those same lambdas already read `anchorAnim.value.x`
 *    directly. One axis subscribed; the other did not.
 * 2. the carried-cell pose block — unconditional, but every value it produced was consumed only inside
 *    `if (carriedMember != null && carryPose != null)`. An open, a return and a d-pad move never carry.
 * 3. the focus ring's `translationX`/`translationY` — computed as composition `val`s and handed to a
 *    `graphicsLayer`, while the sheet BENEATH the ring already folded the same anchor into its own
 *    draw lambda (`homeTomeFolioAnchorVector`). The ring was the odd one out on its own surface.
 *
 * This is a **source pin** because the property is *which phase reads the value*, and no runtime seam
 * exposes that. It is the same shape, and the same defect class, as `CoverflowMountWindowKeyTest` on
 * the sibling surface — and the standing lesson that a removed hazard needs its own gate is exactly why
 * it exists rather than trusting the deletion to stay deleted.
 */
class HomeTomeFolioAnchorReadTest {

    private val source: String by lazy {
        val file = File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeTomeFolio.kt",
        )
        assertTrue(
            "could not locate ${file.absolutePath} — if the file moved, move this pin with it rather " +
                "than deleting it.",
            file.isFile,
        )
        file.readText()
    }

    /** Comments and KDoc stripped, so this file's own prose about the defect cannot satisfy it. */
    private fun code(): List<String> = source.lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

    @Test
    fun `the mount window is remembered against the anchor INSTANCE, not the tome id`() {
        val remembered = Regex("""val animatedRowWindowStart by remember\(([^)]*)\)""")
            .find(source)?.groupValues?.get(1)
        assertTrue(
            "could not find `val animatedRowWindowStart by remember(...)` in HomeTomeFolio.kt. If the " +
                "mount window moved onto the motion state itself, rewrite this pin to assert THAT " +
                "rather than deleting it.",
            remembered != null,
        )
        assertTrue(
            "the mount window's remember must be keyed on the Animatable INSTANCE (`anchorAnim`). " +
                "`anchorAnim` is itself remember(state.tome.tomeId), so keying this on the tome id — or " +
                "on nothing — captures the ABANDONED Animatable after a tome change: tiles keep gliding " +
                "through their own draw closures inside a hydration window that has stopped moving, and " +
                "the focused tile stops being mounted once travel passes its edge. Shipped and caught by " +
                "review on the coverflow the day before (CoverflowMountWindowKeyTest). " +
                "Found keys: `${remembered!!.trim()}`",
            remembered.contains("anchorAnim"),
        )
    }

    @Test
    fun `the derived mount window still reads the anchor, so hydration follows the glide`() {
        // The other direction: a "fix" that stopped reading the spring would key correctly and never
        // move. Both halves are the invariant.
        val block = Regex(
            """val animatedRowWindowStart by remember\([^)]*\)\s*\{(.*?)\n {16}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.groupValues?.get(1).orEmpty()
        assertTrue(
            "the mount window must derive from anchorAnim.value.y — that read is what makes hydration " +
                "follow the sheet at all. Found: `${block.trim()}`",
            block.contains("anchorAnim.value.y"),
        )
        assertTrue(
            "it must stay inside derivedStateOf, or every frame of the spring invalidates the whole " +
                "folio again — the regression this pin exists to prevent. Found: `${block.trim()}`",
            block.contains("derivedStateOf"),
        )
    }

    /**
     * **The distinction this pin exists for: the anchor's VALUE must not be snapshotted; its INSTANCE
     * must be.** They look alike and one line separates them.
     *
     * `rememberUpdatedState(anchorAnim.value.y)` reads the spring in composition, so every frame of
     * every glide invalidates the whole folio — the cost this pass removed.
     *
     * `rememberUpdatedState(anchorAnim)` reads a REFERENCE. Composition never subscribes to the spring,
     * so it costs nothing, and it is load-bearing for correctness: `pointerInput(Unit)` never restarts,
     * so the gesture closure is the FIRST composition's, and `anchorAnim` is `remember(tomeId)` — an
     * A→B tome swap replaces it while the folio stays mounted. A directly captured reference is then the
     * abandoned spring of the previous tome, and every touch hit-test is silently off by the difference
     * between the two poses until the folio closes to null.
     *
     * **The first version of this pin matched the substring `rememberUpdatedState(anchorAnim`, which
     * forbade the correct repair as well as the defect, and its failure message asserted that the direct
     * `.x` captures beside it "never needed one". They did; they were latently broken too. Review caught
     * both.** Hence a pin that separates the two forms instead of banning the idiom.
     */
    @Test
    fun `the anchor is snapshotted by instance, never by value`() {
        val valueSnapshots = code().filter { "rememberUpdatedState(anchorAnim.value" in it }
        assertEquals(
            "a `rememberUpdatedState(anchorAnim.value…)` is back. It reads the spring in COMPOSITION, " +
                "so every frame of every glide invalidates the whole folio. Snapshot the INSTANCE " +
                "instead — `rememberUpdatedState(anchorAnim)` — which is free and also correct.",
            emptyList<String>(),
            valueSnapshots,
        )
        assertTrue(
            "the gesture closure must reach the anchor through an instance snapshot " +
                "(`rememberUpdatedState(anchorAnim)`). Without it the closure captures the Animatable " +
                "directly, and `pointerInput(Unit)` never restarts, so after an A→B tome swap it holds " +
                "the PREVIOUS tome's abandoned spring and every touch hit-test is off by the difference " +
                "between the two poses.",
            "rememberUpdatedState(anchorAnim)" in source,
        )
    }

    /**
     * The gesture closure must not read the Animatable directly — both axes, not just the one that
     * regressed. `.x` carried this defect latently before the instance snapshot existed.
     */
    @Test
    fun `the touch gesture closure captures no Animatable directly`() {
        val lines = code()
        val start = lines.indexOfFirst { ".pointerInput(Unit) {" in it }
        assertTrue("could not find the folio's gesture node", start > 0)
        val offenders = (start until minOf(start + 180, lines.size))
            .filter { "anchorAnim.value." in lines[it] }
            .map { "line ${it + 1}: ${lines[it].trim()}" }
        assertEquals(
            "the gesture closure reads `anchorAnim.value.…` directly. `pointerInput(Unit)` never " +
                "restarts, so this closure is the first composition's and that reference goes stale the " +
                "moment the open tome changes — `anchorAnim` is remember(tomeId) and the folio stays " +
                "mounted across an A→B swap (HomeShelfCoordinator.openTome writes the new session over " +
                "the old with no null frame). Read through `currentAnchor` instead. The error is zero " +
                "whenever both tomes sit at the origin, which is why it survives a casual device check.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the focus ring takes its pose in the draw phase`() {
        assertTrue(
            "the ring's painted pose must be draw-phase providers (`val ringPaintedLeftPx: () -> Float`)," +
                " invoked inside its graphicsLayer. Computed as plain composition vals, the ring's " +
                "position arrives by RECOMPOSING the folio every frame — while the sheet beneath it " +
                "already folds the same anchor into its own draw lambda.",
            source.contains("val ringPaintedLeftPx: () -> Float") &&
                source.contains("val ringPaintedTopPx: () -> Float"),
        )
        assertTrue(
            "the providers must be INVOKED in the graphicsLayer, not read as values",
            source.contains("translationX = ringPaintedLeftPx()") &&
                source.contains("translationY = ringPaintedTopPx() -"),
        )
    }

    /**
     * The carried-cell pose is allowed to read the anchor at composition — `animateDpAsState` takes a
     * target and a target is state — but only while something is actually carried.
     */
    @Test
    fun `the carried-cell pose is derived only inside the carry branch`() {
        val lines = code()
        val carryBranch = lines.indexOfFirst {
            it.contains("if (carriedMember != null && carryPose != null)")
        }
        assertTrue("could not find the carry branch in HomeTomeFolio.kt", carryBranch > 0)
        val travelColumn = lines.indexOfFirst { it.contains("val travelColumn = anchorAnim.value.x") }
        assertTrue(
            "could not find `val travelColumn = anchorAnim.value.x`. If the carried pose stopped " +
                "needing the anchor entirely, rewrite this pin to assert its absence.",
            travelColumn > 0,
        )
        assertTrue(
            "`travelColumn` reads the anchor spring at composition, so it must sit INSIDE the carry " +
                "branch (line ${carryBranch + 1}), not above it (found at line ${travelColumn + 1}). " +
                "Above the branch it subscribes the folio to the spring on every glide, to feed maths " +
                "that only a carry consumes — and an open, a return and a d-pad move never carry.",
            travelColumn > carryBranch,
        )
    }
}
