package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two host-orchestrated phases prove actual process-death rollback on the same Room v9 boundary used by
 * recovery apply. The first phase never returns: the host waits for [ReadyMarker], then force-stops only
 * the separate `.lab` package. The second phase restarts instrumentation and verifies that all four owner
 * stores rolled back while the committed pre-transaction library row survived.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryRestoreProcessDeathPrepareAndroidLabTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun writeAllOwnedStoresThenWaitForProcessDeath() =
        RecoveryRestoreProcessDeathFixture.prepare(context)
}

@RunWith(AndroidJUnit4::class)
class RecoveryRestoreProcessDeathRestartAndroidLabTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun assertRollbackAfterProcessDeath() =
        RecoveryRestoreProcessDeathFixture.assertRollback(context)
}

private object RecoveryRestoreProcessDeathFixture {
    fun prepare(context: Context) = runBlocking {
        assertEquals(MIMIR_LAB_PACKAGE, context.packageName)
        context.deleteDatabase(DatabaseName)
        marker(context).delete()

        val database = openDatabase(context)
        seedLibrary(database)
        database.withTransaction {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO favorites(gameId,favoritedAtMillis) VALUES(?,?)",
                arrayOf(GameId, 101L),
            )
            sqlite.execSQL(
                "INSERT INTO hidden_items(gameId,hidden,reason,duplicateGroupId,isPrimaryMember,hiddenAtMillis) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf(GameId, 1, "USER_DISMISSED", null, 1, 102L),
            )
            sqlite.execSQL(
                "INSERT INTO emulator_overrides(gameId,emulatorId,setAtMillis) VALUES(?,?,?)",
                arrayOf(GameId, "", 103L),
            )
            sqlite.execSQL(
                "INSERT INTO home_shelf_items(profileId,itemKind,itemId,rank,addedAtMillis) VALUES(?,?,?,?,?)",
                arrayOf("local", "EMULATED_GAME", GameId, 1_024L, 104L),
            )
            marker(context).writeText("READY")
            assertTrue(marker(context).isFile)
            Log.i(LogTag, "READY_FOR_PROCESS_DEATH")
            while (true) delay(100L)
        }
    }

    fun assertRollback(context: Context) {
        assertEquals(MIMIR_LAB_PACKAGE, context.packageName)
        assertTrue("Phase-one readiness marker is missing", marker(context).isFile)

        val database = openDatabase(context)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(1L, sqlite.count("games"))
            assertEquals(0L, sqlite.count("favorites"))
            assertEquals(0L, sqlite.count("hidden_items"))
            assertEquals(0L, sqlite.count("emulator_overrides"))
            assertEquals(0L, sqlite.count("home_shelf_items"))
        } finally {
            database.close()
            context.deleteDatabase(DatabaseName)
            marker(context).delete()
        }
    }

    private fun openDatabase(context: Context): MimirDatabase =
        Room.databaseBuilder(context, MimirDatabase::class.java, DatabaseName).build()

    private fun seedLibrary(database: MimirDatabase) {
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            sqlite.execSQL(
                "INSERT INTO platforms(platformId,displayName,gamesCount,requiresBios,catalogLongId) " +
                    "VALUES(?,?,?,?,?)",
                arrayOf("gba", "Game Boy Advance", 1, 0, "game-boy-advance"),
            )
            sqlite.execSQL(
                """
                INSERT INTO games(
                    entryKey,gameId,platformId,contentKind,displayName,title,region,version,serial,
                    titleCatalogKey,identitySource,locationKind,availability,sizeBytes,contentHash,
                    sourceRootUri,sourceDocumentUri,managedPath,relativePath,discNumber,duplicateGroupId,
                    isPrimaryMember,duplicateGroupKind,canonicalTitle,collapseKey,identityOrigin,
                    identityResolvedAtMillis
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf(
                    "entry:ep06b-process-death",
                    GameId,
                    "gba",
                    "ROM",
                    "Ep06bProcessDeath.gba",
                    "Ep06b Process Death",
                    "USA",
                    "1.0",
                    "EP06B-PD",
                    "catalog:ep06b-process-death",
                    "HEADER",
                    "SAF_TREE_GRANT",
                    "AVAILABLE",
                    1_048_576L,
                    "e".repeat(64),
                    "content://lab/process-death-root",
                    "content://lab/process-death-document",
                    null,
                    "Ep06bProcessDeath.gba",
                    0,
                    null,
                    1,
                    null,
                    "Ep06b Process Death",
                    "catalog:ep06b-process-death",
                    "HEADER_PAYLOAD",
                    11L,
                ),
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun marker(context: Context): File = File(context.cacheDir, ReadyMarker)

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Long =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private const val DatabaseName = "ep06b-restore-process-death-lab.db"
    private const val GameId = "game:ep06b-process-death"
    private const val ReadyMarker = "ep06b-restore-process-death.ready"
    private const val LogTag = "MimirEp06bRestore"
}
