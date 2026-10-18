package com.gruning.mimir.rewrite

/** The kind is retained for structured logs and for render-settle side effects. */
internal enum class RootOperationKind {
    PRESENT,
    LAUNCH,
    RECLAIM,
    RECOVERY,
}

/**
 * Process-lifetime token ledger for disposition roots and their renderer-owned child attempts.
 *
 * A new root invalidates every older root and render child. A new render child replaces only the
 * current root's previous render attempt. UUID construction lives in [OperationId], so there is no
 * legacy empty-token path for callers to accidentally accept.
 */
internal class ReclaimSessionLedger(
    private val idSource: OperationIdSource,
) {
    private val issued = linkedSetOf<OperationId>()
    private val roots = linkedSetOf<OperationId>()
    private var root: RootOperation? = null
    private var render: RenderAttempt? = null

    data class RootOperation(
        val operationId: OperationId,
        val kind: RootOperationKind,
    )

    val currentRoot: RootOperation?
        get() = root

    val currentRender: RenderAttempt?
        get() = render

    fun mintRoot(kind: RootOperationKind): RootOperation {
        val operation = RootOperation(nextUniqueId(), kind)
        root = operation
        roots += operation.operationId
        render = null
        return operation
    }

    fun adoptRecoveredRoot(operationId: OperationId): RootOperation? {
        if (root != null || !issued.add(operationId)) return null
        return RootOperation(operationId, RootOperationKind.RECOVERY).also {
            root = it
            roots += operationId
        }
    }

    /**
     * Hands the root back to [operation], an earlier root this ledger minted, after the current root
     * failed before it settled (the D2 reclaim retreat). Only a root ever issued here can come back —
     * never a render id, never a foreign token — and the failed root plus any render it minted stop
     * being accepted the moment it does.
     */
    fun retreatToRoot(operation: RootOperation): Boolean {
        if (operation.operationId !in roots) return false
        root = operation
        render = null
        return true
    }

    fun mintRender(
        parentDispositionId: OperationId,
        rendererOwnerId: RendererOwnerId,
        displayId: Int,
    ): RenderAttempt? {
        if (!acceptsRoot(parentDispositionId)) return null
        return RenderAttempt(
            operationId = nextUniqueId(),
            parentDispositionId = parentDispositionId,
            rendererOwnerId = rendererOwnerId,
            displayId = displayId,
        ).also { render = it }
    }

    fun acceptsRoot(operationId: OperationId): Boolean =
        root?.operationId == operationId

    fun acceptsRender(
        operationId: OperationId,
        parentDispositionId: OperationId,
        rendererOwnerId: RendererOwnerId,
    ): Boolean {
        val expected = render ?: return false
        return acceptsRoot(parentDispositionId) &&
            expected.operationId == operationId &&
            expected.parentDispositionId == parentDispositionId &&
            expected.rendererOwnerId == rendererOwnerId
    }

    fun invalidateRender(rendererOwnerId: RendererOwnerId? = null) {
        if (rendererOwnerId == null || render?.rendererOwnerId == rendererOwnerId) render = null
    }

    fun invalidateAll() {
        root = null
        render = null
    }

    fun kindOf(operationId: OperationId): RootOperationKind? =
        root?.takeIf { it.operationId == operationId }?.kind

    private fun nextUniqueId(): OperationId {
        repeat(MaxIdSourceAttempts) {
            val candidate = idSource.next()
            if (issued.add(candidate)) return candidate
        }
        error("OperationIdSource repeated UUIDs $MaxIdSourceAttempts times")
    }

    private companion object {
        const val MaxIdSourceAttempts = 32
    }
}
