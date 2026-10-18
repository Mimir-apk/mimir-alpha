package com.gruning.mimir.rewrite

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.launch.LaunchStore
import com.gruning.mimir.rewrite.core.launch.LaunchTargetReapResult
import com.gruning.mimir.rewrite.core.launch.RetroArchCoreCheck
import com.gruning.mimir.rewrite.core.launch.performLaunchTargetReap

/**
 * THE single injection site for the app's [LaunchController] — the launch analogue of
 * [LibraryInjection]. Returns the Launch Engineer's real, Room + PackageManager-backed controller
 * ([LaunchStore.controller]): resolve readiness (installed emulator + Open-With override + BIOS +
 * locator) → dispatch the launch intent → record a durable proof.
 *
 * Wired into the [LibraryViewModel] factory so A/CONFIRM on a game actually opens it in its emulator.
 * Process-cached (wraps the singleton DB); NO network (posture manifest).
 */
object LaunchInjection {
    fun installTargetReaper(gateway: PrivilegedUtilityGateway) {
        installRetroArchCoreCheck(gateway)
        LaunchStore.launchTargetReaper = { packageName ->
            when (gateway.forceStopLaunchTarget(packageName)) {
                PrivilegedLaunchTargetStop.REQUEST_ACCEPTED -> LaunchTargetReapResult.REQUEST_ACCEPTED
                PrivilegedLaunchTargetStop.NOT_ALLOWED -> LaunchTargetReapResult.NOT_ALLOWED
                PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE ->
                    LaunchTargetReapResult.AUTHORITY_UNAVAILABLE
                PrivilegedLaunchTargetStop.FAILED -> LaunchTargetReapResult.FAILED
            }
        }
    }

    /**
     * The pre-launch RetroArch core check rides the same privileged authority as the reaper above, so it is
     * installed from the same call: one fixed read-only listing, and Unknown (launch as before) wherever it cannot run.
     */
    private fun installRetroArchCoreCheck(gateway: PrivilegedUtilityGateway) {
        RetroArchCoreCheck.lister = gateway::listRetroArchCores
        RetroArchCoreCheck.logger = { Log.i("MimirRetroArchCores", it) }
    }

    /**
     * Exact-target cleanup after the resumed-host watchdog expires. The session remains RECOVERING
     * (therefore launch-locked) while this runs off-main. Recognition-only/refused packages never
     * receive the background fallback.
     */
    fun recoverLaunchTarget(context: Context, packageName: String): LaunchTargetReapResult {
        return performLaunchTargetReap(
            packageName = packageName,
            privileged = { target -> LaunchStore.launchTargetReaper(target) },
            bestEffort = { target ->
                val manager = context.applicationContext
                    .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.killBackgroundProcesses(target)
            },
        )
    }

    fun controller(context: Context): LaunchController = LaunchStore.controller(context)
}
