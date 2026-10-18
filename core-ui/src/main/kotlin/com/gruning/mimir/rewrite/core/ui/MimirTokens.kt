package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MIMIR-REWRITE :core-ui — the non-color design tokens + the focus-ring modifier.
 *
 *
 * Elevated + generalized from the ratified feature-settings/theme tokens (which stay the baseline).
 * Every gap, radius, and text role is a token so no surface hand-types a number and the identity can't
 * drift (the web product's CSS drifted exactly because spacings were per-surface literals).
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// TYPE SCALE
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * THE capped type scale. Policy 2026-08-11.
 *
 * The tree previously carried EIGHTEEN distinct sizes across 58 call sites — a spread, not a scale.
 * Every role below now resolves to one of exactly [MimirTypeStep]'s five steps, so a surface can no
 * longer invent a size between them.
 *
 * The type-scale constraints, beyond the cap:
 *  - **A row's title and its subtext share one size AND one weight** ([Row] and [Support] are both
 *    Body/Medium). They are told apart by CASE and colour, not by scale — the title is capped, the
 *    subtext is not. This is the change with the widest blast radius: subtext used to be a size smaller
 *    and a weight lighter on every list row in the product.
 *  - **Screen headings stay larger.** The same-size rule binds a title to *its own* subtext, not to the
 *    surface heading above them, so a player can still tell at a glance which screen they are on.
 *
 * Roles are deliberately NOT collapsed even where two now resolve to the same step: [Pill] and
 * [MenuAction] are both Label, but they mean different things, and a later type-scale change can move one without
 * hunting call sites.
 *
 * NOT governed by this scale: `PerformanceOverlayPresenter`'s raw 5/7/8sp literals. That surface sizes
 * its own window from its own text and its geometry is device-certified; pulling it onto the 9sp floor
 * moves certified pixels and is owed an Odin pass, not a host build.
 *
 * ALSO not governed, by `ThemeGallery`'s two off-scale preview-card sizes,
 * **10sp** on the compact card's label and **13sp** on the wide card's title. Same reason in a different
 * shape -- those cards size their content to a fixed book aspect and a measured card height, so the label
 * is laid out against the artwork rather than read as a row. Snapping them to Label/Body would move pixels
 * on a tuned card to satisfy a rule about READING sizes that this text is not doing.
 *
 * The exemption is exactly two sizes in one file. `SettingsTypeScaleAuditTest` holds it there: any third
 * raw size in Settings, or either of these appearing in another file, fails. The four sizes on that same
 * card that DID sit on a step (11sp, 9sp) are bound to [MimirTypeStep] rather than exempted, so they still
 * follow the scale if it moves.
 */
object MimirTypeStep {
    /** Screen heading, hero title, and the rune actions that read as icons. */
    val Display = 22.sp
    /** Panel, modal, and overlay titles — a heading that sits inside a surface rather than naming it. */
    val Heading = 18.sp
    /** THE reading size. A row's title and its subtext both live here . */
    val Body = 14.sp
    /** Eyebrows, pills, menu actions, controller legends — supporting ink, never a row's own copy. */
    val Label = 11.sp
    /** The floor. Dense state, counters, and identifiers only; anything a player must READ sits higher. */
    val Micro = 9.sp
}

object MimirTypeScale {
    /** Screen / hero title (e.g. "Settings", a platform hero). Was 26sp. */
    val Title = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Display,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    )

    /**
     * Static screen/panel title: the capped form of [Title], tracked.
     *
     * The former 18–22sp values now use Body.
     * This role is shared by 34 call sites — onboarding headings, modal titles, Browse/Library headers —
     * so a capped title is now the SAME SIZE as body copy everywhere, carrying its heading weight only
     * through caps, tracking and SemiBold. This is the most aggressive
     * flattening in the scale, and modal titles are the surfaces most worth a second device look.
     */
    val TitleCaps = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Body,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    )

    /**
     * Square/fontScale-safe static title; layouts choose this instead of shrinking interaction targets.
     *
     * Follows [TitleCaps] down one step. A "compact" variant that is LARGER than the role it compacts
     * would invert under font scaling, and two surfaces (AppShellScaffold, MimirShelfChrome) pick
     * between the pair on a `compact` flag.
     */
    val TitleCapsCompact = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )

    /** Dynamic overlay title: preserves authored casing while sharing one restrained modal scale. */
    val OverlayTitle = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Heading,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** A quick-action rune: icon-sized, centered ink rather than a text label. */
    val RuneAction = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Display,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** A rail / nav category label. Was 16sp. */
    val Rail = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Body,
        fontWeight = FontWeight.Medium,
    )

    /** A list row's primary label. Capped at its call sites; matches [Support] exactly otherwise. */
    val Row = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Body,
        fontWeight = FontWeight.Medium,
    )

    /**
     * A row's supporting / secondary line — deliberately IDENTICAL to [Row] in size and weight.
     *
     * Was 13sp/Normal. This is a size-and-weight pairing change: the pair is now separated by case
     * and colour alone. If this ever drifts back below [Row], the pairing no longer holds.
     */
    val Support = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Body,
        fontWeight = FontWeight.Medium,
    )

    /** Normalized-smaller supporting line for panels stacking several subtext rows close together. */
    val SupportCompact = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
    )

    /** Section eyebrow — small, tracked, ACCENT-colored in use. Was 12sp. */
    val Eyebrow = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    )

    /** A status pill / chip (Ready, Local only) or a small badge. Was 12sp. */
    val Pill = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
    )

    /** Static header grammar: compact caps, tracked, optionally led by one restrained rune. Was 10sp. */
    val RunicEyebrow = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )

    /** Square/fontScale-safe form of [RunicEyebrow]. */
    val RunicEyebrowCompact = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Micro,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )

    /** Controller legend grammar: compact caps with stable key/action pairs and separators. Was 10sp. */
    val ControlHint = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.65.sp,
    )

    /** Square/fontScale-safe controller legend; never used as body/help copy. */
    val ControlHintCompact = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Micro,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )

    /** Compact state, count, and identifier ink. Unlike [ControlHint], this never teaches a control. */
    val MicroStatus = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Micro,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.65.sp,
    )

    /** Square-safe micro status. Was 8sp — the scale has no step below [MimirTypeStep.Micro]. */
    val MicroStatusCompact = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Micro,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.45.sp,
    )

    /** Full-caps static action inside menus, tabs, and submenus. Was 12sp. */
    val MenuAction = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.55.sp,
    )

    /** Square/fontScale-safe full-caps action; never used to shrink an interaction target. */
    val MenuActionCompact = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.45.sp,
    )

    /** Dense modal action approved for bounded edit/media pickers; interaction height stays unchanged. */
    val MenuActionDense = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.45.sp,
    )

    /** Dense modal action for compact/font-scale constrained picker geometry. */
    val MenuActionDenseCompact = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Micro,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    )

    /** Persistent command-rail label. Was 10sp. */
    val RailAction = TextStyle(
        fontFamily = MimirFonts.Sans,
        fontSize = MimirTypeStep.Label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// SPACING + SHAPE
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** A single 4dp-based scale + radii + focus-ring width + minimum handheld target. */
object MimirSpacing {
    /** 4dp — hairline gaps, icon-to-label. */
    val xs: Dp = 4.dp
    /** 8dp — tight intra-component spacing. */
    val sm: Dp = 8.dp
    /** 12dp — default row inner padding. */
    val md: Dp = 12.dp
    /** 16dp — standard content gutter. */
    val lg: Dp = 16.dp
    /** 24dp — section separation. */
    val xl: Dp = 24.dp
    /** 32dp — screen edge padding / hero breathing room. */
    val xxl: Dp = 32.dp

    /** Corner radius for cards / rows / media-art frames. */
    val radiusCard: Dp = 14.dp
    /** Corner radius for pills / chips. */
    val radiusPill: Dp = 999.dp
    /** Focus-ring stroke width (the accent border). */
    val focusRingWidth: Dp = 2.dp
    /** Resting (unfocused) hairline width. */
    val hairlineWidth: Dp = 1.dp
    /** Minimum touch/focus target on a handheld. */
    val minTarget: Dp = 48.dp

    // ── Elevation steps (theme-critique F3: depth needs a shadow, not a bigger hex) ──
    /** A raised card / section container. */
    val elevationCard: Dp = 6.dp
    /** A modal panel (select overlay, add-picker). */
    val elevationModal: Dp = 22.dp
}

/**
 * THE one logo stage (policy 2026-09-15, R6-P2-09): the secondary launch card and the launch
 * transition draw clear-logo art on this fixed box, ink-fit and centred, so a logo is the same size on
 * both and never smaller than its file's padding lets it be.
 */
object MimirLogoStage {
    /** The Now Playing card's own logo width: the 538 dp Bottom panel less its 2 x 40 dp column gutter is 458 dp; 430 leaves 14 dp a side. */
    val width: Dp = 430.dp
    /**
     * a SQUARER stage than the old 3:1, so a 1:1 crest draws 215 dp wide
     * instead of 143 (the old threefold gap against a 6:1 wordmark's 430 dp) while a 6:1 wordmark still
     * lands at 430 x 72 dp -- nothing distorts, nothing crops. This is the STAGE's own aspect, a
     * deliberate 2:1 tuning ratio that needs visual assessment on device; it is a distinct law from
     * [MimirFlatLogoArtDefaults.AspectRatio] (3:1), which stays what it always was -- the clear-logo
     * FILE format's own ratio -- and is not re-derived from it.
     */
    const val AspectRatio: Float = 2f
    /** 430 / 2 = 215 dp. */
    val height: Dp = width / AspectRatio
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// FOCUS RING — compositor-only animation
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The HOUSE focus treatment: an accent border + a whisper scale + full opacity when focused, resting on
 * a hairline outline at a slight de-emphasis when not. Three surfaces keep sanctioned bespoke treatments
 * (documented at their call sites): the Settings rail (ember fill+border), the select-overlay
 * rows (fill + left accent bar, no scale), and the shelf coverflow hero (tileRing stroke, snap-by-design).
 *
 * CRITICAL : focus animation uses COMPOSITOR-ONLY properties —
 * `scale` (transform), `alpha` (opacity), and `border` (a draw that does not relayout). It NEVER changes
 * layout on a focus move (no size/padding/weight change), so a D-pad sweep across a grid stays cheap.
 *
 * @param focused whether this element currently owns focus.
 * @param palette the active theme palette (accent + outline come from it).
 * @param cornerRadius the ring radius; defaults to the card radius (pass [MimirSpacing.radiusPill] for chips).
 * @param focusedScale the whisper grow on focus — keep it tiny (compositor scale, no reflow).
 * @param restingAlpha the un-focused de-emphasis (opacity, not a color swap).
 */
// The final-shelf 60 fps reference uses the house CSS "ease" curve. Compose's state animations retarget
// in place when new input arrives: no queued animation and no input-path delay.
internal val MimirFocusEasing: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * Bounded interaction-motion tokens from the final-shelf 60 fps reference.
 *
 * These durations are for retargetable state animations on draw/compositor properties. Input and logical
 * focus update synchronously; callers must never delay dispatch, queue a scroll, or await one of these
 * animations before accepting another command.
 */
object MimirMotion {
    /** Focus ring, quick-action label, and focused chrome. */
    const val FocusMs = 140

    /** Incoming/outgoing hero scale, alpha, bloom, and title handoff. */
    const val HeroContentMs = 160

    /** Whole-scene presence handoff. */
    const val SceneMs = 160

    /** Upper bound for transient overlays, hints, and toast acknowledgement. */
    const val OverlayMs = 180

    /**
     * How far a screen travels as it hands over to the next one in a sequence.
     *
     * The earlier implementation faded without glide, so forward and backward moves looked the same.
     *
     * **Small on purpose.** This is a handover between two screens of the same sequence, not a page push:
     * the arriving screen slides in from the side it is coming from and the departing one leaves the
     * opposite way, far enough to read as direction and not far enough to read as a swipe. At the house
     * [OverlayMs] clock, a larger value turns the tail of the curve into visible sliding rather than a
     * settle -- and both screens are on stage together for that whole window, so travel is the one part of
     * this transition the player sees twice.
     *
     * Density-independent, so it is resolved against `LocalDensity` at the call site and handed to the
     * transition as pixels -- `AnimatedContent`'s `transitionSpec` is not `@Composable` and cannot read it.
     *
     * Onboarding's outer step boundary travels too since 2026-09-28: the
     * departing step glides out, then the new one glides in on [OverlayMs], this distance each way, the header
     * still.
     */
    const val GlideTravelDp = 22

    /**
     * How far a page that is replaced IN PLACE travels: the swap tier (the gamelist's L2/R2 and Carousel/List changes,
     * the Settings detail pane). Pages travel 22dp and swaps 8dp, along the axis of the change, on the house easing. Like [GlideTravelDp], resolved against `LocalDensity`
     * at the call site.
     */
    const val SwapTravelDp = 8

    /**
     * How long scene chrome — the backdrop blur and the destination's suppressed semantics — must outlast
     * an overlay that is dissolving away on [OverlayMs].
     *
     * **Deliberately SHORTER than [OverlayMs], and the gap is measured rather than chosen.** The house
     * [Easing] is `CubicBezierEasing(0.25, 0.1, 0.25, 1.0)` — CSS `ease`, which is slow at the tail — so a
     * surface fading 1 → 0 over 180 ms is already at alpha **.148 by 100 ms, .075 by 120 ms and .044 by
     * 130 ms**. Holding the chrome to the animation's nominal end therefore spends its last ~50 ms
     * covering something the player can no longer see, and the whole return finishes that much later.
     *
     * 130 ms is where two independent instruments agree the outgoing surface is gone, measured on the
     * Odin on 2026-08-15 : `tesseract` last read the
     * departing file manager at t=100 ms and read nothing of it at t=133 ms, and the per-frame
     * variance-of-Laplacian flattened at t≈133 ms. The value follows that evidence; measured
     * effect on device was **367 → 333 ms** for the whole return, with no double exposure introduced.
     *
     * **What this constant does NOT fix, and a metric trap worth knowing.** The middle of the return is
     * illegible — neither surface can be read — and that is NOT a static screen and NOT this constant's
     * doing: a per-frame difference pass finds ZERO identical consecutive frames across the whole
     * transition. It is the backdrop blur unwinding through radii where OCR has nothing to lock onto and
     * variance-of-Laplacian barely moves. Both of those are non-linear readouts of blur RADIUS, not of
     * activity, and an earlier draft of this KDoc mistook one for the other. If that stretch ever needs
     * to shrink, the lever is the blur ramp's duration or its 18 dp depth in `MimirGlass`, not this.
     *
     * This is NOT a licence to shorten it further. The hold's job is to keep the backdrop UNIFORM while
     * the overlay dissolves; releasing it while the outgoing surface is still visible composites two
     * detailed images and puts two complete UIs on screen at once, which is the defect this constant's
     * own investigation started from.
     */
    const val OverlayDissolveCoverMs = 130

    /**
     * The slow arrival: three times [OverlayMs], for onboarding pages and pill flares, and the
     * page tier's arrival ([MimirPageMotion.ArriveMs]).
     */
    const val ShineMs = OverlayMs * 3

    /** Motion never delays the input path. */
    const val InputDelayMs = 0

    val Easing: Easing = MimirFocusEasing

    /** Compatibility names used by the existing primary/companion scene crossfades. */
    const val ModeTransitionMs = SceneMs
    val ModeTransitionEasing: Easing = MimirFocusEasing
}

/**
 * The clocks shared by card-shatter hide animations.
 * Every value is in milliseconds from the moment the hide prompt's [MimirMotion.OverlayDissolveCoverMs] beat ends,
 * the moment the shatter starts, so the card is never destroyed under the closing prompt. The pin in
 * `MimirMotionLiteralPinTest` bans a raw clock outside this file; the shatter's numbers live here so the geometry,
 * the overlay and the hosts that wait on it read one set.
 *
 * Derived from the house clocks where the derivation is honest ([StrikeMs] is half of [MimirMotion.OverlayMs], the
 * [GapGlideMs] is twice it) and named where it is not. The receipt and the input gate are here too, because they are
 * read off the same clock: the receipt lands as the last shard fades, and the pad waits until the neighbours have
 * closed the gap.
 */
object MimirShatterMotion {
    /** The strike: a warm flash on the card, and the cracks spreading from the point of impact. */
    const val StrikeMs = MimirMotion.OverlayMs / 2

    /** How long one shard's edges take to fade in, once the crack has reached it. */
    const val CrackFadeMs = StrikeMs / 3

    /** The crack reaches the farthest shard this late, so the last one finishes fading in as the strike ends. */
    const val CrackSpreadMs = StrikeMs - CrackFadeMs

    /** The shards leave the card from [StrikeMs]; the farthest one leaves this much later than the nearest. */
    const val ShardDelaySpreadMs = 70

    /** A shard holds full alpha until here, then fades to nothing at [EndMs]. */
    const val ShardFadeFromMs = 260

    /** The shards' edge glow, the light along the cracks, is gone by here. */
    const val EdgeGlowEndMs = 400

    /** A spark lives at least this long from [StrikeMs]... */
    const val SparkLifeMinMs = 280

    /** ...and at most this much longer. The longest spark is gone before [EndMs]. */
    const val SparkLifeSpanMs = 180

    /** The overlay is over: the last shard is at alpha 0, and no spark is alive. */
    const val EndMs = 560

    /** The hero art holds the destroyed game's picture until here, then cross-fades to the next one. */
    const val HeroHoldMs = 300

    /** The cross-fade is the house overlay clock: 300 to 480 ms. */
    const val HeroCrossFadeMs = MimirMotion.OverlayMs

    /** The neighbours start to close the gap here, while the shards are still falling. */
    const val GapLeadMs = 300

    /** The gap closes on the house easing over twice the house overlay clock: 300 to 660 ms. */
    const val GapGlideMs = MimirMotion.OverlayMs * 2

    /** The confirmation says where the game went as the last shard fades. */
    const val ReceiptAtMs = EndMs

    /** The pad is held until the gap has closed, and not a frame longer. */
    const val InputHoldMs = GapLeadMs + GapGlideMs
}

/**
 * How far a pressed Bottom control draws in: the one press response. A touch on any Bottom control scales it to
 * this on [MimirMotion.FocusMs] with the house [MimirMotion.Easing], both ways (see `mimirPressResponse`), and nothing else about the control changes.
 * Pressing moves IN, never out : a grown control spills past the box that clips it. The ᛗ coin is native and cannot read this file,
 * so it keeps a mirrored constant that a source pin holds equal.
 */
const val MimirPressScale = 0.97f

internal const val MimirFocusRingDurationMs = MimirMotion.FocusMs

/** Process-wide ambient-motion state. Finite interaction responses do not use this gate. */
enum class AmbientMotionPhase {
    INTERACTING,
    RECENT,
    IDLE,
}

fun Modifier.mimirFocusRing(
    focused: Boolean,
    palette: MimirThemePalette,
    cornerRadius: Dp = MimirSpacing.radiusCard,
    focusedScale: Float = 1.03f,
    restingAlpha: Float = 0.88f,
    active: Boolean = false,
    // Pass the element's CONTENT identity -- the game id, the row key, the file path. Never the list
    // index, which is the position-keying this parameter exists to defeat. Defaulted so all existing
    // call sites compile unchanged; the fix is INERT until a call site passes one.
    identity: Any? = null,
): Modifier = composed {
    // Shipping doesn't SNAP focus — it eases the grow/opacity/ring over ~120ms. We animate ONLY the
    // compositor-safe channels (scale, opacity, ring color, ring width) so nav stays free of layout thrash
    // (invariant #7). `composed` keeps every caller a plain Modifier chain — no @Composable-factory change.
    // `key` is what makes this treatment idempotent. animate*AsState initialises its Animatable AT the
    // target on first composition and only animates on SUBSEQUENT target changes -- so a fresh identity
    // starts already correct and plays nothing, while a focus change within one identity still eases
    // exactly as before. Without this, `composed` keys these four remembers to the composition SLOT, and
    // a recycled slot retargets all four from the previous element's values toward the new one's. That
    // interpolation, sampled across the panel's frames, IS the ghost trail.
    key(identity) {
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(MimirFocusRingDurationMs, easing = MimirFocusEasing),
        label = "focusRingScale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else restingAlpha,
        animationSpec = tween(MimirFocusRingDurationMs, easing = MimirFocusEasing),
        label = "focusRingAlpha",
    )
    // Three states: FOCUSED = gold ring; ACTIVE (open-but-not-focused, e.g. the Settings rail's current
    // category) = a heavier outline, no accent; RESTING = a hairline outline. [active] defaults false, so
    // every existing caller (shelf, gamelist, rows) is unchanged.
    // The ring itself settles on the ring clock (product-wide 2x; fast mid-traverse), the scale and
    // dimming above keep the focus response: see [MimirFocusRingSettleMs].
    val ringColor by animateColorAsState(
        // palette.focusRing == accent on the dark themes (zero change); light themes use a saturated
        // contrast-safe authored tone because their pale decorative accents disappear against the field.
        targetValue = if (focused) palette.focusRing else palette.outline,
        animationSpec = mimirFocusRingSpec(),
        label = "focusRingColor",
    )
    val ringWidth by animateDpAsState(
        targetValue = if (focused || active) MimirSpacing.focusRingWidth else MimirSpacing.hairlineWidth,
        animationSpec = mimirFocusRingSpec(),
        label = "focusRingWidth",
    )
    // ONE layer, and it must never composite offscreen. This replaced `.scale(scale).alpha(contentAlpha)`,
    // which was two layers and a clip nobody asked for.
    //
    // **`Modifier.alpha(a)` sets `clip = true` when `a != 1f`.** Verified from shipped ui-android:1.7.3
    // bytecode rather than assumed: AlphaKt.alpha returns the receiver unchanged when alpha == 1.0f, and
    // otherwise calls graphicsLayer with the default mask 126971 -- bit 2 clear (alpha passed) and bit 12
    // clear (clip passed), with `iconst_1` in the clip slot. Since `restingAlpha` defaults to 0.88, EVERY
    // UNFOCUSED element in this product was being clipped to its own layout bounds, and the clip then
    // VANISHED as the focus animation carried alpha to exactly 1.0 -- the modifier chain changed shape
    // mid-animation, so the layer was destroyed and rebuilt at the moment the ring was widest.
    //
    // CompositingStrategy.ModulateAlpha also prevents pale/non-actionable items and hidden-folder
    // focus rings from clipping during fades.
    // The default `Auto` maps to `setUseCompositingLayer(false)` + `setHasOverlappingRendering(TRUE)`
    // (RenderNodeApi29, same artifact), and a render node with overlapping rendering and alpha < 1 must
    // composite into an offscreen buffer sized to its own bounds -- which clips anything drawn outside
    // them. The focused ring is scaled 1.03 and drawn OUTSIDE the tile's bounds, so on any dimmed element
    // it was cut off. That is why the defect tracked the pale rows and the hidden folders exactly:
    // undimmed tiles sit at alpha 1, never allocate the buffer, and never clip. `ModulateAlpha` maps to
    // `setHasOverlappingRendering(false)` and modulates alpha per drawing instruction instead, so no
    // buffer is allocated and nothing is clipped.
    //
    // The trade is real and belongs on a device pass: ModulateAlpha blends each drawing instruction
    // separately, so genuinely OVERLAPPING children inside a dimmed element blend differently than they
    // would through a single flattened buffer. These treatments paint a background, a border and content
    // that do not overlap each other, which is why it is the right strategy here and not a blanket rule.
    Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = contentAlpha
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .border(width = ringWidth, color = ringColor, shape = RoundedCornerShape(cornerRadius))
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// ELEVATION — the ONE themed surface shadow
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The house elevation treatment (theme-critique F3, policy R15b): cards, rails, and modals lift off
 * the field with a shadow TINTED by the theme's own glow tokens — the Compose translation of shipping's
 * themed box-shadow halos, and a generalization of the pattern the gamelist row + add-picker already
 * shipped. Near-black surfaces can't carry depth by hex alone (the measured base→high span is 1.03–1.26:1
 * across themes), so elevation is the systemic fix; the hexes only widen where prescribed.
 *
 * The default ambient/spot alphas are the gamelist row's; callers with a certified different weight
 * (the add-picker's softer 0.16/0.22) pass their own.
 */
fun Modifier.mimirSurfaceShadow(
    palette: MimirThemePalette,
    elevation: Dp,
    shape: androidx.compose.ui.graphics.Shape,
    ambientAlpha: Float = 0.24f,
    spotAlpha: Float = 0.34f,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = palette.tileGlow.copy(alpha = ambientAlpha),
    spotColor = palette.tileGlowStrong.copy(alpha = spotAlpha),
)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// COMPOSITION LOCALS — bespoke components read tokens without threading params
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The active theme palette, provided by [MimirTheme]. Bespoke Mimir components (focus rings, media-art
 * frames, pills, the shelf) read this instead of remapping the Material [androidx.compose.material3.ColorScheme],
 * so the two-accent rule (accent vs semantic-success) stays intact — Material only carries the accent.
 */
val LocalMimirPalette = staticCompositionLocalOf<MimirThemePalette> {
    error("LocalMimirPalette not provided — wrap content in MimirTheme { }")
}

/** The type scale, so components read one source instead of re-declaring styles. */
val LocalMimirTypeScale = staticCompositionLocalOf { MimirTypeScale }

/**
 * The active theme's identity, provided by [MimirTheme]. Surfaces that need the theme's NAME (e.g. the
 * Appearance row showing the current selection) or its id read this — the palette carries color, not the
 * label. Defaults to [MimirThemeId.DEFAULT] outside a [MimirTheme].
 */
val LocalMimirThemeId = staticCompositionLocalOf { MimirThemeId.DEFAULT }

/**
 * True during a short controller-navigation burst. Shipping suppresses shelf transition noise while the
 * D-pad is moving; Mimir components use this to snap focus chrome and position markers for that window.
 *
 * PERF-G (2026-07-17): the local carries the STABLE [kotlinx.coroutines.flow.StateFlow], not the
 * flipping Boolean. Providing the raw per-press Boolean through a STATIC local invalidated the entire
 * app content twice per d-pad press (composition trace: MimirAppCoordinator ×2/press, ~22ms/commit) —
 * static locals full-invalidate on value change by design. The flow instance never changes, so the
 * provider never invalidates; consumers collect it in their OWN scope and pay alone.
 */
val LocalMimirControllerNavigationActive: androidx.compose.runtime.ProvidableCompositionLocal<kotlinx.coroutines.flow.StateFlow<Boolean>> =
    staticCompositionLocalOf { kotlinx.coroutines.flow.MutableStateFlow(false) }

/**
 * Shared ambient-motion phase for every Compose root. This is deliberately separate from
 * [LocalMimirControllerNavigationActive], which remains a short focus-transition signal. An unprovided root
 * fails closed at [AmbientMotionPhase.IDLE] instead of running an unbounded ambient loop.
 */
val LocalMimirAmbientMotionPhase = compositionLocalOf { AmbientMotionPhase.IDLE }

/**
 * Convenience accessors so call sites read `MimirTokens.palette` / `MimirTokens.type` inside a
 * composable without threading the CompositionLocals by hand. (The theme ENTRY is the `MimirTheme(...)`
 * composable in MimirTheme.kt; this token accessor is deliberately a separate name so both can coexist.)
 */
object MimirTokens {
    val palette: MimirThemePalette
        @Composable get() = LocalMimirPalette.current
    val type: MimirTypeScale
        @Composable get() = LocalMimirTypeScale.current
    val spacing: MimirSpacing get() = MimirSpacing
    val logoStage: MimirLogoStage get() = MimirLogoStage
}
