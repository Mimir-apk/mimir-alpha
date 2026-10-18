package com.gruning.mimir.rewrite.core.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryIdentityPublicationDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun staleResidentEvidenceCannotLandAfterSameKeyRoomReplacement() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        try {
            val oldBytes = "resident bytes A".toByteArray()
            val newBytes = "resident bytes B".toByteArray()
            check(oldBytes.size == newBytes.size)
            val gameDao = database.gameDao()
            gameDao.upsertAll(listOf(game("Original")))
            val currentBytes = AtomicReference(oldBytes)
            val firstReadStarted = CountDownLatch(1)
            val releaseFirstRead = CountDownLatch(1)
            val firstRead = AtomicBoolean(true)
            val mutations = SourceRootMutationCoordinator()
            val enricher = LibraryIdentityEnricher(
                dao = gameDao,
                datSource = BundledDatSource(
                    PackedDatAssetProvider { null },
                    manifest = emptyList(),
                ),
                idSource = BundledIdSource(
                    DatAssetProvider { null },
                    manifest = emptyList(),
                ),
                opener = ContentOpener { _, _ ->
                    val bytesForThisOpen = currentBytes.get()
                    if (firstRead.compareAndSet(true, false)) {
                        firstReadStarted.countDown()
                        check(releaseFirstRead.await(10, TimeUnit.SECONDS))
                    }
                    ByteArrayInputStream(bytesForThisOpen)
                },
                identityDao = database.gameIdentityDao(),
                nowMillis = { 1L },
                sourceIndexMutations = mutations,
            )

            val staleRun = async(Dispatchers.Default) {
                enricher.enrich(maxGames = 1, batch = 1)
            }
            assertTrue(firstReadStarted.await(10, TimeUnit.SECONDS))
            assertTrue(
                mutations.commitScanIfPresent("root") {
                    currentBytes.set(newBytes)
                    gameDao.upsertAll(listOf(game("Replacement")))
                },
            )
            releaseFirstRead.countDown()

            val staleReport = staleRun.await()
            assertEquals(1, staleReport.retryable)
            assertTrue(staleReport.restartFromBeginning)
            assertNull(staleReport.nextCursor)
            gameDao.byEntryKey("root::game.gb")!!.let { replacement ->
                assertEquals("Replacement", replacement.title)
                assertNull(replacement.contentHash)
                assertNull(replacement.canonicalTitle)
                assertNull(replacement.identityResolvedAtMillis)
            }
            assertTrue(
                database.gameIdentityDao().logicalGameIdsForPhysicalIds(listOf("game")).isEmpty(),
            )

            val freshReport = enricher.enrich(maxGames = 1, batch = 1)
            assertEquals(0, freshReport.retryable)
            gameDao.byEntryKey("root::game.gb")!!.let { resolved ->
                assertEquals(
                    "raw-sha1:${FileHasher.hash(ByteArrayInputStream(newBytes)).sha1}",
                    resolved.contentHash,
                )
                assertEquals(1L, resolved.identityResolvedAtMillis)
            }
        } finally {
            database.close()
        }
    }

    private fun game(title: String) = GameEntity(
        entryKey = "root::game.gb",
        gameId = "game",
        platformId = "gb",
        contentKind = "GAME",
        displayName = "game.gb",
        title = title,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 16L,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/games/game.gb",
        relativePath = "game.gb",
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )
}
