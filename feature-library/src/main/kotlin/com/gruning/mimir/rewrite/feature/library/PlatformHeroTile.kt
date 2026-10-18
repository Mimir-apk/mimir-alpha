package com.gruning.mimir.rewrite.feature.library

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.MimirArtFailureLog
import com.gruning.mimir.rewrite.core.ui.drawMimirBadgeMannaz
import com.gruning.mimir.rewrite.core.ui.mimirArtSourceWired
import com.gruning.mimir.rewrite.core.ui.mimirDecodeBoundedCoverBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.gruning.mimir.rewrite.core.ui.MimirHeroMaterial
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.PlatformConsoleArt
import com.gruning.mimir.rewrite.core.ui.mimirFocusTileRing
import com.gruning.mimir.rewrite.core.ui.rememberMimirFocusSettleGlint

/**
 * MIMIR-REWRITE :feature-library — the shelf HERO PLATFORM TILE.
 *
 * The signature of the console coverflow (VISUAL PARITY SPEC §2/§3/§4): ONE tile per platform that is
 * dim, small, and desaturated at rest and blooms into a big lit hero when focused — a per-theme radial
 * fill, a themed ring, a soft glow halo, the ᛗ Mannaz mark upper-right, and the platform's console
 * line-art as the icon. This is the Compose port of the shipping `.eok-slot` / `.eok-slot.is-focused`
 * (`thor-torrent-shop/styles.css`).
 *
 * SCOPE: this composable draws one shipping `.eok-slot`: fill + ring + glow + mark +
 * console icon, plus an optional standalone label stack. Coverflows suppress that stack and own one fixed
 * selected-title band below the art.
 *
 * FOCUS + INPUT : the tile plugs into the single [FocusAuthority]
 * via `Modifier.mimirFocusable` — NEVER a Compose `focusable()`. A tap requests focus AND opens (touch +
 * gamepad parity); a controller CONFIRM over the focused tile
 * is dispatched by the coverflow. The [focused] flag is read from the authority by the caller and passed
 * down, so this stays a pure focus-reactive leaf.
 *
 * COLOR (spec §6): every colour is read from the live [LocalMimirPalette], so the hero re-skins per theme
 * for free — the fill/ring/glow tokens are the baked `tileFillTop/tileFillDeep/tileRing/tileGlow/
 * tileGlowStrong/amb/emberSoft` ARGB values (spec §3), and the icon + mark use `accent`.
 *
 * PERF (spec §6): the resting↔focused transition is COMPOSITOR-CHEAP —
 * a single animated `progress` float drives `alpha`/colour-`lerp` and the glow's alpha; the box
 * layout size snaps immediately like shipping's `.eok-slot` focus rule, while the fill + glow are a single
 * `drawBehind` pass over layered radial gradients (NO blur, NO bitmap) — the same gradient-halo technique as
 * `MimirAtmosphere`. A D-pad sweep never runs a width/height animation through the shelf row.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// GEOMETRY (VISUAL PARITY SPEC §2) — the two rest/focus poses; layout snaps, visual bloom animates
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Every native slot reserves this focused width; side items are compositor-scaled inside it. */
private val FocusWidth: Dp = CoverflowMotionFocusWidthDp.dp

private val FocusBoxHeight: Dp = CoverflowMotionFocusHeightDp.dp

// Emulation keeps a standing-book silhouette in the same fixed-seat system.
private val FocusWidthBook: Dp = CoverflowMotionBookFocusWidthDp.dp
private val FocusBoxHeightBook: Dp = CoverflowMotionBookFocusHeightDp.dp

/** Slot corner. Side items keep the same geometry and scale it as artwork. */
private val FocusRadius: Dp = 26.dp

/** The tile face's corner, for a hide's shatter (walk-32h) to flash inside. */
internal val PlatformHeroFaceCorner: Dp = FocusRadius

/** Shipping `.eok-slot-platform-icon` proportions inside the box. */
private const val IconWidthFraction = 0.78f
private const val IconHeightFraction = 0.72f

/** The Mannaz mark box — `.eok-slot-brand { width:16px; height:16px; right:8; top:8 }`. */
private val MannazSize: Dp = 16.dp
private val MannazInset: Dp = 8.dp

/** Mannaz opacity — `.eok-slot-brand { opacity:.26 }` rest / `.is-focused … { opacity:.46 }` focus. */
private const val MannazRestAlpha = 0.26f
private const val MannazFocusAlpha = 0.46f

/** Slot label gap — `.eok-slot-name { margin-top:8px }`. */
private val SlotLabelGap: Dp = 8.dp

private val SlotNameSize = 15.sp
private val SlotNameLineHeight = 18.sp
private val SlotNameHeight: Dp = 30.dp
private val SlotCountSize = 11.sp
private val SlotCountLineHeight = 14.sp
private val SlotCountHeight: Dp = 20.dp

/** The optional legacy label stack also reserves one stable focused-width lane. */
private val FocusLabelBasisBook: Dp = 200.dp

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// THE HERO TILE
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A single platform tile in the shelf coverflow — the dim resting slot or the lit focused hero (spec §2).
 *
 * Draws (back → front): the per-theme radial fill, the themed ring + glow halo, the console line-art icon
 * ([PlatformConsoleArt]), the ᛗ Mannaz mark upper-right, then the shipping slot label/count below the box.
 *
 * @param label the platform display name (e.g. "Nintendo 64"), drawn under the tile.
 * @param gameCount the platform's game count, drawn as the canonical "N games" line.
 * @param countLabel optional subtitle override for non-platform shelf rows.
 * @param platformIdValue the shelf platform id (`n64`, `ds`, `ps1`, …) → the console line-art icon.
 * @param iconContent optional shelf-art override; native package rows use it to paint cached APK icons.
 *   The third argument is the shipping-style metallic mask brush for platform art; override painters can
 *   ignore it when their source image must keep its own colors.
 * @param showFavoriteStar whether to paint the native shelf favorite mark over the hero box.
 * @param statusBadgeLabel optional focused badge text, used by native shelves for the shipping-style Ready chip.
 * @param focused whether this tile currently owns focus (read from the single [FocusAuthority] by the caller).
 * @param authority the one [FocusAuthority] — the tile registers its bounds here (NO Compose `focusable()`).
 * @param key this tile's stable focus identity (e.g. `FocusKey("shelf/n64")`), minted + owned by the caller.
 * @param scope the focus scope the shelf runs in (the coverflow pushes/owns it).
 * @param registerPhysicalFocus whether this tile reports its physical bounds to [authority]. Coverflows that
 *   register their complete ring virtually disable this duplicate path; touch still requests [key] directly.
 * @param autoBringIntoView keep true outside coverflow; coverflow tiles disable generic relocation because the
 *   shelf row owns exact center snapping.
 * @param onOpen invoked on CONFIRM/tap over the focused tile — opens the platform (host decides the route).
 * @param modifier caller sizing/placement hook (the coverflow lays the row out); the tile owns its own box size.
 */
/**
 * Decoded picked-platform-art bitmaps, kept ACROSS tile remounts so returning to the Library shelf lands ON the
 * art instead of flashing the console line-art for a beat while a fresh [produceState] decode runs. Keyed by the override URI, which carries the file mtime — a re-picked art gets a new key and
 * re-decodes. Bounded; the shelf has ~one entry per platform.
 */
// PERF-B: budgeted in BYTES (not entries) and decoded through the bounded-cover contract — picked SGB
// heroes are often ~1920px sources for ≤ ~500px tiles, so entry-count bounding let ~24 full-size bitmaps
// (~100MB potential) sit resident. 48MB of capped decodes still covers every platform on the shelf.
private val platformCoverBitmapCache =
    object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int =
            value.asAndroidBitmap().byteCount
    }

/**
 * System memory trim: trims the decoded platform covers to half the budget, least recently used first, and returns
 * the bytes let go. Only the app's answer to a system memory trim calls it. The covers a mounted tile reads every
 * paint are the most recently used, so they stay found and nothing re-decodes in view (fix round: an `evictAll`
 * here sent the hero through PENDING and its reveal fade on the next remount).
 */
fun trimPlatformCoverBitmapCacheToHalf(): Int {
    val before = platformCoverBitmapCache.size()
    platformCoverBitmapCache.trimToSize(platformCoverBitmapCache.maxSize() / 2)
    return before - platformCoverBitmapCache.size()
}

private sealed interface PlatformCoverDecodeState {
    val sourceUri: String?

    data class Loading(override val sourceUri: String) : PlatformCoverDecodeState
    data class Failed(override val sourceUri: String?) : PlatformCoverDecodeState

    /** The picked file no longer exists (R8a): the pick counts as no art, so the face takes the normal default. */
    data class Missing(override val sourceUri: String) : PlatformCoverDecodeState
    data class Ready(
        override val sourceUri: String,
        val bitmap: androidx.compose.ui.graphics.ImageBitmap,
    ) : PlatformCoverDecodeState
}

/** A retained decoder result may paint only the exact revision that produced it. */
internal fun platformCoverSourceMatches(decodedSourceUri: String?, requestedSourceUri: String?): Boolean =
    decodedSourceUri == requestedSourceUri

/**
 * The platform face has one owner at a time. Pending durable/decode truth is intentionally quiet; a valid
 * custom image owns the complete face; only ABSENCE restores Mimir's default book/console treatment: no pick, or a pick whose file
 * is gone (R8a). A pick whose file exists but does not decode is wired art that FAILED, and under R8 it draws nothing
 * ([FAILED]), never the default's book or console glyph.
 */
internal enum class PlatformHeroFaceMode { PENDING, DEFAULT, CUSTOM, FAILED }

internal fun platformHeroFaceMode(
    hasOverride: Boolean,
    storeHydrated: Boolean,
    overrideDecodeFailed: Boolean,
    overrideDecoded: Boolean,
    overrideFileMissing: Boolean = false,
): PlatformHeroFaceMode = when {
    hasOverride && overrideDecoded -> PlatformHeroFaceMode.CUSTOM
    hasOverride && overrideFileMissing -> PlatformHeroFaceMode.DEFAULT
    hasOverride && overrideDecodeFailed -> PlatformHeroFaceMode.FAILED
    hasOverride -> PlatformHeroFaceMode.PENDING
    storeHydrated -> PlatformHeroFaceMode.DEFAULT
    else -> PlatformHeroFaceMode.PENDING
}

/**
 * R01 "Land Warm" T4 : pre-decode picked platform art into [platformCoverBitmapCache]
 * behind the cold-start welcome gate, so the FIRST Library entry paints the art on frame 0 exactly like a
 * revisit (the adversarial review's LS-01 P0 — glyph-then-dissolve per tile). Same decode + key contract as
 * the tile's own produceState path; the cache is file-private, so this is its one exposed seam.
 */
object PlatformCoverArtWarmer {
    suspend fun prewarm(overrideCoverArt: String) {
        if (platformCoverBitmapCache.get(overrideCoverArt) != null) return
        val path = android.net.Uri.parse(overrideCoverArt).path ?: overrideCoverArt
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { mimirDecodeBoundedCoverBitmap(path)?.asImageBitmap() }.getOrNull()
        }?.let { platformCoverBitmapCache.put(overrideCoverArt, it) }
    }
}

/**
 * R8a on a WARM return: whether the cover at [override] is GONE. The check is a disk stat, so it runs on the IO dispatcher, never on
 * the composing thread. When the file is gone the cached bitmap is [evict]ed (the default drops the entry from
 * [platformCoverBitmapCache]) so no later mount paints it either, and the answer is true.
 */
internal suspend fun platformCoverWarmGone(
    override: String,
    wired: (String) -> Boolean = { mimirArtSourceWired(it) },
    evict: (String) -> Unit = { platformCoverBitmapCache.remove(it) },
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val path = android.net.Uri.parse(override).path ?: override
    if (wired(path)) {
        false
    } else {
        evict(override)
        true
    }
}

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue") // Warm-cache early return is intentional; the cold path assigns below.
fun PlatformHeroTile(
    label: String,
    gameCount: Int,
    countLabel: String = libraryResources().getQuantityString(R.plurals.library_shelf_games_count, gameCount, gameCount),
    platformIdValue: String,
    iconContent: @Composable (Modifier, Color, Brush) -> Unit = { iconModifier, tint, tintBrush ->
        PlatformConsoleArt(
            platformIdValue = platformIdValue,
            tint = tint,
            modifier = iconModifier,
            tintBrush = tintBrush,
        )
    },
    // a user-picked SGB art override (Y → change platform art). When set, it COVERS the whole
    // leaf (cropped to keep aspect, rounded to the tile's own corner) instead of the small centered console emblem.
    overrideCoverArt: String? = null,
    /** Startup store hydration has not yet proved whether this platform owns an override. */
    suppressDefaultArtWhileHydrating: Boolean = false,
    showFavoriteStar: Boolean = false,
    statusBadgeLabel: String? = null,
    // Book-hero : true only for the EMULATION coverflow — the focused tile becomes a
    // taller-than-wide standing book (drawHeroBook). Android shelves pass false: wide, flat skin, unchanged.
    bookHero: Boolean = false,
    // Shelf seat solve : the book box's real measured
    // size, ONLY used when [bookHero] is true. Defaults to the configured 144x164dp cap so every caller that
    // does not pass a viewport-solved seat (previews, the ring-channel test) keeps today's exact box.
    // [ShelfCoverflow] is the sole production caller and passes [PlatformRowPlan.seatDp]-derived values —
    // bloom ([CoverflowMotionBloomOutsetDp]) and focus scale stay FIXED/additive on top of this box (NOT
    // scaled with it), which is what makes the focus-to-rest ratio drift with seat size, by design (spec §6
    // item 3) — an outer graphicsLayer scale of the fixed 144dp box would have kept that ratio frozen
    // instead, which the spec's own predicted numbers (1.173 at 144dp -> 1.26 at the 320dp floor) rule out.
    bookFocusWidthDp: Dp = FocusWidthBook,
    bookFocusHeightDp: Dp = FocusBoxHeightBook,
    // Centered coverflows own one fixed title band below the art, so they suppress per-item labels. The default
    // keeps this tile useful as a standalone component without changing its call contract.
    showLabels: Boolean = true,
    focused: Boolean,
    // Whether this tile is the CENTRED coverflow hero (slot 0). Drives the big/lit "hero" look (size, opacity,
    // fill, labels); [focused] drives the selection EMPHASIS on top of it (nudge, bloom, Mannaz, title colour)
    // and [ringLit] drives the ring alone. They differ for the frames after a mode switch or a return, where
    // the centred hero should already be big+lit and merely gain its ring when focus lands — no resize pop.
    // Defaults to [focused] for non-coverflow callers (where centred == focused).
    centered: Boolean = focused,
    // Moving coverflows pass their continuous distance-derived role here. This keeps the incoming tile in its
    // neighbour pose until it actually approaches centre and demotes the outgoing tile along the same path.
    // Standalone callers retain the binary [centered] contract through the default.
    /**
     * **A closure, so a gliding coverflow does not recompose this tile every frame (PERF-D / CP04
     * port, 2026-09-03).** It used to be a `Float`, which meant the caller had to read the ring's
     * animated centre in ITS composable body — `ShelfCoverflow.kt:303` did exactly that — and every
     * frame of a glide recomposed the whole tile loop. Traced on the Thor: this composable recomposing
     * at sustained ~8.1–9.6 ms gaps, faster than the 13.67 ms vsync.
     *
     * `GamelistCarousel`'s `GameCard` already carries this shape (`visualSeat: () -> Float`,
     * `GamelistCarousel.kt:445`) after the same fix in July; its own comment at `:458-461` describes
     * the identical bug. This is that port, arriving on the platform row.
     *
     * Everything downstream of it is consumed in LAYOUT or DRAW lambdas, so the values are identical
     * and only the phase they are read in changes. The one deliberate exception is the two title
     * colour `lerp`s near the end of this function, which are composition-phase by nature: they read
     * a composition-phase `focusProgress`, which is `0f` for every unfocused tile because
     * `if (focused)` short-circuits before calling this. So a glide recomposes at most the ONE focused
     * tile instead of the whole visible window.
     */
    centerProgress: () -> Float = { if (centered) 1f else 0f },
    // The RING channel of core-input's two-channel focus law. [focused] is the CONSERVED channel — it keeps
    // its value while a pushed scope (the dock, Utility, a picker) covers this shelf, so paint emphasis,
    // bloom, Mannaz and title colour hold their pose instead of collapsing mid-transition (policy
    // the selected tile keeps its rendered state). The selection RING
    // is the separate input-ownership channel — hidden until return — so it keys off this
    // flag instead, which the coverflows compute from the LIVE scope. Defaults to [focused], so every caller
    // that has no pushed scope to worry about keeps its exact previous behaviour.
    ringLit: Boolean = focused,
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    registerPhysicalFocus: Boolean = true,
    autoBringIntoView: Boolean = true,
    interactive: Boolean = true,
    animateFocusEntrance: Boolean = true,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Applied to the tile's FACE (the leather, the icon, the star), inside the scale the tile grows into focus with and
     * outside everything that draws the face. Walk-32h: the native shelf's centred tile puts the shatter's capture and bounds
     * here, so the picture is the face at its own size and the bounds are where that face really is, scaled. The ring and its
     * bloom are not in it. Nothing else passes one, so the default leaves every other tile exactly as it was.
     */
    faceModifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current

    // Decode before composing the face so the renderer can choose exactly one owner. The old implementation
    // always painted the leather/platform tile first and merely laid the picked bitmap inside it, leaving the
    // rune and tile chrome on top. A picked Platform Icon is now free art: the bitmap is the complete face.
    val cachedCover = remember(overrideCoverArt) {
        overrideCoverArt?.let(platformCoverBitmapCache::get)
    }
    val coverState by androidx.compose.runtime.produceState<PlatformCoverDecodeState>(
        initialValue = when {
            overrideCoverArt == null -> PlatformCoverDecodeState.Failed(sourceUri = null)
            cachedCover != null -> PlatformCoverDecodeState.Ready(overrideCoverArt, cachedCover)
            else -> PlatformCoverDecodeState.Loading(overrideCoverArt)
        },
        overrideCoverArt,
    ) {
        val override = overrideCoverArt
        if (override == null) {
            value = PlatformCoverDecodeState.Failed(sourceUri = null)
            return@produceState
        }
        cachedCover?.let {
            value = PlatformCoverDecodeState.Ready(override, it)
            // R8a: a warm bitmap paints on frame 0 with no stat, so a deleted file would keep its art for as long as the cache holds
            // it. Verify off-main, as the Bottom does for a warm hit; a file that is gone is evicted and the tile takes the normal
            // no-art default (a brief stale frame before it is accepted).
            if (platformCoverWarmGone(override)) value = PlatformCoverDecodeState.Missing(override)
            return@produceState
        }
        val decodedState = value
        if (
            decodedState is PlatformCoverDecodeState.Ready &&
            platformCoverSourceMatches(decodedState.sourceUri, override)
        ) {
            return@produceState
        }
        // produceState retains its value across key changes. Mark the new revision pending before IO so
        // neither the previous bitmap nor the default glyph can flash while the selected file is decoded.
        value = PlatformCoverDecodeState.Loading(override)
        val path = android.net.Uri.parse(override).path ?: override
        // The stat and the decode both run off-main. A file that is gone is the pick gone (R8a: no art, the normal default); a file
        // that exists and does not decode is wired art that failed (R8: nothing is drawn).
        val present = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mimirArtSourceWired(path) }
        if (!present) {
            value = PlatformCoverDecodeState.Missing(override)
            return@produceState
        }
        val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { mimirDecodeBoundedCoverBitmap(path)?.asImageBitmap() }.getOrNull()
        }
        if (decoded != null) platformCoverBitmapCache.put(override, decoded)
        value = decoded?.let { PlatformCoverDecodeState.Ready(override, it) }
            ?: PlatformCoverDecodeState.Failed(override)
    }
    val currentCoverState = coverState.takeIf {
        platformCoverSourceMatches(it.sourceUri, overrideCoverArt)
    }
    val customCover = (currentCoverState as? PlatformCoverDecodeState.Ready)?.bitmap
    val faceMode = platformHeroFaceMode(
        hasOverride = overrideCoverArt != null,
        storeHydrated = !suppressDefaultArtWhileHydrating,
        overrideDecodeFailed = currentCoverState is PlatformCoverDecodeState.Failed,
        overrideDecoded = customCover != null,
        overrideFileMissing = currentCoverState is PlatformCoverDecodeState.Missing,
    )
    MimirArtFailureLog(failed = faceMode == PlatformHeroFaceMode.FAILED, model = overrideCoverArt)
    val artAlpha = remember(overrideCoverArt) {
        Animatable(if (cachedCover != null) 1f else 0f)
    }
    LaunchedEffect(customCover) {
        if (customCover != null && artAlpha.value < 1f) {
            artAlpha.animateTo(1f, tween(CoverflowMotionDurationMs, easing = CoverflowMotionEasing))
        }
    }

    // FocusAuthority has already committed the new logical seat. Base scale and alpha therefore snap now;
    // only the incoming tile's small emphasis nudge/paint/ring acknowledges the move. Losing focus is gated to
    // zero immediately so the old live tile cannot remain large or lit in its new neighbour seat.
    val initialEmphasis = if (focused && !animateFocusEntrance) 1f else 0f
    val emphasis = remember { Animatable(initialEmphasis) }
    val ring = remember { Animatable(if (ringLit && !animateFocusEntrance) 1f else 0f) }
    LaunchedEffect(focused) {
        if (focused) {
            emphasis.animateTo(
                1f,
                animationSpec = tween(CoverflowMotionDurationMs, easing = CoverflowMotionEasing),
            )
        } else {
            emphasis.snapTo(0f)
        }
    }
    // Keyed on [ringLit], not [focused]: the ring is the one thing that must leave when a pushed scope takes
    // input, even though everything above holds its pose. See this file's [ringLit] parameter note.
    LaunchedEffect(ringLit) {
        if (ringLit) {
            // CP07: SNAP, not tween.  A per-tile 0->1 tween means every step of a traverse GROWS a fresh ring on the
            // arriving tile -- the Home shelf has no such thing, because its ring is stationary at the
            // seat (`HomeShelfStationaryTomeFocusRing`, `progress = { 1f }`) and the tiles glide through
            // it.
            //
            // The reveal this tile needs is already present and is the one Home uses: `ringProgress` is
            // `ring.value * resolvedCenterProgress` below, so with `ring` pinned at 1 the ring is
            // revealed purely by the tile's physical approach to the centre seat. The tween was a SECOND
            // animation stacked on that, and it is the observed extra motion.
            ring.snapTo(1f)
        } else {
            ring.snapTo(0f)
        }
    }
    // Logical focus/title commit immediately, but selection paint cannot pre-render on a side seat.
    // It is revealed only as this one body physically approaches the visual centre.
    val resolvedCenterProgress: () -> Float = { centerProgress().coerceIn(0f, 1f) }
    // Draw/layout-phase focus emphasis. Reading `emphasis.value` in here defers that subscription too.
    val focusProgressOf: () -> Float = { if (focused) emphasis.value * resolvedCenterProgress() else 0f }
    // CP07: the RING does not ride `resolvedCenterProgress`. That term ramps 0->1 as a tile glides into
    // the seat, so multiplying by it is what GREW a fresh ring on every arriving tile -- the observed ring growth, and the part my first pass (snapping `ring`) left untouched.
    //
    // Home's ring never grows: it is stationary at the seat at a constant `progress = { 1f }`
    // (`HomeShelfScreen.kt`'s `HomeShelfStationaryTomeFocusRing`) and the tiles glide under it. Full
    // strength on the lit tile is the closest this per-tile ring gets to that, and it removes the growth
    // entirely. `focusProgress` below KEEPS the centre term -- the bloom/scale emphasis is a pose and is
    // meant to arrive with the tile; only the ring was wrong.
    val ringProgress = if (ringLit) 1f else 0f
    // One gleam crosses the lit ring once the tile settles. The ring stays snapped at full strength;
    // only light moves, and a traverse never gleams.
    val ringGlint = rememberMimirFocusSettleGlint(if (ringLit) Unit else null)
    // Deferred poses. Same arithmetic as before, evaluated in the draw lambdas that consume them.
    val artScaleOf: () -> Float = {
        val pose = coverflowBasePose(centerProgress = resolvedCenterProgress(), bookHero = bookHero)
        pose.scale * (1f + (CoverflowMotionFocusScale - 1f) * focusProgressOf())
    }
    val tileAlphaOf: () -> Float = {
        coverflowBasePose(centerProgress = resolvedCenterProgress(), bookHero = bookHero).alpha
    }

    // The slot always measures at its maximum art size. Only [artScale] changes what is painted inside it.
    // Book callers may pass a viewport-solved seat size (bookFocusWidthDp/HeightDp); non-book (native) slots
    // are unaffected by this ticket and keep the shared FocusWidth/FocusBoxHeight constants.
    val boxWidth = if (bookHero) bookFocusWidthDp else FocusWidth
    val boxHeight = if (bookHero) bookFocusHeightDp else FocusBoxHeight
    // Book-hero icon centring (pass 5): NOT a magic fraction — the console icon is centred in
    // the LEAF, the cover area between the spine ("book opener", left) and the clasp (right). Geometry mirrors
    // drawHeroBook exactly: spine = max(7%·width, 3.5dp), clasp = 5dp on the right edge. The icon box is drawn
    // box-centred, so the offset that lands it on the leaf centre is (spine − clasp)/2. For a wide focused book
    // the spine (~11.5dp) outweighs the clasp (5dp) → a small RIGHT shift; for a ghost the two nearly cancel.
    val bookSpine = maxOf(boxWidth * 0.07f, 3.5.dp)
    val bookIconOffsetX = if (bookHero) (bookSpine - 5.dp) / 2f else 0.dp
    val cornerPx = with(LocalDensity.current) { FocusRadius.toPx() }

    val bookMaterial = platformHeroBookMaterial(palette)
    // A book uses its explicit on-cover ink, which stays readable on Folkvangr/Baldr's intentionally dark hero
    // volumes. Flat Android heroes retain the shipping metallic theme gradient.
    val iconTint = if (bookHero) bookMaterial.onCover else palette.accent
    val iconBrush = remember(palette, bookHero) {
        if (bookHero) platformHeroBookIconBrush(bookMaterial) else platformHeroIconBrush(palette)
    }
    val heroGlowColors = remember(palette) { listOf(palette.accentGlow, Color.Transparent) }
    val mannazAlphaOf: () -> Float = {
        MannazRestAlpha + (MannazFocusAlpha - MannazRestAlpha) * focusProgressOf()
    }
    val bloomPeakAlpha = if (palette.usesBrightField) 0.12f else 0.18f

    // A stable interaction source so the ripple-less clickable doesn't allocate one each recomposition.
    val interaction = remember { MutableInteractionSource() }
    val focusRegistration = if (interactive && registerPhysicalFocus) {
        Modifier.mimirFocusable(authority, key, scope, autoBringIntoView = autoBringIntoView)
    } else {
        Modifier
    }
    val activation = if (interactive) {
        Modifier.clickable(interactionSource = interaction, indication = null) {
            authority.requestFocus(key)
            onOpen()
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .width(boxWidth)
            // RG03 : deliberately NO tile-level alpha layer here. An alpha'd layer renders
            // into a bounds-sized offscreen buffer that CLIPS the Switch-bloom ring's outside-bounds outsets
            // while the tile approaches centre (tileAlpha < 1) — the "ring clips on movement then settles"
            // regression. tileAlpha is applied on the flattened content nodes below instead (leaf skin stack,
            // bloom, labels), which keeps every resting render identical.
            // The ONE focus authority — register bounds; NEVER a Compose focusable() (memory
            // `webview-native-focus-nav-vs-js`). The coverflow reads `focused` from the same authority.
            .then(focusRegistration)
            // Touch + gamepad parity : a tap focuses AND opens.
            // Ripple-less (indication=null): the focus glow IS the feedback; a Material ripple would fight it.
            .then(activation)
            // The one press response (0.97 for the focus beat), drawn inside the tap so the hit box never moves.
            .mimirPressResponse(interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(boxWidth)
                .height(boxHeight)
                .graphicsLayer {
                    val scale = artScaleOf()
                    scaleX = scale
                    scaleY = scale
                }
                // CP01/RG03 : the Home shelf's Switch-bloom is THE selection ring on the
                // Library shelf too. It draws HERE — inside the scale layer (so it tracks the focus nudge)
                // but on a node with NO alpha, so no offscreen buffer ever clips its outside-bounds bloom
                // while the tile moves. The skins' own 2px stroke is retired; one focus grammar everywhere.
                .mimirFocusTileRing({ ringProgress }, palette.tileFocusRing, cornerRadius = FocusRadius, glint = ringGlint),
            contentAlignment = Alignment.Center,
        ) {
            // The hero bloom is deliberately a whisper: a small, low-alpha radial underlay that never reaches
            // the header or neighboring labels. It gives the selected volume a little air without the old cloud
            // trail that lingered across snapped focus moves.
            Box(
                Modifier
                    .requiredWidth(boxWidth + (CoverflowMotionBloomOutsetDp * 2).dp)
                    .requiredHeight(boxHeight + (CoverflowMotionBloomOutsetDp * 2).dp)
                    // RG03: fold the retired tile-level alpha into the bloom's own layer.
                    .graphicsLayer { alpha = focusProgressOf() * bloomPeakAlpha * tileAlphaOf() }
                    .drawBehind {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = heroGlowColors,
                                center = Offset(size.width / 2f, size.height * 0.54f),
                                radius = size.maxDimension * 0.58f,
                            ),
                            cornerRadius = CornerRadius(cornerPx * 1.2f),
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .width(boxWidth)
                    .height(boxHeight)
                    // RG03: the leaf carries the tile's presentation alpha — skin + icon + badges flatten into
                    // ONE faded buffer exactly like the old Column-level layer did, so resting neighbours
                    // (tileAlpha 0.48) render identically. The ring lives on the unfaded scale node above.
                    .graphicsLayer { alpha = tileAlphaOf() }
                    .then(faceModifier)
                    // Motion audit batch E: the centred book keeps a copy of its cover for the Library's open,
                    // which swings it on its spine. The ring above is not in the copy, so it never swings.
                    .then(
                        if (bookHero && centered) {
                            Modifier.libraryBookLeaf(
                                leaf = LocalLibraryBookLeaf.current,
                                platformId = platformIdValue,
                                cornerPx = cornerPx,
                                pageColor = palette.surfaceHigh,
                                spineColor = bookMaterial.coverMiddle,
                            )
                        } else {
                            Modifier
                        },
                    )
                    // The fill + the restrained in-box ambient — see drawHeroSkin (no off-box bloom exists;
                    // the source-truth soft shadow without clipping the actual hero box.
                    .drawBehind {
                        if (faceMode == PlatformHeroFaceMode.DEFAULT && bookHero) {
                            // EVERY emulation tile is a bound book  — the focused one lit,
                            // the neighbours dimmed ghost-books, so the whole row reads as a bookshelf. The
                            // companion cover language: leather from the palette, an accent spine with two
                            // raised bands, a clasp; the console art on top is the emblem. Android shelves
                            // (bookHero=false) keep the flat wide skin.
                            drawHeroBook(
                                cornerPx = cornerPx,
                                ringProgress = ringProgress,
                                material = bookMaterial,
                                ring = palette.tileRing,
                                // Book-hero Phase 2 : each theme wears a distinctive
                                // signature binding tooled into the leather; the id selects which.
                                themeId = palette.id,
                            )
                        } else if (faceMode == PlatformHeroFaceMode.DEFAULT) {
                            drawHeroSkin(
                                progress = focusProgressOf(),
                                ringProgress = ringProgress,
                                cornerPx = cornerPx,
                                tileFillTop = palette.tileFillTop,
                                tileFillDeep = palette.tileFillDeep,
                                amb = palette.amb,
                                ring = palette.tileRing,
                                emberSoft = palette.emberSoft,
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (faceMode == PlatformHeroFaceMode.DEFAULT) {
                    // The console line-art icon (spec §4), tinted by the theme accent, centered at shipping scale.
                    // Pure leaf: it only re-skins when the tint changes (no per-frame allocation on a focus move).
                    // Book-hero: centred in the LEAF (between spine + clasp) via the geometry-derived offset above.
                    iconContent(
                        Modifier
                            .offset(x = bookIconOffsetX)
                            .width(boxWidth * IconWidthFraction)
                            .height(boxHeight * IconHeightFraction),
                        iconTint,
                        iconBrush,
                    )
                }
                if (faceMode == PlatformHeroFaceMode.CUSTOM && customCover != null) {
                    // Crop provides full bleed. A square covering the 224x140 landscape face loses 37.5% of its
                    // height; the book face is 144x164. Fit was tried on Odin, but left undecorated bands
                    // because CUSTOM mode suppresses the face rune and badges. The bitmap must fill the face.
                    // Custom platform icons are normalized separately to 512px on apply
                    // (CustomImageImportStore.PlatformIconMaxEdgePx).
                    // An untried alternative is a centered badge at the default rune's 0.78 x 0.72
                    // fractions over the themed face, avoiding both crop and empty bands. This remains
                    // a design option requiring review, not an implemented or device-validated layout.
                    Image(
                        bitmap = customCover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(FocusRadius))
                            .graphicsLayer { alpha = artAlpha.value },
                    )
                }

                // A custom face is deliberately free of the default rune/badges. The selected bitmap owns every
                // pixel inside the rounded face; focus, title, and count remain outside this node.
                if (faceMode == PlatformHeroFaceMode.DEFAULT) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(MannazInset)
                            .width(MannazSize)
                            .height(MannazSize)
                            .drawBehind { drawMimirBadgeMannaz(palette.runeMark.copy(alpha = mannazAlphaOf())) },
                    )

                    if (showFavoriteStar) {
                        Text(
                            text = "★",
                            color = palette.accentBright,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
                        )
                    }

                    if (statusBadgeLabel != null) {
                        Text(
                            text = statusBadgeLabel.uppercase(),
                            color = palette.accentBright,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.08.em,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .graphicsLayer { alpha = focusProgressOf() }
                                .padding(start = 8.dp, end = 8.dp, bottom = 7.dp),
                        )
                    }
                }
            }
        }

        if (showLabels) {
            // The ONLY composition-phase read of the ring left in this file, and it is scoped to the
            // branch that needs it. Both coverflows pass `showLabels = false`
            // (`ShelfCoverflow.kt`, `NativeShelfCoverflow.kt`), so on those surfaces this never runs and
            // the focused tile does not recompose during a glide either — hoisted out of the function
            // body in review, where it was costing the focused tile a recomposition per frame to feed
            // two colour lerps that were never composed here.
            val focusProgress = if (focused) emphasis.value * resolvedCenterProgress() else 0f
            Spacer(Modifier.height(SlotLabelGap))
            Column(
                modifier = Modifier
                    .width(if (bookHero) FocusLabelBasisBook else FocusWidth)
                    .offset(x = bookIconOffsetX)
                    // RG03: labels carry the tile's presentation alpha themselves (text never overlaps, so a
                    // per-node fade renders identically to the old flattened Column layer).
                    .graphicsLayer { alpha = tileAlphaOf() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    color = lerp(palette.textSecondary, palette.textPrimary, focusProgress),
                    fontSize = SlotNameSize,
                    lineHeight = SlotNameLineHeight,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(SlotNameHeight),
                )
                Text(
                    text = countLabel,
                    color = lerp(palette.textMuted, palette.accentBright, focusProgress),
                    fontSize = SlotCountSize,
                    lineHeight = SlotCountLineHeight,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(SlotCountHeight),
                )
            }
        }
    }
}

/** One stable selected-title lane shared by emulated and native coverflows. */
@Composable
internal fun ShelfHeroTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(CoverflowTitleBandHeightDp.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = subtitle,
            color = palette.textSecondary,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// DRAW — the fill + ring (VISUAL PARITY SPEC §2/§3). The outer halo was removed .
// ─────────────────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawHeroSkin(
    progress: Float,
    ringProgress: Float,
    cornerPx: Float,
    tileFillTop: Color,
    tileFillDeep: Color,
    amb: Color,
    ring: Color,
    emberSoft: Color,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val corner = CornerRadius(cornerPx, cornerPx)
    val litProgress = progress.coerceIn(0f, 1f)

    // The hero slot carries NO underside/outer glow at all — the halo was removed by policy
    // 2026-07-08 (see the hero-glow comment above); drawHeroSkin is the tile's COMPLETE paint:
    // fill → ambient whisper → hairline → ring. (Earlier comments named a `drawHeroBloom` pass and an
    // elevation shadow — neither exists; critique-panel F11 + B18 sweep.)

    // (1) BASE FILL — shipping `radial-gradient(130% 130% at 50% 0%, tileFillTop 0%, tileFillDeep 80%)`. This is a
    // genuine ELLIPSE (rx = 130% of the box WIDTH, ry = 130% of the box HEIGHT, independently), centred at the TOP
    // edge. Compose/Skia radial brushes are circles only, so — per the designer handoff — clip to the box and draw
    // a deep base + a circular top-light inside a non-uniform `scale{}` pivoted on the gradient centre, reproducing
    // the CSS ellipse exactly: a top-lit violet fading to true OLED black by the box's lower third (not the flat
    // wash the old maxOf-radius CIRCLE produced, which kept the whole box too uniformly lit). Painted on resting
    // platform-icon tiles too (shipping does); the whole-slot alpha does the neighbour de-emphasis.
    drawRoundRect(color = tileFillDeep, topLeft = Offset.Zero, size = Size(w, h), cornerRadius = corner, style = Fill)
    val fillCenter = Offset(w * 0.5f, 0f)
    val ry = h * 1.3f
    val rx = w * 1.3f
    val boxPath = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, Size(w, h)), corner)) }
    clipPath(boxPath) {
        scale(scaleX = rx / ry, scaleY = 1f, pivot = fillCenter) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to tileFillTop,
                        0.8f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                    center = fillCenter,
                    radius = ry,
                ),
                radius = ry,
                center = fillCenter,
            )
        }
    }

    // (3) AMBIENT LIFT — shipping `radial-gradient(46% 36% at 50% 42%, amb, transparent 60%)` — an ELLIPSE (46% W ×
    // 36% H). Gated on CENTERED (`progress`), not `focused`: the centred coverflow tile is always the big lit hero
    // (its lit backing rides `background`, which shipping never transitions), and only the RING keys off actual
    // focus — so a mode switch / return lands the hero already big+lit and just gains its ring, with no resize pop.
    if (litProgress > 0.5f) {
        val ambRx = w * 0.46f
        val ambRy = (h * 0.36f).coerceAtLeast(1f)
        val ambCenter = Offset(w * 0.5f, h * 0.42f)
        clipPath(boxPath) {
            scale(scaleX = ambRx / ambRy, scaleY = 1f, pivot = ambCenter) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to amb.copy(alpha = amb.alpha),
                            0.6f to Color.Transparent,
                            1.0f to Color.Transparent,
                        ),
                        center = ambCenter,
                        radius = ambRy,
                    ),
                    radius = ambRy,
                    center = ambCenter,
                )
            }
        }
    }

    // Resting themed hairline (current shipping `.has-platform-icon { border-color: --eok-ember-soft }`).
    val restW = 0.5f * density
    drawRoundRect(
        color = emberSoft.copy(alpha = emberSoft.alpha * (1f - litProgress)),
        topLeft = Offset(restW * 0.5f, restW * 0.5f),
        size = Size(w - restW, h - restW),
        cornerRadius = corner,
        style = Stroke(width = restW),
    )

    // (5) RING — CP01 : the 2px stroke is RETIRED; the shared Switch-bloom
    // (mimirFocusTileRing on the tile box, driven by the same ringProgress) is the one selection ring.
}

/**
 * The focused hero, rendered as a bound Norse book — the same cover language as the
 * companion folder tome (`CompanionSurface.NorseBookCover`), scaled to the hero: an authored three-stop cover,
 * hardware spine with two raised tooling bands, and a clasp on the fore-edge. The platform's console line-art
 * (drawn on top by the icon leaf) is the cover emblem — a PLATFORM book wears its console, a FOLDER book
 * wears the Mannaz. Draw-only; the focus ring is unchanged.
 */
private fun DrawScope.drawHeroBook(
    cornerPx: Float,
    ringProgress: Float,
    material: MimirHeroMaterial,
    ring: Color,
    themeId: MimirThemeId = MimirThemeId.DEFAULT,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val corner = CornerRadius(cornerPx, cornerPx)
    // Authored hero roles keep light application themes on a dark, readable book instead of deriving leather
    // from their pale generic surfaces. The middle stop deliberately sits behind the console emblem.
    val leather = Brush.verticalGradient(
        0.00f to material.coverTop,
        0.52f to material.coverMiddle,
        1.00f to material.coverBottom,
    )
    drawRoundRect(brush = leather, topLeft = Offset.Zero, size = Size(w, h), cornerRadius = corner, style = Fill)
    val boxPath = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, Size(w, h)), corner)) }
    // Spine + its two raised gold-tooling bands, clipped to the cover. Purely proportional (7% of width) so
    // a small ghost-book gets a proportionally-thin spine, not a chunky one.
    val spine = (w * 0.07f).coerceAtLeast(3.5f * density)
    clipPath(boxPath) {
        // Spine panel on the left.
        drawRect(color = material.hardware.copy(alpha = 0.30f), topLeft = Offset.Zero, size = Size(spine, h))
        // French-groove hinge: a recessed dark channel just inside the spine so it reads as a bound edge.
        drawLine(
            Color.Black.copy(alpha = 1f / 3f),
            Offset(spine + 1.4f * density, 0f),
            Offset(spine + 1.4f * density, h),
            1.6f * density,
        )
        // Two raised sewing-cord hubs use the theme's explicit hardware metal.
        val hub = material.hardware
        listOf(0.28f, 0.72f).forEach { yf ->
            val yy = h * yf
            drawLine(lerp(hub, Color.Black, 0.55f).copy(alpha = 0.60f), Offset(0f, yy + 1f * density), Offset(spine, yy + 1f * density), 1.7f * density)
            drawLine(hub.copy(alpha = 0.92f), Offset(0f, yy), Offset(spine, yy), 1.7f * density)
            drawLine(lerp(hub, Color.White, 0.60f).copy(alpha = 0.75f), Offset(0f, yy - 0.8f * density), Offset(spine, yy - 0.8f * density), 0.8f * density)
        }
        // The theme's metallic corner emblem, tooled into the bottom-right corner.
        drawBookSignature(themeId, material.hardware, material.coverBottom, w, h, spine)
    }
    // The clasp on the fore-edge.
    val claspH = h * 0.12f
    val claspW = 5f * density
    drawRect(
        color = material.hardware.copy(alpha = 0.72f),
        topLeft = Offset(w - claspW, (h - claspH) / 2f),
        size = Size(claspW, claspH),
    )
    // Gold hairline round the cover.
    val bW = 0.8f * density
    drawRoundRect(
        color = material.hardware.copy(alpha = 0.30f),
        topLeft = Offset(bW * 0.5f, bW * 0.5f),
        size = Size(w - bW, h - bW),
        cornerRadius = corner,
        style = Stroke(width = bW),
    )
    // CP01 : the book's 2px selection stroke is RETIRED — the shared Switch-bloom on the
    // tile box (same ringProgress) is the one selection ring across every shelf surface.
}

/**
 * Book-hero Phase 2 (pass 3): each Norse theme wears a distinctive METALLIC corner emblem —
 * a small, pronounced piece of "metal-thread embroidery" tucked into the book's BOTTOM-RIGHT corner (clear of
 * the spine on the left and the ᛗ Mannaz at top-right). Rendered with a beveled shadow→base→highlight so it
 * reads as raised metal, not a flat wash. The palette id selects the motif; the accent gives the metal its
 * hue (gilt for gold themes, copper/ruby for Surtr, etc.). Proportional, so a ghost-book gets a smaller mark.
 */
private fun DrawScope.drawBookSignature(
    themeId: MimirThemeId,
    accent: Color,
    surfaceBase: Color,
    w: Float,
    h: Float,
    spine: Float,
) {
    val leafW = w - spine
    val d = density
    // Metal palette (pass 4 — "doesn't read as metallic"): a much BRIGHTER specular highlight
    // and a DEEPER occlusion shadow, so the bevel reads as raised metal, not a flat accent line. Light comes
    // from the top-left, so the highlight sits up-left and the shadow drops down-right.
    val hi = lerp(accent, Color.White, 0.80f)
    val lo = lerp(accent, Color.Black, 0.60f)
    val sw = 1.7f * d
    // A beveled metal stroke: dark underside (down-right), the accent body, then a crisp bright specular core (up-left).
    fun mline(x0: Float, y0: Float, x1: Float, y1: Float) {
        drawLine(lo.copy(alpha = 0.70f), Offset(x0 + d, y0 + d), Offset(x1 + d, y1 + d), sw)
        drawLine(accent.copy(alpha = 0.95f), Offset(x0, y0), Offset(x1, y1), sw)
        drawLine(hi.copy(alpha = 0.95f), Offset(x0 - 0.6f * d, y0 - 0.6f * d), Offset(x1 - 0.6f * d, y1 - 0.6f * d), sw * 0.42f)
    }
    fun mring(ccx: Float, ccy: Float, r: Float) {
        drawCircle(lo.copy(alpha = 0.70f), r, Offset(ccx + d, ccy + d), style = Stroke(sw))
        drawCircle(accent.copy(alpha = 0.95f), r, Offset(ccx, ccy), style = Stroke(sw))
        drawCircle(hi.copy(alpha = 0.95f), r, Offset(ccx - 0.6f * d, ccy - 0.6f * d), style = Stroke(sw * 0.42f))
    }
    // A domed metal bead: a diagonal hi→accent→lo gradient fill with a drop shadow and a bright sheen dot.
    fun mdisc(ccx: Float, ccy: Float, r: Float) {
        drawCircle(lo.copy(alpha = 0.75f), r + 0.8f * d, Offset(ccx + 0.6f * d, ccy + 0.6f * d))
        drawCircle(
            brush = Brush.linearGradient(
                listOf(hi, accent, lo),
                start = Offset(ccx - r, ccy - r),
                end = Offset(ccx + r, ccy + r),
            ),
            radius = r,
            center = Offset(ccx, ccy),
        )
        drawCircle(hi.copy(alpha = 0.90f), r * 0.24f, Offset(ccx - r * 0.34f, ccy - r * 0.34f)) // sheen
    }
    fun marc(ccx: Float, ccy: Float, r: Float, start: Float, sweep: Float) {
        val s = Size(r * 2f, r * 2f)
        drawArc(lo.copy(alpha = 0.70f), start, sweep, false, Offset(ccx - r + d, ccy - r + d), s, style = Stroke(sw))
        drawArc(accent.copy(alpha = 0.95f), start, sweep, false, Offset(ccx - r, ccy - r), s, style = Stroke(sw))
        drawArc(hi.copy(alpha = 0.95f), start, sweep, false, Offset(ccx - r - 0.6f * d, ccy - r - 0.6f * d), s, style = Stroke(sw * 0.42f))
    }
    // Bottom-right corner stamp (pass 5: "a tad smaller, closer to the corner, centred
    // between the corner's ends"). Smaller radius, and NOOK-CENTRED — equal margins from the right and bottom
    // edges so it sits on the corner's diagonal, tucked in — instead of floating high (was 74dp off the bottom).
    val r = leafW * 0.068f
    val acx = w - r - leafW * 0.07f
    val acy = h - r - leafW * 0.07f
    fun spokes(count: Int, r0: Float, r1: Float) {
        for (i in 0 until count) {
            val a = Math.toRadians((i * (360.0 / count))).toDouble()
            val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
            mline(acx + dx * r0, acy + dy * r0, acx + dx * r1, acy + dy * r1)
        }
    }
    when (themeId) {
        // Freyr — the golden hall: a sunburst.
        MimirThemeId.FREYR -> { spokes(12, r * 0.34f, r); mring(acx, acy, r * 0.30f) }
        // Baldr — the bright sun: a radiant filled sun.
        MimirThemeId.BALDR -> { spokes(10, r * 0.66f, r * 1.05f); mdisc(acx, acy, r * 0.56f) }
        // Surtr — the forge: an upward ember spark.
        MimirThemeId.SURTR -> {
            mline(acx, acy + r, acx + r * 0.24f, acy)
            mline(acx + r * 0.24f, acy, acx - r * 0.20f, acy - r * 0.55f)
            mline(acx - r * 0.20f, acy - r * 0.55f, acx + r * 0.16f, acy - r)
            mline(acx - r * 0.5f, acy + r * 0.2f, acx - r * 0.2f, acy - r * 0.3f)
        }
        // Máni — moonlit: a crescent moon.
        MimirThemeId.MANI -> { marc(acx, acy, r, 40f, 280f); marc(acx + r * 0.42f, acy, r * 0.82f, 70f, 220f) }
        // Hel — dread: a wax seal with a slashed rune.
        MimirThemeId.HEL -> {
            mdisc(acx, acy, r * 0.9f)
            mline(acx, acy - r * 0.5f, acx, acy + r * 0.5f)
            mline(acx - r * 0.42f, acy - r * 0.22f, acx + r * 0.42f, acy + r * 0.22f)
        }
        // Niflheim — the void: the Void-Eye of Ginnungagap. A black
        // annular core haloed by a thin metallic corona — an eclipse ring, "the yawning void" with a rim of
        // abyss-light. Replaces the old frost snowflake (read as winter/cute, undersold the primordial abyss).
        MimirThemeId.NIFLHEIM -> {
            drawCircle(surfaceBase, r * 0.58f, Offset(acx, acy))         // the void core
            mring(acx, acy, r * 0.92f)                                  // the beveled corona ring
        }
        // Yggdrasil — the rooted well: a sprig with leaf ticks.
        MimirThemeId.YGGDRASIL -> {
            mline(acx, acy + r, acx, acy - r)
            listOf(0.5f, 0.0f, -0.5f).forEach { fy ->
                mline(acx, acy + r * fy, acx + r * 0.62f, acy + r * fy - r * 0.42f)
                mline(acx, acy + r * fy, acx - r * 0.62f, acy + r * fy - r * 0.42f)
            }
        }
        // Folkvangr — bloom: a rose rosette.
        MimirThemeId.FOLKVANGR -> {
            mring(acx, acy, r); mring(acx, acy, r * 0.6f)
            for (i in 0 until 6) {
                val a = Math.toRadians((i * 60.0 + 30.0)).toDouble()
                val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
                mline(acx + dx * r * 0.6f, acy + dy * r * 0.6f, acx + dx * r, acy + dy * r)
            }
        }
        // Default — a simple metallic diamond stud, so every book wears a corner mark.
        MimirThemeId.DEFAULT -> {
            mline(acx, acy - r, acx + r * 0.7f, acy)
            mline(acx + r * 0.7f, acy, acx, acy + r)
            mline(acx, acy + r, acx - r * 0.7f, acy)
            mline(acx - r * 0.7f, acy, acx, acy - r)
        }
    }
}

internal data class PlatformHeroIconStop(val offset: Float, val color: Color)

internal fun platformHeroIconStops(palette: MimirThemePalette): List<PlatformHeroIconStop> = listOf(
    PlatformHeroIconStop(0.00f, palette.iconGradLight),
    PlatformHeroIconStop(0.40f, palette.iconGradHot),
    PlatformHeroIconStop(0.62f, palette.iconGradMid),
    PlatformHeroIconStop(1.00f, palette.iconGradDeep),
)

/** The book renderer consumes the authored hero contract directly; generic surface roles never enter it. */
internal fun platformHeroBookMaterial(palette: MimirThemePalette): MimirHeroMaterial =
    palette.materials.hero

/** Book console art is one high-contrast on-cover ink, including on Folkvangr and Baldr. */
internal fun platformHeroBookIconStops(material: MimirHeroMaterial): List<PlatformHeroIconStop> = listOf(
    PlatformHeroIconStop(0.00f, material.onCover),
    PlatformHeroIconStop(1.00f, material.onCover),
)

private fun platformHeroIconBrush(palette: MimirThemePalette): Brush =
    // One authority (HP-11 v2 S1): the metallic brush lives in core-ui, shared with the companion grid.
    com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush(palette)

private fun platformHeroBookIconBrush(material: MimirHeroMaterial): Brush =
    Brush.verticalGradient(
        colorStops = platformHeroBookIconStops(material)
            .map { it.offset to it.color }
            .toTypedArray(),
    )
