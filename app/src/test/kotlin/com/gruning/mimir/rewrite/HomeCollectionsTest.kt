package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCollectionsTest {
    @Test
    fun `home tome v1 round trip preserves typed identity order pins dormancy and overlays`() {
        val customId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val document = HomeTomeDocument(
            pinOrder = listOf(TomeId.Platform(PlatformId("snes")), customId),
            customTomes = listOf(
                CustomHomeTome(
                    id = customId,
                    label = "RPGs",
                    members = listOf(
                        FavoriteMemberId.Emulated(GameId("chrono")),
                        FavoriteMemberId.AndroidGame(AndroidPackageName("com.game")),
                    ),
                    manualOrderCommitted = true,
                    createdAtMillis = 42L,
                    memberSlots = listOf(
                        FavoriteMemberId.Emulated(GameId("chrono")),
                        null,
                        FavoriteMemberId.AndroidGame(AndroidPackageName("com.game")),
                    ),
                ),
            ),
            platformOrderOverlays = listOf(
                PlatformTomeOrderOverlay(
                    PlatformId("snes"),
                    listOf(GameId("b"), GameId("a")),
                    listOf(GameId("b"), null, GameId("a")),
                ),
            ),
            homeShelfSlots = listOf(
                FavoriteMemberId.Emulated(GameId("chrono")),
                null,
                FavoriteMemberId.AndroidGame(AndroidPackageName("com.game")),
            ),
        )

        assertEquals(document, parseHomeTomeDocument(encodeHomeTomeDocument(document)))
    }

    @Test
    fun `legacy import mints stable ids per row and never admits android apps`() {
        val raw = """[
          {"name":"Same","createdAtMillis":7,"shownAsTome":true,"members":[
            {"kind":"EMULATED_GAME","id":"g1"},
            {"kind":"ANDROID_APP","id":"com.app"}
          ]},
          {"name":"Same","createdAtMillis":7,"shownAsTome":true,"members":[]}
        ]"""

        val first = importLegacyHomeTomes(v3Raw = raw, v2Raw = null, v1Raw = null)
        val retry = importLegacyHomeTomes(v3Raw = raw, v2Raw = null, v1Raw = null)

        assertEquals(first, retry)
        assertNotEquals(first.customTomes[0].id, first.customTomes[1].id)
        assertEquals(listOf(FavoriteMemberId.Emulated(GameId("g1"))), first.customTomes[0].members)
        assertEquals(first.customTomes.map(CustomHomeTome::id), first.pinOrder)
    }

    @Test
    fun `normalization removes dangling custom pins and duplicate order without name identity`() {
        val kept = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val missing = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000003"))
        val normalized = normalizeHomeTomeDocument(
            HomeTomeDocument(
                pinOrder = listOf(kept, missing, kept),
                customTomes = listOf(CustomHomeTome(kept, "Keep", emptyList())),
            ),
        )

        assertEquals(listOf(kept), normalized.pinOrder)
    }

    @Test
    fun `aggregate Home overview cannot leak into pinned Tome identity`() {
        val normalized = normalizeHomeTomeDocument(
            HomeTomeDocument(pinOrder = listOf(TomeId.HomeOverview)),
        )

        assertTrue(normalized.pinOrder.isEmpty())
        // Store refusal at setPinned(HomeOverview) mirrors this strip: overview never joins pinOrder.
        assertFalse(TomeId.HomeOverview in normalized.pinOrder)
    }

    @Test
    fun `custom mutation is stable-id scoped and preserves dormant member order`() {
        val firstId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000004"))
        val secondId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000005"))
        val member = FavoriteMemberId.Emulated(GameId("g1"))
        val tomes = listOf(
            CustomHomeTome(firstId, "Same", emptyList()),
            CustomHomeTome(secondId, "Same", emptyList()),
        )

        val added = toggleCustomTomeMember(tomes, secondId, member)

        assertTrue(added[0].members.isEmpty())
        assertEquals(listOf(member), added[1].members)
        assertFalse(added[1].manualOrderCommitted)
    }

    @Test
    fun `custom Tome reorder persists the visible order and retains dormant members`() {
        val tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000006"))
        val first = FavoriteMemberId.Emulated(GameId("first"))
        val second = FavoriteMemberId.Emulated(GameId("second"))
        val dormant = FavoriteMemberId.Emulated(GameId("dormant"))
        val tomes = listOf(CustomHomeTome(tomeId, "Ordered", listOf(first, second, dormant)))

        val committed = commitCustomTomeMemberOrder(tomes, tomeId, listOf(second, first)).single()

        assertEquals(listOf(second, first, dormant), committed.members)
        assertTrue(committed.manualOrderCommitted)
    }

    @Test
    fun `custom Tome sparse commit preserves empty wells and compact dormant membership truth`() {
        val tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000007"))
        val first = FavoriteMemberId.Emulated(GameId("first"))
        val second = FavoriteMemberId.Emulated(GameId("second"))
        val dormant = FavoriteMemberId.Emulated(GameId("dormant"))
        val tomes = listOf(CustomHomeTome(tomeId, "Sparse", listOf(first, second, dormant)))

        val committed = commitCustomTomeMemberSlots(
            tomes,
            tomeId,
            listOf(second, null, null, first),
        ).single()

        assertEquals(listOf(second, first, dormant), committed.members)
        assertEquals(listOf(second, null, null, first), committed.memberSlots)
        assertTrue(committed.manualOrderCommitted)
    }

    @Test
    fun `custom Tome sparse commit moves an Android game without changing its typed identity`() {
        val tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000008"))
        val android = FavoriteMemberId.AndroidGame(AndroidPackageName("com.example.game"))
        val tome = CustomHomeTome(tomeId, "Android", listOf(android))

        val committed = commitCustomTomeMemberSlots(
            tomes = listOf(tome),
            tomeId = tomeId,
            slots = listOf(null, null, android),
        ).single()

        assertEquals(listOf(android), committed.members)
        assertEquals(listOf(null, null, android), committed.memberSlots)
        assertTrue(committed.manualOrderCommitted)
    }
}
