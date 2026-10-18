package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.storage.CREATE_TITLE_OVERRIDES_SQL
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves [TITLE_OVERRIDE_MATCH_SQL] finds a manual rename across every route a folded/aliased row can
 * carry one on: its own gameId, a collapse-fold sibling's gameId, and — critically, since a re-scan can
 * remap a game onto a NEW gameId (a hash newly proven, etc.) — an OLD gameId that no longer has a live
 * physical `games` row at all, reachable only through `game_id_aliases`.
 */
class TitleOverrideSqlTest {
    private lateinit var connection: Connection

    @Before
    fun openDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE games (" +
                    "entryKey TEXT PRIMARY KEY, gameId TEXT NOT NULL, duplicateGroupId TEXT, " +
                    "title TEXT NOT NULL, canonicalTitle TEXT, titleCatalogKey TEXT)",
            )
            statement.executeUpdate(
                "CREATE TABLE game_id_aliases (oldGameId TEXT PRIMARY KEY, logicalGameId TEXT NOT NULL)",
            )
            statement.executeUpdate(CREATE_TITLE_OVERRIDES_SQL)
        }
    }

    @After
    fun closeDatabase() = connection.close()

    private fun matches(displayedGameId: String): List<Pair<String, Long>> {
        val sql =
            "SELECT ov.title, ov.setAtMillis FROM title_overrides ov, games g " +
                "WHERE g.gameId = '$displayedGameId' AND $TITLE_OVERRIDE_MATCH_SQL " +
                "ORDER BY ov.setAtMillis DESC"
        return connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                buildList {
                    while (result.next()) add(result.getString("title") to result.getLong("setAtMillis"))
                }
            }
        }
    }

    @Test
    fun `an override set on the row's own gameId matches directly`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A', 'My Rename', 100)")
        }
        assertEquals(listOf("My Rename" to 100L), matches("A"))
    }

    @Test
    fun `an override set on a collapse-fold sibling surfaces through the folded primary`() {
        connection.createStatement().use { statement ->
            // Primary of a folded group is 'A'; 'B' is a region-variant sibling folded behind it.
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO games VALUES ('b', 'B', 'A', 'Foo (Europe)', NULL, NULL)")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('B', 'Renamed Before Fold', 100)")
        }
        assertEquals(listOf("Renamed Before Fold" to 100L), matches("A"))
    }

    @Test
    fun `an override set on a since-superseded gameId survives purely through the alias chain`() {
        connection.createStatement().use { statement ->
            // 'A-old' was the gameId at the time of the rename; a later identity strengthening (e.g. a
            // hash newly proven) remapped the SAME physical game onto 'A-new'. The old physical row no
            // longer exists at all under 'A-old' — only the alias record remembers the link.
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A-new', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-old', 'lg:one')")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-new', 'lg:one')")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A-old', 'Renamed Before Remap', 100)")
        }
        assertEquals(listOf("Renamed Before Remap" to 100L), matches("A-new"))
    }

    // ── PIN: read reach (TITLE_OVERRIDE_MATCH_SQL) vs delete reach (the literal clearTitleOverrideForCurrent
    // SQL shape, `DELETE FROM title_overrides WHERE gameId IN (:currentId)`) must agree, or "reset to
    // detected name" silently no-ops. These reproduce the pre-fix defect directly against the DAO's own
    // SQL: the fix moves the fix into rekeyRoomState (production code), so these stay red-by-construction
    // FOREVER on the unconsolidated state they build — that state simply must never reach a live database
    // once rekeyRoomState consolidates title_overrides on every remap, mirroring emulator_overrides.

    @Test
    fun `PIN — a clear issued at the current id after a remap deletes nothing while the stale override still matches`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A-new', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-old', 'lg:one')")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-new', 'lg:one')")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A-old', 'Renamed Before Remap', 100)")

            // clearTitleOverrideForCurrent('A-new'): resolveCurrentGameId('A-new') is 'A-new' itself (it is
            // already current), so this is the exact DELETE the production code issues.
            val deleted = statement.executeUpdate("DELETE FROM title_overrides WHERE gameId IN ('A-new')")
            assertEquals(
                "the override is still keyed at the pre-remap id, so a current-id-only DELETE cannot reach it",
                0,
                deleted,
            )
        }
        assertEquals(
            "reset to detected name' silently no-ops: the stale override still outranks the detected title",
            listOf("Renamed Before Remap" to 100L),
            matches("A-new"),
        )
    }

    @Test
    fun `PIN — a clear issued at the current id after a fold cannot reach an override kept on the folded sibling`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO games VALUES ('b', 'B', 'A', 'Foo (Europe)', NULL, NULL)")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('B', 'Renamed Before Fold', 100)")

            val deleted = statement.executeUpdate("DELETE FROM title_overrides WHERE gameId IN ('A')")
            assertEquals(0, deleted)
        }
        assertEquals(listOf("Renamed Before Fold" to 100L), matches("A"))
    }

    @Test
    fun `PIN — renaming again after a remap then clearing resurrects the stale pre-remap rename`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A-new', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-old', 'lg:one')")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-new', 'lg:one')")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A-old', 'Stale Pre-Remap Rename', 100)")
            // The player renames AGAIN after the remap; setTitleOverrideForCurrent writes at 'A-new'.
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A-new', 'Fresh Post-Remap Rename', 200)")

            statement.executeUpdate("DELETE FROM title_overrides WHERE gameId IN ('A-new')")
        }
        // The fresh row is gone, but the stale one — never reachable by a current-id-only clear — remains,
        // and TITLE_OVERRIDE_MATCH_SQL's most-recently-set tie-break now resurrects it as the display title.
        assertEquals(listOf("Stale Pre-Remap Rename" to 100L), matches("A-new"))
    }

    /**
     * The fix's POST-CONDITION at the SQL level: once a title_overrides row is consolidated onto the
     * CURRENT id — which is exactly what the new `rekeyRoomState` branch does on every remap, mirroring
     * the proven `emulator_overrides` consolidation in the same function — a current-id-only clear works
     * correctly and nothing can resurrect. This does not invoke the Kotlin `rekeyRoomState` function (no
     * JVM-level Room harness exists for it in this codebase — true for every existing branch of that
     * function, not just this one); it proves the invariant the new branch is responsible for establishing.
     */
    @Test
    fun `once consolidated onto the current id, a current-id clear removes the override and nothing resurrects`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A-new', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-old', 'lg:one')")
            statement.executeUpdate("INSERT INTO game_id_aliases VALUES ('A-new', 'lg:one')")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A-old', 'Renamed Before Remap', 100)")

            // rekeyRoomState's new branch, simulated: move the chosen row onto targetGameId, drop the source.
            statement.executeUpdate(
                "INSERT INTO title_overrides VALUES ('A-new', 'Renamed Before Remap', 100)",
            )
            statement.executeUpdate("DELETE FROM title_overrides WHERE gameId IN ('A-old')")

            val deleted = statement.executeUpdate("DELETE FROM title_overrides WHERE gameId IN ('A-new')")
            assertEquals(1, deleted)
        }
        assertTrue(matches("A-new").isEmpty())
    }

    @Test
    fun `an unrelated override never matches`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO games VALUES ('z', 'Z', NULL, 'Bar', NULL, NULL)")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('Z', 'Unrelated Rename', 100)")
        }
        assertTrue(matches("A").isEmpty())
    }

    @Test
    fun `the most recently set override wins when two folded siblings each carry one`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('a', 'A', NULL, 'Foo', NULL, NULL)")
            statement.executeUpdate("INSERT INTO games VALUES ('b', 'B', 'A', 'Foo (Europe)', NULL, NULL)")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A', 'Older Rename', 100)")
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('B', 'Newer Rename', 200)")
        }
        val ranked = matches("A")
        assertEquals(2, ranked.size)
        assertEquals("Newer Rename", ranked.first().first)
    }

    /**
     * `manualTitleOverride` is projected as its OWN column (not folded into SQL's `title` =
     * `COALESCE(canonicalTitle, title)`) — final precedence over provider/scan is applied in Kotlin by
     * [com.gruning.mimir.rewrite.core.resolver.library.libraryDisplayTitle], tested there. This proves the
     * SQL side of that contract: the column is present, correct, and disappears on reset (delete), leaving
     * `title` itself — the scan/provider truth — completely untouched by either state.
     */
    @Test
    fun `the full game row projection surfaces manualTitleOverride independently of title, and reset clears only it`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE favorites (gameId TEXT PRIMARY KEY, favoritedAtMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE emulator_overrides (gameId TEXT PRIMARY KEY, emulatorId TEXT NOT NULL, " +
                    "setAtMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE hidden_items (gameId TEXT PRIMARY KEY, hidden INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE launch_proofs (gameId TEXT, outcome TEXT NOT NULL, timestampMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE title_metadata_cache (catalogKey TEXT, source TEXT, canonicalTitle TEXT, " +
                    "description TEXT, cacheExpiresAt INTEGER)",
            )
            statement.executeUpdate("ALTER TABLE games ADD COLUMN region TEXT")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN version TEXT")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN sourceDocumentUri TEXT")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN managedPath TEXT")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN platformId TEXT NOT NULL DEFAULT 'ps1'")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN isPrimaryMember INTEGER NOT NULL DEFAULT 1")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN duplicateGroupKind TEXT")
            statement.executeUpdate("ALTER TABLE games ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 0")

            statement.executeUpdate(
                "INSERT INTO games (entryKey, gameId, duplicateGroupId, title, canonicalTitle) VALUES " +
                    "('a', 'A', NULL, 'raw scanned title', 'Provider Canonical Title')",
            )
            statement.executeUpdate("INSERT INTO title_overrides VALUES ('A', 'Player Chosen Title', 100)")

            val (titleWithOverride, overrideWithOverride) = projectedTitleAndOverride(statement)
            assertEquals("Provider Canonical Title", titleWithOverride)
            assertEquals("Player Chosen Title", overrideWithOverride)

            statement.executeUpdate("DELETE FROM title_overrides WHERE gameId = 'A'")
            val (titleAfterReset, overrideAfterReset) = projectedTitleAndOverride(statement)
            assertEquals("Provider Canonical Title", titleAfterReset)
            assertNull(overrideAfterReset)
        }
    }

    private fun projectedTitleAndOverride(statement: java.sql.Statement): Pair<String?, String?> {
        val sql = GAME_ROW_BY_ID_SQL.replace(":gameId", "'A'")
        return statement.executeQuery(sql).use { result ->
            assertTrue(result.next())
            result.getString("title") to result.getString("manualTitleOverride")
        }
    }
}
