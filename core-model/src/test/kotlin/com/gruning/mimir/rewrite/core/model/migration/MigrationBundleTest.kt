package com.gruning.mimir.rewrite.core.model.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationBundleTest {

    private fun sampleBundle() = MigrationBundleV1(
        producedByApp = "com.gruning.mimir",
        producedByVersion = "9.9.9",
        producedAtMillis = 1_751_731_200_000L,
        profile = MigrationProfile(localProfileId = "local", displayName = "Sparda"),
        theme = MigrationTheme(themeId = "gold"),
        sourceRoots = listOf(MigrationSourceRoot("SD card › Mimir › Roms", "content://x", isPrimary = true)),
        favorites = listOf(
            MigrationGameRecord("dreamcast", "Sonic Adventure (USA).cdi", 734003200L, title = "Sonic Adventure", platformLabel = "Dreamcast"),
        ),
        hidden = listOf(
            MigrationGameRecord("nes", "Dirty Hack.nes", 40960L, title = "Dirty Hack", platformLabel = "NES"),
        ),
        emulatorOverrides = listOf(
            MigrationEmulatorOverride(MigrationGameRecord("dreamcast", "Sonic Adventure (USA).cdi", 734003200L), "flycast"),
        ),
    )

    @Test fun `round-trips through encode then parse`() {
        val encoded = MigrationBundleCodec.encode(sampleBundle())
        val parsed = MigrationBundleCodec.parse(encoded).getOrThrow()
        assertEquals(sampleBundle(), parsed)
        assertEquals("gold", parsed.theme?.themeId)
        assertEquals(1, parsed.favorites.size)
        assertEquals("flycast", parsed.emulatorOverrides.single().emulatorId)
    }

    @Test fun `tolerates unknown extra fields (forward-compat)`() {
        // A future producer adds fields this reader doesn't know — must still parse.
        val json = """
            {
              "bundleVersion": 1,
              "kind": "mimir.migration.bundle",
              "producedByApp": "com.gruning.mimir",
              "futureField": {"nested": true},
              "playHistory": [{"anything": 1}],
              "favorites": [
                {"platformId":"gba","fileName":"Pokemon - Emerald Version.gba","sizeBytes":16777216,"unknownPerRecord":"x","title":"Pokémon Emerald","platformLabel":"GBA"}
              ]
            }
        """.trimIndent()
        val parsed = MigrationBundleCodec.parse(json).getOrThrow()
        assertEquals(1, parsed.favorites.size)
        assertEquals("gba", parsed.favorites.single().platformId)
    }

    @Test fun `missing optional arrays default to empty`() {
        val json = """{"bundleVersion":1,"kind":"mimir.migration.bundle"}"""
        val parsed = MigrationBundleCodec.parse(json).getOrThrow()
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.emulatorOverrides.isEmpty())
        assertTrue(parsed.sourceRoots.isEmpty())
        assertNull(parsed.theme)
    }

    @Test fun `achievement progress round-trips and an older bundle without it still parses`() {
        val withProgress = sampleBundle().copy(
            achievements = listOf(
                MigrationAchievementAccount(
                    username = "Player One",
                    points = 1234,
                    games = listOf(
                        MigrationAchievementGame(raGameId = 7, title = "Game 7", consoleName = "Nintendo 64", earned = 3, total = 69, lastAwardedAtMillis = 1_700_000_000_000L, iconPath = "/Images/000007.png"),
                    ),
                ),
            ),
        )
        val parsed = MigrationBundleCodec.parse(MigrationBundleCodec.encode(withProgress)).getOrThrow()
        assertEquals(withProgress, parsed)
        assertEquals(1, parsed.achievements.single().games.size)

        // A bundle written before achievements existed has none, and nothing else about it changes.
        val older = MigrationBundleCodec.parse(MigrationBundleCodec.encode(sampleBundle())).getOrThrow()
        assertTrue(older.achievements.isEmpty())
        assertEquals(1, older.favorites.size)
        // An account carries no credential field at all.
        assertTrue(!MigrationBundleCodec.encode(withProgress).contains("apiKey", ignoreCase = true))
    }

    @Test fun `both scores, the hardcore count and the award word round-trip, and a bundle without them reads as none`() {
        val full = sampleBundle().copy(
            achievements = listOf(
                MigrationAchievementAccount(
                    username = "Player One",
                    points = 1234,
                    softcorePoints = 56,
                    games = listOf(
                        MigrationAchievementGame(raGameId = 7, title = "Game 7", earned = 69, total = 69, earnedHardcore = 60, awardWord = "mastered"),
                    ),
                ),
            ),
        )
        val parsed = MigrationBundleCodec.parse(MigrationBundleCodec.encode(full)).getOrThrow()
        assertEquals(full, parsed)

        val older = """{"bundleVersion":1,"kind":"$BUNDLE_KIND","achievements":[{"username":"A","points":5,"games":[{"raGameId":7,"title":"G","earned":1,"total":2}]}]}"""
        val account = MigrationBundleCodec.parse(older).getOrThrow().achievements.single()
        assertNull(account.softcorePoints)
        assertEquals(0, account.games.single().earnedHardcore)
        assertNull(account.games.single().awardWord)
    }

    @Test fun `rejects a future major version`() {
        val json = """{"bundleVersion":2,"kind":"mimir.migration.bundle"}"""
        val result = MigrationBundleCodec.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MigrationBundleException)
    }

    @Test fun `rejects a file that is not a migration bundle`() {
        val json = """{"bundleVersion":1,"kind":"something.else"}"""
        val result = MigrationBundleCodec.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MigrationBundleException)
    }

    @Test fun `rejects malformed json as a failure, not a crash`() {
        val result = MigrationBundleCodec.parse("{ not json ")
        assertTrue(result.isFailure)
    }

    @Test fun `rejects json that omits kind or version (no silent empty-bundle no-op)`() {
        // Without @Required these would default to a valid-looking empty bundle → a silent "0 restored".
        listOf("{}", """{"foo":1}""", """{"kind":"mimir.migration.bundle"}""", """{"bundleVersion":1}""").forEach { json ->
            val result = MigrationBundleCodec.parse(json)
            assertTrue("expected failure for: $json", result.isFailure)
            assertTrue(result.exceptionOrNull() is MigrationBundleException)
        }
    }
}
