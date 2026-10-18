package com.gruning.mimir.rewrite.core.companionnative

import java.util.UUID

/**
 * Pure ownership core for one native panel window. Android supplies add/remove operations; this class owns
 * ordering, UUID authority, and exactly-once forced revocation.
 */
internal class PanelLeaseOwner<T>(
    private val mutationAllowed: () -> Boolean = { true },
    private val idFactory: () -> PanelLeaseId = { PanelLeaseId(UUID.randomUUID()) },
) {
    private data class Entry<T>(
        val id: PanelLeaseId,
        val resource: T,
        val remove: (T) -> Unit,
        val onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    )

    private var active: Entry<T>? = null
    private var attaching = false

    val activeLeaseId: PanelLeaseId?
        get() = active?.id

    fun attach(
        resource: T,
        add: (T) -> Boolean,
        remove: (T) -> Unit,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult {
        if (attaching) return PanelAttachResult.Rejected(PanelRejectReason.ADD_VIEW_FAILED)
        attaching = true
        return try {
            revokeCurrent(PanelRevocationReason.REPLACED)

            val id = idFactory()
            if (!add(resource)) return PanelAttachResult.Rejected(PanelRejectReason.ADD_VIEW_FAILED)

            active = Entry(id, resource, remove, onRevoked)
            PanelAttachResult.Attached(
                object : CompanionPanelLease {
                    override val id: PanelLeaseId = id

                    override fun detach(): Boolean = this@PanelLeaseOwner.detach(id)
                },
            )
        } finally {
            attaching = false
        }
    }

    fun detach(id: PanelLeaseId): Boolean {
        if (!mutationAllowed()) return false
        val entry = active?.takeIf { it.id == id } ?: return false
        active = null
        runCatching { entry.remove(entry.resource) }
        return true
    }

    fun revokeCurrent(reason: PanelRevocationReason): Boolean {
        val entry = active ?: return false
        active = null
        runCatching { entry.remove(entry.resource) }
        runCatching { entry.onRevoked(entry.id, reason) }
        return true
    }
}
