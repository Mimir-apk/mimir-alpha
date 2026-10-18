package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launch cards use cartridge art when enabled, resolving both explicit picks and downloaded art.
 * Checking only an explicit pick would miss enrichment results already visible in the Library list.
 *
 * Assertions were captured failing against pre-change source: geometry rows could not compile
 * without the new branch, and law rows returned the old value.
 */
class HomeLaunchCardCartridgeArtTest {

    private fun emulatedRow(platform: String = "gamecube") = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("g1")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "Resident Evil",
        artworkUri = "file:///art/cover.png",
        supportingLabel = platform,
        platformId = PlatformId(platform),
    )

    private fun androidRow() = HomeShelfRow(
        itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "Some Android game",
        artworkUri = "file:///art/icon.png",
        supportingLabel = "Android game",
    )

    // ---------------------------------------------------------------- the law






    // ------------------------------------------------- the one kind-derivation

    /**
     * RESTATED 2026-08-25, and the reason is the defect it stopped catching.
     *
     * This pin used to assert that setting `cartridgeArtUri` ALONE made `physicalMediaCover` true — which
     * was the derivation at the time, and green. C1 then made that same field carry the face the Shelf
     * resolved, usually a cover, and the pin kept passing while the meaning inverted underneath it: every
     * inherited box was classified physical media and painted at [LibraryPhysicalMediaCoverAspect] (1f),
     * squared off.
     *
     * A uri is no longer evidence of a kind. The kind is its own fact, and this asserts it as one — the
     * `cover.copy(uri)` line below is the exact case that used to come back true.
     */
    @Test
    fun physicalMediaCoverIsTheSingleDerivationBothGeometryCallersRead() {
        val cover = HomeLaunchCardUiState(
            row = emulatedRow(),
            platformLabel = "GameCube",
            coverArtUri = "file:///art/cover.png",
        )
        assertFalse(cover.physicalMediaCover)

        val cartridge = cover.copy(
            cartridgeArtUri = "file:///rom-support.png",
            cartridgeArtIsPhysical = true,
        )
        assertTrue(cartridge.physicalMediaCover)

        assertFalse(
            "a face that is a COVER does not become a cartridge by being carried in this field -- this is " +
                "the line that was green while the launch card squared off every inherited box",
            cover.copy(cartridgeArtUri = "file:///art/box-3D.png").physicalMediaCover,
        )

        assertFalse(
            "a blank URI is not a cartridge, and must not resize the box",
            cover.copy(cartridgeArtUri = "  ", cartridgeArtIsPhysical = true).physicalMediaCover,
        )
    }

    /**
     * An Android game has no provider support media and takes the SQUARE-edge branch of
     * [homeLaunchCardRenderedArtSize], not the equal-area solver. If a caller ever handed one a cartridge
     * URI, honouring it would silently change that branch's geometry, so the kind is pinned false here at
     * the state level rather than defended at each of the two geometry call sites.
     */
    @Test
    fun anAndroidGameIsNeverAPhysicalMediaCoverEvenIfHandedOne() {
        val state = HomeLaunchCardUiState(
            row = androidRow(),
            platformLabel = "Android game",
            cartridgeArtUri = "file:///rom-support.png",
        )
        assertFalse(state.physicalMediaCover)
    }

    // ------------------------------------------------------------- the geometry

    /**
     * `[D]` from the production solvers at the two certified viewports, reference platform `gamecube`
     * (0.72) — the same reference `MimirOdinParityTest` measures its cover rows at. These are the numbers
     * used for hardware visual review: the Odin's cover box grows 216.00
     * -> 256.90dp, and the weighted text lane beside it loses exactly that.
     */
    @Test
    fun theCartridgeBranchResizesTheCoverBoxByADisclosedAmountAtBothCertifiedViewports() {
        val odinBox = MimirCertifiedViewports.Odin.fullViewportDp!!
        val odin = homeLaunchCardLayout(odinBox.widthDp.dp, odinBox.heightDp.dp)
        assertEquals(
            216.00f,
            homeLaunchCardCoverBoxWidth(true, "gamecube", odin, physicalMedia = false).value,
            0.01f,
        )
        assertEquals(
            256.90f,
            homeLaunchCardCoverBoxWidth(true, "gamecube", odin, physicalMedia = true).value,
            0.01f,
        )

        val novaBox = MimirCertifiedViewports.RetroidPocketNova.fullViewportDp!!
        val nova = homeLaunchCardLayout(novaBox.widthDp.dp, novaBox.heightDp.dp)
        assertEquals(
            217.99f,
            homeLaunchCardCoverBoxWidth(true, "gamecube", nova, physicalMedia = false).value,
            0.01f,
        )
        assertEquals(
            221.41f,
            homeLaunchCardCoverBoxWidth(true, "gamecube", nova, physicalMedia = true).value,
            0.01f,
        )
    }

    /**
     * The Odin's cartridge is area-cap-bound and lands on the FULL target area, so a cartridge does not
     * read as a lighter object than the cover it replaced. The Nova's is width-bound by its own
     * `coverColumnWidth` cap and lands at 74% — disclosed, and a property of that cap rather than of this
     * change.
     */
    @Test
    fun theCartridgeSolvesTheSameEqualAreaLawTheCoverDoes() {
        val odinBox = MimirCertifiedViewports.Odin.fullViewportDp!!
        val odin = homeLaunchCardLayout(odinBox.widthDp.dp, odinBox.heightDp.dp)
        val odinArt = homeLaunchCardRenderedArtSize(
            emulated = true,
            platformId = "gamecube",
            maxWidth = odin.coverColumnWidth,
            maxHeight = odin.coverHeight,
            physicalMedia = true,
        )
        assertEquals(1f, odinArt.width.value / odinArt.height.value, 0.0001f)
        assertEquals(66_000f, odinArt.width.value * odinArt.height.value, 1f)

        val novaBox = MimirCertifiedViewports.RetroidPocketNova.fullViewportDp!!
        val nova = homeLaunchCardLayout(novaBox.widthDp.dp, novaBox.heightDp.dp)
        val novaArt = homeLaunchCardRenderedArtSize(
            emulated = true,
            platformId = "gamecube",
            maxWidth = nova.coverColumnWidth,
            maxHeight = nova.coverHeight,
            physicalMedia = true,
        )
        assertEquals(1f, novaArt.width.value / novaArt.height.value, 0.0001f)
        assertEquals(49_022f, novaArt.width.value * novaArt.height.value, 5f)
    }

    /**
     * **The S2 anti-drift invariant, extended to the branch that was just added.** `HomeLaunchCardWide`'s
     * cover `Box` and the art node inside it call two different production functions; the box may never be
     * wider than what paints in it.
     *
     * **What this row can and cannot catch, stated so silence is not read as proof.** It feeds both
     * functions the SAME arguments, so it catches the two SOLVERS disagreeing — a second aspect lookup, a
     * different area constant, a branch added to one and not the other. It CANNOT catch the composable
     * passing `physicalMedia` to one call and forgetting it on the other, because it is not the composable.
     * That specific hazard is why `physicalMedia` is not defaulted on either function and why both sites
     * read one `state.physicalMediaCover` rather than testing `cartridgeArtUri != null` twice — a
     * construction defence, not a measured one. The measured version is a device capture.
     */
    @Test
    fun theCoverBoxStillHugsTheArtInTheCartridgeBranchAtEveryCertifiedViewport() {
        val viewports = listOf(
            MimirCertifiedViewports.Odin,
            MimirCertifiedViewports.RetroidPocketNova,
            MimirCertifiedViewports.Rp5Shelf,
            MimirCertifiedViewports.OdinHeightCompanion538,
        )
        for (vp in viewports) {
            val box = vp.solverInputDp
            val layout = homeLaunchCardLayout(box.widthDp.dp, box.heightDp.dp)
            for (physical in listOf(false, true)) {
                for (platform in listOf("gamecube", "snes", "psp", "gba")) {
                    val boxWidth = homeLaunchCardCoverBoxWidth(
                        emulated = true,
                        platformId = platform,
                        layout = layout,
                        physicalMedia = physical,
                    ).value
                    val artWidth = homeLaunchCardRenderedArtSize(
                        emulated = true,
                        platformId = platform,
                        maxWidth = layout.coverColumnWidth,
                        maxHeight = layout.coverHeight,
                        physicalMedia = physical,
                    ).width.value
                    assertEquals(
                        "${vp.name} $platform physical=$physical: the box must be the art's own width",
                        artWidth,
                        boxWidth,
                        0.0001f,
                    )
                }
            }
        }
    }

    /**
     * A cartridge box is the SAME width whatever platform the game belongs to — a cartridge render carries
     * no platform box proportion. This is what makes the `physicalMedia` parameter meaningful rather than
     * decorative: without it the four platforms below would return four different widths for one square
     * image, and three of them would letterbox it.
     */
    @Test
    fun theCartridgeBoxIsPlatformIndependent() {
        val box = MimirCertifiedViewports.Odin.fullViewportDp!!
        val layout = homeLaunchCardLayout(box.widthDp.dp, box.heightDp.dp)
        val widths = listOf("gamecube", "snes", "psp", "n64", "ps1")
            .map { homeLaunchCardCoverBoxWidth(true, it, layout, physicalMedia = true).value }
            .distinct()
        assertEquals(1, widths.size)

        val coverWidths = listOf("gamecube", "snes", "psp", "n64", "ps1")
            .map { homeLaunchCardCoverBoxWidth(true, it, layout, physicalMedia = false).value }
            .distinct()
        assertTrue(
            "the cover branch must still vary by platform -- otherwise this test proves nothing",
            coverWidths.size > 1,
        )
    }

    /**
     * The launch card and the Library list row take their square lane from ONE named constant, so the two
     * surfaces that paint a cartridge cannot drift into different proportions. Reading the list row's own
     * output rather than the constant is deliberate: it pins the consumer, not the declaration.
     */
    @Test
    fun theLaunchCardAndTheLibraryListRowShareOnePhysicalMediaAspect() {
        val game = GameRow(
            gameId = GameId("g1"),
            title = "Resident Evil",
            platformId = PlatformId("gamecube"),
            artUri = "file:///art/cover.png",
            physicalMediaUri = "file:///rom-support.png",
            memberCount = 1,
            duplicateKind = null,
        )
        // A row carrying both leads with its cover -- the bug the regression report described, in one line. The Cartridge
        // Hero switch that could reverse this was removed on 2026-09-22, and cover-first was already its
        // shipped default; the ORDER is pinned in LibraryShelfStabilizationContractTest, not here.
        assertEquals(LibraryListPreviewMediaSource.COVER, libraryListPreviewMedia(game).source)

        // The disc lane is still reachable and is what this test measures. DL-4 stands on a LIST ROW --
        // real art of either kind beats the placeholder -- so a game whose provider carries a disc render
        // and no cover paints the disc, on the square lane. Reaching that lane through the absent cover
        // rather than through a gate is what keeps the test about the LANE, which is the point of it.
        val listMedia = libraryListPreviewMedia(game.copy(artUri = null))
        assertEquals(LibraryListPreviewMediaSource.PHYSICAL, listMedia.source)
        assertEquals(LibraryPhysicalMediaCoverAspect, listMedia.aspectRatio, 0.0001f)

        val cartridge = homeLaunchCardArtSizeForAspect(
            LibraryPhysicalMediaCoverAspect,
            maxWidth = 400.dp,
            maxHeight = 400.dp,
        )
        assertEquals(
            listMedia.aspectRatio,
            cartridge.width.value / cartridge.height.value,
            0.0001f,
        )
    }

    // ------------------------------------------------------- the Library producer

    /**
     * Rule 2's wiring, at the seam it actually crosses. `libraryLaunchCardUiState` is the Library
     * gamelist's own producer; the app hands it an already-resolved URI, so this pins that the value
     * survives the hop AND that it turns into the physical-media KIND on the far side.
     */
    @Test
    fun theLibraryGamelistProducerCarriesTheCartridgeThroughToTheRenderedKind() {
        val game = GameRow(
            gameId = GameId("g1"),
            title = "Resident Evil",
            platformId = PlatformId("gamecube"),
            artUri = "file:///art/cover.png",
            physicalMediaUri = "file:///rom-support.png",
            memberCount = 1,
            duplicateKind = null,
        )
        val target = LibraryDetailTarget.Emulated(game)

        val onCover = libraryLaunchCardUiState(
            target = target,
            metadata = LibraryDetailMetadata(),
            achievementProgress = null,
            statusLine = null,
            screenScraperConnected = false,
        )
        assertNull(onCover.cartridgeArtUri)
        assertFalse(onCover.physicalMediaCover)

        val onCartridge = libraryLaunchCardUiState(
            target = target,
            metadata = LibraryDetailMetadata(),
            achievementProgress = null,
            statusLine = null,
            screenScraperConnected = false,
            cartridgeArtUri = "file:///rom-support.png",
            // The caller says the resolved face is physical media: the default is now Home's (a face is not physical until it is said to be).
            cartridgeArtIsPhysical = true,
        )
        assertEquals("file:///rom-support.png", onCartridge.cartridgeArtUri)
        assertTrue(onCartridge.physicalMediaCover)
        assertEquals(
            "the cover is retained, not overwritten -- switching the setting off must not lose it",
            "file:///art/cover.png",
            onCartridge.coverArtUri,
        )
    }
}
