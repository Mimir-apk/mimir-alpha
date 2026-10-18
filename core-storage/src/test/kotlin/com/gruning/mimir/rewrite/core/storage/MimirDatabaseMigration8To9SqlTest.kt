package com.gruning.mimir.rewrite.core.storage

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MimirDatabaseMigration8To9SqlTest {
    private lateinit var connection: Connection

    @Before
    fun openV8Database() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE games (" +
                    "entryKey TEXT NOT NULL PRIMARY KEY, gameId TEXT NOT NULL, title TEXT NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE favorites (" +
                    "gameId TEXT NOT NULL PRIMARY KEY, favoritedAtMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "INSERT INTO games VALUES ('root::chrono.cue', 'chrono-old', 'Chrono Cross')",
            )
            statement.executeUpdate(
                "INSERT INTO favorites VALUES ('chrono-old', 1234)",
            )
        }
    }

    @After
    fun closeDatabase() = connection.close()

    @Test
    fun `v8 rows survive the additive v9 schema migration exactly`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT entryKey, gameId, title FROM games").use { result ->
                assertTrue(result.next())
                assertEquals("root::chrono.cue", result.getString("entryKey"))
                assertEquals("chrono-old", result.getString("gameId"))
                assertEquals("Chrono Cross", result.getString("title"))
                assertFalse(result.next())
            }
            statement.executeQuery("SELECT gameId, favoritedAtMillis FROM favorites").use { result ->
                assertTrue(result.next())
                assertEquals("chrono-old", result.getString("gameId"))
                assertEquals(1234L, result.getLong("favoritedAtMillis"))
                assertFalse(result.next())
            }
        }
    }

    @Test
    fun `v9 creates the lineage alias and pending-journal indexes Room declares`() {
        migrate()

        assertEquals(
            setOf(
                "logical_game_groups",
                "game_id_aliases",
                "remap_journal",
                "identity_maintenance",
            ),
            tableNames().intersect(
                setOf(
                    "logical_game_groups",
                    "game_id_aliases",
                    "remap_journal",
                    "identity_maintenance",
                ),
            ),
        )
        assertTrue(
            "current-winner uniqueness missing",
            "index_logical_game_groups_currentGameId" in indexNames("logical_game_groups"),
        )
        assertTrue(
            "alias lookup index missing",
            "index_game_id_aliases_logicalGameId" in indexNames("game_id_aliases"),
        )
        assertTrue(
            "pending replay index missing",
            "index_remap_journal_consumedAtMillis_journalId" in indexNames("remap_journal"),
        )
    }

    @Test
    fun `journal permits a later recurrence of the same textual transition`() {
        migrate()
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO remap_journal " +
                    "(logicalGameId, fromGameId, toGameId, reason, createdAtMillis, " +
                    "storesCompletedMask, consumedAtMillis, lastError) VALUES " +
                    "('lg:1', 'A', 'B', 'WINNER_REMOVED', 1, 1023, 2, NULL)",
            )
            statement.executeUpdate(
                "INSERT INTO remap_journal " +
                    "(logicalGameId, fromGameId, toGameId, reason, createdAtMillis, " +
                    "storesCompletedMask, consumedAtMillis, lastError) VALUES " +
                    "('lg:1', 'A', 'B', 'WINNER_REMOVED', 3, 0, NULL, NULL)",
            )
            statement.executeQuery(
                "SELECT COUNT(*) FROM remap_journal " +
                    "WHERE fromGameId = 'A' AND toGameId = 'B' AND reason = 'WINNER_REMOVED'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals(2, result.getInt(1))
            }
        }
    }

    @Test
    fun `backfill maintenance claim is unique and crash transaction compatible`() {
        migrate()
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT OR IGNORE INTO identity_maintenance VALUES " +
                    "('v9-logical-group-backfill', 10)",
            )
            statement.executeUpdate(
                "INSERT OR IGNORE INTO identity_maintenance VALUES " +
                    "('v9-logical-group-backfill', 20)",
            )
            statement.executeQuery(
                "SELECT COUNT(*), MIN(completedAtMillis) FROM identity_maintenance " +
                    "WHERE maintenanceKey = 'v9-logical-group-backfill'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals(1, result.getInt(1))
                assertEquals(10L, result.getLong(2))
            }
        }
        connection.rollback()
        connection.autoCommit = true
        assertTrue(tableNames().contains("identity_maintenance"))
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(CREATE_LOGICAL_GAME_GROUPS_SQL)
            statement.executeUpdate(CREATE_LOGICAL_GAME_CURRENT_INDEX_SQL)
            statement.executeUpdate(CREATE_GAME_ID_ALIASES_SQL)
            statement.executeUpdate(CREATE_GAME_ID_ALIASES_LOGICAL_INDEX_SQL)
            statement.executeUpdate(CREATE_REMAP_JOURNAL_SQL)
            statement.executeUpdate(CREATE_REMAP_JOURNAL_PENDING_INDEX_SQL)
            statement.executeUpdate(CREATE_IDENTITY_MAINTENANCE_SQL)
        }
    }

    private fun tableNames(): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { result ->
            buildSet { while (result.next()) add(result.getString("name")) }
        }
    }

    private fun indexNames(table: String): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA index_list('$table')").use { result ->
            buildSet { while (result.next()) add(result.getString("name")) }
        }
    }
}
