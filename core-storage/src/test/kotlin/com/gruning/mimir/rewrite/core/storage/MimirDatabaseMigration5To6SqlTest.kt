package com.gruning.mimir.rewrite.core.storage

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MimirDatabaseMigration5To6SqlTest {
    private lateinit var connection: Connection

    @Before
    fun openV5Database() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE favorites (gameId TEXT NOT NULL PRIMARY KEY, favoritedAtMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate("INSERT INTO favorites VALUES ('existing-game', 77)")
        }
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    @Test
    fun `v5 to v6 is additive and creates the locked Home Shelf shape`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT favoritedAtMillis FROM favorites WHERE gameId = 'existing-game'")
                .use { result ->
                    assertTrue(result.next())
                    assertEquals(77L, result.getLong(1))
                }
            statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'home_shelf_items'",
            ).use { result ->
                assertTrue(result.next())
                assertTrue(result.getString(1).contains("CHECK(`itemKind` IN ('EMULATED_GAME', 'ANDROID_GAME'))"))
            }
            statement.executeQuery("PRAGMA index_list('home_shelf_items')").use { result ->
                val names = buildList {
                    while (result.next()) add(result.getString("name"))
                }
                assertTrue("profile/rank index missing: $names", "index_home_shelf_items_profile_rank" in names)
            }
        }

        val primaryKeyColumns = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info('home_shelf_items')").use { result ->
                buildList {
                    while (result.next()) {
                        val position = result.getInt("pk")
                        if (position > 0) add(position to result.getString("name"))
                    }
                }.sortedBy { it.first }.map { it.second }
            }
        }
        assertEquals(listOf("profileId", "itemKind", "itemId"), primaryKeyColumns)
    }

    @Test
    fun `composite identity isolates profile and kind and rejects Android apps`() {
        migrate()
        insert("local", "EMULATED_GAME", "same-id", 1_024L, 10L)
        insert("local", "ANDROID_GAME", "same-id", 2_048L, 20L)
        insert("guest", "EMULATED_GAME", "same-id", 1_024L, 30L)

        assertEquals(3, countRows())
        assertThrows(SQLException::class.java) {
            insert("local", "EMULATED_GAME", "same-id", 9_999L, 99L)
        }
        assertThrows(SQLException::class.java) {
            insert("local", "ANDROID_APP", "com.example.app", 3_072L, 40L)
        }
        assertEquals(3, countRows())
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(CREATE_HOME_SHELF_ITEMS_SQL)
            statement.executeUpdate(CREATE_HOME_SHELF_PROFILE_RANK_INDEX_SQL)
        }
    }

    private fun insert(profile: String, kind: String, id: String, rank: Long, addedAt: Long) {
        connection.prepareStatement("INSERT INTO home_shelf_items VALUES (?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, profile)
            statement.setString(2, kind)
            statement.setString(3, id)
            statement.setLong(4, rank)
            statement.setLong(5, addedAt)
            statement.executeUpdate()
        }
    }

    private fun countRows(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM home_shelf_items").use { result ->
            result.next()
            result.getInt(1)
        }
    }
}
