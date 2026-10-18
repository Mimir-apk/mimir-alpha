package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSurfaceStateStoreTest {
    @Test
    fun `typed game and Tome roots survive a new ledger instance`() {
        val snapshots = listOf(
            HomeSurfaceSnapshot(
                editorsChoiceRoot = HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId("chrono"))),
            ),
            HomeSurfaceSnapshot(
                editorsChoiceRoot = HomeRootEntryRecord.Game(
                    HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")),
                ),
            ),
            HomeSurfaceSnapshot(
                mode = HomeViewMode.PINNED_TOMES,
                editorsChoiceRoot = HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId("chrono"))),
                pinnedTomesRoot = HomeRootEntryRecord.Tome(TomeId.Platform(PlatformId("ps1"))),
            ),
            HomeSurfaceSnapshot(
                editorsChoiceRoot = HomeRootEntryRecord.Tome(TomeId.Custom(UUID.randomUUID())),
            ),
            HomeSurfaceSnapshot(
                editorsChoiceRoot = HomeRootEntryRecord.Tome(TomeId.HomeOverview),
            ),
            HomeSurfaceSnapshot(),
        )

        snapshots.forEach { expected ->
            val persistence = FakePersistence()
            assertTrue(HomeSurfaceLedger(persistence).replace(expected))
            assertEquals(expected, HomeSurfaceLedger(persistence).read())
        }
    }

    @Test
    fun `schema v1 valid game focus migrates without retaining context identity`() {
        val record = HomeSurfaceRecord(1, "platform/ps1", "EMULATED_GAME", "chrono")
        assertEquals(
            HomeSurfaceSnapshot(
                editorsChoiceRoot = HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId("chrono"))),
            ),
            HomeSurfaceLedger(FakePersistence(record)).read(),
        )
    }

    @Test
    fun `legacy Android app is discarded and replaced by an empty typed root`() {
        val persistence = FakePersistence(
            HomeSurfaceRecord(1, "android-apps", "ANDROID_APP", "com.example.app"),
        )
        assertNull(HomeSurfaceLedger(persistence).read())
        assertEquals(
            HomeSurfaceRecord(3, null, null, null, mode = HomeViewMode.EDITORS_CHOICE.name),
            persistence.record,
        )
    }

    @Test
    fun `malformed future roots fail closed`() {
        val invalid = listOf(
            HomeSurfaceRecord(null, null, null, null),
            HomeSurfaceRecord(2, null, "FUTURE", "x"),
            HomeSurfaceRecord(2, null, "GAME_EMULATED", null),
            HomeSurfaceRecord(3, null, null, null),
            HomeSurfaceRecord(3, null, null, null, mode = HomeViewMode.EDITORS_CHOICE.name,
                editorsChoiceKind = "GAME_EMULATED", editorsChoiceIdentity = null),
            HomeSurfaceRecord(4, null, null, null),
        )
        invalid.forEach { assertNull(HomeSurfaceLedger(FakePersistence(it)).read()) }
    }

    @Test
    fun `failed durable replacement leaves preceding root intact`() {
        val original = HomeSurfaceRecord(2, null, null, null)
        val persistence = FakePersistence(original, replaceAllowed = false)
        assertFalse(
            HomeSurfaceLedger(persistence).replace(
                HomeSurfaceSnapshot(
                    editorsChoiceRoot = HomeRootEntryRecord.Tome(TomeId.Platform(PlatformId("n64"))),
                ),
            ),
        )
        assertEquals(original, persistence.record)
    }

    @Test
    fun `restore waits for root truth and returns exact typed entry`() {
        val item = HomeShelfItemId.Emulated(GameId("chrono"))
        val stored = HomeSurfaceSnapshot(editorsChoiceRoot = HomeRootEntryRecord.Game(item))
        assertEquals(HomeSurfaceRestoreResolution.AwaitingTruth, resolveHomeSurfaceRestore(stored, null, null))
        assertEquals(
            HomeSurfaceRestoreResolution.Ready(stored),
            resolveHomeSurfaceRestore(stored, listOf(HomeShelfEntry.Game(item)), emptyList()),
        )
    }

    @Test
    fun `vanished typed root requests safe Favorite fallback`() {
        val stored = HomeSurfaceSnapshot(
            editorsChoiceRoot = HomeRootEntryRecord.Tome(
                TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            ),
        )
        assertEquals(
            HomeSurfaceRestoreResolution.Ready(
                HomeSurfaceSnapshot(
                    editorsChoiceRoot = HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId("other"))),
                ),
            ),
            resolveHomeSurfaceRestore(
                stored,
                listOf(HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("other")))),
                emptyList(),
            ),
        )
    }

    @Test
    fun `schema v2 migrates to Editors Choice and empty pinned mode falls back safely`() {
        val legacy = HomeSurfaceRecord(2, null, "GAME_EMULATED", "chrono")
        assertEquals(
            HomeSurfaceSnapshot(
                editorsChoiceRoot = HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId("chrono"))),
            ),
            HomeSurfaceLedger(FakePersistence(legacy)).read(),
        )

        val pinned = HomeSurfaceSnapshot(
            mode = HomeViewMode.PINNED_TOMES,
            editorsChoiceRoot = HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId("chrono"))),
            pinnedTomesRoot = HomeRootEntryRecord.Tome(TomeId.Platform(PlatformId("snes"))),
        )
        assertEquals(
            HomeViewMode.EDITORS_CHOICE,
            (resolveHomeSurfaceRestore(
                pinned,
                listOf(HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("chrono")))),
                emptyList(),
            ) as HomeSurfaceRestoreResolution.Ready).snapshot.mode,
        )
    }

    private class FakePersistence(
        var record: HomeSurfaceRecord? = null,
        var replaceAllowed: Boolean = true,
    ) : HomeSurfacePersistence {
        override fun read(): HomeSurfaceRecord = record ?: HomeSurfaceRecord(null, null, null, null)

        override fun replace(record: HomeSurfaceRecord): Boolean {
            if (replaceAllowed) this.record = record
            return replaceAllowed
        }
    }
}
