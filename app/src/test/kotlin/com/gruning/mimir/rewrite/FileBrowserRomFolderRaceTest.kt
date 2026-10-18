package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * Traced to `FileBrowserStore.open` firing `FileBrowserRomFolders.refresh()` with a bare
 * `scope.launch { ... }` and mounting the browser — which is what makes `extractOptions` reachable at
 * all, since `resolveExtractOptions` is only wired inside `mount()` — in the same breath, never awaiting
 * the refresh. A player fast enough to open the manager and press SECONDARY on a `.zip`/`.7z` before
 * that Room read landed hit `FileBrowserRomFolders.foldersFor` while it was still the pre-refresh
 * `emptyMap()`, got the honest-but-wrong "nothing to suggest", and had no reason to try again since
 * nothing re-asks after the read actually lands. Intermittent by construction — exactly as reported.
 *
 * The fix sequences the two in `FileBrowserStore.openAfterRomFolderRefresh`: refresh completes, THEN —
 * and only if the session is still the live one — mount runs. `mutableController` (what makes the
 * browser's `SECONDARY` dispatch reachable) stays null for the whole wait, so nothing can observe a
 * not-yet-refreshed cache anymore.
 *
 * This file pins that ordering directly with fakes — no `Context`, no real `Session`, per
 * `openAfterRomFolderRefresh`'s own KDoc — plus one end-to-end test wiring the REAL
 * [FileBrowserRomFolders] to prove the composition, not just each half in isolation.
 *
 * **Disclosed gap, matching `FileBrowserStorePickSafetyTest`'s own idiom for the same store:** the faked
 * tests below prove [FileBrowserStore.openAfterRomFolderRefresh]'s ordering, not that `open()` actually
 * calls it with the real arguments. That wiring is checked separately, source-level, by
 * [theRaceFixIsActuallyWiredIntoOpen] below — and deliberately: reverting `open()` back to its old
 * fire-and-forget body while leaving `openAfterRomFolderRefresh` compiled and unused makes THAT test
 * fail, while the faked ordering tests above it keep passing, because they never call `open()` at all.
 * That is not a gap in those tests' logic; it is exactly why the source-level test exists too.
 */
class FileBrowserRomFolderRaceTest {

    // ---- openAfterRomFolderRefresh: the sequencing itself, with fakes ----

    @Test
    fun `mount never runs before refresh has fully completed`() = runBlocking {
        var refreshCompleted = false
        var refreshWasCompleteWhenMountRan: Boolean? = null

        FileBrowserStore.openAfterRomFolderRefresh(
            refresh = {
                // A real suspension point -- this proves a genuine await, not code that merely happens
                // to run in textual order on a single thread.
                yield()
                refreshCompleted = true
            },
            stillCurrent = { true },
            mount = { refreshWasCompleteWhenMountRan = refreshCompleted },
        )

        assertEquals(
            "mount must never observe refresh as still in flight",
            true,
            refreshWasCompleteWhenMountRan,
        )
    }

    @Test
    fun `a torn-down session's late refresh must not resurrect the browser`() = runBlocking {
        var mounted = false

        FileBrowserStore.openAfterRomFolderRefresh(
            refresh = { /* lands after the player already backed out of the browser */ },
            stillCurrent = { false },
            mount = { mounted = true },
        )

        assertFalse(
            "stillCurrent() false after refresh() means this session was torn down while the read was " +
                "in flight -- mounting anyway would reopen a browser the player already dismissed",
            mounted,
        )
    }

    @Test
    fun `a stale session still lets the shared refresh land -- only its own mount is skipped`() = runBlocking {
        // FileBrowserRomFolders is one process-wide cache, not per-session, so it is correct -- and
        // harmless -- for a superseded session's refresh to finish and update it for whoever opens next.
        var refreshRan = false

        FileBrowserStore.openAfterRomFolderRefresh(
            refresh = { refreshRan = true },
            stillCurrent = { false },
            mount = { },
        )

        assertTrue("a stale session must not cancel the shared cache's refresh, only skip its own mount", refreshRan)
    }

    // ---- end-to-end: the real FileBrowserRomFolders, composed exactly the way open() composes it ----

    private fun game(platformId: String, managedPath: String): GameEntity {
        val key = "race-fixture-$platformId-${managedPath.hashCode()}"
        return GameEntity(
            entryKey = key,
            gameId = key,
            platformId = platformId,
            contentKind = "GAME",
            displayName = "Fixture",
            title = "Fixture",
            region = null,
            version = null,
            serial = null,
            titleCatalogKey = null,
            identitySource = "FILENAME",
            locationKind = "MANAGED",
            availability = "AVAILABLE",
            sizeBytes = 1,
            contentHash = null,
            sourceRootUri = managedPath,
            sourceDocumentUri = null,
            managedPath = managedPath,
            relativePath = File(managedPath).name,
            discNumber = 0,
            duplicateGroupId = null,
            isPrimaryMember = true,
            duplicateGroupKind = null,
        )
    }

    @Test
    fun `by the time mount runs, the real FileBrowserRomFolders already has the platform folder`() = runBlocking {
        FileBrowserRomFolders.clear()
        try {
            var mountSawFolders: List<String>? = null

            FileBrowserStore.openAfterRomFolderRefresh(
                refresh = {
                    FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/sd/Roms/GBA/pokemon.gba")) }
                },
                stillCurrent = { true },
                mount = { mountSawFolders = FileBrowserRomFolders.foldersFor("gba") },
            )

            assertEquals(
                "this is the reported race, fixed end-to-end: by the time the browser is " +
                    "touchable (mount), the platform folder must already be there to suggest -- not " +
                    "arriving some time after",
                listOf("/sd/Roms/GBA"),
                mountSawFolders,
            )
        } finally {
            FileBrowserRomFolders.clear()
        }
    }

    // ---- source-level: open() actually wires the fix above, not just two compiling halves ----

    /**
     * Source-level, following `FileBrowserStorePickSafetyTest`'s own idiom for the same store: an
     * `object` with process-lifetime state and a `Context` requirement cannot be driven end-to-end by a
     * plain JUnit test. This proves the WIRING is written, not that `open()` executes it on a device —
     * device proof is `device QA`'s, not this lane's.
     */
    @Test
    fun theRaceFixIsActuallyWiredIntoOpen() {
        val storeSource = appSource("FileBrowserStore.kt").readText()

        assertTrue(
            "open() must guard reopening on the session, not merely the controller -- otherwise a " +
                "second open() call during the new await window races two sessions over one controller slot",
            storeSource.contains("if (mutableController.value != null || session != null) return"),
        )
        assertTrue(
            "open() must actually call openAfterRomFolderRefresh -- otherwise this file's faked tests " +
                "above pin a helper open() never calls",
            storeSource.contains("openAfterRomFolderRefresh("),
        )
        assertTrue(
            "the refresh handed to openAfterRomFolderRefresh must be the REAL FileBrowserRomFolders.refresh",
            storeSource.contains("refresh = { FileBrowserRomFolders.refresh(context) }"),
        )
        assertTrue(
            "the mount handed to openAfterRomFolderRefresh must be the REAL mount(), not a stand-in",
            storeSource.contains("mount = { mount(opened, rootPath) }"),
        )
        assertTrue(
            "stillCurrent must be an identity check against the specific session THIS open() call " +
                "started, so a newer session can never be stolen by an older call's late-landing refresh",
            storeSource.contains("stillCurrent = { session === opened }"),
        )
        assertFalse(
            "the old fire-and-forget refresh call must not still be sitting beside the fix -- that " +
                "would double-refresh and prove nothing was actually replaced",
            storeSource.contains("scope.launch { FileBrowserRomFolders.refresh(context) }"),
        )
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
