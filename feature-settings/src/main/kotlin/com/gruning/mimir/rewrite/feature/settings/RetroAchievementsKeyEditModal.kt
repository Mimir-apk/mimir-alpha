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
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirSecretFieldKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/** Every visible state in the validated RetroAchievements connect flow. */
sealed interface RetroAchievementsConnectUi {
    data class EnterUsername(val initialUsername: String = "") : RetroAchievementsConnectUi
    data class EnterKey(val username: String) : RetroAchievementsConnectUi
    data class Validating(val username: String) : RetroAchievementsConnectUi
    data class Accepted(val username: String) : RetroAchievementsConnectUi
    data class Rejected(val username: String, val field: RejectedField) : RetroAchievementsConnectUi
    data class Unreachable(val username: String) : RetroAchievementsConnectUi
    data class SaveFailed(val username: String) : RetroAchievementsConnectUi
    data class RecoveryUncertain(val username: String) : RetroAchievementsConnectUi

    enum class RejectedField { USERNAME, API_KEY }
}

/**
 * The RetroAchievements account editor. A candidate is never described as connected here: Done submits the
 * username/key pair to the host, [RetroAchievementsConnectUi.Validating] holds the modal shut while the
 * live check runs, [RetroAchievementsConnectUi.RecoveryUncertain] holds it shut until durable cleanup, and
 * only [RetroAchievementsConnectUi.Accepted] may render success.
 *
 * Drafts live in process memory only while the entry steps are active. Every terminal result and explicit
 * cancel clears the plaintext key; retry carries only the non-secret username in its typed state.
 *
 * the Web API key step is masked like the ScreenScraper password. The keyboard field and
 * the preview line draw one dot per character while typing or pasting. The username step stays readable.
 */
@Composable
fun RetroAchievementsKeyEditOverlay(
    state: RetroAchievementsConnectUi,
    onUsernameEntered: (String) -> Unit,
    onValidate: (username: String, apiKey: String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
) {
    val mayClose = retroAchievementsConnectMayClose(state)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase.copy(alpha = 0.95f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (mayClose) onClose() },
            ),
    ) {
        when (state) {
            is RetroAchievementsConnectUi.EnterUsername -> UsernameStep(
                initialUsername = state.initialUsername,
                onDone = onUsernameEntered,
                controller = controller,
                keyDecorator = keyDecorator,
            )

            is RetroAchievementsConnectUi.EnterKey -> KeyStep(
                username = state.username,
                onDone = { key -> onValidate(state.username, key) },
                controller = controller,
                keyDecorator = keyDecorator,
            )

            is RetroAchievementsConnectUi.Validating -> ResultCard {
                MimirIntentText(
                    intent = MimirTextIntent.Mixed(
                        listOf(
                            MimirTextFragment.Chrome("Checking your key with "),
                            MimirTextFragment.Authored("RetroAchievements"),
                            MimirTextFragment.Chrome("…"),
                        ),
                    ),
                    style = MimirType.MicroStatus,
                    color = MimirColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                MimirIntentText(
                    intent = MimirTextIntent.Mixed(
                        listOf(
                            MimirTextFragment.Chrome("Checking "),
                            MimirTextFragment.Authored(state.username),
                        ),
                    ),
                    style = MimirType.RowSupport,
                    color = MimirColors.TextMuted,
                )
            }

            is RetroAchievementsConnectUi.Accepted -> ResultCard {
                MimirChromeText(
                    MimirAchievementCopy.ConnectAcceptedTitle,
                    style = MimirType.TitleCaps,
                    color = MimirColors.SuccessGreen,
                )
                Text(
                    MimirAchievementCopy.ConnectAcceptedBodyPrefix + state.username + ".",
                    style = MimirType.RowSupport,
                    color = MimirColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    MimirAchievementCopy.EarnedInEmulator,
                    style = MimirType.RowSupport,
                    color = MimirColors.TextMuted,
                    textAlign = TextAlign.Center,
                )
                ResultButton(label = "Done", onClick = onClose)
            }

            is RetroAchievementsConnectUi.Rejected -> ResultCard {
                val rejectedTitle = if (state.field == RetroAchievementsConnectUi.RejectedField.USERNAME) {
                        "Username not accepted"
                    } else {
                        "Web API key not accepted"
                    }
                MimirChromeText(
                    rejectedTitle,
                    style = MimirType.TitleCaps,
                    color = MimirColors.Danger,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (state.field == RetroAchievementsConnectUi.RejectedField.USERNAME) {
                        MimirAchievementCopy.ConnectUsernameRejected
                    } else {
                        MimirAchievementCopy.ConnectKeyRejected
                    },
                    style = MimirType.RowSupport,
                    color = MimirColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                ResultButton(label = "Try again", onClick = onRetry)
            }

            is RetroAchievementsConnectUi.Unreachable -> ResultCard {
                MimirChromeText(
                    "Couldn’t check",
                    style = MimirType.TitleCaps,
                    color = MimirColors.TextPrimary,
                )
                Text(
                    MimirAchievementCopy.ConnectUnreachable,
                    style = MimirType.RowSupport,
                    color = MimirColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                ResultButton(label = "Try again", onClick = onRetry)
            }

            is RetroAchievementsConnectUi.SaveFailed -> ResultCard {
                MimirChromeText(
                    "Couldn’t save",
                    style = MimirType.TitleCaps,
                    color = MimirColors.TextPrimary,
                )
                Text(
                    MimirAchievementCopy.ConnectSaveFailed,
                    style = MimirType.RowSupport,
                    color = MimirColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                ResultButton(label = "Try again", onClick = onRetry)
            }

            is RetroAchievementsConnectUi.RecoveryUncertain -> ResultCard {
                MimirChromeText(
                    "Recovery required",
                    style = MimirType.TitleCaps,
                    color = MimirColors.Danger,
                )
                Text(
                    MimirAchievementCopy.ConnectRecoveryUncertain,
                    style = MimirType.RowSupport,
                    color = MimirColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                ResultButton(label = "Retry cleanup", onClick = onRetry)
            }
        }
    }
}

@Composable
private fun UsernameStep(
    initialUsername: String,
    onDone: (String) -> Unit,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
) {
    var username by remember(initialUsername) {
        mutableStateOf(RetroAchievementsDraftStash.username ?: initialUsername)
    }
    CredentialStep(
        eyebrow = "RETROACHIEVEMENTS USERNAME",
        value = username,
        emptyPrompt = "Type your username",
        // OR-14: the key's location is named on this first step too, so the player can fetch it before Done.
        supporting = "Your RetroAchievements username. Next comes your Web API key, from retroachievements.org › " +
            "Settings › Web API Key. Back cancels.",
        targetLabel = "RetroAchievements username",
        secret = false,
        onValueChange = {
            username = it
            RetroAchievementsDraftStash.username = it
        },
        onDone = {
            username.trim().takeIf(String::isNotBlank)?.let(onDone)
        },
        controller = controller,
        keyDecorator = keyDecorator,
    )
}

@Composable
private fun KeyStep(
    username: String,
    onDone: (String) -> Unit,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
) {
    var apiKey by remember(username) { mutableStateOf(RetroAchievementsDraftStash.apiKey.orEmpty()) }
    val keyOwner = com.gruning.mimir.rewrite.core.ui.rememberMimirSecretStringOwner(
        readText = { apiKey },
        writeText = {
            apiKey = it
            RetroAchievementsDraftStash.apiKey = it
        },
    )
    CredentialStep(
        eyebrow = "RETROACHIEVEMENTS KEY",
        value = apiKey,
        emptyPrompt = "Paste your Web API key",
        supporting = "Find it at retroachievements.org › Settings › Web API Key. Mimir checks it live and " +
            "stores it encrypted only if RetroAchievements accepts both fields. Progress will be read for $username.",
        targetLabel = "RetroAchievements key",
        secret = true,
        secretOwner = keyOwner,
        onValueChange = {
            apiKey = it
            RetroAchievementsDraftStash.apiKey = it
        },
        onDone = {
            apiKey.trim().takeIf(String::isNotBlank)?.let(onDone)
        },
        controller = controller,
        keyDecorator = keyDecorator,
    )
}

/** Shared by scrim and Back routing so validation or an unresolved durable record cannot be dismissed. */
fun retroAchievementsConnectMayClose(state: RetroAchievementsConnectUi): Boolean =
    state !is RetroAchievementsConnectUi.Validating &&
        state !is RetroAchievementsConnectUi.RecoveryUncertain

@Composable
private fun CredentialStep(
    eyebrow: String,
    value: String,
    emptyPrompt: String,
    supporting: String,
    targetLabel: String,
    secret: Boolean,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    secretOwner: com.gruning.mimir.rewrite.core.ui.MimirSecretFieldOwner? = null,
    controller: com.gruning.mimir.rewrite.core.ui.MimirKeyboardController? = null,
    keyDecorator: com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator? = null,
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
                            MimirTextFragment.Authored("RetroAchievements"),
                            MimirTextFragment.Chrome(eyebrow.removePrefix("RETROACHIEVEMENTS")),
                        ),
                    ),
                    style = MimirType.RunicEyebrow,
                    color = MimirColors.Gold,
                )
                if (!compactPreview) {
                    val trimmed = value.trim()
                    MimirIntentText(
                        intent = if (trimmed.isBlank()) {
                            MimirTextIntent.StaticChrome(emptyPrompt)
                        } else if (secret) {
                            // dots, as the ScreenScraper password step draws them.
                            MimirTextIntent.Authored("•".repeat(trimmed.length))
                        } else {
                            MimirTextIntent.Authored(trimmed)
                        },
                        style = if (trimmed.isBlank()) MimirType.TitleCaps else MimirType.OverlayTitle,
                        color = if (value.isBlank()) MimirColors.TextMuted else MimirColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        supporting,
                        style = MimirType.RowSupport,
                        color = MimirColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = MimirSpacing.sm),
                    )
                }
            }
            val intent = MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Authored("RetroAchievements"),
                    MimirTextFragment.Chrome(targetLabel.removePrefix("RetroAchievements")),
                ),
            )
            if (secret && secretOwner != null) {
                // D33 fix round: the key is typed through the ops-only keyboard, which never receives the text.
                MimirSecretFieldKeyboard(
                    owner = secretOwner,
                    onDone = onDone,
                    controller = controller,
                    keyDecorator = keyDecorator,
                    targetLabel = targetLabel,
                    targetLabelIntent = intent,
                    // Product review 2026-09-28 (F2): B leaves this editor on both steps (F1), so the strip says so.
                    controllerLegendAction = "Back",
                )
            } else {
                MimirKeyboard(
                    value = value,
                    onValueChange = onValueChange,
                    onDone = onDone,
                    controller = controller,
                    keyDecorator = keyDecorator,
                    targetLabel = targetLabel,
                    targetLabelIntent = intent,
                    controllerLegendAction = "Back",
                )
            }
        }
    }
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
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

/** An unselected etched chip inside a 48 dp hit box. */
@Composable
private fun ResultButton(label: String, onClick: () -> Unit) {
    val palette = LocalMimirPalette.current
    Box(
        Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(palette = palette) {
            MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(), maxLines = 1)
        }
    }
}

/** Process-memory only; never logged or written to preferences. */
internal object RetroAchievementsDraftStash {
    var username: String? = null
    var apiKey: String? = null

    fun clear() {
        username = null
        apiKey = null
    }
}

/** Host seam used only after a validated save or explicit disconnect. */
fun clearRetroAchievementsConnectDraft() {
    RetroAchievementsDraftStash.clear()
}
