package com.gruning.mimir.rewrite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.drawMimirMetalPlate
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush

/*
 * Transport controls: the transport is struck metal on both screens. Each of Previous, Play/Pause and Next is a plate in
 * Mimir's card corner, its ground the hero leather and its rim and glyph the coin's metal (platformConsoleIconBrush on the plain palette). There is no
 * gleam on it (M3): the gleam marks controller focus, and the touch-only Bottom has none.
 */

/** The hub's Play plate (a 56 dp target of its own) and its skip plates (44 dp in 56 dp targets). */
internal val MediaHubPlayDp: Dp = 56.dp
internal val MediaHubSkipPlateDp: Dp = 44.dp
internal val MediaHubSkipTargetDp: Dp = 56.dp

/** The media tile's Play plate (52 dp) and skip plates (40 dp in 48 dp targets): the hub's stage in small. */
internal val MediaTilePlayDp: Dp = 52.dp
internal val MediaTileSkipPlateDp: Dp = 40.dp
internal val MediaTileSkipTargetDp: Dp = 48.dp

/** Main's panel keeps its 52 dp Play and its 56 dp skip targets; the skip plate inside is the hub's 44 dp. */
internal val MediaMainPlayDp: Dp = 52.dp
internal val MediaMainSkipTargetDp: Dp = 56.dp

/** The glyph's box, as a share of its plate. */
private const val MediaGlyphShare = 0.5f

/** Resting metal strength; a press adds the spec's 20/255 and a playing Pause takes the full strength (the brighter metal). */
private const val MediaMetalRestAlpha = 0.82f
private const val MediaMetalPressedBoost = 20f / 255f
private const val MediaMetalPlayingAlpha = 1f

/** A button that cannot act is flat metal: the book's hardware mixed this far toward the cover (the achievement medal's unearned boss uses the same mix). */
private const val MediaDisabledMetalMix = 0.55f

/**
 * The transport buttons the companion's media card and the music panel share (moved out of CompanionWorkspaceSurface so the two
 * cannot drift into two looks for one control). Previous, Play/Pause and Next are metal plates (see the file's note); [skipTargetSize] is the touch
 * target of a skip (its plate is [skipPlateSize] inside it) and [primarySize] the Play plate, which is its own target. The defaults are Main's panel's
 * (56 dp skip targets with 44 dp plates, a 52 dp Play); the hub and the tile pass theirs. Any other label keeps the plain text button it always was.
 *
 * [pressResponse] is the Bottom's one press response (B5, R4): true draws the whole button in to `MimirPressScale` while a finger holds it. It is off by default
 * and only the companion's Activity page turns it on, so the music panel, which Main hosts too, is not touched. With it on, the order is the caller's [modifier], the
 * tap, the response, [visual] (a fill or clip the caller wants drawn in with the button) and then the button's own ring and fill, so the hit box stays whole. With it
 * off the chain is exactly as it was and [visual] is not read. The metal plate's pressed look (the metal a little stronger, the ground's top warmed by the counter
 * accent) is drawn from the press either way, in the draw phase only: a press recomposes nothing.
 *
 * The companion is touch-only, so by default there is no ring and no focus; the music panel hands in [focused] (its ring) and the
 * [focusAuthority] and [focusKey] a tap publishes focus through, and its own focus registration through [modifier]. Main's focused plate takes the ring on the
 * plate itself, unfilled: the plate is not repainted in the accent.
 */
@Composable
internal fun MediaButton(
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    skipTargetSize: Dp = 80.dp,
    skipPlateSize: Dp = MediaHubSkipPlateDp,
    primarySize: Dp = MediaMainPlayDp,
    focused: Boolean? = null,
    focusAuthority: FocusAuthority? = null,
    focusKey: FocusKey? = null,
    pressResponse: Boolean = false,
    visual: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val transportIcon = label == "Previous" || label == "Next" || label == "Pause" || label == "Play"
    if (transportIcon) {
        MediaMetalButton(
            label = label,
            enabled = enabled,
            primary = primary,
            modifier = modifier,
            skipTargetSize = skipTargetSize,
            skipPlateSize = skipPlateSize,
            primarySize = primarySize,
            focused = focused,
            focusAuthority = focusAuthority,
            focusKey = focusKey,
            pressResponse = pressResponse,
            visual = visual,
            onClick = onClick,
        )
        return
    }
    MediaTextButton(
        label = label,
        enabled = enabled,
        modifier = modifier,
        focused = focused,
        focusAuthority = focusAuthority,
        focusKey = focusKey,
        pressResponse = pressResponse,
        visual = visual,
        onClick = onClick,
    )
}

/** The metal transport plate (Previous, Play, Pause, Next). */
@Composable
private fun MediaMetalButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    modifier: Modifier,
    skipTargetSize: Dp,
    skipPlateSize: Dp,
    primarySize: Dp,
    focused: Boolean?,
    focusAuthority: FocusAuthority?,
    focusKey: FocusKey?,
    pressResponse: Boolean,
    visual: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val pressSource = remember { MutableInteractionSource() }
    // A State that only the draw lambdas read: a press and a release invalidate the draw and recompose nothing.
    val pressed = pressSource.collectIsPressedAsState()
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnClick by rememberUpdatedState(onClick)
    val skip = label == "Previous" || label == "Next"
    val targetSize = if (skip) skipTargetSize else if (primary) primarySize else 48.dp
    val plateSize = if (skip) minOf(skipPlateSize, targetSize) else targetSize
    val metal = remember(palette) { platformConsoleIconBrush(palette) }
    val flat = lerp(hero.hardware, hero.coverBottom, MediaDisabledMetalMix)
    val flatBrush = remember(flat) { SolidColor(flat) }
    val glyphBrush: Brush = if (enabled) metal else flatBrush
    val playing = label == "Pause"
    val metalAlpha: () -> Float = { if (enabled) mediaMetalAlpha(playing = playing, pressed = pressed.value) else 1f }
    val activation = if (enabled) {
        Modifier.mimirActivatable(
            label = label,
            contentDescription = label,
            authority = focusAuthority,
            key = focusKey,
            interactionSource = pressSource,
            onActivate = { if (latestEnabled) latestOnClick() },
        )
    } else {
        Modifier
            .then(
                // The Bottom's (pressResponse): a disabled button still takes its own touch, so the tap never falls through to a pressable around it (the media
                // tile opens the music panel). Main's keeps what it had: no handler, and nothing else changed.
                if (pressResponse) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier,
            )
            .semantics {
                role = Role.Button
                contentDescription = "$label unavailable"
                disabled()
            }
    }
    val actionModifier = if (pressResponse) {
        // The Bottom (B5, R4): the tap first, the press response right after it, then everything that is drawn.
        modifier.then(activation).then(Modifier.mimirPressResponse(pressSource)).then(visual)
    } else {
        modifier.then(activation)
    }
    // no round buttons - Mimir's rounded rectangle (radiusCard); the ring, the clip and the plate are one shape.
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    val ring = if (focused != null) {
        Modifier.mimirSelectionRing(
            focused = focused,
            identity = focusKey?.value ?: label,
            shape = MimirRingShape.Row(MimirSpacing.radiusCard),
            filled = false,
        )
    } else {
        Modifier
    }
    Box(
        modifier = actionModifier.size(targetSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .testTag(MediaTransportPlateTag)
                .size(plateSize)
                .then(ring)
                .clip(shape)
                // pad11: the plate's ground, rim and press warmth are core-ui's shared struck-metal plate (the Bottom's header plates draw through it
                // too); it is the same drawing, in the same order, with the same numbers (MimirMetalPlatePixelIdentityTest).
                .drawBehind {
                    drawMimirMetalPlate(palette, rimBrush = glyphBrush, rimAlpha = metalAlpha(), warmed = enabled && pressed.value)
                },
            contentAlignment = Alignment.Center,
        ) {
            val glyph = plateSize * MediaGlyphShare
            when (label) {
                "Previous" -> MediaSkipIcon(previous = true, brush = glyphBrush, size = glyph, alpha = metalAlpha)
                "Next" -> MediaSkipIcon(previous = false, brush = glyphBrush, size = glyph, alpha = metalAlpha)
                "Pause" -> Canvas(Modifier.size(glyph)) { drawPause(glyphBrush, metalAlpha()) }
                else -> Canvas(Modifier.size(glyph)) { drawPlay(glyphBrush, metalAlpha()) }
            }
        }
    }
}

/** The plain text button (the notification-access prompt): its look is what it always was; the transport's metal does not touch it. */
@Composable
private fun MediaTextButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    focused: Boolean?,
    focusAuthority: FocusAuthority?,
    focusKey: FocusKey?,
    pressResponse: Boolean,
    visual: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    // Remembered either way (a slot, not a branch); only a button that asks for the response reports to it or draws it.
    val pressSource = remember { MutableInteractionSource() }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnClick by rememberUpdatedState(onClick)
    val baseColor = if (enabled) palette.textPrimary else palette.textMuted.copy(alpha = 0.55f)
    // The caller's registration and the ring come before the clip, so the ring is not cut to the button's own bounds.
    val ring = if (focused != null) {
        Modifier.mimirSelectionRing(
            focused = focused,
            identity = focusKey?.value ?: label,
            shape = MimirRingShape.Row(10.dp),
            filled = false,
        )
    } else {
        Modifier
    }
    val ringAndFill = ring
        .clip(RoundedCornerShape(10.dp))
        .background(Color.Transparent)
    val activation = if (enabled) {
        Modifier.mimirActivatable(
            label = label,
            contentDescription = label,
            authority = focusAuthority,
            key = focusKey,
            interactionSource = if (pressResponse) pressSource else null,
            onActivate = { if (latestEnabled) latestOnClick() },
        )
    } else {
        Modifier
            .then(
                if (pressResponse) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier,
            )
            .semantics {
                role = Role.Button
                contentDescription = "$label unavailable"
                disabled()
            }
    }
    val actionModifier = if (pressResponse) {
        modifier.then(activation).then(Modifier.mimirPressResponse(pressSource)).then(visual).then(ringAndFill)
    } else {
        modifier.then(ringAndFill).then(activation)
    }
    Box(modifier = actionModifier, contentAlignment = Alignment.Center) {
        // The chip's own label and padding (md across, sm down): the plate a caller draws behind it is an etched chip's (update), so this reads as one.
        Text(
            text = label,
            style = MimirEtchedChipLabelStyle,
            color = if (enabled) palette.etchedChipInk() else baseColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
        )
    }
}

/**
 * Previous and Next: a bar and a triangle struck in [brush] inside a [size] square, centred in it. [alpha] is read in the draw phase (the plate's
 * pressed strength), so the owner of a press state does not recompose to change it.
 */
@Composable
internal fun MediaSkipIcon(previous: Boolean, brush: Brush, size: Dp, alpha: () -> Float = { 1f }) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barX = if (previous) w * SkipEdge else w * (1f - SkipEdge - SkipBarWidth)
        drawRect(
            brush = brush,
            topLeft = Offset(barX, h * SkipTop),
            size = Size(w * SkipBarWidth, h * (1f - 2f * SkipTop)),
            alpha = alpha(),
        )
        val tip = if (previous) SkipEdge + SkipBarWidth + SkipGap else 1f - SkipEdge - SkipBarWidth - SkipGap
        val base = if (previous) 1f - SkipEdge else SkipEdge
        val triangle = Path().apply {
            moveTo(w * base, h * SkipTop)
            lineTo(w * base, h * (1f - SkipTop))
            lineTo(w * tip, h * 0.5f)
            close()
        }
        drawPath(triangle, brush, alpha = alpha())
    }
}

// The skip glyph in its square, symmetric so Previous and Next are mirrors and each sits in the middle of its plate.
private const val SkipEdge = 0.14f
private const val SkipBarWidth = 0.14f
private const val SkipGap = 0.02f
private const val SkipTop = 0.16f

// Play and Pause in their square: the triangle's weight sits a touch right of centre as a play mark's does; the bars are two equal columns.
private const val PlayLeft = 0.20f
private const val PlayRight = 0.90f
private const val PlayTop = 0.08f
private const val PauseBarWidth = 0.24f
private const val PauseLeft = 0.14f
private const val PauseTop = 0.08f

private fun DrawScope.drawPlay(brush: Brush, alpha: Float) {
    val triangle = Path().apply {
        moveTo(size.width * PlayLeft, size.height * PlayTop)
        lineTo(size.width * PlayLeft, size.height * (1f - PlayTop))
        lineTo(size.width * PlayRight, size.height * 0.5f)
        close()
    }
    drawPath(triangle, brush, alpha = alpha)
}

private fun DrawScope.drawPause(brush: Brush, alpha: Float) {
    val barHeight = size.height * (1f - 2f * PauseTop)
    val barWidth = size.width * PauseBarWidth
    drawRect(brush, topLeft = Offset(size.width * PauseLeft, size.height * PauseTop), size = Size(barWidth, barHeight), alpha = alpha)
    drawRect(brush, topLeft = Offset(size.width * (1f - PauseLeft) - barWidth, size.height * PauseTop), size = Size(barWidth, barHeight), alpha = alpha)
}

/**
 * The metal's strength on an enabled plate: resting [MediaMetalRestAlpha]; a press adds the spec's 20/255; a playing Pause ("the brighter metal") takes the full
 * strength. A disabled plate is flat metal at full strength and is not asked.
 */
internal fun mediaMetalAlpha(playing: Boolean, pressed: Boolean): Float {
    val rest = if (playing) MediaMetalPlayingAlpha else MediaMetalRestAlpha
    return if (pressed) minOf(1f, rest + MediaMetalPressedBoost) else rest
}

/** The plate's own node, for the render tests: its bounds are the plate's, inside the button's touch target. */
internal const val MediaTransportPlateTag = "media-transport-plate"
