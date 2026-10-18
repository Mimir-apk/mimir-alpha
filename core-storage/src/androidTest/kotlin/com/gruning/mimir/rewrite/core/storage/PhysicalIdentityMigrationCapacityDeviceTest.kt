package com.gruning.mimir.rewrite.core.storage

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in migration-capacity proof. Run once with `ep14.capacityRows=100000` and once with `200000`.
 *
 * This deliberately seeds a real exported v10 database, then executes the production 10→11 Room
 * migration and schema validator. It does not replace physical removable-card certification.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PhysicalIdentityMigrationCapacityDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MimirDatabase::class.java,
    )

    @Test
    fun populatedV10CapacityMigratesInjectivelyWithExactRootIndexes() {
        val rows = InstrumentationRegistry.getArguments()
            .getString("ep14.capacityRows")
            ?.toIntOrNull()
        assumeTrue("Run with ep14.capacityRows=100000 or 200000", rows == 100_000 || rows == 200_000)
        val requiredRows = requireNotNull(rows)
        val name = "ep14-capacity-$requiredRows-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        try {
            val seedStarted = SystemClock.elapsedRealtime()
            helper.createDatabase(name, 10).use { database ->
                seedGames(database, requiredRows)
                assertEquals(requiredRows.toLong(), database.scalarLong("SELECT COUNT(*) FROM games"))
            }
            val seedMillis = SystemClock.elapsedRealtime() - seedStarted

            val runtime = Runtime.getRuntime()
            runtime.gc()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val migrationStarted = SystemClock.elapsedRealtime()
            helper.runMigrationsAndValidate(
                name,
                11,
                true,
                MimirDatabaseFactory.MIGRATION_10_11,
            ).use { database ->
                val migrationMillis = SystemClock.elapsedRealtime() - migrationStarted
                assertEquals(requiredRows.toLong(), database.scalarLong("SELECT COUNT(*) FROM games"))
                assertEquals(
                    requiredRows.toLong(),
                    database.scalarLong("SELECT COUNT(DISTINCT entryKey) FROM games"),
                )
                assertEquals(
                    0L,
                    database.scalarLong(
                        "SELECT COUNT(*) FROM games WHERE sourceRootUri IS NULL OR " +
                            "entryKey != ('p2:' || LENGTH(sourceRootUri) || ':' || " +
                            "LENGTH(relativePath) || ':' || sourceRootUri || relativePath)",
                    ),
                )
                assertEquals(
                    requiredRows / 2L,
                    database.scalarLong(
                        "SELECT COUNT(*) FROM games WHERE sourceRootUri = '$ROOT_A'",
                    ),
                )
                assertEquals(
                    requiredRows / 2L,
                    database.scalarLong(
                        "SELECT COUNT(*) FROM games WHERE sourceRootUri = '$ROOT_B'",
                    ),
                )
                val plan = database.query(
                    "EXPLAIN QUERY PLAN SELECT entryKey FROM games " +
                        "WHERE sourceRootUri = ? ORDER BY relativePath, entryKey",
                    arrayOf(ROOT_A),
                ).use { cursor ->
                    buildString {
                        while (cursor.moveToNext()) append(cursor.getString(3)).append('\n')
                    }
                }
                assertTrue(plan, plan.contains("index_games_sourceRootUri_relativePath"))

                runtime.gc()
                val heapAfter = runtime.totalMemory() - runtime.freeMemory()
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "ep14.capacity",
                            "rows=$requiredRows seedMs=$seedMillis migrationMs=$migrationMillis " +
                                "heapBefore=$heapBefore heapAfter=$heapAfter",
                        )
                    },
                )
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun seedGames(database: SupportSQLiteDatabase, rows: Int) {
        val statement = database.compileStatement(
            "INSERT INTO games(" +
                "entryKey, gameId, platformId, contentKind, displayName, title, identitySource, " +
                "locationKind, availability, sizeBytes, sourceRootUri, relativePath, discNumber, " +
                "isPrimaryMember) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        database.beginTransaction()
        try {
            repeat(rows) { index ->
                val root = if (index and 1 == 0) ROOT_A else ROOT_B
                val relativePath = "platform/${index.toString().padStart(6, '0')}/game::${index}.rom"
                statement.clearBindings()
                statement.bindString(1, "$root::$relativePath")
                statement.bindString(2, "game-$index")
                statement.bindString(3, "snes")
                statement.bindString(4, "GAME")
                statement.bindString(5, "Game $index")
                statement.bindString(6, "Game $index")
                statement.bindString(7, "FILENAME")
                statement.bindString(8, "SAF_TREE_GRANT")
                statement.bindString(9, "AVAILABLE")
                statement.bindLong(10, index.toLong() + 1L)
                statement.bindString(11, root)
                statement.bindString(12, relativePath)
                statement.bindLong(13, 0L)
                statement.bindLong(14, 1L)
                statement.executeInsert()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            statement.close()
        }
    }

    private fun SupportSQLiteDatabase.scalarLong(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        const val ROOT_A = "content://ep14/capacity/root-A"
        const val ROOT_B = "content://ep14/capacity/root-B:"
    }
}
