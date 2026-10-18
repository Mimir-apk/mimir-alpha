package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchDispatchDestination
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rank 7c follow-up: a Bottom-card launch with no row on Main's Home writes no Home return record. If Mimir's
 * process dies during that game, Main's return path has no in-memory session to finish, and Home's return only
 * finishes EXTERNAL ELAPSED from a record it can peek, so the persisted `active` entry stayed open until the next
 * launch replaced it and the session's time was lost.
 *
 * The measurement now records the display its game owns. At the Main-return reads where Home finishes an armed
 * launch, a MAIN measurement that an earlier process began is finished once, by the same token-matched,
 * boot-checked reducer. A Bottom game may still be running when Main comes back, and a measurement this process
 * began belongs to the live return path, so neither is finished there. Each guard is paired with a control that
 * differs in one field, so a decision that never finishes anything cannot pass it.
 */
class HomeExternalSessionOrphanTest {

    @Test
    fun `a skipped Main arm left open by a dead process is finished once at Main's return`() {
        val ledger = HomeLaunchReturnLedger(InMemoryReturnRecord())
        val handle = HomeLaunchReturnHandle.create(
            HomeLaunchReturnTarget(itemId = zelda, rank = 0L),
            origin = HomeLaunchOrigin.BOTTOM_CARD_OFF_HOME,
        )
        assertTrue(ledger.arm(handle))
        var document = begun(handle.token, MimirGamePlacement.MAIN, dispatchedAt = 10_000L)

        // The process died. The new one started at 40_000, and Main's return reads the store at 70_000.
        assertNull("a skipped arm leaves Home no record to finish from", ledger.peek(finalShelfEnabled = true))
        document = mainReturnFinish(document, processStart = 40_000L, now = 70_000L)
        assertNull("the dead process's measurement must be finished", document.active)
        assertEquals(60_000L, document.elapsedTotals[zelda])

        // A later Main return finds nothing left to finish, so the time is counted once.
        document = mainReturnFinish(document, processStart = 40_000L, now = 95_000L)
        assertEquals(60_000L, document.elapsedTotals[zelda])
    }

    @Test
    fun `a Bottom game's measurement is never finished by Main's return`() {
        val main = begun("main", MimirGamePlacement.MAIN, dispatchedAt = 10_000L)
        val bottom = begun("bottom", MimirGamePlacement.BOTTOM, dispatchedAt = 10_000L)

        assertEquals("main", orphanToken(main))
        // The Bottom game may still be running on the Bottom display when Main comes back.
        assertNull(orphanToken(bottom))
    }

    @Test
    fun `a measurement this process began is left to Main's live return path`() {
        val earlier = begun("earlier", MimirGamePlacement.MAIN, dispatchedAt = 39_999L)
        val thisProcess = begun("this", MimirGamePlacement.MAIN, dispatchedAt = 40_001L)

        assertEquals("earlier", orphanToken(earlier, processStart = 40_000L))
        assertNull(orphanToken(thisProcess, processStart = 40_000L))
    }

    @Test
    fun `a measurement with no recorded placement is never finished by Main's return`() {
        val main = begun("main", MimirGamePlacement.MAIN, dispatchedAt = 10_000L)
        // Home's Android-game launches, and documents written before placement was recorded.
        val unknown = begun("unknown", placement = null, dispatchedAt = 10_000L)

        assertEquals("main", orphanToken(main))
        assertNull(orphanToken(unknown))
    }

    @Test
    fun `after a reboot the dead process's measurement is dropped without inventing time`() {
        // elapsedRealtime restarted with the boot, so the old dispatch time is AFTER this process's start.
        var document = begun("token", MimirGamePlacement.MAIN, dispatchedAt = 900_000L)

        document = mainReturnFinish(document, processStart = 20_000L, now = 60_000L, bootCount = Boot + 1)

        assertNull(document.active)
        assertEquals(0L, document.elapsedTotals[zelda])
    }

    @Test
    fun `an unreadable boot count leaves the measurement untouched`() {
        val document = begun("token", MimirGamePlacement.MAIN, dispatchedAt = 10_000L)

        assertEquals("token", orphanToken(document))
        assertNull(orphanToken(document, bootCount = null))
    }

    @Test
    fun `the measured game's placement survives the persisted document`() {
        val document = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(zelda to 0L),
            active = HomeExternalSession(zelda, "token", 10_000L, Boot, MimirGamePlacement.BOTTOM),
        )

        val restored = parseHomeExternalSessionElapsedDocument(encodeHomeExternalSessionElapsedDocument(document))

        assertEquals(MimirGamePlacement.BOTTOM, restored.active?.placement)
        assertEquals(document, restored)
        // An unrecognised value reads as unknown, never as MAIN.
        val future = encodeHomeExternalSessionElapsedDocument(document).replace("\"BOTTOM\"", "\"SIDEWAYS\"")
        assertNull(parseHomeExternalSessionElapsedDocument(future).active?.placement)
    }

    @Test
    fun `a launch records the display its game owns`() {
        assertEquals(MimirGamePlacement.MAIN, homeExternalSessionPlacement(LaunchDispatchDestination.Main))
        assertEquals(
            MimirGamePlacement.BOTTOM,
            homeExternalSessionPlacement(LaunchDispatchDestination.Secondary { true }),
        )
        assertEquals(
            MimirGamePlacement.BOTTOM,
            begun("token", MimirGamePlacement.BOTTOM, dispatchedAt = 10_000L).active?.placement,
        )
    }

    private val zelda = HomeShelfItemId.Emulated(GameId("catalog:gbc:zelda-oracle-of-ages"))

    private fun begun(
        token: String,
        placement: MimirGamePlacement?,
        dispatchedAt: Long,
    ): HomeExternalSessionElapsedDocument = beginHomeExternalSession(
        document = HomeExternalSessionElapsedDocument(),
        itemId = zelda,
        returnToken = token,
        dispatchedAtElapsedMillis = dispatchedAt,
        bootCount = Boot,
        placement = placement,
    )

    private fun orphanToken(
        document: HomeExternalSessionElapsedDocument,
        processStart: Long = 40_000L,
        bootCount: Int? = Boot,
    ): String? = homeExternalSessionOrphanToken(
        document = document,
        processStartElapsedMillis = processStart,
        currentBootCount = bootCount,
    )

    /** `HomeExternalSessionElapsedStore.finishOrphanedMainReturn` on the pure reducers. */
    private fun mainReturnFinish(
        document: HomeExternalSessionElapsedDocument,
        processStart: Long,
        now: Long,
        bootCount: Int? = Boot,
    ): HomeExternalSessionElapsedDocument =
        orphanToken(document, processStart, bootCount)
            ?.let { token -> finalizeHomeExternalSession(document, token, now, bootCount) }
            ?: document

    private class InMemoryReturnRecord : HomeLaunchReturnPersistence {
        private var record: HomeLaunchReturnRecord? = null

        override fun read(): HomeLaunchReturnRecord = record ?: HomeLaunchReturnRecord(null, null, null, null, null)

        override fun replace(record: HomeLaunchReturnRecord): Boolean {
            this.record = record
            return true
        }

        override fun clear(): Boolean {
            record = null
            return true
        }
    }

    private companion object {
        const val Boot = 3
    }
}
