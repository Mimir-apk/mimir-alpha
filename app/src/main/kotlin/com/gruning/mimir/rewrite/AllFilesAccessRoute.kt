package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens Android's "All files access" screen for Mimir: the per-app page first, the global list when the OEM build has
 * no per-app page (a `startActivity` for a missing screen throws rather than no-ops, so the fallback is real).
 *
 * The same route as the Utility panel's and onboarding's, for the Library pane's "Allow file access" row (alpha cert
 * 2026-10-08, E3). NEW_TASK is load-bearing for a non-Activity [context], as `AppBrightnessCoordinator` records. Never
 * self-granted: Settings re-reads the grant when the player returns. True when a screen opened.
 */
internal fun openAllFilesAccessScreen(context: Context): Boolean {
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(perApp) }
        .recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        .isSuccess
}
