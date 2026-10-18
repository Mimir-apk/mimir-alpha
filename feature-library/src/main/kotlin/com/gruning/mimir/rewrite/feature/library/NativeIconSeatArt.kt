package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import com.gruning.mimir.rewrite.core.ui.MimirArtFailureLog
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirCoverCacheKey
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.rememberMimirArtWired
import java.io.File

/**
 * The art state of a native-app icon SEAT (the Favorites list and the add picker), hoisted so the seat's ground and its content read
 * ONE state:
 *
 *  - [wired] is whether the icon is a wired source: a blank path is not, and neither is a path whose file is gone, which is the
 *    off-main verdict of [rememberMimirArtWired] (the letter then draws on a ground, the normal no-art look);
 *  - [painter] is the Coil painter of a wired icon (null when unwired);
 *  - [alpha] is the icon's reveal: 0 while it loads or after it failed, fading to 1 on arrival (warm art starts at 1).
 */
internal class NativeIconArt(
    val wired: Boolean,
    val painter: AsyncImagePainter?,
    val alpha: Animatable<Float, AnimationVector1D>,
    val failed: Boolean,
) {
    /**
     * The seat ground's alpha, read in the draw phase: whole for a seat with no art wired (it carries the letter), else it follows
     * the icon, so a loading or failed icon draws NO plate and a loaded one keeps its ground, fading in with it.
     */
    fun groundAlpha(): Float = if (wired) alpha.value else 1f
}

@Composable
internal fun rememberNativeIconArt(iconPath: String?): NativeIconArt = key(iconPath) {
    val wired = rememberMimirArtWired(iconPath)
    val context = LocalContext.current
    val request = remember(iconPath, wired, context) {
        if (wired && !iconPath.isNullOrBlank()) mimirCoverRequest(context, File(iconPath), cacheModel = iconPath) else null
    }
    val painter = if (request != null) rememberAsyncImagePainter(model = request) else null
    val artReady = when (val state = painter?.state) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> state.painter != null
        else -> false
    }
    val warmHit = remember(iconPath, context) {
        !iconPath.isNullOrBlank() &&
            context.imageLoader.memoryCache?.get(MemoryCache.Key(mimirCoverCacheKey(iconPath))) != null
    }
    val alpha = remember(iconPath) { Animatable(if (warmHit) 1f else 0f) }
    LaunchedEffect(iconPath, artReady) {
        if (artReady && alpha.value < 1f) {
            alpha.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        }
    }
    val failed = painter?.state is AsyncImagePainter.State.Error
    // R8 : the icon is wired, so a decode failure draws nothing (the letter is for no icon, or a gone file).
    MimirArtFailureLog(failed = failed, model = iconPath)
    NativeIconArt(wired = wired, painter = painter, alpha = alpha, failed = failed)
}

/**
 * An icon seat's ground (its fill and its outline), drawn at [alpha] in the draw phase so it fades with the icon and never
 * recomposes. [alwaysOutline] keeps the outline whole whatever [alpha] says: the focused seat's outline is selection feedback, not
 * ground, so it stays while a wired icon is still loading.
 */
internal fun Modifier.nativeIconGround(
    corner: Dp,
    fill: Color,
    outline: Color,
    strokeWidth: Dp,
    alwaysOutline: Boolean = false,
    alpha: () -> Float,
): Modifier = drawWithContent {
    val a = alpha().coerceIn(0f, 1f)
    if (a > 0f) drawRoundRect(fill, cornerRadius = CornerRadius(corner.toPx()), alpha = a)
    drawContent()
    val outlineAlpha = if (alwaysOutline) 1f else a
    if (outlineAlpha > 0f) {
        val stroke = strokeWidth.toPx()
        drawRoundRect(
            color = outline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((corner.toPx() - stroke / 2f).coerceAtLeast(0f)),
            style = Stroke(width = stroke),
            alpha = outlineAlpha,
        )
    }
}
