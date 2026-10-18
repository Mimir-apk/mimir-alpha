package com.gruning.mimir.rewrite.feature.settings

/**
 * R6-P3-02 ("make Mimir keyboard default"). *
 * > One tap from Settings and onboarding opens Android's own keyboard screens. There the player turns
 * > Mimir Keyboard on and picks it as the default. Mimir never changes the setting itself: no privileged
 * > route, no shell bridge, no `settings put`, no `ime set`. `isDefault="false"` in `mimir_input_method.xml`
 * > stays.
 *
 * So this entry only ever RE-EXPRESSES two platform facts as one of three offers — it never writes
 * `Settings.Secure.DEFAULT_INPUT_METHOD` and never calls a privileged setter.
 *
 * **Devices with the certified vendor bridge:** there Mimir sets
 * Mimir Keyboard as the default itself and the row becomes a switch ([mimirKeyboardRow]). This entry stays the
 * state of the picker path for every device without a live READY bridge, and is still what the row reads to
 * know whether Mimir Keyboard is the default.
 */
enum class MimirKeyboardPickerEntry {
    /** Mimir Keyboard is not in the enabled-IME list yet — send the player to Android's keyboard settings. */
    OPEN_KEYBOARD_SETTINGS,

    /** Enabled, but some other IME is the current default — show Android's own input-method picker. */
    SHOW_INPUT_METHOD_PICKER,

    /**
     * Already the default. Nothing left for this entry to offer — the row settles (same convention the
     * neighbouring `SpecialAccessRow` permission rows already use for a granted state: it stays visible
     * and reachable, wearing a settled chip, rather than disappearing — see `ControlsPanel`'s
     * `rows.filterNot { it.id == "m_button" }` loop in `SettingsDetail.kt`, which renders every OTHER
     * `SpecialAccessRow` — granted or not — as a focusable `SettingsRow`, not just the ungranted ones).
     */
    ALREADY_DEFAULT,
}

/**
 * Pure decision (R6-P3-02 step 1): reads two platform facts — the enabled-IME id set and the current
 * default IME id — plus Mimir's own IME component id, and returns which of the three
 * [MimirKeyboardPickerEntry] states applies. Deliberately takes plain [String]/[Set] rather than
 * `InputMethodManager`/`Settings.Secure` directly, so this needs no Android framework and no Robolectric
 * to test — the platform reads live in `app`, which calls this with the
 * values it read.
 */
fun mimirKeyboardPickerEntryState(
    mimirImeId: String,
    enabledInputMethodIds: Set<String>,
    defaultInputMethodId: String?,
): MimirKeyboardPickerEntry = when {
    mimirImeId !in enabledInputMethodIds -> MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS
    defaultInputMethodId != mimirImeId -> MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER
    else -> MimirKeyboardPickerEntry.ALREADY_DEFAULT
}

/**
 * What Settings › Controls › Keyboard shows (W2f, policy 2026-10-03).
 *
 * With a live READY vendor bridge the row is a SWITCH whose state is the readback, "is Mimir Keyboard the
 * default now" — so a player who picks another keyboard in Android sees it off. Without the bridge the row keeps
 * today's three picker states, unchanged.
 */
sealed interface MimirKeyboardRow {
    /** No bridge: the original three-state picker row. */
    data class Picker(val entry: MimirKeyboardPickerEntry) : MimirKeyboardRow

    /** Bridge ready: a switch. [on] is the platform readback, not the stored preference. */
    data class Switch(val on: Boolean) : MimirKeyboardRow
}

fun mimirKeyboardRow(entry: MimirKeyboardPickerEntry, bridgeReady: Boolean): MimirKeyboardRow =
    if (bridgeReady) {
        MimirKeyboardRow.Switch(on = entry == MimirKeyboardPickerEntry.ALREADY_DEFAULT)
    } else {
        MimirKeyboardRow.Picker(entry)
    }

internal const val KeyboardSwitchOnSupporting =
    "Mimir Keyboard is your default keyboard. Turn off to go back to your previous one."
internal const val KeyboardSwitchOffSupporting =
    "Make Mimir Keyboard your default keyboard. Picking another keyboard in Android keeps this off."
