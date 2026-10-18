package com.gruning.mimir.rewrite.core.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * UXP-03/UXP-04 : the ONE Mimir liquid-glass recipe, shared by the Home Edit menu and
 * the Utility panel. It replaces the FUX-10b approximation, which called itself liquid glass but actually
 * painted an OPAQUE leather gradient over an 85% scrim — no blur, and nothing of the scene showed through.
 *
 * Two halves, deliberately separable because they live in different composables:
 *  - [mimirGlassBackdrop] goes on the SCENE (the content behind the modal) and actually blurs it.
 *  - [MimirLiquidGlassPanel] is the translucent panel body drawn on top of that blurred scene.
 *
 * Everything is palette-driven ([LocalMimirPalette]); no surface hand-types a colour.
 */
object MimirGlass {

    /**
     * `Modifier.blur` is a real gaussian pass only on API 31+. `minSdk` here is 30, so Android 11
     * genuinely cannot blur — this is a capability check, not a preference, and the fallback below is
     * a deterministic look rather than a degraded one.
     */
    val supportsBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Scene blur radius behind an open glass modal. Enough to kill text legibility, not shape. */
    val backdropBlurRadius: Dp = 18.dp

    /**
     * HWUI maps blur radius r to sigma 0.577r+0.5 and Skia convolves at FULL resolution once sigma ≤ 4
     * (r ≤ ~6 px), costing 20–45 ms a frame on the Bottom; keep any live blur above this and cross-fade
     * it out instead.
     */
    const val BlurDownsampledMinRadiusPx: Float = 7f

    /** Panel corner radius — one step softer than a card, so the glass reads as a floating sheet. */
    val panelRadius: Dp = 20.dp

    /**
     * Scrim alpha over the (already blurred) scene on API 31+.
     *
     * The old recipe used 0.85 because it had to hide an unblurred scene by brute force. With a real
     * blur the scrim only needs to buy contrast, so the scene stays visible through the glass — which
     * is the entire point of the treatment.
     */
    const val ScrimAlphaBlurred: Float = 0.52f

    /**
     * Scrim alpha when blur is unavailable (API 30). Deeper, because separation now has to come from
     * the scrim alone. Still lighter than the old 0.85 opaque-panel approach, and the panel body stays
     * translucent, so the two paths read as the same design rather than two different screens.
     */
    const val ScrimAlphaFlat: Float = 0.74f
}

/**
 * Blurs the scene behind an open glass modal. A no-op when [active] is false, and a no-op on API 30
 * where the platform cannot blur — [MimirLiquidGlassScrim] compensates there with a deeper scrim.
 *
 * Applied to the SCENE, not the panel: blurring the panel would blur its own text.
 */
fun Modifier.mimirGlassBackdrop(active: Boolean): Modifier =
    if (active && MimirGlass.supportsBlur) blur(MimirGlass.backdropBlurRadius) else this

// The ramped Float overload (`mimirGlassBackdrop(progress: Float)`, the launch card's radius sweep of 2026-09-01) is retired: it rebuilt the blur modifier on every frame, which recomposed its caller per frame, dropped the node at 0 and convolved a small radius at
// full resolution. A ramped blur is `Modifier.mimirGlassBackdropProgress { progress }` (MimirGlassTransition.kt), which reads the progress in draw
// and cross-fades one fixed-radius blur.

/**
 * The full-bleed scrim between the blurred scene and the panel. Its depth follows blur availability so
 * readable contrast is identical on both paths.
 */
@Composable
fun mimirGlassScrimColor(): Color {
    val palette = LocalMimirPalette.current
    val alpha = if (MimirGlass.supportsBlur) MimirGlass.ScrimAlphaBlurred else MimirGlass.ScrimAlphaFlat
    // A modal scrim is a neutral depth role. Deriving it from a light theme's app field produces a pale veil
    // behind a dark glass panel and collapses content hierarchy. Every authored palette already owns this
    // shared near-black scrim; only capability controls its alpha.
    return palette.scrim.copy(alpha = alpha)
}

/**
 * Content roles for the dark authored Liquid Glass volume.
 *
 * Every identity owns explicit scrim ink. This keeps the glass contract independent from the application
 * field while preserving the focus-ring treatment authored for that identity.
 */
fun MimirThemePalette.liquidGlassContentPalette(): MimirThemePalette =
    copy(
        textPrimary = materials.onScrimPrimary,
        textSecondary = materials.onScrimSecondary,
        textMuted = materials.onScrimMuted,
        tileFocusRing = if (presentation.glassUsesFieldFocusRing) focusRing else tileFocusRing,
    )

/**
 * The hairline a row rests on over glass: the hero cover's ink at 0.28. One value for [mimirGlassRow] and for every
 * row that wears `mimirSelectionRing` on a glass panel (its `outlineColor`), so a select row, a Settings row and a
 * launch-card button agree on what "resting" looks like.
 */
fun MimirThemePalette.glassRestingOutline(): Color = materials.hero.onCover.copy(alpha = 0.28f)

/**
 * The Mimir glass panel body: a translucent vertical wash of the authored hero material, an authored
 * sheen hairline along the top edge, and a hairline outline.
 *
 * Translucency is the substantive change from FUX-10b. The old panel painted `hero.coverTop/Middle/Bottom`
 * at full opacity, so it was a leather card, not glass. These same authored stops are reused at partial
 * alpha, which keeps every theme's identity while letting the blurred scene through.
 *
 * Ink for content on this panel comes from the three explicit [MimirThemeMaterials] on-scrim roles, so
 * modal contrast is independent from the field and Hero/book materials.
 *
 * @param radius corner radius; defaults to [MimirGlass.panelRadius] so every existing call site is
 *   unaffected. A caller that must keep house card geometry while only the *material* changes (e.g.
 *   Settings' section card, which was `MimirSpacing.radiusCard` before it became glass) passes its own —
 *   one variable moves, so the eye judges one thing rather than two.
 */
@Composable
fun MimirLiquidGlassPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = MimirSpacing.xl,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(MimirSpacing.md),
    radius: Dp = MimirGlass.panelRadius,
    /**
     * When false the body paints ONE flat tone instead of the top→bottom cover gradient.
     *
     *  On a list surface the gradient makes the last row sit on a
     * materially darker field than the first — measured on the Odin, (28,28,39) at the panel top down
     * to (10,12,17) at the bottom, so "Disc 2" had roughly half the ground luminance of "Play". Rows
     * are peers; their ground must not rank them.
     *
     * Defaults TRUE so the Home launch card, Utility, Tweaker and the Tome overlays — which are hero
     * surfaces where the gradient is the point — are byte-identical.
     */
    gradientBody: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalMimirPalette.current
    val contentPalette = remember(palette) { palette.liquidGlassContentPalette() }
    val hero = palette.materials.hero
    val shape = RoundedCornerShape(radius)
    // On the flat path the glass sits a little denser: without a blur behind it, a too-transparent
    // body would read as muddy rather than as glass.
    val bodyAlpha = if (MimirGlass.supportsBlur) 0.78f else 0.88f
    Column(
        modifier = modifier
            .clip(shape)
            .drawWithCache {
                val glass = if (gradientBody) {
                    Brush.verticalGradient(
                        0.00f to hero.coverTop.copy(alpha = bodyAlpha),
                        0.52f to hero.coverMiddle.copy(alpha = bodyAlpha),
                        1.00f to hero.coverBottom.copy(alpha = bodyAlpha),
                    )
                } else {
                    // The gradient's own midpoint, so a flat panel reads as the same material at the
                    // same depth rather than as a different, lighter or darker surface.
                    SolidColor(hero.coverMiddle.copy(alpha = bodyAlpha))
                }
                val outlineColor = hero.onCover.copy(alpha = 0.16f)
                val sheenColor = palette.materials.glassSheen
                onDrawBehind {
                    drawRect(brush = glass)
                    drawLine(
                        color = sheenColor,
                        start = Offset(0f, 0.75.dp.toPx()),
                        end = Offset(size.width, 0.75.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    // The theme-crest draw (drawMimirThemeSignature) that
                    // used to sit here -- first at centerY=0.10, then recentred to 0.5 -- is removed
                    // entirely rather than repositioned or hidden behind alpha=0f. This panel is the
                    // shared glass recipe (MimirLiquidGlassPanel) used by Utility, Tweaker, and the
                    // Home Edit / Tome-library overlays, so removing it here removes it from every one
                    // of those surfaces. UtilityDivider, blur, tint, border, radius, and padding are
                    // all unchanged.
                }
            }
            .border(width = MimirSpacing.hairlineWidth, color = hero.onCover.copy(alpha = 0.16f), shape = shape)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
    ) {
        CompositionLocalProvider(LocalMimirPalette provides contentPalette) {
            content()
        }
    }
}

/** The scrim layer, sized to the whole surface. Callers place the panel above it. */
@Composable
fun MimirLiquidGlassScrim(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(mimirGlassScrimColor())) {}
}

/**
 * A focused row's "focus light" on glass: the accent wash + focus ring the Edit and Utility rows share.
 * Returned as a modifier so both surfaces get an identical focus treatment without copying constants.
 *
 * Defaults to the original unanimated conditional — [eased] is OFF, so every existing call site
 * (Home Edit menu, HomeLaunchCard) compiles and renders byte-identical to before this parameter existed.
 *
 * @param eased opt-in for the house ease-over-time [mimirFocusRing] uses instead of a hard cut (Settings opts in; Edit and Utility retain the default instant switch). When true:
 *   - content alpha eases between [restingAlpha] and `1f`, mirroring [mimirFocusRing]'s own
 *     `restingAlpha` term. `mimirGlassRow` otherwise has NO resting-alpha concept at all — without this,
 *     an eased caller would still snap from dim to full-bright on the one channel nothing here touches.
 *   - the accent fill alpha, the ring colour, and the ring width ease rather than switch, using the SAME
 *     target values the unanimated path already paints (RC-11's hero.onCover@0.28 rest ring / accent@0.16
 *     focus fill) — this restores the ANIMATION shipping had, not the older target colours `mimirFocusRing`
 *     used before RC-11 moved Settings onto this shared grammar; those were re-decided separately and
 *     stay approved.
 *   - scale is intentionally never touched. [mimirFocusRing] has a `focusedScale` term for exactly that,
 *     and every caller of this row grammar renders a full-width row that must not grow — nothing to ease.
 * @param restingAlpha the un-focused content de-emphasis; only read when [eased] is true. Same default as
 *   [mimirFocusRing]'s so the two treatments agree on how dim "resting" looks.
 * @param identity pass the row's own stable content identity (e.g. its `FocusKey`) when [eased] is true
 *   AND this modifier can sit behind a call site that renders a DIFFERENT logical row at the same
 *   composition position across recompositions — an unkeyed `forEach`/`forEachIndexed` over a list that
 *   can change shape (Settings has several: e.g. library-source removal rows, emulator-assignment rows,
 *   controller-shortcut rows — none of those loops wrap their `SettingsRow` call in `key(...)`). Ignored
 *   when [eased] is false. See [mimirFocusRing]'s KDoc for the mechanism: without `key(identity)`, the
 *   `animate*AsState` calls below key their `Animatable`s to the composition SLOT at this call site: if a
 *   dynamic list shrinks/reorders, a slot that used to belong to one logical row is reused for another,
 *   and its remembered alpha/colour/width retarget from the OLD row's values toward the NEW row's —
 *   sampled across frames, that interpolation IS the ghost trail.
 */
@Composable
fun Modifier.mimirGlassRow(
    focused: Boolean,
    shape: RoundedCornerShape,
    eased: Boolean = false,
    restingAlpha: Float = 0.88f,
    identity: Any? = null,
): Modifier {
    val palette = LocalMimirPalette.current
    val restingRingColor = palette.glassRestingOutline()
    val focusedFillAlpha = 0.16f
    return if (!eased) {
        this
            .clip(shape)
            .background(if (focused) palette.accent.copy(alpha = focusedFillAlpha) else Color.Transparent)
            .border(
                width = if (focused) MimirSpacing.focusRingWidth else MimirSpacing.hairlineWidth,
                color = if (focused) palette.focusRing else restingRingColor,
                shape = shape,
            )
    } else {
        // `key(identity)` guard — see the [identity] KDoc above. Compositor-cheap (invariant #7): the four
        // animations below drive only opacity, colour and border width/colour, never layout.
        key(identity) {
            val contentAlpha by animateFloatAsState(
                targetValue = if (focused) 1f else restingAlpha,
                animationSpec = tween(MimirFocusRingDurationMs, easing = MimirFocusEasing),
                label = "glassRowAlpha",
            )
            val fillAlpha by animateFloatAsState(
                targetValue = if (focused) focusedFillAlpha else 0f,
                animationSpec = tween(MimirFocusRingDurationMs, easing = MimirFocusEasing),
                label = "glassRowFill",
            )
            // The ring on the ring clock, the fill and dimming on the focus response ([MimirFocusRingSettleMs]).
            val ringColor by animateColorAsState(
                targetValue = if (focused) palette.focusRing else restingRingColor,
                animationSpec = mimirFocusRingSpec(),
                label = "glassRowRingColor",
            )
            val ringWidth by animateDpAsState(
                targetValue = if (focused) MimirSpacing.focusRingWidth else MimirSpacing.hairlineWidth,
                animationSpec = mimirFocusRingSpec(),
                label = "glassRowRingWidth",
            )
            // Same `ModulateAlpha` rationale as `mimirFocusRing`: the default compositing strategy
            // allocates an offscreen buffer per unfocused row (alpha != 1f, overlapping-rendering=true by
            // default) purely to composite background+border+content, which this row's non-overlapping
            // layers never need. No scale is applied here (by design — see [eased] KDoc), so the buffer's
            // own-bounds clip that bit `mimirFocusRing` never triggers; `ModulateAlpha` is still the
            // cheaper path since every row sits dimmed at rest whenever `restingAlpha` < 1.
            this
                .graphicsLayer {
                    alpha = contentAlpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .clip(shape)
                .background(palette.accent.copy(alpha = fillAlpha))
                .border(width = ringWidth, color = ringColor, shape = shape)
        }
    }
}

/**
 * [rememberMimirGlassProgress] fed through [rememberMimirGlassHold] (dissolve, then clear): the glass under a surface that
 * opens on [MimirMotion.OverlayMs] from its request and, when the request ends, holds for [MimirMotion.OverlayDissolveCoverMs] through the
 * surface's own dissolve before it clears on OverlayMs. The hold's cancel key and the progress's identity are both [identity], so a surface
 * keyed per session (the Bottom's rename) begins each session fresh and a constant identity never cancels. The Bottom's scene-side sites (the
 * Tome's and the platform shelf's lattice blur, the two launch cards, the rename) and their timeline tests call this one function (audit
 * 2026-10-14, W2L7 review #4), so a site that drops the hold fails the test that proves the law.
 */
@Composable
fun rememberMimirHeldGlassProgress(visible: Boolean, identity: Any? = Unit, settleOpen: () -> Boolean = { false }): () -> Float =
    rememberMimirGlassProgress(visible = rememberMimirGlassHold(visible, cancelKey = identity), identity = identity, settleOpen = settleOpen)
