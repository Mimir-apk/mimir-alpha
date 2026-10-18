package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.gruning.mimir.rewrite.core.model.GameId
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-owned, normalized still-image imports retained independently from the active provider pick. */
internal object CustomImageImportStore {
    private const val DirectoryName = "user_art"
    private const val MaxSourceBytes = 32L * 1024L * 1024L
    private const val MinSourceEdgePx = 32
    private const val IconMaxEdgePx = 1_024

    /**
     *  Applies to CUSTOM PLATFORM ICONS ONLY.
     *
     * Scoped deliberately. This import path is shared by platform tiles, Home game tiles and Tome physical
     * art, so lowering [IconMaxEdgePx] itself would silently halve unrelated game art.
     * [SteamGridDbTileFetcher.isPlatformKey] is what separates them.
     *
     * Idempotent by construction: the edge is `min(width, height, cap)`, so a 512 source stays 512 and only
     * something larger is brought down. Re-applying already-correct art is a no-op with no re-encode churn.
     */
    private const val PlatformIconMaxEdgePx = 512

    /** The apply-time edge cap for an icon, by what the cache key addresses. */
    internal fun iconMaxEdgePx(cacheKey: String?): Int =
        if (cacheKey != null && SteamGridDbTileFetcher.isPlatformKey(cacheKey)) {
            PlatformIconMaxEdgePx
        } else {
            IconMaxEdgePx
        }
    private const val HeroMaxWidthPx = 1_920
    private const val HeroMaxHeightPx = 1_080
    private const val HeroJpegQuality = 92

    fun retainedIconFile(context: Context, cacheKey: String): File =
        File(File(root(context), "icon"), encoded(cacheKey) + ".png")

    fun retainedHeroFile(context: Context, gameId: GameId): File =
        File(File(root(context), "hero"), encoded(gameId.value) + ".jpg")

    fun retainedIconPath(context: Context, cacheKey: String): String? =
        retainedIconFile(context, cacheKey).takeIf(::validFile)?.absolutePath

    fun retainedHeroPath(context: Context, gameId: GameId): String? =
        retainedHeroFile(context, gameId).takeIf(::validFile)?.absolutePath

    /** Normalize into retained user storage without changing the active renderer. */
    suspend fun retainIcon(context: Context, uri: Uri, cacheKey: String): Boolean = withContext(Dispatchers.IO) {
        GameIdentityMediaMutationGate.run(context, blocked = false) {
            val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
            normalize(
                context,
                uri,
                retainedIconFile(context, currentCacheKey),
                CustomImageSlot.ICON,
                iconMaxEdgePx(currentCacheKey),
            )
        }
    }

    /** Normalize into retained user storage without changing the active renderer. */
    suspend fun retainHero(context: Context, uri: Uri, gameId: GameId): Boolean = withContext(Dispatchers.IO) {
        GameIdentityMediaMutationGate.run(context, blocked = false) {
            val currentGameId = GameIdentityAliasResolver.resolve(context, gameId)
            normalize(context, uri, retainedHeroFile(context, currentGameId), CustomImageSlot.HERO, IconMaxEdgePx)
        }
    }

    /** Normalize an upload into caller-owned scratch storage; no retained or active art is mutated. */
    suspend fun stageIcon(
        context: Context,
        uri: Uri,
        target: File,
        cacheKey: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        // cacheKey is what decides the platform-icon cap. It is nullable only because a staged upload is
        // scratch art; a caller that knows which slot it is staging for should pass it, or the preview will
        // be normalized to a different size than the applied art.
        normalize(context, uri, target, CustomImageSlot.ICON, iconMaxEdgePx(cacheKey))
    }

    /** Normalize an upload into caller-owned scratch storage; no retained or active art is mutated. */
    suspend fun stageHero(context: Context, uri: Uri, target: File): Boolean = withContext(Dispatchers.IO) {
        normalize(context, uri, target, CustomImageSlot.HERO, IconMaxEdgePx)
    }

    /**
     * Normalize a COVER upload into caller-owned scratch storage, keeping the whole picture and its own shape (reported 10-09: a 2:1 upload
     * was cut to a square at its sides, here, before any tile or preview drew it). Only the longest edge is capped; nothing is cropped or upscaled.
     */
    suspend fun stageCover(context: Context, uri: Uri, target: File): Boolean = withContext(Dispatchers.IO) {
        normalize(context, uri, target, CustomImageSlot.COVER, IconMaxEdgePx)
    }

    suspend fun importIcon(context: Context, uri: Uri, cacheKey: String): Boolean = withContext(Dispatchers.IO) {
        retainIcon(context, uri, cacheKey) && activateIcon(context, cacheKey)
    }

    suspend fun importHero(context: Context, uri: Uri, gameId: GameId): Boolean = withContext(Dispatchers.IO) {
        retainHero(context, uri, gameId) && activateHero(context, gameId)
    }

    suspend fun activateIcon(context: Context, cacheKey: String): Boolean = withContext(Dispatchers.IO) {
        GameIdentityMediaMutationGate.run(context, blocked = false) {
            val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
            val retained = retainedIconFile(context, currentCacheKey).takeIf(::validFile)
                ?: return@run false
            val target = SteamGridDbTileFetcher.canonicalTileIconFile(context, currentCacheKey)
            val temp = File(target.parentFile, target.name + ".upload.part")
            target.parentFile?.mkdirs()
            if (!copyFile(retained, temp, CustomHeroStore.MaxAssetBytes)) return@run false
            val priorSelection = GameIdentityMediaSelectionStore.record(
                context,
                EnrichMediaKind.ICON,
                currentCacheKey,
            )
            if (!GameIdentityMediaSelectionStore.set(
                    context,
                    EnrichMediaKind.ICON,
                    currentCacheKey,
                    GameIdentityMediaSelection.SELECTED,
                )
            ) {
                temp.delete()
                return@run false
            }
            val installed = installHeroAsset(
                temp = temp,
                target = target,
                validate = ::isDecodableStill,
                commit = {
                    runCatching {
                        File(target.parentFile, target.name + ".idx").writeText("upload")
                        true
                    }.getOrDefault(false)
                },
            ) == HeroInstallOutcome.INSTALLED
            if (!installed) {
                GameIdentityMediaSelectionStore.write(
                    context,
                    EnrichMediaKind.ICON,
                    currentCacheKey,
                    priorSelection,
                )
            }
            installed
        }
    }

    suspend fun activateHero(context: Context, gameId: GameId): Boolean = withContext(Dispatchers.IO) {
        GameIdentityMediaMutationGate.run(context, blocked = false) {
            val currentGameId = GameIdentityAliasResolver.resolve(context, gameId)
            val retained = retainedHeroFile(context, currentGameId).takeIf(::validFile)
                ?: return@run false
            val target = CustomHeroStore.assetFile(context, currentGameId, video = false)
            val temp = File(target.parentFile, target.name + ".upload.part")
            target.parentFile?.mkdirs()
            if (!copyFile(retained, temp, CustomHeroStore.MaxAssetBytes)) return@run false
            val priorSelection = GameIdentityMediaSelectionStore.record(
                context,
                EnrichMediaKind.HERO,
                currentGameId.value,
            )
            if (!GameIdentityMediaSelectionStore.set(
                    context,
                    EnrichMediaKind.HERO,
                    currentGameId.value,
                    GameIdentityMediaSelection.SELECTED,
                )
            ) {
                temp.delete()
                return@run false
            }
            val installed = installHeroAsset(
                temp = temp,
                target = target,
                validate = ::isDecodableStill,
                commit = { CustomHeroStore.commitHero(context, currentGameId, uploaded = true) },
            ) == HeroInstallOutcome.INSTALLED
            if (!installed) {
                GameIdentityMediaSelectionStore.write(
                    context,
                    EnrichMediaKind.HERO,
                    currentGameId.value,
                    priorSelection,
                )
            }
            installed
        }
    }

    /**
     * The bounded source copy alone -- [MaxSourceBytes] cap, `target` deleted on any failure -- for an importer with
     * its own processing (`AppearanceImageStore`, concept A 2026-09-30), so every image import in the app shares one
     * byte ceiling and one reading of the picked `Uri`.
     */
    fun copySourceBounded(context: Context, uri: Uri, target: File): Boolean {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return false
        return copyBounded(input, target, MaxSourceBytes)
    }

    private fun normalize(
        context: Context,
        uri: Uri,
        target: File,
        slot: CustomImageSlot,
        maxEdgePx: Int,
    ): Boolean {
        val root = root(context).apply { mkdirs() }
        val source = File(root, "incoming.part")
        val normalized = File(target.parentFile, target.name + ".part")
        return try {
            source.delete()
            normalized.delete()
            val input = context.contentResolver.openInputStream(uri) ?: return false
            if (!copyBounded(input, source, MaxSourceBytes)) return false
            val bitmap = decodeNormalizedBitmap(source, slot, maxEdgePx) ?: return false
            try {
                target.parentFile?.mkdirs()
                val compressed = FileOutputStream(normalized).use { output ->
                    when (slot) {
                        CustomImageSlot.ICON, CustomImageSlot.COVER -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        CustomImageSlot.HERO -> bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            HeroJpegQuality,
                            output,
                        )
                    }
                }
                if (!compressed || !validFile(normalized) || normalized.length() > CustomHeroStore.MaxAssetBytes) {
                    normalized.delete()
                    return false
                }
                installHeroAsset(
                    temp = normalized,
                    target = target,
                    validate = ::isDecodableStill,
                    commit = { true },
                ) == HeroInstallOutcome.INSTALLED
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            false
        } finally {
            source.delete()
            normalized.delete()
        }
    }

    private fun decodeNormalizedBitmap(source: File, slot: CustomImageSlot, maxEdgePx: Int): Bitmap? {
        val decoded = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val size = customImageDecodeSize(info.size.width, info.size.height, slot)
                decoder.setTargetSize(size.width, size.height)
            }
        }.getOrNull() ?: return null
        if (decoded.width < MinSourceEdgePx || decoded.height < MinSourceEdgePx) {
            decoded.recycle()
            return null
        }
        return when (slot) {
            CustomImageSlot.ICON -> {
                val edge = minOf(decoded.width, decoded.height, maxEdgePx)
                val left = (decoded.width - minOf(decoded.width, decoded.height)) / 2
                val top = (decoded.height - minOf(decoded.width, decoded.height)) / 2
                val crop = Bitmap.createBitmap(
                    decoded,
                    left,
                    top,
                    minOf(decoded.width, decoded.height),
                    minOf(decoded.width, decoded.height),
                )
                val result = if (crop.width == edge) crop else Bitmap.createScaledBitmap(crop, edge, edge, true)
                if (crop !== result && crop !== decoded) crop.recycle()
                if (decoded !== result && decoded !== crop) decoded.recycle()
                result
            }
            // A cover keeps its shape: the picture is only brought down to the longest-edge cap, never cropped.
            CustomImageSlot.COVER -> {
                val size = customImageCoverSize(decoded.width, decoded.height, maxEdgePx)
                if (size.width == decoded.width && size.height == decoded.height) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, size.width, size.height, true).also { if (it !== decoded) decoded.recycle() }
                }
            }
            CustomImageSlot.HERO -> decoded
        }
    }

    private fun root(context: Context): File = File(context.applicationContext.filesDir, DirectoryName)

    private fun encoded(value: String): String =
        value.encodeToByteArray().joinToString("") { "%02x".format(it) }

    private fun validFile(file: File): Boolean = file.isFile && file.length() > 0L

    private fun isDecodableStill(file: File): Boolean {
        if (!validFile(file)) return false
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth >= MinSourceEdgePx && options.outHeight >= MinSourceEdgePx
    }

    private fun copyBounded(input: java.io.InputStream, target: File, maxBytes: Long): Boolean = try {
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        target.delete()
                        return false
                    }
                    output.write(buffer, 0, read)
                }
                total > 0L
            }
        }
    } catch (_: Exception) {
        target.delete()
        false
    }

    private fun copyFile(source: File, target: File, maxBytes: Long): Boolean =
        runCatching { copyBounded(source.inputStream(), target, maxBytes) }.getOrDefault(false)
}

/** ICON is square-cropped art, HERO a 16:9-class still, COVER a picture that keeps its own shape. */
internal enum class CustomImageSlot { ICON, HERO, COVER }

internal data class CustomImageSize(val width: Int, val height: Int)

internal fun customImageDecodeSize(width: Int, height: Int, slot: CustomImageSlot): CustomImageSize {
    if (width <= 0 || height <= 0) return CustomImageSize(1, 1)
    val maxWidth = if (slot != CustomImageSlot.HERO) 2_048 else 1_920
    val maxHeight = if (slot != CustomImageSlot.HERO) 2_048 else 1_080
    val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    return CustomImageSize(
        width = (width * scale).toInt().coerceAtLeast(1),
        height = (height * scale).toInt().coerceAtLeast(1),
    )
}

/**
 * A cover's stored size: its own shape with the longest edge brought down to [maxEdgePx] when it is longer, never cropped and never upscaled, so a
 * 2:1 picture stays 2:1 and a square stays a square. The two edges scale together, and neither goes below one pixel.
 */
internal fun customImageCoverSize(width: Int, height: Int, maxEdgePx: Int): CustomImageSize {
    if (width <= 0 || height <= 0) return CustomImageSize(1, 1)
    val longest = maxOf(width, height)
    if (maxEdgePx <= 0 || longest <= maxEdgePx) return CustomImageSize(width, height)
    val scale = maxEdgePx.toDouble() / longest
    return CustomImageSize(
        width = (width * scale).toInt().coerceAtLeast(1),
        height = (height * scale).toInt().coerceAtLeast(1),
    )
}
