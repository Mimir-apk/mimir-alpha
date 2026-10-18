package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Mimir Alpha :core-ui — AUTHORED SCENES, v2 (2026-09-01). NOT YET WIRED.
 *
 * The nine scene painters in [MimirAtmosphere] are soft radial washes: change the hue and every
 * identity paints the same shape. The policy (2026-09-01) is one AUTHORED SCENE PER
 * IDENTITY — a built place, not a recoloured blur. These are the redesigned painters, in the same
 * shape as the ones they replace so the swap is nine call sites in `drawThemeScene` and nothing else.
 *
 * WIRING: in [MimirAtmosphere]'s `drawThemeScene`, route each
 * [MimirAtmosphereKind] to its `…SceneV2` here. Nothing else in that file changes. Once the swap is
 * ratified, the old painters and their now-unused `SceneDot` tables are deleted in the same commit
 * — two scene layers must never both be live.
 *
 * PERF , held unchanged from v1: one draw pass over gradients,
 * lines and straight-edged [Path]s; NO bitmaps; NO allocation on the hot path (every geometry table
 * is a module-level `val`, every [Path] is built from constants and constant-folded by shape, not by
 * frame); the field does not react to focus, so a D-pad sweep never re-runs this draw. [pulse] is the
 * existing ambient breath already computed once per frame by `MimirShelfBackground` — these painters
 * consume it, they never start an animation of their own.
 *
 * ONE ALLOCATION NOTE, 2026-09-02: [drawWellSceneV2]'s ring dust and star field call `drawPoints`, which
 * takes a `List<Offset>` — a boxing API, since [Offset] is a value class. `ringTierPoints`/`starPoints`
 * build that list fresh on each draw: ~760 boxed `Offset`s plus 8 small lists. The alternative is a
 * mutable size-keyed cache, which is new shared state this file has never carried, and the cost does not
 * currently justify it: **this is per INVALIDATION, not per frame.** `MimirShelfBackground` calls
 * `drawThemeSceneV2(palette, showAtmosphericClouds)` — [pulse] takes its `0f` default at the only
 * production call site, and the ambient breath that would supply one is gated to VOID and DREAD. Nothing
 * in this scene animates, so the draw node repaints on size/palette/style change, not on a clock.
 *
 * That is a live precondition, not a permanent fact. **If a pulse is ever threaded into v2, this becomes
 * ~760 allocations per frame on every shelf surface** and the cache stops being optional. Anyone wiring a
 * breath into the Well should size that first — and should prefer the pattern `MimirAtmosphere` already
 * uses for VOID/DREAD, where the heavy scene stays static and a tiny additive delta layer is the only
 * thing that repaints.
 *
 * PALETTE: every colour comes from [MimirThemePalette] / [MimirSceneMaterial]. There is no hex in
 * this file, and no scene reads another identity's material.
 *
 * FOLKVANGR AND BALDR ARE DARK. Their former light WebView palettes are retired, and
 * the source agrees — both resolve `metallicPresentation(...)`, i.e. `fieldTone = DARK`, over
 * surfaceBase #140A11 and #060E18. Their scenes below are light ON dark metal: a rim, a sheen, a
 * glint. Any bright-field reading of BLOOM or SUNLIT is a regression to the retired WebView theme.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Shared geometry — module-level so the hot path allocates nothing
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** A speck in a scene: fractional position, px radius, alpha ceiling. */
internal data class SceneSpeck(val x: Float, val y: Float, val radius: Float, val alpha: Float)

/**
 * **The Well's one viewing angle, and the only one anything concentric in it is allowed to use.**
 *
 *  Three different shapes were stacked on one centre. The rings were ellipses at 0.42; the pit
 * under them was built from `width * 0.46` by `height * 0.33`, which is a DIFFERENT aspect at every panel
 * ratio (0.54 at 640x480) and a different size; the caustic on top was a true CIRCLE, because `wash` draws
 * circles. A well mouth seen at a tilt has exactly one foreshortening — every ring, the shaft and the light
 * at the bottom share it, or the eye reads three unrelated objects at one point instead of one hole.
 *
 * Everything the Well draws now takes its radii from [wellSemiAxes] and this one ratio, so a change to the
 * angle moves the whole family together and nothing can drift off it again — the ring band's particles
 * included, since they ride the same function.
 *
 *  Raised 0.42 -> 0.47 -- the ring plane opens
 * up, as if the well were seen from a slightly higher angle. Still a knob, tuned by eye like the rest of
 * this file.
 */
private const val WellAspect = 0.47f

/**
 * One Well feature's semi-axes, in pixels, for a panel whose short side is [shortSide].
 *
 * `internal` and pure so the family property the regression report described against is ASSERTABLE rather than merely
 * intended — see `MimirWellGeometryTest`. A painter that computes its own radii inline can drift by one
 * edit; a single function cannot.
 */
internal fun wellSemiAxes(shortSide: Float, fraction: Float): Pair<Float, Float> =
    shortSide * fraction to shortSide * fraction * WellAspect

/**
 * The fractions every foreshortened Well feature is drawn at — now the ring band's two edges, and nothing
 * else, because nothing else in this scene is a tilted surface any more.
 *
 * The pit's rim (0.72) was removed because `pit()` formed a BLACK alpha-0.90 radial gradient
 * centred exactly on the sun, producing a dark halo.
 * measurement: the pool floor sat more than half the depth of the field's own brightness (19 field luma
 * against an 8 luma floor) and was still 7 luma down at 2.3 sun-radii out. A body floating in space does
 * not need a hole under it. The caustic glow (0.16) was the unwanted central diffused circle;
 * the caustic core (0.053) was already unpainted after the sun replaced it. Both were removed.
 *
 * The band's particles are generated across the continuous range between these two edges rather than at
 * discrete fractions, so pinning the edges is what there is to pin: if the band still shares the family's
 * angle at both ends, every particle between them does too, since they all derive from [wellSemiAxes].
 */
internal val WellFeatureFractions: FloatArray
    get() = floatArrayOf(WellRingBandInner, WellRingBandOuter)

/** How far the nova's radius breathes on [pulse]'s swing. One constant, tuned by eye. */
private const val NovaBreathScale = 0.14f

/**
 * The sun's outer radius, off the field's short side — the size knob.
 *
 * reduce by 30%, then by a further 20%. 0.30 -> 0.21 -> 0.168, and moved right to
 * 0.760. **Consequence worth knowing rather than discovering:**
 * the ring band is authored in absolute short-side fractions ([WellRingBandInner] 0.34 to
 * [WellRingBandOuter] 0.80), not relative to the body, so shrinking the sun widens the gap between the
 * planet and its ring from 1.13x its radius to 1.62x. The ring reads as further out. If it should keep
 * hugging the body, the two band constants scale with it — that is a separate design change and has not been
 * taken here.
 */
private const val SunRadiusFraction = 0.168f

/**
 * How much of the scene's bright light survives on a compact dock screen: about half. This dimming is used instead of moving the sun, hiding it, or shrinking it into the corner. See `drawWellSceneV2`'s `quietLight`.
 */
internal const val MimirSceneQuietLightScale = 0.5f

/** Where the bright limb sits inside that radius; everything past it is bloom. The rim knob. */
private const val SunRimStop = 0.78f

/**
 * Sun placement on a 4:3 or square field. On Retroid Pocket Nova (640x480 dp), the original
 * anchor (see `drawWellSceneV2`)
 * puts the sun at (510, 387) dp with r 81, and the last line of the widest onboarding pages -- the privacy line's
 * *"microphone or contacts."*, the action pages' *"...gathered into books."* -- runs through its upper-left rim at
 * y 361-368 dp. The requirement is: on 4:3 and square screens the orb moves LOWER and FURTHER RIGHT, below the text column, so
 * it never sits behind words; wide screens keep it exactly where it is.
 *
 * "Below the text column" fixes the numbers rather than leaving them to taste. On the Nova the body's last line ends
 * at ~380 dp (descenders) and the centred pill pair spans 415-460 dp and ends at x 447, so a whole sun cannot fit
 * under the text: its centre goes to the bottom edge and the field's bottom cuts the disc. Both insets are in the
 * sun's OWN radius from the bottom-right corner, so the visible fraction is the same on every 4:3 and square field:
 *  - [SunBelowTextRightInset] = 1.0: the disc is tangent to the right edge -- as far right as it goes while staying
 *    whole across. Nothing is cut on the right; the left edge clears the pill pair by 31 dp on the Nova.
 *  - [SunBelowTextBottomInset] = 0.15: the top edge lands 7 dp under the Nova's last line (387 dp) and the bright
 *    limb's top 10 dp above the pill row; the upper 57% of the disc shows, the rest is below the field.
 * Only placement changes; the 2026-09-03 size remains. The independent quiet-light (`quietLight`)
 * adjustment still applies on its own terms.
 *
 * The ring dust rides the same centre, as everything concentric in the Well must (see [wellSemiAxes]); the star
 * field's [wellStarBlocked] dead zone still guards the default anchor, since its table is generated once for every
 * field and moving it would restyle wide screens.
 */
internal const val SunBelowTextRightInset = 1.0f
internal const val SunBelowTextBottomInset = 0.15f

/** The Well's sun: centre and radius, in the field's own px. */
internal data class WellSunGeometry(val cx: Float, val cy: Float, val radius: Float) {
    val left: Float get() = cx - radius
    val top: Float get() = cy - radius
    val right: Float get() = cx + radius
    val bottom: Float get() = cy + radius
}

/**
 * Where the Well's sun sits on a [width] x [height] field. `internal` and pure, like [wellSemiAxes], so the geometry is
 * ASSERTABLE (`MimirSceneLightBelowTextTest`) rather than merely intended. [belowText] is the 4:3-or-square placement
 * above; false is the anchor every wide field has carried since 2026-09-03, byte for byte.
 */
internal fun wellSunGeometry(width: Float, height: Float, belowText: Boolean): WellSunGeometry {
    val short = min(width, height)
    val radius = short * SunRadiusFraction
    return if (belowText) {
        WellSunGeometry(
            cx = width - radius * SunBelowTextRightInset,
            cy = height - radius * SunBelowTextBottomInset,
            radius = radius,
        )
    } else {
        WellSunGeometry(
            cx = width * 0.760f + short * 0.05f,
            cy = height * 0.756f + short * 0.05f,
            radius = radius,
        )
    }
}

/**
 * A single soft nebula wash: fractional position, radius fraction off the short side, and which of two
 * palette haze materials it uses.
 */
private data class WellNebulaCloud(val x: Float, val y: Float, val radiusFraction: Float, val warm: Boolean)

/**
 *  The haze remains subtle and secondary to the stars. A handful of
 * large, very-low-alpha washes, not a procedural cloud layer.
 *
 * Palette-only, and no new colour math: [MimirSceneMaterial.stagePrimary] / `.stageSecondary` are ALREADY
 * baked low-alpha haze materials (10% / 7% alpha in DEFAULT) built for exactly this kind of background
 * wash — other scenes already use them this way (VOID's mist columns, Hel's floor glow). Positioned away
 * from the sun/ring disc and the star field's own dead zones (see [wellStarBlocked]) so the haze reads as
 * background atmosphere, not a fourth light competing with the sun.
 *
 * Drawn with `washDisc`, never the bare `wash(` helper — `theSurfacesAreForeshortenedAndTheLightIsNot`
 * bans that call anywhere in this painter, and `washDisc` keeps the cloud's size locked to the short side
 * like the rest of the family instead of drifting with panel aspect the way `wash`'s max(w,h) scaling would.
 */
private val WellNebulaClouds = arrayOf(
    WellNebulaCloud(0.20f, 0.24f, 0.34f, warm = false),
    WellNebulaCloud(0.70f, 0.16f, 0.30f, warm = true),
    // Left field, clear of the hero-tile dead zone (x in [0.30,0.66], y in [0.30,0.72]) -- a centre at
    // (0.40, 0.48) used to sit inside it, the same wasted-density mistake round 2's star confounds were.
    // Also balances the field: A and B both sit right-of-centre.
    WellNebulaCloud(0.12f, 0.52f, 0.28f, warm = false),
)

/** Multiplies the haze materials' own baked alpha — the subtlety knob, tuned by eye. */
private const val WellNebulaAlphaScale = 0.7f

/**
 * A single speck of ring dust: an angle around the family's own ellipse and a radius fraction within the
 * band. Position only — pixels are derived at draw time from [wellSemiAxes], so the dust rides [WellAspect]
 * like everything else the Well draws.
 */
private data class WellRingParticle(val theta: Float, val radiusFraction: Float)

/** Ring band inner edge, as a [wellSemiAxes] fraction — just outside the sun, a small Cassini-like gap. */
private const val WellRingBandInner = 0.34f

/** Ring band outer edge, as a [wellSemiAxes] fraction. */
private const val WellRingBandOuter = 0.80f

/** How many depth tiers the band is bucketed into — see [WellRingParticlesByTier]. */
private const val WellRingTierCount = 6

/** Fixed and arbitrary. Determinism is what matters (see [WellRingParticlesByTier]'s KDoc), not the value. */
private const val WellRingParticleSeed = 42L

/** Total dust specks across all six tiers. */
private const val WellRingParticleCount = 640

/**
 *  — with a reference of Jupiter near edge-on: a dense particulate band, not stroked
 * lines, thicker and brighter as it comes toward the viewer and passing visibly behind the body on the far
 * side. This table is the band; [drawWellSceneV2] supplies the occlusion by drawing tiers 0-2 before
 * `sunDisc` and tiers 3-5 after it.
 *
 * Tier is a straight interpolation off each particle's OWN angle — `(sin(theta) + 1) / 2` maps the far
 * point of the ellipse (theta with sin = -1, directly behind the body) to tier 0 and the near point
 * (sin = +1, directly in front) to tier 5. [WellRingTierAlpha] / [WellRingTierDotDp] read heavier toward
 * tier 5, making the near side brighter and thicker toward the viewer.
 *
 * Generated ONCE with a fixed seed — same shape as `MimirAtmosphere.generateStarfield()`, this file's own
 * existing precedent for a deterministic scattered table — so this is a module-level `val`, never
 * `Math.random()` or a time source. That is what keeps `NineThemeAtmosphereVisualTest`'s idle-pixel-hash
 * pin green: nothing here re-rolls per composition or per frame, and [pulse] never touches it.
 */
private val WellRingParticlesByTier: Array<List<WellRingParticle>> = run {
    val rng = Random(WellRingParticleSeed)
    val buckets = Array(WellRingTierCount) { mutableListOf<WellRingParticle>() }
    repeat(WellRingParticleCount) {
        val theta = rng.nextFloat() * (2f * PI.toFloat())
        val radiusFraction = WellRingBandInner + rng.nextFloat() * (WellRingBandOuter - WellRingBandInner)
        val nearness = (sin(theta) + 1f) / 2f
        val tier = (nearness * WellRingTierCount).toInt().coerceIn(0, WellRingTierCount - 1)
        buckets[tier] += WellRingParticle(theta, radiusFraction)
    }
    Array(WellRingTierCount) { buckets[it].toList() }
}

/** Per-tier alpha, farthest (0, behind the body) to nearest (5, in front) — dimmer receding, brighter close. */
private val WellRingTierAlpha = floatArrayOf(0.08f, 0.14f, 0.22f, 0.32f, 0.46f, 0.64f)

/** Per-tier dot diameter in dp, same farthest-to-nearest order — the band reads THICKER toward the viewer. */
private val WellRingTierDotDp = floatArrayOf(0.6f, 0.7f, 0.9f, 1.1f, 1.4f, 1.8f)

/** A single star: unit-square position and whether it belongs to the sparse bright tier. */
private data class SceneStarPoint(val x: Float, val y: Float, val bright: Boolean)

/** Fixed and arbitrary, independent of [WellRingParticleSeed] so tuning one field never reshuffles the other. */
private const val WellStarSeed = 137L

/** The star count increased from 14. */
private const val WellStarCount = 120

/** Fraction of stars in the brighter, larger tier — a few standouts, not a uniform field. */
private const val WellStarBrightFraction = 0.12f

/**
 * Dead zones the star field must avoid, in field fractions — not because stars can't render there, but
 * because a dot there is invisible (painted over) and wasted density. Coordinates from device
 * measurement: a star at (0.06, 0.10) sat under the shelf's "EDITOR'S CHOICE / <title>" header, and one at
 * (0.46, 0.35) sat behind the focused hero tile.
 */
private const val WellHeaderZoneRight = 0.40f
private const val WellHeaderZoneBottom = 0.16f
private const val WellHeroZoneLeft = 0.30f
private const val WellHeroZoneRight = 0.66f
private const val WellHeroZoneTop = 0.30f
private const val WellHeroZoneBottom = 0.72f

/** The sun/ring disc's own footprint — see `cx`/`cy` in [drawWellSceneV2]. Generous, since stars draw after
 * the disc and would otherwise sit painted on top of it. */
private const val WellDiscZoneX = 0.62f
private const val WellDiscZoneY = 0.81f
private const val WellDiscZoneRadius = 0.34f

private fun wellStarBlocked(x: Float, y: Float): Boolean {
    val header = x < WellHeaderZoneRight && y < WellHeaderZoneBottom
    val hero = x in WellHeroZoneLeft..WellHeroZoneRight && y in WellHeroZoneTop..WellHeroZoneBottom
    val dx = x - WellDiscZoneX
    val dy = y - WellDiscZoneY
    val disc = dx * dx + dy * dy < WellDiscZoneRadius * WellDiscZoneRadius
    return header || hero || disc
}

/**
 * DEFAULT's own star field — not [MoonStars], which is shared by six other scenes; restyling it would
 * silently restyle those other scenes. Deterministic for the same reason as
 * [WellRingParticlesByTier] — one fixed seed, generated once. Positions are rejection-sampled around
 * [wellStarBlocked]'s dead zones, so the count above is genuinely visible density rather than dots hidden
 * under other layers.
 */
private val WellStarField: List<SceneStarPoint> = run {
    val rng = Random(WellStarSeed)
    val points = mutableListOf<SceneStarPoint>()
    while (points.size < WellStarCount) {
        val x = rng.nextFloat()
        // Upward bias, same shape as `MimirAtmosphere.generateStarfield()`, so the field reads as sky.
        val y = rng.nextFloat() * rng.nextFloat()
        val bright = rng.nextFloat() < WellStarBrightFraction
        if (wellStarBlocked(x, y)) continue
        points += SceneStarPoint(x, y, bright)
    }
    points
}
private val WellStarFieldDim = WellStarField.filterNot { it.bright }
private val WellStarFieldBright = WellStarField.filter { it.bright }

/** Dim-tier alpha/size — the bulk of the field, kept faint. One knob per look, tuned by eye. */
private const val WellStarDimAlpha = 0.22f
private const val WellStarDimDotDp = 0.9f

/** Bright-tier alpha/size — the [WellStarBrightFraction] standouts. */
private const val WellStarBrightAlpha = 0.55f
private const val WellStarBrightDotDp = 1.4f

/**
 * A static "shining" treatment for a handful of the brightest stars — NOT the whole bright tier, which
 * stays one batched `drawPoints` call. `drawPoints` cannot draw a spike; a few authored stars getting their
 * own draw calls is cheaper than reworking the batch for all [WellStarFieldBright].
 *
 * MUST STAY STATIC: [pulse] is structurally `0f` at the only production call site (see the file
 * KDoc's allocation note) — so "shining" has to be a LOOK, not motion: a static four-point diffraction
 * spike plus a small halo, layered on top of the star's own dot from the batched call.
 * `NineThemeAtmosphereVisualTest`'s idle-pixel-hash pin still applies; nothing here reads `pulse`.
 */
private const val WellShiningStarCount = 5

/** Spike half-length and halo radius, in dp. */
private const val WellShineSpikeLengthDp = 6f
private const val WellShineHaloDp = 3.5f

/** Spike/halo alpha. */
private const val WellShineAlpha = 0.55f

/** The subset of [WellStarFieldBright] that gets the spike treatment — module-level so `.take(...)` runs
 * once, not once per draw. */
private val WellShiningStars = WellStarFieldBright.take(WellShiningStarCount)

/**
 * Where the shining stars sit, as fractions of the field (x, y). Read by the Bottom backdrop's render test to find the
 * specks [drawWellSceneV2]'s `shineStars = false` removes; nothing in production draws from it.
 */
internal fun wellShiningStarFractions(): List<Pair<Float, Float>> = WellShiningStars.map { it.x to it.y }

/**
 * MÁNI's midnight cloud bank: cluster centre x, y offset from [MoonCloudBase], half-width, alpha scale.
 *
 * Same lobe machinery as Niflheim's bank, tuned against the storm-sky reference,
 * reused here rather than re-derived — but in a different sky. Two differences that matter:
 *
 * - It sits LOW and rises, rather than straddling a horizon: this is a sky with weather in the bottom half,
 *   not a wall across the middle. Stars need the upper field to decorate.
 * - It is MOONLIT, not swallowed. Each lobe's colour is mixed toward the moon's own light by how close it
 *   is to the moon (see the painter), so the bank is pale near Máni and falls to near-black at the edges.
 *   A uniformly dark bank under a bright moon is the tell that the light is not real.
 */
private val MoonCloudClusters = arrayOf(
    floatArrayOf(-0.10f, 0.020f, 0.24f, 0.94f),
    floatArrayOf(0.09f, -0.030f, 0.22f, 1.00f),
    floatArrayOf(0.30f, 0.026f, 0.18f, 0.86f),
    floatArrayOf(0.49f, -0.042f, 0.24f, 1.00f),
    floatArrayOf(0.68f, 0.018f, 0.19f, 0.90f),
    floatArrayOf(0.87f, -0.026f, 0.23f, 0.98f),
    floatArrayOf(1.08f, 0.014f, 0.22f, 0.92f),
)

/** Where the bank's base sits in the field. Lower than Niflheim's horizon: this is weather, not a wall. */
private const val MoonCloudBase = 0.76f

/** How far the moon's aureole carries, in moon radii. Safe to widen only because the ladder is steep. */
private const val MoonScatterReach = 7.5f

private const val MoonCloudSeed = 977L
private const val MoonCloudLobesPerCluster = 20
private const val MoonCloudSpread = 0.64f
private const val MoonCloudRise = 0.92f
private const val MoonCloudThinning = 1.5f
private const val MoonCloudCoreAlpha = 0.30f

/** How far the moon's light reaches into the bank, in field widths, and how pale it can make a lobe. */
private const val MoonCloudLitReach = 0.58f
private const val MoonCloudLitMax = 0.50f

/** The rim pass: how far the lit copy is lifted toward the moon, and how strong its crescent is. */
private const val MoonRimLift = 0.010f
private const val MoonRimAlpha = 0.46f

private val MoonCloudLobes: List<CloudLobe> = cloudLobes(
    seed = MoonCloudSeed,
    clusterCount = MoonCloudClusters.size,
    lobesPerCluster = MoonCloudLobesPerCluster,
    spread = MoonCloudSpread,
    thinning = MoonCloudThinning,
    wideMin = 1.35f,
    wideRange = 1.00f,
)

/**
 * MÁNI's decorative star field.
 *
 * The scene had eight, from the shared [MoonStars] table that six other scenes also draw; eight specks is a
 * garnish, not a night sky. This is Máni's own, and [MoonStars] is left exactly as it is so nothing else
 * changes. Rejection-sampled away from the moon and its scatter, because a star inside the aureole is a
 * star nobody can see, and away from the bottom of the field where the bank is densest.
 */
private const val MoonStarSeed = 401L
private const val MoonStarCount = 110
private const val MoonStarBrightFraction = 0.14f
private const val MoonStarDimAlpha = 0.30f
private const val MoonStarDimDotDp = 0.9f
private const val MoonStarBrightAlpha = 0.70f
private const val MoonStarBrightDotDp = 1.5f

private val MoonStarField: List<SceneStarPoint> = run {
    val rng = Random(MoonStarSeed)
    val points = mutableListOf<SceneStarPoint>()
    while (points.size < MoonStarCount) {
        val x = rng.nextFloat()
        // Biased upward, so the field thickens away from the weather rather than fighting it.
        val y = rng.nextFloat() * rng.nextFloat() * 0.92f
        val bright = rng.nextFloat() < MoonStarBrightFraction
        val dx = x - 0.257f
        val dy = y - 0.38f
        if (dx * dx + dy * dy < 0.030f) continue
        points += SceneStarPoint(x, y, bright)
    }
    points
}
private val MoonStarFieldDim = MoonStarField.filterNot { it.bright }
private val MoonStarFieldBright = MoonStarField.filter { it.bright }

/** Máni's moon: size off the short side, how far its cream is mixed from cool highlight toward warm particle. */
// reduce the moon by 15%. 0.104 -> 0.0884.
private const val MoonRadiusFraction = 0.0884f
private const val MoonWarmth = 0.22f

/** Maria: how far each patch spreads past its old crater radius, and how dark it goes. */
private const val MoonMareSpread = 1.7f
private const val MoonMareDepth = 0.62f


/**
 * The horizon is a tonal boundary rather than a full-bleed drawn stroke: sky with light above,
 * land with none below. This follows the same no-line treatment as Well and Niflheim.
 * [BaldrHorizonEdge] controls how quickly
 * that boundary closes, and it is the whole knob — small enough to read as an edge, wide enough that it is
 * never a drawn stroke.
 */
private const val BaldrHorizon = 0.775f
/**
 * How wide the horizon's transition is.
 *
 * It was 0.013 — thirteen thousandths of the height, twelve pixels on the Nova. That is not an edge, it is
 * a STEP, and there were two of them stacked: this one, and `footer`'s own scrim arriving at 0.92 alpha
 * right below it. Both read as bands rather than as one field degrading into another. Widened by a factor of six so the sky hands over to the ground across a real distance.
 */
private const val BaldrHorizonEdge = 0.078f

/** How far the ground goes. Not to full shadow: an absolute floor is the same step in a darker costume. */
private const val BaldrGroundDepth = 0.72f

/**
 * **Baldr's cloud stack, on Freyr's composition.**
 *
 * The former ridge is removed, but its depth rationale still
 * stands: Baldr's sun had nothing to be occluded BY, and occlusion is where depth comes from. Cloud is now
 * the subject that supplies it instead of land.
 *
 * Freyr's structure, re-keyed for a HIGH-KEY sunrise rather than a near-black graphite hall. The ordering
 * and the rim carry over; the palette does not — Freyr sits dark masses against black, this sits pale
 * masses against a lit sky, so the tonal direction of the whole stack inverts. The planes still differ in
 * every property at once, which the method doc names as the actual depth cue.
 */
// **Shared Freyr cloud-depth ladder.** Two measurable structural corrections:
//
// 1. **Baldr had FIVE planes; Freyr has SIX.** The missing one is the DEEP plane — alpha 0.600, firm 0.92,
//    the densest and tightest in the stack. Its whole job in Freyr is to hide the individual circular
//    shapes and set the lower silhouette. Without it the pack has no bottom and the lobes stay readable as
//    lobes, breaking continuity across the cloud pack.
// 2. **The alpha spread was less than half of Freyr's.** Freyr runs 0.085 → 0.600, a 7.1x ratio between
//    its nearest and furthest planes. Baldr ran 0.115 → 0.400, a 3.5x ratio. That spread IS the depth: a
//    near plane only reads as near because it is far denser than the one behind it. Adding clouds to a
//    compressed ladder just adds more clouds at the same distance.
//
// Both ladders are Freyr's now, verbatim. What stays Baldr's is the COLOUR direction, and the sunrise
// logic is the reverse of the hall's: distant cloud CATCHES the low sun and goes pale-warm, near cloud has
// the light behind it and silhouettes DARK. That is why the near planes below go to shadow rather than
// staying pale — pale masses on a lit sky have no silhouette to give, which was the third fault.
// **The seventh plane, DERIVED rather than chosen.**
//
// The place a rung belongs is the ladder's largest discontinuity, and stepping the five MASS planes (the
// rim is a lighting pass, not a mass, so it is excluded) gives one unambiguous answer:
//
//   step               d(horizon)   alpha ratio   d(firm)
//   haze  -> distant     0.052        1.53x        0.14
//   distant -> mid       0.050        1.50x        0.24
//   mid   -> fore        0.096        2.46x        0.42     <-- largest in ALL THREE
//   fore  -> deep        0.060        1.25x        0.12
//
// That triple coincidence is the derivation. It is one missing rung, not three separate irregularities,
// and it sits exactly where "near" stops reading as near.
//
// Its values are interpolated in each ladder's own arithmetic: alpha by GEOMETRIC mean, because that
// ladder is a ratio ladder (1.53, 1.50, 1.25 elsewhere), and the rest linearly because theirs are
// difference ladders. sqrt(0.195 x 0.480) = 0.306. The result regularises the ratios to
// 1.53 / 1.50 / 1.57 / 1.57 / 1.25.
//
// The near group shifts down to open the slot without collapsing the spacing, and the horizon gaps now
// run 0.052 / 0.050 / 0.050 / 0.070 / 0.056 — increasing toward the viewer, which is what perspective
// does: planes crowd together as they recede.
private val BaldrHazePlane = floatArrayOf(0.560f, 18f, 0.090f, 0.220f, 0.085f, 0.00f)
private val BaldrDistantPlane = floatArrayOf(0.612f, 20f, 0.070f, 0.170f, 0.130f, 0.14f)
private val BaldrMidPlane = floatArrayOf(0.662f, 26f, 0.050f, 0.130f, 0.195f, 0.38f)
private val BaldrNearPlane = floatArrayOf(0.712f, 27f, 0.040f, 0.113f, 0.306f, 0.59f)
private val BaldrRimPlane = floatArrayOf(0.748f, 26f, 0.035f, 0.100f, 0.130f, 0.62f)
private val BaldrForePlane = floatArrayOf(0.782f, 28f, 0.030f, 0.095f, 0.480f, 0.80f)
private val BaldrDeepPlane = floatArrayOf(0.838f, 24f, 0.030f, 0.085f, 0.600f, 0.92f)

/** The rim's lift. Paired with [BaldrForePlane] over ONE lobe table — pinned by `MimirRimPairingTest`. */
private const val BaldrRimLift = 0.014f

// Spread, thinning and stretch are Freyr's per-plane values too -- the shaping is the whole point of the
// note, so none of it is re-authored here.
private val BaldrHazeLobes = cloudLobes(4111L, 1, 18, 0.86f, 1.05f, 1.60f, 0.90f)
private val BaldrDistantLobes = cloudLobes(4409L, 1, 20, 0.88f, 1.10f, 1.55f, 0.85f)
private val BaldrMidLobes = cloudLobes(4703L, 1, 26, 0.90f, 1.15f, 1.45f, 0.80f)
private val BaldrRimLobes = cloudLobes(5011L, 1, 28, 0.92f, 1.20f, 1.35f, 0.75f)
private val BaldrNearLobes = cloudLobes(5807L, 1, 27, 0.91f, 1.175f, 1.40f, 0.775f)
private val BaldrDeepLobes = cloudLobes(5309L, 1, 24, 0.92f, 1.25f, 1.30f, 0.70f)

/** Where the sunrise sits on that horizon, and how big it is off the short side. */
private const val BaldrSunX = 0.62f
private const val BaldrSunRadius = 0.070f

/** How far the sun's warmth reaches across the field when tinting cloud, in field widths. */
private const val BaldrSunReach = 0.62f


/**
 * Baldr's own stars, biased to the upper field and kept clear of the caption lane.
 *
 * It was drawing [MoonStars] -- Máni's table, shared with five other scenes. Retuning Máni's sky would have
 * moved Baldr's silently and no gate would have failed, which is the same coupling hazard the frozen
 * `cloudLobes` draw order is already guarded against.
 */
private val BaldrStars = arrayOf(
    SceneSpeck(0.07f, 0.09f, 1.0f, 0.50f), SceneSpeck(0.18f, 0.17f, 0.7f, 0.32f),
    SceneSpeck(0.27f, 0.06f, 0.9f, 0.44f), SceneSpeck(0.39f, 0.14f, 0.6f, 0.28f),
    SceneSpeck(0.47f, 0.05f, 1.0f, 0.46f), SceneSpeck(0.58f, 0.12f, 0.7f, 0.30f),
    SceneSpeck(0.66f, 0.07f, 0.8f, 0.38f), SceneSpeck(0.79f, 0.15f, 0.6f, 0.26f),
    SceneSpeck(0.88f, 0.08f, 0.9f, 0.42f), SceneSpeck(0.95f, 0.19f, 0.7f, 0.30f),
    SceneSpeck(0.12f, 0.24f, 0.6f, 0.24f), SceneSpeck(0.71f, 0.22f, 0.6f, 0.26f),
)




/**
 * **FREYR — the gold cloudscape.** Implemented 2026-09-02.
 *
 * The visual target: Norse noir x cyberpunk lattice x warm gold x PS5-like atmospheric restraint. Almost-black
 * graphite, a quiet centre, storm cloud near the horizon, and gold light originating BEHIND the clouds so
 * that the clouds occlude it, providing most of the depth.
 *
 * Implementation constraints:
 *
 * 1. **No `Modifier.blur`, no per-layer composables.** This scene is a `DrawScope` painter inside
 *    `MimirShelfBackground`'s `drawBehind`; there is no composable seam to hang blur modifiers on without
 *    restructuring the atmosphere host for all nine themes. Softness comes instead from `abyssLobe`'s
 *    `firm` parameter, which is the same control expressed as a gradient: `firm = 0` spreads a lobe's
 *    falloff over its whole radius (what a large blur radius does to a blob) and `firm = 1` holds it solid
 *    to the rim. The design blur ladder 52 → 35 → 22 → 14 → 8 dp maps onto the firm ladder below. It
 *    avoids the GPU cost of four simultaneous handheld blur passes.
 * 2. **Palette, not hex.** Literal reference colours (0xFFFFB72E, 0xFF12100B and the rest) would be
 *    the first hex in this file, against a standing project invariant. Every tone below is mixed from
 *    Freyr's own `accent` (0xFFE2BD70), `highlight`, `particle`, `shadow` and its cool `stageSecondary`,
 *    which are close to the reference values. Exact colour changes belong in Freyr's PALETTE,
 *    not literals in a painter.
 * 3. Static: the scene has no clock (`pulse` is 0 at its only production call site). Layers could
 *    support parallax offsets if that design changes.
 *    composed so a parallax offset per plane is a one-line addition if that ever reverses.
 *
 * The atmospheric stack comes first: black, gold backlight, haze, cloud occlusion, gold rim and
 * foreground depth, then terrain silhouette and horizon scatter. Cyber-lattice and Norse geometry
 * are not part of this painter yet.
 */
private const val FreyrLightX = 0.70f
private const val FreyrLightY = 0.63f

/** Each plane: horizon fraction, cloud count, min and max radius as short-side fractions, alpha, firmness. */
private val FreyrHazePlane = floatArrayOf(0.600f, 18f, 0.09f, 0.22f, 0.085f, 0.00f)
private val FreyrDistantPlane = floatArrayOf(0.645f, 20f, 0.07f, 0.17f, 0.130f, 0.14f)
private val FreyrMidPlane = floatArrayOf(0.685f, 26f, 0.05f, 0.13f, 0.195f, 0.38f)
private val FreyrRimPlane = floatArrayOf(0.730f, 26f, 0.035f, 0.10f, 0.130f, 0.62f)
private val FreyrForePlane = floatArrayOf(0.775f, 28f, 0.030f, 0.095f, 0.480f, 0.80f)
private val FreyrDeepPlane = floatArrayOf(0.835f, 24f, 0.030f, 0.085f, 0.600f, 0.92f)

/** How far the gold rim plane is lifted above the dark plane that shares its seed. The rim IS that offset. */
private const val FreyrRimLift = 0.016f

/**
 * Cloud lobes per plane, generated once at class load. Seeds are fixed and the rim/foreground pair SHARE
 * one — the shared-shape technique: identical geometry, drawn twice, the upper copy gold and the lower
 * copy dark, so the offset leaves a lit edge exposed. Drawing an outline instead is what looks artificial.
 */
private val FreyrHazeLobes = cloudLobes(1301L, 1, 18, 0.86f, 1.05f, 1.60f, 0.90f)
private val FreyrDistantLobes = cloudLobes(1601L, 1, 20, 0.88f, 1.10f, 1.55f, 0.85f)
private val FreyrMidLobes = cloudLobes(1901L, 1, 26, 0.90f, 1.15f, 1.45f, 0.80f)
private val FreyrRimLobes = cloudLobes(2203L, 1, 26, 0.92f, 1.20f, 1.35f, 0.75f)
private val FreyrDeepLobes = cloudLobes(2609L, 1, 24, 0.92f, 1.25f, 1.30f, 0.70f)

/**
 * **The cyber-lattice, and where it is allowed to be.**
 *
 * Keep visual detail at the periphery and calm behind information. Content owns roughly
 * x 0.13–0.87, so lattice stays outside [FreyrLatticeKeepOut] on each side. Variation depends on
 * position as well as depth.
 *
 * Trace alphas stay below 0.10; only a few nodes reach 0.18. This secondary layer must not compete
 * with the atmosphere and become generic orange cyberpunk.
 */
private const val FreyrLatticeKeepOut = 0.30f

/** Circuit traces: each run is x,y pairs in field fractions, drawn as an orthogonal-ish polyline. */
private val FreyrTraces = arrayOf(
    floatArrayOf(0.015f, 0.10f, 0.015f, 0.27f, 0.09f, 0.34f, 0.09f, 0.46f),
    floatArrayOf(0.055f, 0.06f, 0.055f, 0.19f, 0.14f, 0.19f, 0.19f, 0.24f, 0.19f, 0.38f),
    floatArrayOf(0.02f, 0.52f, 0.11f, 0.52f, 0.16f, 0.57f, 0.16f, 0.66f),
    floatArrayOf(0.985f, 0.09f, 0.985f, 0.30f, 0.90f, 0.37f, 0.90f, 0.51f),
    floatArrayOf(0.94f, 0.05f, 0.94f, 0.16f, 0.86f, 0.16f, 0.81f, 0.21f, 0.81f, 0.35f),
    floatArrayOf(0.97f, 0.56f, 0.88f, 0.56f, 0.84f, 0.61f, 0.84f, 0.69f),
)

/** Nodes: x, y, radius fraction of the short side, alpha. A few are bright; most are barely there. */
private val FreyrNodes = arrayOf(
    floatArrayOf(0.09f, 0.34f, 0.0040f, 0.20f), floatArrayOf(0.19f, 0.24f, 0.0055f, 0.34f),
    floatArrayOf(0.055f, 0.19f, 0.0035f, 0.14f), floatArrayOf(0.16f, 0.57f, 0.0045f, 0.22f),
    floatArrayOf(0.11f, 0.52f, 0.0030f, 0.12f), floatArrayOf(0.90f, 0.37f, 0.0040f, 0.18f),
    floatArrayOf(0.81f, 0.21f, 0.0055f, 0.32f), floatArrayOf(0.86f, 0.16f, 0.0030f, 0.13f),
    floatArrayOf(0.84f, 0.61f, 0.0045f, 0.24f), floatArrayOf(0.88f, 0.56f, 0.0030f, 0.11f),
)

/** Hexagonal fragments: centre x, y, radius fraction, alpha, and how many of the six sides to draw. */
private val FreyrHexes = arrayOf(
    floatArrayOf(0.075f, 0.42f, 0.052f, 0.085f, 4f),
    floatArrayOf(0.145f, 0.13f, 0.038f, 0.070f, 3f),
    floatArrayOf(0.925f, 0.44f, 0.048f, 0.080f, 4f),
    floatArrayOf(0.855f, 0.11f, 0.034f, 0.065f, 3f),
)

/**
 * **Norse geometry, as part of the technological system rather than as ornament.**
 *
 * Ancient geometry reads as machine topology: no helmets, longships, large readable runes or
 * fantasy ornament. These are
 * angular branch structures and concentric sigils that read as topology, and they sit inside the lattice's
 * own keep-out so the two languages occupy one periphery rather than two.
 *
 * Each branch: root x, root y, then successive x,y in field fractions. Angular only — no curves, because a
 * curve here reads as decoration and a straight run reads as structure.
 */
private val FreyrBranches = arrayOf(
    floatArrayOf(0.035f, 0.74f, 0.035f, 0.63f, 0.10f, 0.57f, 0.10f, 0.48f),
    floatArrayOf(0.035f, 0.63f, -0.02f, 0.57f),
    floatArrayOf(0.10f, 0.57f, 0.165f, 0.51f),
    floatArrayOf(0.965f, 0.72f, 0.965f, 0.60f, 0.90f, 0.55f, 0.90f, 0.46f),
    floatArrayOf(0.965f, 0.60f, 1.02f, 0.55f),
)

/** Concentric sigils: centre x, y, outer radius fraction, ring count, alpha. */
private val FreyrSigils = arrayOf(
    floatArrayOf(0.115f, 0.235f, 0.042f, 3f, 0.075f),
    floatArrayOf(0.885f, 0.255f, 0.036f, 3f, 0.065f),
)

/** Terrain silhouette: alternating x and y in field fractions, left to right along the ridge. */
private val FreyrRidge = floatArrayOf(
    -0.05f, 0.905f, 0.08f, 0.879f, 0.17f, 0.897f, 0.27f, 0.866f, 0.35f, 0.888f,
    0.46f, 0.858f, 0.55f, 0.884f, 0.66f, 0.871f, 0.74f, 0.893f, 0.85f, 0.862f,
    0.93f, 0.886f, 1.05f, 0.874f,
)

/** Gold points along the horizon. Few, mostly faint -- they are what make the field read as enormous. */
private val FreyrEmbers = arrayOf(
    floatArrayOf(0.12f, 0.842f, 0.30f), floatArrayOf(0.23f, 0.836f, 0.16f),
    floatArrayOf(0.34f, 0.849f, 0.22f), floatArrayOf(0.44f, 0.833f, 0.12f),
    floatArrayOf(0.57f, 0.845f, 0.34f), floatArrayOf(0.63f, 0.831f, 0.14f),
    floatArrayOf(0.71f, 0.840f, 0.26f), floatArrayOf(0.79f, 0.828f, 0.11f),
    floatArrayOf(0.88f, 0.847f, 0.19f), floatArrayOf(0.96f, 0.835f, 0.13f),
)

/** The hall's fixed frame: horizon, vanishing point, and the door's seat OFF that vanishing point. */
private const val HallHorizon = 0.46f
private const val HallVanishX = 0.66f
/**
 * The door sits ON the vanishing point.
 *
 * The off-axis experiment is reverted, and the geometry follows it back: an opening spanning x in [a, b]
 * shows its left reveal where `a < 0` and its right where `b > 0`, both measured from the view axis. Off
 * to one side only one test passes and you see a single reveal; CENTRED ON THE AXIS BOTH PASS, so the
 * opening is a symmetric recess with both jambs turned inward. That is not a smaller version of the
 * off-axis case — it is a different construction, and drawing one reveal here would read as a mistake.
 */
private const val HallDoorX = HallVanishX
private const val HallDoorHalfW = 0.052f
private const val HallDoorH = 0.30f

/** How far the reveal's inner edge runs toward the VP. This is the wall's thickness, in perspective. */
private const val HallRevealInset = 0.16f

/**
 * **The metal ramp.** Flat quads with one flat gradient each made the frame read as a flat icon.
 *
 * Metal does not read from hue or from a single ramp; it reads from a VALUE STRUCTURE that a painted
 * surface never has. Across any lit metal edge, in order: a dark outer terminator, the body tone, a narrow
 * hot specular, a cooler reflected band where it picks up its surroundings, then a dark inner terminator.
 * Five bands, and the narrowness of the specular relative to the rest is most of what says "metal" rather
 * than "plastic". These are the stop positions across a jamb's width, and the tones are mixed at the draw
 * from this theme's own gold, its cool counter and its shadow.
 */
private val HallMetalStops = floatArrayOf(0.00f, 0.22f, 0.46f, 0.54f, 0.78f, 1.00f)

/**
 * The jamb's specular, broken into segments.  stave-church door surrounds are carved
 * interlace, not polished metal, so light catches raised relief in interrupted lengths rather than one
 * continuous line. Each pair is a start and end along the jamb's lit face.
 */
private val HallInterlace = arrayOf(
    floatArrayOf(0.00f, 0.22f),
    floatArrayOf(0.30f, 0.55f),
    floatArrayOf(0.63f, 0.86f),
)

/**
 * The door's beams: inner and outer edge as multiples of the door's half-width, and an alpha scale.
 *
 * Authored as a few decisive wedges rather than many soft washes -- the beam's hard edge is the
 * door frame OCCLUDING the light, which is a boundary doing real work, so the ring-seam rule that governs
 * every other glow in this file does not apply to it.
 */
private val HallBeams = arrayOf(
    floatArrayOf(-0.95f, -0.30f, 0.90f),
    floatArrayOf(-0.20f, 0.35f, 1.00f),
    floatArrayOf(0.45f, 0.95f, 0.82f),
)

/** How far down the room the beams carry, and how much they flare. Full depth reads as a lit floor. */
private const val HallBeamReach = 0.38f
private const val HallBeamFlare = 3.1f

/**
 * Rafter feet along the TOP of the Golden Hall, and floor rails along the BOTTOM, both converging on the
 * vanishing point. **Evenly spaced, and that is a derivation rather than a preference.**
 *
 * Rails parallel to the view axis project so that their intercepts with any horizontal line are evenly
 * spaced IFF they are evenly spaced in world x. The old table ran 0.37 / 0.32 / 0.28 / 0.26 / 0.24 / 0.26
 * / 0.34 -- a 1.54x spread -- so the floor was not a regular grid and read hand-scattered rather than as
 * boards.
 */
private val HallRafters = floatArrayOf(-0.30f, -0.07f, 0.16f, 0.39f, 0.62f, 0.85f, 1.08f, 1.31f)
private val HallFloorRails = floatArrayOf(-0.55f, -0.25f, 0.04f, 0.34f, 0.63f, 0.93f, 1.22f, 1.52f)

/**
 * Floor courses as **1/n**, which is the actual projection of evenly spaced boards.
 *
 * A ground point at depth `d` maps to `y = horizon + f*hc/d`, so with `t = (y-horizon)/(H-horizon)`,
 * `t` is proportional to `1/d`. Evenly spaced depths therefore land at `t_n = 1/n`.
 *
 * The old code used `t = course * course` over an authored table. Back-solving the depths that implies
 * gives gaps between adjacent courses of **79.3, 12.9, 4.0, 1.7, 0.7** -- an eighty-fold spread where a
 * floor has a constant one. Squaring compresses toward the VIEWER; perspective compresses toward the
 * HORIZON. Right instinct, wrong exponent, and it survived visual review for months because nothing about
 * it looks wrong until you invert the projection.
 */
private val HallFloorCourses = floatArrayOf(1f, 1f / 2f, 1f / 3f, 1f / 4f, 1f / 5f, 1f / 6f)

/**
 * **Where the BACK WALL stands, and why a hall needs three horizontal lines rather than one.**
 *
 *  Both are the same fault, and it is a real one:
 *
 *   eye level / horizon   the line the VP lies on — a line ON THE WALL, not on the floor
 *   back-wall base        the wall/floor junction, BELOW eye level
 *   bottom edge           the near edge of the floor
 *
 * The painter drew one. Courses ran `t = 1 … 1/8`, i.e. toward `t → 0`, which IS the horizon — and a floor
 * that reaches the horizon is a floor of INFINITE depth, so there was no back wall for the door to stand
 * in. Wall and floor were then both drawn to the same y, leaving the junction nothing to be a junction
 * between, which is exactly why no horizon read.
 *
 * With the wall at this depth the floor stops here, and the rails stop with it: at `t_wall` they are still
 * 424 px apart on a 1280 panel, not a point. **Rails must not keep converging past the wall, because past
 * the wall there is no floor.** That was the cardinal error.
 */
private const val HallWallT = 0.16f

/**
 * Reservoirs: x, y, radius as a width fraction, intensity. These sit UNDER the crust, never on it — the
 * magma reservoirs rather than arbitrary glowing vertices, and the reason the fractures above them
 * read as gaps with light behind rather than as lines with a glow painted on.
 *
 * They are placed off the junctions in the margins, so none of them lands in the content lane.
 */
private val SurtrReservoirs = arrayOf(
    floatArrayOf(0.055f, 0.300f, 0.155f, 0.92f),
    floatArrayOf(0.070f, 0.700f, 0.130f, 0.70f),
    floatArrayOf(0.945f, 0.330f, 0.150f, 0.88f),
    floatArrayOf(0.930f, 0.740f, 0.125f, 0.66f),
    floatArrayOf(0.500f, 0.075f, 0.165f, 0.52f),
)

/**
 * Ember particles: x, y, radius fraction, alpha. Few, faint, and none in the caption lane.
 */
private val SurtrEmbers = arrayOf(
    floatArrayOf(0.86f, 0.185f, 0.0026f, 0.36f), floatArrayOf(0.93f, 0.320f, 0.0020f, 0.22f),
    floatArrayOf(0.74f, 0.120f, 0.0022f, 0.30f), floatArrayOf(0.10f, 0.180f, 0.0024f, 0.32f),
    floatArrayOf(0.045f, 0.300f, 0.0018f, 0.20f), floatArrayOf(0.235f, 0.135f, 0.0022f, 0.26f),
    floatArrayOf(0.905f, 0.860f, 0.0022f, 0.28f), floatArrayOf(0.075f, 0.880f, 0.0020f, 0.22f),
)

/**
 * **Restrained treatment.**  Three components:
 *
 *   broad thermal bleed   alpha 0.04–0.08   width  8–14 dp
 *   molten body           alpha 0.10–0.18   width   2–3 dp
 *   rare hot core         alpha 0.18–0.28   width 0.6–1.1 dp
 *
 * The previous pass ran the body near-opaque through a five-stop gradient and put a white-hot core along
 * every millimetre of every path. Both are gone. The core is now the exception rather than the finish:
 * see [SurtrHotSegments].
 *
 * Palette also shifts down, so most of the lattice uses DeepHeat/MagmaRed rather than
 * bright orange. In this theme's tokens that is `lerp(accent, shadow, …)` and `accent`; `accentBright`
 * appears only in the hot segments and `highlight` only in their cores.
 */
private val SurtrBleedWidth = 11f
private val SurtrBodyWidth = 2.4f
private val SurtrCoreWidth = 0.9f
private val SurtrBleedAlpha = 0.055f
private val SurtrBodyAlpha = 0.145f
private val SurtrCoreAlpha = 0.24f

/**
 * Which segments carry a hot core, as (path, segment) pairs — 6 of 29, so roughly a fifth.
 *
 * Hot cores occur only in selected segments so dormant and bright sections contrast. A core
 * everywhere reads as a neon tube; localized cores read as a flowing network.
 */
private val SurtrHotSegments = arrayOf(
    intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3),
    intArrayOf(3, 1), intArrayOf(4, 2), intArrayOf(5, 1),
)

private val SurtrFractures = arrayOf(
    floatArrayOf(-0.060f, 0.130f, 0.180f, 0.130f, 0.330f, 0.245f, 0.690f, 0.245f, 0.845f, 0.126f, 1.060f, 0.126f),
    floatArrayOf(-0.060f, 0.760f, 0.250f, 0.760f, 0.430f, 0.621f, 0.760f, 0.621f, 0.930f, 0.752f, 1.060f, 0.752f),
    floatArrayOf(0.120f, -0.050f, 0.120f, 0.180f, 0.290f, 0.311f, 0.290f, 0.560f, 0.145f, 0.672f, 0.145f, 1.050f),
    floatArrayOf(0.880f, -0.050f, 0.880f, 0.210f, 0.700f, 0.349f, 0.700f, 0.585f, 0.865f, 0.712f, 0.865f, 1.050f),
    floatArrayOf(-0.060f, 0.430f, 0.215f, 0.430f, 0.400f, 0.572f, 0.620f, 0.572f, 0.810f, 0.426f, 1.060f, 0.426f),
    floatArrayOf(0.330f, 0.245f, 0.430f, 0.322f, 0.430f, 0.640f),
    floatArrayOf(0.690f, 0.245f, 0.600f, 0.365f, 0.600f, 0.545f),
)


/** Forge seams: each run is x0,y0,x1,y1,x2,y2 in field fractions — an orthogonal trace that forks. */
private val ForgeSeams = arrayOf(
    floatArrayOf(0.00f, 0.34f, 0.22f, 0.34f, 0.32f, 0.46f),
    floatArrayOf(0.32f, 0.46f, 0.58f, 0.46f, 0.68f, 0.30f),
    floatArrayOf(0.68f, 0.30f, 0.88f, 0.30f, 1.00f, 0.40f),
    floatArrayOf(0.06f, 0.62f, 0.30f, 0.62f, 0.44f, 0.74f),
    floatArrayOf(0.56f, 0.80f, 0.74f, 0.80f, 0.86f, 0.66f),
)

/**
 * Yggdrasil's stand: x, half-width at the base, top y, depth 0..1 (1 = nearest and most lit).
 */
/**
 * YGGDRASIL's root network — long organic runs in VIEWPORT space, as x,y point chains.
 *
 *  The painter
 * this replaced drew six strictly vertical full-height trunks, and several of them passed behind the
 * SELECTED tile and carried on down into the dock row — making the selected tile appear connected to the dock controls despite no intended UI relationship.
 *
 * Every run starts and ends OUTSIDE the frame (−0.10 / 1.10), so no root has a visible beginning: the
 * network continues past the screen, which is the whole claim the scene is making.
 */
private val YggRoots = arrayOf(
    floatArrayOf(-0.10f, 0.30f, 0.08f, 0.24f, 0.26f, 0.27f, 0.44f, 0.19f, 0.62f, 0.22f, 0.80f, 0.15f, 1.10f, 0.19f),
    floatArrayOf(-0.10f, 0.74f, 0.14f, 0.70f, 0.30f, 0.79f, 0.50f, 0.76f, 0.68f, 0.84f, 0.88f, 0.79f, 1.10f, 0.86f),
    floatArrayOf(-0.08f, 0.10f, 0.06f, 0.32f, 0.10f, 0.55f, 0.18f, 0.78f, 0.22f, 1.06f),
    floatArrayOf(1.08f, 0.08f, 0.94f, 0.30f, 0.90f, 0.52f, 0.82f, 0.76f, 0.78f, 1.06f),
    floatArrayOf(-0.10f, 0.50f, 0.16f, 0.46f, 0.36f, 0.53f, 0.56f, 0.47f, 0.74f, 0.54f, 1.10f, 0.49f),
    floatArrayOf(0.10f, 0.55f, 0.26f, 0.63f, 0.40f, 0.72f, 0.58f, 0.77f),
    floatArrayOf(0.90f, 0.52f, 0.74f, 0.60f, 0.60f, 0.66f, 0.46f, 0.70f),
    floatArrayOf(-0.10f, 0.94f, 0.20f, 0.90f, 0.44f, 0.96f, 0.66f, 0.91f, 0.92f, 0.97f, 1.10f, 0.93f),
    floatArrayOf(0.02f, 0.04f, 0.14f, 0.14f, 0.24f, 0.12f, 0.34f, 0.20f, 0.40f, 0.34f),
)

/**
 * Where sap is running, as `root, centre-u, span` — u is a position ALONG the root in segment units.
 *
 * Roughly 70-80% of a root remains barely visible bark/olive; warm sap occupies only a small portion.
 * A span is a soft bell, not a lit segment: it rises, peaks and falls inside the
 * stroke (`───░▒▓██▓▒░───`). Six spans across nine roots, so most of the network carries none at all.
 */
private val YggSap = arrayOf(
    floatArrayOf(0f, 3.15f, 0.85f),
    floatArrayOf(1f, 2.40f, 0.70f),
    floatArrayOf(2f, 1.30f, 0.60f),
    floatArrayOf(4f, 2.55f, 0.75f),
    floatArrayOf(5f, 1.15f, 0.55f),
    floatArrayOf(7f, 3.30f, 0.65f),
)

/**
 * Branches occasionally split a root into two thinner paths; one may fade into darkness and
 * a few reconnect.
 *
 * Each begins ON a parent root's own point, so the split is a real fork rather than a line that happens
 * to start nearby. The last column says whether it fades out (1) or lands on another root and rejoins
 * (0). These restructure existing paths without adding new ones, stopping the
 * existing ones reading as single ribbons.
 */
/**
 * Pollen —
 *
 * Star-like means the DISTRIBUTION, not the shape: a real star field is mostly faint with a handful of
 * bright ones, so brightness is raised to a power here rather than spread evenly. An even scatter reads
 * as noise; this reads as depth. Slightly denser toward the top, where the canopy would be, and drawn a
 * fraction beyond every edge so the field has no border.
 *
 * Generated ONCE at class load from a fixed seed, like every other scattered table in this file — these
 * scenes are pinned pixel-static at idle and a per-composition RNG would shimmer.
 */
private val YggPollen: Array<SceneSpeck> = run {
    val rng = Random(YggPollenSeed)
    Array(78) {
        val bright = rng.nextFloat()
        SceneSpeck(
            x = rng.nextFloat() * 1.06f - 0.03f,
            y = rng.nextFloat().pow(0.84f) * 1.04f - 0.02f,
            radius = 0.5f + bright.pow(2.6f) * 1.7f,
            alpha = 0.14f + bright.pow(1.8f) * 0.66f,
        )
    }
}

private const val YggPollenSeed = 7731L

private val YggBranches = arrayOf(
    floatArrayOf(1f, 0.26f, 0.79f, 0.33f, 0.88f, 0.37f, 0.99f),
    floatArrayOf(1f, 0.06f, 0.32f, 0.02f, 0.44f, -0.04f, 0.52f),
    floatArrayOf(1f, 0.90f, 0.30f, 0.97f, 0.41f, 1.06f, 0.47f),
    floatArrayOf(0f, 0.44f, 0.19f, 0.50f, 0.31f, 0.56f, 0.47f),
    floatArrayOf(0f, 0.30f, 0.79f, 0.36f, 0.66f, 0.36f, 0.53f),
)

/**
 * Nine Worlds appear as nine root junctions without icons. Only 3–5 should read immediately,
 * with the others disappearing into fog or shadow.
 *
 * x, y, radius, presence. The presence column is the whole point: three read at once, three are faint,
 * three are barely there. Nine equal knots would be a diagram of nine things, which is the failure this
 * column exists to prevent.
 */
private val YggJunctions = arrayOf(
    floatArrayOf(0.100f, 0.550f, 0.0130f, 1.00f),
    floatArrayOf(0.900f, 0.520f, 0.0115f, 0.92f),
    floatArrayOf(0.585f, 0.772f, 0.0125f, 0.84f),
    floatArrayOf(0.260f, 0.270f, 0.0090f, 0.46f),
    floatArrayOf(0.740f, 0.540f, 0.0085f, 0.40f),
    floatArrayOf(0.360f, 0.530f, 0.0080f, 0.34f),
    floatArrayOf(0.200f, 0.900f, 0.0070f, 0.20f),
    floatArrayOf(0.460f, 0.700f, 0.0065f, 0.16f),
    floatArrayOf(0.660f, 0.910f, 0.0060f, 0.13f),
)

/**
 * The trunk axis is enormous but nearly invisible behind the shelf, so its tree shape emerges
 * gradually rather than reading as an immediate tree silhouette.
 *
 * Five runs: one central rise and two pairs splaying up and down from it. Authored as point chains
 * like the roots so it curves rather than drawing a Y.
 */
private val YggAxis = arrayOf(
    floatArrayOf(0.500f, 1.10f, 0.500f, 0.72f, 0.497f, 0.40f, 0.494f, -0.10f),
    floatArrayOf(0.497f, 0.44f, 0.400f, 0.28f, 0.300f, 0.10f, 0.250f, -0.10f),
    floatArrayOf(0.497f, 0.44f, 0.600f, 0.28f, 0.706f, 0.10f, 0.760f, -0.10f),
    floatArrayOf(0.500f, 0.74f, 0.370f, 0.86f, 0.250f, 1.02f, 0.190f, 1.14f),
    floatArrayOf(0.500f, 0.74f, 0.640f, 0.86f, 0.770f, 1.02f, 0.840f, 1.14f),
)

/** Surtr's traces, each an ORTHOGONAL run of x,y points — along, turn, along. */
private val ForgeTraces = arrayOf(
    floatArrayOf(0.00f, 0.26f, 0.24f, 0.26f, 0.24f, 0.46f, 0.52f, 0.46f),
    floatArrayOf(0.52f, 0.46f, 0.52f, 0.74f, 0.70f, 0.74f),
    floatArrayOf(1.00f, 0.34f, 0.78f, 0.34f, 0.78f, 0.58f, 0.55f, 0.58f),
    floatArrayOf(0.00f, 0.66f, 0.18f, 0.66f, 0.18f, 0.86f, 0.40f, 0.86f),
    floatArrayOf(0.70f, 0.74f, 0.88f, 0.74f, 0.88f, 0.92f, 1.00f, 0.92f),
)

/** Where a forge trace turns or terminates, it burns brightest. */
private val ForgeNodes = arrayOf(
    floatArrayOf(0.24f, 0.46f), floatArrayOf(0.52f, 0.46f), floatArrayOf(0.78f, 0.58f),
    floatArrayOf(0.18f, 0.86f), floatArrayOf(0.88f, 0.74f),
)

// Shallower mountain slopes: The peaks were swinging ~0.16 of the
// field between neighbours, which reads as a saw blade rather than a range. Halved, and the two runs
// now sit lower so the dark mass below them is deeper.
// **TWO RANGES THAT COLLIDE — ** A single run edge-to-edge reads as one wall behind the content.
// Two ranges each RISING toward the centre read as a place with a pass in it — and where they overlap
// mid-field the near one occludes the far one, which is what sells a collision rather than two shapes
// that merely touch.
//
// Each table ends by dropping to the bottom edge (y = 1.00) just past the meeting point, so `ridge`'s
// closing run along the bottom stays invisible instead of cutting a diagonal across the field.

/** Craters: dx, dy (in radii, from the moon's centre), radius fraction, alpha. */
private val MoonCraters = arrayOf(
    floatArrayOf(-0.30f, -0.26f, 0.26f, 0.32f), floatArrayOf(0.26f, 0.08f, 0.19f, 0.26f),
    floatArrayOf(-0.08f, 0.44f, 0.13f, 0.28f), floatArrayOf(0.44f, -0.36f, 0.10f, 0.22f),
    floatArrayOf(-0.54f, 0.16f, 0.09f, 0.20f), floatArrayOf(0.10f, -0.52f, 0.08f, 0.24f),
    floatArrayOf(0.52f, 0.38f, 0.07f, 0.18f), floatArrayOf(-0.34f, 0.62f, 0.06f, 0.16f),
)

private val MoonStars = arrayOf(
    SceneSpeck(0.10f, 0.14f, 1.3f, 0.62f), SceneSpeck(0.26f, 0.09f, 1.0f, 0.52f),
    SceneSpeck(0.44f, 0.17f, 1.4f, 0.56f), SceneSpeck(0.62f, 0.08f, 1.0f, 0.46f),
    SceneSpeck(0.78f, 0.20f, 1.2f, 0.50f), SceneSpeck(0.92f, 0.11f, 1.1f, 0.44f),
    SceneSpeck(0.34f, 0.26f, 0.9f, 0.36f), SceneSpeck(0.70f, 0.30f, 0.9f, 0.32f),
)

/**
 * **HEL — blood suspended in darkness inside a decaying cathedral.** The crimson field needs irregularity and depth;
 * clean, uniform electronic red would flatten the gothic atmosphere.
 *
 * The three corrections, and each is structural rather than a tint change:
 *
 * 1. **No flat wash.** One smooth bloom is what reads as electronic. Crimson arrives instead as layered
 *    irregular fog plus a few uneven intensity islands, so the field runs dead black → dried crimson →
 *    saturated blood → brief ember → black rather than as one gradient.
 * 2. **Verticality.** Freyr is open, Surtr is horizontal; Hel is a cathedral and must be vertical. Arches,
 *    lancets and columns at 2–6% — enough geometry for the eye to infer architecture, never literal.
 * 3. **Vein-gothic, not Nordic.** Surtr's angular lattice would make these two themes one theme. Hel's
 *    paths are narrow, curved, and meet at points: blood vessels crossed with tracery.
 *
 * Palette, and Hel needed no invention either: `shadow` 0xFF020102 against the requested HelVoid
 * 0xFF030102, `accent` the blood red, `accentBright` the ember, and — the one that matters most — `particle`
 * 0xFFC4B6BA IS the aged ivory the direction asks for at 1–2% of the palette. Bone is what makes crimson
 * read as gothic rather than as red.
 *
 * **The Surtr lesson carries over intact:** these paths are viewport-space and tile-blind. The keep-out region is what made that lattice frame the cards, so nothing here knows where a tile is.
 */
private val HelVeins = arrayOf(
    floatArrayOf(-0.06f, 0.215f, 0.180f, 0.215f, 0.320f, 0.330f, 0.480f, 0.330f, 0.620f, 0.230f, 1.06f, 0.230f),
    floatArrayOf(-0.06f, 0.720f, 0.230f, 0.720f, 0.400f, 0.600f, 0.660f, 0.600f, 0.840f, 0.715f, 1.06f, 0.715f),
    floatArrayOf(0.115f, -0.05f, 0.115f, 0.250f, 0.245f, 0.395f, 0.245f, 0.640f, 0.130f, 0.780f, 0.130f, 1.05f),
    floatArrayOf(0.885f, -0.05f, 0.885f, 0.265f, 0.755f, 0.400f, 0.755f, 0.635f, 0.870f, 0.775f, 0.870f, 1.05f),
    floatArrayOf(0.620f, 0.230f, 0.660f, 0.400f, 0.660f, 0.600f),
)

/** Where a vein swells — (path, segment). Blood is not uniform; a vessel thickens and thins. */
private val HelVeinSwell = arrayOf(
    intArrayOf(0, 2), intArrayOf(1, 1), intArrayOf(2, 3), intArrayOf(3, 2), intArrayOf(4, 1),
)

/** Elongated diamonds at the vein junctions — the gothic half of the vocabulary. */
private val HelNodes = arrayOf(
    floatArrayOf(0.320f, 0.330f, 0.017f), floatArrayOf(0.620f, 0.230f, 0.021f),
    floatArrayOf(0.400f, 0.600f, 0.019f), floatArrayOf(0.660f, 0.600f, 0.016f),
    floatArrayOf(0.755f, 0.400f, 0.014f),
)

/** Intensity islands: x, y, radius fraction, strength. Different sizes on purpose — even ones read clean. */
private val HelIslands = arrayOf(
    // broad dried-blood atmosphere -- alpha 0.05, radius 0.60w
    floatArrayOf(0.415f, 0.630f, 0.600f, 0.050f),
    // the main crimson source, deliberately NOT centred -- alpha 0.10, radius 0.32w
    floatArrayOf(0.605f, 0.520f, 0.320f, 0.100f),
    // a small scarlet hotspot well away from it -- alpha 0.08, radius 0.12w
    floatArrayOf(0.230f, 0.345f, 0.120f, 0.080f),
)

/**
 * Gothic arches: centre x, springing y, half-width, apex rise, alpha scale, **plane** (0 far / 1 mid), and
 * **completeness** — how much of the arch survives before fog takes it.
 *
 * Fragments vary in completeness: an arch disappears into fog, a rose window is only half visible,
 * and a column can vanish into black. The previous set was five arches at four alphas and all of them whole, which is why the eye
 * started reading the pattern instead of inferring the building.
 *
 * The FAR pair are enormous — nearly twice the rise of the mid ones — because scale is what says cathedral.
 * They also carry the lowest completeness: the biggest structures are the ones most lost to air.
 */
private val HelArches = arrayOf(
    floatArrayOf(0.140f, 0.760f, 0.150f, 0.560f, 1.00f, 0f, 0.62f),
    floatArrayOf(0.845f, 0.795f, 0.165f, 0.610f, 0.92f, 0f, 0.44f),
    floatArrayOf(0.500f, 0.700f, 0.205f, 0.520f, 0.78f, 0f, 0.30f),
    floatArrayOf(0.068f, 0.545f, 0.058f, 0.215f, 1.00f, 1f, 1.00f),
    floatArrayOf(0.268f, 0.505f, 0.050f, 0.185f, 0.70f, 1f, 0.55f),
    floatArrayOf(0.930f, 0.560f, 0.055f, 0.200f, 0.85f, 1f, 0.78f),
)

/**
 * Vertical haze columns: x, half-width, top, bottom, alpha scale. Very faint light implies windows without drawing them. Not architecture; light that implies it.
 */
private val HelHazeColumns = arrayOf(
    floatArrayOf(0.155f, 0.045f, 0.060f, 0.780f, 0.85f),
    floatArrayOf(0.395f, 0.030f, 0.120f, 0.700f, 0.50f),
    floatArrayOf(0.845f, 0.052f, 0.040f, 0.815f, 1.00f),
)

/** Ash motes: x, y, radius fraction, alpha. Dust in a cold room, not embers. */
private val HelAsh = arrayOf(
    floatArrayOf(0.118f, 0.222f, 0.0016f, 0.30f), floatArrayOf(0.207f, 0.640f, 0.0012f, 0.20f),
    floatArrayOf(0.352f, 0.148f, 0.0014f, 0.26f), floatArrayOf(0.436f, 0.755f, 0.0011f, 0.17f),
    floatArrayOf(0.573f, 0.196f, 0.0015f, 0.24f), floatArrayOf(0.661f, 0.700f, 0.0012f, 0.19f),
    floatArrayOf(0.789f, 0.268f, 0.0014f, 0.27f), floatArrayOf(0.884f, 0.605f, 0.0011f, 0.16f),
    floatArrayOf(0.052f, 0.430f, 0.0013f, 0.22f), floatArrayOf(0.940f, 0.395f, 0.0012f, 0.18f),
)

/** Blood filaments: fine threads, dimmer than the veins, a few of them briefly brighter. */
private val HelFilaments = arrayOf(
    floatArrayOf(-0.04f, 0.115f, 0.290f, 0.115f, 0.395f, 0.190f, 0.62f),
    floatArrayOf(0.700f, 0.095f, 0.860f, 0.180f, 1.04f, 0.180f, 0.40f),
    floatArrayOf(-0.04f, 0.880f, 0.210f, 0.880f, 0.330f, 0.815f, 0.55f),
    floatArrayOf(0.690f, 0.860f, 0.840f, 0.930f, 1.04f, 0.930f, 0.35f),
    floatArrayOf(0.830f, 0.300f, 0.930f, 0.245f, 1.04f, 0.245f, 0.28f),
)

/** The rose window's petals, as angles. Twelve, because a rose window is duodecimal, not octagonal. */
private const val HelRosePetals = 12
private const val HelRoseX = 0.500f
private const val HelRoseY = 0.335f
private const val HelRoseR = 0.230f

/** Mist planes: y, count, min/max radius, alpha, firmness. Narrower and more irregular than Freyr's. */
private val HelMistFar = floatArrayOf(0.520f, 16f, 0.075f, 0.185f, 0.075f, 0.00f)
private val HelMistMid = floatArrayOf(0.640f, 20f, 0.055f, 0.140f, 0.105f, 0.22f)
private val HelMistNear = floatArrayOf(0.780f, 22f, 0.045f, 0.115f, 0.185f, 0.62f)
private val HelMistFarLobes = cloudLobes(9109L, 1, 16, 0.88f, 1.10f, 1.70f, 0.95f)
private val HelMistMidLobes = cloudLobes(9403L, 1, 20, 0.90f, 1.15f, 1.55f, 0.85f)
private val HelMistNearLobes = cloudLobes(9721L, 1, 22, 0.92f, 1.20f, 1.40f, 0.75f)

/*
 * DreadPillars, DreadRails and DreadCourses are removed with the colonnade they described.
 *
 * The old painter drew Hel as dark pillars on a lit floor and its KDoc argued the case well -- a pillar
 * stands on something, has soft edges, and near ones are wider. All true, and all superseded: the current design is a cathedral inferred from tracery at 2-6% rather than a colonnade stated at
 * full strength. Verticality survives; the literal columns do not.
 */

/** Niflheim's floor — sparser than Hel's; the abyss is meant to be nearly empty. */
private val VoidRails = floatArrayOf(-0.45f, -0.05f, 0.30f, 0.70f, 1.05f, 1.45f)
private val VoidCourses = floatArrayOf(0.20f, 0.44f, 0.72f)

/**
 * Niflheim's cloud bank: cluster centre x, y offset from the horizon, half-width, alpha scale.
 *
 *  What was there was a PAIR of hairlines 0.01 of the height apart, one
 * `scene.highlight` at 0.62 alpha and one `palette.accent` at 0.30 — and on this palette `highlight` is
 * `0xFFDFF8FF`, near-white ice, so they measured at luma 159 and 88 across a field of 14. That is the grey
 * that divided the horizon, and it read as two rules ruled across the panel rather than as a place where
 * the world stops.
 *
 * The first replacement used five smooth radial ellipses, which read as blobs rather than clouds.
 * The black-and-white storm-sky reference has three properties missing from a smooth ellipse,
 * and the structure below implements all three:
 *
 * 1. **A lumpy silhouette at more than one scale.** Cumulus is a CLUSTER of overlapping bulges of varying
 *    size, so its outline is never a smooth curve. Hence [VoidCloudLobes]: each cluster here is a seeded
 *    scatter of [VoidCloudLobesPerCluster] circles, not one shape.
 * 2. **Lit rims against near-black cores.** That contrast is the entire cloud read; a uniformly dark mass
 *    is a hole, not weather. Each lobe carries the rim in its own gradient — see [abyssLobe].
 * 3. **Edges that dissolve rather than end.** Every lobe reaches full transparency at its own radius, so
 *    the bank has no drawn boundary anywhere and blends into the background field.
 *
 * The light is the scene's own `horizonGlow`, centred at (0.50, 0.50) and drawn BEHIND this bank, so these
 * clouds are backlit: rim brightness falls off with distance from that centre, and the bank goes to
 * silhouette at the panel edges. That is why [VoidCloudRimAlpha] is scaled per lobe rather than fixed.
 */
private val VoidCloudClusters = arrayOf(
    //  Measured on the Nova, mean luma in
    // the cloud band in the columns clear of tiles: 13 on the far left against 31-33 on the right, so the
    // bank was visibly thinner on one side. Two causes, both fixed here. The right-hand end cluster carried
    // the LOWEST alpha of the six (0.78) and the smallest half-width, and both end clusters sat close enough
    // to the frame that half their mass fell off it -- so the bank tapered exactly where it should have kept
    // going. The two entries at -0.12 and 1.13 are deliberately OFF-PANEL: a cloud bank that ends at the
    // frame edge announces the frame. These carry it past both edges so it is cut off rather than finished.
    floatArrayOf(-0.12f, 0.010f, 0.22f, 0.96f),
    floatArrayOf(0.03f, -0.008f, 0.24f, 1.00f),
    floatArrayOf(0.24f, 0.016f, 0.15f, 0.80f),
    floatArrayOf(0.44f, -0.022f, 0.22f, 1.00f),
    floatArrayOf(0.63f, 0.012f, 0.16f, 0.85f),
    floatArrayOf(0.82f, -0.014f, 0.20f, 0.94f),
    floatArrayOf(0.99f, 0.008f, 0.22f, 1.00f),
    floatArrayOf(1.13f, -0.006f, 0.22f, 0.96f),
)

/** Fixed and arbitrary; determinism is what matters, not the value. Same rule as the Well's two tables. */
private const val VoidCloudSeed = 613L

/**
 * Lobes per cluster. The count IS the lumpiness — but only if they OVERLAP.
 *
 * The first lobe attempt used 9 spread across 0.80 of the half-width and they landed as separate discs, so
 * the bank read as a heap of dark circles rather than one mass. Density and spread are a pair: raising the
 * count without tightening [VoidCloudSpread] just makes more circles.
 */
private const val VoidCloudLobesPerCluster = 22

/** How far lobes scatter from their cluster centre, in half-widths. Tight enough that they must overlap. */
private const val VoidCloudSpread = 0.66f

/** How tall the bank billows relative to a cluster's half-width. The "engulfing" knob. */
private const val VoidCloudRise = 0.78f

/**
 * Alpha of ONE lobe — deliberately far too low to be a shape on its own.
 *
 *  The previous value was 0.94, which made every lobe an object;
 * ninety objects is a cartoon however irregular you arrange them. Density now comes from ACCUMULATION —
 * twenty-two faint washes per cluster piling up where they overlap and thinning to nothing where they do
 * not, which is how a real cloud varies. This is the knob that decides cartoon vs weather; raising it back
 * toward 1 will bring the sticker look straight back.
 */
private const val VoidCloudCoreAlpha = 0.26f

/**
 * How much fainter a lobe gets as it rises, as an exponent on its own height in the bank.
 *
 * This controls dissipation: the base stays dense and the top thins out into the
 * field instead of stopping at an edge. Without it the bank has a lid.
 */
private const val VoidCloudThinning = 1.6f

/** Horizontal stretch range for a lobe. Clouds shear sideways; a circle reads as a ball. */
private const val VoidCloudWideMin = 1.30f
private const val VoidCloudWideRange = 0.95f

/**
 * One billow: its cluster, its offset and radius in that cluster's half-width units, how far it is stretched
 * sideways, and its own alpha scale.
 */
private data class CloudLobe(
    val cluster: Int,
    val dx: Float,
    val dy: Float,
    val r: Float,
    val wide: Float,
    val alpha: Float,
)

/**
 * Builds one bank's lobes. Shared by Niflheim's abyss bank and Máni's midnight sky.
 *
 * Extracted rather than copied, but with the RNG draw ORDER frozen exactly as Niflheim's inline version had
 * it — lift (two draws), dx, r, wide, in that sequence — because a seeded table's output is a function of
 * that order. Reordering these lines silently reshuffles the established scene, and
 * nothing would fail a gate: it would just be a different sky.
 */
private fun cloudLobes(
    seed: Long,
    clusterCount: Int,
    lobesPerCluster: Int,
    spread: Float,
    thinning: Float,
    wideMin: Float,
    wideRange: Float,
): List<CloudLobe> {
    val rng = Random(seed)
    val out = mutableListOf<CloudLobe>()
    for (cluster in 0 until clusterCount) {
        repeat(lobesPerCluster) {
            val lift = rng.nextFloat() * rng.nextFloat()
            out += CloudLobe(
                cluster = cluster,
                dx = (rng.nextFloat() * 2f - 1f) * spread,
                dy = 0.10f - lift * 1.05f,
                r = 0.17f + rng.nextFloat() * 0.30f * (1f - 0.35f * lift),
                wide = wideMin + rng.nextFloat() * wideRange,
                alpha = (1f - lift).pow(thinning),
            )
        }
    }
    // Large first, so small lobes land on top and break the outline.
    return out.sortedByDescending { it.r }
}

/**
 * The bank's lobes, generated ONCE with a fixed seed — same shape as the Well's tables, so nothing re-rolls
 * per composition and `NineThemeAtmosphereVisualTest`'s idle-pixel-hash pin stays green.
 *
 * Two deliberate biases, both taken from the reference: lobes are lifted by `nextFloat() * nextFloat()`,
 * which crowds them toward the BASE and thins them toward the top the way a real cloud stacks; and higher
 * lobes are smaller, so the silhouette breaks into finer detail as it rises instead of staying chunky.
 *
 * Sorted large-first so small lobes paint last. **That ordering is the billow** — a small lobe landing on
 * top of a big one leaves its own lit rim exposed against the big one's dark body, which is exactly the
 * overlap that reads as volume. Sorted the other way the detail disappears under the mass.
 */
private val VoidCloudLobes: List<CloudLobe> = cloudLobes(
    seed = VoidCloudSeed,
    clusterCount = VoidCloudClusters.size,
    lobesPerCluster = VoidCloudLobesPerCluster,
    spread = VoidCloudSpread,
    thinning = VoidCloudThinning,
    wideMin = VoidCloudWideMin,
    wideRange = VoidCloudWideRange,
)

/** Niflheim's mist columns: x, half-width, alpha scale. */
private val VoidColumns = arrayOf(
    floatArrayOf(0.12f, 0.070f, 0.85f), floatArrayOf(0.30f, 0.050f, 0.60f),
    floatArrayOf(0.47f, 0.085f, 1.00f), floatArrayOf(0.66f, 0.055f, 0.70f),
    floatArrayOf(0.84f, 0.075f, 0.80f),
)

/** Yggdrasil's root lattice: each root is x-at-floor, x-at-canopy, and the height it climbs. */
private val Roots = arrayOf(
    floatArrayOf(0.06f, 0.16f, 0.52f), floatArrayOf(0.20f, 0.12f, 0.68f),
    floatArrayOf(0.34f, 0.42f, 0.44f), floatArrayOf(0.52f, 0.46f, 0.72f),
    floatArrayOf(0.68f, 0.78f, 0.50f), floatArrayOf(0.84f, 0.76f, 0.64f),
    floatArrayOf(0.95f, 0.88f, 0.40f),
)

/**
 * **FÓLKVANGR · THE FIELD OF THE SLAIN** — replacing Brísingamen.
 *
 * Freyja receives half the war dead in Fólkvangr, and their spears are planted where they fell. The
 * scene is that field: rows of shafts standing in flat ground, thinning toward a horizon, under a sky
 * that gives no warmth back.
 *
 * **The recession is a projective map, not a curve chosen by eye** — the same discipline the Well's
 * geometry note demands of a floor. For a ground plane viewed from a fixed height, screen distance below
 * the horizon is a Möbius function of world depth:
 *
 * ```
 * u(t) = t / (t + c(1 − t))      u(0) = 0 at the horizon,  u(1) = 1 at the near edge
 * ```
 *
 * `c` is the only tuning constant, and it is the camera's compression: small values bunch the far rows
 * hard against the horizon, which is what distance actually looks like. The SAME `u` scales each spear's
 * height, because an object of fixed world height projects to a screen height proportional to its
 * distance from the horizon. One function therefore places a spear and sizes it, so a row can never sit
 * at one depth and be drawn at another — the failure that makes a field read as wallpaper.
 */
private const val FieldHorizon = 0.44f

/** Camera compression for [groundU]. Smaller crowds the far rows harder; 0.22 reads as open ground. */
private const val FieldCompression = 0.22f

/** The one low, cold light. Every shaft's lit side faces it, so the field is lit from one place. */
private const val FieldLightX = 0.30f
private const val FieldLightY = 0.30f

/** Depth [t] in 0..1 to its projected fraction of the ground. See the class KDoc for the derivation. */
private fun groundU(t: Float): Float = t / (t + FieldCompression * (1f - t))

/**
 * The planted field: x across the ground, depth t, lean, and length jitter.
 *
 * Rows get denser as they recede because a fixed spacing in world units projects to a smaller spacing on
 * screen. The x jitter is a fraction of the row's OWN spacing, so near rows scatter widely and far rows
 * stay tight — a constant jitter would make the distance look noisy rather than far away.
 */
private val FieldSpears: Array<FloatArray> = run {
    val rng = Random(9137L)
    val rows = 7
    val out = ArrayList<FloatArray>(160)
    for (r in 0 until rows) {
        val t = (r + 1f) / rows
        val count = 5 + (rows - r) * 3
        for (i in 0 until count) {
            out += floatArrayOf(
                (i + 0.5f) / count + (rng.nextFloat() - 0.5f) * (0.85f / count),
                t,
                (rng.nextFloat() - 0.5f) * 0.13f,
                0.80f + rng.nextFloat() * 0.40f,
            )
        }
    }
    out.toTypedArray()
}

/** Ash over the field. Mostly faint with a few bright, the distribution every speck table here uses. */
private val FieldAsh: Array<SceneSpeck> = run {
    val rng = Random(5501L)
    Array(52) {
        val bright = rng.nextFloat()
        SceneSpeck(
            x = rng.nextFloat() * 1.06f - 0.03f,
            y = FieldHorizon + rng.nextFloat() * (1.02f - FieldHorizon),
            radius = 0.5f + bright.pow(2.5f) * 1.4f,
            alpha = 0.10f + bright.pow(1.9f) * 0.50f,
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Small shared painters
// ─────────────────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.specks(table: Array<SceneSpeck>, color: Color, scale: Float = 1f) {
    for (s in table) {
        drawCircle(
            color = color.copy(alpha = color.alpha * s.alpha * scale),
            radius = s.radius.dp.toPx(),
            center = Offset(size.width * s.x, size.height * s.y),
        )
    }
}

/** A soft round wash. [radius] is a fraction of the field's LONG side, so it survives any aspect. */
private fun DrawScope.wash(cx: Float, cy: Float, radius: Float, color: Color) {
    val r = maxOf(size.width, size.height) * radius
    if (r <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to color,
            0.55f to color.copy(alpha = color.alpha * 0.34f),
            1.00f to Color.Transparent,
            center = Offset(size.width * cx, size.height * cy),
            radius = r,
        ),
        radius = r,
        center = Offset(size.width * cx, size.height * cy),
    )
}

/**
 * [wash], foreshortened — the same radial falloff drawn into an ellipse rather than a circle.
 *
 * The Well needs this because its light sits at the bottom of a shaft being viewed at a tilt: a circular
 * glow inside elliptical rings is the giveaway that the rings are decoration rather than geometry. [rx] and
 * [ry] are semi-axes in PIXELS, not fractions, because the caller derives them from the ring family it must
 * agree with rather than from the panel.
 */
/**
 * [wash] at a pixel radius — a true DISC, deliberately not foreshortened.
 *
 *  The one exception to the
 * Well's single-angle rule, and it is a real one rather than an oversight: the rings and the shaft are
 * SURFACES seen at a tilt, so they foreshorten; the light at the bottom is a source, and a source reads as
 * a point. Squashed onto the ring family's angle it stopped being a nova and became a lens.
 *
 * Takes pixels rather than a fraction of `max(w, h)` like [wash], so the core still scales off the panel's
 * SHORT side with everything else — the same size at every panel aspect, just round.
 */
private fun DrawScope.washDisc(cx: Float, cy: Float, radius: Float, color: Color) {
    if (radius <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to color,
            0.55f to color.copy(alpha = color.alpha * 0.34f),
            1.00f to Color.Transparent,
            center = Offset(cx, cy),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )
}

/**
 * The DEFAULT scene's SUN. Uses a synthwave reference.
 * Rim-lit — brightest at the LIMB ([SunRimStop]), dim interior, short bloom past it — because that is the
 * single feature the synthwave reference turned on.
 *
 * A prior monotonic centre-bright rewrite was reverted because it removed the intended rim.
 * The radial luma profile peaks at exactly 0.78 of the radius with darker regions on both sides;
 * that peak is distinct from the unwanted caustic glow, a separate soft ellipse beneath the disc
 * brightest at the centre and fading out by ~0.53 of the sun's radius. Remove that ellipse while
 * preserving this rim ladder. The brightest measured feature is not necessarily the unwanted one.
 *
 * Still ONE `drawCircle` — the file's one-pass rule is untouched, and `washDisc` is left alone because five
 * other scenes draw with it.
 */
private fun DrawScope.sunDisc(cx: Float, cy: Float, radius: Float, color: Color) {
    if (radius <= 0f) return
    val a = color.alpha
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to color.copy(alpha = a * 0.26f),
            0.42f to color.copy(alpha = a * 0.34f),
            0.64f to color.copy(alpha = a * 0.48f),
            SunRimStop to color.copy(alpha = a),
            0.86f to color.copy(alpha = a * 0.30f),
            0.94f to color.copy(alpha = a * 0.10f),
            1.00f to Color.Transparent,
            center = Offset(cx, cy),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )
}

/**
 * The corner vignette every canvas panel carries, and none of the painters had.
 *
 * This is what makes a field read as a PLACE rather than a wash: the canvas darkens every panel's
 * edges by 0.52–0.82 so the eye is pushed to the scene's own anchor. Drawn as one radial over the
 * whole field, transparent at the centre — one gradient, no layer, no bitmap.
 */
/**
 * One billow: a stretched, softly-falling wash that is not meant to be visible on its own.
 *
 * **The ladder has no plateau, and that is the point.** It used to hold 0.90 of its alpha out to 0.62 of
 * the radius, producing a flat opaque core with a soft skirt rather than dissipating haze.
 * Every stop below is lower than the one before it from the
 * centre outward, so a lobe has no body and no edge, only a falling-off. What density there is comes from
 * lobes piling on lobes.
 *
 * The stretch is applied as a `scale` around the lobe's own centre rather than by drawing an oval. Drawing
 * an oval with a circular brush clips the gradient at the oval's bounds and leaves a hard horizontal cut
 * top and bottom — the shape's own edge, reintroduced by the very call meant to soften it. Scaling the
 * whole draw stretches the gradient with the shape, so the falloff stays elliptical and seamless.
 */
private fun DrawScope.abyssLobe(
    cx: Float,
    cy: Float,
    r: Float,
    wide: Float,
    color: Color,
    firm: Float = 0f,
) {
    if (r <= 0f) return
    // [firm] blends this lobe from Niflheim's ladder toward a firmer one that holds its value further out
    // before falling. It defaults to 0, and that default is load-bearing: Niflheim's existing bank shares this painter, so its curve has to come through UNCHANGED. Baldr asks for the firm end because
    // the two scenes want opposite things -- that bank recedes behind a horizon, this one is the subject,
    // and "no edge anywhere" is also "no form anywhere" when the cloud is what you are meant to look at.
    val a = color.alpha
    fun stop(soft: Float, hard: Float) = color.copy(alpha = a * (soft + (hard - soft) * firm))
    scale(scaleX = wide, scaleY = 1f, pivot = Offset(cx, cy)) {
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to color,
                0.30f to stop(0.70f, 0.90f),
                0.55f to stop(0.42f, 0.72f),
                0.75f to stop(0.20f, 0.40f),
                0.90f to stop(0.06f, 0.12f),
                1.00f to Color.Transparent,
                center = Offset(cx, cy),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
    }
}

private fun DrawScope.vignette(strength: Float) {
    val r = maxOf(size.width, size.height) * 0.78f
    drawRect(
        brush = Brush.radialGradient(
            0.00f to Color.Transparent,
            0.62f to Color.Transparent,
            1.00f to Color.Black.copy(alpha = strength),
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            radius = r,
        ),
    )
}

/**
 * THE FAMILY'S SHARED DEVICE — a floor plane running away to a vanishing point.
 *
 * A shared floor unifies the nine authored scenes: Freyr's hall, Niflheim's abyss, Surtr's plate and Hel's colonnade all stand on the same
 * receding ground. Rails converge on [vpX] at the horizon; courses cross them at intervals SQUARED so
 * spacing compresses with depth — that compression is the entire perspective cue, and a floor drawn
 * with even spacing reads as a flat grid instead of a room.
 */
private fun DrawScope.perspectiveFloor(
    horizonY: Float,
    vpX: Float,
    rails: FloatArray,
    courses: FloatArray,
    color: Color,
    farAlpha: Float,
    nearAlpha: Float,
) {
    val hy = size.height * horizonY
    val vx = size.width * vpX
    for (rail in rails) {
        drawLine(
            brush = Brush.verticalGradient(
                0f to color.copy(alpha = farAlpha),
                1f to color.copy(alpha = nearAlpha),
                startY = hy,
                endY = size.height,
            ),
            start = Offset(vx, hy),
            end = Offset(size.width * rail, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
    for (course in courses) {
        val y = hy + (size.height - hy) * course * course
        drawLine(
            color = color.copy(alpha = farAlpha + (nearAlpha - farAlpha) * course),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** A light standing ON the horizon: the glow that tells you the plane has an end. */
private fun DrawScope.horizonGlow(y: Float, cx: Float, color: Color, alpha: Float, reach: Float = 0.42f) {
    drawRect(
        brush = Brush.radialGradient(
            0.00f to color.copy(alpha = alpha),
            1.00f to Color.Transparent,
            center = Offset(size.width * cx, size.height * y),
            radius = size.width * reach,
        ),
    )
}

/** The footer every scene sits on: the field falls into its own shadow at the bottom. */
private fun DrawScope.footer(scene: MimirSceneMaterial) {
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to Color.Transparent,
            0.62f to Color.Transparent,
            1.00f to scene.fieldFooter.copy(alpha = 0.92f),
        ),
    )
}





// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The nine
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** DEFAULT · THE WELL — a sun with a particulate ring plane, receding into the dark the name still keeps. */
internal fun DrawScope.drawWellSceneV2(
    palette: MimirThemePalette,
    pulse: Float = 0f,
    quietLight: Boolean = false,
    lightBelowText: Boolean = false,
    // The four-point diffraction spikes on the bright stars. On by default (every Main surface keeps them); the Bottom's
    // 1/4-size miniature turns them off, because at that scale each spike is a sub-pixel speck that the box blur and the
    // x4 upscale turn into a faint grey square. The star dots stay.
    shineStars: Boolean = true,
    // False takes the sun and its ring dust out altogether (the Mimir-button picker's strip stages, where the sun's anchor lands on a caption).
    sceneLight: Boolean = true,
) {
    val scene = palette.materials.scene
    // on a compact square the anchor below lands the sun under
    // the dock's last icons. Dim the light there while keeping its geometry: same anchor, same size, same family, at
    // [MimirSceneQuietLightScale] of the light. Only the sun and its ring dust -- the stars and nebula are
    // the field, not the light.
    val light = when {
        !sceneLight -> 0f
        quietLight -> MimirSceneQuietLightScale
        else -> 1f
    }
    val short = min(size.width, size.height)
    // **The anchor is the canvas's, not centre.** Every canvas panel is composed AROUND a mock hero
    // tile at (49.5%, 52.8%); the well sits at (57.3%, 75.6%) so the eye finds it beside the content.
    // Centring it put the one point of light in this field directly behind Home's focused tile, its
    // backdrop and the centre veil — which is most of why this scene measured invisible.
    //  +0.05 of the short
    // side on both axes, expressed off `short` so it scales with the ring family rather than with the
    // viewport's aspect. Everything below derives from these two values -- the ring band, the caustic glow
    // and the sun -- so the family moves as ONE and the shared foreshortening is preserved. `specks`,
    // `footer` and `vignette` stay field-anchored on purpose. [WellDiscZoneX]/[WellDiscZoneY] in the
    // star-field exclusion above track this same anchor, approximately -- see that constant's KDoc.
    //  0.573 -> 0.66. The whole family --
    // ring band, caustic and sun -- derives from this one value, so they move together.
    // on a 4:3 or square field the sun moves lower and further right, below the text
    // column -- [wellSunGeometry] and [SunBelowTextRightInset]. A wide field gets the anchor described above,
    // unchanged. One function, so the place is assertable and the ring family still derives from cx/cy.
    val sun = wellSunGeometry(size.width, size.height, belowText = lightBelowText)
    val cx = sun.cx
    val cy = sun.cy
    // Semi-axes for anything concentric here, off the family's own scale and [WellAspect]. Local, because
    // nothing outside this well has this geometry.
    fun rx(fraction: Float) = wellSemiAxes(short, fraction).first
    fun ry(fraction: Float) = wellSemiAxes(short, fraction).second
    // Ring-band particle -> pixel, riding `rx`/`ry` like everything else here so [WellAspect] moves the
    // whole family together. `theta`/`radiusFraction` are the only stored values; sin/cos are pure, so
    // computing the point here at draw time is still fully deterministic per [WellRingParticlesByTier].
    fun ringPoint(p: WellRingParticle) =
        Offset(cx + rx(p.radiusFraction) * cos(p.theta), cy + ry(p.radiusFraction) * sin(p.theta))
    fun ringTierPoints(tier: Int) = WellRingParticlesByTier[tier].map(::ringPoint)
    fun starPoints(field: List<SceneStarPoint>) = field.map { Offset(size.width * it.x, size.height * it.y) }
    // A static diffraction spike + halo for one "shining" star -- see [WellShiningStarCount]'s KDoc. Local,
    // because nothing outside this well draws a spike.
    fun shineSpike(center: Offset, color: Color) {
        val spike = WellShineSpikeLengthDp.dp.toPx()
        val halo = WellShineHaloDp.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(0.00f to color, 1.00f to Color.Transparent, center = center, radius = halo),
            radius = halo,
            center = center,
        )
        drawLine(
            brush = Brush.linearGradient(
                0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent,
                start = Offset(center.x - spike, center.y), end = Offset(center.x + spike, center.y),
            ),
            start = Offset(center.x - spike, center.y), end = Offset(center.x + spike, center.y),
            strokeWidth = 0.6.dp.toPx(), cap = StrokeCap.Round,
        )
        drawLine(
            brush = Brush.linearGradient(
                0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent,
                start = Offset(center.x, center.y - spike), end = Offset(center.x, center.y + spike),
            ),
            start = Offset(center.x, center.y - spike), end = Offset(center.x, center.y + spike),
            strokeWidth = 0.6.dp.toPx(), cap = StrokeCap.Round,
        )
    }

    // NEBULA CLOUDS, drawn first so everything else paints over them -- background atmosphere, not a
    // fourth light. See [WellNebulaClouds]'s KDoc for the visual intent and material choice.
    for (cloud in WellNebulaClouds) {
        val material = if (cloud.warm) scene.stagePrimary else scene.stageSecondary
        washDisc(
            size.width * cloud.x,
            size.height * cloud.y,
            short * cloud.radiusFraction,
            material.copy(alpha = material.alpha * WellNebulaAlphaScale),
        )
    }
    // REMOVED,  This used to be `pit(...)`, a sunk mouth: a radial gradient of BLACK
    // at alpha 0.90 centred exactly on the sun. Measured on device, that read as a well more than half the
    // depth of the field's own brightness and still 7 luma down at 2.3 sun-radii out -- not subtle, and not
    // fixable by softening it without also defeating the reason it existed. The scene has become a body in
    // space rather than a well with something at the bottom of it; a body in space does not need a hole
    // under it. The sun's own brightness/bloom (see `sunDisc`'s KDoc for the sun rewrite) and the
    // caustic glow below now do all the grounding this scene has.
    //  The stroked ellipses are gone -- this is dust now, batched by depth tier
    // via `drawPoints` (one draw call per tier, not one `drawCircle` per speck: see
    // [WellRingParticlesByTier]'s KDoc for the cost reasoning).
    //
    // FAR HALF FIRST, drawn before the sun so the body occludes it -- the single thing that makes this
    // read as a ring passing BEHIND a planet rather than a decal floating over a circle.
    for (tier in 0 until WellRingTierCount / 2) {
        drawPoints(
            points = ringTierPoints(tier),
            pointMode = PointMode.Points,
            color = palette.accent.copy(alpha = WellRingTierAlpha[tier] * light),
            strokeWidth = WellRingTierDotDp[tier].dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    // REMOVED,  This was
    // the caustic glow — a soft ellipse of `scene.highlight` at 0.16 of the short side, brightest at dead
    // centre and fading out by roughly half the sun's radius, painted UNDER the disc. It was the last
    // survivor of the well this scene used to be: the pool of light at the bottom of a shaft. With the
    // shaft gone (round 3) it had nothing left to be the bottom of, and read as a small diffused disc
    // sitting inside the sun. The sun's own ladder is untouched — see `sunDisc`'s KDoc for the rim rewrite
    // that was tried here first and reverted.
    // The bright core: round — see [washDisc]. Original size, off the short side like the rest.
    // The sun is sized off the family's own scale, like everything else in this well, so it stays in
    // proportion at any panel size.
    //
    // This scene is static. MimirShelfBackground calls drawThemeSceneV2 without pulse, so it
    // stays at its 0f default; ambient breath is gated to VOID and DREAD. The pulse terms remain
    // only for the shared painter signature. Threading a clock would change the scene and turn
    // ring-dust allocation from per-invalidation into per-frame work (see the header note).
    sunDisc(
        cx,
        cy,
        short * SunRadiusFraction * (1f + pulse * NovaBreathScale),
        scene.highlight.copy(alpha = (0.90f + pulse * 0.08f) * light),
    )
    // NEAR HALF, drawn AFTER the sun so it occludes the body instead of the other way round -- the other
    // half of the occlusion split, and the near tiers are also the bigger/brighter
    // ones, so the band reads thicker as it comes toward the viewer.
    for (tier in WellRingTierCount / 2 until WellRingTierCount) {
        drawPoints(
            points = ringTierPoints(tier),
            pointMode = PointMode.Points,
            color = palette.accent.copy(alpha = WellRingTierAlpha[tier] * light),
            strokeWidth = WellRingTierDotDp[tier].dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    // REMOVED,  A single full-bleed horizontal rule at
    // 30% height, running edge to edge with nothing on either side of it standing in the same plane. It was
    // added as an "architectural rim" and read as a stray divider laid across the sky — the well is the
    // subject here, and a rule that touches both frame edges competes with it for the whole width of the
    // panel. Nothing replaces it; the ring band and the vignette already give the field its horizon.
    // , a denser independent star field --
    // [WellStarField], not the shared [MoonStars] table the other six scenes still use. Two `drawPoints`
    // calls (dim bulk, bright accents) rather than 120 `drawCircle`s.
    drawPoints(
        points = starPoints(WellStarFieldDim),
        pointMode = PointMode.Points,
        color = scene.highlight.copy(alpha = WellStarDimAlpha),
        strokeWidth = WellStarDimDotDp.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawPoints(
        points = starPoints(WellStarFieldBright),
        pointMode = PointMode.Points,
        color = scene.highlight.copy(alpha = WellStarBrightAlpha),
        strokeWidth = WellStarBrightDotDp.dp.toPx(),
        cap = StrokeCap.Round,
    )
    // A handful of the already-bright stars receive a static shining treatment,
    // bright tier, layered with a static spike -- see [WellShiningStarCount]'s KDoc for why this is a few
    // individual draws rather than a `drawPoints` batch, and why it is static.
    if (shineStars) {
        for (s in WellShiningStars) {
            shineSpike(Offset(size.width * s.x, size.height * s.y), scene.highlight.copy(alpha = WellShineAlpha))
        }
    }
    footer(scene)
    vignette(0.60f)
}

/** FREYR · GOLDEN HALL — a longhouse in perspective, rafters converging on the hearth. */
/**
 * One atmospheric plane. Lobes are scattered horizontally and merged into banks by their own overlap; the
 * plane's `firm` value stands in for the design blur radius.
 */
private fun DrawScope.freyrCloudPlane(
    plane: FloatArray,
    lobes: List<CloudLobe>,
    color: Color,
    lift: Float = 0f,
) {
    val short = min(size.width, size.height)
    val baseY = size.height * plane[0] - size.height * lift
    val count = plane[1].toInt()
    for ((i, p) in lobes.withIndex()) {
        if (i >= count) break
        // Spread across the full width and a little past it, so no bank ends inside the frame. Vertical
        // displacement stays inside +-10% of the height, which is what merges blobs into banks instead of
        // leaving them as a row of circles.
        val x = size.width * (-0.06f + 1.12f * ((i + 0.5f) / count)) + p.dx * size.width * 0.055f
        val y = baseY + p.dy * size.height * 0.085f
        val r = short * (plane[2] + (plane[3] - plane[2]) * p.r)
        abyssLobe(x, y, r, p.wide, color.copy(alpha = color.alpha * plane[4]), plane[5])
    }
}

internal fun DrawScope.drawGoldenHallSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene
    val short = min(size.width, size.height)
    // Palette equivalents for the reference colours. Mixed, never literal -- see the KDoc on
    // [FreyrLightX] for why, and for the one-line change that would make them exact.
    val voidBlack = scene.shadow
    val gold = palette.accent
    val brightGold = palette.accentBright
    val deepGold = lerp(palette.accent, scene.shadow, 0.58f)
    val smoke = lerp(scene.shadow, palette.accent, 0.10f)
    val darkSmoke = lerp(scene.shadow, palette.accent, 0.04f)

    // ---- 1. graphite base, warm-biased rather than brown -------------------------------------------
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to lerp(voidBlack, gold, 0.045f),
            0.55f to lerp(voidBlack, gold, 0.018f),
            1.00f to voidBlack,
        ),
    )

    // ---- 2. the gold backlight, BEFORE the clouds so they occlude it --------------------------------
    // The whole depth of the scene rests on this ordering. Light drawn over cloud is a tint; light drawn
    // under cloud is a source.
    val lightC = Offset(size.width * FreyrLightX, size.height * FreyrLightY)
    val lightR = size.width * 0.44f
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to brightGold.copy(alpha = 0.24f),
            0.22f to gold.copy(alpha = 0.135f),
            0.46f to gold.copy(alpha = 0.062f),
            0.72f to deepGold.copy(alpha = 0.030f),
            1.00f to Color.Transparent,
            center = lightC,
            radius = lightR,
        ),
        radius = lightR,
        center = lightC,
    )

    // ---- 3. far haze: barely reads as cloud at all -------------------------------------------------
    freyrCloudPlane(FreyrHazePlane, FreyrHazeLobes, lerp(smoke, gold, 0.30f))
    // ---- 4. distant clouds, catching the backlight: burnished gold through smoke, never yellow -----
    freyrCloudPlane(FreyrDistantPlane, FreyrDistantLobes, lerp(deepGold, smoke, 0.45f))
    // ---- 5. the middle bank, where structure becomes readable --------------------------------------
    freyrCloudPlane(FreyrMidPlane, FreyrMidLobes, lerp(smoke, deepGold, 0.42f))

    // ---- 6. THE RIM. One geometry, drawn twice ------------------------------------------------------
    // The shared-geometry technique and the reason no outline is drawn anywhere: the gold copy sits a
    // fraction of the height ABOVE the dark copy that shares its seed, so the offset leaves a lit sliver
    // along the upper edge of an otherwise opaque bank. Light scattering around a cloud, by construction.
    freyrCloudPlane(FreyrRimPlane, FreyrRimLobes, gold, lift = FreyrRimLift)
    freyrCloudPlane(FreyrForePlane, FreyrRimLobes, lerp(voidBlack, smoke, 0.35f))

    // ---- 7. foreground: darker, tighter, and it hides the lowest circular edges ---------------------
    freyrCloudPlane(FreyrDeepPlane, FreyrDeepLobes, lerp(voidBlack, darkSmoke, 0.45f))

    // ---- 8. terrain silhouette: scale, and a second mask over the last of the blob geometry ---------
    drawPath(
        Path().apply {
            moveTo(size.width * FreyrRidge[0], size.height * FreyrRidge[1])
            var i = 2
            while (i < FreyrRidge.size) {
                lineTo(size.width * FreyrRidge[i], size.height * FreyrRidge[i + 1]); i += 2
            }
            lineTo(size.width * 1.05f, size.height); lineTo(size.width * -0.05f, size.height); close()
        },
        brush = Brush.verticalGradient(
            0f to lerp(voidBlack, gold, 0.02f),
            1f to voidBlack,
            startY = size.height * 0.85f,
            endY = size.height,
        ),
        style = Fill,
    )

    // ---- 9. gold horizon scatter: few, mostly faint, and the field reads enormous -------------------
    for (e in FreyrEmbers) {
        val c = Offset(size.width * e[0], size.height * e[1])
        if (e[2] > 0.24f) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to gold.copy(alpha = e[2] * 0.30f),
                    1.00f to Color.Transparent,
                    center = c,
                    radius = short * 0.020f,
                ),
                radius = short * 0.020f,
                center = c,
            )
        }
        drawCircle(color = brightGold.copy(alpha = e[2]), radius = 1.2.dp.toPx(), center = c)
    }

    // ---- 10. the cyber-lattice, peripheral only -----------------------------------------------------
    // Nothing here crosses the middle: `FreyrLatticeKeepOut` fences the content lane off, so richness sits
    // at the edges and the field stays calm behind information. Traces under 0.10 alpha, nodes to 0.18 --
    // loud traces are how this becomes "generic orange cyberpunk", which defeats the atmospheric restraint.
    fun outsideKeepOut(x: Float) = x < FreyrLatticeKeepOut || x > 1f - FreyrLatticeKeepOut
    for (run in FreyrTraces) {
        var i = 0
        while (i + 3 < run.size) {
            if (outsideKeepOut(run[i]) && outsideKeepOut(run[i + 2])) {
                drawLine(
                    color = gold.copy(alpha = 0.075f),
                    start = Offset(size.width * run[i], size.height * run[i + 1]),
                    end = Offset(size.width * run[i + 2], size.height * run[i + 3]),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            i += 2
        }
    }
    for (h in FreyrHexes) {
        val cx = size.width * h[0]
        val cy = size.height * h[1]
        val r = short * h[2]
        val sides = h[4].toInt()
        for (k in 0 until sides) {
            val a0 = (k / 6f) * 2f * PI.toFloat()
            val a1 = ((k + 1) / 6f) * 2f * PI.toFloat()
            drawLine(
                color = gold.copy(alpha = h[3]),
                start = Offset(cx + r * cos(a0), cy + r * sin(a0)),
                end = Offset(cx + r * cos(a1), cy + r * sin(a1)),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
    // Norse geometry: angular branch topology and concentric sigils, inside the same periphery. Straight
    // runs only -- a curve here reads as decoration, a straight run reads as structure.
    for (b in FreyrBranches) {
        var i = 0
        while (i + 3 < b.size) {
            drawLine(
                color = lerp(gold, brightGold, 0.30f).copy(alpha = 0.085f),
                start = Offset(size.width * b[i], size.height * b[i + 1]),
                end = Offset(size.width * b[i + 2], size.height * b[i + 3]),
                strokeWidth = 1.1.dp.toPx(),
            )
            i += 2
        }
    }
    for (g in FreyrSigils) {
        val c = Offset(size.width * g[0], size.height * g[1])
        val rings = g[3].toInt()
        for (k in 0 until rings) {
            drawCircle(
                color = gold.copy(alpha = g[4] * (1f - k * 0.22f)),
                radius = short * g[2] * (1f - k * 0.30f),
                center = c,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
    for (n in FreyrNodes) {
        val c = Offset(size.width * n[0], size.height * n[1])
        drawCircle(color = brightGold.copy(alpha = n[3]), radius = short * n[2], center = c)
    }

    // ---- 11. vignette, strong: the edges nearly disappear -------------------------------------------
    vignette(0.68f)
}

/** SURTR · THE FORGE — black steel, cracked. The seams fork like traces and burn at every fork. */
/** A run of x,y pairs as a stroked path. */
private fun DrawScope.surtrPath(run: FloatArray, points: Int): Path = Path().apply {
    moveTo(size.width * run[0], size.height * run[1])
    var i = 2
    while (i + 1 < points) {
        lineTo(size.width * run[i], size.height * run[i + 1]); i += 2
    }
}

/**
 * One segment of the lattice, in three restrained passes.
 *
 * The obsidian shoulder from the previous pass is gone with the keep-out that made it necessary: at these
 * alphas there is no bright line for a crust lip to bound, and a dark stroke under a 0.055-alpha bleed
 * would only have muddied the plate. What sells "beneath" now is the thermal body under everything and the
 * lattice being dim enough to belong to it.
 */
private fun DrawScope.surtrSegment(
    p0: Offset,
    p1: Offset,
    scene: MimirSceneMaterial,
    palette: MimirThemePalette,
    hot: Boolean,
) {
    val deepHeat = lerp(palette.accent, scene.shadow, 0.42f)
    val magmaRed = lerp(palette.accent, scene.shadow, 0.12f)
    drawLine(
        color = deepHeat.copy(alpha = SurtrBleedAlpha),
        start = p0, end = p1,
        strokeWidth = SurtrBleedWidth.dp.toPx(), cap = StrokeCap.Round,
    )
    drawLine(
        color = (if (hot) magmaRed else deepHeat).copy(alpha = SurtrBodyAlpha),
        start = p0, end = p1,
        strokeWidth = SurtrBodyWidth.dp.toPx(), cap = StrokeCap.Round,
    )
    if (!hot) return
    drawLine(
        color = lerp(palette.accentBright, scene.highlight, 0.35f).copy(alpha = SurtrCoreAlpha),
        start = p0, end = p1,
        strokeWidth = SurtrCoreWidth.dp.toPx(), cap = StrokeCap.Round,
    )
}

internal fun DrawScope.drawForgeSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene
    val short = min(size.width, size.height)
    // **MUSPELHEIM AS A BURIED LATTICE.**  The scene this replaces routed Surtr
    // like a circuit BOARD -- orthogonal traces over a plate, on the argument that right angles read as
    // something built while diagonals read as broken glass. That argument was sound for a board and is
    // not appropriate here: the surface IS meant to be broken, and the geometry is a fracture
    // network with molten rock under it rather than wiring on top of it.
    //
    // The rule underneath is the method doc's first one, in a new costume: the light is drawn BEFORE what
    // occludes it. Freyr puts gold behind cloud; Surtr puts magma under crust. The thermal light must appear through gaps rather than as orange lines painted on black: this is the tint-versus-source distinction — a glow painted ON a surface is a tint,
    // a glow seen THROUGH a gap in it is a source.
    //
    // Value budget: roughly 85% obsidian, 10% thermal, 4% molten, 1% white-hot. The crust is the
    // subject; the magma is what makes the crust legible.
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to lerp(scene.shadow, palette.accent, 0.030f),
            0.58f to scene.shadow,
            1.00f to lerp(scene.shadow, palette.accent, 0.055f),
        ),
    )
    // Faint thermal haze -- the field is warm before any fracture is drawn, which is what stops the
    // fractures reading as decals applied to a neutral background.
    for (r in SurtrReservoirs) {
        val c = Offset(size.width * r[0], size.height * r[1])
        val rad = size.width * r[2] * 1.9f
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to palette.accent.copy(alpha = 0.055f * r[3]),
                0.55f to lerp(palette.accent, scene.shadow, 0.5f).copy(alpha = 0.022f * r[3]),
                1.00f to Color.Transparent,
                center = c, radius = rad,
            ),
            radius = rad, center = c,
        )
    }
    // THE RESERVOIRS. Under the crust, at the junctions, so a junction reads as a magma pool rather than
    // as a glowing vertex. Drawn before every fracture below them, which is the whole point.
    for (r in SurtrReservoirs) {
        val c = Offset(size.width * r[0], size.height * r[1])
        val rad = size.width * r[2]
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to palette.accentBright.copy(alpha = 0.185f * r[3]),
                0.34f to palette.accent.copy(alpha = 0.090f * r[3]),
                0.68f to lerp(palette.accent, scene.shadow, 0.45f).copy(alpha = 0.032f * r[3]),
                1.00f to Color.Transparent,
                center = c, radius = rad,
            ),
            radius = rad, center = c,
        )
    }
    // The lattice. Viewport-space, tile-blind, and faint enough that passing behind a cover is
    // unremarkable -- which is the point, not a compromise.
    for ((pi, path) in SurtrFractures.withIndex()) {
        var i = 0
        var seg = 0
        while (i + 3 < path.size) {
            val hot = SurtrHotSegments.any { it[0] == pi && it[1] == seg }
            surtrSegment(
                Offset(size.width * path[i], size.height * path[i + 1]),
                Offset(size.width * path[i + 2], size.height * path[i + 3]),
                scene, palette, hot,
            )
            i += 2; seg++
        }
    }
    // Embers, none of them in the caption lane.
    for (e in SurtrEmbers) {
        val c = Offset(size.width * e[0], size.height * e[1])
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to scene.highlight.copy(alpha = e[3] * 0.35f),
                1.00f to Color.Transparent,
                center = c, radius = short * e[2] * 5f,
            ),
            radius = short * e[2] * 5f, center = c,
        )
        drawCircle(color = scene.highlight.copy(alpha = e[3]), radius = short * e[2], center = c)
    }
    footer(scene)
    vignette(0.66f)
}

/** MÁNI · MOONLIT VIGIL — a cold moon low behind the fjord, and the water that repeats it. */
internal fun DrawScope.drawMoonlitSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene
    val short = min(size.width, size.height)
    //  Máni's own field — see [MoonStarField] — not the
    // eight specks of the shared [MoonStars] table, which six other scenes still draw unchanged. Two
    // `drawPoints` batches rather than 110 `drawCircle`s.
    drawPoints(
        points = MoonStarFieldDim.map { Offset(size.width * it.x, size.height * it.y) },
        pointMode = PointMode.Points,
        color = scene.highlight.copy(alpha = MoonStarDimAlpha),
        strokeWidth = MoonStarDimDotDp.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawPoints(
        points = MoonStarFieldBright.map { Offset(size.width * it.x, size.height * it.y) },
        pointMode = PointMode.Points,
        color = scene.highlight.copy(alpha = MoonStarBrightAlpha),
        strokeWidth = MoonStarBrightDotDp.dp.toPx(),
        cap = StrokeCap.Round,
    )

    // **THE MOON IS A BODY, NOT A LAMP.**
    // Four corrections: less illumination, a little more radius, craters (without them it
    // reads as a light source and not a moon), and the broad pale sky band GONE — it was a wide hue
    // washing the whole field for no reason a night sky would have.
    // **MOVED, ** It sat at
    // (0.257, 0.38), and measured against a moonless capture of the same shelf that seat is **100%
    // occluded** — not partly hidden, entirely behind the leftmost platform tile. Every round of tuning it
    // has had (smaller, less illumination, craters, a gradient rather than a flat fill) was spent on
    // something no one could see on the surface that matters.
    //
    // (0.070, 0.30) measures at 0.1% covered — the residue is a few star pixels inside the disc, not
    // chrome. The seat was chosen by scanning the shelf for a moon-sized patch with no content in it
    // rather than by eye, because the gutter left of the tiles is 170px wide and the moon is 169px across:
    // the margin here is single-digit pixels, which is not a thing to judge from a screenshot.
    //
    // Everything that depends on where the moon is follows it automatically — the cloud bank's per-lobe
    // tint and the reflection below both derive from `mx`/`my` rather than restating them, which is the
    // trap this file has hit twice before.
    val mx = size.width * 0.070f
    val my = size.height * 0.30f
    // The photographic moon reference is
    // CREAM, not the blue-white this palette's `highlight` gives, it is bigger than a pinprick, and its
    // maria are soft tonal patches rather than outlined circles. `moonBody` mixes a little of the scene's
    // warm `particle` into the cool highlight to get there without inventing a colour.
    val r = short * MoonRadiusFraction
    val moonBody = lerp(scene.highlight, scene.particle, MoonWarmth)
    // **Moon light scatter.**
    //
    // What stood here was a TWO-stop ramp, 0.30 alpha to transparent over 1.9 radii. A two-stop radial is a
    // linear ramp, and a linear ramp is the one falloff the sky never has: it holds half its brightness all
    // the way out to half its reach, which is why the earlier, larger version of this halo overlit the sky and had to be cut back to a collar. Cutting the reach treated the symptom — the shape was the
    // problem.
    //
    // Real scatter is steep then long: an aureole that is bright within a fraction of a radius of the limb,
    // then a faint tail that carries much further than the eye reads as "glow". The ladder below is that,
    // so the reach can go back out to [MoonScatterReach] radii WITHOUT washing the field — at the old
    // halo's own edge this is down to about 0.06 alpha, and everything past it is under 0.03. It is a wider
    // glow that is dimmer everywhere except right at the moon, which is the correction.
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to scene.highlight.copy(alpha = 0.40f),
            0.07f to scene.highlight.copy(alpha = 0.30f),
            0.14f to scene.highlight.copy(alpha = 0.17f),
            0.26f to scene.highlight.copy(alpha = 0.085f),
            0.44f to scene.highlight.copy(alpha = 0.036f),
            0.70f to scene.highlight.copy(alpha = 0.013f),
            1.00f to Color.Transparent,
            center = Offset(mx, my),
            radius = r * MoonScatterReach,
        ),
        radius = r * MoonScatterReach,
        center = Offset(mx, my),
    )
    // A gradient gives the moon depth rather than a flat fill.
    // The bright point sits toward the upper-left so the body has a lit limb and a dimmer far edge; a
    // disc filled with one colour reads as a hole punched in the sky whatever is drawn on top of it.
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to moonBody.copy(alpha = 0.99f),
            0.55f to moonBody.copy(alpha = 0.94f),
            0.88f to lerp(moonBody, scene.stagePrimary, 0.35f).copy(alpha = 0.86f),
            1.00f to scene.stagePrimary.copy(alpha = 0.70f),
            center = Offset(mx - r * 0.30f, my - r * 0.30f),
            radius = r * 1.45f,
        ),
        radius = r,
        center = Offset(mx, my),
    )
    // Texture, rather than five dots: A flat disc with a few even craters
    // still reads as a sticker. Real relief needs three things: a large dim MARE breaking the field so
    // the face is not uniform, craters at genuinely different sizes rather than one size repeated, and
    // a lit rim on each so they read as depressions catching the same light as the body.
    // Maria as SOFT patches. They used to be flat discs each wearing a bright `Stroke` rim, which is an
    // outlined circle however small -- the same "bright edge inside a radius reads as a drawn ring" defect
    // this file removed from the Well's sun and from Niflheim's cloud lobes. A mare is a tonal difference,
    // so it has no edge at all.
    for (c in MoonCraters) {
        val cc = Offset(mx + r * c[0], my + r * c[1])
        val cr = r * c[2] * MoonMareSpread
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to scene.stagePrimary.copy(alpha = c[3] * MoonMareDepth),
                0.55f to scene.stagePrimary.copy(alpha = c[3] * MoonMareDepth * 0.55f),
                1.00f to Color.Transparent,
                center = cc,
                radius = cr,
            ),
            radius = cr,
            center = cc,
        )
    }

    // **THE MOUNTAINS ARE GONE.** Four fjord ridges and their crests
    // previously formed a silhouette, with subtler crests and darkness extending to the footer.
    // The cloud bank replaces that silhouette.
    //
    // The bank is drawn AFTER the moon, so its tops pass in front of it rather than stopping politely
    // below. It cannot bury it: the tallest lobes are also the faintest ([MoonCloudThinning]), and the
    // bank's rise puts them just under the moon's own centre.
    val cloudBase = size.height * MoonCloudBase
    // **THE RIM PASS.** The shared Freyr rim-light technique: the
    // bank already tints its lobes toward the moon by proximity, but a tint is a wash — it never produces
    // an EDGE. So the identical lobe set is drawn once first, displaced toward the moon and in the moon's
    // own light, and the dark bank goes over it. The offset leaves a lit crescent exactly where a cloud
    // faces the moon and nowhere else.
    //
    // The method doc's caveat is satisfied and is why this works here and failed on Niflheim: the trick
    // needs OPAQUE geometry to occlude the copy underneath. These lobes qualify. The moon's own disc does
    // not need it and does not get it — its gradient already does that job.
    fun moonLobe(lobe: CloudLobe, lift: Float): Triple<Float, Float, Float> {
        val cluster = MoonCloudClusters[lobe.cluster]
        val half = size.width * cluster[2]
        return Triple(
            size.width * cluster[0] + lobe.dx * half,
            cloudBase + size.height * cluster[1] + lobe.dy * half * MoonCloudRise - size.height * lift,
            lobe.r * half,
        )
    }
    for (lobe in MoonCloudLobes) {
        val (x, y, r) = moonLobe(lobe, MoonRimLift)
        val toMoon = if (x > mx) -1f else 1f
        val dx = (x - mx) / size.width
        val dy = (y - my) / size.height
        val lit = (1f - sqrt(dx * dx + dy * dy) / MoonCloudLitReach).coerceIn(0f, 1f)
        if (lit <= 0.02f) continue
        abyssLobe(
            cx = x + toMoon * r * MoonRimLift * 2f,
            cy = y,
            r = r * 1.02f,
            wide = lobe.wide,
            color = scene.highlight.copy(
                alpha = MoonRimAlpha * lit * MoonCloudClusters[lobe.cluster][3] * lobe.alpha,
            ),
        )
    }
    for (lobe in MoonCloudLobes) {
        val cluster = MoonCloudClusters[lobe.cluster]
        val (x, y, r) = moonLobe(lobe, 0f)
        val dx = (x - mx) / size.width
        val dy = (y - my) / size.height
        val lit = (1f - sqrt(dx * dx + dy * dy) / MoonCloudLitReach).coerceIn(0f, 1f) * MoonCloudLitMax
        abyssLobe(
            cx = x,
            cy = y,
            r = r,
            wide = lobe.wide,
            color = lerp(scene.shadow, scene.highlight, lit)
                .copy(alpha = MoonCloudCoreAlpha * cluster[3] * lobe.alpha),
        )
    }
    // REMOVED,  A narrow vertical
    // band of `highlight` was painted from 0.80 of the height to the bottom, centred on the moon -- a
    // moonlight reflection, from when this scene had a fjord and water under it. Both went with the
    // mountains, so it was light on nothing: one bright column sitting under one side of the dock with
    // no counterpart anywhere else in the field. That produced a visible asymmetry.
    footer(scene)
    // Vignette raised to Freyr's depth -- the one lever here that needs no other geometry change.
    vignette(0.66f)
}

/** HEL · THE DREAD — bone-ash light behind hanging veils, and a wound low in the field. */
/**
 * One arch, drawn only as far as its COMPLETENESS allows before fog takes the rest.
 *
 * The completeness term is what stops the set reading as a repeated motif: a half-finished arch is a ruin,
 * six whole ones are a pattern.
 */
private fun DrawScope.helArch(arch: FloatArray, color: Color, alpha: Float, wide: Float) {
    val cx = size.width * arch[0]
    val spring = size.height * arch[1]
    val half = size.width * arch[2]
    val apex = spring - size.height * arch[3]
    val a = alpha * arch[4]
    val done = arch[6]
    // Three passes at one geometry, widest and faintest first. This is the Surtr fracture idiom and it is
    // the only soft edge available in a DrawScope -- `blur` does not exist here. A far arch is therefore a
    // BROAD faint band and a near one a narrow firm line, which is the covariance the stack was missing:
    // every arch used to be a 1.2 dp hairline whatever plane it stood on, so the planes differed in opacity
    // alone and read as one wall of wire at three brightnesses.
    val passes = arrayOf(
        floatArrayOf(wide, 0.34f),
        floatArrayOf(wide * 0.42f, 0.62f),
        floatArrayOf(wide * 0.155f, 1.00f),
    )
    val path = Path().apply {
        moveTo(cx - half, spring)
        quadraticBezierTo(cx - half, apex, cx, apex - size.height * 0.030f)
        if (done > 0.6f) quadraticBezierTo(cx + half, apex, cx + half, spring)
    }
    val sides = if (done > 0.6f) intArrayOf(-1, 1) else intArrayOf(-1)
    for (pass in passes) {
        drawPath(path, color = color.copy(alpha = a * pass[1]), style = Stroke(width = pass[0].dp.toPx()))
        // columns: the second one only exists if the arch got far enough to need it
        for (side in sides) {
            drawLine(
                brush = Brush.verticalGradient(
                    0f to color.copy(alpha = a * pass[1]),
                    1f to Color.Transparent,
                    startY = spring, endY = spring + size.height * 0.34f * done,
                ),
                start = Offset(cx + side * half, spring),
                end = Offset(cx + side * half, spring + size.height * 0.34f * done),
                strokeWidth = pass[0].dp.toPx(),
            )
        }
    }
}
private fun DrawScope.helArchMass(arch: FloatArray, void: Color) {
    val cx = size.width * arch[0]
    val spring = size.height * arch[1]
    val half = size.width * arch[2]
    val apex = spring - size.height * arch[3]
    for (side in intArrayOf(-1, 1)) {
        val x = cx + side * half
        drawRect(
            brush = Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.50f to void.copy(alpha = 0.52f * arch[4]),
                1.00f to Color.Transparent,
                startX = x - size.width * 0.022f, endX = x + size.width * 0.022f,
            ),
            topLeft = Offset(x - size.width * 0.022f, apex),
            size = Size(size.width * 0.044f, size.height - apex),
        )
    }
}

/** One mist plane. Same machinery as Freyr's cloud stack, narrower and more irregular. */
private fun DrawScope.helMist(plane: FloatArray, lobes: List<CloudLobe>, color: Color) {
    val short = min(size.width, size.height)
    val count = plane[1].toInt()
    for ((i, p) in lobes.withIndex()) {
        if (i >= count) break
        val x = size.width * (-0.08f + 1.16f * ((i + 0.5f) / count)) + p.dx * size.width * 0.06f
        val y = size.height * plane[0] + p.dy * size.height * 0.095f
        val r = short * (plane[2] + (plane[3] - plane[2]) * p.r)
        abyssLobe(x, y, r, p.wide, color.copy(alpha = color.alpha * plane[4] * p.alpha), plane[5])
    }
}

internal fun DrawScope.drawDreadSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene
    val short = min(size.width, size.height)
    // Palette equivalents for the direction's named colours. `particle` is the aged ivory, unchanged.
    val helVoid = scene.shadow
    val driedBlood = lerp(palette.accent, scene.shadow, 0.72f)
    val deepCrimson = lerp(palette.accent, scene.shadow, 0.50f)
    val bloodRed = lerp(palette.accent, scene.shadow, 0.15f)
    val scarlet = palette.accent
    val ember = palette.accentBright
    val bone = scene.particle
    // Structure is drawn in a tone lifted toward the ember, not in the crushed dried-blood. Measured on
    // the 2026-09-03 capture: driedBlood carries luminance 17/255, so a 3.4% plane delivers 0.58 -- under
    // one 8-bit step, and the far arches, the rose window and the haze columns were all mathematically
    // invisible. The 2-4 / 5-10 depth percentages describe how present each plane should FEEL; they are
    // a ladder, not literal alpha on a tone that is already 72% black. The ladder below rebases those percentages.
    val farStone = lerp(deepCrimson, ember, 0.30f)
    val midStone = lerp(deepCrimson, ember, 0.45f)

    // ---- 1. the void, with only a burgundy bias ------------------------------------------------------
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to helVoid,
            0.45f to lerp(helVoid, driedBlood, 0.30f),
            0.78f to lerp(helVoid, deepCrimson, 0.22f),
            1.00f to helVoid,
        ),
    )

    // ---- FAR: 2-4%, and it should almost disappear ---------------------------------------------------
    // The gothic geometry previously occupied one depth plane. Three
    // planes now, and the separation is in EVERY property at once -- opacity, scale, completeness and
    // sharpness -- which is the same covariance the cloud stacks use. The geometry should suggest a
    // cathedral subtly rather than invite examination of the pattern.
    //
    // The rose window is PARTIAL. Half of it is gone into fog, which
    // makes it read as a ruin rather than as a diagram: a complete symmetric figure invites reading.
    val rc = Offset(size.width * HelRoseX, size.height * HelRoseY)
    val rr = short * HelRoseR
    for (ring in 0 until 3) {
        if (ring == 1) continue // one ring simply missing -- decay, not damage
        drawCircle(
            color = farStone.copy(alpha = 0.105f - ring * 0.022f),
            radius = rr * (1f - ring * 0.28f), center = rc,
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
    for (k in 0 until HelRosePetals) {
        // only the petals from roughly one o'clock to seven survive; the rest are swallowed
        if (k > 6) continue
        val a = (k / HelRosePetals.toFloat()) * 2f * PI.toFloat() - PI.toFloat() * 0.35f
        val fade = 1f - (k / 6f) * 0.55f
        val inner = Offset(rc.x + rr * 0.28f * cos(a), rc.y + rr * 0.28f * sin(a))
        val outer = Offset(rc.x + rr * cos(a), rc.y + rr * sin(a))
        drawLine(farStone.copy(alpha = 0.098f * fade), inner, outer, strokeWidth = 1.4.dp.toPx())
        val mid = Offset(rc.x + rr * 0.72f * cos(a), rc.y + rr * 0.72f * sin(a))
        drawCircle(
            color = farStone.copy(alpha = 0.084f * fade),
            radius = rr * 0.115f, center = mid, style = Stroke(width = 1.4.dp.toPx()),
        )
    }
    // the far arches: enormous, and the least complete of the three planes
    for (arch in HelArches) {
        if (arch[5] != 0f) continue
        helArch(arch, farStone, 0.115f, wide = 11f)
    }
    // vertical haze -- light through impossible windows, not architecture
    for (h in HelHazeColumns) {
        val cx = size.width * h[0]
        val half = size.width * h[1]
        drawRect(
            brush = Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.50f to farStone.copy(alpha = 0.078f * h[4]),
                1.00f to Color.Transparent,
                startX = cx - half, endX = cx + half,
            ),
            topLeft = Offset(cx - half, size.height * h[2]),
            size = Size(half * 2f, size.height * (h[3] - h[2])),
        )
    }

    // ---- the crimson sources: three, overlapping, and deliberately off-centre -----------------------
    // Spatially unstable illumination: avoid an overly neat centre. Broad dried-blood
    // atmosphere, a main source at 0.605 rather than the middle, and a small hotspot well away from both.
    for (isl in HelIslands) {
        val c = Offset(size.width * isl[0], size.height * isl[1])
        val r = size.width * isl[2]
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to scarlet.copy(alpha = isl[3]),
                0.34f to deepCrimson.copy(alpha = isl[3] * 0.55f),
                0.70f to driedBlood.copy(alpha = isl[3] * 0.22f),
                1.00f to Color.Transparent,
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
    }

    // ---- MID: 5-10%, fog and partial tracery --------------------------------------------------------
    helMist(HelMistFar, HelMistFarLobes, driedBlood)
    helMist(HelMistMid, HelMistMidLobes, deepCrimson)
    for (arch in HelArches) {
        if (arch[5] != 1f) continue
        helArch(arch, midStone, 0.185f, wide = 5.2f)
    }

    // ---- OCCLUSION: black over the crimson, so red leaks BETWEEN structures --------------------------
    // The Surtr lesson stated the other way round. There the light went under the crust; here dark mass
    // goes over the light. Either way the rule is the same one: red painted onto black is a background,
    // red seen past something is depth.
    helMist(HelMistNear, HelMistNearLobes, lerp(helVoid, driedBlood, 0.18f))
    for (arch in HelArches) {
        if (arch[5] != 0f) continue
        helArchMass(arch, helVoid)
    }

    // ---- 6. vein-gothic: continuous, viewport-space, tile-blind --------------------------------------
    for ((pi, path) in HelVeins.withIndex()) {
        var i = 0
        var seg = 0
        while (i + 3 < path.size) {
            val swell = HelVeinSwell.any { it[0] == pi && it[1] == seg }
            val p0 = Offset(size.width * path[i], size.height * path[i + 1])
            val p1 = Offset(size.width * path[i + 2], size.height * path[i + 3])
            drawLine(driedBlood.copy(alpha = 0.055f), p0, p1, strokeWidth = 6f.dp.toPx(), cap = StrokeCap.Round)
            drawLine(
                (if (swell) bloodRed else midStone).copy(alpha = if (swell) 0.200f else 0.125f),
                p0, p1, strokeWidth = (if (swell) 1.9f else 1.1f).dp.toPx(), cap = StrokeCap.Round,
            )
            i += 2; seg++
        }
    }
    // elongated diamonds where veins meet -- gothic tracery rather than a circuit node
    for (n in HelNodes) {
        val c = Offset(size.width * n[0], size.height * n[1])
        val w = size.width * n[2] * 0.42f
        val h = size.height * n[2]
        drawPath(
            Path().apply {
                moveTo(c.x, c.y - h); lineTo(c.x + w, c.y); lineTo(c.x, c.y + h); lineTo(c.x - w, c.y); close()
            },
            color = midStone.copy(alpha = 0.150f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    // ---- 7. filaments: finer than veins, one section of each briefly brighter -----------------------
    for (f in HelFilaments) {
        val a = Offset(size.width * f[0], size.height * f[1])
        val b = Offset(size.width * f[2], size.height * f[3])
        val c = Offset(size.width * f[4], size.height * f[5])
        drawLine(driedBlood.copy(alpha = 0.048f), a, b, strokeWidth = 0.9.dp.toPx(), cap = StrokeCap.Round)
        drawLine(scarlet.copy(alpha = 0.190f * f[6]), b, c, strokeWidth = 0.9.dp.toPx(), cap = StrokeCap.Round)
    }

    // ---- 8. no ivory ------------------------------------------------------------------------------
    // The former bone glyph occupied 1-2% of the visual palette but conflicted with the scene.
    // Hel is crimson and black and carries no
    // ivory. Do not reintroduce one; the ash motes below are the only light-toned thing in the scene and
    // they are dust, not a mark.

    // ---- 9. a darker mist OVER everything -------------------------------------------------------------
    // A second darker fog layer sits over the red. It is what
    // stops the crimson reading as emitted light and makes it read as blood seen THROUGH something.
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to helVoid.copy(alpha = 0.20f),
            0.42f to Color.Transparent,
            0.72f to helVoid.copy(alpha = 0.22f),
            1.00f to helVoid.copy(alpha = 0.72f),
        ),
    )
    // Ash, not embers -- tiny ash and dust motes. Dust in a cold room; an ember would imply a fire, and
    // there is no fire in Hel, only what is left of one.
    for (m in HelAsh) {
        drawCircle(
            color = lerp(bone, driedBlood, 0.45f).copy(alpha = m[3] * 0.5f),
            radius = short * m[2], center = Offset(size.width * m[0], size.height * m[1]),
        )
    }
    footer(scene)
    vignette(0.70f)
}

/** NIFLHEIM · THE ABYSS — mist columns in a void, and a horizon that will not resolve. */
internal fun DrawScope.drawVoidSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene
    // The sheet draws Niflheim as the emptiest of the nine: a floor running to a horizon that GLOWS,
    // and almost nothing standing on it. The mist columns stay, but they are behind the floor now —
    // the plane is what makes the emptiness readable as distance rather than as a blank field.
    horizonGlow(0.50f, 0.50f, scene.stagePrimary, 0.34f + pulse * 0.06f, 0.62f)
    for (col in VoidColumns) {
        val half = size.width * col[1]
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.5f to scene.stagePrimary.copy(alpha = 0.20f * col[2]),
                1.0f to Color.Transparent,
                startX = size.width * col[0] - half,
                endX = size.width * col[0] + half,
            ),
            topLeft = Offset(size.width * col[0] - half, 0f),
            size = Size(half * 2f, size.height * 0.52f),
        )
    }
    perspectiveFloor(0.50f, 0.50f, VoidRails, VoidCourses, scene.stagePrimary, 0.05f, 0.30f)
    // The abyss eats the horizon.  Drawn AFTER the floor on purpose — the rails converge into the bank and
    // stop being visible, which is what "engulfing" means; drawn before it, the floor would run over the
    // clouds and they would read as haze behind a grid.
    val hy = size.height * 0.50f
    // ONE PASS NOW, and the rim went with the second one. The previous version drew a light pass behind
    // the mass so an occluded crescent survived as a lit edge; that needed the mass to be nearly opaque to
    // do the occluding, and near-opaque lobes are exactly what read as cartoon. With [VoidCloudCoreAlpha]
    // down at 0.26 the mass cannot occlude anything, so a light pass would simply glow through the whole
    // bank as haze.
    //
    // Nothing is lost: `horizonGlow` is already drawn BEHIND this bank, so the thin places let it through
    // and the dense places stop it. That is the same backlight the two-pass version was imitating, except
    // the cloud's own varying density does the work instead of a displaced copy of itself.
    for (lobe in VoidCloudLobes) {
        val cluster = VoidCloudClusters[lobe.cluster]
        val half = size.width * cluster[2]
        abyssLobe(
            cx = size.width * cluster[0] + lobe.dx * half,
            cy = hy + size.height * cluster[1] + lobe.dy * half * VoidCloudRise,
            r = lobe.r * half,
            wide = lobe.wide,
            color = scene.shadow.copy(alpha = VoidCloudCoreAlpha * cluster[3] * lobe.alpha),
        )
    }
    // Remove all three eyes-of-the-abyss dots. Their measured local-background contrast was
    // +123, +70 and +27, so removing only the brightest would leave the same defect in the others.
    // `scene.highlight` on this
    // palette is near-white ice, which is why a 0.009 radius dot read as a flat grey ball rather than
    // something watching from a distance. Nothing replaces them; the bank above is what the field carries
    // now, and `drawAmbientBreath`'s VOID branch that used to swell them is dead anyway
    // (`AmbientBreathEnabled = false`).
    specks(MoonStars, scene.particle, 0.9f)
    footer(scene)
    vignette(0.62f)
}

/** YGGDRASIL · THE ROOTED WELL — roots climbing out of the floor, canopy light coming down. */
/**
 * A smooth path THROUGH a chain of points, rather than to hand-authored control points.
 *
 * Catmull-Rom converted to cubic Bézier: each span's control points are derived from its neighbours, so
 * the curve passes exactly through every authored point and its curvature is a consequence of where the
 * points are. Authoring Bézier handles by eye is how you get a shape that looks drawn; this is the same
 * discipline the perspective doc asks for — derive it, do not nudge it.
 */
private fun DrawScope.rootPath(pts: FloatArray, from: Int = 0, to: Int = -1): Path {
    val n = pts.size / 2
    val last = if (to < 0) n - 1 else min(to, n - 1)
    val path = Path()
    if (n < 2 || from >= last) return path
    fun px(i: Int) = size.width * pts[2 * i.coerceIn(0, n - 1)]
    fun py(i: Int) = size.height * pts[2 * i.coerceIn(0, n - 1) + 1]
    path.moveTo(px(from), py(from))
    for (i in from until last) {
        val x0 = px(i - 1); val y0 = py(i - 1)
        val x1 = px(i); val y1 = py(i)
        val x2 = px(i + 1); val y2 = py(i + 1)
        val x3 = px(i + 2); val y3 = py(i + 2)
        path.cubicTo(
            x1 + (x2 - x0) / 6f, y1 + (y2 - y0) / 6f,
            x2 - (x3 - x1) / 6f, y2 - (y3 - y1) / 6f,
            x2, y2,
        )
    }
    return path
}

/** A point on the Catmull-Rom chain at [u], measured in SEGMENT units (0 = first point). */
private fun DrawScope.rootAt(pts: FloatArray, u: Float): Offset {
    val n = pts.size / 2
    val i = u.toInt().coerceIn(0, n - 2)
    val t = (u - i).coerceIn(0f, 1f)
    fun px(k: Int) = size.width * pts[2 * k.coerceIn(0, n - 1)]
    fun py(k: Int) = size.height * pts[2 * k.coerceIn(0, n - 1) + 1]
    val t2 = t * t
    val t3 = t2 * t
    fun spline(a: Float, b: Float, c: Float, d: Float): Float =
        0.5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
    return Offset(
        spline(px(i - 1), px(i), px(i + 1), px(i + 2)),
        spline(py(i - 1), py(i), py(i + 1), py(i + 2)),
    )
}

/** Deterministic in [0,1) from two ints — the wobble that stops a root being an even ribbon. */
private fun yggNoise(a: Int, b: Int): Float {
    val v = sin(a * 12.9898f + b * 78.233f) * 43758.547f
    return v - kotlin.math.floor(v)
}

/**
 * One root, SAMPLED rather than stroked.
 *
 * The previous smooth, uniformly glowing strokes made the network read as energy ribbons. A single `drawPath` with one stroke width can only ever produce a ribbon,
 * however organic the curve under it is. Walking the curve instead lets thickness, bark alpha and sap all
 * vary ALONG the path, which is the whole difference between a root and a neon line.
 *
 * - **thickness**: tapers toward both ends and carries a per-root wobble, so no two spans match
 * - **sap**: a bell centred on [sapU] — it rises, peaks and falls inside the stroke
 * - **[fadeTail]**: the branch does not end, it stops being visible, which is what "disappears into
 *   darkness" has to mean when there is nowhere for it to go
 */
private fun DrawScope.yggRoot(
    pts: FloatArray,
    ri: Int,
    bark: Color,
    sap: Color,
    sapCore: Color,
    baseWide: Float,
    alpha: Float,
    sapU: Float = -1f,
    sapSpan: Float = 0.7f,
    fadeTail: Boolean = false,
    alphaAt: (Offset) -> Float = { 1f },
) {
    val n = pts.size / 2
    val steps = (n - 1) * 22
    var prev = rootAt(pts, 0f)
    for (step in 1..steps) {
        val u = (step.toFloat() / steps) * (n - 1)
        val here = rootAt(pts, u)
        val t = u / (n - 1)
        // thin at both ends, full through the middle, plus an irregular wobble
        val taper = 0.42f + 0.58f * sin(PI.toFloat() * t.coerceIn(0f, 1f))
        val wobble = 0.74f + 0.34f * yggNoise(ri * 17, (u * 3.1f).toInt())
        val tail = if (fadeTail) (1f - t).pow(1.6f) else 1f
        val w = baseWide * taper * wobble * (0.55f + 0.45f * tail)
        val a = alpha * tail * alphaAt(here)
        if (a > 0.002f) {
            drawLine(bark.copy(alpha = a * 0.34f), prev, here, strokeWidth = w.dp.toPx(), cap = StrokeCap.Round)
            drawLine(bark.copy(alpha = a), prev, here, strokeWidth = (w * 0.38f).dp.toPx(), cap = StrokeCap.Round)
        }
        if (sapU >= 0f) {
            val d = abs(u - sapU) / sapSpan
            if (d < 1f) {
                // cosine bell: no edge anywhere, so the sap has no visible beginning or end
                val bell = 0.5f + 0.5f * cos(PI.toFloat() * d)
                drawLine(
                    sap.copy(alpha = 0.17f * bell * tail),
                    prev, here, strokeWidth = (w * 0.62f).dp.toPx(), cap = StrokeCap.Round,
                )
                drawLine(
                    sapCore.copy(alpha = 0.26f * bell * bell * tail),
                    prev, here, strokeWidth = (w * 0.20f).dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        prev = here
    }
}

/** A knot where roots meet — a bark swelling, deliberately lopsided so it is not a dot. */
private fun DrawScope.yggKnot(c: Offset, r: Float, bark: Color, sap: Color, presence: Float) {
    drawCircle(bark.copy(alpha = 0.42f * presence), radius = r, center = c)
    drawCircle(bark.copy(alpha = 0.30f * presence), radius = r * 0.78f, center = c + Offset(r * 0.42f, -r * 0.26f))
    drawCircle(bark.copy(alpha = 0.24f * presence), radius = r * 0.62f, center = c + Offset(-r * 0.34f, r * 0.30f))
    drawCircle(sap.copy(alpha = 0.16f * presence), radius = r * 0.34f, center = c)
}

/**
 * YGGDRASIL · THE ROOT SYSTEM — the interface sits somewhere inside the network that joins the Nine Worlds.
 *
 * Scene correction: keep the olive/earth/old-gold palette and the UI; the correction is
 * ENVIRONMENTAL GEOMETRY. What was here drew six strictly vertical full-height trunks; several passed
 * behind the selected tile and continued into the dock row, making the composition read as a hub-and-spoke UI diagram rather than an environment.
 *
 * **The alpha figures are a relative ladder, not literal opacities.** The trunk axis is 2–4%, and
 * 3% of a tone already crushed toward the ground delivers under one 8-bit step — the exact error that
 * made Hel's whole depth pass invisible earlier the same day. The axis is drawn in `accent`
 * (luminance ~142/255) so 3% composites to ~4 luma over a ~10 luma floor: present, deniable, and
 * actually there.
 */
internal fun DrawScope.drawRootedWellSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene
    val moss = palette.materials.counterAccent
    val bark = lerp(moss, scene.shadow, 0.74f)
    val sap = palette.accent
    val sapCore = lerp(palette.accent, scene.highlight, 0.32f)
    val short = min(size.width, size.height)

    // ---- soil: dark moss haze -> olive-black -> peat -------------------------------------------------
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to lerp(scene.shadow, moss, 0.115f),
            0.42f to lerp(scene.shadow, moss, 0.070f),
            0.72f to lerp(scene.shadow, moss, 0.030f),
            1.00f to scene.shadow,
        ),
    )

    // ---- the trunk axis ------------------------------------------------------------------------------
    // The central trunk gains 10-15% visibility near the top and vanishes toward the shelf.
    // Only the CENTRAL run ramps; the four splaying limbs keep their flat presence, so
    // the emphasis reads as the trunk rising rather than as the whole figure brightening.
    for ((ai, run) in YggAxis.withIndex()) {
        val central = ai == 0
        val ramp: (Offset) -> Float = if (!central) {
            { 1f }
        } else {
            { p -> (1f - (p.y / size.height) / 0.34f).coerceIn(0f, 1f) * 0.85f + 0.32f }
        }
        for (pass in 0..1) {
            //  Was
            // 0.085 / 0.028 of the short side -- 92 px and 30 px on a 1080 panel, which is a column, not
            // a vine. Alpha is deliberately NOT raised to compensate: a narrower stroke carrying the same
            // alpha is genuinely fainter, preserving the intended fainter appearance.
            val wide = if (pass == 0) short * 0.030f else short * 0.0095f
            val base = if (pass == 0) 0.030f else 0.022f
            val n = run.size / 2
            var prev = rootAt(run, 0f)
            val steps = (n - 1) * 16
            for (step in 1..steps) {
                val here = rootAt(run, (step.toFloat() / steps) * (n - 1))
                val a = base * ramp(here) * (if (central) 1.14f else 1f)
                drawLine(sap.copy(alpha = a), prev, here, strokeWidth = wide, cap = StrokeCap.Round)
                prev = here
            }
        }
    }

    // ---- the root network ----------------------------------------------------------------------------
    val sapFor = YggSap.associate { it[0].toInt() to Pair(it[1], it[2]) }
    for ((ri, run) in YggRoots.withIndex()) {
        val depth = 1f - (ri % 3) * 0.24f
        val s0 = sapFor[ri]
        yggRoot(
            pts = run, ri = ri, bark = bark, sap = sap, sapCore = sapCore,
            baseWide = 9f * depth, alpha = 0.30f * depth,
            sapU = s0?.first ?: -1f, sapSpan = s0?.second ?: 0.7f,
        )
    }
    // Branches: thinner than their parent, and the ones flagged 1 stop being visible rather than ending.
    for ((bi, br) in YggBranches.withIndex()) {
        val fades = br[0] > 0.5f
        val pts = FloatArray(br.size - 1) { br[it + 1] }
        yggRoot(
            pts = pts, ri = 40 + bi, bark = bark, sap = sap, sapCore = sapCore,
            baseWide = 4.6f, alpha = 0.24f, fadeTail = fades,
        )
    }

    // ---- knots where roots meet ----------------------------------------------------------------------
    for (j in YggJunctions) {
        yggKnot(
            c = Offset(size.width * j[0], size.height * j[1]),
            r = short * j[2] * 0.92f,
            bark = bark, sap = sap, presence = j[3],
        )
    }

    wash(0.50f, 0.02f, 0.44f, scene.highlight.copy(alpha = 0.16f + pulse * 0.04f))
    // Pollen rather than Máni's borrowed star table: warmer ink, far more of it, and a brightness
    // distribution that leaves most of it barely there.
    specks(YggPollen, scene.particle, 0.62f)
    // ---- the Nine Worlds ----
    // Exactly nine motes are somewhat larger than the ambient dust, without glow or labels.
    // Draw them after the dust in the same ink so they remain part of the field, with no halo.
    for (j in YggJunctions) {
        drawCircle(
            color = scene.particle.copy(alpha = 0.34f + 0.20f * j[3]),
            radius = 2.1f.dp.toPx(),
            center = Offset(size.width * j[0], size.height * j[1]),
        )
    }
    footer(scene)
    vignette(0.62f)
}

/**
 * FÓLKVANGR · THE FIELD OF THE SLAIN — cold light on planted shafts. Dark field: see the class KDoc.
 *
 * Drawn far row first, so a near spear occludes the one behind it rather than the other way round. Each
 * shaft is two strokes, not one: a dark body and a **narrow** lit edge on the side facing
 * [FieldLightX]/[FieldLightY]. The narrowness is what separates metal from a coloured line — the same
 * rule the retired necklace's five bands were built on, kept at the width this scale can actually carry.
 */
internal fun DrawScope.drawFolkvangrSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene

    // (1) SKY AND GROUND in one pass. The stops are placed at [FieldHorizon] so the ground begins exactly
    // where the spears are planted; a separate sky rect would need its own height and could drift from it.
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to scene.shadow,
            FieldHorizon * 0.70f to scene.stagePrimary.copy(alpha = 0.26f),
            FieldHorizon to scene.highlight.copy(alpha = 0.13f + pulse * 0.02f),
            1.00f to scene.railTrough,
        ),
    )

    // (2) The low cold light, sitting ON the horizon rather than above it: this field has no sun, only
    // the direction one would have been.
    wash(FieldLightX, FieldLightY, 0.46f, palette.accent.copy(alpha = 0.15f + pulse * 0.03f))
    wash(FieldLightX, FieldHorizon, 0.24f, scene.highlight.copy(alpha = 0.12f + pulse * 0.03f))

    // (3) The horizon itself: one quiet line, because a gradient alone reads as fog rather than as ground.
    drawLine(
        color = scene.highlight.copy(alpha = 0.16f),
        start = Offset(0f, size.height * FieldHorizon),
        end = Offset(size.width, size.height * FieldHorizon),
        strokeWidth = 1f.dp.toPx(),
    )

    // (4) THE FIELD.
    val nearHeight = size.height * 0.30f
    for (spear in FieldSpears) {
        val t = spear[1]
        val u = groundU(t)
        val baseY = size.height * (FieldHorizon + (1f - FieldHorizon) * u)
        val baseX = size.width * spear[0]
        val height = nearHeight * u * spear[3]
        if (height < 1.5f) continue

        val tipX = baseX + height * spear[2]
        val tipY = baseY - height
        // Depth fade: far shafts are thinner, dimmer and closer to the sky they stand against.
        val width = (0.6f + 2.0f * u).dp.toPx()
        val alpha = 0.22f + 0.62f * u

        drawLine(
            color = scene.shadow.copy(alpha = alpha),
            start = Offset(baseX, baseY),
            end = Offset(tipX, tipY),
            strokeWidth = width,
        )
        // The lit edge, offset toward the light and a fraction of the body's width.
        val lightSide = if (baseX > size.width * FieldLightX) -1f else 1f
        drawLine(
            color = palette.accent.copy(alpha = alpha * 0.85f),
            start = Offset(baseX + lightSide * width * 0.30f, baseY),
            end = Offset(tipX + lightSide * width * 0.30f, tipY),
            strokeWidth = width * 0.34f,
        )
        // The head: a short, brighter segment. At this scale a blade reads as a thickening, not a shape.
        drawLine(
            color = scene.highlight.copy(alpha = alpha * 0.9f),
            start = Offset(tipX, tipY),
            end = Offset(tipX + height * spear[2] * 0.18f, tipY + height * 0.13f),
            strokeWidth = width * 1.45f,
        )
    }

    specks(FieldAsh, scene.particle, 0.5f)
    footer(scene)
    vignette(0.66f)
}

/** BALDR · SUNLIT — first light on cold metal. Dark field: see the class KDoc. */
/** One of Baldr's planes. Same shape as Freyr's, with `firm` standing in for a blur radius. */
private fun DrawScope.baldrCloudPlane(
    plane: FloatArray,
    lobes: List<CloudLobe>,
    color: Color,
    lift: Float = 0f,
) {
    val short = min(size.width, size.height)
    val baseY = size.height * plane[0] - size.height * lift
    val count = plane[1].toInt()
    for ((i, p) in lobes.withIndex()) {
        if (i >= count) break
        val x = size.width * (-0.06f + 1.12f * ((i + 0.5f) / count)) + p.dx * size.width * 0.055f
        val y = baseY + p.dy * size.height * 0.085f
        val r = short * (plane[2] + (plane[3] - plane[2]) * p.r)
        abyssLobe(x, y, r, p.wide, color.copy(alpha = color.alpha * plane[4]), plane[5])
    }
}

internal fun DrawScope.drawSunlitSceneV2(palette: MimirThemePalette, pulse: Float = 0f) {
    val scene = palette.materials.scene
    val short = min(size.width, size.height)
    // The sky's own ground, lifted off pure shadow. The reference is HIGH KEY — pale everywhere, with the
    // darkest value in it still a mid grey-lavender — so a near-black ceiling was fighting the pastel below
    // it and putting a hard value break across the middle of the field.
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to lerp(scene.shadow, palette.accent, 0.30f),
            0.34f to lerp(scene.shadow, palette.accent, 0.16f).copy(alpha = 0.72f),
            0.62f to Color.Transparent,
        ),
    )
    // **A SUNRISE WITH WEATHER IN IT.**
    //
    //  The aurora is gone entirely — both colour fields, the six mirrored
    // curtains and every constant that fed them. What stays from those rounds is the part that was never
    // borealis: the three cloud tiers and the tonal horizon, both independent of aurora and
    // neither of which depends on an aurora being in the sky.
    //
    // The sunrise itself is restored as it was: a warm sky graded from the horizon up, light pooling along
    // the horizon and spreading sideways, a broad low bloom, and a disc whose limb dissolves rather than
    // ending. Nothing draws the horizon as a line — the light gets denser toward it, and the ground below
    // is simply unlit. Preserve the tonal horizon rather than drawing a line.
    val hy = size.height * BaldrHorizon
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to Color.Transparent,
            0.30f to lerp(scene.shadow, palette.accent, 0.45f).copy(alpha = 0.10f),
            0.50f to lerp(palette.accent, scene.particle, 0.30f).copy(alpha = 0.16f),
            0.66f to lerp(palette.accent, scene.particle, 0.62f).copy(alpha = 0.26f),
            BaldrHorizon to lerp(scene.particle, scene.highlight, 0.34f).copy(alpha = 0.40f),
            0.90f to lerp(palette.accent, scene.particle, 0.40f).copy(alpha = 0.16f),
            1.00f to Color.Transparent,
        ),
    )
    // Baldr's own stars. It used to draw [MoonStars], Máni's table -- retuning that sky would have moved
    // this one silently, with no gate to catch it. Same hazard class as the frozen `cloudLobes` draw order.
    specks(BaldrStars, scene.highlight, 0.55f)
    // Freyr's ember treatment, on the few brightest only. It is the cheapest thing in that scene and most
    // of why its field reads as enormous -- a point with a faint halo has a size, and a bare dot does not.
    for (star in BaldrStars) {
        if (star.alpha < 0.44f) continue
        val c = Offset(size.width * star.x, size.height * star.y)
        val r = min(size.width, size.height) * 0.016f
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to scene.highlight.copy(alpha = star.alpha * 0.22f),
                1.00f to Color.Transparent,
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )
    }
    // Light pooling along the horizon and spreading sideways — the cue a drawn rule was reaching for.
    // Wide and short, so it never resolves into an edge.
    // Light pooling along the horizon. Drawn as a CIRCULAR gradient inside a `scale`, not as a circular
    // gradient clipped by an oval -- that was cutting a hard 1200px arc across the field, because the
    // oval's vertical half-extent is only 0.24 of the brush radius, so the shape truncated its own gradient
    // at alpha 0.225. Measurement found the step at 4.8-8.3x the ambient gradient rate. `abyssLobe`'s KDoc
    // documents this exact hazard and its fix; the comment that used to sit here claimed the shape "never
    // resolves into an edge" while it was resolving into one.
    run {
        val poolR = size.height * 0.20f
        val poolC = Offset(size.width * BaldrSunX, hy)
        scale(scaleX = (size.width * 0.62f) / poolR, scaleY = 1f, pivot = poolC) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to scene.particle.copy(alpha = 0.30f),
                    0.45f to scene.particle.copy(alpha = 0.16f),
                    0.78f to scene.particle.copy(alpha = 0.05f),
                    1.00f to Color.Transparent,
                    center = poolC,
                    radius = poolR,
                ),
                radius = poolR,
                center = poolC,
            )
        }
    }
    // The bloom, then the body. Both monotonic from the centre outward, and the body is ONE shape rather
    // than a core plus a bounded halo -- the ring this file keeps re-shipping comes from a SEAM between two
    // shapes, not from a small radius.
    wash(BaldrSunX, BaldrHorizon, 0.24f, scene.particle.copy(alpha = 0.44f + pulse * 0.06f))
    val sunR = short * BaldrSunRadius
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to lerp(scene.highlight, scene.particle, 0.30f).copy(alpha = 0.72f),
            0.30f to scene.particle.copy(alpha = 0.58f),
            0.58f to scene.particle.copy(alpha = 0.32f),
            0.80f to lerp(scene.particle, palette.accent, 0.25f).copy(alpha = 0.13f),
            1.00f to Color.Transparent,
            center = Offset(size.width * BaldrSunX, hy),
            radius = sunR,
        ),
        radius = sunR,
        center = Offset(size.width * BaldrSunX, hy),
    )
    // FAR tier: distant cloud just above the horizon, lit from behind and hazing toward the sky rather
    // than silhouetting against it. Pale-far and dark-near is the depth cue; sizes alone are not.
    // **NO CLOUDS. **
    //
    // Six rounds and three separate primitives went through here -- soft gradient washes, filled silhouette
    // paths, then solid-bodied cumulus lobes -- and the whole apparatus is gone with this scene revision: the
    // cluster tables, the puff tables, the two painters and their constants. Removed rather than left
    // switched off, so nothing here is a decoy for the next reader.
    //
    // Baldr is its sky again: a warm ramp from the horizon up, light pooling along the horizon and
    // spreading sideways, a sun whose limb dissolves, and stars above. `cloudLobes`, `CloudLobe` and
    // `abyssLobe` all survive -- Niflheim's bank is their remaining caller and is signed off.
    // The horizon: lit sky above, ground below, handed over across a wide band rather than stepped.
    drawRect(
        brush = Brush.verticalGradient(
            BaldrHorizon - BaldrHorizonEdge to Color.Transparent,
            BaldrHorizon - BaldrHorizonEdge * 0.45f to scene.shadow.copy(alpha = 0.18f),
            BaldrHorizon to scene.shadow.copy(alpha = 0.42f),
            BaldrHorizon + BaldrHorizonEdge * 0.6f to scene.shadow.copy(alpha = 0.62f),
            1.00f to scene.shadow.copy(alpha = BaldrGroundDepth),
        ),
    )
    // ---- the cloud stack, on Freyr's composition ----------------------------------------------------
    // Everything above this line is the SOURCE -- sky ramp, horizon pool, the sun. Everything below is
    // what stands in front of it. That ordering IS the change: the sun finally has something to be behind,
    // providing an occluding layer for depth.
    //
    // Tonal direction inverted from Freyr's: pale masses against a lit sky rather than dark masses against
    // black. The structure transfers, the palette does not.
    // Sunrise logic, which is the hall's inverted: FAR cloud catches the low sun and goes pale-warm,
    // NEAR cloud has the light behind it and silhouettes dark. Freyr's ladder, Baldr's direction.
    val litFar = lerp(scene.particle, scene.highlight, 0.42f)
    val litMid = lerp(scene.particle, palette.accent, 0.30f)
    val rimTone = lerp(scene.highlight, scene.particle, 0.25f)
    val darkNear = lerp(palette.accent, scene.shadow, 0.66f)
    val darkDeep = lerp(palette.accent, scene.shadow, 0.82f)
    baldrCloudPlane(BaldrHazePlane, BaldrHazeLobes, litFar)
    baldrCloudPlane(BaldrDistantPlane, BaldrDistantLobes, lerp(litFar, litMid, 0.55f))
    baldrCloudPlane(BaldrMidPlane, BaldrMidLobes, litMid)
    // The derived rung. Its tone is the same interpolation as its geometry: half way between the mid
    // plane's lit warm and the near plane's silhouette, because this is where the light stops reaching
    // round the cloud and the mass starts turning away from it.
    baldrCloudPlane(BaldrNearPlane, BaldrNearLobes, lerp(litMid, darkNear, 0.50f))
    // THE RIM, paired with the foreground over one table and pinned by `MimirRimPairingTest`.
    baldrCloudPlane(BaldrRimPlane, BaldrRimLobes, rimTone, lift = BaldrRimLift)
    baldrCloudPlane(BaldrForePlane, BaldrRimLobes, darkNear)
    // DEEP: the plane that was missing. Densest and tightest, and its job is to give the pack a bottom --
    // without it the individual lobes stay readable as lobes however many are added above.
    baldrCloudPlane(BaldrDeepPlane, BaldrDeepLobes, darkDeep)
    footer(scene)
    // Vignette raised toward Freyr's, the shared depth adjustment on both scenes.
    vignette(0.64f)
}

/**
 * The v2 dispatcher. This replaces `drawThemeScene`'s nine arms for this one call — see the class KDoc.
 * `showAtmosphericClouds` is accepted so the signature matches the site it replaces; v2 has no cloud
 * layer to gate (the banks in SUNLIT and the veils in DREAD are scene structure, not weather), so it
 * is deliberately unused and must be removed from the call site when the old painters are deleted.
 */
internal fun DrawScope.drawThemeSceneV2(
    palette: MimirThemePalette,
    @Suppress("UNUSED_PARAMETER") showAtmosphericClouds: Boolean = true,
    pulse: Float = 0f,
    quietSceneLight: Boolean = false,
    sceneLightBelowText: Boolean = false,
    shineStars: Boolean = true,
    sceneLight: Boolean = true,
) {
    when (palette.materials.scene.atmosphere) {
        MimirAtmosphereKind.WELL -> drawWellSceneV2(
            palette,
            pulse,
            quietLight = quietSceneLight,
            lightBelowText = sceneLightBelowText,
            shineStars = shineStars,
            sceneLight = sceneLight,
        )
        MimirAtmosphereKind.GOLDEN_HALL -> drawGoldenHallSceneV2(palette, pulse)
        MimirAtmosphereKind.FORGE -> drawForgeSceneV2(palette, pulse)
        MimirAtmosphereKind.MOONLIT -> drawMoonlitSceneV2(palette, pulse)
        MimirAtmosphereKind.DREAD -> drawDreadSceneV2(palette, pulse)
        MimirAtmosphereKind.VOID -> drawVoidSceneV2(palette, pulse)
        MimirAtmosphereKind.ROOTED_WELL -> drawRootedWellSceneV2(palette, pulse)
        MimirAtmosphereKind.FOLKVANGR -> drawFolkvangrSceneV2(palette, pulse)
        MimirAtmosphereKind.SUNLIT -> drawSunlitSceneV2(palette, pulse)
    }
}
