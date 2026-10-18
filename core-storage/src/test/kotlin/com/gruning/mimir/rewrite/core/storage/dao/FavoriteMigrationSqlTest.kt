package com.gruning.mimir.rewrite.core.storage.dao

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FavoriteMigrationSqlTest {
    private lateinit var connection: Connection

    @Before
    fun openDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE games (gameId TEXT NOT NULL, isPrimaryMember INTEGER NOT NULL, duplicateGroupId TEXT)",
            )
            statement.executeUpdate(
                "CREATE TABLE favorites (gameId TEXT NOT NULL PRIMARY KEY, favoritedAtMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "INSERT INTO games VALUES ('group', 1, NULL), ('member', 0, 'group')",
            )
        }
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    @Test
    fun `promotion resolves member to primary and carries the explicit migration timestamp`() {
        assertEquals(1, promote("member", -2L))
        assertEquals(listOf("group" to -2L), favorites())
    }

    @Test
    fun `promotion never rewrites an existing primary or member Favorite timestamp`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO favorites VALUES ('member', 111)")
        }

        assertEquals(0, promote("group", -1L))
        assertEquals(0, promote("member", -2L))
        assertEquals(listOf("member" to 111L), favorites())
    }

    @Test
    fun `equal Favorite timestamps order by canonical game identity`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO games VALUES ('alpha', 1, NULL), ('zulu', 1, NULL)")
            statement.executeUpdate("INSERT INTO favorites VALUES ('zulu', 50), ('alpha', 50)")
        }
        val ordered = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT g.gameId FROM games g WHERE g.isPrimaryMember = 1 $FAVORITE_GAME_ORDER_BY_SQL",
            ).use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }

        assertEquals(listOf("alpha", "zulu", "group"), ordered)
    }

    private fun promote(gameId: String, timestamp: Long): Int {
        val safeId = gameId.replace("'", "''")
        val sql = PROMOTE_FAVORITE_GROUP_IF_ABSENT_SQL
            .replace(":favoritedAtMillis", timestamp.toString())
            .replace(":gameId", "'$safeId'")
        return connection.createStatement().use { it.executeUpdate(sql) }
    }

    private fun favorites(): List<Pair<String, Long>> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT gameId, favoritedAtMillis FROM favorites ORDER BY gameId").use { result ->
            buildList {
                while (result.next()) add(result.getString(1) to result.getLong(2))
            }
        }
    }
}
