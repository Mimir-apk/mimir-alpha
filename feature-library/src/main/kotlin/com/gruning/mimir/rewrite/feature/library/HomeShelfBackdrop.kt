package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FUX-F "Hearth" Home backdrop. The focused game's LOCAL preview clip
 * plays behind the Home Shelf under a LIFTED theme veil, with a static radial "hero pool" anchoring the
 * central slot. Video-only on Home: there is **no static-cover backdrop, ever** (dup-cover ban) — the
 * fallback for no-clip / Android / power-save / reduced-motion is achieved by NOT mounting this wrapper at
 * all, so today's plain Home gradient scene stays byte-identical (see [HomeShelfScreen]'s mount gate).
 *
 * This file keeps the request/idle logic in a PURE reducer ([HomeBackdropSleep]) so the timing races are
 * unit-tested off-device; the composable stays thin. The engine ([VideoBackdrop]) is untouched beyond its
 * two FUX-F defaulted params — everything it already owns (first-frame gate, null-request teardown,
 * power-save eligibility, lifecycle pause) rides along.
 */

// ── F3 tokens (zero new hex; all colour from the palette) ────────────────────────────────────────────

/** Hearth "chair pick" settle: focus must dwell this long before the Home clip binds a decoder. Longer than
 *  the gamelist's [BifrostMediaSettleMs] (300ms) so a browsed shelf never spins up a player per game. */
internal const val HomeBackdropSettleMs = 450L

/** Walk-32c: the longest an outgoing hero waits for the incoming one across a Home mode switch -- the shelf's own
 *  settle plus a page's settle cap -- so a hero that never decodes can only fall back to a cut, never hang. */
internal const val HomeHeroHandoffCapMs: Long = HomeBackdropSettleMs + MimirPageMotion.SettleCapMs

/** The ONE new duration FUX-F introduces: the idle-sleep fade of the wrapper's own alpha (1→0, then 0→1 on
 *  wake). The VIDEO fade in/out stays the engine's [BifrostVisualHandoffMs] (140ms) — no other animation. */
internal const val HomeBackdropIdleFadeMs = 400

/** Whisper-style idle: no Home input for this long → fade the backdrop out and release the decoder behind
 *  an already-invisible layer. SystemClock-based (see the composable); never wall-clock.
 *
 *  **MOTION ONLY.**  The sentence
 *  above names the reason itself: this exists to release a DECODER. A still hero has no decoder, no
 *  frame loop and no wakelock — it is a bitmap that is already resident — so the cost this timeout is
 *  paying for does not exist for it, and all the player gets is a Home screen that goes blank while they
 *  read it. [HomeShelfStaticBackdrop] passes `sleepEligible = false`; [HomeShelfBackdrop] keeps it. */
internal const val HomeBackdropIdleTimeoutMs = 45_000L

// ── The hero decode envelope: solved from the window, never from a panel ─────────────────────────────
//
// This block REPLACES a `1920x1080` literal — the Odin's exact panel — that every hero request site shared.
// A fixed panel size caused undersupply on other geometries: a 1440p-class handheld received a x1.40 UPSCALED hero, because the envelope named a
// panel instead of deriving one. Undersupply is now impossible by construction rather than tested against.
//
// The law: `envelope = ceil16(windowPx * focalOverscale)` per axis, where the overscale is the SAME pure
// [homeBackdropFocalTransform] the renderer applies — decode what will actually be displayed, not what fits
// the box before the focal stage re-centres it. No new tuning value is introduced by this law.

/** A solved decode envelope. [cacheTag] is the ONLY place the wire format of the identity is authored. */
internal data class MimirHeroDecodeEnvelope(val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0) { "hero decode envelope must be positive, was ${widthPx}x$heightPx" }
    }

    /** `-v3` supersedes `-v2`: v2 asserted the retired full-HD law, so the identity must not be reused. */
    val cacheTag: String get() = "${widthPx}x$heightPx-fill-v3"
}

/** The superseded full-HD law, retained ONLY as the pre-observation value — see [MimirHeroDecode]. */
internal val MimirFullHdHeroDecodeEnvelope = MimirHeroDecodeEnvelope(widthPx = 1920, heightPx = 1080)

/** Per-axis decode quantum. Quantizing absorbs the <0.2% window-box vs content-box overscale difference. */
internal const val MimirHeroDecodeQuantumPx = 16

/** [solveHomeShelfGeometry]'s own declared floor (`require(widthDp >= 320f && heightDp >= 300f)`). A window
 *  below it cannot render a shelf at all, so the envelope solves AT the floor rather than throwing inside a
 *  decode path — and the floor's overscale (1.1000) is the table's maximum, so it can only oversupply. */
internal const val MimirHeroDecodeSolverFloorWidthDp = 320f
internal const val MimirHeroDecodeSolverFloorHeightDp = 300f

internal fun mimirHeroDecodeCeilToQuantum(valuePx: Double): Int =
    (kotlin.math.ceil(valuePx / MimirHeroDecodeQuantumPx) * MimirHeroDecodeQuantumPx).toInt()

/**
 * THE law — pure, window px in, envelope out. Every hero request site resolves through this one function
 * (directly, or through [MimirHeroDecode]'s observed value) so prewarm and render keep ONE cache identity.
 *
 * Window px rather than a measured container, deliberately: three of the seven request sites (the Folio's
 * focused-neighbour prewarm, the Tome face readiness probe, the cold-start warm pass) have no composable box
 * to measure, and the window is the largest box any hero surface can fill — so an envelope solved from it can
 * never undersupply an inner box, and every site computes the IDENTICAL value. `Scale.FILL`'s source floor
 * means the resulting oversupply never upsamples a smaller source.
 */
internal fun mimirHeroDecodeEnvelopePx(
    windowWidthPx: Int,
    windowHeightPx: Int,
    density: Float,
): MimirHeroDecodeEnvelope {
    if (windowWidthPx <= 0 || windowHeightPx <= 0 || !density.isFinite() || density <= 0f) {
        return MimirFullHdHeroDecodeEnvelope
    }
    val boxWidthDp = (windowWidthPx / density).coerceAtLeast(MimirHeroDecodeSolverFloorWidthDp)
    val boxHeightDp = (windowHeightPx / density).coerceAtLeast(MimirHeroDecodeSolverFloorHeightDp)
    val overscale = homeBackdropFocalTransform(solveHomeShelfGeometry(boxWidthDp, boxHeightDp)).scale
    return MimirHeroDecodeEnvelope(
        widthPx = mimirHeroDecodeCeilToQuantum(windowWidthPx.toDouble() * overscale),
        heightPx = mimirHeroDecodeCeilToQuantum(windowHeightPx.toDouble() * overscale),
    )
}

/**
 * The one process-wide envelope every hero site reads.
 *
 * Why a holder rather than a parameter: four of the seven request sites live in separate files ([HomeTomeFace]'s readiness probe, [HomeTomeFolio]'s neighbour prewarm, and `app`'s cold-start warm
 * pass), and they reach the law through [MimirHomeStaticHeroDecodeWidthPx] / [mimirHomeStaticHeroCacheKey].
 * Keeping those symbol names means the law moves at all seven sites at once — which is the property that
 * keeps prewarm and render on one cache entry.
 *
 * The density used is `displayMetrics.density`, NOT `LocalDensity` — deliberately, because a non-composable
 * site cannot read `LocalDensity`, and two sources for one identity is exactly the drift this holder exists
 * to prevent. It only feeds the dp box the overscale is solved from (a 1.02–1.10 factor); the pixel counts
 * are exact either way, and the quantum absorbs the remainder.
 */
internal object MimirHeroDecode {
    @Volatile private var observed: MimirHeroDecodeEnvelope? = null
    @Volatile private var observedStamp: String? = null

    /** The display box, derived exactly as `MimirAppHost.rememberPrimaryMOrb` and [LibraryScreen] derive
     *  theirs: the APPLICATION context's metrics (an Activity context reports the window, not the display,
     *  since API 30) — so two surfaces cannot denominate the same identity against two different boxes. */
    fun observe(context: Context): MimirHeroDecodeEnvelope {
        val metrics = context.applicationContext.resources.displayMetrics
        return observe(metrics.widthPixels, metrics.heightPixels, metrics.density)
    }

    fun observe(windowWidthPx: Int, windowHeightPx: Int, density: Float): MimirHeroDecodeEnvelope {
        val stamp = "${windowWidthPx}x$windowHeightPx@$density"
        observed?.takeIf { observedStamp == stamp }?.let { return it }
        val solved = mimirHeroDecodeEnvelopePx(windowWidthPx, windowHeightPx, density)
        observed = solved
        observedStamp = stamp
        return solved
    }

    /**
     * The value the four foreign sites read. Before any surface has observed a context — the cold-start warm
     * pass can decode a hero before a hero surface has composed — this falls back to the system display box,
     * which agrees with the observed box on a landscape-native panel and costs one extra decode where it does
     * not. It is a fallback, not the law: the moment a hero surface composes, [observe] owns the identity.
     */
    val envelope: MimirHeroDecodeEnvelope get() = observed ?: systemDisplayEnvelope

    private val systemDisplayEnvelope: MimirHeroDecodeEnvelope by lazy {
        // Unit tests run against the android.jar stubs, where this throws; the retired literal is then the
        // deterministic value, and the law itself is pinned through the pure function above.
        runCatching {
            val metrics = Resources.getSystem().displayMetrics
            mimirHeroDecodeEnvelopePx(metrics.widthPixels, metrics.heightPixels, metrics.density)
        }.getOrElse { MimirFullHdHeroDecodeEnvelope }
    }

    internal fun resetObservedForTest() {
        observed = null
        observedStamp = null
    }
}

/** Recomposes (and re-observes) on a configuration change, so a resized window re-solves its envelope. */
@Composable
internal fun rememberMimirHeroDecodeEnvelope(): MimirHeroDecodeEnvelope {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { MimirHeroDecode.observe(context) }
}

/** The hero identity, authored once. [mimirHomeStaticHeroCacheKey] is this against the observed envelope. */
internal fun mimirHeroCacheKey(uri: String, envelope: MimirHeroDecodeEnvelope): String =
    "home-static-hero:$uri#${envelope.cacheTag}"

/**
 * The ONE hero request. Seven sites decode heroes; the four that this lane owns build them here, and the
 * three it does not reach the same envelope and the same key through the symbols below.
 */
internal fun mimirHomeStaticHeroRequest(
    context: Context,
    uri: String,
    envelope: MimirHeroDecodeEnvelope,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        // Heroes are full-bleed rather than tile art. FILL decode semantics preserve the source aspect while
        // decoding enough pixels for the final centre crop, rather than FIT-decoding a wide hero and then
        // upscaling that smaller bitmap into a soft backdrop. FILL never upsamples past the source.
        .size(envelope.widthPx, envelope.heightPx)
        .scale(Scale.FILL)
        .memoryCacheKey(mimirHeroCacheKey(uri, envelope))
        .placeholderMemoryCacheKey(mimirHeroCacheKey(uri, envelope))
        .crossfade(false)
        .build()

val MimirHomeStaticHeroDecodeWidthPx: Int get() = MimirHeroDecode.envelope.widthPx
val MimirHomeStaticHeroDecodeHeightPx: Int get() = MimirHeroDecode.envelope.heightPx

fun mimirHomeStaticHeroCacheKey(uri: String): String = mimirHeroCacheKey(uri, MimirHeroDecode.envelope)

/** Lifted Home veil profile (top→bottom alphas over [signatureBottom]). The mid band sits at 0.52 — lifted
 *  vs the gamelist [themeVeil]'s 0.44 — so frameless Home covers separate from the motion without shadows. */
// Backdrop contrast: mids −0.06; the heavy 0.72/0.92 tail is GONE because the
// footer reveal mask (screen-side) hands the backdrop off to the original scene under the dock.
internal val HomeVeilStops = floatArrayOf(0.88f, 0.54f, 0.46f, 0.46f, 0.52f, 0.58f, 0.58f)
/** Metallic themes use a nearly clear wash: localized chrome owns contrast, not a full-screen veil. */
internal val LightHomeVeilStops = floatArrayOf(0.06f, 0.03f, 0.02f, 0.01f, 0.02f, 0.03f, 0.04f)
internal val HomeVeilPositions = floatArrayOf(0.00f, 0.14f, 0.30f, 0.50f, 0.68f, 0.84f, 1.00f)

internal fun homeVeilStops(palette: MimirThemePalette): FloatArray =
    if (palette.usesNearClearMediaVeil) LightHomeVeilStops else HomeVeilStops

internal fun homeVeilInk(palette: MimirThemePalette): Color =
    if (palette.usesNearClearMediaVeil) palette.surfaceBase else palette.tileFillDeep

/** Static hero pool: a [signatureBottom] radial at this centre alpha fading to 0 at [HomeHeroPoolRadiusFactor]
 *  × heroSide, anchored behind the central hero slot. Static = zero per-frame cost; it deepens the stage
 *  under the hero cover so the frameless art reads as "standing in a pool", drawn in both the video and
 *  gradient-fallback states of the mounted wrapper. */
internal const val HomeHeroPoolCenterAlpha = 0.25f
internal const val HomeHeroPoolRadiusFactor = 1.4f

/** Palette-authored identity wash applied only while Home media is mounted. */
internal fun homeMediaTintVeilColor(palette: MimirThemePalette): Color =
    palette.presentation.homeMediaTint

/**
 * The lifted Home veil brush (single [MimirThemePalette.signatureBottom] hue at [HomeVeilStops] alphas over
 * [HomeVeilPositions] — the same vertical profile shape as the gamelist [themeVeil], mid band lifted). Built
 * once per palette by the caller (`remember(palette)`) and handed to [VideoBackdrop]'s `veil` param.
 */
internal fun homeVeil(palette: MimirThemePalette): Brush {
    // Standard themes retain the contrast stage. Metallic themes use a near-clear surface wash so their
    // backdrop keeps its authored media treatment while the header still gets a small readability lift.
    val ink = homeVeilInk(palette)
    val stops = homeVeilStops(palette)
    return Brush.verticalGradient(
        HomeVeilPositions[0] to ink.copy(alpha = stops[0]), // header
        HomeVeilPositions[1] to ink.copy(alpha = stops[1]),
        HomeVeilPositions[2] to ink.copy(alpha = stops[2]),
        HomeVeilPositions[3] to ink.copy(alpha = stops[3]), // lifted see-through middle
        HomeVeilPositions[4] to ink.copy(alpha = stops[4]),
        HomeVeilPositions[5] to ink.copy(alpha = stops[5]),
        HomeVeilPositions[6] to ink.copy(alpha = stops[6]), // tiles/footer
    )
}

/** Pre-solved hero-pool paint (px-space, cached per palette/geometry) so the draw allocates nothing. */
private data class HomeHeroPool(val brush: Brush, val center: Offset, val radius: Float)

/** The reveal mask's header hold ends this far ABOVE the title block's bottom (FUX-11b). */
internal const val HomeBackdropHeaderRevealHoldInsetDp = 12f

/** The reveal mask finishes dissolving this far BELOW the title block's bottom (FUX-11b). */
internal const val HomeBackdropHeaderRevealEndInsetDp = 22f
internal const val HomeBackdropHeaderEraseAlpha = 0f

/** Light media remains continuous under the footer; dark themes retain the authored scene handoff. */
internal fun homeBackdropFooterEraseAlpha(palette: MimirThemePalette): Float =
    if (palette.usesNearClearMediaVeil) 0f else 1f

/**
 * The header/footer reveal mask, shared by the clip and the chosen-still backdrops so both hand off to
 * the scene background identically.
 *
 * Footer reveal: the whole
 * composite fades out across the footer band, from the hint lane's top to the viewport bottom.
 * CP05 : the header no longer uses DstOut. The shared Home veil already carries
 * the requested subtle media presence across the whole screen while naturally blending into the title
 * band. Metallic themes also keep that continuity through the footer: their opaque quick-action controls
 * and localized text rails provide contrast without a full-width pale band.
 */
private fun homeBackdropRevealMask(
    geometry: HomeShelfGeometry,
    density: Density,
    palette: MimirThemePalette,
): Brush =
    with(density) {
        val height = geometry.viewport.bottom.dp.toPx()
        val footerStart = geometry.hintToastBand.top.dp.toPx() / height
        val footerEraseAlpha = homeBackdropFooterEraseAlpha(palette)
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = HomeBackdropHeaderEraseAlpha),
            footerStart.coerceIn(0f, 1f) to Color.Transparent,
            1f to Color.Black.copy(alpha = footerEraseAlpha),
            startY = 0f,
            endY = height,
        )
    }

/**
 * Focal-safe presentation transform for the Home backdrop media.
 *
 * The hero still and the preview clip both fill the WHOLE viewport with a centre crop, but the shared
 * reveal mask erases everything above the header dissolve and below the footer hand-off — so a
 * full-viewport centre put the media's focal centre under the erased header band, visibly cutting the
 * composition. This transform re-centres the media on the REVEAL-SAFE stage instead of the masked
 * full screen: a uniform overscale (aspect-preserving by construction — one factor on both axes over
 * an already centre-cropped fill) plus a vertical shift that moves the media centre onto the stage
 * centre while the enlarged bounds still cover the full viewport (no edge exposure under the mask's
 * dissolve ramps).
 *
 * Stage (dimension-based, from the SAME fields the mask reads, so they cannot drift):
 *   safeTop    = titleBand.bottom + [HomeBackdropHeaderRevealEndInsetDp]  (header fully dissolved)
 *   safeBottom = hintToastBand.top                                        (footer hand-off begins)
 *
 * shift = stageCentre − viewportCentre; scale = 1 + 2·|shift| / viewportHeight. A degenerate or
 * inverted stage yields the identity transform. Pure and dp-based so exact tests pin it off-device.
 */
internal data class HomeBackdropFocalTransform(val scale: Float, val translationYDp: Float) {
    companion object {
        val Identity = HomeBackdropFocalTransform(scale = 1f, translationYDp = 0f)
    }
}

internal fun homeBackdropFocalTransform(geometry: HomeShelfGeometry): HomeBackdropFocalTransform {
    val viewportHeight = geometry.viewport.height
    if (viewportHeight <= 1f) return HomeBackdropFocalTransform.Identity
    val safeTop = geometry.titleBand.bottom + HomeBackdropHeaderRevealEndInsetDp
    val safeBottom = geometry.hintToastBand.top
    if (safeBottom <= safeTop) return HomeBackdropFocalTransform.Identity
    val stageCenter = (safeTop + safeBottom) / 2f
    val shift = stageCenter - viewportHeight / 2f
    if (shift == 0f) return HomeBackdropFocalTransform.Identity
    return HomeBackdropFocalTransform(
        scale = 1f + 2f * kotlin.math.abs(shift) / viewportHeight,
        translationYDp = shift,
    )
}

/** One graphicsLayer application of the focal transform, shared verbatim by still and clip media. */
private fun Modifier.homeBackdropFocalMedia(
    transform: HomeBackdropFocalTransform,
    density: Density,
): Modifier = graphicsLayer {
    scaleX = transform.scale
    scaleY = transform.scale
    translationY = with(density) { transform.translationYDp.dp.toPx() }
}

/** Hero-pool centre (dp) + radius (dp) for the given hero slot — pure so F5 can pin it off-device. The pool
 *  follows the SCALED hero tile: on a ring shelf that is always slot 0 (centre), but on a fixed-layout shelf
 *  the emphasis travels to a side slot, so the centre X must track [slotBounds] not the geometric centre. */
internal data class HomeHeroPoolGeometry(val centerXDp: Float, val centerYDp: Float, val radiusDp: Float)

internal fun homeHeroPoolGeometry(geometry: HomeShelfGeometry, heroSlotOffset: Int): HomeHeroPoolGeometry =
    homeHeroPoolGeometry(geometry, heroSlotOffset.toFloat())

internal fun homeHeroPoolGeometry(geometry: HomeShelfGeometry, heroSlotOffset: Float): HomeHeroPoolGeometry {
    return HomeHeroPoolGeometry(
        centerXDp = geometry.centerAnchor + heroSlotOffset * geometry.slotStride,
        centerYDp = geometry.gameRailBand.top + geometry.heroSide / 2f,
        radiusDp = geometry.heroSide * HomeHeroPoolRadiusFactor,
    )
}

/**
 * F2/F5 eligibility ladder as a PURE decision: the eligible focused clip to mount the Hearth wrapper with, or
 * null to leave today's plain Home scene byte-identical. Each rung is unit-pinned (F5) so a future edit that
 * inverts one (e.g. `!reducedMotion` → `reducedMotion`, or the Android-row exclusion) fails loudly here
 * instead of silently mounting video where it must not (kill-switch residue, Android row, reduced motion).
 *
 * Rungs, in order → null: mode OFF/AURORA ([homeBackdropActive]) · reduced motion · focused row is any
 * Android row · power-save or blank/remote clip ([eligibleBifrostVideoUri]).
 *
 * removed the separate `HOME_BACKDROP_ENABLED` build kill-switch: the Settings
 * two-state choice is the sole on/off authority, so [mode] alone gates the mount.
 */
internal fun homeBackdropMountClip(
    mode: GamelistBackdropMode,
    reducedMotion: Boolean,
    focusedItemId: HomeShelfItemId?,
    focusedPreviewVideoUri: String?,
    powerSave: Boolean,
): String? = (
    homeBackdropMount(
        mode = mode,
        reducedMotion = reducedMotion,
        focusedItemId = focusedItemId,
        focusedPreviewVideoUri = focusedPreviewVideoUri,
        powerSave = powerSave,
    ) as? HomeBackdropMount.Video
    )?.uri

/** What Bifröst should mount behind the focused row, or null for today's plain scene. */
internal sealed interface HomeBackdropMount {
    /** A clip for the settled, power-aware video engine. */
    data class Video(val uri: String) : HomeBackdropMount

    /** A still image rendered as a static backdrop — no decoder, no motion. */
    data class Static(val uri: String) : HomeBackdropMount
}

/**
 * UXP-01 + Bifröst : the per-game CHOSEN hero wins over the automatic one.
 *
 * The chosen preview clip and chosen static hero are independent. An eligible chosen clip plays first;
 * its static hero is the no-decoder fallback; with neither override, automatic behaviour is unchanged.
 *
 * [chosenHeroUri] is always a LOCAL file that `CustomHeroStore` already downloaded and validated — the
 * renderer never sees a remote URL, which is what keeps the no-network-on-navigation contract intact.
 *
 * Reduced motion still suppresses the whole backdrop, including a chosen STATIC hero. A still image
 * arguably carries no motion, but the brief says preserve the reduced-motion contract, so the strict
 * reading stands until the reduced-motion policy changes.
 */
internal fun homeBackdropMount(
    mode: GamelistBackdropMode,
    reducedMotion: Boolean,
    focusedItemId: HomeShelfItemId?,
    focusedPreviewVideoUri: String?,
    powerSave: Boolean,
    chosenHeroUri: String? = null,
    chosenPreviewVideoUri: String? = null,
    focusedHeroUri: String? = null,
): HomeBackdropMount? {
    if (!homeBackdropActive(mode)) return null
    if (reducedMotion) return null
    // Android package rows accept only their explicit local Home Hero. They never inherit an automatic
    // game clip (nor leave the previous emulated row's video standing behind them).
    if (focusedItemId is HomeShelfItemId.AndroidGame || focusedItemId is HomeShelfItemId.AndroidApp) {
        return chosenHeroUri?.let(HomeBackdropMount::Static)
    }
    eligibleBifrostVideoUri(chosenPreviewVideoUri, powerSave)?.let {
        return HomeBackdropMount.Video(it)
    }
    if (chosenHeroUri != null) return HomeBackdropMount.Static(chosenHeroUri)
    // A chosen clip that power-save suppresses must not silently substitute a different automatic clip.
    if (chosenPreviewVideoUri != null) return null
    eligibleBifrostVideoUri(focusedPreviewVideoUri, powerSave)?.let {
        return HomeBackdropMount.Video(it)
    }
    // **The rung that was missing.** Until 2026-09-17 this function ended at the line above, so the ONLY
    // automatic backdrop Home could ever mount was a clip. Automatic Hero Art -- the `-hero.png` that
    // enrichment downloads for every game -- had no path to this surface at all.
    //
    // It went unnoticed for as long as it did because something else was filling the gap: 86 foreign
    // `.mp4` files that a previous frontend had left beside the ROMs were being adopted as
    // [focusedPreviewVideoUri], so Home always had "a clip" and always mounted Video. When that adoption
    // was correctly stopped (a clip must be asked for, not inherited), the clip went away, this ladder ran
    // off its end, and Home mounted nothing -- while the Library, which reads `GameRow.heroArtUri`
    // directly, went on showing the hero. That asymmetry is what the regression report described: "Home shelf still
    // shows no hero art behind, but library does."
    //
    // It also made the Bifrost setting's own words false. That row promises "Hero Motion when you have
    // chosen one in Customize Media, otherwise Hero Art" -- and there was no otherwise.
    return focusedHeroUri?.let(HomeBackdropMount::Static)
}

// ── F2 pure reducer: idle sleep (Whisper) ────────────────────────────────────────────────────────────

/**
 * Pure idle-sleep state for the Hearth backdrop. No wall-clock and no Compose: the composable feeds
 * `SystemClock.uptimeMillis()` and the wrapper's own fade-completion signal, and F5 exercises the state
 * machine directly.
 *
 * The invariant this encodes (F5, priority attack surface): the wrapper alpha reaches 0 (the fade) BEFORE
 * the video request is released — the decoder is torn down behind an already-invisible layer, never a
 * visible pop. That is why [alphaTarget] drops to 0 in [Phase.SLEEPING] but [requestReleased] only becomes
 * true in [Phase.ASLEEP], and ASLEEP is reachable ONLY via [onFadeComplete] from SLEEPING.
 */
internal data class HomeBackdropSleep(
    val phase: Phase = Phase.AWAKE,
    val lastInputMillis: Long = 0L,
) {
    internal enum class Phase { AWAKE, SLEEPING, ASLEEP }

    /** Wrapper alpha target — awake shows the backdrop; sleeping and asleep hold it faded out. */
    val alphaTarget: Float get() = if (phase == Phase.AWAKE) 1f else 0f

    /** True once the fade has fully completed and the video request must be nulled (ASLEEP only). */
    val requestReleased: Boolean get() = phase == Phase.ASLEEP

    /** Any Home input wakes: back to AWAKE and restart the idle countdown from [nowMillis]. */
    fun onInput(nowMillis: Long): HomeBackdropSleep =
        if (phase == Phase.AWAKE && lastInputMillis == nowMillis) {
            this
        } else {
            HomeBackdropSleep(Phase.AWAKE, nowMillis)
        }

    /**
     * Clock reached the idle deadline: begin the fade. Meaningful only while still AWAKE.
     *
     * [sleepEligible] is false for a STILL hero, which never sleeps — see [HomeBackdropIdleTimeoutMs].
     * The rule lives here rather than only in the composable's countdown so it is falsifiable off-device
     * and so a future caller cannot reach SLEEPING for a still by driving the reducer directly. It
     * defaults to true because every existing caller is the clip path, whose behaviour is unchanged.
     */
    fun onIdleElapsed(nowMillis: Long, sleepEligible: Boolean = true): HomeBackdropSleep =
        if (sleepEligible && phase == Phase.AWAKE && nowMillis - lastInputMillis >= HomeBackdropIdleTimeoutMs) {
            copy(phase = Phase.SLEEPING)
        } else {
            this
        }

    /** The wrapper's alpha animation reached 0 — NOW (and only now) release the video request. A stale
     *  fade completion after a wake (phase already AWAKE) is ignored. */
    fun onFadeComplete(): HomeBackdropSleep =
        if (phase == Phase.SLEEPING) copy(phase = Phase.ASLEEP) else this
}

private data class HomeBackdropRuntime(
    /**
     * The idle sleep/wake fade, as the live State. Both hosts read it inside their `graphicsLayer {}` lambda, so the
     * [HomeBackdropIdleFadeMs] ramp is a draw-phase update: handing the host a Float here recomposed it (and the
     * VideoBackdrop under it) on every frame of the fade.
     */
    val alpha: State<Float>,
    val requestGated: Boolean,
)

/** Automatic synchronized Tome rotations are media changes, not user activity. */
internal fun homeBackdropWakeContextEpoch(contextEpoch: Int, contextChangesWake: Boolean): Int =
    if (contextChangesWake) contextEpoch else 0

internal fun homeTomeBackdropCompositeVisible(imageUri: String?): Boolean = imageUri != null

/**
 * The still backdrop's handoff clock, with reduced motion folded in.
 *
 * RC-34 made this necessary rather than merely tidy. Until the two Tome hero stages were merged into
 * one mounted host, a Tome opening flipped between two call sites, so it DISPOSED and REMOUNTED —
 * which under reduced motion happened to produce the right answer (an instant swap) for entirely the
 * wrong reason. One host means a real crossfade runs, and it would run for reduced-motion players too
 * unless the clock is told. Zero is the same reduced-motion grammar the Folio's own opening uses
 * (`tween(if (reducedMotion) 0 else MimirMotion.SceneMs)`) and that `HomeTomeFace` uses for the cover
 * plate's dissolve, so all three agree rather than each inventing a suppression.
 */
internal fun homeBackdropHandoffMs(durationMs: Int, reducedMotion: Boolean): Int =
    if (reducedMotion) 0 else durationMs

/**
 * The Tome stage's handoff clock on a RETURN, where the return value is zero.
 *
 * A return's destination is remembered and arrives at rest; the departure is the animated subject.
 * Two animations played end to end create a seam that reads as jitter.
 *
 * **That is exactly the shape of the Tome return today.** Closing the Folio flips `tomeFolioActive`
 * false on the commit frame, so `homeShelfTomeStageArt` reverts from the focused member's hero to the
 * collection lead art immediately — and because the two Tome stages are now ONE mounted host (RC-34),
 * that URI change is a real animated media handoff on [HomeTomePreviewFadeMs] (the overlay clock, 180 ms; it was 240) (a `Crossfade`
 * in the earlier implementation; the bed/veil form since CP01-b — same clock either way). Meanwhile the Folio
 * itself is still dissolving on top. Two clocks, two subjects, one press: the destination announcing
 * itself underneath a departure that has not finished leaving.
 *
 * The destination is the remembered thing and must not announce. So the stage art
 * changes **at rest** — one frame, no crossfade — leaving the departure as the only animated subject.
 *
 * **Why snapping here is not itself a pop: the swap lands on the most COVERED frame the return has.**
 * That reason used to be stated two ways, hedged against a host patch that had not landed yet. It has
 * landed, and the reason is now simply stronger, so the hedge is gone.
 *
 * On the first frame of the return, `MimirAppHost` has the Folio's **recorded pixels** at full alpha over
 * 90% x 90%, a **host-owned scrim at full depth** over the other 10%, and the shelf under its **full
 * 18 dp backdrop blur** — all three held for `MimirMotion.OverlayDissolveCoverMs` before anything begins
 * to unwind. The frame is not merely maximally blurred, which is what this KDoc used to claim: it is
 * genuinely covered. A hero swap there is unobservable.
 *
 * Every LATER frame is strictly more exposed than this one, so if a swap must happen this is where it is
 * cheapest. Holding the member art until the departure completes would be strictly worse: it moves the
 * same swap into the clear, which is the arrangement the return-grammar spec ranks last.
 *
 * [atRest] wins over [reducedMotion] only in the sense that both answer zero; they are independent
 * reasons and neither is derived from the other.
 */
internal fun homeTomeStageHandoffMs(durationMs: Int, reducedMotion: Boolean, atRest: Boolean): Int =
    if (atRest) 0 else homeBackdropHandoffMs(durationMs, reducedMotion)

/**
 * Shared settle / launch / restoration / idle-sleep gate for both chosen stills and clips.
 *
 * @param sleepEligible whether the idle sleep applies at all. False for a still hero, which has no decoder
 *   to release and therefore nothing to save by going blank — see [HomeBackdropIdleTimeoutMs]. Deliberately
 *   has NO default: there are exactly two callers, one of each kind, and a default would let a third
 *   inherit the wrong one silently.
 */
@Composable
private fun rememberHomeBackdropRuntime(
    launching: Boolean,
    launchReturnRestoring: Boolean,
    contextEpoch: Int,
    wakeSignal: Any?,
    sleepEligible: Boolean,
    contextSettleMs: Long = HomeBackdropSettleMs,
    contextChangesWake: Boolean = true,
): HomeBackdropRuntime {
    var sleep by remember { mutableStateOf(HomeBackdropSleep(lastInputMillis = SystemClock.uptimeMillis())) }
    var firstWake by remember { mutableStateOf(true) }
    val wakeContextEpoch = homeBackdropWakeContextEpoch(contextEpoch, contextChangesWake)
    LaunchedEffect(wakeSignal, wakeContextEpoch, launching) {
        if (firstWake) {
            firstWake = false
            return@LaunchedEffect
        }
        sleep = sleep.onInput(SystemClock.uptimeMillis())
    }
    LaunchedEffect(sleep.lastInputMillis, sleep.phase, sleepEligible) {
        // A still hero never starts the countdown at all, so there is no pending `delay` sitting on a
        // surface that is never going to act on it.
        if (!sleepEligible) return@LaunchedEffect
        if (sleep.phase != HomeBackdropSleep.Phase.AWAKE) return@LaunchedEffect
        val remaining = sleep.lastInputMillis + HomeBackdropIdleTimeoutMs - SystemClock.uptimeMillis()
        if (remaining > 0L) delay(remaining)
        sleep = sleep.onIdleElapsed(SystemClock.uptimeMillis(), sleepEligible = true)
    }

    val alpha = remember { Animatable(1f) }
    LaunchedEffect(sleep.phase) {
        val target = sleep.alphaTarget
        alpha.animateTo(target, tween(durationMillis = HomeBackdropIdleFadeMs, easing = MimirMotion.Easing))
        if (target == 0f && sleep.phase == HomeBackdropSleep.Phase.SLEEPING) {
            sleep = sleep.onFadeComplete()
        }
    }

    // Nova hero-loading regression, 2026-09-30: stopping a traverse on its original tile could leave the hero absent. Only the FIRST context is exempt from the dwell, and only once. This used to exempt the epoch
    // the host was born with instead, so a move back onto the landing tile inside the window took the exemption with
    // the gate still raised by the move away, and nothing was left running to lower it: the request stayed nulled until
    // focus left that tile again. Home lands on that tile every time (cold start, every return from the Library), so a
    // traverse that stopped where it began kept the bare scene for good. A return to it now dwells like any other tile.
    var firstContext by remember { mutableStateOf(true) }
    var contextSettling by remember { mutableStateOf(false) }
    LaunchedEffect(contextEpoch, contextSettleMs) {
        if (firstContext) {
            firstContext = false
            return@LaunchedEffect
        }
        if (contextSettleMs <= 0L) {
            contextSettling = false
            return@LaunchedEffect
        }
        contextSettling = true
        delay(contextSettleMs)
        contextSettling = false
    }
    return HomeBackdropRuntime(
        alpha = alpha.asState(),
        requestGated = launching || launchReturnRestoring || contextSettling || sleep.requestReleased,
    )
}

// ── F2 composable (thin): mounts the engine, gates the request, draws the pool ───────────────────────

/**
 * The mounted Hearth wrapper. Mount eligibility (mode / kill-switch / clip / Android / power-save /
 * reduced-motion) is decided by the CALLER ([HomeShelfScreen]) so an ineligible Home is byte-identical;
 * here we own only the request gating and the static pool.
 *
 * @param clip the eligible focused-row preview clip (already local-only + power-save filtered, non-blank).
 * @param launching true while a Home launch is armed ([HomeShelfCoordinator.launchingRow] non-null) — the
 *   request nulls immediately so the decoder releases ≤500ms after arming.
 * @param launchReturnRestoring true until an exact-return cover clears — keeps the request null through the
 *   return so a stale clip never flashes under the restoration.
 * @param contextEpoch the FUX-C context-cycle epoch — a change nulls the request through a settle window so
 *   the previous context's clip cannot bleed across the row-holder hold.
 * @param wakeSignal any value that changes on Home input (focus/scope) — resets the idle countdown.
 * @param heroSlotOffset the slot the scaled hero tile currently renders at (0 on ring shelves; on a fixed-layout shelf the
 *   emphasis travels, so the pool must follow it, not sit at the geometric centre). A lambda, read only in the pool's layer
 *   lambda: the shelf's ring glide is a draw-phase update here and recomposes no backdrop.
 */
@Composable
internal fun HomeShelfBackdrop(
    clip: String,
    geometry: HomeShelfGeometry,
    launching: Boolean,
    launchReturnRestoring: Boolean,
    contextEpoch: Int,
    wakeSignal: Any?,
    heroSlotOffset: () -> Float,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val density = LocalDensity.current
    val veil = remember(palette) { homeVeil(palette) }
    val mediaTintVeil = remember(palette) { homeMediaTintVeilColor(palette) }
    // Cache one centre-slot pool and translate its layer with the shared emphasis motion. Rebuilding a radial
    // brush for every animated offset frame would allocate on the render path.
    val pool = remember(palette, geometry, density) {
        with(density) {
            val solved = homeHeroPoolGeometry(geometry, 0)
            val center = Offset(x = solved.centerXDp.dp.toPx(), y = solved.centerYDp.dp.toPx())
            val radius = solved.radiusDp.dp.toPx()
            HomeHeroPool(
                brush = Brush.radialGradient(
                    colors = listOf(
                        // FUX-02 R5: pool ink rides the same contrast base as the veil (never gold).
                        palette.tileFillDeep.copy(alpha = HomeHeroPoolCenterAlpha),
                        palette.tileFillDeep.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radius,
                ),
                center = center,
                radius = radius,
            )
        }
    }

    val runtime = rememberHomeBackdropRuntime(
        launching = launching,
        launchReturnRestoring = launchReturnRestoring,
        contextEpoch = contextEpoch,
        wakeSignal = wakeSignal,
        // A clip IS the decoder this timeout was written for.
        sleepEligible = true,
    )
    val requestedUri = clip.takeUnless { runtime.requestGated }

    // Footer reveal: the whole
    // composite (video + veil + pool) fades out across the footer band, from the hint lane's top to
    // the viewport bottom, handing off to the scene's own background under the dock. Cached per
    // geometry; DstOut on an offscreen layer erases the composite where the mask is opaque.
    // Header reveal: the HEADER gets the same reveal as the footer — the composite fades out toward
    // the top so the corner title block and the status pill sit on the original scene background.
    val footerMask = remember(geometry, density, palette) {
        homeBackdropRevealMask(geometry, density, palette)
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = runtime.alpha.value
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(brush = footerMask, blendMode = BlendMode.DstOut)
            },
    ) {
        // the clip re-centres on the reveal-safe stage, identically to
        // the chosen still — the SAME pure transform, applied to the media layer only (veil/floor stay).
        val focalTransform = remember(geometry) { homeBackdropFocalTransform(geometry) }
        VideoBackdrop(
            videoUri = requestedUri,
            // Video-only on Home: NO static cover ever (dup-cover ban). artUri = null suppresses both the
            // cover layer and the no-clip cover fallback, leaving the veiled video / gradient floor.
            artUri = null,
            veil = veil,
            settleMs = HomeBackdropSettleMs,
            mediaModifier = Modifier.homeBackdropFocalMedia(focalTransform, density),
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(mediaTintVeil))
        // The static hero pool sits OVER the veiled video but UNDER the shelf tiles (which are drawn by the
        // caller, above this whole wrapper) — a soft, cached, zero-per-frame darkening under the hero.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = with(density) {
                        (heroSlotOffset() * geometry.slotStride).dp.toPx()
                    }
                }
                .drawBehind { drawCircle(brush = pool.brush, radius = pool.radius, center = pool.center) },
        )
    }
}

/**
 * UXP-01: the per-game CHOSEN STATIC hero, rendered as a still backdrop.
 *
 * Wears the same veil, hero pool and header/footer reveal mask as [HomeShelfBackdrop] so a chosen still
 * and a chosen clip sit in the identical scene. It mounts no decoder, but deliberately shares the clip
 * path's settle/cancellation/launch/return/idle-sleep lifecycle so a chosen still cannot bleed between
 * contexts or remain live forever while Home is idle. A non-null [imageUri] is always a validated local
 * file; null is the synchronized Tome handoff's explicit "return to the plain Home atmosphere" state.
 */
@Composable
internal fun HomeShelfStaticBackdrop(
    imageUri: String?,
    geometry: HomeShelfGeometry,
    launching: Boolean,
    launchReturnRestoring: Boolean,
    contextEpoch: Int,
    wakeSignal: Any?,
    heroSlotOffset: () -> Float,
    modifier: Modifier = Modifier,
    contextSettleMs: Long = HomeBackdropSettleMs,
    synchronizedTomeHandoff: Boolean = false,
    /** Suppresses every handoff clock in this host — see [homeBackdropHandoffMs]. */
    reducedMotion: Boolean = false,
    /**
     * A RETURN is in flight, so the Tome stage's art changes at rest rather than crossfading — see
     * [homeTomeStageHandoffMs]. Defaults false, which is byte-identical to the previous behaviour.
     *
     * Deliberately scoped to the SYNCHRONIZED Tome presence/media clock only. The ordinary still path's
     * [stillRevealMs] is the decoder's own hydrate reveal, not a transition between two known states, and
     * suppressing it would make an undecoded hero pop in rather than arrive.
     */
    tomeStageArrivesAtRest: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val density = LocalDensity.current
    val veil = remember(palette) { homeVeil(palette) }
    val mediaTintVeil = remember(palette) { homeMediaTintVeilColor(palette) }
    val pool = remember(palette, geometry, density) {
        with(density) {
            val solved = homeHeroPoolGeometry(geometry, 0)
            val center = Offset(x = solved.centerXDp.dp.toPx(), y = solved.centerYDp.dp.toPx())
            val radius = solved.radiusDp.dp.toPx()
            HomeHeroPool(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.tileFillDeep.copy(alpha = HomeHeroPoolCenterAlpha),
                        palette.tileFillDeep.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radius,
                ),
                center = center,
                radius = radius,
            )
        }
    }
    val revealMask = remember(geometry, density, palette) {
        homeBackdropRevealMask(geometry, density, palette)
    }
    val runtime = rememberHomeBackdropRuntime(
        launching = launching,
        launchReturnRestoring = launchReturnRestoring,
        contextEpoch = contextEpoch,
        wakeSignal = wakeSignal,
        // a still hero does not go away while you look at it. Everything else
        // this runtime gates -- launch, return restoration, context settle -- still applies.
        sleepEligible = false,
        contextSettleMs = contextSettleMs,
        contextChangesWake = !synchronizedTomeHandoff,
    )
    val desiredUri = imageUri.takeUnless { runtime.requestGated }
    val presenceFadeMs = homeTomeStageHandoffMs(
        durationMs = HomeTomePreviewFadeMs,
        reducedMotion = reducedMotion,
        atRest = tomeStageArrivesAtRest,
    )
    val stillRevealMs = homeBackdropHandoffMs(MimirMotion.OverlayMs, reducedMotion)
    // Keep the last decoded still mounted while it dissolves. Replacing the request immediately made
    // focus/context/launch changes snap the old hero to transparent before the new hero could hydrate.
    val context = LocalContext.current
    val heroEnvelope = rememberMimirHeroDecodeEnvelope()
    // A hero already in memory is on screen from Home's first frame when Home composes again (every
    // return from the Library), instead of fading in over OverlayMs after the destination switch has ended.
    val heroWarmAtBirth = remember {
        desiredUri != null && !synchronizedTomeHandoff &&
            context.imageLoader.memoryCache?.get(MemoryCache.Key(mimirHeroCacheKey(desiredUri, heroEnvelope))) != null
    }
    val artReveal = remember { Animatable(if (heroWarmAtBirth) 1f else 0f) }
    var presentedUri by remember { mutableStateOf(desiredUri.takeIf { heroWarmAtBirth }) }
    var synchronizedPresentedUri by remember {
        mutableStateOf(desiredUri.takeIf { synchronizedTomeHandoff })
    }
    val synchronizedPresence = remember {
        Animatable(
            if (synchronizedTomeHandoff && desiredUri != null) 1f else 0f,
        )
    }
    // Nova hero-handoff regression, 2026-09-30. The switch flips this host between its two painters, and the flip cut
    // whichever was showing while the other started from nothing: the hero vanished on the press and the new one
    // arrived after the band had settled (5x, both directions). Now the outgoing hero holds until the incoming one is
    // on screen. Going to a Tome, its composite takes the still already showing as its bed (the ledger's first
    // arrival) and ramps the Tome's art over it once that has decoded; coming back, it holds until the shelf's still
    // has revealed beneath it, then dissolves. Both waits are capped ([HomeHeroHandoffCapMs]), so a hero that never
    // decodes falls back to the cut. Only a fully shown still is adopted, so the swap is pixel for pixel.
    val adoptedStill = remember(synchronizedTomeHandoff) {
        Snapshot.withoutReadObservation {
            presentedUri.takeIf { synchronizedTomeHandoff && imageUri != null && artReveal.value >= 1f }
        }
    }
    var synchronizedBedShown by remember { mutableStateOf(false) }
    val currentImageUri by rememberUpdatedState(imageUri)
    LaunchedEffect(desiredUri, synchronizedTomeHandoff) {
        // The synchronized Tome branch owns a paired, already-prewarmed request below. Keeping this
        // ordinary still painter alive as well would duplicate work and could contend with rotation.
        if (synchronizedTomeHandoff) {
            // Walk-32c: an adopted still stays drawn under the Tome composite until that composite shows it itself.
            if (adoptedStill != null) {
                withTimeoutOrNull(HomeHeroHandoffCapMs) {
                    snapshotFlow { synchronizedBedShown && synchronizedPresence.value >= 1f }.first { it }
                }
            }
            artReveal.snapTo(0f)
            presentedUri = null
            return@LaunchedEffect
        }
        if (desiredUri == presentedUri) return@LaunchedEffect
        if (presentedUri != null && artReveal.value > 0f) {
            artReveal.animateTo(
                0f,
                tween(stillRevealMs, easing = MimirMotion.Easing),
            )
        }
        presentedUri = desiredUri
        artReveal.snapTo(0f)
    }
    val request = remember(presentedUri, context, heroEnvelope) {
        presentedUri?.let { uri -> mimirHomeStaticHeroRequest(context, uri, heroEnvelope) }
    }
    val painter = rememberAsyncImagePainter(request)
    val artReady = when (val state = painter.state) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> state.painter != null
        else -> false
    }
    LaunchedEffect(presentedUri, artReady) {
        if (presentedUri != null && artReady && artReveal.value < 1f) {
            artReveal.animateTo(
                1f,
                tween(stillRevealMs, easing = MimirMotion.Easing),
            )
        }
    }
    LaunchedEffect(desiredUri, synchronizedTomeHandoff) {
        if (!synchronizedTomeHandoff) {
            // CP03 Direction B (tome -> carousel): this was a bare `snapTo(0f)` -- a ONE-FRAME cut of the
            // outgoing tome art while the carousel's own backdrop is still decoding, so the theme ground
            // is briefly alone on screen. Same symptom as Direction A, reached without any unmount.
            //
            // Fade it out instead, on the clock the `nextUri == null` branch below already uses for
            // exactly this situation: the outgoing art keeps owning the surface until it is invisible,
            // and only then is the composite cleared. No new clock, no new token -- this branch simply
            // was not given the treatment its sibling already had.
            if (synchronizedPresentedUri != null && synchronizedPresence.value > 0f) {
                // Walk-32c: this composite is still drawn over the shelf's still, so it holds until that has
                // revealed beneath it and then dissolves over it, instead of leaving the bare scene between them.
                if (currentImageUri != null) {
                    withTimeoutOrNull(HomeHeroHandoffCapMs) { snapshotFlow { artReveal.value >= 1f }.first { it } }
                }
                synchronizedPresence.animateTo(
                    0f,
                    tween(presenceFadeMs, easing = MimirMotion.Easing),
                )
            }
            synchronizedPresentedUri = null
            synchronizedBedShown = false
            synchronizedPresence.snapTo(0f)
            return@LaunchedEffect
        }
        // Walk-32c: going to a Tome, take the still the shelf is showing as this composite's bed and show it the frame
        // its painter has it, so the Tome's own art ramps over it below rather than in from the bare scene.
        if (synchronizedPresentedUri == null && adoptedStill != null) {
            synchronizedBedShown = false
            synchronizedPresentedUri = adoptedStill
            synchronizedPresence.snapTo(0f)
            withTimeoutOrNull(HomeHeroHandoffCapMs) { snapshotFlow { synchronizedBedShown }.first { it } }
            synchronizedPresence.snapTo(1f)
        }
        val nextUri = desiredUri
        when {
            nextUri == null -> {
                if (synchronizedPresentedUri != null && synchronizedPresence.value > 0f) {
                    synchronizedPresence.animateTo(
                        0f,
                        tween(presenceFadeMs, easing = MimirMotion.Easing),
                    )
                }
                // Clear only after the complete composite is invisible. A later null→URI mount then
                // starts with the destination already installed and receives exactly one presence fade.
                synchronizedPresentedUri = null
                synchronizedBedShown = false
                synchronizedPresence.snapTo(0f)
            }

            synchronizedPresentedUri == null -> {
                synchronizedPresentedUri = nextUri
                synchronizedPresence.snapTo(0f)
                synchronizedPresence.animateTo(
                    1f,
                    tween(presenceFadeMs, easing = MimirMotion.Easing),
                )
            }

            synchronizedPresentedUri == nextUri -> {
                if (synchronizedPresence.value < 1f) {
                    synchronizedPresence.animateTo(
                        1f,
                        tween(presenceFadeMs, easing = MimirMotion.Easing),
                    )
                }
            }

            synchronizedPresence.value >= 1f -> {
                // URI→URI: only this target changes, so only the media bed/veil handoff runs.
                synchronizedPresentedUri = nextUri
            }

            else -> {
                // A new Tome arrives while the old one is still fading toward null. Finish hiding the
                // stale frame, install the new URI at zero opacity, then perform one presence fade.
                synchronizedPresence.animateTo(
                    0f,
                    tween(presenceFadeMs, easing = MimirMotion.Easing),
                )
                synchronizedPresentedUri = nextUri
                synchronizedPresence.snapTo(0f)
                synchronizedPresence.animateTo(
                    1f,
                    tween(presenceFadeMs, easing = MimirMotion.Easing),
                )
            }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = runtime.alpha.value
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(brush = revealMask, blendMode = BlendMode.DstOut)
            },
    ) {
        // the hero still re-centres on the reveal-safe stage with the
        // SAME pure transform the clip path applies — aspect preserved, veil/pool stay stage-anchored.
        val focalTransform = remember(geometry) { homeBackdropFocalTransform(geometry) }
        // Walk-32c: across a mode switch both composites are drawn, the Tome's over the shelf's, so the outgoing hero is
        // still on screen while the incoming one takes over (see adoptedStill). Each mounts only while it has something
        // to show, so a settled screen draws exactly the one composite it drew before.
        if (!synchronizedTomeHandoff || presentedUri != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .homeBackdropFocalMedia(focalTransform, density)
                    .graphicsLayer { alpha = artReveal.value },
            )
            // The same veil the video path paints over its clip, so contrast under title/tiles matches.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(brush = veil) })
            Box(Modifier.fillMaxSize().background(mediaTintVeil))
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = with(density) {
                            (heroSlotOffset() * geometry.slotStride).dp.toPx()
                        }
                    }
                    .drawBehind {
                        drawCircle(brush = pool.brush, radius = pool.radius, center = pool.center)
                    },
            )
        }
        if (synchronizedTomeHandoff || synchronizedPresentedUri != null) {
            // A Tome rotation hands off MEDIA only. Veil/pool density stays constant across URI→URI,
            // preventing the two partially-transparent chrome layers from producing a brightness pulse.
            // Presence changes fade this one complete composite, so URI→null still leaves no residue.
            // CP01-b finished this comment's own job: the media used to sit in a Crossfade, whose two
            // src-over children at complementary alpha dropped the whole hero to ~75% opacity mid-swap
            // over the transparent stage — the same arithmetic this block already banned for the chrome.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = synchronizedPresence.value },
            ) {
                // Do not keep a null target in the media stack: null↔URI is owned exclusively by the
                // complete-composite presence animation above, while URI↔URI remains media-only.
                synchronizedPresentedUri?.let { presentedSynchronizedUri ->
                    HomeSynchronizedTomeBedVeil(
                        incomingUri = presentedSynchronizedUri,
                        rampMs = presenceFadeMs,
                        focalTransform = focalTransform,
                        density = density,
                        onBedReady = { synchronizedBedShown = true },
                    )
                }
                Box(Modifier.fillMaxSize().drawBehind { drawRect(brush = veil) })
                Box(Modifier.fillMaxSize().background(mediaTintVeil))
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = with(density) {
                                (heroSlotOffset() * geometry.slotStride).dp.toPx()
                            }
                        }
                        .drawBehind {
                            drawCircle(brush = pool.brush, radius = pool.radius, center = pool.center)
                        },
                )
            }
        }
    }
}

/**
 * CP01-b, same beat as the plate: the 1080-class hero and the Tome plate begin the same [HomeTomePreviewFadeMs] handoff off one decode-ready
 * snapshot, and the hero's old `Crossfade` carried the identical `e + (1−e)²` arithmetic — over the
 * TRANSPARENT stage, so the whole backdrop went translucent to ~75% mid-swap instead of dark. Same
 * correction: the committed hero holds fully opaque as the bed, only the incoming ramps.
 *
 * [rampMs] is `presenceFadeMs`, exactly the clock the Crossfade used — it already answers 0 for reduced
 * motion and for at-rest returns upstream (`homeTomeStageHandoffMs`), so this composable deliberately
 * takes no `reducedMotion` of its own: one decision, threaded, never re-derived. At 0 the veil snaps and
 * commits in one frame, which keeps `HomeTomeReturnAtRestTest`'s covered-frame swap a true single-frame
 * cut — and strictly better than the old duration-0 Crossfade, whose freshly-keyed child could paint
 * nothing for a frame while the outgoing was already gone.
 *
 * One loop, one `key()` per URI: at commit the veil entry becomes the bed entry of the same list, so the
 * keyed group MOVES and its painter survives. Two role-slots would remount the painter on the commit
 * frame — and a fresh `AsyncImagePainter` is `State.Empty` on its first composition regardless of cache
 * warmth, which would hole the full-screen hero on the exact frame the handoff completes.
 */
@Composable
private fun HomeSynchronizedTomeBedVeil(
    incomingUri: String,
    rampMs: Int,
    focalTransform: HomeBackdropFocalTransform,
    density: Density,
    /** Walk-32c: the bed's own painter has its art, so a still the host adopted is on screen from here. */
    onBedReady: () -> Unit = {},
) {
    var mediaLayers by remember { mutableStateOf(HomeTomeBedVeil<String>()) }
    // Applied during composition, as Crossfade mutates its own visible-states list; convergent because
    // arrive() returns an equal value once the arrival is absorbed.
    val arrived = mediaLayers.arrive(incomingUri) { it }
    if (arrived != mediaLayers) mediaLayers = arrived
    val veilUri = arrived.veil
    val reveal = remember { Animatable(0f) }
    // Spec §3.4, hero half: the ramp is gated on the painter that DRAWS the incoming hero. The plate's
    // paired probe already held this URI until decode-ready, but that probe is a different painter
    // instance — this drawing painter still spends ≥1 frame Empty, and ramping through it would make
    // the hero arrive mid-curve as a partial snap.
    var veilArtReady by remember(veilUri) { mutableStateOf(false) }
    LaunchedEffect(veilUri, rampMs) {
        if (veilUri == null) {
            // Commit flips the veil to null and restarts this effect mid-body; this branch is the
            // post-commit ramp reset, not tidiness. Nothing reads reveal while the veil is empty.
            reveal.snapTo(0f)
            return@LaunchedEffect
        }
        snapshotFlow { veilArtReady }.first { it }
        if (rampMs <= 0) {
            reveal.snapTo(1f)
        } else {
            reveal.animateTo(1f, tween(rampMs, easing = MimirMotion.Easing))
        }
        // Commit: bed := veil in ONE state write — a visual no-op, both slots hold the same URI on
        // this frame. A retarget mid-ramp cancels animateTo above and never reaches this line.
        mediaLayers = mediaLayers.commit()
    }
    val stack = listOfNotNull(arrived.bed, arrived.veil)
    stack.forEachIndexed { index, uri ->
        val isVeil = index == stack.lastIndex && stack.size > 1
        key(uri) {
            HomeSynchronizedTomeBackdropImage(
                imageUri = uri,
                focalTransform = focalTransform,
                density = density,
                alpha = if (isVeil) ({ reveal.value }) else HomeTomeBedAlpha,
                onArtReady = if (isVeil) ({ veilArtReady = true }) else onBedReady,
            )
        }
    }
}

/** The paired Tome hero has already passed the same decode gate as its plate before this mounts. */
@Composable
private fun HomeSynchronizedTomeBackdropImage(
    imageUri: String,
    focalTransform: HomeBackdropFocalTransform,
    density: Density,
    /** Read in the draw phase only — the bed passes [HomeTomeBedAlpha], the veil its live ramp. */
    alpha: () -> Float = HomeTomeBedAlpha,
    /** CP01-b §3.4: the veil's ramp gate — the SAME painter instance that draws reports readiness. */
    onArtReady: (() -> Unit)? = null,
) {
    val uri = imageUri
    val context = LocalContext.current
    val heroEnvelope = rememberMimirHeroDecodeEnvelope()
    val request = remember(uri, context, heroEnvelope) {
        mimirHomeStaticHeroRequest(context, uri, heroEnvelope)
    }
    val painter = rememberAsyncImagePainter(request)
    val ready = when (val state = painter.state) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> state.painter != null
        else -> false
    }
    val currentOnArtReady by rememberUpdatedState(onArtReady)
    LaunchedEffect(ready) {
        if (ready) currentOnArtReady?.invoke()
    }
    if (ready) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .homeBackdropFocalMedia(focalTransform, density)
                .graphicsLayer {
                    this.alpha = alpha()
                    // CP01-b AFTER-run finding (same as HomeTomeLeadArt): the default Auto strategy
                    // gives an alpha < 1 node a compositing layer rasterized at layout size, which
                    // the focal transform then samples as a scaled texture — a different sampling
                    // chain than the settled (alpha == 1, layer-free) render of the same hero.
                    // ModulateAlpha folds alpha into the single painter draw op: no buffer, one
                    // sampling chain in both states. Also strictly cheaper — no 1080-class offscreen
                    // buffer allocated per rotation while the veil ramps.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
    }
}
