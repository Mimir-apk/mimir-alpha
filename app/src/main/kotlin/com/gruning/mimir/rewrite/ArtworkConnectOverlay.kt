package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirSecretFieldKeyboard
import com.gruning.mimir.rewrite.core.ui.rememberMimirSecretStringOwner
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * The "connect your ScreenScraper account" overlay — a full-screen TOUCH flow reusing the one in-app
 * [MimirKeyboard] . Two steps (username → password) because the
 * shared keyboard edits one value at a time, then the host validates LIVE and reports the result. The
 * app shell swallows controller nav while open and routes Back → [onCancel] (same as the name editor).
 *
 * Copy is the artwork-review §6 microcopy. The dev soft-name is never surfaced — the user only ever sees
 * ScreenScraper.fr + their own account.
 */
sealed interface ArtworkConnectUi {
    data object EnterId : ArtworkConnectUi
    data class EnterPassword(val id: String) : ArtworkConnectUi
    data object Connecting : ArtworkConnectUi
    data class Done(val account: String, val dailyLimit: Int, val usedToday: Int) : ArtworkConnectUi
    /** [message] is the honest, reason-specific copy (dev key vs member sign-in vs busy vs offline). */
    data class Failed(val message: String) : ArtworkConnectUi
}

internal const val ScreenScraperAccountEyebrowCopy = "SCREENSCRAPER ACCOUNT"

@Composable
fun ArtworkConnectOverlay(
    state: ArtworkConnectUi,
    onIdEntered: (String) -> Unit,
    onPasswordEntered: (String) -> Unit,
    onClose: () -> Unit,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase.copy(alpha = 0.95f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        when (state) {
            is ArtworkConnectUi.EnterId -> KeyboardStep(
                prompt = "Your ScreenScraper username",
                promptIntent = MimirTextIntent.Mixed(
                    listOf(
                        MimirTextFragment.Chrome("Your "),
                        MimirTextFragment.Authored("ScreenScraper"),
                        MimirTextFragment.Chrome(" username"),
                    ),
                ),
                // A member sign-in is required — ScreenScraper's API has no anonymous/shared lookup (verified
                // live: ssuserInfos rejects a memberless call). Don't promise a "shared allowance" that fails.
                hint = "Use your own ScreenScraper account — there’s no shared login.",
                mask = false,
                onDone = onIdEntered,
                controller = controller,
                keyDecorator = keyDecorator,
            )
            is ArtworkConnectUi.EnterPassword -> KeyboardStep(
                prompt = "Password for ${state.id}",
                promptIntent = MimirTextIntent.Mixed(
                    listOf(
                        MimirTextFragment.Chrome("Password for "),
                        MimirTextFragment.Authored(state.id),
                    ),
                ),
                hint = "Kept on this device only — never synced, never shown in logs.",
                mask = true,
                onDone = onPasswordEntered,
                controller = controller,
                keyDecorator = keyDecorator,
            )
            is ArtworkConnectUi.Connecting -> CenterCard {
                MimirIntentText(
                    intent = MimirTextIntent.Mixed(
                        listOf(
                            MimirTextFragment.Chrome("Signing in to "),
                            MimirTextFragment.Authored("ScreenScraper"),
                            MimirTextFragment.Chrome("…"),
                        ),
                    ),
                    style = MimirType.MicroStatus,
                    color = MimirColors.TextSecondary,
                )
            }
            is ArtworkConnectUi.Done -> CenterCard {
                MimirChromeText("Connected", style = MimirType.TitleCaps, color = MimirColors.TextPrimary)
                Text(
                    "Signed in as ${state.account}. You’ve used ${state.usedToday} of ${state.dailyLimit} lookups today.",
                    style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center,
                )
                CloseButton(onClose)
            }
            is ArtworkConnectUi.Failed -> CenterCard {
                Text(
                    state.message,
                    style = MimirType.RowSupport, color = MimirColors.Danger, textAlign = TextAlign.Center,
                )
                CloseButton(onClose)
            }
        }
    }
}

@Composable
private fun KeyboardStep(
    prompt: String,
    promptIntent: MimirTextIntent,
    hint: String,
    mask: Boolean,
    onDone: (String) -> Unit,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
) {
    // Credential drafts survive keyboard dismissal and stray taps outside the flow:
    // the overlay's root scrim closes the WHOLE connect flow on a stray tap, which used to destroy
    // this remember{}-local draft mid-credential. Drafts now survive in a per-prompt, in-memory
    // stash (never persisted, never logged); each step's Done clears its own entry.
    var draft by remember(prompt) { mutableStateOf(ArtworkConnectDraftStash.values[prompt] ?: "") }
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = MimirSpacing.xxl, vertical = MimirSpacing.md),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MimirIntentText(
                intent = MimirTextIntent.Mixed(
                    listOf(
                        MimirTextFragment.Authored("ScreenScraper"),
                        MimirTextFragment.Chrome(" account"),
                    ),
                ),
                style = MimirType.RunicEyebrow,
                color = MimirColors.Gold,
            )
            MimirIntentText(
                intent = promptIntent,
                style = MimirType.RunicEyebrowCompact,
                color = MimirColors.TextMuted,
                modifier = Modifier.padding(top = MimirSpacing.xs),
            )
            Text(
                text = if (draft.isEmpty()) "…" else if (mask) "•".repeat(draft.length) else draft,
                style = MimirType.Title,
                color = if (draft.isEmpty()) MimirColors.TextMuted else MimirColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(hint, style = MimirType.RowSupport, color = MimirColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = MimirSpacing.sm))
        }
        val finish = {
            ArtworkConnectDraftStash.values.remove(prompt)
            // Read at call time: no closure here may hold the typed text.
            onDone(draft.trim())
        }
        if (mask) {
            // D33 fix round: a password is typed through the ops-only keyboard. The owner (this draft) is read and
            // written at call time by the owner object; the keyboard never receives the text.
            val owner = rememberMimirSecretStringOwner(
                readText = { draft },
                writeText = {
                    draft = it
                    ArtworkConnectDraftStash.values[prompt] = it
                },
            )
            MimirSecretFieldKeyboard(
                owner = owner,
                onDone = finish,
                controller = controller,
                keyDecorator = keyDecorator,
                targetLabel = prompt,
                targetLabelIntent = promptIntent,
            )
        } else {
            MimirKeyboard(
                value = draft,
                onValueChange = {
                    draft = it
                    ArtworkConnectDraftStash.values[prompt] = it
                },
                onDone = finish,
                controller = controller,
                keyDecorator = keyDecorator,
                targetLabel = prompt,
                targetLabelIntent = promptIntent,
            )
        }
    }
}

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(MimirColors.SurfaceRaised)
                .padding(MimirSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

/** An unselected etched chip inside a 48 dp hit box; never a filled capsule. */
@Composable
private fun CloseButton(onClose: () -> Unit) {
    val palette = LocalMimirPalette.current
    Box(
        Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(palette = palette) {
            MimirChromeText("Close", style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(), maxLines = 1)
        }
    }
}

/**
 * BM03: unfinished connect-step drafts survive an accidental scrim dismissal, keyed by step prompt.
 * In-memory only (never persisted, never logged — the password posture is unchanged); each step's
 * Done removes its own entry, and process death clears everything.
 */
internal object ArtworkConnectDraftStash {
    val values = mutableMapOf<String, String>()
}
