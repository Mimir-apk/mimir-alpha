package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Icon art": one Customize-media type for the shelf, which takes a square, a box, the physical article
 * and an upload, and whose tile follows the pick. These tests drive the REAL stores (`sgdb2/`, `custom-cover-art/`,
 * `tome-cartridge-art/`, the Saved catalogs, the face record and the selection markers) under Robolectric, and read the result back
 * through the one resolver the shelves paint from ([resolveShelfFaces]).
 *
 * Nothing here touches `media_art/` or a `Mimir-media-art-backup-*` folder except to prove that Default leaves them alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class IconArtStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val autoCover = "file:///media/Game.png"

    /**
     * The saved store resolves the game's current identity through Room, and [MimirDatabaseFactory] caches its database for the
     * life of the process. Robolectric builds a new application for every test, so the cached one would point into the previous
     * test's closed sandbox ("Illegal connection pointer"): each test starts on a database of its own.
     */
    private fun dropCachedDatabase() {
        val field = MimirDatabaseFactory::class.java.getDeclaredField("instance")
        field.isAccessible = true
        (field.get(null) as? androidx.room.RoomDatabase)?.let { runCatching { it.close() } }
        field.set(null, null)
    }

    @Before fun freshDatabase() = dropCachedDatabase()

    @After fun closeDatabase() = dropCachedDatabase()

    /** A decodable 1 x 1 PNG: the icon lane validates what it installs. */
    private fun png(name: String): File {
        val file = File(context.cacheDir, "icon-art-test/$name")
        file.parentFile?.mkdirs()
        file.writeBytes(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
                0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
                0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
                0x0D, 0x0A, 0x2D, 0xB4.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
                // The tail makes each file's content (and so its asset id) its own.
                name.hashCode().toByte(), (name.length).toByte(),
            ),
        )
        return file
    }

    /** Saves [name] into [lane] with the provider type a found result of that shape carries, and returns its asset id. */
    private fun saveInto(
        lane: IconArtLane,
        key: String,
        name: String,
        providerMediaType: String?,
        source: EnrichSavedSource = EnrichSavedSource.SCREENSCRAPER,
    ): String = runBlocking {
        val outcome = EnrichSavedStore.save(
            context = context,
            kind = lane.kind,
            cacheKey = key,
            source = source,
            label = name,
            fullAsset = png(name),
            providerMediaType = providerMediaType,
            activate = false,
        )
        (outcome as EnrichSaveOutcome.Saved).asset.assetId
    }

    private fun pick(lane: IconArtLane, key: String, name: String, providerMediaType: String?): String {
        // Picks are ordered by the time of their selection markers, and a tie goes to the icon (as it always did), so
        // a test that depends on "later" lets a few milliseconds pass.
        Thread.sleep(5)
        val id = saveInto(
            lane,
            key,
            name,
            providerMediaType,
            if (lane == IconArtLane.SQUARE) EnrichSavedSource.STEAMGRIDDB else EnrichSavedSource.SCREENSCRAPER,
        )
        assertTrue("$name activates", runBlocking { IconArtStore.activate(context, key, id) })
        return id
    }

    private fun shelf(key: String, hasCover: Boolean = true) =
        resolveShelfFaces(context, key, hasCover, if (hasCover) autoCover else null).shelf

    private fun tileFile(key: String) = SteamGridDbTileFetcher.canonicalTileIconFile(context, key)

    // -- apply by shape: the right lane, the right record ------------------------------------------------

    @Test
    fun `a square goes to the icon lane, records SQUARE and is the shelf's face`() {
        val key = "icon-art/square"
        pick(IconArtLane.SQUARE, key, "square.png", SteamGridDbSquareGridType)

        assertTrue("the file is in sgdb2/", tileFile(key).isFile)
        assertFalse(CustomCoverStore.hasOverride(context, key))
        assertFalse(TomeCartridgeArtStore.hasOverride(context, key))
        assertEquals(WiredFaceKind.SQUARE, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertEquals(GameIdentityMediaSelection.SELECTED, GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.ICON, key))
        assertTrue("and the marker carries the time of the pick", GameIdentityMediaSelectionStore.record(context, EnrichMediaKind.ICON, key).changedAtMillis > 0L)
        val face = shelf(key)
        assertEquals(ShelfFaceTier.PICK, face.tier)
        assertEquals(ShelfFaceShape.SQUARE, face.face.shape)
    }

    @Test
    fun `a box goes to the cover lane and records COVER, and the tile is a ledge box`() {
        val key = "icon-art/box"
        pick(IconArtLane.BOX, key, "box.png", "box-3d")

        assertTrue(CustomCoverStore.hasOverride(context, key))
        assertFalse("not in sgdb2/", tileFile(key).isFile)
        assertFalse(TomeCartridgeArtStore.hasOverride(context, key))
        assertEquals(WiredFaceKind.COVER, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        val face = shelf(key)
        assertEquals(ShelfFaceTier.PICK, face.tier)
        assertEquals(ShelfFaceShape.LEDGE_COVER, face.face.shape)
    }

    @Test
    fun `the article goes to the Tome store and records PHYSICAL, and the tile is the article`() {
        val key = "icon-art/article"
        pick(IconArtLane.ARTICLE, key, "disc.png", "support-2d")

        assertTrue(TomeCartridgeArtStore.hasOverride(context, key))
        assertFalse(CustomCoverStore.hasOverride(context, key))
        assertFalse(tileFile(key).isFile)
        assertEquals(WiredFaceKind.PHYSICAL, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        val face = shelf(key)
        assertEquals(ShelfFaceTier.PICK, face.tier)
        assertEquals(ShelfFaceShape.PHYSICAL, face.face.shape)
    }

    @Test
    fun `a square grid activated in the cover lane by a single-lane picker keeps the PHYSICAL record its file can be found by`() {
        // The merged picker routes such a choice to the icon lane (see the routing tests below); the Launch card's own picker still activates in the cover lane.
        val key = "icon-art/legacy-pc-square"
        val id = saveInto(IconArtLane.BOX, key, "pc-square.png", SteamGridDbSquareGridType, EnrichSavedSource.STEAMGRIDDB)
        assertTrue(runBlocking { EnrichSavedStore.activate(context, EnrichMediaKind.COVER, key, id) })

        assertEquals(
            "a SQUARE record is only honoured for a file in sgdb2/, and this file is in the cover store",
            WiredFaceKind.PHYSICAL,
            WiredFaceStore.recorded(context, key, MediaState.SHELF),
        )
        assertEquals(ShelfFaceTier.PICK, shelf(key).tier)
        assertNotNull(shelf(key).face.uri)
    }

    @Test
    fun `the newest pick wins across lanes, in both directions`() {
        val key = "icon-art/newest"
        pick(IconArtLane.SQUARE, key, "s1.png", SteamGridDbSquareGridType)
        assertEquals(ShelfFaceShape.SQUARE, shelf(key).face.shape)

        pick(IconArtLane.BOX, key, "b1.png", "box-2d")
        assertEquals("a box picked after the square", ShelfFaceShape.LEDGE_COVER, shelf(key).face.shape)
        assertEquals(IconArtLane.BOX, IconArtStore.load(context, key).activeLane)

        val square2 = pick(IconArtLane.SQUARE, key, "s2.png", null)
        assertEquals("a square picked after the box wins outright: the record names it", ShelfFaceShape.SQUARE, shelf(key).face.shape)
        val merged = IconArtStore.load(context, key)
        assertEquals(IconArtLane.SQUARE, merged.activeLane)
        assertEquals(square2, merged.activeAssetId)

        pick(IconArtLane.ARTICLE, key, "d1.png", "support-3d")
        assertEquals("and the article after that", ShelfFaceShape.PHYSICAL, shelf(key).face.shape)
    }

    @Test
    fun `a square pick beats the automatic cover, a default does not`() {
        val key = "icon-art/vs-automatic"
        assertEquals(ShelfFaceTier.AUTOMATIC_COVER, shelf(key).tier)
        pick(IconArtLane.SQUARE, key, "sq.png", null)
        assertEquals(ShelfFaceShape.SQUARE, shelf(key).face.shape)
    }

    // -- Saved choices list every lane --------------------------------------------------------------------

    @Test
    fun `the Saved gallery lists every lane's choices once, the one in use first, and says where each lives`() {
        val key = "icon-art/gallery"
        val square = saveInto(IconArtLane.SQUARE, key, "g-square.png", SteamGridDbSquareGridType, EnrichSavedSource.STEAMGRIDDB)
        val box = saveInto(IconArtLane.BOX, key, "g-box.png", "box-3d")
        val article = saveInto(IconArtLane.ARTICLE, key, "g-disc.png", "support-2d")
        runBlocking { IconArtStore.activate(context, key, box) }

        val merged = IconArtStore.load(context, key)

        assertEquals(setOf(square, box, article), merged.saved.map { it.assetId }.toSet())
        assertEquals("the one in use leads", box, merged.saved.first().assetId)
        assertEquals(IconArtLane.SQUARE, merged.lanes.getValue(square))
        assertEquals(IconArtLane.BOX, merged.lanes.getValue(box))
        assertEquals(IconArtLane.ARTICLE, merged.lanes.getValue(article))
        assertEquals(box, merged.activeAssetId)
    }

    @Test
    fun `activating a saved choice goes to the lane that holds it`() {
        val key = "icon-art/activate-saved"
        val square = saveInto(IconArtLane.SQUARE, key, "a-square.png", SteamGridDbSquareGridType, EnrichSavedSource.STEAMGRIDDB)
        val article = saveInto(IconArtLane.ARTICLE, key, "a-disc.png", "support-label")

        assertTrue(runBlocking { IconArtStore.activate(context, key, article) })
        assertEquals(WiredFaceKind.PHYSICAL, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertTrue(runBlocking { IconArtStore.activate(context, key, square) })
        assertEquals(WiredFaceKind.SQUARE, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertFalse("an asset no catalog holds is not activated", runBlocking { IconArtStore.activate(context, key, "nope") })
    }

    /** The gallery lists a picture two lanes hold once, under one lane; the lane he activates it in is that one, not the first in the fixed order. */
    @Test
    fun `a picture two lanes hold is activated in the lane the gallery listed it under`() {
        val key = "icon-art/shared-asset"
        val inBox = saveInto(IconArtLane.BOX, key, "shared.png", "box-3d")
        val inArticle = saveInto(IconArtLane.ARTICLE, key, "shared.png", "support-2d")
        assertEquals("one picture, one id", inBox, inArticle)
        assertEquals("the fixed order finds the box lane first", IconArtLane.BOX, IconArtStore.laneOf(context, key, inBox))

        assertTrue(runBlocking { IconArtStore.activate(context, key, inBox, lane = IconArtLane.ARTICLE) })

        assertEquals("the lane the page named", WiredFaceKind.PHYSICAL, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertTrue(TomeCartridgeArtStore.hasOverride(context, key))
        assertFalse(CustomCoverStore.hasOverride(context, key))

        val other = "icon-art/shared-asset-default"
        val id = saveInto(IconArtLane.BOX, other, "shared2.png", "box-3d")
        saveInto(IconArtLane.ARTICLE, other, "shared2.png", "support-2d")
        assertTrue("no lane given: the fixed order, as before", runBlocking { IconArtStore.activate(context, other, id) })
        assertEquals(WiredFaceKind.COVER, WiredFaceStore.recorded(context, other, MediaState.SHELF))
        assertTrue("a lane that does not hold it falls back", runBlocking { IconArtStore.activate(context, other, id, lane = IconArtLane.SQUARE) })
    }

    @Test
    fun `a saved choice is routed by its real shape, and a box, an upload and a legacy file stay in the box lane`() {
        fun entry(type: String?, source: EnrichSavedSource = EnrichSavedSource.SCREENSCRAPER) =
            EnrichSavedAsset("id", source, "label", "", "", 0, 0, 1L, addedAt = 1L, providerMediaType = type)
        assertEquals(IconArtLane.SQUARE, iconArtLaneOfSavedEntry(IconArtLane.BOX, entry(SteamGridDbSquareGridType, EnrichSavedSource.STEAMGRIDDB)))
        assertEquals(IconArtLane.ARTICLE, iconArtLaneOfSavedEntry(IconArtLane.BOX, entry("support-2d")))
        assertEquals(IconArtLane.ARTICLE, iconArtLaneOfSavedEntry(IconArtLane.BOX, entry("support-label")))
        assertEquals(IconArtLane.BOX, iconArtLaneOfSavedEntry(IconArtLane.BOX, entry("box-3d")))
        assertEquals(IconArtLane.BOX, iconArtLaneOfSavedEntry(IconArtLane.BOX, entry(SteamGridDbPortraitGridType, EnrichSavedSource.STEAMGRIDDB)))
        assertEquals("an upload has no provider type", IconArtLane.BOX, iconArtLaneOfSavedEntry(IconArtLane.BOX, entry(null, EnrichSavedSource.UPLOAD)))
        assertEquals("a legacy file adopted from disk", IconArtLane.BOX, iconArtLaneOfSavedEntry(IconArtLane.BOX, entry(null, EnrichSavedSource.LEGACY)))
        assertEquals("the other lanes' choices are already in their own lane", IconArtLane.ARTICLE, iconArtLaneOfSavedEntry(IconArtLane.ARTICLE, entry(SteamGridDbSquareGridType)))
        assertEquals(IconArtLane.SQUARE, iconArtLaneOfSavedEntry(IconArtLane.SQUARE, entry("support-2d")))
    }

    /**
     * A disc saved in the BOX lane's catalog by the old Shelves picker. Activating it there recorded PHYSICAL, and the shelf reads PHYSICAL as "the
     * article store first, then the cover store": an older article pick still in `tome-cartridge-art/` painted over the choice he had just made, and the
     * picker marked the disc in use. Activated by its real shape it is the article, it is what paints, and the mark is the shelf's.
     */
    @Test
    fun `activating a pre-merge disc saved in the box lane paints that disc, and the mark matches`() {
        val key = "icon-art/legacy-disc-in-box-lane"
        pick(IconArtLane.ARTICLE, key, "older-disc.png", "support-2d")
        val legacy = saveInto(IconArtLane.BOX, key, "legacy-disc.png", "support-2d")
        Thread.sleep(5)

        assertTrue(runBlocking { IconArtStore.activate(context, key, legacy, lane = IconArtLane.BOX) })

        assertEquals(ShelfFaceShape.PHYSICAL, shelf(key).face.shape)
        assertTrue(
            "the article store holds the choice he just made, not the older pick",
            TomeCartridgeArtStore.activeFile(context, key).readBytes().contentEquals(png("legacy-disc.png").readBytes()),
        )
        assertEquals(WiredFaceKind.PHYSICAL, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        val merged = IconArtStore.load(context, key)
        assertEquals(IconArtLane.ARTICLE, merged.activeLane)
        assertEquals("the mark is the shelf's: the choice he made", legacy, merged.activeAssetId)
        assertEquals("and it is listed once", 1, merged.saved.count { it.assetId == legacy })
        assertEquals(IconArtLane.ARTICLE, merged.lanes.getValue(legacy))
        assertFalse("what is in use has no remove", runBlocking { IconArtStore.remove(context, key, legacy) })
    }

    @Test
    fun `activating a pre-merge square grid saved in the box lane paints it as a square, and the mark matches`() {
        val key = "icon-art/legacy-grid-in-box-lane"
        pick(IconArtLane.ARTICLE, key, "older-disc2.png", "support-2d")
        val legacy = saveInto(IconArtLane.BOX, key, "legacy-grid.png", SteamGridDbSquareGridType, EnrichSavedSource.STEAMGRIDDB)
        Thread.sleep(5)

        assertTrue(runBlocking { IconArtStore.activate(context, key, legacy, lane = IconArtLane.BOX) })

        assertEquals("a square is a square tile", ShelfFaceShape.SQUARE, shelf(key).face.shape)
        assertTrue("in sgdb2/", tileFile(key).readBytes().contentEquals(png("legacy-grid.png").readBytes()))
        assertEquals(WiredFaceKind.SQUARE, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        val merged = IconArtStore.load(context, key)
        assertEquals(IconArtLane.SQUARE, merged.activeLane)
        assertEquals(legacy, merged.activeAssetId)
    }

    @Test
    fun `activating a box saved in the box lane is as it was, and the page reads the mark back from the shelf`() {
        val key = "icon-art/box-in-box-lane"
        val box = saveInto(IconArtLane.BOX, key, "plain-box.png", "box-3d")

        assertTrue(runBlocking { IconArtStore.activate(context, key, box, lane = IconArtLane.BOX) })

        assertEquals(ShelfFaceShape.LEDGE_COVER, shelf(key).face.shape)
        assertEquals(WiredFaceKind.COVER, WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertEquals(box, IconArtStore.load(context, key).activeAssetId)
        val activation = sourceOf("MimirAppCoordinator.kt")
            .substringAfter("is HomeCustomEnrichCommand.ActivateSaved -> {")
            .substringBefore("is HomeCustomEnrichCommand.RemoveSaved -> {")
        assertTrue("the page reads the merged gallery back after an activation", activation.contains("IconArtStore.loadOffMain(core.context, picker.cacheKey)"))
        assertTrue(activation.contains("activeAssetId = if (shown != null) shown.activeAssetId"))
    }

    @Test
    fun `the shelf winner is the lane and the time of the pick from one reading, and the two older calls agree with it`() {
        val key = "icon-art/shelf-winner"
        assertEquals(IconArtShelfWinner(null, null), IconArtStore.shelfWinner(context, key))
        pick(IconArtLane.BOX, key, "w-box.png", "box-3d")
        val box = IconArtStore.shelfWinner(context, key)
        assertEquals(IconArtLane.BOX, box.lane)
        assertEquals(GameIdentityMediaSelectionStore.record(context, EnrichMediaKind.COVER, key).changedAtMillis, box.pickedAtMillis)
        pick(IconArtLane.SQUARE, key, "w-square.png", SteamGridDbSquareGridType)
        val square = IconArtStore.shelfWinner(context, key)
        assertEquals(IconArtLane.SQUARE, square.lane)
        assertEquals(GameIdentityMediaSelectionStore.record(context, EnrichMediaKind.ICON, key).changedAtMillis, square.pickedAtMillis)
        assertTrue("the square was picked after the box", square.pickedAtMillis!! > box.pickedAtMillis!!)
        assertEquals(square.lane, IconArtStore.winningLane(context, key))
        assertEquals(square.pickedAtMillis, IconArtStore.winningPickedAtMillis(context, key))
    }

    /**
     * The shelf's own reading stats files and reads markers, so the picker builder, which runs on the main thread, never asks it there: it
     * hands the page a first frame and reads the winner on the IO dispatcher straight after (and reloads the gallery there, too).
     */
    @Test
    fun `the picker builder asks the shelf for the winner off the main thread`() {
        val builder = sourceOf("CustomMediaPage.kt").substringAfter("private fun buildIconArtPicker(")
        val synchronous = builder.substringBefore("scope.launch {")
        listOf("shelfWinner(", "winningLane(", "winningPickedAtMillis(", "IconArtStore.load(").forEach { call ->
            assertFalse("the synchronous part of the builder does not call $call", synchronous.contains(call))
        }
        val read = builder.substringAfter("scope.launch {").substringBefore("val ready")
        assertTrue("the winner is read on the IO dispatcher", read.contains("withContext(Dispatchers.IO) {") && read.contains("IconArtStore.shelfWinner(context, cacheKey)"))
        assertTrue("the gallery is reloaded off the main thread", builder.contains("IconArtStore.loadOffMain(context, cacheKey)"))
        assertFalse("and nowhere through the plain load", builder.substringBefore("return initial").contains("IconArtStore.load(context"))
    }

    @Test
    fun `the page activates a saved choice in the lane its gallery listed it under`() {
        val coordinator = sourceOf("MimirAppCoordinator.kt")
        val activation = coordinator.substringAfter("is HomeCustomEnrichCommand.ActivateSaved -> {").substringBefore("is HomeCustomEnrichCommand.RemoveSaved -> {")
        assertTrue(
            "the merged activation names the lane the picker holds for the asset",
            activation.contains("IconArtStore.activate(core.context, picker.cacheKey, command.assetId, picker.savedLanes[command.assetId])"),
        )
    }

    @Test
    fun `a saved choice in a lane that is not showing can still be removed`() {
        val key = "icon-art/remove-hidden"
        val square = pick(IconArtLane.SQUARE, key, "r-square.png", SteamGridDbSquareGridType)
        val box = pick(IconArtLane.BOX, key, "r-box.png", "box-3d")
        assertEquals("the box is showing", box, IconArtStore.load(context, key).activeAssetId)

        assertTrue("the square is saved and not showing", runBlocking { IconArtStore.remove(context, key, square) })

        val after = IconArtStore.load(context, key)
        assertEquals(listOf(box), after.saved.map { it.assetId })
        assertEquals("what was showing still is", ShelfFaceShape.LEDGE_COVER, shelf(key).face.shape)
        assertFalse("the removed square's lane no longer holds a pick", tileFile(key).isFile)
        assertFalse("the choice in use cannot be removed", runBlocking { IconArtStore.remove(context, key, box) })
    }

    // -- Default --------------------------------------------------------------------------------------------

    /** The automatic fetch's tile, as it leaves it: the file and its sidecar, and no selection marker (it is not his pick). */
    private fun automaticTile(key: String): ByteArray {
        val file = tileFile(key)
        file.parentFile?.mkdirs()
        file.writeBytes(png("automatic-tile-$key").readBytes())
        File(file.parentFile, file.name + ".idx").writeText("0")
        return file.readBytes()
    }

    /**
     * A game with no cover shows its AUTOMATIC square. Pick a box over it, press Default: the box goes and the automatic square
     * stays. The icon lane used to be cleared unconditionally, which deleted `sgdb2/`'s automatic tile with the box.
     */
    @Test
    fun `Default after a box on a game with no cover leaves the automatic square and the shelf shows it`() {
        val key = "icon-art/default-keeps-automatic"
        val automatic = automaticTile(key)
        assertEquals(ShelfFaceTier.AUTOMATIC_TILE, shelf(key, hasCover = false).tier)
        pick(IconArtLane.BOX, key, "k-box.png", "box-3d")
        assertEquals("his box shows over it", ShelfFaceShape.LEDGE_COVER, shelf(key, hasCover = false).face.shape)

        assertTrue(runBlocking { IconArtStore.activateAutomatic(context, key) })

        assertTrue("the automatic square is still there", tileFile(key).isFile)
        assertTrue("and untouched", automatic.contentEquals(tileFile(key).readBytes()))
        val after = shelf(key, hasCover = false)
        assertEquals(ShelfFaceTier.AUTOMATIC_TILE, after.tier)
        assertEquals(ShelfFaceShape.SQUARE, after.face.shape)
        assertFalse(CustomCoverStore.hasOverride(context, key))
        assertNull(WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertNull("nothing of his is in use", IconArtStore.load(context, key).activeAssetId)
        assertEquals("and he never picked an icon, so none is marked", GameIdentityMediaSelection.NEVER_CHOSEN, GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.ICON, key))
    }

    @Test
    fun `Default with nothing of his held touches nothing, and Default after his square removes his square and its record`() {
        val untouched = "icon-art/default-nothing"
        val automatic = automaticTile(untouched)
        assertTrue(runBlocking { IconArtStore.activateAutomatic(context, untouched) })
        assertTrue("the automatic square stays", automatic.contentEquals(tileFile(untouched).readBytes()))

        val squared = "icon-art/default-square"
        automaticTile(squared)
        pick(IconArtLane.SQUARE, squared, "k-square.png", SteamGridDbSquareGridType)
        assertEquals(WiredFaceKind.SQUARE, WiredFaceStore.recorded(context, squared, MediaState.SHELF))
        assertTrue(runBlocking { IconArtStore.activateAutomatic(context, squared) })
        assertNull("no SQUARE record is left to make the next automatic tile his", WiredFaceStore.recorded(context, squared, MediaState.SHELF))
        assertFalse("his square pick is gone", homeIconOwnerPicked(context, squared))
    }

    @Test
    fun `Default clears his shelf pick in every lane and the record, so automatic art shows`() {
        val key = "icon-art/default"
        pick(IconArtLane.SQUARE, key, "d-square.png", SteamGridDbSquareGridType)
        pick(IconArtLane.BOX, key, "d-box.png", "box-3d")
        pick(IconArtLane.ARTICLE, key, "d-disc.png", "support-2d")
        assertEquals(ShelfFaceTier.PICK, shelf(key).tier)

        assertTrue(runBlocking { IconArtStore.activateAutomatic(context, key) })

        assertFalse(tileFile(key).isFile)
        assertFalse(CustomCoverStore.hasOverride(context, key))
        assertFalse(TomeCartridgeArtStore.hasOverride(context, key))
        assertNull(WiredFaceStore.recorded(context, key, MediaState.SHELF))
        val face = shelf(key)
        assertEquals("automatic art shows", ShelfFaceTier.AUTOMATIC_COVER, face.tier)
        assertEquals(autoCover, face.face.uri)
        assertNull("nothing is in use", IconArtStore.load(context, key).activeAssetId)
        assertEquals("the saved choices stay listed for another day", 3, IconArtStore.load(context, key).saved.size)
    }

    @Test
    fun `Default never touches media_art or a backup folder, and the store has no way to`() {
        val key = "icon-art/default-media-art"
        val rom = File(context.cacheDir, "volume/ROMs/snes")
        val automatic = File(rom, "media_art/Game.png").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val backup = File(context.cacheDir, "volume/Mimir-media-art-backup-2026-10-01/Game.png")
            .apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 5, 6)) }
        pick(IconArtLane.BOX, key, "m-box.png", "box-3d")

        assertTrue(runBlocking { IconArtStore.activateAutomatic(context, key) })

        assertTrue("the provider's own art stays", automatic.isFile)
        assertTrue("every backup folder stays", backup.isFile)
        val code = sourceOf("IconArtStore.kt")
            .substringAfter("internal object IconArtStore")
            .lines()
            .filterNot { line -> line.trim().let { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") } }
            .joinToString("\n")
        listOf("deleteRecursively", ".delete()", "media_art", "Mimir-media-art-backup").forEach { forbidden ->
            assertFalse(
                "IconArtStore reaches the three app-private stores through their own calls and names no path of its own: $forbidden",
                code.contains(forbidden),
            )
        }
    }

    @Test
    fun `Default with nothing picked is harmless and leaves no stray lane marker beyond the icon lane's own`() {
        val key = "icon-art/default-empty"
        assertTrue(runBlocking { IconArtStore.activateAutomatic(context, key) })
        assertNull(WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertEquals(
            "a lane that held nothing writes no tombstone",
            GameIdentityMediaSelection.NEVER_CHOSEN,
            GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.COVER, key),
        )
        assertEquals(
            GameIdentityMediaSelection.NEVER_CHOSEN,
            GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.TOME_PHYSICAL, key),
        )
    }

    // -- The picker opens on what is already there --------------------------------------------------------------

    /** A pick an older build left in a store: a real picture at the store's own path, with no catalog and no record. */
    private fun writePick(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(png("old-${file.name}").readBytes())
    }

    @Test
    fun `an old Shelves cover pick, never catalogued and never recorded, is listed and still the face`() {
        val key = "icon-art/migrate-cover"
        writePick(CustomCoverStore.activeFile(context, key))
        assertNull(WiredFaceStore.recorded(context, key, MediaState.SHELF))

        val inputs = IconArtStore.laneInputs(context, key)
        val box = inputs.first { it.lane == IconArtLane.BOX }
        assertTrue("it will be adopted behind the first frame", box.needsMigration)
        val shown = mergeIconArtCatalogs(inputs.associate { it.lane to it.display }, IconArtStore.winningLane(context, key))
        assertEquals("and it is on the page at once", 1, shown.saved.size)
        assertEquals(IconArtLane.BOX, shown.activeLane)
        assertNotNull(shown.activeAssetId)
        assertEquals("and the shelf paints it, as it did", ShelfFaceShape.LEDGE_COVER, shelf(key).face.shape)

        val outcome = runBlocking {
            EnrichSavedStore.migrateLegacy(context, box.lane.kind, key, box.legacyActive, box.retainedUpload, box.activeWasUpload)
        }
        assertTrue(outcome is EnrichLegacyMigrationOutcome.Ready)
        val after = IconArtStore.load(context, key)
        assertEquals(1, after.saved.size)
        assertEquals(IconArtLane.BOX, after.activeLane)
        assertEquals(ShelfFaceShape.LEDGE_COVER, shelf(key).face.shape)
    }

    /** Opens the picker's adoption the way the page does: the ceiling read once, then every lane that holds something adopted. */
    private fun adoptEverything(key: String) {
        val notAfter = IconArtStore.winningPickedAtMillis(context, key)
        for (input in IconArtStore.laneInputs(context, key).filter(IconArtLaneInput::needsMigration)) {
            assertTrue(
                "${input.lane} is adopted",
                runBlocking { IconArtStore.adopt(context, key, input, notAfter) } is EnrichLegacyMigrationOutcome.Ready,
            )
        }
    }

    private fun mark(kind: EnrichMediaKind, key: String, at: Long) = GameIdentityMediaSelectionStore.write(
        context, kind, key, GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, at),
    )

    private fun markedAt(kind: EnrichMediaKind, key: String): Long = GameIdentityMediaSelectionStore.record(context, kind, key).changedAtMillis

    /**
     * Opening Customize media must not change the face on the shelf. A record COVER, a newer icon showing, and an uncatalogued
     * article file that is fresher on disk than the icon was picked: adopting it used to stamp it with its modification time, which
     * made the article newer than the icon, and the shelf flipped to the box the moment the picker opened.
     */
    @Test
    fun `opening the picker leaves the square on the shelf when an uncatalogued article file is fresher than the icon`() {
        val key = "icon-art/adopt-keeps-square"
        writePick(CustomCoverStore.activeFile(context, key))
        mark(EnrichMediaKind.COVER, key, 1_000L)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
        writePick(tileFile(key))
        mark(EnrichMediaKind.ICON, key, 2_000L)
        writePick(TomeCartridgeArtStore.activeFile(context, key))
        assertTrue("the article file is fresher on disk than the icon pick", TomeCartridgeArtStore.activeFile(context, key).lastModified() > 2_000L)
        assertEquals("the square shows before", ShelfFaceShape.SQUARE, shelf(key).face.shape)

        adoptEverything(key)

        assertEquals("and after the picker adopted every lane", ShelfFaceShape.SQUARE, shelf(key).face.shape)
        assertEquals(IconArtLane.SQUARE, IconArtStore.load(context, key).activeLane)
        assertEquals("the cover keeps the time of the pick it was", 1_000L, markedAt(EnrichMediaKind.COVER, key))
        assertTrue("the article is stamped no later than the pick on the shelf", markedAt(EnrichMediaKind.TOME_PHYSICAL, key) <= 2_000L)
        assertEquals("the icon keeps its own", 2_000L, markedAt(EnrichMediaKind.ICON, key))
    }

    /** With no record to arbitrate, an adopted file with no time of its own must not become the newest pick by its mtime. */
    @Test
    fun `an adopted cover that carries no time of its own cannot become newer than the icon on the shelf`() {
        val key = "icon-art/adopt-no-time"
        writePick(tileFile(key))
        mark(EnrichMediaKind.ICON, key, 2_000L)
        writePick(CustomCoverStore.activeFile(context, key))
        assertEquals("no marker, no record: the icon shows, as it always did", ShelfFaceShape.SQUARE, shelf(key).face.shape)

        adoptEverything(key)

        assertEquals(ShelfFaceShape.SQUARE, shelf(key).face.shape)
        assertTrue("the adopted cover is stamped no later than the icon", markedAt(EnrichMediaKind.COVER, key) <= 2_000L)
        assertEquals(IconArtLane.SQUARE, IconArtStore.load(context, key).activeLane)
    }

    /**
     * The old marker format carried no time (`SELECTED|0`), and the shelf reads a pick with no time as unknown, which the icon wins. Adoption used to
     * read that as "no marker time" and stamp the tile with its file's mtime, which was OLDER than the cover's marker, so opening the picker flipped the
     * shelf from the square to the cover. A marker that says SELECTED keeps its own time, 0 included.
     */
    @Test
    fun `opening the picker keeps an old SELECTED marker with no time as it is, and the square stays on the shelf`() {
        val key = "icon-art/adopt-old-marker"
        writePick(tileFile(key))
        assertTrue(tileFile(key).setLastModified(1_000L))
        mark(EnrichMediaKind.ICON, key, 0L)
        writePick(CustomCoverStore.activeFile(context, key))
        mark(EnrichMediaKind.COVER, key, 2_000L)
        assertNull("no record", WiredFaceStore.recorded(context, key, MediaState.SHELF))
        assertEquals("the square shows before the picker opens", ShelfFaceShape.SQUARE, shelf(key).face.shape)

        adoptEverything(key)

        assertEquals("and after", ShelfFaceShape.SQUARE, shelf(key).face.shape)
        assertEquals("the icon marker is not stamped with the file's time", 0L, markedAt(EnrichMediaKind.ICON, key))
        assertEquals(GameIdentityMediaSelection.SELECTED, GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.ICON, key))
        assertEquals("the cover keeps its own", 2_000L, markedAt(EnrichMediaKind.COVER, key))
        assertEquals(IconArtLane.SQUARE, IconArtStore.load(context, key).activeLane)
    }

    /** The ceiling caps only a time adoption makes up: a marker's own time stands even when it is later than the pick on the shelf. */
    @Test
    fun `the adoption ceiling never rewrites a time a marker already carries`() {
        val key = "icon-art/adopt-ceiling-real-time"
        writePick(tileFile(key))
        mark(EnrichMediaKind.ICON, key, 1_000L)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.SQUARE)
        writePick(CustomCoverStore.activeFile(context, key))
        mark(EnrichMediaKind.COVER, key, 5_000L)
        assertEquals("his last choice is the square, whatever the clocks say", ShelfFaceShape.SQUARE, shelf(key).face.shape)
        assertEquals("the ceiling is the pick on the shelf", 1_000L, IconArtStore.winningPickedAtMillis(context, key))

        adoptEverything(key)

        assertEquals("the cover's real time stands", 5_000L, markedAt(EnrichMediaKind.COVER, key))
        assertEquals(ShelfFaceShape.SQUARE, shelf(key).face.shape)
    }

    @Test
    fun `adopting a lane that is the only pick stamps it with its own time, and a single-lane caller is unchanged`() {
        val key = "icon-art/adopt-only"
        writePick(TomeCartridgeArtStore.activeFile(context, key))
        mark(EnrichMediaKind.TOME_PHYSICAL, key, 7_000L)

        adoptEverything(key)

        assertEquals("the marker's own time stands, not the file's", 7_000L, markedAt(EnrichMediaKind.TOME_PHYSICAL, key))
        assertEquals(ShelfFaceShape.PHYSICAL, shelf(key).face.shape)

        val plain = "icon-art/adopt-single-lane"
        val file = TomeCartridgeArtStore.activeFile(context, plain).also(::writePick)
        runBlocking {
            EnrichSavedStore.migrateLegacy(context, EnrichMediaKind.TOME_PHYSICAL, plain, file, null, false)
        }
        assertEquals("no ceiling passed: the file's time, as before", file.lastModified(), markedAt(EnrichMediaKind.TOME_PHYSICAL, plain))
    }

    @Test
    fun `an old cartridge pick is listed in the article lane`() {
        val key = "icon-art/migrate-article"
        writePick(TomeCartridgeArtStore.activeFile(context, key))

        val inputs = IconArtStore.laneInputs(context, key)
        assertTrue(inputs.first { it.lane == IconArtLane.ARTICLE }.needsMigration)
        val shown = mergeIconArtCatalogs(inputs.associate { it.lane to it.display }, IconArtStore.winningLane(context, key))
        assertEquals(IconArtLane.ARTICLE, shown.activeLane)
        assertEquals(ShelfFaceShape.PHYSICAL, shelf(key).face.shape)
    }

    @Test
    fun `an icon he picked before this build is listed, and the automatic tile is not adopted as a pick`() {
        val owned = "icon-art/migrate-owned-icon"
        writePick(tileFile(owned))
        GameIdentityMediaSelectionStore.write(
            context,
            EnrichMediaKind.ICON,
            owned,
            GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, 5_000L),
        )
        assertTrue(IconArtStore.laneInputs(context, owned).first { it.lane == IconArtLane.SQUARE }.needsMigration)

        val automatic = "icon-art/migrate-automatic-tile"
        writePick(tileFile(automatic))
        File(tileFile(automatic).parentFile, tileFile(automatic).name + ".idx").writeText("0")
        val square = IconArtStore.laneInputs(context, automatic).first { it.lane == IconArtLane.SQUARE }
        assertFalse(
            "opening Customize media must not turn the AUTOMATIC tile into his pick (it would outrank the game's own cover)",
            square.needsMigration,
        )
        assertTrue(square.display.entries.isEmpty())
        assertEquals(
            "and the cover he has still wins",
            ShelfFaceTier.AUTOMATIC_COVER,
            shelf(automatic).tier,
        )
    }

    // -- The pure rules ------------------------------------------------------------------------------------------

    @Test
    fun `a found result goes to the lane its shape belongs in`() {
        assertEquals(IconArtLane.SQUARE, iconArtLaneOf(SteamGridDbSquareGridType, fromSteamGridDb = true))
        assertEquals("a SteamGridDB icon has no media type", IconArtLane.SQUARE, iconArtLaneOf(null, fromSteamGridDb = true))
        assertEquals(IconArtLane.BOX, iconArtLaneOf(SteamGridDbPortraitGridType, fromSteamGridDb = true))
        listOf("box-2d", "box-3d", "box-texture", "box-2d-back", "box-scan").forEach {
            assertEquals(it, IconArtLane.BOX, iconArtLaneOf(it, fromSteamGridDb = false))
        }
        listOf("support-2d", "support-3d", "support-label", "support-texture", "support-future").forEach {
            assertEquals(it, IconArtLane.ARTICLE, iconArtLaneOf(it, fromSteamGridDb = false))
        }
        assertEquals("a ScreenScraper asset of no known type keeps its shape", IconArtLane.BOX, iconArtLaneOf(null, fromSteamGridDb = false))
        assertEquals(
            "each lane is the store the old pickers used",
            listOf(EnrichMediaKind.ICON, EnrichMediaKind.COVER, EnrichMediaKind.TOME_PHYSICAL),
            IconArtLane.entries.map { it.kind },
        )
    }

    @Test
    fun `an upload goes by its own shape`() {
        assertEquals(IconArtLane.SQUARE, iconArtLaneOfAspect(1f))
        assertEquals(IconArtLane.SQUARE, iconArtLaneOfAspect(1.07f))
        assertEquals(IconArtLane.SQUARE, iconArtLaneOfAspect(0.93f))
        assertEquals(IconArtLane.BOX, iconArtLaneOfAspect(0.75f))
        assertEquals(IconArtLane.BOX, iconArtLaneOfAspect(2f))
        assertEquals(IconArtLane.BOX, iconArtLaneOfAspect(1.2f))
        assertEquals("an unreadable shape is a box, the lane that never crops", IconArtLane.BOX, iconArtLaneOfAspect(null))
        assertEquals(IconArtLane.BOX, iconArtLaneOfAspect(Float.NaN))
        assertEquals(IconArtLane.BOX, iconArtLaneOfAspect(0f))
    }

    @Test
    fun `the Type filter keeps exactly what it names`() {
        val square = SteamGridDbSquareGridType
        assertTrue(iconArtTypeMatches(IconArtType.All, "box-3d"))
        assertTrue(iconArtTypeMatches(IconArtType.Square, square))
        assertTrue(iconArtTypeMatches(IconArtType.Square, null))
        assertFalse(iconArtTypeMatches(IconArtType.Square, "box-2d"))
        assertTrue(iconArtTypeMatches(IconArtType.Front, "box-2d"))
        assertTrue(iconArtTypeMatches(IconArtType.Front, "box-scan"))
        assertTrue("a PC game's portrait grid is its front", iconArtTypeMatches(IconArtType.Front, SteamGridDbPortraitGridType))
        assertFalse("the back is not the front", iconArtTypeMatches(IconArtType.Front, "box-2d-back"))
        assertTrue(iconArtTypeMatches(IconArtType.ThreeD, "box-3d"))
        assertFalse(iconArtTypeMatches(IconArtType.ThreeD, "box-2d"))
        assertTrue(iconArtTypeMatches(IconArtType.Texture, "box-texture"))
        assertTrue(iconArtTypeMatches(IconArtType.Back, "box-2d-back"))
        assertFalse(iconArtTypeMatches(IconArtType.Back, "box-2d"))
        listOf("support-2d", "support-3d", "support-label", "support-texture").forEach {
            assertTrue(it, iconArtTypeMatches(IconArtType.Article, it))
            assertFalse(it, iconArtTypeMatches(IconArtType.ThreeD, it))
        }
        assertFalse(iconArtTypeMatches(IconArtType.Article, "box-3d"))
    }

    @Test
    fun `the Type values are the shapes a provider can answer for the game`() {
        assertEquals(
            listOf("All", "Square", "Front", "3D", "Article", "Texture", "Back"),
            iconArtTypeValues(PlatformId("snes"), emulated = true),
        )
        assertEquals(
            "a PC game has SteamGridDB only: squares and its portrait front",
            listOf("All", "Square", "Front"),
            iconArtTypeValues(PlatformId.PC_GAMES, emulated = true),
        )
        assertEquals(listOf("All", "Square"), iconArtTypeValues(null, emulated = false))
        assertTrue(iconArtTypeUsesSteamGridDb(IconArtType.All, pcGame = false))
        assertTrue(iconArtTypeUsesSteamGridDb(IconArtType.Square, pcGame = false))
        assertFalse(iconArtTypeUsesSteamGridDb(IconArtType.ThreeD, pcGame = false))
        assertTrue(iconArtTypeUsesSteamGridDb(IconArtType.Front, pcGame = true))
        assertFalse(iconArtTypeUsesSteamGridDb(IconArtType.Front, pcGame = false))
        assertFalse(iconArtTypeUsesScreenScraper(IconArtType.Square))
        assertTrue(iconArtTypeUsesScreenScraper(IconArtType.All))
        assertTrue(iconArtTypeUsesScreenScraper(IconArtType.Article))
    }

    @Test
    fun `the lane of a shelf face is the store it was painted from, and an automatic face is no lane`() {
        val inputs = ShelfFaceInputs(
            coverPickUri = "file:///cover.png#v=1",
            physicalPickUri = "file:///article.png#v=1",
            squareTileUri = "file:///tile.png#v=1",
        )
        assertEquals(IconArtLane.SQUARE, iconArtLaneOfShelfFace(ShelfFace(inputs.squareTileUri, ShelfFaceShape.SQUARE), inputs))
        assertEquals(IconArtLane.BOX, iconArtLaneOfShelfFace(ShelfFace(inputs.coverPickUri, ShelfFaceShape.LEDGE_COVER), inputs))
        assertEquals(IconArtLane.ARTICLE, iconArtLaneOfShelfFace(ShelfFace(inputs.physicalPickUri, ShelfFaceShape.PHYSICAL), inputs))
        assertEquals(
            "a disc recorded as PHYSICAL whose file sits in the cover store is the box lane's file",
            IconArtLane.BOX,
            iconArtLaneOfShelfFace(ShelfFace(inputs.coverPickUri, ShelfFaceShape.PHYSICAL), inputs),
        )
        assertNull("the automatic cover is no pick", iconArtLaneOfShelfFace(ShelfFace("file:///media/Game.png", ShelfFaceShape.LEDGE_COVER), inputs))
        assertNull(iconArtLaneOfShelfFace(ShelfFace(null, ShelfFaceShape.NONE), inputs))
    }

    /**
     * The picker's "in use" is the shelf's own answer. The scenario that shipped it wrong, built on the state an older build
     * leaves: a Shelves box (record COVER), then a newer square from the OLD Icon art picker, which wrote no record. The shelf
     * shows the square (the newer pick); the picker used to read its catalogs, let the COVER record win outright, mark the box
     * in use, and hand the square a remove mark that would have deleted the file the shelf was painting.
     */
    @Test
    fun `a newer square under an older recorded box is the one in use, and removing never deletes what the shelf shows`() {
        val key = "icon-art/in-use-newer-square"
        val box = pick(IconArtLane.BOX, key, "u-box.png", "box-3d")
        val square = pick(IconArtLane.SQUARE, key, "u-square.png", SteamGridDbSquareGridType)
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
        val shown = shelf(key)
        assertEquals("the shelf shows the newer square", ShelfFaceShape.SQUARE, shown.face.shape)

        val merged = IconArtStore.load(context, key)

        assertEquals(IconArtLane.SQUARE, IconArtStore.winningLane(context, key))
        assertEquals(IconArtLane.SQUARE, merged.activeLane)
        assertEquals("the picker marks what the shelf shows", square, merged.activeAssetId)
        assertEquals("and it leads", square, merged.saved.first().assetId)
        assertFalse("what the shelf shows has no remove", runBlocking { IconArtStore.remove(context, key, square) })
        assertTrue("the box is saved and not showing", runBlocking { IconArtStore.remove(context, key, box) })
        assertEquals("the shelf still paints the same file", shown.face.uri, shelf(key).face.uri)
        assertTrue(tileFile(key).isFile)
        // And no remove, of any choice the gallery lists, ever takes the file the shelf paints.
        for (asset in IconArtStore.load(context, key).saved) {
            runBlocking { IconArtStore.remove(context, key, asset.assetId) }
            val after = shelf(key)
            assertEquals("after removing ${asset.assetId} the shelf still paints a pick", ShelfFaceTier.PICK, after.tier)
            assertEquals("and it is the same file, untouched", shown.face.uri, after.face.uri)
            assertTrue("whose file is there", tileFile(key).isFile)
        }
    }

    @Test
    fun `a newer box over an older square is the one in use, and the picker always names the lane the shelf paints`() {
        val key = "icon-art/in-use-newer-box"
        pick(IconArtLane.SQUARE, key, "n-square.png", SteamGridDbSquareGridType)
        val box = pick(IconArtLane.BOX, key, "n-box.png", "box-3d")
        assertEquals("the shelf shows the newer box", ShelfFaceShape.LEDGE_COVER, shelf(key).face.shape)
        assertEquals(box, IconArtStore.load(context, key).activeAssetId)

        // A record naming the square is his last choice outright: the shelf answers, and the picker follows it.
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.SQUARE)
        val shownShape = shelf(key).face.shape
        val lane = IconArtStore.winningLane(context, key)
        assertEquals(if (shownShape == ShelfFaceShape.SQUARE) IconArtLane.SQUARE else IconArtLane.BOX, lane)
        assertEquals(lane, IconArtStore.load(context, key).activeLane)
    }

    @Test
    fun `an uncatalogued fresher square and an older cover read as one gallery with the square in use`() {
        val key = "icon-art/in-use-legacy"
        writePick(CustomCoverStore.activeFile(context, key))
        GameIdentityMediaSelectionStore.write(
            context, EnrichMediaKind.COVER, key,
            GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, 1_000L),
        )
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
        writePick(tileFile(key))
        GameIdentityMediaSelectionStore.write(
            context, EnrichMediaKind.ICON, key,
            GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, 2_000L),
        )

        val shown = mergeIconArtCatalogs(IconArtStore.laneInputs(context, key).associate { it.lane to it.display }, IconArtStore.winningLane(context, key))

        assertEquals(ShelfFaceShape.SQUARE, shelf(key).face.shape)
        assertEquals("the page marks the square the shelf paints, before anything is adopted", IconArtLane.SQUARE, shown.activeLane)
    }

    /**
     * No record, two live picks, each with the time its marker carries: the newer one is the face, and swapping the times swaps
     * the face. (This used to be proven on catalogs alone, where a PHYSICAL record fell back to the box lane and never reached
     * the clock; the shelf reads files and markers, and so does this.)
     */
    @Test
    fun `with no record the newer of two live picks is the one in use, and swapping the times swaps it`() {
        fun setUp(key: String, squareAt: Long, articleAt: Long) {
            writePick(tileFile(key))
            GameIdentityMediaSelectionStore.write(
                context, EnrichMediaKind.ICON, key,
                GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, squareAt),
            )
            writePick(TomeCartridgeArtStore.activeFile(context, key))
            GameIdentityMediaSelectionStore.write(
                context, EnrichMediaKind.TOME_PHYSICAL, key,
                GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, articleAt),
            )
            assertNull(WiredFaceStore.recorded(context, key, MediaState.SHELF))
        }
        val articleNewer = "icon-art/clock-article"
        setUp(articleNewer, squareAt = 1_000L, articleAt = 2_000L)
        assertEquals(ShelfFaceShape.PHYSICAL, shelf(articleNewer).face.shape)
        assertEquals(IconArtLane.ARTICLE, IconArtStore.winningLane(context, articleNewer))

        val squareNewer = "icon-art/clock-square"
        setUp(squareNewer, squareAt = 2_000L, articleAt = 1_000L)
        assertEquals(ShelfFaceShape.SQUARE, shelf(squareNewer).face.shape)
        assertEquals(IconArtLane.SQUARE, IconArtStore.winningLane(context, squareNewer))
    }

    /**
     * The clock, with a record in play: a COVER record whose box lane is EMPTY names no file of its own, so the shelf paints the article's
     * file under it (the kind decides the shape, the file is looked up where it is) and compares it with the square by their marker times.
     * The newer wins, and the picker names the lane whose file is on the shelf.
     */
    @Test
    fun `a COVER record with an empty box lane leaves the clock to decide between the article's file and the square`() {
        fun setUp(key: String, squareAt: Long, articleAt: Long) {
            writePick(tileFile(key))
            mark(EnrichMediaKind.ICON, key, squareAt)
            writePick(TomeCartridgeArtStore.activeFile(context, key))
            mark(EnrichMediaKind.TOME_PHYSICAL, key, articleAt)
            WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
            assertFalse("the box lane is empty", CustomCoverStore.hasOverride(context, key))
        }
        val articleNewer = "icon-art/cover-record-article-newer"
        setUp(articleNewer, squareAt = 1_000L, articleAt = 2_000L)
        val article = shelf(articleNewer)
        assertEquals("the record names a cover and the article's file is what there is", ShelfFaceShape.LEDGE_COVER, article.face.shape)
        assertTrue(article.face.uri!!.contains("tome-cartridge-art"))
        assertEquals(IconArtLane.ARTICLE, IconArtStore.winningLane(context, articleNewer))

        val squareNewer = "icon-art/cover-record-square-newer"
        setUp(squareNewer, squareAt = 2_000L, articleAt = 1_000L)
        assertEquals(ShelfFaceShape.SQUARE, shelf(squareNewer).face.shape)
        assertEquals(IconArtLane.SQUARE, IconArtStore.winningLane(context, squareNewer))
    }

    @Test
    fun `a picture held by two lanes is listed once, under the lane that shows it`() {
        fun asset(id: String) = EnrichSavedAsset(id, EnrichSavedSource.STEAMGRIDDB, id, "", "", 0, 0, 1L, addedAt = 1L)
        val shared = asset("sgdb-shared")
        val merged = mergeIconArtCatalogs(
            mapOf(
                IconArtLane.SQUARE to EnrichSavedCatalog(null, listOf(shared)),
                IconArtLane.BOX to EnrichSavedCatalog(
                    "sgdb-shared",
                    listOf(shared),
                    GameIdentityMediaSelection.SELECTED,
                    selectionChangedAtMillis = 5L,
                ),
            ),
            winner = IconArtLane.BOX,
        )
        assertEquals(listOf("sgdb-shared"), merged.saved.map { it.assetId })
        assertEquals(IconArtLane.BOX, merged.lanes.getValue("sgdb-shared"))
        assertEquals("sgdb-shared", merged.activeAssetId)
    }

    // -- The picker's state ---------------------------------------------------------------------------------------

    private fun iconArtState(types: List<String> = iconArtTypeValues(PlatformId("snes"), emulated = true)) =
        newHomeCustomEnrichState(
            sessionId = "icon-art",
            kind = EnrichMediaKind.ICON,
            cacheKey = "icon-art/state",
            gameTitle = "Metroid",
            catalog = EnrichSavedCatalog(null, emptyList()),
            iconArt = IconArtCatalog.Empty,
            faceTypes = types,
        )

    @Test
    fun `the merged picker's filters show Region with ScreenScraper and Style and Size with SteamGridDB`() {
        val state = iconArtState()
        assertTrue(state.shelfIconArt)
        assertEquals(listOf("face", "region", "style", "size"), state.filters.map { it.key })
        assertEquals(listOf("All", "Square", "Front", "3D", "Article", "Texture", "Back"), state.filters.first().values)

        fun visible(type: String) = state.copy(
            filters = state.filters.map { if (it.key == IconArtTypeFilterKey) it.copy(selectedIndex = it.values.indexOf(type)) else it },
        ).visibleFilters().map { it.key }

        assertEquals("All asks both providers", listOf("face", "region", "style", "size"), visible("All"))
        assertEquals("a square is SteamGridDB's", listOf("face", "style", "size"), visible("Square"))
        listOf("Front", "3D", "Article", "Texture", "Back").forEach {
            assertEquals("$it is ScreenScraper's", listOf("face", "region"), visible(it))
        }
    }

    /** ScreenScraper is asked for an emulated game that is not a PC game, and for nothing else: a Region chip anywhere else constrains nothing. */
    @Test
    fun `the Region chip is shown only where ScreenScraper is asked`() {
        fun visible(types: List<String>, type: String): List<String> {
            val state = iconArtState(types)
            return state.copy(
                filters = state.filters.map { if (it.key == IconArtTypeFilterKey) it.copy(selectedIndex = it.values.indexOf(type)) else it },
            ).visibleFilters().map { it.key }
        }
        val emulated = iconArtTypeValues(PlatformId("snes"), emulated = true)
        val pc = iconArtTypeValues(PlatformId.PC_GAMES, emulated = true)
        val android = iconArtTypeValues(null, emulated = false)

        assertEquals(listOf("face", "region", "style", "size"), visible(emulated, "All"))
        assertEquals(listOf("face", "region"), visible(emulated, "Front"))
        assertEquals("a PC game: SteamGridDB only", listOf("face", "style", "size"), visible(pc, "All"))
        assertEquals(listOf("face", "style", "size"), visible(pc, "Square"))
        assertEquals("its portrait grid is SteamGridDB's too", listOf("face"), visible(pc, "Front"))
        assertEquals("an Android row", listOf("face", "style", "size"), visible(android, "All"))
        assertEquals(listOf("face", "style", "size"), visible(android, "Square"))
    }

    @Test
    fun `a filter that is not shown does not constrain the request`() {
        val state = iconArtState()
        val threeD = state.copy(
            filters = state.filters.map { if (it.key == IconArtTypeFilterKey) it.copy(selectedIndex = it.values.indexOf("3D")) else it },
        )
        assertEquals("Style is hidden under 3D, so the request uses its neutral default", "All", threeD.effectiveFilterValue("style", hiddenDefault = "All"))
        assertEquals("Any", threeD.effectiveFilterValue("size", hiddenDefault = "Any"))
    }

    @Test
    fun `the platform picker is untouched by the merge`() {
        val platform = newHomeCustomEnrichState(
            sessionId = "platform",
            kind = EnrichMediaKind.ICON,
            cacheKey = SteamGridDbTileFetcher.platformKey(PlatformId("snes")),
            gameTitle = "Super Nintendo",
            catalog = EnrichSavedCatalog(null, emptyList()),
        )
        assertFalse(platform.shelfIconArt)
        assertEquals(listOf("kind", "style", "size"), platform.filters.map { it.key })
        assertEquals(listOf("All", "Grids", "Icons"), platform.filters.first().values)
        assertEquals(EnrichMediaKind.ICON, platform.geometryKind)
    }

    @Test
    fun `the merged picker takes the cover lane's geometry, so a square is a square and a box is a box`() {
        val state = iconArtState()
        assertEquals(EnrichMediaKind.COVER, state.geometryKind)
        val bar = 20
        val squareWidth = homeCustomEnrichCoverTileWidthDp(false, 1f, 0, bar, bar)
        val boxWidth = homeCustomEnrichCoverTileWidthDp(false, 0.72f, 0, bar, bar)
        assertEquals("a square is as wide as the art is tall", homeCustomEnrichTileArtHeightDp(EnrichMediaKind.COVER, false, bar, bar), squareWidth)
        assertTrue("and a box is narrower: $boxWidth < $squareWidth", boxWidth < squareWidth)
    }

    @Test
    fun `an asset that brings no size takes the shape its lane gives it`() {
        val state = iconArtState().copy(savedLanes = mapOf("s" to IconArtLane.SQUARE, "b" to IconArtLane.BOX, "d" to IconArtLane.ARTICLE))
        fun saved(id: String) = HomeCustomEnrichAsset.Saved(EnrichSavedAsset(id, EnrichSavedSource.LEGACY, id, "", "", 0, 0, 1L, addedAt = 1L))
        assertEquals(1f, state.iconArtLaneAspect(saved("s")))
        assertNull("a box takes the cover law's own portrait", state.iconArtLaneAspect(saved("b")))
        assertEquals(1f, state.iconArtLaneAspect(saved("d")))
        assertNull(state.iconArtLaneAspect(HomeCustomEnrichAsset.Automatic))
        assertNull("only the merged picker has lanes", iconArtState().copy(shelfIconArt = false, savedLanes = state.savedLanes).iconArtLaneAspect(saved("s")))
    }

    // -- The tile menu publishes once ------------------------------------------------------------------------------

    @Test
    fun `an icon action in the tile menu refreshes Home's tiles and publishes the shelf faces once`() {
        val calls = mutableListOf<String>()
        publishTileMenuIconChange({ calls += "tiles" }, { calls += "faces" })
        assertEquals(listOf("tiles", "faces"), calls)
    }

    @Test
    fun `the tile menu's icon pick, upload and Default each publish through that one call`() {
        val coordinator = sourceOf("MimirAppCoordinator.kt")
        val actions = listOf("onApplyPick = ", "onApplyUploadedArt = ", "onResetArt = ", "onLoadHero = ")
        val bodies = actions.zipWithNext().map { (from, to) ->
            from to coordinator.substringAfter("                $from").substringBefore("                $to")
        }
        bodies.take(3).forEach { (action, body) ->
            assertEquals("$action publishes once", 1, Regex("publishTileMenuIconChange\\(").findAll(body).count())
            assertTrue("$action publishes the shelf faces", body.contains("core.repository.refreshShelfFaces()"))
            assertEquals("$action no longer refreshes Home's tiles on its own", 0, Regex("homeShelf\\.refreshTiles\\(\\)").findAll(body).count())
        }
        assertEquals(
            "refreshShelfFaces is called from exactly these three places in the coordinator",
            3,
            Regex("refreshShelfFaces\\(\\)").findAll(coordinator).count(),
        )
    }

    /**
     * The merged picker's apply path is the one that is live. Every leg of it (Default, a saved choice, a found result, an upload) ends in
     * `refreshCustomEnrichOwner`, which asks the repository to `refreshArt` the game: that re-projects every open row flow and re-reads each
     * row's shelf face, so the Library and the Bottom repaint (the repository's side is pinned in `LibraryRepositoryShelfFaceTest`). It does not
     * ALSO publish `refreshShelfFaces()`: that would be a second library-wide re-projection for the same pick, and the three calls left in the
     * coordinator are the tile menu's dormant ones.
     */
    @Test
    fun `every leg of the merged picker's apply ends in the one refresh that repaints the Library`() {
        val coordinator = sourceOf("MimirAppCoordinator.kt")
        fun leg(from: String, to: String) = coordinator.substringAfter(from).substringBefore(to)
        val automatic = leg("HomeCustomEnrichCommand.Automatic -> {", "is HomeCustomEnrichCommand.ActivateSaved -> {")
        val activate = leg("is HomeCustomEnrichCommand.ActivateSaved -> {", "is HomeCustomEnrichCommand.RemoveSaved -> {")
        assertTrue(automatic.contains("IconArtStore.activateAutomatic(") && automatic.contains("if (marked) refreshCustomEnrichOwner(owner)"))
        assertTrue(activate.contains("IconArtStore.activate(") && activate.contains("if (activated) refreshCustomEnrichOwner(owner)"))
        assertTrue(
            "a found result",
            coordinator.contains("if (activated && command.activate) refreshCustomEnrichOwner(owner)"),
        )
        assertTrue("an upload", coordinator.contains("if (activated) refreshCustomEnrichOwner(request.owner)"))
        val refresh = coordinator.substringAfter("fun refreshCustomEnrichOwner(owner: CustomEnrichPickerOwner) {").substringBefore("fun updateHomeCustomEnrich(")
        assertTrue("and that reaches the repository", refresh.contains("core.repository.refreshArt(gameId)"))
        assertEquals("one re-projection per apply: refreshShelfFaces is not also called there", 0, refresh.split("refreshShelfFaces(").size - 1)
        assertTrue(
            "the tile menu's publish says it is dormant",
            sourceOf("MimirAppHomeArtHelpers.kt").substringBefore("internal fun publishTileMenuIconChange").contains("DORMANT"),
        )
    }

    private fun sourceOf(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()
}
