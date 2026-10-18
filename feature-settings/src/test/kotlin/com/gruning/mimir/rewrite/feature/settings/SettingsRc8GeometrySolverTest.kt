package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay

import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings geometry coverage — pins the rail/margin/modal-cap solver added to `SettingsHome.kt`. Rail width
 * and the B1 modal cap stay width-keyed; the detail-pane edge margin no longer is as of the 2026-08-21
 * policy retiring its xl/xxl split (see `settingsDetailEdgeMarginDp`'s own KDoc) — this file's tests
 * for that function now pin a width-invariant value, not a threshold branch.
 *
 * VIEWPORT PROVENANCE: `feature-settings/src/test` cannot reach `MimirCertifiedViewports` — RC-1 landed
 * it as an `internal` fixture duplicated verbatim into `core-ui`/`feature-library`/`app`'s own test trees
 * only (no cross-module test-fixture wiring exists in this build, per that file's own KDoc), so this file does not duplicate the fixture. It lists exactly
 * the width/height literals load-bearing here, cross-checked against the existing copy:
 * - Nova = 640.0 x 480.0dp, STANDARD posture [V core-ui/src/test/.../MimirCertifiedViewports.kt:118,124,
 *   `RetroidPocketNova.fullViewportDp`/`.posture`]
 * - Odin = 832.5 x 468.3dp, STANDARD posture [V core-ui/src/test/.../MimirCertifiedViewports.kt:99,105,
 *   `Odin.fullViewportDp`/`.posture`]
 * - CompactSquare480 = 480.0 x 480.0dp, COMPACT_SQUARE posture [V core-ui/src/test/.../
 *   MimirCertifiedViewports.kt:158-176, `compactSquareRow`], used only to pin the B1 posture guard below,
 *   not asserted anywhere as a real Settings width beyond the posture it produces.
 * RP5's width is UNKNOWN for this screen: `Rp5Shelf.fullViewportDp` is null in that same fixture (only a
 * shelf-specific `postInsetShelfDp` of 700x350dp exists, and Settings has no established post-inset box of
 * its own). 700x350dp is used below ONLY as a third, above-threshold, STANDARD-posture data point to prove
 * the solver doesn't misbehave off the two named devices — never asserted as the real RP5 Settings box.
 */
class SettingsRc8GeometrySolverTest {

    private val novaWidthDp = 640f
    private val novaHeightDp = 480f // STANDARD: aspect 1.333 > CompactSquareMaximumAspect (1.12).
    private val odinWidthDp = 832.5f
    private val odinHeightDp = 468.3f // STANDARD: aspect 1.777.
    private val rp5ProbeWidthDp = 700f // UNKNOWN as a real Settings width -- see class KDoc.
    private val rp5ProbeHeightDp = 350f // STANDARD: aspect 2.0.
    private val compactSquareProbeSideDp = 480f // COMPACT_SQUARE: aspect 1.0, side 480 <= 760.

    // ── A3: the shared threshold itself ─────────────────────────────────────────────────────────────
    @Test
    fun `narrow threshold is the 4-3 spec's proposed Library search-compact cliff value`() {
        assertEquals(680f, SettingsNarrowThresholdWidthDp, 0f)
    }

    // ── A1: rail width ───────────────────────────────────────────────────────────────────────────────
    @Test
    fun `rail width at the Nova is floored to 154dp -- the uncoerced 140-8dp clips APPEARANCE`() {
        // Corrected 2026-08-21 (device regression, build 54ff6193: "APPEARAN..." clipped on the Nova).
        // The uncoerced 0.22 * 640 = 140.8dp formula value is BELOW the true 146dp minimum (82dp ink +
        // 64dp column/item chrome -- see settingsRailWidthDp's own KDoc) -- the floor below (154f) rescues
        // it, same as it did before this fix, just at a value that actually clears the label this time.
        assertTrue(0.22f * novaWidthDp < 146f)
        assertEquals(154f, settingsRailWidthDp(novaWidthDp), 0.01f)
    }

    @Test
    fun `rail width at the Odin is pinned to its pre-existing 168dp -- zero movement`() {
        assertEquals(168f, settingsRailWidthDp(odinWidthDp), 0.01f)
    }

    @Test
    fun `rail width formula would exceed 168dp uncoerced, proving the ceiling is load-bearing`() {
        assertTrue(0.22f * odinWidthDp > 168f)
    }

    @Test
    fun `rail width never drops below the 154dp APPEARANCE label floor`() {
        // 154dp = 146dp true minimum (82dp ink + 64dp column/item chrome) + 8dp margin. See
        // settingsRailWidthDp's own KDoc for the full chain and why the old 132dp floor clipped.
        assertEquals(154f, settingsRailWidthDp(1f), 0.01f)
    }

    // THE pin this ticket exists to add: not a value pin (132 -> 154 is just as replaceable a literal as
    // the old one), but the PROPERTY the old 132dp floor violated -- the label's lane (rail width minus
    // the 64dp column/item chrome, see settingsRailWidthDp's own KDoc) must clear its ink at every width
    // the solver can return. A future floor change that regresses this fails HERE, by construction, not by
    // shipping and getting caught on a device screenshot again. Solver is monotone non-decreasing in width
    // (coerceIn of a positive-slope line), so probing the floor case (1f) plus every certified width below
    // covers the whole range; nothing above the Odin needs a separate probe since the floor only ever binds
    // going down.
    @Test
    fun `longest rail label lane clears its ink at every width the solver can return`() {
        val inkDp = 82f // "APPEARANCE" -- see settingsRailWidthDp's own KDoc for the ground-truthed figure.
        val chromeDp = 64f // 24 column-start + 8 column-end + 32 item horizontal padding, both sides.
        listOf(1f, novaWidthDp, rp5ProbeWidthDp, odinWidthDp).forEach { widthDp ->
            val lane = settingsRailWidthDp(widthDp) - chromeDp
            assertTrue("lane=$lane must clear ink=$inkDp at widthDp=$widthDp", lane >= inkDp)
        }
    }

    // ── A2: detail-pane edge margin ──────────────────────────────────────────────────────────────────
    @Test
    fun `edge margin is xl below the threshold`() {
        assertEquals(MimirSpacing.xl, settingsDetailEdgeMarginDp(novaWidthDp))
    }

    @Test
    fun `Odin uses xl edge margin and gains 8dp on the right`() {
        // Was xxl (32dp) before the margin change. The Odin-parity harness's settings.marginSymmetry check flagged the
        // NOVA red for being symmetric (24/24) against an asymmetric Odin baseline (24/32); the reference device was made symmetric too. See settingsDetailEdgeMarginDp's own KDoc.
        assertEquals(MimirSpacing.xl, settingsDetailEdgeMarginDp(odinWidthDp))
    }

    @Test
    fun `edge margin is width-invariant -- the threshold branch is retired from this function`() {
        // Pre-ruling this pinned "at-or-above threshold reads xxl, below reads xl"; the branch is gone,
        // so both sides of SettingsNarrowThresholdWidthDp now read the same xl value. The constant itself
        // survives for B1 (settingsModalWidthCapDp) below -- see its own KDoc.
        assertEquals(MimirSpacing.xl, settingsDetailEdgeMarginDp(SettingsNarrowThresholdWidthDp))
        assertEquals(
            MimirSpacing.xl,
            settingsDetailEdgeMarginDp(SettingsNarrowThresholdWidthDp - 0.01f),
        )
    }

    @Test
    fun `Nova edge margin now matches the rail's own hardcoded 24dp start -- asymmetry repaired`() {
        // Rail's start is hardcoded MimirSpacing.xl (SettingsRailPane call site, unchanged by this pass).
        // Before this change, SettingsDetailPane's start-end.dp were both xxl (32dp) unconditionally, so
        // the Nova's left screen margin (24) and right screen margin (32) disagreed. After: both 24dp.
        assertEquals(MimirSpacing.xl, settingsDetailEdgeMarginDp(novaWidthDp))
    }

    // ── detail pane / section card width (STANDARD posture only -- see class KDoc) ──────────────────
    @Test
    fun `Odin card width grows 16dp with the xl edge margin`() {
        // The previous independent measurement was 600.5dp. With 24dp detail margins:
        // 832.5 - 168 - 2*24 = 616.5dp, +16dp from the earlier 32/32 margins.
        // This does not reach settingsModalWidthCapDp on Odin; see that function's KDoc.
        assertEquals(616.5f, settingsDetailPaneWidthDp(odinWidthDp), 0.01f)
    }

    @Test
    fun `card width at the Nova grows from the pre-existing 408dp toward the Tier A target`() {
        // Pre-Tier-A (fixed 168dp rail, xxl-xxl margins): 640 - 168 - 64 = 408dp (matches R2-5's own
        // "408.0dp" measurement). Post-Tier-A, post rail-floor-fix (this ticket, 2026-08-21): the Nova's
        // rail is now floored to 154dp (was 140.8dp uncoerced -- that value clipped APPEARANCE, see
        // settingsRailWidthDp's own KDoc), so the card is 640 - 154 - 48 = 438.0dp -- 13.2dp narrower than
        // the pre-fix 451.2dp. That narrowing is the real cost of the clip fix, not noise: more rail width
        // for the label means less width for the detail card at the same viewport.
        val preTierA = novaWidthDp - 168f - 2 * MimirSpacing.xxl.value
        assertEquals(408f, preTierA, 0.01f)
        assertEquals(438f, settingsDetailPaneWidthDp(novaWidthDp), 0.01f)
    }

    // ── B1: modal width cap ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `modal cap at the Odin is unchanged for every authored cap in this screen -- zero movement`() {
        // The Odin (832.5dp) sits at/above SettingsNarrowThresholdWidthDp, so the gate's card-bounding
        // branch is never taken here at all -- it returns authoredCapDp untouched regardless of the
        // card's own width (616.5dp post-ruling), proven per-value, not asserted once.
        assertEquals(600f, settingsModalWidthCapDp(odinWidthDp, odinHeightDp, authoredCapDp = 600f), 0.01f)
        assertEquals(640f, settingsModalWidthCapDp(odinWidthDp, odinHeightDp, authoredCapDp = 640f), 0.01f)
        assertEquals(720f, settingsModalWidthCapDp(odinWidthDp, odinHeightDp, authoredCapDp = 720f), 0.01f)
    }

    @Test
    fun `modal cap at the Nova is bounded by the card for MimirSelectOverlay's 600dp authored cap`() {
        // 438.0dp post rail-floor-fix (was 451.2dp) -- the card narrowed when the rail widened to clear
        // APPEARANCE. See `card width at the Nova grows...` above for the full accounting.
        assertEquals(438f, settingsModalWidthCapDp(novaWidthDp, novaHeightDp, authoredCapDp = 600f), 0.01f)
    }

    @Test
    fun `modal cap at the Nova is bounded by the card for ControllerMappingOverlay's 720dp authored cap`() {
        assertEquals(438f, settingsModalWidthCapDp(novaWidthDp, novaHeightDp, authoredCapDp = 720f), 0.01f)
    }

    @Test
    fun `modal cap resolves the MimirColorDial 640dp knife-edge -- no longer equals the Nova's own width`() {
        val capped = settingsModalWidthCapDp(novaWidthDp, novaHeightDp, authoredCapDp = 640f)
        assertEquals(438f, capped, 0.01f)
        assertTrue("cap must no longer sit exactly on the viewport edge", capped < novaWidthDp)
    }

    // ── B1 posture guard: COMPACT_SQUARE never gets the STANDARD-layout card math ───────────────────
    @Test
    fun `modal cap on a below-threshold COMPACT_SQUARE viewport is NOT narrowed by the wide-layout card`() {
        // At width 480 (below the 680 threshold) the STANDARD-layout formula would compute a card of
        // 480 - 154 - 48 = 278dp (rail floored to 154dp post rail-floor-fix, this ticket -- was
        // 480 - 132 - 48 = 300dp pre-fix) -- but SettingsHome's compactSquare branch never renders that
        // rail+detail Row at all (single full-bleed pane, MimirSpacing.lg margins), so that 278dp describes
        // nothing on this posture. The gate must fall through to the authored cap unchanged, same as
        // at/above the threshold.
        assertEquals(
            278f,
            settingsDetailPaneWidthDp(compactSquareProbeSideDp),
            0.01f,
        ) // sanity: confirms the STANDARD-only formula WOULD shrink here if not guarded.
        assertEquals(
            600f,
            settingsModalWidthCapDp(compactSquareProbeSideDp, compactSquareProbeSideDp, authoredCapDp = 600f),
            0.01f,
        )
        assertEquals(
            640f,
            settingsModalWidthCapDp(compactSquareProbeSideDp, compactSquareProbeSideDp, authoredCapDp = 640f),
            0.01f,
        )
        assertEquals(
            720f,
            settingsModalWidthCapDp(compactSquareProbeSideDp, compactSquareProbeSideDp, authoredCapDp = 720f),
            0.01f,
        )
    }

    // ── third data point (RP5 probe, >= threshold, STANDARD): the solver takes the Odin-shaped branch ──
    @Test
    fun `at the above-threshold RP5 probe box, every authored cap is returned unchanged`() {
        // Edge margin is xl here too post-ruling (width-invariant now) -- this assertion is no longer
        // about the threshold branch, only a sanity check that the probe box still reads the one value
        // this function ever returns.
        assertEquals(MimirSpacing.xl, settingsDetailEdgeMarginDp(rp5ProbeWidthDp))
        assertEquals(
            600f,
            settingsModalWidthCapDp(rp5ProbeWidthDp, rp5ProbeHeightDp, authoredCapDp = 600f),
            0.01f,
        )
        assertEquals(
            640f,
            settingsModalWidthCapDp(rp5ProbeWidthDp, rp5ProbeHeightDp, authoredCapDp = 640f),
            0.01f,
        )
        assertEquals(
            720f,
            settingsModalWidthCapDp(rp5ProbeWidthDp, rp5ProbeHeightDp, authoredCapDp = 720f),
            0.01f,
        )
    }
}
