package com.gruning.mimir.rewrite.core.resolver.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameIdentityAvailabilityDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun unchangedCurrentIdPromotesItsAvailableCrossRootPhysicalRow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        try {
            database.gameDao().upsertAll(
                listOf(
                    game(
                        entryKey = "a-card",
                        gameId = "A",
                        root = "card",
                        availability = "AVAILABLE",
                        primary = true,
                        duplicateGroupId = null,
                    ),
                    game(
                        entryKey = "a-internal",
                        gameId = "A",
                        root = "internal",
                        availability = "STORAGE_UNAVAILABLE",
                        primary = false,
                        duplicateGroupId = "A",
                    ),
                    game(
                        entryKey = "b-alias",
                        gameId = "B",
                        root = "other",
                        availability = "STORAGE_UNAVAILABLE",
                        primary = false,
                        duplicateGroupId = "A",
                    ),
                ),
            )
            val identityDao = database.gameIdentityDao()
            identityDao.backfillExistingGroups(nowMillis = 1L)
            val logicalId = identityDao.logicalGameIdsForPhysicalIds(listOf("A")).single()

            database.openHelper.writableDatabase.execSQL(
                "UPDATE games SET availability = CASE entryKey " +
                    "WHEN '${physicalResidentKey("card", "a-card")}' THEN 'STORAGE_UNAVAILABLE' " +
                    "WHEN '${physicalResidentKey("internal", "a-internal")}' " +
                    "THEN 'AVAILABLE' ELSE availability END",
            )
            identityDao.reconcileCurrentWinners(listOf(logicalId), nowMillis = 2L)

            val aRows = database.gameDao().membersOfGame("A").filter { it.gameId == "A" }
            val available = aRows.single {
                it.entryKey == physicalResidentKey("internal", "a-internal")
            }
            val unavailable = aRows.single {
                it.entryKey == physicalResidentKey("card", "a-card")
            }
            assertTrue(available.isPrimaryMember)
            assertEquals("AVAILABLE", available.availability)
            assertFalse(unavailable.isPrimaryMember)
            assertEquals("STORAGE_UNAVAILABLE", unavailable.availability)
        } finally {
            database.close()
        }
    }

    @Test
    fun unchangedCurrentIdFoldsAResurrectedHistoricalPrimaryButKeepsItsNestedMembers() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        try {
            database.gameDao().upsertAll(
                listOf(
                    game(
                        entryKey = "a-current",
                        gameId = "A",
                        root = "current",
                        availability = "AVAILABLE",
                        primary = true,
                        duplicateGroupId = null,
                    ),
                    game(
                        entryKey = "b-former-primary",
                        gameId = "B",
                        root = "former",
                        availability = "AVAILABLE",
                        primary = false,
                        duplicateGroupId = "A",
                    ),
                    game(
                        entryKey = "b-disc-two",
                        gameId = "B",
                        root = "former",
                        availability = "AVAILABLE",
                        primary = false,
                        duplicateGroupId = "B",
                    ),
                ),
            )
            val identityDao = database.gameIdentityDao()
            identityDao.backfillExistingGroups(nowMillis = 1L)
            val logicalId = identityDao.logicalGameIdsForPhysicalIds(listOf("A", "B")).single()

            // Exact/catalog rescan dissolution can expose both physical ids as primaries temporarily.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE games SET isPrimaryMember = 1, duplicateGroupId = NULL " +
                    "WHERE entryKey = '${physicalResidentKey("former", "b-former-primary")}'",
            )
            identityDao.reconcileCurrentWinners(listOf(logicalId), nowMillis = 2L)

            val visibleIds = database.gameDao().allPrimaryGames().map { it.gameId }
            assertEquals(listOf("A"), visibleIds)
            val formerPrimary = database.gameDao().byEntryKey(
                physicalResidentKey("former", "b-former-primary"),
            )!!
            assertFalse(formerPrimary.isPrimaryMember)
            assertEquals("A", formerPrimary.duplicateGroupId)
            val nested = database.gameDao().membersOfGame("A").map { it.entryKey }.toSet()
            assertTrue(
                "nested alias member was lost",
                physicalResidentKey("former", "b-disc-two") in nested,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun managedRootDetachAndReturnUseExactEntryKeyOwnershipWithoutPrefixCollision() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java).build()
        try {
            val cardRoot = "/storage/ABCD-1234/ROMs"
            val neighboringRoot = "/storage/ABCD-1234/ROMs2"
            val cardKey = physicalResidentKey(cardRoot, "GBA/card-copy.gba")
            val neighboringKey = physicalResidentKey(neighboringRoot, "GBA/not-owned.gba")
            database.gameDao().upsertAll(
                listOf(
                    game(
                        entryKey = "$cardRoot::GBA/card-copy.gba",
                        gameId = "A",
                        root = cardRoot,
                        availability = "AVAILABLE",
                        primary = true,
                        duplicateGroupId = null,
                    ),
                    game(
                        entryKey = "/storage/emulated/0/ROMs::GBA/internal-copy.gba",
                        gameId = "B",
                        root = "/storage/emulated/0/ROMs",
                        availability = "AVAILABLE",
                        primary = false,
                        duplicateGroupId = "A",
                    ),
                    game(
                        entryKey = "$neighboringRoot::GBA/not-owned.gba",
                        gameId = "C",
                        root = neighboringRoot,
                        availability = "AVAILABLE",
                        primary = true,
                        duplicateGroupId = null,
                    ),
                ),
            )
            val identityDao = database.gameIdentityDao()
            identityDao.backfillExistingGroups(nowMillis = 1L)

            val detached = identityDao.reconcileRootAvailability(
                absentRootUris = listOf(cardRoot),
                presentRootUris = emptyList(),
                nowMillis = 2L,
            )
            assertEquals(1, detached.markedUnavailable)
            assertEquals(
                "STORAGE_UNAVAILABLE",
                database.gameDao().byEntryKey(cardKey)!!.availability,
            )
            assertEquals("AVAILABLE", database.gameDao().byEntryKey(neighboringKey)!!.availability)
            assertEquals("B", identityDao.resolveCurrentGameId("A"))

            val returned = identityDao.reconcileRootAvailability(
                absentRootUris = emptyList(),
                presentRootUris = listOf(cardRoot),
                nowMillis = 3L,
            )
            assertEquals(1, returned.restoredAvailable)
            assertEquals("AVAILABLE", database.gameDao().byEntryKey(cardKey)!!.availability)
            assertEquals("AVAILABLE", database.gameDao().byEntryKey(neighboringKey)!!.availability)
        } finally {
            database.close()
        }
    }

    private fun game(
        entryKey: String,
        gameId: String,
        root: String,
        availability: String,
        primary: Boolean,
        duplicateGroupId: String?,
    ): GameEntity {
        val prefix = "$root::"
        val relativePath = entryKey.removePrefix(prefix)
        return GameEntity(
        entryKey = physicalResidentKey(root, relativePath),
        gameId = gameId,
        platformId = "gba",
        contentKind = "ROM",
        displayName = "$entryKey.gba",
        title = "Identity Fixture",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "TEST",
        locationKind = "MANAGED_PATH",
        availability = availability,
        sizeBytes = 1_024L,
        contentHash = "raw-sha1:${"a".repeat(40)}",
        sourceRootUri = root,
        sourceDocumentUri = null,
        managedPath = "/$root/$entryKey.gba",
        relativePath = relativePath,
        discNumber = 0,
        duplicateGroupId = duplicateGroupId,
        isPrimaryMember = primary,
        duplicateGroupKind = "EXACT_HASH",
        )
    }
}
