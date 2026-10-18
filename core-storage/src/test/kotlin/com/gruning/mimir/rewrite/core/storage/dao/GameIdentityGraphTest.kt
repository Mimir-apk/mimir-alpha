package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIdentityGraphTest {

    @Test
    fun `historical terminal chains close into one deterministic component`() {
        val links = listOf(
            link("entry-a", "A", null, primary = true),
            link("entry-b", "B", "A"),
            link("entry-c", "C", "B"),
            link("entry-d", "D", null, primary = true),
        )

        assertEquals(listOf(listOf("A", "B", "C")), groupedIdentityComponents(links))
    }

    @Test
    fun `winner prefers a true group primary then SAF then stable ids`() {
        val links = listOf(
            link("z", "managed-primary", null, primary = true, location = "MANAGED_PATH"),
            link("a", "saf-loser", "managed-primary", location = "SAF_TREE_GRANT"),
            link("b", "fake-primary", "managed-primary", primary = true, location = "SAF_TREE_GRANT"),
        )

        assertEquals(
            "managed-primary",
            chooseLogicalWinner(links, setOf("managed-primary", "saf-loser", "fake-primary")),
        )
    }

    @Test
    fun `winner falls back to SAF and lexical identity when no root primary survives`() {
        val links = listOf(
            link("z", "managed", "missing", location = "MANAGED_PATH"),
            link("b", "saf-z", "missing", location = "SAF_TREE_GRANT"),
            link("a", "saf-a", "missing", location = "SAF_TREE_GRANT"),
        )

        assertEquals(
            "saf-a",
            chooseLogicalWinner(links, setOf("managed", "saf-z", "saf-a")),
        )
    }

    @Test
    fun `opaque resident key spelling cannot steer logical winner selection`() {
        val physicalFirstKey = physicalResidentKey("aa", "same.rom")
        val physicalSecondKey = physicalResidentKey("z", "same.rom")
        assertTrue(physicalFirstKey > physicalSecondKey)
        val firstCodecOrder = listOf(
            link(
                entry = physicalFirstKey,
                game = "a-game",
                parent = "missing",
                sourceRootUri = "aa",
                relativePath = "same.rom",
            ),
            link(
                entry = physicalSecondKey,
                game = "b-game",
                parent = "missing",
                sourceRootUri = "z",
                relativePath = "same.rom",
            ),
        )

        assertEquals(
            "a-game",
            chooseLogicalWinner(firstCodecOrder, setOf("a-game", "b-game")),
        )
        assertEquals(
            "a-game",
            chooseLogicalWinner(firstCodecOrder.reversed(), setOf("a-game", "b-game")),
        )
    }

    @Test
    fun `missing historical parent promotes the surviving physical child`() {
        val links = listOf(
            link("survivor-entry", "survivor", "removed-parent", location = "SAF_TREE_GRANT"),
        )
        val component = groupedIdentityComponents(links).single()

        assertEquals(listOf("removed-parent", "survivor"), component)
        assertEquals("survivor", chooseLogicalWinner(links, component.toSet()))
    }

    @Test
    fun `playlist and manifest rank ahead of lexicographic disc members during rescue`() {
        val links = listOf(
            link(
                entry = "disc-two",
                game = "a-disc-two",
                parent = "removed-parent",
                contentKind = "DISC_MEMBER",
                discNumber = 2,
            ),
            link(
                entry = "disc-one",
                game = "b-disc-one",
                parent = "removed-parent",
                contentKind = "DISC_MEMBER",
                discNumber = 1,
            ),
            link(
                entry = "set-playlist",
                game = "z-playlist",
                parent = "removed-parent",
                contentKind = "MULTI_DISC_PLAYLIST",
            ),
        )

        assertEquals(
            "z-playlist",
            chooseLogicalWinner(links, groupedIdentityComponents(links).single().toSet()),
        )
    }

    @Test
    fun `available alias outranks an unavailable physical primary`() {
        val links = listOf(
            link(
                entry = "primary-on-ejected-card",
                game = "card-copy",
                parent = null,
                primary = true,
                availability = "STORAGE_UNAVAILABLE",
            ),
            link(
                entry = "surviving-copy",
                game = "internal-copy",
                parent = "card-copy",
                availability = "AVAILABLE",
            ),
        )

        assertEquals(
            "internal-copy",
            chooseLogicalWinner(links, setOf("card-copy", "internal-copy")),
        )
    }

    @Test
    fun `card swap applies departures and arrivals before one winner reconcile`() = runBlocking {
        val state = mutableMapOf(
            "departing" to "AVAILABLE",
            "arriving" to "STORAGE_UNAVAILABLE",
        )
        var reconciles = 0

        val result = applyRootAvailabilitySnapshot(
            absentRootUris = listOf("departing", "arriving"),
            presentRootUris = listOf("arriving"),
            logicalIdsForRoots = { roots ->
                assertEquals(setOf("departing", "arriving"), roots.toSet())
                listOf("lg:swap")
            },
            markUnavailable = { roots ->
                roots.count { root ->
                    state[root] == "AVAILABLE" &&
                        state.put(root, "STORAGE_UNAVAILABLE") == "AVAILABLE"
                }
            },
            restoreAvailable = { roots ->
                roots.count { root ->
                    state[root] == "STORAGE_UNAVAILABLE" &&
                        state.put(root, "AVAILABLE") == "STORAGE_UNAVAILABLE"
                }
            },
            reconcile = { logicalIds ->
                reconciles++
                assertEquals(listOf("lg:swap"), logicalIds)
                assertEquals("STORAGE_UNAVAILABLE", state["departing"])
                assertEquals("AVAILABLE", state["arriving"])
            },
        )

        assertEquals(RootAvailabilityMutation(1, 1), result)
        assertEquals(1, reconciles)
    }

    @Test
    fun `journal mask covers every distinct store without overlap`() {
        val bits = listOf(
            REMAP_STORE_HOME_TOMES,
            REMAP_STORE_PLAY_TIME,
            REMAP_STORE_HOME_SURFACE,
            REMAP_STORE_HOME_RETURN,
            REMAP_STORE_COMPANION_RETURN,
            REMAP_STORE_CUSTOM_HERO,
            REMAP_STORE_ACTIVE_ART,
            REMAP_STORE_SAVED_ART,
            REMAP_STORE_ART_RESOLUTION,
            REMAP_STORE_ART_NO_MATCH,
            REMAP_STORE_TWEAKER_SELECTION,
        )

        assertEquals(bits.size, bits.distinct().size)
        assertTrue(bits.all { it > 0L && it.countOneBits() == 1 })
        assertEquals(bits.fold(0L, Long::or), REMAP_ALL_STORES_MASK)
    }

    @Test
    fun `legacy catalog metadata follows the previous winner into the current game key`() {
        val previousWinner = metadata(
            key = "screenscraper:ps1:resident-evil-2-disc-1",
            description = "The complete synopsis.",
            cachedAtMillis = 50,
        )
        val targetCatalogFallback = metadata(
            key = "screenscraper:ps1:resident-evil-2-disc-2",
            description = null,
            cachedAtMillis = 100,
        )

        val remapped = metadataCopyForIdentityRemap(
            metadata = listOf(previousWinner, targetCatalogFallback),
            targetGameId = "disc-2",
            preferredKeys = setOf("game:disc-1", previousWinner.catalogKey),
            preferPreviousWinner = true,
        )

        assertEquals("game:disc-2", remapped?.catalogKey)
        assertEquals("The complete synopsis.", remapped?.description)
    }

    private fun link(
        entry: String,
        game: String,
        parent: String?,
        primary: Boolean = false,
        location: String = "MANAGED_PATH",
        availability: String = "AVAILABLE",
        contentKind: String = "ROM",
        discNumber: Int = 0,
        sourceRootUri: String = "root",
        relativePath: String = entry,
    ) = GroupedIdentityLink(
        entryKey = entry,
        sourceRootUri = sourceRootUri,
        relativePath = relativePath,
        gameId = game,
        duplicateGroupId = parent,
        duplicateGroupKind = "EXACT_HASH",
        isPrimaryMember = primary,
        locationKind = location,
        availability = availability,
        contentKind = contentKind,
        discNumber = discNumber,
    )

    private fun metadata(
        key: String,
        description: String?,
        cachedAtMillis: Long,
    ) = TitleMetadataCacheEntity(
        catalogKey = key,
        platformId = "ps1",
        canonicalTitle = "Resident Evil 2",
        region = null,
        version = null,
        cachedAtMillis = cachedAtMillis,
        description = description,
        source = "screenscraper:en",
    )
}
