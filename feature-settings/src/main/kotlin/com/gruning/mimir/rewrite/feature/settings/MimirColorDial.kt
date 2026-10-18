package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirRingStop
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.enabledFor
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirSurfaceShadow
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.screenToneFor
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlin.math.roundToInt

/**
 * AP01 §3 — "MimirColorDial": the all-themes FOCUS-HIGHLIGHT dialer. A bespoke horizontal detent row
 * (sibling of ThemePreviewGallery — bespoke shell, house scrim, A/B contract): each detent is a swatch
 * of the stop's BRIGHT tone; the focused detent enlarges with its name beneath; guardrail-failed stops
 * render dim + non-committable ("Too pale for this theme"). Left/Right (or right-stick) step the enabled
 * detents — matching both directional keys and stick rotation.
 *
 * LIVE PREVIEW: the focused detent writes [MimirAppearanceOverrides.ringPreview], so every focus ring in
 * the app re-colours behind the scrim, and the host's LED collector pulses the sticks. Commit persists
 * per-theme; cancel restores the on-open value. "Theme default" IS the reset detent.
 */
val FocusRingDialScope = FocusScope("appearance/focusRingDial")
const val MimirColorDialRowTestTag = "mimir_color_dial_row"

internal fun colorDialPanelPaddingDp(viewportWidthDp: Float): Int =
    if (viewportWidthDp <= 400f) 12 else 24

/**
 * [ringReachPx] is the clear edge on each side of the seats inside the scroll host: horizontalScroll clips its
 * axis at the content bounds, and a seat's circle ring reaches past the seat, so without it the first and
 * last detents' rings were sliced flat.
 */
internal fun colorDialScrollTargetPx(
    focusedIndex: Int,
    stopCount: Int,
    seatWidthPx: Float,
    viewportWidthPx: Float,
    ringReachPx: Float,
): Int {
    if (stopCount <= 0 || seatWidthPx <= 0f || viewportWidthPx <= 0f) return 0
    val reach = ringReachPx.coerceAtLeast(0f)
    val totalWidth = stopCount * seatWidthPx + reach * 2f
    val maxScroll = (totalWidth - viewportWidthPx).coerceAtLeast(0f)
    val focusedCenter = reach + focusedIndex.coerceIn(0, stopCount - 1) * seatWidthPx + seatWidthPx / 2f
    return (focusedCenter - viewportWidthPx / 2f).coerceIn(0f, maxScroll).roundToInt()
}

internal fun colorDialRowCenters(
    stopCount: Int,
    seatWidthDp: Float,
    viewportWidthDp: Float,
    ringReachDp: Float,
): Boolean = stopCount > 0 && seatWidthDp > 0f && viewportWidthDp > 0f &&
    stopCount * seatWidthDp + ringReachDp.coerceAtLeast(0f) * 2f <= viewportWidthDp

/**
 * @param presenceManagedExternally true when an app/feature-owned pixel handoff already owns the modal's
 *   alpha timeline (the app-shell overlay host, behind the shared pixel-fade). This composable must not
 *   layer a second, independently-scheduled fade over the same visual as that host's own entrance —
 *   exactly the "two motions, one visual, on different schedules" shape a 2026-08-11 device report named
 *   for an unrelated pair of animations, and the shape this parameter (shared with every other picker
 *   riding the same pixel-fade host) exists to rule out here too.
 */
@Composable
fun MimirColorDialOverlay(
    focusedStop: MimirRingStop,
    authoredPalette: MimirThemePalette,
    onStepTo: (MimirRingStop) -> Unit,
    onCommit: (MimirRingStop) -> Unit,
    onDismiss: () -> Unit,
    presenceManagedExternally: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val appear = remember(presenceManagedExternally) {
        Animatable(if (presenceManagedExternally) 1f else 0f)
    }
    LaunchedEffect(presenceManagedExternally) {
        if (!presenceManagedExternally) {
            appear.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        }
    }

    // Live preview: whatever detent is focused is the ring the whole app wears right now.
    LaunchedEffect(focusedStop) { MimirAppearanceOverrides.ringPreview = focusedStop }

    BackHandler(enabled = true, onBack = onDismiss)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(palette.scrim)
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
        val panelPadding = colorDialPanelPaddingDp(viewport.width.value).dp
        // Modal width constraint: 640dp is a knife-edge on the Nova
        // (the 4:3 spec's own term for a cap landing exactly on a device's viewport width) -- below the
        // Nova threshold, on the STANDARD rail+detail layout, this panel is additionally bounded by the
        // section card it opened from. 0.0dp of movement on the Odin, whose 600.5dp card is narrower than
        // this 640dp authored cap, so the swap gates to below-threshold AND STANDARD posture only rather
        // than an unconditional min() -- a COMPACT_SQUARE viewport's full-bleed SettingsHome pane is a
        // different card width the rail/margin formula never describes, so it is untouched too.
        val modalCapDp = settingsModalWidthCapDp(
            viewport.width.value,
            viewport.height.value,
            authoredCapDp = 640f,
        )
        Column(
            Modifier
                .mimirMeasuredViewport(viewport)
                .wrapContentSize(Alignment.Center)
                .widthIn(max = modalCapDp.dp)
                .fillMaxWidth()
                .graphicsLayer { val s = 0.97f + 0.03f * appear.value; scaleX = s; scaleY = s }
                .padding(panelPadding)
                .mimirSurfaceShadow(palette, MimirSpacing.elevationModal, RoundedCornerShape(MimirSpacing.radiusCard))
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(palette.surfaceRaised)
                .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusCard))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(panelPadding),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MimirChromeText("Focus highlight", style = MimirType.RunicEyebrow, color = MimirColors.Gold)
            Text(
                // CF01: the joystick-LED follow was discarded — the ring claims screens only.
                "The ring follows you everywhere — on every screen.",
                style = MimirType.RowSupport,
                color = MimirColors.TextMuted,
                textAlign = TextAlign.Center,
            )
            // The horizontal detent row. Compact-square keeps full 52dp seats and settles a short window
            // around the active stop instead of shrinking or wrapping the one-dimensional dial.
            // Swipe-to-spin was missing, so a drag after a tap did nothing. A horizontal drag
            // now steps the enabled detents every [DialDragStepPx] of travel, matching L/R exactly.
            // rememberUpdatedState: the gesture loop outlives recompositions, so it must read the LIVE
            // focused detent — a captured value would step from a stale stop after the first move.
            val liveStop = androidx.compose.runtime.rememberUpdatedState(focusedStop)
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .testTag(MimirColorDialRowTestTag)
                    .pointerInput(authoredPalette) {
                        var dragAccum = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragAccum = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                            while (dragAccum >= DialDragStepPx) {
                                dragAccum -= DialDragStepPx
                                stepDial(liveStop.value, authoredPalette, forward = true, onStepTo)
                            }
                            while (dragAccum <= -DialDragStepPx) {
                                dragAccum += DialDragStepPx
                                stepDial(liveStop.value, authoredPalette, forward = false, onStepTo)
                            }
                        }
                    },
            ) {
                val seatWidth = 52.dp
                val stops = MimirRingStop.entries
                val dialScroll = rememberScrollState()
                val density = LocalDensity.current
                val rowAlignment = if (
                    colorDialRowCenters(
                        stopCount = stops.size,
                        seatWidthDp = seatWidth.value,
                        viewportWidthDp = maxWidth.value,
                        ringReachDp = MimirFocusTileRingReach.value,
                    )
                ) {
                    Alignment.Center
                } else {
                    Alignment.CenterStart
                }
                LaunchedEffect(focusedStop, dialScroll.maxValue, maxWidth) {
                    val target = with(density) {
                        colorDialScrollTargetPx(
                            focusedIndex = stops.indexOf(focusedStop),
                            stopCount = stops.size,
                            seatWidthPx = seatWidth.toPx(),
                            viewportWidthPx = maxWidth.toPx(),
                            ringReachPx = MimirFocusTileRingReach.toPx(),
                        )
                    }
                    dialScroll.animateScrollTo(target, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
                }
                Row(
                    Modifier
                        .align(rowAlignment)
                        .horizontalScroll(dialScroll, enabled = false)
                        // Inside the scroll host, so it scrolls with the seats: the end detents' rings reach
                        // past their seats, and horizontalScroll clips at the content's edge.
                        .padding(horizontal = MimirFocusTileRingReach)
                        .width(seatWidth * stops.size),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stops.forEach { stop ->
                        val enabled = stop.enabledFor(authoredPalette)
                        DialDetent(
                            stop = stop,
                            focused = stop == focusedStop,
                            enabled = enabled,
                            authoredPalette = authoredPalette,
                            onTap = {
                                if (!enabled) return@DialDetent
                                // Touch parity: first tap focuses (previews); tapping the focused detent commits.
                                if (stop == focusedStop) onCommit(stop) else onStepTo(stop)
                            },
                        )
                    }
                }
                if (dialScroll.value > 0) {
                    Text("‹", style = MimirType.ControlHint, color = palette.accentBright, modifier = Modifier.align(Alignment.CenterStart))
                }
                if (dialScroll.value < dialScroll.maxValue) {
                    Text("›", style = MimirType.ControlHint, color = palette.accentBright, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
            // The focused detent's name + honest state line.
            val subtitle = when {
                !focusedStop.enabledFor(authoredPalette) -> "Too pale for this theme."
                focusedStop == MimirRingStop.THEME_DEFAULT -> "The look this theme shipped with."
                else -> "Every focus ring follows this color."
            }
            MimirChromeText(
                focusedStop.label,
                style = MimirType.MenuAction,
                color = MimirColors.TextPrimary,
            )
            Text(subtitle, style = MimirType.RowSupport, color = MimirColors.TextMuted)
            MimirControlHintText(
                ColorDialControlHint,
                style = MimirType.ControlHint,
                color = MimirColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DialDetent(
    stop: MimirRingStop,
    focused: Boolean,
    enabled: Boolean,
    authoredPalette: MimirThemePalette,
    onTap: () -> Unit,
) {
    // The swatch shows the tone the SCREEN would get for this theme; "Theme default" wears the authored
    // ring colour so the reset detent is honest about what it restores.
    val swatchColor = if (stop == MimirRingStop.THEME_DEFAULT) {
        authoredPalette.focusRing
    } else {
        stop.screenToneFor(authoredPalette)
    }
    // The size is read in layout, not composition (audit 2026-10-14, S-22): the grow and shrink on focus re-lay out the swatch each frame without recomposing the detent.
    val sizeState = animateDpAsState(
        targetValue = if (focused) 44.dp else 30.dp,
        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "dialDetentSize",
    )
    Box(
        Modifier
            .size(52.dp)
            // The one ring (ring migration, 2026-09-28): a circle ring on the detent's SEAT, in the LIVE palette's ring
            // colour (LocalMimirPalette: the committed ring stop and the focused detent's ringPreview), so the dial's own
            // ring is the colour being chosen, like every other ring. Never the authored palette here: that is the raw
            // theme tone, and the dial would be the one ring that does not follow the dial. The 2 dp ink edge on the
            // swatch was this dial's private ring. The seat and not the swatch: the swatch's alpha clips to its own
            // bounds, and it grows on focus, which a ring sized to it would chase.
            .mimirSelectionRing(
                focused = focused,
                identity = stop,
                shape = MimirRingShape.Circle,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .layout { measurable, _ ->
                    val px = sizeState.value.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(px, px))
                    layout(px, px) { placeable.place(0, 0) }
                }
                .alpha(if (enabled) 1f else 0.28f) // guardrail-failed: dim, un-tappable, skipped by L/R
                .clip(CircleShape)
                .background(swatchColor),
        )
    }
}

/** One dial step in [forward] direction through the ENABLED detents (shared by drag + D-pad paths). */
private fun stepDial(
    focusedStop: MimirRingStop,
    authoredPalette: MimirThemePalette,
    forward: Boolean,
    onStepTo: (MimirRingStop) -> Unit,
) {
    val enabled = MimirRingStop.entries.filter { it.enabledFor(authoredPalette) }
    if (enabled.isEmpty()) return
    val index = enabled.indexOf(focusedStop).coerceAtLeast(0)
    val next = if (forward) (index + 1) % enabled.size else (index - 1 + enabled.size) % enabled.size
    onStepTo(enabled[next])
}

/**
 * The dial's legend. It said "L/R Adjust", and "L/R" is a controller
 * hint token, so the Nova projected it onto its shoulders ("L1/R1 ADJUST") while [dispatchColorDial] steps on
 * NAV_LEFT/NAV_RIGHT only. "D-pad" is not a token, so no controller map can turn it into a key that does not
 * step the dial.
 */
internal const val ColorDialControlHint: String = "D-pad Adjust · A Keep · B Back"

/** Horizontal travel per detent step while dragging the dial (finger-sized; ~3 detents per swipe). */
private const val DialDragStepPx = 96f

/**
 * §3 dispatch — hard modal: LEFT/RIGHT step the ENABLED detents, CONFIRM commits the focused stop,
 * CANCEL/Back reverts (host clears the preview + restores the on-open value). Everything else swallowed.
 * NAV_LEFT deliberately steps rather than dismissing — this is a horizontal dial, not a list.
 */
fun dispatchColorDial(
    focusedStop: MimirRingStop,
    authoredPalette: MimirThemePalette,
    action: ControllerAction,
    onStepTo: (MimirRingStop) -> Unit,
    onCommit: (MimirRingStop) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    val enabledStops = MimirRingStop.entries.filter { it.enabledFor(authoredPalette) }
    val index = enabledStops.indexOf(focusedStop).coerceAtLeast(0)
    when (action) {
        // BM03: a detent step is a real action that never moves app focus — record it for the
        // router's tick gate. A one-stop ring "steps" to itself: nothing changed, stay silent.
        ControllerAction.NAV_LEFT ->
            enabledStops[(index - 1 + enabledStops.size) % enabledStops.size].let { next ->
                if (next != focusedStop) MimirNavFeedback.consumed()
                onStepTo(next)
            }
        ControllerAction.NAV_RIGHT ->
            enabledStops[(index + 1) % enabledStops.size].let { next ->
                if (next != focusedStop) MimirNavFeedback.consumed()
                onStepTo(next)
            }
        ControllerAction.CONFIRM ->
            if (focusedStop.enabledFor(authoredPalette)) onCommit(focusedStop)
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> onDismiss()
        else -> Unit
    }
    return true
}
