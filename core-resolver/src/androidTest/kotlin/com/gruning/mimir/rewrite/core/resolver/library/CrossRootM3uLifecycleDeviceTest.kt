package com.gruning.mimir.rewrite.core.resolver.library

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrossRootM3uLifecycleDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun catalogFoldKeepsItsStrengthAcrossNestedM3uRescanRemovalAndReturn() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        val lister = MutableLifecycleLister()
        var now = 1L
        val repository = LibraryRepositoryImpl(
            gameDao = database.gameDao(),
            platformDao = database.platformDao(),
            sourceRootDao = database.sourceRootDao(),
            hiddenItemDao = database.hiddenItemDao(),
            favoriteDao = database.favoriteDao(),
            walker = lister,
            runScanTransaction = { block -> database.withTransaction { block() } },
            clock = { now++ },
            gameIdentityDao = database.gameIdentityDao(),
        )
        try {
            lister.setComplete(ROOT_A, listOf(gameFile(ROOT_A, "Biohazard 2.chd")))
            lister.setComplete(
                ROOT_B,
                listOf(
                    gameFile(ROOT_B, "Resident Evil 2.m3u"),
                    gameFile(ROOT_B, "Leon.chd"),
                    gameFile(ROOT_B, "Claire.chd"),
                ),
            )
            assertEquals(
                1,
                repository.addSource(ROOT_A, LibrarySourceRole.PRIMARY).groups,
            )
            assertEquals(
                1,
                repository.addSource(ROOT_B, LibrarySourceRole.ADDITIONAL).groups,
            )

            val a = requireNotNull(database.gameDao().byEntryKey(key(ROOT_A, "Biohazard 2.chd")))
            val playlist = requireNotNull(
                database.gameDao().byEntryKey(key(ROOT_B, "Resident Evil 2.m3u")),
            )
            val leon = requireNotNull(database.gameDao().byEntryKey(key(ROOT_B, "Leon.chd")))
            val claire = requireNotNull(database.gameDao().byEntryKey(key(ROOT_B, "Claire.chd")))
            assertTrue(playlist.isPrimaryMember)
            listOf(leon, claire).forEach { disc ->
                assertFalse(disc.isPrimaryMember)
                assertEquals(playlist.gameId, disc.duplicateGroupId)
                assertEquals("MULTI_DISC", disc.duplicateGroupKind)
            }

            database.gameDao().setIdentity(
                a.gameId,
                "Resident Evil 2",
                CATALOG_KEY,
                "TEST",
                now++,
            )
            database.gameDao().setIdentity(
                playlist.gameId,
                "Resident Evil 2",
                CATALOG_KEY,
                "TEST",
                now++,
            )
            repository.setFavorite(GameId(a.gameId), true)
            assertEquals(
                1,
                database.gameIdentityDao().foldLiveCatalogGroup(
                    candidateGameIds = listOf(a.gameId, playlist.gameId),
                    platformId = "ps1",
                    collapseKey = CATALOG_KEY,
                    baseTitle = "resident evil 2",
                    nowMillis = now++,
                ),
            )
            assertEquals("CATALOG_TITLE_ID", logicalGroupKind(database, a.gameId))
            assertTrue("CATALOG_TITLE_ID_FOLD" in journalReasons(database))

            // A healthy nested-root rescan must not weaken the already-proven cross-root catalog edge.
            repository.rescan(ROOT_B)
            assertEquals("CATALOG_TITLE_ID", logicalGroupKind(database, a.gameId))
            assertComposedWinner(database, a.gameId, playlist.gameId)
            val logicalId = database.gameIdentityDao()
                .logicalGameIdsForPhysicalIds(listOf(a.gameId, playlist.gameId))
                .single()
            assertEquals(
                "settled reconciliation must be a physical no-op",
                0,
                database.gameIdentityDao().reconcileCurrentWinners(listOf(logicalId), now++),
            )

            // Detaching the current M3U winner republishes A while retaining the unavailable nested graph.
            database.gameIdentityDao().reconcileRootAvailability(
                absentRootUris = listOf(ROOT_B),
                presentRootUris = emptyList(),
                nowMillis = now++,
            )
            assertEquals(a.gameId, database.gameIdentityDao().resolveCurrentGameId(playlist.gameId))
            assertTrue(database.favoriteDao().isFavorite(a.gameId))
            assertEquals("CATALOG_TITLE_ID", logicalGroupKind(database, a.gameId))
            val detachedMembers = database.gameDao().membersOfGame(a.gameId)
            assertEquals(4, detachedMembers.size)
            assertEquals(
                3,
                detachedMembers.count { it.availability == "STORAGE_UNAVAILABLE" },
            )
            assertTrue("WINNER_FLIPPED" in journalReasons(database))

            // Availability return is deliberately stable: it restores the rows but does not steal focus
            // from a still-available current winner until a real rescan republishes the nested group.
            database.gameIdentityDao().reconcileRootAvailability(
                absentRootUris = emptyList(),
                presentRootUris = listOf(ROOT_B),
                nowMillis = now++,
            )
            assertEquals(a.gameId, database.gameIdentityDao().resolveCurrentGameId(playlist.gameId))
            assertTrue(database.favoriteDao().isFavorite(a.gameId))

            // Confirmed removal prunes only B and republishes A as the surviving logical game.
            lister.setMissing(ROOT_B)
            val removal = repository.reconcileSources()
            assertEquals(1, removal.removedSources)
            assertEquals(a.gameId, database.gameIdentityDao().resolveCurrentGameId(playlist.gameId))
            assertTrue(database.favoriteDao().isFavorite(a.gameId))
            assertEquals("CATALOG_TITLE_ID", logicalGroupKind(database, a.gameId))
            assertEquals(
                setOf(key(ROOT_A, "Biohazard 2.chd")),
                database.gameDao().allEntryKeys().toSet(),
            )
            val singleton = requireNotNull(repository.observeGame(GameId(a.gameId)).first())
            assertEquals(1, singleton.memberCount)
            assertEquals(null, singleton.duplicateKind)

            // Historical aliases restore the nested group when B returns; no second catalog fetch is needed.
            lister.setComplete(
                ROOT_B,
                listOf(
                    gameFile(ROOT_B, "Resident Evil 2.m3u"),
                    gameFile(ROOT_B, "Leon.chd"),
                    gameFile(ROOT_B, "Claire.chd"),
                ),
            )
            repository.addSource(ROOT_B, LibrarySourceRole.ADDITIONAL)
            assertEquals("CATALOG_TITLE_ID", logicalGroupKind(database, a.gameId))
            assertComposedWinner(database, a.gameId, playlist.gameId)
            assertTrue(
                database.favoriteDao().isFavorite(
                    database.gameIdentityDao().resolveCurrentGameId(a.gameId),
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun exactLineageSurvivesNestedM3uRescanAndReturnsFromMemberlessState() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        val lister = MutableLifecycleLister()
        var now = 100L
        val repository = LibraryRepositoryImpl(
            gameDao = database.gameDao(),
            platformDao = database.platformDao(),
            sourceRootDao = database.sourceRootDao(),
            hiddenItemDao = database.hiddenItemDao(),
            favoriteDao = database.favoriteDao(),
            walker = lister,
            runScanTransaction = { block -> database.withTransaction { block() } },
            clock = { now++ },
            gameIdentityDao = database.gameIdentityDao(),
        )
        try {
            lister.setComplete(ROOT_A, listOf(gameFile(ROOT_A, "Twin A.chd")))
            lister.setComplete(ROOT_B, listOf(gameFile(ROOT_B, "Twin B.chd")))
            repository.addSource(ROOT_A, LibrarySourceRole.ADDITIONAL)
            repository.addSource(ROOT_B, LibrarySourceRole.PRIMARY)

            val a = requireNotNull(database.gameDao().byEntryKey(key(ROOT_A, "Twin A.chd")))
            val oldB = requireNotNull(database.gameDao().byEntryKey(key(ROOT_B, "Twin B.chd")))
            val exactHash = "raw-sha1:${"a".repeat(40)}"
            assertEquals(1, database.gameDao().setContentHash(a.entryKey, exactHash))
            assertEquals(1, database.gameDao().setContentHash(oldB.entryKey, exactHash))
            repository.setFavorite(GameId(a.gameId), true)
            assertEquals(1, database.gameIdentityDao().reconcileExactHashDuplicates(now++))
            assertEquals("EXACT_HASH", logicalGroupKind(database, a.gameId))
            assertTrue("EXACT_HASH_FOLD" in journalReasons(database))

            // The same physical B identity becomes an explicit multi-disc playlist on its next scan.
            lister.setComplete(
                ROOT_B,
                listOf(
                    gameFile(ROOT_B, "Twin B.m3u"),
                    gameFile(ROOT_B, "Side One.chd"),
                    gameFile(ROOT_B, "Side Two.chd"),
                ),
            )
            repository.rescan(ROOT_B)
            val playlist = requireNotNull(database.gameDao().byEntryKey(key(ROOT_B, "Twin B.m3u")))
            assertEquals("extension change must retain filename identity", oldB.gameId, playlist.gameId)
            assertEquals("EXACT_HASH", logicalGroupKind(database, a.gameId))
            assertComposedWinner(database, a.gameId, playlist.gameId, "EXACT_HASH")
            val discIds = listOf("Side One.chd", "Side Two.chd").map { name ->
                requireNotNull(database.gameDao().byEntryKey(key(ROOT_B, name))).gameId
            }
            (listOf(a.gameId, oldB.gameId) + discIds).forEach { physicalId ->
                assertEquals(
                    playlist.gameId,
                    database.gameIdentityDao().resolveCurrentGameId(physicalId),
                )
            }
            assertTrue(database.favoriteDao().isFavorite(playlist.gameId))

            // The primary root is reconciled first. Removing B then A leaves the lineage memberless
            // with A as its last current id; returning B must journal a true resurrection.
            lister.setMissing(ROOT_A)
            lister.setMissing(ROOT_B)
            val removal = repository.reconcileSources()
            assertEquals(2, removal.removedSources)
            assertTrue(database.gameDao().allEntryKeys().isEmpty())
            assertTrue(logicalGroupMemberless(database, a.gameId))
            assertTrue("WINNER_REMOVED" in journalReasons(database))

            lister.setComplete(
                ROOT_B,
                listOf(
                    gameFile(ROOT_B, "Twin B.m3u"),
                    gameFile(ROOT_B, "Side One.chd"),
                    gameFile(ROOT_B, "Side Two.chd"),
                ),
            )
            repository.addSource(ROOT_B, LibrarySourceRole.PRIMARY)
            assertEquals("EXACT_HASH", logicalGroupKind(database, playlist.gameId))
            assertComposedWinner(database, a.gameId, playlist.gameId, "EXACT_HASH")
            assertTrue(database.favoriteDao().isFavorite(playlist.gameId))
            assertEquals("IDENTITY_RESURRECTED", latestJournalReason(database))
        } finally {
            database.close()
        }
    }

    private suspend fun assertComposedWinner(
        database: MimirDatabase,
        firstGameId: String,
        playlistGameId: String,
        terminalKind: String = "CATALOG_TITLE_ID",
    ) {
        val current = database.gameIdentityDao().resolveCurrentGameId(firstGameId)
        assertEquals(playlistGameId, current)
        val visible = database.gameDao().allPrimaryGames()
        assertEquals(listOf(playlistGameId), visible.map { it.gameId }.distinct())
        assertEquals(terminalKind, visible.single().duplicateGroupKind)
        val members = database.gameDao().membersOfGame(playlistGameId)
        assertTrue(members.any { it.contentKind == "MULTI_DISC_PLAYLIST" })
        assertEquals(2, members.count { it.contentKind == "DISC_MEMBER" })
        members.filter { it.contentKind == "DISC_MEMBER" }.forEach { disc ->
            assertEquals("MULTI_DISC", disc.duplicateGroupKind)
        }
    }

    private fun logicalGroupKind(database: MimirDatabase, gameId: String): String =
        database.openHelper.readableDatabase.query(
            "SELECT lg.groupKind FROM logical_game_groups lg " +
                "JOIN game_id_aliases a ON a.logicalGameId = lg.logicalGameId " +
                "WHERE a.oldGameId = ?",
            arrayOf(gameId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun logicalGroupMemberless(database: MimirDatabase, gameId: String): Boolean =
        database.openHelper.readableDatabase.query(
            "SELECT lg.memberless FROM logical_game_groups lg " +
                "JOIN game_id_aliases a ON a.logicalGameId = lg.logicalGameId " +
                "WHERE a.oldGameId = ?",
            arrayOf(gameId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0) != 0
        }

    private fun latestJournalReason(database: MimirDatabase): String =
        database.openHelper.readableDatabase.query(
            "SELECT reason FROM remap_journal ORDER BY journalId DESC LIMIT 1",
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun journalReasons(database: MimirDatabase): List<String> =
        database.openHelper.readableDatabase.query(
            "SELECT reason FROM remap_journal ORDER BY journalId ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private class MutableLifecycleLister : SourceLister {
        private val states = mutableMapOf<String, SourceWalkResult>()

        fun setComplete(root: String, files: List<WalkedFile>) {
            states[root] = SourceWalkResult.Complete(files)
        }

        fun setMissing(root: String) {
            states[root] = SourceWalkResult.Missing("fixture root removed")
        }

        override suspend fun walk(rootUri: String): SourceWalkResult =
            states[rootUri] ?: SourceWalkResult.Missing("fixture root absent")

        override suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? =
            when (file.displayName) {
                "Resident Evil 2.m3u" -> "Leon.chd\nClaire.chd\n"
                "Twin B.m3u" -> "Side One.chd\nSide Two.chd\n"
                else -> null
            }?.take(maxBytes.toInt())
    }

    private companion object {
        const val ROOT_A = "content://ep10/root/A"
        const val ROOT_B = "content://ep10/root/B"
        const val CATALOG_KEY = "catalog:resident-evil-2"

        fun key(root: String, name: String) = "$root::PS1/$name"

        fun gameFile(root: String, name: String) = WalkedFile(
            displayName = name,
            relativePath = "PS1/$name",
            sizeBytes = if (name.endsWith(".m3u")) 64L else 32L * 1024L * 1024L,
            documentUri = "$root/document/$name",
            filePath = null,
        )
    }
}
