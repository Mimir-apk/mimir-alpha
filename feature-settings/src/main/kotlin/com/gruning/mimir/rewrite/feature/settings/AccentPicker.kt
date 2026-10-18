package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.core.ui.select.dispatchMimirSelect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirAccentStop
import com.gruning.mimir.rewrite.core.ui.MimirAccentTemp
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.shift

/**
 * AP01 §2 + CF01 — the ACCENT picker, now for EVERY theme:
 *  - DEFAULT keeps its six ratified curated warm-metal stops.
 *  - Each NAMED theme gets five TEMPERATURE stops (Cooler…Warmer) that cool/warm its OWN authored
 *    accent system; the swatch shows the theme's accentBright hue-shifted by that stop.
 * Both ride the house [MimirSelectOverlay] (leading swatch, ✓ on the committed stop, `A Select ·
 * B Cancel`).
 *
 * LIVE PREVIEW: focusing a row writes the matching preview field on [MimirAppearanceOverrides] — the
 * whole app re-accents behind the dim scrim. Commit persists (per theme); cancel clears the preview.
 */
val AccentPickerScope = FocusScope("appearance/accentPicker")

private fun stopKey(stop: MimirAccentStop) = FocusKey("accent/${stop.name}")
private fun tempKey(temp: MimirAccentTemp) = FocusKey("accentTemp/${temp.name}")

private fun accentItems(current: MimirAccentStop): List<MimirSelectItem> =
    MimirAccentStop.entries.map { stop ->
        MimirSelectItem.Option(
            key = stopKey(stop),
            label = stop.label,
            labelIntent = MimirTextIntent.StaticChrome(stop.label),
            current = stop == current,
            swatch = stop.accentBright,
            subtitle = if (stop == MimirAccentStop.CLASSIC_GOLD) "The original Mimir gold." else null,
        )
    }

private fun tempItems(themeId: MimirThemeId, current: MimirAccentTemp): List<MimirSelectItem> {
    val authoredBright = MimirThemePalette.forId(themeId).accentBright
    return MimirAccentTemp.entries.map { temp ->
        MimirSelectItem.Option(
            key = tempKey(temp),
            label = temp.label,
            labelIntent = MimirTextIntent.StaticChrome(temp.label),
            current = temp == current,
            swatch = temp.shift(authoredBright),
            subtitle = if (temp == MimirAccentTemp.ORIGINAL) "The authored ${themeId.label} accent." else null,
        )
    }
}

private fun stopForKey(key: FocusKey?): MimirAccentStop? =
    MimirAccentStop.entries.firstOrNull { stopKey(it) == key }

private fun tempForKey(key: FocusKey?): MimirAccentTemp? =
    MimirAccentTemp.entries.firstOrNull { tempKey(it) == key }

@Composable
fun AccentPickerOverlay(
    authority: FocusAuthority,
    themeId: MimirThemeId,
    onCommit: (MimirAccentStop) -> Unit,
    onCommitTemp: (MimirAccentTemp) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    val defaultTheme = themeId == MimirThemeId.DEFAULT
    // Live preview rides row FOCUS — D-pad or touch. Watching the one focus authority keeps preview in
    // lock-step with what the counter/ring show, with no overlay-shell changes.
    LaunchedEffect(authority, defaultTheme) {
        authority.state.collect { state ->
            if (state.scope == AccentPickerScope) {
                if (defaultTheme) {
                    stopForKey(state.focused)?.let { MimirAppearanceOverrides.accentPreview = it }
                } else {
                    tempForKey(state.focused)?.let { MimirAppearanceOverrides.accentTempPreview = it }
                }
            }
        }
    }
    MimirSelectOverlay(
        authority = authority,
        scope = AccentPickerScope,
        title = "ACCENT COLOR",
        items = if (defaultTheme) {
            accentItems(MimirAppearanceOverrides.accentStop)
        } else {
            tempItems(themeId, MimirAppearanceOverrides.accentTemps[themeId] ?: MimirAccentTemp.ORIGINAL)
        },
        onSelect = { key ->
            if (defaultTheme) stopForKey(key)?.let(onCommit) else tempForKey(key)?.let(onCommitTemp)
        },
        onDismiss = onDismiss,
        presenceManagedExternally = presenceManagedExternally,
        centeredPlainRows = true,
    )
}

/** Input contract for an open accent picker — the shared select dispatch (hard modal). */
fun dispatchAccentPicker(
    authority: FocusAuthority,
    themeId: MimirThemeId,
    action: ControllerAction,
    onCommit: (MimirAccentStop) -> Unit,
    onCommitTemp: (MimirAccentTemp) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    val defaultTheme = themeId == MimirThemeId.DEFAULT
    return dispatchMimirSelect(
        authority = authority,
        scope = AccentPickerScope,
        items = if (defaultTheme) {
            accentItems(MimirAppearanceOverrides.accentStop)
        } else {
            tempItems(themeId, MimirAppearanceOverrides.accentTemps[themeId] ?: MimirAccentTemp.ORIGINAL)
        },
        action = action,
        onSelect = { key ->
            if (defaultTheme) stopForKey(key)?.let(onCommit) else tempForKey(key)?.let(onCommitTemp)
        },
        onDismiss = onDismiss,
    )
}
