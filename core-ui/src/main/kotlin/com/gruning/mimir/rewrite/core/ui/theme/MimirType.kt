package com.gruning.mimir.rewrite.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale

/**
 * MIMIR type tokens — now a THIN ALIAS over the `:core-ui` [MimirTypeScale].
 *
 * The type ramp is theme-INVARIANT (only color re-skins per theme), so these are plain aliases onto the
 * single core-ui scale — one source of truth for sizes/weights, keeping the existing `MimirType.X` call
 * sites unchanged. Names map 1:1 (the sizes are identical to the previous local ramp):
 *   RailItem → Rail, RowTitle → Row, RowSupport → Support; Title/Eyebrow/Pill are direct.
 */
object MimirType {
    /** Screen / hero title. */
    val Title: TextStyle = MimirTypeScale.Title
    /** Static capped screen/panel title. */
    val TitleCaps: TextStyle = MimirTypeScale.TitleCaps
    /** Square-safe static capped title. */
    val TitleCapsCompact: TextStyle = MimirTypeScale.TitleCapsCompact
    /** Section eyebrow — small, tracked, accent-colored in use. */
    val Eyebrow: TextStyle = MimirTypeScale.Eyebrow
    /** A rail category label. */
    val RailItem: TextStyle = MimirTypeScale.Rail
    /** A settings row's primary label. */
    val RowTitle: TextStyle = MimirTypeScale.Row
    /**
     * Action-row title lane — was RowTitle at a bespoke 13sp.
     *
     * That 13sp existed to match the `HERO MOTION` row back when [MimirTypeScale.Row] was 15sp and
     * [MimirTypeScale.Support] was 13sp. Under the capped scale  both sit on
     * one Body step, so the bespoke size is not a "tighter role" any more — it is a nineteenth size
     * punching through the cap. It now IS RowTitle, matching the shared row role.
     */
    val SettingsActionTitle: TextStyle = RowTitle
    /** Library/storage receipt values use the same normalized Settings scale, without uppercasing. */
    val SettingsReceiptValue: TextStyle = RowTitle
    /** A settings row's supporting/secondary line. */
    val RowSupport: TextStyle = MimirTypeScale.Support
    /**
     * the SMALL-PANE form of [RowSupport], used only when the
     * Settings pane's own measured width is under `SettingsRowSupportCompactThresholdDp` (500dp) — see
     * `settingsRowSupportCompact` in `SettingsComponents.kt`. This is a DELIBERATE, SCOPED REVERSAL of the
     * 2026-08-11 same-size pairing ([RowSupport] == [RowTitle]) at small panes only; the pair still
     * matches everywhere at/above the threshold, including the Odin's 616.5dp pane. Do not delete this
     * role to "restore" the same-size pairing — the pairing remains in force outside small panes.
     */
    val RowSupportCompact: TextStyle = MimirTypeScale.SupportCompact
    /** A status pill / chip. */
    val Pill: TextStyle = MimirTypeScale.Pill
    /** Release-alpha static menu/section header. Never use for authored or provider text. */
    val RunicEyebrow: TextStyle = MimirTypeScale.RunicEyebrow
    /** Square-safe release-alpha static header. */
    val RunicEyebrowCompact: TextStyle = MimirTypeScale.RunicEyebrowCompact
    /** Capped controller legend. */
    val ControlHint: TextStyle = MimirTypeScale.ControlHint
    /** Square-safe capped controller legend. */
    val ControlHintCompact: TextStyle = MimirTypeScale.ControlHintCompact
    /** Compact state, counter, and identifier text. */
    val MicroStatus: TextStyle = MimirTypeScale.MicroStatus
    /** Square-safe compact state text: the same 9 sp with the tracking trimmed (0.45 sp), for a word that must fit a 59 dp tile at a raised font scale. */
    val MicroStatusCompact: TextStyle = MimirTypeScale.MicroStatusCompact
    /** Full-caps menu/action text. */
    val MenuAction: TextStyle = MimirTypeScale.MenuAction
    /** Square-safe full-caps menu/action text. */
    val MenuActionCompact: TextStyle = MimirTypeScale.MenuActionCompact
    /** Persistent centered command-rail label. */
    val RailAction: TextStyle = MimirTypeScale.RailAction
    /** Dynamic modal title that preserves authored casing. */
    val OverlayTitle: TextStyle = MimirTypeScale.OverlayTitle
}
