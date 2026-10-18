package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The authored plate width, in dp, at [MimirMannazPinHeightDp]. */
const val MimirMannazPinWidthDp = 32

/** The authored plate height, in dp. Every other dimension derives from the height. */
const val MimirMannazPinHeightDp = 24

/** The authored plate's corner radius, in dp at [MimirMannazPinHeightDp]; it scales with the height like every other dimension. */
const val MimirMannazPinCornerDp = 7

/** Where the plate starts when absent, so appearing is a scale-up rather than a pop. */
const val MimirMannazPinHiddenScale = 0.88f

/**
 * The rune's geometry in plate units (24 to the plate's height, 32 to its width; the rune is centred). Its two stems
 * stand [MimirMannazRuneHalfWidthUnits] either side of centre, from [MimirMannazRuneTopUnits] down to
 * [MimirMannazRuneBottomUnits]; its two bars cross from each stem's top to the other's waist at
 * [MimirMannazRuneWaistUnits]. The four stroke tips are where the mark gleam's stars sit.
 */
internal const val MimirMannazRuneHalfWidthUnits = 6f
internal const val MimirMannazRuneTopUnits = 5f
internal const val MimirMannazRuneWaistUnits = 13f
internal const val MimirMannazRuneBottomUnits = 19f
internal const val MimirMannazRuneStemUnits = 2.2f
internal const val MimirMannazRuneBarUnits = 1.8f

/**
 * A mark sparkle star's arm at its peak, in plate units: a sixth of the plate's height, so every star and its halo
 * stays inside the plate (the pin sits on clipped card art) -- the top tips' stars end one unit short of the rim.
 * The ring's star is 6 dp on a tile ([MimirFocusSparkleArm]); this is 4 dp at the authored height.
 */
internal const val MimirMannazPinSparkleArmUnits = 4f

/**
 * The plate's width at an arbitrary [height], holding the authored 32:24 (4:3) proportion.
 *
 * Pure, so the law is pinned by a JVM test rather than trusted -- same discipline as
 * [mimirNorseBookCoverCorner]. It matters more than it looks: the draw body derives its unit from
 * `size.minDimension`, so a caller that shrank the width independently would silently flip which
 * edge sets the scale and re-proportion the rune. Deriving width here makes the height the
 * minimum dimension by construction, at every size.
 */
fun mimirMannazPinWidth(height: Dp): Dp = height * MimirMannazPinWidthDp / MimirMannazPinHeightDp

/**
 * The Mimir ᛗ Mannaz **pin** -- a stamped leather plate carrying the branded-gold rune.
 *
 * Shared with the Library gamelist's favorite marker so Tome pins use the same legible asset.
 * This component was moved out of `feature-library`'s
 * `LibraryFavoriteMarker`, not copied. `feature-library` now delegates to it and keeps its own name as
 * a thin wrapper, so the gamelist renders the identical thing it rendered before and there is exactly
 * one implementation of the geometry. The hoist exists because `app` cannot see a `feature-library`
 * `internal`, and the Tome collection's pin marker lives in `app`.
 *
 * This codebase has already paid for the alternative: `mimirPlatformShelfmarkCode` records three
 * drifting copies of one helper. There must not be a fourth copy of this rune.
 *
 * The 64-unit rune geometry matches the Mimir brand mark, but this pin deliberately omits the mark's
 * framed global-M badge (`MimirShelfChrome`'s `MannazBadge`), so it reads as a small state tag rather
 * than a command. That separation is semantic: a framed Mannaz means the global
 * M overlay, and no surface may present two Mannaz controls with different meanings.
 *
 * **What makes it legible**, and therefore what a caller must not strip: the rune is not drawn onto
 * whatever art happens to be behind it. It carries its OWN ground -- a leather `coverTop -> coverBottom`
 * gradient with a `hardware` rim -- so contrast is a property of the pin, not of the tile under it. The
 * ink stays [MimirThemePalette.BrandGoldBright] on all nine themes: favourite/pinned is one product-wide
 * semantic mark, not a theme accent, and routing it through `palette.accent*` is what lets it disappear
 * into a cover on the themes whose accent is dim.
 *
 * **Compositor-cheap** (invariant #7): the node stays mounted and [present] drives only
 * `graphicsLayer { alpha; scaleX; scaleY }`. Toggling it can never reflow, re-measure, or displace a
 * sibling, at any [durationMs].
 *
 * A newly marked game gleams like its focused tile:
 * one slow crossing ([MimirFocusGameGlintMs]), then the slow four-star sparkle ([MimirFocusFinalSparkleMs]),
 * both tuned across three device walks. With [sparkleOnMark], a mark -- [present] going false -> true while
 * the pin is already composed -- plays that gleam on the RUNE: a band of the rune's own gold crosses its
 * strokes on the x+y diagonal, then the ring's four stars twinkle on the rune's four tips in the ring's order
 * (bottom-right, top-right, top-left, bottom-left). It is the ring's clock, [rememberMimirFocusGameGleam],
 * keyed on the mark ([rememberMimirMannazPinMarkGleam]) and read in the draw phase only, so its numbers,
 * reduced motion and calm shine hold here without a second clock, and a sparkle never recomposes the pin.
 * The composition a pin is born with is not a mark: a starred game scrolling into view is still. Un-marking
 * cancels a sparkle in flight. Only a game's mark passes it -- the Library's marker, onboarding's favourites
 * and the compact rows. The Tome's pinned mark keeps the default; sparkle there remains an unresolved design choice.
 *
 * The light waits [MimirFocusSettleDelayMs] (160 ms), at least the presence clocks (140 ms here, 110 ms at
 * onboarding and the Tome), so it starts on a plate that is fully present, at alpha one. Keep that order.
 *
 * **The second caller is not wired yet.** The Tome collection's marker is a rotated square,
 * 7dp non-compact / 5dp compact, in `palette.accentBright` at `app`'s `HomeTomeLibraryOverlay.kt`
 * (`HomeTomeLibraryEntry.Tome` branch). Replacing it remains pending and needs three changes together:
 *  1. the `Box`/`.rotate(45f)`/`.background(palette.accentBright)` marker replaced by this composable,
 *     positioned with the same `.align(Alignment.TopEnd).padding(pinPad)`;
 *  2. `durationMs = HomeTomeMutationAckMs` passed explicitly, preserving its existing 110ms
 *     acknowledgement instead of falling back to this component's gamelist default;
 *  3. `HomeTomeLibraryHeaderStatusMotionTest.thePinMarkerItselfStaysAnAlphaAndScaleOnlyGraphicsLayerTransform`
 *     updated in the SAME change -- it source-slices the marker between
 *     `"val pinMarkerPresence by animateFloatAsState("` and `".rotate(45f)"`, and BOTH anchors cease to
 *     exist. Its `sliceBetween` fails loudly on a missing anchor, so this is a hard break, not a drift.
 *     The alpha/scale-only contract that test was guarding now lives in `MimirMannazPinTest`; what
 *     remains app-side is that the call site still names `HomeTomeMutationAckMs`.
 *
 * A [height] must be chosen at that call site, because the marker being replaced is already
 * face-relative (7dp non-compact / 5dp compact). **Proposed, and PENDING REVIEW -- no one has seen this on
 * hardware:** the authored [MimirMannazPinHeightDp] non-compact, and ~16dp compact (→ ~21dp wide,
 * roughly a fifth of the ~112.7dp compact cell the 3-column 398dp grid produces, against a face 64dp
 * to 150dp tall). Both proportions remain proposals; legibility still needs on-device visual review.
 *
 * @param present whether the mark is showing. Animated, never a mount/unmount.
 * @param height the plate's height; width follows from [mimirMannazPinWidth].
 * @param durationMs the presence clock. Defaults to the gamelist's [MimirMotion.FocusMs]; a caller
 *   with its own surface-specific acknowledgement clock passes that instead.
 * @param sparkleOnMark whether a mark plays a focused game's gleam on the rune. Off by default; a
 *   GAME's favourite mark passes true.
 */
@Composable
fun MimirMannazPin(
    present: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = MimirMannazPinHeightDp.dp,
    durationMs: Int = MimirMotion.FocusMs,
    sparkleOnMark: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val presence = remember { Animatable(if (present) 1f else 0f) }
    LaunchedEffect(present) {
        presence.animateTo(
            targetValue = if (present) 1f else 0f,
            animationSpec = tween(durationMs, easing = MimirMotion.Easing),
        )
    }
    val markGlint = rememberMimirMannazPinMarkGleam(present = present, sparkleOnMark = sparkleOnMark)
    // The star is one cached unit path, as the ring's is; a pin that never sparkles (the Tome's) never builds it.
    val star = remember(sparkleOnMark) { if (sparkleOnMark) mimirFocusSparkleUnitStar() else null }
    val hero = palette.materials.hero
    Box(
        modifier = modifier
            .width(mimirMannazPinWidth(height))
            .height(height)
            .graphicsLayer {
                alpha = presence.value
                val scale = MimirMannazPinHiddenScale +
                    (1f - MimirMannazPinHiddenScale) * presence.value
                scaleX = scale
                scaleY = scale
            }
            .drawWithCache {
                // Built once per size, never per frame (the ring's discipline in mimirFocusTileRing): the plate's
                // leather and rim, the rune's ink, and the mark gleam's band and star colour.
                val unit = size.minDimension / 24f
                val centreX = size.width / 2f
                val corner = CornerRadius(MimirMannazPinCornerDp * unit)
                val leather = Brush.verticalGradient(listOf(hero.coverTop, hero.coverBottom))
                val rim = Stroke(width = 0.8f * unit)
                // One product-wide semantic mark, not a theme accent: it remains branded gold.
                val ink = MimirThemePalette.BrandGoldBright
                val inkBrush = SolidColor(ink)
                // The rune's box, the element the mark gleam plays over; its corners are the rune's four tips.
                val runeLeft = centreX - MimirMannazRuneHalfWidthUnits * unit
                val runeTop = MimirMannazRuneTopUnits * unit
                val runeWidth = 2f * MimirMannazRuneHalfWidthUnits * unit
                val runeHeight = (MimirMannazRuneBottomUnits - MimirMannazRuneTopUnits) * unit
                // The crossing's band: the ring's brush in the rune's gold, built over the rune's box the way the
                // ring's is over its element, and clearing the stems' round caps by half a stem, so it enters and
                // leaves out of sight at both ends (mimirFocusGlintShiftPx).
                val bandOutset = MimirMannazRuneStemUnits * unit / 2f
                val bandReach = (minOf(runeWidth, runeHeight) * MimirFocusRingGlintReach).coerceAtLeast(1f)
                val band = Brush.linearGradient(
                    0.0f to Color.Transparent,
                    0.5f to lerp(ink, Color.White, MimirFocusRingGlintLift),
                    1.0f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(bandReach, bandReach),
                )
                // The sparkle's stars: the ring's own lift toward white, from the rune's gold.
                val starColor = lerp(ink, Color.White, MimirFocusSparkleLift)
                val starArm = MimirMannazPinSparkleArmUnits * unit
                onDrawBehind {
                    if (unit <= 0f) return@onDrawBehind
                    drawRoundRect(brush = leather, cornerRadius = corner)
                    drawRoundRect(color = hero.hardware.copy(alpha = 0.72f), cornerRadius = corner, style = rim)
                    drawMimirMannazRune(inkBrush, centreX, unit)
                    // The mark gleam, read here in the draw phase only: a sparkle never recomposes the pin.
                    val glint = markGlint()
                    if (glint >= 0f && glint < MimirFocusRingGlintIdle) {
                        // The ring's trick: the band stays where it was built and the canvas moves under the rune,
                        // which is drawn back by the same move, so the light travels without a brush per frame.
                        val shift = mimirFocusGlintShiftPx(
                            travel = glint,
                            elementWidth = runeWidth,
                            elementHeight = runeHeight,
                            outset = bandOutset,
                            reach = bandReach,
                        )
                        val moveX = runeLeft + shift
                        val moveY = runeTop + shift
                        translate(left = moveX, top = moveY) {
                            drawMimirMannazRune(band, centreX, unit, dx = -moveX, dy = -moveY)
                        }
                    }
                    if (star != null && glint > MimirFocusRingGlintIdle && glint < MimirFocusRingSparkleEnd) {
                        drawMimirFocusSparkleStars(
                            star = star,
                            color = starColor,
                            sparkle = glint - MimirFocusRingGlintIdle,
                            left = runeLeft,
                            top = runeTop,
                            right = runeLeft + runeWidth,
                            bottom = runeTop + runeHeight,
                            arm = starArm,
                            strength = 1f,
                        )
                    }
                }
            }
            .clearAndSetSemantics { },
    )
}

/**
 * The mark gleam's clock: a focused game's gleam ([rememberMimirFocusGameGleam]) keyed on the MARK instead of on
 * focus. Marking a favorite gives its Mannaz rune the game gleam.
 *
 * A mark is [present] going false -> true while the pin is already composed, and each one is a new epoch, so the
 * clock restarts for it as it does for a new selection. What the composition was born with is not a mark: a starred
 * game scrolling into view stays still (the flare's own discipline, [rememberMimirFocusFlare]). [present] going
 * false clears the epoch, which stops a sparkle in flight; with [sparkleOnMark] off the clock sees null and idles.
 * The shared clock carries everything else -- the settle delay, the one crossing, the slow sparkle, reduced motion,
 * calm shine -- so there is no clock here and none of its numbers.
 *
 * Derived in composition rather than in an effect, so the gleam re-keys in the very composition that saw the mark:
 * the holder is plain state read and written here only, and a re-execution with the same [present] changes nothing.
 * Internal so the trigger is tested the way the ring's clock is ([MimirFocusGameGlintLoopTest]); the pin reads the
 * provider in the draw phase only.
 */
@Composable
internal fun rememberMimirMannazPinMarkGleam(present: Boolean, sparkleOnMark: Boolean): () -> Float {
    val mark = remember { MimirMannazPinMark(present) }
    if (present != mark.previous) {
        mark.previous = present
        mark.epoch = if (present) (mark.epoch ?: 0) + 1 else null
    }
    return rememberMimirFocusGameGleam(settledOn = if (sparkleOnMark) mark.epoch else null)
}

/** What a pin's composition was born with, and the marks it has seen since; see [rememberMimirMannazPinMarkGleam]. */
private class MimirMannazPinMark(var previous: Boolean) {
    var epoch: Int? = null
}

/**
 * The ᛗ rune's four strokes, [dx]/[dy] px from where they sit on the plate. The ink pass draws them in place; the
 * mark gleam's crossing draws them again under a moved canvas, passing the move back so the strokes land on the ink
 * while the band brush stays where it was built. One geometry, two passes: the light can never drift off the rune.
 */
private fun DrawScope.drawMimirMannazRune(brush: Brush, centreX: Float, unit: Float, dx: Float = 0f, dy: Float = 0f) {
    val left = centreX - MimirMannazRuneHalfWidthUnits * unit + dx
    val right = centreX + MimirMannazRuneHalfWidthUnits * unit + dx
    val top = MimirMannazRuneTopUnits * unit + dy
    val waist = MimirMannazRuneWaistUnits * unit + dy
    val bottom = MimirMannazRuneBottomUnits * unit + dy
    val stem = MimirMannazRuneStemUnits * unit
    val bar = MimirMannazRuneBarUnits * unit
    drawLine(brush, Offset(left, top), Offset(left, bottom), strokeWidth = stem, cap = StrokeCap.Round)
    drawLine(brush, Offset(right, top), Offset(right, bottom), strokeWidth = stem, cap = StrokeCap.Round)
    drawLine(brush, Offset(left, top), Offset(right, waist), strokeWidth = bar, cap = StrokeCap.Round)
    drawLine(brush, Offset(right, top), Offset(left, waist), strokeWidth = bar, cap = StrokeCap.Round)
}
