package com.gruning.mimir.rewrite.core.files

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.storage`. Upstream path:
 * `core/storage/src/main/java/com/explorer/fileexplorer/core/storage/StorageVolumeHelper.kt`.
 *
 * De-Hilted: upstream was `@Singleton class StorageVolumeHelper @Inject constructor(
 * @ApplicationContext private val context: Context)`. Mimir has no DI framework; the
 * `@Singleton`/`@Inject`/`@ApplicationContext` annotations are removed and the constructor is a
 * plain, hand-wired `StorageVolumeHelper(context: Context)`.
 *
 * [StorageVolume] was already vendored into this same package in slice 1 (from upstream
 * `core/model/src/main/java/com/explorer/fileexplorer/core/model/TransferTask.kt`, now
 * `TransferTask.kt` in this module), so upstream's
 * `import com.explorer.fileexplorer.core.model.StorageVolume` disappears here — it resolves
 * same-package instead of cross-module.
 *
 * No network, no root surface: enumerates only local/removable storage volumes
 * (internal storage and SD cards) via `StorageManager`/`Environment`/`StatFs`.
 *
 * **G8 fix (QA parity audit, 2026-08-14):** the local `sm` (`StorageManager`) was fetched here but
 * never used upstream of this change — carried unmodified from the vendored source, per the same
 * KDoc this paragraph replaces. It is now the source of a removable volume's real, live label; see
 * [StorageVolumeListing.externalVolumeName]'s own KDoc for the full "prefer Android's label, fall
 * back honestly" policy this delegates to.
 */
class StorageVolumeHelper(private val context: Context) {
    fun getStorageVolumes(): List<StorageVolume> {
        val volumes = mutableListOf<StorageVolume>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        // Primary internal storage
        val internal = Environment.getExternalStorageDirectory()
        val internalStat = StatFs(internal.absolutePath)
        volumes.add(
            StorageVolume(
                name = "Internal Storage",
                path = internal.absolutePath,
                totalBytes = internalStat.totalBytes,
                freeBytes = internalStat.freeBytes,
                isRemovable = false,
                isPrimary = true,
            )
        )

        // External volumes (SD cards, USB)
        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            if (dir == null) continue
            // MIMIR DEVIATION (structural only, 2026-08-14): the primary-storage skip and the
            // "/storage/XXXX-XXXX" mount-point derivation were inline here; they now live in
            // StorageVolumeListing.mountPointForExternalFilesDir so the one piece of SD-card detection
            // that is pure string arithmetic is reachable from a plain-JVM unit test (this class needs a
            // live Context and this module configures no Robolectric, so nothing here was testable
            // before). Same inputs, same outputs, same order -- see that function's own KDoc.
            val mountPoint = StorageVolumeListing.mountPointForExternalFilesDir(
                externalFilesDir = dir.absolutePath,
                primaryStoragePath = internal.absolutePath,
            ) ?: continue

            try {
                val stat = StatFs(mountPoint)
                // G8: ask Android for this volume's real label. Isolated in its OWN runCatching,
                // separate from this try/catch -- a labeling failure must fall back to the
                // synthesized name (StorageVolumeListing.externalVolumeName's own job), never
                // propagate to the outer catch and drop the whole volume out of the chooser. That
                // would trade a cosmetic defect for a functional one, which is a worse regression
                // than the bug this fixes.
                val platformLabel = runCatching { sm.getStorageVolume(dir)?.getDescription(context) }
                    .getOrNull()
                volumes.add(
                    StorageVolume(
                        // Falls back to upstream's `segments[2]` id-derived name -- "SD Card (<id>)"
                        // -- exactly as before whenever Android has no live label to offer.
                        name = StorageVolumeListing.externalVolumeName(mountPoint, platformLabel),
                        path = mountPoint,
                        totalBytes = stat.totalBytes,
                        freeBytes = stat.freeBytes,
                        isRemovable = true,
                        isPrimary = false,
                    )
                )
            } catch (_: Exception) { /* volume not accessible */ }
        }

        return volumes
    }

    fun getVolumeForPath(path: String): StorageVolume? {
        return getStorageVolumes().firstOrNull { path.startsWith(it.path) }
    }
}
