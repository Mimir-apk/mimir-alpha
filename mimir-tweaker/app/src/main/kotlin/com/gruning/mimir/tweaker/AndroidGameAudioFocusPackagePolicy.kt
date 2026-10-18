package com.gruning.mimir.tweaker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Installed and not a system app, from this daemon's own [PackageManager].
 *
 * Package visibility is what makes "not installed" mean something here: the manifest's `<queries>` entry
 * (every launcher app) is what lets this daemon see the games and emulators Mimir launches, with no
 * QUERY_ALL_PACKAGES. A package the daemon cannot see is refused as NOT_INSTALLED, which is the fail-closed
 * direction: the game keeps normal focus behaviour.
 */
internal class AndroidGameAudioFocusPackagePolicy(
    context: Context,
) : GameAudioFocusPackagePolicy {
    private val packageManager: PackageManager = context.applicationContext.packageManager

    override fun admission(packageName: String): GameAudioFocusAdmission {
        val info = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return GameAudioFocusAdmission.Refused(
                TweakerGameAudioFocusContract.REFUSAL_NOT_INSTALLED,
                "package is not installed or not visible",
            )
        } catch (_: RuntimeException) {
            return GameAudioFocusAdmission.Refused(
                TweakerGameAudioFocusContract.REFUSAL_NOT_INSTALLED,
                "package could not be looked up",
            )
        }
        // FLAG_SYSTEM is also set on an updated system app, which is the intent: those are not games.
        return if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
            GameAudioFocusAdmission.Refused(TweakerGameAudioFocusContract.REFUSAL_SYSTEM_APP, "a system app is never held")
        } else {
            GameAudioFocusAdmission.Admitted
        }
    }

    override fun isInstalled(packageName: String): Boolean? = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        null
    }
}
