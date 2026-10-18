package com.gruning.mimir.rewrite.core.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.storage`. Upstream path:
 * `core/storage/src/main/java/com/explorer/fileexplorer/core/storage/PermissionHelper.kt`.
 *
 * De-Hilted: upstream was `@Singleton class PermissionHelper @Inject constructor(
 * @ApplicationContext private val context: Context)`. Mimir has no DI framework; the
 * `@Singleton`/`@Inject`/`@ApplicationContext` annotations are removed and the constructor is a
 * plain, hand-wired `PermissionHelper(context: Context)`.
 *
 * minSdk 30 here vs upstream's 26: `Build.VERSION_CODES.R` is 30, so `SDK_INT >= R` is always
 * true on this floor. The pre-R branches in `hasFullStorageAccess()`, `getManageStorageIntent()`,
 * and `getRequiredPermissions()` were therefore dead code and were deleted rather than carried,
 * per the minSdk floor. `getRequiredPermissions()`'s and `hasNotificationPermission()`'s
 * `Build.VERSION_CODES.TIRAMISU` (33) branches stay — 33 is above Mimir's floor of 30, so that
 * check is still live.
 *
 * `androidx.core.content.ContextCompat.checkSelfPermission(context, permission)` was rewritten to
 * the plain `Context.checkSelfPermission(permission)` member call: that method has existed
 * directly on `Context` since API 23, so the AndroidX compat wrapper is redundant above Mimir's
 * API-30 floor and this avoids adding a new module dependency (`androidx.core:core-ktx`) for a
 * single call. `core-files` still declares zero dependencies after this file.
 *
 * No network surface.
 */
class PermissionHelper(private val context: Context) {
    /** Full storage access granted (MANAGE_EXTERNAL_STORAGE) */
    fun hasFullStorageAccess(): Boolean = Environment.isExternalStorageManager()

    /** Intent to open system settings for MANAGE_EXTERNAL_STORAGE */
    fun getManageStorageIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /** Required runtime permissions for current API level */
    fun getRequiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            else -> arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Returns a path that remains readable and writable when all-files access is denied.
     * This is deliberately app-scoped; it never implies access to shared storage.
     */
    fun scopedStorageRootPath(): String =
        context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
}
