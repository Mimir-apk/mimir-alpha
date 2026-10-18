package com.gruning.mimir.rewrite

import android.content.Context
import android.content.pm.PackageManager

/**
 * The live, evidence-grounded posture facts shown on the Diagnostics screen. Rather than hardcode a
 * label, we READ the app's actually-declared permissions from the [PackageManager] at runtime.
 *
 * Mimir Alpha is OFFLINE-FIRST: it runs fully without a network, and `INTERNET` is scoped to explicit
 * connected metadata/media actions, never scan, launch, game content, BIOS, or firmware. The historical
 * implicit DAT refresh is revoked at startup; its remaining proof hook is debug-only. M-button permissions
 * are local device controls for the floating overlay, foreground-return evidence, and tracked-task
 * cleanup. The readout stays self-auditing: any permission we did NOT expect (a dangerous perm, or one
 * that contradicts SAF-only storage) is surfaced in red. The LEGAL posture — no acquisition of content —
 * is permanent and separate.
 */
data class PostureReadout(
    val appId: String,
    val versionName: String,
    val declaredPermissions: List<String>,
    val modules: List<String>,
) {
    /** INTERNET is declared. Expected + opt-in metadata/media scoped (see class doc) — a fact, not a violation. */
    val hasInternetPermission: Boolean
        get() = declaredPermissions.any { it == PERMISSION_INTERNET }

    val hasNativeCompanionPermission: Boolean
        get() = declaredPermissions.any { it in NATIVE_COMPANION_PERMISSIONS }

    /**
     * Permissions we did NOT expect. Expected = the documented [EXPECTED] allowlist (INTERNET for the
     * connected metadata/media work + its WorkManager infrastructure) and any permission the
     * app declares on ITSELF (namespaced under our own [appId], e.g. AndroidX's
     * `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — self-scoped, benign). Anything else is surfaced for
     * scrutiny — this is what would flag a real posture regression (a dangerous, storage, or broad-
     * visibility permission that shouldn't be here in a SAF-only, metadata-only app).
     */
    val unexpectedPermissions: List<String>
        get() = declaredPermissions.filterNot { it in EXPECTED || it.startsWith(appId) }

    /** True when the posture holds: nothing declared beyond the [EXPECTED] set + our own self-scoped perms. */
    val isPostureClean: Boolean
        get() = unexpectedPermissions.isEmpty()

    /** A short, honest purpose for a declared permission — makes the readout self-documenting. */
    fun purposeOf(perm: String): String = when (perm) {
        PERMISSION_INTERNET -> "Opt-in metadata and artwork only — never scan, launch, or game content."
        PERMISSION_OVERLAY -> "Draws the Mimir button above other apps."
        PERMISSION_KILL_BACKGROUND -> "Lets Mimir clean up a tracked app after you close its tab."
        PERMISSION_USAGE_STATS -> "Notices when you return from a game. Optional, stays on device."
        PERMISSION_VIBRATE -> "Provides adjustable Mimir navigation feedback."
        PERMISSION_WRITE_SETTINGS -> "User-approved main-display brightness changes from Utility."
        PERMISSION_MODIFY_AUDIO -> "Adjusts the public Android media-volume stream from Utility, and applies the Equalizer to the audio mix while you have it on."
        "android.permission.ACCESS_NETWORK_STATE" -> "Supports user-approved connected metadata work."
        "android.permission.WAKE_LOCK" -> "Supports user-approved connected metadata work."
        "android.permission.RECEIVE_BOOT_COMPLETED" -> "Restores user-approved background work after reboot."
        "android.permission.FOREGROUND_SERVICE" ->
            "Keeps user-approved connected work and the enabled Mimir button responsive."
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" ->
            "Keeps only the user-enabled Mimir button responsive over other apps."
        else -> if (perm.startsWith(appId)) "internal (self-scoped)" else "UNEXPECTED — review"
    }

    companion object {
        const val PERMISSION_INTERNET = "android.permission.INTERNET"
        const val PERMISSION_OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
        const val PERMISSION_KILL_BACKGROUND = "android.permission.KILL_BACKGROUND_PROCESSES"
        const val PERMISSION_USAGE_STATS = "android.permission.PACKAGE_USAGE_STATS"
        const val PERMISSION_VIBRATE = "android.permission.VIBRATE"
        const val PERMISSION_WRITE_SETTINGS = "android.permission.WRITE_SETTINGS"
        const val PERMISSION_MODIFY_AUDIO = "android.permission.MODIFY_AUDIO_SETTINGS"

        val NATIVE_COMPANION_PERMISSIONS: Set<String> = setOf(
            PERMISSION_OVERLAY,
            PERMISSION_KILL_BACKGROUND,
            PERMISSION_USAGE_STATS,
        )

        /**
         * The permissions this app is EXPECTED to declare. INTERNET and the WorkManager permissions support
         * explicit connected metadata/media work; the M-button permissions are local overlay/foreground/
         * task-control aids. Utility adds app vibration, normal
         * media-volume control, and a user-approved public main-display Settings.System write. None are
         * storage/location/acquisition permissions. A permission OUTSIDE this set (and not the app's own
         * self-scoped one) flags posture red — a genuine regression signal.
         */
        val EXPECTED: Set<String> = setOf(
            PERMISSION_INTERNET,
            PERMISSION_OVERLAY,
            PERMISSION_KILL_BACKGROUND,
            PERMISSION_USAGE_STATS,
            PERMISSION_VIBRATE,
            PERMISSION_WRITE_SETTINGS,
            PERMISSION_MODIFY_AUDIO,
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        )

        /** The rewrite's registered Gradle modules — the module map, surfaced for the diagnostics readout. */
        val MODULE_LIST: List<String> = listOf(
            "app (this — Compose shell + diagnostics)",
            "core-input (input + focus authority)",
            "core-ui (shared design system — shipping Norse themes)",
            "feature-settings (strict-4 Settings + tokens)",
            "feature-library (shelf + gamelist)",
            "adaptive-display feature",
            "core-model (Eng A — typed truth)",
            "core-storage (Eng A — Room)",
            "core-resolver (Eng A — recognition)",
            "core-launch (Eng A — launch readiness)",
            "core-metadata (identity + connected-provider clients; app owns opt-in work orchestration)",
        )

        /** Build the readout from the live package info. Never throws — degrades to empty perms on error. */
        fun from(context: Context): PostureReadout {
            val pm = context.packageManager
            val pkg = context.packageName
            val declared: List<String> = try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                info.requestedPermissions?.toList().orEmpty()
            } catch (t: Throwable) {
                emptyList()
            }
            val versionName: String = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0).versionName ?: "?"
            } catch (t: Throwable) {
                "?"
            }
            return PostureReadout(
                appId = pkg,
                versionName = versionName,
                declaredPermissions = declared,
                modules = MODULE_LIST,
            )
        }
    }
}
