package com.gruning.mimir.rewrite.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlin.math.roundToInt

val ControllerMappingScope = FocusScope("settings/controller-mapping")
val ControllerMappingExitScope = FocusScope("settings/controller-mapping/exit")

const val ControllerMappingExitApplyRowId = "controller-mapping/exit/apply"
const val ControllerMappingExitDiscardRowId = "controller-mapping/exit/discard"
const val ControllerMappingExitContinueRowId = "controller-mapping/exit/continue"
const val ControllerMappingViewportTestTag = "controller_mapping_viewport"

/**
 * Controller Mapping overlay copy — single-surface, stays at its surface per core-ui `MimirCopy.kt`'s own
 * scoping doc comment ("single-surface body copy ... stays at its surface"). Named constants replace the
 * inline literals per the standing MimirCopy-discipline instruction, matching the pattern already used a
 * few hundred lines away in `SettingsDetail.kt` (`RetroAchievementsTitle`, `MimirManagedArtworkStorageCopy`,
 * etc.) — local `internal const val`s here, not the shared `core-ui` object, because none of these form a
 * cross-surface contract (the one string here that does — the "no binding" sentinel — lives in
 * `MimirCopy.NoBindingAssigned` instead).
 *
 * Titles below are sentence case per policy 2026-08-12
 * : this file's action-row titles were
 * ALL CAPS through the exact `MimirType.SettingsActionTitle` token `SettingsDetail.kt` uses sentence-case
 * at 20 other sites — content-authoring drift, not a deliberate convention for this screen.
 */
internal const val ControllerMappingDefaultStatus = "Choose an action, then press its new controller input."
internal const val ControllerMappingResetTitle = "Reset to default"
internal const val ControllerMappingResetSupporting = "Prepares the factory layout. Apply saves it."
internal const val ControllerMappingResetLabel = "Reset"
internal const val ControllerMappingApplyMappingTitle = "Apply mapping"
internal const val ControllerMappingApplySupporting = "Saves the layout and updates Mimir’s control hints."
/** Shared by the Apply row's trailing chip and the exit-confirmation dialog's Apply row title. */
internal const val ControllerMappingApplyLabel = "Apply"
internal const val ControllerMappingCaptureBody =
    "Release one button to map it, or hold up to four together. Rear macro keys are accepted when Android exposes them."
internal const val ControllerMappingExitBody = "Apply this controller layout before leaving?"
internal const val ControllerMappingDiscardTitle = "Discard"
internal const val ControllerMappingContinueEditingTitle = "Continue editing"

private fun controllerMappingKey(id: String) = FocusKey(id)

/**
 * Opens the exit confirmation's focus scope, in the frame of the command that asks for it (audit 2026-10-14, S-40b). Apply is planted as the scope's landing before the push, so the
 * sheet's rows seed it as they register (the first of them to lay out is not the one that wins), and no effect has to push late and request after a frame. Idempotent: a scope already
 * open is left as it is.
 */
fun openControllerMappingExitScope(authority: FocusAuthority) {
    if (authority.state.value.scope == ControllerMappingExitScope) return
    authority.rememberFocusForScope(ControllerMappingExitScope, controllerMappingKey(ControllerMappingExitApplyRowId))
    authority.pushScope(ControllerMappingExitScope)
}

private class RetainedActionName {
    var value: String = ""
}

internal data class ControllerMappingRowGeometry(
    val topPx: Int,
    val heightPx: Int,
)

/**
 * The mapper has one scroll owner. Middle rows settle around the viewport centre; the first and last
 * controls reveal the complete beginning/end so the header and footer never look clipped.
 */
internal fun controllerMappingSettlementTarget(
    focusedId: String?,
    focusIds: List<String>,
    geometry: ControllerMappingRowGeometry?,
    viewportHeightPx: Int,
    maxScrollPx: Int,
): Int? = when {
    focusedId == null || focusedId !in focusIds -> null
    focusedId == focusIds.firstOrNull() -> 0
    focusedId == focusIds.lastOrNull() -> maxScrollPx.coerceAtLeast(0)
    geometry == null || viewportHeightPx <= 0 -> null
    else -> (geometry.topPx + geometry.heightPx / 2 - viewportHeightPx / 2)
        .coerceIn(0, maxScrollPx.coerceAtLeast(0))
}

internal fun controllerMappingInitialFocusId(
    rows: List<ControllerMappingRow>,
    requestedActionName: String?,
    retainedFocusId: String?,
    focusIds: List<String>,
): String? = rows.firstOrNull { it.actionName == requestedActionName }?.id
    ?: retainedFocusId?.takeIf(focusIds::contains)
    ?: rows.firstOrNull()?.id

@Composable
fun ControllerMappingOverlay(
    authority: FocusAuthority,
    rows: List<ControllerMappingRow>,
    status: String?,
    initialFocusActionName: String? = null,
    pendingActionName: String?,
    exitConfirmationVisible: Boolean,
    onBeginCapture: (String) -> Unit,
    onCancelCapture: () -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus by authority.state.collectAsState()
    val scroll = rememberScrollState()
    // Plain memory, written during composition and read only there: the capture card's action name, held after the capture ends so the card fades out wearing it.
    val retained = remember { RetainedActionName() }
    pendingActionName?.let { retained.value = it }
    val retainedActionName = retained.value
    val focusIds = remember(rows) {
        rows.map(ControllerMappingRow::id) + ControllerMappingResetRowId + ControllerMappingApplyRowId
    }
    var viewportTopPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val rowGeometry = remember { mutableStateMapOf<String, ControllerMappingRowGeometry>() }
    val focusedId = focus.focused?.value
    val focusedGeometry = focusedId?.let(rowGeometry::get)
    BackHandler(enabled = true) {
        when {
            pendingActionName != null -> onCancelCapture()
            exitConfirmationVisible -> onContinueEditing()
            else -> onDismiss()
        }
    }
    LaunchedEffect(focusIds, initialFocusActionName) {
        withFrameNanos { }
        controllerMappingInitialFocusId(
            rows = rows,
            requestedActionName = initialFocusActionName,
            retainedFocusId = authority.state.value.focused?.value,
            focusIds = focusIds,
        )?.let {
            authority.requestFocus(controllerMappingKey(it))
        }
    }
    LaunchedEffect(focusedId, focusedGeometry, viewportHeightPx, focusIds, scroll.maxValue) {
        controllerMappingSettlementTarget(
            focusedId = focusedId,
            focusIds = focusIds,
            geometry = focusedGeometry,
            viewportHeightPx = viewportHeightPx,
            maxScrollPx = scroll.maxValue,
        )?.let { target -> scroll.animateScrollTo(target, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)) }
    }
    // The coordinator opens the exit scope in the command frame ([openControllerMappingExitScope], from its close) and pops it in the continue / discard / leave commands;
    // these are the net for a caller that did not (audit 2026-10-14, S-40b: the push used to run here, a frame late, and the request after another frame, while the sheet was
    // already visible). Idempotent, so on the normal path they find the scope already there (or already gone) and do nothing.
    LaunchedEffect(exitConfirmationVisible) {
        if (exitConfirmationVisible) {
            openControllerMappingExitScope(authority)
        } else if (authority.state.value.scope == ControllerMappingExitScope) {
            authority.popScope()
        }
    }
    DisposableEffect(authority) {
        onDispose {
            if (authority.state.value.scope == ControllerMappingExitScope) authority.popScope()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(mimirGlassScrimColor())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        val measuredRowModifier: @Composable (String) -> Modifier = { id ->
            Modifier.onGloballyPositioned { coordinates ->
                if (viewport.suppressDrawing || coordinates.size.height <= 0 || viewportHeightPx <= 0) {
                    return@onGloballyPositioned
                }
                val visibleTop = coordinates.positionInWindow().y.roundToInt()
                val measured = ControllerMappingRowGeometry(
                    topPx = visibleTop - viewportTopPx + scroll.value,
                    heightPx = coordinates.size.height,
                )
                if (rowGeometry[id] != measured) rowGeometry[id] = measured
            }
        }
        // Panel, capture scrim, and exit scrim are sibling layers; this wrapper preserves their centered
        // stacking while holding the boundary at the last valid viewport through a zero frame.
        Box(Modifier.mimirMeasuredViewport(viewport), contentAlignment = Alignment.Center) {
        // Modal width constraint: below the Nova threshold, on the
        // STANDARD rail+detail layout, this overlay can no longer render wider than the glass section card
        // it was opened from. The Odin's 600.5dp card is narrower than this 720dp authored cap too, so an
        // unconditional min() would have moved the Odin (720dp -> 600.5dp); settingsModalWidthCapDp gates
        // the swap to below-threshold AND STANDARD posture only, so the Odin keeps its authored 720dp cap
        // unchanged (0.0dp of movement), and a COMPACT_SQUARE viewport -- whose SettingsHome layout is a
        // full-bleed pane the rail/margin formula never describes -- is untouched too.
        val modalCapDp = settingsModalWidthCapDp(viewport.width.value, viewport.height.value, authoredCapDp = 720f)
        MimirLiquidGlassPanel(
            modifier = Modifier
                .widthIn(max = modalCapDp.dp)
                .fillMaxWidth()
                .padding(MimirSpacing.xl)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentPadding = MimirSpacing.xl,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    MimirChromeText(
                        text = "ᛗ · CONTROLLER MAPPING",
                        style = MimirType.RunicEyebrow,
                        color = MimirColors.GoldBright,
                        maxLines = 1,
                    )
                    Text(
                        text = status ?: ControllerMappingDefaultStatus,
                        style = MimirType.RowSupport,
                        color = MimirColors.TextSecondary,
                        maxLines = 2,
                    )
                }
                Text(
                    text = "${rows.count { it.binding != MimirCopy.NoBindingAssigned }} / ${rows.size}",
                    style = MimirType.MicroStatus,
                    color = MimirColors.TextMuted,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ControllerMappingViewportTestTag)
                    // heightIn(max = 460.dp) removed here: dead configuration (RC-8 review) -- the
                    // incoming maxHeight from the modal's own contentPadding is ~352dp at a 480dp
                    // viewport, so a 460dp cap never bound on either device. viewportHeightPx below reads
                    // the coordinates' actual laid-out size, which this cap never affected.
                    .onGloballyPositioned { coordinates ->
                        if (!viewport.suppressDrawing) {
                            viewportTopPx = coordinates.positionInWindow().y.roundToInt()
                            viewportHeightPx = coordinates.size.height
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        // SettingsRow wears the one ring (2026-09-28), which reaches MimirFocusTileRingReach past the
                        // row. The settlement above pins the first row to 0 and Apply to maxValue, so the clearance
                        // lives inside the scroll host: those ends keep their ring's top and bottom edge. Row
                        // geometry is measured in content coordinates and already includes this padding.
                        .padding(vertical = MimirFocusTileRingReach),
                    // Siblings 8 dp apart, as the Settings rail (spec §3.3): at 2 dp the focused ring's strokes
                    // lay over the neighbours' resting hairlines.
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                ) {
                    rows.forEach { row ->
                        key(row.id) {
                            SettingsRow(
                                key = controllerMappingKey(row.id),
                                scope = ControllerMappingScope,
                                authority = authority,
                                title = row.title,
                                modifier = measuredRowModifier(row.id),
                                titleStyle = MimirType.SettingsActionTitle,
                                supporting = if (row.capturing) {
                                    "LISTENING · PRESS ONE INPUT OR HOLD A COMBINATION"
                                } else {
                                    null
                                },
                                onActivate = { onBeginCapture(row.actionName) },
                                trailing = {
                                    DynamicValueChip(
                                        text = if (row.capturing) "LISTENING" else row.binding,
                                        color = if (row.binding == MimirCopy.NoBindingAssigned) {
                                            MimirColors.Danger
                                        } else {
                                            MimirColors.TextSecondary
                                        },
                                    )
                                },
                            )
                        }
                    }
                    SettingsRow(
                        key = controllerMappingKey(ControllerMappingResetRowId),
                        scope = ControllerMappingScope,
                        authority = authority,
                        title = ControllerMappingResetTitle,
                        modifier = measuredRowModifier(ControllerMappingResetRowId),
                        titleStyle = MimirType.SettingsActionTitle,
                        supporting = ControllerMappingResetSupporting,
                        onActivate = onReset,
                        trailing = { RowAction(ControllerMappingResetLabel) },
                    )
                    SettingsRow(
                        key = controllerMappingKey(ControllerMappingApplyRowId),
                        scope = ControllerMappingScope,
                        authority = authority,
                        title = ControllerMappingApplyMappingTitle,
                        modifier = measuredRowModifier(ControllerMappingApplyRowId),
                        titleStyle = MimirType.SettingsActionTitle,
                        supporting = ControllerMappingApplySupporting,
                        onActivate = onApply,
                        trailing = { RowAction(ControllerMappingApplyLabel) },
                    )
                }
            }
            MimirChromeText(
                text = "A REMAP · B BACK · TOUCH ALSO WORKS",
                style = MimirType.ControlHint,
                color = MimirColors.TextMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        AnimatedVisibility(
            visible = pendingActionName != null,
            // Was Compose's default spec (a different duration AND curve from the overlay's own
            // MimirMotion.OverlayMs/Easing entrance, which this scrim sits stacked on top of) — two
            // motions, one visual, on different schedules. Now shares the same token.
            enter = fadeIn(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
            exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(mimirGlassScrimColor())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCancelCapture,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MimirLiquidGlassPanel(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .padding(MimirSpacing.xl)
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    contentPadding = MimirSpacing.xl,
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                ) {
                    MimirChromeText(
                        text = "PRESS OR HOLD INPUT",
                        style = MimirType.OverlayTitle,
                        color = MimirColors.GoldBright,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        // The last name stays through the 180 ms the card fades out; the line used to go blank while the card was still on screen (audit S-40a).
                        text = retainedActionName,
                        style = MimirType.SettingsActionTitle,
                        color = MimirColors.TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = ControllerMappingCaptureBody,
                        style = MimirType.RowSupport,
                        color = MimirColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "TOUCH OUTSIDE OR PRESS ANDROID BACK TO CANCEL",
                        style = MimirType.ControlHint,
                        color = MimirColors.TextMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCancelCapture)
                            .padding(MimirSpacing.md),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = exitConfirmationVisible,
            // Was Compose's default spec (a different duration AND curve from the overlay's own
            // MimirMotion.OverlayMs/Easing entrance, which this scrim sits stacked on top of) — two
            // motions, one visual, on different schedules. Now shares the same token.
            enter = fadeIn(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
            exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(mimirGlassScrimColor())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onContinueEditing,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MimirLiquidGlassPanel(
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .padding(MimirSpacing.xl)
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    contentPadding = MimirSpacing.xl,
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                ) {
                    MimirChromeText(
                        text = "KEEP CHANGES?",
                        style = MimirType.OverlayTitle,
                        color = MimirColors.GoldBright,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = ControllerMappingExitBody,
                        style = MimirType.RowSupport,
                        color = MimirColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    SettingsRow(
                        key = controllerMappingKey(ControllerMappingExitApplyRowId),
                        scope = ControllerMappingExitScope,
                        authority = authority,
                        title = ControllerMappingApplyLabel,
                        titleStyle = MimirType.SettingsActionTitle,
                        onActivate = onApply,
                    )
                    SettingsRow(
                        key = controllerMappingKey(ControllerMappingExitDiscardRowId),
                        scope = ControllerMappingExitScope,
                        authority = authority,
                        title = ControllerMappingDiscardTitle,
                        titleStyle = MimirType.SettingsActionTitle,
                        onActivate = onDiscard,
                    )
                    SettingsRow(
                        key = controllerMappingKey(ControllerMappingExitContinueRowId),
                        scope = ControllerMappingExitScope,
                        authority = authority,
                        title = ControllerMappingContinueEditingTitle,
                        titleStyle = MimirType.SettingsActionTitle,
                        onActivate = onContinueEditing,
                    )
                    MimirChromeText(
                        text = "A SELECT · B CONTINUE EDITING",
                        style = MimirType.ControlHint,
                        color = MimirColors.TextMuted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    }
}

fun dispatchControllerMapping(
    authority: FocusAuthority,
    rows: List<ControllerMappingRow>,
    action: ControllerAction,
    exitConfirmationVisible: Boolean,
    onBeginCapture: (String) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    if (exitConfirmationVisible) {
        when (action) {
            ControllerAction.NAV_UP,
            ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT,
            ControllerAction.NAV_RIGHT -> authority.moveFocus(action)
            ControllerAction.CONFIRM -> when (authority.state.value.focused?.value) {
                ControllerMappingExitApplyRowId -> onApply()
                ControllerMappingExitDiscardRowId -> onDiscard()
                ControllerMappingExitContinueRowId -> onContinueEditing()
            }
            ControllerAction.CANCEL,
            ControllerAction.SYSTEM_BACK -> onContinueEditing()
            else -> Unit
        }
        return true
    }
    when (action) {
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT -> authority.moveFocus(action)
        ControllerAction.CONFIRM -> when (val focused = authority.state.value.focused?.value) {
            ControllerMappingResetRowId -> onReset()
            ControllerMappingApplyRowId -> onApply()
            else -> rows.firstOrNull { it.id == focused }?.let { onBeginCapture(it.actionName) }
        }
        ControllerAction.CANCEL,
        ControllerAction.SYSTEM_BACK -> onDismiss()
        else -> Unit
    }
    return true
}
