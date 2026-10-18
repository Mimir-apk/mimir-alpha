package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest

/**
 * How art that arrives COLD is revealed (policy 2026-10-14, Q13): nothing is drawn while it loads (no placeholder, no plate), and when it lands it fades in from
 * nothing on [MimirMotion.FocusMs] with [MimirMotion.Easing]. Art that is already warm when its site is born (in Coil's memory cache, or in an app-level icon cache) starts
 * whole, so most shows are instant and only a truly cold first show fades. Reduced motion snaps it in.
 *
 * This file is the ONE rule. Every art site speaks it through the pieces below: the spec ([MimirColdArtRevealSpec]), the alpha a site is born at
 * ([mimirColdArtStartAlpha]), the warm test ([mimirMemoryCacheWarm]) and the readiness test ([mimirArtLanded]). A site that animates a lambda alpha calls
 * [rememberMimirColdArtReveal]; one that keeps its own layers (the picker's retained image, the Bottom's art sites) takes the pieces and keeps its layering, so a cold
 * arrival reads the same everywhere.
 */
val MimirColdArtRevealSpec: TweenSpec<Float> = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)

/** The alpha a cold-art site is born at: whole for art that is warm (it paints on frame 0) or under reduced motion, nothing for a cold arrival. */
fun mimirColdArtStartAlpha(warm: Boolean, reducedMotion: Boolean): Float = if (warm || reducedMotion) 1f else 0f

/**
 * Whether [state] holds a finished image FOR [request], the request the site built and remembered. A painter keeps the PREVIOUS request's state until the new request
 * starts, so a bare `state is Success` is true for a frame or two after the model changed, with the old image: a reveal that trusted it faded the previous art in under
 * the new identity. A state is the request's only when the request it answered carries the same data.
 *
 * It compares against the request's own data, not the model the site was handed: a site may hand Coil a different type than it was given (a `Uri` model, a `String`
 * request), and a model that never equals the data hides its art forever.
 */
fun mimirArtLanded(state: AsyncImagePainter.State, request: ImageRequest?): Boolean =
    request != null && state is AsyncImagePainter.State.Success && state.result.request.data == request.data

/**
 * [identity] names the art (its model): a site recycled onto other art starts again. [warm] is read once, at the site's birth. [ready] is true once the painter has
 * something to draw. The returned lambda is the alpha, to be read inside a layer or draw block only (`graphicsLayer { alpha = reveal() }`), so the fade redraws and never
 * recomposes.
 */
@Composable
fun rememberMimirColdArtReveal(
    identity: Any?,
    warm: Boolean,
    ready: Boolean,
    reducedMotion: Boolean = mimirReducedMotion(),
): () -> Float {
    val alpha = remember(identity) { Animatable(mimirColdArtStartAlpha(warm, reducedMotion)) }
    LaunchedEffect(identity, ready, reducedMotion) {
        if (ready && alpha.value < 1f) {
            if (reducedMotion) {
                alpha.snapTo(1f)
            } else {
                alpha.animateTo(1f, MimirColdArtRevealSpec)
            }
        }
    }
    return { alpha.value }
}

/**
 * [rememberMimirColdArtReveal] for a site that draws from an `AsyncImagePainter`: it is ready only when [state] is a Success for [request] ([mimirArtLanded]), never a
 * stale Success left from the request before. [request] is the one the site remembered and handed the painter.
 */
@Composable
fun rememberMimirColdArtReveal(
    identity: Any?,
    warm: Boolean,
    state: AsyncImagePainter.State,
    request: ImageRequest?,
    reducedMotion: Boolean = mimirReducedMotion(),
): () -> Float = rememberMimirColdArtReveal(identity, warm, mimirArtLanded(state, request), reducedMotion)

/** Whether Coil's memory cache already holds [key]: one map lookup, safe to ask on the main thread. The key is the request's own (`mimirCoverCacheKey`, a hero key). */
fun mimirMemoryCacheWarm(context: Context, key: String): Boolean = mimirMemoryCacheWarm(context.imageLoader, key)

/** [mimirMemoryCacheWarm] against a loader of the caller's own (a picker that is handed one). */
fun mimirMemoryCacheWarm(imageLoader: ImageLoader, key: String): Boolean =
    imageLoader.memoryCache?.get(MemoryCache.Key(key)) != null
