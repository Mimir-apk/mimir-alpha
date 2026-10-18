package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The one row id of Settings > Emulators > Emulator definitions. `emulators/` is already a registered Settings prefix. */
const val EmulatorDefinitionsRowId: String = "emulators/definitions/check"

/** Shown instead of the status the first time, before anything is downloaded: what is fetched. */
const val EmulatorDefinitionsExplanation: String =
    "Downloads updated emulator support. Choose Download to continue."

/** What the last check ended as, in the terms the row words. Mapped from the launch engine's result by the app. */
sealed interface EmulatorDefinitionsResult {
    /** Nothing new for this player. */
    data object UpToDate : EmulatorDefinitionsResult

    /** A new list is in force; [emulators] emulators have different launch details now. */
    data class Updated(val emulators: Int) : EmulatorDefinitionsResult

    /**
     * Nothing changed. [usingFetched]: the previous update stays; false means the built-in list.
     * [needsAppUpdate]: the update is written for a newer Mimir.
     */
    data class CouldntUpdate(val usingFetched: Boolean, val needsAppUpdate: Boolean = false) : EmulatorDefinitionsResult
}

/** The result line under the status. Plain English, no codes. */
fun EmulatorDefinitionsResult.line(): String = when (this) {
    EmulatorDefinitionsResult.UpToDate -> "Up to date"
    is EmulatorDefinitionsResult.Updated -> if (emulators == 1) "Updated: 1 emulator" else "Updated: $emulators emulators"
    is EmulatorDefinitionsResult.CouldntUpdate ->
        if (needsAppUpdate) {
            "This update needs a newer Mimir. " + if (usingFetched) "Keeping your last update." else "Using built-in."
        } else {
            "Couldn’t update; " + if (usingFetched) "keeping your last update" else "using built-in"
        }
}

/**
 * "Built-in · 8 Oct 2026" until an update has been downloaded, then "Updated · 12 Oct 2026".
 * [builtInDate] is the day the compiled list last changed; [fetchedOn] and [tag] describe the downloaded one.
 */
fun emulatorDefinitionsStatusLine(
    builtInDate: LocalDate,
    fetchedOn: LocalDate?,
    tag: String?,
    locale: Locale = Locale.getDefault(),
): String {
    val format = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    return if (tag != null) {
        listOfNotNull("Updated", fetchedOn?.format(format)).joinToString(" · ")
    } else {
        "Built-in · ${builtInDate.format(format)}"
    }
}

/** Everything the row shows. The app computes it; the pane only draws it. */
data class EmulatorDefinitionsRowState(
    /** [emulatorDefinitionsStatusLine]. */
    val status: String,
    /** True while the one-line explanation is shown and the next press downloads (the first use, opt-in). */
    val explaining: Boolean = false,
    val result: EmulatorDefinitionsResult? = null,
    val checking: Boolean = false,
) {
    val action: String
        get() = when {
            checking -> "Checking…"
            explaining -> "Download"
            else -> "Check for updates"
        }

    val supporting: String
        get() = when {
            explaining -> EmulatorDefinitionsExplanation
            result != null -> status + "\n" + result.line()
            else -> status
        }
}

/**
 * The seam between the app and the pane. `MimirAppHost` passes the pane its read models by parameter; this row has no
 * parameter there, so the app publishes it here instead. Null (nothing published: previews, tests) hides the section.
 */
object EmulatorDefinitionsRowBridge {
    private val current = MutableStateFlow<EmulatorDefinitionsRowState?>(null)

    val state: StateFlow<EmulatorDefinitionsRowState?> = current.asStateFlow()

    fun publish(state: EmulatorDefinitionsRowState?) {
        current.value = state
    }
}

/**
 * Settings > Emulators > Emulator updates. One controller-reachable row on [authority]'s detail scope, like the
 * Launch check row beside it. The press is [onRowActivate] with [EmulatorDefinitionsRowId]; the app decides what it means
 * (explain first, then download) and publishes the new state.
 */
@Composable
internal fun EmulatorDefinitionsSection(authority: FocusAuthority, onRowActivate: (String) -> Unit) {
    val row by EmulatorDefinitionsRowBridge.state.collectAsState()
    val shown = row ?: return
    SettingsSection(title = "Emulator updates") {
        Text(
            "Keeps Mimir’s emulator support current. Downloaded only when you choose.",
            style = MimirTypeScale.SupportCompact,
            color = MimirColors.TextMuted,
        )
        SettingsRow(
            key = detailKey(EmulatorDefinitionsRowId),
            scope = DetailScope,
            authority = authority,
            title = "Emulator support",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = shown.supporting,
            swapSupporting = true,
            onActivate = { onRowActivate(EmulatorDefinitionsRowId) },
            trailing = { RowAction(shown.action) },
        )
    }
}
