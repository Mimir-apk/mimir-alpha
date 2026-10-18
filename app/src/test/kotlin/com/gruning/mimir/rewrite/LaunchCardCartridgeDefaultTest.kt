package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  for the LAUNCH CARD state.
 *
 * Previously, a game opened from a Tome showed the shelf's art instead of the cartridge. It was
 * not a crash and not a mis-wire — the second tier said *inherit whatever the Shelf resolved*, so the
 * card's face was decided entirely by a switch that rules shelves. The LAUNCH CARD state now has a
 * default of its own.
 */
class LaunchCardCartridgeDefaultTest {

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: error("$name not found")

    private val host by lazy { source("MimirAppHost.kt") }

    @Test
    fun `the card no longer takes the shelf face as its default`() {
        // Scoped to the ladder BODY. The `LaunchedEffect(...)` key list above it names `active.row.faceUri`
        // too, and including it would compare a dependency against a tier.
        val ladder = host.substringAfter("val cacheKey = homeTileMenuCacheKey(row.itemId)")
            .substringBefore("\n    if (backEnabled)")
        val cartridgeAt = ladder.indexOf("TomeCartridgeArtStore.activeUri")
        val shelfFaceAt = ladder.indexOf("row.faceUri")
        assertTrue("the cartridge tier must exist at all", cartridgeAt > 0)
        assertTrue("and the shelf face must survive as the LAST tier, not the first", shelfFaceAt > cartridgeAt)
    }

    /**
     * The tier is deliberately outside the Cartridge Hero switch. If someone later folds it back under
     * `tomeCartridgeArtVisibility.enabled`, the shelf-art substitution returns the moment the switch is off —
     * which is the shipping default.
     */
    @Test
    fun `the cartridge tier is not gated on the shelves switch`() {
        val ladder = host.substringAfter("// L2 -- THE CARTRIDGE").substringBefore("// L3")
        assertTrue(
            "the cartridge tier must not consult the shelves switch; it reads:\n$ladder",
            !ladder.contains("tomeCartridgeArtVisibility"),
        )
        assertTrue("and a hand-picked cartridge outranks the provider render", ladder.indexOf(
            "TomeCartridgeArtStore.activeUri",
        ) < ladder.indexOf("row.physicalMediaUri"))
    }

    /**
     * SILENT DATA LOSS, fixed: an uploaded pick has no provider media type, so no kind was recorded — and
     * `wiredFaceKind` answers null for LAUNCH_CARD without a record, so `resolveWiredFace` found no face. The file
     * was written and never read back.
     */
    @Test
    fun `an uploaded cover pick still records a kind`() {
        val enrich = source("EnrichSavedStore.kt")
        assertTrue(
            "wiredFaceKindOf returns null for an upload; the fallback is what keeps the pick readable",
            enrich.contains("wiredFaceKindOf(asset.providerMediaType) ?: WiredFaceKind.COVER"),
        )
        assertTrue(
            "and the record must not be conditional on the kind being derivable any more",
            !enrich.contains("wiredFaceKindOf(asset.providerMediaType)?.let"),
        )
    }

    /**
     * The line that reads like a bug and is not: the picker pre-applies [mediaStorageKey], so the key
     * arriving at the record site is already state-scoped and SHELF is the identity that writes it
     * verbatim. Passing the real state here would prefix it twice and orphan every launch-card pick.
     */
    @Test
    fun `the launch-card record key is what the resolver reads back`() {
        val bare = "game-123"
        val asThePickerBuildsIt = mediaStorageKey(bare, MediaState.LAUNCH_CARD)
        val asTheRecordWritesIt = mediaStorageKey(asThePickerBuildsIt, MediaState.SHELF)
        val asTheResolverReadsIt = mediaStorageKey(bare, MediaState.LAUNCH_CARD)
        assertEquals(
            "double-prefixing here would orphan the pick silently",
            asTheResolverReadsIt,
            asTheRecordWritesIt,
        )
        assertEquals("launch-card#$bare", asTheRecordWritesIt)
    }
}
