package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirShatterMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import kotlin.math.roundToInt

/*
 * "Every hide shatters the card" (): the overlay that plays it.
 *
 * One Canvas, above the carousel or the list and below the chrome and the dock, that draws a picture of the card the
 * player hid. The strike (a warm flash and the cracks spreading from the point of impact) and then the shards and the
 * sparks are drawn from that picture, so the frame, the focus ring and the favourite pin all break with the card.
 *
 * Nothing recomposes while it plays. There is ONE clock, an Animatable from 0 to [MimirShatterMotion.EndMs], and it
 * is read in exactly one place, the Canvas's draw block: a state read there invalidates the draw and never the
 * composition (compose-expert `animation.md`, "Defer state reads": the draw phase is the latest phase a read can
 * live in; `performance.md`, "Defer State Reads to Layout/Draw Phase"). The shard outlines, the strokes, the clip and
 * the sizes are built once when the overlay starts; a frame allocates nothing (compose-expert `modifiers.md`:
 * `drawBehind` and the inline `withTransform` / `clipPath` are draw-phase primitives, `Offset` and `Color` are value
 * classes). Colours come from the theme's palette, so each theme's destruction matches its metal.
 *
 * Reduced motion is a cut: nothing is drawn, and [onFinished] is called at once, so the host seats the neighbours,
 * swaps the hero and shows the receipt in the frame the card leaves.
 */

/** The overlay's node, for a test that asks whether a shatter is on screen. It carries no semantics of its own. */
internal const val LibraryShatterOverlayTag = "library-shatter-overlay"

/** One crack and one edge glow are hairlines: 1.4 dp and 1.2 dp, the prototype's. */
private const val ShatterCrackStrokeDp = 1.4f
private const val ShatterEdgeStrokeDp = 1.2f

/**
 * Plays the destruction of a card.
 *
 * @param snapshot a picture of the card as it was drawn, captured before it left composition
 *   ([LibraryShatterCapture]).
 * @param bounds where the card was, in WINDOW coordinates and pixels, as `boundsInWindow()` reports it: the leaving
 *   card's last bounds. The overlay finds its own place in the window and draws over them, so the host can put it
 *   anywhere it fills. The picture is scaled to it, so a card that was painted through a focus scale breaks at the size
 *   it was seen at.
 * @param stableId the hidden item's stable id: the same game always breaks the same way.
 * @param reducedMotion the system's reduced-motion state; true cuts.
 * @param onFinished called once, when the last shard has faded (or at once, on a cut).
 * @param cornerRadius the card's corner, for the strike's flash to sit inside it.
 * @param clock the one clock, 0 to [MimirShatterMotion.EndMs] in milliseconds. It is the host's to hold when it wants to
 *   read the time (a test); left alone, the overlay makes its own.
 */
@Composable
internal fun LibraryShatterOverlay(
    snapshot: ImageBitmap,
    bounds: Rect,
    stableId: String,
    reducedMotion: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    cornerRadius: Dp = MimirBoxArtCorner,
    clock: Animatable<Float, AnimationVector1D> = remember(stableId) { Animatable(0f) },
) {
    if (reducedMotion || bounds.width < 1f || bounds.height < 1f) {
        LibraryShatterCut(onFinished)
    } else {
        LibraryShatterPlay(snapshot, bounds, stableId, palette, onFinished, modifier, cornerRadius, clock)
    }
}

@Composable
private fun LibraryShatterCut(onFinished: () -> Unit) {
    val latestOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) { latestOnFinished() }
}

@Composable
private fun LibraryShatterPlay(
    snapshot: ImageBitmap,
    bounds: Rect,
    stableId: String,
    palette: MimirThemePalette,
    onFinished: () -> Unit,
    modifier: Modifier,
    cornerRadius: Dp,
    clock: Animatable<Float, AnimationVector1D>,
) {
    val latestOnFinished by rememberUpdatedState(onFinished)
    val density = LocalDensity.current
    val plan = remember(stableId, bounds.width, bounds.height) { buildShatterPlan(bounds.width, bounds.height, stableId) }
    val kit = remember(plan, snapshot, density, cornerRadius) { ShatterDrawKit(plan, snapshot, density, cornerRadius) }
    // Where this overlay sits in the window, written when it is laid out (before the draw of that same frame) and read in
    // draw: plain memory, so finding it costs no recomposition.
    val origin = remember { ShatterWindowOrigin() }
    LaunchedEffect(clock) {
        clock.animateTo(
            MimirShatterMotion.EndMs.toFloat(),
            tween(MimirShatterMotion.EndMs, easing = LinearEasing),
        )
        latestOnFinished()
    }
    val strikeColor = palette.accentBright
    val sparkColor = palette.accent
    Canvas(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val at = coordinates.positionInWindow()
                origin.x = at.x
                origin.y = at.y
            }
            .testTag(LibraryShatterOverlayTag),
    ) {
        val elapsedMs = clock.value
        translate(left = bounds.left - origin.x, top = bounds.top - origin.y) {
            when (ShatterTimeline.phaseAt(elapsedMs)) {
                ShatterPhase.Strike -> drawShatterStrike(kit, elapsedMs, strikeColor)
                ShatterPhase.Shards -> drawShatterShards(kit, elapsedMs, strikeColor, sparkColor)
                ShatterPhase.Done -> Unit
            }
        }
    }
}

/** The overlay's top-left in the window. Plain memory: written in layout, read in draw. */
private class ShatterWindowOrigin {
    var x: Float = 0f
    var y: Float = 0f
}

/** Everything a frame needs that does not change during it, built once per destroy. */
private class ShatterDrawKit(
    val plan: ShatterPlan,
    val snapshot: ImageBitmap,
    density: Density,
    cornerRadius: Dp,
) {
    val shardPaths: Array<Path> = Array(plan.shards.size) { i -> plan.shards[i].outline.toPath() }

    /** The card's own rounded rectangle, so the flash never spills past a corner. */
    val cardClip: Path = Path().apply {
        val corner = with(density) { cornerRadius.toPx() }
        addRoundRect(RoundRect(0f, 0f, plan.width, plan.height, corner, corner))
    }

    val crackStroke = Stroke(width = ShatterCrackStrokeDp * density.density)
    val edgeStroke = Stroke(width = ShatterEdgeStrokeDp * density.density)
    val srcSize = IntSize(snapshot.width, snapshot.height)
    val dstSize = IntSize(plan.width.roundToInt(), plan.height.roundToInt())
    val sparkRadiusScale: Float = density.density
}

private fun ShatterPolygon.toPath(): Path = Path().apply {
    moveTo(x[0].toFloat(), y[0].toFloat())
    for (i in 1 until size) lineTo(x[i].toFloat(), y[i].toFloat())
    close()
}

/** The card, whole, with the flash over it and a crack showing at each shard's edge once the strike has reached it. */
private fun DrawScope.drawShatterStrike(kit: ShatterDrawKit, elapsedMs: Float, strikeColor: Color) {
    drawImage(
        image = kit.snapshot,
        srcOffset = IntOffset.Zero,
        srcSize = kit.srcSize,
        dstOffset = IntOffset.Zero,
        dstSize = kit.dstSize,
    )
    val flash = ShatterTimeline.flashAlpha(elapsedMs)
    if (flash > 0f) {
        clipPath(kit.cardClip) { drawRect(color = strikeColor, alpha = flash) }
    }
    val shards = kit.plan.shards
    for (i in shards.indices) {
        val crack = ShatterTimeline.crackAlpha(elapsedMs, shards[i].crackAtMs)
        if (crack > 0f) drawPath(kit.shardPaths[i], color = strikeColor, alpha = crack, style = kit.crackStroke)
    }
}

/** Each shard translated and turned from where it started, clipped out of the picture, then the sparks over them. */
private fun DrawScope.drawShatterShards(kit: ShatterDrawKit, elapsedMs: Float, strikeColor: Color, sparkColor: Color) {
    val plan = kit.plan
    val fade = ShatterTimeline.shardAlpha(elapsedMs)
    if (fade > 0f) {
        val edge = ShatterTimeline.edgeGlowAlpha(elapsedMs)
        val shards = plan.shards
        for (i in shards.indices) {
            val shard = shards[i]
            val seconds = ShatterTimeline.shardSeconds(elapsedMs, shard.delayMs)
            withTransform(
                {
                    translate(shard.travelX(seconds), shard.travelY(seconds, plan.shardGravityPxPerS2))
                    rotate(shard.turnDegrees(seconds), Offset(shard.centreX, shard.centreY))
                },
            ) {
                clipPath(kit.shardPaths[i]) {
                    drawImage(
                        image = kit.snapshot,
                        srcOffset = IntOffset.Zero,
                        srcSize = kit.srcSize,
                        dstOffset = IntOffset.Zero,
                        dstSize = kit.dstSize,
                        alpha = fade,
                    )
                }
                if (edge > 0f) drawPath(kit.shardPaths[i], color = strikeColor, alpha = edge * fade, style = kit.edgeStroke)
            }
        }
    }
    val age = ShatterTimeline.sparkAgeMs(elapsedMs)
    val sparks = plan.sparks
    for (i in sparks.indices) {
        val spark = sparks[i]
        val alpha = ShatterTimeline.sparkAlpha(age, spark.lifeMs)
        if (alpha <= 0f) continue
        drawCircle(
            color = sparkColor,
            radius = spark.radiusDp * kit.sparkRadiusScale,
            center = Offset(
                plan.impactX + ShatterTimeline.sparkOffsetX(spark, age),
                plan.impactY + ShatterTimeline.sparkOffsetY(spark, age, plan.sparkGravityPxPerS2),
            ),
            alpha = alpha,
        )
    }
}
