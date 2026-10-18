package com.gruning.mimir.rewrite

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirPageTouchShield
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/**
 * The player-facing half of [auditStorageGrants]: **say the grant is gone, and offer the one repair.**
 *
 * The Nova defect this exists for was not that art failed — it was that art failed SILENTLY. Every
 * SD-card lookup returned a `Permission Denial`, the shelf rendered whatever the app-private SteamGridDB
 * store happened to hold, and the missing permission could appear to be lost artwork.
 * A prompt that names the folder and offers the picker turns an invisible permission fault into a
 * fifteen-second fix.
 *
 * Shape, palette and focus handling are copied from [ArtworkFetchOverlay]'s `ConfirmNewGames` page
 * (`NewGameEnrichPrompt`'s render site) rather than re-invented: same near-opaque scrim that absorbs
 * touch and never dismisses, same 480dp raised card, same [MimirType.TitleCaps] title over a
 * [MimirType.RowSupport] body, same primary etched chip above a plain one, same
 * [mimirFocusable] + [mimirActivatable] pair with a pushed [FocusScope]. Palette values come only from
 * [MimirColors] (the `LocalMimirPalette` façade) and there is no Compose `focusable()`/`focusTarget()`
 * anywhere in this file — the app has exactly one FocusAuthority.
 */

/** Pinned in `StorageGrantPromptCopyTest`. Names the fault, not the mechanism. */
internal const val StorageGrantLostTitleCopy = "STORAGE ACCESS LOST"

/** Pinned in `StorageGrantPromptCopyTest`. */
internal const val StorageGrantReconnectCopy = "RECONNECT"

/** Pinned in `StorageGrantPromptCopyTest`. */
internal const val StorageGrantLaterCopy = "LATER"

/**
 * The body. Names the folder with the label the registry already shows the player ("SD card › ROMs"),
 * never a `content://` URI, and states both consequences the device run actually produced — failed art
 * and failed launches — so the sentence is a fact about this device and not a generic warning.
 */
internal fun storageGrantLostBodyCopy(friendlyLabel: String): String =
    "Mimir can no longer read \"$friendlyLabel\". " +
        "Art and launches from it will fail until you reconnect it."

internal val StorageGrantFocusScope = FocusScope("storage/grant-lost")
internal val StorageGrantReconnectKey = FocusKey("storage/grant-lost/reconnect")
internal val StorageGrantLaterKey = FocusKey("storage/grant-lost/later")

/**
 * The host site, composed from `MimirAppHost` in the modal layer.
 *
 * [visible] is the caller's "no overlay is open" verdict. Two separate silences, both required:
 * - nothing lost (or LATER pressed) → [storageGrantPromptRoot] returns null and this composes NOTHING,
 *   so a healthy shelf is never covered and never pays a composition for this feature;
 * - an overlay IS open → the prompt does not compose either, so it can never push its focus scope over a
 *   live picker or launch card and steal the pad from it. It renders on the next frame after that
 *   overlay closes, which is the same ordering every other member of this host uses.
 */
@Composable
internal fun StorageGrantPromptHost(authority: FocusAuthority, visible: Boolean) {
    val lost by StorageGrantAuditCoordinator.lostRoots.collectAsState()
    val deferred by StorageGrantAuditCoordinator.promptDeferred.collectAsState()
    StorageGrantPromptLayer(
        root = storageGrantPromptRoot(lost, deferred),
        visible = visible,
        authority = authority,
        onReconnect = StorageGrantAuditCoordinator::requestRegrant,
        onLater = StorageGrantAuditCoordinator::deferForProcess,
    )
}

/** What the host draws for the verdict it collected: stateless, so a test can drive [root] and [visible] directly. */
@Composable
internal fun StorageGrantPromptLayer(
    root: StoredRoot?,
    visible: Boolean,
    authority: FocusAuthority?,
    onReconnect: (StoredRoot) -> Unit,
    onLater: () -> Unit,
) {
    // RC-33 (audit 2026-10-14, O-33 / X-85): the prompt used to cut in on the frame no overlay was open and cut out on Later,
    // Reconnect or any overlay opening. It fades on the house clock both ways (`storageNoticeFadeSpec`), and the root it last showed
    // is retained until the leave has settled, so it has something to fade. While it leaves it is inert: no focus scope, no Back,
    // every touch shielded ([StorageGrantPrompt]'s `interactive`), so it never holds the pad or the shelf under it.
    val shown = root != null && visible
    var retained by remember { mutableStateOf<StoredRoot?>(null) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = shown
    val leaveSettled = visibility.isIdle && !visibility.currentState && !visibility.targetState
    val promptRoot = if (shown) root else if (leaveSettled) null else retained
    SideEffect { retained = promptRoot }
    if (promptRoot == null) return
    val reducedMotion = mimirReducedMotion()
    BackHandler(enabled = shown) { onLater() }
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(storageNoticeFadeSpec(reducedMotion)),
        exit = fadeOut(storageNoticeFadeSpec(reducedMotion)),
    ) {
        StorageGrantPrompt(
            root = promptRoot,
            onReconnect = { onReconnect(promptRoot) },
            onLater = onLater,
            authority = authority,
            interactive = shown,
        )
    }
}

@Composable
internal fun StorageGrantPrompt(
    root: StoredRoot,
    onReconnect: () -> Unit,
    onLater: () -> Unit,
    authority: FocusAuthority? = null,
    /** False while the prompt fades out ([StorageGrantPromptHost]): it holds no focus and takes no touch, so nothing under it is shadowed. */
    interactive: Boolean = true,
) {
    // The prompt's own authority is the real one while it is live and none while it leaves, so the scope it pushed is discarded in
    // the frame the leave begins and its buttons stop registering, not 180 ms later.
    StorageGrantPromptBody(
        root = root,
        onReconnect = onReconnect,
        onLater = onLater,
        authority = authority.takeIf { interactive },
        interactive = interactive,
    )
}

@Composable
private fun StorageGrantPromptBody(
    root: StoredRoot,
    onReconnect: () -> Unit,
    onLater: () -> Unit,
    authority: FocusAuthority?,
    interactive: Boolean,
) {
    // The RING channel of the two-channel focus law: null the instant this scope stops being active, so a
    // covered prompt never claims input it does not have.
    val focusedKey = authority?.state?.collectAsState()?.value
        ?.takeIf { it.scope == StorageGrantFocusScope }
        ?.focused
    DisposableEffect(authority) {
        if (authority != null && authority.state.value.scope != StorageGrantFocusScope) {
            authority.pushScope(StorageGrantFocusScope)
        }
        onDispose { authority?.discardScope(StorageGrantFocusScope) }
    }
    LaunchedEffect(authority) {
        if (authority == null) return@LaunchedEffect
        // One frame after the push, so the two buttons have registered their bounds before focus lands —
        // the same beat ArtworkFetchOverlay waits for before requesting NewGameEnrichConfirmKey.
        withFrameNanos { }
        authority.requestFocus(StorageGrantReconnectKey)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase.copy(alpha = 0.96f))
            // Absorb every touch — the scrim NEVER dismisses; exit is only via the explicit button.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(MimirColors.SurfaceRaised)
                .padding(MimirSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MimirChromeText(
                StorageGrantLostTitleCopy,
                style = MimirType.TitleCaps,
                color = MimirColors.TextPrimary,
            )
            Text(
                text = storageGrantLostBodyCopy(root.friendlyLabel),
                style = MimirType.RowSupport,
                color = MimirColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            StorageGrantActionButton(
                label = StorageGrantReconnectCopy,
                primary = true,
                onClick = onReconnect,
                authority = authority,
                focusKey = StorageGrantReconnectKey,
                focused = focusedKey == StorageGrantReconnectKey,
            )
            StorageGrantActionButton(
                label = StorageGrantLaterCopy,
                primary = false,
                onClick = onLater,
                authority = authority,
                focusKey = StorageGrantLaterKey,
                focused = focusedKey == StorageGrantLaterKey,
            )
        }
        // Leaving: a sibling laid OVER the content takes every touch (a button cannot be pressed through its own fade).
        if (!interactive) Box(Modifier.matchParentSize().mimirPageTouchShield())
    }
}

/**
 * The controller arm for this prompt, shaped exactly like `dispatchArtworkFetch`'s `ConfirmNewGames`
 * branch so the shell's dispatcher can adopt it with one `if`.
 *
 * Wired in `MimirAppInputDispatch.dispatchMimirInput` right after the overlay stack declines, gated on no
 * overlay being open — the same condition `StorageGrantPromptHost` composes under, so pad and pixels agree.
 */
internal fun dispatchStorageGrantPrompt(
    action: ControllerAction,
    promptRoot: StoredRoot?,
    onReconnect: () -> Unit,
    onLater: () -> Unit,
    authority: FocusAuthority?,
): Boolean {
    if (promptRoot == null) return false
    if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
        onLater()
        return true
    }
    val focusAuthority = authority ?: return true
    if (focusAuthority.state.value.scope != StorageGrantFocusScope) return true
    when (action) {
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT,
        -> focusAuthority.moveFocus(action)
        ControllerAction.CONFIRM -> when (focusAuthority.state.value.focused) {
            StorageGrantReconnectKey -> onReconnect()
            StorageGrantLaterKey -> onLater()
            else -> Unit
        }
        else -> Unit
    }
    return true
}

/**
 * Copied from `ArtworkFetchOverlay`'s private `ActionButton` (it is private to that file and hardcodes `NewGameEnrichFocusScope`). An etched chip inside a 48 dp hit box
 *: [primary] is the prompt's one repair (the same dark plate,
 * its rim and word in the accent), never a fill. The pad's focus is the shared selection ring on the PLATE itself, with no inset and BEFORE the surface.
 */
@Composable
private fun StorageGrantActionButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    authority: FocusAuthority?,
    focusKey: FocusKey,
    focused: Boolean,
) {
    val palette = LocalMimirPalette.current
    Box(
        Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .then(if (authority != null) Modifier.mimirFocusable(authority, focusKey, StorageGrantFocusScope) else Modifier)
            .mimirActivatable(label = label, authority = authority, key = focusKey, onActivate = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The ring first, then the surface (the plate applies its modifier before its own): the ring hugs the plate, not the 48 dp box, and its wash is under the opaque metal.
        MimirEtchedChipPlate(
            modifier = Modifier.testTag(etchedChipPlateTag("storage", label)).then(
                if (authority != null) {
                    Modifier.mimirSelectionRing(focused = focused, identity = focusKey, shape = MimirRingShape.Pill, edgeFlush = true)
                } else {
                    Modifier
                },
            ),
            focused = focused && authority != null,
            primary = primary,
            palette = palette,
            edgeFlush = true,
        ) {
            MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(primary = primary), maxLines = 1, softWrap = false)
        }
    }
}
