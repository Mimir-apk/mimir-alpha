package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLaunchCardTest {
    @Test
    fun scrollableDescriptionSelectionUsesASquareOuterRing() {
        // The ring migration (2026-09-28) draws it with mimirSelectionRing on a Card corner of 0 dp: still square.
        assertEquals(0f, HomeLaunchDescriptionSelectionCorner.value, 0f)
    }

    @Test
    fun descriptionViewportKeepsAuthoredLinesClearOfTheSelectionRing() {
        assertEquals(4f, HomeLaunchDescriptionContentInset.value, 0f)
        assertEquals(19f, HomeLaunchDescriptionLineHeight.value, 0f)
        assertEquals(65f, homeLaunchDescriptionViewportHeight(3, 19.dp).value, 0f)
        assertEquals(80f, homeLaunchDescriptionViewportHeight(3, 24.dp).value, 0f)
        assertEquals(8f, homeLaunchDescriptionViewportHeight(0, 24.dp).value, 0f)
    }

    @Test
    fun descriptionPagingSettlesOnWholeTextLines() {
        assertEquals(399, homeLaunchDescriptionAlignedMaxOffset(400, 57))
        assertEquals(456, homeLaunchDescriptionAlignedMaxOffset(457, 57))
        assertEquals(
            114,
            homeLaunchDescriptionPageTarget(
                currentOffset = 0,
                direction = 1,
                viewportHeightPx = 195,
                maxOffset = 500,
                lineHeightPx = 57,
            ),
        )
        assertEquals(
            0,
            homeLaunchDescriptionPageTarget(114, -1, 195, 500, 57),
        )
        assertEquals(
            399,
            homeLaunchDescriptionPageTarget(342, 1, 195, 400, 57),
        )
    }

    @Test
    fun launchActionsRetainRoundedGlassWhileDescriptionCopyFloatsFree() {
        val source = homeLaunchCardSource()
        val description = source
            .substringAfter("private fun HomeLaunchCardDescription(")
            .substringBefore("@Composable\nprivate fun HomeLaunchFactRow")
        val action = source.substringAfter("private fun HomeLaunchActionButton(")

        assertTrue(description.contains(".homeLaunchDescriptionSelectionRing(focused && scrollable, identity = focusKey)"))
        assertTrue(description.contains(".fillMaxWidth()"))
        assertFalse(description.contains(".fillMaxSize()"))
        assertFalse(description.contains(".clip(shape)"))
        assertFalse(description.contains("width = MimirSpacing.hairlineWidth"))
        assertFalse(description.contains("palette.accent.copy(alpha = if (focused && scrollable)"))
        assertFalse(description.contains("Modifier.mimirGlassRow(true, shape)"))
        // Ring migration (2026-09-28): the button wears the one selection ring, and its disabled layer sits BELOW the
        // ring and modulates -- an offscreen layer above the ring cut it on every disabled button.
        assertTrue(action.contains(".mimirSelectionRing("))
        assertFalse(action.contains(".mimirGlassRow("))
        val ring = action.indexOf(".mimirSelectionRing(")
        val dim = action.indexOf("alpha = if (enabled) 1f else 0.55f")
        val clip = action.indexOf(".clip(shape)")
        assertTrue(ring in 0 until dim && dim < clip)
        assertTrue(action.contains("compositingStrategy = CompositingStrategy.ModulateAlpha"))
        assertFalse(action.contains(".homeLaunchDescriptionSelectionRing("))
    }

    /**
     * Provider-specific copy wiring: both production
     * `HomeLaunchCardUiState` constructors (Home's `HomeLaunchCardLiveContent` and the Library detail
     * card's `libraryLaunchCardUiState`) are pinned to correctly populate `steamGridDbKeyPresent` --
     * `LibraryDetailTargetTest`'s "gives a keyed pc_games row the Enrich sentence" pin proves that value
     * survives into a call to `homeShelfDescriptionText`, but not that THIS render actually makes that
     * call with it: deleting `steamGridDbKeyPresent = state.steamGridDbKeyPresent` below would leave every
     * other pin green while silently reintroducing the "add a key you have" defect on both surfaces. Pins
     * the one production line that closes that gap.
     */
    @Test
    fun `HomeLaunchCardMetadata threads steamGridDbKeyPresent into homeShelfDescriptionText`() {
        val metadata = homeLaunchCardSource()
            .substringAfter("private fun HomeLaunchCardMetadata(")
            .substringBefore("@Composable\nprivate fun HomeLaunchCardDescription")

        assertTrue(metadata.contains("homeShelfDescriptionText("))
        val call = metadata
            .substringAfter("homeShelfDescriptionText(")
            .substringBefore("),")
        assertTrue(call.contains("steamGridDbKeyPresent = state.steamGridDbKeyPresent"))
        assertTrue(call.contains("isPcGames = state.row.platformId == PlatformId.PC_GAMES"))
    }

    /**
     * The fading "Rename" hint () must reuse the existing hint vocabulary and motion tokens rather than invent new ones,
     * and must be wired to the SAME focus condition as the glyph it explains.
     */
    @Test
    fun titleEditHintReusesTheExistingHintMechanismAndMotionTokens() {
        val source = homeLaunchCardSource()
        val hint = source
            .substringAfter("private fun HomeLaunchCardTitleEditHint(")
            .substringBefore("@Composable\nprivate fun HomeLaunchCardMetadata")

        assertTrue(hint.contains("MimirControlHintText("))
        assertTrue(hint.contains("MimirCopy.RenameGame"))
        assertTrue(hint.contains("MimirMotion.OverlayMs"))
        assertTrue(hint.contains("MimirMotion.Easing"))
        assertFalse(hint.contains("MimirHintToastRail"))

        val header = source
            .substringAfter("private fun HomeLaunchCardHeader(")
            .substringBefore("@Composable\nprivate fun HomeLaunchCardTitleEditGlyph")
        assertTrue(
            header.contains("HomeLaunchCardTitleEditHint(focused = focusedKey == focusContract.titleEditKey)"),
        )
    }

    @Test
    fun descriptionScrollStateRetainsLegacyPositionalConstructorOrder() {
        val state = HomeLaunchDescriptionScrollState(true, true, false)

        assertTrue(state.scrollable)
        assertTrue(state.canScrollUp)
        assertFalse(state.canScrollDown)
        assertFalse(state.measured)
    }

    @Test
    fun launchCardActionButtonsCarryNoArrivalFlare() {
        // Onboarding pills keep their arrival flare. Launch-card buttons use the shared ring without it.
        val button = homeLaunchCardSource()
            .substringAfter("private fun HomeLaunchActionButton(")
            .substringBefore("\n@Composable")
        assertTrue(button.contains(".mimirSelectionRing("))
        assertTrue(button.contains("flare = false,"))
        assertFalse(button.contains("flare = true"))
    }

    /**
     * Update (no filled blob; sweep 10-09: PLAY was tinted at rest and when focused): every action on the card is an etched chip, PLAY the PRIMARY one. The ring is a pill on the
     * plate itself, before the disabled-dim layer and the surface; the surface hands its rim to the ring while focused; no button paints a tint of its own.
     */
    @Test
    fun launchCardActionsAreEtchedChipsAndPlayIsThePrimaryOne() {
        val button = homeLaunchCardSource()
            .substringAfter("private fun HomeLaunchActionButton(")
            .substringBefore("\n@Composable")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        assertTrue(button.contains("shape = MimirRingShape.Pill,"))
        assertFalse("the card's old rounded-rect ring is gone", button.contains("MimirRingShape.Row("))
        assertTrue(button.contains(".mimirEtchedChipSurface(palette, warmed = false, focused = focused, primary = primary, edgeFlush = true)"))
        assertTrue(button.contains("color = palette.etchedChipInk(primary = primary)"))
        val ring = button.indexOf(".mimirSelectionRing(")
        val dim = button.indexOf("alpha = if (enabled) 1f else 0.55f")
        val surface = button.indexOf(".mimirEtchedChipSurface(")
        assertTrue("ring, then the disabled dim, then the surface", ring in 0 until dim && dim < surface)
        assertFalse("no tint of the button's own, no hairline under the ring", button.contains(".background(") || button.contains("restingOutline") || button.contains("palette.accent.copy"))
        assertTrue("a capsule, like every other chip", button.contains("RoundedCornerShape(percent = 50)"))
        // PLAY is the one primary on the card: the others never pass it.
        val footer = homeLaunchCardSource().substringAfter("private fun HomeLaunchCardFooter(").substringBefore("@Composable\nprivate fun HomeLaunchCardHeader(")
        val calls = footer.split("HomeLaunchActionButton(").drop(1)
        assertTrue(calls.isNotEmpty())
        calls.forEach { call ->
            val primary = call.substringBefore("modifier = ").contains("primary = true")
            assertEquals("a call is primary exactly when it is the PLAY button: ${call.take(60)}", call.trimStart().startsWith("state.primaryActionLabel"), primary)
        }
    }

    private fun homeLaunchCardSource(): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeLaunchCard.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeLaunchCard.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeLaunchCard.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun exactDescriptionRestoreRejectsPremeasureAndStaleOwnersThenClamps() {
        val command = HomeLaunchDescriptionScrollCommand(
            sequence = 4,
            restoreOffset = 240,
            expectedRevision = 17L,
            ownerToken = 9L,
        )

        assertEquals(null, homeLaunchDescriptionRestoreTarget(command, Int.MAX_VALUE, 17L, 9L))
        assertEquals(null, homeLaunchDescriptionRestoreTarget(command, 200, 17L, 8L))
        assertEquals(200, homeLaunchDescriptionRestoreTarget(command, 200, 17L, 9L))
        assertEquals(0, homeLaunchDescriptionRestoreTarget(command, 200, 18L, 9L))
    }

    @Test
    fun descriptionMeasurementRequiresALaidOutViewportAndFiniteRange() {
        assertFalse(homeLaunchDescriptionMeasured(0, 120))
        assertFalse(homeLaunchDescriptionMeasured(80, Int.MAX_VALUE))
        assertFalse(homeLaunchDescriptionMeasured(80, -1))
        assertTrue(homeLaunchDescriptionMeasured(80, 0))
    }

    @Test
    fun descriptionFocusRestoreWaitsForMatchingOwnerMeasurementAndSettlement() {
        val command = HomeLaunchDescriptionScrollCommand(
            sequence = 3,
            restoreOffset = 24,
            expectedRevision = 7L,
            ownerToken = 11L,
        )
        val ready = HomeLaunchDescriptionScrollState(
            scrollable = true,
            measured = true,
            ownerToken = 11L,
            settledSequence = 3,
        )

        assertTrue(ready.isSettledFor(command))
        assertFalse(ready.copy(measured = false).isSettledFor(command))
        assertFalse(ready.copy(ownerToken = 10L).isSettledFor(command))
        assertFalse(ready.copy(settledSequence = 2).isSettledFor(command))
    }

    @Test
    fun independentOwnerRejectsStaleCommandsAndRevisionChangeDoesNotReplaySettledDirection() {
        val settledDirection = HomeLaunchDescriptionScrollCommand(
            sequence = 6,
            direction = 1,
            ownerToken = 21L,
        )

        assertFalse(homeLaunchDescriptionCommandPending(settledDirection, 6, 21L))
        assertFalse(homeLaunchDescriptionCommandPending(settledDirection, 5, 22L))
        assertTrue(homeLaunchDescriptionCommandPending(settledDirection, 5, 21L))
        assertEquals(0, homeLaunchDescriptionRevisionResetTarget(100L, 101L, 300))
        assertEquals(null, homeLaunchDescriptionRevisionResetTarget(101L, 101L, 300))
        assertEquals(null, homeLaunchDescriptionRevisionResetTarget(100L, 101L, Int.MAX_VALUE))
        assertEquals(0, homeLaunchDescriptionPublishedOffset(100L, 101L, 240))
        assertEquals(240, homeLaunchDescriptionPublishedOffset(101L, 101L, 240))
    }

    @Test
    fun descriptionScrollCommandRejectsAmbiguousPageAndRestore() {
        var rejected = false
        try {
            HomeLaunchDescriptionScrollCommand(direction = 1, restoreOffset = 12)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun primaryActionCopyUsesPlayOnlyForEmulatedGames() {
        fun state(itemId: HomeShelfItemId) = HomeLaunchCardUiState(
            row = HomeShelfRow(
                itemId = itemId,
                rank = 0L,
                addedAtMillis = 0L,
                title = "Title",
            ),
            platformLabel = "Platform",
        )

        assertEquals("Play", state(HomeShelfItemId.Emulated(GameId("game"))).primaryActionLabel)
        assertEquals(
            "Play",
            state(HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game"))).primaryActionLabel,
        )
        assertEquals(
            "Open",
            state(HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app"))).primaryActionLabel,
        )
    }

    @Test
    fun platformEyebrowPreservesResolvedPlatformAndAndroidCasing() {
        fun state(itemId: HomeShelfItemId, platformId: String, label: String) = HomeLaunchCardUiState(
            row = HomeShelfRow(
                itemId = itemId,
                rank = 0L,
                addedAtMillis = 0L,
                title = "Title",
                supportingLabel = platformId,
            ),
            platformLabel = label,
        )

        assertEquals(
            "Super Nintendo",
            state(HomeShelfItemId.Emulated(GameId("game")), "snes", "Super Nintendo").platformEyebrowLabel(),
        )
        assertEquals(
            "Android game",
            state(
                HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")),
                "Android game",
                "Android game",
            ).platformEyebrowLabel(),
        )
    }

    /**
     * RC-3 continued (2026-08-21) — `coverColumnWidth`/`coverHeight` moved off the
     * `homeLaunchCardContentBands` per-arm literals this test used to pin (288/300, 250/220, 156/176) onto
     * the continuous, height-anchored law `homeLaunchCardCoverHeight`/`homeLaunchCardCoverColumnWidth` now
     * implements (see that function's KDoc in `HomeLaunchCard.kt` for the axis argument). Recomputed from a
     * real run at each width's own height, not carried over from the old band literals — `descriptionMaxLines`
     * and `compact` are untouched (still width-banded, OWED, out of this pass's scope).
     *
     * Regression 1  —
     * `coverColumnWidth` gained a second, WIDTH-anchored term (`minOf`, see
     * `LaunchCardCoverColumnWidthCapRatio`'s KDoc in `HomeLaunchCard.kt`). `full` (960x480, aspect 2.0) stays
     * on the height term unchanged (2.0 > the Odin's own 1.7778); `medium` (720x432, aspect 1.667) and
     * `narrow` (560x390, aspect 1.436) both now cross under 1.7778 and move to the WIDTH term — their
     * `coverColumnWidth` literals below are recomputed from a real run, not the old height-only law.
     */
    @Test
    fun targetLandscapeBreakpointsKeepMetadataLeftAndCoverRightWithoutOvergrowth() {
        val full = homeLaunchCardLayout(960.dp, 480.dp)
        assertEquals(295.1954f, full.coverColumnWidth.value, 0.001f)
        assertEquals(307.4952f, full.coverHeight.value, 0.001f)
        assertEquals(6, full.descriptionMaxLines)
        assertFalse(full.compact)

        val medium = homeLaunchCardLayout(720.dp, 432.dp)
        assertEquals(249.0811f, medium.coverColumnWidth.value, 0.001f)
        assertEquals(276.7457f, medium.coverHeight.value, 0.001f)
        assertEquals(4, medium.descriptionMaxLines)

        val narrow = homeLaunchCardLayout(560.dp, 390.dp)
        assertEquals(193.7297f, narrow.coverColumnWidth.value, 0.001f)
        assertEquals(249.8398f, narrow.coverHeight.value, 0.001f)
        assertEquals(4, narrow.descriptionMaxLines)
        assertTrue(narrow.compact)

        assertTrue(homeLaunchCardLayout(480.dp, 320.dp).compact)
    }

    /**
     * Launch-card padding convergence. The four expectations below at 380/480/720/960dp were
     * synthetic test-fixture artifacts, not device measurements or an policy — verified by git-blame
     * (commits `ad10fbfd`/`78e97bf5`/`dc398c5b`/`5603e9d9`, each a generic "settle …" pass with no KDoc or
     * message claiming a measured or configured value) and by cross-check against `MimirCertifiedViewports` (none
     * of the four widths matches a certified DEVICE row or the SAME box as a certified SYNTHETIC_BOUNDARY
     * row — see [homeLaunchCardOuterHorizontal]'s own KDoc for the full per-pin verdict). Moved to match
     * `homeLaunchCardOuterHorizontal`'s new single Odin-anchored proportional law
     * (`w * (44f / 832.5f)`), recomputed from a real run, not copied from the doc comment.
     */
    @Test
    fun footerActionClusterStaysCompactAndCenteredInsteadOfGrowingWithTheFooter() {
        // full/medium subtract coverColumnWidth -- moved by this ticket's coverColumnWidth fix (see
        // targetLandscapeBreakpointsKeepMetadataLeftAndCoverRightWithoutOvergrowth's own KDoc). Both are
        // `layout.compact == false` already (960/720dp are both >= the 720dp band), so
        // [homeLaunchCardUsesWideSkeleton] returns true on the `!layout.compact` short-circuit alone --
        // unaffected by this ticket's Defect B convergence.
        val full = homeLaunchCardLayout(960.dp, 480.dp)
        assertEquals(527.3271f, homeLaunchCardTextColumnWidth(960.dp, full, wideSkeleton = true).value, 0.001f)

        // Regression 1 (this ticket): 720x432 is aspect 1.667, under the Odin's own 1.7778, so
        // `medium.coverColumnWidth` now takes the WIDTH term (249.0811dp, not the old height term's
        // 265.6759dp) -- the text column WIDENS accordingly, from 350.2160dp to 366.8108dp.
        val medium = homeLaunchCardLayout(720.dp, 432.dp)
        assertEquals(366.8108f, homeLaunchCardTextColumnWidth(720.dp, medium, wideSkeleton = true).value, 0.001f)

        // RC-3 Defect B : this used to be a 480x320
        // probe of the "compact does not subtract" branch. Under `homeLaunchCardUsesWideSkeleton`, 480x320
        // is STANDARD-postured (aspect 1.5 > 1.12) so it now takes the WIDE (subtract) branch instead --
        // the "does not subtract" branch is only reachable at a genuinely COMPACT_SQUARE box now, so this
        // probe moves to 480x480 (matching `MimirCertifiedViewports.CompactSquare480`) to keep testing the
        // branch it was written to test. `outerHorizontal` depends on WIDTH only, so 480dp gives the exact
        // same 429.2613 content width as the old 480x320 probe did -- the literal below is unchanged.
        val compactSquareProbe = homeLaunchCardLayout(480.dp, 480.dp)
        assertFalse(
            "480x480 is COMPACT_SQUARE (aspect 1.0 <= 1.12) -- stays on the stacked skeleton",
            homeLaunchCardUsesWideSkeleton(480.dp, 480.dp, compactSquareProbe),
        )
        assertEquals(
            429.2613f,
            homeLaunchCardTextColumnWidth(480.dp, compactSquareProbe, wideSkeleton = false).value,
            0.001f,
        )

        listOf(520.dp, 560.dp, 640.dp).forEach { width ->
            val layout = homeLaunchCardLayout(width, 390.dp)
            assertTrue("$width should keep all three actions readable", layout.compact)
            // RC-3 Defect B: width x 390dp is STANDARD-postured at all three widths (aspect > 1.12), so
            // `homeLaunchCardUsesWideSkeleton` now returns true here even though `layout.compact` (which
            // governs columnGap/descriptionMaxLines only, not the render arm any more) stays true too --
            // this loop used to assert the OPPOSITE (no subtraction); see that function's own KDoc.
            val wideSkeleton = homeLaunchCardUsesWideSkeleton(width, 390.dp, layout)
            assertTrue("$width x 390dp should resolve to the Wide skeleton", wideSkeleton)
            assertEquals(
                width - layout.outerHorizontal * 2 - layout.coverColumnWidth - layout.columnGap,
                homeLaunchCardTextColumnWidth(width, layout, wideSkeleton),
            )
        }

        listOf(480.dp, 520.dp, 720.dp, 800.dp, 960.dp).forEach { width ->
            assertEquals(
                "$width should never expand the action cluster",
                380f,
                homeLaunchCardActionRowWidth(width, homeLaunchCardLayout(width, 432.dp)).value,
                0.001f,
            )
            assertFalse(homeLaunchCardActionsStacked(width, homeLaunchCardLayout(width, 432.dp)))
        }

        val tiny = homeLaunchCardLayout(380.dp, 320.dp)
        assertEquals(339.8318f, homeLaunchCardActionRowWidth(380.dp, tiny).value, 0.001f)
        assertTrue(homeLaunchCardActionsStacked(380.dp, tiny))
    }

    /**
     * The footer's un-stacked shape at the two viewports the retired M-lane tests used as fixtures.
     *
     * **Every literal here is the UNRESERVED one those tests already pinned, carried over unchanged.**
     * Three sibling tests once pinned a reserved arm as well -- the Nova shrinking to 440.3483dp, the 538
     * companion to 349.1303dp, the 320x300 probe stacking for a floor reason. No surface reserves for the
     * orb any more  and every production caller always passed zero, so the
     * reserved arm never executed on any device and its pins are gone with it. What survives is what
     * actually shipped.
     */
    @Test
    fun extraActionFooterKeepsItsNominalRowAndTheFloorProbeStillStacks() {
        val nova = homeLaunchCardLayout(640.dp, 480.dp)
        val floor = homeLaunchCardLayout(320.dp, 300.dp)

        assertFalse(
            "Nova, extraAction: un-stacked",
            homeLaunchCardActionsStacked(640.dp, nova, hasExtraAction = true),
        )
        assertEquals(
            530f,
            homeLaunchCardActionRowWidth(640.dp, nova, hasExtraAction = true).value,
            0.001f,
        )
        assertFalse(
            "Nova, no extraAction: three-button row was never at risk",
            homeLaunchCardActionsStacked(640.dp, nova, hasExtraAction = false),
        )
        assertEquals(
            380f,
            homeLaunchCardActionRowWidth(640.dp, nova, hasExtraAction = false).value,
            0.001f,
        )

        // The 320x300 floor probe stacks on its own content width (286.1742dp < 400dp), as it always did.
        assertTrue(
            "320x300, three-button: stacks on the legacy content-width literal",
            homeLaunchCardActionsStacked(320.dp, floor, hasExtraAction = false),
        )
        assertTrue(
            "320x300, extraAction: stacks",
            homeLaunchCardActionsStacked(320.dp, floor, hasExtraAction = true),
        )
        assertEquals(
            286.1742f,
            homeLaunchCardActionRowWidth(320.dp, floor, hasExtraAction = false).value,
            0.001f,
        )
    }

    /** `homeLaunchCardActionRowFloorWidth` is the uniform-scale floor (Play, the smallest nominal button
     * at 96dp, is what binds `minScale = 48/96 = 0.5`) — NOT the sum of four independent 48dp minimums,
     * which would describe a non-uniform solve this ticket's own KDoc argues against. `[D]` 352*0.5 plus two gaps
     * (three-button), 488*0.5 plus three (extraAction), the gap being the focus ring's reach (14 dp). */
    @Test
    fun actionRowFloorWidthIsTheUniformScaleFloorNotFourIndependentMinimums() {
        val gap = HomeLaunchCardActionGap.value
        assertEquals(352f * 0.5f + 2 * gap, homeLaunchCardActionRowFloorWidth(hasExtraAction = false).value, 0.001f)
        assertEquals(488f * 0.5f + 3 * gap, homeLaunchCardActionRowFloorWidth(hasExtraAction = true).value, 0.001f)
        assertEquals(204f, homeLaunchCardActionRowFloorWidth(hasExtraAction = false).value, 0.001f)
        assertEquals(286f, homeLaunchCardActionRowFloorWidth(hasExtraAction = true).value, 0.001f)
    }

    /** [homeLaunchCardActionButtonWidths] must reproduce the historical fixed 96/120/136(/136)dp widths
     * bit-exactly whenever the row is at or above its own nominal width — every certified viewport this
     * ticket does not touch (every unreserved surface; the Odin, always) renders identically to before. */
    @Test
    fun actionButtonWidthsAreBitExactToTheHistoricalFixedWidthsAtOrAboveNominal() {
        assertEquals(
            listOf(96f, 120f, 136f),
            homeLaunchCardActionButtonWidths(380.dp, hasExtraAction = false).map { it.value },
        )
        assertEquals(
            listOf(96f, 120f, 136f),
            homeLaunchCardActionButtonWidths(500.dp, hasExtraAction = false).map { it.value },
        )
        assertEquals(
            listOf(96f, 136f, 120f, 136f),
            homeLaunchCardActionButtonWidths(530.dp, hasExtraAction = true).map { it.value },
        )
    }

    /**
     * [homeLaunchCardActionBounds]'s un-stacked branch used to be three hardcoded [FocusBounds] literals,
     * correct only because the row's painted width never actually shrank before this ticket. It now reads
     * [homeLaunchCardActionButtonWidths] — the SAME solver the render uses — so this test both proves the
     * historical numbers survive bit-exactly at the nominal width AND that a shrunk row's focus bounds
     * track its painted buttons instead of diverging from them (the "paint and focus lattice diverge" trap
     * the brief warned about).
     */
    @Test
    fun actionBoundsTrackThePaintedButtonWidthsInsteadOfAHardcodedNominalMap() {
        val gap = MimirFocusTileRingReach.value
        val nominal = homeLaunchCardActionBounds(stacked = false, actionRowWidth = 380f)
        assertEquals(FocusBounds(0f, 0f, 96f, 44f), nominal.getValue(HomeLaunchCardPlayKey))
        assertEquals(FocusBounds(96f + gap, 0f, 96f + gap + 120f, 44f), nominal.getValue(HomeLaunchCardOpenWithKey))
        assertEquals(FocusBounds(96f + gap + 120f + gap, 0f, 380f, 44f), nominal.getValue(HomeLaunchCardAchievementsKey))
        assertEquals("the one gap constant is the focus ring's whole reach, the token and not a number", MimirFocusTileRingReach, HomeLaunchCardActionGap)
        assertEquals("and the default row width is the nominal sum", 380f, HomeLaunchCardActionNominalRowWidthDp, 0f)

        // 538 companion, reserved: actionRowWidth 349.1303dp -- the SAME value
        // threeButtonFooterShrinksOnThe538CompanionInsteadOfIntrudingOnTheMLane already pins.
        val shrunk = homeLaunchCardActionBounds(stacked = false, actionRowWidth = 349.1303f)
        val expectedWidths = homeLaunchCardActionButtonWidths(349.1303f.dp, hasExtraAction = false)
        val playBounds = shrunk.getValue(HomeLaunchCardPlayKey)
        assertEquals(0f, playBounds.left, 0.001f)
        assertEquals(expectedWidths[0].value, playBounds.right, 0.001f)
        val openWithBounds = shrunk.getValue(HomeLaunchCardOpenWithKey)
        assertEquals(expectedWidths[0].value + HomeLaunchCardActionGap.value, openWithBounds.left, 0.001f)
        assertEquals(openWithBounds.left + expectedWidths[1].value, openWithBounds.right, 0.001f)
        val achievementsBounds = shrunk.getValue(HomeLaunchCardAchievementsKey)
        assertEquals(openWithBounds.right + HomeLaunchCardActionGap.value, achievementsBounds.left, 0.001f)
        assertEquals(achievementsBounds.left + expectedWidths[2].value, achievementsBounds.right, 0.001f)
        // No painted button ever falls below minTarget's own dp value in this focus space either.
        assertTrue(playBounds.right - playBounds.left >= MimirSpacing.minTarget.value - 0.01f)
    }

    /**
     * [homeLaunchCardTextColumnWidth] against an independently-written reproduction of its algebra, at every
     * certified STANDARD viewport. `oldFormula` is retyped here rather than called, so a change to the
     * production solver fails this rather than moving both sides together.
     *
     * This test once also pinned an inset-aware arm: the solver took an M-lane inset and had to read
     * exactly 66dp narrower when a caller passed it. That parameter is gone (no
     * surface reserves for the orb at all) and no production caller ever passed a non-zero value, so only
     * the default-call-site half -- the half that always described production -- survives.
     */
    @Test
    fun textColumnWidthMatchesItsOwnAlgebraAtEveryCertifiedViewport() {
        fun oldFormula(width: Dp, height: Dp): Float {
            val layout = homeLaunchCardLayout(width, height)
            val wideSkeleton = homeLaunchCardUsesWideSkeleton(width, height, layout)
            val contentWidth = (width - layout.outerHorizontal * 2).coerceAtLeast(0.dp)
            return if (!wideSkeleton) {
                contentWidth
            } else {
                (contentWidth - layout.coverColumnWidth - layout.columnGap).coerceAtLeast(0.dp)
            }.value
        }

        listOf(
            MimirCertifiedViewports.Odin,
            MimirCertifiedViewports.RetroidPocketNova,
            MimirCertifiedViewports.Rp5Shelf,
            MimirCertifiedViewports.OdinHeightCompanion538,
        ).forEach { vp ->
            val box = vp.launchCardInputDp()
            val width = box.widthDp.dp
            val height = box.heightDp.dp
            val layout = homeLaunchCardLayout(width, height)
            val wideSkeleton = homeLaunchCardUsesWideSkeleton(width, height, layout)

            assertEquals(
                "${vp.name}: solver must be bit-identical to its own algebra",
                oldFormula(width, height),
                homeLaunchCardTextColumnWidth(width, layout, wideSkeleton).value,
                0.001f,
            )
        }
    }

    /**
     * RC-3 Defect C fault #2  — differential proof of
     * the fix's mechanism, `git apply -R`-style but done algebraically since the render tree itself needs a
     * device/Robolectric harness this module does not have. `oldOffsetDp` reproduces the FORMER render
     * structure exactly: the Footer's `Box(fillMaxWidth, contentAlignment = Center)` used to sit inside the
     * SAME outer Column whose `end` padding already carried the retired static M lane (66dp = a 56dp orb
     * plus 10dp of clearance), so it centred the fixed-width action row
     * inside a box already narrowed by 66dp on one side only. `newOffsetDp` reproduces the CURRENT structure
     * (`HomeLaunchCard.kt`'s composable body, this ticket): the inset now lands only on the header+art `Box`,
     * so the Footer centres in the TRUE, un-narrowed content width.
     *
     * The offset reduces algebraically to exactly `-mInsetDp / 2` regardless of width or row width (the
     * `rowWidth` terms cancel) — so it is EXACTLY -33.0dp on every viewport under the old formula, not merely
     * close to it. The geometry doc's own pixel captures read -33.2dp (Nova) and -32.8dp (Odin), each within
     * 0.2dp of this exact prediction — comfortably inside that doc's own declared "±1dp" pixel-read error,
     * and evidence the two independent captures were measuring the SAME single mechanism, not two different
     * ones that happened to land close together.
     */
    @Test
    fun actionRowCentresInTheFullContentBoxNowThatTheMInsetNoLongerNarrowsTheFooter() {
        val mInsetDp = 66f // the retired static M lane: 56dp orb + 10dp clearance

        fun oldOffsetDp(width: Float, height: Float): Float {
            val layout = homeLaunchCardLayout(width.dp, height.dp)
            val contentWidth = width - layout.outerHorizontal.value * 2
            val rowWidth = homeLaunchCardActionRowWidth(width.dp, layout).value
            val narrowedWidth = contentWidth - mInsetDp
            val rowLeftInNarrowedBox = (narrowedWidth - rowWidth) / 2f
            val rowCentre = rowLeftInNarrowedBox + rowWidth / 2f
            return rowCentre - contentWidth / 2f
        }

        fun newOffsetDp(width: Float, height: Float): Float {
            val layout = homeLaunchCardLayout(width.dp, height.dp)
            val contentWidth = width - layout.outerHorizontal.value * 2
            val rowWidth = homeLaunchCardActionRowWidth(width.dp, layout).value
            val rowLeft = (contentWidth - rowWidth) / 2f
            val rowCentre = rowLeft + rowWidth / 2f
            return rowCentre - contentWidth / 2f
        }

        // Nova: geometry doc measured -33.2dp [D, pixel capture, ~1dp read error].
        assertEquals(-33.0f, oldOffsetDp(640f, 480f), 0.001f)
        assertEquals(0.0f, newOffsetDp(640f, 480f), 0.001f)

        // Odin: geometry doc measured -32.8dp [D, pixel capture, ~1dp read error].
        assertEquals(-33.0f, oldOffsetDp(832.5f, 468.3f), 0.001f)
        assertEquals(0.0f, newOffsetDp(832.5f, 468.3f), 0.001f)

        // The identity holds at every certified STANDARD viewport, not just the two captured devices --
        // rowWidth cancels out of the algebra, so the old offset is exactly -mInsetDp/2 everywhere.
        MimirCertifiedViewports.standard.forEach { vp ->
            val box = vp.launchCardInputDp()
            assertEquals(
                "${vp.name}: old formula must reproduce the exact -mInsetDp/2 identity",
                -33.0f,
                oldOffsetDp(box.widthDp, box.heightDp),
                0.001f,
            )
            assertEquals(
                "${vp.name}: new formula must centre exactly",
                0.0f,
                newOffsetDp(box.widthDp, box.heightDp),
                0.001f,
            )
        }
    }

    /**
     * RC-3 Defect B convergence  -- the render-arm
     * selector at every certified viewport, cross-checked two ways: against
     * [MimirCertifiedViewports]'s own `posture` column (never re-derived independently here — a divergence
     * between this file's own `mimirCertifiedViewportPostureMatchesTheClassifier` self-test and this one
     * would mean the two disagree on what `mimirViewportProfile` returns, which would be a real bug) AND
     * against `layout.compact`'s existing width band, to verify which viewports changed. **The Odin, and every viewport where `layout.compact` was
     * already false (720/1080 squares), are proven UNCHANGED** — [homeLaunchCardUsesWideSkeleton]'s
     * `!layout.compact` short-circuit means their posture is never even consulted.
     */
    @Test
    fun usesWideSkeletonExactlyAtStandardPostureCompactBandedCertifiedViewports() {
        fun wideSkeletonAt(vp: MimirDpBox): Boolean {
            val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
            return homeLaunchCardUsesWideSkeleton(vp.widthDp.dp, vp.heightDp.dp, layout)
        }

        // Unchanged: layout.compact was already false, so the posture check never runs.
        listOf(
            MimirCertifiedViewports.Odin,
            MimirCertifiedViewports.CompactSquare720,
            MimirCertifiedViewports.LargeSquareBoundary1080,
        ).forEach { vp ->
            val box = vp.launchCardInputDp()
            val layout = homeLaunchCardLayout(box.widthDp.dp, box.heightDp.dp)
            assertFalse("${vp.name} must already have layout.compact == false", layout.compact)
            assertTrue("${vp.name} must stay on the Wide skeleton", wideSkeletonAt(box))
        }

        // Changed by this ticket: layout.compact is true AND posture is STANDARD.
        listOf(MimirCertifiedViewports.RetroidPocketNova, MimirCertifiedViewports.Rp5Shelf).forEach { vp ->
            assertEquals("${vp.name} must be STANDARD posture", MimirViewportPosture.STANDARD, vp.posture)
            assertTrue(
                "${vp.name} moves to the Wide skeleton (RC-3 Defect B)",
                wideSkeletonAt(vp.launchCardInputDp()),
            )
        }
        assertEquals(MimirViewportPosture.STANDARD, MimirCertifiedViewports.OdinHeightCompanion538.posture)
        assertTrue(wideSkeletonAt(MimirCertifiedViewports.OdinHeightCompanion538.launchCardInputDp()))

        // Protected: COMPACT_SQUARE posture keeps the stacked skeleton no matter how `layout.compact` bands.
        listOf(
            MimirCertifiedViewports.CompactSquare360,
            MimirCertifiedViewports.CompactSquare411,
            MimirCertifiedViewports.CompactSquare480,
            MimirCertifiedViewports.MinimumFloor320x300,
        ).forEach { vp ->
            assertEquals("${vp.name} must be COMPACT_SQUARE posture", MimirViewportPosture.COMPACT_SQUARE, vp.posture)
            assertFalse(
                "${vp.name} must stay on the stacked skeleton (its Wide text column would fall below a " +
                    "readable floor -- see homeLaunchCardUsesWideSkeleton's own KDoc)",
                wideSkeletonAt(vp.launchCardInputDp()),
            )
        }
    }

    @Test
    fun emulatedAndAndroidCardsFreezeTheirRuledActionSets() {
        assertEquals(
            listOf(
                HomeLaunchCardTitleEditKey,
                HomeLaunchCardPlayKey,
                HomeLaunchCardOpenWithKey,
                HomeLaunchCardAchievementsKey,
            ),
            homeLaunchCardActionKeys(emulated = true),
        )
        assertEquals(
            listOf(HomeLaunchCardPlayKey),
            homeLaunchCardActionKeys(emulated = false),
        )
    }

    @Test
    fun descriptionContinuationGlyphIsContentAware() {
        assertEquals("↓", homeLaunchDescriptionContinuationGlyph(canScrollUp = false, canScrollDown = true))
        assertEquals("↕", homeLaunchDescriptionContinuationGlyph(canScrollUp = true, canScrollDown = true))
        assertEquals("↑", homeLaunchDescriptionContinuationGlyph(canScrollUp = true, canScrollDown = false))
        assertEquals(null, homeLaunchDescriptionContinuationGlyph(canScrollUp = false, canScrollDown = false))
    }

    @Test
    fun focusedDescriptionPagesBeforeReturningToPlayAtTheEnd() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        authority.registerVirtual(
            HomeLaunchCardDescriptionKey,
            HomeLaunchCardScope,
            FocusBounds(0f, -100f, 368f, -20f),
        )
        authority.requestFocus(HomeLaunchCardDescriptionKey)
        val pages = mutableListOf<Int>()

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.NAV_DOWN,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(
                scrollable = true,
                canScrollDown = true,
            ),
            onDescriptionScroll = pages::add,
        )
        assertEquals(listOf(1), pages)
        assertEquals(HomeLaunchCardDescriptionKey, authority.state.value.focused)

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.NAV_DOWN,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(
                scrollable = true,
                canScrollDown = false,
            ),
        )
        assertEquals(HomeLaunchCardPlayKey, authority.state.value.focused)
    }

    /**
     * Renamed from "...ReturnsToPlayWhenUpReachesTheBeginning" — the deterministic vertical ladder moved this edge: Description's UP-at-the-top exit now continues to Edit, not back
     * down to Play. Play only sits BELOW Description on the ladder now; see the KDoc on
     * [emulatedSpatialGraphPinsAllSixteenDirectionalMoves] and on [HomeLaunchCardTitleEditKey].
     */
    @Test
    fun focusedDescriptionReturnsToEditWhenUpReachesTheBeginning() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        authority.registerVirtual(
            HomeLaunchCardDescriptionKey,
            HomeLaunchCardScope,
            FocusBounds(0f, -100f, 368f, -20f),
        )
        authority.requestFocus(HomeLaunchCardDescriptionKey)

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.NAV_UP,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(
                scrollable = true,
                canScrollUp = false,
            ),
        )

        assertEquals(HomeLaunchCardTitleEditKey, authority.state.value.focused)
    }

    /** DOWN from Edit is the mirror of UP from Play: it visits Description first when there is one. */
    @Test
    fun titleEditDownEntersAScrollableDescriptionBeforePlay() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        authority.registerVirtual(
            HomeLaunchCardDescriptionKey,
            HomeLaunchCardScope,
            FocusBounds(0f, -100f, 368f, -20f),
        )
        authority.requestFocus(HomeLaunchCardTitleEditKey)

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.NAV_DOWN,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(scrollable = true),
        )

        assertEquals(HomeLaunchCardDescriptionKey, authority.state.value.focused)
    }

    /** DOWN from Edit with no description to visit lands straight on Play — pinned separately from the
     * default-state 16-edge test since this asserts the *reason* (no scrollable description), not just
     * the default value. */
    @Test
    fun titleEditDownWithNoDescriptionLandsOnPlay() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        authority.requestFocus(HomeLaunchCardTitleEditKey)

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.NAV_DOWN,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(scrollable = false),
        )

        assertEquals(HomeLaunchCardPlayKey, authority.state.value.focused)
    }

    /** Sweep 10-09: OpenWith's and Achievements' UP reach the Rename mark on an emulated card, as Play's does (they used to dead-end on themselves, so the mark could only be
     * reached from Play). A card with no Rename mark (not emulated) registers no Edit key, so there UP is the plain geometric move it always was. */
    @Test
    fun openWithAndAchievementsUpReachTheRenameMarkOnlyWhenThereIsOne() {
        val emulated = homeAuthority()
        enterHomeLaunchCard(emulated, emulated = true)
        for (key in listOf(HomeLaunchCardOpenWithKey, HomeLaunchCardAchievementsKey)) {
            emulated.requestFocus(key)
            dispatchHomeLaunchCard(
                authority = emulated,
                emulated = true,
                action = ControllerAction.NAV_UP,
                onPlay = {}, onEdit = {}, onEditTitle = {}, onAchievements = {}, onOpenWith = {}, onClose = {},
            )
            assertEquals("UP from $key reaches the Rename mark", HomeLaunchCardTitleEditKey, emulated.state.value.focused)
        }

        val plain = homeAuthority()
        enterHomeLaunchCard(plain, emulated = false)
        for (key in listOf(HomeLaunchCardOpenWithKey, HomeLaunchCardAchievementsKey)) {
            plain.requestFocus(key)
            dispatchHomeLaunchCard(
                authority = plain,
                emulated = false,
                action = ControllerAction.NAV_UP,
                onPlay = {}, onEdit = {}, onEditTitle = {}, onAchievements = {}, onOpenWith = {}, onClose = {},
            )
            assertNotEquals("a card with no Rename mark has nothing to reach from $key", HomeLaunchCardTitleEditKey, plain.state.value.focused)
        }
    }

    /** Play's UP redirect to Edit is gated on `emulated` — a non-emulated card has no Edit key registered
     * at all, so it must keep the plain geometric self-loop instead of requesting an unregistered key. */
    @Test
    fun nonEmulatedPlayUpStaysOnPlay() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = false)

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = false,
            action = ControllerAction.NAV_UP,
            onPlay = {}, onEdit = {}, onEditTitle = {}, onAchievements = {}, onOpenWith = {}, onClose = {},
        )

        assertEquals(HomeLaunchCardPlayKey, authority.state.value.focused)
    }

    @Test
    fun confirmOnFocusedDescriptionPagesThenReturnsToPlay() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        authority.registerVirtual(
            HomeLaunchCardDescriptionKey,
            HomeLaunchCardScope,
            FocusBounds(0f, -100f, 368f, -20f),
        )
        authority.requestFocus(HomeLaunchCardDescriptionKey)
        val pages = mutableListOf<Int>()

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.CONFIRM,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(
                scrollable = true,
                canScrollDown = true,
            ),
            onDescriptionScroll = pages::add,
        )

        assertEquals(listOf(1), pages)
        assertEquals(HomeLaunchCardDescriptionKey, authority.state.value.focused)

        dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.CONFIRM,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(
                scrollable = true,
                canScrollDown = false,
            ),
        )

        assertEquals(HomeLaunchCardPlayKey, authority.state.value.focused)
    }

    @Test
    fun launchArtUsesTheSamePlatformAspectFamilyWithEqualVisualArea() {
        val psp = homeLaunchCardArtSize("psp", maxWidth = 300.dp, maxHeight = 300.dp)
        assertEquals(300.dp, psp.height)
        assertEquals(177f, psp.width.value, 0.001f)

        val snes = homeLaunchCardArtSize("snes", maxWidth = 300.dp, maxHeight = 300.dp)
        assertEquals(300f, snes.width.value, 0.5f)
        assertEquals(300f / 1.36f, snes.height.value, 0.5f)

        val square = homeLaunchCardArtSize("gba", maxWidth = 300.dp, maxHeight = 300.dp)
        assertEquals(256.9f, square.width.value, 0.1f)
        assertEquals(256.9f, square.height.value, 0.1f)

        assertEquals(0.59f, psp.width.value / psp.height.value, 0.001f)
        assertEquals(1.36f, snes.width.value / snes.height.value, 0.001f)
        assertEquals(66_000f, square.width.value * square.height.value, 1f)
    }

    /**
     * RC-3 continued (this ticket, 2026-08-21) — renamed and rewritten. The old name/premise ("wide RP5 band
     * RAISES the ceiling") pinned exactly the `heightBand`/per-arm-clamp behaviour this ticket removes: at
     * height=540dp the OLD law gave 300dp at width=960 (unclamped `>=800.dp` arm) but only 240dp at
     * width=760 (the `>=720.dp` arm's `minOf(heightBand, 240.dp)` clamp) — the SAME height, two different
     * ceilings, because the ceiling was keyed off which WIDTH arm was taken. `coverHeight`/`coverColumnWidth`
     * are now pure functions of height alone (see `homeLaunchCardCoverHeight`'s KDoc) — width cannot move
     * either value anymore, so 960dp and 760dp at the same height now agree exactly, and the value is
     * `homeLaunchCardCoverHeight(540.dp)`, not a clamped band constant.
     */
    @Test
    fun coverHeightNoLongerDependsOnWhichWidthArmWasTaken() {
        val wide540 = homeLaunchCardLayout(960.dp, 540.dp)
        val narrow540 = homeLaunchCardLayout(760.dp, 540.dp)
        assertEquals(wide540.coverHeight, narrow540.coverHeight)
        assertEquals(345.9321f, wide540.coverHeight.value, 0.001f)

        val wide420 = homeLaunchCardLayout(960.dp, 420.dp)
        assertEquals(269.0583f, wide420.coverHeight.value, 0.001f)
    }

    @Test
    fun everyOpenLandsOnPlayAndBackRestoresTheExactHomeTile() {
        val authority = homeAuthority()
        val homeKey = FocusKey("home/shelf/exact")
        authority.registerVirtual(homeKey, HomeShelfScope, FocusBounds(0f, 0f, 100f, 100f))
        authority.pushScope(HomeShelfScope)
        authority.requestFocus(homeKey)

        enterHomeLaunchCard(authority, emulated = true)
        authority.requestFocus(HomeLaunchCardAchievementsKey)
        exitHomeLaunchCard(authority)
        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals(homeKey, authority.state.value.focused)

        enterHomeLaunchCard(authority, emulated = true)
        assertEquals(HomeLaunchCardScope, authority.state.value.scope)
        assertEquals(HomeLaunchCardPlayKey, authority.state.value.focused)
    }

    /**
     * Was "pins all TWELVE" (3 keys x 4 directions) before the rename glyph, then extended to 4 keys x 4 =
     * sixteen when the glyph landed (adding [HomeLaunchCardTitleEditKey] to the LEFT of Play). Still
     * sixteen: every key/direction pair below is exercised with the DEFAULT (non-scrollable) description
     * state, exactly as before.
     *
     * TWO EDGES INTENTIONALLY CHANGED for the deterministic vertical ladder (policy 2026-08-11,
     * `dispatchHomeLaunchCard`'s new Play/TitleEdit branches) and re-pinned here at their NEW values:
     *  - `HomeLaunchCardPlayKey` + `NAV_UP`: was self (`HomeLaunchCardPlayKey`), now
     *    `HomeLaunchCardTitleEditKey` — Play's UP now always continues the ladder; with the default
     *    non-scrollable description there is no Description rung to visit, so it reaches Edit directly.
     *  - `HomeLaunchCardTitleEditKey` + `NAV_DOWN`: was self (`HomeLaunchCardTitleEditKey`), now
     *    `HomeLaunchCardPlayKey` — the mirror-image return trip, DOWN from Edit with nothing to visit.
     * TWO MORE EDGES CHANGED (sweep 10-09: UP from ACHIEVEMENTS found nothing): `HomeLaunchCardOpenWithKey` + `NAV_UP` and
     * `HomeLaunchCardAchievementsKey` + `NAV_UP` were self, and now reach `HomeLaunchCardTitleEditKey`, as Play's does: nothing in the single
     * row sits above them, so the ladder continues to the mark (the row's own geometric neighbour is asked first, see
     * `aStackedSecondRowStepsUpToTheRowAboveBeforeTheLadderContinues`).
     * Every OTHER edge below — including TitleEdit's own NAV_UP (top of the ladder, still a self dead-end
     * via the untouched geometric `moveFocus` fallback) — is UNCHANGED, proving the ladder addition does not disturb any
     * other pinned traversal. This is the
     * differential proof for the highest-risk part of the lane: Play/OpenWith/Achievements/TitleEdit all
     * still share the SAME synthetic coordinate space (see the bounds comment on
     * [HomeLaunchCardTitleEditKey]), and the two new edges above are explicit `authority.requestFocus`
     * calls, never a new `moveFocus` candidate set — so neither one can repeat BUG-A's physical/virtual
     * mixing.
     */
    @Test
    fun emulatedSpatialGraphPinsAllSixteenDirectionalMoves() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        val expected = mapOf(
            HomeLaunchCardTitleEditKey to mapOf(
                ControllerAction.NAV_UP to HomeLaunchCardTitleEditKey,
                ControllerAction.NAV_DOWN to HomeLaunchCardPlayKey, // CHANGED: was self, see class KDoc.
                ControllerAction.NAV_LEFT to HomeLaunchCardTitleEditKey,
                ControllerAction.NAV_RIGHT to HomeLaunchCardPlayKey,
            ),
            HomeLaunchCardPlayKey to mapOf(
                ControllerAction.NAV_UP to HomeLaunchCardTitleEditKey, // CHANGED: was self, see class KDoc.
                ControllerAction.NAV_DOWN to HomeLaunchCardPlayKey,
                ControllerAction.NAV_LEFT to HomeLaunchCardTitleEditKey,
                ControllerAction.NAV_RIGHT to HomeLaunchCardOpenWithKey,
            ),
            HomeLaunchCardOpenWithKey to mapOf(
                ControllerAction.NAV_UP to HomeLaunchCardTitleEditKey, // CHANGED (sweep 10-09): the row reaches the rename mark, see class KDoc.
                ControllerAction.NAV_DOWN to HomeLaunchCardOpenWithKey,
                ControllerAction.NAV_LEFT to HomeLaunchCardPlayKey,
                ControllerAction.NAV_RIGHT to HomeLaunchCardAchievementsKey,
            ),
            HomeLaunchCardAchievementsKey to mapOf(
                ControllerAction.NAV_UP to HomeLaunchCardTitleEditKey, // CHANGED (sweep 10-09): the row reaches the rename mark, see class KDoc.
                ControllerAction.NAV_DOWN to HomeLaunchCardAchievementsKey,
                ControllerAction.NAV_LEFT to HomeLaunchCardOpenWithKey,
                ControllerAction.NAV_RIGHT to HomeLaunchCardAchievementsKey,
            ),
        )

        expected.forEach { (from, directions) ->
            directions.forEach { (direction, target) ->
                authority.requestFocus(from)
                assertTrue(
                    dispatchHomeLaunchCard(
                        authority = authority,
                        emulated = true,
                        action = direction,
                        onPlay = {},
                        onEdit = {},
                        onEditTitle = {},
                        onAchievements = {},
                        onOpenWith = {},
                        onClose = {},
                    ),
                )
                assertEquals("$from + $direction", target, authority.state.value.focused)
            }
        }
    }

    /**
     * the one F1
     * mixed-coordinate-space instance that actually fires. Registers Description PHYSICALLY (as
     * `HomeLaunchCard.kt:1313-1315`'s `.mimirFocusable(...)` does at runtime once mounted and scrollable)
     * at bounds a geometric `moveFocus` NAV_DOWN contest CANNOT reach — centerY -150, above/behind Play's
     * synthetic centerY 22, the same "opposite side of the footer" shape the frozen suite's own fixture
     * uses at `HomeLaunchCardTest.kt:330` (`FocusBounds(0f, -100f, 368f, -20f)`) for the CONFIRM-driven
     * scroll test. `inDirection`'s NAV_DOWN filter (`DefaultFocusAuthority.kt:201`,
     * `to.centerY > from.centerY`) EXCLUDES a candidate up here, and no other footer key sits below Play
     * either — so a bare `authority.moveFocus(NAV_DOWN)` dead-ends (returns false, focus stays on Play).
     * If this test passed with only the call site reverted, the fix would be cosmetic: proof this bites
     * is captured by reverting just the new branch and observing this exact assertion fail (captured during the differential check).
     */
    @Test
    fun playDownReachesDescriptionByAnExplicitHopEvenWhenGeometryWouldDeadEnd() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        authority.register(
            HomeLaunchCardDescriptionKey,
            HomeLaunchCardScope,
            FocusBounds(0f, -200f, 368f, -100f),
        )
        authority.requestFocus(HomeLaunchCardPlayKey)

        val moved = dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.NAV_DOWN,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(scrollable = true),
        )

        assertTrue(moved)
        assertEquals(HomeLaunchCardDescriptionKey, authority.state.value.focused)
    }

    /**
     * Same fix, the other edge: when Description isn't scrollable it never registers physically in
     * production (`HomeLaunchCard.kt:1313-1315`'s `if (scrollable)` gate), so DOWN from Play must stay a
     * self dead-end exactly as the pre-fix geometric fallback already produced for this case (pinned
     * separately by [emulatedSpatialGraphPinsAllSixteenDirectionalMoves]) — the new explicit branch must
     * reproduce that outcome, not just the scrollable one.
     */
    @Test
    fun playDownStaysOnPlayWhenDescriptionIsNotScrollable() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        authority.requestFocus(HomeLaunchCardPlayKey)

        val moved = dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = ControllerAction.NAV_DOWN,
            onPlay = {},
            onEdit = {},
            onEditTitle = {},
            onAchievements = {},
            onOpenWith = {},
            onClose = {},
            descriptionScrollState = HomeLaunchDescriptionScrollState(scrollable = false),
        )

        assertTrue(moved)
        assertEquals(HomeLaunchCardPlayKey, authority.state.value.focused)
    }

    @Test
    fun sub400FooterUsesTwoDimensionalFocusGeometryThatMatchesItsVisualRows() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        registerHomeLaunchCardActions(
            authority = authority,
            emulated = true,
            stacked = true,
            actionRowWidth = 352f,
        )

        authority.requestFocus(HomeLaunchCardPlayKey)
        authority.moveFocus(ControllerAction.NAV_DOWN)
        assertEquals(HomeLaunchCardAchievementsKey, authority.state.value.focused)

        authority.requestFocus(HomeLaunchCardOpenWithKey)
        authority.moveFocus(ControllerAction.NAV_DOWN)
        assertEquals(HomeLaunchCardAchievementsKey, authority.state.value.focused)

        authority.moveFocus(ControllerAction.NAV_UP)
        assertEquals(HomeLaunchCardOpenWithKey, authority.state.value.focused)

        authority.requestFocus(HomeLaunchCardAchievementsKey)
        assertFalse(authority.moveFocus(ControllerAction.NAV_DOWN))
        assertEquals(HomeLaunchCardAchievementsKey, authority.state.value.focused)
    }

    @Test
    fun confirmAndShortcutsRouteOnlyTheFocusedCardAction() {
        val authority = homeAuthority()
        enterHomeLaunchCard(authority, emulated = true)
        val calls = mutableListOf<String>()
        fun dispatch(action: ControllerAction) = dispatchHomeLaunchCard(
            authority = authority,
            emulated = true,
            action = action,
            onPlay = { calls += "play" },
            onEdit = { calls += "edit" },
            onEditTitle = { calls += "edit-title" },
            onAchievements = { calls += "achievements" },
            onOpenWith = { calls += "open-with" },
            onClose = { calls += "close" },
        )

        dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(HomeLaunchCardOpenWithKey)
        dispatch(ControllerAction.CONFIRM)
        dispatch(ControllerAction.TERTIARY)
        dispatch(ControllerAction.SYSTEM_SELECT)
        authority.requestFocus(HomeLaunchCardTitleEditKey)
        dispatch(ControllerAction.CONFIRM)
        dispatch(ControllerAction.CANCEL)

        assertEquals(
            listOf("play", "open-with", "edit", "achievements", "edit-title", "close"),
            calls,
        )
    }

    private fun homeAuthority(): DefaultFocusAuthority = DefaultFocusAuthority()
}
