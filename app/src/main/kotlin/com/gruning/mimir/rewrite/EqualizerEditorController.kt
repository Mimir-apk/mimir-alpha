package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback

/**
 * One open equalizer editor's input. Created when Settings opens the editor with the app's [authority] and discarded
 * ([dispose]) when it closes; it owns the editor's focus scope for that time and no state of its own: the settings it edits are the
 * engine's ([EqualizerEditorEngine.settings]), every step is applied and stored at once, and closing keeps what was set.
 *
 * Touch calls the `on...` methods directly; the controller goes through [dispatch]. A hard modal: every action is consumed.
 *
 * While the Equalizer is off (see [equalizerEditorLive]) only its switch is reachable: the other rows are dimmed and the D-pad skips
 * them.
 */
internal class EqualizerEditorController(
    private val engine: EqualizerEditorEngine,
    val authority: FocusAuthority,
    private val onClose: () -> Unit,
) {
    /** The settings the editor shows: the engine's own flow. */
    val settings get() = engine.settings

    /** The global engine the editor reports on: what the effect is honestly doing about the switch. */
    val systemEq get() = engine.systemEq

    private val enabled: Boolean get() = engine.settings.value.enabled

    /** The curve is audible somewhere: its sliders and preset are reachable. */
    private val live: Boolean get() = equalizerEditorLive(engine.settings.value)

    init {
        authority.rememberFocusForScope(EqualizerEditorScope, equalizerEditorFocusKey(EqualizerEditorControl.TOGGLE))
        authority.pushScope(EqualizerEditorScope)
    }

    fun focusedControl(): EqualizerEditorControl? = equalizerEditorControlFor(authority.state.value.focused)

    // ---- touch and A: the same actions ----

    fun requestFocus(control: EqualizerEditorControl) {
        if (control in equalizerEditorReachable(live)) authority.requestFocus(equalizerEditorFocusKey(control))
    }

    fun onToggle() {
        val next = !enabled
        engine.setEnabled(next)
        // Off leaves only the switch reachable: the ring is on it already (it is where A was pressed), and stays.
        if (!next) authority.requestFocus(equalizerEditorFocusKey(EqualizerEditorControl.TOGGLE))
    }

    /**
     * X on the switch while Android has refused the global engine: ask again, from scratch. (A and a tap switch the Equalizer off, and
     * on again asks the platform afresh.) Returns whether it asked. With the Equalizer off, or the engine in any other state, X does
     * nothing here.
     */
    fun retryEngine(): Boolean {
        if (focusedControl() != EqualizerEditorControl.TOGGLE || !enabled) return false
        if (engine.systemEq.value.status != SystemEqStatus.UNAVAILABLE) return false
        engine.retrySystemEq()
        return true
    }

    /** One preset the cycle steps [direction]. Returns whether the preset changed. */
    fun onPresetStep(direction: Int): Boolean {
        if (!live) return false
        val now = engine.settings.value.presetId
        val next = equalizerCyclePreset(now, direction)
        if (next == now) return false
        engine.applyPreset(next)
        return true
    }

    /** A touch on a gain slider: [fraction] of its track, snapped to the one-dB positions. */
    fun onGainRequested(control: EqualizerEditorControl, fraction: Float) {
        if (!live || !control.isSlider) return
        val db = EqGain.dbFor(fraction)
        if (db != equalizerGainOf(engine.settings.value, control)) equalizerSetGain(engine, control, db)
    }

    /** X on a slider: back to 0 dB. Returns whether it moved anything. */
    fun resetFocusedGain(): Boolean {
        val control = focusedControl()?.takeIf { it.isSlider && live } ?: return false
        if (equalizerGainOf(engine.settings.value, control) == 0f) return false
        equalizerSetGain(engine, control, 0f)
        return true
    }

    // ---- the controller ----

    /** The router's handler. Every action is consumed. */
    fun dispatch(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> onClose()
            ControllerAction.CONFIRM -> if (focusedControl() == EqualizerEditorControl.TOGGLE) onToggle()
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN ->
                equalizerEditorNeighbor(live, focusedControl(), action)?.let(::requestFocus)
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                val moved = when (val focused = focusedControl()) {
                    null -> false
                    EqualizerEditorControl.TOGGLE -> false
                    EqualizerEditorControl.PRESET -> onPresetStep(direction)
                    else -> if (live) equalizerStepGain(engine, focused, direction) else false
                }
                // A step the router cannot see in the focus snapshot still ticks, as Utility's value steps do.
                if (moved) MimirNavFeedback.consumed()
            }
            ControllerAction.SECONDARY -> if (resetFocusedGain() || retryEngine()) MimirNavFeedback.consumed()
            else -> Unit
        }
        return true
    }

    fun dispose() {
        authority.discardScope(EqualizerEditorScope)
    }
}
