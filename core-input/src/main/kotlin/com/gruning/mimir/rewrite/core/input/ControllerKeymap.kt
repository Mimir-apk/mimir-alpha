package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent

/**
 * Stable physical control groups. Persist names, never enum ordinals.
 *
 * D-pad groups intentionally include the equivalent HAT/left-stick direction: a user remaps the
 * console direction once and every Android source for that direction follows it.
 */
enum class PhysicalControl(val displayLabel: String) {
    DPAD_UP("D-PAD ↑"),
    DPAD_DOWN("D-PAD ↓"),
    DPAD_LEFT("D-PAD ←"),
    DPAD_RIGHT("D-PAD →"),
    FACE_A("A"),
    FACE_B("B"),
    FACE_X("X"),
    FACE_Y("Y"),
    L1("L1"),
    R1("R1"),
    L2("L2"),
    R2("R2"),
    START("START"),
    SELECT("SELECT"),
    MENU("M"),
    /** The left stick's click (OR-8): the controller-hint legend by default. */
    L3("L3"),
}

typealias ControllerKeymap = Map<PhysicalControl, ControllerAction?>

/**
 * The factory mapping. ONE shared instance: this is a constant, and it used to be rebuilt on every call.
 *
 * `DefaultInputAuthority` defaults its keymap provider to `{ defaultControllerKeymap() }`, and
 * `interpretKey`, `interpretMotion`, `interpretHat`, `releaseHatDirection` and `keyToAction` each call
 * that provider independently — so a single motion sample allocated three or four 15-entry maps. While
 * the Controller Mapping overlay is open every motion sample (batched history included) routes through
 * an authority on that default, so a held stick allocated hundreds of throwaway maps per second on the
 * thread that also has to make the frame deadline.
 *
 * Sharing is safe because [ControllerKeymap] is a read-only `Map`: every caller that changes a binding
 * goes through `toMutableMap()`, which copies. Pinned by `ControllerKeymapTest`, by identity — the maps
 * were always EQUAL, which is precisely why the allocation went unnoticed.
 */
fun defaultControllerKeymap(): ControllerKeymap = FactoryControllerKeymap

private val FactoryControllerKeymap: ControllerKeymap = mapOf(
    PhysicalControl.DPAD_UP to ControllerAction.NAV_UP,
    PhysicalControl.DPAD_DOWN to ControllerAction.NAV_DOWN,
    PhysicalControl.DPAD_LEFT to ControllerAction.NAV_LEFT,
    PhysicalControl.DPAD_RIGHT to ControllerAction.NAV_RIGHT,
    PhysicalControl.FACE_A to ControllerAction.CONFIRM,
    PhysicalControl.FACE_B to ControllerAction.CANCEL,
    PhysicalControl.FACE_X to ControllerAction.SECONDARY,
    PhysicalControl.FACE_Y to ControllerAction.TERTIARY,
    PhysicalControl.L1 to ControllerAction.PAGE_LEFT,
    PhysicalControl.R1 to ControllerAction.PAGE_RIGHT,
    PhysicalControl.L2 to ControllerAction.TRIGGER_LEFT,
    PhysicalControl.R2 to ControllerAction.TRIGGER_RIGHT,
    PhysicalControl.START to ControllerAction.SYSTEM_START,
    PhysicalControl.SELECT to ControllerAction.SYSTEM_SELECT,
    PhysicalControl.MENU to ControllerAction.SYSTEM_MENU,
    PhysicalControl.L3 to ControllerAction.SHOW_HINTS,
)

/** One Android key source table shared by routing, capture, and macro-key reservation. */
fun physicalControlForKeyCode(keyCode: Int): PhysicalControl? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> PhysicalControl.DPAD_UP
    KeyEvent.KEYCODE_DPAD_DOWN -> PhysicalControl.DPAD_DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> PhysicalControl.DPAD_LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> PhysicalControl.DPAD_RIGHT
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER -> PhysicalControl.FACE_A
    KeyEvent.KEYCODE_BUTTON_B -> PhysicalControl.FACE_B
    KeyEvent.KEYCODE_BUTTON_X -> PhysicalControl.FACE_X
    KeyEvent.KEYCODE_BUTTON_Y -> PhysicalControl.FACE_Y
    KeyEvent.KEYCODE_BUTTON_L1 -> PhysicalControl.L1
    KeyEvent.KEYCODE_BUTTON_R1 -> PhysicalControl.R1
    KeyEvent.KEYCODE_BUTTON_L2 -> PhysicalControl.L2
    KeyEvent.KEYCODE_BUTTON_R2 -> PhysicalControl.R2
    KeyEvent.KEYCODE_BUTTON_START -> PhysicalControl.START
    KeyEvent.KEYCODE_BUTTON_SELECT -> PhysicalControl.SELECT
    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_BUTTON_MODE -> PhysicalControl.MENU
    KeyEvent.KEYCODE_BUTTON_THUMBL -> PhysicalControl.L3
    else -> null
}

fun isConsolePhysicalKeyCode(keyCode: Int): Boolean = physicalControlForKeyCode(keyCode) != null

internal val PhysicalControl.isDirectionalSource: Boolean
    get() = this == PhysicalControl.DPAD_UP ||
        this == PhysicalControl.DPAD_DOWN ||
        this == PhysicalControl.DPAD_LEFT ||
        this == PhysicalControl.DPAD_RIGHT

internal val PhysicalControl.hasAxisSource: Boolean
    get() = isDirectionalSource || this == PhysicalControl.L2 || this == PhysicalControl.R2
