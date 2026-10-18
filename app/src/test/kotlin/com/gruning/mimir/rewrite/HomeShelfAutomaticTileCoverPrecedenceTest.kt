package com.gruning.mimir.rewrite

import android.net.Uri
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A COVER outranks the automatic SteamGridDB tile on the Home shelf.
 *
 * The PC-cover precedence rule matches the Library (pinned by `LibraryShelfFaceSeamTest.kt`)
 * and extends to every emulated row: a game's face is its box, on Home as everywhere else.
 *
 * This file was `HomeShelfAutomaticTilePcPrecedenceTest` and one of its tests asserted the OPPOSITE of
 * what it now asserts -- see `an emulated row with a cover keeps the cover` below, which is the same
 * fixture with the expectation reversed. That test was not wrong when it was written: the initial rule was scoped to
 * `pc_games` on purpose and the test pinned the scope. What it also did was pin the defect reported
 * on 2026-09-17 -- every scraped game wearing a square SteamGridDB icon on Home while its box-3D sat on
 * disk one surface away. The boxes were
 * fetched. Home was painting over them.
 *
 * ## The second failure, and what this file was missing
 *
 * The widening shipped on 2026-09-17 and **changed nothing on the device.** Every test here passed. The
 * same square icons were reported after a device check.
 *
 * The guard carried an escape hatch: a `.idx` sidecar beside the tile meant "explicitly selected by the user", so the cover stood aside. A comment in `HomeShelfCoordinator.kt` asserted that only
 * `activateLocalPick` ever writes that sidecar. The automatic batch writes one too, on every successful
 * download (`SteamGridDbTileFetcher.kt:865`, FUX-09's reroll cursor) -- by hand, not through
 * `pickIndexFile`, which is why searching for the writer finds one caller and appears to confirm the
 * comment. The hatch was open on every tile in existence.
 *
 * **These tests could not have caught it**, and that is the more useful lesson than the bug. Every one of
 * them wrote a tile and no sidecar -- a state the automatic path never leaves behind on a real device.
 * The fixture was a state that does not occur. `an automatic tile writes a sidecar too` below is the same
 * fixture built the way `fetchMissing` actually builds it, and it fails against the old guard.
 *
 * ## What decides it now
 *
 * [homeIconOwnerPicked] -- the explicit selection marker, the same authority
 * [automaticTileFetchMayInstall] uses to decide whether the automatic fetch may write the slot at all, so
 * guard and install gate cannot disagree. The stamp itself is folded into the ONE shelf-face resolver
 * ([resolveShelfFaces], 2026-10-10: one shelf, one "Icon art"); these tests drive it through the same row feed Home
 * uses ([withShelfFaces]) against real on-disk state, because the disk-state -> flag step is the one that shipped
 * broken and must be tested directly rather than only through the boolean.
 *
 * Pins the shapes that matter, including the one edge the widening must NOT cross:
 *  - any emulated row WITH its own `artworkUri` keeps it, `pc_games` or not, even though a tile exists on
 *    disk for the identical cache key (the differential guard: the tile genuinely exists, so "no effect"
 *    cannot pass by accident);
 *  - an emulated row with NO `artworkUri` (and, separately, a blank/whitespace-only one -- treated as
 *    absent, matching the Library projection's `artUri?.trim()?.takeIf { it.isNotEmpty() }` idiom) still
 *    gets the tile: a tile beats nothing;
 *  - an ANDROID row still gets its tile even though it carries an `artworkUri`. That value is the
 *    launcher icon (`content://launcher/icon`), not a cover, and a package's art IS its icon. This is why
 *    the guard is keyed on the item KIND rather than on `artworkUri` alone -- key it on the field and
 *    every Android tile on Home goes blank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class HomeShelfAutomaticTileCoverPrecedenceTest {

    private fun row(
        itemId: HomeShelfItemId,
        platformId: PlatformId?,
        artworkUri: String?,
    ) = HomeShelfRow(
        itemId = itemId,
        rank = 0L,
        addedAtMillis = 0L,
        title = "Test",
        artworkUri = artworkUri,
        platformId = platformId,
    )

    /** Writes a tile at the canonical automatic path and returns the file [SteamGridDbTileFetcher.tileIconFile] resolves to (identical, absent any EnrichSavedStore rollback/override for this fresh cache key). */
    private fun writeTile(cacheKey: String): File {
        val context = RuntimeEnvironment.getApplication()
        val target = SteamGridDbTileFetcher.canonicalTileIconFile(context, cacheKey)
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))
        val resolved = SteamGridDbTileFetcher.tileIconFile(context, cacheKey)
        assertEquals("test setup sanity: tileIconFile must resolve to the file this test wrote", target, resolved)
        return resolved
    }

    /**
     * Writes the tile AND the reroll cursor beside it, exactly as `SteamGridDbTileFetcher.fetchMissing`
     * leaves the slot after a successful automatic download: `File(dir, fileNameFor(key) + ".idx")`
     * holding the candidate index it took. This is the on-disk state a real device is in; [writeTile]'s
     * sidecar-less state is not one the automatic path can produce.
     */
    private fun writeAutomaticallyFetchedTile(cacheKey: String): File {
        val tile = writeTile(cacheKey)
        File(tile.parentFile, tile.name + ".idx").writeText("0")
        return tile
    }

    private fun expectedTileUri(tile: File): String = Uri.fromFile(tile).toString() + "#v=" + tile.lastModified()

    private fun cacheKeyOf(row: HomeShelfRow): String = when (val id = row.itemId) {
        is HomeShelfItemId.Emulated -> SteamGridDbTileFetcher.emulatedKey(id.gameId)
        is HomeShelfItemId.AndroidGame -> SteamGridDbTileFetcher.androidKey(id.packageName.value)
        is HomeShelfItemId.AndroidApp -> SteamGridDbTileFetcher.androidKey(id.packageName.value)
    }

    /**
     * What the coordinator does to a row: one resolution, fed into the row's face fields. [ownerPickedIcon] writes
     * the explicit selection marker `SteamGridDbTileFetcher.applyPick` commits, which is the only thing that makes
     * a tile "his".
     */
    private fun HomeShelfRow.shelfFaced(ownerPickedIcon: Boolean): HomeShelfRow {
        val context = RuntimeEnvironment.getApplication()
        val key = cacheKeyOf(this)
        if (ownerPickedIcon) {
            GameIdentityMediaSelectionStore.set(context, EnrichMediaKind.ICON, key, GameIdentityMediaSelection.SELECTED)
        }
        return withShelfFaces(resolveShelfFaces(context, key, hasOwnCoverArt(), artworkUri))
    }

    // ── the regression pin ────────────────────────────────────────────────────────────────────────

    /**
     * **The test this file did not have.** Identical to `an emulated row with a cover keeps the cover`
     * except that the tile is on disk in the state the automatic batch actually leaves it in -- sidecar
     * and all. Against the guard that shipped on 2026-09-17 this fails: the `.idx` clause read the
     * reroll cursor as an owner's pick, stood the cover aside, and the square icon won. Against the
     * current guard the sidecar is not consulted at all, which is the point.
     */
    @Test
    fun `an automatic tile writes a sidecar too, and the cover still wins`() {
        val id = GameId("snes/fetched-automatically")
        val tile = writeAutomaticallyFetchedTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = "file:///scraped/cover.png")

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertNull(
            "a reroll cursor is not an owner's pick -- the box must still win",
            stamped.tileIconUri,
        )
        assertEquals("the box is the face", "file:///scraped/cover.png", stamped.faceUri)
    }

    // ── the predicate itself, against real on-disk + SharedPreferences state ──────────────────────

    @Test
    fun `homeIconOwnerPicked is false for a tile the automatic batch fetched, sidecar and all`() {
        val context = RuntimeEnvironment.getApplication()
        val key = SteamGridDbTileFetcher.emulatedKey(GameId("snes/auto"))
        writeAutomaticallyFetchedTile(key)

        assertFalse(
            "nothing on the automatic path records an ICON selection -- fetchMissing only reads one",
            homeIconOwnerPicked(context, key),
        )
    }

    @Test
    fun `homeIconOwnerPicked is true once the owner picks an icon`() {
        val context = RuntimeEnvironment.getApplication()
        val key = SteamGridDbTileFetcher.emulatedKey(GameId("snes/picked"))
        writeAutomaticallyFetchedTile(key)

        // What `SteamGridDbTileFetcher.applyPick` commits before it installs the chosen candidate.
        GameIdentityMediaSelectionStore.set(
            context,
            EnrichMediaKind.ICON,
            key,
            GameIdentityMediaSelection.SELECTED,
        )

        assertTrue(homeIconOwnerPicked(context, key))
    }

    @Test
    fun `homeIconOwnerPicked is false after the owner reverts to Automatic`() {
        val context = RuntimeEnvironment.getApplication()
        val key = SteamGridDbTileFetcher.emulatedKey(GameId("snes/reverted"))
        writeAutomaticallyFetchedTile(key)
        GameIdentityMediaSelectionStore.set(
            context,
            EnrichMediaKind.ICON,
            key,
            GameIdentityMediaSelection.SELECTED,
        )

        // What `clearPickAsAutomatic` commits. An explicit "go back to automatic" is a real answer and is
        // durable, but it is not a pick, so the cover takes the face back.
        GameIdentityMediaSelectionStore.set(
            context,
            EnrichMediaKind.ICON,
            key,
            GameIdentityMediaSelection.AUTOMATIC,
        )

        assertFalse(homeIconOwnerPicked(context, key))
    }

    @Test
    fun `an owner-picked icon outranks the cover`() {
        val id = GameId("snes/owner-chose-this")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = "file:///scraped/cover.png")

        val stamped = original.shelfFaced(ownerPickedIcon = true)

        assertEquals(
            "CP06: an icon the owner chose himself is no longer automatic and must not be withheld",
            expectedTileUri(tile),
            stamped.tileIconUri,
        )
    }

    // ── the original precedence shapes ────────────────────────────────────────────────────────────

    @Test
    fun `pc_games row with its own cover keeps it even though a tile exists on disk`() {
        val id = GameId("pc/own-cover")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.PC_GAMES, artworkUri = "file:///rom/pc/own-cover.png")

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertNull("owner's own cover must not be displaced by the automatic tile", stamped.tileIconUri)
        assertEquals("file:///rom/pc/own-cover.png", stamped.faceUri)
    }

    @Test
    fun `pc_games row with no cover still gets the tile`() {
        val id = GameId("pc/no-cover")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.PC_GAMES, artworkUri = null)

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertEquals(expectedTileUri(tile), stamped.tileIconUri)
    }

    @Test
    fun `blank whitespace-only artworkUri on a pc_games row is treated as absent, matching the Library idiom`() {
        val id = GameId("pc/blank-cover")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.PC_GAMES, artworkUri = "   ")

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertEquals(expectedTileUri(tile), stamped.tileIconUri)
    }

    /**
     * The inverted one. Read `assertEquals(expectedTileUri(tile), ...)` here until 2026-09-17: a scraped
     * SNES box lost its own Home tile to a SteamGridDB square. Same fixture, opposite expectation, and the
     * reason is a ruling rather than a discovery -- the old scope was deliberate, it just turned out to be
     * the wrong shape once every emulated game started getting a box-3D.
     */
    @Test
    fun `an emulated row with a cover keeps the cover, on every platform`() {
        val id = GameId("snes/some-game")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = "file:///scraped/cover.png")

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertNull(
            "a scraped box-3D must not be displaced by an automatic square tile",
            stamped.tileIconUri,
        )
        assertEquals("file:///scraped/cover.png", stamped.faceUri)
    }

    @Test
    fun `an emulated row with no cover still gets the tile, because a tile beats nothing`() {
        val id = GameId("snes/unscraped")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = null)

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertEquals(expectedTileUri(tile), stamped.tileIconUri)
    }

    @Test
    fun `an Android row keeps its tile -- its artworkUri is a launcher icon, not a cover`() {
        val packageName = AndroidPackageName("com.example.app")
        val tile = writeTile(SteamGridDbTileFetcher.androidKey(packageName.value))
        val original = row(HomeShelfItemId.AndroidGame(packageName), platformId = null, artworkUri = "content://launcher/icon")

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertEquals(expectedTileUri(tile), stamped.tileIconUri)
    }

    @Test
    fun `no tile on disk never stamps, pc_games or not`() {
        val id = GameId("pc/never-fetched")
        val context = RuntimeEnvironment.getApplication()
        val tile = SteamGridDbTileFetcher.tileIconFile(context, SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.PC_GAMES, artworkUri = null)

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertNull(stamped.tileIconUri)
        assertNull(stamped.faceUri)
    }

    /** Even an owner's pick cannot stamp a file that is not there. */
    @Test
    fun `no tile on disk never stamps, even when the owner picked one`() {
        val id = GameId("snes/picked-but-missing")
        val context = RuntimeEnvironment.getApplication()
        val tile = SteamGridDbTileFetcher.tileIconFile(context, SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = "file:///scraped/cover.png")

        val stamped = original.shelfFaced(ownerPickedIcon = true)

        assertNull(stamped.tileIconUri)
        assertEquals("file:///scraped/cover.png", stamped.faceUri)
    }

    // -- 2026-10-10: one shelf face --------------------------------------------------------------------------

    /**
     * The C4 gap the old stamp had. Its cover guard read the AUTOMATIC cover (`artworkUri`) only, so a cover the
     * owner had PICKED -- which rides `faceUri`, not `artworkUri` -- lost to an automatic square whenever no
     * automatic cover sat beside it. His pick is the first tier now.
     */
    @Test
    fun `a cover he picked beats an automatic square even with no automatic cover beside it`() {
        val id = GameId("snes/picked-cover-no-auto-cover")
        val key = SteamGridDbTileFetcher.emulatedKey(id)
        writeAutomaticallyFetchedTile(key)
        val context = RuntimeEnvironment.getApplication()
        val pick = CustomCoverStore.activeFile(context, key)
        pick.parentFile?.mkdirs()
        pick.writeBytes(byteArrayOf(9, 9, 9))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = null)

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertNull("his cover pick must not lose to an automatic square", stamped.tileIconUri)
        assertTrue(stamped.faceUri!!.contains("custom-cover-art"))
        assertFalse(stamped.faceIsPhysicalMedia)
    }

    /** A pick of the article reaches the row with its kind, not as a cover. */
    @Test
    fun `an article he picked rides faceUri as physical media`() {
        val id = GameId("gba/picked-article")
        val key = SteamGridDbTileFetcher.emulatedKey(id)
        val context = RuntimeEnvironment.getApplication()
        val pick = TomeCartridgeArtStore.activeFile(context, key)
        pick.parentFile?.mkdirs()
        pick.writeBytes(byteArrayOf(9, 9, 9))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = "file:///scraped/cover.png")

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertTrue(stamped.faceUri!!.contains("tome-cartridge-art"))
        assertTrue(stamped.faceIsPhysicalMedia)
        assertNull(stamped.tileIconUri)
    }

    /** A square pick fills the tile slot, and the face the card inherits stays the cover. */
    @Test
    fun `a square pick rides tileIconUri and leaves faceUri the cover the card inherits`() {
        val id = GameId("snes/square-pick-card-face")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.SNES, artworkUri = "file:///scraped/cover.png")

        val stamped = original.shelfFaced(ownerPickedIcon = true)

        assertEquals(expectedTileUri(tile), stamped.tileIconUri)
        assertEquals("a square never becomes a launch-card face", "file:///scraped/cover.png", stamped.faceUri)
        assertFalse(stamped.faceIsPhysicalMedia)
    }

    /**
     * `homeShelfCoverUri` hands a `pc_games` row its clear logo when it has no cover. That logo is the row's own
     * `flatArtUri` and is not a cover: it must not block the tile, or Home would keep the logo while the Library --
     * which gates on `GameRow.artUri` alone -- showed the box.
     */
    @Test
    fun `a pc_games clear logo standing in for a cover does not block the tile`() {
        val id = GameId("pc/logo-stands-in")
        val tile = writeTile(SteamGridDbTileFetcher.emulatedKey(id))
        val logo = "file:///rom/pc/logo-stands-in-logo.png"
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.PC_GAMES, artworkUri = logo).copy(flatArtUri = logo)

        assertFalse(original.hasOwnCoverArt())
        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertEquals(expectedTileUri(tile), stamped.tileIconUri)
    }

    @Test
    fun `a pc_games logo with no tile is still the face, as before`() {
        val id = GameId("pc/logo-only")
        val logo = "file:///rom/pc/logo-only-logo.png"
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.PC_GAMES, artworkUri = logo).copy(flatArtUri = logo)

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertNull(stamped.tileIconUri)
        assertEquals(logo, stamped.faceUri)
    }

    /** The portrait sibling is the Library's box for a `pc_games` row; Home paints the same face now. */
    @Test
    fun `a pc_games portrait is the face and leaves the square slot empty`() {
        val id = GameId("pc/portrait-box")
        val key = SteamGridDbTileFetcher.emulatedKey(id)
        writeTile(key)
        val context = RuntimeEnvironment.getApplication()
        val portrait = SteamGridDbTileFetcher.canonicalPortraitTileIconFile(context, key)
        portrait.writeBytes(byteArrayOf(7))
        val original = row(HomeShelfItemId.Emulated(id), PlatformId.PC_GAMES, artworkUri = null)

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertNull(stamped.tileIconUri)
        assertEquals(expectedTileUri(portrait), stamped.faceUri)
        assertFalse("a portrait box is a cover, not a disc", stamped.faceIsPhysicalMedia)
    }

    /** An Android row's preset launcher icon stays until a tile replaces it. */
    @Test
    fun `an Android row keeps its preset launcher icon when no tile exists`() {
        val packageName = AndroidPackageName("com.example.noart")
        val preset = "content://launcher/icon"
        val original = row(HomeShelfItemId.AndroidGame(packageName), platformId = null, artworkUri = preset)
            .copy(tileIconUri = preset)

        val stamped = original.shelfFaced(ownerPickedIcon = false)

        assertEquals(preset, stamped.tileIconUri)
        assertEquals(preset, stamped.faceUri)
    }
}
