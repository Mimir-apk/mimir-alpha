package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

internal val CompanionPermissionScope = FocusScope("companion/permission")

internal val CompanionPermissionDismissKey = FocusKey("companion/permission/dismiss")

internal val CompanionPermissionOpenSettingsKey = FocusKey("companion/permission/open-settings")

internal fun companionPermissionDismissBounds(): FocusBounds =
    FocusBounds(left = 0f, top = 0f, right = 100f, bottom = 90f)

internal fun companionPermissionOpenSettingsBounds(): FocusBounds =
    FocusBounds(left = 100f, top = 0f, right = 200f, bottom = 90f)

/** Strict order per the focus-scope law: pushScope → registerVirtual → requestFocus (else focus no-ops). */
internal fun mountCompanionPermissionScope(authority: FocusAuthority) {
    if (authority.state.value.scope != CompanionPermissionScope) {
        authority.pushScope(CompanionPermissionScope)
    }
    authority.registerVirtual(
        key = CompanionPermissionDismissKey,
        scope = CompanionPermissionScope,
        bounds = companionPermissionDismissBounds(),
    )
    authority.registerVirtual(
        key = CompanionPermissionOpenSettingsKey,
        scope = CompanionPermissionScope,
        bounds = companionPermissionOpenSettingsBounds(),
    )
    authority.requestFocus(CompanionPermissionOpenSettingsKey)
}

internal fun unmountCompanionPermissionScope(authority: FocusAuthority) {
    authority.unregisterVirtual(CompanionPermissionDismissKey)
    authority.unregisterVirtual(CompanionPermissionOpenSettingsKey)
    authority.discardScope(CompanionPermissionScope)
}


/**
 * Controller reducer for the companion overlay-permission modal. Left/Right move between the exact
 * dismiss and open-settings keys; Confirm invokes the focused action; Cancel/System Back dismisses;
 * every other action is swallowed.
 */
internal fun dispatchCompanionPermission(
    authority: FocusAuthority,
    action: ControllerAction,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    when (action) {
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> authority.moveFocus(action)
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> onDismiss()
        ControllerAction.CONFIRM -> when (authority.state.value.focused) {
            CompanionPermissionOpenSettingsKey -> onGrant()
            CompanionPermissionDismissKey -> onDismiss()
            else -> Unit
        }
        else -> Unit
    }
    return true
}

/**
 * BG / overlay-permission ( over the dead toast): when the user opens the tab
 * manager but the `TYPE_APPLICATION_OVERLAY` window can't attach for lack of "Draw over other apps"
 * (`CompanionRecentsState.ui.awaitingOverlayPermission`), Mimir showed a toast and the Tabs request just sat
 * unreachable. This actionable prompt takes its place on the PRIMARY display — where Mimir is visible — with a
 * real **Grant** that fires the system settings intent (`requestOverlayPermission()`, already wired). On return
 * with the appop granted, the overlay host re-attaches (onResume / overlayPermissionMissing) and clears the
 * awaiting flag, which dismisses this. **Not now** drops the Tabs request.
 *
 * Touch-first, but each button is a [FocusAuthority] target (D-pad + A) with Open settings pre-focused.
 */
@Composable
fun CompanionOverlayPermissionPrompt(
    authority: FocusAuthority,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()

    DisposableEffect(authority) {
        mountCompanionPermissionScope(authority)
        onDispose {
            unmountCompanionPermissionScope(authority)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC05070B))
            // Swallow every touch so the shell behind the scrim can't be driven while the prompt is up.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(palette.surfaceRaised)
                .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MimirChromeText(
                stringResource(R.string.app_overlay_prompt_eyebrow),
                visualOverride = stringResource(R.string.app_overlay_prompt_eyebrow_visual),
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
            )
            MimirChromeText(
                stringResource(R.string.app_overlay_prompt_title),
                style = MimirTypeScale.TitleCaps,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.app_overlay_prompt_body),
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                // The two chips are as wide as their words (a label never wraps, the earlier update) and stand a ring's whole reach apart, centred: the old equal halves were narrower than "Open settings"
                // at a 1.3x font and cut it to "Open". The row's width is the card's content width, which holds both at 1.3x.
                horizontalArrangement = Arrangement.spacedBy(MimirFocusTileRingReach, Alignment.CenterHorizontally),
            ) {
                PromptButton(
                    text = stringResource(R.string.app_common_not_now),
                    primary = false,
                    focusKey = CompanionPermissionDismissKey,
                    focused = focus.scope == CompanionPermissionScope &&
                        focus.focused == CompanionPermissionDismissKey,
                    authority = authority,
                    onClick = onDismiss,
                )
                PromptButton(
                    text = stringResource(R.string.app_overlay_prompt_open_settings),
                    primary = true,
                    focusKey = CompanionPermissionOpenSettingsKey,
                    focused = focus.scope == CompanionPermissionScope &&
                        focus.focused == CompanionPermissionOpenSettingsKey,
                    authority = authority,
                    onClick = onGrant,
                )
            }
            MimirControlHintText(
                text = stringResource(R.string.app_overlay_prompt_hint),
                style = MimirTypeScale.ControlHintCompact,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PromptButton(
    text: String,
    primary: Boolean,
    focusKey: FocusKey,
    focused: Boolean,
    authority: FocusAuthority,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    // An etched chip inside a 48 dp hit box; never a filled or outlined rectangle.
    // [primary] is the prompt's main answer (the same dark plate, its rim and word in the accent), never a fill. The ring is the shared selection ring on
    // the PLATE itself, with no inset and BEFORE the surface, so it is the plate's one edge. The panel's 24 dp padding clears the ring's reach.
    Box(
        modifier = modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, focusKey, CompanionPermissionScope)
            .mimirActivatable(label = text, authority = authority, key = focusKey, onActivate = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(
            modifier = Modifier.testTag(etchedChipPlateTag("permission", text)).mimirSelectionRing(focused = focused, identity = focusKey, shape = MimirRingShape.Pill, edgeFlush = true),
            focused = focused,
            primary = primary,
            palette = palette,
            edgeFlush = true,
        ) {
            MimirChromeText(text, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(primary = primary), maxLines = 1, softWrap = false)
        }
    }
}
