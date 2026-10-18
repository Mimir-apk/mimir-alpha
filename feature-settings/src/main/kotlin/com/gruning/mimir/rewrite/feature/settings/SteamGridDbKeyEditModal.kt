package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirSecretFieldKeyboard
import com.gruning.mimir.rewrite.core.ui.rememberMimirSecretFieldValueOwner
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

enum class SteamGridDbKeyValidationUiState {
    IDLE,
    CHECKING,
    REJECTED,
    UNREACHABLE,
    PROVIDER_UNAVAILABLE,
    STORAGE_FAILED,
}

internal fun steamGridDbKeyValidationCopy(state: SteamGridDbKeyValidationUiState): String? = when (state) {
    SteamGridDbKeyValidationUiState.IDLE -> null
    SteamGridDbKeyValidationUiState.CHECKING -> "CHECKING CONNECTION…"
    SteamGridDbKeyValidationUiState.REJECTED -> "KEY NOT ACCEPTED · CHECK IT AND TRY AGAIN"
    SteamGridDbKeyValidationUiState.UNREACHABLE -> "STEAMGRIDDB COULDN’T BE REACHED · TRY AGAIN"
    SteamGridDbKeyValidationUiState.PROVIDER_UNAVAILABLE -> "STEAMGRIDDB IS UNAVAILABLE RIGHT NOW · TRY AGAIN"
    SteamGridDbKeyValidationUiState.STORAGE_FAILED -> "KEY COULDN’T BE SAVED ON THIS DEVICE"
}

/**
 * The SteamGridDB API-key editor. Same full-screen touch overlay + shared [MimirKeyboard] as the
 * profile-name editor (typing is 100% touch; the controller only
 * DISMISSES). The key is a read-only SteamGridDB fetch token the user pastes/types; it stays on this device
 * (opt-in, local — `mimir-project-posture`). The shared keyboard is lowercase, which fits SteamGridDB's
 * lowercase-hex keys. Done saves, Back/scrim cancels.
 *
 * the key is masked like the ScreenScraper password. The keyboard field and the preview
 * line draw one dot per character while typing or pasting, and a saved key seeded into the draft is only ever
 * drawn as dots, never in full.
 *
 * @param currentKey the key to seed the draft with ("" when none is set yet). Drawn as dots only.
 * @param onSave the finished, trimmed key (may be "" to clear — the fetch simply stays off).
 * @param onCancel dismiss without saving (Done not pressed).
 */
@Composable
fun SteamGridDbKeyEditOverlay(
    currentKey: String,
    validationState: SteamGridDbKeyValidationUiState = SteamGridDbKeyValidationUiState.IDLE,
    onSave: (String) -> Unit,
    onDraftChanged: () -> Unit = {},
    onCancel: () -> Unit,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
) {
    // The scrim tap dismisses this whole overlay, which used to destroy the remember{}-local draft.
    // The draft now lives in a process-lifetime stash: reopening resumes exactly where typing
    // stopped; only a completed Done/save clears it.
    var draftValue by remember {
        val initial = SteamGridDbKeyDraftStash.value ?: currentKey
        mutableStateOf(TextFieldValue(text = initial, selection = TextRange(initial.length)))
    }
    val draft = draftValue.text
    // A saved key opens as dots, so the player cannot see where an edit lands: the first edit replaces it instead of
    // appending to it. Done with no edit still keeps it. A stray Space is no edit, and the
    // first Backspace only selects the key, because controller B presses Done and a blank Done clears the stored
    // key without validation.
    var savedKeyUntouched by remember {
        mutableStateOf(SteamGridDbKeyDraftStash.value == null && currentKey.isNotEmpty())
    }
    val trimmed = draft.trim()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase.copy(alpha = 0.95f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactPreview = settingsKeyboardPreviewCompact(maxHeight.value)
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(
                            horizontal = MimirSpacing.xxl,
                            vertical = if (compactPreview) MimirSpacing.xs else MimirSpacing.md,
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MimirIntentText(
                        intent = MimirTextIntent.Mixed(
                            listOf(
                                MimirTextFragment.Authored("SteamGridDB"),
                                MimirTextFragment.Chrome(" key"),
                            ),
                        ),
                        style = MimirType.RunicEyebrow,
                        color = MimirColors.Gold,
                    )
                    if (!compactPreview) {
                        MimirIntentText(
                            intent = if (trimmed.isBlank()) {
                                MimirTextIntent.StaticChrome("Paste your key")
                            } else {
                                // dots, as the ScreenScraper password step draws them.
                                MimirTextIntent.Authored("•".repeat(trimmed.length))
                            },
                            style = if (trimmed.isBlank()) MimirType.TitleCaps else MimirType.OverlayTitle,
                            color = if (trimmed.isBlank()) MimirColors.TextMuted else MimirColors.TextPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Get a free key at steamgriddb.com › Preferences › API. It stays on this device.",
                            style = MimirType.RowSupport,
                            color = MimirColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = MimirSpacing.sm),
                        )
                    }
                    steamGridDbKeyValidationCopy(validationState)?.let { status ->
                        MimirIntentText(
                            intent = MimirTextIntent.StaticChrome(status),
                            style = MimirType.MicroStatus,
                            color = when (validationState) {
                                SteamGridDbKeyValidationUiState.CHECKING -> MimirColors.Gold
                                SteamGridDbKeyValidationUiState.IDLE -> MimirColors.TextMuted
                                else -> MimirColors.Danger
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = MimirSpacing.sm),
                        )
                    }
                }
                // the key is typed through the ops-only keyboard. The owner (this draft and its saved-key
                // rule) is read and written at call time; the keyboard never receives the text.
                val keyOwner = rememberMimirSecretFieldValueOwner(
                    read = { draftValue },
                    write = { edited ->
                        // While the saved key is untouched the draft IS the saved key, so the first-edit rule runs on the
                        // live draft read here: nothing in this writer holds the key.
                        val next = if (savedKeyUntouched) {
                            val first = steamGridDbFirstEditOfSavedKey(draftValue.text, draftValue, edited)
                            savedKeyUntouched = first.savedKeyUntouched
                            first.value
                        } else {
                            edited
                        }
                        val textChanged = next.text != draftValue.text
                        draftValue = next
                        if (textChanged) {
                            SteamGridDbKeyDraftStash.value = next.text
                            onDraftChanged()
                        }
                    },
                )
                MimirSecretFieldKeyboard(
                    owner = keyOwner,
                    // Read at call time: no closure here may hold the typed text.
                    onDone = { onSave(draftValue.text.trim()) },
                    controller = controller,
                    keyDecorator = keyDecorator,
                    targetLabel = "SteamGridDB key",
                    targetLabelIntent = MimirTextIntent.Mixed(
                        listOf(
                            MimirTextFragment.Authored("SteamGridDB"),
                            MimirTextFragment.Chrome(" key"),
                        ),
                    ),
                )
            }
        }
    }
}

/** The draft after an edit of the untouched saved key, and whether the saved key is still untouched. */
internal data class SteamGridDbSavedKeyEdit(val value: TextFieldValue, val savedKeyUntouched: Boolean)

/**
 * The edit of a saved key that is drawn only as dots. Typed or pasted text replaces the key, wherever touch had put
 * the caret or selection among the dots. Whitespace alone is no edit. The first delete selects the whole key and
 * leaves it saved, so controller B (which presses Done) still keeps and re-validates it; a second delete then clears
 * it, and typing replaces the selection.
 */
internal fun steamGridDbFirstEditOfSavedKey(
    savedKey: String,
    previous: TextFieldValue,
    edited: TextFieldValue,
): SteamGridDbSavedKeyEdit {
    if (edited.text == savedKey) return SteamGridDbSavedKeyEdit(edited, savedKeyUntouched = true)
    val wholeKeySelected = previous.text == savedKey &&
        previous.selection.min == 0 && previous.selection.max == savedKey.length
    if (wholeKeySelected) {
        return when {
            edited.text.isEmpty() -> SteamGridDbSavedKeyEdit(edited, savedKeyUntouched = false)
            edited.text.isBlank() -> SteamGridDbSavedKeyEdit(previous, savedKeyUntouched = true)
            else -> SteamGridDbSavedKeyEdit(edited, savedKeyUntouched = false)
        }
    }
    val inserted = steamGridDbTextInsertedIntoSavedKey(savedKey, previous, edited.text)
    if (inserted.isNullOrEmpty()) {
        return SteamGridDbSavedKeyEdit(
            TextFieldValue(text = savedKey, selection = TextRange(0, savedKey.length)),
            savedKeyUntouched = true,
        )
    }
    if (inserted.isBlank()) return SteamGridDbSavedKeyEdit(previous, savedKeyUntouched = true)
    return SteamGridDbSavedKeyEdit(
        TextFieldValue(text = inserted, selection = TextRange(inserted.length)),
        savedKeyUntouched = false,
    )
}

/**
 * The text a key press or paste put in place of the caret or selection that [previous] held on the saved key, or
 * null when the edit is not one insertion there (a delete at a collapsed caret). It is cut out by position only: the
 * inserted text is never compared with the saved key, so a pasted key that starts or ends like the saved one keeps
 * every character.
 */
internal fun steamGridDbTextInsertedIntoSavedKey(savedKey: String, previous: TextFieldValue, edited: String): String? {
    if (previous.text != savedKey) return null
    val start = previous.selection.min.coerceIn(0, savedKey.length)
    val end = previous.selection.max.coerceIn(start, savedKey.length)
    val insertedLength = edited.length - (savedKey.length - (end - start))
    if (insertedLength < 0) return null
    if (!edited.startsWith(savedKey.substring(0, start)) || !edited.endsWith(savedKey.substring(end))) return null
    return edited.substring(start, start + insertedLength)
}

/** BM03: unfinished key drafts survive an accidental dismissal (cleared on save; process-lifetime). */
internal object SteamGridDbKeyDraftStash {
    var value: String? = null
}

/** Clear process-lifetime draft state only after a durable save/disconnect succeeds. */
fun clearSteamGridDbKeyDraft() {
    SteamGridDbKeyDraftStash.value = null
}
