package com.gruning.mimir.rewrite.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirSurfaceShadow
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlin.math.roundToInt

/**
 * Settings › Music's volume dial (spec 2026-09-30, "Volumes"): in the detail pane LEFT pops to the rail, so a
 * row cannot take left/right. A opens this dial on the Focus-highlight dial's shell ([MimirColorDialOverlay]):
 * left/right move in [MusicVolumeDialStepPercent] detents from 0 to 100, the level applies LIVE on every
 * step so the player hears it, A keeps, B reverts to the level the dial opened on. One shared dial for both
 * volumes; the title names which.
 *
 * The bar is one `Canvas`, not twenty ringed seats: nothing here holds focus state per detent, so the
 * ghost-free identity census has nothing to classify, and the only animation is a draw-phase fill.
 */
val MusicVolumeDialScope = FocusScope("music/volumeDial")
const val MusicVolumeDialTestTag = "music_volume_dial"

/** One detent. 5% is the spec's step: twenty stops between silent and full, each one audible. */
const val MusicVolumeDialStepPercent: Int = 5

/** Snap a stored level onto the nearest detent. A 0.5 ambient volume is exactly 50; a 0.37 lands on 35. */
fun musicVolumeDialSnap(levelPercent: Int): Int {
    val clamped = levelPercent.coerceIn(0, 100)
    return ((clamped + MusicVolumeDialStepPercent / 2) / MusicVolumeDialStepPercent) * MusicVolumeDialStepPercent
}

/** One detent in [forward]'s direction, clamped: 100 stays 100, 0 stays 0, so an edge press changes nothing. */
fun musicVolumeDialStep(levelPercent: Int, forward: Boolean): Int {
    val snapped = musicVolumeDialSnap(levelPercent)
    val next = if (forward) snapped + MusicVolumeDialStepPercent else snapped - MusicVolumeDialStepPercent
    return next.coerceIn(0, 100)
}

/** The detent under a touch at [fraction] of the bar's width, for a tap or a drag on the bar itself. */
fun musicVolumeDialLevelAt(fraction: Float): Int =
    musicVolumeDialSnap((fraction.coerceIn(0f, 1f) * 100f).roundToInt())

/**
 * The dial's input contract -- a hard modal, like [dispatchColorDial]. LEFT/RIGHT step a detent and apply it
 * through [onStepTo]; CONFIRM keeps the current level; CANCEL and Back hand the on-open level back through
 * [onDismiss] (the host restores it). Everything else is swallowed so no press leaks to the pane beneath.
 *
 * NAV_LEFT steps rather than dismissing: this is a dial, not a list, the same interaction rule the colour dial carries.
 */
fun dispatchMusicVolumeDial(
    levelPercent: Int,
    action: ControllerAction,
    onStepTo: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    when (action) {
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
            val next = musicVolumeDialStep(levelPercent, forward = action == ControllerAction.NAV_RIGHT)
            // BM03: a detent step is a real action that never moves app focus -- tell the router's tick gate.
            // An edge press that lands on the same level changed nothing and stays silent.
            if (next != levelPercent) {
                MimirNavFeedback.consumed()
                onStepTo(next)
            }
        }
        ControllerAction.CONFIRM -> onCommit(musicVolumeDialSnap(levelPercent))
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> onDismiss()
        else -> Unit
    }
    return true
}

/** Horizontal travel per detent while dragging the bar (the colour dial's figure, finger-sized). */
private const val VolumeDialDragStepPx = 48f

/**
 * @param title the row that opened it: "Volume" or "Launch sound volume".
 * @param subtitle one honest line under it ("Also in Utility as Music volume." / "Independent of the music volume.").
 * @param levelPercent the live level; the host applies every step so this is also what the player hears.
 * @param presenceManagedExternally true when the app-shell overlay host already owns the fade ([MimirColorDialOverlay]'s
 *   own parameter, same reason: never two motions on one visual).
 */
@Composable
fun MimirVolumeDialOverlay(
    title: String,
    subtitle: String,
    levelPercent: Int,
    onStepTo: (Int) -> Unit,
    onCommit: (Int) -> Unit,
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
            MimirChromeText(title, style = MimirType.RunicEyebrow, color = MimirColors.Gold)
            Text(
                subtitle,
                style = MimirType.RowSupport,
                color = MimirColors.TextMuted,
                textAlign = TextAlign.Center,
            )
            // The readout: the one number the player is setting, in the pane's title lane. "Off" at zero, the
            // word Utility's slider already uses for the same level.
            MimirChromeText(
                musicPercentLabel(levelPercent),
                style = MimirType.Title,
                color = MimirColors.TextPrimary,
            )
            // The bar. Twenty detents in one draw; the fill eases on the focus clock. A tap lands on the detent
            // under the finger; a drag steps detents every VolumeDialDragStepPx of travel, matching L/R exactly.
            // rememberUpdatedState: the gesture loops outlive recompositions and must read the LIVE level.
            val liveLevel = rememberUpdatedState(levelPercent)
            val fill by animateFloatAsState(
                targetValue = levelPercent.coerceIn(0, 100) / 100f,
                animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
                label = "volumeDialFill",
            )
            val detentCount = 100 / MusicVolumeDialStepPercent
            val accentBright = palette.accentBright
            val outline = palette.outline
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .testTag(MusicVolumeDialTestTag)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onStepTo(musicVolumeDialLevelAt(offset.x / size.width.toFloat()))
                        }
                    }
                    .pointerInput(Unit) {
                        var dragAccum = 0f
                        detectHorizontalDragGestures(onDragStart = { dragAccum = 0f }) { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                            while (dragAccum >= VolumeDialDragStepPx) {
                                dragAccum -= VolumeDialDragStepPx
                                onStepTo(musicVolumeDialStep(liveLevel.value, forward = true))
                            }
                            while (dragAccum <= -VolumeDialDragStepPx) {
                                dragAccum += VolumeDialDragStepPx
                                onStepTo(musicVolumeDialStep(liveLevel.value, forward = false))
                            }
                        }
                    },
            ) {
                val gap = 3.dp.toPx()
                val segment = (size.width - gap * (detentCount - 1)) / detentCount
                val radius = CornerRadius(2.dp.toPx())
                val lit = (fill * detentCount)
                for (index in 0 until detentCount) {
                    val left = index * (segment + gap)
                    // A detent lights fully once the fill has passed it, and partially as the fill crosses it, so
                    // the eased fill reads as a sweep rather than a flicker of whole segments.
                    val litFraction = (lit - index).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(left, 0f),
                        size = Size(segment, size.height),
                        cornerRadius = radius,
                    )
                    if (litFraction > 0f) {
                        drawRoundRect(
                            color = accentBright,
                            topLeft = Offset(left, 0f),
                            size = Size(segment * litFraction, size.height),
                            cornerRadius = radius,
                        )
                    }
                }
            }
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
