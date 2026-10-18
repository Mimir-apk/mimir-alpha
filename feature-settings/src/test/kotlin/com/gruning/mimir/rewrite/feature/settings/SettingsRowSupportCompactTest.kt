package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the compact support-line gate: a settings row's support line moves [MimirType.RowSupport] (Body 14) ->
 * [MimirType.RowSupportCompact] (Label 11) once the Settings pane's OWN measured width drops under 500dp.
 * This differs from the same-size title/support pair only for small panes — see
 * `settingsRowSupportCompact`'s own KDoc (`SettingsComponents.kt`). If this file goes green without the
 * production gate, the reversal has been "fixed" back; that is the regression this file exists to catch.
 *
 * VIEWPORT PROVENANCE (same convention as `SettingsRc8GeometrySolverTest`, whose class KDoc explains why
 * this module hand-types the literals instead of sharing a cross-module test fixture):
 * - Nova pane = 437.5dp [M, spec §3a/§3b] — `settingsDetailPaneWidthDp(640f)` == 438.0f
 *   [V `SettingsRc8GeometrySolverTest.kt:150`, formula-identical within measurement rounding].
 * - Odin pane = 616.5dp [V `SettingsRc8GeometrySolverTest.kt:137`] — spec §3b also cites a 550dp Odin
 *   figure "under either RC-28 state" that this file does not re-derive; both stated Odin figures sit
 *   comfortably above the 500dp gate, so probing 550f and 616.5f both prove the Odin's zero-movement claim
 *   without hand-typing an unverified formula for the 550dp state.
 */
class SettingsRowSupportCompactTest {

    private val novaPaneWidthDp = 437.5f
    private val odinPaneWidthDpLower = 550f
    private val odinPaneWidthDpUpper = 616.5f

    // ── the gate itself ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `Nova pane at 437,5dp resolves compact`() {
        assertTrue(settingsRowSupportCompact(novaPaneWidthDp))
    }

    @Test
    fun `Odin pane at 550dp resolves Body 14, not compact`() {
        assertFalse(settingsRowSupportCompact(odinPaneWidthDpLower))
    }

    @Test
    fun `Odin pane at 616,5dp resolves Body 14, not compact`() {
        assertFalse(settingsRowSupportCompact(odinPaneWidthDpUpper))
    }

    @Test
    fun `boundary at exactly 500dp resolves to Body 14 -- the cliff is strictly-less-than`() {
        // settingsRowSupportCompact is `paneWidthDp < 500f`, so 500.0dp itself is NOT compact. Documented
        // here so a future `<=` "cleanup" is caught by this exact case.
        assertFalse(settingsRowSupportCompact(SettingsRowSupportCompactThresholdDp))
    }

    @Test
    fun `just under the boundary resolves compact`() {
        assertTrue(settingsRowSupportCompact(SettingsRowSupportCompactThresholdDp - 0.01f))
    }

    // ── tied to both certified devices via the pane-width solver, not a hand-typed literal ────────────
    @Test
    fun `gate applied to the certified Nova screen width resolves compact`() {
        assertTrue(settingsRowSupportCompact(settingsDetailPaneWidthDp(640f)))
    }

    @Test
    fun `gate applied to the certified Odin screen width resolves Body 14 -- zero movement`() {
        assertFalse(settingsRowSupportCompact(settingsDetailPaneWidthDp(832.5f)))
    }

    // ── the resolved style, not just the boolean -- what SettingsRow/SettingsInfoRow actually select ──
    @Test
    fun `compact pane resolves to Label 11, the RowSupportCompact role`() {
        val style = if (settingsRowSupportCompact(novaPaneWidthDp)) {
            MimirType.RowSupportCompact
        } else {
            MimirType.RowSupport
        }
        assertEquals(11f, style.fontSize.value, 0f)
    }

    @Test
    fun `Odin-width pane resolves to Body 14, the unmoved RowSupport role`() {
        listOf(odinPaneWidthDpLower, odinPaneWidthDpUpper).forEach { paneWidthDp ->
            val style = if (settingsRowSupportCompact(paneWidthDp)) {
                MimirType.RowSupportCompact
            } else {
                MimirType.RowSupport
            }
            assertEquals(14f, style.fontSize.value, 0f)
        }
    }
}
