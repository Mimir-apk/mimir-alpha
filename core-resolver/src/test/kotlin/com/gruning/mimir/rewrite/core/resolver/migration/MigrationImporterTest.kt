package com.gruning.mimir.rewrite.core.resolver.migration

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementAccount
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementGame
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleV1
import com.gruning.mimir.rewrite.core.model.migration.MigrationEmulatorOverride
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.migration.MigrationProfile
import com.gruning.mimir.rewrite.core.model.migration.MigrationSourceRoot
import com.gruning.mimir.rewrite.core.model.migration.MigrationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationImporterTest {

    private val sonic = MigrationGameRecord("dreamcast", "Sonic Adventure (USA).cdi", 734003200L, title = "Sonic Adventure", platformLabel = "Dreamcast")
    private val missing = MigrationGameRecord("gba", "Totally Made Up Rom.gba", 1024L, title = "Made Up", platformLabel = "GBA")
    private val hiddenGame = MigrationGameRecord("nes", "Some Hidden Game.nes", 40960L, title = "Hidden", platformLabel = "NES")

    /** A library that "contains" whatever gameIds we seed. */
    private class FakeLibrary(private val present: Set<String>) {
        suspend fun exists(id: GameId): Boolean = id.value in present
    }

    private class RecordingSeams : MigrationSeams {
        val favorited = mutableListOf<String>()
        val hiddenSet = mutableListOf<String>()
        val overrides = mutableListOf<Pair<String, String>>()
        var appliedTheme: String? = null
        val achievementAccounts = mutableListOf<MigrationAchievementAccount>()
        override suspend fun restoreAchievements(accounts: List<MigrationAchievementAccount>): Int {
            achievementAccounts += accounts
            return accounts.sumOf { it.games.size }
        }
        override suspend fun setFavorite(gameId: GameId) { favorited += gameId.value }
        override suspend fun setHidden(gameId: GameId) { hiddenSet += gameId.value }
        override suspend fun setEmulatorOverride(gameId: GameId, emulatorId: String) { overrides += gameId.value to emulatorId }
        override fun applyTheme(themeStorageKey: String) { appliedTheme = themeStorageKey }
    }

    @Test fun `resolve matches a game the library has and reports the rest`() = runBlocking {
        val matcher = MigrationMatcher(gameExists = { false })
        val sonicId = matcher.deriveGameId(sonic)
        val lib = FakeLibrary(setOf(sonicId.value))
        val m = MigrationMatcher(gameExists = lib::exists)

        val matched = m.resolve(sonic)
        assertTrue(matched is MatchOutcome.Matched)
        assertEquals(sonicId, (matched as MatchOutcome.Matched).gameId)

        assertTrue(m.resolve(missing) is MatchOutcome.Unmatched)
    }

    @Test fun `derive is deterministic for the same record`() {
        val m = MigrationMatcher(gameExists = { false })
        assertEquals(m.deriveGameId(sonic), m.deriveGameId(sonic.copy()))
    }

    @Test fun `historical backup identity resolves to the current alias before preview writes`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val historical = probe.deriveGameId(sonic)
        val current = GameId("logical-current-winner")
        val matcher = MigrationMatcher(
            resolveCurrentGameId = { gameId -> if (gameId == historical) current else gameId },
            gameExists = { it == current },
        )

        val outcome = matcher.resolve(sonic) as MatchOutcome.Matched
        assertEquals(current, outcome.gameId)
    }

    @Test fun `preview and apply produce an honest tally`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonic)
        val hiddenId = probe.deriveGameId(hiddenGame)
        val lib = FakeLibrary(setOf(sonicId.value, hiddenId.value)) // 'missing' deliberately absent

        val importer = MigrationImporter(MigrationMatcher(gameExists = lib::exists))
        val bundle = MigrationBundleV1(
            profile = MigrationProfile(displayName = "Sparda"),
            theme = MigrationTheme("gold"),
            sourceRoots = listOf(MigrationSourceRoot("SD card › Mimir › Roms", isPrimary = true)),
            favorites = listOf(sonic, missing),
            hidden = listOf(hiddenGame),
            emulatorOverrides = listOf(
                MigrationEmulatorOverride(sonic, "flycast"),     // game found + emulator recognized
                MigrationEmulatorOverride(missing, "mgba"),      // game not found
            ),
        )

        val plan = importer.preview(
            bundle,
            isKnownTheme = { it == "gold" },
            isEmulatorRecognized = { _, _, emulatorId -> emulatorId == "flycast" }, // flycast is a known candidate; mgba not
        )
        assertEquals(1, plan.favoritesMatched.size)
        assertEquals(1, plan.favoritesUnmatched.size)
        assertEquals(1, plan.hiddenMatched.size)
        assertEquals(1, plan.overridesApplicable.size)
        assertEquals("gold", plan.theme?.themeId)
        assertNull(plan.ignoredThemeId)
        assertEquals(listOf("SD card › Mimir › Roms"), plan.sourceRootHints)

        val seams = RecordingSeams()
        val result = importer.apply(plan, seams)
        assertEquals(1, result.favoritesRestored)
        assertEquals(1, result.favoritesNotFound)
        assertEquals(1, result.hiddenRestored)
        assertEquals(1, result.overridesRestored)
        assertEquals(1, result.overridesSkipped) // the 'missing' override
        assertTrue(result.themeApplied)
        assertEquals(listOf("SD card › Mimir › Roms"), result.sourceRootsToRegrant)
        assertTrue(result.notFoundTitles.any { it.contains("Made Up") })

        // The seams received exactly the matched writes, nothing dangling.
        assertEquals(listOf(sonicId.value), seams.favorited)
        assertEquals(listOf(hiddenId.value), seams.hiddenSet)
        assertEquals(listOf(sonicId.value to "flycast"), seams.overrides)
        assertEquals("gold", seams.appliedTheme)
    }

    @Test fun `an unknown theme is ignored and reported, never forced`() = runBlocking {
        val importer = MigrationImporter(MigrationMatcher(gameExists = { false }))
        val bundle = MigrationBundleV1(theme = MigrationTheme("chartreuse"))
        val plan = importer.preview(bundle, isKnownTheme = { it in setOf("gold", "default") }, isEmulatorRecognized = { _, _, _ -> true })
        assertNull(plan.theme)
        assertEquals("chartreuse", plan.ignoredThemeId)

        val seams = RecordingSeams()
        val result = importer.apply(plan, seams)
        assertFalse(result.themeApplied)
        assertNull(seams.appliedTheme)
        assertEquals("chartreuse", result.ignoredThemeId)
    }

    private fun achievementGame(id: Int, earned: Int = 2, total: Int = 10, icon: String? = "/Images/%06d.png".format(id)) =
        MigrationAchievementGame(raGameId = id, title = "Game $id", consoleName = "SNES", earned = earned, total = total, iconPath = icon)

    @Test fun `achievement progress is checked in the plan and handed to the seam`() = runBlocking {
        val importer = MigrationImporter(MigrationMatcher(gameExists = { false }))
        val bundle = MigrationBundleV1(
            achievements = listOf(
                MigrationAchievementAccount(
                    username = "  Player  ",
                    points = 500,
                    games = listOf(
                        achievementGame(1),
                        achievementGame(1, earned = 9), // a duplicate id keeps the first
                        achievementGame(0), // no id
                        achievementGame(3, earned = 11, total = 10), // more earned than the set holds
                        achievementGame(4, total = 0), // an empty set
                        achievementGame(5, icon = "https://elsewhere.example/x.png"), // not RA's relative shape
                    ),
                ),
                MigrationAchievementAccount(username = "   ", games = listOf(achievementGame(8))), // no name
                MigrationAchievementAccount(username = "nothing-usable", games = listOf(achievementGame(0))),
            ),
        )
        val plan = importer.preview(bundle, isKnownTheme = { true }, isEmulatorRecognized = { _, _, _ -> true })
        val account = plan.achievements.single()
        assertEquals("Player", account.username)
        assertEquals(500, account.points)
        assertEquals(listOf(1, 5), account.games.map { it.raGameId })
        assertEquals(2, account.games.first().earned)
        assertEquals("/Images/000001.png", account.games[0].iconPath)
        assertNull("only RA's own relative path survives", account.games[1].iconPath)

        val seams = RecordingSeams()
        val result = importer.apply(plan, seams)
        assertEquals(2, result.achievementGamesRestored)
        assertEquals(plan.achievements, seams.achievementAccounts)
    }

    @Test fun `the scores, the hardcore count and the award word are checked in the plan like every other number`() = runBlocking {
        val importer = MigrationImporter(MigrationMatcher(gameExists = { false }))
        val bundle = MigrationBundleV1(
            achievements = listOf(
                MigrationAchievementAccount(
                    username = "Player",
                    points = 500,
                    softcorePoints = 70,
                    games = listOf(
                        achievementGame(1).copy(earned = 4, earnedHardcore = 3, awardWord = "beaten-hardcore"),
                        achievementGame(2).copy(earned = 4, earnedHardcore = 99, awardWord = "x".repeat(200)), // past the earned count; not a word
                        achievementGame(3).copy(earned = 4, earnedHardcore = -5, awardWord = "  "), // below zero; blank
                    ),
                ),
                MigrationAchievementAccount(username = "Other", softcorePoints = -1, games = listOf(achievementGame(4))), // a negative score is none
            ),
        )
        val plan = importer.preview(bundle, isKnownTheme = { true }, isEmulatorRecognized = { _, _, _ -> true })
        val player = plan.achievements.first()
        assertEquals(500, player.points)
        assertEquals(70, player.softcorePoints)
        assertEquals(listOf(3, 4, 0), player.games.map { it.earnedHardcore })
        assertEquals(listOf("beaten-hardcore", null, null), player.games.map { it.awardWord })
        assertNull(plan.achievements.last().softcorePoints)
    }

    @Test fun `an oversized achievement section is cut to its bounds before it is handed on`() = runBlocking {
        val importer = MigrationImporter(MigrationMatcher(gameExists = { false }))
        val long = "x".repeat(1000)
        val many = (1..10_050).map { MigrationAchievementGame(raGameId = it, title = long, consoleName = long, earned = 1, total = 2) }
        val others = (1..12).map { MigrationAchievementAccount(username = "player$it", games = listOf(achievementGame(it))) }
        val bundle = MigrationBundleV1(
            achievements = listOf(
                MigrationAchievementAccount(username = "big", games = many),
                MigrationAchievementAccount(username = "n".repeat(65), games = listOf(achievementGame(1))), // no RA name is this long
            ) + others,
        )
        val plan = importer.preview(bundle, isKnownTheme = { true }, isEmulatorRecognized = { _, _, _ -> true })

        assertEquals("at most eight accounts", 8, plan.achievements.size)
        assertEquals("big", plan.achievements.first().username)
        assertTrue("the over-long name is no account", plan.achievements.none { it.username.length > 64 })
        val big = plan.achievements.first()
        assertEquals("at most ten thousand games an account", 10_000, big.games.size)
        assertTrue(big.games.all { it.title.length == 256 && it.consoleName.length == 64 })
    }

    @Test fun `a bundle with no achievements never calls the seam, and a seam that predates them restores none`() = runBlocking {
        val importer = MigrationImporter(MigrationMatcher(gameExists = { false }))
        val plan = importer.preview(MigrationBundleV1(), isKnownTheme = { true }, isEmulatorRecognized = { _, _, _ -> true })
        assertTrue(plan.achievements.isEmpty())
        val seams = RecordingSeams()
        assertEquals(0, importer.apply(plan, seams).achievementGamesRestored)
        assertTrue(seams.achievementAccounts.isEmpty())

        // The default of the interface: a seam written before achievements existed keeps nothing and reports zero.
        val oldSeam = object : MigrationSeams {
            override suspend fun setFavorite(gameId: GameId) = Unit
            override suspend fun setHidden(gameId: GameId) = Unit
            override suspend fun setEmulatorOverride(gameId: GameId, emulatorId: String) = Unit
            override fun applyTheme(themeStorageKey: String) = Unit
        }
        val withProgress = importer.preview(
            MigrationBundleV1(achievements = listOf(MigrationAchievementAccount("p", games = listOf(achievementGame(1))))),
            isKnownTheme = { true },
            isEmulatorRecognized = { _, _, _ -> true },
        )
        assertEquals(0, importer.apply(withProgress, oldSeam).achievementGamesRestored)
    }

    @Test fun `an empty bundle applies nothing and does not fail`() = runBlocking {
        val importer = MigrationImporter(MigrationMatcher(gameExists = { true }))
        val plan = importer.preview(MigrationBundleV1(), isKnownTheme = { true }, isEmulatorRecognized = { _, _, _ -> true })
        val result = importer.apply(plan, RecordingSeams())
        assertEquals(0, result.favoritesRestored)
        assertEquals(0, result.hiddenRestored)
        assertEquals(0, result.overridesRestored)
        assertFalse(result.themeApplied)
        assertTrue(result.notFoundTitles.isEmpty())
    }
}
