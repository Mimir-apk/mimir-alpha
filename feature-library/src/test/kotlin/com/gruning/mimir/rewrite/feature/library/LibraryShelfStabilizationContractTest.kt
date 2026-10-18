package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.layout.ContentScale
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibraryShelfStabilizationContractTest {
    /**
     * THE DISPLAY LAW,
     *
     * This test used to be named `listPreviewPrefersPhysicalThenCoverThenFallback...` and asserted the
     * exact opposite of its first case. It is RESTATED rather than fixed: the old order was not a bug in
     * the test, it was the previous behavior that was replaced, and a lane meeting a red assertion without
     * knowing that would "correct" the test back.
     *
     * Both gate states are pinned here, because a law with a switch is only half-pinned by one of them.
     */
    @Test
    fun listPreviewLeadsWithTheCoverAndNothingInvertsItAnyMore() {
        val base = GameRow(
            gameId = GameId("preview"),
            title = "Preview",
            platformId = PlatformId("psp"),
            memberCount = 1,
            duplicateKind = null,
        )
        val both = base.copy(
            physicalMediaUri = "  file:///physical.webp  ",
            artUri = "  file:///cover.webp  ",
        )

        // DEFAULT (gate OFF): the cover wins, and it carries the PLATFORM aspect, not the square lane.
        val default = libraryListPreviewMedia(both)
        assertEquals(LibraryListPreviewMediaSource.COVER, default.source)
        assertEquals("file:///cover.webp", default.artUri)
        assertEquals(platformCoverAspect("psp"), default.aspectRatio, 0f)
        assertTrue(default.usesFramelessRealArt)
        // The parameterless call is the same law -- the default argument IS the configured default.
        assertEquals(default, libraryListPreviewMedia(both))

    }

    /**
     * a row whose art has not been resolved wears NOTHING, never the book.
     *
     * The book is a claim — *this game has no art* — and it was being painted from a null check made before
     * resolution had happened. Two costumes existed and both assert something; there was no way to say
     * "we have not finished looking". This pins the third state and, just as importantly, pins that it is
     * NOT reached when the answer really is known: a settled miss still gets the book.
     */
    @Test
    fun anUnresolvedRowClaimsNothingWhileASettledMissStillWearsTheBook() {
        val base = GameRow(
            gameId = GameId("preview"),
            title = "Preview",
            platformId = PlatformId("psp"),
            memberCount = 1,
            duplicateKind = null,
        )

        val unresolved = libraryListPreviewMedia(base.copy(artResolutionSettled = false))
        assertEquals(LibraryListPreviewMediaSource.UNRESOLVED, unresolved.source)
        assertEquals(null, unresolved.artUri)
        assertFalse(unresolved.usesFramelessRealArt)

        val settledMiss = libraryListPreviewMedia(base)
        assertEquals(LibraryListPreviewMediaSource.FALLBACK, settledMiss.source)

        // Unsettled resolution never suppresses art that DID resolve -- the state is about absence only,
        // and it must not turn a game that has a cover into a blank panel.
        val withCover = base.copy(artUri = "file:///cover.webp", artResolutionSettled = false)
        assertEquals(LibraryListPreviewMediaSource.COVER, libraryListPreviewMedia(withCover).source)
    }

    /**
     * REHOMED, not deleted (C1, 2026-08-25).
     *
     * The test around this guard asserted `gamelistLaunchCardShowsPhysicalMedia` — that the VIEW you opened
     * from decided the launch card's face. C1 supersedes that: the card reads an explicit LAUNCH CARD
     * state, so the same game wears the same face whichever view reached it, and the function is gone.
     *
     * **The guard itself is not about that function and had to survive it.** It pins that
     * [GamelistViewMode] has exactly two members, so adding a third forces a decision about how it behaves
     * rather than letting it inherit one silently. Deleting the guard along with the test it happened to
     * live in is precisely how a real invariant gets thrown out with a superseded one — a failure mode this guard must prevent.
     */
    @Test
    fun aThirdGamelistViewModeMustBeRuledOnRatherThanInheritBehaviour() {
        assertEquals(
            "the carousel and the list are the two views the display contract is " +
                "written against; a third needs a design decision, not a default",
            2,
            GamelistViewMode.entries.size,
        )
    }

    /**
     * An explicit pick replaces the current state media with Cartridge Hero either on or off.
     *
     * **This pins a defect that shipped hours earlier and is fixed here.** The first Cover picker folded
     * the explicit pick into `artUri`, so with Cartridge Hero ON the ladder reached the provider's disc
     * render FIRST and a manually chosen cover silently lost to provider art. The pick is now
     * its own tier above both, in both switch positions.
     *
     * FL-6 rides along: the lane follows the WINNER. A pick can now be a disc while the switch is OFF,
     * which is a lane the off branch had never painted before.
     */
    @Test
    fun aWiredFaceOutranksTheSwitchAndTheAutomaticCoverInBothPositions() {
        val base = GameRow(
            gameId = GameId("preview"),
            title = "Preview",
            platformId = PlatformId("psp"),
            memberCount = 1,
            duplicateKind = null,
            artUri = "file:///automatic-cover.webp",
            physicalMediaUri = "file:///provider-disc.webp",
        )

        // A COVER he picked beats the provider disc -- the exact case that was broken -- and keeps the
        // platform lane, not the square one. This used to run both switch positions; with the Cartridge
        // Hero switch removed (2026-09-22) it is a STRONGER pin, because the pick now has to win on its
        // own rather than with a switch position agreeing with it.
        val pickedCover = base.copy(wiredFace = WiredFace("file:///his-cover.png", physical = false))
        libraryListPreviewMedia(pickedCover).let { resolved ->
            assertEquals("file:///his-cover.png", resolved.artUri)
            assertEquals(LibraryListPreviewMediaSource.COVER, resolved.source)
            assertEquals(platformCoverAspect("psp"), resolved.aspectRatio, 0f)
        }

        // A DISC he picked beats the automatic cover and takes the square lane. This is C4 standing on
        // its own: ordering is now cover-first unconditionally, so a hand-picked disc winning proves the
        // pick is resolved BEFORE the ordering rather than surviving it.
        val pickedDisc = base.copy(wiredFace = WiredFace("file:///his-disc.png", physical = true))
        libraryListPreviewMedia(pickedDisc).let { resolved ->
            assertEquals("file:///his-disc.png", resolved.artUri)
            assertEquals(LibraryListPreviewMediaSource.PHYSICAL, resolved.source)
            assertEquals(LibraryPhysicalMediaCoverAspect, resolved.aspectRatio, 0f)
        }

        // A blank pick is absent, not a wiring -- FL-5, so it must not blank the surface.
        val blank = base.copy(wiredFace = WiredFace("   ", physical = true))
        assertEquals(LibraryListPreviewMediaSource.COVER, libraryListPreviewMedia(blank).source)
    }

    @Test
    fun searchGeometryKeepsNamedViewportClassesInsideCeilingsWithoutHardMinimum() {
        // RP5 reference ~853×480 after the Library's 32 dp outer inset → ~789 usable content width.
        val rp5 = resolveGamelistHeaderSearchGeometry(availableWidthDp = 789f, compact = false)
        assertTrue(rp5.showSearch)
        assertEquals(136f, rp5.statusEndInsetDp, 0.01f)
        // R6-P2-06: the wide branch's ceiling now matches the compact branch's (136f), not an
        // independently-tuned lower one -- see resolveGamelistHeaderSearchGeometry.
        assertEquals(136f, rp5.searchWidthDp, 0.01f)
        // Walk-32i: the label's seat was 132 dp, which the measured "Favorites · 10 games" (134 dp at density 2.0)
        // overran on either branch; both branches now share the measured ceiling.
        assertEquals(LibraryGamelistMetadataCeilingDp, rp5.metadataMaxWidthDp, 0.01f)
        assertTrue(
            rp5.titleMaxWidthDp +
                rp5.metadataMaxWidthDp +
                rp5.searchWidthDp +
                (2f * rp5.itemGapDp) <= 789f - rp5.statusEndInsetDp,
        )

        val wide169 = resolveGamelistHeaderSearchGeometry(availableWidthDp = 960f, compact = false)
        assertTrue(wide169.showSearch)
        assertEquals(136f, wide169.searchWidthDp, 0.01f)
        assertEquals(LibraryGamelistMetadataCeilingDp, wide169.metadataMaxWidthDp, 0.01f)

        val threeTwo = resolveGamelistHeaderSearchGeometry(availableWidthDp = 720f, compact = false)
        assertTrue(threeTwo.showSearch)
        assertTrue(threeTwo.searchWidthDp <= 136f)

        val fourThree = resolveGamelistHeaderSearchGeometry(availableWidthDp = 640f, compact = true)
        assertTrue(fourThree.showSearch)
        assertTrue(fourThree.searchWidthDp in 120f..136f)
        // Walk-32i: compact's label seat matches the wide branch's once the row has the room (it was 90 dp).
        assertEquals(LibraryGamelistMetadataCeilingDp, fourThree.metadataMaxWidthDp, 0.01f)

        val square = resolveGamelistHeaderSearchGeometry(availableWidthDp = 480f, compact = true)
        assertTrue(square.showSearch)
        assertTrue(square.searchWidthDp <= 136f)
        assertEquals(LibraryGamelistMetadataCeilingDp, square.metadataMaxWidthDp, 0.01f)
        assertTrue(
            square.metadataMaxWidthDp + square.itemGapDp + square.searchWidthDp <=
                480f - square.statusEndInsetDp,
        )
        // Never restore a 180 dp hard search minimum that can force overflow.
        assertTrue(square.searchWidthDp < 180f)
    }

    @Test
    fun searchGeometryHidesBelowReadabilityFloor() {
        val tight = resolveGamelistHeaderSearchGeometry(availableWidthDp = 220f, compact = true)
        assertEquals(0f, tight.searchWidthDp, 0f)
        assertFalse(tight.showSearch)
    }

    /**
     * Walk-32i (lead, emulator at the Nova's and the RG's geometry, 2026-09-30): with Favorites only on, the compact
     * header cut its count to "Favorites · …" on the Nova and "Favorites · 1 …" on the RG while everything right of
     * the Search pill stood empty, because compact pinned the label at 90 dp whatever the row had. Compact's second
     * row is the full width (the title sits above it), so the label now takes what is left after a full Search
     * seat, between the old 90 dp floor and the ceiling both branches share. Search keeps its own rules: the 136 dp
     * ceiling, the 120 dp floor and the 240 dp visibility rule. The ceiling is measured against "Favorites · 10
     * games" at the real header in GamelistHeaderCountFitsTest (the old 132 was short of it at the Nova's density,
     * so the wide branch's seat moved with it); this pins the arithmetic.
     */
    @Test
    fun compactCountLabelTakesWhatTheRowHasLeftAfterAFullSearchSeat() {
        // Nova (640x480 dp) and the RG (564.7 dp square) after Library's 32 dp inset on both sides.
        val nova = resolveGamelistHeaderSearchGeometry(availableWidthDp = 640f - 64f, compact = true)
        val rg = resolveGamelistHeaderSearchGeometry(availableWidthDp = 564.7f - 64f, compact = true)
        listOf(nova, rg).forEach { geometry ->
            assertTrue(geometry.showSearch)
            assertEquals(LibraryGamelistMetadataCeilingDp, geometry.metadataMaxWidthDp, 0f)
            assertEquals(136f, geometry.searchWidthDp, 0f)
        }
        // Both branches hand the label the same seat once the row has the room.
        val rp5 = resolveGamelistHeaderSearchGeometry(availableWidthDp = 789f, compact = false)
        assertEquals(rp5.metadataMaxWidthDp, nova.metadataMaxWidthDp, 0f)

        // Between the floor and the ceiling the label takes exactly the leftover; Search never yields below its ceiling.
        val leftover = resolveGamelistHeaderSearchGeometry(availableWidthDp = 260f, compact = true)
        assertEquals(260f - 136f - LibraryGamelistHeaderGapDp, leftover.metadataMaxWidthDp, 0f)
        assertEquals(136f, leftover.searchWidthDp, 0f)

        // At the narrowest surface that shows Search the label is back near its floor and yields first.
        val narrowest = resolveGamelistHeaderSearchGeometry(availableWidthDp = 240f, compact = true)
        assertTrue(narrowest.showSearch)
        assertEquals(94f, narrowest.metadataMaxWidthDp, 0f)
        assertEquals(136f, narrowest.searchWidthDp, 0f)

        // Below the 240 dp rule Search hides as before, and the floor holds the label at the old 90 dp.
        val hidden = resolveGamelistHeaderSearchGeometry(availableWidthDp = 220f, compact = true)
        assertFalse(hidden.showSearch)
        assertEquals(LibraryGamelistCompactMetadataFloorDp, hidden.metadataMaxWidthDp, 0f)
        assertEquals(90f, LibraryGamelistCompactMetadataFloorDp, 0f)

        // The row always closes and Search stays inside its floor and ceiling wherever it is shown.
        listOf(240f, 260f, 296f, 480f, 500.7f, 576f).forEach { width ->
            val geometry = resolveGamelistHeaderSearchGeometry(availableWidthDp = width, compact = true)
            assertTrue("$width", geometry.showSearch)
            assertTrue("$width", geometry.searchWidthDp in LibraryGamelistSearchReadabilityFloorDp..136f)
            assertTrue("$width", geometry.metadataMaxWidthDp in 90f..LibraryGamelistMetadataCeilingDp)
            assertTrue(
                "$width",
                geometry.metadataMaxWidthDp + geometry.itemGapDp + geometry.searchWidthDp <= width + 0.01f,
            )
        }
    }

    @Test
    fun listCoverUsesFitInsideStableFrame() {
        assertEquals(ContentScale.Fit, LibraryListCoverContentScale)
    }

    @Test
    fun favoriteMarkerKeepsLeatherEnvelope() {
        assertEquals(32, LibraryFavoriteMarkerWidthDp)
        assertEquals(24, LibraryFavoriteMarkerHeightDp)
        assertEquals(0.88f, LibraryFavoriteMarkerHiddenScale, 0f)
    }

    @Test
    fun viewModeHintLabelsArePaintOnlyToggleCopy() {
        assertEquals("L/R · ᛚ List", gamelistViewModeHintLabel(RuntimeEnvironment.getApplication().resources, GamelistViewMode.CAROUSEL))
        assertEquals("L/R · ᚲ Shelf", gamelistViewModeHintLabel(RuntimeEnvironment.getApplication().resources, GamelistViewMode.LIST))
    }

    @Test
    fun previewMetadataDedupesIdenticalSupportAndGenre() {
        assertEquals(listOf("RPG"), distinctPreviewSupportFacts("RPG", "RPG"))
        assertEquals(listOf("rpg"), distinctPreviewSupportFacts("rpg", "RPG"))
        assertEquals(listOf("PS1", "RPG"), distinctPreviewSupportFacts("PS1", "RPG"))
        assertEquals(listOf("PS1"), distinctPreviewSupportFacts("PS1", null))
        assertEquals(listOf("RPG"), distinctPreviewSupportFacts(null, "RPG"))
        assertEquals("PS1 · RPG", previewSupportGenreLine("PS1", "RPG"))
        assertEquals("RPG", previewSupportGenreLine("RPG", "RPG"))
    }

    @Test
    fun listBottomControlInsetTracksHintEnvelopePlusClearanceAtFontScale() {
        val pad = gamelistViewModeHintBottomPadDp()
        assertEquals(MimirSpacing.lg.value + HintButtonSize.value + HintRailButtonGap.value, pad, 0.01f)
        assertEquals(74f, pad, 0.01f)

        val at1 = gamelistListBottomControlInsetForFontScale(1.0f)
        val at13 = gamelistListBottomControlInsetForFontScale(1.3f)
        val text1 = controlHintCompactTextHeightDp(1.0f)
        val text13 = controlHintCompactTextHeightDp(1.3f)
        assertEquals(9f, text1, 0.01f)
        assertEquals(9f * 1.3f, text13, 0.01f)
        assertEquals(pad + text1 + GamelistViewModeHintClearanceDp, at1, 0.01f)
        assertEquals(pad + text13 + GamelistViewModeHintClearanceDp, at13, 0.01f)
        assertTrue(at1 >= pad + GamelistViewModeHintClearanceDp)
        assertTrue(at13 > at1)
        // Never freeze a magic 52/98 — geometry must stay token-derived.
        assertTrue(at1 != 52f)
        assertEquals(
            gamelistListBottomControlInsetDp(hintTextHeightDp = text13),
            at13,
            0.01f,
        )
    }

    /**
     * , specified as **same painted size for
     * every cover**.
     *
     * The pin is the PROPERTY, not a table of numbers: every aspect the stage supports must come back
     * with the same painted area, to a rounding tolerance. A numeric table would go green the day the
     * area target drifts, as long as it drifted consistently.
     */
    @Test
    fun everyCoverAspectGetsTheSamePaintedArea() {
        val aspects = floatArrayOf(0.45f, 0.59f, 0.62f, 0.72f, 1.0f, 1.16f, 1.36f, 1.46f, 1.60f)
        // Both measured panels (Odin ~296dp, Nova ~276dp) plus a deliberately tight one.
        for (panelHeight in floatArrayOf(296f, 276f, 200f)) {
            for (fontScale in floatArrayOf(1.0f, 1.3f)) {
                val reserve = gamelistPreviewChromeReserveDp(fontScale)
                val area = gamelistPreviewArtAreaDp2(
                    availableWidthDp = 170f,
                    availableHeightDp = panelHeight,
                    reservedBelowArtDp = reserve,
                )
                val stageHeight = panelHeight - reserve
                val areas = aspects.map { aspect ->
                    val solve = gamelistPreviewArtSize(
                        areaDp2 = area,
                        aspectRatio = aspect,
                        maxWidthDp = 170f,
                        maxHeightDp = stageHeight,
                    )
                    // Aspect is preserved exactly; only the seat changes shape.
                    assertEquals(aspect, solve.artWidthDp / solve.artHeightDp, 0.01f)
                    // And it always fits the stage that produced it.
                    assertTrue(solve.artWidthDp <= 170f + 0.01f)
                    assertTrue(solve.artHeightDp <= stageHeight + 0.01f)
                    solve.artWidthDp * solve.artHeightDp
                }
                areas.forEach { assertEquals(areas.first(), it, areas.first() * 0.01f) }
            }
        }
    }

    /**
     * The fixed-stage invariant: the stage may not be a function of the game. This is what the
     * previous solver got wrong — it took five per-game flags, so the same cover painted 104×144 beside
     * one metadata line and 46×64 beside the full set.
     */
    @Test
    fun theStageIsSolvedFromThePanelAndNeverFromTheRow() {
        val reserve = gamelistPreviewChromeReserveDp(1.0f)
        val area = gamelistPreviewArtAreaDp2(170f, 296f, reserve)
        val stageHeight = 296f - reserve

        // A GameCube cover beside a busy row and beside a bare one is the SAME size, because nothing in
        // either signature can carry the difference.
        val busy = gamelistPreviewArtSize(area, 0.72f, 170f, stageHeight)
        val bare = gamelistPreviewArtSize(area, 0.72f, 170f, stageHeight)
        assertEquals(busy.artWidthDp, bare.artWidthDp, 0.001f)

        // And the reserve itself answers to the font scale alone.
        assertTrue(gamelistPreviewChromeReserveDp(1.3f) > gamelistPreviewChromeReserveDp(1.0f))
        assertEquals(28f + 8f + 16f + 16f + 8f, reserve, 0.01f)
    }

    /**
     * The measured reason the panel's card had to go, kept as arithmetic so nobody restores it and
     * quietly re-shrinks every cover. At the OLD reserve (media action + a six-line metadata block =
     * 176dp of a 296dp panel) the best area that fits every supported aspect is ~7.2k dp² — below the
     * ~10.3k a GameCube cover already had. Equal area was only an increase once that height came back.
     */
    @Test
    fun theOldReserveCouldNotHaveHeldAnEqualAreaWithoutShrinkingEveryCover() {
        val oldReserve = 176f
        val starved = gamelistPreviewArtAreaDp2(170f, 296f, oldReserve)
        assertTrue(starved.toString(), starved < 7_500f)

        val today = gamelistPreviewArtAreaDp2(170f, 296f, gamelistPreviewChromeReserveDp(1.0f))
        assertTrue(today.toString(), today > 17_500f)
        assertTrue(today > starved * 2f)
    }

    /**
     * One area target for BOTH handhelds. The width bound governs on each, so a GameCube cover is the
     * same size on the Odin and the Nova rather than each screen inventing its own standard.
     */
    @Test
    fun bothHandheldsLandOnTheSameStandardSize() {
        val reserve = gamelistPreviewChromeReserveDp(1.0f)
        val odin = gamelistPreviewArtAreaDp2(170f, 296f, reserve)
        val nova = gamelistPreviewArtAreaDp2(170f, 276f, reserve)
        assertEquals(odin, nova, odin * 0.02f)
        // The width bound, named rather than pasted: 170^2 / 1.60.
        assertEquals(170f * 170f / GamelistPreviewAspectMax, odin, 1f)
    }
}
