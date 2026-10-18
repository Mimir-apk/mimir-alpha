package com.gruning.mimir.rewrite

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReclaimSessionLedgerTest {
    @Test
    fun `missing empty malformed and nil UUIDs are rejected`() {
        assertNull(OperationId.parseOrNull(null))
        assertNull(OperationId.parseOrNull(""))
        assertNull(OperationId.parseOrNull("   "))
        assertNull(OperationId.parseOrNull("not-a-uuid"))
        assertNull(OperationId.parseOrNull("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `new root supersedes every prior root and render child`() {
        val ledger = ReclaimSessionLedger(SequenceIds())
        val owner = RendererOwnerId.create()
        val first = ledger.mintRoot(RootOperationKind.PRESENT)
        val firstRender = ledger.mintRender(first.operationId, owner, displayId = 4)!!

        val second = ledger.mintRoot(RootOperationKind.LAUNCH)

        assertNotEquals(first.operationId, second.operationId)
        assertFalse(ledger.acceptsRoot(first.operationId))
        assertFalse(
            ledger.acceptsRender(
                firstRender.operationId,
                firstRender.parentDispositionId,
                firstRender.rendererOwnerId,
            ),
        )
        assertTrue(ledger.acceptsRoot(second.operationId))
    }

    @Test
    fun `new render child supersedes only prior child of current root`() {
        val ledger = ReclaimSessionLedger(SequenceIds())
        val root = ledger.mintRoot(RootOperationKind.RECLAIM)
        val ownerA = RendererOwnerId.create()
        val ownerB = RendererOwnerId.create()
        val renderA = ledger.mintRender(root.operationId, ownerA, displayId = 4)!!
        val renderB = ledger.mintRender(root.operationId, ownerB, displayId = 4)!!

        assertTrue(ledger.acceptsRoot(root.operationId))
        assertFalse(ledger.acceptsRender(renderA.operationId, root.operationId, ownerA))
        assertTrue(ledger.acceptsRender(renderB.operationId, root.operationId, ownerB))
        assertFalse(ledger.acceptsRender(renderB.operationId, root.operationId, ownerA))
    }

    @Test
    fun `recovered root can only be adopted into an empty ledger`() {
        val recovered = OperationId.create()
        val ledger = ReclaimSessionLedger(SequenceIds())

        assertTrue(ledger.adoptRecoveredRoot(recovered) != null)
        assertNull(ledger.adoptRecoveredRoot(OperationId.create()))
        assertTrue(ledger.acceptsRoot(recovered))
    }

    @Test
    fun `retreat restores only a root this ledger minted and drops the failed root's render`() {
        val ledger = ReclaimSessionLedger(SequenceIds())
        val owner = RendererOwnerId.create()
        val host = ledger.mintRoot(RootOperationKind.LAUNCH)
        val reclaim = ledger.mintRoot(RootOperationKind.RECLAIM)
        val render = ledger.mintRender(reclaim.operationId, owner, displayId = 4)!!

        // A render id was issued here but was never a root; a foreign token was never issued at all.
        assertFalse(ledger.retreatToRoot(ReclaimSessionLedger.RootOperation(render.operationId, RootOperationKind.LAUNCH)))
        assertFalse(ledger.retreatToRoot(ReclaimSessionLedger.RootOperation(OperationId.create(), RootOperationKind.LAUNCH)))
        assertTrue(ledger.acceptsRoot(reclaim.operationId))
        assertTrue(ledger.acceptsRender(render.operationId, reclaim.operationId, owner))

        assertTrue(ledger.retreatToRoot(host))

        assertEquals(host, ledger.currentRoot)
        assertNull(ledger.currentRender)
        assertTrue(ledger.acceptsRoot(host.operationId))
        assertFalse(ledger.acceptsRoot(reclaim.operationId))
        assertFalse(ledger.acceptsRender(render.operationId, reclaim.operationId, owner))
    }

    private class SequenceIds : OperationIdSource {
        private var next = 1L

        override fun next(): OperationId =
            OperationId.parseOrNull(UUID(0L, next++).toString())!!
    }
}
