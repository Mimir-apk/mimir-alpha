package com.gruning.mimir.rewrite.core.storage.dao

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LogicalGameMemberSqlTest {
    private lateinit var connection: Connection

    @Before
    fun openDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE games (" +
                    "entryKey TEXT PRIMARY KEY, gameId TEXT NOT NULL, duplicateGroupId TEXT, " +
                    "discNumber INTEGER NOT NULL, displayName TEXT NOT NULL, sizeBytes INTEGER NOT NULL, " +
                    "sourceRootUri TEXT NOT NULL, relativePath TEXT NOT NULL, " +
                    "platformId TEXT NOT NULL, sourceDocumentUri TEXT, managedPath TEXT, " +
                    "isPrimaryMember INTEGER NOT NULL, availability TEXT NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE game_id_aliases (" +
                    "oldGameId TEXT PRIMARY KEY, logicalGameId TEXT NOT NULL)",
            )
            statement.executeUpdate(
                "INSERT INTO games VALUES " +
                    "('a', 'A', NULL, 1, 'Set A Disc 1.chd', 10, 'root', 'a', 'ps1', NULL, '/a', 1, 'AVAILABLE')," +
                    "('c', 'C', 'A', 1, 'Set C Disc 1.chd', 11, 'root', 'c', 'ps1', NULL, '/c', 0, 'AVAILABLE')," +
                    "('d', 'D', 'C', 2, 'Set C Disc 2.chd', 12, 'root', 'd', 'ps1', NULL, '/d', 0, 'AVAILABLE')",
            )
            statement.executeUpdate(
                "INSERT INTO game_id_aliases VALUES ('A', 'lg:one'), ('C', 'lg:one'), ('D', 'lg:one')",
            )
        }
    }

    @After
    fun closeDatabase() = connection.close()

    @Test
    fun `member and hash queries close nested physical groups through aliases`() {
        val memberSql = GAME_MEMBERS_READ_SQL.replace(":gameId", "'A'")
        val members = connection.createStatement().use { statement ->
            statement.executeQuery(memberSql).use { result ->
                buildList {
                    while (result.next()) add(result.getString("entryKey"))
                }
            }
        }
        assertEquals(listOf("a", "c", "d"), members)

        val hashSql = GAME_HASH_SOURCE_SQL.replace(":gameId", "'D'")
        val hashPath = connection.createStatement().use { statement ->
            statement.executeQuery(hashSql).use { result ->
                result.next()
                result.getString("managedPath")
            }
        }
        assertEquals("/a", hashPath)
    }

    @Test
    fun `row projection count includes every nested logical alias`() {
        val sql =
            "SELECT COUNT(*) AS memberCount, COUNT(DISTINCT m.discNumber) AS discCount " +
                "FROM games g JOIN games m ON $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL " +
                "WHERE g.gameId = 'A'"
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                result.next()
                assertEquals(3, result.getInt("memberCount"))
                assertEquals(2, result.getInt("discCount"))
            }
        }
    }
}
