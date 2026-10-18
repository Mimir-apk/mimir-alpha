package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP01 §5.2 : *"Reduced motion
 * is ONE decision, taken once at the hop, applied to all three beats together."*
 *
 * The Folio's own comment (`MimirAppHost.kt:1941-1948` at spec-time) names the failure mode this guards:
 * a scene blur and a panel alpha deciding reduced motion INDEPENDENTLY can end up in different regimes --
 * "the two-clocks defect arriving through the accessibility path instead of the animation one." No
 * Compose harness exists in this module to prove two `LaunchedEffect`s never observe different values at
 * runtime, so this file pins the STRUCTURAL property instead, the same discipline
 * `OnboardingAccessMotionTest` uses for its own "read from one source, not three" pin: exactly one
 * `mimirReducedMotion()` call feeds both CP01 overrides, threaded as a parameter rather than re-derived.
 *
 * **CP02 §5 extends this to beat 3**
 * , the Folio's teardown. The ruling
 * there is explicit: beat 3 must derive reduced motion from `homeTomeLibraryOpenSettled` (which already
 * collapses to one frame under reduced motion, because `instantIncoming`'s `snapTo(1f)` and its
 * `onIncomingSettleChanged(target, true)` fire on the same beat) rather than adding a FOURTH
 * `mimirReducedMotion()` read of its own -- "that is not a reason to special-case reduced motion in beat 3;
 * it is a reason to prefer the latch, which needs no reduced-motion branch at all because it observes the
 * thing reduced motion changes."
 *
 * **Rewritten post-crash, 2026-09-02** to follow beat 3's wait to its new home. It no longer lives inside
 * `HomeShelfCoordinator.kt`'s `closeTomeToLibrary` -- that function launches no coroutine and reads no
 * navigation state at all now, because giving `rememberHomeShelfCoordinator` (a ~2300-line composable) a
 * `MimirNavigationCoordinator` parameter to read it from VerifyError'd the app on cold start
 * . The wait -- and so
 * this ruling -- now lives in `HomeTomeLibraryReturnReleaseEffect` (`MimirAppCoordinator.kt`).
 */
class IncomingOverlayReducedMotionThreadingTest {

    private fun source(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppHost.kt not found from ${File(".").absolutePath}")
    }

    private fun homeShelfCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeShelfCoordinator.kt not found from ${File(".").absolutePath}")
    }

    /** Presence-checked slice, matching MimirAppHostMotionTest's own helper. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    @Test
    fun `the scene-blur override and the panel-alpha override both key off HOME_TOME_LIBRARY`() {
        val code = source()
        assertTrue(
            "the reduced-motion scene-blur settle must be scoped to HOME_TOME_LIBRARY, not every glass " +
                "overlay -- CP01 ships exactly one member",
            code.contains("activeOverlay == MimirOverlayKind.HOME_TOME_LIBRARY"),
        )
        assertTrue(
            "the reduced-motion panel-alpha settle (instantIncoming) must be scoped identically",
            code.contains("active?.kind == MimirOverlayKind.HOME_TOME_LIBRARY"),
        )
    }

    @Test
    fun `MimirAppHost reads reduced motion exactly once and threads it, never re-derives it`() {
        val code = source()
        val hostBody = code.sliceBetween(
            "internal fun MimirAppHost(",
            "internal fun mimirOverlayUsesGlass(",
        )
        val ownReads = Regex("""val reducedMotion = mimirReducedMotion\(\)""").findAll(hostBody).count()
        assertEquals(
            "MimirAppHost must read mimirReducedMotion() exactly once for this hop -- a second read " +
                "inside the same composable tree is exactly the drift risk the spec forbids",
            1,
            ownReads,
        )
        assertTrue(
            "the read value must reach MimirAppOverlayHost as a parameter",
            hostBody.contains("MimirAppOverlayHost(bindings, activeOverlay, reducedMotion)"),
        )
        assertFalse(
            "MimirDirectOverlayPixelFadeHost must NOT independently call mimirReducedMotion() -- it must " +
                "receive the threaded value as a parameter instead",
            code.sliceBetween(
                "private fun MimirDirectOverlayPixelFadeHost(",
                "@Composable\nprivate fun MimirDirectOverlayContent(",
            ).contains("mimirReducedMotion()"),
        )
    }

    @Test
    fun `the incoming hold is wired through the shared pixel-fade timing seam, not a second host`() {
        val code = source()
        // The call site goes through
        // mimirIncomingOverlayHoldMsForPresentation, the entry-path-gated wrapper for the Folio's own return to the Library inheriting this hold (IncomingOverlayHoldEntry
        // PathTest pins that gate directly). mimirIncomingOverlayHoldMs itself stays untouched -- kind
        // only, still one non-zero member -- which this test also still asserts.
        assertTrue(
            "the call site must route through the entry-path-gated wrapper, not the raw per-kind " +
                "function directly, or the Folio-return regression comes back",
            code.contains("holdMs = mimirIncomingOverlayHoldMsForPresentation("),
        )
        assertTrue(
            "the wrapper must still be keyed on the active target's kind",
            code.contains("kind = active?.kind,"),
        )
        assertTrue(
            "and on the Library's own real entry-path signal, not a second clock or a Folio-liveness guess",
            code.contains("homeTomeLibraryOpenOrigin = bindings.navigation.homeTomeLibrary?.openOrigin,"),
        )
    }

    private fun mimirAppCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppCoordinator.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun `HomeShelfCoordinator never calls mimirReducedMotion() -- beat 3's wait no longer lives there`() {
        val code = homeShelfCoordinatorSource()
        assertFalse(
            "HomeShelfCoordinator.kt must not call mimirReducedMotion() anywhere -- if reduced motion " +
                "ever needs to reach this file it must arrive as a threaded parameter, the same discipline " +
                "MimirAppHost.kt already keeps for beats 1 and 2. Beat 3's own wait moved entirely out of " +
                "this file post-crash, so this is now unconditional rather than scoped to one function.",
            code.contains("mimirReducedMotion()"),
        )
    }

    @Test
    fun `CP02 beat 3 -- HomeTomeLibraryReturnReleaseEffect derives reduced motion from the settle latch, never a 4th read`() {
        val code = mimirAppCoordinatorSource()
        val body = code.sliceBetween(
            "private fun HomeTomeLibraryReturnReleaseEffect(",
            "private fun MimirAppLifecycleEffect(",
        )
        assertFalse(
            "HomeTomeLibraryReturnReleaseEffect must NOT call mimirReducedMotion() itself -- CP02 spec " +
                "beat 3 derives reduced motion from homeTomeLibraryOpenSettled " +
                "(already collapsed to one frame under reduced motion by MimirAppHost.kt's " +
                "instantIncoming/onIncomingSettleChanged pairing), not from a fourth independent read " +
                "that could drift from the other three beats.",
            body.contains("mimirReducedMotion()"),
        )
    }
}
