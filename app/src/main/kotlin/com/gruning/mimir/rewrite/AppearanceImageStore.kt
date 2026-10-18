package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirSceneGround
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The player's appearance images (concept A): the app's own processed copies under
 * `filesDir/appearance/<kind>/`, each named by a hash of the SOURCE bytes with a `.json` sidecar beside it
 * (width, height, `p90`, the date added, and the copy's length in bytes, which is how a truncated copy is told from a whole
 * one without decoding it). The same image imported twice is stored once. Nothing here touches the
 * player's storage after the import's bounded copy, so a revoked storage grant has no effect on a saved choice.
 *
 * The two saved lists ARE these directories: up to [AppearanceImageKind.capacity] images each (Customize Media's
 * limit), ordered by the date added. `AppearanceOverrideStore` reads them at load and after every import or
 * removal; the pickers take the lists as data.
 *
 * Processing crops and scales images to the viewport:
 *  - **Background:** centre-cropped to the main display's aspect and scaled to at most its pixel size (long edge at
 *    most [MaxLongEdgePx]; never upscaled) -- [appearanceBackgroundPlan] -- so a stored copy is never larger than the
 *    screen needs and never letterboxed; decoded to sRGB in software, saved as JPEG quality [BackgroundJpegQuality];
 *    `p90` measured once on a 1/8 copy of the stored crop ([appearanceLuminanceP90]). An animated image keeps its
 *    first frame; a small image is accepted with a note. The second screen keeps its soft blur of the same copy (R4).
 *  - **Mark:** [AppearanceMarkProcessing] (`AppearanceMarkProcessing.kt`): a 256 px centre crop written as a PNG whose
 *    alpha is the mask -- the image's own transparency, or an opaque image's luminance. The button strikes the theme's
 *    metal through it.
 *
 * The bounded copy (32 MB cap, 32 px minimum) is `CustomImageImportStore`'s, and the atomic `.part` install is
 * `installHeroAsset`, so an interrupted import never leaves a half-written file where a whole one is expected.
 */
internal enum class AppearanceImageKind(val directoryName: String, val fileExtension: String, val capacity: Int) {
    BACKGROUND("backgrounds", "jpg", 6),
    MARK("marks", "png", 6),
}

/** One saved image: its processed file and the sidecar's facts. */
internal data class AppearanceSavedImage(
    val kind: AppearanceImageKind,
    val id: String,
    val file: File,
    val width: Int,
    val height: Int,
    val p90: Float,
    val addedAtMillis: Long,
    /**
     * The processed copy's length in bytes, recorded in the sidecar at import; null for a sidecar written before the
     * length was recorded.
     */
    val bytes: Long? = null,
    /**
     * Where the player put this image on the Mimir button : its size and its offset from the coin's centre, kept per image so
     * each one brings its own back when the player switches. Read from the sidecar's optional `scale`, `offsetX` and `offsetY`; a sidecar that
     * records none (every image saved before the player could place it) is the image centred at 100%. Only a mark has one.
     */
    val placement: MimirMarkPlacement = MimirMarkPlacement.Default,
    /**
     * [wholeOnDisk] as it stood when the sidecar was read (`list` / `find`), or null for an image built by hand. A picker opens from the saved
     * lists the stores already hold, so reading this costs it no stat on the main thread (audit 2026-10-14, RC-29: S-24, S-25); the two stats
     * were taken where the list was read, on whatever thread read it, and [listOffMain] reads it on IO.
     */
    val availableAtRead: Boolean? = null,
) {
    val path: String get() = file.absolutePath

    /**
     * False once the copy is missing, empty, or -- when its length was recorded at import -- not that length any more: a
     * truncated but non-empty copy would otherwise read SAVED with a blank thumbnail and be committed over Atmosphere.
     * The tile then reads PREVIEW UNAVAILABLE, X removes it, and A refuses it. A file's stat, never a decode. Answered from the
     * state the list was read in ([availableAtRead]); an image built without a read stats the file here. A legacy sidecar with no
     * recorded length counts as available when the file is non-empty.
     */
    val available: Boolean get() = availableAtRead ?: wholeOnDisk()

    /** The two stats behind [available]: blocking, so a caller that is not already off the main thread asks [AppearanceImageStore.listOffMain]. */
    fun wholeOnDisk(): Boolean = file.isFile && file.length().let { length -> length > 0L && (bytes == null || length == bytes) }

    fun toGround(dim: MimirSceneGroundDim, blur: MimirSceneGroundBlur): MimirSceneGround =
        MimirSceneGround(id = id, path = path, width = width, height = height, p90 = p90, dim = dim, blur = blur)

    fun toMark(): MimirButtonMark =
        MimirButtonMark(id = id, path = path, scale = placement.scale, offsetX = placement.offsetX, offsetY = placement.offsetY)
}

internal sealed interface AppearanceImportOutcome {
    /** The image is in the saved list, with the note the picker shows ("Uploaded · Saved", and any caveat). */
    data class Saved(val image: AppearanceSavedImage, val note: String) : AppearanceImportOutcome

    /** "Saved is full. Remove an unused choice first." -- nothing was written. */
    data object Full : AppearanceImportOutcome

    /** The bytes could not be read or decoded, or were under the size floor / over the byte cap. */
    data object Unusable : AppearanceImportOutcome

    /**
     * The picture is a fine image with nothing in it to use as a mark: blank, or one flat colour (cert E6). Told apart
     * from [Unusable] so the player is not sent to pick a "PNG, JPEG, WebP, or HEIF" they just did.
     */
    data object NoSubject : AppearanceImportOutcome
}

/** What a processor made of a picture: the processed copy, a picture with nothing to use, or one that cannot be used at all. */
internal sealed interface AppearanceProcessOutcome {
    data class Processed(val image: AppearanceProcessedImage) : AppearanceProcessOutcome

    data object NoSubject : AppearanceProcessOutcome

    data object Unusable : AppearanceProcessOutcome
}

/** The picker note for an import that did not save anything, or for [AppearanceImportOutcome.Saved] its own. Pure, for the copy test. */
internal fun appearanceImportNote(outcome: AppearanceImportOutcome): String = when (outcome) {
    is AppearanceImportOutcome.Saved -> outcome.note
    AppearanceImportOutcome.Full -> AppearanceImageStore.NoteFull
    AppearanceImportOutcome.Unusable -> AppearanceImageStore.NoteUnusable
    AppearanceImportOutcome.NoSubject -> AppearanceImageStore.NoteNoSubject
}

/** The largest attached display, in pixels, the background is scaled to cover. */
internal data class AppearanceDisplaySize(val width: Int, val height: Int)

internal object AppearanceImageStore {
    private const val Tag = "MimirAppearanceImage"
    private const val RootDirectoryName = "appearance"
    internal const val MaxLongEdgePx = 1_920
    internal const val BackgroundJpegQuality = 90
    private const val MinSourceEdgePx = 32

    /** Below half the display's long edge the upscale at draw time is visible: "It may look soft." */
    internal const val SoftBelowDisplayFraction = 0.5f

    internal const val NoteSaved = "Uploaded · Saved"
    internal const val NoteAlreadySaved = "Already in Saved"
    internal const val NoteSoft = "It may look soft."
    internal const val NoteAnimated = "Animated images are saved as a still."
    internal const val NoteFull = "Saved is full. Remove an unused choice first."
    internal const val NoteUnusable = "That image couldn’t be used. Choose a PNG, JPEG, WebP, or HEIF."
    internal const val NoteNoSubject = "That image has no shape to use as a mark. Choose one with a clear subject."

    fun root(context: Context, kind: AppearanceImageKind): File =
        File(File(context.applicationContext.filesDir, RootDirectoryName), kind.directoryName)

    fun imageFile(context: Context, kind: AppearanceImageKind, id: String): File =
        File(root(context, kind), "$id.${kind.fileExtension}")

    private fun sidecarFile(context: Context, kind: AppearanceImageKind, id: String): File =
        File(root(context, kind), "$id.json")

    /** Every saved image of [kind] whose sidecar reads, oldest first. Cheap when the directory does not exist. */
    fun list(context: Context, kind: AppearanceImageKind): List<AppearanceSavedImage> {
        val files = root(context, kind).listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { sidecar -> readSidecar(context, kind, sidecar.name.removeSuffix(".json")) }
            .sortedWith(compareBy({ it.addedAtMillis }, { it.id }))
    }

    fun find(context: Context, kind: AppearanceImageKind, id: String): AppearanceSavedImage? =
        if (isAppearanceImageId(id)) readSidecar(context, kind, id) else null

    /** [list] on the IO dispatcher: the directory listing, every sidecar's JSON and each copy's two stats, none of them on the caller's thread. */
    suspend fun listOffMain(context: Context, kind: AppearanceImageKind): List<AppearanceSavedImage> =
        withContext(Dispatchers.IO) { list(context, kind) }

    /** [remove] on the IO dispatcher. */
    suspend fun removeOffMain(context: Context, kind: AppearanceImageKind, id: String): Boolean =
        withContext(Dispatchers.IO) { remove(context, kind, id) }

    /** [updateMarkPlacement] on the IO dispatcher: the sidecar's read, its rewrite aside and the atomic move. */
    suspend fun updateMarkPlacementOffMain(context: Context, id: String, placement: MimirMarkPlacement): Boolean =
        withContext(Dispatchers.IO) { updateMarkPlacement(context, id, placement) }

    /** Delete the processed copy, its sidecar and any half-written sidecar aside. True when nothing of [id] remains. */
    fun remove(context: Context, kind: AppearanceImageKind, id: String): Boolean {
        if (!isAppearanceImageId(id)) return false
        val image = imageFile(context, kind, id)
        val sidecar = sidecarFile(context, kind, id)
        // The sidecar rewrite's own aside file (updateMarkPlacement): a process that died between writing and moving it leaves one behind.
        val sidecarPart = File(sidecar.parentFile, sidecar.name + ".part")
        image.delete()
        sidecar.delete()
        sidecarPart.delete()
        File(root(context, kind), image.name + ".bak").delete()
        return !image.exists() && !sidecar.exists() && !sidecarPart.exists()
    }

    /**
     * Import a background: the bounded copy, the content hash, the capacity check, then [processBackground] into the
     * processed file. Runs on IO. The same source imported twice answers its existing entry without rewriting.
     */
    suspend fun importBackground(
        context: Context,
        uri: Uri,
        display: AppearanceDisplaySize,
    ): AppearanceImportOutcome = withContext(Dispatchers.IO) {
        import(context, uri, AppearanceImageKind.BACKGROUND) { source, target ->
            processBackground(source, target, display)
                ?.let { processed -> AppearanceProcessOutcome.Processed(processed) }
                ?: AppearanceProcessOutcome.Unusable
        }
    }

    /** Import a mark through the companion lane's processing seam ([AppearanceMarkProcessing]). Runs on IO. */
    suspend fun importMark(context: Context, uri: Uri): AppearanceImportOutcome = withContext(Dispatchers.IO) {
        import(context, uri, AppearanceImageKind.MARK) { source, target ->
            AppearanceMarkProcessing.processOutcome(source, target)
        }
    }

    private fun import(
        context: Context,
        uri: Uri,
        kind: AppearanceImageKind,
        process: (source: File, target: File) -> AppearanceProcessOutcome,
    ): AppearanceImportOutcome {
        val root = root(context, kind).apply { mkdirs() }
        val source = File(root, "incoming.part")
        try {
            source.delete()
            if (!CustomImageImportStore.copySourceBounded(context, uri, source)) return AppearanceImportOutcome.Unusable
            val id = contentHash(source)
            find(context, kind, id)?.takeIf { it.available }?.let { existing ->
                return AppearanceImportOutcome.Saved(existing, NoteAlreadySaved)
            }
            if (list(context, kind).count { it.id != id } >= kind.capacity) return AppearanceImportOutcome.Full
            val target = imageFile(context, kind, id)
            val part = File(root, target.name + ".part")
            part.delete()
            val processed = when (val made = process(source, part)) {
                is AppearanceProcessOutcome.Processed -> made.image
                AppearanceProcessOutcome.NoSubject -> {
                    part.delete()
                    return AppearanceImportOutcome.NoSubject
                }
                AppearanceProcessOutcome.Unusable -> {
                    part.delete()
                    return AppearanceImportOutcome.Unusable
                }
            }
            // The length the sidecar records, taken from the finished file before it moves: a copy that later differs from
            // it (truncated, or overwritten) reads PREVIEW UNAVAILABLE rather than SAVED.
            val bytes = part.length()
            val addedAt = System.currentTimeMillis()
            val installed = installHeroAsset(
                temp = part,
                target = target,
                validate = ::isDecodable,
                commit = { writeSidecar(context, kind, id, processed, addedAt, bytes) },
            ) == HeroInstallOutcome.INSTALLED
            if (!installed) {
                Log.w(Tag, "kind=${kind.name} id=$id install failed")
                return AppearanceImportOutcome.Unusable
            }
            val saved = AppearanceSavedImage(kind, id, target, processed.width, processed.height, processed.p90, addedAt, bytes)
            val note = listOfNotNull(NoteSaved, processed.note).joinToString(" · ")
            Log.i(Tag, "kind=${kind.name} id=$id saved ${processed.width}x${processed.height} p90=${"%.3f".format(processed.p90)}")
            return AppearanceImportOutcome.Saved(saved, note)
        } catch (_: Exception) {
            return AppearanceImportOutcome.Unusable
        } finally {
            source.delete()
        }
    }

    /**
     * Background processing: decode in software to sRGB, scaled DOWN (never up) so the centred window of the source with
     * [display]'s aspect is at most the display's size, and cropped to that window by the decoder ([appearanceBackgroundPlan]);
     * keep an animated source's first frame, measure `p90` on a 1/8 copy of the crop, write JPEG quality 90 to [target].
     */
    private fun processBackground(source: File, target: File, display: AppearanceDisplaySize): AppearanceProcessedImage? {
        var animated = false
        var windowLongEdge = 0
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                animated = info.isAnimated
                val plan = appearanceBackgroundPlan(info.size.width, info.size.height, display)
                windowLongEdge = max(plan.sourceWindowWidth, plan.sourceWindowHeight)
                decoder.setTargetSize(plan.decodeWidth, plan.decodeHeight)
                decoder.setCrop(Rect(plan.cropLeft, plan.cropTop, plan.cropLeft + plan.cropWidth, plan.cropTop + plan.cropHeight))
            }
        }.getOrNull() ?: return null
        try {
            if (bitmap.width < MinSourceEdgePx || bitmap.height < MinSourceEdgePx) return null
            val p90 = measureP90(bitmap)
            val written = FileOutputStream(target).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, BackgroundJpegQuality, output)
            }
            if (!written) return null
            val soft = appearanceBackgroundLooksSoft(windowLongEdge, display)
            val note = listOfNotNull(
                NoteAnimated.takeIf { animated },
                NoteSoft.takeIf { soft },
            ).joinToString(" · ").ifEmpty { null }
            return AppearanceProcessedImage(bitmap.width, bitmap.height, p90, note)
        } finally {
            bitmap.recycle()
        }
    }

    private fun measureP90(bitmap: Bitmap): Float {
        val width = (bitmap.width / 8).coerceAtLeast(1)
        val height = (bitmap.height / 8).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            return appearanceLuminanceP90(pixels)
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    private fun readSidecar(context: Context, kind: AppearanceImageKind, id: String): AppearanceSavedImage? {
        val sidecar = sidecarFile(context, kind, id)
        if (!sidecar.isFile) return null
        return runCatching {
            val json = JSONObject(sidecar.readText())
            AppearanceSavedImage(
                kind = kind,
                id = id,
                file = imageFile(context, kind, id),
                width = json.getInt("width"),
                height = json.getInt("height"),
                p90 = json.getDouble("p90").toFloat().coerceIn(0f, 1f),
                addedAtMillis = json.optLong("addedAt", 0L),
                bytes = if (json.has("bytes")) json.getLong("bytes") else null,
                placement = readPlacement(json),
            ).let { read -> read.copy(availableAtRead = read.wholeOnDisk()) }
        }.getOrNull()
    }

    /**
     * The placement a sidecar records: `scale`, `offsetX` and `offsetY`, each optional. Absent (an image saved before placement existed), not a
     * number or not finite is the field's default, and a finite value out of range is held at the range's edge ([MimirMarkPlacement.sanitized]),
     * so a hand-edited or damaged value never costs the image its place in the saved list and never reaches the geometry unchecked.
     */
    internal fun readPlacement(json: JSONObject): MimirMarkPlacement {
        fun field(key: String, default: Float): Float {
            val value = json.optDouble(key, Double.NaN)
            // Held in the double domain first, so a huge but finite value is clamped by the placement and not overflowed into infinity.
            return if (value.isFinite()) value.coerceIn(-1_000.0, 1_000.0).toFloat() else default
        }
        return MimirMarkPlacement(scale = field("scale", 1f), offsetX = field("offsetX", 0f), offsetY = field("offsetY", 0f)).sanitized()
    }

    /**
     * Record where the player put the mark [id] : its sidecar's `scale`, `offsetX` and `offsetY`, written beside the facts the
     * import recorded, which stay as they are. A placement that sanitizes to the default removes the three keys, so an image the player never
     * placed (or reset) keeps the sidecar it was imported with. The file is replaced whole, written aside and moved over, so an interrupted write
     * leaves the old sidecar and not half of one. True when the sidecar now holds [placement]; false (nothing changed) for an id that is not a
     * hash, a missing or unreadable sidecar, or a write that failed.
     */
    fun updateMarkPlacement(context: Context, id: String, placement: MimirMarkPlacement): Boolean {
        if (!isAppearanceImageId(id)) return false
        val sidecar = sidecarFile(context, AppearanceImageKind.MARK, id)
        if (!sidecar.isFile) return false
        return runCatching {
            val json = JSONObject(sidecar.readText())
            val clean = placement.sanitized()
            if (clean.isDefault) {
                json.remove("scale")
                json.remove("offsetX")
                json.remove("offsetY")
            } else {
                // Through the float's shortest text, so the sidecar reads 1.05 and not 1.0499999523162842 (it reads back as the same float).
                json.put("scale", clean.scale.toString().toDouble())
                json.put("offsetX", clean.offsetX.toString().toDouble())
                json.put("offsetY", clean.offsetY.toString().toDouble())
            }
            val part = File(sidecar.parentFile, sidecar.name + ".part")
            try {
                part.writeText(json.toString())
                try {
                    Files.move(part.toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                part.delete()
            }
            true
        }.getOrDefault(false)
    }

    private fun writeSidecar(
        context: Context,
        kind: AppearanceImageKind,
        id: String,
        processed: AppearanceProcessedImage,
        addedAt: Long,
        bytes: Long,
    ): Boolean = runCatching {
        val json = JSONObject()
            .put("width", processed.width)
            .put("height", processed.height)
            .put("p90", processed.p90.toDouble())
            .put("addedAt", addedAt)
            .put("bytes", bytes)
        sidecarFile(context, kind, id).writeText(json.toString())
        true
    }.getOrDefault(false)

    private fun isDecodable(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth >= MinSourceEdgePx && options.outHeight >= MinSourceEdgePx
    }

    /** SHA-256 of the source bytes, as 32 hex characters: enough to name a file, short enough for a preference. */
    private fun contentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}

/** What processing wrote to the `.part` file: the sidecar's facts plus a caveat for the picker's note. */
internal data class AppearanceProcessedImage(
    val width: Int,
    val height: Int,
    val p90: Float,
    val note: String? = null,
)

/**
 * How a [sourceWindowWidth] x [sourceWindowHeight] window of the source -- the centred rectangle with the display's
 * aspect -- becomes the stored copy: the whole source decoded at [decodeWidth] x [decodeHeight] (scaled down, never up),
 * and the [cropWidth] x [cropHeight] window at ([cropLeft], [cropTop]) of that, which the decoder keeps.
 */
internal data class AppearanceBackgroundPlan(
    val decodeWidth: Int,
    val decodeHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val sourceWindowWidth: Int,
    val sourceWindowHeight: Int,
)

/**
 * The decode-and-crop plan for a background: the centred window of the [width] x [height] source with [display]'s aspect
 * (`ContentScale.Crop` at import time, so nothing is letterboxed), scaled DOWN so that window is at most the display's
 * size -- never up, and never past [AppearanceImageStore.MaxLongEdgePx] on the long edge whatever the display claims --
 * so a stored copy is never larger than the screen needs. The window's edges may differ from the display's by a pixel of
 * rounding; the draw-time crop-to-fill absorbs that. A degenerate size answers a 1x1 plan so the decoder never sees zero.
 */
internal fun appearanceBackgroundPlan(width: Int, height: Int, display: AppearanceDisplaySize): AppearanceBackgroundPlan {
    if (width <= 0 || height <= 0) return AppearanceBackgroundPlan(1, 1, 0, 0, 1, 1, 1, 1)
    val bounded = appearanceBoundedDisplay(display)
    val aspect = bounded.width.toDouble() / bounded.height
    var windowWidth = width
    var windowHeight = height
    if (width.toDouble() / height > aspect) {
        windowWidth = (height * aspect).roundToInt().coerceIn(1, width)
    } else {
        windowHeight = (width / aspect).roundToInt().coerceIn(1, height)
    }
    val scale = min(1.0, min(bounded.width.toDouble() / windowWidth, bounded.height.toDouble() / windowHeight))
    val decodeWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val decodeHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val cropWidth = (windowWidth * scale).roundToInt().coerceIn(1, decodeWidth)
    val cropHeight = (windowHeight * scale).roundToInt().coerceIn(1, decodeHeight)
    return AppearanceBackgroundPlan(
        decodeWidth = decodeWidth,
        decodeHeight = decodeHeight,
        cropLeft = (decodeWidth - cropWidth) / 2,
        cropTop = (decodeHeight - cropHeight) / 2,
        cropWidth = cropWidth,
        cropHeight = cropHeight,
        sourceWindowWidth = windowWidth,
        sourceWindowHeight = windowHeight,
    )
}

/** The display a background is sized for: at least 1x1, long edge capped at [AppearanceImageStore.MaxLongEdgePx]. */
internal fun appearanceBoundedDisplay(display: AppearanceDisplaySize): AppearanceDisplaySize {
    val width = display.width.coerceAtLeast(1)
    val height = display.height.coerceAtLeast(1)
    val longEdge = max(width, height)
    if (longEdge <= AppearanceImageStore.MaxLongEdgePx) return AppearanceDisplaySize(width, height)
    val scale = AppearanceImageStore.MaxLongEdgePx.toDouble() / longEdge
    return AppearanceDisplaySize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
}

/** "It may look soft." when the stored window's long edge, in the source's pixels, is under half the display's: the stretch shows. */
internal fun appearanceBackgroundLooksSoft(sourceLongEdge: Int, display: AppearanceDisplaySize): Boolean {
    val bounded = appearanceBoundedDisplay(display)
    return sourceLongEdge > 0 && sourceLongEdge < max(bounded.width, bounded.height) * AppearanceImageStore.SoftBelowDisplayFraction
}

/**
 * The 90th-percentile WCAG relative luminance of packed ARGB pixels (the brightness of the brightest tenth): the
 * brightness of the image, measured once at import on a 1/8 copy and recorded (nothing decides on it since the readability floor was removed, 2026-10-03). Alpha is ignored (a JPEG has none).
 * Empty input answers 0.
 */
internal fun appearanceLuminanceP90(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    val luminance = FloatArray(pixels.size) { index ->
        val p = pixels[index]
        val r = SrgbToLinear[(p shr 16) and 0xFF]
        val g = SrgbToLinear[(p shr 8) and 0xFF]
        val b = SrgbToLinear[p and 0xFF]
        0.2126f * r + 0.7152f * g + 0.0722f * b
    }
    luminance.sort()
    // The first sample of the brightest tenth: with 100 pixels, sorted index 90 (0.9 * n), so exactly ten brightest
    // pixels sit at or above it. `0.9 * (n - 1)` would land one slot short and read a 10%-white image as black.
    val index = (0.9 * luminance.size).toInt().coerceIn(0, luminance.size - 1)
    return luminance[index].coerceIn(0f, 1f)
}

/** WCAG 2.1 channel linearisation, tabulated once for the 256 sRGB codes. */
private val SrgbToLinear: FloatArray = FloatArray(256) { code ->
    val c = code / 255.0
    (if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
}

/**
 * The MAIN screen in pixels, in the orientation Mimir runs in -- the viewport a background is cropped and sized for
 * (the second screen keeps its soft blur of the same copy): the window's maximum metrics when
 * [context] is a visual one (the activity), else the default display's mode, else 1920x1080, so a background is never
 * sized for a display the process cannot see. A panel that is portrait-native reports its mode that way; the metrics
 * come first because they are already turned.
 */
internal fun appearanceMainDisplayPx(context: Context): AppearanceDisplaySize {
    val fromWindow = runCatching {
        context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            ?.let { AppearanceDisplaySize(it.width(), it.height()) }
    }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    if (fromWindow != null) return fromWindow
    val fromDisplay = runCatching {
        context.applicationContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
            ?.let { AppearanceDisplaySize(it.physicalWidth, it.physicalHeight) }
    }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    return fromDisplay ?: AppearanceDisplaySize(1_920, 1_080)
}
