package com.gruning.mimir.rewrite.core.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MimirDatabaseMigration12To13SqlTest {
    private lateinit var connection: Connection

    @Before
    fun openV12Database() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE games (" +
                    "entryKey TEXT NOT NULL PRIMARY KEY, gameId TEXT NOT NULL, title TEXT NOT NULL, " +
                    "canonicalTitle TEXT)",
            )
            statement.executeUpdate(
                "INSERT INTO games VALUES ('root::chrono.cue', 'chrono-old', 'Chrono Cross', NULL)",
            )
        }
    }

    @After
    fun closeDatabase() = connection.close()

    @Test
    fun `v12 rows including title and canonicalTitle survive the additive v13 migration exactly`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT entryKey, gameId, title, canonicalTitle FROM games").use { result ->
                assertTrue(result.next())
                assertEquals("root::chrono.cue", result.getString("entryKey"))
                assertEquals("chrono-old", result.getString("gameId"))
                assertEquals("Chrono Cross", result.getString("title"))
                assertEquals(null, result.getString("canonicalTitle"))
                assertFalse(result.next())
            }
        }
    }

    @Test
    fun `v13 creates an empty title_overrides table — no row is seeded by the migration itself`() {
        migrate()

        assertTrue(tableNames().contains("title_overrides"))
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM title_overrides").use { result ->
                assertTrue(result.next())
                assertEquals(0, result.getInt(1))
            }
        }
    }

    @Test
    fun `title_overrides round-trips a manual rename keyed on gameId alone`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO title_overrides (gameId, title, setAtMillis) VALUES " +
                    "('chrono-old', 'Chrono Trigger 2: Radical Dreamers Cut', 5000)",
            )
            statement.executeQuery(
                "SELECT title, setAtMillis FROM title_overrides WHERE gameId = 'chrono-old'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals("Chrono Trigger 2: Radical Dreamers Cut", result.getString("title"))
                assertEquals(5000L, result.getLong("setAtMillis"))
                assertFalse(result.next())
            }
        }
    }

    @Test
    fun `title_overrides primary key rejects a second row for the same gameId`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO title_overrides (gameId, title, setAtMillis) VALUES ('chrono-old', 'First', 1)",
            )
            var threw = false
            try {
                statement.executeUpdate(
                    "INSERT INTO title_overrides (gameId, title, setAtMillis) VALUES ('chrono-old', 'Second', 2)",
                )
            } catch (_: java.sql.SQLException) {
                threw = true
            }
            assertTrue("a duplicate gameId must violate the PRIMARY KEY", threw)
        }
    }

    @Test
    fun `re-scan style upsert of games title never touches an existing title_overrides row`() {
        // The whole precedence mechanism: title_overrides is a separate table, so an upsertAll-style
        // rewrite of games.title (what a re-scan does) cannot reach it.
        migrate()
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO title_overrides (gameId, title, setAtMillis) VALUES " +
                    "('chrono-old', 'My Corrected Title', 5000)",
            )
            // Simulate a re-scan re-upserting the scanned title AND a later re-scrape writing canonicalTitle.
            statement.executeUpdate(
                "UPDATE games SET title = 'chrono cross rescanned filename' WHERE gameId = 'chrono-old'",
            )
            statement.executeUpdate(
                "UPDATE games SET canonicalTitle = 'Chrono Cross (Provider Name)' WHERE gameId = 'chrono-old'",
            )
            statement.executeQuery(
                "SELECT title FROM title_overrides WHERE gameId = 'chrono-old'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals("My Corrected Title", result.getString("title"))
            }
        }
    }

    /**
     * The other tests exercise [CREATE_TITLE_OVERRIDES_SQL] directly (the repo-wide pattern for every
     * migration test here — no JVM-level `SupportSQLiteDatabase` backed by real SQLite exists in this
     * codebase for any of the 12 migrations before this one). This one instead calls the actual registered
     * `MIGRATION_12_13.migrate(...)` through a recording proxy, so the test would fail if that object's
     * body ever diverged from the constant (e.g. were emptied) even though the constant itself stayed
     * correct — the gap a constant-only test can't see.
     */
    @Test
    fun `the registered MIGRATION_12_13 object issues exactly CREATE_TITLE_OVERRIDES_SQL`() {
        val issued = mutableListOf<String>()
        val recordingDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args?.size == 1) {
                issued += args[0] as String
                Unit
            } else {
                error("MIGRATION_12_13 should only call execSQL; unexpected: ${method.name}")
            }
        } as SupportSQLiteDatabase

        MimirDatabaseFactory.MIGRATION_12_13.migrate(recordingDb)

        assertEquals(listOf(CREATE_TITLE_OVERRIDES_SQL), issued)
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(CREATE_TITLE_OVERRIDES_SQL)
        }
    }

    private fun tableNames(): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { result ->
            buildSet { while (result.next()) add(result.getString("name")) }
        }
    }
}
