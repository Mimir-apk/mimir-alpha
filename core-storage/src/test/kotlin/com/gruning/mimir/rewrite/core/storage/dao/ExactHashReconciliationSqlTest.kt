package com.gruning.mimir.rewrite.core.storage.dao

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExactHashReconciliationSqlTest {
    private lateinit var connection: Connection
    private val raw = "raw-sha1:" + "a".repeat(40)

    @Before
    fun openDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE games (
                    entryKey TEXT PRIMARY KEY NOT NULL,
                    gameId TEXT NOT NULL,
                    platformId TEXT NOT NULL,
                    contentKind TEXT NOT NULL,
                    locationKind TEXT NOT NULL,
                    contentHash TEXT,
                    isPrimaryMember INTEGER NOT NULL,
                    duplicateGroupId TEXT,
                    duplicateGroupKind TEXT,
                    collapseKey TEXT,
                    sourceRootUri TEXT NOT NULL,
                    relativePath TEXT NOT NULL,
                    managedPath TEXT
                )
                """,
            )
            statement.executeUpdate("CREATE INDEX index_games_gameId ON games(gameId)")
            statement.executeUpdate("CREATE INDEX index_games_duplicateGroupId ON games(duplicateGroupId)")
            statement.executeUpdate(
                "CREATE INDEX index_games_sourceRootUri_entryKey ON games(sourceRootUri, entryKey)",
            )
        }
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    @Test
    fun `candidate SQL admits only same-platform primary exact tagged sha1 games`() {
        insert("rootA::a", "a", "ps1", raw, 1)
        insert("rootB::b", "b", "ps1", raw, 1)
        insert("rootC::cross", "cross", "ps2", raw, 1)
        insert("rootD::track", "track", "ps1", raw, 0)
        insert("rootE::canonical-a", "canonical-a", "ps1", "a".repeat(40), 1)
        insert("rootF::canonical-b", "canonical-b", "ps1", "a".repeat(40), 1)
        val malformed = "raw-sha1:" + "g" + "a".repeat(39)
        insert("rootG::bad-a", "bad-a", "ps1", malformed, 1)
        insert("rootH::bad-b", "bad-b", "ps1", malformed, 1)

        val ids = connection.createStatement().use { statement ->
            statement.executeQuery(EXACT_HASH_CANDIDATES_SQL).use { result ->
                buildList { while (result.next()) add(result.getString("gameId")) }
            }
        }
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `folded loser is absent from the next candidate read`() {
        insert("rootA::a", "a", "ps1", raw, 1)
        insert("rootB::b", "b", "ps1", raw, 1)
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "UPDATE games SET isPrimaryMember = 0, duplicateGroupId = 'a', " +
                    "duplicateGroupKind = 'EXACT_HASH' WHERE gameId = 'b' AND isPrimaryMember = 1",
            )
        }
        val count = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM ($EXACT_HASH_CANDIDATES_SQL)").use { result ->
                result.next()
                result.getInt(1)
            }
        }
        assertEquals(0, count)
    }

    @Test
    fun `a grouped primary cannot let its playlist or track hash stand for the whole game`() {
        insert("rootA::playlist", "multi", "ps1", raw, 1, "multi", "MULTI_DISC")
        insert("rootA::track", "multi", "ps1", null, 0, "multi", "MULTI_DISC")
        insert("rootB::single", "single", "ps1", raw, 1)
        val count = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM ($EXACT_HASH_CANDIDATES_SQL)").use { result ->
                result.next()
                result.getInt(1)
            }
        }
        assertEquals(0, count)
    }

    @Test
    fun `cross-root dissolve resets only terminal exact rows and preserves subordinate grouping`() {
        insert("rootA::winner", "winner", "ps1", raw, 1, null, "EXACT_HASH")
        insert("rootB::loser", "loser", "ps1", raw, 0, "winner", "EXACT_HASH")
        insert("rootB::track", "loser", "ps1", null, 0, "loser", "MULTI_DISC")
        insert("rootC::other", "other", "ps1", raw, 1, null, "EXACT_HASH")

        val groupIds = terminalGroupIds("rootB")
        assertEquals(setOf("winner", "loser"), groupIds.toSet())

        val logicalGameIds = logicalGameIds(groupIds)
        assertEquals(setOf("winner", "loser"), logicalGameIds.toSet())

        assertEquals(2, dissolve(groupIds))
        assertEquals(1, restore(logicalGameIds))

        assertRow("rootA::winner", 1, null, null)
        assertRow("rootB::loser", 1, "loser", "MULTI_DISC")
        assertRow("rootB::track", 0, "loser", "MULTI_DISC")
        assertRow("rootC::other", 1, null, "EXACT_HASH")
    }

    @Test
    fun `cross-root catalog winner loss restores a nested multi-disc survivor`() {
        insert("rootA::winner", "winner", "ps1", null, 1, null, "CATALOG_TITLE_ID")
        insert("rootB::playlist", "playlist", "ps1", null, 0, "winner", "CATALOG_TITLE_ID")
        insert("rootB::disc-1", "playlist", "ps1", null, 0, "playlist", "MULTI_DISC")
        insert("rootB::disc-2", "playlist", "ps1", null, 0, "playlist", "MULTI_DISC")
        insert("rootC::unrelated", "unrelated", "ps1", null, 1, null, "CATALOG_TITLE_ID")

        val groupIds = terminalGroupIds("rootA")
        assertEquals(setOf("winner", "playlist"), groupIds.toSet())

        val logicalGameIds = logicalGameIds(groupIds)
        assertEquals(setOf("winner", "playlist"), logicalGameIds.toSet())

        assertEquals(2, dissolve(groupIds))
        assertEquals(1, restore(logicalGameIds))

        assertRow("rootA::winner", 1, null, null)
        assertRow("rootB::playlist", 1, "playlist", "MULTI_DISC")
        assertRow("rootB::disc-1", 0, "playlist", "MULTI_DISC")
        assertRow("rootB::disc-2", 0, "playlist", "MULTI_DISC")
        assertRow("rootC::unrelated", 1, null, "CATALOG_TITLE_ID")
    }

    @Test
    fun `terminal graph closure dissolves every layer of a historical catalog winner chain`() {
        insert("rootC::winner", "c", "ps1", null, 1, null, "CATALOG_TITLE_ID")
        insert("rootA::former-winner", "a", "ps1", null, 0, "c", "CATALOG_TITLE_ID")
        insert("rootB::old-loser", "b", "ps1", null, 0, "a", "CATALOG_TITLE_ID")
        insert("rootB::disc", "b", "ps1", null, 0, "b", "MULTI_DISC")
        insert("rootD::unrelated", "d", "ps1", null, 1, null, "CATALOG_TITLE_ID")

        val groupIds = terminalGroupIds("rootB")
        assertEquals(setOf("a", "b", "c"), groupIds.toSet())
        val logicalGameIds = logicalGameIds(groupIds)
        assertEquals(setOf("a", "b", "c"), logicalGameIds.toSet())

        assertEquals(3, dissolve(groupIds))
        assertEquals(1, restore(logicalGameIds))

        assertRow("rootC::winner", 1, null, null)
        assertRow("rootA::former-winner", 1, null, null)
        assertRow("rootB::old-loser", 1, "b", "MULTI_DISC")
        assertRow("rootB::disc", 0, "b", "MULTI_DISC")
        assertRow("rootD::unrelated", 1, null, "CATALOG_TITLE_ID")
    }

    @Test
    fun `catalog candidate query cannot overwrite a terminal exact group`() {
        insert("rootA::exact", "exact", "ps1", raw, 1, null, "EXACT_HASH")
        insert("rootB::catalog", "catalog", "ps1", null, 1, null, null)
        connection.createStatement().use { statement ->
            statement.executeUpdate("UPDATE games SET collapseKey = 'shared' WHERE gameId IN ('exact', 'catalog')")
        }
        val ids = connection.createStatement().use { statement ->
            statement.executeQuery(RESOLVED_COLLAPSE_PRIMARIES_SQL).use { result ->
                buildList { while (result.next()) add(result.getString("gameId")) }
            }
        }
        assertEquals(listOf("catalog"), ids)
    }

    @Test
    fun `exact source seeds do not claim a colon-suffixed neighboring root`() {
        insert(
            entryKey = "root:::neighbor",
            gameId = "neighbor",
            platformId = "ps1",
            contentHash = raw,
            isPrimary = 1,
            groupKind = "EXACT_HASH",
            sourceRootUri = "root:",
            managedPath = "root:/neighbor",
            locationKind = "MANAGED_PATH",
        )

        assertEquals(emptyList<String>(), terminalGroupIds("root"))
        assertEquals(listOf("neighbor"), terminalGroupIds("root:"))
        val seedPlan = terminalSeedQueryPlan("root:")
        assertTrue(
            "seed plan=$seedPlan",
            seedPlan.any { "SEARCH" in it && "index_games_sourceRootUri_entryKey" in it },
        )
        assertTrue("seed plan=$seedPlan", seedPlan.none { it.startsWith("SCAN g") })
        assertTrue("seed plan=$seedPlan", seedPlan.none { it.startsWith("SCAN t") })
        assertTrue("seed plan=$seedPlan", seedPlan.any { "MULTI-INDEX OR" in it })
        assertTrue("seed plan=$seedPlan", seedPlan.any { "index_games_gameId" in it })
        assertTrue("seed plan=$seedPlan", seedPlan.any { "index_games_duplicateGroupId" in it })
        val expansionPlan = terminalExpansionQueryPlan(listOf("neighbor"))
        assertTrue(expansionPlan.any { it.contains("MULTI-INDEX OR") })
        assertTrue(expansionPlan.any { it.contains("index_games_gameId") })
        assertTrue(expansionPlan.any { it.contains("index_games_duplicateGroupId") })
    }

    private fun insert(
        entryKey: String,
        gameId: String,
        platformId: String,
        contentHash: String?,
        isPrimary: Int,
        groupId: String? = null,
        groupKind: String? = null,
        sourceRootUri: String = entryKey.substringBefore("::"),
        managedPath: String? = null,
        locationKind: String = "SAF_TREE_GRANT",
    ) {
        connection.prepareStatement(
            "INSERT INTO games " +
                "(entryKey, gameId, platformId, contentKind, locationKind, contentHash, isPrimaryMember, " +
                "duplicateGroupId, duplicateGroupKind, sourceRootUri, relativePath, managedPath) " +
                "VALUES (?, ?, ?, 'GAME', ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, entryKey)
            statement.setString(2, gameId)
            statement.setString(3, platformId)
            statement.setString(4, locationKind)
            statement.setString(5, contentHash)
            statement.setInt(6, isPrimary)
            statement.setString(7, groupId)
            statement.setString(8, groupKind)
            statement.setString(9, sourceRootUri)
            statement.setString(10, entryKey)
            statement.setString(11, managedPath)
            statement.executeUpdate()
        }
    }

    private fun assertRow(entryKey: String, primary: Int, groupId: String?, groupKind: String?) {
        connection.prepareStatement("SELECT * FROM games WHERE entryKey = ?").use { statement ->
            statement.setString(1, entryKey)
            statement.executeQuery().use { result ->
                result.next()
                assertEquals(primary, result.getInt("isPrimaryMember"))
                assertEquals(groupId, result.getString("duplicateGroupId"))
                assertEquals(groupKind, result.getString("duplicateGroupKind"))
            }
        }
    }

    private fun terminalGroupIds(rootUri: String): List<String> {
        val ids = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        fun discover(links: List<TerminalGroupLink>) {
            links.forEach { link ->
                if (ids.add(link.gameId)) pending.addLast(link.gameId)
                link.duplicateGroupId?.let { parent ->
                    if (ids.add(parent)) pending.addLast(parent)
                }
            }
        }

        val seedSql = TERMINAL_GROUP_SEEDS_FOR_EXACT_SOURCE_SQL.replace(":rootUri", "?")
        val rootParameterCount = TERMINAL_GROUP_SEEDS_FOR_EXACT_SOURCE_SQL
            .windowed(":rootUri".length)
            .count { it == ":rootUri" }
        connection.prepareStatement(seedSql).use { statement ->
            repeat(rootParameterCount) { index -> statement.setString(index + 1, rootUri) }
            statement.executeQuery().use { result -> discover(result.terminalGroupLinks()) }
        }
        while (pending.isNotEmpty()) {
            val frontier = buildList {
                while (size < DOUBLE_IN_QUERY_CHUNK_SIZE && pending.isNotEmpty()) {
                    add(pending.removeFirst())
                }
            }
            connection.prepareStatement(
                TERMINAL_GROUP_LINKS_FOR_IDS_SQL.replace(":groupIds", placeholders(frontier.size)),
            ).use { statement ->
                statement.bind(frontier, repetitions = 2)
                statement.executeQuery().use { result -> discover(result.terminalGroupLinks()) }
            }
        }
        return ids.sorted()
    }

    private fun java.sql.ResultSet.terminalGroupLinks(): List<TerminalGroupLink> = buildList {
        while (next()) {
            add(
                TerminalGroupLink(
                    gameId = getString("gameId"),
                    duplicateGroupId = getString("duplicateGroupId"),
                ),
            )
        }
    }

    private fun terminalSeedQueryPlan(rootUri: String): List<String> {
        val explained = "EXPLAIN QUERY PLAN " +
            TERMINAL_GROUP_SEEDS_FOR_EXACT_SOURCE_SQL.replace(":rootUri", "?")
        val rootParameterCount = TERMINAL_GROUP_SEEDS_FOR_EXACT_SOURCE_SQL
            .windowed(":rootUri".length)
            .count { it == ":rootUri" }
        return connection.prepareStatement(explained).use { statement ->
            repeat(rootParameterCount) { index -> statement.setString(index + 1, rootUri) }
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString("detail")) }
            }
        }
    }

    private fun terminalExpansionQueryPlan(groupIds: List<String>): List<String> =
        connection.prepareStatement(
            "EXPLAIN QUERY PLAN " +
                TERMINAL_GROUP_LINKS_FOR_IDS_SQL.replace(":groupIds", placeholders(groupIds.size)),
        ).use { statement ->
            statement.bind(groupIds, repetitions = 2)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString("detail")) }
            }
        }

    private fun logicalGameIds(groupIds: List<String>): List<String> =
        connection.prepareStatement(
            EXACT_LOGICAL_GAME_IDS_SQL.replace(":groupIds", placeholders(groupIds.size)),
        ).use { statement ->
            statement.bind(groupIds, repetitions = 2)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }

    private fun dissolve(groupIds: List<String>): Int =
        connection.prepareStatement(
            DISSOLVE_EXACT_GROUPS_SQL.replace(":groupIds", placeholders(groupIds.size)),
        ).use { statement ->
            statement.bind(groupIds, repetitions = 2)
            statement.executeUpdate()
        }

    private fun restore(logicalGameIds: List<String>): Int =
        connection.prepareStatement(
            RESTORE_SUBORDINATE_GROUP_KINDS_SQL.replace(
                ":logicalGameIds",
                placeholders(logicalGameIds.size),
            ),
        ).use { statement ->
            statement.bind(logicalGameIds, repetitions = 1)
            statement.executeUpdate()
        }

    private fun placeholders(size: Int): String =
        List(size) { "?" }.joinToString()

    private fun java.sql.PreparedStatement.bind(values: List<String>, repetitions: Int) {
        var parameter = 1
        repeat(repetitions) {
            values.forEach { value -> setString(parameter++, value) }
        }
    }
}
