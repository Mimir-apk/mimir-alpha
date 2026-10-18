package com.gruning.mimir.rewrite.feature.companion

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gruning.mimir.rewrite.core.ui.MimirArtFailureLogTag
import com.gruning.mimir.rewrite.core.ui.MimirColdArtRevealSpec
import com.gruning.mimir.rewrite.core.ui.mimirArtSourceWired
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a Bottom decoder has produced, in three states. The producer's value is
 * `CompanionDecodedArt?`:
 *
 *  - an OUTER null (the [rememberIdentityProducedValue] initial) means the decode has not finished: LOADING;
 *  - an inner null [bitmap] means the decode finished with nothing: FAILED, which draws nothing, exactly like loading;
 *  - [sourceMissing] is a decode that found the source's FILE gone (R8a): the pick no longer exists, so it counts as
 *    UNWIRED and takes the honest no-art fallback. It is not a failure.
 *  - [isCover] says the bitmap is a GAME'S cover (a Tabs tile's `artRef`), not an app's icon: the one app-icon shape (V8) is for icons only, and a
 *    cover keeps its own shape and its fit (art law).
 *
 * A plain `ImageBitmap?` could not say loading from failed, which is why a tile with art wired drew its letter through every
 * load: nothing told the draw that the letter was the answer to a question nobody had asked yet.
 */
internal class CompanionDecodedArt(val bitmap: ImageBitmap?, val sourceMissing: Boolean = false, val isCover: Boolean = false)

internal enum class CompanionArtLoad { LOADING, READY, FAILED }

/** What a Bottom art site draws: the art, nothing (the host ground only), or its honest no-art fallback. */
internal enum class CompanionArtDraw { ART, NOTHING, FALLBACK }

internal fun companionArtLoad(decoded: CompanionDecodedArt?): CompanionArtLoad = when {
    decoded == null -> CompanionArtLoad.LOADING
    decoded.bitmap == null -> CompanionArtLoad.FAILED
    else -> CompanionArtLoad.READY
}

/**
 * Whether the site has art wired: it has a source ([hasSource]) and that source's file was not found gone ([decoded]'s
 * [CompanionDecodedArt.sourceMissing], R8a). While the decode is still running a source counts as wired, so a load in
 * progress never shows the fallback.
 */
internal fun companionArtWired(hasSource: Boolean, decoded: CompanionDecodedArt?): Boolean =
    hasSource && decoded?.sourceMissing != true

/**
 * The one decision every Bottom art site obeys:
 *
 * | state            | draws                                                          |
 * |------------------|----------------------------------------------------------------|
 * | ready            | [CompanionArtDraw.ART] (cold: fades in; warm: snaps)          |
 * | loading          | [CompanionArtDraw.NOTHING]                                     |
 * | failed (R8)      | [CompanionArtDraw.NOTHING]                                     |
 * | nothing wired    | [CompanionArtDraw.FALLBACK], today's honest fallback           |
 *
 * [load] is irrelevant for an unwired site: with nothing wired there is nothing to wait for or to fail.
 */
internal fun companionArtDraw(load: CompanionArtLoad, wired: Boolean): CompanionArtDraw = when {
    !wired -> CompanionArtDraw.FALLBACK
    load == CompanionArtLoad.READY -> CompanionArtDraw.ART
    else -> CompanionArtDraw.NOTHING
}

/**
 * The `art.plate` receipt facts for a site, from the same state the draw reads (the harness grammar is
 * `state=none|loading|loaded|failed`). [hasArt] is whether art is WIRED, [loaded] and [failed] say where that art is, and
 * [plateVisible] is the fallback THIS site actually draws. A wired art that failed reads `hasArt=true state=failed plate=hidden`
 * with no breach: under R8 it draws nothing, and the failure is logged. A tile whose wired art is loading reads
 * `hasArt=true state=loading plate=hidden`, not a breach it does not commit.
 */
internal data class CompanionArtReceiptFacts(
    val hasArt: Boolean,
    val loaded: Boolean,
    val failed: Boolean,
    val plateVisible: Boolean,
)

internal fun companionArtReceiptFacts(load: CompanionArtLoad, wired: Boolean): CompanionArtReceiptFacts =
    CompanionArtReceiptFacts(
        hasArt = wired,
        loaded = wired && load == CompanionArtLoad.READY,
        failed = wired && load == CompanionArtLoad.FAILED,
        plateVisible = companionArtDraw(load, wired) == CompanionArtDraw.FALLBACK,
    )

/**
 * Decoded app icons, kept across tile remounts and across the three icon sites (the lattice, the dock and picker, the Tabs
 * drawer), so an icon that was decoded once paints on frame 0 the next time instead of loading from nothing. Budgeted in BYTES
 * like [HomeQuickLaunchPickerIconCache] (16 MiB, about 110 icons at 192 px) and keyed by the icon PATH: the path is content
 * addressed (`writeVersionedNativeIcon` stems it with the package-update epoch and a digest of the bytes), so one path names one
 * set of bytes and the cache cannot serve a stale icon.
 */
internal const val CompanionAppIconCacheBytes = 16 * 1024 * 1024

internal val companionAppIconCache: android.util.LruCache<String, ImageBitmap> =
    object : android.util.LruCache<String, ImageBitmap>(CompanionAppIconCacheBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

/**
 * The synchronous warm-paint read: the cached bitmap for [path] as a ready [CompanionDecodedArt], or null
 * when the path is null or not warm. Called from `remember(path, decode)` so a warm site is READY on its first composition and its
 * probe is refreshed when the site enters the decode window.
 */
internal fun companionWarmIcon(path: String?): CompanionDecodedArt? =
    path?.let { companionAppIconCache.get(it) }?.let { CompanionDecodedArt(it) }

/**
 * A Tabs tile's game cover, kept in the SAME byte-budgeted cache as the icons (so a memory trim that halves [companionAppIconCache] halves these too, and one bounded
 * budget covers both) under its own prefix. The cover is a content reference opened through the resolver, so the key is the reference itself. It used to be decoded
 * again, at full size and uncached, on every open of the drawer, and drawn blank until it landed.
 */
internal fun companionTabCoverKey(artRef: String): String = "tab-cover:$artRef"

/** The cached, bounded tab cover for [artRef] as a ready [CompanionDecodedArt] (a cover, not an icon), or null when it is not warm. Read synchronously, on the first frame. */
internal fun companionWarmTabCover(artRef: String?): CompanionDecodedArt? =
    artRef?.let { companionAppIconCache.get(companionTabCoverKey(it)) }?.let { CompanionDecodedArt(it, isCover = true) }

/**
 * The longest edge a tab cover is decoded to: the 72 dp card shows it at [CompanionTabIconDp] (about 125 px on the Thor), so this keeps four times that and never the
 * source's full size (a 2000 px cover is 16 MB decoded, which alone would evict every icon in the 16 MiB cache).
 */
internal const val CompanionTabCoverMaxPx = 512

/**
 * The power-of-two `inSampleSize` that brings a [width] x [height] source under twice [capPx] on its long edge, never below the cap itself: the same rule as the platform-art
 * lane's bounded decode, here for a stream that is opened twice (the bounds, then the pixels).
 */
internal fun companionCoverSampleSize(width: Int, height: Int, capPx: Int = CompanionTabCoverMaxPx): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= capPx) sample *= 2
    return sample
}

/**
 * Decodes the icon at [path] off the caller's thread and fills [companionAppIconCache]. A file that no longer exists is
 * [CompanionDecodedArt.sourceMissing] (R8a, unwired); a file that exists and does not decode is FAILED, and is logged (R8).
 * [wired] and [decode] are injectable so the decision is host-testable.
 */
internal suspend fun decodeCompanionIcon(
    path: String,
    wired: (String) -> Boolean = { mimirArtSourceWired(it) },
    decode: (String) -> ImageBitmap? = { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() },
): CompanionDecodedArt = withContext(Dispatchers.IO) {
    if (!wired(path)) {
        CompanionDecodedArt(bitmap = null, sourceMissing = true)
    } else {
        val bitmap = decode(path)
        if (bitmap != null) {
            companionAppIconCache.put(path, bitmap)
        } else {
            runCatching { Log.w(MimirArtFailureLogTag, "bottom icon failed to decode; drawing nothing: $path") }
        }
        CompanionDecodedArt(bitmap)
    }
}

/**
 * Verifies, OFF the main thread, that a WARM cache hit's source file still exists (R8a). A warm bitmap paints on frame 0 without
 * any stat, so deleting the file would otherwise leave stale art. When [wired] says the file is gone, [drop] removes the cache entry
 * and the answer is a missing-source [CompanionDecodedArt] (the fallback); when it is there the answer is null and the warm art
 * stands. A brief stale frame before the fallback is accepted.
 */
internal suspend fun companionVerifyWarm(
    source: String,
    wired: (String) -> Boolean = { mimirArtSourceWired(it) },
    drop: () -> Unit,
): CompanionDecodedArt? = withContext(Dispatchers.IO) {
    if (wired(source)) {
        null
    } else {
        drop()
        CompanionDecodedArt(bitmap = null, sourceMissing = true)
    }
}

/**
 * The verdict of the off-main verification of a warm hit: a missing-source [CompanionDecodedArt] once the file is found gone, null
 * while it is pending or when the file is there. It runs only for a site that holds a warm bitmap ([warm]), and re-runs when the site
 * enters the decode window again.
 */
@Composable
internal fun rememberCompanionWarmGone(
    identity: Any?,
    source: String?,
    warm: Boolean,
    wired: (String) -> Boolean = { mimirArtSourceWired(it) },
    drop: () -> Unit,
): CompanionDecodedArt? = rememberIdentityProducedValue(identity, source, warm) {
    if (!warm || source == null) null else companionVerifyWarm(source, wired, drop)
}

/**
 * The icon art for [path]: warm on frame 0 from [companionAppIconCache], else decoded once off-main, in three states. The warm
 * probe is refreshed when [decode] turns true (a site entering the decode window may find the cache filled meanwhile) and released
 * when it turns false, and nothing decodes while [decode] is false, so a tile outside the window reads as LOADING and draws
 * nothing. A warm hit is verified off-main ([rememberCompanionWarmGone]): a deleted file drops the entry and shows the fallback.
 */
@Composable
internal fun rememberCompanionIconArt(
    path: String?,
    decode: Boolean = true,
    identity: Any? = null,
    wired: (String) -> Boolean = { mimirArtSourceWired(it) },
): CompanionDecodedArt? {
    val warm = remember(path, decode) { if (decode) companionWarmIcon(path) else null }
    val produced = rememberIdentityProducedValue(identity, path, decode) {
        if (!decode || warm != null || path == null) null else decodeCompanionIcon(path, wired)
    }
    val gone = rememberCompanionWarmGone(identity, path, warm != null, wired, drop = { path?.let { companionAppIconCache.remove(it) } })
    return gone ?: warm ?: produced
}

/**
 * The shared warm path for an app icon drawn OUTSIDE the lattice, the dock and the Tabs drawer (the recent-app tile on the Activity page): the same cache and the
 * same off-main decode as [rememberCompanionIconArt], so an icon any of those sites decoded paints on the very first frame, and one none has decoded is read once off
 * the main thread. It draws nothing while it loads, when the file is gone, and after a failed decode. Policy Q13 (2026-10-14): an icon that
 * is warm when the site is born is whole on the first frame; one that arrives COLD fades in from nothing on FocusMs with the house easing, through the same art site
 * ([rememberCompanionArtSite]) the lattice and the dock use. Reduced motion snaps it. The fade is read in the layer, so it never recomposes.
 */
@Composable
fun CompanionAppIconArt(path: String?, modifier: Modifier = Modifier) {
    val art = rememberCompanionIconArt(path)
    val site = rememberCompanionArtSite(hasSource = path != null, decoded = art, identity = path)
    val bitmap = art?.bitmap ?: return
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier.graphicsLayer { alpha = site.reveal.value })
}

/**
 * One Bottom art site's resolved state: what it decoded, the decision ([companionArtDraw]), the receipt facts, and the reveal
 * alpha. [reveal] is 1 while the site draws its art or its fallback and 0 while it draws nothing; cold art fades it in, warm art
 * and reduced motion snap it in, and hiding always snaps. Read it inside a layer or draw lambda only.
 *
 * [reveal] is GUARDED by [draw]: a site whose draw state is [CompanionArtDraw.NOTHING] (loading, or failed) reads 0 on
 * the very frame it composes that state, whatever alpha its animation still holds. The animation catches up a frame later, and
 * without the guard a site that was mounted ready and is recycled onto a cold source drew its ground or plate for one frame
 * against a receipt that already said `plate=hidden`.
 */
internal class CompanionArtSite(
    val decoded: CompanionDecodedArt?,
    val draw: CompanionArtDraw,
    val facts: CompanionArtReceiptFacts,
    animatedReveal: State<Float>,
) {
    val bitmap: ImageBitmap? get() = decoded?.bitmap

    val reveal: State<Float> = object : State<Float> {
        override val value: Float get() = companionArtRevealAlpha(draw, animatedReveal.value)
    }
}

/** The alpha a site draws at: 0 for [CompanionArtDraw.NOTHING] at once, otherwise the animated [animated] value. */
internal fun companionArtRevealAlpha(draw: CompanionArtDraw, animated: Float): Float =
    if (draw == CompanionArtDraw.NOTHING) 0f else animated

/**
 * Resolves a Bottom art site from its [decoded] state. [hasSource] is whether a source is wired at all (a path, a reference);
 * [identity] and [window] key the warm-at-birth test, so a site recycled onto another source, or entering the decode window,
 * starts again. A site that has its bitmap in hand when it is born or enters the window (the memory cache) is WARM and snaps; only
 * art that arrives after that fades.
 */
@Composable
internal fun rememberCompanionArtSite(
    hasSource: Boolean,
    decoded: CompanionDecodedArt?,
    identity: Any?,
    window: Boolean = true,
): CompanionArtSite {
    val load = companionArtLoad(decoded)
    val wired = companionArtWired(hasSource, decoded)
    val draw = companionArtDraw(load, wired)
    val warmAtBirth = remember(identity, window) { decoded?.bitmap != null }
    val reveal = rememberCompanionArtReveal(
        visible = draw != CompanionArtDraw.NOTHING,
        fadeIn = draw == CompanionArtDraw.ART && !warmAtBirth,
    )
    return CompanionArtSite(decoded, draw, companionArtReceiptFacts(load, wired), reveal)
}

/**
 * Whether a reveal tweens (true) or snaps (false). Only a cold ART arrival fades in, and never under reduced motion; HIDING always
 * snaps, so a plate or ground cannot linger while a mounted site changes to a cold source.
 */
internal fun companionArtRevealFades(visible: Boolean, fadeIn: Boolean, reduced: Boolean): Boolean = visible && fadeIn && !reduced

/**
 * The reveal alpha for a Bottom art site: 1 when [visible]. Only a cold ART arrival ([fadeIn]) tweens, on the product's one cold-art spec ([MimirColdArtRevealSpec])
 * and the house easing (the 10-01 fade is for loading only); a warm bitmap, a fallback and reduced motion snap, and HIDING always
 * snaps, so a plate or ground never lingers while a mounted site changes to a cold source. Read inside a layer or draw lambda, so
 * the fade redraws and never recomposes.
 */
@Composable
internal fun rememberCompanionArtReveal(visible: Boolean, fadeIn: Boolean): State<Float> {
    val reduced = mimirReducedMotion()
    return animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (companionArtRevealFades(visible, fadeIn, reduced)) MimirColdArtRevealSpec else snap(),
        label = "companionArtReveal",
    )
}

/**
 * What a Tabs tile's decoded sources add up to. A game's cover outranks the emulator's icon and falls back to it. [coverWired] is a
 * cover that is wired (a content reference cannot be statted, so it counts; a local file counts only while it exists), and [icon] is
 * the icon's own outcome (null with no icon path). A decoded cover or icon is the art; a wired source that did not decode is FAILED
 * and draws nothing (R8); only when EVERY source is gone (a deleted local cover and an absent or deleted icon) is the tab unwired, so
 * it takes the fallback letter (R8a) instead of reading as a failure.
 */
internal fun companionTabArtOutcome(coverWired: Boolean, cover: ImageBitmap?, icon: CompanionDecodedArt?): CompanionDecodedArt = when {
    cover != null -> CompanionDecodedArt(cover, isCover = true)
    icon?.bitmap != null -> icon
    coverWired || (icon != null && !icon.sourceMissing) -> CompanionDecodedArt(bitmap = null)
    else -> CompanionDecodedArt(bitmap = null, sourceMissing = true)
}
