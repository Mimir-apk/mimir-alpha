package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The library can only ever name folders it already has games in, which is the wrong way round for the
 * case that matters — a player setting a new system up has an EMPTY platform folder, and that is exactly
 * when they need somewhere to extract to. These pins cover the walk that finds it.
 *
 * The filesystem and the registry are both injected ([FileBrowserRomFolders.discoverFrom]), following
 * [FileBrowserRomFolders.refreshFrom]'s own precedent: no `Context`, no Room, no real disk.
 */
class FileBrowserRomFolderDiscoveryTest {

    @After
    fun tearDown() = FileBrowserRomFolders.clear()

    /** A card laid out with the reported setup shape: one populated platform folder, one empty, plus junk. */
    private val disk = mapOf(
        "/storage/9C33-6BBD/Roms" to listOf(
            "gba" to "/storage/9C33-6BBD/Roms/gba",
            "ps2" to "/storage/9C33-6BBD/Roms/ps2",
            "Screenshots" to "/storage/9C33-6BBD/Roms/Screenshots",
        ),
        "/storage/emulated/0/Games" to listOf(
            "psp" to "/storage/emulated/0/Games/psp",
        ),
    )

    private fun lister(): suspend (String) -> List<Pair<String, String>> = { path -> disk[path].orEmpty() }

    /** Stands in for the locked registry's folder index: the real one is exercised in `core-resolver`. */
    private fun registry(): (String) -> String? = { name ->
        when (name.lowercase()) {
            "gba" -> "gba"
            "ps2" -> "ps2"
            "psp" -> "psp"
            else -> null
        }
    }

    /** Same fixture shape `FileBrowserRomFolderRaceTest` uses — only `platformId`/`managedPath` matter here. */
    private fun game(platformId: String, managedPath: String): GameEntity {
        val key = "discovery-fixture-$platformId-${managedPath.hashCode()}"
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
            relativePath = managedPath.substringAfterLast('/'),
            discNumber = 0,
            duplicateGroupId = null,
            isPrimaryMember = true,
            duplicateGroupKind = null,
        )
    }

    @Test
    fun `an EMPTY platform folder beside a populated one is discovered`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/storage/9C33-6BBD/Roms/gba/Zelda.gba")) }
        FileBrowserRomFolders.discoverFrom(
            sourceRoots = emptyList(),
            listChildDirectories = lister(),
            platformOfFolderName = registry(),
        )

        assertTrue(
            "the library has never sourced a single PS2 game, and the folder is still a real place to " +
                "put one -- a library-only list cannot see it by construction",
            FileBrowserRomFolders.discoveredFolders().any { it.path == "/storage/9C33-6BBD/Roms/ps2" },
        )
    }

    @Test
    fun `the roots are DERIVED from where the library's games already are, with no configuration`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/storage/9C33-6BBD/Roms/gba/Zelda.gba")) }
        FileBrowserRomFolders.discoverFrom(
            sourceRoots = emptyList(),
            listChildDirectories = lister(),
            platformOfFolderName = registry(),
        )

        assertEquals(
            "one game in Roms/gba names Roms as a ROM root, and everything platform-shaped inside it " +
                "follows -- nothing had to be configured, asked for, or guessed",
            listOf("gba", "ps2"),
            FileBrowserRomFolders.discoveredFolders().map { it.path.substringAfterLast('/') }.sorted(),
        )
    }

    @Test
    fun `a source root the player added is walked even when the library found nothing in it`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { emptyList() }
        FileBrowserRomFolders.discoverFrom(
            sourceRoots = listOf("/storage/emulated/0/Games"),
            listChildDirectories = lister(),
            platformOfFolderName = registry(),
        )

        assertEquals(
            "a fresh install with a source added and nothing scanned yet is the FIRST run, and it must " +
                "not be the one case this feature cannot serve",
            listOf("/storage/emulated/0/Games/psp"),
            FileBrowserRomFolders.discoveredFolders().map { it.path },
        )
    }

    @Test
    fun `only folders the registry recognises appear - Screenshots is not a platform`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/storage/9C33-6BBD/Roms/gba/Zelda.gba")) }
        FileBrowserRomFolders.discoverFrom(
            sourceRoots = emptyList(),
            listChildDirectories = lister(),
            platformOfFolderName = registry(),
        )

        assertTrue(
            "the list is offered under a focus ring; filling it with every directory on the card would " +
                "make it useless faster than an empty one would",
            FileBrowserRomFolders.discoveredFolders().none { it.path.endsWith("/Screenshots") },
        )
    }

    @Test
    fun `a SAF tree URI is skipped rather than failing the whole walk`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { emptyList() }
        FileBrowserRomFolders.discoverFrom(
            sourceRoots = listOf("content://com.android.externalstorage.documents/tree/primary%3ARoms", "/storage/emulated/0/Games"),
            listChildDirectories = { path ->
                if (path.startsWith("content://")) error("File cannot list a tree URI")
                disk[path].orEmpty()
            },
            platformOfFolderName = registry(),
        )

        assertEquals(
            "one unlistable root must cost its own rows and nothing else -- and the existing " +
                "position is that Mimir owns this surface rather than hopping through SAF",
            listOf("/storage/emulated/0/Games/psp"),
            FileBrowserRomFolders.discoveredFolders().map { it.path },
        )
    }

    @Test
    fun `a walk that throws leaves the previous answer rather than a crash`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/storage/9C33-6BBD/Roms/gba/Zelda.gba")) }
        FileBrowserRomFolders.discoverFrom(
            sourceRoots = emptyList(),
            listChildDirectories = { error("card pulled mid-walk") },
            platformOfFolderName = registry(),
        )

        assertEquals(
            "same posture refreshFrom already takes: a destination list is not worth crashing a file " +
                "browser over, and empty degrades to the library's own folders",
            emptyList<String>(),
            FileBrowserRomFolders.discoveredFolders().map { it.path },
        )
    }

    @Test
    fun `the same folder reached from two roots is discovered once`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/storage/9C33-6BBD/Roms/gba/Zelda.gba")) }
        FileBrowserRomFolders.discoverFrom(
            // The source root and the derived root are the same directory.
            sourceRoots = listOf("/storage/9C33-6BBD/Roms/"),
            listChildDirectories = lister(),
            platformOfFolderName = registry(),
        )

        assertEquals(2, FileBrowserRomFolders.discoveredFolders().size)
    }

    @Test
    fun `clear drops the discovered folders too - a session's cache may not outlive its session`() = runBlocking {
        FileBrowserRomFolders.refreshFrom { listOf(game("gba", "/storage/9C33-6BBD/Roms/gba/Zelda.gba")) }
        FileBrowserRomFolders.discoverFrom(emptyList(), lister(), registry())
        assertTrue(FileBrowserRomFolders.discoveredFolders().isNotEmpty())

        FileBrowserRomFolders.clear()

        assertTrue(FileBrowserRomFolders.discoveredFolders().isEmpty())
        assertTrue(FileBrowserRomFolders.libraryFolders().isEmpty())
    }
}
