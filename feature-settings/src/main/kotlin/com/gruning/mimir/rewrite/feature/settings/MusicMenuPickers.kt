package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.core.ui.select.dispatchMimirSelect

import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent

/**
 * Settings › Music's other choice menus, Order, Music hours and Launch sound, on the same select shell as the Source picker
 * ([MusicSourcePickerOverlay], spec 2026-09-30: "Choices: MimirSelectOverlay"). They share its overlay slot and its focus scope
 * ([MusicSourcePickerScope]): one of the four is open at a time, and which one is [MusicPickerMenu].
 */
enum class MusicPickerMenu { SOURCE, ORDER, HOURS, LAUNCH_SOUND }

/**
 * The Launch sound choice (update): the one row that replaced a toggle and a volume. Five steps, Off then 25 to 100 percent.
 * The two stored preferences stay as they were (the toggle and the 0..1 level), so nothing has to migrate on disk: [fromStored]
 * reads a pair of them as a step, and [enabled] with [percent] writes a step back as a pair.
 *
 * Off written is exactly "the toggle is off" (the level is left alone, so choosing a step later brings the sound back at that step).
 * Any other step written is the toggle on at that level. Read back, Off is only the toggle off: the toggle on never reads as Off, at any
 * stored level. A level between steps rounds to the nearest of the four sounding steps, so a level of zero or a few percent reads as 25%,
 * the quietest step.
 */
enum class MusicLaunchSoundValue(val label: String, val percent: Int) {
    OFF("Off", 0),
    QUARTER("25%", 25),
    HALF("50%", 50),
    THREE_QUARTERS("75%", 75),
    FULL("100%", 100),
    ;

    /** The toggle this step stores. */
    val enabled: Boolean get() = this != OFF

    companion object {
        /**
         * The step a stored toggle and level read as: Off when the toggle is off and only then; else the non-zero step nearest the level
         * (a tie goes up, and nothing below 25 reads as Off).
         */
        fun fromStored(enabled: Boolean, volumePercent: Int): MusicLaunchSoundValue {
            if (!enabled) return OFF
            val nearest = (((volumePercent.coerceIn(0, 100) + 12) / 25) * 25).coerceAtLeast(QUARTER.percent)
            return entries.first { it.percent == nearest }
        }
    }
}

private fun musicOrderKey(value: MusicOrderValue) = FocusKey("music/order/${value.name}")

private fun musicHoursKey(value: MusicHoursValue) = FocusKey("music/hours/${value.name}")

private fun musicLaunchSoundKey(value: MusicLaunchSoundValue) = FocusKey("music/launchsound/${value.name}")

/** The launch sound keyed by [key], or null when [key] is not one of the Launch sound menu's rows. */
fun musicLaunchSoundForKey(key: FocusKey?): MusicLaunchSoundValue? =
    MusicLaunchSoundValue.entries.firstOrNull { musicLaunchSoundKey(it) == key }

internal fun musicLaunchSoundItems(current: MusicLaunchSoundValue): List<MimirSelectItem> =
    MusicLaunchSoundValue.entries.map { value ->
        MimirSelectItem.Option(
            key = musicLaunchSoundKey(value),
            label = value.label,
            labelIntent = MimirTextIntent.StaticChrome(value.label),
            current = value == current,
        )
    }

/** The order keyed by [key], or null when [key] is not one of the Order menu's rows. */
fun musicOrderForKey(key: FocusKey?): MusicOrderValue? =
    MusicOrderValue.entries.firstOrNull { musicOrderKey(it) == key }

/** The hours keyed by [key], or null when [key] is not one of the Music hours menu's rows. */
fun musicHoursForKey(key: FocusKey?): MusicHoursValue? =
    MusicHoursValue.entries.firstOrNull { musicHoursKey(it) == key }

internal fun musicOrderItems(current: MusicOrderValue): List<MimirSelectItem> =
    MusicOrderValue.entries.map { value ->
        MimirSelectItem.Option(
            key = musicOrderKey(value),
            label = value.label,
            labelIntent = MimirTextIntent.StaticChrome(value.label),
            current = value == current,
            subtitle = value.detail,
        )
    }

internal fun musicHoursItems(current: MusicHoursValue): List<MimirSelectItem> =
    MusicHoursValue.entries.map { value ->
        MimirSelectItem.Option(
            key = musicHoursKey(value),
            label = value.label,
            labelIntent = MimirTextIntent.StaticChrome(value.label),
            current = value == current,
            subtitle = value.detail,
        )
    }

@Composable
fun MusicOrderPickerOverlay(
    authority: FocusAuthority,
    current: MusicOrderValue,
    onSelect: (MusicOrderValue) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    MimirSelectOverlay(
        authority = authority,
        scope = MusicSourcePickerScope,
        title = "MUSIC ORDER",
        items = musicOrderItems(current),
        onSelect = { key -> musicOrderForKey(key)?.let(onSelect) },
        onDismiss = onDismiss,
        presenceManagedExternally = presenceManagedExternally,
        centeredPlainRows = true,
    )
}

@Composable
fun MusicHoursPickerOverlay(
    authority: FocusAuthority,
    current: MusicHoursValue,
    onSelect: (MusicHoursValue) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    MimirSelectOverlay(
        authority = authority,
        scope = MusicSourcePickerScope,
        title = "MUSIC HOURS",
        items = musicHoursItems(current),
        onSelect = { key -> musicHoursForKey(key)?.let(onSelect) },
        onDismiss = onDismiss,
        presenceManagedExternally = presenceManagedExternally,
        centeredPlainRows = true,
    )
}

@Composable
fun MusicLaunchSoundPickerOverlay(
    authority: FocusAuthority,
    current: MusicLaunchSoundValue,
    onSelect: (MusicLaunchSoundValue) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    MimirSelectOverlay(
        authority = authority,
        scope = MusicSourcePickerScope,
        title = "LAUNCH SOUND",
        items = musicLaunchSoundItems(current),
        onSelect = { key -> musicLaunchSoundForKey(key)?.let(onSelect) },
        onDismiss = onDismiss,
        presenceManagedExternally = presenceManagedExternally,
        centeredPlainRows = true,
    )
}

/** Input contract for an open Launch sound menu: the shared select dispatch (hard modal). */
fun dispatchMusicLaunchSoundPicker(
    authority: FocusAuthority,
    current: MusicLaunchSoundValue,
    action: ControllerAction,
    onSelect: (MusicLaunchSoundValue) -> Unit,
    onDismiss: () -> Unit,
): Boolean = dispatchMimirSelect(
    authority = authority,
    scope = MusicSourcePickerScope,
    items = musicLaunchSoundItems(current),
    action = action,
    onSelect = { key -> musicLaunchSoundForKey(key)?.let(onSelect) },
    onDismiss = onDismiss,
)

/** Input contract for an open Order menu: the shared select dispatch (hard modal). */
fun dispatchMusicOrderPicker(
    authority: FocusAuthority,
    current: MusicOrderValue,
    action: ControllerAction,
    onSelect: (MusicOrderValue) -> Unit,
    onDismiss: () -> Unit,
): Boolean = dispatchMimirSelect(
    authority = authority,
    scope = MusicSourcePickerScope,
    items = musicOrderItems(current),
    action = action,
    onSelect = { key -> musicOrderForKey(key)?.let(onSelect) },
    onDismiss = onDismiss,
)

/** Input contract for an open Music hours menu: the shared select dispatch (hard modal). */
fun dispatchMusicHoursPicker(
    authority: FocusAuthority,
    current: MusicHoursValue,
    action: ControllerAction,
    onSelect: (MusicHoursValue) -> Unit,
    onDismiss: () -> Unit,
): Boolean = dispatchMimirSelect(
    authority = authority,
    scope = MusicSourcePickerScope,
    items = musicHoursItems(current),
    action = action,
    onSelect = { key -> musicHoursForKey(key)?.let(onSelect) },
    onDismiss = onDismiss,
)
