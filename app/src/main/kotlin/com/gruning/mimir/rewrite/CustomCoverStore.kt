package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The player's explicitly chosen COVER for one game.
 *
 * Every menu with media lanes offers a Cover lane. Previously the cover was AUTOMATIC-ONLY,
 * with no explicit choice. In the probed provider corpus, **every title also publishes `box-2D`**,
 * while the automatic ladder is
 * `box-2D → box-3D → box-texture`. A 3D box is therefore unreachable automatically across that corpus.
 * A picker is the only route to one.
 *
 * ## Why this is a private override and NOT a write into the ROM's own cover slot
 *
 * Writing the pick to `<base>.png` beside the ROM would have been nearly free: every surface already reads
 * that file through `LocalArtSource`, so nothing downstream would need to change. **It would also have
 * destroyed the player's picks.** `ArtworkFetchController` computes
 *
 * ```
 * replaceCover = existingCover != null && unattributed
 * ```
 *
 * where `unattributed` means the game has no `ArtResolutionStore` record. A hand-picked cover written into
 * that slot is exactly an unattributed cover, so **the next enrichment pass would overwrite it** — silently,
 * with no error, and with no way to get the choice back.
 *
 * Keeping the pick in app-private storage removes that path *by construction* rather than by a flag the
 * automatic side has to remember to honour. It is also what every other explicitly-chosen medium in this
 * product already does — icons, heroes, clips and cartridge art all live outside the ROM folder for the
 * same reason. This is the fourth instance of one pattern, not a new one.
 *
 * ## How it reaches the screen
 *
 * `LibraryRepositoryImpl` takes a [com.gruning.mimir.rewrite.core.model.library.CoverOverrideSource] and
 * prefers it when projecting `GameRow.artUri`, so one seam covers every surface that paints a cover —
 * gamelist carousel, list preview, launch card, Home tiles, Tome wells — instead of each learning about
 * this store. The seam mirrors `cartridgeArtUriForGame`: `:app` owns the store, the module that needs the
 * answer takes a lambda.
 */
internal object CustomCoverStore {
    /**
     * Its own directory, so a cover pick and a cartridge pick for the same game cannot collide.
     *
     * NEW in this commit, so unlike `TomeCartridgeArtStore`'s directory there is no installed base to
     * protect — but it becomes a persisted identity the moment the first pick lands, and from then on it is
     * exactly as unrenameable. Treat it as frozen from now.
     */
    private const val DirectoryName = "custom-cover-art"

    /**
     * Deliberately the SAME derivation the cartridge store uses.
     *
     * Safe because the two live in different directories, so identical names cannot collide, and it avoids
     * minting a second frozen filename scheme to go wrong independently. The derivation itself is frozen —
     * see [tomeCartridgeArtStorageName]'s KDoc; changing it by one character makes every file here
     * unaddressable with no error.
     */
    private fun storageName(cacheKey: String): String = tomeCartridgeArtStorageName(cacheKey)

    fun activeFile(context: Context, cacheKey: String): File = File(
        File(context.applicationContext.filesDir, DirectoryName),
        storageName(cacheKey),
    )

    /**
     * The chosen cover as a renderable URI, or null when this game is on automatic.
     *
     * The `#v=` fragment is the file's mtime: the decoder ignores a fragment, but it changes the image
     * cache key, so re-picking repaints in place instead of showing the previous choice.
     */
    fun activeUri(context: Context, cacheKey: String): String? {
        when (val projection = EnrichSavedStore.rendererReadProjection(EnrichMediaKind.COVER, cacheKey)) {
            EnrichRendererReadProjection.Automatic -> return null
            is EnrichRendererReadProjection.Override ->
                return projection.file.takeIf(::isValid)?.let(::versionedUri)
            null -> Unit
        }
        if (GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.COVER, cacheKey) ==
            GameIdentityMediaSelection.AUTOMATIC
        ) {
            return null
        }
        return activeFile(context, cacheKey).takeIf(::isValid)?.let(::versionedUri)
    }

    private fun versionedUri(file: File): String =
        Uri.fromFile(file).toString() + "#v=" + file.lastModified()

    fun hasOverride(context: Context, cacheKey: String): Boolean = activeUri(context, cacheKey) != null

    internal fun hasCanonicalOverride(context: Context, cacheKey: String): Boolean =
        GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.COVER, cacheKey) !=
            GameIdentityMediaSelection.AUTOMATIC && isValid(activeFile(context, cacheKey))

    /** Stage-then-atomic-move, so a failed copy can never leave a half-written cover as the active one. */
    fun activate(context: Context, cacheKey: String, source: File): Boolean {
        if (!isValid(source) || source.length() > CustomHeroStore.MaxAssetBytes) return false
        val target = activeFile(context, cacheKey)
        val parent = requireNotNull(target.parentFile)
        if (!parent.exists() && !parent.mkdirs()) return false
        val staged = File(parent, target.name + ".part")
        staged.delete()
        return try {
            source.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
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

    /** Drop the pick and fall back to whatever enrichment resolved. Never touches the ROM's own art. */
    fun clear(context: Context, cacheKey: String): Boolean {
        val target = activeFile(context, cacheKey)
        File(requireNotNull(target.parentFile), target.name + ".part").delete()
        return (!target.exists() || target.delete()) && !target.exists()
    }

    private fun isValid(file: File): Boolean = file.isFile && file.length() > 0L
}
