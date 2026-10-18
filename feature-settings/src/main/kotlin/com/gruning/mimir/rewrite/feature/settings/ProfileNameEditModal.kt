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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * The profile display-name editor — a full-screen touch overlay that reuses the ONE in-app [MimirKeyboard]
 * (typing is 100% touch; the controller only DISMISSES). Same shape
 * as the gamelist / Browse search keyboard: a live preview field above, the keys pinned at the bottom, Done
 * saves, Back/scrim cancels. No `mimirFocusable` here — the keyboard is touch-only by design; the app shell
 * swallows controller nav while this is open and routes Back → [onCancel].
 *
 * The name is trimmed but otherwise keeps the user’s authored case.
 *
 * @param currentName the name to seed the draft with ("" when none is set yet).
 * @param onSave the finished, trimmed name (may be "" to clear — a name is always optional).
 * @param onCancel dismiss without saving (Done not pressed).
 */
@Composable
fun ProfileNameEditOverlay(
    currentName: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
) {
    // The scrim tap used to destroy the remember{}-local draft with the overlay. The draft now
    // lives in a process-lifetime stash — reopening resumes typing; only Done/save clears it.
    var draft by remember { mutableStateOf(ProfileNameDraftStash.value ?: currentName) }
    val display = draft.trim()
    Box(
        modifier = Modifier
            .fillMaxSize()
            // A near-opaque themed scrim; a tap on it (outside the keys) cancels — standard modal dismiss,
            // non-destructive (the draft survives in the stash). No-indication click so it reads as a scrim.
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
                    MimirChromeText("Display name", style = MimirType.RunicEyebrow, color = MimirColors.Gold)
                    if (!compactPreview) {
                        MimirIntentText(
                            intent = if (display.isBlank()) {
                                MimirTextIntent.StaticChrome("Type a name")
                            } else {
                                MimirTextIntent.Authored(display)
                            },
                            style = if (display.isBlank()) MimirType.TitleCaps else MimirType.OverlayTitle,
                            color = if (display.isBlank()) MimirColors.TextMuted else MimirColors.TextPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "A name is optional — everything stays on this device.",
                            style = MimirType.RowSupport,
                            color = MimirColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = MimirSpacing.sm),
                        )
                    }
                }
                // THE shared in-app keyboard — touch only. Done preserves the user-authored name.
                MimirKeyboard(
                    value = draft,
                    onValueChange = {
                        draft = it
                        ProfileNameDraftStash.value = it
                    },
                    onDone = {
                        ProfileNameDraftStash.value = null
                        onSave(display)
                    },
                    controller = controller,
                    keyDecorator = keyDecorator,
                    targetLabel = "Display name",
                )
            }
        }
    }
}

/** BM03: unfinished name drafts survive an accidental dismissal (cleared on save; process-lifetime). */
internal object ProfileNameDraftStash {
    var value: String? = null
}
