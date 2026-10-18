package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardware report, 2026-08-13: focus was not remembered on Settings returns to Home or Library;
 * the Library return also briefly displayed an incorrect image.
 *
 * Root cause traced to `MimirSettingsLibraryReturnEffect` (this file): on Settings -> Library, the
 * coroutine used to call `authority.restoreMimirOriginReturnScope(target)` (a scope pop/push walk)
 * SYNCHRONOUSLY, immediately after writing `navigation.destination = MimirDestination.LIBRARY` and
 * BEFORE the first `withFrameNanos {}` yield in this effect. A Compose state write does not take effect
 * until the next recomposition, so at that exact point `SettingsHome`/`SettingsDetailPane` (feature-settings
 * SettingsHome.kt) are STILL mounted and STILL own the FocusAuthority scope stack
 * (`SettingsRailScope`/`DetailScope`) -- the scope walk popped THAT still-live stack, landing on
 * `LibraryShelfScope`/`GamelistScope` before `LibraryScreen` (feature-library LibraryScreen.kt) had even
 * mounted, i.e. before any of the target platform's/game's rows were registered in `FocusAuthority`.
 * `DefaultFocusAuthority.maybeSeedFocusOnRegister` (core-input DefaultFocusAuthority.kt:66-72) then seeds
 * focus onto WHICHEVER row registers first as `LibraryScreen` mounts for real, a few frames before the
 * SAME effect's own frame-gated retry loop (below, unaffected by this fix) corrects it with an explicit
 * `authority.requestFocus(targetKey)` -- a multi-frame window (up to `MimirLibraryOriginReturnFocusAttempts`
 * = 8 frames) where the wrong item is focused, which is the mechanism-level source for both "doesn't land
 * smoothly" and a brief unintended image flash (a wrong hero/backdrop paints for those frames).
 *
 * The retry loop three lines below the removed call already performs the identical
 * `restoreMimirOriginReturnScope(target)` + `restoreLibraryDetailSession(authority, it)` pair, but SAFELY,
 * gated behind its own `withFrameNanos {}` on every attempt -- so the fix is a pure deletion of the
 * premature, un-gated duplicate, not a new mechanism. `LibraryScreen`'s own mount effects
 * (`enterLibraryBaseScope` / `reconcileLibraryEntryScope`, feature-library LibraryScreen.kt /
 * LibraryDispatch.kt) independently derive the same target scope from the same
 * `viewModel.restoreOriginReturnTargetState(target)` state this effect writes moments earlier, so removing
 * the premature call does not remove any coverage -- it removes a second, out-of-order actor racing the
 * first one.
 *
 * Source characterization, matching the pattern `MimirAppHostMotionTest`/`DebugNavigationHooksTest`
 * already use in this file's own package: `MimirSettingsLibraryReturnEffect` runs inside a `LaunchedEffect`
 * this module has no Robolectric harness to drive end to end (it needs the full `LibraryViewModel`/
 * `MimirLibraryCoordinator`/`MimirNativeShelfCoordinator` app graph), so this pins the STRUCTURAL ordering
 * guarantee the fix relies on directly against the real file text.
 */
class MimirSettingsReturnPathRaceTest {

    private val effectBody: String by lazy {
        val source = sourceFile("MimirNavigationCoordinator.kt")
        source.sliceBetween(
            "private fun MimirSettingsLibraryReturnEffect(",
            "internal fun installSettingsLibraryDetailSession(",
        )
    }

    @Test
    fun theDestinationWriteComesBeforeTheFirstFrameYield() {
        val destinationWriteIndex = effectBody.indexOf("navigation.destination = MimirDestination.LIBRARY")
        val firstFrameYieldIndex = effectBody.indexOf("withFrameNanos { }")
        assertTrue("could not locate the destination write", destinationWriteIndex >= 0)
        assertTrue("could not locate any withFrameNanos {} yield", firstFrameYieldIndex >= 0)
        assertTrue(
            "the destination write must precede the first frame yield (it always did; this just anchors " +
                "the other two assertions below to real positions in the file)",
            destinationWriteIndex < firstFrameYieldIndex,
        )
    }

    @Test
    fun restoreMimirOriginReturnScopeNeverRunsBeforeTheFirstFrameYield() {
        val firstFrameYieldIndex = effectBody.indexOf("withFrameNanos { }")
        val firstScopeCallIndex = effectBody.indexOf("restoreMimirOriginReturnScope(target)")
        assertTrue("could not locate any withFrameNanos {} yield", firstFrameYieldIndex >= 0)
        assertTrue("could not locate any restoreMimirOriginReturnScope(target) call", firstScopeCallIndex >= 0)
        assertTrue(
            "authority.restoreMimirOriginReturnScope(target) must not run before the first withFrameNanos " +
                "{} yield in MimirSettingsLibraryReturnEffect -- called that early it races Settings' still-" +
                "live scope stack (SettingsHome has not disposed yet) instead of Library's freshly-mounted " +
                "one, landing focus on whichever row registers first rather than the remembered target",
            firstScopeCallIndex > firstFrameYieldIndex,
        )
    }

    @Test
    fun theFrameGatedRetryLoopStillOwnsBothTheScopeRestoreAndTheExplicitFocusRequest() {
        // Guards against a future edit removing BOTH copies (the premature one this test's sibling above
        // proves is gone, and the safe, frame-gated one) and leaving no restore mechanism at all.
        val retryLoop = effectBody.sliceBetween(
            "repeat(MimirLibraryOriginReturnFocusAttempts) {",
            "retainedDetail?.let { session ->",
        )
        assertTrue(
            "the frame-gated retry loop must still call restoreMimirOriginReturnScope(target)",
            retryLoop.contains("restoreMimirOriginReturnScope(target)"),
        )
        assertTrue(
            "the frame-gated retry loop must still call the explicit, typed focus target -- this is what " +
                "actually prevents focus resetting to the shelf/gamelist's first item on return",
            retryLoop.contains("authority.requestFocus(targetKey)"),
        )
        assertTrue(
            "the scope restore inside the loop must be gated behind withFrameNanos {}",
            retryLoop.indexOf("withFrameNanos { }") < retryLoop.indexOf("restoreMimirOriginReturnScope(target)"),
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
