package com.gruning.mimir.rewrite.core.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryCollapseRegrouperDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun prunedSnapshotWinnerCannotHideTheOnlySurvivingCatalogVariant() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        try {
            val gameDao = database.gameDao()
            gameDao.upsertAll(
                listOf(
                    variant("a-saf", "A", "SAF_TREE_GRANT", "AVAILABLE"),
                    variant("b-managed", "B", "MANAGED_PATH", "AVAILABLE"),
                ),
            )
            val regrouper = LibraryCollapseRegrouper(
                dao = gameDao,
                identityDao = database.gameIdentityDao(),
                nowMillis = { 2L },
                afterSnapshot = {
                    // Forced scan interleaving: A existed in the enrichment snapshot, then its source
                    // was pruned before catalog fold acquired the Room transaction.
                    gameDao.deleteByEntryKey("a-saf")
                },
            )

            assertEquals(0, regrouper.regroup())
            assertEquals(listOf("B"), gameDao.allPrimaryGames().map { it.gameId })
            assertTrue(
                database.gameIdentityDao().logicalGameIdsForPhysicalIds(listOf("A", "B")).isEmpty(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun transactionalCatalogFoldPrefersAnAvailableVariantOverUnavailableSaf() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        try {
            val gameDao = database.gameDao()
            gameDao.upsertAll(
                listOf(
                    variant("a-saf", "A", "SAF_TREE_GRANT", "STORAGE_UNAVAILABLE"),
                    variant("b-managed", "B", "MANAGED_PATH", "AVAILABLE"),
                ),
            )

            val folded = LibraryCollapseRegrouper(
                dao = gameDao,
                identityDao = database.gameIdentityDao(),
                nowMillis = { 2L },
            ).regroup()

            assertEquals(1, folded)
            assertEquals("B", database.gameIdentityDao().resolveCurrentGameId("A"))
            assertFalse(gameDao.byEntryKey("a-saf")!!.isPrimaryMember)
            assertTrue(gameDao.byEntryKey("b-managed")!!.isPrimaryMember)
        } finally {
            database.close()
        }
    }

    private fun variant(
        entryKey: String,
        gameId: String,
        locationKind: String,
        availability: String,
    ) = GameEntity(
        entryKey = entryKey,
        gameId = gameId,
        platformId = "gba",
        contentKind = "GAME",
        displayName = "$gameId.gba",
        title = "Metroid Fusion ($gameId)",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "TEST",
        locationKind = locationKind,
        availability = availability,
        sizeBytes = 1_024L,
        contentHash = null,
        sourceRootUri = "root-$gameId",
        sourceDocumentUri = null,
        managedPath = "/root-$gameId/$gameId.gba",
        relativePath = "$gameId.gba",
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
        canonicalTitle = "Metroid Fusion ($gameId)",
        collapseKey = "gba:metroid-fusion",
        identityOrigin = "TEST",
        identityResolvedAtMillis = 1L,
    )
}
