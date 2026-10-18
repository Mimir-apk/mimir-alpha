package com.gruning.mimir.rewrite.core.ui

/**
 * What a shelf-scene raster's draw pass must do for the key it was just asked to paint -- the Bottom's theme-scene
 * backdrop, or the Main shelf's full-size background.
 *
 * a theme switch left the Bottom with a
 * 262 ms main-thread hole and then swapped the whole backdrop in one frame, because the miniature was baked inside
 * `drawWithCache` on the UI thread and drawn as one texture per palette. [MimirSceneBackdropSwap] is the pure
 * bookkeeping of the fix -- keep the old raster on screen while the new one bakes off the UI thread, then
 * crossfade -- kept free of bitmaps, threads and Compose so every transition is unit-testable. `MimirShelfBackground`
 * on Main runs the same bookkeeping as a CUTTING swap (`crossfadeMillis = 0`): its full-size bake had the same hole
 * (55.3% of the Thor's main-thread samples on a cold switch, 2026-09-11), and §4.2(c) keeps Main's one-frame change.
 */
internal enum class MimirSceneBackdropStep {
    /** The requested raster is already shown (or fading in), or its bake is already out: draw what the swap holds. */
    DRAW,

    /**
     * Nothing may stand in meanwhile -- the first bake, or a change of anything but the palette (size, density,
     * scene style): bake on the calling thread, exactly as before, then call [MimirSceneBackdropSwap.showNow].
     */
    BAKE_NOW,

    /**
     * A palette change with the previous raster still drawable and nothing cached for the new key: bake it off the
     * UI thread and report it through [MimirSceneBackdropSwap.landed] (or [landOffThreadBake], which also publishes or
     * releases the raster). The old raster keeps drawing until then.
     */
    BAKE_OFF_THREAD,

    /**
     * A palette change whose raster was already cached: the crossfade has begun, so frames must be driven. A cutting
     * swap never answers this -- it shows the cached raster at once and answers [DRAW].
     */
    CROSSFADE,
}

/**
 * The shown / outgoing / pending rasters of one theme-scene backdrop, and the crossfade clock between them.
 *
 * UI-thread-owned, like the bitmap cache it sits in front of: every call happens on the thread that draws. The only
 * work that runs elsewhere is the bake a caller starts on [MimirSceneBackdropStep.BAKE_OFF_THREAD], and its result
 * comes back through [landed] on the UI thread.
 *
 * "Released" means dropped: [outgoing] is cleared the moment the fade completes, and [release] clears everything. A
 * raster is never recycled here -- the process-wide cache, and any other backdrop drawing the same key, may still
 * hold it.
 *
 * A non-positive [crossfadeMillis] makes a CUTTING swap: a cached palette ([request]) or a landed bake ([landed]) is
 * shown at once through [showNow], [outgoing] is never set, and [request] never answers
 * [MimirSceneBackdropStep.CROSSFADE]. `MimirShelfBackground` on Main uses it; no crossfade there.
 */
internal class MimirSceneBackdropSwap<K : Any, B : Any>(
    private val crossfadeMillis: Int = MimirMotion.SceneMs,
) {
    /** A zero-length crossfade is a cut: nothing ever fades out. */
    private val cuts: Boolean
        get() = crossfadeMillis <= 0

    /** The key of [shown]. */
    var shownKey: K? = null
        private set

    /** The raster drawn at full weight -- or, while a crossfade runs, the one fading IN. */
    var shown: B? = null
        private set

    /** The raster fading OUT beneath [shown]; null whenever no crossfade runs. */
    var outgoing: B? = null
        private set

    /** The key whose off-thread bake is still expected; a bake for any other key is stale when it lands. */
    var pendingKey: K? = null
        private set

    private var fadeStartNanos: Long = FadeNotStarted
    private var frameLoopActive = false

    /**
     * Decide what painting [key] takes. [differsOnlyInPalette] (shown key, requested key) says whether the shown
     * raster may stand in while the new one bakes; [cachedFor] is consulted only when that answer is yes.
     */
    fun request(
        key: K,
        differsOnlyInPalette: (K, K) -> Boolean,
        cachedFor: (K) -> B?,
    ): MimirSceneBackdropStep {
        val currentKey = shownKey
        if (currentKey == key && shown != null) {
            // Back on the raster already shown: a bake still out for another key is stale from here on.
            pendingKey = null
            return MimirSceneBackdropStep.DRAW
        }
        if (currentKey == null || shown == null || !differsOnlyInPalette(currentKey, key)) {
            outgoing = null
            pendingKey = null
            fadeStartNanos = FadeNotStarted
            return MimirSceneBackdropStep.BAKE_NOW
        }
        if (pendingKey == key) return MimirSceneBackdropStep.DRAW
        val cached = cachedFor(key)
        if (cached != null) {
            if (cuts) {
                showNow(key, cached)
                return MimirSceneBackdropStep.DRAW
            }
            crossfadeTo(key, cached)
            return MimirSceneBackdropStep.CROSSFADE
        }
        pendingKey = key
        return MimirSceneBackdropStep.BAKE_OFF_THREAD
    }

    /** Show [bitmap] for [key] at once, with no crossfade -- the [MimirSceneBackdropStep.BAKE_NOW] path. */
    fun showNow(key: K, bitmap: B) {
        shownKey = key
        shown = bitmap
        outgoing = null
        pendingKey = null
        fadeStartNanos = FadeNotStarted
    }

    /**
     * The off-thread bake for [key] finished. It is adopted -- the crossfade begun, or for a cutting swap the raster
     * shown at once -- only while [key] is still the pending one; returns whether it was.
     */
    fun landed(key: K, bitmap: B): Boolean {
        if (pendingKey != key) return false
        if (cuts) showNow(key, bitmap) else crossfadeTo(key, bitmap)
        return true
    }

    /**
     * The weight of [shown] at frame [frameNanos]: 1 when no crossfade runs, 0 until the fade's first frame has been
     * [advance]d (so nothing jumps before the clock starts), then [mimirSceneBackdropCrossfadeAlpha].
     */
    fun incomingAlpha(frameNanos: Long): Float {
        if (outgoing == null) return 1f
        if (fadeStartNanos == FadeNotStarted) return 0f
        return mimirSceneBackdropCrossfadeAlpha(frameNanos - fadeStartNanos, crossfadeMillis)
    }

    /**
     * Step the crossfade to frame [frameNanos]; the first call pins the fade's start. Releases [outgoing] once the
     * fade completes. Returns true when nothing is left to fade.
     */
    fun advance(frameNanos: Long): Boolean {
        if (outgoing == null) return true
        if (fadeStartNanos == FadeNotStarted) fadeStartNanos = frameNanos
        if (mimirSceneBackdropCrossfadeAlpha(frameNanos - fadeStartNanos, crossfadeMillis) < 1f) return false
        outgoing = null
        fadeStartNanos = FadeNotStarted
        return true
    }

    /** Claim the one frame loop that drives [advance]; false while a loop already runs (it picks up a restarted fade). */
    fun beginFrameLoop(): Boolean {
        if (frameLoopActive) return false
        frameLoopActive = true
        return true
    }

    /** Hand the frame loop back -- the loop settled or its scope was cancelled. */
    fun endFrameLoop() {
        frameLoopActive = false
    }

    /** Drop every raster and any pending bake: the backdrop left composition. */
    fun release() {
        shownKey = null
        shown = null
        outgoing = null
        pendingKey = null
        fadeStartNanos = FadeNotStarted
    }

    private fun crossfadeTo(key: K, bitmap: B) {
        // A crossfade already under way is not blended into a third raster: the raster fading in becomes the one
        // fading out and the clock restarts -- a one-frame step, reachable only by a second palette change landing
        // inside one SceneMs fade.
        outgoing = shown
        shown = bitmap
        shownKey = key
        pendingKey = null
        fadeStartNanos = FadeNotStarted
    }

    private companion object {
        const val FadeNotStarted = Long.MIN_VALUE
    }
}

/**
 * The crossfade a theme-scene backdrop's swap runs: [MimirMotion.SceneMs], or none under reduced motion. A non-positive
 * length makes [MimirSceneBackdropSwap] a CUTTING swap -- the Main shelf's own -- so the Bottom screen and the compact
 * miniature cut on a theme or background change instead of fading under the reduced-motion cut policy.
 */
internal fun mimirSceneBackdropCrossfadeMillis(reducedMotion: Boolean): Int =
    if (reducedMotion) 0 else MimirMotion.SceneMs

/**
 * The incoming raster's weight [elapsedNanos] into a crossfade of [durationMillis]: 0 at or before the start, 1 from
 * the nominal end on, and the house curve [MimirMotion.Easing] in between -- the curve the Bottom's own SceneMs
 * handoff rides (`MimirCompanionPresentation.kt`, `tween(MimirMotion.SceneMs, easing = MimirMotion.Easing)`). A
 * non-positive duration is already settled.
 */
internal fun mimirSceneBackdropCrossfadeAlpha(elapsedNanos: Long, durationMillis: Int): Float {
    if (durationMillis <= 0) return 1f
    if (elapsedNanos <= 0L) return 0f
    val fraction = elapsedNanos.toDouble() / (durationMillis * 1_000_000.0)
    if (fraction >= 1.0) return 1f
    return MimirMotion.Easing.transform(fraction.toFloat()).coerceIn(0f, 1f)
}

/**
 * Whether [next] differs from this key in its palette or its ground ALONE -- the changes [MimirThemeSceneBackdrop]
 * covers by keeping the old raster on screen while the new one bakes. The ground joined the palette here with the
 * player's backgrounds (concept A, 2026-09-30): a background change is a whole-raster change of the same shape as a
 * theme switch, and without this it would bake on the UI thread. A size, density, direction, scene-style, cloud,
 * downscale or blur change keeps the synchronous bake: stretching an old raster over new geometry, or fading
 * between two scene styles, is a change to the established appearance.
 */
internal fun MimirShelfBackgroundCacheKey.differsOnlyInPaletteFrom(next: MimirShelfBackgroundCacheKey): Boolean =
    (palette != next.palette || ground != next.ground) && copy(palette = next.palette, ground = next.ground) == next

/**
 * What became of one off-thread bake once its coroutine finished -- the `outcome=` of the Main shelf's `MimirShelfBake`
 * log line ([mimirShelfBakeLogMessage]).
 */
internal enum class MimirSceneBakeOutcome(val logName: String) {
    /**
     * Still the pending key: the raster was published to the UI-thread-owned cache -- or, when the cache declines its
     * size, to the surface alone, exactly as the synchronous path does -- and is drawn from the next frame.
     */
    INSERTED("inserted"),

    /** Still the pending key, but another surface had already cached that key: its raster is adopted, this one released. */
    ALREADY_CACHED("already-cached"),

    /** No longer the pending key -- a newer palette, a switch back or a geometry change overtook it: released unpublished. */
    STALE("stale"),

    /** The surface left composition before the raster reached the UI thread: released unpublished. */
    CANCELLED("cancelled"),
}

/**
 * Land the off-thread bake of [key] -- its raster [result] -- on the UI thread, the only thread that may touch the cache
 * [publish] writes to.
 *
 * The swap is asked FIRST. A result whose key is no longer pending is [release]d and [publish] is never called, so a
 * stale raster can neither replace the newer key on screen nor take a slot in the bounded cache: two stale results
 * published into a two-entry cache would push out the raster of the key that overtook them. Only a pending result is
 * published; when [publish] hands back a raster other than [rasterOf] `result` -- another surface cached the key first
 * -- that raster is adopted and this one released. [release] may free the raster outright: in every branch that calls
 * it, the raster never reached the cache or the screen.
 */
internal fun <K : Any, B : Any, R : Any> MimirSceneBackdropSwap<K, B>.landOffThreadBake(
    key: K,
    result: R,
    rasterOf: (R) -> B,
    publish: (K, R) -> B,
    release: (R) -> Unit,
): MimirSceneBakeOutcome {
    if (pendingKey != key) {
        release(result)
        return MimirSceneBakeOutcome.STALE
    }
    val raster = publish(key, result)
    landed(key, raster)
    if (raster === rasterOf(result)) return MimirSceneBakeOutcome.INSERTED
    release(result)
    return MimirSceneBakeOutcome.ALREADY_CACHED
}
