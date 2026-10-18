package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WHERE THE ART ACTUALLY IS INSIDE ITS FILE.
 *
 * Equal seats showed unequal apparent box-art sizes on Odin: Dreamcast filled its seat while
 * GameCube art appeared smaller. The visible artwork, rather than the provider canvas, needs
 * consistent dimensions and padding.
 *
 * The difference is not 3D-versus-flat — **both** are Box (3D) renders. It is that the two provider files
 * carry different amounts of empty canvas around the box, and every surface here sizes art by its FILE,
 * via `ContentScale.Fit`. Measured on the Odin the same day, in seats that were already equal-area:
 * Resident Evil's GameCube render painted ~31 % ink, Sonic Adventure 2's Dreamcast render very nearly
 * all of its seat. So a correct layout still produced covers that read half the size of each other,
 * and no amount of seat geometry could have closed it.
 *
 * This measures the opaque rectangle inside the file so a seat can be sized to the ART rather than to
 * the artboard. Normalised, so it survives any decode scale.
 */
@Immutable
data class MimirArtInkBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Absolute width/height of the ink in source pixels — what a seat should be shaped like. */
    val aspect: Float,
) {
    val widthFraction: Float get() = (right - left).coerceAtLeast(MinInkFraction)
    val heightFraction: Float get() = (bottom - top).coerceAtLeast(MinInkFraction)

    /**
     * True when trimming would actually change what the player sees.
     *
     * A flat cover that already fills its file trims to itself, and hugging it would be pure churn — a
     * recomposition and a re-layout to arrive at the same picture. [FillsCanvasFraction] is deliberately
     * loose: provider files routinely carry a pixel or two of transparent edge, and treating that as
     * "needs hugging" would make every cover in the library take the slow path for nothing.
     */
    val worthHugging: Boolean
        get() = widthFraction < FillsCanvasFraction || heightFraction < FillsCanvasFraction

    companion object {
        const val MinInkFraction = 0.02f
        const val FillsCanvasFraction = 0.97f
    }
}

/**
 * Scans an ARGB grid for the tightest rectangle containing every pixel that is not effectively
 * transparent. Pure, so the rule is testable without a device or a decoder.
 *
 * [alphaFloor] is a floor rather than `> 0` on purpose: a resampled 3D render leaves a halo of
 * near-zero alpha well outside the box it drew, and honouring those pixels reproduces the untrimmed
 * canvas almost exactly — the bug wearing a tighter bounding box.
 *
 * Returns null when there is nothing to say: a fully transparent image (no ink to hug), or a fully
 * opaque one (nothing to trim). Both mean "leave this art alone", and a null is how the caller keeps
 * today's behaviour rather than inventing a rectangle.
 */
fun mimirScanArtInkBounds(
    argb: IntArray,
    width: Int,
    height: Int,
    alphaFloor: Int = DefaultInkAlphaFloor,
): MimirArtInkBounds? {
    if (width <= 0 || height <= 0 || argb.size < width * height) return null
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val alpha = (argb[row + x] ushr 24) and 0xFF
            if (alpha < alphaFloor) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    if (maxX < minX || maxY < minY) return null
    val inkW = (maxX - minX + 1).toFloat()
    val inkH = (maxY - minY + 1).toFloat()
    if (inkW <= 0f || inkH <= 0f) return null
    return MimirArtInkBounds(
        left = minX / width.toFloat(),
        top = minY / height.toFloat(),
        right = (maxX + 1) / width.toFloat(),
        bottom = (maxY + 1) / height.toFloat(),
        aspect = inkW / inkH,
    )
}

/** Below this, a pixel is the resampling halo around a 3D render rather than part of the box. */
const val DefaultInkAlphaFloor = 24


/**
 * Opens an art URI for a second, measurement-only read.
 *
 * The `#mv=` fragment `localRenderUri` appends is a CACHE key, not part of the document's address —
 * a `content://` DocumentsProvider will refuse a URI carrying it. Stripped here rather than at the
 * store's key, because the key must keep the fragment: that is what makes a re-picked face measure
 * again instead of inheriting the previous picture's trim.
 */
internal fun mimirOpenArtStream(
    context: Context,
    resolver: ContentResolver,
    artUri: String,
): java.io.InputStream? = runCatching {
    val address = artUri.substringBefore('#')
    resolver.openInputStream(Uri.parse(address))
}.getOrNull()

/**
 * What one measurement attempt concluded.
 *
 * Three outcomes, not two, and the third is the point: **"I could not measure this" is not
 * "this has no margin"**. Collapsing them is what let one broken decode write a permanent
 * no-margin verdict for every cover in the library.
 */
sealed interface MimirArtInkMeasurement {
    data class Measured(val bounds: MimirArtInkBounds) : MimirArtInkMeasurement
    /** Measured successfully; there is nothing to trim. Worth remembering so it is never redone. */
    data object NoInkToHug : MimirArtInkMeasurement
    /** The attempt failed. Records nothing, so the next visit asks again. */
    data object Unmeasurable : MimirArtInkMeasurement
}

/**
 * Turns a completed scan into a verdict. Lifted out of the decoder so the rule can be tested without a
 * device: everything above it is Android framework calls, and this is the only part that decides.
 *
 * A null scan here means the decode SUCCEEDED and found no ink — a fully transparent file. That is a
 * finding, so it is remembered. It is never how a failure arrives; failures do not reach this function.
 */
fun mimirArtInkOutcome(scanned: MimirArtInkBounds?): MimirArtInkMeasurement = when {
    scanned == null -> MimirArtInkMeasurement.NoInkToHug
    scanned.worthHugging -> MimirArtInkMeasurement.Measured(scanned)
    else -> MimirArtInkMeasurement.NoInkToHug
}

/**
 * Per-art ink bounds, measured once and remembered.
 *
 * Shaped after [MeasuredCoverAspects] deliberately — same store, same discipline: a snapshot-state read
 * so a composable recomposes when a measurement lands, persistence so a revisit starts settled, and
 * **one reflow ever per art**, at that art's first measure. The alternative, measuring during layout,
 * would put a decode on the critical path of a scrolling list.
 *
 * That reflow is a change of the seat the art is solved into, and it is seen unless the surface keeps the art out of sight
 * until it has happened. On Nova, the list painted each art at the platform seat on the first
 * frame and again at its ink seat afterward, visibly resizing each reached title.
 * the decode had landed, for every art it had not measured yet. So the store says when it is done with an art
 * ([hasVerdict]), a surface holds its art until then, and it can ask the store to measure the arts around the one it is
 * showing ([prefetch]) so the answer is in before the art is reached.
 *
 * Keyed by art URI. A hand-picked face changing writes a NEW uri, so a stale entry cannot outlive the
 * picture it describes — which is why this does not need to hang off `artVersion` the way the cover
 * projection does.
 */
object MimirArtInkStore {
    private const val PrefsName = "mimir_rewrite_art_ink"
    private const val Absent = "-"

    private val bounds: SnapshotStateMap<String, MimirArtInkBounds> = mutableStateMapOf()
    /** Keys measured and found not worth hugging — remembered so they are never decoded twice. */
    private val settledFlat = mutableSetOf<String>()
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    /**
     * Keys the store has said everything it will say about THIS SESSION: measured, found flat, or (session only, nothing
     * persisted) found unmeasurable. A snapshot-state map, so a composable that holds its art until [hasVerdict] recomposes
     * the moment the verdict lands. Everything in [bounds] and [settledFlat] is in here, and so is the one outcome neither
     * keeps, which is what lets a hold end on an art that cannot be measured.
     */
    private val verdicts: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /** The one load of the persisted verdicts. Concurrent callers wait for it instead of reading an empty store. */
    private val loadGate = Mutex()
    @Volatile private var loadedDone = false
    private var prefs: SharedPreferences? = null

    /** Test seam only: what a measurement is, and where it runs. See [setMeasurementForTest]. */
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var measurement: suspend (Context, String) -> MimirArtInkMeasurement = { context, key -> decodeAndScan(context, key) }

    /** Snapshot-state read: composables calling this recompose when a measurement lands. */
    fun boundsFor(artUri: String?): MimirArtInkBounds? = artUri?.let { bounds[it] }

    /**
     * Whether the store has a verdict on [artUri] this session, from a measurement or from the persisted store. A surface
     * that sizes a seat from [boundsFor] must not PAINT the art at a seat solved before this is true: the art would be seen
     * at one size and then at another, and nothing here can know the ink before a decode has scanned it. True for a null or
     * blank uri, which has nothing to measure. Snapshot-state read.
     */
    fun hasVerdict(artUri: String?): Boolean = artUri.isNullOrBlank() || verdicts[artUri] == true

    suspend fun ensureLoaded(context: Context) {
        if (loadedDone) return
        loadGate.withLock {
            if (loadedDone) return
            val store = withContext(ioDispatcher) {
                context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
                    .also { it.all } // force the XML parse off the main thread
            }
            prefs = store
            for ((key, value) in store.all) {
                val encoded = value as? String ?: continue
                if (encoded == Absent) {
                    settledFlat += key
                    verdicts[key] = true
                    continue
                }
                decode(encoded)?.let {
                    bounds[key] = it
                    verdicts[key] = true
                }
            }
            loadedDone = true
        }
    }

    /**
     * Measures [artUri] if it has never been measured. Safe to call on every composition — the
     * in-flight and settled sets make every call after the first a set lookup.
     */
    suspend fun measure(context: Context, artUri: String?) {
        val key = artUri?.takeIf { it.isNotBlank() } ?: return
        while (true) {
            if (key in bounds || key in settledFlat) return
            val mine = CompletableDeferred<Unit>()
            val theirs = synchronized(inFlight) { inFlight[key].also { if (it == null) inFlight[key] = mine } }
            if (theirs == null) {
                try {
                    measureOnce(context, key)
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                    mine.complete(Unit)
                }
                return
            }
            // Someone else is measuring this art (the list's look-ahead and the panel can ask for the same one). Wait for them
            // rather than returning: a caller that returned would be left with no verdict coming if the other was then
            // cancelled, because the player had moved on. If they were, this goes round and measures it itself.
            theirs.await()
            if (hasVerdict(key)) return
        }
    }

    private suspend fun measureOnce(context: Context, key: String) {
        val measured = withContext(ioDispatcher) { measurement(context, key) }
        val store = prefs ?: withContext(ioDispatcher) {
            context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        }.also { prefs = it }
        when (measured) {
            is MimirArtInkMeasurement.Measured -> {
                bounds[key] = measured.bounds
                store.edit().putString(key, encode(measured.bounds)).apply()
            }
            MimirArtInkMeasurement.NoInkToHug -> {
                // Remembered as a NEGATIVE, not simply left absent: an unrecorded key is
                // indistinguishable from an unmeasured one, and this art would be decoded again on
                // every visit for the rest of the install.
                settledFlat += key
                store.edit().putString(key, Absent).apply()
            }
            // NOT recorded. A failure to measure is not a finding of "no margin", and writing one
            // as the other is exactly how a broken decode poisoned every cover it touched
            // permanently. Left unknown so the next visit asks again.
            MimirArtInkMeasurement.Unmeasurable -> Unit
        }
        // The verdict is IN for this session whichever it was: a surface holding its art for it lets go, and an art that
        // cannot be measured is painted at the seat it has rather than held for ever. Not the same as remembered: only
        // the two findings above are written, so the next visit to an unmeasurable art asks again.
        verdicts[key] = true
    }

    /**
     * Measures, in order, what [artUris] names that has no verdict yet. A surface that knows which arts the player is about to
     * see (the rows around the focused one of a list) calls this so the verdict is in before the art is reached, and the seat
     * is solved once, for the art that is about to be painted, instead of twice. Sequential on purpose: one decode at a time,
     * nearest first, on the caller's own scope, so moving on cancels what is no longer wanted.
     */
    suspend fun prefetch(context: Context, artUris: List<String?>) {
        ensureLoaded(context)
        for (artUri in artUris) {
            if (hasVerdict(artUri)) continue
            measure(context, artUri)
        }
    }

    /**
     * Decodes small and scans.
     *
     * **The stream is bound to a val on purpose.** The first draft wrote
     * `open()?.use { BitmapFactory.decodeStream(it, null, header) } ?: return null`, which reads as a
     * null-check on the STREAM and is actually a null-check on the DECODE — and a bounds-only decode
     * returns null by definition, so the elvis fired every single time. Device-caught 2026-08-28: the
     * store ran, measured nothing, and wrote the "no margin" verdict for a cover that is 48 % ink.
     */
    private fun decodeAndScan(context: Context, artUri: String): MimirArtInkMeasurement {
        val resolver = context.applicationContext.contentResolver
        return runCatching {
            val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val headerStream = mimirOpenArtStream(context, resolver, artUri)
                ?: return MimirArtInkMeasurement.Unmeasurable
            headerStream.use { BitmapFactory.decodeStream(it, null, header) }
            if (header.outWidth <= 0 || header.outHeight <= 0) {
                return MimirArtInkMeasurement.Unmeasurable
            }
            var sample = 1
            while (header.outWidth / sample > ScanEdgePx || header.outHeight / sample > ScanEdgePx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val pixelStream = mimirOpenArtStream(context, resolver, artUri)
                ?: return MimirArtInkMeasurement.Unmeasurable
            val bitmap = pixelStream.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return MimirArtInkMeasurement.Unmeasurable
            try {
                // No alpha channel is a real ANSWER, not a failure: the file fills its own canvas.
                if (!bitmap.hasAlpha()) return MimirArtInkMeasurement.NoInkToHug
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                mimirArtInkOutcome(mimirScanArtInkBounds(pixels, bitmap.width, bitmap.height))
            } finally {
                bitmap.recycle()
            }
        }.getOrElse { MimirArtInkMeasurement.Unmeasurable }
    }

    private fun encode(value: MimirArtInkBounds): String =
        "${value.left},${value.top},${value.right},${value.bottom},${value.aspect}"

    private fun decode(raw: String): MimirArtInkBounds? {
        val parts = raw.split(',')
        if (parts.size != 5) return null
        val nums = parts.map { it.toFloatOrNull() ?: return null }
        return MimirArtInkBounds(nums[0], nums[1], nums[2], nums[3], nums[4])
    }

    /** Test seam only — the store is process-scoped and would otherwise leak between cases. */
    fun clearForTest() {
        bounds.clear()
        verdicts.clear()
        settledFlat.clear()
        synchronized(inFlight) { inFlight.clear() }
        prefs = null
        loadedDone = false
        ioDispatcher = Dispatchers.IO
        measurement = { context, key -> decodeAndScan(context, key) }
    }

    /**
     * Test seam only. Swaps what a measurement is (and the dispatcher it, and the preferences read, run on) so a test can decide
     * when an art "decodes": a measurement that waits on the test's own signal is an image that decodes late. Both are put back
     * by [clearForTest].
     */
    fun setMeasurementForTest(
        dispatcher: CoroutineDispatcher,
        measure: suspend (Context, String) -> MimirArtInkMeasurement,
    ) {
        ioDispatcher = dispatcher
        measurement = measure
    }

    /** Longest edge the scan decodes to. A trim rectangle does not get more truthful past this. */
    private const val ScanEdgePx = 256
}

/**
 * How to draw a canvas so its INK lands Fit inside a seat.
 *
 * Expressed as a DRAW-TIME transform — a scale and a translation for `graphicsLayer` — over an image
 * that already fills the seat with `ContentScale.FillBounds`.
 *
 * **Not as an oversized child, which is what the first two attempts did.** `Modifier.size` is coerced
 * into the incoming constraints, and `requiredSize` escapes them but then fights the Box's placement;
 * device-caught 2026-08-28, twice, the second time with the arithmetic provably correct and the render
 * still wrong. A `graphicsLayer` has no constraints to argue with: the layout is exactly the seat, and
 * only the pixels move.
 *
 * Because `FillBounds` has already stretched the canvas onto the seat, the scale here does two jobs at
 * once — it undoes that stretch and re-applies the one the ink needs. Both fall out of the same ratio,
 * which is why they are not two corrections.
 *
 * Units are the caller's: pass pixels (from `graphicsLayer`'s own `size`) and the translations come back
 * in pixels. [transformOriginX]/[transformOriginY] are 0 — the arithmetic assumes the top-left pivot.
 */
data class MimirArtInkDraw(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
) {
    companion object {
        const val transformOriginX = 0f
        const val transformOriginY = 0f
    }
}

fun mimirArtInkDraw(
    seatWidth: Float,
    seatHeight: Float,
    ink: MimirArtInkBounds,
): MimirArtInkDraw {
    val seatW = seatWidth.coerceAtLeast(1f)
    val seatH = seatHeight.coerceAtLeast(1f)
    val inkAspect = ink.aspect.coerceAtLeast(MimirArtInkBounds.MinInkFraction)
    // Fit the INK into the seat. When the seat was solved for this art the two agree and the fit is a
    // no-op; when it was not -- the carousel's platform-shaped cell -- the ink centres instead of
    // overflowing, so an un-converted seat degrades safely rather than clipping.
    //
    // Safely, NOT identically. A surface that has not opted in still Fit-draws the CANVAS and so paints
    // the art smaller; the carousel adopting this is owed work under the same ink-fit policy, not a difference
    // this function papers over.
    val widthBound = (seatW / seatH) <= inkAspect
    val inkW = if (widthBound) seatW else seatH * inkAspect
    val inkH = if (widthBound) seatW / inkAspect else seatH
    val scaleX = inkW / (seatW * ink.widthFraction)
    val scaleY = inkH / (seatH * ink.heightFraction)
    return MimirArtInkDraw(
        scaleX = scaleX,
        scaleY = scaleY,
        translationX = -(ink.left * seatW * scaleX) + (seatW - inkW) / 2f,
        translationY = -(ink.top * seatH * scaleY) + (seatH - inkH) / 2f,
    )
}

/**
 * The longest a surface holds an art for [MimirArtInkStore.hasVerdict] before it paints it anyway. A hold is for the tens of
 * milliseconds a small decode takes; it must never be the reason an art is not on screen, so past this the art is painted at
 * the seat it has (and may move once when the verdict lands, which is what it did before the hold existed). Not a motion clock:
 * nothing is animated by it.
 */
const val MimirArtInkHoldCapMs = 600
