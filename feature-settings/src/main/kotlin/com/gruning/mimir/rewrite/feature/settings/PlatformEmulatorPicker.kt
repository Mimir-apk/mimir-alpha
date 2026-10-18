package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.core.ui.select.dispatchMimirSelect

import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent

/** The modal focus scope the per-platform emulator picker owns while open (pushed from the Emulators pane). */
val PlatformEmulatorPickerScope = FocusScope("settings/platformEmulator")

/** The "Automatic" (clear the platform default → Auto) row's focus key. */
val PlatformEmulatorAutoKey = FocusKey("settings/platformEmulator/auto")

/** Stable focus key for a candidate emulator row (keyed by the emulator id value). */
fun platformEmulatorKey(emulatorIdValue: String) = FocusKey("settings/platformEmulator/$emulatorIdValue")

/** A UI-friendly emulator candidate for the picker (the app maps the launch engine's profiles into these). */
data class EmulatorChoice(val emulatorIdValue: String, val label: String, val installed: Boolean)

internal fun platformEmulatorPickerTitle(platformLabel: String?): String =
    platformLabel?.trim()?.takeIf(String::isNotEmpty)?.let { "$it emulator" } ?: "Platform emulator"

/** The picker rows: an "Automatic" (best-installed) row atop the candidates; a not-installed one is dim + inert. */
private fun platformEmulatorItems(
    choices: List<EmulatorChoice>,
    currentDefaultIdValue: String?,
): List<MimirSelectItem> = buildList {
    add(
        MimirSelectItem.Option(
            PlatformEmulatorAutoKey,
            "Automatic",
            labelIntent = MimirTextIntent.StaticChrome("Automatic"),
            current = currentDefaultIdValue == null,
        ),
    )
    choices.forEach { c ->
        add(
            MimirSelectItem.Option(
                key = platformEmulatorKey(c.emulatorIdValue),
                label = c.label,
                enabled = c.installed,
                current = c.emulatorIdValue == currentDefaultIdValue,
            ),
        )
    }
}

/** Map a committed row key back to the domain action (Auto, or an INSTALLED candidate — never a dead default). */
private fun commitPlatformEmulator(
    key: FocusKey,
    choices: List<EmulatorChoice>,
    onChooseAuto: () -> Unit,
    onChoose: (emulatorIdValue: String) -> Unit,
) {
    if (key == PlatformEmulatorAutoKey) {
        onChooseAuto()
    } else {
        choices.firstOrNull { platformEmulatorKey(it.emulatorIdValue) == key && it.installed }
            ?.let { onChoose(it.emulatorIdValue) }
    }
}

/**
 * The per-PLATFORM default emulator picker — which installed emulator opens THIS system by default . Now a thin caller over the shared [MimirSelectOverlay]: an "Automatic" row
 * plus the candidates (a not-installed one is a dim, non-committable row). Same focus discipline as before.
 */
@Composable
fun PlatformEmulatorPickerOverlay(
    authority: FocusAuthority,
    platformLabel: String?,
    choices: List<EmulatorChoice>,
    currentDefaultIdValue: String?,
    onChooseAuto: () -> Unit,
    onChoose: (emulatorIdValue: String) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    MimirSelectOverlay(
        authority = authority,
        scope = PlatformEmulatorPickerScope,
        title = platformEmulatorPickerTitle(platformLabel),
        titleIntent = platformLabel?.trim()?.takeIf(String::isNotEmpty)?.let { label ->
            MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Authored(label),
                    MimirTextFragment.Chrome(" emulator"),
                ),
            )
        } ?: MimirTextIntent.StaticChrome("Platform emulator"),
        items = platformEmulatorItems(choices, currentDefaultIdValue),
        onSelect = { key -> commitPlatformEmulator(key, choices, onChooseAuto, onChoose) },
        onDismiss = onDismiss,
        presenceManagedExternally = presenceManagedExternally,
    )
}

/**
 * The ONE input entry for the picker while open (hard modal). Delegates the mechanics to [dispatchMimirSelect];
 * only the key→choice mapping (Auto / installed candidate) is platform-specific.
 */
fun dispatchPlatformEmulatorPicker(
    authority: FocusAuthority,
    choices: List<EmulatorChoice>,
    action: ControllerAction,
    onChooseAuto: () -> Unit,
    onChoose: (emulatorIdValue: String) -> Unit,
    onDismiss: () -> Unit,
): Boolean = dispatchMimirSelect(
    authority = authority,
    scope = PlatformEmulatorPickerScope,
    items = platformEmulatorItems(choices, null),
    action = action,
    onSelect = { key -> commitPlatformEmulator(key, choices, onChooseAuto, onChoose) },
    onDismiss = onDismiss,
)
