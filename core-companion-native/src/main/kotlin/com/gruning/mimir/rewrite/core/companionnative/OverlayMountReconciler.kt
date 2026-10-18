package com.gruning.mimir.rewrite.core.companionnative

internal data class OverlayWindowSnapshot(
    val mButtonDisplayId: Int? = null,
    val edgeDisplayId: Int? = null,
    val panelAttached: Boolean = false,
) {
    val hasAnyWindow: Boolean
        get() = mButtonDisplayId != null || edgeDisplayId != null || panelAttached

    fun hasRequiredWindowsOn(target: CompanionOverlayTarget): Boolean =
        mButtonDisplayId == target.displayId &&
            (target.role == CompanionOverlayRole.PRIMARY_SINGLE || edgeDisplayId == target.displayId)

    /** Test/source-compatible secondary-role shorthand. */
    fun hasRequiredWindowsOn(displayId: Int): Boolean =
        hasRequiredWindowsOn(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, displayId))
}

/**
 * Pure permission/display reconciliation. Permission is deliberately sampled before the mounted fast path,
 * and every teardown decision observes all three native window kinds rather than trusting display bookkeeping.
 * A grant is published only after the target's required base windows are committed; that callback may
 * synchronously attach a panel, so no reconciliation work may run after it.
 */
internal class OverlayMountReconciler(
    private val hasPermission: () -> Boolean,
    private val windowSnapshot: () -> OverlayWindowSnapshot,
    private val mount: (CompanionOverlayTarget) -> Boolean,
    private val unmount: (PanelRevocationReason) -> Unit,
    private val onPermissionMissingChanged: (Boolean) -> Unit,
) {
    var targetOverlay: CompanionOverlayTarget? = null
        private set

    var mountedOverlay: CompanionOverlayTarget? = null
        private set

    val targetDisplayId: Int?
        get() = targetOverlay?.displayId

    val mountedDisplayId: Int?
        get() = mountedOverlay?.displayId

    private var lastPermissionGranted: Boolean? = null

    fun target(target: CompanionOverlayTarget) {
        val previous = targetOverlay
        if (previous != null && previous != target && hasMountedWindows()) {
            unmount(PanelRevocationReason.RETARGETED)
            mountedOverlay = null
        }
        targetOverlay = target
        reconcile()
    }

    /** Test/source-compatible secondary-role shorthand. */
    fun target(displayId: Int) {
        target(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, displayId))
    }

    fun reconcile() {
        val target = targetOverlay
        if (target == null) {
            onPermissionMissingChanged(false)
            if (hasMountedWindows()) unmount(PanelRevocationReason.RETARGETED)
            mountedOverlay = null
            return
        }

        val granted = hasPermission()
        val permissionChanged = granted != lastPermissionGranted
        lastPermissionGranted = granted

        if (!granted) {
            onPermissionMissingChanged(true)
            if (permissionChanged || hasMountedWindows()) {
                unmount(PanelRevocationReason.PERMISSION_LOST)
            }
            mountedOverlay = null
            return
        }

        val beforeMount = windowSnapshot()
        if (mountedOverlay == target && beforeMount.hasRequiredWindowsOn(target)) {
            onPermissionMissingChanged(false)
            return
        }

        if (mountedOverlay != null || beforeMount.hasAnyWindow) {
            unmount(PanelRevocationReason.RETARGETED)
            mountedOverlay = null
        }

        val reportedMounted = mount(target)
        val afterMount = windowSnapshot()
        if (reportedMounted && afterMount.hasRequiredWindowsOn(target)) {
            mountedOverlay = target
            onPermissionMissingChanged(false)
        } else {
            // A callback that leaves only M or edge behind is a failed transaction, even if it reported success.
            if (afterMount.hasAnyWindow) unmount(PanelRevocationReason.RETARGETED)
            mountedOverlay = null
        }
    }

    fun displayRemoved(displayId: Int) {
        if (targetOverlay?.displayId != displayId) return
        targetOverlay = null
        if (hasMountedWindows()) unmount(PanelRevocationReason.DISPLAY_LOST)
        mountedOverlay = null
    }

    fun clearTarget(reason: PanelRevocationReason = PanelRevocationReason.DISPLAY_LOST) {
        targetOverlay = null
        if (hasMountedWindows()) unmount(reason)
        mountedOverlay = null
    }

    /** Force a clean window transaction when an attached WindowManager layout can no longer be trusted. */
    fun forceRemount(reason: PanelRevocationReason = PanelRevocationReason.RETARGETED) {
        if (hasMountedWindows()) unmount(reason)
        mountedOverlay = null
        reconcile()
    }

    private fun hasMountedWindows(): Boolean =
        mountedOverlay != null || windowSnapshot().hasAnyWindow
}
