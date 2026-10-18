package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.TomeId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLaunchReturnStoreTest {
    @Test
    fun `schema v6 retains Home mode typed Tome origin browser position and navigation epoch`() {
        val tome = TomeId.Platform(PlatformId("snes"))
        val session = HomeTomeSession(
            tomeId = tome,
            origin = HomeTomeOrigin.TOME_LIBRARY,
            rootEntry = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("chrono"))),
            rootMode = HomeViewMode.PINNED_TOMES,
            rootFallbackEntry = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("fallback"))),
            browserFocusedIndex = 7,
            browserFirstVisibleIndex = 4,
            generation = 41L,
        )
        val handle = HomeLaunchReturnHandle(emulatedTarget, "folio", session)
        val persistence = DurableFakePersistence()
        assertTrue(HomeLaunchReturnLedger(persistence).arm(handle))
        assertEquals(handle, HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))
        assertEquals(6, persistence.record?.schemaVersion)
        assertEquals(41L, persistence.record?.tomeGeneration)
        assertEquals(HomeViewMode.PINNED_TOMES.name, persistence.record?.homeViewMode)

        persistence.record = requireNotNull(persistence.record).copy(tomeGeneration = null)
        assertNull(HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))
    }

    @Test
    fun `schema v4 Tome records remain readable at the baseline epoch`() {
        val tome = TomeId.Platform(PlatformId("snes"))
        val modern = HomeLaunchReturnHandle(
            emulatedTarget,
            "legacy-folio",
            HomeTomeSession(tome, HomeTomeOrigin.HOME_ROOT, rootEntry = null, generation = 9L),
        )
        val persistence = DurableFakePersistence()
        assertTrue(HomeLaunchReturnLedger(persistence).arm(modern))
        persistence.record = requireNotNull(persistence.record).copy(
            schemaVersion = 4,
            tomeGeneration = null,
        )

        val restored = HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true)
        assertEquals(0L, restored?.tomeSession?.generation)
        assertEquals(tome, restored?.tomeSession?.tomeId)
    }

    @Test
    fun `schema v5 records migrate to Editors Choice`() {
        val persistence = DurableFakePersistence()
        assertTrue(HomeLaunchReturnLedger(persistence).arm(emulatedHandle))
        persistence.record = requireNotNull(persistence.record).copy(
            schemaVersion = 5,
            homeViewMode = null,
        )

        assertEquals(
            HomeViewMode.EDITORS_CHOICE,
            HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true)?.homeViewMode,
        )
    }

    @Test
    fun `aggregate Home overview round trips without pretending to be a pinned Tome`() {
        val session = HomeTomeSession(
            tomeId = TomeId.HomeOverview,
            origin = HomeTomeOrigin.TOME_LIBRARY,
            rootEntry = HomeShelfEntry.Tome(TomeId.HomeOverview),
            rootMode = HomeViewMode.EDITORS_CHOICE,
            rootFallbackEntry = null,
            generation = 7L,
        )
        val persistence = DurableFakePersistence()
        val handle = HomeLaunchReturnHandle(emulatedTarget, "home-overview", session)

        assertTrue(HomeLaunchReturnLedger(persistence).arm(handle))
        assertEquals(
            session,
            HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true)?.tomeSession,
        )
    }

    @Test
    fun `modern records retire context while v3 context remains decode tolerant`() {
        val persistence = DurableFakePersistence()
        val target = HomeLaunchReturnTarget(
            itemId = HomeShelfItemId.Emulated(GameId("chrono")),
            rank = 1_024L,
            contextKey = "platform/ps1",
        )
        val handle = HomeLaunchReturnHandle(target, "token-ctx")
        assertTrue(HomeLaunchReturnLedger(persistence).arm(handle))
        assertNull(persistence.record?.contextKey)
        assertNull(HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true)?.target?.contextKey)

        val legacy = requireNotNull(persistence.record).copy(
            schemaVersion = 3,
            contextKey = "platform/ps1",
        )
        persistence.record = legacy
        val restored = HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true)
        assertEquals("platform/ps1", restored?.target?.contextKey)
        assertEquals(target.itemId, restored?.target?.itemId)
    }

    @Test
    fun `Android App can neither arm nor survive a legacy peek`() {
        val persistence = DurableFakePersistence()
        val appHandle = HomeLaunchReturnHandle(
            HomeLaunchReturnTarget(
                itemId = HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app")),
                rank = 1L,
            ),
            "retired-app",
        )
        val ledger = HomeLaunchReturnLedger(persistence)
        assertEquals(false, ledger.arm(appHandle))
        persistence.record = HomeLaunchReturnRecord(
            schemaVersion = 3,
            kind = "ANDROID_APP",
            identity = "com.example.app",
            rank = 1L,
            token = "legacy-app",
        )

        assertNull(ledger.peek(finalShelfEnabled = true))
        assertNull(persistence.record)
    }

    @Test
    fun `the armed token is read without writing, so a legacy Android App record survives it`() {
        val persistence = DurableFakePersistence()
        val ledger = HomeLaunchReturnLedger(persistence)

        // Nothing armed: no token, and the read changes nothing.
        assertNull(ledger.armedTokenOrNull())
        assertNull(persistence.record)

        // A normal record returns its token and stays armed.
        assertTrue(ledger.arm(emulatedHandle))
        assertEquals("exact-token", ledger.armedTokenOrNull())
        assertEquals(emulatedHandle, ledger.peek(finalShelfEnabled = true))

        // A legacy ANDROID_APP record is not a return to name, and unlike peek the read leaves it where it is.
        val legacyApp = HomeLaunchReturnRecord(
            schemaVersion = 3,
            kind = "ANDROID_APP",
            identity = "com.example.app",
            rank = 1L,
            token = "legacy-app",
        )
        persistence.record = legacyApp
        val clearsBefore = persistence.clearCount
        assertNull(ledger.armedTokenOrNull())
        assertEquals("the record is still present afterwards", legacyApp, persistence.record)
        assertEquals("and nothing was cleared", clearsBefore, persistence.clearCount)

        // A record that cannot be decoded names nobody and is not touched either.
        val broken = legacyApp.copy(kind = "EMULATED", token = null)
        persistence.record = broken
        assertNull(ledger.armedTokenOrNull())
        assertEquals(broken, persistence.record)
        assertEquals(clearsBefore, persistence.clearCount)
    }

    @Test
    fun `complete typed records survive a new ledger instance exactly`() {
        val targets = listOf(
            HomeLaunchReturnTarget(
                itemId = HomeShelfItemId.Emulated(GameId("game-id-1")),
                rank = 1_024L,
            ),
            HomeLaunchReturnTarget(
                itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")),
                rank = 2_048L,
            ),
        )

        targets.forEachIndexed { index, target ->
            val persistence = DurableFakePersistence()
            val handle = HomeLaunchReturnHandle(target, "token-$index")
            assertTrue(HomeLaunchReturnLedger(persistence).arm(handle))

            val afterProcessDeath = HomeLaunchReturnLedger(persistence)
            assertEquals(handle, afterProcessDeath.peek(finalShelfEnabled = true))
        }
    }

    @Test
    fun `every incomplete or unknown record is rejected`() {
        val persistence = DurableFakePersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        assertTrue(ledger.arm(emulatedHandle))
        val complete = requireNotNull(persistence.record)
        val incompleteRecords = listOf(
            complete.copy(schemaVersion = null),
            complete.copy(schemaVersion = requireNotNull(complete.schemaVersion) + 1),
            complete.copy(kind = null),
            complete.copy(kind = "REMOVED_KIND"),
            complete.copy(identity = null),
            complete.copy(identity = "  "),
            complete.copy(rank = null),
            complete.copy(token = null),
        )

        incompleteRecords.forEach { record ->
            persistence.record = record
            assertNull(HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))
        }
    }

    @Test
    fun `disabled gate never reads token and acknowledged clear survives process death`() {
        val persistence = DurableFakePersistence()
        assertTrue(HomeLaunchReturnLedger(persistence).arm(emulatedHandle))
        val afterProcessDeath = HomeLaunchReturnLedger(persistence)
        val readsBeforeGate = persistence.readCount

        assertNull(afterProcessDeath.peek(finalShelfEnabled = false))
        assertEquals(readsBeforeGate, persistence.readCount)
        assertTrue(afterProcessDeath.clearWhenFinalShelfDisabled(finalShelfEnabled = false))
        assertNull(HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))
    }

    @Test
    fun `failed clear acknowledgement retains durable token for retry`() {
        val persistence = DurableFakePersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        assertTrue(ledger.arm(emulatedHandle))
        persistence.clearAcknowledged = false

        assertEquals(HomeLaunchReturnAcknowledgement.RETRY, ledger.acknowledge(emulatedHandle))
        assertEquals(emulatedHandle, HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))

        persistence.clearAcknowledged = true
        assertEquals(
            HomeLaunchReturnAcknowledgement.CLEARED,
            HomeLaunchReturnLedger(persistence).acknowledge(emulatedHandle),
        )
        assertNull(HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))
    }

    @Test
    fun `stale acknowledgement cannot clear a newer home anchor`() {
        val persistence = DurableFakePersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        val newer = HomeLaunchReturnHandle(emulatedTarget.copy(rank = 8_192L), "newer")
        assertTrue(ledger.arm(emulatedHandle))
        assertTrue(ledger.arm(newer))

        assertEquals(HomeLaunchReturnAcknowledgement.SUPERSEDED, ledger.acknowledge(emulatedHandle))
        assertEquals(newer, ledger.peek(finalShelfEnabled = true))
    }

    @Test
    fun `failed arm acknowledgement blocks external dispatch`() = runBlocking {
        val persistence = DurableFakePersistence(replaceAcknowledged = false)
        val ledger = HomeLaunchReturnLedger(persistence)
        var dispatchCount = 0

        val result = dispatchHomeExternalLaunchAfterArm(
            arm = { ledger.arm(emulatedHandle) },
            dispatch = {
                dispatchCount += 1
                true
            },
        )

        assertEquals(HomeExternalLaunchAttempt.ARMING_FAILED, result)
        assertEquals(0, dispatchCount)
        assertNull(persistence.record)
    }

    @Test
    fun `acknowledged arm dispatches exactly once`() = runBlocking {
        val persistence = DurableFakePersistence()
        val ledger = HomeLaunchReturnLedger(persistence)
        var dispatchCount = 0

        val result = dispatchHomeExternalLaunchAfterArm(
            arm = { ledger.arm(emulatedHandle) },
            dispatch = {
                dispatchCount += 1
                true
            },
        )

        assertEquals(HomeExternalLaunchAttempt.DISPATCHED, result)
        assertEquals(1, dispatchCount)
        assertEquals(emulatedHandle, HomeLaunchReturnLedger(persistence).peek(finalShelfEnabled = true))
    }

    @Test
    fun `arm exception fails closed before dispatch`() = runBlocking {
        var dispatchCount = 0

        val result = dispatchHomeExternalLaunchAfterArm(
            arm = { error("storage unavailable") },
            dispatch = {
                dispatchCount += 1
                true
            },
        )

        assertEquals(HomeExternalLaunchAttempt.ARMING_FAILED, result)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun `dispatch exception becomes an explicit failed attempt`() = runBlocking {
        val persistence = DurableFakePersistence()
        val ledger = HomeLaunchReturnLedger(persistence)

        val result = dispatchHomeExternalLaunchAfterArm(
            arm = { ledger.arm(emulatedHandle) },
            dispatch = { error("activity dispatch failed") },
        )

        assertEquals(HomeExternalLaunchAttempt.DISPATCH_FAILED, result)
        assertEquals(emulatedHandle, ledger.peek(finalShelfEnabled = true))
    }

    private class DurableFakePersistence(
        var replaceAcknowledged: Boolean = true,
        var clearAcknowledged: Boolean = true,
    ) : HomeLaunchReturnPersistence {
        var record: HomeLaunchReturnRecord? = null
        var readCount: Int = 0
        var clearCount: Int = 0

        override fun read(): HomeLaunchReturnRecord {
            readCount += 1
            return record ?: HomeLaunchReturnRecord(null, null, null, null, null)
        }

        override fun replace(record: HomeLaunchReturnRecord): Boolean {
            if (replaceAcknowledged) this.record = record
            return replaceAcknowledged
        }

        override fun clear(): Boolean {
            clearCount += 1
            if (clearAcknowledged) record = null
            return clearAcknowledged
        }
    }

    private companion object {
        val emulatedTarget = HomeLaunchReturnTarget(
            itemId = HomeShelfItemId.Emulated(GameId("exact-game-id")),
            rank = 4_096L,
        )
        val emulatedHandle = HomeLaunchReturnHandle(emulatedTarget, "exact-token")
    }
}
