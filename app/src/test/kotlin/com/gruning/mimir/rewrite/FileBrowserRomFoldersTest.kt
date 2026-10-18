package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FileBrowserRomFolders] had ZERO tests before this file — verified: `grep -rln FileBrowserRomFolders
 * app/src/test` returned nothing. It is F-06's whole smart default: the platform folder "Extract in…"
 * offers, read from the real library rather than guessed from a folder name.
 *
 * Covers the object's OWN contract — the grouping rule ([groupByPlatform]), the not-yet-refreshed/empty
 * case, [FileBrowserRomFolders.clear], and a read that fails outright. **The race this object used to
 * lose to `FileBrowserStore.open`'s fire-and-forget refresh is pinned separately**, in
 * `FileBrowserRomFolderRaceTest` — that fix lives in `FileBrowserStore.open` /
 * `FileBrowserStore.openAfterRomFolderRefresh`, not in this object, so it needs `FileBrowserStore`'s own
 * fakes rather than [GameEntity] fixtures.
 *
 * [FileBrowserRomFolders] is a process-lifetime `object` shared by every test in this JVM — [clear] runs
 * before AND after every test here so no test can leak a populated cache into an unrelated one, and no
 * unrelated test's leftovers can make one of these pass by accident.
 */
class FileBrowserRomFoldersTest {

    @Before
    fun clearBefore() = FileBrowserRomFolders.clear()

    @After
    fun clearAfter() = FileBrowserRomFolders.clear()

    private var nextKey = 0

    /** Minimal, valid [GameEntity] — only [platformId] and [managedPath] matter to the code under test. */
    private fun game(platformId: String, managedPath: String?): GameEntity {
        val key = "entry-${nextKey++}"
        return GameEntity(
            entryKey = key,
            gameId = key,
            platformId = platformId,
            contentKind = "GAME",
            displayName = "Test Game $key",
            title = "Test Game $key",
            region = null,
            version = null,
            serial = null,
            titleCatalogKey = null,
            identitySource = "FILENAME",
            locationKind = if (managedPath != null) "MANAGED" else "SAF",
            availability = "AVAILABLE",
            sizeBytes = 1,
            contentHash = null,
            sourceRootUri = managedPath ?: "content://tree/root",
            sourceDocumentUri = if (managedPath == null) "content://tree/root/doc" else null,
            managedPath = managedPath,
            relativePath = managedPath?.substringAfterLast('/') ?: "game.bin",
            discNumber = 0,
            duplicateGroupId = null,
            isPrimaryMember = true,
            duplicateGroupKind = null,
        )
    }

    // ---- groupByPlatform: the pure grouping rule behind refreshFrom ----

    @Test
    fun `groups managedPath rows by platform into their parent directories`() {
        val games = listOf(
            game("gba", "/sd/Roms/GBA/pokemon.gba"),
            game("gba", "/sd/Roms/GBA/metroid.gba"),
            game("snes", "/sd/Roms/SNES/mario.sfc"),
        )

        val grouped = groupByPlatform(games)

        assertEquals(listOf("/sd/Roms/GBA"), grouped["gba"])
        assertEquals(listOf("/sd/Roms/SNES"), grouped["snes"])
    }

    @Test
    fun `two distinct folders for one platform are both reported, and a repeat is not double-counted`() {
        val games = listOf(
            game("gba", "/sd/Roms/GBA/pokemon.gba"),
            game("gba", "/sd/Roms/GBA/metroid.gba"), // same folder again
            game("gba", "/internal/Games/GBA/zelda.gba"), // a second, different folder
        )

        assertEquals(
            "a folder repeated across games must not be double-counted, and a genuinely second folder " +
                "must not be dropped -- ExtractDestinationPolicy is the one that decides two folders is " +
                "an ambiguous answer; this object's job is only to report what it actually found",
            listOf("/sd/Roms/GBA", "/internal/Games/GBA"),
            groupByPlatform(games)["gba"],
        )
    }

    @Test
    fun `a null managedPath -- a SAF-sourced row -- contributes no folder`() {
        val games = listOf(game("gba", managedPath = null))

        assertTrue(
            "a SAF tree URI is not a filesystem path an extraction can write into -- FileBrowserRomFolders' own KDoc",
            groupByPlatform(games).isEmpty(),
        )
    }

    @Test
    fun `a blank managedPath contributes no folder`() {
        assertTrue(groupByPlatform(listOf(game("gba", managedPath = "   "))).isEmpty())
    }

    @Test
    fun `a managedPath with no directory component contributes no folder`() {
        // File("pokemon.gba").parent is null -- there is no folder to suggest.
        assertTrue(groupByPlatform(listOf(game("gba", managedPath = "pokemon.gba"))).isEmpty())
    }

    @Test
    fun `an empty game list groups to an empty map`() {
        assertEquals(emptyMap<String, List<String>>(), groupByPlatform(emptyList()))
    }

    // ---- refreshFrom / foldersFor / clear: the object's own state machine ----

    @Test
    fun `before any refresh, foldersFor answers empty -- the not-yet-refreshed case`() {
        assertEquals(emptyList<String>(), FileBrowserRomFolders.foldersFor("gba"))
    }

    @Test
    fun `foldersFor answers empty for a null platformId`() {
        assertEquals(emptyList<String>(), FileBrowserRomFolders.foldersFor(null))
    }

    @Test
    fun `refreshFrom populates foldersFor from the injected loader, no Context or Room needed`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/sd/Roms/GBA/pokemon.gba")) }

        assertEquals(listOf("/sd/Roms/GBA"), FileBrowserRomFolders.foldersFor("gba"))
        assertEquals(
            "a platform refreshFrom found no rows for must still answer empty, not throw or guess",
            emptyList<String>(),
            FileBrowserRomFolders.foldersFor("n64"),
        )
    }

    @Test
    fun `clear wipes a populated cache back to the not-yet-refreshed answer`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/sd/Roms/GBA/pokemon.gba")) }
        assertEquals(listOf("/sd/Roms/GBA"), FileBrowserRomFolders.foldersFor("gba"))

        FileBrowserRomFolders.clear()

        assertEquals(
            "clear() must actually drop the cache -- a finished session's browser must not leak into the next",
            emptyList<String>(),
            FileBrowserRomFolders.foldersFor("gba"),
        )
    }

    @Test
    fun `a failing loader degrades to empty, never a thrown exception and never a stale prior answer`() = runBlocking {
        // A previous, successful refresh -- the player had a real GBA folder a moment ago.
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/sd/Roms/GBA/pokemon.gba")) }
        assertEquals(listOf("/sd/Roms/GBA"), FileBrowserRomFolders.foldersFor("gba"))

        // The library read then fails outright (a real DB hiccup, not "found nothing").
        FileBrowserRomFolders.refreshFrom { throw IllegalStateException("Room is unavailable") }

        assertEquals(
            "a failed refresh must degrade to the honest empty answer, not keep serving the PRIOR " +
                "successful snapshot -- a stale folder could easily be wrong by the time it is offered",
            emptyList<String>(),
            FileBrowserRomFolders.foldersFor("gba"),
        )
    }
}
