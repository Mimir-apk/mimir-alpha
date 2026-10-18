package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.gruning.mimir.rewrite.core.ui.GlassMenuCapsule
import com.gruning.mimir.rewrite.core.ui.LocalMimirKeyboardHapticFeedback
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirFocusAlwaysArmed
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.PlainMenuSeparator
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReportPress

/*
 * The Bottom's one choice dialog.
 *
 * There used to be two drifted copies of "Opens on": the Presentation's game display chooser and the quick-launch layer's choice (which
 * also drew PAUSE GAME). Both set the heading in the 22 sp Title role and the actions as ControlHint text on bare clickables, and
 * PAUSE GAME set its paragraph in the heading's own type. They are now this one dialog, on Main's menu grammar (HomeTileMenuOverlay):
 *
 *  - the accent eyebrow, then the heading in the overlay-title role (18 sp, authored case);
 *  - a paragraph, when there is one, in the Support role (14 sp, wraps, never ellipsized: a warning that is cut is no warning);
 *  - a beam between the actions ([PlainMenuSeparator]) and each action a promoted [GlassMenuCapsule] on a row at least 48 dp tall.
 *
 * Touch-first, as the shelf is: a tap is a plain `detectTapGestures` node with button semantics, never `clickable` (which would
 * register a focus target beside the one FocusAuthority) and never `mimirFocusable`: the Bottom has no pad cursor. A capsule is therefore
 * unlit unless the caller says one is [CompanionChoiceAction.lit] (the player's current choice, or the one action that goes forward,
 * as the launch card's PLAY is always lit), and a lit capsule flares on the dialog's own arrival ([armed]).
 */

internal const val CompanionChoiceDialogTag = "companion-choice-dialog"
internal const val CompanionChoicePanelTag = "companion-choice-panel"
internal const val CompanionChoiceEyebrowTag = "companion-choice-eyebrow"
internal const val CompanionChoiceHeadingTag = "companion-choice-heading"
internal const val CompanionChoiceBodyTag = "companion-choice-body"
internal const val CompanionChoiceSeparatorTag = "companion-choice-separator"
internal const val CompanionChoiceActionTag = "companion-choice-action"

internal const val CompanionOpensOnEyebrow = "ᛗ · OPENS ON"
internal const val CompanionPauseGameEyebrow = "ᛗ · PAUSE GAME"
internal const val CompanionOpensOnFallbackHeading = "Choose where it opens"

/**
 * PAUSE GAME's paragraph. "Bottom screen" is the player's word for the display (the action beside it says "Bottom screen"); it said
 * "lower screen" before the terminology change. The words are otherwise as they were; only the type they are set in changed.
 */
internal fun companionPauseGameCopy(label: String?): String =
    "${label ?: "This app"} opens on the Bottom screen. The game pauses behind Mimir; Return to game brings it back. " +
        "Android may close a paused game after about ten minutes; save first if you can."

/** One action of [CompanionChoiceDialog]. [lit] draws the selection ring and its flare on the capsule. */
class CompanionChoiceAction(
    val label: String,
    val onTap: () -> Unit,
    val lit: Boolean = false,
)

/**
 * The dialog: [eyebrow], then an optional [heading] and [body], then [actions] under a beam each. [enabled] false is a dialog that is
 * leaving or stale (it takes no tap and its actions are muted). A tap outside the panel is [onDismiss]; a tap inside it that lands
 * on nothing is absorbed, so it never reaches the scrim. [armed] reads snapshot state: the dialog's own arrival, which a lit
 * capsule's gleam and flare wait for.
 */
@Composable
fun CompanionChoiceDialog(
    eyebrow: String,
    heading: String?,
    body: String?,
    actions: List<CompanionChoiceAction>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    armed: () -> Boolean = MimirFocusAlwaysArmed,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier = modifier.fillMaxSize().testTag(CompanionChoiceDialogTag),
        contentAlignment = Alignment.Center,
    ) {
        MimirLiquidGlassScrim(
            modifier = Modifier
                .fillMaxSize()
                .companionChoiceTap(enabled = enabled, label = "Dismiss") { latestDismiss() },
        )
        MimirLiquidGlassPanel(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .testTag(CompanionChoicePanelTag)
                // Absorbs a tap that lands on the panel's own glass, so it never reaches the dismissing scrim beneath.
                .pointerInput(Unit) { detectTapGestures { } },
            contentPadding = MimirSpacing.xl,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            gradientBody = false,
        ) {
            val palette = LocalMimirPalette.current
            MimirChromeText(
                text = eyebrow,
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(CompanionChoiceEyebrowTag),
            )
            if (heading != null) {
                // Authored text (a game's or an app's name): never transformed, so Text and not chrome.
                Text(
                    text = heading,
                    style = MimirTypeScale.OverlayTitle,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MimirSpacing.lg).testTag(CompanionChoiceHeadingTag),
                )
            }
            if (body != null) {
                // Prose: Support, sentence case, as long as it needs to be. No maxLines: the panel wraps its content.
                Text(
                    text = body,
                    style = MimirTypeScale.Support,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MimirSpacing.lg).testTag(CompanionChoiceBodyTag),
                )
            }
            actions.forEach { action ->
                Column {
                    Box(Modifier.testTag(CompanionChoiceSeparatorTag)) { PlainMenuSeparator() }
                    CompanionChoiceRow(action = action, enabled = enabled, armed = armed)
                }
            }
        }
    }
}

/** A row at least 48 dp tall whose whole width takes the tap; the capsule inside is the label's own ring. */
@Composable
private fun CompanionChoiceRow(action: CompanionChoiceAction, enabled: Boolean, armed: () -> Boolean) {
    val palette = LocalMimirPalette.current
    val haptic by rememberUpdatedState(LocalMimirKeyboardHapticFeedback.current)
    // The Bottom's one press response (R4, B5): the row, capsule and all, draws in to the token while a finger holds it.
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .testTag(CompanionChoiceActionTag)
            .companionChoiceTap(enabled = enabled, label = action.label, interactionSource = source) {
                haptic?.invoke()
                action.onTap()
            }
            // After the tap, so the row (capsule and all) draws in while its hit box stays whole.
            .mimirPressResponse(source)
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        GlassMenuCapsule(focused = action.lit && enabled, identity = action.label, flare = true, armed = armed) {
            MimirChromeText(
                text = action.label,
                style = MimirTypeScale.MenuAction,
                color = when {
                    !enabled -> palette.textMuted
                    action.lit -> palette.textPrimary
                    else -> palette.textPrimary.copy(alpha = 0.72f)
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * The "Opens on" dialog, for a game ([CompanionQuickLaunchDestination] is the same three answers for an app): the heading is the
 * thing being opened, the actions are where. "Ask next time" is offered only when the player is [editing] a saved choice.
 * [current] is the saved choice when the caller knows it, and lights its capsule.
 */
@Composable
fun CompanionOpensOnDialog(
    label: String?,
    editing: Boolean,
    enabled: Boolean,
    onSelect: (CompanionQuickLaunchDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    current: CompanionQuickLaunchDestination? = null,
    armed: () -> Boolean = MimirFocusAlwaysArmed,
) {
    val actions = buildList {
        add(CompanionChoiceAction("Main screen", { onSelect(CompanionQuickLaunchDestination.MAIN) }, lit = current == CompanionQuickLaunchDestination.MAIN))
        add(CompanionChoiceAction("Bottom screen", { onSelect(CompanionQuickLaunchDestination.BOTTOM) }, lit = current == CompanionQuickLaunchDestination.BOTTOM))
        if (editing) {
            add(
                CompanionChoiceAction(
                    "Ask next time",
                    { onSelect(CompanionQuickLaunchDestination.ASK_NEXT_TIME) },
                    lit = current == CompanionQuickLaunchDestination.ASK_NEXT_TIME,
                ),
            )
        }
        add(CompanionChoiceAction("Cancel", onDismiss))
    }
    CompanionChoiceDialog(
        eyebrow = CompanionOpensOnEyebrow,
        heading = label ?: CompanionOpensOnFallbackHeading,
        body = null,
        actions = actions,
        enabled = enabled,
        onDismiss = onDismiss,
        modifier = modifier,
        armed = armed,
    )
}

/**
 * PAUSE GAME: a Bottom launch that covers a live Main DS/3DS lower screen needs an explicit yes. [Continue] is the one action that goes
 * forward, so it is the lit one (the PLAY rule); Cancel stays plain.
 */
@Composable
fun CompanionPauseGameDialog(
    label: String?,
    enabled: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    armed: () -> Boolean = MimirFocusAlwaysArmed,
) {
    CompanionChoiceDialog(
        eyebrow = CompanionPauseGameEyebrow,
        heading = null,
        body = companionPauseGameCopy(label),
        actions = listOf(
            CompanionChoiceAction("Continue", onContinue, lit = true),
            CompanionChoiceAction("Cancel", onDismiss),
        ),
        enabled = enabled,
        onDismiss = onDismiss,
        modifier = modifier,
        armed = armed,
    )
}

/**
 * A tap on the Bottom, the way the shelf takes one: a `detectTapGestures` node with button semantics (so a screen reader can press it),
 * inert and said to be when [enabled] is false. The detector calls the latest [onTap], not the one in scope when it started.
 */
@Composable
internal fun Modifier.companionChoiceTap(
    enabled: Boolean,
    label: String,
    interactionSource: MutableInteractionSource? = null,
    onTap: () -> Unit,
): Modifier {
    val latestTap by rememberUpdatedState(onTap)
    // Read through the state, not captured: the gesture's keys are only `enabled`, so a source that is replaced while it is unchanged must still get the presses.
    val latestSource by rememberUpdatedState(interactionSource)
    return this
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = label
            if (enabled) onClick(label = label) { latestTap(); true } else disabled()
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = { mimirReportPress(latestSource, it) }, onTap = { latestTap() })
        }
}
