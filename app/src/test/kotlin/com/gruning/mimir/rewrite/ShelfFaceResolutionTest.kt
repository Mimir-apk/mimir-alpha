package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [resolveShelfFaces] against the real stores, and the seam behind `GameRow.wiredFace`.
 *
 * **The migration proof.** The 2026-10-10 merge moves nothing and renames nothing: `custom-cover-art/`,
 * `tome-cartridge-art/`, `filesDir/sgdb2/` and the prefs are read where they are. So the claim "every existing pick
 * keeps showing" is a claim about files an older build left behind, and these tests build exactly those: a file in a
 * store with NO `mimir_wired_face_kind` record, as every pick made before the record existed, and assert the face
 * that same state painted before this build. Nothing here writes a record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class ShelfFaceResolutionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val autoCover = "file:///media/Game.png"

    private fun writePng(target: File) {
        target.parentFile?.mkdirs()
        target.writeBytes(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
                0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
                0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
                0x0D, 0x0A, 0x2D, 0xB4.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
            ),
        )
    }

    private fun coverPick(key: String): File = CustomCoverStore.activeFile(context, key).also(::writePng)
    private fun articlePick(key: String): File = TomeCartridgeArtStore.activeFile(context, key).also(::writePng)

    /**
     * A tile at the canonical path, optionally carrying the explicit owner-selection marker, made at [pickedAt] (a
     * timeless marker when 0, as a pick that predates the times).
     */
    private fun tile(key: String, ownersPick: Boolean, pickedAt: Long = System.currentTimeMillis()): File {
        val file = SteamGridDbTileFetcher.canonicalTileIconFile(context, key)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        // The automatic batch leaves a sidecar too; the marker, not the sidecar, is what identifies an explicit selection.
        File(file.parentFile, file.name + ".idx").writeText("0")
        if (ownersPick) select(EnrichMediaKind.ICON, key, pickedAt)
        return file
    }

    /** The selection marker a pick through Customize Media leaves: SELECTED, with the time it was made. */
    private fun select(kind: EnrichMediaKind, key: String, at: Long) {
        GameIdentityMediaSelectionStore.write(
            context,
            kind,
            key,
            GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, at),
        )
    }

    private fun portrait(key: String): File {
        val file = SteamGridDbTileFetcher.canonicalPortraitTileIconFile(context, key)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(4, 5, 6))
        return file
    }

    private fun shelf(key: String, hasCover: Boolean, cover: String? = if (hasCover) autoCover else null) =
        resolveShelfFaces(context, key, hasCover, cover).shelf

    private fun card(key: String, hasCover: Boolean, cover: String? = if (hasCover) autoCover else null) =
        resolveShelfFaces(context, key, hasCover, cover).card

    // -- migration: a pick with no record still wins ---------------------------------------------------

    @Test
    fun `a legacy cover pick with no record still wins over the automatic cover`() {
        val key = "shelf-face/legacy-cover"
        coverPick(key)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFaceTier.PICK, r.tier)
        assertEquals(ShelfFaceShape.LEDGE_COVER, r.face.shape)
        assertTrue(r.face.uri!!.contains("custom-cover-art"))
        assertNull("nothing was written to remember it", WiredFaceStore.recorded(context, key, MediaState.SHELF))
    }

    @Test
    fun `a legacy cartridge pick with no record is still the physical face`() {
        val key = "shelf-face/legacy-cartridge"
        articlePick(key)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFaceTier.PICK, r.tier)
        assertEquals(ShelfFaceShape.PHYSICAL, r.face.shape)
        assertTrue(r.face.uri!!.contains("tome-cartridge-art"))
    }

    @Test
    fun `two legacy picks with no record resolve to the cover, deterministically`() {
        val key = "shelf-face/legacy-both"
        coverPick(key)
        articlePick(key)

        val r = shelf(key, hasCover = false)

        assertEquals(ShelfFaceShape.LEDGE_COVER, r.face.shape)
        assertTrue(r.face.uri!!.contains("custom-cover-art"))
    }

    /** Today's Home result for a game holding a player-selected icon: the square wins, with or without a cover. */
    @Test
    fun `an icon he picked before this build is still the square, ahead of a cover and the automatic cover`() {
        val key = "shelf-face/legacy-icon"
        val file = tile(key, ownersPick = true)
        coverPick(key)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFaceTier.PICK, r.tier)
        assertEquals(ShelfFaceShape.SQUARE, r.face.shape)
        assertEquals(versionedFileUri(file), r.face.uri)
    }

    /**
     * His last choice is the NEWEST of his picks. A cover pick writes a record (and a selection marker with its time);
     * an icon pick writes only its marker. Reading the record alone made a cover picked long ago hide an icon picked
     * since, where Home had always shown the icon.
     */
    @Test
    fun `a newer icon beats an older recorded cover`() {
        val key = "shelf-face/newer-icon"
        coverPick(key)
        select(EnrichMediaKind.COVER, key, at = 1_000L)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
        val icon = tile(key, ownersPick = true, pickedAt = 2_000L)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFaceTier.PICK, r.tier)
        assertEquals(ShelfFace(versionedFileUri(icon), ShelfFaceShape.SQUARE), r.face)
    }

    @Test
    fun `a newer recorded cover beats an older icon`() {
        val key = "shelf-face/newer-cover"
        tile(key, ownersPick = true, pickedAt = 1_000L)
        coverPick(key)
        select(EnrichMediaKind.COVER, key, at = 2_000L)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFaceShape.LEDGE_COVER, r.face.shape)
        assertTrue(r.face.uri!!.contains("custom-cover-art"))
    }

    @Test
    fun `a newer article pick beats an older icon, and an older one loses to it`() {
        val newer = "shelf-face/newer-article"
        tile(newer, ownersPick = true, pickedAt = 1_000L)
        articlePick(newer)
        select(EnrichMediaKind.TOME_PHYSICAL, newer, at = 2_000L)
        assertEquals(ShelfFaceShape.PHYSICAL, shelf(newer, hasCover = true).face.shape)

        val older = "shelf-face/older-article"
        tile(older, ownersPick = true, pickedAt = 2_000L)
        articlePick(older)
        select(EnrichMediaKind.TOME_PHYSICAL, older, at = 1_000L)
        assertEquals(ShelfFaceShape.SQUARE, shelf(older, hasCover = true).face.shape)
    }

    /**
     * The icon is compared with the time of the pick that would otherwise WIN, never the newer of his two face picks. An article
     * marker stamped after the icon (an adoption, a replayed identity, a stale pick) must not make an older recorded cover look
     * newer than the icon he picked since: the shelf showed the box again the moment Customize media was opened.
     */
    @Test
    fun `a stale article marker does not make an older recorded cover look newer than the icon`() {
        val key = "shelf-face/stale-article"
        coverPick(key)
        select(EnrichMediaKind.COVER, key, at = 1_000L)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
        val icon = tile(key, ownersPick = true, pickedAt = 2_000L)
        articlePick(key)
        select(EnrichMediaKind.TOME_PHYSICAL, key, at = 3_000L)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFace(versionedFileUri(icon), ShelfFaceShape.SQUARE), r.face)
    }

    @Test
    fun `a stale cover marker does not make an older recorded article look newer than the icon`() {
        val key = "shelf-face/stale-cover"
        articlePick(key)
        select(EnrichMediaKind.TOME_PHYSICAL, key, at = 1_000L)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.PHYSICAL)
        val icon = tile(key, ownersPick = true, pickedAt = 2_000L)
        coverPick(key)
        select(EnrichMediaKind.COVER, key, at = 3_000L)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFace(versionedFileUri(icon), ShelfFaceShape.SQUARE), r.face)
    }

    @Test
    fun `a recorded article newer than the icon still beats it when an older cover sits beside it`() {
        val key = "shelf-face/article-wins"
        coverPick(key)
        select(EnrichMediaKind.COVER, key, at = 500L)
        tile(key, ownersPick = true, pickedAt = 2_000L)
        articlePick(key)
        select(EnrichMediaKind.TOME_PHYSICAL, key, at = 3_000L)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.PHYSICAL)

        assertEquals(ShelfFaceShape.PHYSICAL, shelf(key, hasCover = true).face.shape)
    }

    /** The resolver reads the two times on their own: a source pin that nothing folds them back into one maximum. */
    @Test
    fun `the resolver passes the cover time and the article time separately`() {
        val source = listOf(
            "src/main/kotlin/com/gruning/mimir/rewrite/ShelfFaceResolver.kt",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/ShelfFaceResolver.kt",
        ).map(::File).first { it.isFile }.readText()
        val code = source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }.joinToString(System.lineSeparator())
        assertTrue(code.contains("coverPickedAtMillis = inputs.coverPickedAtMillis"))
        assertTrue(code.contains("physicalPickedAtMillis = inputs.physicalPickedAtMillis"))
        assertFalse("the two times are never folded into one maximum again", code.contains("maxOrNull()"))
    }

    /** No times to compare: the icon wins, which is what Home showed for every pick made before this build. */
    @Test
    fun `with no timestamps the icon wins over a recorded cover`() {
        val key = "shelf-face/no-times"
        coverPick(key)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
        val icon = tile(key, ownersPick = true, pickedAt = 0L)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFace(versionedFileUri(icon), ShelfFaceShape.SQUARE), r.face)
    }

    // -- the automatic tiers ----------------------------------------------------------------------------

    /** The C4 gap: an automatic tile used to beat a cover he PICKED whenever no automatic cover sat beside it. */
    @Test
    fun `a cover pick beats an automatic square, with or without an automatic cover`() {
        val key = "shelf-face/pick-over-auto-square"
        tile(key, ownersPick = false)
        coverPick(key)

        listOf(true, false).forEach { hasCover ->
            val r = shelf(key, hasCover)
            assertEquals(ShelfFaceTier.PICK, r.tier)
            assertEquals(ShelfFaceShape.LEDGE_COVER, r.face.shape)
        }
    }

    @Test
    fun `an automatic square loses to an automatic cover`() {
        val key = "shelf-face/auto-square-loses"
        tile(key, ownersPick = false)

        val r = shelf(key, hasCover = true)

        assertEquals(ShelfFaceTier.AUTOMATIC_COVER, r.tier)
        assertEquals(ShelfFace(autoCover, ShelfFaceShape.LEDGE_COVER), r.face)
    }

    @Test
    fun `an automatic square stands in when there is no cover, on a non-pc platform key`() {
        val key = "snes/shelf-face/auto-square"
        val file = tile(key, ownersPick = false)

        val r = shelf(key, hasCover = false)

        assertEquals(ShelfFaceTier.AUTOMATIC_TILE, r.tier)
        assertEquals(ShelfFace(versionedFileUri(file), ShelfFaceShape.SQUARE), r.face)
    }

    @Test
    fun `the pc portrait sibling is preferred to the square, and a cover he placed beats both`() {
        val key = "pc_games/shelf-face/portrait"
        val square = tile(key, ownersPick = false)
        val portrait = portrait(key)

        val noCover = shelf(key, hasCover = false)
        assertEquals(ShelfFace(versionedFileUri(portrait), ShelfFaceShape.LEDGE_COVER), noCover.face)
        assertEquals(ShelfFaceTier.AUTOMATIC_TILE, noCover.tier)

        val ownCover = shelf(key, hasCover = true)
        assertEquals(ShelfFace(autoCover, ShelfFaceShape.LEDGE_COVER), ownCover.face)

        // Differential: with no portrait the same game falls back to the square, so the tests above cannot pass by
        // the portrait being the only thing on disk.
        portrait.delete()
        assertEquals(ShelfFace(versionedFileUri(square), ShelfFaceShape.SQUARE), shelf(key, hasCover = false).face)
    }

    @Test
    fun `no tile and no pick leaves the row's own art as the stand-in, and nothing without it`() {
        val key = "shelf-face/stand-in"
        val standIn = shelf(key, hasCover = false, cover = "file:///media/Game-logo.png")
        assertEquals(ShelfFaceTier.STAND_IN, standIn.tier)
        assertEquals(ShelfFaceShape.LEDGE_COVER, standIn.face.shape)

        val none = shelf("shelf-face/nothing", hasCover = false)
        assertEquals(ShelfFaceTier.NONE, none.tier)
        assertEquals(ShelfFace.None, none.face)
    }

    @Test
    fun `the provider support render is painted only when the surface supplies it`() {
        val key = "shelf-face/support"
        val withSupport = resolveShelfFaces(context, key, false, null, physicalMediaUri = "file:///media/Game-support.png")
        assertEquals(ShelfFaceTier.SUPPORT, withSupport.shelf.tier)
        assertEquals(ShelfFaceShape.PHYSICAL, withSupport.shelf.face.shape)
        assertEquals("the card reads the article as its own tier, never through the shelf", ShelfFace.None, withSupport.card.face)
        assertEquals(ShelfFace.None, shelf(key, hasCover = false).face)
    }

    @Test
    fun `a blank cache key reads no store`() {
        val r = resolveShelfFaces(context, "", hasAutomaticCover = true, automaticCoverUri = autoCover)
        assertEquals(ShelfFace(autoCover, ShelfFaceShape.LEDGE_COVER), r.shelf.face)
        assertEquals(ShelfFaceTier.AUTOMATIC_COVER, r.shelf.tier)
    }

    // -- the launch card never wears a square ------------------------------------------------------------

    @Test
    fun `the card keeps a cover pick that sits under a square pick`() {
        val key = "shelf-face/card-under-square"
        tile(key, ownersPick = true)
        coverPick(key)

        assertEquals(ShelfFaceShape.SQUARE, shelf(key, hasCover = true).face.shape)
        val card = card(key, hasCover = true)
        assertEquals(ShelfFaceShape.LEDGE_COVER, card.face.shape)
        assertTrue(card.face.uri!!.contains("custom-cover-art"))
    }

    @Test
    fun `resolveWiredFace answers null for a recorded square, in the shelf state`() {
        val key = "shelf-face/wired-null"
        coverPick(key)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.SQUARE)

        assertNull(resolveWiredFace(context, key, MediaState.SHELF))
    }

    @Test
    fun `the launch card of a game whose shelf face is his square is its cover, not the square`() {
        val key = "shelf-face/launch-card"
        tile(key, ownersPick = true)

        val face = launchCardWiredFace(
            context = context,
            cacheKey = key,
            providerPhysicalMediaUri = null,
            shelfArtworkUri = autoCover,
        )

        assertNotNull(face)
        assertEquals(autoCover, face!!.uri)
        assertFalse(face.physical)
    }

    @Test
    fun `the launch card of a game with only a square has no face`() {
        val key = "shelf-face/launch-card-square-only"
        tile(key, ownersPick = true)

        assertNull(launchCardWiredFace(context, key, providerPhysicalMediaUri = null, shelfArtworkUri = null))
    }

    @Test
    fun `the launch card inherits the automatic pc portrait that used to ride artUri`() {
        val key = "pc_games/shelf-face/launch-card-portrait"
        tile(key, ownersPick = false)
        val portrait = portrait(key)

        val face = launchCardWiredFace(context, key, providerPhysicalMediaUri = null, shelfArtworkUri = null)

        assertNotNull(face)
        assertEquals(versionedFileUri(portrait), face!!.uri)
        assertFalse("a portrait box is a cover, not a disc", face.physical)
    }

    // -- the seam behind GameRow.wiredFace ---------------------------------------------------------------

    @Test
    fun `the seam carries his pick with its shape for every platform`() {
        val cover = "snes/seam/cover"
        val article = "gba/seam/article"
        coverPick(cover)
        articlePick(article)

        assertEquals(ShelfFaceShape.LEDGE_COVER, shelfFaceOverride(context, cover, hasAutomaticCover = true)?.shape)
        assertEquals(ShelfFaceShape.PHYSICAL, shelfFaceOverride(context, article, hasAutomaticCover = true)?.shape)
    }

    @Test
    fun `the seam carries the automatic square only without a cover`() {
        val key = "nes/seam/auto-square"
        val file = tile(key, ownersPick = false)

        val without = shelfFaceOverride(context, key, hasAutomaticCover = false)
        assertEquals(ShelfFaceShape.SQUARE, without?.shape)
        assertEquals(versionedFileUri(file), without?.uri)
        assertNull("a cover is the resolver's own artUri; the seam adds nothing", shelfFaceOverride(context, key, hasAutomaticCover = true))
    }

    @Test
    fun `the seam says nothing when only automatic art or no art exists`() {
        assertNull(shelfFaceOverride(context, "seam/none", hasAutomaticCover = false))
        assertNull(shelfFaceOverride(context, "seam/none", hasAutomaticCover = true))
    }

    @Test
    fun `the seam carries his square pick over a cover`() {
        val key = "psx/seam/owner-square"
        val file = tile(key, ownersPick = true)

        val face = shelfFaceOverride(context, key, hasAutomaticCover = true)

        assertEquals(ShelfFaceShape.SQUARE, face?.shape)
        assertEquals(versionedFileUri(file), face?.uri)
    }
}
