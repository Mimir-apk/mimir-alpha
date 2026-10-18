package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMountReconcilerTest {
    @Test
    fun permissionIsCheckedBeforeMountedFastPathAndRegainRemountsTarget() {
        var permission = true
        var windows = OverlayWindowSnapshot()
        val events = mutableListOf<String>()
        val reconciler = OverlayMountReconciler(
            hasPermission = { events += "permission"; permission },
            windowSnapshot = { windows.also { events += "snapshot" } },
            mount = { id ->
                events += "mount:${id.displayId}"
                windows = fullyMounted(id)
                true
            },
            unmount = { reason ->
                events += "unmount:$reason"
                windows = OverlayWindowSnapshot()
            },
            onPermissionMissingChanged = { events += "missing:$it" },
        )

        reconciler.target(4)
        events.clear()
        permission = false
        reconciler.reconcile()

        assertEquals(
            listOf("permission", "missing:true", "unmount:PERMISSION_LOST"),
            events,
        )
        assertNull(reconciler.mountedDisplayId)

        events.clear()
        permission = true
        reconciler.reconcile()

        assertEquals(
            listOf("permission", "snapshot", "mount:4", "snapshot", "missing:false"),
            events,
        )
        assertEquals(4, reconciler.mountedDisplayId)
    }

    @Test
    fun permissionRegainCommitsBaseBeforeReentrantPanelAttach() {
        var permission = true
        var baseWindows = OverlayWindowSnapshot()
        var attachPanelOnGrant = false
        val panelOwner = PanelLeaseOwner<String>()
        val revocations = mutableListOf<PanelRevocationReason>()
        lateinit var recoveredLease: CompanionPanelLease
        val reconciler = OverlayMountReconciler(
            hasPermission = { permission },
            windowSnapshot = {
                baseWindows.copy(panelAttached = panelOwner.activeLeaseId != null)
            },
            mount = { displayId ->
                baseWindows = fullyMounted(displayId)
                true
            },
            unmount = { reason ->
                baseWindows = OverlayWindowSnapshot()
                panelOwner.revokeCurrent(reason)
            },
            onPermissionMissingChanged = { missing ->
                if (!missing && attachPanelOnGrant) {
                    recoveredLease = panelOwner.attach(
                        resource = "recovered",
                        add = { true },
                        remove = {},
                        onRevoked = { _, reason -> revocations += reason },
                    ).attachedLease()
                }
            },
        )

        reconciler.target(4)
        val staleLease = panelOwner.attach(
            resource = "before-permission-loss",
            add = { true },
            remove = {},
            onRevoked = { _, reason -> revocations += reason },
        ).attachedLease()

        permission = false
        reconciler.reconcile()
        attachPanelOnGrant = true
        permission = true
        reconciler.reconcile()

        assertEquals(4, reconciler.mountedDisplayId)
        assertTrue(baseWindows.hasRequiredWindowsOn(4))
        assertEquals(recoveredLease.id, panelOwner.activeLeaseId)
        assertEquals(listOf(PanelRevocationReason.PERMISSION_LOST), revocations)
        assertFalse(staleLease.detach())
        assertEquals(recoveredLease.id, panelOwner.activeLeaseId)
        assertTrue(recoveredLease.detach())
        assertNull(panelOwner.activeLeaseId)
    }

    @Test
    fun repeatedReconcileUsesFastPathOnlyAfterPermissionCheck() {
        val events = mutableListOf<String>()
        var windows = OverlayWindowSnapshot()
        val reconciler = OverlayMountReconciler(
            hasPermission = { events += "permission"; true },
            windowSnapshot = { windows.also { events += "snapshot" } },
            mount = { id ->
                events += "mount:${id.displayId}"
                windows = fullyMounted(id)
                true
            },
            unmount = { reason ->
                events += "unmount:$reason"
                windows = OverlayWindowSnapshot()
            },
            onPermissionMissingChanged = { events += "missing:$it" },
        )
        reconciler.target(4)
        events.clear()

        reconciler.reconcile()

        assertEquals(listOf("permission", "snapshot", "missing:false"), events)
    }

    @Test
    fun forcedRemountTearsDownAndRebuildsAnAttachedTarget() {
        val events = mutableListOf<String>()
        var windows = OverlayWindowSnapshot()
        val reconciler = OverlayMountReconciler(
            hasPermission = { true },
            windowSnapshot = { windows },
            mount = { target ->
                events += "mount:${target.displayId}"
                windows = fullyMounted(target)
                true
            },
            unmount = { reason ->
                events += "unmount:$reason"
                windows = OverlayWindowSnapshot()
            },
            onPermissionMissingChanged = {},
        )
        reconciler.target(4)
        events.clear()

        reconciler.forceRemount()

        assertEquals(listOf("unmount:RETARGETED", "mount:4"), events)
        assertEquals(4, reconciler.mountedDisplayId)
    }

    @Test
    fun retargetAndDisplayLossCarryDistinctRevocationReasons() {
        val reasons = mutableListOf<PanelRevocationReason>()
        var windows = OverlayWindowSnapshot()
        val reconciler = OverlayMountReconciler(
            hasPermission = { true },
            windowSnapshot = { windows },
            mount = { id -> windows = fullyMounted(id); true },
            unmount = { reason -> reasons += reason; windows = OverlayWindowSnapshot() },
            onPermissionMissingChanged = {},
        )

        reconciler.target(4)
        reconciler.target(7)
        reconciler.displayRemoved(7)

        assertEquals(
            listOf(PanelRevocationReason.RETARGETED, PanelRevocationReason.DISPLAY_LOST),
            reasons,
        )
        assertNull(reconciler.targetDisplayId)
    }

    @Test
    fun partialMOrEdgeAddIsRolledBackBeforeGrantIsPublished() {
        listOf(
            OverlayWindowSnapshot(mButtonDisplayId = 4),
            OverlayWindowSnapshot(edgeDisplayId = 4),
        ).forEach { partialMount ->
            var windows = OverlayWindowSnapshot()
            val reasons = mutableListOf<PanelRevocationReason>()
            val permissionSignals = mutableListOf<Boolean>()
            val reconciler = OverlayMountReconciler(
                hasPermission = { true },
                windowSnapshot = { windows },
                mount = {
                    windows = partialMount
                    true
                },
                unmount = { reason ->
                    reasons += reason
                    windows = OverlayWindowSnapshot()
                },
                onPermissionMissingChanged = { permissionSignals += it },
            )

            reconciler.target(4)

            assertNull(reconciler.mountedDisplayId)
            assertEquals(OverlayWindowSnapshot(), windows)
            assertEquals(listOf(PanelRevocationReason.RETARGETED), reasons)
            assertTrue(permissionSignals.isEmpty())
        }
    }

    @Test
    fun panelOnlyWindowIsRevokedAfterFailedBaseMountForEveryOwnerLoss() {
        panelOnlyFixture().run {
            permission = false
            reconciler.reconcile()
            assertPanelRevoked(PanelRevocationReason.PERMISSION_LOST)
        }
        panelOnlyFixture().run {
            reconciler.target(7)
            assertPanelRevoked(PanelRevocationReason.RETARGETED)
        }
        panelOnlyFixture().run {
            reconciler.displayRemoved(4)
            assertPanelRevoked(PanelRevocationReason.DISPLAY_LOST)
        }
    }

    @Test
    fun primaryToSecondaryRetargetUnmountsBeforeMountAndNeverOverlapsRoles() {
        val events = mutableListOf<String>()
        var windows = OverlayWindowSnapshot()
        val reconciler = OverlayMountReconciler(
            hasPermission = { true },
            windowSnapshot = { windows },
            mount = { target ->
                events += "mount:${target.role}:${target.displayId}"
                windows = if (target.role == CompanionOverlayRole.PRIMARY_SINGLE) {
                    OverlayWindowSnapshot(mButtonDisplayId = target.displayId)
                } else {
                    fullyMounted(target)
                }
                true
            },
            unmount = { reason ->
                events += "unmount:$reason"
                windows = OverlayWindowSnapshot()
            },
            onPermissionMissingChanged = {},
        )
        val primary = CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, 0)
        val secondary = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, 7)

        reconciler.target(primary)
        reconciler.target(secondary)

        assertEquals(
            listOf(
                "mount:PRIMARY_SINGLE:0",
                "unmount:RETARGETED",
                "mount:SECONDARY_COMPANION:7",
            ),
            events,
        )
        assertEquals(secondary, reconciler.mountedOverlay)
        assertTrue(windows.hasRequiredWindowsOn(secondary))
    }

    @Test
    fun noTargetDoesNotClaimOverlayPermissionIsMissing() {
        val missingSignals = mutableListOf<Boolean>()
        OverlayMountReconciler(
            hasPermission = { false },
            windowSnapshot = { OverlayWindowSnapshot() },
            mount = { false },
            unmount = {},
            onPermissionMissingChanged = { missingSignals += it },
        ).reconcile()

        assertEquals(listOf(false), missingSignals)
    }

    private fun panelOnlyFixture(): PanelOnlyFixture = PanelOnlyFixture().also { fixture ->
        fixture.reconciler.target(4)
        assertNull(fixture.reconciler.mountedDisplayId)
        fixture.attachPanel()
    }

    private class PanelOnlyFixture {
        var permission = true
        private var baseWindows = OverlayWindowSnapshot()
        private val panelOwner = PanelLeaseOwner<String>()
        private val unmountReasons = mutableListOf<PanelRevocationReason>()
        private val panelRevocations = mutableListOf<PanelRevocationReason>()
        private var panelRemovals = 0

        val reconciler = OverlayMountReconciler(
            hasPermission = { permission },
            windowSnapshot = {
                baseWindows.copy(panelAttached = panelOwner.activeLeaseId != null)
            },
            mount = { false },
            unmount = { reason ->
                unmountReasons += reason
                baseWindows = OverlayWindowSnapshot()
                panelOwner.revokeCurrent(reason)
            },
            onPermissionMissingChanged = {},
        )

        fun attachPanel() {
            panelOwner.attach(
                resource = "panel",
                add = { true },
                remove = { panelRemovals += 1 },
                onRevoked = { _, reason -> panelRevocations += reason },
            )
        }

        fun assertPanelRevoked(reason: PanelRevocationReason) {
            assertEquals(listOf(reason), unmountReasons)
            assertEquals(listOf(reason), panelRevocations)
            assertEquals(1, panelRemovals)
            assertNull(panelOwner.activeLeaseId)
        }
    }

    private companion object {
        fun PanelAttachResult.attachedLease(): CompanionPanelLease =
            (this as PanelAttachResult.Attached).lease

        fun fullyMounted(displayId: Int) = OverlayWindowSnapshot(
            mButtonDisplayId = displayId,
            edgeDisplayId = displayId,
        )

        fun fullyMounted(target: CompanionOverlayTarget) = fullyMounted(target.displayId)
    }
}
