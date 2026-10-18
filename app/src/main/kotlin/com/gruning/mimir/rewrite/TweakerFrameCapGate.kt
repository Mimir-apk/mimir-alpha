package com.gruning.mimir.rewrite

/**
 * The per-game frame cap is withheld from Alpha.
 *
 * the feature is too finnicky to ship, so it is gated off rather than
 * deleted — the policy in [TweakerFpsCapRowPolicy] and its tests stay, and this flips when the
 * behaviour is stable and has device proof behind it.
 *
 * The gate is read at BOTH declaration sites the row has — the render in `BrightnessOverlay` and the
 * focus order in `brightnessControls` — because gating only one of them is precisely the
 * render-vs-focus-position defect this rail keeps producing: a row drawn but unreachable, or reachable
 * but never drawn, either of which compiles clean and passes every test.
 *
 * Not a `const`: a compile-time false would fold the call sites away and cost the "condition is always
 * false" warnings that make the gate look like dead code rather than a decision.
 */
internal object TweakerFrameCapGate {
    val enabled: Boolean = false
}
