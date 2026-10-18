package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Investigates lost focus memory on Settings returns to Home and Library.
 *
 * The Settings -> Library path (see `MimirSettingsReturnPathRaceTest`) had a genuine, named race: a
 * SECOND actor (`MimirSettingsLibraryReturnEffect`) manipulated the shared FocusAuthority scope stack
 * synchronously, before Compose had disposed Settings or mounted Library. The Settings -> Home path has
 * NO analogous second actor to investigate: `restoreSettingsOriginOrLibraryRoot()`'s no-Library-origin
 * branch (below) writes only `destination`, never touches `FocusAuthority` at all -- it does not even take
 * an `authority` parameter. Focus restoration on Home entry is owned by exactly one place,
 * `HomeShelfScreen`'s own `DisposableEffect(authority) { authority.pushScope(HomeShelfScope) ... onDispose
 * { ... popScope ... } }` (feature-library/HomeShelfScreen.kt), the SAME already-proven single-owner
 * push/pop-on-mount/dispose pattern `DefaultFocusAuthorityTest.pushAndPopScopeRestoresFocusMemory`
 * (core-input) pins generically, and which `pushScope`'s own landing rule (core-input
 * DefaultFocusAuthority.kt:66-72, `maybeSeedFocusOnRegister`) explicitly favors the scope's remembered
 * key over the first-registered sibling.
 *
 * This is NOT a behavioral proof that the return currently feels right on device -- that needs device interaction evidence. It is a source-characterization pin, matching this codebase's own "investigated, not
 * reproduced as broken" precedent (`MimirKeyboardShoulderActionsIntegrationTest`'s username->password
 * finding), that the mechanism this path relies on is structurally single-owner and was not the site of
 * the race found on the Library path. If device interaction still shows stale/reset focus on this path,
 * it is either (a) a DIFFERENT, narrower defect than the dual-actor race found on Library, or (b) the
 * `HomeShelfQuickRailScope` entry point specifically (Settings opened from the quick rail rather than
 * directly off a shelf tile) -- both remain unknown and are not claimed fixed here.
 */
class MimirSettingsHomeReturnPathStructureTest {

    private val navigationSource: String by lazy { appSourceFile("MimirNavigationCoordinator.kt") }
    private val homeShelfScreenSource: String by lazy { homeShelfScreenSourceFile() }

    @Test
    fun theNoLibraryOriginReturnBranchNeverTouchesFocusAuthority() {
        val fn = navigationSource.sliceBetween(
            "fun restoreSettingsOriginOrLibraryRoot() {",
            "\n    fun openPlatformInLibrary(",
        )
        assertTrue(
            "restoreSettingsOriginOrLibraryRoot must not take/reference a FocusAuthority -- the Home " +
                "return path relies entirely on HomeShelfScreen's own mount/dispose scope push/pop, not on " +
                "this function racing it the way the (now-fixed) Library return path did",
            !fn.contains("authority") && !fn.contains("FocusAuthority"),
        )
        assertTrue(fn.contains("destination = returnDestination ?: MimirDestination.HOME"))
    }

    @Test
    fun homeShelfScreenIsTheSoleOwnerOfHomeShelfScopeOnMountAndDispose() {
        val mountEffect = homeShelfScreenSource.sliceBetween(
            "DisposableEffect(authority) {\n        authority.pushScope(HomeShelfScope)",
            "val canonicalRows = remember(rows)",
        )
        assertTrue(mountEffect.contains("onDispose {"))
        assertTrue(
            "the mount effect's onDispose must walk HomeShelfScopes back to Root -- this is the ONLY " +
                "place HomeShelfScope is popped, matching Settings' own single-owner SettingsRailScope " +
                "push/pop (SettingsHome.kt) with no third actor racing either side",
            mountEffect.contains("authority.state.value.scope in HomeShelfScopes"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun appSourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun homeShelfScreenSourceFile(): String {
        val relative = "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"
        val candidates = listOf(
            File(relative),
            File("../$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeShelfScreen.kt not found from ${File(".").absolutePath}")
    }
}
