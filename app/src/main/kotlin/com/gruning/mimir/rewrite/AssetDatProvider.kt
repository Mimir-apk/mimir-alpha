package com.gruning.mimir.rewrite

import android.content.Context
import android.os.ParcelFileDescriptor
import com.gruning.mimir.rewrite.core.metadata.DatAssetProvider
import com.gruning.mimir.rewrite.core.metadata.PackedDatAssetProvider
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Android asset seam for offline game-identity catalogues. A valid file in `filesDir/dat/` takes precedence
 * over its bundled `assets/dat/` peer, allowing an explicitly refreshed catalogue to survive app updates.
 *
 * [openRaw] preserves the stream contract used by compressed `.idcatgz` catalogues. [openPacked] maps an
 * immutable `.midx` file read-only, avoiding a second in-memory copy of the packed hash index. Bundled packed
 * assets must remain uncompressed in the APK so [android.content.res.AssetManager.openFd] can expose their
 * offset and length; `app/build.gradle.kts` enforces that packaging rule.
 */
class AssetDatProvider(context: Context) : DatAssetProvider, PackedDatAssetProvider {
    private val app = context.applicationContext
    private val assets = app.assets

    override fun openRaw(assetName: String): InputStream? {
        val override = resolveOverride(datDir(app), assetName)
        if (override != null && override.isFile && override.length() > 0) {
            runCatching { return override.inputStream() }
        }
        val assetPath = bundledAssetPath(assetName) ?: return null
        return runCatching { assets.open(assetPath) }.getOrNull()
    }

    override fun openPacked(assetName: String): ByteBuffer? {
        val override = resolveOverride(datDir(app), assetName)
        mapReadOnly(override)?.let { return it }

        val assetPath = bundledAssetPath(assetName) ?: return null
        return runCatching {
            assets.openFd(assetPath).use { descriptor ->
                val length = descriptor.length
                if (length <= 0L) return@use null
                // Map through a duplicate: closing the channel must not close the descriptor still owned by
                // AssetFileDescriptor.use (double-closing a shared raw fd is device/runtime dependent).
                val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                ParcelFileDescriptor.AutoCloseInputStream(duplicate).channel.use { channel ->
                    channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        length,
                    ).asReadOnlyBuffer()
                }
            }
        }.getOrNull()
    }

    companion object {
        /** The writable dir for downloaded DAT overrides (`filesDir/dat/`). */
        fun datDir(context: Context): File =
            File(context.applicationContext.filesDir, "dat").apply { mkdirs() }

        /** Resolve only a single filename inside [datDirectory]; manifest input must never escape the root. */
        internal fun resolveOverride(datDirectory: File, assetName: String): File? {
            if (!isAssetLeaf(assetName)) return null
            return runCatching {
                val root = datDirectory.canonicalFile
                File(root, assetName).canonicalFile.takeIf { it.parentFile == root }
            }.getOrNull()
        }

        /** Map a non-empty filesystem override without granting write access to the packed-index reader. */
        internal fun mapReadOnly(file: File?): ByteBuffer? {
            if (file == null || !file.isFile || file.length() <= 0L) return null
            return runCatching {
                FileInputStream(file).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size()).asReadOnlyBuffer()
                }
            }.getOrNull()
        }

        internal fun bundledAssetPath(assetName: String): String? =
            assetName.takeIf(::isAssetLeaf)?.let { "dat/$it" }

        private fun isAssetLeaf(assetName: String): Boolean =
            assetName.isNotBlank() &&
                assetName != "." &&
                assetName != ".." &&
                '/' !in assetName &&
                '\\' !in assetName &&
                '\u0000' !in assetName
    }
}
