package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rank 7c (AYN Thor, build 5fa4c795, 2026-09-11): Zelda launched from the Bottom card armed Main's Home launch
 * return (`mimir_home_launch_return.xml` held `identity=catalog:gbc:zelda-oracle-of-ages rank=0` while the game
 * ran). Zelda has no row on Main's Editor's Choice, so on quit Home resolved the return as RowAbsent and held the
 * branded return cover for the whole 4 s grace.
 *
 * Ruling: a Bottom-card launch arms only when the game has a row on Main's Home, decided from the launching call
 * site's own row lookup. Main Home launches arm exactly as before. A skipped arm still dispatches, and its EXTERNAL
 * ELAPSED measurement is finished by Main's return path, because Home's resume only finishes a record it can peek.
 */
class HomeLaunchReturnOriginTest {

    @Test
    fun `a Bottom-card launch of a game with no row on Main's Home skips the arm`() {
        val origin = bottomCardHomeLaunchOrigin(mainHomeRow = null)

        assertEquals(HomeLaunchOrigin.BOTTOM_CARD_OFF_HOME, origin)
        assertFalse(homeLaunchReturnArmDecision(origin).arms)
        assertEquals("no_row_on_main_home", homeLaunchReturnArmDecision(origin).reason)
    }

    @Test
    fun `a Bottom-card launch of a game with a row on Main's Home still arms`() {
        // Rank 0 is a real Home rank (the first emulated favourite is 0 * HomeFavoriteRankStep), so the row's
        // presence decides, never its rank.
        val firstRow = HomeShelfRow(itemId = zelda, rank = 0L, addedAtMillis = 0L, title = "Oracle of Ages")

        val origin = bottomCardHomeLaunchOrigin(mainHomeRow = firstRow)

        assertEquals(HomeLaunchOrigin.BOTTOM_CARD_ON_HOME, origin)
        assertTrue(homeLaunchReturnArmDecision(origin).arms)
        assertEquals("row_on_main_home", homeLaunchReturnArmDecision(origin).reason)
    }

    @Test
    fun `a Main Home launch arms exactly as before`() {
        val handle = HomeLaunchReturnHandle.create(HomeLaunchReturnTarget(itemId = zelda, rank = 2_048L))
        val persistence = RecordingPersistence()

        assertEquals(HomeLaunchOrigin.MAIN_HOME, handle.origin)
        assertTrue(homeLaunchReturnArmDecision(handle.origin).arms)
        assertTrue(HomeLaunchReturnLedger(persistence).arm(handle))
        assertEquals(handle, HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))
    }

    @Test
    fun `a skipped arm writes nothing and the launch still dispatches`() = runBlocking {
        val persistence = RecordingPersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        var dispatchCount = 0

        val attempt = dispatchHomeExternalLaunchAfterArm(
            arm = { ledger.arm(offHomeHandle()) },
            dispatch = {
                dispatchCount += 1
                true
            },
        )

        assertEquals(HomeExternalLaunchAttempt.DISPATCHED, attempt)
        assertEquals(1, dispatchCount)
        assertEquals(0, persistence.replaceCount)
        assertNull(ledger.peek(finalShelfEnabled = true))
    }

    @Test
    fun `a skipped arm leaves an older unconsumed record untouched`() {
        val persistence = RecordingPersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        val older = HomeLaunchReturnHandle.create(
            HomeLaunchReturnTarget(itemId = HomeShelfItemId.Emulated(GameId("chrono")), rank = 1_024L),
        )
        assertTrue(ledger.arm(older))
        val olderRecord = persistence.record

        assertTrue(ledger.arm(offHomeHandle()))

        assertEquals(olderRecord, persistence.record)
        assertEquals(older, ledger.peek(finalShelfEnabled = true))
    }

    @Test
    fun `a Bottom-card handle clears the record it armed`() {
        val persistence = RecordingPersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        val handle = onHomeHandle()
        assertTrue(ledger.arm(handle))
        assertNotNull(persistence.record)

        // The Bottom cleanup, the dispatch watchdog and a failed dispatch all acknowledge with the IN-MEMORY
        // handle, while the decoded record reads MAIN_HOME because the origin is never persisted.
        assertEquals(HomeLaunchReturnAcknowledgement.CLEARED, ledger.acknowledge(handle))
        assertNull(persistence.record)
    }

    @Test
    fun `a skipped arm's EXTERNAL ELAPSED is finished once by Main's return path`() {
        val persistence = RecordingPersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        val handle = offHomeHandle()
        assertTrue(ledger.arm(handle))
        var document = begun(handle)

        document = mainReturnPathFinish(document, gameSession(handle), nowElapsedMillis = 70_000L)
        assertNull("Main's return path must close the skipped arm's measurement", document.active)
        assertEquals(60_000L, document.elapsedTotals[zelda])

        // Home's resume finishes only what it can peek, and a skipped arm left nothing to peek.
        document = homeResumeFinish(document, ledger, nowElapsedMillis = 70_500L)
        assertEquals(60_000L, document.elapsedTotals[zelda])
    }

    @Test
    fun `an armed launch finished by Main's return path and by Home counts once`() {
        val persistence = RecordingPersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        val handle = onHomeHandle()
        assertTrue(ledger.arm(handle))
        var document = begun(handle)

        document = mainReturnPathFinish(document, gameSession(handle), nowElapsedMillis = 70_000L)
        assertNull("Main's return path must close the armed launch's measurement", document.active)
        document = homeResumeFinish(document, ledger, nowElapsedMillis = 70_500L)

        assertEquals(60_000L, document.elapsedTotals[zelda])
        assertNull(document.active)
    }

    @Test
    fun `Main's return path finishes only its own Main-placement session`() {
        val handle = onHomeHandle()
        val main = gameSession(handle)
        val bottom = gameSession(handle, placement = MimirGamePlacement.BOTTOM)

        assertEquals(handle.token, primaryGameReturnElapsedToken(main, main.operation))
        // A guard: Main's return ticket only records MAIN-placed sessions, and a Bottom game's measurement
        // belongs to the Bottom cleanup.
        assertNull(primaryGameReturnElapsedToken(bottom, bottom.operation))
        assertNull(primaryGameReturnElapsedToken(main, MimirGameSessionOperation("home:successor")))
        assertNull(primaryGameReturnElapsedToken(null, main.operation))
    }

    private val zelda = HomeShelfItemId.Emulated(GameId("catalog:gbc:zelda-oracle-of-ages"))

    private fun offHomeHandle() = HomeLaunchReturnHandle.create(
        HomeLaunchReturnTarget(itemId = zelda, rank = 0L),
        origin = HomeLaunchOrigin.BOTTOM_CARD_OFF_HOME,
    )

    private fun onHomeHandle() = HomeLaunchReturnHandle.create(
        HomeLaunchReturnTarget(itemId = zelda, rank = 0L),
        origin = HomeLaunchOrigin.BOTTOM_CARD_ON_HOME,
    )

    private fun begun(handle: HomeLaunchReturnHandle): HomeExternalSessionElapsedDocument =
        beginHomeExternalSession(
            document = HomeExternalSessionElapsedDocument(),
            itemId = zelda,
            returnToken = handle.token,
            dispatchedAtElapsedMillis = 10_000L,
            bootCount = BootCount,
        )

    /** Main's return path as `MainActivity.settleReturningGameSession` runs it, against the store's pure reducer. */
    private fun mainReturnPathFinish(
        document: HomeExternalSessionElapsedDocument,
        session: MimirGameSession,
        nowElapsedMillis: Long,
    ): HomeExternalSessionElapsedDocument =
        primaryGameReturnElapsedToken(session, session.operation)
            ?.let { token -> finalizeHomeExternalSession(document, token, nowElapsedMillis, BootCount) }
            ?: document

    /** Home's resume as `HomeShelfCoordinator`'s ON_RESUME observer runs it: finish the peeked record's token. */
    private fun homeResumeFinish(
        document: HomeExternalSessionElapsedDocument,
        ledger: HomeLaunchReturnLedger,
        nowElapsedMillis: Long,
    ): HomeExternalSessionElapsedDocument =
        ledger.peek(finalShelfEnabled = true)
            ?.let { peeked -> finalizeHomeExternalSession(document, peeked.token, nowElapsedMillis, BootCount) }
            ?: document

    private fun gameSession(
        handle: HomeLaunchReturnHandle,
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
    ) = MimirGameSession(
        operation = MimirGameSessionOperation("home:rank-7c"),
        sequence = 1L,
        gameId = zelda.gameId,
        platformId = PlatformId("gbc"),
        returnAnchor = MimirGameReturnAnchor.Home(handle.target),
        homeReturnHandle = handle,
        startedAtEpochMs = 1_000L,
        placement = placement,
        dualScreen = true,
        phase = MimirSessionPhase.RETURNING,
    )

    private class RecordingPersistence : HomeLaunchReturnPersistence {
        var record: HomeLaunchReturnRecord? = null
        var replaceCount: Int = 0

        override fun read(): HomeLaunchReturnRecord = record ?: HomeLaunchReturnRecord(null, null, null, null, null)

        override fun replace(record: HomeLaunchReturnRecord): Boolean {
            replaceCount += 1
            this.record = record
            return true
        }

        override fun clear(): Boolean {
            record = null
            return true
        }
    }

    private companion object {
        const val BootCount = 3
    }
}
