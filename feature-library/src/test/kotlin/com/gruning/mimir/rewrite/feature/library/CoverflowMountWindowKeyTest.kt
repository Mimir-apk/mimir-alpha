package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The mount window must follow the ring that is CURRENTLY mounted, not the one composition first saw.**
 *
 * Caught in adversarial review of the PERF-D/CP04 port (2026-09-03), before it reached a second build.
 * The port moved `ShelfCoverflow`'s mount centre behind `derivedStateOf` so a glide stops invalidating
 * the whole composable — correct in itself — but keyed the `remember` on `stops.size`:
 *
 * ```kotlin
 * val mountCentre by remember(stops.size) {                       // WRONG
 *     derivedStateOf { Math.floorMod(ringMotion.center.value.roundToInt(), stops.size) }
 * }
 * ```
 *
 * `rememberCoverflowRingMotion` recreates its state on `identityOrder` as well as `itemCount`
 * (`CoverflowMotion.kt:343`), and its own comment says why: *"A same-sized reorder is a different
 * ring."* So on a same-size reorder the closure keeps the FIRST motion instance. The driver
 * `LaunchedEffect` is re-keyed on `motion`, the captured `Animatable` is abandoned mid-flight, and the
 * derived value never invalidates again.
 *
 * **The failure is silent and does not look like a stale window.** Tiles keep gliding correctly — they
 * read the live motion through their own layout/draw closures — inside a window that has stopped
 * moving. Once focus travels past its edge the focused tile is simply not mounted, so it is not
 * painted. It heals only on remount.
 *
 * No existing test could see it: `CenteredCoverflowWindowTest` exercises the pure window function,
 * `GhostFreeIdentityTest` is a source-text lint over key sites, and neither observes what the
 * `remember` is keyed on. Hence this pin — a removed hazard needs its own gate, or the next refactor
 * reintroduces it for the same tidy-looking reason.
 *
 * The repo already ships the structurally safer form: `CoverflowLinearMotionState.mountIndex`
 * (`CoverflowMotion.kt:468-476`) lives ON the motion instance, so it cannot be captured stale. Moving
 * `ShelfCoverflow` to that shape would make this pin redundant — which is a fine outcome; update it
 * then rather than deleting it.
 */
class CoverflowMountWindowKeyTest {

    private fun source(relative: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$relative")
        assertTrue(
            "could not locate ${file.absolutePath} — if the file moved, move this pin with it rather " +
                "than deleting it.",
            file.isFile,
        )
        return file.readText()
    }

    @Test
    fun `the mount window is remembered against the ring INSTANCE, not just its size`() {
        val code = source("ShelfCoverflow.kt")
        val remembered = Regex("""val mountCentre by remember\(([^)]*)\)""").find(code)?.groupValues?.get(1)
        assertTrue(
            "could not find `val mountCentre by remember(...)` in ShelfCoverflow.kt. If the mount centre " +
                "moved onto the motion state itself (the CoverflowLinearMotionState.mountIndex shape), " +
                "this pin should be rewritten to assert THAT, not deleted.",
            remembered != null,
        )
        assertTrue(
            "the mount window's remember must be keyed on the motion INSTANCE (`ringMotion`). Keyed on " +
                "size alone it captures the first composition's motion, and a same-size reorder " +
                "(CoverflowMotion.kt:343 recreates on identityOrder) leaves it reading an abandoned " +
                "Animatable forever — the window freezes and the focused tile stops being painted once " +
                "focus leaves it. Found keys: `${remembered!!.trim()}`",
            remembered.contains("ringMotion"),
        )
    }

    @Test
    fun `the derived mount centre still reads the ring, so the window follows the glide`() {
        // The other direction: a "fix" that stopped reading `center.value` would key correctly and
        // never move at all. Both halves are the invariant.
        val code = source("ShelfCoverflow.kt")
        val block = Regex(
            """val mountCentre by remember\([^)]*\)\s*\{(.*?)\n {4}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(code)?.groupValues?.get(1).orEmpty()
        assertTrue(
            "the mount centre must derive from ringMotion.center.value — that read is what makes the " +
                "window follow the ring at all",
            block.contains("ringMotion.center.value"),
        )
        assertTrue(
            "it must stay inside derivedStateOf, or every glide frame invalidates the whole composable " +
                "again — the regression this port exists to remove",
            block.contains("derivedStateOf"),
        )
    }
}
