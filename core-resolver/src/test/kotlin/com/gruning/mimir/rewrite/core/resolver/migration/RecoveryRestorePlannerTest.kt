package com.gruning.mimir.rewrite.core.resolver.migration

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RECORDS
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveIdentityIndex
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveRestoreSnapshot
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedEmulatorOverride
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedFavorite
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedGameEvidence
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedHidden
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedShelfItem
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreExcludedCategory
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRecordCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreShelfRankOverflowException
import com.gruning.mimir.rewrite.core.model.recovery.RestoreFavoriteOutcome
import com.gruning.mimir.rewrite.core.model.recovery.RestoreStorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RestoreSkipped
import com.gruning.mimir.rewrite.core.model.recovery.RestoreSkipReason
import com.gruning.mimir.rewrite.core.model.recovery.RestoreShelfOutcome
import com.gruning.mimir.rewrite.core.model.recovery.RestoreWillWrite
import com.gruning.mimir.rewrite.core.model.recovery.ShelfSkipReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRestorePlannerTest {
    private val sonicRecord = MigrationGameRecord(
        platformId = "gba",
        fileName = "Sonic.gba",
        sizeBytes = 1_048_576L,
        title = "Sonic",
        platformLabel = "Game Boy Advance",
    )
    private val missingRecord = MigrationGameRecord(
        platformId = "nes",
        fileName = "Missing.nes",
        sizeBytes = 32_768L,
        title = "Missing",
        platformLabel = "NES",
    )

    private class FakeLocalState(
        private val games: Set<String> = emptySet(),
        val favorites: MutableSet<String> = mutableSetOf(),
        val hidden: MutableSet<String> = mutableSetOf(),
        val overrides: MutableSet<String> = mutableSetOf(),
        val shelf: MutableSet<String> = mutableSetOf(),
        private val installedPackages: Set<String> = emptySet(),
        private val maxRank: Long? = null,
        private val scanned: Boolean = true,
    ) : RecoveryRestoreLocalState {
        override suspend fun libraryHasGames(): Boolean = scanned && games.isNotEmpty()
        override suspend fun hasFavorite(gameId: GameId): Boolean = gameId.value in favorites
        override suspend fun hasHidden(gameId: GameId): Boolean = gameId.value in hidden
        override suspend fun hasEmulatorOverride(gameId: GameId): Boolean = gameId.value in overrides
        override suspend fun hasShelfItem(profileId: ProfileId, itemId: HomeShelfItemId): Boolean =
            shelfKey(profileId, itemId) in shelf
        override suspend fun maxShelfRank(profileId: ProfileId): Long? = maxRank
        override suspend fun isPackageInstalled(packageName: String): Boolean =
            packageName in installedPackages
    }

    private class RecordingSeams(
        private val acceptFavorites: Set<String> = emptySet(),
        private val acceptHidden: Set<String> = emptySet(),
        private val acceptOverrides: Set<String> = emptySet(),
        private val acceptShelf: Set<String> = emptySet(),
        private val applyMaxShelfRank: Long? = 0L,
    ) : RecoveryRestoreSeams {
        val favorites = mutableListOf<Pair<String, Long>>()
        val hidden = mutableListOf<Pair<String, Long>>()
        val overrides = mutableListOf<Triple<String, String, Long>>()
        val shelf = mutableListOf<Quadruple<String, String, Long, Long>>()
        val seamCalls = mutableListOf<String>()

        data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

        override suspend fun currentMaxShelfRank(profileId: ProfileId): Long? {
            seamCalls += "currentMaxShelfRank"
            return applyMaxShelfRank
        }

        override suspend fun insertFavorite(gameId: GameId, favoritedAtMillis: Long): Boolean {
            seamCalls += "insertFavorite"
            if (gameId.value in acceptFavorites) return false
            favorites += gameId.value to favoritedAtMillis
            return true
        }

        override suspend fun insertHidden(gameId: GameId, hiddenAtMillis: Long): Boolean {
            seamCalls += "insertHidden"
            if (gameId.value in acceptHidden) return false
            hidden += gameId.value to hiddenAtMillis
            return true
        }

        override suspend fun insertEmulatorOverride(
            gameId: GameId,
            emulatorId: String,
            setAtMillis: Long,
        ): Boolean {
            seamCalls += "insertEmulatorOverride"
            if (gameId.value in acceptOverrides) return false
            overrides += Triple(gameId.value, emulatorId, setAtMillis)
            return true
        }

        override suspend fun insertShelfItem(
            profileId: ProfileId,
            itemId: HomeShelfItemId,
            rank: Long,
            addedAtMillis: Long,
        ): Boolean {
            seamCalls += "insertShelfItem"
            val key = shelfKey(profileId, itemId)
            if (key in acceptShelf) return false
            shelf += RecordingSeams.Quadruple(profileId.value, key, rank, addedAtMillis)
            return true
        }
    }

    private class ProbeTrackingLocalState(
        private val delegate: FakeLocalState,
    ) : RecoveryRestoreLocalState by delegate {
        val probes = mutableListOf<String>()

        override suspend fun libraryHasGames(): Boolean = delegate.libraryHasGames().also {
            probes += "libraryHasGames"
        }

        override suspend fun hasFavorite(gameId: GameId): Boolean =
            delegate.hasFavorite(gameId).also { probes += "hasFavorite" }

        override suspend fun hasHidden(gameId: GameId): Boolean =
            delegate.hasHidden(gameId).also { probes += "hasHidden" }

        override suspend fun hasEmulatorOverride(gameId: GameId): Boolean =
            delegate.hasEmulatorOverride(gameId).also { probes += "hasEmulatorOverride" }

        override suspend fun hasShelfItem(profileId: ProfileId, itemId: HomeShelfItemId): Boolean =
            delegate.hasShelfItem(profileId, itemId).also { probes += "hasShelfItem" }

        override suspend fun maxShelfRank(profileId: ProfileId): Long? =
            delegate.maxShelfRank(profileId).also { probes += "maxShelfRank" }

        override suspend fun isPackageInstalled(packageName: String): Boolean =
            delegate.isPackageInstalled(packageName).also { probes += "isPackageInstalled:$packageName" }
    }

    @Test
    fun `additive local-wins preview and apply only write missing local rows`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val missingId = probe.deriveGameId(missingRecord)
        val matcher = MigrationMatcher(gameExists = { it.value in setOf(sonicId.value, missingId.value) })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite(sonicId.value, 10L), RecoveryArchivedFavorite(missingId.value, 11L)),
            hidden = listOf(RecoveryArchivedHidden(sonicId.value, hidden = true, hiddenAtMillis = 12L)),
            overrides = listOf(
                RecoveryArchivedEmulatorOverride(sonicId.value, "flycast", 13L),
                RecoveryArchivedEmulatorOverride(missingId.value, "mgba", 14L),
            ),
            evidence = mapOf(
                sonicId.value to evidence(sonicId.value, sonicRecord),
                missingId.value to evidence(missingId.value, missingRecord),
            ),
        )
        val local = FakeLocalState(
            games = setOf(sonicId.value, missingId.value),
            favorites = mutableSetOf(sonicId.value),
            overrides = mutableSetOf(missingId.value),
        )

        val plan = planner.preview(snapshot, local) { _, _, emulatorId -> emulatorId == "flycast" }
        assertEquals(1, plan.favorites.matched)
        assertEquals(1, plan.favorites.skipped)
        assertEquals(1, plan.hidden.matched)
        assertEquals(1, plan.overrides.matched)
        assertEquals(1, plan.overrides.skipped)
        assertTrue(plan.notRestored.contains(RecoveryRestoreExcludedCategory.PLATFORMS))

        val seams = RecordingSeams()
        val result = planner.apply(plan, seams)
        assertEquals(1, result.favoritesRestored)
        assertEquals(1, result.hiddenRestored)
        assertEquals(1, result.overridesRestored)
        assertEquals(listOf(missingId.value to 11L), seams.favorites)
        assertEquals(listOf(sonicId.value to 12L), seams.hidden)
        assertEquals(listOf(Triple(sonicId.value, "flycast", 13L)), seams.overrides)
    }

    @Test
    fun `blank Auto override restores without emulator recognition`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val gameId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == gameId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            overrides = listOf(RecoveryArchivedEmulatorOverride(gameId.value, "", 99L)),
            evidence = mapOf(gameId.value to evidence(gameId.value, sonicRecord)),
        )
        val local = FakeLocalState(games = setOf(gameId.value))

        val plan = planner.preview(snapshot, local) { _, _, _ -> false }
        assertEquals(1, plan.overrides.matched)

        val seams = RecordingSeams()
        planner.apply(plan, seams)
        assertEquals(listOf(Triple(gameId.value, "", 99L)), seams.overrides)
    }

    @Test
    fun `retired archived alias maps through archived evidence and local current identity`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val historical = probe.deriveGameId(sonicRecord)
        val current = GameId("logical-current-winner")
        val matcher = MigrationMatcher(
            resolveCurrentGameId = { gameId -> if (gameId == historical) current else gameId },
            gameExists = { it == current },
        )
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite("game/historical-id", 1L)),
            identityIndex = RecoveryArchiveIdentityIndex(
                aliasToLogical = mapOf("game/historical-id" to "logical/sonic"),
                logicalToCurrent = mapOf("logical/sonic" to historical.value),
            ),
            evidence = mapOf(historical.value to evidence(historical.value, sonicRecord)),
        )
        val local = FakeLocalState(games = setOf(current.value))

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        val write = plan.favorites.decisions.single() as RestoreWillWrite
        assertEquals(current, write.outcome.matchedLocalGameId)
    }

    @Test
    fun `empty library yields pending scan with per-store decisions and zero writes`() = runBlocking {
        val planner = RecoveryRestorePlanner(MigrationMatcher(gameExists = { false }))
        val probe = MigrationMatcher(gameExists = { false })
        val gameId = probe.deriveGameId(sonicRecord)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite(gameId.value, 1L)),
            hidden = listOf(
                RecoveryArchivedHidden(gameId.value, hidden = true, hiddenAtMillis = 2L),
                RecoveryArchivedHidden(gameId.value, hidden = false, hiddenAtMillis = 3L),
            ),
            overrides = listOf(RecoveryArchivedEmulatorOverride(gameId.value, "flycast", 4L)),
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "EMULATED_GAME", gameId.value, rank = 0, addedAtMillis = 5L),
                RecoveryArchivedShelfItem("foreign", "EMULATED_GAME", gameId.value, rank = 0, addedAtMillis = 6L),
            ),
            evidence = mapOf(gameId.value to evidence(gameId.value, sonicRecord)),
        )
        val local = ProbeTrackingLocalState(FakeLocalState(scanned = false))

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        assertTrue(plan.pendingLibraryScan)
        assertEquals(0, plan.totalWouldWrite)
        assertEquals(1, plan.favorites.decisions.size)
        assertEquals(1, plan.favorites.pending)
        assertEquals(2, plan.hidden.decisions.size)
        assertEquals(1, plan.hidden.pending)
        assertEquals(1, plan.overrides.pending)
        assertEquals(1, plan.shelf.pending)
        assertEquals(
            listOf("libraryHasGames"),
            local.probes,
        )

        val result = planner.apply(plan, RecordingSeams())
        assertTrue(result.pendingLibraryScan)
        assertEquals(0, result.totalRestored)
        assertEquals(1, result.favoritesPending)
        assertEquals(1, result.hiddenPending)
    }

    @Test
    fun `second apply is idempotent and reports no new writes`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val gameId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == gameId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite(gameId.value, 5L)),
            evidence = mapOf(gameId.value to evidence(gameId.value, sonicRecord)),
        )
        val local = FakeLocalState(games = setOf(gameId.value))
        val seams = RecordingSeams()

        val first = planner.preview(snapshot, local) { _, _, _ -> true }
        planner.apply(first, seams)
        local.favorites.add(gameId.value)

        val second = planner.preview(snapshot, local) { _, _, _ -> true }
        assertEquals(0, second.totalWouldWrite)
        val secondResult = planner.apply(second, seams)
        assertEquals(0, secondResult.totalRestored)
        assertEquals(1, seams.favorites.size)
    }

    @Test
    fun `shelf append order profile and package rules are exact`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == sonicId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "EMULATED_GAME", sonicId.value, rank = 0, addedAtMillis = 1L),
                RecoveryArchivedShelfItem("local", "ANDROID_GAME", "com.installed.game", rank = 1, addedAtMillis = 2L),
                RecoveryArchivedShelfItem("local", "ANDROID_GAME", "com.missing.game", rank = 2, addedAtMillis = 3L),
                RecoveryArchivedShelfItem("foreign", "EMULATED_GAME", sonicId.value, rank = 0, addedAtMillis = 4L),
            ),
            evidence = mapOf(sonicId.value to evidence(sonicId.value, sonicRecord)),
        )
        val local = FakeLocalState(
            games = setOf(sonicId.value),
            installedPackages = setOf("com.installed.game"),
            maxRank = 5L,
        )

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        assertEquals(2, plan.shelf.matched)
        assertEquals(2, plan.shelf.skipped)

        val writes = plan.shelf.decisions.filterIsInstance<RestoreWillWrite<RestoreShelfOutcome>>()
        assertEquals(listOf(1_029L, 2_053L), writes.map { it.outcome.plannedRank })

        val seams = RecordingSeams(applyMaxShelfRank = 5L)
        planner.apply(plan, seams)
        assertEquals(
            listOf(
                RecordingSeams.Quadruple("local", "EMULATED_GAME:${sonicId.value}", 1_029L, 1L),
                RecordingSeams.Quadruple("local", "ANDROID_GAME:com.installed.game", 2_053L, 2L),
            ),
            seams.shelf,
        )
    }

    @Test
    fun `record ceiling refuses before planning writes`() = runBlocking {
        val planner = RecoveryRestorePlanner(MigrationMatcher(gameExists = { true }))
        val favorites = (0 until RECOVERY_MAX_RESTORE_RECORDS + 1).map { index ->
            RecoveryArchivedFavorite("game/$index", index.toLong())
        }
        val snapshot = snapshot(favorites = favorites)

        try {
            planner.preview(snapshot, FakeLocalState(games = setOf("game/0"))) { _, _, _ -> true }
            throw AssertionError("Expected record cap refusal")
        } catch (error: RecoveryRestoreRecordCapException) {
            assertEquals(RECOVERY_MAX_RESTORE_RECORDS + 1, error.attemptedRecords)
        }
    }

    @Test
    fun `apply tallies only accepted seam inserts when local row wins the race`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val gameId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == gameId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite(gameId.value, 5L)),
            evidence = mapOf(gameId.value to evidence(gameId.value, sonicRecord)),
        )
        val local = FakeLocalState(games = setOf(gameId.value))
        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        assertEquals(1, plan.favorites.matched)

        val result = planner.apply(plan, RecordingSeams(acceptFavorites = setOf(gameId.value)))
        assertEquals(0, result.favoritesRestored)
        assertEquals(1, result.favoritesSkipped)
    }

    @Test
    fun `android app shelf rows are unsupported and never planned for write`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == sonicId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "ANDROID_APP", "com.example.app", rank = 0, addedAtMillis = 1L),
                RecoveryArchivedShelfItem("local", "ANDROID_GAME", "com.installed.game", rank = 1, addedAtMillis = 2L),
            ),
        )
        val local = ProbeTrackingLocalState(
            FakeLocalState(
                games = setOf(sonicId.value),
                installedPackages = setOf("com.installed.game"),
                maxRank = 0L,
            ),
        )

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        assertEquals(1, plan.shelf.matched)
        val appDecision = plan.shelf.decisions.first {
            it.outcome.archived.itemKind == "ANDROID_APP"
        } as RestoreSkipped
        assertEquals(RestoreSkipReason.UNSUPPORTED_ITEM_KIND, appDecision.reason)
        assertEquals(ShelfSkipReason.UNSUPPORTED_ITEM_KIND, appDecision.outcome.skipReason)
        assertEquals(1, local.probes.count { it.startsWith("isPackageInstalled") })
    }

    @Test
    fun `apply uses live shelf max rank after preview and appends in archive order`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == sonicId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "EMULATED_GAME", sonicId.value, rank = 0, addedAtMillis = 1L),
                RecoveryArchivedShelfItem("local", "ANDROID_GAME", "com.installed.game", rank = 1, addedAtMillis = 2L),
            ),
            evidence = mapOf(sonicId.value to evidence(sonicId.value, sonicRecord)),
        )
        val local = FakeLocalState(
            games = setOf(sonicId.value),
            installedPackages = setOf("com.installed.game"),
            maxRank = 5L,
        )

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        val writes = plan.shelf.decisions.filterIsInstance<RestoreWillWrite<RestoreShelfOutcome>>()
        assertEquals(listOf(1_029L, 2_053L), writes.map { it.outcome.plannedRank })

        val seams = RecordingSeams(applyMaxShelfRank = 10L)
        planner.apply(plan, seams)
        assertEquals(
            listOf(
                RecordingSeams.Quadruple("local", "EMULATED_GAME:${sonicId.value}", 1_034L, 1L),
                RecordingSeams.Quadruple("local", "ANDROID_GAME:com.installed.game", 2_058L, 2L),
            ),
            seams.shelf,
        )
    }

    @Test
    fun `apply reuses live rank when an earlier planned shelf insert loses the race`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == sonicId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "EMULATED_GAME", sonicId.value, rank = 0, addedAtMillis = 1L),
                RecoveryArchivedShelfItem("local", "ANDROID_GAME", "com.installed.game", rank = 1, addedAtMillis = 2L),
            ),
            evidence = mapOf(sonicId.value to evidence(sonicId.value, sonicRecord)),
        )
        val local = FakeLocalState(
            games = setOf(sonicId.value),
            installedPackages = setOf("com.installed.game"),
            maxRank = 5L,
        )
        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        val racedKey = "EMULATED_GAME:${sonicId.value}"
        val seams = RecordingSeams(
            acceptShelf = setOf(racedKey),
            applyMaxShelfRank = 10L,
        )

        val result = planner.apply(plan, seams)

        assertEquals(1, result.shelfRestored)
        assertEquals(1, result.shelfSkipped)
        assertEquals(
            listOf(
                RecordingSeams.Quadruple(
                    "local",
                    "ANDROID_GAME:com.installed.game",
                    1_034L,
                    2L,
                ),
            ),
            seams.shelf,
        )
    }

    @Test
    fun `apply refuses oversized forged plan before any seam call`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val gameId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == gameId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite(gameId.value, 1L)),
            evidence = mapOf(gameId.value to evidence(gameId.value, sonicRecord)),
        )
        val honest = planner.preview(snapshot, FakeLocalState(games = setOf(gameId.value))) { _, _, _ -> true }
        val forged = honest.copy(
            archiveRestoreRecordCount = RECOVERY_MAX_RESTORE_RECORDS + 1,
            favorites = RestoreStorePlan(
                decisions = honest.favorites.decisions +
                    (0 until RECOVERY_MAX_RESTORE_RECORDS).map { index ->
                        RestoreSkipped(
                            RestoreFavoriteOutcome(
                                archived = RecoveryArchivedFavorite("forged/$index", index.toLong()),
                                normalizedArchivedGameId = "forged/$index",
                                migrationRecord = null,
                            ),
                            RestoreSkipReason.UNMATCHED_GAME,
                        )
                    },
            ),
        )
        val seams = RecordingSeams()

        try {
            planner.apply(forged, seams)
            throw AssertionError("Expected forged-plan refusal")
        } catch (error: RecoveryRestoreRecordCapException) {
            assertEquals(RECOVERY_MAX_RESTORE_RECORDS + 1, error.attemptedRecords)
        }
        assertTrue(seams.seamCalls.isEmpty())
    }

    @Test
    fun `apply refuses oversized forged pending plan before any seam call`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val gameId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == gameId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite(gameId.value, 1L)),
            evidence = mapOf(gameId.value to evidence(gameId.value, sonicRecord)),
        )
        val pending = planner.preview(snapshot, FakeLocalState(scanned = false)) { _, _, _ -> true }
        assertTrue(pending.pendingLibraryScan)
        val forged = pending.copy(
            archiveRestoreRecordCount = RECOVERY_MAX_RESTORE_RECORDS + 1,
        )
        val seams = RecordingSeams()

        try {
            planner.apply(forged, seams)
            throw AssertionError("Expected forged pending-plan refusal")
        } catch (error: RecoveryRestoreRecordCapException) {
            assertEquals(RECOVERY_MAX_RESTORE_RECORDS + 1, error.attemptedRecords)
        }
        assertTrue(seams.seamCalls.isEmpty())
    }

    @Test
    fun `shelf rank one step below max accepts one row at long max value`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == sonicId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "EMULATED_GAME", sonicId.value, rank = 0, addedAtMillis = 1L),
            ),
            evidence = mapOf(sonicId.value to evidence(sonicId.value, sonicRecord)),
        )
        val local = FakeLocalState(games = setOf(sonicId.value), maxRank = Long.MAX_VALUE - 1_024L)

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        val write = plan.shelf.decisions.filterIsInstance<RestoreWillWrite<RestoreShelfOutcome>>().single()
        assertEquals(Long.MAX_VALUE, write.outcome.plannedRank)

        val seams = RecordingSeams(applyMaxShelfRank = Long.MAX_VALUE - 1_024L)
        val result = planner.apply(plan, seams)
        assertEquals(1, result.shelfRestored)
        assertEquals(
            listOf(
                RecordingSeams.Quadruple("local", "EMULATED_GAME:${sonicId.value}", Long.MAX_VALUE, 1L),
            ),
            seams.shelf,
        )
    }

    @Test
    fun `shelf rank overflow for multiple rows performs zero writes`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == sonicId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "EMULATED_GAME", sonicId.value, rank = 0, addedAtMillis = 1L),
                RecoveryArchivedShelfItem("local", "ANDROID_GAME", "com.installed.game", rank = 1, addedAtMillis = 2L),
            ),
            evidence = mapOf(sonicId.value to evidence(sonicId.value, sonicRecord)),
        )
        val local = FakeLocalState(
            games = setOf(sonicId.value),
            installedPackages = setOf("com.installed.game"),
            maxRank = Long.MAX_VALUE - 1_024L,
        )

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        assertEquals(1, plan.shelf.matched)
        assertEquals(1, plan.shelf.skipped)
        val overflow = plan.shelf.decisions.filterIsInstance<RestoreSkipped<RestoreShelfOutcome>>()
            .single { it.reason == RestoreSkipReason.RANK_OVERFLOW }
        assertEquals(ShelfSkipReason.RANK_OVERFLOW, overflow.outcome.skipReason)

        val forged = plan.copy(
            shelf = RestoreStorePlan(
                decisions = plan.shelf.decisions.map { decision ->
                    if (decision is RestoreSkipped && decision.reason == RestoreSkipReason.RANK_OVERFLOW) {
                        val outcome = decision.outcome
                        val itemId = when (outcome.archived.itemKind) {
                            "ANDROID_GAME" -> HomeShelfItemId.AndroidGame(
                                AndroidPackageName(outcome.archived.itemId),
                            )
                            else -> error("unexpected forged shelf row")
                        }
                        RestoreWillWrite(
                            outcome.copy(
                                itemId = itemId,
                                plannedRank = Long.MAX_VALUE,
                                skipReason = null,
                            ),
                        )
                    } else {
                        decision
                    }
                },
            ),
        )
        val seams = RecordingSeams(applyMaxShelfRank = Long.MAX_VALUE - 1_024L)

        try {
            planner.apply(forged, seams)
            throw AssertionError("Expected multi-row shelf rank overflow")
        } catch (error: RecoveryRestoreShelfRankOverflowException) {
            assertEquals(Long.MAX_VALUE, error.lastAssignedRank)
        }
        assertTrue(seams.shelf.isEmpty())
        assertEquals(listOf("currentMaxShelfRank"), seams.seamCalls)
    }

    @Test
    fun `shelf rank overflow refuses at apply time before shelf insert`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val sonicId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == sonicId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            shelf = listOf(
                RecoveryArchivedShelfItem("local", "EMULATED_GAME", sonicId.value, rank = 0, addedAtMillis = 1L),
            ),
            evidence = mapOf(sonicId.value to evidence(sonicId.value, sonicRecord)),
        )
        val local = FakeLocalState(games = setOf(sonicId.value), maxRank = 5L)

        val plan = planner.preview(snapshot, local) { _, _, _ -> true }
        val seams = RecordingSeams(applyMaxShelfRank = Long.MAX_VALUE)

        try {
            planner.apply(plan, seams)
            throw AssertionError("Expected shelf rank overflow")
        } catch (error: RecoveryRestoreShelfRankOverflowException) {
            assertEquals(Long.MAX_VALUE, error.lastAssignedRank)
        }
        assertTrue(seams.shelf.isEmpty())
        assertTrue(seams.favorites.isEmpty())
        assertTrue(seams.hidden.isEmpty())
        assertTrue(seams.overrides.isEmpty())
    }

    @Test
    fun `excluded categories are always surfaced and never written by seams`() = runBlocking {
        val probe = MigrationMatcher(gameExists = { false })
        val gameId = probe.deriveGameId(sonicRecord)
        val matcher = MigrationMatcher(gameExists = { it == gameId })
        val planner = RecoveryRestorePlanner(matcher)
        val snapshot = snapshot(
            favorites = listOf(RecoveryArchivedFavorite(gameId.value, 1L)),
            evidence = mapOf(gameId.value to evidence(gameId.value, sonicRecord)),
        )
        val plan = planner.preview(snapshot, FakeLocalState(games = setOf(gameId.value))) { _, _, _ -> true }

        assertFalse(plan.notRestored.isEmpty())
        assertTrue(plan.notRestored.contains(RecoveryRestoreExcludedCategory.LAUNCH_PROOFS))
        assertTrue(plan.notRestored.contains(RecoveryRestoreExcludedCategory.CREDENTIALS))
    }

    private fun snapshot(
        favorites: List<RecoveryArchivedFavorite> = emptyList(),
        hidden: List<RecoveryArchivedHidden> = emptyList(),
        overrides: List<RecoveryArchivedEmulatorOverride> = emptyList(),
        shelf: List<RecoveryArchivedShelfItem> = emptyList(),
        evidence: Map<String, RecoveryArchivedGameEvidence> = emptyMap(),
        identityIndex: RecoveryArchiveIdentityIndex = RecoveryArchiveIdentityIndex(emptyMap(), emptyMap()),
    ): RecoveryArchiveRestoreSnapshot = RecoveryArchiveRestoreSnapshot(
        schemaVersion = 9,
        favorites = favorites,
        hidden = hidden,
        emulatorOverrides = overrides,
        shelfItems = shelf,
        identityIndex = identityIndex,
        gameEvidence = evidence,
        sourceRootHints = emptyList(),
    )

    private fun evidence(
        gameId: String,
        record: MigrationGameRecord,
    ): RecoveryArchivedGameEvidence = RecoveryArchivedGameEvidence(
        normalizedGameId = gameId,
        platformId = record.platformId,
        fileName = record.fileName,
        sizeBytes = record.sizeBytes,
        relativePath = record.relativePath ?: record.fileName,
        contentHash = record.contentHash,
        serial = record.serial,
        title = record.title,
        platformLabel = record.platformLabel,
    )

    private companion object {
        fun shelfKey(profileId: ProfileId, itemId: HomeShelfItemId): String =
            when (itemId) {
                is HomeShelfItemId.Emulated -> "EMULATED_GAME:${itemId.gameId.value}"
                is HomeShelfItemId.AndroidGame -> "ANDROID_GAME:${itemId.packageName.value}"
                is HomeShelfItemId.AndroidApp -> "ANDROID_APP:${itemId.packageName.value}"
            }
    }
}
