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
 * Settings › Music › Source (spec 2026-09-30): Mimir's music (the theme's bed) or Your music, on the house
 * [MimirSelectOverlay] like the Backdrop and Accent pickers. "Your music" is offered dim and non-committable
 * until a folder holds tracks ([MusicReadModel.customSourceAvailable]); its subtitle says what to do first
 * rather than letting a choice that changes nothing audible be made.
 */
val MusicSourcePickerScope = FocusScope("music/sourcePicker")

internal const val MusicSourceNeedsFolderCopy = "Choose a music folder with tracks first."

private fun musicSourceKey(value: MusicSourceValue) = FocusKey("music/source/${value.name}")

/** The option keyed by [key], or null when [key] is not one of this picker's rows. */
fun musicSourceForKey(key: FocusKey?): MusicSourceValue? =
    MusicSourceValue.entries.firstOrNull { musicSourceKey(it) == key }

internal fun musicSourceItems(current: MusicSourceValue, customAvailable: Boolean): List<MimirSelectItem> =
    MusicSourceValue.entries.map { value ->
        MimirSelectItem.Option(
            key = musicSourceKey(value),
            label = value.label,
            labelIntent = MimirTextIntent.StaticChrome(value.label),
            current = value == current,
            enabled = value == MusicSourceValue.MIMIR || customAvailable,
            subtitle = when (value) {
                MusicSourceValue.MIMIR -> "The theme’s own music bed."
                MusicSourceValue.CUSTOM -> if (customAvailable) "Tracks from your music folder." else MusicSourceNeedsFolderCopy
            },
        )
    }

@Composable
fun MusicSourcePickerOverlay(
    authority: FocusAuthority,
    current: MusicSourceValue,
    customAvailable: Boolean,
    onSelect: (MusicSourceValue) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    MimirSelectOverlay(
        authority = authority,
        scope = MusicSourcePickerScope,
        title = "MUSIC SOURCE",
        items = musicSourceItems(current, customAvailable),
        onSelect = { key -> musicSourceForKey(key)?.let(onSelect) },
        onDismiss = onDismiss,
        presenceManagedExternally = presenceManagedExternally,
        centeredPlainRows = true,
    )
}

/** Input contract for an open source picker: the shared select dispatch (hard modal). */
fun dispatchMusicSourcePicker(
    authority: FocusAuthority,
    current: MusicSourceValue,
    customAvailable: Boolean,
    action: ControllerAction,
    onSelect: (MusicSourceValue) -> Unit,
    onDismiss: () -> Unit,
): Boolean = dispatchMimirSelect(
    authority = authority,
    scope = MusicSourcePickerScope,
    items = musicSourceItems(current, customAvailable),
    action = action,
    onSelect = { key -> musicSourceForKey(key)?.let(onSelect) },
    onDismiss = onDismiss,
)
