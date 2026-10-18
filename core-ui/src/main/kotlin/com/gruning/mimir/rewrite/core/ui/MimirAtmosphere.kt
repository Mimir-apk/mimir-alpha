package com.gruning.mimir.rewrite.core.ui

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// COMPOSE-WIN ambient-breath tuning. Half-period = one direction of the sine; RepeatMode.Reverse doubles it
// to a full ~6.8s breath. Idle timeout settles the breath after a quiet navigation gap (protects OLED battery).
private const val AmbientBreathHalfPeriodMs = 3400
private const val AmbientIdleTimeoutMs = 14_000L

/**
 * Mimir Alpha :core-ui — the shelf ATMOSPHERE.
 *
 * The console "lit room" behind the coverflow. Each theme owns a scene and a surface texture; Default's
 * architectural lattice, Freyr's weave, Surtr's forged striations, Mani's starlight, Hel's engulfing shadow, the Void,
 * Yggdrasil's roots, Folkvangr's paper bloom, and Baldr's open sky do not share one recolored grid.
 *
 * Two public surfaces:
 *  - [MimirShelfBackground] — the full atmospheric field (grid + stars + ambient wash) as a Box wrapper.
 *  - [BeamSeparator] — the shipping `.eok-sep` beam rule: a thin themed gradient line
 *    (transparent → accent → transparent) with a centered rotated-square diamond node.
 *
 * PERF : everything here is a single [drawBehind] / Canvas pass over
 * gradients + a handful of primitives — NO bitmaps, NO per-frame allocation on the hot path (the starfield
 * layout is computed ONCE and remembered), and NO layout thrash. The field is static; it does not react to
 * focus, so a D-pad sweep never re-runs this draw.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// TUNING — all provisional, owed a Thor/RP5 legibility pass
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Grid cell size — a faint architectural lattice, not a dense mesh. */
private val GridCell: Dp = 44.dp

/** How far DEFAULT's lattice is taken below its material's own alpha.  */
private const val LatticeAlphaScale = 0.5f

/** How many static starfield dots. Sparse: a few sky specks, never a Milky Way. */
private const val StarCount = 26

/** Star dot alpha ceiling — dim; a couple read, most are near-subliminal. */
private const val StarAlphaMax = 0.16f
private const val StarAlphaMin = 0.05f

/** Deterministic seed so the starfield is identical every recomposition / rotation (no twinkle churn). */
private const val StarfieldSeed = 0x50FA_11L

/** Ambient wash radius as a fraction of the field width (a broad top-centre glow, not a spotlight). */
private const val AmbientRadiusFraction = 0.62f
// The ambient "breath" (Void watching-eyes / Dread wound) is a Compose-WIN divergence — but the shipping shelf is
// DEAD-STILL when idle (measured: baseline motion ~0 vs Mimir Alpha's ~0.4 with breath on), which reads as
// restlessness against strict parity. Default OFF for the parity certification; flip to true to restore the motif.
private const val AmbientBreathEnabled = false

private val shelfBackgroundBitmapCache = MimirShelfBackgroundBitmapCache()

/** How far down the field's top edge the ambient wash centre sits (0 = top edge). */
private const val AmbientCentreYFraction = 0.06f

/** Palette-only field stops, exposed so the three repaired backgrounds stay regression-testable. */
internal data class MimirFieldTreatment(
    val top: Color,
    val horizon: Color,
    val body: Color,
    val footer: Color,
    val rail: Color,
    val railAlpha: Float,
)

internal fun MimirThemePalette.fieldTreatment(): MimirFieldTreatment {
    val scene = materials.scene
    return when {
        scene.atmosphere == MimirAtmosphereKind.MOONLIT -> MimirFieldTreatment(
            top = surfaceHigh,
            horizon = surfaceRaised,
            body = surfaceBase,
            footer = scene.fieldFooter,
            rail = scene.railTrough,
            railAlpha = 0.58f,
        )
        usesBrightField -> MimirFieldTreatment(
            top = surfaceHigh,
            horizon = surfaceBase,
            body = surfaceBase,
            footer = scene.fieldFooter,
            rail = scene.railTrough,
            railAlpha = 0.35f,
        )
        else -> MimirFieldTreatment(
            top = surfaceBase,
            horizon = signatureTop,
            body = surfaceBase,
            footer = scene.fieldFooter,
            rail = scene.railTrough,
            railAlpha = 0.72f,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// (a) SHELF BACKGROUND - authored field + material texture + ambient wash
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The console atmosphere behind the shelf. Draws (bottom to top):
 *  1. the theme field and rail trough,
 *  2. the theme-specific deterministic material texture,
 *  3. the primary/counter-hue stage wash,
 *  4. the theme's authored scene geometry,
 * then lays [content] on top in a [BoxScope].
 *
 * @param modifier caller sizing — typically `Modifier.fillMaxSize()`.
 * @param palette the active theme; defaults to the CompositionLocal-provided one so it re-skins per theme.
 * @param showAtmosphericClouds preserves authored Máni/Baldr cloud geometry by default. Home alone
 *   disables it; Library, onboarding, and theme previews remain byte-identical.
 * @param quietSceneLight dims the scene's one bright light (the Default theme's sun and its ring dust) to
 *   [MimirSceneQuietLightScale]. On compact squares the sun anchor lands beneath the final dock
 *   icons; dim it there instead of moving it.
 *   Home, Library and onboarding pass their compact profile; every other surface keeps the default and is
 *   byte-identical.
 * @param sceneLightBelowText moves that same light (the Default theme's sun, with its ring dust) lower and further
 *   right, below the text column, on a 4:3 or square field -- `MimirSceneAtmosphereV2.kt`'s [wellSunGeometry].
 *   On Retroid Pocket Nova (640x480): the default anchor sat
 *   behind the end of the privacy line and the action pages' where-lines. Home, Library and onboarding pass
 *   `MimirViewportProfile.sceneLightBelowText` (all three, so the sun does not jump at the
 *   hand-over from onboarding to Home; OR-3, 2026-09-29: on 16:9 too); theme previews and the companion miniature
 *   keep the default and are byte-identical.
 * @param sceneLight false takes the scene's one bright light (the Default theme's sun and its ring dust) out altogether, for a small stage where it
 *   would sit over a caption: the Mimir-button picker's two stages, whose "ACTUAL SIZE" and "ON A BRIGHT GAME" lines the sun's default anchor (lower
 *   right of a strip) landed on. The stars, the nebula and every other theme's scene are untouched, and true -- the default -- is every other
 *   surface exactly as it was.
 * @param ground the player's own background in place of the drawn scene (concept A), looked up by
 *   the palette this field is given -- `LocalMimirSceneGrounds.current[palette.id]` -- so Home, Library and each theme
 *   gallery card show their own theme's image with no call-site change, and a window that must never show one
 *   (onboarding) provides an empty map. The Background picker's stage passes its preview in here; null is Atmosphere.
 *   Home's priority is unchanged: a game's hero, drawn in [content], covers the ground as it covers the scene.
 * @param content the shelf chrome + coverflow, drawn ON the lit field.
 */
@Composable
fun MimirShelfBackground(
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    showAtmosphericClouds: Boolean = true,
    quietSceneLight: Boolean = false,
    sceneLightBelowText: Boolean = false,
    sceneLight: Boolean = true,
    ground: MimirSceneGround? = LocalMimirSceneGrounds.current[palette.id],
    content: @Composable BoxScope.() -> Unit,
) {
    // The starfield layout is computed ONCE (unit coords), then scaled to the field each draw — no
    // per-frame RNG, no per-frame allocation on the hot path.
    val stars = remember { generateStarfield() }
    val scene = palette.materials.scene.atmosphere

    // COMPOSE-WIN — a subtle ambient "breath" the shipping WebView's static SVG can't do, on the TWO darkest
    // lore themes only (Void's watching eyes, Dread's welling wound). The heavy scene stays FULLY static
    // (drawn once); only the tiny [drawAmbientBreath] glow-delta layer animates. It is IDLE-PAUSED: the breath
    // runs while you navigate and settles to rest after a quiet window, so an idle shelf costs zero per-frame
    // work — protecting the OLED handheld battery, which is exactly why the base atmosphere is static.
    val breathes = AmbientBreathEnabled &&
        (scene == MimirAtmosphereKind.VOID || scene == MimirAtmosphereKind.DREAD)
    val phase = remember { Animatable(0f) }
    if (breathes) {
        // PERF-G: the nav pulse is collected HERE, inside the breathing branch only — the two lore
        // themes pay a tiny per-press recompose of this scope to wake the breath; every other theme
        // (and the rest of the app) no longer recomposes on d-pad presses at all.
        val controllerActive by LocalMimirControllerNavigationActive.current.collectAsState()
        var awake by remember { mutableStateOf(true) }
        LaunchedEffect(controllerActive, palette.id) {
            // Any nav activity (or a theme switch to Void/Dread) wakes the breath; it settles after a quiet gap.
            awake = true
            delay(AmbientIdleTimeoutMs)
            awake = false
        }
        LaunchedEffect(awake) {
            if (awake) {
                phase.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(AmbientBreathHalfPeriodMs, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
            } else {
                phase.animateTo(0f, animationSpec = tween(1100, easing = FastOutSlowInEasing))
            }
        }
    }

    // **One build, two scene layers — policy 2026-09-01.** Read in COMPOSITION, not inside
    // `drawBehind`: the lambda captures this value, so a flip changes the modifier and repaints. Read
    // inside the draw it would be a snapshot access on the draw thread and would not invalidate.
    //
    // `pulse` is deliberately NOT threaded into v2.  these painters accept
    // it so their signatures match the site they replace, and v1 is called without it — starting an
    // animation here to feed them would be inventing a producer the comparison never asked for.

    // **The background is RASTERISED ONCE and blitted, not redrawn every frame.**
    //
    // The comment above has claimed since it was written that "the heavy scene stays FULLY static (drawn
    // once)" and that "an idle shelf costs zero per-frame work". That was the INTENT and it was not what
    // the code did: `drawBehind` re-runs on every draw invalidation, and the platform tiles above this
    // Box invalidate it on every frame of a traversal. The scene was being rebuilt 60 times a second.
    //
    // Measured on the Thor, 2026-09-03, Library shelf, 16 x DPAD_RIGHT, interleaved two rounds:
    // Yggdrasil (`terra`) ran at **p50 34 ms and produced 474 frames** where `default` produced 832 in
    // the same wall-clock. ~29 fps sustained, against ~60 for every other theme. The cause is arithmetic:
    // the Yggdrasil painter issues ~3,500 individual `drawLine` calls per frame (~480 trunk axis, ~2,500
    // roots, ~550 branches), each carrying its own alpha — which is exactly the "roots that vary along
    // their length" the request specified for, so they cannot collapse into one `Path`.
    //
    // **Caching is sound here because the block below reads NOTHING that animates.** `pulse` is not
    // threaded into the v2 painters (`drawThemeSceneV2(palette, showAtmosphericClouds)` — the parameter
    // defaults to 0), and the one thing that does animate, [drawAmbientBreath], lives in its own `Box`
    // with its own `drawBehind` layered on top and is untouched. So this is a pure function of size,
    // palette, starfield and scene kind.
    //
    // **The pixels are identical, deliberately.** The same calls run in the same order; only their
    // frequency changes. Thinning the sample counts would have been a smaller change and would have
    // altered the established look.
    //
    // Cost: one ARGB bitmap the size of the surface (~8 MB full-screen). Completed bitmaps are retained
    // in the bounded process-owned cache below so Home and Library can reuse an exact static key across
    // destination mounts. It holds at most two entries and 16 MiB; `MimirIconSetGallery` calls
    // `drawThemeSceneV2` directly and is deliberately NOT routed through here — it paints many small
    // scene previews at once, where per-tile bitmaps would be the wrong trade.
    //
    // **A palette-only change bakes off the UI thread, then cuts.** The key carries the
    // palette, so a theme switch misses it, and this draw used to bake the full-size raster right here on the UI
    // thread: on the Thor every first use of a theme in a process skipped 33-46 frames, and a main-thread
    // profile of the cold switch to crimson put 55.3% of its samples in `getOrBuild` -> [bakeShelfScene] from this
    // block. Now, when only the palette changed and this surface still holds its previous raster, that raster keeps
    // drawing while [bakeShelfBackgroundRaster] runs on `Dispatchers.Default`; back on the UI thread the finished
    // raster enters the UI-thread-owned cache and replaces the old one in ONE frame -- no crossfade on Main, §4.2(c)
    // -- and a stale or cancelled raster is released unpublished ([landOffThreadBake], [runOffThreadShelfBake]). The
    // The tradeoff: the new palette's chrome now shows before its shelf raster, by the bake's duration
    // (the `bakeMs` of the `MimirShelfBake` log line). Pixels are identical by construction: both threads run the one
    // bake body, which paints from the cache key alone. A surface's first bake (cold start, a destination mount) and
    // any change besides the palette (size, density, direction, scene style, clouds) keep the synchronous path, so
    // nothing ever draws an empty frame. **A synchronous bake never reads a file** (the player's background, concept A,
    // brief §C): with its ground's decoded copy not resident it paints the Atmosphere and the image bakes off the UI
    // thread next ([planSynchronousBake]), so a D-pad sweep across the picker's tiles or a pass through the theme
    // gallery -- a fresh swap per stage layer and per card -- decodes nothing on the UI thread.
    val scope = rememberCoroutineScope()
    val swap = remember { MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>(crossfadeMillis = 0) }
    // Bumped on the UI thread when an off-thread raster is adopted; read only inside the draw, so it invalidates the
    // draw alone.
    val adoptedRasters = remember { mutableIntStateOf(0) }
    DisposableEffect(swap) { onDispose { swap.release() } }
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width.toInt()
            val h = size.height.toInt()
            // A zero-area pass happens before the first real measure; caching it would poison the layer.
            if (w <= 0 || h <= 0) return@drawWithCache onDrawBehind { }
            val cacheKey = MimirShelfBackgroundCacheKey(
                size = size,
                density = density,
                layoutDirection = layoutDirection,
                palette = palette,
                showAtmosphericClouds = showAtmosphericClouds,
                quietSceneLight = quietSceneLight,
                sceneLightBelowText = sceneLightBelowText,
                sceneLight = sceneLight,
                ground = ground,
            )
            val estimatedBytes = MimirShelfBackgroundBitmapCache.nominalAllocationBytes(w, h)
            // Plain values only: the bake paints what cacheKey names, so a bake on another thread reads neither this
            // CacheDrawScope (the next pass reuses it) nor state that moved after the key was built.
            val bakeFontScale = fontScale
            // The bake that may block, for Dispatchers.Default: a ground whose decoded copy is not resident is decoded
            // there, on the bake's own thread. The UI-thread bake below takes only a resident copy.
            val bake = { bakeShelfBackgroundRaster(cacheKey, bakeFontScale, stars, MimirSceneGroundImages::getOrDecode) }
            val requestRaster = {
                swap.request(
                    key = cacheKey,
                    differsOnlyInPalette = MimirShelfBackgroundCacheKey::differsOnlyInPaletteFrom,
                    cachedFor = shelfBackgroundBitmapCache::get,
                )
            }
            val bakeOffThread = {
                scope.launch {
                    runOffThreadShelfBake(
                        bakeContext = Dispatchers.Default,
                        bake = bake,
                        land = { built ->
                            // Resumed on the UI thread: the cache is UI-thread-owned, so the insert happens here.
                            val outcome = swap.landOffThreadBake(
                                key = cacheKey,
                                result = built,
                                rasterOf = MimirShelfBackgroundCacheBuild::bitmap,
                                publish = { key, result ->
                                    shelfBackgroundBitmapCache.getOrBuild(key, estimatedBytes) { result }
                                },
                                release = ::releaseUnpublishedShelfRaster,
                            )
                            if (outcome != MimirSceneBakeOutcome.STALE) adoptedRasters.intValue++
                            outcome
                        },
                        release = ::releaseUnpublishedShelfRaster,
                        report = { outcome, bakeMillis ->
                            Log.i(ShelfBakeLogTag, mimirShelfBakeLogMessage(cacheKey, outcome, bakeMillis))
                        },
                    )
                }
            }
            when (requestRaster()) {
                // A cutting swap shows a cached palette at once and answers DRAW; CROSSFADE never comes back here.
                MimirSceneBackdropStep.DRAW, MimirSceneBackdropStep.CROSSFADE -> Unit
                MimirSceneBackdropStep.BAKE_NOW -> {
                    // Nothing may stand in, so this bakes on the UI thread -- and a UI-thread bake never reads a file
                    // (brief §C): with the ground's copy not resident it paints the Atmosphere and the image bakes off
                    // the UI thread next, cutting in on this swap's own clock. A ground already resident, or already
                    // cached as a raster, is painted as asked.
                    val plan = planSynchronousBake(cacheKey, rasterCached = { shelfBackgroundBitmapCache.get(it) != null })
                    swap.showNow(
                        plan.key,
                        shelfBackgroundBitmapCache.getOrBuild(plan.key, estimatedBytes) {
                            bakeShelfBackgroundRaster(plan.key, bakeFontScale, stars) { plan.groundImage }
                        },
                    )
                    // The stand-in differs from the requested key by its ground alone, which takes the off-thread path.
                    if (plan.groundPending && requestRaster() == MimirSceneBackdropStep.BAKE_OFF_THREAD) bakeOffThread()
                }
                MimirSceneBackdropStep.BAKE_OFF_THREAD -> bakeOffThread()
            }
            onDrawBehind {
                // Subscribes this draw to adoptions: an off-thread raster shows on the frame after it lands.
                adoptedRasters.intValue
                swap.shown?.let { drawImage(it) }
            }
        },
    ) {
        // Reading phase.value INSIDE drawBehind invalidates only the draw (no recomposition); when settled the
        // value is constant so nothing redraws.
        if (breathes) {
            Box(Modifier.matchParentSize().drawBehind { drawAmbientBreath(scene, palette, phase.value) })
        }
        content()
    }
}

/** Logcat tag of [MimirShelfBackground]'s off-thread palette bake: one line per bake ([mimirShelfBakeLogMessage]). */
private const val ShelfBakeLogTag = "MimirShelfBake"

/**
 * The full-size shelf raster for [key] -- the ONE bake body behind both of [MimirShelfBackground]'s paths: inline on the
 * UI thread for a surface's first bake or a non-palette change, and on `Dispatchers.Default` for a palette-only change.
 * It paints [bakeShelfScene] into a `key.size` bitmap with every argument taken from [key], so a raster can never
 * disagree with the key it is cached under; it touches no cache and no UI-thread state, and the caller picks the thread.
 * [fontScale] only completes the [Density] and is not in the key: the scene painters convert dp, never sp, and
 * `CanvasDrawScope` reads nothing from a [Density] but its `density` and `fontScale` -- so this paints the pixels the
 * draw scope itself produced when it was passed in. The painters hold no mutable top-level state (see
 * [bakeSceneBackdropMiniature]), so a Main bake and a Bottom bake may run on two threads at once. A key with a
 * ground takes its decoded copy from [groundImageOf]: [MimirSceneGroundImages.getOrDecode] on `Dispatchers.Default`, where
 * a decode may block, and the copy [planSynchronousBake] found resident on the UI thread, where nothing may.
 */
private fun bakeShelfBackgroundRaster(
    key: MimirShelfBackgroundCacheKey,
    fontScale: Float,
    stars: List<AtmosphereStar>,
    groundImageOf: (MimirSceneGround) -> ImageBitmap?,
): MimirShelfBackgroundCacheBuild {
    val image = ImageBitmap(key.size.width.toInt(), key.size.height.toInt())
    bakeShelfScene(
        image = image,
        density = Density(density = key.density, fontScale = fontScale),
        layoutDirection = key.layoutDirection,
        size = key.size,
        stars = stars,
        palette = key.palette,
        showAtmosphericClouds = key.showAtmosphericClouds,
        quietSceneLight = key.quietSceneLight,
        sceneLightBelowText = key.sceneLightBelowText,
        sceneLight = key.sceneLight,
        ground = key.ground,
        groundImage = key.ground?.let(groundImageOf),
        applyGroundBlur = true,
    )
    return MimirShelfBackgroundCacheBuild(
        bitmap = image,
        allocationBytes = MimirShelfBackgroundBitmapCache.actualAllocationBytes(image),
    )
}

/**
 * Runs one off-thread shelf bake: [bake] on [bakeContext], then [land] back on the calling dispatcher -- the UI thread,
 * for `MimirShelfBackground` -- and exactly one [report] of the outcome with the bake's wall-clock duration.
 *
 * The painters cannot be interrupted, so cancelling the caller's scope (the surface left composition) does not stop a
 * bake that has started: `withContext` returns only once its block has finished, and then throws. The raster that block
 * produced was never cached or drawn, so it is [release]d here and reported [MimirSceneBakeOutcome.CANCELLED] without
 * landing. A bake cancelled before it started never runs, and reports no duration.
 */
internal suspend fun <R : Any> runOffThreadShelfBake(
    bakeContext: CoroutineContext,
    bake: () -> R,
    land: (R) -> MimirSceneBakeOutcome,
    release: (R) -> Unit,
    report: (outcome: MimirSceneBakeOutcome, bakeMillis: Long?) -> Unit,
) {
    // Written on the bake thread and read here only after withContext has returned or thrown, which it does only once
    // the block has finished, so the handoff is ordered.
    var baked: R? = null
    var bakeNanos: Long? = null
    val built = try {
        withContext(bakeContext) {
            val start = System.nanoTime()
            bake().also { raster ->
                bakeNanos = System.nanoTime() - start
                baked = raster
            }
        }
    } catch (cancelled: CancellationException) {
        baked?.let(release)
        report(MimirSceneBakeOutcome.CANCELLED, bakeNanos?.let { it / NanosPerMillisecond })
        throw cancelled
    }
    report(land(built), bakeNanos?.let { it / NanosPerMillisecond })
}

private const val NanosPerMillisecond = 1_000_000L

/** Frees a raster an off-thread bake painted that was never cached or drawn -- a stale, duplicate or cancelled result. */
internal fun releaseUnpublishedShelfRaster(build: MimirShelfBackgroundCacheBuild) {
    build.bitmap.asAndroidBitmap().recycle()
}

/**
 * The one `MimirShelfBake` log line per off-thread bake: the key's palette, the bake's duration and its outcome, and
 * `ground=` (the image's id, or `none`) so a background bake's cost can be read against the drawn scene's.
 */
internal fun mimirShelfBakeLogMessage(
    key: MimirShelfBackgroundCacheKey,
    outcome: MimirSceneBakeOutcome,
    bakeMillis: Long?,
): String =
    "off-thread bake palette=${key.palette.id.storageKey} " +
        "size=${key.size.width.toInt()}x${key.size.height.toInt()} " +
        "clouds=${key.showAtmosphericClouds} bakeMs=${bakeMillis ?: "-"} outcome=${outcome.logName} " +
        "ground=${key.ground?.id ?: "none"}"

/**
 * The ONE bake body behind the static shelf raster. Paints, bottom to top, exactly what the inline block in
 * [MimirShelfBackground] painted before it was factored out: the field ramp and rail trough, the theme material
 * texture, the ambient wash, the authored theme scene (v1 or v2), then the quiet theme crest. Same calls in the
 * same order -- [MimirShelfBackground] (full size, 1:1) and [MimirThemeSceneBackdrop] (a miniature) both route
 * through here, so the two rasters can never drift in layer order or content. A pure function of its arguments:
 * nothing here reads animated state.
 *
 * @param density the density the scene's dp-sized strokes, grid cells and star radii resolve against. The
 *   shelf passes its real density; a miniature passes `real / downscale` so those measures shrink with the
 *   raster instead of staying at full-size pixel weights inside a smaller bitmap.
 * @param size the logical draw size -- the bitmap's own pixel size for a 1:1 bake.
 * @param ground the player's background in place of the drawn scene, with [groundImage] its
 *   decoded copy. With both present the scene layers are not painted at all: the image is cropped to fill, blurred
 *   per its setting when [applyGroundBlur] (the full-size shelves; a miniature blurs itself afterwards), then
 *   dimmed with `surfaceBase` at the chosen dim, exactly. A ground whose copy failed to decode paints
 *   the scene exactly as before, so a missing file shows Atmosphere.
 */
private fun bakeShelfScene(
    image: ImageBitmap,
    density: Density,
    layoutDirection: LayoutDirection,
    size: Size,
    stars: List<AtmosphereStar>,
    palette: MimirThemePalette,
    showAtmosphericClouds: Boolean,
    quietSceneLight: Boolean = false,
    sceneLightBelowText: Boolean = false,
    ground: MimirSceneGround? = null,
    groundImage: ImageBitmap? = null,
    applyGroundBlur: Boolean = true,
    shineStars: Boolean = true,
    sceneLight: Boolean = true,
) {
    val scene = palette.materials.scene.atmosphere
    CanvasDrawScope().draw(density, layoutDirection, Canvas(image), size) {
        if (ground != null && groundImage != null) {
            drawSceneGround(groundImage, ground, palette, layoutDirection, applyGroundBlur)
            return@draw
        }
        drawAtmosphereField(palette)
        drawShelfTexture(stars, palette)
        drawAmbientWash(palette)
        drawThemeSceneV2(
            palette,
            showAtmosphericClouds,
            quietSceneLight = quietSceneLight,
            sceneLightBelowText = sceneLightBelowText,
            shineStars = shineStars,
            sceneLight = sceneLight,
        )
        // One quiet authored crest, behind content and outside the primary title lane. It comes from
        // the existing Tome signature vocabulary; never repeated as wallpaper.
        // Máni already owns one authored crescent in drawMoonlitScene. Repeating the generic theme
        // signature beside it reads as two moons rather than a restrained watermark.
        //  -- applies to DEFAULT (WELL) only; the
        // other themes keep it without this change. Nothing load-bearing sits behind it there,
        // only starfield specks. `MimirThemeSignature.kt`'s non-background call site is unaffected.
        if (scene != MimirAtmosphereKind.MOONLIT && scene != MimirAtmosphereKind.WELL) {
            drawMimirThemeSignature(
                palette = palette,
                centerX = 0.92f,
                centerY = 0.20f,
                radiusFraction = 0.032f,
                alpha = if (palette.usesBrightField) 0.14f else 0.09f,
            )
        }
    }
}

/**
 * The ground branch of [bakeShelfScene]: the player's image in place of the scene. Crop to fill (the centred source
 * rectangle of [mimirGroundCropRect], the `ContentScale.Crop` behaviour), then the blur -- the crop drawn into a raster
 * `1/downscale` the size of this one, box-blurred by the setting's radius in that raster's own pixels, and stretched
 * back bilinearly: Soft is exactly the Bottom's current miniature treatment, Strong halves the raster again -- then
 * the `surfaceBase` veil at [MimirSceneGround.dim], the chosen dim as stored (there is no readability floor),
 * Existing veils (Home's centre veil, the gamelist's) stay on top in content, unchanged.
 *
 * Per frame nothing here runs: like the scene, this paints once into the cached raster. The cost is one texture draw
 * plus, for a blur, one small raster and two O(pixels) passes -- cheaper than Yggdrasil's ~3,500 line draws.
 */
private fun DrawScope.drawSceneGround(
    image: ImageBitmap,
    ground: MimirSceneGround,
    palette: MimirThemePalette,
    layoutDirection: LayoutDirection,
    applyBlur: Boolean,
) {
    val crop = mimirGroundCropRect(image.width, image.height, size.width, size.height)
    val dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    val blur = if (applyBlur) ground.blur else MimirSceneGroundBlur.OFF
    val small = mimirSceneBackdropBakeSize(dstSize.width, dstSize.height, blur.downscale)
    if (blur.downscale > 1 && blur.radiusPx > 0 && small.width < dstSize.width) {
        val miniature = ImageBitmap(small.width, small.height)
        CanvasDrawScope().draw(
            Density(1f),
            layoutDirection,
            Canvas(miniature),
            Size(small.width.toFloat(), small.height.toFloat()),
        ) {
            drawImage(
                image = image,
                srcOffset = crop.offset,
                srcSize = crop.size,
                dstSize = small,
                filterQuality = FilterQuality.Low,
            )
        }
        val softened = boxBlurImageBitmap(miniature, blur.radiusPx)
        drawImage(image = softened, dstSize = dstSize, filterQuality = FilterQuality.Low)
    } else {
        drawImage(
            image = image,
            srcOffset = crop.offset,
            srcSize = crop.size,
            dstSize = dstSize,
            filterQuality = FilterQuality.Low,
        )
    }
    drawRect(color = palette.surfaceBase.copy(alpha = ground.dim.alpha))
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// (a1) THEME SCENE BACKDROP - the same raster as a soft, cheap miniature (the Bottom's stage)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Miniature factor: the backdrop bakes the scene at 1/4 x 1/4 of the surface by default. */
private const val SceneBackdropDefaultDownscale = 4

/** Default box-blur radius, in the MINIATURE's pixels (so ~4x that at surface scale with the default factor). */
private const val SceneBackdropDefaultBlurRadiusPx = 2

/**
 * The backdrop's own bounded cache -- the same class as [shelfBackgroundBitmapCache], a separate instance.
 * That cache holds two entries and Home + Library already fill it; sharing it would let every Bottom bake evict
 * one of Main's full-size rasters, and a backdrop key can never match a shelf key anyway (different
 * `downscale`), so sharing buys nothing. A miniature is ~1/16 of a shelf raster (~0.5 MB at 1920x1080 / 4).
 */
private val sceneBackdropBitmapCache = MimirShelfBackgroundBitmapCache()

/**
 * The theme scene as a soft, cheap BACKDROP --  Paints the SAME scene [MimirShelfBackground] bakes (same painters, same layer order, via
 * [bakeShelfScene]) as a miniature: `(w / downscale) x (h / downscale)` pixels, drawn with the DrawScope size
 * AND density both divided by [downscale], so gradients, dp-sized strokes, grid cells and star radii all shrink
 * together and the miniature is a true scaled copy of the shelf raster rather than full-size line weights inside
 * a small bitmap. The miniature is box-blurred ONCE in software at bake time ([boxBlurArgb], [blurRadiusPx] in
 * the miniature's pixels) and cached. Per frame the cost is one bilinear-filtered `drawImage` stretch to the
 * surface size -- a single texture draw (two during the [MimirMotion.SceneMs] crossfade after a palette change; see
 * [mimirThemeSceneBackdrop]). That is what "cheap" has to mean here: a live `RenderEffect` blur at
 * small radii measured 20-45 ms/frame on the Bottom, and the tail of every glass ramp already pays it.
 *
 * "Slightly blurred" = the bilinear upscale of a 1/4-size raster plus a 2 px box blur at that size. Both knobs
 * are exposed; those values are the defaults.
 *
 * Clouds stay on: this is a shelf surface. The Home-only cloud opt-out in [MimirShelfBackground] exists because
 * hero art covers the sky there; nothing covers it on a stage.
 *
 * @param alpha the opacity the finished raster is drawn with (1 = as baked); coerced to 0..1.
 */
@Composable
fun MimirThemeSceneBackdrop(
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    downscale: Int = SceneBackdropDefaultDownscale,
    blurRadiusPx: Int = SceneBackdropDefaultBlurRadiusPx,
    alpha: Float = 1f,
    quietSceneLight: Boolean = false,
    shineStars: Boolean = true,
) {
    Box(
        modifier = modifier.mimirThemeSceneBackdrop(
            palette, downscale, blurRadiusPx, alpha, quietSceneLight = quietSceneLight, shineStars = shineStars,
        ),
    )
}

/**
 * The same softened scene as [MimirThemeSceneBackdrop], as a draw modifier, for a surface that already owns
 * its root node (the Bottom's platform shelf, D14 2026-09-11: its ground was an opaque `surfaceBase`, so the themed secondary-screen background stopped at the lattice). Read the scene kind in
 * composition here for the same reason the composable does; `composed` runs once per attach, never per frame.
 *
 * **A palette change bakes off the UI thread, then crossfades.** A theme switch used to
 * miss the cache in `drawWithCache`, bake the new miniature on the UI thread (the Bottom showed a 262 ms hole on the
 * device) and swap the whole backdrop in one frame. Now, when only the palette changed and the old raster is still
 * drawable, the old raster keeps drawing while [bakeSceneBackdropMiniature] runs on `Dispatchers.Default` into a
 * standalone bitmap; back on the UI thread the result enters the UI-thread-owned cache and the two rasters crossfade
 * over [MimirMotion.SceneMs] -- two `drawImage` calls a frame during the fade, one otherwise -- and the old raster is
 * dropped when the fade ends ([MimirSceneBackdropSwap]). The finished miniature's pixels are unchanged by
 * construction: both threads run the one bake function. The first bake, and any change besides the palette (size,
 * density, scene style), keep the synchronous path exactly as it was.
 */
fun Modifier.mimirThemeSceneBackdrop(
    palette: MimirThemePalette,
    downscale: Int = SceneBackdropDefaultDownscale,
    blurRadiusPx: Int = SceneBackdropDefaultBlurRadiusPx,
    alpha: Float = 1f,
    quietSceneLight: Boolean = false,
    shineStars: Boolean = true,
): Modifier = composed {
    val stars = remember { generateStarfield() }
    // The player's background for this palette (concept A, 2026-09-30): the Bottom screen and the compact browser
    // show the same image as the shelves, in this miniature's own soft treatment -- the Blur setting is the full-size
    // shelves' alone (R4). Read in composition, like the palette: a commit changes the key and re-bakes.
    val ground = LocalMimirSceneGrounds.current[palette.id]
    val scope = rememberCoroutineScope()
    // Receipt display: a theme switch's crossfade is reported as the transition `theme-switch.crossfade` on the Bottom
    // display that hosts this backdrop, and silently anywhere else (Main passes no display).
    val receiptDisplay = LocalBottomUiDisplay.current
    // Reduced motion (the system's animator scale at zero) is a CUT: a change of palette or ground swaps the raster in at
    // once and nothing fades (brief §F). The swap keeps its previous raster on screen while the new one bakes either way.
    val reducedMotion = mimirReducedMotion()
    val swap = remember(reducedMotion) {
        MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>(
            crossfadeMillis = mimirSceneBackdropCrossfadeMillis(reducedMotion),
        )
    }
    // Receipt: publish the value remembered above, so the Bottom's theme line reports the cut or crossfade this backdrop will
    // actually run rather than re-sampling a setting that may have changed since (BottomUiReceiptLog.themeSwitchIsCut).
    DisposableEffect(receiptDisplay, reducedMotion) {
        val owner = Any()
        BottomUiReceipts.log.registerBackdropMotion(receiptDisplay, owner, reducedMotion)
        onDispose { BottomUiReceipts.log.unregisterBackdropMotion(receiptDisplay, owner) }
    }
    // Written once a frame while a crossfade runs, never otherwise; read only inside the draw, so it invalidates the
    // draw alone.
    val fadeFrameNanos = remember { mutableLongStateOf(0L) }
    DisposableEffect(swap) { onDispose { swap.release() } }
    drawWithCache {
        val w = size.width.toInt()
        val h = size.height.toInt()
        // A zero-area pass happens before the first real measure; caching it would poison the layer.
        if (w <= 0 || h <= 0) return@drawWithCache onDrawBehind { }
        val scale = downscale.coerceAtLeast(1)
        val radius = blurRadiusPx.coerceAtLeast(0)
        val bakeSize = mimirSceneBackdropBakeSize(w, h, scale)
        val cacheKey = MimirShelfBackgroundCacheKey(
            size = size,
            density = density,
            layoutDirection = layoutDirection,
            palette = palette,
            showAtmosphericClouds = true,
            quietSceneLight = quietSceneLight,
            downscale = scale,
            blurRadiusPx = radius,
            shineStars = shineStars,
            ground = ground,
        )
        val estimatedBytes = MimirShelfBackgroundBitmapCache.nominalAllocationBytes(bakeSize.width, bakeSize.height)
        // Plain values only: an off-thread bake must not capture this CacheDrawScope, which the next pass reuses.
        val bakeDensity = Density(density = density / scale, fontScale = fontScale)
        val bakeLayoutDirection = layoutDirection
        // The bake that may block, for Dispatchers.Default: a ground whose decoded copy is not resident is decoded there.
        val bake = {
            bakeSceneBackdropMiniature(
                bakeSize, bakeDensity, bakeLayoutDirection, stars, palette, radius, ground, MimirSceneGroundImages::getOrDecode,
                quietSceneLight, shineStars,
            )
        }
        val requestRaster = {
            swap.request(
                key = cacheKey,
                differsOnlyInPalette = MimirShelfBackgroundCacheKey::differsOnlyInPaletteFrom,
                cachedFor = sceneBackdropBitmapCache::get,
            )
        }
        val bakeOffThread = {
            scope.launch {
                val built = withContext(Dispatchers.Default) { bake() }
                // Resumed on the UI thread: the cache is UI-thread-owned, so the insert happens here, never above.
                val bitmap = sceneBackdropBitmapCache.getOrBuild(cacheKey, estimatedBytes) { built }
                if (swap.landed(cacheKey, bitmap)) {
                    runReportedSceneCrossfade(reducedMotion, receiptDisplay, swap, fadeFrameNanos)
                }
            }
        }
        when (requestRaster()) {
            MimirSceneBackdropStep.DRAW -> Unit
            MimirSceneBackdropStep.BAKE_NOW -> {
                // A first bake never reads a file (brief §C): a ground whose copy is not resident stands the Atmosphere
                // in, and the image bakes off the UI thread next and swaps in on this swap's clock -- the scene
                // crossfade, or a cut under reduced motion.
                val plan = planSynchronousBake(cacheKey, rasterCached = { sceneBackdropBitmapCache.get(it) != null })
                swap.showNow(
                    plan.key,
                    sceneBackdropBitmapCache.getOrBuild(plan.key, estimatedBytes) {
                        bakeSceneBackdropMiniature(
                            bakeSize, bakeDensity, bakeLayoutDirection, stars, palette, radius, plan.key.ground,
                            groundImageOf = { plan.groundImage },
                            quietSceneLight = quietSceneLight,
                            shineStars = shineStars,
                        )
                    },
                )
                if (plan.groundPending && requestRaster() == MimirSceneBackdropStep.BAKE_OFF_THREAD) bakeOffThread()
            }
            MimirSceneBackdropStep.BAKE_OFF_THREAD -> bakeOffThread()
            MimirSceneBackdropStep.CROSSFADE -> {
                scope.launch {
                    runReportedSceneCrossfade(reducedMotion, receiptDisplay, swap, fadeFrameNanos)
                }
            }
        }
        val dstSize = IntSize(w, h)
        val drawAlpha = alpha.coerceIn(0f, 1f)
        onDrawBehind {
            val frameNanos = fadeFrameNanos.longValue
            val incoming = swap.shown ?: return@onDrawBehind
            val outgoing = swap.outgoing
            val incomingAlpha = swap.incomingAlpha(frameNanos)
            if (outgoing == null || incomingAlpha >= 1f) {
                // One texture draw per frame: the miniature stretched to the surface with bilinear filtering.
                drawImage(
                    image = incoming,
                    dstSize = dstSize,
                    alpha = drawAlpha,
                    filterQuality = FilterQuality.Low,
                )
                return@onDrawBehind
            }
            // The crossfade. Both rasters are opaque -- every field-ramp stop is an opaque palette token and
            // [boxBlurArgb] keeps an opaque raster opaque -- so the old one at full weight with the new one over it at
            // `incomingAlpha` IS the complementary pair (1 - a, a). Drawing the old one at 1 - a as well would let
            // whatever lies behind the backdrop through by a(1 - a), a quarter at mid-fade. Under a caller alpha below
            // 1 the pair is composited first, or the old raster would still show when the fade hands off.
            val layered = drawAlpha < 1f
            if (layered) {
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = drawAlpha })
            }
            drawImage(image = outgoing, dstSize = dstSize, filterQuality = FilterQuality.Low)
            if (incomingAlpha > 0f) {
                drawImage(image = incoming, dstSize = dstSize, alpha = incomingAlpha, filterQuality = FilterQuality.Low)
            }
            if (layered) drawContext.canvas.restore()
        }
    }
}

/**
 * The miniature bake itself -- the body the draw ran inline before the off-thread bake, unchanged, so the
 * UI-thread path and the off-thread path produce the same pixels by construction. Touches no cache and no UI-thread
 * state; the caller picks the thread. The painters it reaches (this file, `MimirSceneAtmosphereV2.kt`,
 * `MimirThemeSignature.kt`) hold no top-level `var` and only read-only tables built at class initialisation, so two
 * bakes on two threads -- the Main shelf's and this one, on a theme switch -- share nothing mutable.
 */
internal fun bakeSceneBackdropMiniature(
    bakeSize: IntSize,
    density: Density,
    layoutDirection: LayoutDirection,
    stars: List<AtmosphereStar>,
    palette: MimirThemePalette,
    blurRadiusPx: Int,
    ground: MimirSceneGround?,
    groundImageOf: (MimirSceneGround) -> ImageBitmap?,
    quietSceneLight: Boolean = false,
    shineStars: Boolean = true,
): MimirShelfBackgroundCacheBuild {
    val miniature = ImageBitmap(bakeSize.width, bakeSize.height)
    bakeShelfScene(
        image = miniature,
        density = density,
        layoutDirection = layoutDirection,
        size = Size(bakeSize.width.toFloat(), bakeSize.height.toFloat()),
        stars = stars,
        palette = palette,
        showAtmosphericClouds = true,
        quietSceneLight = quietSceneLight,
        ground = ground,
        groundImage = ground?.let(groundImageOf),
        // The miniature's own 1/4 raster and box blur below ARE its soft treatment (R4); the Blur setting is not applied.
        applyGroundBlur = false,
        shineStars = shineStars,
    )
    val softened = if (blurRadiusPx > 0) boxBlurImageBitmap(miniature, blurRadiusPx) else miniature
    return MimirShelfBackgroundCacheBuild(
        bitmap = softened,
        allocationBytes = MimirShelfBackgroundBitmapCache.actualAllocationBytes(softened),
    )
}

/**
 * [runSceneBackdropCrossfade], reported as the Bottom's `theme-switch.crossfade` transition. Under reduced motion the switch is a
 * deliberate cut and the routine returns at once, so it reports NOTHING: the theme line says `transition=none
 * reason=reduced_motion` instead, and a begin/end around a fade that never ran would be a false receipt.
 */
private suspend fun runReportedSceneCrossfade(
    reducedMotion: Boolean,
    receiptDisplay: Int,
    swap: MimirSceneBackdropSwap<*, *>,
    frameNanos: MutableLongState,
) {
    if (reducedMotion) {
        runSceneBackdropCrossfade(swap, frameNanos)
    } else {
        bottomUiTransition("theme-switch.crossfade", receiptDisplay) { runSceneBackdropCrossfade(swap, frameNanos) }
    }
}

/**
 * Drives [swap]'s crossfade one frame at a time until the outgoing raster is released, publishing each frame time to
 * [frameNanos] for the draw to read. A second call while a loop runs returns at once: that loop picks up a restarted
 * fade on its next frame.
 */
private suspend fun runSceneBackdropCrossfade(
    swap: MimirSceneBackdropSwap<*, *>,
    frameNanos: MutableLongState,
) {
    if (!swap.beginFrameLoop()) return
    try {
        do {
            val settled = withFrameNanos { now ->
                val done = swap.advance(now)
                frameNanos.longValue = now
                done
            }
        } while (!settled)
    } finally {
        swap.endFrameLoop()
    }
}

/**
 * Miniature bake dimensions for [MimirThemeSceneBackdrop]: integer-floored `surface / downscale`, never below
 * 1x1 (a zero-area bitmap cannot be allocated), with a non-positive [downscale] read as 1 (no reduction).
 */
internal fun mimirSceneBackdropBakeSize(widthPx: Int, heightPx: Int, downscale: Int): IntSize {
    val scale = downscale.coerceAtLeast(1)
    return IntSize(
        width = (widthPx / scale).coerceAtLeast(1),
        height = (heightPx / scale).coerceAtLeast(1),
    )
}

/** Reads [source]'s pixels, box-blurs them with [boxBlurArgb], and writes the result into a NEW bitmap. */
private fun boxBlurImageBitmap(source: ImageBitmap, radius: Int): ImageBitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.readPixels(pixels)
    val blurred = boxBlurArgb(pixels, width, height, radius)
    val out = ImageBitmap(width, height)
    out.asAndroidBitmap().setPixels(blurred, 0, width, 0, 0, width, height)
    return out
}

/**
 * Separable box blur over packed ARGB ints (the Android `getPixels` layout: A in the top byte, then R, G, B).
 * Horizontal pass then vertical pass, window `2 * radius + 1`, edge taps clamped onto the nearest edge pixel so
 * a uniform image stays exactly uniform and no dark border creeps in. Channels are averaged independently --
 * right for an opaque backdrop, where nothing needs premultiplying. Integer arithmetic rounded to nearest; each
 * pass is O(width * height) through a running window sum, independent of [radius]. `radius <= 0` (or an empty
 * raster) returns [pixels] itself, untouched. Pure Kotlin so it is unit-testable without a graphics stack.
 */
internal fun boxBlurArgb(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
    require(width >= 0 && height >= 0 && pixels.size == width * height) {
        "boxBlurArgb: ${pixels.size} pixels do not fill ${width}x$height"
    }
    if (radius <= 0 || width == 0 || height == 0) return pixels
    val horizontal = IntArray(pixels.size)
    boxBlurPass(pixels, horizontal, width, height, radius, alongRows = true)
    val out = IntArray(pixels.size)
    boxBlurPass(horizontal, out, width, height, radius, alongRows = false)
    return out
}

/** One 1-D box-blur pass over every row (`alongRows`) or every column of a `width x height` ARGB raster. */
private fun boxBlurPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, alongRows: Boolean) {
    val lines = if (alongRows) height else width
    val length = if (alongRows) width else height
    val stride = if (alongRows) 1 else width
    val window = 2 * radius + 1
    val half = window / 2
    val last = length - 1
    for (line in 0 until lines) {
        val base = if (alongRows) line * width else line
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        // Prime the window centred on sample 0; taps left of the edge clamp onto sample 0.
        for (k in -radius..radius) {
            val p = src[base + k.coerceIn(0, last) * stride]
            sumA += (p ushr 24) and 0xFF
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
        }
        for (i in 0 until length) {
            dst[base + i * stride] =
                (((sumA + half) / window) shl 24) or
                (((sumR + half) / window) shl 16) or
                (((sumG + half) / window) shl 8) or
                ((sumB + half) / window)
            // Slide by one: the tap entering on the right joins, the tap leaving on the left drops out.
            val enter = src[base + (i + radius + 1).coerceIn(0, last) * stride]
            val leave = src[base + (i - radius).coerceIn(0, last) * stride]
            sumA += ((enter ushr 24) and 0xFF) - ((leave ushr 24) and 0xFF)
            sumR += ((enter shr 16) and 0xFF) - ((leave shr 16) and 0xFF)
            sumG += ((enter shr 8) and 0xFF) - ((leave shr 8) and 0xFF)
            sumB += (enter and 0xFF) - (leave and 0xFF)
        }
    }
}

/** A static star: unit-square position (0..1) + a baked alpha + a baked radius (px at 1x). */
internal data class AtmosphereStar(val x: Float, val y: Float, val alpha: Float, val radiusPx: Float)

/** Deterministic sparse starfield — same specks every time (seeded), biased toward the upper field. */
internal fun generateStarfield(): List<AtmosphereStar> {
    val rng = Random(StarfieldSeed)
    return List(StarCount) {
        AtmosphereStar(
            x = rng.nextFloat(),
            // Bias upward (y skewed to the top third) so stars read as "sky", not scattered over the shelf.
            y = rng.nextFloat() * rng.nextFloat(),
            alpha = StarAlphaMin + rng.nextFloat() * (StarAlphaMax - StarAlphaMin),
            radiusPx = 0.7f + rng.nextFloat() * 1.1f,
        )
    }
}

/** (1) The theme field — deep night on dark identities, pale authored depth on Folkvangr and Baldr. */
private fun DrawScope.drawAtmosphereField(palette: MimirThemePalette) {
    drawFieldRamp(palette)
    val field = palette.fieldTreatment()
    drawRect(
        brush = Brush.verticalGradient(
            0.42f to Color.Transparent,
            0.58f to field.rail.copy(alpha = field.railAlpha),
            0.74f to Color.Transparent,
        ),
    )
}

/**
 * The field's four-stop vertical wash (top/horizon/body/footer) ONLY — no rail trough, no texture, no
 * scene geometry. Shared by [drawAtmosphereField] (the shelf, which layers the rail trough + texture +
 * scene on top of this) and [mimirAtmosphereField] (any other surface that wants the same authored field
 * without the coverflow-specific layers above it — e.g. Settings, which has no rail). One ramp, one
 * source of the four stops, so a surface using either path can never drift from the other.
 */
private fun DrawScope.drawFieldRamp(palette: MimirThemePalette) {
    val field = palette.fieldTreatment()
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to field.top,
            0.34f to field.horizon,
            0.60f to field.body,
            1.00f to field.footer,
        ),
    )
}

/**
 * The shelf's authored field ramp (top/horizon/body/footer, per [MimirThemePalette.fieldTreatment]) as a
 * standalone paintable [Modifier], for a persistent (non-shelf) surface that wants the same vertical
 * structure behind it instead of a flat [MimirThemePalette.surfaceBase] fill. Draws only the ramp — not
 * the rail trough, starfield, or theme scene geometry [MimirShelfBackground] layers on top of it, since
 * those are authored for the coverflow rail and would be invented context on a surface that has none.
 *
 * Zero new material: this is the same four stops the shelf has always painted, exposed as its own
 * modifier so a non-shelf caller does not need to widen [drawAtmosphereField] (private, DrawScope-only)
 * into feature code to reuse them.
 */
fun Modifier.mimirAtmosphereField(palette: MimirThemePalette): Modifier =
    drawBehind { drawFieldRamp(palette) }

/** Theme material geometry. Only Default owns a grid; the other identities have their own texture language. */
private fun DrawScope.drawShelfTexture(stars: List<AtmosphereStar>, palette: MimirThemePalette) {
    val scene = palette.materials.scene
    when (scene.texture) {
        MimirShelfTexture.ARCHITECTURAL -> {
            // Restore the lattice unchanged with the same GridCell and tuned LatticeAlphaScale.
            drawArchitecturalGrid(scene.shelfThread)
            drawDiagonalSheen(scene.shelfSheen, forward = true)
        }
        MimirShelfTexture.GOLDEN_WEAVE -> {
            drawDiagonalWeave(scene.shelfThread, forward = true, spacing = 24.dp)
            drawDiagonalSheen(scene.shelfSheen, forward = false)
        }
        MimirShelfTexture.FORGED_STEEL -> drawForgedTexture(stars, scene)
        MimirShelfTexture.STARLIGHT -> drawStarfield(stars, scene.highlight, scene.particle)
        MimirShelfTexture.BONE_ASH -> drawBoneAsh(stars, scene)
        MimirShelfTexture.SHADOW_VEIL -> Unit // Hel's authored geometry is the engulfing shadow itself.
        MimirShelfTexture.ABYSS -> Unit
        MimirShelfTexture.ROOT_GRAIN -> drawRootGrain(scene)
        MimirShelfTexture.SUMMER_SKY -> drawSummerSkyTexture(scene)
    }
}


private fun DrawScope.drawDiagonalWeave(color: Color, forward: Boolean, spacing: Dp) {
    val step = spacing.toPx().coerceAtLeast(1f)
    var x = -size.height
    while (x < size.width + size.height) {
        val endX = if (forward) x + size.height else x - size.height
        drawLine(color, Offset(x, 0f), Offset(endX, size.height), strokeWidth = 1f)
        x += step
    }
}

private fun DrawScope.drawDiagonalSheen(color: Color, forward: Boolean) {
    val start = if (forward) Offset.Zero else Offset(size.width, 0f)
    val end = if (forward) Offset(size.width, size.height) else Offset(0f, size.height)
    drawRect(brush = Brush.linearGradient(listOf(Color.Transparent, color, Color.Transparent), start, end))
}

private fun DrawScope.drawForgedTexture(stars: List<AtmosphereStar>, scene: MimirSceneMaterial) {
    val step = 18.dp.toPx().coerceAtLeast(1f)
    var y = step
    while (y < size.height) {
        drawLine(scene.shelfThread, Offset.Zero.copy(y = y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
    stars.take(8).forEach { mark ->
        val x = mark.x * size.width
        val sy = (0.36f + mark.y * 0.50f) * size.height
        drawLine(scene.stageSecondary, Offset(x - 10f, sy + 5f), Offset(x + 13f, sy - 6f), strokeWidth = 1f)
    }
    drawDiagonalSheen(scene.shelfSheen, forward = true)
}

private fun DrawScope.drawStarfield(stars: List<AtmosphereStar>, cool: Color, warm: Color) {
    stars.forEachIndexed { index, s ->
        val base = if (index % 4 == 1) warm else cool
        drawCircle(
            color = base.copy(alpha = base.alpha * s.alpha),
            radius = s.radiusPx,
            center = Offset(s.x * size.width, s.y * size.height),
        )
    }
}

private fun DrawScope.drawBoneAsh(stars: List<AtmosphereStar>, scene: MimirSceneMaterial) {
    stars.take(12).forEachIndexed { index, ash ->
        val x = ash.x * size.width
        val startY = ash.y * size.height * 0.72f
        val length = (18f + ash.radiusPx * 15f) * density
        val drift = if (index % 2 == 0) 4f * density else -3f * density
        drawLine(
            color = scene.shelfThread.copy(alpha = scene.shelfThread.alpha * (0.55f + ash.alpha)),
            start = Offset(x, startY),
            end = Offset(x + drift, (startY + length).coerceAtMost(size.height)),
            strokeWidth = 1f,
        )
    }
    drawDiagonalSheen(scene.shelfSheen, forward = false)
}

private fun DrawScope.drawRootGrain(scene: MimirSceneMaterial) {
    val stroke = 1.2f * density
    for (index in 1..7) {
        val x = size.width * index / 8f
        val bend = if (index % 2 == 0) size.width * 0.025f else -size.width * 0.025f
        val path = Path().apply {
            moveTo(x, 0f)
            cubicTo(x + bend, size.height * 0.30f, x - bend, size.height * 0.66f, x + bend, size.height)
        }
        drawPath(path, scene.shelfThread, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
    drawDiagonalSheen(scene.shelfSheen, forward = true)
}

private fun DrawScope.drawSummerSkyTexture(scene: MimirSceneMaterial) {
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to scene.shelfSheen,
            0.34f to Color.Transparent,
            0.72f to scene.shelfThread,
            1.00f to Color.Transparent,
        ),
    )
}

/** (4) The per-theme ambient wash — a broad accent radial glow, top-centre, behind everything. */
private fun DrawScope.drawAmbientWash(palette: MimirThemePalette) {
    val scene = palette.materials.scene
    val centre = Offset(size.width * 0.5f, size.height * AmbientCentreYFraction)
    val radius = min(size.width, size.height).coerceAtLeast(1f) * (AmbientRadiusFraction * 2f)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(scene.stagePrimary, Color.Transparent),
            center = centre,
            radius = radius,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(scene.stageSecondary, Color.Transparent),
            center = Offset(size.width * 0.78f, size.height * 0.18f),
            radius = radius * 0.72f,
        ),
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// (a2) THEME SCENE LAYERS — static shipping lore signatures, no animation cost
// ─────────────────────────────────────────────────────────────────────────────────────────────────

private data class SceneDot(val x: Float, val y: Float, val radius: Float, val alpha: Float)

private val WarmDust = listOf(
    SceneDot(0.24f, 0.34f, 1.8f, 0.42f),
    SceneDot(0.70f, 0.26f, 1.5f, 0.38f),
    SceneDot(0.84f, 0.56f, 1.6f, 0.34f),
    SceneDot(0.38f, 0.62f, 1.4f, 0.34f),
    SceneDot(0.56f, 0.44f, 1.5f, 0.30f),
)

private val MoonStars = listOf(
    SceneDot(0.14f, 0.22f, 1.4f, 0.60f),
    SceneDot(0.32f, 0.14f, 1.0f, 0.56f),
    SceneDot(0.58f, 0.18f, 1.5f, 0.52f),
    SceneDot(0.82f, 0.12f, 1.0f, 0.50f),
    SceneDot(0.90f, 0.34f, 1.2f, 0.42f),
    SceneDot(0.22f, 0.40f, 1.0f, 0.40f),
    SceneDot(0.48f, 0.28f, 1.3f, 0.48f),
)

private val SoulDots = listOf(
    SceneDot(0.12f, 0.88f, 2.0f, 0.42f),
    SceneDot(0.28f, 0.84f, 1.7f, 0.34f),
    SceneDot(0.41f, 0.90f, 1.8f, 0.38f),
    SceneDot(0.55f, 0.82f, 1.6f, 0.32f),
    SceneDot(0.67f, 0.87f, 1.9f, 0.36f),
    SceneDot(0.79f, 0.84f, 1.6f, 0.32f),
    SceneDot(0.90f, 0.89f, 1.8f, 0.34f),
)

// Shipping void-eyes (styles.css:2921-2925): 5 SMALL bright dots peeking through the footer void, low in the
// field. `radius` = the CSS px radius (~4-5px, drawn as a single soft dot); `alpha` = the bright-core alpha.
private val VoidEyes = listOf(
    SceneDot(0.16f, 0.80f, 5.0f, 0.88f),
    SceneDot(0.70f, 0.86f, 4.2f, 0.82f),
    SceneDot(0.44f, 0.90f, 4.6f, 0.86f),
    SceneDot(0.86f, 0.76f, 3.8f, 0.76f),
    SceneDot(0.28f, 0.88f, 3.6f, 0.80f),
)

private fun DrawScope.drawVoidEye(
    centerX: Float,
    centerY: Float,
    radiusSeed: Float,
    alpha: Float,
    color: Color,
) {
    val center = Offset(size.width * centerX, size.height * centerY)
    // match shipping's void-eyes exactly — ONE small soft dot per eye (a bright core fading to
    // 0.16 at 52% and transparent by 72% of a ~4-5px radius), NOT the 3-layer halo Mimir Alpha drew (that big
    // outer glow at 3× read as prominent "orbs", not tiny eyes peeking through the footer void). radiusSeed is the
    // CSS px radius directly, so the eye is genuinely small — a distant glint on the near-black creep, not a lamp.
    val r = radiusSeed.dp.toPx().coerceAtLeast(1f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to color.copy(alpha = alpha),
                0.52f to color.copy(alpha = alpha * 0.16f),
                0.72f to Color.Transparent,
                1.0f to Color.Transparent,
            ),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

/**
 * COMPOSE-WIN breath: the ADDITIVE, idle-paused glow delta drawn on top of the static Void/Dread scenes.
 * [pulse] is 0..1; at 0 (settled) every layer is fully transparent, so the shelf falls back EXACTLY to the
 * static base scene — the breath is a pure enhancement that costs nothing when idle. Kept deliberately subtle.
 */
private fun DrawScope.drawAmbientBreath(scene: MimirAtmosphereKind, palette: MimirThemePalette, pulse: Float) {
    if (pulse <= 0f) return
    val sceneColors = palette.materials.scene
    when (scene) {
        MimirAtmosphereKind.VOID -> VoidEyes.forEach { eye ->
            val center = Offset(size.width * eye.x, size.height * eye.y)
            val radius = eye.radius.dp.toPx() * 1.35f
            // a breathing halo that swells + brightens with the pulse — "the abyss watches"
            drawCircle(
                color = sceneColors.highlight.copy(alpha = eye.alpha * 0.12f * pulse),
                radius = radius * (3.2f + pulse * 1.4f),
                center = center,
            )
            drawCircle(
                color = sceneColors.highlight.copy(alpha = eye.alpha * 0.20f * pulse),
                radius = radius * (0.5f + pulse * 0.2f),
                center = center,
            )
        }
        MimirAtmosphereKind.DREAD -> {
            // the wound wells + recedes at the tide crest (additive over the static accent oval)
            val center = Offset(size.width * 0.50f, size.height * 0.74f)
            drawCircle(
                color = palette.accent.copy(alpha = 0.12f * pulse),
                radius = size.width * (0.26f + pulse * 0.06f),
                center = center,
            )
            drawCircle(
                color = palette.accentBright.copy(alpha = 0.10f * pulse),
                radius = size.width * (0.10f + pulse * 0.03f),
                center = center,
            )
        }
        else -> Unit
    }
}

private fun DrawScope.drawWarmDust(dots: List<SceneDot>, color: Color) {
    dots.forEach { dot ->
        drawCircle(
            color = color.copy(alpha = dot.alpha),
            radius = dot.radius.dp.toPx(),
            center = Offset(size.width * dot.x, size.height * dot.y),
        )
    }
}

private fun DrawScope.drawTwoToneDust(dots: List<SceneDot>, primary: Color, secondary: Color) {
    dots.forEachIndexed { index, dot ->
        drawCircle(
            color = (if (index % 3 == 1) secondary else primary).copy(alpha = dot.alpha),
            radius = dot.radius.dp.toPx(),
            center = Offset(size.width * dot.x, size.height * dot.y),
        )
    }
}

private fun DrawScope.drawRadialLayer(
    centerX: Float,
    centerY: Float,
    radiusFraction: Float,
    inner: Color,
    outer: Color,
) {
    val radius = min(size.width, size.height) * radiusFraction.coerceAtLeast(0.001f)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(inner, outer),
            center = Offset(size.width * centerX, size.height * centerY),
            radius = radius,
        ),
    )
}

private fun DrawScope.drawDreamCloud(
    centerX: Float,
    centerY: Float,
    widthFraction: Float,
    heightFraction: Float,
    high: Color,
    low: Color,
) {
    val center = Offset(size.width * centerX, size.height * centerY)
    val radiusX = size.width * widthFraction * 0.5f
    val radiusY = (size.height * heightFraction * 0.5f).coerceAtLeast(1f)
    scale(scaleX = radiusX / radiusY, scaleY = 1f, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to high,
                    0.46f to low,
                    0.78f to low.copy(alpha = low.alpha * 0.28f),
                    1.00f to Color.Transparent,
                ),
                center = center,
                radius = radiusY,
            ),
            radius = radiusY,
            center = center,
        )
    }
}

private fun DrawScope.drawCloudWisp(
    centerX: Float,
    centerY: Float,
    widthFraction: Float,
    heightFraction: Float,
    alpha: Float,
    color: Color,
) {
    drawOval(
        color = color.copy(alpha = alpha),
        topLeft = Offset(
            size.width * (centerX - widthFraction * 0.5f),
            size.height * (centerY - heightFraction * 0.5f),
        ),
        size = Size(size.width * widthFraction, size.height * heightFraction),
    )
}

private fun DrawScope.drawRose(centerX: Float, centerY: Float, scale: Float, palette: MimirThemePalette) {
    val cx = size.width * centerX
    val cy = size.height * centerY
    val s = min(size.width, size.height) * 0.045f * scale
    val deep = palette.materials.readableAccent
    val petal = palette.accent
    val gold = palette.materials.counterAccent

    // Still peripheral and static, but now survives the real veil on the warm-ivory field.
    drawCircle(color = petal.copy(alpha = 0.06f), radius = s * 1.75f, center = Offset(cx, cy))
    drawCircle(color = deep.copy(alpha = 0.14f), radius = s * 0.72f, center = Offset(cx, cy))
    val petals = listOf(
        Offset(0.95f, 0.18f),
        Offset(0.34f, 0.88f),
        Offset(-0.72f, 0.64f),
        Offset(-0.92f, -0.22f),
        Offset(-0.22f, -0.88f),
        Offset(0.74f, -0.62f),
    )
    petals.forEach { p ->
        drawOval(
            color = petal.copy(alpha = 0.16f),
            topLeft = Offset(cx + p.x * s - s * 0.72f, cy + p.y * s - s * 0.52f),
            size = Size(s * 1.44f, s * 1.04f),
        )
    }
    drawCircle(color = gold.copy(alpha = 0.14f), radius = s * 0.22f, center = Offset(cx - s * 1.6f, cy + s * 1.3f))
}

private fun DrawScope.drawFooterShade(color: Color) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, color),
            startY = size.height * 0.82f,
            endY = size.height,
        ),
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// (b) BEAM SEPARATOR — the shipping `.eok-sep` rule + centred diamond node
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The separator band's total height (line + the diamond's vertical reach). */
private val BeamHeight: Dp = 14.dp

/** The diamond node's edge length (the shipping lozenge is ~9px). */
private val DiamondEdge: Dp = 9.dp

/** Vertical beam node — sized for the 24.dp Tome Collection gap (MimirPageRunes / pin-marker magnitude). */
private val VerticalBeamDiamondEdge: Dp = 7.dp

/** The beam line thickness (the shipping rule is ~1.5px). */
private val BeamLineThickness: Dp = 1.5.dp

/**
 * The beam separator: a thin THEMED horizontal gradient rule (transparent → accent → transparent) with a
 * centred rotated-square diamond node sitting on it — the shipping `.eok-sep` look (a per-theme lore line,
 * brighter at its core, with a small gold lozenge). Cheap: one Canvas, gradients + primitives, no bitmap.
 *
 * @param modifier caller sizing — typically `Modifier.fillMaxWidth()` with horizontal padding.
 * @param palette the active theme; defaults to the CompositionLocal-provided one (re-skins per theme).
 */
@Composable
fun BeamSeparator(
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    showCenterNode: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BeamHeight)
            .drawBehind { drawBeam(palette, showCenterNode) },
    )
}

/** Draw the beam rule + the core highlight + the optional centred diamond node. */
private fun DrawScope.drawBeam(palette: MimirThemePalette, showCenterNode: Boolean) {
    val midY = size.height / 2f
    val lineH = BeamLineThickness.toPx()
    val separator = palette.materials.separator

    // The shoulder/core split carries the second authored hue where shipping calls for it: moon/gold,
    // foliage/bronze, rose/gold, or sky/sun. It is not synthesized from one accent.
    drawRect(
        brush = Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.24f to separator.shoulder,
            0.50f to separator.core,
            0.76f to separator.shoulder,
            1.0f to Color.Transparent,
        ),
        topLeft = Offset(0f, midY - lineH / 2f),
        size = Size(size.width, lineH),
    )

    // A soft accent halo around the beam's core so brighter themes (crimson/nightmare) get the shipping glow
    // without a blur pass — a wide, very translucent line under the crisp rule.
    drawRect(
        brush = Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.5f to separator.glow,
            1.0f to Color.Transparent,
        ),
        topLeft = Offset(0f, midY - lineH * 1.6f),
        size = Size(size.width, lineH * 3.2f),
    )

    if (!showCenterNode) return

    val edge = DiamondEdge.toPx()
    val half = edge / 2f
    val cx = size.width / 2f

    // A soft glow halo UNDER the node first (the shipping lozenge has a `box-shadow`), so the crisp diamond
    // sits on top of its own glow rather than being washed by it.
    drawCircle(
        color = separator.glow,
        radius = edge * 1.25f,
        center = Offset(cx, midY),
        style = Fill,
    )

    // The centred diamond node: a rotated square (45°) with a warm accent-gradient fill + a fine bright edge.
    rotateRad(radians = QUARTER_TURN_RAD, pivot = Offset(cx, midY)) {
        // Fill — bright core → accent → a dark seat (the lozenge reads as lit metal on the rail).
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(separator.node, separator.core, palette.surfaceBase),
                start = Offset(cx - half, midY - half),
                end = Offset(cx + half, midY + half),
            ),
            topLeft = Offset(cx - half, midY - half),
            size = Size(edge, edge),
        )
        // Fine bright rim.
        drawRect(
            color = separator.node.copy(alpha = separator.node.alpha * 0.65f),
            topLeft = Offset(cx - half, midY - half),
            size = Size(edge, edge),
            style = Stroke(width = 0.75.dp.toPx()),
        )
    }
}

/**
 * Vertical transpose of [drawBeam] for draw-only gaps (Home Tome Collection divider).
 * Uses the same separator materials/stops with a 7.dp node so the glow fits a 24.dp gap.
 */
fun DrawScope.drawMimirVerticalBeam(
    palette: MimirThemePalette,
    centerX: Float,
    top: Float,
    height: Float,
) {
    val lineH = BeamLineThickness.toPx()
    val separator = palette.materials.separator
    val midY = top + height / 2f

    drawRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.24f to separator.shoulder,
            0.50f to separator.core,
            0.76f to separator.shoulder,
            1.0f to Color.Transparent,
        ),
        topLeft = Offset(centerX - lineH / 2f, top),
        size = Size(lineH, height),
    )

    drawRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.5f to separator.glow,
            1.0f to Color.Transparent,
        ),
        topLeft = Offset(centerX - lineH * 1.6f, top),
        size = Size(lineH * 3.2f, height),
    )

    val edge = VerticalBeamDiamondEdge.toPx()
    val half = edge / 2f

    drawCircle(
        color = separator.glow,
        radius = edge * 1.25f,
        center = Offset(centerX, midY),
        style = Fill,
    )

    rotateRad(radians = QUARTER_TURN_RAD, pivot = Offset(centerX, midY)) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(separator.node, separator.core, palette.surfaceBase),
                start = Offset(centerX - half, midY - half),
                end = Offset(centerX + half, midY + half),
            ),
            topLeft = Offset(centerX - half, midY - half),
            size = Size(edge, edge),
        )
        drawRect(
            color = separator.node.copy(alpha = separator.node.alpha * 0.65f),
            topLeft = Offset(centerX - half, midY - half),
            size = Size(edge, edge),
            style = Stroke(width = 0.75.dp.toPx()),
        )
    }
}

/** 45° in radians — the diamond's rotation. */
private const val QUARTER_TURN_RAD = 0.7853982f

/**
 * DEFAULT's lattice.
 *
 *  [line] arrives as `scene.shelfThread`, whose alpha
 * is baked at 8/255 for texture use — [LatticeAlphaScale] takes it the rest of the way down rather than
 * re-authoring the material, which several other textures also draw with.
 */
private fun DrawScope.drawArchitecturalGrid(line: Color) {
    val step = GridCell.toPx()
    if (step <= 0f) return
    val stroke = 1f
    val ink = line.copy(alpha = line.alpha * LatticeAlphaScale)
    var x = step
    while (x < size.width) {
        drawLine(color = ink, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = stroke)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(color = ink, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = stroke)
        y += step
    }
}
