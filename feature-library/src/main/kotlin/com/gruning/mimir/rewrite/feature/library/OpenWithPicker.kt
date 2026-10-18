package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectAlwaysActive
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay

/** The modal focus scope the Open-With picker owns while open from typed detail metadata. */
val OpenWithScope = FocusScope("library/openWith")

/** The "Automatic" (clear override → default-to-installed) row's focus key. */
val OpenWithAutoKey = FocusKey("library/openWith/auto")

/** Stable focus key for a candidate emulator row. */
fun openWithFocusKey(emulatorIdValue: String) = FocusKey("library/openWith/emu/$emulatorIdValue")

/** The picker rows (UXCI-01 Phase 3 redesign): "Automatic" atop the candidates, each row a real emulator app
 *  icon + clean label. The auto-pick gets the structured gold "Recommended" chip (not baked into the label); a
 *  unavailable candidate shows a dim, non-committable reason (no dead launch); the "Automatic" row names which emulator Auto resolves to (recognition over
 *  recall). Nothing here is persisted — the override is keyed on emulatorId only. */
internal fun openWithItems(
    options: OpenWithOptions,
): List<MimirSelectItem> = buildList {
    val autoPick = options.candidates.firstOrNull { it.isAutoPick }
    // Information only : when every installed emulator keeps a list of the files it accepts and the game's file is on
    // none of them, one muted line says so above the rows. Play and Automatic are untouched; nothing is hidden or disabled.
    if (options.noInstalledEmulatorListsFile) add(MimirSelectItem.Note(openWithNoneListLine(options.fileExtension)))
    add(
        MimirSelectItem.Option(
            OpenWithAutoKey,
            "Automatic",
            labelIntent = MimirTextIntent.StaticChrome("Automatic"),
            current = !options.hasOverride,
            subtitle = autoPick?.let { "Uses ${it.label}" },
        ),
    )
    options.candidates.forEach { c ->
        add(
            MimirSelectItem.Option(
                key = openWithFocusKey(c.emulatorId.value),
                label = c.label,
                enabled = c.selectable,
                current = c.isCurrentOverride,
                recommended = c.isAutoPick,
                subtitle = when {
                    !c.installed -> "Not installed"
                    !c.dispatchSupported -> "Not supported yet"
                    // More specific than the generic RetroArch setup caution below, so it takes the one line. A label only:
                    // the row stays selectable, and Recommended stays where Auto put it.
                    c.listsFile == false -> openWithDoesNotListLine(options.fileExtension)
                    c.requiresManualCoreProof -> "Additional emulator setup may be required"
                    else -> null
                },
                iconPackage = c.packageName,
            ),
        )
    }
}

/** The muted line under an emulator whose list leaves the game's file out; [extension] is the file's real one (lowercase, no dot). */
internal fun openWithDoesNotListLine(extension: String?): String =
    "Doesn’t list ${extension?.let { ".$it files" } ?: "this file"}"

/** The one line above the rows when no installed emulator lists the game's file. */
internal fun openWithNoneListLine(extension: String?): String =
    "None of your emulators list ${extension?.let { ".$it files" } ?: "this file"}."

/**
 * The "Open with…" picker — a controller-navigable modal of the installable emulators for a game's platform
 * . Now a thin caller over the shared [MimirSelectOverlay]. Input is routed
 * through the Library dispatch (which navigates [OpenWithScope] via the same keys), so only the overlay's render
 * moved onto the shared shell; the scope + keys are unchanged.
 *
 * @param options the current override + candidates (from LaunchController.openWithOptions).
 * @param onChooseAuto reset the game to Auto.
 * @param onChoose apply an installed candidate as the override.
 * @param onDismiss the scrim/Back closed the picker without changing the override.
 * @param active false while the sheet is composed and measured ahead of its opening (see [LibraryStandbySlot]); true otherwise. See [MimirSelectOverlay].
 */
@Composable
fun OpenWithOverlay(
    authority: FocusAuthority,
    options: OpenWithOptions,
    onChooseAuto: () -> Unit,
    onChoose: (OpenWithCandidate) -> Unit,
    onDismiss: () -> Unit,
    active: () -> Boolean = MimirSelectAlwaysActive,
) {
    // Built once per options, not per composition: a fresh list is a changed parameter, and the overlay would rebuild its rows each time the card behind it did.
    val items = remember(options) { openWithItems(options) }
    MimirSelectOverlay(
        authority = authority,
        scope = OpenWithScope,
        title = "Open with",
        items = items,
        onSelect = { key ->
            when (key) {
                OpenWithAutoKey -> onChooseAuto()
                else -> options.candidates
                    .firstOrNull { openWithFocusKey(it.emulatorId.value) == key && it.selectable }
                    ?.let(onChoose)
            }
        },
        onDismiss = onDismiss,
        presenceManagedExternally = true,
        active = active,
    )
}
