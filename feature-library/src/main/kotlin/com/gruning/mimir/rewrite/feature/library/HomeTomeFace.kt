package com.gruning.mimir.rewrite.feature.library

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.imageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirNorseBookCover
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val HomeTomePreviewHoldMs = 3_200L
// Tome preview crossfades use OverlayMs rather than a separate literal240ms clock.
internal const val HomeTomePreviewFadeMs = MimirMotion.OverlayMs

internal fun nextHomeTomePreviewIndex(current: Int, previewCount: Int): Int =
    if (previewCount <= 1) 0 else (current.coerceAtLeast(0) + 1) % previewCount

internal fun homeTomeMemberCountLabel(res: Resources, tome: HomeShelfTome): String =
    homeTomeMemberCountLabel(res, tome.tomeId, tome.memberCount)

internal fun homeTomeMemberCountLabel(res: Resources, tomeId: TomeId, memberCount: Int): String = when {
    memberCount <= 0 -> res.getString(R.string.library_home_tome_count_empty)
    else -> res.getQuantityString(R.plurals.library_home_games_count, memberCount, memberCount)
}
/**
 * The Home Tome is the companion-display bound book at the primary-display scale. A focused Tome rotates
 * one local game thumbnail at a time; title/count carry identity without a redundant console glyph.
 */
@Composable
fun HomeTomeFace(
    tome: HomeShelfTome,
    focused: Boolean,
    modifier: Modifier = Modifier,
    focusScale: Float = 1f,
    showFocusRing: Boolean = true,
    /** Compact collection-grid face; defaults preserve every existing full-size caller. */
    compact: Boolean = false,
    /**
     * Additive plate/thumbnail seam. Default true preserves Home/full-book preview behavior.
     * False skips plate art, rotation, prewarm, decode composition, and onPreviewPresented.
     */
    showPreviewPlate: Boolean = true,
    reducedMotion: Boolean = false,
    /** Home-only paired-media seam. A null callback keeps Tome Library faces plate-only. */
    onPreviewPresented: ((HomeTomePreview?) -> Unit)? = null,
    /** Whether the cover's own embossed Mannaz is drawn: off while the Tome wears its pin, a Mannaz of its own (one ᛗ per book). Default on, as it always was. */
    showMannaz: Boolean = true,
    /** The fade of [showMannaz], in ms: the pin's own clock where the pin stands on this Tome. */
    mannazFadeMs: Int = MimirMotion.FocusMs,
) {
    val platformIdValue = (tome.tomeId as? TomeId.Platform)?.platformId?.value
    if (!showPreviewPlate) {
        MimirNorseBookCover(
            focused = focused,
            eyebrow = homeTomeMemberCountLabel(libraryResources(), tome),
            title = tome.title,
            platformIdValue = platformIdValue,
            hasMembers = tome.memberCount > 0,
            focusScale = focusScale,
            showFocusRing = showFocusRing,
            compact = compact,
            showMannaz = showMannaz,
            mannazFadeMs = mannazFadeMs,
            plate = null,
            modifier = modifier,
        )
        return
    }
    val previews = remember(tome.tomeId, tome.previews) {
        tome.previews
            .filter { isHomeTomeLocalArtUri(it.artUri) }
            .distinctBy { preview -> preview.itemId ?: preview.artUri }
    }
    val previewIdentities = remember(previews) {
        previews.map { preview -> preview.itemId ?: preview.artUri }
    }
    var previewIndex by remember(tome.tomeId, previewIdentities) { mutableIntStateOf(0) }
    LaunchedEffect(focused, reducedMotion, previewIdentities) {
        if (reducedMotion || previews.size <= 1) {
            previewIndex = 0
            return@LaunchedEffect
        }
        if (!focused) return@LaunchedEffect
        while (true) {
            delay(HomeTomePreviewHoldMs)
            previewIndex = nextHomeTomePreviewIndex(previewIndex, previews.size)
        }
    }
    HomeTomePreviewArtPrewarm(previews.map(HomeTomePreview::artUri))
    val platePreview = previews.getOrNull(previewIndex)
    val currentPreviewPresented by rememberUpdatedState(onPreviewPresented)
    LaunchedEffect(focused, previews.isEmpty()) {
        if (focused && previews.isEmpty()) currentPreviewPresented?.invoke(null)
    }
    val plateContent: (@Composable BoxScope.() -> Unit)? = platePreview?.let { preview ->
        {
            HomeTomePreviewDissolve(
                target = preview,
                reducedMotion = reducedMotion,
                publishPresentation = focused && onPreviewPresented != null,
                onPresented = currentPreviewPresented,
            )
        }
    }
    MimirNorseBookCover(
        focused = focused,
        eyebrow = homeTomeMemberCountLabel(libraryResources(), tome),
        title = tome.title,
        platformIdValue = platformIdValue,
        hasMembers = tome.memberCount > 0,
        focusScale = focusScale,
        showFocusRing = showFocusRing,
        compact = compact,
        showMannaz = showMannaz,
        mannazFadeMs = mannazFadeMs,
        plate = plateContent,
        modifier = modifier,
    )
}

/** Keeps decoded outgoing art mounted until the next local thumbnail is actually ready. */
@Composable
private fun HomeTomePreviewDissolve(
    target: HomeTomePreview,
    reducedMotion: Boolean,
    publishPresentation: Boolean,
    onPresented: ((HomeTomePreview?) -> Unit)?,
) {
    var visiblePreview by remember { mutableStateOf<HomeTomePreview?>(null) }
    // A plate may prewarm while unfocused, but it is never presentation-authoritative until the
    // focused generation has passed the paired 1080-class gate as well.
    var pairedPresentation by remember(publishPresentation) {
        mutableStateOf<HomeTomePreview?>(null)
    }
    HomeTomePreviewReadyProbe(target, publishPresentation) { ready ->
        // One decode-ready snapshot advances both consumers. The plate and Home backdrop therefore
        // begin the same [HomeTomePreviewFadeMs] handoff and an outgoing game remains authoritative until both assets
        // for its successor are locally renderable.
        visiblePreview = ready
        if (publishPresentation) pairedPresentation = ready
    }
    // Publish from the committed decode-ready pair, never from a dissolve layer. The outgoing bed and
    // incoming veil coexist during the dissolve and therefore must not own presentation truth.
    LaunchedEffect(pairedPresentation, publishPresentation) {
        if (publishPresentation) pairedPresentation?.let { onPresented?.invoke(it) }
    }
    visiblePreview?.let { visible ->
        HomeTomePreviewBedVeil(incoming = visible, reducedMotion = reducedMotion)
    }
}

/** The plate keys layers exactly as the old dissolve keyed its children: game identity plus art URI. */
private fun homeTomePreviewDissolveIdentity(preview: HomeTomePreview): Any =
    preview.itemId to preview.artUri

/**
 * CP01-b : one opaque bed, one
 * ramping veil — the [HomeTomeBedVeil] form of the rotation dissolve. `Crossfade` fades BOTH children by
 * construction, and two `src-over` layers at complementary alpha let the book leaf carry up to a quarter
 * of the plate mid-fade; here the outgoing art stays at alpha 1 underneath while only the incoming ramps,
 * so the composite is `α·B + (1−α)·A` at every instant. Same [HomeTomePreviewFadeMs], same easing, no second clock.
 *
 * The two layers render through ONE loop with a `key()` per identity, deliberately: at commit the veil
 * entry becomes the bed entry of the same list, so Compose moves the keyed group instead of remounting
 * it and the layer's `AsyncImagePainter` survives. Two fixed role-slots would rebuild the painter at
 * commit — and a fresh painter is `State.Empty` on its first composition no matter how warm the memory
 * cache (Coil 2.7's synchronous placeholder path is preview-only), which would flash the bare leaf on
 * the exact frame the fade completes.
 */
@Composable
private fun HomeTomePreviewBedVeil(
    incoming: HomeTomePreview,
    reducedMotion: Boolean,
) {
    var layers by remember { mutableStateOf(HomeTomeBedVeil<HomeTomePreview>()) }
    // Applied during composition, exactly as Crossfade mutates its own visible-states list; convergent
    // because arrive() is identity-driven and returns an equal value once the arrival is absorbed.
    val arrived = layers.arrive(incoming, ::homeTomePreviewDissolveIdentity)
    if (arrived != layers) layers = arrived
    val veilIdentity = arrived.veil?.let(::homeTomePreviewDissolveIdentity)
    val rampMs = homeTomeVeilRampMs(reducedMotion)
    val reveal = remember { Animatable(0f) }
    // §3.4: the ramp is gated on the painter that DRAWS the veil, not on the probe's separate painter
    // upstream — otherwise the ramp runs while the veil still paints nothing and the art appears at
    // whatever alpha the curve has already reached (a partial snap). Spec §6 accepts the 1–3 frame
    // later start this costs on a warm cache.
    var veilArtReady by remember(veilIdentity) { mutableStateOf(false) }
    LaunchedEffect(veilIdentity, rampMs) {
        if (veilIdentity == null) {
            // Not tidiness: commit() flips the veil to null, which restarts this effect mid-body — the
            // cancelled body never reaches its own reset, so THIS branch is what parks the ramp at 0
            // for the next arrival. Nothing reads reveal while the veil is empty.
            reveal.snapTo(0f)
            return@LaunchedEffect
        }
        snapshotFlow { veilArtReady }.first { it }
        if (rampMs <= 0) {
            // Reduced motion (spec §5): duration-0 ramp + immediate atomic commit = a clean one-frame
            // cut. The rotation loop's own early-return above stays the load-bearing suppression.
            reveal.snapTo(1f)
        } else {
            reveal.animateTo(1f, tween(rampMs, easing = MimirMotion.Easing))
        }
        // Commit: bed := veil in ONE state write. Visually a no-op — bed and veil hold the same
        // identity on this frame, so removing the old bed underneath a fully-opaque veil changes no
        // pixel where the incoming art paints. A retarget mid-ramp cancels animateTo above and never
        // reaches this line.
        layers = layers.commit()
    }
    Box(Modifier.fillMaxSize()) {
        val stack = listOfNotNull(arrived.bed, arrived.veil)
        stack.forEachIndexed { index, preview ->
            val isVeil = index == stack.lastIndex && stack.size > 1
            key(homeTomePreviewDissolveIdentity(preview)) {
                HomeTomeLeadArt(
                    preview = preview,
                    // Draw-phase alpha reads only (Aurora grammar) — the ramp costs invalidation, not
                    // recomposition. The bed is HomeTomeBedAlpha: fully opaque at every instant.
                    alpha = if (isVeil) ({ reveal.value }) else HomeTomeBedAlpha,
                    onArtReady = if (isVeil) ({ veilArtReady = true }) else null,
                )
            }
        }
    }
}

@Composable
private fun HomeTomePreviewReadyProbe(
    preview: HomeTomePreview,
    publishPresentation: Boolean,
    onReady: (HomeTomePreview) -> Unit,
) {
    val context = LocalContext.current
    val plateRequest = remember(preview.artUri, context) {
        mimirCoverRequest(context, preview.artUri)
    }
    val platePainter = rememberAsyncImagePainter(plateRequest)
    val plateReady = when (val state = platePainter.state) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> state.painter != null
        else -> false
    }
    var backdropReady by remember(preview, publishPresentation) {
        mutableStateOf(!publishPresentation)
    }
    if (publishPresentation) {
        HomeTomePairedBackdropReadyProbe(preview) { backdropReady = true }
    }
    LaunchedEffect(preview, plateReady, backdropReady, publishPresentation) {
        if (plateReady && backdropReady) onReady(preview)
    }
}

/** Only the focused Home Tome pays the 1080-class decode gate; Tome Library faces stay plate-only. */
@Composable
private fun HomeTomePairedBackdropReadyProbe(
    preview: HomeTomePreview,
    onReady: () -> Unit,
) {
    val context = LocalContext.current
    val backdropUri = (preview.backdropArtUri ?: preview.artUri.takeUnless { preview.isPhysicalMedia })
        ?.takeIf(::isHomeTomeLocalArtUri)
    if (backdropUri == null) {
        LaunchedEffect(preview) { onReady() }
        return
    }
    val request = remember(backdropUri, context) {
        ImageRequest.Builder(context)
            .data(backdropUri)
            .size(MimirHomeStaticHeroDecodeWidthPx, MimirHomeStaticHeroDecodeHeightPx)
            .scale(Scale.FILL)
            .memoryCacheKey(mimirHomeStaticHeroCacheKey(backdropUri))
            .placeholderMemoryCacheKey(mimirHomeStaticHeroCacheKey(backdropUri))
            .crossfade(false)
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val ready = when (val state = painter.state) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> state.painter != null
        else -> false
    }
    LaunchedEffect(preview, ready) {
        if (ready) onReady()
    }
}

@Composable
private fun HomeTomePreviewArtPrewarm(uris: List<String>) {
    val context = LocalContext.current
    LaunchedEffect(uris, context) {
        val loader: ImageLoader = context.imageLoader
        uris.filter(::isHomeTomeLocalArtUri).forEach { uri ->
            loader.enqueue(
                mimirCoverRequest(context, uri),
            )
        }
    }
}

/** Known art owns a quiet tooled seat until decoded; no glyph or substitute identity ever flashes. */
@Composable
private fun HomeTomeLeadArt(
    preview: HomeTomePreview,
    /** Read in the draw phase only — the bed passes [HomeTomeBedAlpha], the veil its live ramp. */
    alpha: () -> Float = HomeTomeBedAlpha,
    /** CP01-b §3.4: the veil's ramp gate — the SAME painter instance that draws reports readiness. */
    onArtReady: (() -> Unit)? = null,
) {
    val uri = preview.artUri
    val context = LocalContext.current
    val request = remember(uri, context) {
        mimirCoverRequest(context, uri)
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
            // Provider support art is already the real transparent cartridge/disc/card composition;
            // preserve it with Fit. Cover/tile fallback keeps the authored edge-to-edge crop.
            contentScale = if (preview.isPhysicalMedia) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(MimirBoxArtCorner))
                .graphicsLayer {
                    this.alpha = alpha()
                    // CP01-b AFTER-run finding: with the default Auto strategy an alpha < 1 promotes
                    // this node to a compositing layer that rasterizes at LAYOUT size and is then
                    // magnified by the cover's ancestor focus-zoom (MimirNorseBook 1.02-1.03x) —
                    // while at alpha == 1 the same art draws direct at device resolution. Two
                    // different sampling chains of the same 1024px source made the ramping art
                    // measurably different from its own settled render (decorrelated label text,
                    // ~1px-fattened pale edges), which the G1 decomposition read as Σw<1 + bright k.
                    // ModulateAlpha folds the alpha into the single painter draw op instead — no
                    // buffer, one sampling chain in both states, arithmetically identical compositing
                    // for single-op content. The bed rides the same block: at 1.0 it is a no-op.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
    }
}
