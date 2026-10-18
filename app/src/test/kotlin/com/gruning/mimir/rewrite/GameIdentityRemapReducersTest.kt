package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemKind
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIdentityRemapReducersTest {
    private val from = GameId("game-A")
    private val to = GameId("game-B")

    @Test
    fun `Tome remap deduplicates membership and sparse seats without touching Android games`() {
        val android = FavoriteMemberId.AndroidGame(AndroidPackageName("com.example.android"))
        val fromMember = FavoriteMemberId.Emulated(from)
        val toMember = FavoriteMemberId.Emulated(to)
        val tome = CustomHomeTome(
            id = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            label = "RPG",
            members = listOf(fromMember, android, toMember),
            memberSlots = listOf(fromMember, null, toMember, android),
        )
        val document = HomeTomeDocument(
            customTomes = listOf(tome),
            platformOrderOverlays = listOf(
                PlatformTomeOrderOverlay(
                    platformId = PlatformId("ps1"),
                    gameOrder = listOf(from, to),
                    gameSlots = listOf(from, null, to),
                ),
            ),
        )

        val mapped = remapHomeTomeGameId(document, from, to)
        val mappedTome = mapped.customTomes.single()
        assertEquals(listOf(toMember, android), mappedTome.members)
        assertFalse(mappedTome.memberSlots.filterNotNull().contains(fromMember))
        assertEquals(1, mappedTome.memberSlots.count { it == toMember })
        assertEquals(listOf(to), mapped.platformOrderOverlays.single().gameOrder)
        assertEquals(1, mapped.platformOrderOverlays.single().gameSlots.count { it == to })
        assertEquals(mapped, remapHomeTomeGameId(mapped, from, to))
    }

    @Test
    fun `external session elapsed remap saturates totals and preserves active return token`() {
        val fromItem = HomeShelfItemId.Emulated(from)
        val toItem = HomeShelfItemId.Emulated(to)
        val active = HomeExternalSession(fromItem, "token", 99L, bootCount = 7)
        val document = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(fromItem to 8L, toItem to Long.MAX_VALUE - 3L),
            active = active,
        )

        val mapped = remapHomeExternalSessionGameId(document, from, to)
        assertEquals(Long.MAX_VALUE, mapped.elapsedTotals[toItem])
        assertFalse(fromItem in mapped.elapsedTotals)
        assertEquals(active.copy(itemId = toItem), mapped.active)
        assertEquals(mapped, remapHomeExternalSessionGameId(mapped, from, to))
    }

    @Test
    fun `Home surface and return anchors remap every emulated root only`() {
        val snapshot = HomeSurfaceSnapshot(
            editorsChoiceRoot = HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(from)),
            pinnedTomesRoot = HomeRootEntryRecord.Tome(TomeId.Platform(PlatformId("ps1"))),
        )
        val mappedSnapshot = remapHomeSurfaceGameId(snapshot, from, to)
        assertEquals(
            HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(to)),
            mappedSnapshot.editorsChoiceRoot,
        )
        assertEquals(snapshot.pinnedTomesRoot, mappedSnapshot.pinnedTomesRoot)

        val record = HomeLaunchReturnRecord(
            schemaVersion = 6,
            kind = HomeShelfItemKind.EMULATED_GAME.name,
            identity = from.value,
            rank = 7L,
            token = "return",
            rootKind = "GAME_EMULATED_GAME",
            rootIdentity = from.value,
            fallbackRootKind = "GAME_EMULATED_GAME",
            fallbackRootIdentity = from.value,
        )
        val mappedRecord = remapHomeLaunchReturnRecord(record, from, to)
        assertEquals(to.value, mappedRecord.identity)
        assertEquals(to.value, mappedRecord.rootIdentity)
        assertEquals(to.value, mappedRecord.fallbackRootIdentity)
        assertEquals(mappedRecord, remapHomeLaunchReturnRecord(mappedRecord, from, to))
    }

    @Test
    fun `companion origin remaps typed game arguments and exact focus key only`() {
        val origin = PrimaryReturnOrigin(
            destination = "LIBRARY",
            arguments = mapOf(
                "detailGameId" to from.value,
                "focusKey" to "library/gamelist/${from.value}",
                "gameSelection" to from.value,
                "unrelated" to from.value,
            ),
            revision = 9L,
        )

        val mapped = origin.remapGameId(from.value, to.value)
        assertEquals(to.value, mapped.arguments["detailGameId"])
        assertEquals("library/gamelist/${to.value}", mapped.arguments["focusKey"])
        assertEquals(to.value, mapped.arguments["gameSelection"])
        assertEquals(from.value, mapped.arguments["unrelated"])
        assertEquals(mapped, mapped.remapGameId(from.value, to.value))
    }

    @Test
    fun `saved gallery merge stays bounded and keeps the adopted source active choice`() {
        val target = EnrichSavedCatalog(
            activeAssetId = null,
            entries = (1..EnrichMediaKind.ICON.capacity).map { asset("target-$it", it.toLong()) },
        )
        val source = EnrichSavedCatalog(
            activeAssetId = "source-active",
            entries = listOf(asset("source-old", 1L), asset("source-active", 99L)),
        )

        val merged = mergeEnrichSavedCatalog(EnrichMediaKind.ICON, target, source)
        assertEquals(EnrichMediaKind.ICON.capacity, merged.entries.size)
        assertEquals("source-active", merged.activeAssetId)
        assertTrue(merged.entries.any { it.assetId == "source-active" })
        assertEquals(merged, mergeEnrichSavedCatalog(EnrichMediaKind.ICON, merged, source))
        assertEquals((1..6).map { "target-$it" }, target.entries.map { it.assetId })
        assertEquals(2, source.entries.size)
    }

    @Test
    fun `media generations preserve the newest selected choice through recurring winners`() {
        val artOne = EnrichSavedCatalog(
            activeAssetId = "art-1",
            entries = listOf(asset("art-1", 10L)),
            selection = GameIdentityMediaSelection.SELECTED,
            selectionChangedAtMillis = 10L,
        )
        val empty = EnrichSavedCatalog(activeAssetId = null, entries = emptyList())
        val firstB = mergeEnrichSavedCatalog(EnrichMediaKind.HERO, empty, artOne)
        assertEquals("art-1", firstB.activeAssetId)

        val userChangedB = EnrichSavedCatalog(
            activeAssetId = "art-2",
            entries = firstB.entries + asset("art-2", 20L),
            selection = GameIdentityMediaSelection.SELECTED,
            selectionChangedAtMillis = 20L,
        )
        val currentA = mergeEnrichSavedCatalog(EnrichMediaKind.HERO, artOne, userChangedB)
        assertEquals("art-2", currentA.activeAssetId)
        assertEquals(20L, currentA.selectionChangedAtMillis)

        val currentBAgain = mergeEnrichSavedCatalog(EnrichMediaKind.HERO, userChangedB, currentA)
        assertEquals("art-2", currentBAgain.activeAssetId)
        assertEquals(20L, currentBAgain.selectionChangedAtMillis)
    }

    @Test
    fun `newer Automatic clears an older selection and ties stay target owned`() {
        val selected = EnrichSavedCatalog(
            activeAssetId = "art-1",
            entries = listOf(asset("art-1", 10L)),
            selection = GameIdentityMediaSelection.SELECTED,
            selectionChangedAtMillis = 10L,
        )
        val automatic = EnrichSavedCatalog(
            activeAssetId = null,
            entries = emptyList(),
            selection = GameIdentityMediaSelection.AUTOMATIC,
            selectionChangedAtMillis = 20L,
        )
        val cleared = mergeEnrichSavedCatalog(EnrichMediaKind.ICON, selected, automatic)
        assertEquals(GameIdentityMediaSelection.AUTOMATIC, cleared.selection)
        assertEquals(null, cleared.activeAssetId)

        val equalSource = selected.copy(selectionChangedAtMillis = 20L)
        val tied = mergeEnrichSavedCatalog(EnrichMediaKind.ICON, automatic, equalSource)
        assertEquals(GameIdentityMediaSelection.AUTOMATIC, tied.selection)
        assertEquals(null, tied.activeAssetId)
    }

    @Test
    fun `media source decision is shared across all renderer kinds`() {
        val olderSelected = GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, 5L)
        val newerAutomatic = GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.AUTOMATIC, 9L)
        val never = GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.NEVER_CHOSEN, 0L)

        EnrichMediaKind.entries.forEach {
            assertTrue(gameIdentityMediaSourceWins(olderSelected, never))
            assertTrue(gameIdentityMediaSourceWins(newerAutomatic, olderSelected))
            assertFalse(gameIdentityMediaSourceWins(olderSelected, newerAutomatic))
            assertFalse(gameIdentityMediaSourceWins(newerAutomatic, newerAutomatic))
        }
    }

    @Test
    fun `external session elapsed moves conserved totals through recurring winners without duplication`() {
        val fromItem = HomeShelfItemId.Emulated(from)
        val toItem = HomeShelfItemId.Emulated(to)
        val initial = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(fromItem to 10L),
            active = HomeExternalSession(fromItem, "stable-token", 100L, bootCount = 7),
        )

        val onB = remapHomeExternalSessionGameId(initial, from, to)
        val withNewElapsed = onB.copy(elapsedTotals = onB.elapsedTotals + (toItem to 15L))
        val backOnA = remapHomeExternalSessionGameId(withNewElapsed, to, from)
        val onBAgain = remapHomeExternalSessionGameId(backOnA, from, to)

        assertEquals(mapOf(toItem to 15L), onBAgain.elapsedTotals)
        assertEquals(
            HomeExternalSession(toItem, "stable-token", 100L, bootCount = 7),
            onBAgain.active,
        )
    }

    @Test
    fun `saved gallery writer gate prevents stale read modify write interleaving`() = runBlocking {
        val gate = EnrichSavedMutationGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            gate.run {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val second = async {
            gate.run { order += "second" }
        }
        yield()
        assertFalse(second.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun `identity reducers return the original instance for an identity no-op`() {
        val document = HomeTomeDocument()
        assertSame(document, remapHomeTomeGameId(document, from, from))
    }

    private fun asset(id: String, addedAt: Long) = EnrichSavedAsset(
        assetId = id,
        source = EnrichSavedSource.UPLOAD,
        label = id,
        localPath = "/retained/$id.webp",
        previewPath = "/retained/$id.webp",
        width = 512,
        height = 512,
        bytes = 100L,
        addedAt = addedAt,
    )
}
