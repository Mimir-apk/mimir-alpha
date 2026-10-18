package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W2f: with a live READY bridge the Keyboard row is a switch whose state is the readback; without one it keeps
 * today's three picker states exactly.
 */
class MimirKeyboardRowTest {

    @Test
    fun withoutTheBridgeTheRowKeepsTodaysThreeStates() {
        MimirKeyboardPickerEntry.entries.forEach { entry ->
            assertEquals(MimirKeyboardRow.Picker(entry), mimirKeyboardRow(entry, bridgeReady = false))
        }
    }

    @Test
    fun withTheBridgeTheSwitchIsOnOnlyWhileMimirIsTheDefault() {
        assertEquals(
            MimirKeyboardRow.Switch(on = true),
            mimirKeyboardRow(MimirKeyboardPickerEntry.ALREADY_DEFAULT, bridgeReady = true),
        )
        assertEquals(
            MimirKeyboardRow.Switch(on = false),
            mimirKeyboardRow(MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER, bridgeReady = true),
        )
        // Not even enabled yet: the bridge can enable it, so it is still a switch, and it is off.
        assertEquals(
            MimirKeyboardRow.Switch(on = false),
            mimirKeyboardRow(MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS, bridgeReady = true),
        )
    }

    @Test
    fun theSwitchCopyNamesTheRespectRule() {
        assertEquals(true, KeyboardSwitchOffSupporting.contains("another keyboard in Android"))
        assertEquals(true, KeyboardSwitchOnSupporting.contains("Turn off"))
    }
}
