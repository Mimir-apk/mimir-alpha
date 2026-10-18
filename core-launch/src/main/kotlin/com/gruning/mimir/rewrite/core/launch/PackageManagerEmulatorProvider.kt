package com.gruning.mimir.rewrite.core.launch

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * The real [InstalledEmulatorProvider]: detects which seed emulators are installed on THIS device via
 * [PackageManager], and hands the readiness engine live candidates for a platform.
 *
 * Detection is per-package against [EmulatorSeedRegistry]. Package visibility on Android 11+ (API 30,
 * our minSdk) is scoped — the app declares exactly the seed packages in a `<queries>` block (NO
 * `QUERY_ALL_PACKAGES`, posture law). Without that declaration `getPackageInfo` throws
 * `NameNotFoundException` for a package that IS installed but not visible, which we honestly treat as
 * "not installed / not reachable" (a greyed "Needs install" candidate), never a crash.
 *
 * Cheap by design: a `getPackageInfo` presence check is a fast binder call, and the candidate list per
 * platform is tiny (callers should resolve readiness off the UI hot path).
 */
class PackageManagerEmulatorProvider(
    private val packageManager: PackageManager,
) : InstalledEmulatorProvider {

    constructor(context: Context) : this(context.applicationContext.packageManager)

    override fun candidatesFor(platformId: PlatformId): List<EmulatorProfile> =
        EmulatorSeedRegistry.candidatesFor(platformId, ::isPackageInstalled).map { candidate ->
            candidate.withComponentPreflight(
                platformId = platformId,
                activityAvailable = ::hasExportedEnabledActivity,
                packageLauncherAvailable = ::hasPackageLauncher,
            )
        }

    /**
     * True when [packageName] is installed AND visible to this app. A `NameNotFoundException` (not
     * installed, or not declared in `<queries>`) is the honest "no" — the candidate shows as
     * "Needs install", it is never auto-selected, and nothing crashes.
     */
    fun isPackageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun hasExportedEnabledActivity(packageName: String, activityName: String): Boolean = try {
        val info = packageManager.getActivityInfo(
            ComponentName(packageName, activityName),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        info.exported && info.enabled && info.applicationInfo.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Runtime-backed shell fallback for a known installed emulator whose ROM payload contract is not
     * proven. A real launcher activity permits "Open only"; it never upgrades the package to direct
     * game handoff.
     */
    private fun hasPackageLauncher(packageName: String): Boolean =
        packageManager.getLaunchIntentForPackage(packageName) != null ||
            packageManager.getLeanbackLaunchIntentForPackage(packageName) != null
}

/**
 * Preflight an installed launch route without erasing package-recognition truth. A known package whose
 * activity drifted remains `installed=true`, but becomes non-selectable through `componentAvailable=false`.
 */
internal fun EmulatorProfile.withComponentPreflight(
    platformId: PlatformId,
    packageLauncherAvailable: (packageName: String) -> Boolean = { false },
    activityAvailable: (packageName: String, activityName: String) -> Boolean,
): EmulatorProfile {
    if (!installed) return copy(componentAvailable = null)
    if (capability == EmulatorCapability.RECOGNIZE_ONLY) {
        val launcherAvailable = packageLauncherAvailable(packageName)
        return if (launcherAvailable) {
            copy(
                capability = EmulatorCapability.OPEN_ONLY,
                componentAvailable = true,
                locatorForm = LaunchLocatorForm.NONE,
                openShellInferred = true,
            )
        } else {
            copy(componentAvailable = false)
        }
    }
    val activity = ShippingLaunchProfiles.targetActivityFor(platformId, this)
        ?: return copy(componentAvailable = false)
    if (activityAvailable(packageName, activity)) return copy(componentAvailable = true)
    // A build that renamed its activity (Flycast v2.2's `com.reicast.emulator.MainActivity`) is still the same
    // emulator taking the same intent: launch it through the class it has.
    val alternate = ShippingLaunchProfiles.alternateActivitiesFor(platformId, this)
        .firstOrNull { activityAvailable(packageName, it) }
        ?: return copy(componentAvailable = false)
    return copy(componentAvailable = true, installedActivityName = alternate)
}
