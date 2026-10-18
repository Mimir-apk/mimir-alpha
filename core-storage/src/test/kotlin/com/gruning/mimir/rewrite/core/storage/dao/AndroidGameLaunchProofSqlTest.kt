package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchProof
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchProof
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.storage.mapper.toEntity
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AndroidGameLaunchProofSqlTest {
    private lateinit var connection: Connection

    @Before
    fun openDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE launch_proofs (" +
                    "gameId TEXT, androidPackageName TEXT, outcome TEXT NOT NULL, " +
                    "timestampMillis INTEGER NOT NULL)",
            )
            statement.executeUpdate(
                "INSERT INTO launch_proofs VALUES " +
                    "(NULL, 'com.zeta.game', 'intent_sent', 400), " +
                    "(NULL, 'com.alpha.game', 'intent_sent', 400), " +
                    "(NULL, 'com.middle.game', 'intent_sent', 200), " +
                    "(NULL, 'com.middle.game', 'bridge_error', 900), " +
                    "(NULL, 'com.middle.game', 'intent_sent', 300), " +
                    "(NULL, 'com.failed.game', 'bridge_error', 1000), " +
                    "('emulated-game', NULL, 'intent_sent', 2000)",
            )
        }
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    @Test
    fun `Android recency is success-only latest-per-package and deterministic`() {
        val sql = RECENTLY_PLAYED_ANDROID_GAME_PROOFS_SQL.replace(":limit", "?")
        val rows = connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, 10)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.getString("androidPackageName") to result.getLong("lastPlayedMillis"))
                    }
                }
            }
        }

        assertEquals(
            listOf(
                "com.alpha.game" to 400L,
                "com.zeta.game" to 400L,
                "com.middle.game" to 300L,
            ),
            rows,
        )
    }

    @Test
    fun `Android recency honors the shelf-sized limit`() {
        val sql = RECENTLY_PLAYED_ANDROID_GAME_PROOFS_SQL.replace(":limit", "?")
        val packages = connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, 2)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString("androidPackageName")) }
            }
        }

        assertEquals(listOf("com.alpha.game", "com.zeta.game"), packages)
    }

    @Test
    fun `proof mappers populate exactly one typed target`() {
        val android = AndroidGameLaunchProof(
            packageName = AndroidPackageName("com.example.game"),
            outcome = LaunchOutcome.Failed(LaunchDispatchFailure.BRIDGE_ERROR),
            timestampMillis = 700L,
        ).toEntity()
        val emulated = LaunchProof(
            gameId = GameId("chrono-cross"),
            platformId = PlatformId("ps1"),
            emulatorId = EmulatorId("duckstation"),
            locator = "/roms/chrono.m3u",
            outcome = LaunchOutcome.IntentSent,
            timestampMillis = 800L,
        ).toEntity()

        assertNull(android.gameId)
        assertEquals("com.example.game", android.androidPackageName)
        assertEquals("BRIDGE_ERROR", android.outcome)
        assertEquals("chrono-cross", emulated.gameId)
        assertNull(emulated.androidPackageName)
        assertEquals("intent_sent", emulated.outcome)
    }
}
