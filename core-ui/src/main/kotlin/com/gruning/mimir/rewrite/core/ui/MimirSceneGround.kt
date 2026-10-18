package com.gruning.mimir.rewrite.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The player's own BACKGROUND behind shelves: each theme remembers its own choice, the row is
 * "Background" with default "Atmosphere", and images come only from the player.
 *
 * A ground replaces the theme's drawn atmosphere scene wherever that scene is baked today ([MimirShelfBackground] on
 * Home, Library and the theme gallery's cards; [mimirThemeSceneBackdrop] on the compact browser and the Bottom
 * screen). The priority on Home is unchanged: a game's hero covers it, as it covers the scene. It never changes the
 * palette.
 *
 * The model is immutable and plain: the app's `AppearanceOverrideStore` decodes it from one preference
 * (`appearanceGround.<THEME> = sha|DIM|BLUR`) and the sidecar beside the processed file, and publishes the committed
 * choices through [MimirAppearanceOverrides.grounds]; [MimirTheme] hands them to every window as [LocalMimirSceneGrounds].
 * The Background picker previews on its own stage by passing a ground in as a parameter -- there is no global preview,
 * because one would rebuild the Bottom's backdrop on every D-pad move.
 *
 * @property id the content hash the processed file is named by (the same image imported twice is stored once).
 * @property path the app's private, already-processed copy (`filesDir/appearance/backgrounds/<sha>.jpg`).
 * @property width the processed copy's width, from its sidecar.
 * @property height the processed copy's height, from its sidecar.
 * @property p90 the 90th-percentile WCAG relative luminance of the processed copy, measured once on a 1/8 copy at
 *   import: the brightness of the image's brightest tenth. Recorded only: NOTHING decides on it any more (removed the readability floor).
 * @property dim how far the image is dimmed toward `surfaceBase`: exactly what is drawn, whatever the image (the player decides what they can see).
 * @property blur the blur of the FULL-SIZE shelves only (the compact layout and the Bottom keep their own soft blur).
 */
@Immutable
data class MimirSceneGround(
    val id: String,
    val path: String,
    val width: Int,
    val height: Int,
    val p90: Float,
    val dim: MimirSceneGroundDim = MimirSceneGroundDim.MEDIUM,
    val blur: MimirSceneGroundBlur = MimirSceneGroundBlur.OFF,
)

/**
 * The Dim row: a `surfaceBase` veil over the image at [alpha]. Ordinal order is strength order. The chosen step is what is drawn.
 */
enum class MimirSceneGroundDim(val label: String, val alpha: Float) {
    LIGHT("Light", 0.35f),
    MEDIUM("Medium", 0.52f),
    STRONG("Strong", 0.70f),
    DEEP("Deep", 0.86f),
}

/**
 * The Blur row, applied ONCE at bake time in software, never live (a live blur measured 20-45 ms/frame on the Bottom).
 * Soft is the Bottom screen's current look: the crop drawn at [downscale] 1/4, box-blurred by [radiusPx] in the small
 * raster's own pixels, then stretched back bilinearly. Strong halves that again.
 */
enum class MimirSceneGroundBlur(val label: String, val downscale: Int, val radiusPx: Int) {
    OFF("Off", 1, 0),
    SOFT("Soft", 4, 2),
    STRONG("Strong", 8, 2),
}

/** The one mark style of the alpha (R3): the theme metal is painted through the image's mask. No full-colour style. */
enum class MimirButtonMarkStyle { STRUCK }

/**
 * The player's own mark on the Mimir button, in place of the rune. Persisted as `appearanceButtonMark = sha|STRUCK`;
 * the file is a 256x256 PNG with alpha under `filesDir/appearance/marks/`. The renderer (`CompanionMenuCoinView`) and
 * the mark processing are implemented in the native companion module; this is only the contract every window and the store share.
 *
 * [scale], [offsetX] and [offsetY] are where the player put the image on the coin : its size as a share of the 44 dp box and
 * its centre from the coin's centre in dp on the 56 dp reference coin. They are the three numbers of `MimirMarkPlacement` in `:core-model`,
 * which owns the range, the clamps and the geometry; they are plain floats here because this module does not depend on core-model. The
 * defaults are the image centred at 100%, which is how every mark was drawn before the player could place it.
 */
@Immutable
data class MimirButtonMark(
    val id: String,
    val path: String,
    val style: MimirButtonMarkStyle = MimirButtonMarkStyle.STRUCK,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * The committed grounds, by theme, published by [MimirTheme] to every window that wears the theme -- both screens and
 * the panels over other apps -- so a shelf looks its ground up by the palette it is given (`grounds[palette.id]`) and
 * each theme gallery card shows its own theme's image. A window that must never show one (onboarding) provides an
 * empty map. A `compositionLocalOf`, not static: a commit changes exactly the readers, which are the few shelf roots.
 */
val LocalMimirSceneGrounds = compositionLocalOf<Map<MimirThemeId, MimirSceneGround>> { emptyMap() }

// ── Geometry ──────────────────────────────────────────────────────────────────────────────────────

/** The source rectangle a crop-to-fill draw reads from the image (`ContentScale.Crop`, centred). */
data class MimirGroundCrop(val offset: IntOffset, val size: IntSize)

/**
 * The centred source rectangle whose aspect matches a [targetWidth] x [targetHeight] surface: the image is scaled by
 * the LARGER of the two ratios (so it covers the surface) and the overhang is cut equally from both sides. A
 * degenerate target or image answers the whole image.
 */
fun mimirGroundCropRect(imageWidth: Int, imageHeight: Int, targetWidth: Float, targetHeight: Float): MimirGroundCrop {
    if (imageWidth <= 0 || imageHeight <= 0 || targetWidth <= 0f || targetHeight <= 0f) {
        return MimirGroundCrop(IntOffset.Zero, IntSize(imageWidth.coerceAtLeast(0), imageHeight.coerceAtLeast(0)))
    }
    val scale = max(targetWidth / imageWidth, targetHeight / imageHeight)
    val sourceWidth = (targetWidth / scale).roundToInt().coerceIn(1, imageWidth)
    val sourceHeight = (targetHeight / scale).roundToInt().coerceIn(1, imageHeight)
    return MimirGroundCrop(
        offset = IntOffset((imageWidth - sourceWidth) / 2, (imageHeight - sourceHeight) / 2),
        size = IntSize(sourceWidth, sourceHeight),
    )
}

/**
 * The power-of-two `BitmapFactory` sample that keeps a [width] x [height] file's long edge at or above [maxLongEdge]
 * while never decoding more than it must. A stored ground is already at most 1920 px on its long edge, so this is 1
 * for every file the store wrote; it is the bound a stray larger file meets.
 */
fun mimirGroundInSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
    val longEdge = max(width, height)
    if (longEdge <= 0 || maxLongEdge <= 0) return 1
    var sample = 1
    while (longEdge / (sample * 2) >= maxLongEdge) sample *= 2
    return sample
}

// ── The decoded copies ────────────────────────────────────────────────────────────────────────────

/**
 * The decoded ground images, by id: ONE RGB_565 copy each (about 1 MB on the RG Rotate's 720 px, about 4 MB at 1080p),
 * bounded to [MaxEntries] on an access order. [MaxEntries] is the saved list's own cap: every ground a theme, the theme
 * gallery or the picker can show is one of the saved images, so all of them stay resident and a D-pad sweep across the
 * picker's tiles, or a pass through the gallery, finds every copy already decoded.
 *
 * Decoded off the main thread at process start, on every commit and as the picker opens ([prefetch]), so a surface's
 * first synchronous bake never reads the file. A bake on the UI thread that does not find its copy resident does NOT
 * decode it (`planSynchronousBake`): it paints the theme's Atmosphere and the image bakes off-thread next, landing on
 * the swap's own clock. [getOrDecode] is for a thread that may block -- the off-thread bake -- and a decode it makes on
 * the main thread anyway is logged, `MimirSceneGround: sync decode id=<id>`, an id and never a path, so the device can
 * see it. A decode failure (the copy missing or corrupt) answers null: the bake falls back to the theme's atmosphere,
 * and the picker's tile reads PREVIEW UNAVAILABLE.
 *
 * Thread-safe: the map is guarded, and a decode runs outside the guard. An evicted bitmap is never recycled here -- a
 * bake on another thread may still be drawing it; the collector takes it once the last raster that read it is gone.
 */
object MimirSceneGroundImages {
    /**
     * Six: the saved list holds at most six backgrounds (`AppearanceImageKind.BACKGROUND`), and a ground is always one of
     * them. At four the picker's own prefetch of six evicted itself. Each copy is 1-4 MB, so this is the ceiling, not a
     * budget to raise.
     */
    const val MaxEntries = 6

    /** The long edge no decoded copy exceeds: the largest display the store scales for. */
    internal const val MaxLongEdgePx = 1920

    private const val Tag = "MimirSceneGround"

    private val lock = Any()
    private val entries = LinkedHashMap<String, ImageBitmap>(8, 0.75f, true)
    private val inFlight = HashSet<String>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The decoded copy for [id], if resident; never decodes. Counts as a use for the eviction order. */
    fun get(id: String): ImageBitmap? = synchronized(lock) { entries[id] }

    /**
     * The decoded copy for [ground], decoding on the calling thread when it is not resident. Null when the file fails.
     * Never call this from a draw or a composition: a UI-thread bake takes [get] or `planSynchronousBake`. A decode made
     * on the main thread is logged, so it cannot happen unseen.
     */
    fun getOrDecode(ground: MimirSceneGround): ImageBitmap? {
        get(ground.id)?.let { return it }
        if (isMainThread()) runCatching { Log.w(Tag, "sync decode id=${ground.id}") }
        val decoded = decodeGroundFile(ground.path)
        if (decoded == null) {
            Log.w(Tag, "ground=${ground.id} decode failed; drawing the theme's atmosphere instead")
            return null
        }
        return publish(ground.id, decoded)
    }

    /** Decode every non-resident [grounds] entry on the IO dispatcher; a decode already in flight is not started twice. */
    fun prefetch(grounds: Collection<MimirSceneGround>) {
        grounds.forEach { ground ->
            val claimed = synchronized(lock) { entries[ground.id] == null && inFlight.add(ground.id) }
            if (!claimed) return@forEach
            prefetchScope.launch {
                try {
                    decodeGroundFile(ground.path)?.let { publish(ground.id, it) }
                } finally {
                    synchronized(lock) { inFlight.remove(ground.id) }
                }
            }
        }
    }

    /** Drop the resident copy for [id] (the image was removed from the saved list). */
    fun forget(id: String) {
        synchronized(lock) { entries.remove(id) }
    }

    internal val entryCount: Int
        get() = synchronized(lock) { entries.size }

    /** False where there is no Android looper (a plain JVM test): nothing there is a UI thread. */
    private fun isMainThread(): Boolean =
        runCatching { Looper.getMainLooper()?.thread === Thread.currentThread() }.getOrDefault(false)

    private fun publish(id: String, bitmap: ImageBitmap): ImageBitmap = synchronized(lock) {
        entries[id]?.let { return it }
        entries[id] = bitmap
        while (entries.size > MaxEntries) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        bitmap
    }

    private fun decodeGroundFile(path: String): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = mimirGroundInSampleSize(bounds.outWidth, bounds.outHeight, MaxLongEdgePx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }.getOrNull()
}

// ── The bake on the UI thread never reads a file ─────────────────────────────────────────────────────────────

/**
 * What a bake on the UI thread -- [MimirSceneBackdropStep.BAKE_NOW], a surface's first bake or a change of anything but
 * the palette -- may paint for the key it was asked for. A first bake cannot show the previous raster, so it must not
 * wait for a file either because a first bake never reads a file: a fresh swap per stage `Crossfade` child, per
 * theme-gallery card, per destination mount would otherwise decode a 1-4 MB image on the UI thread at each D-pad move.
 *
 * @property key the key to bake and cache the raster under: the requested one, or the requested one WITHOUT its ground
 *   (the theme's Atmosphere) when the ground's copy is not resident -- never a raster of one look under the other's key.
 * @property groundImage the resident decoded copy [key]'s ground paints from; null for Atmosphere, or when the raster is
 *   already cached and no bake will run.
 * @property groundPending the ground's copy is not resident and its raster is not cached: [key] is the Atmosphere
 *   stand-in, and the caller asks the swap for the requested key again -- a change of the ground alone, which bakes
 *   off the UI thread (decoding there) and lands on the swap's own clock (a cut on Main, the scene crossfade on the
 *   Bottom, a cut under reduced motion).
 */
internal class MimirSynchronousBakePlan(
    val key: MimirShelfBackgroundCacheKey,
    val groundImage: ImageBitmap?,
    val groundPending: Boolean,
)

/**
 * Plan a UI-thread bake of [requested]: paint it as asked when it names no ground, when the ground's decoded copy is
 * [residentImage], or when its raster is already [rasterCached]; otherwise stand the Atmosphere in and report the ground
 * pending. Reads no file and decodes nothing -- [residentImage] must not either ([MimirSceneGroundImages.get]).
 */
internal fun planSynchronousBake(
    requested: MimirShelfBackgroundCacheKey,
    residentImage: (String) -> ImageBitmap? = MimirSceneGroundImages::get,
    rasterCached: (MimirShelfBackgroundCacheKey) -> Boolean,
): MimirSynchronousBakePlan {
    val ground = requested.ground ?: return MimirSynchronousBakePlan(requested, groundImage = null, groundPending = false)
    val image = residentImage(ground.id)
    if (image != null || rasterCached(requested)) {
        return MimirSynchronousBakePlan(requested, groundImage = image, groundPending = false)
    }
    return MimirSynchronousBakePlan(requested.copy(ground = null), groundImage = null, groundPending = true)
}
