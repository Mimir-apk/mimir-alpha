package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PanelLeaseOwnerTest {
    @Test
    fun attachAThenBAcknowledgesAOnceBeforePublishingB() {
        val ids = ArrayDeque(listOf(leaseId(1), leaseId(2)))
        val events = mutableListOf<String>()
        val owner = PanelLeaseOwner<String> { ids.removeFirst() }

        val a = owner.attach(
            resource = "A",
            add = { events += "add:$it"; true },
            remove = { events += "remove:$it" },
            onRevoked = { id, reason -> events += "revoke:${id.value}:$reason" },
        ) as PanelAttachResult.Attached
        val b = owner.attach(
            resource = "B",
            add = { events += "add:$it"; true },
            remove = { events += "remove:$it" },
            onRevoked = { id, reason -> events += "revoke:${id.value}:$reason" },
        ) as PanelAttachResult.Attached

        assertEquals(
            listOf(
                "add:A",
                "remove:A",
                "revoke:${a.lease.id.value}:REPLACED",
                "add:B",
            ),
            events,
        )
        assertEquals(b.lease.id, owner.activeLeaseId)
    }

    @Test
    fun staleDetachAndDelayedRevocationWorkCannotRemoveReplacement() {
        val ids = ArrayDeque(listOf(leaseId(1), leaseId(2)))
        val owner = PanelLeaseOwner<String> { ids.removeFirst() }
        var delayedA: (() -> Unit)? = null
        val disposed = mutableListOf<String>()

        val a = owner.attach(
            resource = "A",
            add = { true },
            remove = {},
            onRevoked = { id, reason -> delayedA = { disposed += "A:${id.value}:$reason" } },
        ) as PanelAttachResult.Attached
        val b = owner.attach(
            resource = "B",
            add = { true },
            remove = {},
            onRevoked = { _, _ -> },
        ) as PanelAttachResult.Attached

        assertFalse(a.lease.detach())
        delayedA?.invoke()

        assertEquals(b.lease.id, owner.activeLeaseId)
        assertEquals(listOf("A:${a.lease.id.value}:REPLACED"), disposed)
    }

    @Test
    fun replacementAddFailureLeavesNoActiveLeaseOrOldFields() {
        val ids = ArrayDeque(listOf(leaseId(1), leaseId(2)))
        val owner = PanelLeaseOwner<String> { ids.removeFirst() }
        var aRevocations = 0

        owner.attach("A", add = { true }, remove = {}, onRevoked = { _, _ -> aRevocations++ })
        val result = owner.attach("B", add = { false }, remove = {}, onRevoked = { _, _ -> })

        assertEquals(PanelAttachResult.Rejected(PanelRejectReason.ADD_VIEW_FAILED), result)
        assertEquals(1, aRevocations)
        assertNull(owner.activeLeaseId)
    }

    @Test
    fun displayAndPermissionLossRevokeEachCurrentLeaseExactlyOnce() {
        val ids = ArrayDeque(listOf(leaseId(1), leaseId(2)))
        val owner = PanelLeaseOwner<String> { ids.removeFirst() }
        val reasons = mutableListOf<PanelRevocationReason>()

        owner.attach("A", add = { true }, remove = {}, onRevoked = { _, reason -> reasons += reason })
        assertTrue(owner.revokeCurrent(PanelRevocationReason.DISPLAY_LOST))
        assertFalse(owner.revokeCurrent(PanelRevocationReason.DISPLAY_LOST))
        owner.attach("B", add = { true }, remove = {}, onRevoked = { _, reason -> reasons += reason })
        assertTrue(owner.revokeCurrent(PanelRevocationReason.PERMISSION_LOST))
        assertFalse(owner.revokeCurrent(PanelRevocationReason.PERMISSION_LOST))

        assertEquals(
            listOf(PanelRevocationReason.DISPLAY_LOST, PanelRevocationReason.PERMISSION_LOST),
            reasons,
        )
    }

    private fun leaseId(value: Long) = PanelLeaseId(UUID(0L, value))
}
