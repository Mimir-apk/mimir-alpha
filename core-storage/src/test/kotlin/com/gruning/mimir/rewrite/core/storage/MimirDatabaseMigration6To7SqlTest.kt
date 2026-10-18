package com.gruning.mimir.rewrite.core.storage

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MimirDatabaseMigration6To7SqlTest {
    private lateinit var connection: Connection

    @Before
    fun openV6Database() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE launch_proofs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "gameId TEXT NOT NULL, platformId TEXT NOT NULL, emulatorId TEXT NOT NULL, " +
                    "locator TEXT NOT NULL, outcome TEXT NOT NULL, timestampMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE INDEX index_launch_proofs_gameId ON launch_proofs (gameId)",
            )
            statement.executeUpdate(
                "INSERT INTO launch_proofs " +
                    "(id, gameId, platformId, emulatorId, locator, outcome, timestampMillis) VALUES " +
                    "(7, 'chrono-cross', 'ps1', 'duckstation', '/roms/chrono.m3u', 'intent_sent', 1234)",
            )
        }
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    @Test
    fun `v6 to v7 preserves emulated proofs and adds the explicit package target`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM launch_proofs WHERE id = 7").use { result ->
                assertTrue(result.next())
                assertEquals("chrono-cross", result.getString("gameId"))
                assertNull(result.getString("androidPackageName"))
                assertEquals("intent_sent", result.getString("outcome"))
                assertEquals(1234L, result.getLong("timestampMillis"))
            }
        }

        val nullableTargets = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info('launch_proofs')").use { result ->
                buildMap {
                    while (result.next()) put(result.getString("name"), result.getInt("notnull"))
                }
            }
        }
        assertEquals(0, nullableTargets["gameId"])
        assertEquals(0, nullableTargets["androidPackageName"])

        val indices = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list('launch_proofs')").use { result ->
                buildList { while (result.next()) add(result.getString("name")) }
            }
        }
        assertTrue("game index missing: $indices", "index_launch_proofs_gameId" in indices)
        assertTrue(
            "Android-package index missing: $indices",
            "index_launch_proofs_androidPackageName" in indices,
        )

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO launch_proofs " +
                    "(gameId, androidPackageName, platformId, emulatorId, locator, outcome, timestampMillis) " +
                    "VALUES (NULL, 'com.example.game', 'android-games', 'com.example.game', " +
                    "'package:com.example.game', 'intent_sent', 2000)",
            )
        }
        assertEquals(2, rowCount())
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT id FROM launch_proofs WHERE androidPackageName = 'com.example.game'",
            ).use { result ->
                assertTrue(result.next())
                assertTrue("AUTOINCREMENT did not advance past the preserved id", result.getLong(1) > 7L)
            }
        }
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(CREATE_LAUNCH_PROOFS_V7_SQL)
            statement.executeUpdate(
                "INSERT INTO launch_proofs_v7 " +
                    "(id, gameId, androidPackageName, platformId, emulatorId, locator, outcome, timestampMillis) " +
                    "SELECT id, gameId, NULL, platformId, emulatorId, locator, outcome, timestampMillis " +
                    "FROM launch_proofs",
            )
            statement.executeUpdate("DROP TABLE launch_proofs")
            statement.executeUpdate("ALTER TABLE launch_proofs_v7 RENAME TO launch_proofs")
            statement.executeUpdate(CREATE_LAUNCH_PROOFS_GAME_INDEX_SQL)
            statement.executeUpdate(CREATE_LAUNCH_PROOFS_ANDROID_PACKAGE_INDEX_SQL)
        }
    }

    private fun rowCount(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM launch_proofs").use { result ->
            result.next()
            result.getInt(1)
        }
    }
}
