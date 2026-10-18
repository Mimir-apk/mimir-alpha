package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.library.LibraryDepartureReason
import com.gruning.mimir.rewrite.core.model.library.LibraryRelocation
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * Before this, the file manager could move a ROM and nothing told the library. The row went on naming a
 * path with no file at it — so the game was unlaunchable until the next scan of that root, which
 * **deleted** it (`GameDao.pruneEntriesMissingFromSource`), and only the scan after that re-added it from
 * the new location. If the destination was outside every source root it never came back at all.
 *
 * The invariant every test here is really about: **never leave a row a fresh scan would contradict.** So
 * the row may only follow the file when the scanner would reach the same identity at the new location,
 * and must otherwise reach the state the scanner would — which for a file gone from a live root is
 * deletion, not some new "moved away" flavour of unavailable that nothing else can read.
 *
 * The classifier here is the REAL one against the REAL locked registry: what makes
 * `/sd/Roms/ps2/Zelda.gba` a departure is the registry's own refusal, not a fixture that says so.
 */
class LibraryRelocationTest {

    private val root = "/sd/Roms"

    private class Recorded {
        val deleted = mutableListOf<String>()
        val inserted = mutableListOf<GameEntity>()
        var memberCount = 1
        var row: GameEntity? = null
    }

    private fun zelda(path: String = "$root/gba/Zelda.gba") = GameEntity(
        entryKey = physicalResidentKey(root, path.removePrefix("$root/")),
        gameId = "title:gba:zelda",
        platformId = "gba",
        contentKind = "CARTRIDGE_ROM",
        displayName = "Zelda.gba",
        title = "Zelda",
        region = "EUR",
        version = null,
        serial = null,
        titleCatalogKey = "catalog-key",
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 8_388_608L,
        contentHash = "sha1:deadbeef",
        sourceRootUri = root,
        sourceDocumentUri = null,
        managedPath = path,
        relativePath = path.removePrefix("$root/"),
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private fun repository(
        recorded: Recorded,
        roots: List<String> = listOf(root),
    ): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { method, args ->
            when (method) {
                "gameByManagedPath" -> recorded.row.takeIf { it?.managedPath == args[0] }
                "memberCount" -> recorded.memberCount
                "deleteByEntryKey" -> {
                    recorded.deleted += args[0] as String
                    Unit
                }
                "upsertAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    recorded.inserted += args[0] as List<GameEntity>
                    Unit
                }
                else -> error("Unexpected GameDao call: $method")
            }
        }
        val sourceRootDao = proxy<SourceRootDao> { method, _ ->
            when (method) {
                "allOnce" -> roots.map {
                    SourceRootEntity(
                        rootId = it,
                        rootUri = it,
                        locationKind = "MANAGED_PATH",
                        friendlyLabel = it,
                        isPrimarySource = true,
                        lastSeenMillis = 0L,
                    )
                }
                else -> error("Unexpected SourceRootDao call: $method")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = sourceRootDao,
            hiddenItemDao = unusedProxy(),
            favoriteDao = unusedProxy(),
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a ROM moved within its source root keeps its identity and follows`() = runBlocking {
        val recorded = Recorded().apply { row = zelda() }
        val from = "$root/gba/Zelda.gba"
        val to = "$root/gba/Europe/Zelda.gba"

        val result = repository(recorded).relocate(from, to)

        assertEquals(LibraryRelocation.Relocated(com.gruning.mimir.rewrite.core.model.GameId("title:gba:zelda"), false), result)
        assertEquals("the old row is a PRIMARY KEY, so this is a rewrite", listOf(recorded.row!!.entryKey), recorded.deleted)
        val moved = recorded.inserted.single()
        assertEquals(to, moved.managedPath)
        assertEquals("gba/Europe/Zelda.gba", moved.relativePath)
        assertEquals(physicalResidentKey(root, "gba/Europe/Zelda.gba"), moved.entryKey)
        assertEquals(
            "gameId is CONTENT-derived, so favourites, play history and Home membership all survive",
            "title:gba:zelda",
            moved.gameId,
        )
    }

    @Test
    fun `everything the row knew that is not a location survives the rewrite`() = runBlocking {
        val recorded = Recorded().apply { row = zelda() }

        repository(recorded).relocate("$root/gba/Zelda.gba", "$root/gba/Europe/Zelda.gba")

        val moved = recorded.inserted.single()
        val original = recorded.row!!
        assertEquals(
            "a relocation that dropped the hash would make the next identity pass re-read 8 MB, and one " +
                "that dropped duplicateGroupId would resurrect a duplicate the player already resolved",
            listOf(original.contentHash, original.titleCatalogKey, original.region, original.title),
            listOf(moved.contentHash, moved.titleCatalogKey, moved.region, moved.title),
        )
        assertEquals(original.discNumber, moved.discNumber)
        assertEquals(original.isPrimaryMember, moved.isPrimaryMember)
        assertEquals(original.sizeBytes, moved.sizeBytes)
    }

    @Test
    fun `a ROM moved OUT of every source root departs, because no scan will ever see it again`() = runBlocking {
        val recorded = Recorded().apply { row = zelda() }

        val result = repository(recorded).relocate("$root/gba/Zelda.gba", "/sd/Downloads/Zelda.gba")

        assertTrue(result is LibraryRelocation.Departed)
        assertEquals(LibraryDepartureReason.OUTSIDE_SOURCES, (result as LibraryRelocation.Departed).reason)
        assertEquals(
            "the same thing a scan of that root would do -- pruneEntriesMissingFromSource DELETES rows " +
                "for files that are gone, so inventing a 'moved away' state here would be a state " +
                "nothing else in the system can read",
            listOf(recorded.row!!.entryKey),
            recorded.deleted,
        )
        assertTrue(recorded.inserted.isEmpty())
    }

    @Test
    fun `a GBA ROM dropped into the ps2 folder departs - the registry itself refuses it`() = runBlocking {
        val recorded = Recorded().apply { row = zelda() }

        val result = repository(recorded).relocate("$root/gba/Zelda.gba", "$root/ps2/Zelda.gba")

        assertEquals(
            "the destination list makes a cross-platform move ONE press, and the scanner would not call " +
                "this a GBA game at its new address -- a row that said otherwise is one the next scan " +
                "deletes",
            LibraryDepartureReason.IDENTITY_CHANGED,
            (result as LibraryRelocation.Departed).reason,
        )
        assertEquals(listOf(recorded.row!!.entryKey), recorded.deleted)
        assertTrue(recorded.inserted.isEmpty())
    }

    @Test
    fun `a Keep both rename is an identity change, not a relocation`() = runBlocking {
        val recorded = Recorded().apply { row = zelda() }

        val result = repository(recorded).relocate("$root/gba/Zelda.gba", "$root/gba/Zelda [2].gba")

        assertEquals(
            "title: gameIds are FILENAME-derived, so a file that arrived under a different name is a " +
                "different entry -- and the caller cannot always predict the renamed path anyway",
            LibraryDepartureReason.IDENTITY_CHANGED,
            (result as LibraryRelocation.Departed).reason,
        )
        assertTrue(recorded.inserted.isEmpty())
    }

    @Test
    fun `a file the library never indexed is not an error`() = runBlocking {
        val recorded = Recorded() // no row

        val result = repository(recorded).relocate("$root/gba/screenshot.png", "$root/ps2/screenshot.png")

        assertEquals(
            "the file manager moves screenshots, saves and BIOS images; none of them are library entries",
            LibraryRelocation.NotInLibrary,
            result,
        )
        assertTrue(recorded.deleted.isEmpty() && recorded.inserted.isEmpty())
    }

    @Test
    fun `a member of a disc set is reported as such, because the playlist on disk is now broken`() = runBlocking {
        val recorded = Recorded().apply {
            row = zelda()
            memberCount = 3
        }

        val result = repository(recorded).relocate("$root/gba/Zelda.gba", "$root/gba/Europe/Zelda.gba")

        assertTrue(
            "an .m3u names its discs by path; moving one member breaks the playlist itself, which no " +
                "amount of bookkeeping here can repair -- so the caller has to be able to say so",
            (result as LibraryRelocation.Relocated).partOfSet,
        )
    }

    @Test
    fun `a SAF root cannot claim a plain path`() = runBlocking {
        val recorded = Recorded().apply { row = zelda() }

        val result = repository(
            recorded,
            roots = listOf("content://com.android.externalstorage.documents/tree/primary%3ARoms"),
        ).relocate("$root/gba/Zelda.gba", "$root/gba/Europe/Zelda.gba")

        assertEquals(
            "a content:// tree uri is not a prefix of a filesystem path, and a row claiming a SAF root " +
                "it was not written through would be a lie about how the scanner will find it",
            LibraryDepartureReason.OUTSIDE_SOURCES,
            (result as LibraryRelocation.Departed).reason,
        )
    }

    @Test
    fun `the deepest source root wins, so a nested source claims its own files`() = runBlocking {
        val recorded = Recorded().apply { row = zelda() }

        repository(recorded, roots = listOf("/sd", root)).relocate(
            "$root/gba/Zelda.gba",
            "$root/gba/Europe/Zelda.gba",
        )

        val moved = recorded.inserted.single()
        assertEquals(root, moved.sourceRootUri)
        assertEquals(
            "keyed to /sd instead, the relative path would carry Roms/ and no walk of the real source " +
                "would ever produce that key",
            "gba/Europe/Zelda.gba",
            moved.relativePath,
        )
    }

    @Test
    fun `nothing here touches a file`() {
        val source = LibraryRepositoryImpl::class.java.declaredMethods.map { it.name }
        assertFalse(
            "relocate is bookkeeping catching up with the filesystem, never the other way round",
            source.any { it.contains("deleteFile") || it.contains("moveFile") },
        )
    }

    private inline fun <reified T> unusedProxy(): T = proxy { method, _ ->
        error("Unexpected ${T::class.java.simpleName} call: $method")
    }

    /** [LibraryRepositoryClearLogoTest]'s idiom, widened to hand the arguments to the answer. */
    private inline fun <reified T> proxy(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name, args ?: emptyArray())
        } as T
}
