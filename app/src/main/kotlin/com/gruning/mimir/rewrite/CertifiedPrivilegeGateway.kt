package com.gruning.mimir.rewrite

import android.util.Log
import com.gruning.mimir.rewrite.core.launch.RetroArchCoreListing

/**
 * Single-authority broker for the narrow privileged Utility contract.
 *
 * The certified vendor PServer is the only privileged route. A device without a live admitted
 * vendor posture remains unavailable; no operation is redirected to another authority.
 */
internal class CertifiedPrivilegeGateway(
    private val vendor: PrivilegedUtilityGateway,
    private val vendorRecents: MButtonSystemRecentsGateway,
    /** The vendor authority's verified-close route; production passes the same object as [vendor]. */
    private val vendorClose: PrivilegedTaskCloseGateway = PrivilegedTaskCloseGateway.None,
    /** The vendor authority's keyboard-default route (W2f); production passes the same object as [vendor]. */
    private val vendorKeyboard: PrivilegedKeyboardDefaultGateway = PrivilegedKeyboardDefaultGateway.None,
) : PrivilegedUtilityGateway, MButtonSystemRecentsGateway, PrivilegedTaskCloseGateway,
    PrivilegedKeyboardDefaultGateway {
    override fun posture(): PrivilegedUtilityPosture = vendor.posture()

    override fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean =
        vendor.requestAccess(expectedStatus)

    override fun enforceDeviceSleep(): Boolean = vendor.enforceDeviceSleep()

    override fun readVendorSetting(key: String): String? =
        vendor.posture().canWriteVendorSettings.takeIf { it }?.let { vendor.readVendorSetting(key) }

    override fun writeVendorSetting(key: String, value: String): Boolean =
        vendor.posture().canWriteVendorSettings && vendor.writeVendorSetting(key, value)

    override fun readDisplayBrightness(displayId: Int): Float? =
        vendorOwnsSecondaryPanel().takeIf { it }?.let { vendor.readDisplayBrightness(displayId) }

    override fun setSecondaryBrightness(
        displayId: Int,
        fraction: Float,
        mirrorPercent: Int,
    ): Boolean = vendorOwnsSecondaryPanel() &&
        vendor.setSecondaryBrightness(displayId, fraction, mirrorPercent)

    private fun vendorOwnsSecondaryPanel(): Boolean {
        val posture = vendor.posture()
        return posture.status == PrivilegedUtilityStatus.READY && posture.canControlSecondaryBrightness
    }

    override fun forceStopLaunchTarget(packageName: String): PrivilegedLaunchTargetStop =
        closeLaunchTarget(packageName).asLaunchTargetStop()

    override fun closeLaunchTarget(packageName: String): PrivilegedCloseOutcome {
        val ready = vendor.posture().status == PrivilegedUtilityStatus.READY
        val result = if (ready) {
            vendorClose.closeLaunchTarget(packageName)
        } else {
            PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE
        }
        Log.i(
            "MimirPrivilege",
            "forceStop package=$packageName route=${if (ready) "CERTIFIED_VENDOR" else "none"} " +
                "result=$result alive=${result.aliveLogValue()}",
        )
        return result
    }

    override fun removeTask(taskId: Int): PrivilegedTaskRemoval {
        val ready = vendor.posture().status == PrivilegedUtilityStatus.READY
        val result = if (ready) vendorClose.removeTask(taskId)
        else PrivilegedTaskRemoval.AUTHORITY_UNAVAILABLE
        Log.i(
            "MimirPrivilege",
            "removeTask taskId=$taskId route=${if (ready) "CERTIFIED_VENDOR" else "none"} result=$result",
        )
        return result
    }

    override fun focusTask(taskId: Int): PrivilegedTaskFocus {
        val ready = vendor.posture().status == PrivilegedUtilityStatus.READY
        val result = if (ready) vendorClose.focusTask(taskId)
        else PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE
        Log.i(
            "MimirPrivilege",
            "focusTask taskId=$taskId route=${if (ready) "CERTIFIED_VENDOR" else "none"} result=$result",
        )
        return result
    }

    override fun resolveTaskId(packageName: String, displayId: Int): Int? {
        val ready = vendor.posture().status == PrivilegedUtilityStatus.READY
        val taskId = if (ready) vendorClose.resolveTaskId(packageName, displayId) else null
        Log.i(
            "MimirPrivilege",
            "resolveTask package=$packageName display=$displayId taskId=${taskId ?: "none"} " +
                "source=${if (ready) "vendor" else "none"}",
        )
        return taskId
    }

    override fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome {
        val ready = vendor.posture().status == PrivilegedUtilityStatus.READY
        val outcome = if (ready) {
            vendorClose.resolveTaskOutcome(packageName, displayId)
        } else {
            VendorTaskOutcome.Unavailable("unavailable")
        }
        Log.i(
            "MimirPrivilege",
            "resolveTaskOutcome package=$packageName display=$displayId outcome=$outcome " +
                "source=${if (ready) "vendor" else "none"}",
        )
        return outcome
    }

    override fun isHudTelemetryReady(): Boolean = vendor.isHudTelemetryReady()

    override fun readForegroundFrameRateMilli(packageName: String): Int? =
        vendor.isHudTelemetryReady().takeIf { it }?.let { vendor.readForegroundFrameRateMilli(packageName) }

    override fun readDeviceUtilization(): DeviceUtilizationSample? =
        vendor.isHudTelemetryReady().takeIf { it }?.let { vendor.readDeviceUtilization() }

    /** The vendor route gates itself on a live admitted posture and answers Unknown otherwise. */
    override fun listRetroArchCores(userId: Int, packageName: String): RetroArchCoreListing =
        vendor.listRetroArchCores(userId, packageName)

    override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus =
        vendorRecents.systemRecentsAccessStatus()

    override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean =
        vendorRecents.requestSystemRecentsAccess(expectedStatus)

    override fun requestBuiltInSystemRecentsConsent(
        expectedStatus: PrivilegedUtilityStatus,
    ): Boolean {
        val vendorStatus = vendorRecents.systemRecentsAccessStatus()
        if (vendorStatus != PrivilegedUtilityStatus.PERMISSION_REQUIRED ||
            expectedStatus != vendorStatus
        ) {
            return false
        }
        return vendorRecents.requestBuiltInSystemRecentsConsent(expectedStatus)
    }

    override fun openSystemRecents(): Boolean =
        vendorRecents.isReady() && vendorRecents.openSystemRecents()

    /**
     * The keyboard-default operations run only on a live admitted vendor posture: the PServer attestation
     * ([posture] READY), read now and never from a stored flag or a device list. Without it no command is built.
     */
    override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult =
        if (keyboardBridgeReady()) vendorKeyboard.setMimirKeyboardDefault(shouldContinue) else KeyboardDefaultResult.UNAVAILABLE

    override fun mimirKeyboardIsDefault(): Boolean? =
        if (keyboardBridgeReady()) vendorKeyboard.mimirKeyboardIsDefault() else null

    override fun resetKeyboardDefault(): KeyboardDefaultResult =
        if (keyboardBridgeReady()) vendorKeyboard.resetKeyboardDefault() else KeyboardDefaultResult.UNAVAILABLE

    override fun revertKeyboardDefaultForSafety(): KeyboardDefaultResult =
        if (keyboardBridgeReady()) vendorKeyboard.revertKeyboardDefaultForSafety() else KeyboardDefaultResult.UNAVAILABLE

    private fun keyboardBridgeReady(): Boolean = vendor.posture().status == PrivilegedUtilityStatus.READY

    override fun close() {
        vendor.close()
    }
}
