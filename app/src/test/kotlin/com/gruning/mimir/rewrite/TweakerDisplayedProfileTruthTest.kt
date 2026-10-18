package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a surface may display as the live profile.
 *
 * The performance overlay used to render `TweakerBridge.selectedProfileId()` — the stored pick. That
 * pref goes stale in one specific, misleading direction: `normalizeStoredSelection` bails whenever
 * `capabilities` is null, so when the Tweaker service dies the pref keeps naming a profile the hardware
 * is no longer running.
 *
 * Device-proven 2026-08-10 on Odin3: with `balanced` dropped to Stock (`gpumax` 2 → 0, cpu ceiling
 * 1996800 → 3532800), the projection logged `selected=stock` while `KEY_PROFILE` still read `balanced`,
 * and the overlay went on showing the Balanced glyph over Stock silicon. Screenshots taken before,
 * during and after the drop were pixel-identical, which is also why the visual evidence plan could not
 * certify the re-arm fix.
 *
 * The display truth is [TweakerUtilityRowState.effectiveProfileId] — one definition, shared with the
 * Utility row and the standing re-arm watcher.
 */
class TweakerDisplayedProfileTruthTest {

    private fun row(
        selected: String,
        availability: TweakerUtilityAvailability = TweakerUtilityAvailability.READY,
        selectable: List<String> = listOf("stock", "quiet", "balanced", "performance"),
    ) = TweakerUtilityRowState(
        availability = availability,
        selectedProfileId = selected,
        selectableProfileIds = selectable,
    )

    @Test
    fun `a live certified profile is displayed`() {
        assertEquals("balanced", row("balanced").effectiveProfileId)
    }

    /**
     * The reported defect. The service is gone, so the row reads UNAVAILABLE and nothing can confirm a
     * profile — the surface must say Stock rather than keep flying the player's last pick.
     */
    @Test
    fun `a dead service is never displayed as a live profile`() {
        assertEquals(
            "stock",
            row(
                selected = "balanced",
                availability = TweakerUtilityAvailability.UNAVAILABLE,
                selectable = listOf("stock"),
            ).effectiveProfileId,
        )
    }

    @Test
    fun `an uninstalled Tweaker is never displayed as a live profile`() {
        assertEquals(
            "stock",
            row(
                selected = "balanced",
                availability = TweakerUtilityAvailability.NOT_INSTALLED,
                selectable = listOf("stock"),
            ).effectiveProfileId,
        )
    }

    /** Hardware that answered and declined tuning is Stock, whatever the stored pick says. */
    @Test
    fun `stock-only hardware is never displayed as a live profile`() {
        assertEquals(
            "stock",
            row(
                selected = "balanced",
                availability = TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            ).effectiveProfileId,
        )
    }

    /**
     * A stored pick that is no longer certified is not applied at launch either, so displaying it would
     * advertise a mode the device will refuse.
     */
    @Test
    fun `a decertified pick is not displayed`() {
        assertEquals(
            "stock",
            row(selected = "balanced", selectable = listOf("stock", "quiet")).effectiveProfileId,
        )
    }

    @Test
    fun `stock displays as stock`() {
        assertEquals("stock", row("stock").effectiveProfileId)
    }
}
