package com.gruning.mimir.rewrite

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **P0 2026-08-14 — the Odin spent ~80 minutes on the loading rune.**
 *
 * Mechanism, device-reproduced on `emulator-5558` with
 * `am start -n .../.MainActivity --es mimir.openFiles manage` from a cold start:
 *
 *  1. `MimirPrimaryReturnCover` (`MimirAppHost.kt:2217`) is a bare full-screen Mannaz rune. It is added
 *     FIRST to the overlay stack (`MimirOverlayCoordinator.kt:184`), so it is always TOP; it absorbs every
 *     controller action (`MimirOverlayCoordinator.kt:364`), consumes every pointer event, and swallows
 *     Back. Its own source comment claims its worst case is "the 4s absent-row grace".
 *  2. That claim was false. The cover is visible while `HomeShelfCoordinator`'s `launchReturnRestoring` is
 *     true, and two of that flag's inputs — `pendingSurfaceRestore` and `pendingLaunchReturn` — were each
 *     cleared only at the end of an UNBOUNDED `while (isActive)` spin waiting for `FocusAuthority` to make
 *     the Home shelf the active scope with a specific key focused.
 *  3. Any full-screen surface that owns its own `FocusScope` makes that exit unreachable for as long as it
 *     is open. `FileBrowserController.open()` pushes `FocusScope("files")` (`FileBrowserController.kt:602`)
 *     and replaces Home on screen, so neither half of the condition could hold.
 *  4. The cycle closes: the cover waits on Home focus, Home focus cannot happen while the browser is up,
 *     and the browser cannot be dismissed because the cover ate the input. Exit: force-stop only. The main
 *     thread stayed idle in the looper throughout (`/data/anr` dump, `state=S`,
 *     `MessageQueue.nativePollOnce`) — this was never a blocked thread, so nothing timed out, nothing
 *     ANR'd, and background work kept logging normally.
 *
 * These tests pin the fix at both altitudes: the bounded-wait rule itself, and the two call sites actually
 * using it. Only [the source pins][returnCoverArmsUseTheBoundedWait] can catch a regression that deletes
 * the *call*, so they are not decoration — the behavioural tests below all pass against a coordinator that
 * never calls [awaitHomeSurfaceFocusSettled] at all.
 */
class HomeReturnCoverBoundedPatienceTest {

    private companion object {
        /** Comfortably longer than any bounded wait; on `runTest`'s virtual clock it costs no real time. */
        const val VirtualWatchdogMs = 10L * 60L * 1000L
        const val RetryMs = 100L
    }

    /**
     * **THE P0 PIN.** A settle attempt that can never succeed — the browser owning the focus scope — must
     * still terminate.
     *
     * The watchdog is what makes the failure legible rather than a hang: with the deadline check removed
     * from [awaitHomeSurfaceFocusSettled], the loop advances `runTest`'s virtual clock forever via its own
     * `delay`, [withTimeoutOrNull] fires, the result is null, and this assertion names the defect.
     * (Differentially verified: with `if (nowMillis() >= deadlineMs) return false` deleted, this test fails
     * with the message below rather than passing.)
     */
    @Test
    fun `a focus that never lands releases the cover instead of holding it forever`() = runTest {
        var attempts = 0
        var now = 0L
        val landed = withTimeoutOrNull(VirtualWatchdogMs) {
            awaitHomeSurfaceFocusSettled(
                deadlineMs = HomeSurfaceRestoreFocusGraceMs,
                nowMillis = { now },
                awaitFrame = { now += 16L },
                retryMs = RetryMs,
            ) {
                attempts++
                false
            }
        }
        assertNotNull(
            "the wait must END when focus never lands -- an unbounded spin here is what stranded the " +
                "device blocked behind an input-swallowing return cover for ~80 minutes",
            landed,
        )
        assertFalse("a wait that never landed must report that it did not land", landed!!)
        assertTrue("it must have genuinely tried before giving up", attempts > 1)
    }

    /** The happy path is untouched: land on the first successful attempt, no waiting out the grace. */
    @Test
    fun `focus that lands settles immediately and does not wait out the grace`() = runTest {
        var attempts = 0
        var now = 0L
        val landed = awaitHomeSurfaceFocusSettled(
            deadlineMs = HomeSurfaceRestoreFocusGraceMs,
            nowMillis = { now },
            awaitFrame = { now += 16L },
            retryMs = RetryMs,
        ) {
            attempts++
            true
        }
        assertTrue("a landing must be reported as a landing", landed)
        assertEquals("landing must cost exactly one attempt", 1, attempts)
        assertTrue("landing must not consume the grace window", now < HomeSurfaceRestoreFocusGraceMs)
    }

    /**
     * Focus that arrives late — the shelf needed a few frames — still lands, and lands on the attempt it
     * became possible rather than being rounded up to the deadline.
     */
    @Test
    fun `focus that lands late still lands`() = runTest {
        var attempts = 0
        var now = 0L
        val landed = awaitHomeSurfaceFocusSettled(
            deadlineMs = HomeSurfaceRestoreFocusGraceMs,
            nowMillis = { now },
            awaitFrame = { now += 16L },
            retryMs = RetryMs,
        ) {
            attempts++
            attempts >= 5
        }
        assertTrue(landed)
        assertEquals(5, attempts)
    }

    /**
     * The deadline is ABSOLUTE, not per-attempt: a caller re-entering with an already-passed deadline gets
     * exactly ONE honest attempt and then releases. This is what stops a churning truth source (art bumps,
     * Room re-emissions restarting the effect) from extending the window without limit — the same property
     * `returnGraceDeadlines` was introduced for on the absent-row arm.
     */
    @Test
    fun `an already-passed deadline still makes one honest attempt and then releases`() = runTest {
        var attempts = 0
        val landed = withTimeoutOrNull(VirtualWatchdogMs) {
            awaitHomeSurfaceFocusSettled(
                deadlineMs = 0L,
                nowMillis = { 5_000L },
                awaitFrame = { },
                retryMs = RetryMs,
            ) {
                attempts++
                false
            }
        }
        assertEquals("a passed deadline must not deny the attempt it would otherwise have won", 1, attempts)
        assertNotNull(landed)
        assertFalse(landed!!)
    }

    /** A passed deadline must not override a focus that IS available — trying still beats declining. */
    @Test
    fun `a passed deadline still honours a focus that is already available`() = runTest {
        val landed = awaitHomeSurfaceFocusSettled(
            deadlineMs = 0L,
            nowMillis = { 5_000L },
            awaitFrame = { },
            retryMs = RetryMs,
        ) { true }
        assertTrue("the deadline bounds waiting, it must never refuse a landing", landed)
    }

    /**
     * One patience budget for the return cover, expressed once.
     *
     * `MimirAppHost.MimirPrimaryReturnCover`'s own comment tells the reader the cover's worst case is "the
     * 4s absent-row grace". Deriving this constant from [HomeLaunchReturnRenderableGraceMs] rather than
     * restating 4000 is what keeps that sentence true as the budget moves — the same "express the budget
     * once" rule `WelcomeHoldMaxMs` applies to the welcome splash.
     */
    @Test
    fun `the restore grace is the return cover's one budget, not a second number`() {
        assertEquals(
            "a second literal here would silently outlive the comment that documents it",
            HomeLaunchReturnRenderableGraceMs,
            HomeSurfaceRestoreFocusGraceMs,
        )
    }

    /**
     * **The pin the behavioural tests above cannot provide.** They exercise the rule; nothing in them
     * notices if `HomeShelfCoordinator` stops calling it. Both cover-holding arms are checked by source,
     * the same idiom `FileBrowserStorePickSafetyTest` uses for the F-01 delete capability.
     */
    @Test
    fun returnCoverArmsUseTheBoundedWait() {
        val source = appSource("HomeShelfCoordinator.kt").readText()
        assertEquals(
            "both cover-holding focus waits -- the game-return anchor and the cold-process surface " +
                "restore -- must go through the bounded helper; an inline retry loop is how this P0 " +
                "happened",
            2,
            Regex("awaitHomeSurfaceFocusSettled\\(").findAll(source).count(),
        )
        assertFalse(
            "no unbounded `while (isActive)` focus spin may remain in this coordinator -- that shape IS " +
                "the defect, and it holds an overlay that swallows the input needed to escape it",
            source.contains("while (isActive) {"),
        )
        assertTrue(
            "the surface-restore deadline must be absolute across effect restarts, not recomputed per " +
                "attempt, or a churning truth source extends the window without limit",
            source.contains("surfaceRestoreGraceDeadlines.getOrPut(stored)"),
        )
        assertTrue(
            "the game-return focus deadline must live in its own map -- the Ready branch clears " +
                "returnGraceDeadlines on entry, which would wipe a deadline parked there every restart",
            source.contains("returnFocusGraceDeadlines.getOrPut(handle.token)"),
        )
    }

    /**
     * The cover really is the thing these waits hold, and it really does swallow every escape. Pinned by
     * source because the alternative — asserting it in a Compose harness — would prove less: what matters
     * is that a future edit cannot quietly make the cover dismissible-looking while the waits stay
     * unbounded, or vice versa.
     */
    @Test
    fun theReturnCoverStillSwallowsEveryEscapeItIsHolding() {
        val cover = appSource("ReturnCoverSurface.kt").readText()
        val overlay = appSource("MimirOverlayCoordinator.kt").readText()
        assertTrue(
            "the cover blocks Back for as long as it is requested (and only then: a fading cover is pixels)",
            cover.contains("if (requested) BackHandler { }"),
        )
        assertTrue(
            "the cover is TOP of the overlay stack -- it covers whatever the player could have used",
            overlay.contains("if (snapshot.returnCover) add(MimirOverlayKind.RETURN_COVER)"),
        )
        assertTrue(
            "and it absorbs every controller action while it is up",
            overlay.contains("MimirOverlayKind.RETURN_COVER -> true"),
        )
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
