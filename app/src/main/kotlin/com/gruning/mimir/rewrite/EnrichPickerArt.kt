package com.gruning.mimir.rewrite

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import com.gruning.mimir.rewrite.core.ui.MimirColdArtRevealSpec
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirArtLanded
import com.gruning.mimir.rewrite.core.ui.mimirColdArtStartAlpha
import com.gruning.mimir.rewrite.core.ui.mimirCoverCacheKey
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.mimirHold
import com.gruning.mimir.rewrite.core.ui.mimirMemoryCacheWarm
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Customize media's image painters and the glass under its dropdowns: the motion half of the page, kept out of the
// 4 000-line picker (audit 2026-10-14: RC-26, RC-28).

/**
 * True while [requested], and for [holdMs] after it ends, on the animation clock. The glass behind a dropdown follows this, not the
 * raw request, so a leaving dropdown dissolves over a steady blur and the page clears only once the dropdown is gone to the eye
 * (a modal dissolves fully before the glass under it clears; the same hold the Library's card and search keep, with
 * [MimirMotion.OverlayDissolveCoverMs] being where a panel fading out on OverlayMs is at alpha .044).
 */
@Composable
internal fun rememberHomeCustomEnrichGlassHold(
    requested: Boolean,
    holdMs: Int = MimirMotion.OverlayDissolveCoverMs,
): Boolean {
    var held by remember { mutableStateOf(requested) }
    LaunchedEffect(requested) {
        if (requested) {
            held = true
        } else if (held) {
            mimirHold(holdMs)
            held = false
        }
    }
    return held
}

/**
 * One image a [RetainedArtImage] has shown: the model it decoded for, its painter, and the alpha it is dissolving in at. A layer that is [leaving] (the model went away)
 * is dissolving out on the same clock and is dropped when it is gone; it is no longer "the art on top".
 */
internal class RetainedArtLayer(val model: Any, val painter: Painter, initialAlpha: Float) {
    val alpha = Animatable(initialAlpha)
    var leaving by mutableStateOf(false)

    /** The coroutine dissolving this layer out while it is [leaving]; cancelled when the same model comes back and the layer is revived. */
    var fadeOutJob: Job? = null
}

/**
 * The layers a [RetainedArtImage] draws, oldest first. A new model's art is appended and dissolves in over what is there; when it is whole,
 * everything beneath it is dropped. A model that arrives while the one before is still dissolving stacks on top of it, and that one carries on,
 * so a held D-pad over the candidates never drops a half-faded image in a single frame.
 */
internal class RetainedArtLayers {
    val stack = mutableStateListOf<RetainedArtLayer>()

    /** The model whose art is on top, or null with nothing shown. A layer that is leaving is not on top. */
    val topModel: Any? get() = stack.lastOrNull { !it.leaving }?.model

    /** Everything beneath [layer] is covered once it is whole. */
    fun pruneBeneath(layer: RetainedArtLayer) {
        val at = stack.indexOf(layer)
        if (at > 0) stack.removeRange(0, at)
    }

    /**
     * "No art here": every layer dissolves out on the cold-art clock ([MimirColdArtRevealSpec], FocusMs) and is dropped once it is gone, or at once under reduced motion.
     * Nothing is cleared in a single frame (audit W1A #8). A model that arrives meanwhile appends its own layer over the leaving ones and is never cleared with them.
     */
    fun fadeOut(scope: CoroutineScope, reduced: Boolean) {
        val going = stack.filter { !it.leaving }
        if (going.isEmpty()) return
        going.forEach { it.leaving = true }
        if (reduced) {
            stack.removeAll(going)
            return
        }
        going.forEach { layer ->
            layer.fadeOutJob = scope.launch {
                layer.alpha.animateTo(0f, MimirColdArtRevealSpec)
                stack.remove(layer)
            }
        }
    }

    /**
     * The same [model] coming back while its own art is still dissolving out: that layer is brought back, not copied. A second layer for the same image fading in over
     * its own leaving copy composites to less than whole at the crossing (a dip), where one layer going back up never goes below where it was. The layer takes the top
     * of the stack, so it is "the art on top" again. Returns the layer, or null when no leaving layer holds [model].
     */
    fun revive(model: Any): RetainedArtLayer? {
        val layer = stack.lastOrNull { it.leaving && it.model == model } ?: return null
        layer.fadeOutJob?.cancel()
        layer.fadeOutJob = null
        layer.leaving = false
        if (stack.lastOrNull() !== layer) {
            stack.remove(layer)
            stack.add(layer)
        }
        return layer
    }
}

/**
 * The picker's image painter (audit 2026-10-14, RC-28). A candidate's art used to be a bare `AsyncImage` or
 * `rememberAsyncImagePainter`: on a model swap (focus moved to the next candidate) it dropped to Loading and drew nothing until
 * the next file decoded, so the stage blanked and then popped.
 *
 *  - **The request is the shared cover request** ([mimirCoverRequest]): one cache identity with the shelf, the platform tiles and the
 *    warm passes, so a model seen before paints on its FIRST frame from Coil's memory cache (its `placeholderMemoryCacheKey`), and
 *    nothing here decodes on the composition thread.
 *  - **The last good image stays** while the next model decodes, and the new image dissolves in over it on [MimirMotion.FocusMs]
 *    and the house curve (a reduced-motion player gets the cut). Nothing is cleared in between, so there is no blank frame.
 *  - A COLD first show (nothing before it, and the cache did not hold the model when it was asked for) draws nothing -- no placeholder, not
 *    even for the one frame between the decode landing and the layer taking over -- and fades in from nothing. A WARM first show paints on
 *    frame 0 and is never faded. The warm test, the alpha a first show is born at and the fade's spec are the product's one cold-art rule
 *    ([mimirMemoryCacheWarm], [mimirColdArtStartAlpha], [MimirColdArtRevealSpec]; Q13); this keeps only the layering the swap needs.
 *
 * A null [model] draws nothing and forgets what was shown, but not in one frame: the art dissolves out on the same clock
 * ([RetainedArtLayers.fadeOut]). "No art here" is not the previous candidate's art.
 */
@Composable
internal fun RetainedArtImage(
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    imageLoader: ImageLoader = LocalContext.current.imageLoader,
    onError: (() -> Unit)? = null,
    onIntrinsicSize: ((Size) -> Unit)? = null,
) {
    val layers = remember { RetainedArtLayers() }
    val scope = rememberCoroutineScope()
    val reduced = mimirReducedMotion()
    val context = LocalContext.current
    val request = remember(model, context) { model?.let { mimirCoverRequest(context, it) } }
    // Asked once per model, on the frame it is first asked for: a model the shared cache holds paints on frame 0 and is not faded in.
    val warm = remember(model, imageLoader) { model != null && mimirMemoryCacheWarm(imageLoader, mimirCoverCacheKey(model)) }
    // `rememberAsyncImagePainter` is called for a null request too (it answers an Error state and draws nothing): one composition shape.
    val painter = rememberAsyncImagePainter(model = request, imageLoader = imageLoader, contentScale = contentScale)
    val state = painter.state

    LaunchedEffect(model, state) {
        if (model == null) {
            layers.fadeOut(scope, reduced)
            return@LaunchedEffect
        }
        // The painter keeps the PREVIOUS model's state until the new request starts: a state is this model's only when its request was for it.
        if (state is AsyncImagePainter.State.Error && state.result.request.data == request?.data) onError?.invoke()
        if (!mimirArtLanded(state, request)) return@LaunchedEffect
        val loaded = state as AsyncImagePainter.State.Success
        if (layers.topModel == model) return@LaunchedEffect
        // The model that just left is back before its art is gone: bring that layer back up from where it is, never a second copy over the leaving one (audit W2A the earlier update).
        val revived = layers.revive(model)
        if (revived != null) {
            if (reduced) {
                revived.alpha.snapTo(1f)
                layers.pruneBeneath(revived)
            } else {
                scope.launch {
                    revived.alpha.animateTo(1f, MimirColdArtRevealSpec)
                    layers.pruneBeneath(revived)
                }
            }
            return@LaunchedEffect
        }
        // A first show is born by the product's one cold-art rule; a swap always dissolves over the art it replaces.
        val firstShow = layers.stack.isEmpty()
        val born = if (firstShow) mimirColdArtStartAlpha(warm, reduced) else if (reduced) 1f else 0f
        val layer = RetainedArtLayer(model, loaded.painter, initialAlpha = born)
        layers.stack.add(layer)
        if (layer.alpha.value >= 1f) {
            layers.pruneBeneath(layer)
        } else {
            // Its own scope: a later model must not cancel a fade that is half done.
            scope.launch {
                layer.alpha.animateTo(1f, MimirColdArtRevealSpec)
                layers.pruneBeneath(layer)
            }
        }
    }
    if (onIntrinsicSize != null) {
        val intrinsic = painter.intrinsicSize
        LaunchedEffect(intrinsic) {
            if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) onIntrinsicSize(intrinsic)
        }
    }

    Box(
        modifier = modifier.then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        ),
    ) {
        layers.stack.forEach { layer ->
            key(layer) {
                Image(
                    painter = layer.painter,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha * layer.alpha.value },
                )
            }
        }
        if (warm && model != null && layers.topModel != model && layers.stack.isEmpty()) {
            // A warm first show: the painter itself, so the model the cache already holds paints on frame 0 (its placeholder). A cold one has no
            // seat here: drawn directly it would land at full alpha for the frame before its layer starts the fade.
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

/**
 * The aspect (width over height) a Default tile's art has, remembered for the process: the header read that gives it is on IO and lands
 * after the first frame, so a strip that sized its Default tile from the fallback jumped to the art's own width every time the page
 * opened (audit 10-14, H-09d). A second open reads the memory on frame 0 and does not jump. The first one still reads the header, under
 * the page's 130 ms arrival lead. Bounded, keyed by the art's model string.
 */
internal object EnrichDefaultAspectMemory {
    private const val Capacity = 32
    private val aspects = ConcurrentHashMap<String, Float>()

    fun peek(model: Any?): Float? = (model as? String)?.let(aspects::get)

    fun remember(model: Any?, aspect: Float) {
        val key = model as? String ?: return
        if (!aspect.isFinite() || aspect <= 0f) return
        if (aspects.size >= Capacity && !aspects.containsKey(key)) aspects.keys.firstOrNull()?.let(aspects::remove)
        aspects[key] = aspect
    }

    internal fun clear() = aspects.clear()
}
