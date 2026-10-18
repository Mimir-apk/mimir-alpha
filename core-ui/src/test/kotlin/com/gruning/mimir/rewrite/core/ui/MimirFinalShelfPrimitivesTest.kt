package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirFinalShelfPrimitivesTest {
    @Test
    fun quickRailActionsHaveFixedOrderRunesAndAccessibleLabelResources() {
        assertEquals(
            listOf(
                Triple(MimirQuickRailAction.HOME, "ᚺ", R.string.ui_dock_home),
                Triple(MimirQuickRailAction.LIBRARY, "ᛚ", R.string.ui_dock_library),
                Triple(MimirQuickRailAction.BRIGHTNESS, "ᛋ", R.string.ui_dock_utility),
                Triple(MimirQuickRailAction.SETTINGS, "ᚲ", R.string.ui_dock_settings),
                // Observed on Nova: the DOCK LABEL is "Apps" -- the
                // enum constant/dispatch/focus-key identity (QUICK_LAUNCH) is unchanged; only this
                // string moved. The Quick Launch picker’s own header ("ᛗ · QUICK LAUNCH") and every
                // other surface naming the feature are unchanged; their copy remains a separate design question.
                Triple(MimirQuickRailAction.QUICK_LAUNCH, "ᛏ", R.string.ui_dock_apps),
                Triple(MimirQuickRailAction.FILES, "\u16DF", R.string.ui_dock_files),
            ),
            MimirQuickRailAction.entries.map { Triple(it, it.rune, it.labelRes) },
        )
/** Every dock seat, including QUICK LAUNCH and FILES, shares the authored 88dp width. See ActionSlotWidth. */        assertEquals(528.dp, MimirRunicQuickRailDefaults.RailWidth)
        assertEquals(88.dp, MimirRunicQuickRailDefaults.ActionSlotWidth)
        assertEquals(
            264.dp,
            MimirRunicQuickRailDefaults.preferredRailWidth(
                listOf(
                    MimirQuickRailAction.HOME,
                    MimirQuickRailAction.LIBRARY,
                    MimirQuickRailAction.SETTINGS,
                ),
            ),
        )
        assertEquals(48.dp, MimirRunicQuickRailDefaults.MinActionDiameter)
        assertEquals(64.dp, MimirRunicQuickRailDefaults.ActionBoxDiameter)
        assertEquals(60.dp, MimirRunicQuickRailDefaults.MaxActionDiameter)
        // 64dp box + 22dp label band whose own leading is the gap: the rail stays 86dp so the
        // Library shelf's certified 444dp fixed-lane budget (441dp) is untouched by FUX-D.
        assertEquals(86.dp, MimirRunicQuickRailDefaults.RailHeight)
        // Was 10sp; the capped scale  has no step there, so this role sits on
        // Label. The 22dp label band above is UNCHANGED and is the thing to watch: a 1sp rise inside a
        // fixed band is cheap on a host assertion and is owed a device look before anyone calls the
        // rail's 86dp height re-certified.
        assertEquals(MimirTypeStep.Label.value, MimirTypeScale.RailAction.fontSize.value, 0f)
        // The label's own floor when the rail narrows -- the capped scale's lowest step, never an
        // invented size (see labelFontSizeMatchesTheCoinsExactGuardAtBothCertifiedViewports below).
        assertEquals(MimirTypeStep.Micro.value, MimirRunicQuickRailDefaults.MinLabelFontSize.value, 0f)
        assertTrue(
            MimirRunicQuickRailDefaults.MaxActionDiameter * MimirRunicQuickRailDefaults.FocusedScale <=
                MimirRunicQuickRailDefaults.ActionBoxDiameter,
        )
    }

    @Test
    fun everySeatScalesUniformlyAndStaysEqualBelowTheAuthoredWidth() {
        val authored = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, 700.dp)
        assertEquals(listOf(88.dp, 88.dp, 88.dp, 88.dp, 88.dp, 88.dp), authored)
        assertEquals(528.dp, authored.fold(0.dp) { width, slot -> width + slot })

        // overturned the differentiated-seat shrink rule this test used to pin
        // (QUICK LAUNCH floored at 96dp, FILES at 64dp, regular seats absorbing the rest -- the shape
        // that produced the uneven Nova gaps the regression report described). With one shared preferred width, uniform
        // scaling keeps every seat equal all the way down; this also happens to resolve the
        // PRE-EXISTING, spec-flagged missing-minTarget-floor defect the old pin documented (four regular
        // seats landing at 40dp, below MimirSpacing.minTarget) as a side effect, not a fix attempted here
        // -- at 320dp every seat now lands at ~53.3dp, comfortably above the 48dp floor.
        val constrained = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, 320.dp)
        assertEquals(320f, constrained.sumOf { it.value.toDouble() }.toFloat(), 0.001f)
        constrained.forEach { assertEquals(320f / 6f, it.value, 0.01f) } // all six seats, computed, equal
    }

    @Test
    fun endCapCoinIsFixedAt48dpRegardlessOfTheRailsSolvedDiameter() {
        // D-3: the end-cap's coin pins to the BOTTOM of the 48-60dp clamp every other coin resolves
        // inside -- never the rail's solved diameter, however wide the rail gets.
        assertEquals(48.dp, mimirQuickRailItemDiameter(MimirQuickRailAction.FILES, 60.dp))
        assertEquals(48.dp, mimirQuickRailItemDiameter(MimirQuickRailAction.FILES, 48.dp))
        // Every other action passes the resolved diameter through unchanged.
        assertEquals(60.dp, mimirQuickRailItemDiameter(MimirQuickRailAction.QUICK_LAUNCH, 60.dp))
        assertEquals(54.dp, mimirQuickRailItemDiameter(MimirQuickRailAction.HOME, 54.dp))
    }

    /** The rail target narrows with the viewport before physical fitting requires it, up to the authored 528dp ceiling. */
    @Test
    fun targetWidthKeepsTheOdinAtItsUnscaledPreferredWidthWithRealHeadroom() {
        // The exact certified Odin dp (OnboardingScreenFitTest's own fixture): 1920x1080@369dpi.
        val odinTarget = mimirQuickRailTargetWidth(832.5.dp)
        assertEquals(560.5f, odinTarget.value, 0.01f)
        assertTrue(
            "Odin's target must clear RailWidth with real headroom, not a float-rounding coincidence",
            odinTarget > MimirRunicQuickRailDefaults.RailWidth + 30.dp,
        )
        // Exact early-return behavior: mimirQuickRailSlotWidths's
        // own early-return path hands back the literal preferred list whenever it clears RailWidth.
        assertEquals(
            listOf(88.dp, 88.dp, 88.dp, 88.dp, 88.dp, 88.dp),
            mimirQuickRailSlotWidths(MimirQuickRailAction.entries, odinTarget),
        )
    }

    @Test
    fun targetWidthShrinksTheNovaWellBelowItsRawViewportAndEverySeatStaysEqual() {
        // Retroid Pocket Nova, 640dp -- a capture showed X · TOME against FILES. 82.5% of the
        // viewport (528dp raw) drops to 57.5% (368dp target). Unaffected by the 2026-08-21 equal-seat
        // override: 640-272=368 clears both the old (352dp) and new (288dp) structural floor either way.
        val novaTarget = mimirQuickRailTargetWidth(640.dp)
        assertEquals(368f, novaTarget.value, 0.01f)
        assertTrue(novaTarget < MimirRunicQuickRailDefaults.RailWidth)

        // every seat now lands on
        // the SAME computed width -- 368/6, above MinActionDiameter (48dp) so no coin becomes unhittable,
        // but visibly narrower than the 88dp authored seat. This is the exact bug the panel showed: the
        // OLD algorithm floored QUICK_LAUNCH at 96dp and FILES at 64dp here while the four regular seats
        // absorbed the rest at 52dp -- four evenly spaced seats then two drifting apart, byte-for-byte
        // the gap pattern the regression report described (104/104/104/148/160px).
        val widths = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, novaTarget)
        widths.forEach { assertEquals(368f / 6f, it.value, 0.01f) }
    }

    @Test
    fun targetWidthNeverDropsBelowItsStructuralFloorAndEverySeatStaysEqual() {
        // 538dp fixture: raw viewport minus 2xRailEndReserve would be 266dp, which the structural floor
        // (MinTargetRailWidth, re-derived 2026-08-21 from 352dp to 288dp -- six seats at the coin's own
        // 48dp hard floor, see MinTargetRailWidth's own KDoc) must catch -- going lower would push a
        // seat's coin below MinActionDiameter, an untappable dock (requirement, not a preference).
        val target = mimirQuickRailTargetWidth(538.dp)
        assertEquals(MimirRunicQuickRailDefaults.MinTargetRailWidth, target)
        assertEquals(288.dp, target) // the re-derived value, spelled out so a drift here fails loudly

        // Riding the floor exactly: every seat, not just two, lands at MinActionDiameter (48dp) -- the
        // narrowest any seat is ever asked to render, and still equal to its five neighbours.
        val widths = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, target)
        widths.forEach { assertEquals(48.dp, it) }
    }

    @Test
    fun targetWidthClampsToTheNewStructuralFloorAtTheMinimumSupportedViewport() {
        // 320dp is HomeShelfGeometry's own stated minimum (`require(widthDp >= 320f...)`). Before
        // 2026-08-21 this passed through UNCHANGED (MinTargetRailWidth was 352dp, WIDER than this
        // viewport, so the raw-viewport cap in mimirQuickRailTargetWidth's minOf won -- see
        // MimirCertifiedViewportMatrixTest's own retired comment on that exact inconsistency: a
        // "structural floor" wider than the narrowest viewport it was supposed to floor). The re-derived
        // 288dp floor is NARROWER than 320dp, so it now wins instead -- fixing that inconsistency, not
        // merely renumbering it: the target genuinely narrows here, one more real behavior change from
        // this override, reported rather than left for device review to discover.
        assertEquals(288.dp, mimirQuickRailTargetWidth(320.dp))
        val widths = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, mimirQuickRailTargetWidth(320.dp))
        widths.forEach { assertEquals(48.dp, it) } // matches everySeatScalesUniformlyAndStaysEqualBelow
        // TheAuthoredWidth's own 320dp-direct-call case above (53.3dp) only by coincidence of both being
        // near 320dp -- this one goes through the target-width clamp first and lands narrower (48dp).
    }

    @Test
    fun scaledDiameterNeverOvalsACoinAndRidesTheFloorAtTheNova() {
        // At the Nova, Home's model still solves quickActionDiameter=60dp (a HEIGHT-based, width-blind
        // formula in HomeShelfGeometry.kt, out of this module) -- wider than the coin has any business
        // being once the SLOT the coin sits in has itself narrowed (post-2026-08-21, every Nova seat is
        // ~61.3dp, not the pre-override regular seat's 52dp -- see targetWidthShrinksTheNova... above).
        // Unscaled, a 60dp circle inside a narrower fixed-size paint envelope would be coerced into an
        // oval by Compose's incoming-constraint clamp regardless of the exact slot width. The rail must
        // scale the coin down WITH the slot instead, floored at MinActionDiameter so it never becomes
        // unhittable. This is the arithmetic MimirRunicQuickRail's composable performs; pinned here with every coin at the 48dp floor.
        val railWidth = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, mimirQuickRailTargetWidth(640.dp))
            .fold(0.dp) { sum, slot -> sum + slot }
        val preferredWidth = MimirRunicQuickRailDefaults.preferredRailWidth(MimirQuickRailAction.entries)
        val scaled = (60.dp * (railWidth.value / preferredWidth.value))
            .coerceAtLeast(MimirRunicQuickRailDefaults.MinActionDiameter)
        assertEquals(48.dp, scaled)
    }

    @Test
    fun labelFontSizeMatchesTheCoinsExactGuardAtBothCertifiedViewports() {
        // The label must shrink WITH the rail exactly as the coin above does -- same railWidth/
        // preferredWidth pair, same exact `>=` guard, so the Odin's label renders at its untouched
        // authored 11sp (MimirTypeStep.Label) and only the Nova's narrowed rail engages the floor.
        val preferredWidth = MimirRunicQuickRailDefaults.preferredRailWidth(MimirQuickRailAction.entries)

        val odinWidth = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, mimirQuickRailTargetWidth(832.5.dp))
            .fold(0.dp) { sum, slot -> sum + slot }
        assertEquals(
            MimirTypeStep.Label.value,
            mimirQuickRailLabelFontSize(odinWidth, preferredWidth).value,
            0f,
        )

        val novaWidth = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, mimirQuickRailTargetWidth(640.dp))
            .fold(0.dp) { sum, slot -> sum + slot }
        // 11sp * (368/528) = 7.67sp, below MimirTypeStep.Micro (9sp) -- the floor engages, and every
        // seat (including QUICK LAUNCH/FILES, whose own slots are proportionally wider) lands on the
        // SAME 9sp: one shared rail-level ratio, not a per-slot one -- see mimirQuickRailLabelFontSize's
        // own doc for why a per-slot ratio would put three different label sizes on one dock.
        assertEquals(
            MimirTypeStep.Micro.value,
            mimirQuickRailLabelFontSize(novaWidth, preferredWidth).value,
            0f,
        )
    }

    @Test
    fun labelFontSizeStaysAtTheSameFloorAcrossEveryNarrowedViewport() {
        // Two arbitrary widths well below the 528dp authored total -- 352dp is no longer THE structural
        // floor (MinTargetRailWidth was re-derived to 288dp on 2026-08-21, see its own KDoc), just a
        // width that still exercises the scaled branch -- and 320dp (HomeShelfGeometry's own stated
        // minimum, see everySeatScalesUniformlyAndStaysEqualBelowTheAuthoredWidth above) both narrow the
        // rail well past the Nova; the label floor is the type scale's own lowest step regardless of how
        // far below preferredWidth the rail lands -- there is nothing smaller to fall back to, so it
        // never goes negative or below Micro.
        val preferredWidth = MimirRunicQuickRailDefaults.preferredRailWidth(MimirQuickRailAction.entries)
        listOf(352.dp, 320.dp).forEach { width ->
            val railWidth = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, width)
                .fold(0.dp) { sum, slot -> sum + slot }
            assertEquals(
                MimirTypeStep.Micro.value,
                mimirQuickRailLabelFontSize(railWidth, preferredWidth).value,
                0f,
            )
        }
    }

    @Test
    fun everySeatIsEqualNotMonotoneAtTheCompanionViewport() {
        // §6.2's worked example, the 538dp companion viewport's Library host (538 - 2×20dp gutter =
        // 498dp available), used to be the proof of a DELIBERATE monotone shape (design spec §5):
        // 88,88,88,88,104,72 authored -> ~83.0/~98.1/~67.9 solved, end-cap narrowest, QUICK LAUNCH
        // widest. overturned that shape -- monotone was the visible symptom of
        // the uneven-gap defect the regression report described, not a feature to keep proving. Same viewport, new truth:
        // every seat computes to the SAME 83.0dp (498/6, no floor engaged, exact).
        val widths = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, 498.dp)
        widths.forEach { assertEquals(83f, it.value, 0.01f) } // computed, not floor-pinned -- a tolerance
        assertEquals(498f, widths.sumOf { it.value.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun threeNamedThemesTakeTheMoltenDockPlinthAndNoOthersDo() {
        // Two until 2026-09-22, when Fólkvangr also gained the plinth. The set is asserted
        // whole rather than by membership: the plinth is a per-theme DECISION, and a theme drifting into
        // it because someone copied a separator block is the failure this has always guarded against.
        val molten = MimirThemePalette.all.filter(::mimirQuickRailShowsMoltenSeparator).map { it.id }.toSet()
        assertEquals(
            setOf(MimirThemeId.SURTR, MimirThemeId.NIFLHEIM, MimirThemeId.FOLKVANGR),
            molten,
        )
    }

    @Test
    fun selectedDestinationStaysQuietUntilItActuallyOwnsFocus() {
        val selectedOnly = mimirQuickRailEmphasis(selected = true, focused = false)
        assertFalse(selectedOnly.usesSolidAccent)
        assertFalse(selectedOnly.usesFocusRing)
        // FUX-D (panel §5): labels are PERSISTENT — discoverability never depends on focus.
        assertTrue(selectedOnly.showsLabel)
        assertTrue(selectedOnly.usesDestinationInk)

        val focused = mimirQuickRailEmphasis(selected = false, focused = true)
        assertTrue(focused.usesSolidAccent)
        assertTrue(focused.usesFocusRing)
        assertTrue(focused.showsLabel)
        assertFalse(focused.usesDestinationInk)
    }

    @Test
    fun quickRailDiameterRespondsWithinStableTargetBounds() {
        assertEquals(48.dp, mimirQuickRailDiameter(320.dp))
        assertEquals(54.dp, mimirQuickRailDiameter(540.dp))
        assertEquals(60.dp, mimirQuickRailDiameter(1_080.dp))
    }

    @Test
    fun finalShelfMotionMatchesReferenceAndNeverDelaysInput() {
        assertEquals(140, MimirMotion.FocusMs)
        assertEquals(160, MimirMotion.HeroContentMs)
        assertEquals(160, MimirMotion.SceneMs)
        assertEquals(180, MimirMotion.OverlayMs)
        assertTrue(MimirMotion.OverlayMs <= 180)
        assertEquals(0, MimirMotion.InputDelayMs)
        assertEquals(MimirMotion.SceneMs, MimirMotion.ModeTransitionMs)
        assertEquals(MimirMotion.FocusMs, MimirFocusRingDurationMs)
    }

    @Test
    fun hintToastRailMeetsContrastContractAcrossEveryPalette() {
        MimirThemePalette.all.forEach { palette ->
            val colors = mimirHintToastRailColors(palette)
            val textContrast = mimirContrastRatio(colors.content, colors.background)
            assertTrue("${palette.id} text contrast was $textContrast", textContrast >= 7.0)

            listOf(
                palette.surfaceBase,
                palette.surfaceRaised,
                palette.surfaceHigh,
                palette.signatureTop,
                palette.signatureBottom,
            ).forEach { sceneColor ->
                val outlineContrast = mimirContrastRatio(colors.outline, sceneColor)
                assertTrue(
                    "${palette.id} outline contrast was $outlineContrast",
                    outlineContrast >= 3.0,
                )
            }
        }
    }

    @Test
    fun everyReleaseThemeUsesItsDarkMessageMaterial() {
        MimirThemePalette.all.forEach { palette ->
            assertEquals(palette, mimirMessageCardPalette(palette))
        }
    }

    @Test
    fun focusedRuneInkSurvivesTheFocusWashOnEveryPalette() {
        // The focused seat is no longer a solid molten disc. Its mark sits on the dock's
        // own field under the ring's faint accent wash, so the ink is proven against that composite, on every
        // field tone the dock can stand on, at the normal-text 4.5:1 floor rather than the old 3:1 large-glyph one.
        MimirThemePalette.all.forEach { palette ->
            val ink = mimirQuickRailFocusedRuneInk(palette)
            val wash = palette.accent.copy(alpha = MimirRunicQuickRailDefaults.FocusWashAlpha)
            val fields = listOf(palette.surfaceBase, palette.surfaceRaised, palette.signatureTop, palette.signatureBottom)
            // Over light media the seat keeps its quiet disc (controlBottom at 0.84), so that is a ground too.
            val mediaDisc = palette.materials.controlBottom.copy(alpha = 0.84f)
            val grounds = fields + fields.map { field -> mediaDisc.compositeOver(field) }
            grounds.forEach { ground ->
                val contrast = mimirContrastRatio(ink, wash.compositeOver(ground))
                assertTrue("${palette.id} focused rune ink contrast was $contrast", contrast >= 4.5)
            }
        }
    }

    @Test
    fun unfocusedMarksReadOnTheBareFieldAndOnTheMediaDisc() {
        // The seats paint no disc of their own any more, so a resting mark (textSecondary) stands on the field,
        // or on the media disc over light media. Icons, so the 3:1 non-text floor.
        MimirThemePalette.all.forEach { palette ->
            val fields = listOf(palette.surfaceBase, palette.surfaceRaised, palette.signatureTop, palette.signatureBottom)
            val mediaDisc = palette.materials.controlBottom.copy(alpha = 0.84f)
            (fields + fields.map { field -> mediaDisc.compositeOver(field) }).forEach { ground ->
                val contrast = mimirContrastRatio(palette.textSecondary, ground)
                assertTrue("${palette.id} resting mark contrast was $contrast", contrast >= 3.0)
            }
        }
    }

    @Test
    fun hintToastRailStaysInsideReservedBand() {
        assertEquals(32.dp, mimirHintToastRailHeight(360.dp))
        assertEquals(36.dp, mimirHintToastRailHeight(540.dp))
        assertTrue(MimirHintToastRailDefaults.CompactHeight >= 28.dp)
        assertTrue(MimirHintToastRailDefaults.ExpandedHeight <= 36.dp)
    }

    @Test
    fun duplicateToastDoesNotExtendWindowAndNewToastInterrupts() {
        val deduplicator = MimirToastDeduplicator()

        val first = deduplicator.offer(message = "Saved", nowMs = 100L)
        assertTrue(first.accepted)
        assertEquals(2_500L, first.expiresAtMs)

        val duplicate = deduplicator.offer(message = "Saved", nowMs = 1_000L)
        assertFalse(duplicate.accepted)
        assertEquals(first.expiresAtMs, duplicate.expiresAtMs)

        val replacement = deduplicator.offer(message = "Removed", nowMs = 1_100L)
        assertTrue(replacement.accepted)
        assertEquals(3_500L, replacement.expiresAtMs)
        assertTrue(deduplicator.isCurrent(replacement))
        assertEquals(replacement.copy(accepted = false), deduplicator.current(nowMs = 2_000L))

        val replay = deduplicator.offer(message = "Removed", nowMs = replacement.expiresAtMs)
        assertTrue(replay.accepted)
        assertEquals(5_900L, replay.expiresAtMs)
    }

    @Test
    fun clearLogoAcceptsOnlyLocalSchemesAndUsesWideGeometry() {
        assertTrue(isMimirLocalArtScheme("content"))
        assertTrue(isMimirLocalArtScheme("FILE"))
        assertTrue(isMimirLocalArtScheme("android.resource"))
        assertFalse(isMimirLocalArtScheme("https"))
        assertFalse(isMimirLocalArtScheme("http"))
        assertFalse(isMimirLocalArtScheme(null))
        assertEquals(3f, MimirFlatLogoArtDefaults.AspectRatio)
        assertTrue(MimirFlatLogoArtDefaults.AspectRatio > 1f)
    }
}
