package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TomeScopedArtwork(
    val uri: String?,
    val isPhysicalMedia: Boolean,
)

/** Pure presentation resolver shared by closed Tome previews and the open Folio. */
internal fun resolveTomeScopedArtwork(
    standardArtworkUri: String?,
    selectedCartridgeUri: String?,
    providerPhysicalMediaUri: String? = null,
): TomeScopedArtwork {
    // C4, this read `visibility.enabled && selected != null`, so the Cartridge
    // Hero switch VETOED a face he had picked by hand whenever it was off ; a picked face must replace the current state
    // regardless of the Cartridge Hero switch. The switch is gone as of 2026-09-22; the tier order it was
    // corrected into is what remains, and it is the one this function still runs.
    val selected = selectedCartridgeUri?.takeIf(String::isNotBlank)
    if (selected != null) return TomeScopedArtwork(uri = selected, isPhysicalMedia = true)
    val provider = providerPhysicalMediaUri?.takeIf(String::isNotBlank)
    val standard = standardArtworkUri?.takeIf(String::isNotBlank)
    return if (standard != null) {
        TomeScopedArtwork(uri = standard, isPhysicalMedia = false)
    } else {
        TomeScopedArtwork(uri = provider, isPhysicalMedia = provider != null)
    }
}

/**
 * Deterministic renderer for one explicitly selected physical cartridge/support image.
 *
 * The file is intentionally separate from SteamGridDB's tile icon and the resolver's provider support
 * cache, and provider work remains confined to the explicit Customize Media search/apply transaction.
 *
 * **"Tome-only … cannot alter Home or Library" was true until 2026-08-22, and the correction it was
 * given on 2026-08-25 went stale in its turn.** That version still said the store *"cannot alter a Home
 * tile, a Library gamelist tile or a Library list row"*. **It can, and does.** The shelf's face (Home's
 * and the Library's, one resolver since 2026-10-10: `resolveShelfFaces`) is chosen from the stores, this one
 * included, and the launch card's through `resolveWiredFace` — see that function's own KDoc, which records the
 * C4 gap that made it consult `CustomCoverStore` too. The Library gamelist list view reaches it through
 * `libraryListPreviewMedia`.
 *
 * So the honest statement is the short one: **this store holds his explicitly picked cartridge images,
 * and every surface that paints a face can read it.** Tome tiles and wells, the Home tile, the Library
 * list row and carousel card, and [launchCardWiredFace] for the launch card. What has never changed
 * is the store's ISOLATION: separate from SteamGridDB's tile icon and the resolver's provider cache, and
 * provider work stays inside the explicit Customize Media transaction.
 *
 * Corrected 2026-08-26. Twice now this sentence has been narrowed to a surface list that a later ruling
 * outgrew; it is stated as a property this time rather than as an enumeration.
 */
internal object TomeCartridgeArtStore {
    // DELIBERATELY THE OLD NAME (R3-M, policy 2026-08-25). THE SERIOUS ONE. This is filesDir/tome-cartridge-art/,
    // holding every per-game cartridge image he has ever picked -- each one the product of a manual
    // provider search. activeFile() composes the path from this constant, so renaming it does not
    // move a single file: it makes all of them unreachable, in one edit, with no error and no
    // warning. A tidy-up rename here is data loss wearing the clothes of a cleanup.
    private const val DirectoryName = "tome-cartridge-art"

    fun activeFile(context: Context, cacheKey: String): File = File(
        File(context.applicationContext.filesDir, DirectoryName),
        tomeCartridgeArtStorageName(cacheKey),
    )

    fun activeUri(context: Context, cacheKey: String): String? {
        when (val projection = EnrichSavedStore.rendererReadProjection(
            EnrichMediaKind.TOME_PHYSICAL,
            cacheKey,
        )) {
            EnrichRendererReadProjection.Automatic -> return null
            is EnrichRendererReadProjection.Override -> {
                return projection.file.takeIf(::isValid)
                    ?.let { file -> Uri.fromFile(file).toString() + "#v=" + file.lastModified() }
            }
            null -> Unit
        }
        if (GameIdentityMediaSelectionStore.state(
                context,
                EnrichMediaKind.TOME_PHYSICAL,
                cacheKey,
            ) == GameIdentityMediaSelection.AUTOMATIC
        ) {
            return null
        }
        return activeFile(context, cacheKey)
            .takeIf(::isValid)
            ?.let { file -> Uri.fromFile(file).toString() + "#v=" + file.lastModified() }
    }

    fun hasOverride(context: Context, cacheKey: String): Boolean = activeUri(context, cacheKey) != null

    internal fun hasCanonicalOverride(context: Context, cacheKey: String): Boolean =
        GameIdentityMediaSelectionStore.state(
            context,
            EnrichMediaKind.TOME_PHYSICAL,
            cacheKey,
        ) != GameIdentityMediaSelection.AUTOMATIC && isValid(activeFile(context, cacheKey))

    fun activate(context: Context, cacheKey: String, source: File): Boolean {
        if (!isValid(source) || source.length() > CustomHeroStore.MaxAssetBytes) return false
        val target = activeFile(context, cacheKey)
        val parent = requireNotNull(target.parentFile)
        if (!parent.exists() && !parent.mkdirs()) return false
        val staged = File(parent, target.name + ".part")
        staged.delete()
        return try {
            source.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            if (!isValid(staged) || staged.length() > CustomHeroStore.MaxAssetBytes) {
                staged.delete()
                false
            } else {
                runCatching {
                    Files.move(
                        staged.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.recoverCatching {
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.isSuccess && isValid(target)
            }
        } catch (_: Exception) {
            staged.delete()
            false
        }
    }

    fun clear(context: Context, cacheKey: String): Boolean {
        val target = activeFile(context, cacheKey)
        File(requireNotNull(target.parentFile), target.name + ".part").delete()
        return (!target.exists() || target.delete()) && !target.exists()
    }

    /** One file's identity for [decodable]: any write changes at least one of these. */
    private data class DecodeStamp(val length: Long, val modified: Long)

    /**
     * Files that have already decoded, by absolute path, stamped with the size and mtime they had when
     * they did.
     *
     * [isValid] is called during COMPOSITION, per game, on the UI thread — a folio open ran it through
     * `BitmapFactory` (bounds-only, but still a file open and a header parse) once for every game on the
     * shelf. Measured on the Thor 2026-09-04 at 1.7 ms per open, and that was *after* the caller stopped
     * asking twice.
     *
     * The stamp is what makes this safe rather than merely fast: both `stat` values are read on every
     * call regardless (the caller needs `lastModified` for the `#v=` cache-buster), so a changed file
     * misses the cache and is decoded again. Only a positive verdict is remembered — a file that failed
     * to decode is re-checked every time, because a failure can be a half-written `.part` rename that
     * will succeed a moment later, and persisting THAT would poison the key for the process's life.
     */
    private val decodable = java.util.concurrent.ConcurrentHashMap<String, DecodeStamp>()

    private fun isValid(file: File): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length <= 0L) return false
        val modified = file.lastModified()
        val cached = decodable[file.absolutePath]
        if (tomeCartridgeArtDecodeCacheHits(
                cachedLength = cached?.length,
                cachedModified = cached?.modified,
                length = length,
                modified = modified,
            )
        ) {
            return true
        }
        val stamp = DecodeStamp(length = length, modified = modified)
        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)
        if (decoded) decodable[file.absolutePath] = stamp else decodable.remove(file.absolutePath)
        return decoded
    }
}

/**
 * The on-disk filename for one saved cartridge pick: SHA-256(cacheKey), first 24 hex digits, `.png`.
 *
 * **THE DERIVATION IS FROZEN (R3-M, policy 2026-08-25).** The FUNCTION NAME may be renamed
 * freely -- that is compiler-checked. The derivation may not change by one character of input or one
 * digit of `take(24)`: [TomeCartridgeArtStore.activeFile] composes every path from it, so a "harmless"
 * tweak makes every existing file in `tome-cartridge-art/` unaddressable, with no error.
 *
 * Called out because this is the one frozen identity in the Cartridge Hero rename that is neither a
 * string literal nor a symbol -- it appears in no grep for either, and so in no rename checklist.
 */
/**
 * Whether a remembered decode verdict may be reused for the file now on disk.
 *
 * Split out as a pure function because it cannot be tested through the store: Robolectric's
 * `BitmapFactory` fabricates bounds for any bytes at all, so a unit test there cannot tell a real PNG
 * from garbage and therefore cannot see the decode this cache skips. The rule it enforces — a hit needs
 * BOTH the size and the mtime to match, and a missing entry is never a hit — is the whole safety
 * property, so it is pinned here directly instead of being implied by a test that cannot fail.
 */
internal fun tomeCartridgeArtDecodeCacheHits(
    cachedLength: Long?,
    cachedModified: Long?,
    length: Long,
    modified: Long,
): Boolean = cachedLength != null && cachedModified != null &&
    cachedLength == length && cachedModified == modified

internal fun tomeCartridgeArtStorageName(cacheKey: String): String {
    require(cacheKey.isNotBlank()) { "Tome cartridge-art cache key must not be blank" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(cacheKey.encodeToByteArray())
        .toMimirLowerHex()
    return digest.take(24) + ".png"
}
