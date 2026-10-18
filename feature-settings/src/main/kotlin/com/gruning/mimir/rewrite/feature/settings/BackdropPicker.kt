package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.core.ui.select.dispatchMimirSelect

import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent

/**
 * The Bifröst picker — a thin caller over the shared [MimirSelectOverlay], mirroring the per-platform emulator
 * picker. The committed VALUE is mapped back to the real GamelistBackdropMode app-side (feature-settings can't
 * see the library enum), exactly like the emulator picker maps its emulator-id value (UXCI-01.1).
 *
 * **two mutually-exclusive states, and this picker is their sole authority.**
 * The former `Backdrop only` option is retired — it was the one state that hid cover art, which the media policy excludes. [BackdropValueBackdrop] survives as a read-only legacy token so a persisted value still maps
 * somewhere sane; nothing offers it any more.
 */
val BackdropPickerScope = FocusScope("settings/backdrop")

/** The stable option VALUES the app maps to a GamelistBackdropMode. */
const val BackdropValueOff = "off"

/** Retired 2026-07-19. Never offered; kept only so a stored legacy value still resolves. */
const val BackdropValueBackdrop = "backdrop"
const val BackdropValueMedia = "media"

private fun backdropKey(value: String) = FocusKey("settings/backdrop/$value")

/**
 * The current value → its user-facing row label (also used by the Settings row's trailing action).
 *
 * **this reads as a switch, because that is what it is.** It said
 * "Cover only" / "Cover + Hero", which named two MODES and made the player work out which one was the
 * absence of the other — and neither label mentioned the thing the setting actually governs. Cover art
 * renders in both states and always has; the only difference is whether the hero appears behind it. So the
 * labels now say so, and the subtitles carry the detail the old labels were trying to.
 */
fun backdropValueLabel(value: String): String = when (value) {
    BackdropValueOff -> "Off"
    // Legacy `backdrop` collapses into the single on-state, matching the store's own migration.
    else -> "On"
}

private fun backdropItems(currentValue: String): List<MimirSelectItem> = listOf(
    MimirSelectItem.Option(
        key = backdropKey(BackdropValueMedia),
        label = "On",
        labelIntent = MimirTextIntent.StaticChrome("On"),
        // One state covers both hero kinds on purpose. Whether a game shows a still or a moving backdrop
        // is a property of that GAME (whether a Hero Motion has been chosen for it in Customize Media),
        // never a second setting to find. Splitting them here would ask the player to configure
        // something they configure per game.
        subtitle = "The game’s hero behind the cover — Hero Motion when it has one, otherwise Hero Art.",
        current = currentValue != BackdropValueOff,
    ),
    MimirSelectItem.Option(
        key = backdropKey(BackdropValueOff),
        label = "Off",
        labelIntent = MimirTextIntent.StaticChrome("Off"),
        subtitle = "No backdrop at all. Cover art on a flat theme color.",
        current = currentValue == BackdropValueOff,
    ),
)

private fun commitBackdrop(key: FocusKey, onChoose: (value: String) -> Unit) {
    when (key) {
        backdropKey(BackdropValueOff) -> onChoose(BackdropValueOff)
        backdropKey(BackdropValueMedia) -> onChoose(BackdropValueMedia)
    }
}

@Composable
fun BackdropPickerOverlay(
    authority: FocusAuthority,
    currentValue: String,
    onChoose: (value: String) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    MimirSelectOverlay(
        authority = authority,
        scope = BackdropPickerScope,
        title = "GAME BACKDROPS",
        items = backdropItems(currentValue),
        onSelect = { key -> commitBackdrop(key, onChoose) },
        onDismiss = onDismiss,
        presenceManagedExternally = presenceManagedExternally,
        centeredPlainRows = true,
    )
}

/** The ONE input entry for the picker while open (hard modal); delegates to [dispatchMimirSelect]. */
fun dispatchBackdropPicker(
    authority: FocusAuthority,
    action: ControllerAction,
    onChoose: (value: String) -> Unit,
    onDismiss: () -> Unit,
): Boolean = dispatchMimirSelect(
    authority = authority,
    scope = BackdropPickerScope,
    items = backdropItems(currentValue = ""),
    action = action,
    onSelect = { key -> commitBackdrop(key, onChoose) },
    onDismiss = onDismiss,
)
