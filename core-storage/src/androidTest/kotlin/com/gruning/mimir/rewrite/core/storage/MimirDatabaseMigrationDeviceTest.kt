package com.gruning.mimir.rewrite.core.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailureReason
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the committed Room schemas and the production migration objects on Android SQLite.
 *
 * The older JVM migration tests intentionally stay fast and surgical; this suite is the release gate that
 * proves the real exported schema ladder, Room validation, SQLite transaction rollback, fail-closed
 * downgrade policy, the production first-open v9 backfill, the v10 pass ledger, the v11
 * injective physical identity rebuild, and the v12 emulator-scoped BIOS presence rebuild.
 */
@RunWith(AndroidJUnit4::class)
class MimirDatabaseMigrationDeviceTest {
    // **Migration ladder raised 12 -> 14.** It validated to v12 while the
    // database already shipped v13, so it had stopped covering the newest migration before the ladder was updated.
    //
    // **Only the tests that walk the chain to "current newest" moved.** A first pass replaced every
    // `12` in this file and five tests failed on the Thor, correctly: the refusal and frozen-ceiling
    // tests encode a RELATIVE version -- "a build frozen at 11 meeting an actual v12", "found 13
    // against supported 12" -- and bumping those turns a meaningful scenario into a contradiction.
    // The frozen-ceiling tests keep their numbers. The three NEWER_SCHEMA refusal tests do NOT: they
    // fake "a database written by a future build" with `PRAGMA user_version`, so that fake future has
    // to stay one ahead of what this build supports. 13-against-12 became 15-against-14. Leaving them
    // would have meant asserting that a v13 database is refused by a build that migrates to v14 --
    // which is not a guarantee, it is a contradiction.

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseNames = linkedSetOf<String>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MimirDatabase::class.java,
    )

    @After
    fun deleteDatabases() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun everyExportedStartingVersionReachesValidatedV14() {
        (1..13).forEach { startVersion ->
            val name = databaseName("all-starts-$startVersion")
            helper.createDatabase(name, startVersion).close()

            helper.runMigrationsAndValidate(
                name,
                14,
                true,
                *MimirDatabaseFactory.ALL_MIGRATIONS,
            ).close()
        }
    }

    @Test
    fun populatedV1StateSurvivesTheCompleteMigrationLadder() {
        val name = databaseName("populated-v1")
        helper.createDatabase(name, 1).use { database ->
            seedV1Platform(database)
            seedGame(database, entryKey = "card::chrono.cue", gameId = "chrono", primary = true)
            insert(
                database,
                "source_roots",
                values(
                    "rootId" to "card",
                    "rootUri" to "content://card",
                    "locationKind" to "SAF_TREE_GRANT",
                    "friendlyLabel" to "CARD",
                    "isPrimarySource" to 1,
                    "lastSeenMillis" to 11L,
                ),
            )
            insert(
                database,
                "hidden_items",
                values(
                    "gameId" to "chrono",
                    "hidden" to 1,
                    "reason" to "OWNER",
                    "duplicateGroupId" to null,
                    "isPrimaryMember" to 1,
                    "hiddenAtMillis" to 12L,
                ),
            )
            insert(
                database,
                "emulator_overrides",
                values(
                    "gameId" to "chrono",
                    "emulatorId" to "duckstation",
                    "setAtMillis" to 13L,
                ),
            )
            insert(
                database,
                "launch_proofs",
                values(
                    "gameId" to "chrono",
                    "platformId" to "ps1",
                    "emulatorId" to "duckstation",
                    "locator" to "content://card/chrono.cue",
                    "outcome" to "DISPATCHED",
                    "timestampMillis" to 14L,
                ),
            )
            insert(
                database,
                "bios_profiles",
                values(
                    "platformId" to "ps1",
                    "fileName" to "scph5501.bin",
                    "required" to 1,
                    "presence" to "PRESENT",
                    "expectedHash" to "abc",
                    "expectedRelativePath" to "bios/scph5501.bin",
                ),
            )
            insert(
                database,
                "title_metadata_cache",
                values(
                    "catalogKey" to "ps1:chrono",
                    "platformId" to "ps1",
                    "canonicalTitle" to "Chrono Cross",
                    "region" to "USA",
                    "version" to "1.0",
                    "cachedAtMillis" to 15L,
                ),
            )
        }

        helper.runMigrationsAndValidate(
            name,
            14,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals("Chrono Cross", database.string("SELECT title FROM games WHERE gameId = 'chrono'"))
            assertEquals(
                "content://card",
                database.string("SELECT rootUri FROM source_roots WHERE rootId = 'card'"),
            )
            assertEquals(1L, database.long("SELECT hidden FROM hidden_items WHERE gameId = 'chrono'"))
            assertEquals(
                "duckstation",
                database.string("SELECT emulatorId FROM emulator_overrides WHERE gameId = 'chrono'"),
            )
            assertEquals(
                "content://card/chrono.cue",
                database.string("SELECT locator FROM launch_proofs WHERE gameId = 'chrono'"),
            )
            assertEquals(
                "scph5501.bin",
                database.string("SELECT fileName FROM bios_profiles WHERE platformId = 'ps1'"),
            )
            assertEquals(
                "UNKNOWN",
                database.string("SELECT presence FROM bios_profiles WHERE platformId = 'ps1'"),
            )
            assertEquals(
                "",
                database.string("SELECT emulatorScopeKey FROM bios_profiles WHERE platformId = 'ps1'"),
            )
            assertTrue(database.isNull("SELECT packageName FROM bios_profiles WHERE platformId = 'ps1'"))
            assertEquals(
                "abc",
                database.string("SELECT expectedHash FROM bios_profiles WHERE platformId = 'ps1'"),
            )
            assertEquals(
                "bios/scph5501.bin",
                database.string("SELECT expectedRelativePath FROM bios_profiles WHERE platformId = 'ps1'"),
            )
            assertEquals(
                "Chrono Cross",
                database.string("SELECT canonicalTitle FROM title_metadata_cache WHERE catalogKey = 'ps1:chrono'"),
            )
            assertTrue(database.isNull("SELECT sourceDocumentUri FROM games WHERE gameId = 'chrono'"))
            assertTrue(database.isNull("SELECT description FROM title_metadata_cache WHERE catalogKey = 'ps1:chrono'"))
        }
    }

    @Test
    fun populatedV2V3V4FieldsSurviveFromTheirNativeSchemas() {
        val v2Name = databaseName("populated-v2")
        helper.createDatabase(v2Name, 2).use { database ->
            insert(
                database,
                "title_metadata_cache",
                values(
                    "catalogKey" to "ps1:v2",
                    "platformId" to "ps1",
                    "canonicalTitle" to "V2 Title",
                    "region" to "USA",
                    "version" to "2.0",
                    "cachedAtMillis" to 21L,
                    "canonicalSerial" to "SLUS-00002",
                    "description" to "V2 description",
                    "boxArtUri" to "file:///v2-box.png",
                    "screenshotUris" to "[\"file:///v2-shot.png\"]",
                    "source" to "OFFLINE_FIXTURE",
                    "license" to "fixture",
                    "lastValidatedMillis" to 22L,
                    "cacheExpiresAt" to 23L,
                ),
            )
        }
        helper.runMigrationsAndValidate(
            v2Name,
            14,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(
                "V2 description",
                database.string("SELECT description FROM title_metadata_cache WHERE catalogKey = 'ps1:v2'"),
            )
            assertEquals(
                "file:///v2-box.png",
                database.string("SELECT boxArtUri FROM title_metadata_cache WHERE catalogKey = 'ps1:v2'"),
            )
            assertEquals(
                23L,
                database.long("SELECT cacheExpiresAt FROM title_metadata_cache WHERE catalogKey = 'ps1:v2'"),
            )
        }

        val v3Name = databaseName("populated-v3")
        helper.createDatabase(v3Name, 3).use { database ->
            seedV1Platform(database)
            seedGame(database, entryKey = "v3::source.cue", gameId = "v3-game", primary = true)
            database.execSQL(
                "UPDATE games SET sourceDocumentUri = 'content://card/v3-source.cue' " +
                    "WHERE gameId = 'v3-game'",
            )
        }
        helper.runMigrationsAndValidate(
            v3Name,
            14,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(
                "content://card/v3-source.cue",
                database.string("SELECT sourceDocumentUri FROM games WHERE gameId = 'v3-game'"),
            )
        }

        val v4Name = databaseName("populated-v4")
        helper.createDatabase(v4Name, 4).use { database ->
            seedV1Platform(database)
            seedGame(database, entryKey = "v4::identity.cue", gameId = "v4-game", primary = true)
            database.execSQL(
                "UPDATE games SET canonicalTitle = 'V4 Canonical', collapseKey = 'ps1:v4', " +
                    "identityOrigin = 'OFFLINE_DAT', identityResolvedAtMillis = 44 " +
                    "WHERE gameId = 'v4-game'",
            )
        }
        helper.runMigrationsAndValidate(
            v4Name,
            14,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(
                "V4 Canonical",
                database.string("SELECT canonicalTitle FROM games WHERE gameId = 'v4-game'"),
            )
            assertEquals(
                "ps1:v4",
                database.string("SELECT collapseKey FROM games WHERE gameId = 'v4-game'"),
            )
            assertEquals(
                "OFFLINE_DAT",
                database.string("SELECT identityOrigin FROM games WHERE gameId = 'v4-game'"),
            )
            assertEquals(
                44L,
                database.long("SELECT identityResolvedAtMillis FROM games WHERE gameId = 'v4-game'"),
            )
        }
    }

    @Test
    fun versionSpecificUserStateSurvivesToV14() {
        val v5Name = databaseName("populated-v5")
        helper.createDatabase(v5Name, 5).use { database ->
            insert(
                database,
                "favorites",
                values("gameId" to "favorite-v5", "favoritedAtMillis" to 51L),
            )
        }
        helper.runMigrationsAndValidate(
            v5Name,
            14,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(51L, database.long("SELECT favoritedAtMillis FROM favorites WHERE gameId = 'favorite-v5'"))
        }

        val v6Name = databaseName("populated-v6")
        helper.createDatabase(v6Name, 6).use { database ->
            insert(
                database,
                "home_shelf_items",
                values(
                    "profileId" to "local",
                    "itemKind" to "EMULATED_GAME",
                    "itemId" to "home-v6",
                    "rank" to 1_024L,
                    "addedAtMillis" to 61L,
                ),
            )
            insert(
                database,
                "launch_proofs",
                values(
                    "gameId" to "home-v6",
                    "platformId" to "ps1",
                    "emulatorId" to "duckstation",
                    "locator" to "/roms/home-v6.cue",
                    "outcome" to "DISPATCHED",
                    "timestampMillis" to 62L,
                ),
            )
        }
        helper.runMigrationsAndValidate(
            v6Name,
            14,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(1_024L, database.long("SELECT rank FROM home_shelf_items WHERE itemId = 'home-v6'"))
            assertEquals("home-v6", database.string("SELECT gameId FROM launch_proofs WHERE locator = '/roms/home-v6.cue'"))
            assertTrue(database.isNull("SELECT androidPackageName FROM launch_proofs WHERE locator = '/roms/home-v6.cue'"))
        }

        val v7Name = databaseName("populated-v7")
        helper.createDatabase(v7Name, 7).use { database ->
            insert(
                database,
                "launch_proofs",
                values(
                    "gameId" to null,
                    "androidPackageName" to "com.example.game",
                    "platformId" to "android",
                    "emulatorId" to "android-package",
                    "locator" to "package:com.example.game",
                    "outcome" to "DISPATCHED",
                    "timestampMillis" to 71L,
                ),
            )
        }
        helper.runMigrationsAndValidate(
            v7Name,
            14,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(
                "com.example.game",
                database.string("SELECT androidPackageName FROM launch_proofs WHERE platformId = 'android'"),
            )
            assertTrue(database.isNull("SELECT gameId FROM launch_proofs WHERE platformId = 'android'"))
        }
    }

    @Test
    fun productionFirstOpenMigratesV8AndBackfillsIdentityExactlyOnce(): Unit = runBlocking {
        val name = databaseName("production-first-open")
        helper.createDatabase(name, 8).use { database ->
            seedV1Platform(database)
            seedGame(database, entryKey = "root-a::disc1.cue", gameId = "A", primary = true)
            seedGame(
                database,
                entryKey = "root-b::disc2.bin",
                gameId = "B",
                primary = false,
                duplicateGroupId = "A",
                discNumber = 2,
            )
            insert(
                database,
                "favorites",
                values("gameId" to "A", "favoritedAtMillis" to 81L),
            )
        }

        var journalCount = 0L
        var database = MimirDatabaseFactory.openPreparedDatabase(context, name)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(1L, sqlite.long("SELECT COUNT(*) FROM logical_game_groups"))
            assertEquals(2L, sqlite.long("SELECT COUNT(*) FROM game_id_aliases"))
            assertEquals(1L, sqlite.long("SELECT COUNT(*) FROM identity_maintenance"))
            assertEquals(1L, sqlite.long("SELECT COUNT(*) FROM favorites"))
            assertEquals(
                1L,
                sqlite.long(
                    "SELECT COUNT(*) FROM favorites f " +
                        "JOIN logical_game_groups g ON g.currentGameId = f.gameId",
                ),
            )
            assertEquals(2L, sqlite.long("SELECT COUNT(*) FROM games"))
            journalCount = sqlite.long("SELECT COUNT(*) FROM remap_journal")
            assertTrue("Backfill rekey did not publish replay work", journalCount >= 1L)
        } finally {
            database.close()
        }

        database = MimirDatabaseFactory.openPreparedDatabase(context, name)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(1L, sqlite.long("SELECT COUNT(*) FROM logical_game_groups"))
            assertEquals(2L, sqlite.long("SELECT COUNT(*) FROM game_id_aliases"))
            assertEquals(1L, sqlite.long("SELECT COUNT(*) FROM identity_maintenance"))
            assertEquals(journalCount, sqlite.long("SELECT COUNT(*) FROM remap_journal"))
        } finally {
            database.close()
        }
    }

    @Test
    fun identityBackfillRollsBackCompletelyThenResumesIdempotently(): Unit = runBlocking {
        val name = databaseName("backfill-rollback")
        helper.createDatabase(name, 8).use { database ->
            seedV1Platform(database)
            seedGame(database, entryKey = "root-a::a-disc1.cue", gameId = "A", primary = true)
            seedGame(
                database,
                entryKey = "root-a::a-disc2.bin",
                gameId = "B",
                primary = false,
                duplicateGroupId = "A",
                discNumber = 2,
            )
            seedGame(database, entryKey = "root-c::c-disc1.cue", gameId = "C", primary = true)
            seedGame(
                database,
                entryKey = "root-c::c-disc2.bin",
                gameId = "D",
                primary = false,
                duplicateGroupId = "C",
                discNumber = 2,
            )
        }
        helper.runMigrationsAndValidate(
            name,
            12,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).close()

        val database = MimirDatabaseFactory.databaseBuilder(context, name).build()
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "CREATE TRIGGER ep05_abort_second_group " +
                    "BEFORE INSERT ON logical_game_groups " +
                    "WHEN (SELECT COUNT(*) FROM logical_game_groups) >= 1 " +
                    "BEGIN SELECT RAISE(ABORT, 'EP-05 injected backfill interruption'); END",
            )

            val failure = runCatching {
                database.gameIdentityDao().backfillExistingGroups(nowMillis = 111L)
            }.exceptionOrNull()
            assertTrue("Injected backfill interruption did not fail", failure != null)
            assertEquals(0L, sqlite.long("SELECT COUNT(*) FROM logical_game_groups"))
            assertEquals(0L, sqlite.long("SELECT COUNT(*) FROM game_id_aliases"))
            assertEquals(0L, sqlite.long("SELECT COUNT(*) FROM remap_journal"))
            assertEquals(0L, sqlite.long("SELECT COUNT(*) FROM identity_maintenance"))
            assertEquals(4L, sqlite.long("SELECT COUNT(*) FROM games"))

            sqlite.execSQL("DROP TRIGGER ep05_abort_second_group")
            assertEquals(2, database.gameIdentityDao().backfillExistingGroups(nowMillis = 112L))
            assertEquals(2L, sqlite.long("SELECT COUNT(*) FROM logical_game_groups"))
            assertEquals(4L, sqlite.long("SELECT COUNT(*) FROM game_id_aliases"))
            assertEquals(1L, sqlite.long("SELECT COUNT(*) FROM identity_maintenance"))

            assertEquals(0, database.gameIdentityDao().backfillExistingGroups(nowMillis = 113L))
            assertEquals(2L, sqlite.long("SELECT COUNT(*) FROM logical_game_groups"))
            assertEquals(4L, sqlite.long("SELECT COUNT(*) FROM game_id_aliases"))
            assertEquals(1L, sqlite.long("SELECT COUNT(*) FROM identity_maintenance"))
        } finally {
            database.close()
        }
    }

    @Test
    fun interruptedRoomMigrationRollsBackAndCanResume() {
        val name = databaseName("rollback-v6")
        helper.createDatabase(name, 6).use { database ->
            insert(
                database,
                "launch_proofs",
                values(
                    "gameId" to "rollback-game",
                    "platformId" to "ps1",
                    "emulatorId" to "duckstation",
                    "locator" to "/roms/rollback.cue",
                    "outcome" to "DISPATCHED",
                    "timestampMillis" to 91L,
                ),
            )
        }

        val injectedFailure = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(CREATE_LAUNCH_PROOFS_V7_SQL)
                database.execSQL(
                    "INSERT INTO launch_proofs_v7 " +
                        "(id, gameId, androidPackageName, platformId, emulatorId, locator, outcome, timestampMillis) " +
                        "SELECT id, gameId, NULL, platformId, emulatorId, locator, outcome, timestampMillis " +
                        "FROM launch_proofs",
                )
                error("EP-05 injected interruption")
            }
        }

        assertThrows(Exception::class.java) {
            helper.runMigrationsAndValidate(name, 7, true, injectedFailure).close()
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(6L, database.long("PRAGMA user_version"))
            assertEquals(1L, database.long("SELECT COUNT(*) FROM launch_proofs WHERE gameId = 'rollback-game'"))
            assertEquals(
                0L,
                database.long(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'launch_proofs_v7'",
                ),
            )
        }

        helper.runMigrationsAndValidate(
            name,
            12,
            true,
            *MimirDatabaseFactory.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(1L, database.long("SELECT COUNT(*) FROM launch_proofs WHERE gameId = 'rollback-game'"))
            assertTrue(database.isNull("SELECT androidPackageName FROM launch_proofs WHERE gameId = 'rollback-game'"))
        }
    }

    @Test
    fun populatedV10IdentityRebuildPreservesRowsLedgerAndAdmitsFormerCollisionTwin() {
        val name = databaseName("populated-v10-identity")
        val safRoot = "content://provider/tree/ROM::\u03a9"
        val safRelative = "PS1/Disc::e\u0301\uD83D\uDE00.chd"
        val safLegacyKey = "$safRoot::$safRelative"
        val managedRoot = "/storage/card/ROMs:"
        val managedRelative = ":legacy.cue"
        val managedLegacyKey = "$managedRoot::$managedRelative"
        helper.createDatabase(name, 10).use { database ->
            insert(
                database,
                "games",
                values(
                    "entryKey" to safLegacyKey,
                    "gameId" to "saf-game",
                    "platformId" to "ps1",
                    "contentKind" to "DISC_MEMBER",
                    "displayName" to "Disc \u03a9",
                    "title" to "Preserved Title",
                    "region" to "USA",
                    "version" to "1.1",
                    "serial" to "SLUS-EP14",
                    "titleCatalogKey" to "ps1:ep14",
                    "identitySource" to "CATALOG",
                    "locationKind" to "SAF_TREE_GRANT",
                    "availability" to "STORAGE_UNAVAILABLE",
                    "sizeBytes" to 9_876_543L,
                    "contentHash" to "raw-sha1:" + "a".repeat(40),
                    "sourceRootUri" to safRoot,
                    "sourceDocumentUri" to "content://provider/document/ep14",
                    "managedPath" to null,
                    "relativePath" to safRelative,
                    "discNumber" to 2,
                    "duplicateGroupId" to "logical-ep14",
                    "isPrimaryMember" to 0,
                    "duplicateGroupKind" to "MULTI_DISC",
                    "canonicalTitle" to "Canonical EP14",
                    "collapseKey" to "redump:ep14",
                    "identityOrigin" to "REDUMP",
                    "identityResolvedAtMillis" to 1414L,
                ),
            )
            insert(
                database,
                "games",
                values(
                    "entryKey" to managedLegacyKey,
                    "gameId" to "managed-game",
                    "platformId" to "ps1",
                    "contentKind" to "TRACK_MANIFEST",
                    "displayName" to "legacy.cue",
                    "title" to "Legacy Managed",
                    "identitySource" to "FILENAME",
                    "locationKind" to "MANAGED_PATH",
                    "availability" to "AVAILABLE",
                    "sizeBytes" to 22L,
                    "sourceRootUri" to null,
                    "managedPath" to "$managedRoot/$managedRelative",
                    "relativePath" to managedRelative,
                    "discNumber" to 1,
                    "isPrimaryMember" to 1,
                ),
            )
            seedGame(database, entryKey = "r:::game", gameId = "collision-a", primary = true)
            insert(
                database,
                "favorites",
                values("gameId" to "saf-game", "favoritedAtMillis" to 1415L),
            )
            insert(
                database,
                "source_reconcile_runs",
                values(
                    "runId" to "run-ep14",
                    "policyStamp" to "policy-v10",
                    "ownerToken" to "owner-before-upgrade",
                    "startedAtMillis" to 1416L,
                    "resumeCount" to 2,
                    "sourcesChecked" to 3,
                    "healthySources" to 1,
                    "removedSources" to 0,
                    "unavailableSources" to 1,
                    "failedSources" to 0,
                    "quarantinedSources" to 0,
                    "filesSeen" to 30,
                    "recognized" to 20,
                    "groups" to 10,
                    "skipped" to 10,
                    "resumedWithDrift" to 1,
                    "driftedSources" to 1,
                ),
            )
            insert(
                database,
                "source_reconcile_roots",
                values(
                    "runId" to "run-ep14",
                    "rootId" to "root-ep14",
                    "rootUri" to safRoot,
                    "ordinal" to 1,
                    "plannedPrimary" to 1,
                    "phase" to "RUNNING",
                    "outcome" to null,
                    "attempts" to 2,
                    "rootSeenMillis" to 1417L,
                    "settledAtMillis" to null,
                ),
            )
        }

        helper.runMigrationsAndValidate(
            name,
            11,
            true,
            MimirDatabaseFactory.MIGRATION_10_11,
        ).use { database ->
            assertEquals(3L, database.long("SELECT COUNT(*) FROM games"))
            assertEquals(
                physicalResidentKey(safRoot, safRelative),
                database.string("SELECT entryKey FROM games WHERE gameId = 'saf-game'"),
            )
            assertEquals(
                physicalResidentKey(managedRoot, managedRelative),
                database.string("SELECT entryKey FROM games WHERE gameId = 'managed-game'"),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM games WHERE gameId = 'saf-game' " +
                        "AND sourceRootUri = '$safRoot' AND relativePath = '$safRelative' " +
                        "AND displayName = 'Disc \u03a9' AND title = 'Preserved Title' " +
                        "AND region = 'USA' AND version = '1.1' AND serial = 'SLUS-EP14' " +
                        "AND titleCatalogKey = 'ps1:ep14' AND identitySource = 'CATALOG' " +
                        "AND locationKind = 'SAF_TREE_GRANT' AND availability = 'STORAGE_UNAVAILABLE' " +
                        "AND sizeBytes = 9876543 AND contentHash = 'raw-sha1:${"a".repeat(40)}' " +
                        "AND sourceDocumentUri = 'content://provider/document/ep14' " +
                        "AND managedPath IS NULL AND discNumber = 2 " +
                        "AND duplicateGroupId = 'logical-ep14' AND isPrimaryMember = 0 " +
                        "AND duplicateGroupKind = 'MULTI_DISC' AND canonicalTitle = 'Canonical EP14' " +
                        "AND collapseKey = 'redump:ep14' AND identityOrigin = 'REDUMP' " +
                        "AND identityResolvedAtMillis = 1414",
                ),
            )
            assertEquals(
                managedRoot,
                database.string("SELECT sourceRootUri FROM games WHERE gameId = 'managed-game'"),
            )
            assertEquals(1415L, database.long("SELECT favoritedAtMillis FROM favorites WHERE gameId = 'saf-game'"))
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM source_reconcile_runs WHERE runId = 'run-ep14' " +
                        "AND policyStamp = 'policy-v10' AND ownerToken = 'owner-before-upgrade' " +
                        "AND startedAtMillis = 1416 AND resumeCount = 2 AND sourcesChecked = 3 " +
                        "AND healthySources = 1 AND removedSources = 0 AND unavailableSources = 1 " +
                        "AND failedSources = 0 AND quarantinedSources = 0 AND filesSeen = 30 " +
                        "AND recognized = 20 AND groups = 10 AND skipped = 10 " +
                        "AND resumedWithDrift = 1 AND driftedSources = 1",
                ),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM source_reconcile_roots WHERE runId = 'run-ep14' " +
                        "AND rootId = 'root-ep14' AND rootUri = '$safRoot' AND ordinal = 1 " +
                        "AND plannedPrimary = 1 AND phase = 'RUNNING' AND outcome IS NULL " +
                        "AND attempts = 2 AND rootSeenMillis = 1417 AND settledAtMillis IS NULL",
                ),
            )
            assertEquals(
                7L,
                database.long(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN (" +
                        "'index_games_gameId', 'index_games_platformId', 'index_games_contentHash', " +
                        "'index_games_duplicateGroupId', 'index_games_identity_sweep', " +
                        "'index_games_sourceRootUri_relativePath', 'index_games_sourceRootUri_entryKey')",
                ),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT `notnull` FROM pragma_table_info('games') WHERE name = 'sourceRootUri'",
                ),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT `unique` FROM pragma_index_list('games') " +
                        "WHERE name = 'index_games_sourceRootUri_relativePath'",
                ),
            )
            assertEquals(
                "sourceRootUri,relativePath",
                database.string(
                    "SELECT group_concat(name, ',') FROM (" +
                        "SELECT name FROM pragma_index_xinfo('index_games_sourceRootUri_relativePath') " +
                        "WHERE key = 1 ORDER BY seqno)",
                ),
            )
            assertEquals(
                "sourceRootUri,entryKey",
                database.string(
                    "SELECT group_concat(name, ',') FROM (" +
                        "SELECT name FROM pragma_index_xinfo('index_games_sourceRootUri_entryKey') " +
                        "WHERE key = 1 ORDER BY seqno)",
                ),
            )

            insert(
                database,
                "games",
                values(
                    "entryKey" to physicalResidentKey("r:", "game"),
                    "gameId" to "collision-b",
                    "platformId" to "ps1",
                    "contentKind" to "DISC_IMAGE",
                    "displayName" to "game",
                    "title" to "Chrono Cross",
                    "identitySource" to "FILENAME",
                    "locationKind" to "SAF_TREE_GRANT",
                    "availability" to "AVAILABLE",
                    "sizeBytes" to 1_024L,
                    "sourceRootUri" to "r:",
                    "relativePath" to "game",
                    "discNumber" to 1,
                    "isPrimaryMember" to 1,
                ),
            )
            assertEquals(
                2L,
                database.long("SELECT COUNT(*) FROM games WHERE gameId IN ('collision-a', 'collision-b')"),
            )
            assertEquals(
                2L,
                database.long(
                    "SELECT COUNT(DISTINCT entryKey) FROM games " +
                        "WHERE gameId IN ('collision-a', 'collision-b')",
                ),
            )
        }
    }

    @Test
    fun unsafeV10IdentityMigrationRollsBackWithoutPartialSchema() {
        val name = databaseName("unsafe-v10-rollback")
        helper.createDatabase(name, 10).use { database ->
            seedGame(database, entryKey = "root::game.chd", gameId = "unsafe", primary = true)
            database.execSQL("UPDATE games SET entryKey = 'other::game.chd' WHERE gameId = 'unsafe'")
            seedActiveV10ReconcileLedger(database, rootUri = "root")
        }

        assertThrows(Exception::class.java) {
            helper.runMigrationsAndValidate(
                name,
                11,
                true,
                MimirDatabaseFactory.MIGRATION_10_11,
            ).close()
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(10L, database.long("PRAGMA user_version"))
            assertEquals("other::game.chd", database.string("SELECT entryKey FROM games WHERE gameId = 'unsafe'"))
            assertEquals(0L, database.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'games_v11'"))
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM games WHERE entryKey = 'other::game.chd' " +
                        "AND gameId = 'unsafe' AND platformId = 'ps1' AND contentKind = 'GAME' " +
                        "AND displayName = 'game.chd' AND title = 'Chrono Cross' " +
                        "AND region IS NULL AND version IS NULL AND serial IS NULL " +
                        "AND titleCatalogKey IS NULL AND identitySource = 'FILENAME' " +
                        "AND locationKind = 'SAF_TREE_GRANT' AND availability = 'AVAILABLE' " +
                        "AND sizeBytes = 1024 AND contentHash IS NULL AND sourceRootUri = 'root' " +
                        "AND sourceDocumentUri IS NULL AND managedPath IS NULL " +
                        "AND relativePath = 'game.chd' AND discNumber = 1 " +
                        "AND duplicateGroupId IS NULL AND isPrimaryMember = 1 " +
                        "AND duplicateGroupKind IS NULL AND canonicalTitle IS NULL " +
                        "AND collapseKey IS NULL AND identityOrigin IS NULL " +
                        "AND identityResolvedAtMillis IS NULL",
                ),
            )
            assertEquals(
                5L,
                database.long(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN (" +
                        "'index_games_gameId', 'index_games_platformId', 'index_games_contentHash', " +
                        "'index_games_duplicateGroupId', 'index_games_identity_sweep')",
                ),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM source_reconcile_runs WHERE runId = 'rollback-run' " +
                        "AND policyStamp = 'rollback-policy' AND ownerToken = 'rollback-owner' " +
                        "AND startedAtMillis = 2001 AND resumeCount = 3 AND sourcesChecked = 1 " +
                        "AND healthySources = 0 AND removedSources = 0 AND unavailableSources = 0 " +
                        "AND failedSources = 0 AND quarantinedSources = 0 AND filesSeen = 7 " +
                        "AND recognized = 6 AND groups = 5 AND skipped = 1 " +
                        "AND resumedWithDrift = 1 AND driftedSources = 1",
                ),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM source_reconcile_roots WHERE runId = 'rollback-run' " +
                        "AND rootId = 'rollback-root' AND rootUri = 'root' AND ordinal = 0 " +
                        "AND plannedPrimary = 1 AND phase = 'RUNNING' AND outcome IS NULL " +
                        "AND attempts = 4 AND rootSeenMillis = 2002 AND settledAtMillis IS NULL",
                ),
            )
        }
    }

    @Test
    fun wrongStorageClassV10IdentityMigrationRollsBack() {
        val name = databaseName("wrong-storage-v10-rollback")
        helper.createDatabase(name, 10).use { database ->
            seedGame(database, entryKey = "root::game.chd", gameId = "blob-path", primary = true)
            database.execSQL(
                "UPDATE games SET relativePath = X'0102' WHERE gameId = 'blob-path'",
            )
        }

        assertThrows(Exception::class.java) {
            helper.runMigrationsAndValidate(
                name,
                11,
                true,
                MimirDatabaseFactory.MIGRATION_10_11,
            ).close()
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(10L, database.long("PRAGMA user_version"))
            assertEquals(
                "blob",
                database.string("SELECT typeof(relativePath) FROM games WHERE gameId = 'blob-path'"),
            )
            assertEquals(0L, database.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'games_v11'"))
        }
    }

    @Test
    fun populatedV11BiosPresenceResetsToUnknownWithLegacyScope() {
        val name = databaseName("populated-v11-bios")
        helper.createDatabase(name, 11).use { database ->
            insert(
                database,
                "platforms",
                values(
                    "platformId" to "ps1",
                    "displayName" to "PlayStation",
                    "gamesCount" to 3,
                    "requiresBios" to 1,
                    "catalogLongId" to "sony_playstation",
                ),
            )
            insert(
                database,
                "platforms",
                values(
                    "platformId" to "ps2",
                    "displayName" to "PlayStation 2",
                    "gamesCount" to 0,
                    "requiresBios" to 0,
                    "catalogLongId" to null,
                ),
            )
            insert(
                database,
                "bios_profiles",
                values(
                    "platformId" to "ps1",
                    "fileName" to "present.bin",
                    "required" to 1,
                    "presence" to "PRESENT",
                    "expectedHash" to "hash-present",
                    "expectedRelativePath" to "bios/present.bin",
                ),
            )
            insert(
                database,
                "bios_profiles",
                values(
                    "platformId" to "ps1",
                    "fileName" to "missing.bin",
                    "required" to 1,
                    "presence" to "MISSING",
                    "expectedHash" to "hash-missing",
                    "expectedRelativePath" to "bios/missing.bin",
                ),
            )
            insert(
                database,
                "bios_profiles",
                values(
                    "platformId" to "ps2",
                    "fileName" to "unknown.bin",
                    "required" to 0,
                    "presence" to "UNKNOWN",
                    "expectedHash" to null,
                    "expectedRelativePath" to null,
                ),
            )
        }

        helper.runMigrationsAndValidate(
            name,
            12,
            true,
            MimirDatabaseFactory.MIGRATION_11_12,
        ).use { database ->
            assertEquals(3L, database.long("SELECT COUNT(*) FROM bios_profiles"))
            assertEquals(
                3L,
                database.long("SELECT COUNT(*) FROM bios_profiles WHERE presence = 'UNKNOWN'"),
            )
            assertEquals(
                3L,
                database.long("SELECT COUNT(*) FROM bios_profiles WHERE emulatorScopeKey = ''"),
            )
            assertEquals(
                3L,
                database.long("SELECT COUNT(*) FROM bios_profiles WHERE packageName IS NULL"),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM bios_profiles WHERE fileName = 'present.bin' " +
                        "AND required = 1 AND expectedHash = 'hash-present' " +
                        "AND expectedRelativePath = 'bios/present.bin'",
                ),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM bios_profiles WHERE fileName = 'missing.bin' " +
                        "AND required = 1 AND expectedHash = 'hash-missing' " +
                        "AND expectedRelativePath = 'bios/missing.bin'",
                ),
            )
            assertEquals(
                1L,
                database.long(
                    "SELECT COUNT(*) FROM bios_profiles WHERE fileName = 'unknown.bin' " +
                        "AND required = 0 AND expectedHash IS NULL " +
                        "AND expectedRelativePath IS NULL",
                ),
            )
            assertEquals(1L, database.long("SELECT requiresBios FROM platforms WHERE platformId = 'ps1'"))
            assertEquals(0L, database.long("SELECT requiresBios FROM platforms WHERE platformId = 'ps2'"))
            assertEquals(
                "sony_playstation",
                database.string("SELECT catalogLongId FROM platforms WHERE platformId = 'ps1'"),
            )
        }
    }

    @Test
    fun frozenV11CompatibilityCeilingRefusesActualV12DatabaseWithoutMutatingBytes() {
        val name = databaseName("v11-ceiling-actual-v12")
        helper.createDatabase(name, 11).use { database ->
            insert(
                database,
                "bios_profiles",
                values(
                    "platformId" to "ps1",
                    "fileName" to "scph5501.bin",
                    "required" to 1,
                    "presence" to "PRESENT",
                    "expectedHash" to "owner-hash",
                    "expectedRelativePath" to "bios/scph5501.bin",
                ),
            )
            insert(
                database,
                "platforms",
                values(
                    "platformId" to "ps1",
                    "displayName" to "PlayStation",
                    "gamesCount" to 1,
                    "requiresBios" to 1,
                    "catalogLongId" to "sony_playstation",
                ),
            )
        }
        helper.runMigrationsAndValidate(
            name,
            12,
            true,
            MimirDatabaseFactory.MIGRATION_11_12,
        ).close()

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(12L, database.long("PRAGMA user_version"))
        }

        val before = databaseFilesDigest(name)
        val failure = MimirDatabaseFactory.inspectDatabaseCompatibility(
            appContext = context,
            databaseName = name,
            supportedSchemaVersion = 11,
        )
        val after = databaseFilesDigest(name)

        assertEquals(DatabaseOpenFailureReason.NEWER_SCHEMA, failure?.reason)
        assertEquals(12, failure?.foundSchemaVersion)
        assertEquals(11, failure?.supportedSchemaVersion)
        assertEquals("Frozen v11 ceiling probe changed owner bytes", before, after)
    }

    @Test
    fun newerDatabaseIsRefusedWithoutDeletingItsState() {
        val name = databaseName("downgrade-refusal")
        helper.createDatabase(name, 11).use { database ->
            insert(
                database,
                "favorites",
                values("gameId" to "must-survive", "favoritedAtMillis" to 101L),
            )
            database.execSQL("PRAGMA user_version = 15")
        }

        val beforeGate = databaseFilesDigest(name)
        val gateFailure = MimirDatabaseFactory.inspectDatabaseCompatibility(context, name)
        val afterGate = databaseFilesDigest(name)
        assertEquals(DatabaseOpenFailureReason.NEWER_SCHEMA, gateFailure?.reason)
        assertEquals(15, gateFailure?.foundSchemaVersion)
        assertEquals(14, gateFailure?.supportedSchemaVersion)
        assertEquals("Compatibility gate changed database bytes", beforeGate, afterGate)

        val candidate = MimirDatabaseFactory.databaseBuilder(context, name).build()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                candidate.openHelper.writableDatabase
            }
            assertTrue(
                failure.message.orEmpty().contains("migration", ignoreCase = true),
            )
        } finally {
            candidate.close()
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(15L, database.long("PRAGMA user_version"))
            assertEquals(101L, database.long("SELECT favoritedAtMillis FROM favorites WHERE gameId = 'must-survive'"))
            assertTrue(database.hasTable("logical_game_groups"))
        }
    }

    @Test
    fun corruptDatabaseIsNeverDeletedByProbeOrRoomOpen() {
        val name = databaseName("corrupt-preservation")
        val file = context.getDatabasePath(name)
        checkNotNull(file.parentFile).mkdirs()
        file.writeBytes(
            ByteArray(8 * 1024) { index ->
                ((index * 31 + 17) and 0xff).toByte()
            },
        )
        val before = databaseFilesDigest(name)

        assertThrows(SQLiteException::class.java) {
            MimirDatabaseFactory.inspectDatabaseCompatibility(context, name)
        }
        assertEquals("Read-only probe deleted or rewrote corrupt bytes", before, databaseFilesDigest(name))

        val candidate = MimirDatabaseFactory.databaseBuilder(context, name).build()
        try {
            assertThrows(SQLiteException::class.java) {
                candidate.openHelper.writableDatabase
            }
        } finally {
            candidate.close()
        }
        assertEquals("Room corruption handling deleted or rewrote bytes", before, databaseFilesDigest(name))
        assertTrue(file.isFile)
        assertEquals(8 * 1024L, file.length())
    }

    @Test
    fun hotWalNewerSchemaIsRefusedWithoutChangingDatabaseOrSidecars() {
        val name = databaseName("hot-wal-downgrade-refusal")
        val file = context.getDatabasePath(name)
        val writer = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            assertTrue(writer.enableWriteAheadLogging())
            writer.execSQL("CREATE TABLE owner_state (id INTEGER PRIMARY KEY, marker TEXT NOT NULL)")
            writer.execSQL("INSERT INTO owner_state(marker) VALUES ('must-survive-hot-wal')")
            writer.execSQL("PRAGMA user_version = 15")

            val wal = File(file.path + "-wal")
            assertTrue("Fixture did not retain a WAL", wal.isFile && wal.length() > 32L)
            assertNotEquals(
                "Fixture checkpointed the future schema into the main header",
                13,
                sqliteHeaderUserVersion(file),
            )
            val before = databaseFilesDigest(name)

            val failure = MimirDatabaseFactory.inspectDatabaseCompatibility(context, name)

            assertEquals(DatabaseOpenFailureReason.NEWER_SCHEMA, failure?.reason)
            assertEquals(15, failure?.foundSchemaVersion)
            assertEquals(14, failure?.supportedSchemaVersion)
            assertEquals(
                "Hot-WAL compatibility probe changed database bytes",
                before,
                databaseFilesDigest(name),
            )
        } finally {
            writer.close()
        }
    }

    @Test
    fun directBorrowerLatchesRefusalBeforeApplicationStartupCanRun() {
        val name = MimirDatabase.DB_NAME
        databaseNames += name
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { database ->
            database.execSQL("CREATE TABLE owner_state (id INTEGER PRIMARY KEY, marker TEXT NOT NULL)")
            database.execSQL("INSERT INTO owner_state(marker) VALUES ('worker-must-not-open')")
            database.execSQL("PRAGMA user_version = 15")
        }
        val before = databaseFilesDigest(name)

        val workerFailure = assertThrows(DatabaseOpenRefusedException::class.java) {
            MimirDatabaseFactory.get(context)
        }

        assertEquals(15, workerFailure.failure.foundSchemaVersion)
        assertEquals(14, workerFailure.failure.supportedSchemaVersion)
        assertEquals(before, databaseFilesDigest(name))

        // Simulate an alternate borrower arriving after the first background discovery. Removing the
        // fixture means a missing latch would create/open a fresh Room database; the process authority
        // must instead keep every later get() blocked and hand the same fact to Application startup.
        assertTrue(context.deleteDatabase(name))
        val laterBorrower = assertThrows(DatabaseOpenRefusedException::class.java) {
            MimirDatabaseFactory.get(context)
        }
        assertSame(workerFailure.failure, laterBorrower.failure)
        assertSame(workerFailure.failure, MimirDatabaseFactory.startupFailure(context))
        assertTrue(!context.getDatabasePath(name).exists())
    }

    private fun databaseName(label: String): String =
        "ep05-$label.db".also(databaseNames::add)

    private fun sqliteHeaderUserVersion(file: File): Int =
        RandomAccessFile(file, "r").use { input ->
            input.seek(60L)
            input.readInt()
        }

    private fun databaseFilesDigest(name: String): String {
        val database = context.getDatabasePath(name)
        val files = listOf(
            database,
            File(database.path + "-wal"),
            File(database.path + "-shm"),
            File(database.path + "-journal"),
        ).filter { it.isFile }
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.name }.forEach { file ->
            digest.update(file.name.toByteArray())
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun seedV1Platform(database: SupportSQLiteDatabase) {
        insert(
            database,
            "platforms",
            values(
                "platformId" to "ps1",
                "displayName" to "PlayStation",
                "gamesCount" to 1,
                "requiresBios" to 1,
                "catalogLongId" to "sony_playstation",
            ),
        )
    }

    private fun seedGame(
        database: SupportSQLiteDatabase,
        entryKey: String,
        gameId: String,
        primary: Boolean,
        duplicateGroupId: String? = null,
        discNumber: Int = 1,
    ) {
        val root = entryKey.substringBefore("::")
        val relativePath = entryKey.removePrefix("$root::")
        insert(
            database,
            "games",
            values(
                "entryKey" to entryKey,
                "gameId" to gameId,
                "platformId" to "ps1",
                "contentKind" to "GAME",
                "displayName" to relativePath,
                "title" to "Chrono Cross",
                "identitySource" to "FILENAME",
                "locationKind" to "SAF_TREE_GRANT",
                "availability" to "AVAILABLE",
                "sizeBytes" to 1_024L,
                "sourceRootUri" to root,
                "managedPath" to null,
                "relativePath" to relativePath,
                "discNumber" to discNumber,
                "duplicateGroupId" to duplicateGroupId,
                "isPrimaryMember" to if (primary) 1 else 0,
                "duplicateGroupKind" to duplicateGroupId?.let { "MULTI_DISC" },
            ),
        )
    }

    private fun seedActiveV10ReconcileLedger(
        database: SupportSQLiteDatabase,
        rootUri: String,
    ) {
        insert(
            database,
            "source_reconcile_runs",
            values(
                "runId" to "rollback-run",
                "policyStamp" to "rollback-policy",
                "ownerToken" to "rollback-owner",
                "startedAtMillis" to 2001L,
                "resumeCount" to 3,
                "sourcesChecked" to 1,
                "healthySources" to 0,
                "removedSources" to 0,
                "unavailableSources" to 0,
                "failedSources" to 0,
                "quarantinedSources" to 0,
                "filesSeen" to 7,
                "recognized" to 6,
                "groups" to 5,
                "skipped" to 1,
                "resumedWithDrift" to 1,
                "driftedSources" to 1,
            ),
        )
        insert(
            database,
            "source_reconcile_roots",
            values(
                "runId" to "rollback-run",
                "rootId" to "rollback-root",
                "rootUri" to rootUri,
                "ordinal" to 0,
                "plannedPrimary" to 1,
                "phase" to "RUNNING",
                "outcome" to null,
                "attempts" to 4,
                "rootSeenMillis" to 2002L,
                "settledAtMillis" to null,
            ),
        )
    }

    private fun values(vararg entries: Pair<String, Any?>): ContentValues =
        ContentValues(entries.size).apply {
            entries.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> error("Unsupported ContentValues value for $key: ${value::class.java.name}")
                }
            }
        }

    private fun insert(
        database: SupportSQLiteDatabase,
        table: String,
        values: ContentValues,
    ) {
        assertNotEquals(
            "insert into $table failed",
            -1L,
            database.insert(table, SQLiteDatabase.CONFLICT_ABORT, values),
        )
    }
}

private fun SupportSQLiteDatabase.long(sql: String): Long =
    query(sql).use { cursor ->
        assertTrue("No row for: $sql", cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun SupportSQLiteDatabase.string(sql: String): String =
    query(sql).use { cursor ->
        assertTrue("No row for: $sql", cursor.moveToFirst())
        cursor.getString(0)
    }

private fun SupportSQLiteDatabase.isNull(sql: String): Boolean =
    query(sql).use { cursor ->
        assertTrue("No row for: $sql", cursor.moveToFirst())
        cursor.isNull(0)
    }

private fun SQLiteDatabase.long(sql: String): Long =
    rawQuery(sql, null).use { cursor ->
        assertTrue("No row for: $sql", cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun SQLiteDatabase.string(sql: String): String =
    rawQuery(sql, null).use { cursor ->
        assertTrue("No row for: $sql", cursor.moveToFirst())
        cursor.getString(0)
    }

private fun SQLiteDatabase.hasTable(table: String): Boolean =
    rawQuery(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(table),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0) == 1L
    }
