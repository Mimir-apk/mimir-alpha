package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUX-F F3/F5: the Hearth visual + timing constants. Every value here is a deliberate design decision from
 * the recipe; a drift is a visible change and must fail loudly rather than slip through.
 */
class HomeBackdropVisualsTest {
    @Test
    fun focusedFolioMemberProjectsOnlyItsExistingLocalHero() {
        val itemId = HomeShelfItemId.Emulated(GameId("folio-hero"))
        assertEquals(
            "file:///hero.webp",
            homeTomeFolioBackdropArt(
                HomeTomeMember(
                    itemId = itemId,
                    title = "Folio Hero",
                    backdropArtUri = "file:///hero.webp",
                ),
            ),
        )
        assertEquals(
            null,
            homeTomeFolioBackdropArt(
                HomeTomeMember(
                    itemId = itemId,
                    title = "Remote Hero",
                    backdropArtUri = "https://example.invalid/hero.webp",
                ),
            ),
        )
        assertEquals(null, homeTomeFolioBackdropArt(null))
    }

    @Test
    fun synchronizedTomeRotationsDoNotAdvanceTheIdleWakeEpoch() {
        assertEquals(0, homeBackdropWakeContextEpoch(contextEpoch = 11, contextChangesWake = false))
        assertEquals(0, homeBackdropWakeContextEpoch(contextEpoch = 12, contextChangesWake = false))
        assertEquals(12, homeBackdropWakeContextEpoch(contextEpoch = 12, contextChangesWake = true))
        assertTrue(homeTomeBackdropCompositeVisible("file:///hero.webp"))
        assertTrue(!homeTomeBackdropCompositeVisible(null))
    }

    @Test
    fun homeVeilPinsTheSevenLiftedStops() {
        assertArrayEquals(
            floatArrayOf(0.88f, 0.54f, 0.46f, 0.46f, 0.52f, 0.58f, 0.58f),
            HomeVeilStops,
            0f,
        )
        assertEquals("Home veil is a seven-stop vertical profile", 7, HomeVeilStops.size)
        assertEquals(0f, HomeBackdropHeaderEraseAlpha, 0f)
        assertEquals(0.12f, 1f - HomeVeilStops.first(), 0.0001f)
    }

    @Test
    fun metallicThemesKeepTheNearClearMediaVeilWithoutChangingStandardThemes() {
        assertArrayEquals(
            floatArrayOf(0.06f, 0.03f, 0.02f, 0.01f, 0.02f, 0.03f, 0.04f),
            LightHomeVeilStops,
            0f,
        )
        listOf(MimirThemePalette.Folkvangr, MimirThemePalette.Baldr).forEach { palette ->
            assertArrayEquals(LightHomeVeilStops, homeVeilStops(palette), 0f)
            assertEquals(palette.surfaceBase, homeVeilInk(palette))
            assertTrue("metallic veil should be nearly absent through the media field", homeVeilStops(palette)[3] <= 0.01f)
            assertEquals(0f, homeBackdropFooterEraseAlpha(palette), 0f)
        }
        MimirThemePalette.all.filterNot { it.usesNearClearMediaVeil }.forEach { palette ->
            assertArrayEquals(HomeVeilStops, homeVeilStops(palette), 0f)
            assertEquals(palette.tileFillDeep, homeVeilInk(palette))
            assertEquals(1f, homeBackdropFooterEraseAlpha(palette), 0f)
        }
    }

    @Test
    fun metallicHomeMediaUsesEachThemesOwnTenPercentTint() {
        assertEquals(
            MimirThemePalette.Folkvangr.presentation.homeMediaTint,
            homeMediaTintVeilColor(MimirThemePalette.Folkvangr),
        )
        assertEquals(
            MimirThemePalette.Baldr.presentation.homeMediaTint,
            homeMediaTintVeilColor(MimirThemePalette.Baldr),
        )
        assertEquals(0.10f, MimirThemePalette.Folkvangr.presentation.homeMediaTint.alpha, 0.002f)
        assertEquals(0.10f, MimirThemePalette.Baldr.presentation.homeMediaTint.alpha, 0.002f)
        MimirThemePalette.all.filterNot { it.usesNearClearMediaVeil }.forEach { palette ->
            assertEquals(androidx.compose.ui.graphics.Color.Transparent, homeMediaTintVeilColor(palette))
        }
    }

    @Test
    fun libraryRetiresCloudsAndKeepsOnlyARestrainedManiFooterShadow() {
        assertFalse(LibraryShelfAtmosphericClouds)
        assertEquals(0.12f, ManiLibraryFooterShadowAlpha, 0f)
        assertEquals(
            MimirThemePalette.Mani.materials.scene.shadow.copy(alpha = 0.12f),
            libraryShelfFooterShadowColor(MimirThemePalette.Mani),
        )
        MimirThemePalette.all.filterNot { it.id == MimirThemePalette.Mani.id }.forEach { palette ->
            assertEquals(
                androidx.compose.ui.graphics.Color.Transparent,
                libraryShelfFooterShadowColor(palette),
            )
        }
    }

    @Test
    fun releaseThemesDeclineLocalizedHomeTitleRails() {
        assertFalse(homeShelfShowsMediaTitleRail(MimirThemePalette.Folkvangr, mediaBackdropAvailable = true))
        assertFalse(homeShelfShowsMediaTitleRail(MimirThemePalette.Baldr, mediaBackdropAvailable = true))
        assertFalse(homeShelfShowsMediaTitleRail(MimirThemePalette.Baldr, mediaBackdropAvailable = false))
        assertFalse(
            homeShelfShowsMediaTitleRail(
                palette = MimirThemePalette.Baldr,
                mediaBackdropAvailable = false,
                tomeSurfaceActive = true,
            ),
        )
        assertFalse(
            homeShelfShowsMediaTitleRail(
                palette = MimirThemePalette.Default,
                mediaBackdropAvailable = false,
                tomeSurfaceActive = true,
            ),
        )
        assertFalse(homeShelfShowsMediaTitleRail(MimirThemePalette.Default, mediaBackdropAvailable = true))
        assertEquals(0.88f, LightHomeTitleRailAlpha, 0f)
        assertEquals(0.22f, LightHomeTitleRailOutlineAlpha, 0f)
    }

    @Test
    fun lightTomeTitleRailUsesOneBoundedWidthAcrossFocusChanges() {
        assertEquals(144f, HomeShelfTomeTitleRailWidth.value, 0f)
        assertEquals(
            144f,
            homeShelfStableTitleRailWidth(
                titleBandWidth = 466.dp,
                showMediaRail = true,
                tomeSurfaceActive = true,
            )!!.value,
            0f,
        )
        assertEquals(
            98f,
            homeShelfStableTitleRailWidth(
                titleBandWidth = 98.dp,
                showMediaRail = true,
                tomeSurfaceActive = true,
            )!!.value,
            0f,
        )
        assertTrue(
            homeShelfStableTitleRailWidth(
                titleBandWidth = 466.dp,
                showMediaRail = true,
                tomeSurfaceActive = false,
            ) == null,
        )
    }

    @Test
    fun releaseThemesDeclineLocalizedGamelistChrome() {
        assertFalse(gamelistUsesLightMediaContrast(MimirThemePalette.Folkvangr, "file:///hero.jpg"))
        assertFalse(gamelistUsesLightMediaContrast(MimirThemePalette.Baldr, "file:///hero.jpg"))
        assertFalse(gamelistUsesLightMediaContrast(MimirThemePalette.Baldr, null))
        assertFalse(gamelistUsesLightMediaContrast(MimirThemePalette.Baldr, ""))
        assertFalse(gamelistUsesLightMediaContrast(MimirThemePalette.Default, "file:///hero.jpg"))
        assertEquals(0.05f, LightGamelistVeilAlpha, 0f)
    }

    @Test
    fun homeVeilPositionsArePinnedAscendingAndPairWithTheStops() {
        assertArrayEquals(
            floatArrayOf(0.00f, 0.14f, 0.30f, 0.50f, 0.68f, 0.84f, 1.00f),
            HomeVeilPositions,
            0f,
        )
        assertEquals("stops and positions must pair 1:1", HomeVeilStops.size, HomeVeilPositions.size)
        // Gradient stop positions must strictly ascend or the veil renders wrong / throws.
        for (i in 1 until HomeVeilPositions.size) {
            assertTrue("veil positions must strictly ascend", HomeVeilPositions[i] > HomeVeilPositions[i - 1])
        }
        // FUX-02 R6: the toned-down mid alpha (0.46) lands at the vertical middle (position 0.50).
        assertEquals(0.50f, HomeVeilPositions[3], 0f)
        assertEquals(0.46f, HomeVeilStops[3], 0f)
    }

    @Test
    fun homeVeilMiddleIsLiftedAboveTheGamelistProfile() {
        // Hero separation: mid band 0.46 — still ABOVE the gamelist 0.44 (the
        // frameless-cover separation the lift exists for survives the tone-down). Stops 2/3 = flat middle.
        assertEquals(0.46f, HomeVeilStops[2], 0f)
        assertEquals(0.46f, HomeVeilStops[3], 0f)
        assertTrue("Home mid band must stay above the gamelist 0.44", HomeVeilStops[3] > 0.44f)
    }

    @Test
    fun heroPoolFactorsArePinned() {
        assertEquals(0.25f, HomeHeroPoolCenterAlpha, 0f)
        assertEquals(1.4f, HomeHeroPoolRadiusFactor, 0f)
    }

    @Test
    fun heroPoolFollowsTheHeroSlotHorizontallyNotAlwaysTheGeometricCenter() {
        val geo = solveHomeShelfGeometry(1280f, 720f)
        // Ring shelf: the hero lives at slot 0, so the pool sits on the geometric centre.
        val center = homeHeroPoolGeometry(geo, 0)
        assertEquals(geo.centerAnchor, center.centerXDp, 0.001f)
        assertEquals(geo.heroSide * HomeHeroPoolRadiusFactor, center.radiusDp, 0.001f)
        // Fixed-layout shelf: the emphasis travels to a side slot — the pool must FOLLOW it, not stay centred
        // (regression guard for the centerAnchor-hardcode bug).
        assertEquals(geo.centerAnchor + geo.slotStride, homeHeroPoolGeometry(geo, 1).centerXDp, 0.001f)
        assertEquals(geo.centerAnchor - geo.slotStride, homeHeroPoolGeometry(geo, -1).centerXDp, 0.001f)
        assertEquals(
            geo.centerAnchor + geo.slotStride * 0.5f,
            homeHeroPoolGeometry(geo, 0.5f).centerXDp,
            0.001f,
        )
        // Y is the shelf band centre — independent of the slot offset.
        assertEquals(center.centerYDp, homeHeroPoolGeometry(geo, 2).centerYDp, 0.001f)
    }

    @Test
    fun theOnlyNewMotionDurationIsThe400msIdleFade() {
        assertEquals(400, HomeBackdropIdleFadeMs)
    }

    @Test
    fun homeSettleIsTheHearthChairPickAndLongerThanTheGamelist() {
        assertEquals(450L, HomeBackdropSettleMs)
        // The Hearth dwell must exceed the gamelist's settle so a browsed Home never spins a decoder per row.
        assertTrue("Home dwell must exceed the gamelist settle", HomeBackdropSettleMs > BifrostMediaSettleMs)
    }

    @Test
    fun idleTimeoutIsFortyFiveSeconds() {
        assertEquals(45_000L, HomeBackdropIdleTimeoutMs)
    }

    // ── RC-38: the hero decode envelope, solved from the window (spec §2/§5B, policy OWED-3) ────
    //
    // REPLACED PIN, not a widened tolerance. The retired cell `chosenHeroDecodesForTheFullHdFillEnvelope`
    // asserted `1920 == MimirHomeStaticHeroDecodeWidthPx` — i.e. it asserted the LAW that the envelope IS
    // the Odin's panel. That law is what the viewport-derived envelope replaces, so the assertion naming it is retired with it and
    // `theRetiredFullHdLiteralIsNoLongerTheLawOnTheReferencePanel` below now asserts its NEGATION.
    //
    // Every `expected` value here is a hand-derived literal written independently of the production
    // arithmetic it is compared against. The window px and density come from `MimirCertifiedViewports`, so
    // a drift in the fixture's panel facts fails here rather than being silently absorbed.

    /** A certified panel with real pixels on file, plus the envelope §5B derives for it by hand. */
    private data class HeroDecodeRow(
        val name: String,
        val windowWidthPx: Int,
        val windowHeightPx: Int,
        val density: Float,
        val expectedWidthPx: Int,
        val expectedHeightPx: Int,
    )

    private fun certifiedRow(
        viewport: MimirCertifiedViewport,
        expectedWidthPx: Int,
        expectedHeightPx: Int,
    ): HeroDecodeRow {
        val px = requireNotNull(viewport.resolutionPx) { "${viewport.name} has no panel px on file" }
        val dpi = requireNotNull(viewport.declaredDensityDpi) { "${viewport.name} has no density on file" }
        return HeroDecodeRow(
            name = viewport.name,
            windowWidthPx = px.first,
            windowHeightPx = px.second,
            density = dpi / 160f,
            expectedWidthPx = expectedWidthPx,
            expectedHeightPx = expectedHeightPx,
        )
    }

    private fun heroDecodeRows(): List<HeroDecodeRow> = listOf(
        // Odin — 1920x1080 @369dpi. The panel the retired literal WAS; it still moves, because the focal
        // overscale the renderer applies now rides into the decode.
        certifiedRow(MimirCertifiedViewports.Odin, expectedWidthPx = 2032, expectedHeightPx = 1152),
        // Retroid Pocket Nova — 1280x960 @320dpi. The 4:3 panel that is neither the Odin nor 16:9.
        certifiedRow(
            MimirCertifiedViewports.RetroidPocketNova,
            expectedWidthPx = 1376,
            expectedHeightPx = 1024,
        ),
        // Untested 1440p-class 16:9 handheld — the by-construction failure the policy predicts:
        // the retired law handed it a x1.40 UPSCALED hero. Not a device on file, so it is stated as a
        // synthetic window rather than pulled from the fixture.
        HeroDecodeRow(
            name = "untested 1440p 16:9 (2560x1440 @480dpi)",
            windowWidthPx = 2560,
            windowHeightPx = 1440,
            density = 3f,
            expectedWidthPx = 2704,
            expectedHeightPx = 1520,
        ),
    )

    @After
    fun clearObservedHeroDecodeEnvelope() {
        MimirHeroDecode.resetObservedForTest()
    }

    @Test
    fun heroDecodeEnvelopeSolvesFromTheWindowAtEveryPanelWithPixelsOnFile() {
        heroDecodeRows().forEach { row ->
            val solved = mimirHeroDecodeEnvelopePx(row.windowWidthPx, row.windowHeightPx, row.density)
            assertEquals(
                "${row.name} decode width must be the window solved by the focal overscale, ceil-16",
                row.expectedWidthPx,
                solved.widthPx,
            )
            assertEquals(
                "${row.name} decode height must be the window solved by the focal overscale, ceil-16",
                row.expectedHeightPx,
                solved.heightPx,
            )
        }
    }

    @Test
    fun theRetiredFullHdLiteralIsNoLongerTheLawOnTheReferencePanel() {
        // The direct refutation of the retired pin: on the Odin's own panel the solved envelope must NOT be
        // the full-HD literal that used to be hardcoded there. If this ever passes again, the device-cap
        // literal has come back.
        val odin = heroDecodeRows().first()
        val solved = mimirHeroDecodeEnvelopePx(odin.windowWidthPx, odin.windowHeightPx, odin.density)
        assertFalse(
            "the Odin's envelope must no longer BE the retired 1920x1080 literal",
            solved == MimirFullHdHeroDecodeEnvelope,
        )
        // ...and the 1440p panel the retired law upscaled by x1.40 must now be supplied exactly.
        val fourteenForty = heroDecodeRows().last()
        val solved1440 = mimirHeroDecodeEnvelopePx(
            fourteenForty.windowWidthPx,
            fourteenForty.windowHeightPx,
            fourteenForty.density,
        )
        assertTrue(
            "a 1440p window must no longer be undersupplied by a 1080-class envelope",
            solved1440.widthPx > MimirFullHdHeroDecodeEnvelope.widthPx &&
                solved1440.heightPx > MimirFullHdHeroDecodeEnvelope.heightPx,
        )
    }

    @Test
    fun heroDecodeEnvelopeNeverUndersuppliesTheWindowOrDropsTheFocalOverscale() {
        MimirCertifiedViewports.all.forEach { viewport ->
            val px = viewport.resolutionPx ?: return@forEach
            val dpi = viewport.declaredDensityDpi ?: return@forEach
            val density = dpi / 160f
            val solved = mimirHeroDecodeEnvelopePx(px.first, px.second, density)
            // Undersupply is the failure the solve exists to make impossible: the envelope can never be
            // smaller than the window the media has to cover.
            assertTrue(
                "${viewport.name} must never decode below its own window width",
                solved.widthPx >= px.first,
            )
            assertTrue(
                "${viewport.name} must never decode below its own window height",
                solved.heightPx >= px.second,
            )
            // Per-row inequality rather than a global `s <= bound`: the 320x300 floor sits at exactly
            // s = 1.100, and a global bound would put a certified row on a knife edge.
            val overscale = homeBackdropFocalTransform(
                solveHomeShelfGeometry(px.first / density, px.second / density),
            ).scale
            assertTrue(
                "${viewport.name} must decode what the focal transform will actually display",
                solved.widthPx >= kotlin.math.ceil(px.first * overscale).toInt() &&
                    solved.heightPx >= kotlin.math.ceil(px.second * overscale).toInt(),
            )
        }
    }

    @Test
    fun heroDecodeEnvelopeQuantizesToSixteenPixelsOnBothAxes() {
        assertEquals(16, MimirHeroDecodeQuantumPx)
        // Hand-checked boundaries: an exact multiple stays put, one pixel past it steps a whole quantum.
        assertEquals(2016, mimirHeroDecodeCeilToQuantum(2016.0))
        assertEquals(2032, mimirHeroDecodeCeilToQuantum(2016.0001))
        assertEquals(16, mimirHeroDecodeCeilToQuantum(0.5))
        heroDecodeRows().forEach { row ->
            val solved = mimirHeroDecodeEnvelopePx(row.windowWidthPx, row.windowHeightPx, row.density)
            assertEquals("${row.name} width quantum", 0, solved.widthPx % MimirHeroDecodeQuantumPx)
            assertEquals("${row.name} height quantum", 0, solved.heightPx % MimirHeroDecodeQuantumPx)
        }
    }

    @Test
    fun everyHeroRequestSiteSharesOneCacheIdentityCarryingTheSolvedEnvelope() {
        val odin = heroDecodeRows().first()
        val observed = MimirHeroDecode.observe(odin.windowWidthPx, odin.windowHeightPx, odin.density)
        assertEquals(2032, observed.widthPx)
        assertEquals(1152, observed.heightPx)

        // The four sites this lane owns read the envelope directly; the three it does not (the Tome face
        // probe, the Folio neighbour prewarm, the cold-start warm pass) reach the same value through these
        // symbols. If they ever stop agreeing, every prewarm becomes a miss.
        assertEquals(observed.widthPx, MimirHomeStaticHeroDecodeWidthPx)
        assertEquals(observed.heightPx, MimirHomeStaticHeroDecodeHeightPx)
        assertEquals(
            "home-static-hero:file:///hero.png#2032x1152-fill-v3",
            mimirHomeStaticHeroCacheKey("file:///hero.png"),
        )
        assertEquals(
            mimirHeroCacheKey("file:///hero.png", observed),
            mimirHomeStaticHeroCacheKey("file:///hero.png"),
        )
        // v2 named the retired full-HD identity; reusing it would serve 1080-class bitmaps under the new law.
        assertFalse(mimirHomeStaticHeroCacheKey("file:///hero.png").contains("-fill-v2"))
        assertFalse(mimirHomeStaticHeroCacheKey("file:///hero.png").contains("1920x1080"))
    }

    @Test
    fun observingAWindowMemoizesItAndANewWindowReplacesTheIdentity() {
        val first = MimirHeroDecode.observe(1920, 1080, 2.30625f)
        assertSame(first, MimirHeroDecode.observe(1920, 1080, 2.30625f))
        val nova = MimirHeroDecode.observe(1280, 960, 2f)
        assertEquals(1376, nova.widthPx)
        assertEquals(1024, nova.heightPx)
        assertEquals(nova.widthPx, MimirHomeStaticHeroDecodeWidthPx)
    }

    @Test
    fun heroDecodeEnvelopeStaysInsideTheSolversOwnFloorInsteadOfThrowing() {
        // The floor constants must be the solver's OWN declared minimum, not a second opinion about it.
        solveHomeShelfGeometry(MimirHeroDecodeSolverFloorWidthDp, MimirHeroDecodeSolverFloorHeightDp)
        listOf(
            MimirHeroDecodeSolverFloorWidthDp - 1f to MimirHeroDecodeSolverFloorHeightDp,
            MimirHeroDecodeSolverFloorWidthDp to MimirHeroDecodeSolverFloorHeightDp - 1f,
        ).forEach { (width, height) ->
            var threw = false
            try {
                solveHomeShelfGeometry(width, height)
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue("solveHomeShelfGeometry must refuse ${width}x$height", threw)
        }
        // A window below the shelf floor must still yield a usable envelope — a decode path may not throw.
        val tiny = mimirHeroDecodeEnvelopePx(240, 200, 2f)
        assertTrue("a sub-floor window still covers itself", tiny.widthPx >= 240 && tiny.heightPx >= 200)
        // Degenerate metrics fall back deterministically rather than dividing by zero.
        assertEquals(MimirFullHdHeroDecodeEnvelope, mimirHeroDecodeEnvelopePx(0, 1080, 2f))
        assertEquals(MimirFullHdHeroDecodeEnvelope, mimirHeroDecodeEnvelopePx(1920, 1080, 0f))
    }

    // ── RC-38 routed defect (spec §6.1): one veil dialect per surface ─────────────────────────────────

    @Test
    fun theGamelistMotionPathWearsTheGamelistVeilNotHomes() {
        MimirThemePalette.all.filter { it.usesNearClearMediaVeil }.forEach { palette ->
            // Metallic themes are the only ones that ever forked: the still path took the uniform 5% wash
            // while the clip path took Home's shaped light profile, so a game with a clip changed veil
            // profile under otherwise identical art.
            assertEquals(
                "${palette.id} motion default must be the gamelist wash",
                libraryGamelistVeil(palette),
                bifrostDefaultVeil(palette),
            )
            assertNotEquals(
                "${palette.id} gamelist wash must not be Home's shaped profile",
                homeVeil(palette),
                bifrostDefaultVeil(palette),
            )
        }
        MimirThemePalette.all.filterNot { it.usesNearClearMediaVeil }.forEach { palette ->
            // Every other theme is bit-identical before and after: the gamelist dialect IS homeVeil there.
            assertEquals(
                "${palette.id} must be bit-identical to the previous default",
                homeVeil(palette),
                bifrostDefaultVeil(palette),
            )
        }
    }

    // ── focal-safe media presentation ────────────────────────────────

    @Test
    fun focalTransformCentersTheMediaOnTheRevealSafeStage() {
        listOf(700f to 350f, 833f to 444f, 538f to 444f).forEach { (width, height) ->
            val geometry = solveHomeShelfGeometry(width, height)
            val transform = homeBackdropFocalTransform(geometry)
            val safeTop = geometry.titleBand.bottom + HomeBackdropHeaderRevealEndInsetDp
            val safeBottom = geometry.hintToastBand.top
            val stageCenter = (safeTop + safeBottom) / 2f

            // The transformed media centre must land exactly on the reveal-safe stage centre.
            assertEquals(
                "$width x $height media centre must sit on the stage centre",
                stageCenter,
                height / 2f + transform.translationYDp,
                0.001f,
            )
        }
    }

    @Test
    fun focalTransformKeepsFullViewportCoverageWithoutDistortion() {
        listOf(700f to 350f, 833f to 444f, 538f to 444f, 1080f to 1080f).forEach { (width, height) ->
            val geometry = solveHomeShelfGeometry(width, height)
            val transform = homeBackdropFocalTransform(geometry)

            // Uniform scale only (one factor, both axes) — aspect ratio is preserved by construction.
            assertTrue("$width x $height never downscales", transform.scale >= 1f)
            // After scaling about the centre and shifting, the media must still cover the viewport:
            // no exposed edge can appear under the reveal mask's dissolve ramps.
            val scaledHalf = height * transform.scale / 2f
            val center = height / 2f + transform.translationYDp
            assertTrue("$width x $height top edge stays covered", center - scaledHalf <= 0.001f)
            assertTrue("$width x $height bottom edge stays covered", center + scaledHalf >= height - 0.001f)
        }
    }

    @Test
    fun focalTransformSharesTheRevealMaskConstantsAndPinsRp5() {
        // The stage derives from the SAME inset the mask dissolves through — a drift in either
        // constant moves both together or fails here.
        assertEquals(12f, HomeBackdropHeaderRevealHoldInsetDp, 0f)
        assertEquals(22f, HomeBackdropHeaderRevealEndInsetDp, 0f)

        // RP5 700x350 exact pin: title bottom 73.2dp -> safe top 95.2dp; hint lane top is the safe
        // bottom. The stage centre sits BELOW the viewport centre, so the media shifts down and
        // overscales by exactly 2*shift/height.
        val geometry = solveHomeShelfGeometry(700f, 350f)
        val transform = homeBackdropFocalTransform(geometry)
        val safeTop = geometry.titleBand.bottom + HomeBackdropHeaderRevealEndInsetDp
        val expectedShift = (safeTop + geometry.hintToastBand.top) / 2f - 175f
        assertEquals(expectedShift, transform.translationYDp, 0.001f)
        assertEquals(1f + 2f * kotlin.math.abs(expectedShift) / 350f, transform.scale, 0.0001f)
        assertTrue("RP5 stage is real and non-degenerate", geometry.hintToastBand.top > safeTop)
    }

    @Test
    fun focalTransformDegeneratesToIdentitySafely() {
        assertEquals(HomeBackdropFocalTransform(1f, 0f), HomeBackdropFocalTransform.Identity)
    }

    /** pad14: the two-palette list above is the metallic subset on purpose; every one of the nine shipping palettes takes exactly one of the two veils. */
    @Test
    fun allNineShippingPalettesTakeExactlyOneOfTheTwoVeils() {
        assertEquals(9, MimirThemePalette.all.size)
        MimirThemePalette.all.forEach { palette ->
            val expected = if (palette.usesNearClearMediaVeil) LightHomeVeilStops else HomeVeilStops
            assertArrayEquals("${palette.id}", expected, homeVeilStops(palette), 0f)
        }
    }
}
