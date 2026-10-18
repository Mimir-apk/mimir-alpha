package com.gruning.mimir.rewrite.core.storage

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v7 → v8 adds `index_games_identity_sweep`.
 *
 * This asserts the QUERY PLAN, not just that the DDL ran. The index exists for one reason: SD01B measured
 * identity throughput stuck at ~11/s and named the cause as Room-side query churn, with the instruction to
 * NAME the query before shipping. Two queries share the predicate
 * `isPrimaryMember = 1 AND identityResolvedAtMillis IS NULL` — the enrichment driver (`unresolvedPrimaries`,
 * once per chunk drain) and the progress counter (`unresolvedPrimaryCount`, a Flow Room re-runs on every
 * write to `games`, with ≥2 live collectors during a pass). Unindexed, SQLite plans both as `SCAN games`,
 * plus `USE TEMP B-TREE FOR ORDER BY` for the driver — over 5,538 rows, ~111 times per pass.
 *
 * A test that only checked `PRAGMA index_list` would still pass if someone reordered the index columns and
 * silently reintroduced the temp B-tree sort, which is half the cost. So the plans are asserted directly.
 */
class MimirDatabaseMigration7To8SqlTest {
    private lateinit var connection: Connection

    @Before
    fun openV7Database() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE games (" +
                    "entryKey TEXT NOT NULL PRIMARY KEY, " +
                    "gameId TEXT NOT NULL, " +
                    "platformId TEXT NOT NULL, " +
                    "isPrimaryMember INTEGER NOT NULL, " +
                    "identityResolvedAtMillis INTEGER, " +
                    "canonicalTitle TEXT, " +
                    "sizeBytes INTEGER NOT NULL)",
            )
            // A spread wide enough that SQLite prefers an index when one exists.
            repeat(400) { i ->
                statement.executeUpdate(
                    "INSERT INTO games VALUES ('k$i', 'g$i', 'snes', 1, " +
                        (if (i % 3 == 0) "NULL" else "$i") + ", NULL, ${1_000 + i})",
                )
            }
        }
    }

    @After
    fun closeDatabase() = connection.close()

    @Test
    fun `the migration is additive and every row survives`() {
        val before = countRows()
        migrate()
        assertEquals("v7 to v8 is one CREATE INDEX — it must not touch a single row", before, countRows())
    }

    @Test
    fun `the index is created under the name GameEntity declares`() {
        migrate()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list('games')").use { result ->
                val names = buildList { while (result.next()) add(result.getString("name")) }
                assertTrue(
                    "identity-sweep index missing: $names. The name must match GameEntity exactly or " +
                        "Room's post-migration schema validation throws on upgrade.",
                    "index_games_identity_sweep" in names,
                )
            }
        }
    }

    @Test
    fun `the progress counter stops scanning the table`() {
        migrate()
        val plan = planFor(
            "SELECT COUNT(*) FROM games WHERE isPrimaryMember = 1 AND identityResolvedAtMillis IS NULL",
        )
        assertTrue("expected an index search, got: $plan", plan.contains("USING", ignoreCase = true))
        assertFalse("counter is still scanning the whole table: $plan", plan.contains("SCAN games"))
    }

    @Test
    fun `the enrichment driver seeks AND loses the temp B-tree sort`() {
        migrate()
        val plan = planFor(
            "SELECT * FROM games WHERE isPrimaryMember = 1 AND identityResolvedAtMillis IS NULL " +
                "ORDER BY sizeBytes ASC LIMIT 50",
        )
        assertTrue("expected an index search, got: $plan", plan.contains("USING", ignoreCase = true))
        assertFalse("driver is still scanning the whole table: $plan", plan.contains("SCAN games"))
        // The reason sizeBytes is the third index column. Drop it, or reorder the columns, and this returns.
        assertFalse(
            "the ORDER BY is sorting in a temp B-tree again — check the index column order: $plan",
            plan.contains("TEMP B-TREE", ignoreCase = true),
        )
    }

    private fun migrate() {
        connection.createStatement().use { it.executeUpdate(CREATE_GAMES_IDENTITY_SWEEP_INDEX_SQL) }
    }

    private fun planFor(sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery("EXPLAIN QUERY PLAN $sql").use { result ->
            buildString { while (result.next()) append(result.getString("detail")).append(' ') }
        }
    }

    private fun countRows(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM games").use { result ->
            result.next()
            result.getInt(1)
        }
    }
}
