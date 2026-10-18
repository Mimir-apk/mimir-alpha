package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.jobs.JobProgressState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.model.jobs.ScanProgressState
import com.gruning.mimir.rewrite.core.model.jobs.ScanPhase
import com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameHashSourceProjection
import com.gruning.mimir.rewrite.core.storage.dao.GameMemberProjection
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.dao.PlatformCountProjection
import com.gruning.mimir.rewrite.core.storage.dao.PlatformDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import com.gruning.mimir.rewrite.core.storage.dao.TerminalGroupLink
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.PlatformEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.SourceFailureKind
import com.gruning.mimir.rewrite.core.model.library.SourceRootUnreadableReason
import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM test of the repository's scan ORCHESTRATION (walk → recognize → dedup → persist → ScanResult)
 * with in-memory DAO fakes and a stub walker. No Room, no Android device — just proves the wiring folds a
 * representative folder to the right rows + honest counts. The Room queries + SAF tree walk themselves
 * are device/instrumented-validated.
 */
class LibraryRepositoryScanTest {

    @Test
    fun `scan folds a mixed folder and persists grouped rows with honest counts`() = runBlocking {
        val walked = listOf(
            // FF7: 3 discs + m3u → 1 group (m3u primary), 4 members
            wf("Final Fantasy VII (Disc 1).chd", "PSX/Final Fantasy VII (Disc 1).chd"),
            wf("Final Fantasy VII (Disc 2).chd", "PSX/Final Fantasy VII (Disc 2).chd"),
            wf("Final Fantasy VII (Disc 3).chd", "PSX/Final Fantasy VII (Disc 3).chd"),
            wf("Final Fantasy VII.m3u", "PSX/Final Fantasy VII.m3u"),
            // two standalone GBA games → 2 groups
            wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"),
            wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba"),
            // a non-game file → skipped
            wf("cover.png", "GBA/cover.png"),
            // a .cue manifest + its .bin track in one folder → fold to ONE tile (cue = launch primary)
            wf("Some Game.cue", "PSX/Some Game.cue"),
            wf("Some Game.bin", "PSX/Some Game.bin"),
        )
        val walker = StubWalker(mapOf("path/roms" to walked))
        val gameDao = FakeGameDao()
        val platformDao = FakePlatformDao()
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = platformDao,
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        val result = repo.rescan("path/roms")

        assertEquals("all 9 files seen", 9, result.filesSeen)
        assertEquals("only png skipped (cue is now an indexed track manifest)", 1, result.skipped)
        assertEquals("8 game files recognized (FF7 x4, 2 gba, cue + bin)", 8, result.recognized)
        assertEquals("folds to 4 groups (FF7, Metroid, Golden Sun, Some Game)", 4, result.groups)

        // Persisted game rows: all 8 members stored, but only 4 are primary (the shelf tiles).
        assertEquals(8, gameDao.stored.size)
        val primaries = gameDao.stored.values.filter { it.isPrimaryMember }
        assertEquals(4, primaries.size)
        // The FF7 primary is the m3u playlist; its group carries 4 members.
        val ff7Primary = primaries.first { it.title.contains("Final Fantasy VII") }
        assertTrue(ff7Primary.displayName.endsWith(".m3u"))
        // The Some Game primary is the .cue manifest; its .bin track folds behind it (one tile).
        val someGamePrimary = primaries.first { it.title.contains("Some Game") }
        assertTrue("cue is the launch primary of its track set", someGamePrimary.displayName.endsWith(".cue"))

        // Platform rows: ps1 (2 games: FF7 + Some Game) + gba (2 games).
        assertEquals(setOf("ps1", "gba"), platformDao.stored.keys)
        assertEquals(2, platformDao.stored.getValue("ps1").gamesCount)
        assertEquals(2, platformDao.stored.getValue("gba").gamesCount)

        // The first source is deterministically primary regardless of storage transport.
        assertEquals(1, sourceDao.stored.size)
        val src = sourceDao.stored.values.single()
        assertTrue(src.friendlyLabel.isNotBlank())
        assertTrue("no raw content-uri in friendly label", !src.friendlyLabel.contains("content://"))
    }

    @Test
    fun `scan reads m3u membership and folds scenario-named discs`() = runBlocking {
        val root = "path/roms"
        val playlist = wf("Resident Evil 2.m3u", "PSX/Resident Evil 2.m3u")
        val leon = wf("Leon.iso", "PSX/Leon.iso")
        val claire = wf("Claire.iso", "PSX/Claire.iso")
        val gameDao = FakeGameDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = PlaylistStubWalker(
                root = root,
                files = listOf(playlist, leon, claire),
                bodies = mapOf(playlist.relativePath to "#EXTM3U\nLeon.iso\nClaire.iso\n"),
            ),
            runScanTransaction = { block -> block() },
        )

        val result = repo.rescan(root)

        assertEquals(3, result.recognized)
        assertEquals(1, result.groups)
        assertEquals(3, gameDao.stored.size)
        assertTrue(gameDao.stored.values.single { it.isPrimaryMember }.displayName.endsWith(".m3u"))
        assertEquals(
            listOf(1, 2),
            gameDao.stored.values.filter { it.discNumber > 0 }.map { it.discNumber }.sorted(),
        )
    }

    @Test
    fun `cancellation during playlist read never publishes a partial source`() = runBlocking {
        val root = "path/roms"
        val playlist = wf("Resident Evil 2.m3u", "PSX/Resident Evil 2.m3u")
        val leon = wf("Leon.iso", "PSX/Leon.iso")
        val claire = wf("Claire.iso", "PSX/Claire.iso")
        val readEntered = CompletableDeferred<Unit>()
        val walker = object : SourceLister {
            override suspend fun walk(rootUri: String): SourceWalkResult =
                SourceWalkResult.Complete(listOf(playlist, leon, claire))

            override suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? {
                readEntered.complete(Unit)
                awaitCancellation()
            }
        }
        val gameDao = FakeGameDao()
        val platformDao = FakePlatformDao()
        val sourceDao = FakeSourceDao()
        val repository = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = platformDao,
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
        )

        val scan = async(Dispatchers.Default) { repository.rescan(root) }
        readEntered.await()
        scan.cancel(CancellationException("cancel blocked playlist read"))
        try {
            withTimeout(2_000) { scan.await() }
            fail("Expected playlist-read cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(gameDao.stored.isEmpty())
        assertTrue(platformDao.stored.isEmpty())
        assertTrue(sourceDao.stored.isEmpty())
    }

    @Test
    fun `scan forwards a SAF root outside Mimir unchanged to provider traversal`() = runBlocking {
        val root = "content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs"
        val walker = RecordingOutcomeRootWalker(SourceWalkResult.Failed("provider unavailable in JVM"))
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        val result = repo.rescan(root)

        assertTrue(walker.isSafTree(root))
        assertEquals(listOf(root), walker.requestedRoots)
        assertEquals(ScanOutcome.Failed("provider unavailable in JVM"), result.outcome)
    }

    @Test
    fun `scan accepts a mounted path outside Mimir without rewriting its identity`() = runBlocking {
        val root = "/storage/691E-7BEA/ROMs"
        val filePath = "$root/GBA/Metroid.gba"
        val walked = WalkedFile(
            displayName = "Metroid.gba",
            relativePath = "GBA/Metroid.gba",
            sizeBytes = 256L * 1024,
            documentUri = null,
            filePath = filePath,
        )
        val walker = RecordingRootWalker(root, listOf(walked))
        val gameDao = FakeGameDao()
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        val result = repo.rescan(root)

        assertEquals(ScanOutcome.Healthy, result.outcome)
        assertEquals(1, result.filesSeen)
        assertEquals(1, result.recognized)
        assertEquals(1, result.groups)
        assertEquals(listOf(root), walker.requestedRoots)

        val game = gameDao.stored.values.single()
        assertEquals(root, game.sourceRootUri)
        assertEquals(filePath, game.managedPath)
        assertTrue(game.entryKey.startsWith("p2:"))

        val source = sourceDao.stored.getValue(root)
        assertEquals(root, source.rootId)
        assertEquals(root, source.rootUri)
        assertEquals("MANAGED_PATH", source.locationKind)
        assertEquals(true, source.isPrimarySource)
    }

    @Test
    fun `rescan prunes rows for files deleted from the source (reflects deletions, not just adds)`() = runBlocking {
        val walker = MutableStubWalker(
            root = "roms",
            files = listOf(
                wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"),
                wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba"),
                wf("Mario Kart (USA).gba", "GBA/Mario Kart (USA).gba"),
            ),
        )
        val gameDao = FakeGameDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        repo.rescan("roms")
        assertEquals("all 3 indexed", 3, gameDao.stored.size)

        // The user deletes one ROM from the source, then rescans.
        walker.files = walker.files.filterNot { it.displayName.contains("Golden Sun") }
        val result = repo.rescan("roms")

        assertEquals("scan sees 2", 2, result.recognized)
        assertEquals("the deleted game's row is PRUNED, not left behind", 2, gameDao.stored.size)
        assertTrue("Golden Sun gone", gameDao.stored.keys.none { it.contains("Golden Sun") })
        assertTrue("survivors kept", gameDao.stored.keys.any { it.contains("Metroid Fusion") })
    }

    @Test
    fun `failed root traversals preserve existing rows and report the reason`() = runBlocking {
        val walker = MutableOutcomeWalker(
            SourceWalkResult.Complete(listOf(wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"))),
        )
        val gameDao = FakeGameDao()
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        repo.rescan("roms")
        val originalSource = sourceDao.stored.getValue("roms")

        listOf(
            "Read permission was revoked",
            "SAF provider query failed",
            "Source root does not exist",
        ).forEach { reason ->
            walker.outcome = SourceWalkResult.Failed(reason)

            val result = repo.rescan("roms")

            assertEquals(ScanOutcome.Failed(reason), result.outcome)
            assertEquals("failed scan does not prune the indexed game", 1, gameDao.stored.size)
            assertEquals("failed scan does not refresh source availability", originalSource, sourceDao.stored["roms"])
        }
    }

    @Test
    fun `healthy empty traversal prunes existing rows`() = runBlocking {
        val walker = MutableOutcomeWalker(
            SourceWalkResult.Complete(listOf(wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"))),
        )
        val gameDao = FakeGameDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        repo.rescan("roms")
        walker.outcome = SourceWalkResult.Complete(emptyList())

        val result = repo.rescan("roms")

        assertEquals(ScanOutcome.Healthy, result.outcome)
        assertEquals(0, result.filesSeen)
        assertTrue("a complete empty source is a real deletion", gameDao.stored.isEmpty())
    }

    /**
     * with "All files access" revoked the shared folder still opens and lists as
     * EMPTY, so a rescan took it for a real deletion and the index went from 195 rows to 0. A root the lister cannot
     * vouch for is never walked, never pruned, and reads as unavailable.
     */
    @Test
    fun `an unreadable root is never pruned even when its listing comes back empty`() = runBlocking {
        val walker = AccessGatedWalker(
            SourceWalkResult.Complete(listOf(wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"))),
        )
        val writes = mutableListOf<String>()
        val gameDao = FakeGameDao { label -> writes += label }
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )
        repo.rescan("roms")
        val rowsBefore = gameDao.stored.toMap()
        val sourceBefore = sourceDao.stored.getValue("roms")
        assertEquals("fixture precondition: the first scan indexed the game", 1, rowsBefore.size)
        writes.clear()

        // Access is revoked: the filtered listing is empty, exactly what the device returned.
        walker.readable = false
        walker.outcome = SourceWalkResult.Complete(emptyList())
        val result = repo.rescan("roms")

        assertEquals(
            ScanOutcome.Failed(SourceRootUnreadableReason, SourceFailureKind.UNAVAILABLE),
            result.outcome,
        )
        assertEquals("the index is untouched", rowsBefore, gameDao.stored.toMap())
        assertEquals("and so is the source's last-seen stamp", sourceBefore, sourceDao.stored["roms"])
        assertTrue("no write of any kind reached the games table: $writes", writes.isEmpty())
        assertEquals("the unreadable root was never walked", 1, walker.walks)
    }

    /**
     * The discriminating case. With only the media permission held, Android lists a PARTIAL folder (the sidecars, no
     * ROMs), so "empty and unreadable" is not the rule: a listing that holds files but no games would prune
     * every game just the same.
     */
    @Test
    fun `an unreadable root is never pruned by a partial listing that recognises nothing`() = runBlocking {
        val walker = AccessGatedWalker(
            SourceWalkResult.Complete(listOf(wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"))),
        )
        val writes = mutableListOf<String>()
        val gameDao = FakeGameDao { label -> writes += label }
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )
        repo.rescan("roms")
        writes.clear()

        walker.readable = false
        walker.outcome = SourceWalkResult.Complete(listOf(wf("preview.mp4", "GBA/preview.mp4")))
        val result = repo.rescan("roms")

        assertTrue(result.outcome is ScanOutcome.Failed)
        assertEquals("the game is still indexed", 1, gameDao.stored.size)
        assertTrue("no write reached the games table: $writes", writes.isEmpty())
    }

    @Test
    fun `rescanning every source with access lost keeps every row and reports the sources unavailable`() = runBlocking {
        val walker = AccessGatedWalker(
            SourceWalkResult.Complete(listOf(wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"))),
        )
        val writes = mutableListOf<String>()
        val gameDao = FakeGameDao { label -> writes += label }
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )
        repo.addSource("roms", LibrarySourceRole.PRIMARY)
        val rowsBefore = gameDao.stored.toMap()
        writes.clear()

        walker.readable = false
        walker.outcome = SourceWalkResult.Complete(emptyList())
        val receipt = repo.reconcileSources()

        assertEquals(1, receipt.sourcesChecked)
        assertEquals(0, receipt.healthySources)
        assertEquals(1, receipt.unavailableSources)
        assertEquals(0, receipt.removedSources)
        assertEquals(rowsBefore, gameDao.stored.toMap())
        assertEquals(setOf("roms"), sourceDao.stored.keys)
        assertTrue("no write reached the games table: $writes", writes.isEmpty())
    }

    /** The other side of the gate, pinned so it cannot drift: a root that IS readable and truly empty still empties. */
    @Test
    fun `a readable root whose listing is genuinely empty still removes its games`() = runBlocking {
        val walker = AccessGatedWalker(
            SourceWalkResult.Complete(listOf(wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"))),
        )
        val gameDao = FakeGameDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )
        repo.rescan("roms")
        assertEquals(1, gameDao.stored.size)

        walker.readable = true
        walker.outcome = SourceWalkResult.Complete(emptyList())
        val result = repo.rescan("roms")

        assertEquals(ScanOutcome.Healthy, result.outcome)
        assertTrue("a readable, genuinely empty folder is a real deletion", gameDao.stored.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun `rescan preserves cancellation from the walker`() {
        runBlocking {
            val repo = LibraryRepositoryImpl(
                gameDao = FakeGameDao(),
                platformDao = FakePlatformDao(),
                sourceRootDao = FakeSourceDao(),
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = FakeFavoriteDao(),
                walker = object : SourceLister {
                    override suspend fun walk(rootUri: String): SourceWalkResult =
                        throw CancellationException("cancelled")
                },
                runScanTransaction = { block -> block() },
                clock = { 1000L },
            )

            repo.rescan("roms")
        }
    }

    @Test
    fun `rescanning one root never prunes another root's rows`() = runBlocking {
        val gameDao = FakeGameDao()
        fun repoFor(walker: SourceLister) = LibraryRepositoryImpl(
            gameDao = gameDao, platformDao = FakePlatformDao(), sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(), favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1L },
        )
        repoFor(MutableStubWalker("sdA", listOf(wf("A.gba", "gba/A.gba")))).rescan("sdA")
        repoFor(MutableStubWalker("sdB", listOf(wf("B.gba", "gba/B.gba")))).rescan("sdB")
        assertEquals(2, gameDao.stored.size)
        // Rescan root A empty (card removed) — B's row must survive.
        repoFor(MutableStubWalker("sdA", emptyList())).rescan("sdA")
        assertEquals("only A pruned", 1, gameDao.stored.size)
        assertEquals("sdB", gameDao.stored.values.single().sourceRootUri)
        assertEquals(listOf("sdA", "sdB", "sdA"), gameDao.entryKeySourceQueries)
        assertEquals("production prune never asks for the whole library", 0, gameDao.allEntryKeyQueryCount)
    }

    @Test
    fun `healthy rescan of either side dissolves a complete cross-root exact group`() = runBlocking {
        for (touchedRoot in listOf("sdA", "sdB")) {
            val gameDao = FakeGameDao()
            fun repoFor(root: String, file: String) = LibraryRepositoryImpl(
                gameDao = gameDao,
                platformDao = FakePlatformDao(),
                sourceRootDao = FakeSourceDao(),
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = FakeFavoriteDao(),
                walker = MutableStubWalker(root, listOf(wf(file, "GBA/$file"))),
                runScanTransaction = { block -> block() },
                clock = { 1L },
            )
            repoFor("sdA", "Alpha.gba").rescan("sdA")
            repoFor("sdB", "Beta.gba").rescan("sdB")

            val winner = gameDao.stored.values.first { it.sourceRootUri == "sdA" }
            val loser = gameDao.stored.values.first { it.sourceRootUri == "sdB" }
            val hash = "raw-sha1:" + "c".repeat(40)
            gameDao.stored[winner.entryKey] = winner.copy(
                contentHash = hash,
                duplicateGroupKind = "EXACT_HASH",
                canonicalTitle = "Alpha canonical",
                identityResolvedAtMillis = 10L,
            )
            gameDao.stored[loser.entryKey] = loser.copy(
                contentHash = hash,
                isPrimaryMember = false,
                duplicateGroupId = winner.gameId,
                duplicateGroupKind = "EXACT_HASH",
                canonicalTitle = "Beta canonical",
                identityResolvedAtMillis = 10L,
            )

            val file = if (touchedRoot == "sdA") "Alpha.gba" else "Beta.gba"
            repoFor(touchedRoot, file).rescan(touchedRoot)

            val afterWinner = gameDao.stored.getValue(winner.entryKey)
            val afterLoser = gameDao.stored.getValue(loser.entryKey)
            assertTrue("$touchedRoot winner must be primary", afterWinner.isPrimaryMember)
            assertTrue("$touchedRoot loser must be primary", afterLoser.isPrimaryMember)
            assertEquals(null, afterWinner.duplicateGroupKind)
            assertEquals(null, afterLoser.duplicateGroupKind)
            val touched = if (touchedRoot == "sdA") afterWinner else afterLoser
            val untouched = if (touchedRoot == "sdA") afterLoser else afterWinner
            assertEquals("rescanned hash must invalidate", null, touched.contentHash)
            assertEquals("rescanned enrichment must invalidate", null, touched.identityResolvedAtMillis)
            assertEquals("other root keeps valid hash", hash, untouched.contentHash)
            assertEquals("other root keeps enrichment", 10L, untouched.identityResolvedAtMillis)
        }
    }

    @Test
    fun `source roles keep exactly one explicit primary`() = runBlocking {
        val walker = MutableByRootOutcomeWalker(
            mutableMapOf(
                "main" to SourceWalkResult.Complete(listOf(wf("Main.gba", "GBA/Main.gba"))),
                "extra" to SourceWalkResult.Complete(listOf(wf("Extra.gba", "GBA/Extra.gba"))),
                "replacement" to SourceWalkResult.Complete(listOf(wf("New.gba", "GBA/New.gba"))),
            ),
        )
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
        )

        repo.addSource("main", LibrarySourceRole.PRIMARY)
        repo.addSource("extra", LibrarySourceRole.ADDITIONAL)
        assertEquals(listOf("main"), sourceDao.stored.values.filter { it.isPrimarySource }.map { it.rootUri })

        repo.addSource("replacement", LibrarySourceRole.PRIMARY)
        assertEquals(
            listOf("replacement"),
            sourceDao.stored.values.filter { it.isPrimarySource }.map { it.rootUri },
        )
    }

    @Test
    fun `owner removal drops only the selected additional source and its indexed games`() = runBlocking {
        val sourceDao = FakeSourceDao()
        val gameDao = FakeGameDao()
        val artSource = CountingArtSource()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            artSource = artSource,
            walker = MutableByRootOutcomeWalker(
                mutableMapOf(
                    "main" to SourceWalkResult.Complete(listOf(wf("Main.gba", "GBA/Main.gba"))),
                    "extra" to SourceWalkResult.Complete(listOf(wf("Extra.gba", "GBA/Extra.gba"))),
                ),
            ),
            runScanTransaction = { block -> block() },
        )
        repo.addSource("main", LibrarySourceRole.PRIMARY)
        repo.addSource("extra", LibrarySourceRole.ADDITIONAL)
        artSource.invalidateAllCount = 0

        val result = repo.removeAdditionalSource("extra")

        assertEquals(SourceRemovalResult.Removed("extra"), result)
        assertEquals(setOf("main"), sourceDao.stored.keys)
        assertTrue(gameDao.stored.values.all { it.sourceRootUri == "main" })
        assertEquals(1, artSource.invalidateAllCount)
    }

    @Test
    fun `owner removal rejects primary and leaves its registry and index untouched`() = runBlocking {
        val sourceDao = FakeSourceDao()
        val gameDao = FakeGameDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = MutableByRootOutcomeWalker(
                mutableMapOf(
                    "main" to SourceWalkResult.Complete(listOf(wf("Main.gba", "GBA/Main.gba"))),
                ),
            ),
            runScanTransaction = { block -> block() },
        )
        repo.addSource("main", LibrarySourceRole.PRIMARY)
        val indexedBefore = LinkedHashMap(gameDao.stored)

        assertEquals(SourceRemovalResult.PrimaryRejected, repo.removeAdditionalSource("main"))
        assertEquals(setOf("main"), sourceDao.stored.keys)
        assertEquals(indexedBefore, gameDao.stored)
        assertEquals(SourceRemovalResult.NotFound, repo.removeAdditionalSource("missing"))
    }

    @Test
    fun `explicit reconciliation preserves a failed root even when diagnostic text says it is missing`() =
        runBlocking {
            val outcomes = mutableMapOf<String, SourceWalkResult>(
                "main" to SourceWalkResult.Complete(listOf(wf("Main.gba", "GBA/Main.gba"))),
                "gone" to SourceWalkResult.Complete(listOf(wf("Gone.gba", "GBA/Gone.gba"))),
            )
            val gameDao = FakeGameDao()
            val sourceDao = FakeSourceDao()
            val favorites = FakeFavoriteDao()
            val repo = LibraryRepositoryImpl(
                gameDao = gameDao,
                platformDao = FakePlatformDao(),
                sourceRootDao = sourceDao,
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = favorites,
                walker = MutableByRootOutcomeWalker(outcomes),
                runScanTransaction = { block -> block() },
            )
            repo.addSource("main", LibrarySourceRole.PRIMARY)
            repo.addSource("gone", LibrarySourceRole.ADDITIONAL)
            val favoriteId = gameDao.stored.values.first { it.sourceRootUri == "main" }.gameId
            favorites.set(FavoriteEntity(favoriteId, 7L))

            outcomes["gone"] = SourceWalkResult.Failed("Source root does not exist")
            val receipt = repo.reconcileSources()

            assertEquals(2, receipt.sourcesChecked)
            assertEquals(1, receipt.healthySources)
            assertEquals(0, receipt.removedSources)
            assertEquals(0, receipt.unavailableSources)
            assertEquals(1, receipt.failedSources)
            assertEquals(setOf("main", "gone"), sourceDao.stored.keys)
            assertEquals(2, gameDao.stored.size)
            assertTrue(favoriteId in favorites.stored)
            assertEquals(listOf("main"), sourceDao.stored.values.filter { it.isPrimarySource }.map { it.rootUri })
        }

    @Test
    fun `explicit confirmed missing result removes only Mimir index and registry state`() = runBlocking {
        val outcomes = mutableMapOf<String, SourceWalkResult>(
            "main" to SourceWalkResult.Complete(listOf(wf("Main.gba", "GBA/Main.gba"))),
            "gone" to SourceWalkResult.Complete(listOf(wf("Gone.gba", "GBA/Gone.gba"))),
        )
        val gameDao = FakeGameDao()
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = MutableByRootOutcomeWalker(outcomes),
            runScanTransaction = { block -> block() },
        )
        repo.addSource("main", LibrarySourceRole.PRIMARY)
        repo.addSource("gone", LibrarySourceRole.ADDITIONAL)

        outcomes["gone"] = SourceWalkResult.Missing("Presence authority confirmed absence")
        val receipt = repo.reconcileSources()

        assertEquals(2, receipt.sourcesChecked)
        assertEquals(1, receipt.healthySources)
        assertEquals(1, receipt.removedSources)
        assertEquals(0, receipt.unavailableSources)
        assertEquals(0, receipt.failedSources)
        assertEquals(setOf("main"), sourceDao.stored.keys)
        assertTrue(gameDao.stored.values.all { it.sourceRootUri == "main" })
    }

    @Test
    fun `explicit reconciliation does not silently promote an additional root when primary is removed`() =
        runBlocking {
            val outcomes = mutableMapOf<String, SourceWalkResult>(
                "main" to SourceWalkResult.Complete(listOf(wf("Main.gba", "GBA/Main.gba"))),
                "extra" to SourceWalkResult.Complete(listOf(wf("Extra.gba", "GBA/Extra.gba"))),
            )
            val sourceDao = FakeSourceDao()
            val repo = LibraryRepositoryImpl(
                gameDao = FakeGameDao(),
                platformDao = FakePlatformDao(),
                sourceRootDao = sourceDao,
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = FakeFavoriteDao(),
                walker = MutableByRootOutcomeWalker(outcomes),
                runScanTransaction = { block -> block() },
            )
            repo.addSource("main", LibrarySourceRole.PRIMARY)
            repo.addSource("extra", LibrarySourceRole.ADDITIONAL)

            outcomes["main"] = SourceWalkResult.Missing("Presence authority confirmed absence")
            val receipt = repo.reconcileSources()

            assertEquals(1, receipt.removedSources)
            assertEquals(setOf("extra"), sourceDao.stored.keys)
            assertTrue(sourceDao.stored.values.none(SourceRootEntity::isPrimarySource))
        }

    @Test
    fun `reconciliation retains a healthy empty root and batches media invalidation once`() = runBlocking {
        val outcomes = mutableMapOf<String, SourceWalkResult>(
            "main" to SourceWalkResult.Complete(listOf(wf("Main.gba", "GBA/Main.gba"))),
            "empty" to SourceWalkResult.Complete(emptyList()),
        )
        val sourceDao = FakeSourceDao()
        val artSource = CountingArtSource()
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            artSource = artSource,
            walker = MutableByRootOutcomeWalker(outcomes),
            runScanTransaction = { block -> block() },
        )
        repo.addSource("main", LibrarySourceRole.PRIMARY)
        repo.addSource("empty", LibrarySourceRole.ADDITIONAL)
        artSource.invalidateAllCount = 0

        val receipt = repo.reconcileSources()

        assertEquals(2, receipt.healthySources)
        assertEquals(0, receipt.removedSources)
        assertEquals(setOf("main", "empty"), sourceDao.stored.keys)
        assertEquals(1, artSource.invalidateAllCount)
    }

    @Test
    fun `reconciliation reports mixed typed outcomes and preserves every non-authoritative failure`() =
        runBlocking {
            val outcomes = mutableMapOf<String, SourceWalkResult>(
                "healthy" to SourceWalkResult.Complete(listOf(wf("Healthy.gba", "GBA/Healthy.gba"))),
                "missing" to SourceWalkResult.Complete(listOf(wf("Missing.gba", "GBA/Missing.gba"))),
                "unavailable" to SourceWalkResult.Complete(listOf(wf("Unavailable.gba", "GBA/Unavailable.gba"))),
                "failed" to SourceWalkResult.Complete(listOf(wf("Failed.gba", "GBA/Failed.gba"))),
            )
            val gameDao = FakeGameDao()
            val sourceDao = FakeSourceDao()
            val artSource = CountingArtSource()
            val repo = LibraryRepositoryImpl(
                gameDao = gameDao,
                platformDao = FakePlatformDao(),
                sourceRootDao = sourceDao,
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = FakeFavoriteDao(),
                artSource = artSource,
                walker = MutableByRootOutcomeWalker(outcomes),
                runScanTransaction = { block -> block() },
            )
            repo.addSource("healthy", LibrarySourceRole.PRIMARY)
            repo.addSource("missing", LibrarySourceRole.ADDITIONAL)
            repo.addSource("unavailable", LibrarySourceRole.ADDITIONAL)
            repo.addSource("failed", LibrarySourceRole.ADDITIONAL)
            artSource.invalidateAllCount = 0

            outcomes["missing"] = SourceWalkResult.Missing("Positive absence proof")
            outcomes["unavailable"] = SourceWalkResult.Unavailable("Permission revoked")
            outcomes["failed"] = SourceWalkResult.Failed("Provider protocol fault")
            val receipt = repo.reconcileSources()

            assertEquals(4, receipt.sourcesChecked)
            assertEquals(1, receipt.healthySources)
            assertEquals(1, receipt.removedSources)
            assertEquals(1, receipt.unavailableSources)
            assertEquals(1, receipt.failedSources)
            assertEquals(1, receipt.filesSeen)
            assertEquals(1, receipt.recognized)
            assertEquals(1, receipt.groups)
            assertEquals(0, receipt.skipped)
            assertEquals(setOf("healthy", "unavailable", "failed"), sourceDao.stored.keys)
            assertEquals(3, gameDao.stored.size)
            assertEquals(1, artSource.invalidateAllCount)
        }

    @Test
    fun `all preserved reconciliation failures do not invalidate media`() = runBlocking {
        val outcomes = mutableMapOf<String, SourceWalkResult>(
            "unavailable" to SourceWalkResult.Complete(listOf(wf("Unavailable.gba", "GBA/Unavailable.gba"))),
            "failed" to SourceWalkResult.Complete(listOf(wf("Failed.gba", "GBA/Failed.gba"))),
        )
        val gameDao = FakeGameDao()
        val sourceDao = FakeSourceDao()
        val artSource = CountingArtSource()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            artSource = artSource,
            walker = MutableByRootOutcomeWalker(outcomes),
            runScanTransaction = { block -> block() },
        )
        repo.addSource("unavailable", LibrarySourceRole.PRIMARY)
        repo.addSource("failed", LibrarySourceRole.ADDITIONAL)
        artSource.invalidateAllCount = 0

        outcomes["unavailable"] = SourceWalkResult.Unavailable("Storage detached")
        outcomes["failed"] = SourceWalkResult.Failed("Provider protocol fault")
        val receipt = repo.reconcileSources()

        assertEquals(0, receipt.healthySources)
        assertEquals(0, receipt.removedSources)
        assertEquals(1, receipt.unavailableSources)
        assertEquals(1, receipt.failedSources)
        assertEquals(setOf("unavailable", "failed"), sourceDao.stored.keys)
        assertEquals(2, gameDao.stored.size)
        assertEquals(0, artSource.invalidateAllCount)
    }

    @Test(expected = CancellationException::class)
    fun `reconciliation preserves cancellation from the walker`() {
        runBlocking {
            val sourceDao = FakeSourceDao()
            sourceDao.upsert(
                SourceRootEntity(
                    rootId = "roms",
                    rootUri = "roms",
                    locationKind = "FILE",
                    friendlyLabel = "roms",
                    isPrimarySource = true,
                    lastSeenMillis = 1L,
                ),
            )
            val repo = LibraryRepositoryImpl(
                gameDao = FakeGameDao(),
                platformDao = FakePlatformDao(),
                sourceRootDao = sourceDao,
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = FakeFavoriteDao(),
                walker = object : SourceLister {
                    override suspend fun walk(rootUri: String): SourceWalkResult =
                        throw CancellationException("cancelled")
                },
                runScanTransaction = { block -> block() },
            )

            repo.reconcileSources()
        }
    }

    @Test
    fun `duplicate relative paths fail before persistence`() = runBlocking {
        var transactions = 0
        val duplicatePath = listOf(
            wf("Alpha.gba", "GBA/Same.gba"),
            wf("Beta.gba", "GBA/Same.gba").copy(filePath = "/alternate/GBA/Same.gba"),
        )
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = StubWalker(mapOf("roms" to duplicatePath)),
            runScanTransaction = { block ->
                transactions += 1
                block()
            },
        )

        val result = repo.rescan("roms")

        assertTrue(result.outcome is ScanOutcome.Failed)
        assertEquals(0, transactions)
    }

    @Test
    fun `post commit media cache failure cannot disguise a healthy durable scan`() = runBlocking {
        val gameDao = FakeGameDao()
        val sourceDao = FakeSourceDao()
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            artSource = object : ArtSource {
                override fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null
                override fun invalidateAll() = error("synthetic cache failure")
            },
            walker = StubWalker(mapOf("roms" to listOf(wf("Game.gba", "GBA/Game.gba")))),
            runScanTransaction = { block -> block() },
        )

        val result = repo.rescan("roms")

        assertEquals(ScanOutcome.Healthy, result.outcome)
        assertEquals(1, gameDao.stored.size)
        assertEquals(setOf("roms"), sourceDao.stored.keys)
    }

    @Test
    fun `provider enumeration order cannot change the selected primary`() = runBlocking {
        val files = listOf(
            wf("Final Fantasy VII (Disc 2).chd", "PSX/Final Fantasy VII (Disc 2).chd"),
            wf("Final Fantasy VII.m3u", "PSX/Final Fantasy VII.m3u"),
            wf("Final Fantasy VII (Disc 1).chd", "PSX/Final Fantasy VII (Disc 1).chd"),
        )

        suspend fun scan(order: List<WalkedFile>): String {
            val games = FakeGameDao()
            val repo = LibraryRepositoryImpl(
                gameDao = games,
                platformDao = FakePlatformDao(),
                sourceRootDao = FakeSourceDao(),
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = FakeFavoriteDao(),
                walker = StubWalker(mapOf("roms" to order)),
                runScanTransaction = { block -> block() },
            )
            repo.rescan("roms")
            return games.stored.values.single { it.isPrimaryMember }.entryKey
        }

        assertEquals(scan(files), scan(files.reversed()))
    }

    @Test
    fun `scan publication rolls back at every mutation checkpoint`() = runBlocking {
        val fault = MutationFault()
        val gameDao = FakeGameDao(fault::checkpoint)
        val platformDao = FakePlatformDao(fault::checkpoint)
        val sourceDao = FakeSourceDao(fault::checkpoint)
        val artSource = CountingArtSource()
        val transaction = SnapshotTransaction(gameDao, platformDao, sourceDao, fault)
        val walker = MutableStubWalker(
            root = "roms",
            files = listOf(wf("Old.gba", "GBA/Old.gba")),
        )
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = platformDao,
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            artSource = artSource,
            walker = walker,
            runScanTransaction = transaction::run,
            clock = { 10L },
        )
        repo.rescan("roms")
        artSource.invalidateAllCount = 0
        val oldGames = LinkedHashMap(gameDao.stored)
        val oldPlatforms = LinkedHashMap(platformDao.stored)
        val oldSources = LinkedHashMap(sourceDao.stored)
        walker.files = listOf(wf("New.sfc", "SNES/New.sfc"))

        listOf(
            "games.upsert",
            "platforms.upsert",
            "games.delete-batch",
            "sources.upsert",
            "transaction.commit",
        ).forEach { checkpoint ->
            fault.failAt = checkpoint
            try {
                repo.rescan("roms")
                fail("Expected injected failure at $checkpoint")
            } catch (_: InjectedScanFault) {
                // Expected: the transaction adapter restores the exact prior source snapshot.
            }
            assertEquals("games after $checkpoint", oldGames, gameDao.stored)
            assertEquals("platforms after $checkpoint", oldPlatforms, platformDao.stored)
            assertEquals("sources after $checkpoint", oldSources, sourceDao.stored)
            assertEquals("media invalidation after $checkpoint", 0, artSource.invalidateAllCount)
        }
    }

    @Test
    fun `cancellation during publication rolls back the old source snapshot`() = runBlocking {
        val fault = MutationFault()
        val gameDao = FakeGameDao(fault::checkpoint)
        val platformDao = FakePlatformDao(fault::checkpoint)
        val sourceDao = FakeSourceDao(fault::checkpoint)
        val transaction = SnapshotTransaction(gameDao, platformDao, sourceDao, fault)
        val walker = MutableStubWalker("roms", listOf(wf("Old.gba", "GBA/Old.gba")))
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = platformDao,
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = transaction::run,
        )
        repo.rescan("roms")
        val oldGames = LinkedHashMap(gameDao.stored)
        val oldPlatforms = LinkedHashMap(platformDao.stored)
        val oldSources = LinkedHashMap(sourceDao.stored)
        walker.files = listOf(wf("New.sfc", "SNES/New.sfc"))
        fault.failAt = "platforms.upsert"
        fault.failure = CancellationException("cancel publication")

        try {
            repo.rescan("roms")
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertEquals(oldGames, gameDao.stored)
        assertEquals(oldPlatforms, platformDao.stored)
        assertEquals(oldSources, sourceDao.stored)
    }

    @Test
    fun `confirmed missing removal rolls back as one source transaction`() = runBlocking {
        val fault = MutationFault()
        val gameDao = FakeGameDao(fault::checkpoint)
        val platformDao = FakePlatformDao(fault::checkpoint)
        val sourceDao = FakeSourceDao(fault::checkpoint)
        val transaction = SnapshotTransaction(gameDao, platformDao, sourceDao, fault)
        val outcomes = mutableMapOf<String, SourceWalkResult>(
            "roms" to SourceWalkResult.Complete(listOf(wf("Old.gba", "GBA/Old.gba"))),
        )
        val repo = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = platformDao,
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = MutableByRootOutcomeWalker(outcomes),
            runScanTransaction = transaction::run,
        )
        repo.addSource("roms", LibrarySourceRole.PRIMARY)
        val oldGames = LinkedHashMap(gameDao.stored)
        val oldPlatforms = LinkedHashMap(platformDao.stored)
        val oldSources = LinkedHashMap(sourceDao.stored)
        outcomes["roms"] = SourceWalkResult.Missing("positive absence proof")

        listOf("games.delete-batch", "sources.remove", "transaction.commit").forEach { checkpoint ->
            fault.failAt = checkpoint
            try {
                repo.reconcileSources()
                fail("Expected injected failure at $checkpoint")
            } catch (_: InjectedScanFault) {
                // Expected.
            }
            assertEquals("games after $checkpoint", oldGames, gameDao.stored)
            assertEquals("platforms after $checkpoint", oldPlatforms, platformDao.stored)
            assertEquals("sources after $checkpoint", oldSources, sourceDao.stored)
        }
    }

    @Test
    fun `scan gate serializes separate repository instances`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val firstWalker = object : SourceLister {
            override suspend fun walk(rootUri: String): SourceWalkResult {
                firstEntered.complete(Unit)
                releaseFirst.await()
                return SourceWalkResult.Complete(emptyList())
            }
        }
        val secondWalker = object : SourceLister {
            override suspend fun walk(rootUri: String): SourceWalkResult {
                secondEntered.complete(Unit)
                return SourceWalkResult.Complete(emptyList())
            }
        }
        fun repository(walker: SourceLister) = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
        )

        val first = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository(firstWalker).rescan("first")
        }
        firstEntered.await()
        val second = async(Dispatchers.Default) {
            repository(secondWalker).rescan("second")
        }
        delay(100)
        assertFalse("second repository entered while first held the process gate", secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        withTimeout(2_000) {
            first.await()
            second.await()
            secondEntered.await()
        }
    }

    @Test
    fun `first-add detach snapshot between walk and commit rejects the unregistered stale scan`() = runBlocking {
        val root = "/storage/ABCD-1234/ROMs"
        val walkEntered = CompletableDeferred<Unit>()
        val releaseWalk = CompletableDeferred<Unit>()
        val walker = object : SourceLister {
            override suspend fun walk(rootUri: String): SourceWalkResult {
                walkEntered.complete(Unit)
                releaseWalk.await()
                return SourceWalkResult.Complete(
                    listOf(
                        WalkedFile(
                            displayName = "Metroid.gba",
                            relativePath = "GBA/Metroid.gba",
                            sizeBytes = 256L * 1024,
                            documentUri = null,
                            filePath = "$root/GBA/Metroid.gba",
                        ),
                    ),
                )
            }
        }
        val gameDao = FakeGameDao()
        val sourceDao = FakeSourceDao()
        val mutations = SourceRootMutationCoordinator()
        val repository = LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            sourceRootMutations = mutations,
        )

        val scan = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository.rescan(root)
        }
        walkEntered.await()
        mutations.applyPresenceSnapshot(
            // The first-add root is not registered yet, so the monitor's Room-derived lists are empty.
            absentRootUris = emptyList(),
            presentRootUris = emptyList(),
            rootPresentAtSnapshot = { candidate -> candidate != root },
        ) {
            // This is where StorageVolumeMonitor commits STORAGE_UNAVAILABLE.
        }
        releaseWalk.complete(Unit)
        val result = withTimeout(2_000) { scan.await() }

        val outcome = result.outcome as ScanOutcome.Failed
        assertEquals(com.gruning.mimir.rewrite.core.model.library.SourceFailureKind.UNAVAILABLE, outcome.kind)
        assertTrue("stale scan wrote games after detach", gameDao.stored.isEmpty())
        assertTrue("stale scan registered an absent root", sourceDao.stored.isEmpty())
    }

    // ── stubs / fakes ────────────────────────────────────────────────────────

    @Test
    fun `CHARACTERIZATION the reported x360 iso survives the whole scan path to a shelf row`() =
        runBlocking {
            // Replay of a reported field case: an `.iso` under `x360/` reported as
            // missing from the shelf. This records what the pipeline ACTUALLY does end to end —
            // walk → recognize → group → persist — rather than what it ought to do.
            //
            // Observed: it is recognized as xbox360, groups to one tile, and persists a primary game
            // row plus a platform row. So neither recognition NOR grouping NOR persistence drops it;
            // the reported disappearance must come from somewhere this harness does not cover (the
            // real SAF traversal, or the shelf/visibility projection above the repository).
            val name = "Resident Evil 5 ~ Biohazard 5 (World) (En,Ja,Fr,De,Es,It).iso"
            val gameDao = FakeGameDao()
            val platformDao = FakePlatformDao()
            val repo = LibraryRepositoryImpl(
                gameDao = gameDao,
                platformDao = platformDao,
                sourceRootDao = FakeSourceDao(),
                hiddenItemDao = FakeHiddenDao(),
                favoriteDao = FakeFavoriteDao(),
                walker = StubWalker(mapOf("path/roms" to listOf(wf(name, "x360/$name")))),
                runScanTransaction = { block -> block() },
                clock = { 1000L },
            )

            val result = repo.rescan("path/roms")

            assertEquals(1, result.filesSeen)
            assertEquals("recognition does NOT drop it", 1, result.recognized)
            assertEquals(0, result.skipped)
            assertTrue("nothing to explain away", result.skippedByReason.isEmpty())
            assertEquals("grouping does NOT drop it", 1, result.groups)

            // Persistence does not drop it either: one primary row on the xbox360 platform.
            val primaries = gameDao.stored.values.filter { it.isPrimaryMember }
            assertEquals(1, primaries.size)
            assertEquals("xbox360", primaries.single().platformId)
            assertEquals("Resident Evil 5 ~ Biohazard 5", primaries.single().title)
            assertTrue("xbox360 platform row is written", platformDao.stored.containsKey("xbox360"))
            assertEquals(1, platformDao.stored.getValue("xbox360").gamesCount)
        }

    @Test
    fun `scan attributes every skipped file to the reason that dropped it`() = runBlocking {
        // Each skipped case below is one the recognizer's own suite already pins to this exact
        // reason (see ScanRecognizerTest) — so a miscount here is the ledger's fault, not a
        // disagreement about classification.
        val walked = listOf(
            // Ambiguous containers: the extension is claimed by several platforms and the file sits
            // at the root, so no folder resolves it. Two of them, so the test reads a COUNT and not
            // merely the presence of a key.
            wf("Game.iso", "Game.iso"),
            wf("Game.rvz", "Game.rvz"),
            // A different reason each, to prove reasons do not bleed into one another.
            wf("archive.chd.cpgz", "archive.chd.cpgz"),
            wf("README.TXT", "README.TXT"),
            wf(".DS_Store", ".DS_Store"),
            wf("SCPH1001.BIN", "PS1/BIOS/SCPH1001.BIN"),
            // Two real games, so recognized is non-zero and the totals have to balance.
            wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"),
            wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba"),
        )
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = StubWalker(mapOf("path/roms" to walked)),
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        val result = repo.rescan("path/roms")

        // (a) the ambiguous drops land under AMBIGUOUS_PLATFORM, counted not just flagged.
        assertEquals(2, result.skippedByReason["AMBIGUOUS_PLATFORM"])
        // (b) every other drop lands under ITS OWN reason.
        assertEquals(1, result.skippedByReason["UNKNOWN_EXTENSION"])
        assertEquals(1, result.skippedByReason["SIDECAR_EXTENSION"])
        assertEquals(1, result.skippedByReason["HIDDEN_PATH"])
        assertEquals(1, result.skippedByReason["SUPPORT_CONTENT"])
        // The whole breakdown, so a reason silently appearing or vanishing fails here.
        assertEquals(
            mapOf(
                "UNKNOWN_EXTENSION" to 1,
                "HIDDEN_PATH" to 1,
                "SIDECAR_EXTENSION" to 1,
                "SUPPORT_CONTENT" to 1,
                "AMBIGUOUS_PLATFORM" to 2,
            ),
            result.skippedByReason,
        )
        // Reasons that did not fire are omitted rather than reported as zero.
        assertFalse(result.skippedByReason.containsKey("EMPTY_FILE"))
        assertFalse(result.skippedByReason.containsKey("IMPLAUSIBLE_SIZE"))

        // (c) the totals are unchanged by instrumentation: every walked file is still either
        // recognized or skipped, exactly once, and the breakdown accounts for all of the skips.
        assertEquals("all 8 files seen", 8, result.filesSeen)
        assertEquals("both gba games recognized", 2, result.recognized)
        assertEquals("the other 6 skipped", 6, result.skipped)
        assertEquals(result.filesSeen, result.recognized + result.skipped)
        assertEquals(result.skipped, result.skippedByReason.values.sum())
    }

    @Test
    fun `the skip breakdown is ordered and empty when nothing is dropped`() = runBlocking {
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = StubWalker(
                mapOf(
                    "clean" to listOf(wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba")),
                    "dirty" to listOf(
                        wf("Game.iso", "Game.iso"),
                        wf("README.TXT", "README.TXT"),
                    ),
                ),
            ),
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        val clean = repo.rescan("clean")
        assertEquals(0, clean.skipped)
        assertTrue("a scan that drops nothing claims no reasons", clean.skippedByReason.isEmpty())

        // Keys follow SkipReason declaration order, so a logged summary reads identically every scan.
        val dirty = repo.rescan("dirty")
        assertEquals(
            listOf("SIDECAR_EXTENSION", "AMBIGUOUS_PLATFORM"),
            dirty.skippedByReason.keys.toList(),
        )
    }

    // ── D1-R21: what a scan may honestly say while it runs ──────────────────────────────────────

    @Test
    fun `the walk publishes a count and no fraction, because no total exists yet`() = runBlocking {
        val walked = listOf(
            wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba"),
            wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"),
            wf("Final Fantasy VII.m3u", "PSX/Final Fantasy VII.m3u"),
        )
        val walker = ProgressCapturingWalker("path/roms", walked)
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        repo.rescan("path/roms")

        val duringWalk = checkNotNull(walker.duringWalk) { "the repository never passed a progress callback" }
        assertEquals(ScanPhase.WALKING, duringWalk.phase)
        assertEquals("the walk reports what it has actually found", 7, duringWalk.seen)
        assertNull("nothing knows the total during a walk, so there is no honest bar", duringWalk.total)
        assertNull("and therefore no fraction -- null means no bar, never stalled", duringWalk.fraction)
    }

    @Test
    fun `recognition publishes a real fraction over the walked total`() = runBlocking {
        val walked = listOf(
            wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba"),
            wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"),
            wf("Final Fantasy VII.m3u", "PSX/Final Fantasy VII.m3u"),
        )
        val walker = ProgressCapturingWalker("path/roms", walked)
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        repo.rescan("path/roms")

        val afterRecognise = checkNotNull(walker.afterRecognise) {
            "readUtf8 was never called, so this test observed nothing -- the fixture needs an .m3u"
        }
        assertEquals(ScanPhase.RECOGNISING, afterRecognise.phase)
        assertEquals("every walked file is accounted for by the time recognition ends", 3, afterRecognise.seen)
        assertEquals("the total is the walked size, known only after the walk", 3, afterRecognise.total)
        assertEquals("a completed count over a known total IS an honest fraction", 1f, afterRecognise.fraction)
    }

    @Test
    fun `progress is cleared when the scan ends, so no bar outlives its job`() = runBlocking {
        val walker = ProgressCapturingWalker("path/roms", listOf(wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba")))
        val repo = LibraryRepositoryImpl(
            gameDao = FakeGameDao(),
            platformDao = FakePlatformDao(),
            sourceRootDao = FakeSourceDao(),
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = walker,
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )

        repo.rescan("path/roms")

        assertNull(
            "a finished scan must leave nothing behind -- absence is stated, not drawn as an empty bar",
            ScanProgressState.progress.value,
        )
        // Asserting only that the channel is EMPTY afterwards is a pin that cannot fail: it passes
        // whether the scan mirrored and cleared, or never mirrored at all. The differential replay
        // caught exactly that, so this now proves the row EXISTED first.
        val duringScan = walker.sharedDuringWalk.firstOrNull { it.kind == JobKind.SCAN }
        assertEquals(
            "the shared channel must carry the scan while it runs, not only after it stops (D1-R22)",
            7,
            checkNotNull(duringScan) { "no SCAN row on the shared channel during the walk" }.seen,
        )
        assertNull(
            "and it must clear with the scan -- one producer call keeps the two in step, so they can " +
                "never disagree about whether a scan is running",
            JobProgressState.running.value.firstOrNull { it.kind == JobKind.SCAN },
        )
    }

    private fun wf(name: String, relative: String) =
        WalkedFile(
            displayName = name,
            relativePath = relative,
            sizeBytes = 2L * 1024 * 1024,
            documentUri = null,
            filePath = "/$relative",
        )

    // ── One folder, two spellings, ONE source row ────────────────────────────────────────────────
    //
    // Reproduced from a live database, pulled read-only off the Odin 2026-08-29:
    // `source_roots` carried BOTH `content://…/tree/6A7F-FCFE%3AROMs` (SAF, registered by a QA rescan
    // hook on 2026-08-26 16:57) and `/storage/6A7F-FCFE/ROMs` (managed library source, `isPrimarySource=1`), with
    // the same friendly label. Every one of the 38 ROMs was indexed twice and 25 games wore a phantom
    // "2 versions" badge.

    private val dedupeSafRoot = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs"
    private val dedupePathRoot = "/storage/6A7F-FCFE/ROMs"

    private fun dedupeRepo(sourceDao: SourceRootDao): LibraryRepositoryImpl =
        dedupeRepoWith(FakeGameDao(), sourceDao)

    private fun dedupeRepoWith(gameDao: GameDao, sourceDao: SourceRootDao): LibraryRepositoryImpl {
        val one = listOf(
            wf("Resident Evil 2 (USA).iso", "gamecube/Resident Evil 2 (USA).iso"),
        )
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = FakePlatformDao(),
            sourceRootDao = sourceDao,
            hiddenItemDao = FakeHiddenDao(),
            favoriteDao = FakeFavoriteDao(),
            walker = StubWalker(
                mapOf(
                    dedupePathRoot to one,
                    dedupeSafRoot to one,
                    "/storage/6A7F-FCFE/ROMs2" to one,
                ),
            ),
            runScanTransaction = { block -> block() },
            clock = { 1000L },
        )
    }

    /** The recorded order: the managed path first (2026-08-24), then the QA hook's SAF grant (08-26). */
    @Test
    fun aSafRescanOfAnAlreadyManagedFolderDoesNotMintASecondRoot() = runBlocking {
        val sourceDao = FakeSourceDao()
        val repo = dedupeRepo(sourceDao)

        repo.rescan(dedupePathRoot)
        assertEquals(1, sourceDao.stored.size)

        repo.rescan(dedupeSafRoot)

        assertEquals("the SAF spelling must refresh the folder, not rival it", 1, sourceDao.stored.size)
        val row = sourceDao.stored.values.single()
        assertEquals("the row already there keeps its identity", dedupePathRoot, row.rootUri)
        assertEquals(dedupePathRoot, row.rootId)
        assertEquals("a SAF rescan must not flip a managed root's kind", "MANAGED_PATH", row.locationKind)
    }

    /** The mirror order, so the fix is not an artefact of which spelling arrived first. */
    @Test
    fun aManagedRescanOfAnAlreadyGrantedFolderDoesNotMintASecondRoot() = runBlocking {
        val sourceDao = FakeSourceDao()
        val repo = dedupeRepo(sourceDao)

        repo.rescan(dedupeSafRoot)
        assertEquals(1, sourceDao.stored.size)

        repo.rescan(dedupePathRoot)

        assertEquals(1, sourceDao.stored.size)
        val row = sourceDao.stored.values.single()
        assertEquals(dedupeSafRoot, row.rootUri)
        assertEquals("SAF_TREE_GRANT", row.locationKind)
    }

    /** A second spelling must not demote the row's primary flag. */
    @Test
    fun theExistingSourceRowKeepsItsPrimaryFlagAcrossASecondSpelling() = runBlocking {
        val sourceDao = FakeSourceDao()
        val repo = dedupeRepo(sourceDao)

        repo.rescan(dedupePathRoot)
        assertTrue(
            "fixture precondition: the first root elects itself primary",
            sourceDao.stored.values.single().isPrimarySource,
        )

        repo.rescan(dedupeSafRoot)

        assertTrue(sourceDao.stored.values.single().isPrimarySource)
    }

    /**
     * **The one the source-count assertions missed.** Guarding registration alone kept `source_roots` at
     * one row while the GAMES table doubled again — caught on the Odin, not here, because every pin above
     * asserts `sourceDao.stored.size` and none of them looked at the rows.
     *
     * A SAF rescan of a folder added by path must re-key nothing: same `entryKey`, same `sourceRootUri`,
     * one row per file.
     */
    @Test
    fun aSafRescanOfAnAlreadyManagedFolderDoesNotDuplicateTheGamesUnderIt() = runBlocking {
        val gameDao = FakeGameDao()
        val repo = dedupeRepoWith(gameDao, FakeSourceDao())

        repo.rescan(dedupePathRoot)
        val afterFirst = gameDao.stored.size
        assertEquals("fixture precondition: one file indexed once", 1, afterFirst)

        repo.rescan(dedupeSafRoot)

        assertEquals("a second spelling must refresh the row, not add one", 1, gameDao.stored.size)
        assertEquals(
            "and the row keeps the registered root's identity",
            dedupePathRoot,
            gameDao.stored.values.single().sourceRootUri,
        )
    }

    /** The mirror order: adopting the registered spelling must work whichever one got there first. */
    @Test
    fun aManagedRescanOfAnAlreadyGrantedFolderDoesNotDuplicateTheGamesUnderIt() = runBlocking {
        val gameDao = FakeGameDao()
        val repo = dedupeRepoWith(gameDao, FakeSourceDao())

        repo.rescan(dedupeSafRoot)
        repo.rescan(dedupePathRoot)

        assertEquals(1, gameDao.stored.size)
        assertEquals(dedupeSafRoot, gameDao.stored.values.single().sourceRootUri)
    }

    /** The prefix trap, end to end: a neighbouring folder is still its own root. */
    @Test
    fun aDifferentFolderOnTheSameCardIsStillItsOwnRoot() = runBlocking {
        val sourceDao = FakeSourceDao()
        val repo = dedupeRepo(sourceDao)

        repo.rescan(dedupePathRoot)
        repo.rescan("/storage/6A7F-FCFE/ROMs2")

        assertEquals(2, sourceDao.stored.size)
    }

    private class StubWalker(private val byRoot: Map<String, List<WalkedFile>>) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(byRoot[rootUri].orEmpty())
        // Plain-path root in the test → not a SAF tree (default isSafTree checks the content:// prefix).
    }

    /**
     * A lister that captures [ScanProgressState] at two moments the scan cannot otherwise be observed
     * at, without threads or timing.
     *
     * [duringWalk] is read from inside the progress callback the repository hands down, so it proves
     * the callback is wired and that the walk publishes a COUNT with no fraction. [afterRecognise] is
     * read from [readUtf8], which `M3uPlaylistResolver` calls **after** the recognise loop has
     * finished — so it observes the last RECOGNISING report without racing a [StateFlow] collector,
     * whose conflation would make an exact-sequence assertion flaky by construction.
     */
    private class ProgressCapturingWalker(
        private val root: String,
        private val files: List<WalkedFile>,
    ) : SourceLister {
        var duringWalk: com.gruning.mimir.rewrite.core.model.jobs.ScanProgress? = null
        var sharedDuringWalk: List<com.gruning.mimir.rewrite.core.model.jobs.JobProgress> = emptyList()
        var afterRecognise: com.gruning.mimir.rewrite.core.model.jobs.ScanProgress? = null

        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(if (rootUri == root) files else emptyList())

        override suspend fun walk(rootUri: String, onProgress: (Int) -> Unit): SourceWalkResult {
            onProgress(7)
            duringWalk = ScanProgressState.progress.value
            sharedDuringWalk = JobProgressState.running.value
            return walk(rootUri)
        }

        override suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? {
            if (afterRecognise == null) afterRecognise = ScanProgressState.progress.value
            return ""
        }
    }

    private class PlaylistStubWalker(
        private val root: String,
        private val files: List<WalkedFile>,
        private val bodies: Map<String, String>,
    ) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(if (rootUri == root) files else emptyList())

        override suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? =
            bodies[file.relativePath]?.takeIf { it.toByteArray().size.toLong() <= maxBytes }
    }

    private class RecordingRootWalker(
        private val expectedRoot: String,
        private val files: List<WalkedFile>,
    ) : SourceLister {
        val requestedRoots = mutableListOf<String>()

        override suspend fun walk(rootUri: String): SourceWalkResult {
            requestedRoots += rootUri
            return SourceWalkResult.Complete(if (rootUri == expectedRoot) files else emptyList())
        }
    }

    private class RecordingOutcomeRootWalker(private val outcome: SourceWalkResult) : SourceLister {
        val requestedRoots = mutableListOf<String>()

        override suspend fun walk(rootUri: String): SourceWalkResult {
            requestedRoots += rootUri
            return outcome
        }
    }

    /** A walker whose file list can change between rescans of one root (to test add/remove reconcile). */
    private class MutableStubWalker(private val root: String, var files: List<WalkedFile>) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(if (rootUri == root) files else emptyList())
    }

    private class MutableOutcomeWalker(var outcome: SourceWalkResult) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult = outcome
    }

    /** A lister whose root can be made unreadable, to model a revoked "All files access" that still lists. */
    private class AccessGatedWalker(var outcome: SourceWalkResult) : SourceLister {
        var readable = true
        var walks = 0

        override suspend fun rootReadable(rootUri: String): Boolean = readable

        override suspend fun walk(rootUri: String): SourceWalkResult {
            walks += 1
            return outcome
        }
    }

    private class MutableByRootOutcomeWalker(
        private val outcomes: MutableMap<String, SourceWalkResult>,
    ) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            outcomes[rootUri] ?: SourceWalkResult.Failed("Source root does not exist")
    }

    private class CountingArtSource : ArtSource {
        var invalidateAllCount = 0
        override fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null
        override fun invalidateAll() {
            invalidateAllCount += 1
        }
    }

    private class MutationFault {
        var failAt: String? = null
        var failure: RuntimeException? = null

        fun checkpoint(label: String) {
            if (label != failAt) return
            throw failure ?: InjectedScanFault(label)
        }
    }

    private class InjectedScanFault(label: String) : RuntimeException(label)

    private class SnapshotTransaction(
        private val gameDao: FakeGameDao,
        private val platformDao: FakePlatformDao,
        private val sourceDao: FakeSourceDao,
        private val fault: MutationFault,
    ) {
        suspend fun run(block: suspend () -> Unit) {
            val games = LinkedHashMap(gameDao.stored)
            val platforms = LinkedHashMap(platformDao.stored)
            val sources = LinkedHashMap(sourceDao.stored)
            try {
                block()
                fault.checkpoint("transaction.commit")
            } catch (error: Throwable) {
                gameDao.stored.clear()
                gameDao.stored.putAll(games)
                platformDao.stored.clear()
                platformDao.stored.putAll(platforms)
                sourceDao.stored.clear()
                sourceDao.stored.putAll(sources)
                throw error
            }
        }
    }

    private class FakeGameDao(
        private val mutationCheckpoint: (String) -> Unit = {},
    ) : GameDao {
        val stored = LinkedHashMap<String, GameEntity>()
        val entryKeySourceQueries = mutableListOf<String>()
        var allEntryKeyQueryCount = 0
        override suspend fun upsertAll(games: List<GameEntity>) {
            games.forEach { stored[it.entryKey] = it }
            mutationCheckpoint("games.upsert")
        }
        override suspend fun byEntryKey(entryKey: String): GameEntity? = stored[entryKey]
        override suspend fun anyGamesExist(): Boolean = stored.isNotEmpty()
        // LG01: the BIOS seeder resolves a game's platform through this, so its filesystem probe stays
        // OUT of Room row projection (SD01 lesson).
        override suspend fun platformIdForGame(gameId: String): String? =
            stored.values.firstOrNull { it.gameId == gameId }?.platformId
        override suspend fun membersOfGame(gameId: String): List<GameEntity> =
            stored.values.filter { it.gameId == gameId || it.duplicateGroupId == gameId }
        override fun observeGameMembers(gameId: String): Flow<List<GameMemberProjection>> =
            MutableStateFlow(emptyList())
        override suspend fun hashSourceForGame(gameId: String): GameHashSourceProjection? =
            stored.values.firstOrNull { it.gameId == gameId }?.let {
                GameHashSourceProjection(
                    entryKey = it.entryKey,
                    sourceRootUri = it.sourceRootUri,
                    relativePath = it.relativePath,
                    platformId = it.platformId,
                    sourceDocumentUri = it.sourceDocumentUri,
                    managedPath = it.managedPath,
                    sizeBytes = it.sizeBytes,
                )
            }
        override suspend fun managedPathForGame(gameId: String): String? =
            stored.values.firstOrNull { it.gameId == gameId }?.managedPath
        override fun visibleGamesForPlatform(platformId: String): Flow<List<GameEntity>> =
            MutableStateFlow(stored.values.filter { it.platformId == platformId && it.isPrimaryMember })
        override suspend fun visibleGameCount(platformId: String): Int =
            stored.values.count { it.platformId == platformId && it.isPrimaryMember }
        override suspend fun exactHashDuplicateCandidates(): List<GameEntity> = stored.values
            .filter { it.isPrimaryMember && it.contentHash?.matches(Regex("raw-sha1:[0-9a-f]{40}")) == true }
            .groupBy { it.platformId to it.contentHash }
            .values.filter { rows -> rows.map { it.gameId }.distinct().size > 1 }
            .flatten()
        override suspend fun setContentHash(entryKey: String, hash: String?): Int {
            val row = stored[entryKey] ?: return 0
            stored[entryKey] = row.copy(contentHash = hash)
            return 1
        }
        override suspend fun storageUnavailableCount(): Int =
            stored.values.count { it.isPrimaryMember && it.availability == "STORAGE_UNAVAILABLE" }
        override suspend fun stampGroupWinner(winnerGameId: String, groupKind: String): Int {
            val hits = stored.values.filter { it.gameId == winnerGameId && it.isPrimaryMember }
            hits.forEach {
                stored[it.entryKey] = it.copy(duplicateGroupId = null, duplicateGroupKind = groupKind)
            }
            return hits.size
        }
        override suspend fun terminalGroupSeedsForSource(rootUri: String): List<TerminalGroupLink> {
            val terminalRows = stored.values.filter {
                it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID")
            }
            return stored.values
                .filter { row ->
                    row.sourceRootUri == rootUri &&
                        terminalRows.any { terminal ->
                            terminal.gameId == row.gameId ||
                                terminal.duplicateGroupId == row.gameId ||
                                (
                                    row.duplicateGroupId != null &&
                                        (
                                            terminal.gameId == row.duplicateGroupId ||
                                                terminal.duplicateGroupId == row.duplicateGroupId
                                            )
                                    )
                        }
                }
                .map { TerminalGroupLink(it.gameId, it.duplicateGroupId) }
        }
        override suspend fun terminalGroupLinksForIds(groupIds: List<String>): List<TerminalGroupLink> =
            stored.values
                .filter {
                    it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID") &&
                        (it.gameId in groupIds || it.duplicateGroupId in groupIds)
                }
                .map { TerminalGroupLink(it.gameId, it.duplicateGroupId) }
        override suspend fun dissolveExactGroups(groupIds: List<String>): Int {
            val hits = stored.values.filter {
                it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID") &&
                    (it.gameId in groupIds || it.duplicateGroupId in groupIds)
            }
            hits.forEach {
                stored[it.entryKey] = it.copy(
                    isPrimaryMember = true,
                    duplicateGroupId = null,
                    duplicateGroupKind = null,
                )
            }
            mutationCheckpoint("games.dissolve-exact")
            return hits.size
        }
        override suspend fun exactLogicalGameIds(groupIds: List<String>): List<String> = stored.values
            .filter {
                it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID") &&
                    (it.gameId in groupIds || it.duplicateGroupId in groupIds)
            }
            .map { it.gameId }
            .distinct()
        override suspend fun restoreSubordinateGroupKinds(logicalGameIds: List<String>): Int {
            var restored = 0
            logicalGameIds.forEach { gameId ->
                val subordinateKind = stored.values.firstOrNull {
                    it.duplicateGroupId == gameId && it.duplicateGroupKind != null &&
                        it.duplicateGroupKind !in setOf("EXACT_HASH", "CATALOG_TITLE_ID")
                }?.duplicateGroupKind ?: return@forEach
                stored.values.filter { it.gameId == gameId && it.isPrimaryMember }.forEach {
                    stored[it.entryKey] = it.copy(duplicateGroupId = gameId, duplicateGroupKind = subordinateKind)
                    restored++
                }
            }
            mutationCheckpoint("games.restore-subordinate-groups")
            return restored
        }
        override fun favoriteCandidateGameRows(): Flow<List<GameRowProjection>> = flowOf(emptyList())
        override fun favoriteGroupIds(): Flow<List<String>> = flowOf(emptyList())

        override fun visibleGameRowsForPlatform(platformId: String): Flow<List<GameRowProjection>> =
            MutableStateFlow(emptyList())
        override fun observeGameRow(gameId: String): Flow<GameRowProjection?> = MutableStateFlow(null)
        override fun visibleAvailableGameRowsByIds(gameIds: List<String>): Flow<List<GameRowProjection>> =
            MutableStateFlow(emptyList())
        override fun recentlyPlayedGameRows(limit: Int): Flow<List<GameRowProjection>> =
            MutableStateFlow(emptyList())
        override fun hiddenGameRows(): Flow<List<GameRowProjection>> = MutableStateFlow(emptyList())
        override fun favoriteGameRows(): Flow<List<GameRowProjection>> = MutableStateFlow(emptyList())
        override fun observeVisibleGameCount(): Flow<Int> =
            MutableStateFlow(stored.values.count { it.isPrimaryMember })
        override suspend fun platformsWithVisibleGames(): List<String> =
            stored.values.filter { it.isPrimaryMember }.map { it.platformId }.distinct()
        override fun observePlatformCounts(): Flow<List<PlatformCountProjection>> =
            MutableStateFlow(emptyList())
        override suspend fun visibleGameCountForSource(rootUri: String): Int = 0
        override fun observeStorageUnavailableSourceRoots(): Flow<List<String>> = flowOf(
            stored.values.filter { it.availability == "STORAGE_UNAVAILABLE" }
                .mapNotNull { it.sourceRootUri }
                .distinct(),
        )
        override suspend fun allPrimaryGames(): List<GameEntity> = stored.values.filter { it.isPrimaryMember }
        override suspend fun primaryGamesForEnrichment() = stored.values.filter { it.isPrimaryMember }
        override suspend fun primaryGamesWithoutScreenScraperMetadata(limit: Int): List<GameEntity> =
            stored.values.filter { it.isPrimaryMember }.take(limit)
        override suspend fun gameByManagedPath(path: String): GameEntity? =
            stored.values.firstOrNull { it.managedPath == path }
        override suspend fun memberCount(gameId: String): Int =
            stored.values.count { it.gameId == gameId }
        override suspend fun deleteByEntryKey(entryKey: String) {
            stored.remove(entryKey)
            mutationCheckpoint("games.delete")
        }
        override suspend fun entryKeysForSource(rootUri: String): List<String> {
            entryKeySourceQueries += rootUri
            return stored.values
                .filter { row -> row.sourceRootUri == rootUri }
                .map(GameEntity::entryKey)
                .sorted()
        }
        override suspend fun deleteByEntryKeys(entryKeys: List<String>): Int {
            var deleted = 0
            entryKeys.forEach { entryKey ->
                if (stored.remove(entryKey) != null) deleted++
            }
            mutationCheckpoint("games.delete-batch")
            return deleted
        }
        override suspend fun allEntryKeys(): List<String> {
            allEntryKeyQueryCount++
            return stored.keys.toList()
        }
        override suspend fun smallestHashablePrimary(): GameEntity? =
            stored.values.filter { it.isPrimaryMember && it.sourceDocumentUri != null }.minByOrNull { it.sizeBytes }
        override suspend fun smallestHashablePrimaryIn(platforms: List<String>): GameEntity? =
            stored.values.filter { it.isPrimaryMember && it.sourceDocumentUri != null && it.platformId in platforms }
                .minByOrNull { it.sizeBytes }
        override suspend fun distinctHashablePlatforms(): List<String> =
            stored.values.filter { it.isPrimaryMember && it.sourceDocumentUri != null }.map { it.platformId }.distinct()
        override suspend fun hashableMembers(platform: String): List<GameEntity> =
            stored.values.filter { it.sourceDocumentUri != null && it.platformId == platform }.sortedBy { it.sizeBytes }
        override suspend fun smallestHashableMember(platform: String): GameEntity? =
            stored.values.filter { it.sourceDocumentUri != null && it.platformId == platform && it.sizeBytes > 4096 }
                .minByOrNull { it.sizeBytes }
        override suspend fun unresolvedPrimaries(limit: Int): List<GameEntity> =
            stored.values.filter { it.isPrimaryMember && it.identityResolvedAtMillis == null }
                .sortedBy { it.sizeBytes }.take(limit)
        override suspend fun unresolvedPrimariesAfter(
            afterSizeBytes: Long,
            afterEntryKey: String,
            limit: Int,
        ): List<GameEntity> =
            stored.values.filter {
                it.isPrimaryMember && it.identityResolvedAtMillis == null &&
                    (it.sizeBytes > afterSizeBytes ||
                        (it.sizeBytes == afterSizeBytes && it.entryKey > afterEntryKey))
            }.sortedWith(compareBy(GameEntity::sizeBytes, GameEntity::entryKey)).take(limit)
        override suspend fun membersOfGameBySize(gameId: String): List<GameEntity> =
            stored.values.filter { it.gameId == gameId || it.duplicateGroupId == gameId }.sortedBy { it.sizeBytes }
        override suspend fun setIdentity(gameId: String, title: String?, collapse: String?, origin: String?, ts: Long) {
            stored.values.filter { it.gameId == gameId }.forEach {
                stored[it.entryKey] = it.copy(canonicalTitle = title, collapseKey = collapse, identityOrigin = origin, identityResolvedAtMillis = ts)
            }
        }
        override suspend fun availableChdShapedResidents(limit: Int): List<GameEntity> =
            stored.values.filter {
                it.availability == "AVAILABLE" && (
                    it.relativePath.lowercase().endsWith(".chd") ||
                        it.displayName.lowercase().endsWith(".chd") ||
                        it.sourceDocumentUri.orEmpty().lowercase().endsWith(".chd")
                    )
            }.sortedBy(GameEntity::entryKey).take(limit)
        override suspend fun availableArcadeResidentsForSource(
            rootUri: String,
            limit: Int,
        ): List<GameEntity> =
            stored.values.filter {
                it.sourceRootUri == rootUri &&
                    it.availability == "AVAILABLE" &&
                    it.platformId == "arcade"
            }.sortedWith(compareBy(GameEntity::relativePath, GameEntity::entryKey)).take(limit)
        override suspend fun reopenTerminalChdAttempts(): Int {
            val chdGameIds = stored.values.filter {
                it.relativePath.lowercase().endsWith(".chd") ||
                    it.displayName.lowercase().endsWith(".chd") ||
                    it.sourceDocumentUri.orEmpty().lowercase().endsWith(".chd")
            }.map(GameEntity::gameId).toSet()
            val hits = stored.values.filter {
                it.identityResolvedAtMillis != null && it.canonicalTitle == null && it.gameId in chdGameIds
            }
            hits.forEach { stored[it.entryKey] = it.copy(identityResolvedAtMillis = null) }
            return hits.size
        }
        override suspend fun reopenTerminalZipAttempts(): Int {
            val zipGameIds = stored.values.filter {
                it.relativePath.lowercase().endsWith(".zip") ||
                    it.displayName.lowercase().endsWith(".zip") ||
                    it.sourceDocumentUri.orEmpty().lowercase().endsWith(".zip")
            }.map(GameEntity::gameId).toSet()
            val hits = stored.values.filter {
                it.identityResolvedAtMillis != null && it.canonicalTitle == null &&
                    it.gameId in zipGameIds
            }
            hits.forEach { stored[it.entryKey] = it.copy(identityResolvedAtMillis = null) }
            return hits.size
        }
        override suspend fun clearUnmatchedIdentity(): Int {
            val hits = stored.values.filter { it.canonicalTitle == null }
            hits.forEach { stored[it.entryKey] = it.copy(identityResolvedAtMillis = null) }
            return hits.size
        }
        override fun unresolvedPrimaryCount(): Flow<Int> =
            flowOf(stored.values.count { it.isPrimaryMember && it.identityResolvedAtMillis == null })
        override suspend fun unresolvedCountAmong(gameIds: List<String>): Int =
            stored.values.count { it.gameId in gameIds && it.identityResolvedAtMillis == null }
        override suspend fun unverifiedResolvedPrimaries(
            attemptedPrefix: String,
            unreadablePrefix: String,
            unreadableSince: Long,
            limit: Int,
        ) = emptyList<GameEntity>()

        override suspend fun unverifiedResolvedPrimaryCount(attemptedPrefix: String): Int = 0

        override suspend fun stampMaintenance(key: String, completedAtMillis: Long) = Unit

        override suspend fun refreshMaintenance(key: String, completedAtMillis: Long) = Unit

        override suspend fun clearAllIdentity(): Int {
            val all = stored.values.toList()
            all.forEach {
                stored[it.entryKey] = it.copy(canonicalTitle = null, collapseKey = null, identityOrigin = null, identityResolvedAtMillis = null)
            }
            return all.size
        }
        override suspend fun clearLegacyFilenameKeyedIdentity(): Int = 0
        override suspend fun resolvedCollapsePrimaries(): List<GameEntity> = stored.values.filter {
            it.isPrimaryMember && it.collapseKey != null && it.duplicateGroupKind != "EXACT_HASH"
        }
        override suspend fun foldIntoGroup(loserGameId: String, winnerGameId: String, groupKind: String): Int {
            val hits = stored.values.filter { it.gameId == loserGameId && it.isPrimaryMember }
            hits.forEach {
                stored[it.entryKey] = it.copy(
                    isPrimaryMember = false,
                    duplicateGroupId = winnerGameId,
                    duplicateGroupKind = groupKind,
                )
            }
            return hits.size
        }
    }

    private class FakePlatformDao(
        private val mutationCheckpoint: (String) -> Unit = {},
    ) : PlatformDao {
        val stored = LinkedHashMap<String, PlatformEntity>()
        override suspend fun upsertAll(platforms: List<PlatformEntity>) {
            platforms.forEach { stored[it.platformId] = it }
            mutationCheckpoint("platforms.upsert")
        }
        override fun shelfPlatforms(): Flow<List<PlatformEntity>> =
            MutableStateFlow(stored.values.filter { it.gamesCount > 0 })
        override fun observeAll(): Flow<List<PlatformEntity>> =
            MutableStateFlow(stored.values.sortedBy { it.displayName })
        override suspend fun byId(platformId: String): PlatformEntity? = stored[platformId]
        // LG01: the BIOS seeder writes requiresBios here, which is what finally makes
        // LaunchReadiness.NeedsBios reachable.
        override suspend fun setRequiresBios(platformId: String, requiresBios: Boolean): Int {
            val existing = stored[platformId] ?: return 0
            stored[platformId] = existing.copy(requiresBios = requiresBios)
            return 1
        }
    }

    private class FakeSourceDao(
        private val mutationCheckpoint: (String) -> Unit = {},
    ) : SourceRootDao {
        val stored = LinkedHashMap<String, SourceRootEntity>()
        override suspend fun upsert(root: SourceRootEntity) {
            stored[root.rootId] = root
            mutationCheckpoint("sources.upsert")
        }
        override fun all(): Flow<List<SourceRootEntity>> = MutableStateFlow(stored.values.toList())
        override suspend fun allOnce(): List<SourceRootEntity> = stored.values.toList()
        override suspend fun remove(rootId: String) {
            stored.remove(rootId)
            mutationCheckpoint("sources.remove")
        }
    }

    private class FakeHiddenDao : HiddenItemDao {
        val stored = LinkedHashMap<String, HiddenItemEntity>()
        override suspend fun upsert(item: HiddenItemEntity) { stored[item.gameId] = item }
        override suspend fun byGame(gameId: String): HiddenItemEntity? = stored[gameId]
        override fun restorable(): Flow<List<HiddenItemEntity>> = MutableStateFlow(stored.values.toList())
        override suspend fun clear(gameId: String) { stored.remove(gameId) }
    }

    private class FakeFavoriteDao : FavoriteDao {
        val stored = LinkedHashSet<String>()
        override suspend fun set(favorite: FavoriteEntity) { stored.add(favorite.gameId) }
        override suspend fun promoteGroupIfAbsent(gameId: String, favoritedAtMillis: Long): Long =
            if (stored.add(gameId)) 1L else 0L
        override suspend fun clear(gameId: String) { stored.remove(gameId) }
        override suspend fun isFavorite(gameId: String): Boolean = gameId in stored
    }
}
