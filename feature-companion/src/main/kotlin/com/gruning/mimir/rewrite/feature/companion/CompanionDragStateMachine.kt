package com.gruning.mimir.rewrite.feature.companion

import kotlin.math.sqrt

internal data class DragPoint(val x: Float, val y: Float) {
    operator fun minus(other: DragPoint): DragPoint = DragPoint(x - other.x, y - other.y)

    fun distanceTo(other: DragPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

internal data class DragRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val topLeft: DragPoint get() = DragPoint(left, top)
    val center: DragPoint get() = DragPoint((left + right) / 2f, (top + bottom) / 2f)

    fun contains(point: DragPoint): Boolean =
        point.x in left..right && point.y in top..bottom
}

internal data class CompanionDragTileSnapshot(
    val id: String,
    val isBook: Boolean,
)

internal data class CompanionDragModel(
    val version: Long,
    val tiles: List<CompanionDragTileSnapshot>,
) {
    init {
        require(tiles.map { it.id }.distinct().size == tiles.size) {
            "Companion drag models must contain unique tile ids"
        }
    }

    val order: List<String> = tiles.map { it.id }

    fun tile(id: String): CompanionDragTileSnapshot? = tiles.firstOrNull { it.id == id }
}

internal data class CompanionDragGeometry(
    val generation: Long,
    val modelVersion: Long,
    val pageCount: Int,
    val settledPage: Int,
    val railBounds: DragRect,
    val slotRects: Map<Int, DragRect>,
    /**
     * Where each slot's VISIBLE face sits, in the same coordinates as [slotRects] (the surface lays it out from
     * `solveCompanionLatticeCell`'s face). The merge hotspot is the face, not the full slot: the face is top-aligned
     * over its label band, so a slot's centre is no longer on it at large font scales. A slot with no entry here
     * merges about its slot rect, as before. [slotRects] itself is unchanged: reorder, hit-testing and settle targets
     * keep the whole cell.
     */
    val faceRects: Map<Int, DragRect> = emptyMap(),
) {
    init {
        require(pageCount > 0) { "Companion drag geometry needs at least one page" }
        require(settledPage in 0 until pageCount) { "Settled page must exist" }
    }
}

internal data class CompanionMergeCandidate(
    val targetId: String,
    val token: Long,
    val enteredAtMillis: Long,
    val armed: Boolean,
)

internal data class CompanionEdgeCandidate(
    val direction: Int,
    val token: Long,
    val enteredAtMillis: Long,
)

internal data class CompanionDragTransaction(
    val id: String,
    val pointerId: Long,
    val draggedId: String,
    val originOrder: List<String>,
    val workingOrder: List<String>,
    val frozenTiles: List<CompanionDragTileSnapshot>,
    val newestModel: CompanionDragModel,
    val originPage: Int,
    val settledPage: Int,
    val sourceRect: DragRect,
    val pickupOffset: DragPoint,
    val lastPointer: DragPoint,
    val mergeCandidate: CompanionMergeCandidate? = null,
    val edgeCandidate: CompanionEdgeCandidate? = null,
    val edgeGeneration: Long = 0L,
    val turnGeneration: Long = 0L,
    val layoutGeneration: Long,
)

internal data class CompanionPendingUp(
    val pointerId: Long,
    val point: DragPoint,
    val atMillis: Long,
)

internal sealed interface CompanionDragPhase {
    data object Idle : CompanionDragPhase

    data class Pressed(
        val pointerId: Long,
        val downRoot: DragPoint,
        val currentRoot: DragPoint,
        val tileId: String?,
        val slot: Int?,
        val downAtMillis: Long,
        val pressToken: Long,
        val sourceRect: DragRect?,
        val modelVersion: Long?,
        val layoutGeneration: Long?,
        val pendingUp: CompanionPendingUp? = null,
    ) : CompanionDragPhase

    data class PagerOwned(val pointerId: Long) : CompanionDragPhase

    data class Dragging(val transaction: CompanionDragTransaction) : CompanionDragPhase

    data class Turning(
        val transaction: CompanionDragTransaction,
        val turnToken: Long,
        val targetPage: Int,
        val pendingUp: CompanionPendingUp? = null,
    ) : CompanionDragPhase

    data class RollingBack(
        val transaction: CompanionDragTransaction,
        val rollbackToken: Long,
    ) : CompanionDragPhase
}

internal data class CompanionDragMachine(
    val phase: CompanionDragPhase,
    val latestModel: CompanionDragModel,
    val geometry: CompanionDragGeometry? = null,
    val nextToken: Long = 1L,
) {
    companion object {
        fun initial(model: CompanionDragModel): CompanionDragMachine = CompanionDragMachine(
            phase = CompanionDragPhase.Idle,
            latestModel = model,
        )
    }
}

internal sealed interface CompanionDragEvent {
    data class ModelChanged(val model: CompanionDragModel) : CompanionDragEvent

    data class Down(
        val pointerId: Long,
        val point: DragPoint,
        val atMillis: Long,
        val geometry: CompanionDragGeometry,
    ) : CompanionDragEvent

    data class Move(
        val pointerId: Long,
        val point: DragPoint,
        val atMillis: Long,
        val geometry: CompanionDragGeometry,
        val pagerConsumed: Boolean = false,
        val pressedPointerCount: Int = 1,
    ) : CompanionDragEvent

    data class LongPressElapsed(
        val pointerId: Long,
        val pressToken: Long,
        val transactionId: String,
        val atMillis: Long,
        val geometry: CompanionDragGeometry,
    ) : CompanionDragEvent

    data class Up(
        val pointerId: Long,
        val point: DragPoint,
        val atMillis: Long,
        val geometry: CompanionDragGeometry,
    ) : CompanionDragEvent

    data class Cancel(val pointerId: Long? = null) : CompanionDragEvent

    data class MergeDwellElapsed(
        val transactionId: String,
        val mergeToken: Long,
        val atMillis: Long,
    ) : CompanionDragEvent

    data class EdgeDwellElapsed(
        val transactionId: String,
        val edgeToken: Long,
        val atMillis: Long,
    ) : CompanionDragEvent

    data class PageTurnStarted(
        val transactionId: String,
        val turnToken: Long,
    ) : CompanionDragEvent

    data class PageTurnFinished(
        val transactionId: String,
        val turnToken: Long,
        val targetPage: Int,
        val succeeded: Boolean,
        val atMillis: Long,
        val geometry: CompanionDragGeometry,
    ) : CompanionDragEvent

    data class RollbackFinished(
        val transactionId: String,
        val rollbackToken: Long,
    ) : CompanionDragEvent

    data class LayoutChanged(val geometry: CompanionDragGeometry) : CompanionDragEvent
}

internal sealed interface CompanionDragEffect {
    data class ActivateTile(val tileId: String) : CompanionDragEffect

    data class ArmDrag(
        val transactionId: String,
        val draggedId: String,
        val pickupOffset: DragPoint,
        val sourceRect: DragRect,
    ) : CompanionDragEffect

    data class ScheduleMerge(
        val transactionId: String,
        val mergeToken: Long,
        val delayMillis: Long,
    ) : CompanionDragEffect

    data class ScheduleEdge(
        val transactionId: String,
        val edgeToken: Long,
        val direction: Int,
        val delayMillis: Long,
    ) : CompanionDragEffect

    data class AnimatePage(
        val transactionId: String,
        val turnToken: Long,
        val targetPage: Int,
    ) : CompanionDragEffect

    data class SnapPage(val page: Int) : CompanionDragEffect

    data class RenderOrder(val order: List<String>) : CompanionDragEffect

    data class CommitOrder(val order: List<String>) : CompanionDragEffect

    data class CommitMerge(
        val order: List<String>,
        val draggedId: String,
        val targetId: String,
    ) : CompanionDragEffect

    data class Rollback(
        val transactionId: String,
        val rollbackToken: Long,
        val authoritativeOrder: List<String>,
    ) : CompanionDragEffect

    data object WakeMotion : CompanionDragEffect
}

internal data class CompanionDragReduction(
    val machine: CompanionDragMachine,
    val effects: List<CompanionDragEffect> = emptyList(),
)

internal object CompanionDragStateMachine {
    const val LongPressMillis = 180L
    const val EdgeDwellMillis = 450L
    const val MergeDwellMillis = 750L
    const val SlotsPerPage = 9

    private const val EdgeZoneFraction = 0.11f
    private const val MergeStillPixels = 6f
    private const val MergeDeepFraction = 0.30f
    private const val ReorderEnterFraction = 0.42f

    fun reduce(
        machine: CompanionDragMachine,
        event: CompanionDragEvent,
    ): CompanionDragReduction = when (event) {
        is CompanionDragEvent.ModelChanged -> modelChanged(machine, event.model)
        is CompanionDragEvent.Down -> down(machine, event)
        is CompanionDragEvent.Move -> move(machine, event)
        is CompanionDragEvent.LongPressElapsed -> longPressElapsed(machine, event)
        is CompanionDragEvent.Up -> up(machine, event)
        is CompanionDragEvent.Cancel -> cancel(machine, event)
        is CompanionDragEvent.MergeDwellElapsed -> mergeDwellElapsed(machine, event)
        is CompanionDragEvent.EdgeDwellElapsed -> edgeDwellElapsed(machine, event)
        is CompanionDragEvent.PageTurnStarted -> pageTurnStarted(machine, event)
        is CompanionDragEvent.PageTurnFinished -> pageTurnFinished(machine, event)
        is CompanionDragEvent.RollbackFinished -> rollbackFinished(machine, event)
        is CompanionDragEvent.LayoutChanged -> layoutChanged(machine, event.geometry)
    }

    fun reconcileOrder(transaction: CompanionDragTransaction): List<String> {
        val newestOrder = transaction.newestModel.order
        val newestIds = newestOrder.toHashSet()
        val survivingOriginIds = transaction.originOrder.filterTo(linkedSetOf()) { it in newestIds }
        val reorderedSurvivors = transaction.workingOrder.filter { it in survivingOriginIds }
        val replacements = reorderedSurvivors.iterator()

        return newestOrder.map { id ->
            if (id in survivingOriginIds && replacements.hasNext()) replacements.next() else id
        }
    }

    private fun modelChanged(
        machine: CompanionDragMachine,
        model: CompanionDragModel,
    ): CompanionDragReduction {
        if (model == machine.latestModel) return CompanionDragReduction(machine)

        val geometry = machine.geometry?.takeIf { it.modelVersion == model.version }
        val current = machine.phase
        if (current is CompanionDragPhase.Pressed) {
            val renderEffect = listOf(CompanionDragEffect.RenderOrder(model.order))
            val unresolved = current.withoutTarget()
            val pressed = geometry?.let { resolvePressedTarget(unresolved, model, it) } ?: unresolved
            val updated = machine.copy(
                phase = pressed,
                latestModel = model,
                geometry = geometry,
            )
            if (pressed.pendingUp != null) {
                val settled = settlePressedUp(updated, pressed, geometry)
                return settled.copy(effects = renderEffect + settled.effects)
            }
            if (geometry != null && pageGeometryComplete(model, geometry) && pressed.tileId == null) {
                return CompanionDragReduction(updated.copy(phase = CompanionDragPhase.Idle), renderEffect)
            }
            return CompanionDragReduction(updated, renderEffect)
        }

        val phase = when (current) {
            CompanionDragPhase.Idle -> current
            is CompanionDragPhase.Pressed -> current
            is CompanionDragPhase.PagerOwned -> current
            is CompanionDragPhase.Dragging -> current.copy(
                transaction = current.transaction.copy(newestModel = model),
            )
            is CompanionDragPhase.Turning -> current.copy(
                transaction = current.transaction.copy(newestModel = model),
            )
            is CompanionDragPhase.RollingBack -> current.copy(
                transaction = current.transaction.copy(newestModel = model),
            )
        }
        val effects = when (phase) {
            CompanionDragPhase.Idle -> listOf(CompanionDragEffect.RenderOrder(model.order))
            is CompanionDragPhase.RollingBack -> listOf(CompanionDragEffect.RenderOrder(model.order))
            else -> emptyList()
        }
        return CompanionDragReduction(
            machine.copy(phase = phase, latestModel = model, geometry = geometry),
            effects,
        )
    }

    private fun down(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.Down,
    ): CompanionDragReduction {
        if (machine.phase !is CompanionDragPhase.Idle) return CompanionDragReduction(machine)
        val retainedGeometry = preferGeometry(machine.geometry, event.geometry)
        if (machine.latestModel.order.isEmpty()) {
            return CompanionDragReduction(
                machine.copy(geometry = retainedGeometry),
                listOf(CompanionDragEffect.WakeMotion),
            )
        }

        val pressToken = machine.nextToken
        val unresolved = CompanionDragPhase.Pressed(
            pointerId = event.pointerId,
            downRoot = event.point,
            currentRoot = event.point,
            tileId = null,
            slot = null,
            downAtMillis = event.atMillis,
            pressToken = pressToken,
            sourceRect = null,
            modelVersion = null,
            layoutGeneration = null,
        )
        val geometry = geometryForCurrentModel(machine, event.geometry)
        val pressed = geometry?.let {
            resolvePressedTarget(unresolved, machine.latestModel, it)
        }
        if (pressed == null && geometry != null && pageGeometryComplete(machine.latestModel, geometry)) {
            return CompanionDragReduction(
                machine.copy(geometry = retainedGeometry),
                listOf(CompanionDragEffect.WakeMotion),
            )
        }

        return CompanionDragReduction(
            machine.copy(
                phase = pressed ?: unresolved,
                geometry = retainedGeometry,
                nextToken = pressToken + 1L,
            ),
            listOf(CompanionDragEffect.WakeMotion),
        )
    }

    private fun move(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.Move,
    ): CompanionDragReduction = when (val phase = machine.phase) {
        CompanionDragPhase.Idle -> CompanionDragReduction(machine)
        is CompanionDragPhase.Pressed -> {
            val retainedGeometry = preferGeometry(machine.geometry, event.geometry)
            when {
                event.pointerId != phase.pointerId || event.pressedPointerCount != 1 ->
                    CompanionDragReduction(
                        machine.copy(phase = CompanionDragPhase.Idle, geometry = retainedGeometry),
                    )
                event.pagerConsumed -> CompanionDragReduction(
                    machine.copy(
                        phase = CompanionDragPhase.PagerOwned(phase.pointerId),
                        geometry = retainedGeometry,
                    ),
                )
                else -> {
                    val moved = phase.copy(currentRoot = event.point)
                    val geometry = geometryForCurrentModel(machine, event.geometry)
                    val reconciled = geometry?.let {
                        reconcilePressedTarget(moved, machine.latestModel, it)
                    } ?: moved.withoutTarget()
                    CompanionDragReduction(
                        machine.copy(
                            phase = reconciled,
                            geometry = retainedGeometry,
                        ),
                    )
                }
            }
        }
        is CompanionDragPhase.PagerOwned -> CompanionDragReduction(
            machine.copy(geometry = preferGeometry(machine.geometry, event.geometry)),
        )
        is CompanionDragPhase.Dragging -> {
            if (event.pointerId != phase.transaction.pointerId || event.pressedPointerCount != 1) {
                rollback(machine, phase.transaction)
            } else {
                advanceDrag(machine, phase.transaction, event)
            }
        }
        is CompanionDragPhase.Turning -> {
            if (event.pointerId != phase.transaction.pointerId || event.pressedPointerCount != 1) {
                rollback(machine, phase.transaction)
            } else {
                CompanionDragReduction(
                    machine.copy(
                        phase = phase.copy(
                            transaction = phase.transaction.copy(lastPointer = event.point),
                        ),
                        geometry = event.geometry,
                    ),
                )
            }
        }
        is CompanionDragPhase.RollingBack -> CompanionDragReduction(machine)
    }

    private fun longPressElapsed(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.LongPressElapsed,
    ): CompanionDragReduction {
        val pressed = machine.phase as? CompanionDragPhase.Pressed
            ?: return CompanionDragReduction(machine)
        if (
            event.pointerId != pressed.pointerId ||
            event.pressToken != pressed.pressToken ||
            event.transactionId.isBlank() ||
            event.atMillis - pressed.downAtMillis < LongPressMillis ||
            pressed.pendingUp != null
        ) {
            return CompanionDragReduction(machine)
        }

        val retainedGeometry = preferGeometry(machine.geometry, event.geometry)
        val geometry = geometryForCurrentModel(machine, event.geometry)
            ?: return CompanionDragReduction(
                machine.copy(
                    phase = pressed.withoutTarget(),
                    geometry = retainedGeometry,
                ),
            )
        val targeted = reconcilePressedTarget(pressed, machine.latestModel, geometry)
        val tileId = targeted?.tileId
        val slot = tileId?.let(machine.latestModel.order::indexOf) ?: -1
        val sourceRect = geometry.slotRects[slot]
        if (tileId == null || slot < 0 || sourceRect == null) {
            val phase = if (pageGeometryComplete(machine.latestModel, geometry)) {
                CompanionDragPhase.Idle
            } else {
                pressed.withoutTarget()
            }
            return CompanionDragReduction(machine.copy(phase = phase, geometry = retainedGeometry))
        }
        if (!sourceRect.contains(targeted.currentRoot)) {
            return CompanionDragReduction(
                machine.copy(phase = CompanionDragPhase.Idle, geometry = retainedGeometry),
            )
        }

        val pickupOffset = targeted.currentRoot - sourceRect.topLeft
        val transaction = CompanionDragTransaction(
            id = event.transactionId,
            pointerId = targeted.pointerId,
            draggedId = tileId,
            originOrder = machine.latestModel.order,
            workingOrder = machine.latestModel.order,
            frozenTiles = machine.latestModel.tiles,
            newestModel = machine.latestModel,
            originPage = geometry.settledPage,
            settledPage = geometry.settledPage,
            sourceRect = sourceRect,
            pickupOffset = pickupOffset,
            lastPointer = targeted.currentRoot,
            layoutGeneration = geometry.generation,
        )
        return CompanionDragReduction(
            machine.copy(
                phase = CompanionDragPhase.Dragging(transaction),
                geometry = retainedGeometry,
            ),
            listOf(
                CompanionDragEffect.ArmDrag(
                    transactionId = transaction.id,
                    draggedId = transaction.draggedId,
                    pickupOffset = pickupOffset,
                    sourceRect = sourceRect,
                ),
                CompanionDragEffect.RenderOrder(transaction.workingOrder),
            ),
        )
    }

    private fun up(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.Up,
    ): CompanionDragReduction = when (val phase = machine.phase) {
        CompanionDragPhase.Idle -> CompanionDragReduction(machine)
        is CompanionDragPhase.Pressed -> pressedUp(machine, phase, event)
        is CompanionDragPhase.PagerOwned -> CompanionDragReduction(
            machine.copy(phase = CompanionDragPhase.Idle, geometry = event.geometry),
        )
        is CompanionDragPhase.Dragging -> {
            if (event.pointerId != phase.transaction.pointerId) rollback(machine, phase.transaction)
            else resolveTerminal(machine, phase.transaction, event.point, event.geometry)
        }
        is CompanionDragPhase.Turning -> {
            if (event.pointerId != phase.transaction.pointerId) {
                rollback(machine, phase.transaction)
            } else {
                CompanionDragReduction(
                    machine.copy(
                        phase = phase.copy(
                            pendingUp = CompanionPendingUp(event.pointerId, event.point, event.atMillis),
                        ),
                    ),
                )
            }
        }
        is CompanionDragPhase.RollingBack -> CompanionDragReduction(machine)
    }

    private fun cancel(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.Cancel,
    ): CompanionDragReduction = when (val phase = machine.phase) {
        CompanionDragPhase.Idle -> CompanionDragReduction(machine)
        is CompanionDragPhase.Pressed -> {
            if (event.pointerId != null && event.pointerId != phase.pointerId) CompanionDragReduction(machine)
            else CompanionDragReduction(machine.copy(phase = CompanionDragPhase.Idle))
        }
        is CompanionDragPhase.PagerOwned -> {
            if (event.pointerId != null && event.pointerId != phase.pointerId) CompanionDragReduction(machine)
            else CompanionDragReduction(machine.copy(phase = CompanionDragPhase.Idle))
        }
        is CompanionDragPhase.Dragging -> rollback(machine, phase.transaction)
        is CompanionDragPhase.Turning -> rollback(machine, phase.transaction)
        is CompanionDragPhase.RollingBack -> CompanionDragReduction(machine)
    }

    private fun mergeDwellElapsed(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.MergeDwellElapsed,
    ): CompanionDragReduction {
        val dragging = machine.phase as? CompanionDragPhase.Dragging
            ?: return CompanionDragReduction(machine)
        val transaction = dragging.transaction
        val candidate = transaction.mergeCandidate ?: return CompanionDragReduction(machine)
        if (
            transaction.id != event.transactionId ||
            candidate.token != event.mergeToken ||
            event.atMillis - candidate.enteredAtMillis < MergeDwellMillis
        ) {
            return CompanionDragReduction(machine)
        }
        return CompanionDragReduction(
            machine.copy(
                phase = dragging.copy(
                    transaction = transaction.copy(
                        mergeCandidate = candidate.copy(armed = true),
                    ),
                ),
            ),
        )
    }

    private fun edgeDwellElapsed(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.EdgeDwellElapsed,
    ): CompanionDragReduction {
        val dragging = machine.phase as? CompanionDragPhase.Dragging
            ?: return CompanionDragReduction(machine)
        val transaction = dragging.transaction
        val edge = transaction.edgeCandidate ?: return CompanionDragReduction(machine)
        if (
            transaction.id != event.transactionId ||
            edge.token != event.edgeToken ||
            event.atMillis - edge.enteredAtMillis < EdgeDwellMillis
        ) {
            return CompanionDragReduction(machine)
        }

        val targetPage = transaction.settledPage + edge.direction
        val pageCount = machine.geometry
            ?.takeIf { it.modelVersion == transaction.newestModel.version }
            ?.pageCount
            ?: transactionPageCount(transaction)
        if (targetPage !in 0 until pageCount) {
            return CompanionDragReduction(machine)
        }
        val turnToken = machine.nextToken
        val turningTransaction = transaction.copy(
            edgeCandidate = null,
            mergeCandidate = null,
            turnGeneration = turnToken,
        )
        return CompanionDragReduction(
            machine.copy(
                phase = CompanionDragPhase.Turning(
                    transaction = turningTransaction,
                    turnToken = turnToken,
                    targetPage = targetPage,
                ),
                nextToken = turnToken + 1L,
            ),
            listOf(
                CompanionDragEffect.AnimatePage(transaction.id, turnToken, targetPage),
            ),
        )
    }

    private fun pageTurnStarted(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.PageTurnStarted,
    ): CompanionDragReduction {
        val turning = machine.phase as? CompanionDragPhase.Turning
            ?: return CompanionDragReduction(machine)
        return if (
            turning.transaction.id == event.transactionId &&
            turning.turnToken == event.turnToken
        ) {
            CompanionDragReduction(machine)
        } else {
            CompanionDragReduction(machine)
        }
    }

    private fun pageTurnFinished(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.PageTurnFinished,
    ): CompanionDragReduction {
        val turning = machine.phase as? CompanionDragPhase.Turning
            ?: return CompanionDragReduction(machine)
        if (
            turning.transaction.id != event.transactionId ||
            turning.turnToken != event.turnToken ||
            turning.targetPage != event.targetPage
        ) {
            return CompanionDragReduction(machine)
        }

        val destinationSlots = event.geometry.slotRects.keys.any {
            it in pageSlotRange(event.targetPage)
        }
        if (
            !event.succeeded ||
            event.geometry.modelVersion != turning.transaction.newestModel.version ||
            event.geometry.settledPage != event.targetPage ||
            !destinationSlots
        ) {
            return rollback(machine.copy(geometry = event.geometry), turning.transaction)
        }

        val finalPoint = turning.pendingUp?.point ?: turning.transaction.lastPointer
        val settled = turning.transaction.copy(
            settledPage = event.targetPage,
            lastPointer = finalPoint,
            layoutGeneration = event.geometry.generation,
            edgeCandidate = null,
            mergeCandidate = null,
        )
        val retargeted = moveToPoint(settled, finalPoint, event.geometry, force = true)
            ?: return rollback(machine.copy(geometry = event.geometry), settled)

        if (turning.pendingUp != null) {
            return resolveTerminal(
                machine = machine.copy(
                    phase = CompanionDragPhase.Dragging(retargeted),
                    geometry = event.geometry,
                ),
                transaction = retargeted,
                point = finalPoint,
                geometry = event.geometry,
            )
        }

        val renderEffects = if (retargeted.workingOrder != settled.workingOrder) {
            listOf(CompanionDragEffect.RenderOrder(retargeted.workingOrder))
        } else {
            emptyList()
        }
        val baseMachine = machine.copy(
            phase = CompanionDragPhase.Dragging(retargeted),
            geometry = event.geometry,
        )
        val edgeDirection = edgeDirection(finalPoint, event.geometry, retargeted.settledPage)
        if (edgeDirection == 0) return CompanionDragReduction(baseMachine, renderEffects)

        val edgeToken = baseMachine.nextToken
        val withEdge = retargeted.copy(
            edgeCandidate = CompanionEdgeCandidate(
                direction = edgeDirection,
                token = edgeToken,
                enteredAtMillis = event.atMillis,
            ),
            edgeGeneration = edgeToken,
        )
        return CompanionDragReduction(
            baseMachine.copy(
                phase = CompanionDragPhase.Dragging(withEdge),
                nextToken = edgeToken + 1L,
            ),
            renderEffects + CompanionDragEffect.ScheduleEdge(
                transactionId = withEdge.id,
                edgeToken = edgeToken,
                direction = edgeDirection,
                delayMillis = EdgeDwellMillis,
            ),
        )
    }

    private fun rollbackFinished(
        machine: CompanionDragMachine,
        event: CompanionDragEvent.RollbackFinished,
    ): CompanionDragReduction {
        val rollingBack = machine.phase as? CompanionDragPhase.RollingBack
            ?: return CompanionDragReduction(machine)
        if (
            rollingBack.transaction.id != event.transactionId ||
            rollingBack.rollbackToken != event.rollbackToken
        ) {
            return CompanionDragReduction(machine)
        }
        return CompanionDragReduction(machine.copy(phase = CompanionDragPhase.Idle))
    }

    private fun layoutChanged(
        machine: CompanionDragMachine,
        geometry: CompanionDragGeometry,
    ): CompanionDragReduction {
        if (geometry.modelVersion != machine.latestModel.version) {
            return CompanionDragReduction(
                machine.copy(geometry = preferGeometry(machine.geometry, geometry)),
            )
        }
        val currentGeometry = preferGeometry(
            machine.geometry?.takeIf { it.modelVersion == machine.latestModel.version },
            geometry,
        )
        val baseMachine = machine.copy(geometry = currentGeometry)
        val phase = when (val current = machine.phase) {
            is CompanionDragPhase.Pressed -> {
                reconcilePressedTarget(current, machine.latestModel, currentGeometry)
                    ?: current.withoutTarget()
            }
            is CompanionDragPhase.Dragging -> {
                if (currentGeometry.settledPage == current.transaction.settledPage) {
                    current.copy(
                        transaction = current.transaction.copy(layoutGeneration = currentGeometry.generation),
                    )
                } else {
                    current
                }
            }
            else -> current
        }
        if (phase is CompanionDragPhase.Pressed) {
            val updated = baseMachine.copy(phase = phase)
            if (phase.pendingUp != null) return settlePressedUp(updated, phase, currentGeometry)
            if (phase.tileId == null && pageGeometryComplete(machine.latestModel, currentGeometry)) {
                return CompanionDragReduction(updated.copy(phase = CompanionDragPhase.Idle))
            }
            return CompanionDragReduction(updated)
        }
        return CompanionDragReduction(baseMachine.copy(phase = phase))
    }

    private fun pressedUp(
        machine: CompanionDragMachine,
        pressed: CompanionDragPhase.Pressed,
        event: CompanionDragEvent.Up,
    ): CompanionDragReduction {
        val retainedGeometry = preferGeometry(machine.geometry, event.geometry)
        if (
            event.pointerId != pressed.pointerId ||
            event.atMillis - pressed.downAtMillis >= LongPressMillis
        ) {
            return CompanionDragReduction(
                machine.copy(phase = CompanionDragPhase.Idle, geometry = retainedGeometry),
            )
        }

        val pending = CompanionPendingUp(event.pointerId, event.point, event.atMillis)
        val waiting = pressed.copy(pendingUp = pending)
        val updated = machine.copy(phase = waiting, geometry = retainedGeometry)
        return settlePressedUp(updated, waiting, geometryForCurrentModel(machine, event.geometry))
    }

    private fun settlePressedUp(
        machine: CompanionDragMachine,
        pressed: CompanionDragPhase.Pressed,
        geometry: CompanionDragGeometry?,
    ): CompanionDragReduction {
        val pending = pressed.pendingUp ?: return CompanionDragReduction(machine.copy(phase = pressed))
        if (geometry == null) return CompanionDragReduction(machine.copy(phase = pressed))

        val targeted = reconcilePressedTarget(pressed, machine.latestModel, geometry)
        if (targeted == null) {
            return if (pageGeometryComplete(machine.latestModel, geometry)) {
                CompanionDragReduction(machine.copy(phase = CompanionDragPhase.Idle, geometry = geometry))
            } else {
                CompanionDragReduction(machine.copy(phase = pressed.withoutTarget(), geometry = geometry))
            }
        }

        val targetId = targeted.tileId
            ?: return CompanionDragReduction(machine.copy(phase = CompanionDragPhase.Idle, geometry = geometry))
        val finalHit = hitSlot(pending.point, geometry, requireInside = true)
        if (finalHit == null && !pageGeometryComplete(machine.latestModel, geometry)) {
            return CompanionDragReduction(machine.copy(phase = targeted, geometry = geometry))
        }
        val finalId = finalHit?.let { machine.latestModel.order.getOrNull(it.slot) }
        val activate = pending.pointerId == targeted.pointerId &&
            pending.atMillis - targeted.downAtMillis < LongPressMillis &&
            finalId == targetId
        return CompanionDragReduction(
            machine.copy(phase = CompanionDragPhase.Idle, geometry = geometry),
            if (activate) listOf(CompanionDragEffect.ActivateTile(targetId)) else emptyList(),
        )
    }

    private fun CompanionDragPhase.Pressed.withoutTarget(): CompanionDragPhase.Pressed = copy(
        tileId = null,
        slot = null,
        sourceRect = null,
        modelVersion = null,
        layoutGeneration = null,
    )

    private fun resolvePressedTarget(
        pressed: CompanionDragPhase.Pressed,
        model: CompanionDragModel,
        geometry: CompanionDragGeometry,
    ): CompanionDragPhase.Pressed? {
        if (geometry.modelVersion != model.version) return null
        val hit = hitSlot(pressed.downRoot, geometry, requireInside = true) ?: return null
        val tileId = model.order.getOrNull(hit.slot) ?: return null
        return pressed.copy(
            tileId = tileId,
            slot = hit.slot,
            sourceRect = hit.rect,
            modelVersion = model.version,
            layoutGeneration = geometry.generation,
        )
    }

    private fun reconcilePressedTarget(
        pressed: CompanionDragPhase.Pressed,
        model: CompanionDragModel,
        geometry: CompanionDragGeometry,
    ): CompanionDragPhase.Pressed? {
        if (geometry.modelVersion != model.version) return null
        val tileId = pressed.tileId
        if (tileId == null || pressed.modelVersion != model.version) {
            return resolvePressedTarget(pressed, model, geometry)
        }
        val slot = model.order.indexOf(tileId)
        if (slot < 0) return resolvePressedTarget(pressed.withoutTarget(), model, geometry)
        val rect = geometry.slotRects[slot] ?: return pressed
        return pressed.copy(
            slot = slot,
            sourceRect = rect,
            layoutGeneration = geometry.generation,
        )
    }

    private fun geometryForCurrentModel(
        machine: CompanionDragMachine,
        incoming: CompanionDragGeometry,
    ): CompanionDragGeometry? {
        val modelVersion = machine.latestModel.version
        if (machine.geometry?.modelVersion?.let { it > modelVersion } == true) return null
        val retained = machine.geometry?.takeIf { it.modelVersion == modelVersion }
        return when {
            incoming.modelVersion > modelVersion -> null
            incoming.modelVersion < modelVersion -> retained
            else -> preferGeometry(retained, incoming)
        }
    }

    private fun preferGeometry(
        retained: CompanionDragGeometry?,
        incoming: CompanionDragGeometry,
    ): CompanionDragGeometry {
        if (retained == null) return incoming
        return when {
            incoming.modelVersion != retained.modelVersion ->
                if (incoming.modelVersion > retained.modelVersion) incoming else retained
            incoming.generation != retained.generation ->
                if (incoming.generation > retained.generation) incoming else retained
            incoming.slotRects.size >= retained.slotRects.size -> incoming
            else -> retained
        }
    }

    private fun pageGeometryComplete(
        model: CompanionDragModel,
        geometry: CompanionDragGeometry,
    ): Boolean {
        if (geometry.modelVersion != model.version) return false
        val first = geometry.settledPage * SlotsPerPage
        val lastExclusive = minOf(first + SlotsPerPage, model.order.size)
        return (first until lastExclusive).all(geometry.slotRects::containsKey)
    }

    private fun advanceDrag(
        machine: CompanionDragMachine,
        transaction: CompanionDragTransaction,
        event: CompanionDragEvent.Move,
    ): CompanionDragReduction {
        val movement = transaction.lastPointer.distanceTo(event.point)
        var updated = transaction.copy(
            lastPointer = event.point,
        )
        var nextToken = machine.nextToken
        val effects = mutableListOf<CompanionDragEffect>()

        if (!geometryMatches(updated, event.geometry)) {
            updated = updated.copy(edgeCandidate = null, mergeCandidate = null)
            return CompanionDragReduction(
                machine.copy(
                    phase = CompanionDragPhase.Dragging(updated),
                    geometry = event.geometry,
                ),
            )
        }
        updated = updated.copy(layoutGeneration = event.geometry.generation)

        val direction = edgeDirection(event.point, event.geometry, updated.settledPage)
        if (direction != 0) {
            val existing = updated.edgeCandidate
            updated = if (existing?.direction == direction) {
                updated.copy(mergeCandidate = null)
            } else {
                val token = nextToken++
                effects += CompanionDragEffect.ScheduleEdge(
                    transactionId = updated.id,
                    edgeToken = token,
                    direction = direction,
                    delayMillis = EdgeDwellMillis,
                )
                updated.copy(
                    mergeCandidate = null,
                    edgeCandidate = CompanionEdgeCandidate(direction, token, event.atMillis),
                    edgeGeneration = token,
                )
            }
            return CompanionDragReduction(
                machine.copy(
                    phase = CompanionDragPhase.Dragging(updated),
                    geometry = event.geometry,
                    nextToken = nextToken,
                ),
                effects,
            )
        }
        updated = updated.copy(edgeCandidate = null)

        val hit = hitSlot(event.point, event.geometry, requireInside = false)
        if (hit == null) {
            updated = updated.copy(mergeCandidate = null)
            return CompanionDragReduction(
                machine.copy(
                    phase = CompanionDragPhase.Dragging(updated),
                    geometry = event.geometry,
                ),
            )
        }

        val targetId = updated.workingOrder.getOrNull(hit.slot)
        val target = targetId?.let { id -> updated.frozenTiles.firstOrNull { it.id == id } }
        val dragged = updated.frozenTiles.firstOrNull { it.id == updated.draggedId }
        val canMerge = target != null && target.isBook && target.id != updated.draggedId &&
            dragged?.isBook == false && updated.newestModel.tile(target.id)?.isBook == true &&
            movement <= MergeStillPixels && isDeep(event.point, hit.rect, hit.face)

        if (canMerge) {
            val existing = updated.mergeCandidate
            if (existing?.targetId != target!!.id) {
                val token = nextToken++
                effects += CompanionDragEffect.ScheduleMerge(
                    transactionId = updated.id,
                    mergeToken = token,
                    delayMillis = MergeDwellMillis,
                )
                updated = updated.copy(
                    mergeCandidate = CompanionMergeCandidate(
                        targetId = target.id,
                        token = token,
                        enteredAtMillis = event.atMillis,
                        armed = false,
                    ),
                )
            }
            return CompanionDragReduction(
                machine.copy(
                    phase = CompanionDragPhase.Dragging(updated),
                    geometry = event.geometry,
                    nextToken = nextToken,
                ),
                effects,
            )
        }

        updated = updated.copy(mergeCandidate = null)
        if (target?.isBook == true && dragged?.isBook != true) {
            return CompanionDragReduction(
                machine.copy(
                    phase = CompanionDragPhase.Dragging(updated),
                    geometry = event.geometry,
                ),
            )
        }

        val span = minOf(hit.rect.width, hit.rect.height).coerceAtLeast(1f)
        if (event.point.distanceTo(hit.rect.center) > span * ReorderEnterFraction) {
            return CompanionDragReduction(
                machine.copy(
                    phase = CompanionDragPhase.Dragging(updated),
                    geometry = event.geometry,
                ),
            )
        }

        val moved = moveToSlot(updated, hit.slot)
        if (moved.workingOrder != updated.workingOrder) {
            effects += CompanionDragEffect.RenderOrder(moved.workingOrder)
        }
        return CompanionDragReduction(
            machine.copy(
                phase = CompanionDragPhase.Dragging(moved),
                geometry = event.geometry,
                nextToken = nextToken,
            ),
            effects,
        )
    }

    private fun resolveTerminal(
        machine: CompanionDragMachine,
        transaction: CompanionDragTransaction,
        point: DragPoint,
        geometry: CompanionDragGeometry,
    ): CompanionDragReduction {
        if (transaction.newestModel.tile(transaction.draggedId) == null) {
            return rollback(machine.copy(geometry = geometry), transaction)
        }
        if (!geometryMatches(transaction, geometry)) {
            return rollback(machine.copy(geometry = geometry), transaction)
        }

        val hit = hitSlot(point, geometry, requireInside = false)
            ?: return rollback(machine.copy(geometry = geometry), transaction)
        val finalTargetId = transaction.workingOrder.getOrNull(hit.slot)
        val candidate = transaction.mergeCandidate
        val validMerge = candidate?.armed == true &&
            candidate.targetId == finalTargetId &&
            finalTargetId != transaction.draggedId &&
            transaction.newestModel.tile(finalTargetId)?.isBook == true &&
            transaction.frozenTiles.firstOrNull { it.id == transaction.draggedId }?.isBook == false &&
            transaction.lastPointer.distanceTo(point) <= MergeStillPixels &&
            isDeep(point, hit.rect, hit.face)

        if (validMerge) {
            val reconciled = reconcileOrder(transaction)
            val merged = reconciled.filterNot { it == transaction.draggedId }
            return CompanionDragReduction(
                machine.copy(
                    phase = CompanionDragPhase.Idle,
                    latestModel = transaction.newestModel.withOrder(merged),
                    geometry = geometry,
                ),
                listOf(
                    CompanionDragEffect.RenderOrder(merged),
                    CompanionDragEffect.CommitMerge(
                        order = merged,
                        draggedId = transaction.draggedId,
                        targetId = candidate!!.targetId,
                    ),
                ),
            )
        }

        val dropped = moveToSlot(
            transaction.copy(
                lastPointer = point,
                mergeCandidate = null,
                edgeCandidate = null,
                layoutGeneration = geometry.generation,
            ),
            hit.slot,
        )
        val reconciled = reconcileOrder(dropped)
        val effects = buildList {
            add(CompanionDragEffect.RenderOrder(reconciled))
            if (reconciled != dropped.newestModel.order) {
                add(CompanionDragEffect.CommitOrder(reconciled))
            }
        }
        return CompanionDragReduction(
            machine.copy(
                phase = CompanionDragPhase.Idle,
                latestModel = dropped.newestModel.withOrder(reconciled),
                geometry = geometry,
            ),
            effects,
        )
    }

    /**
     * A drop is rendered and persisted before the authoritative payload necessarily makes the
     * round trip back to this reducer. Keep the local model in that committed order so a second
     * gesture resolves its pressed slot against the same ids the user can see.
     */
    private fun CompanionDragModel.withOrder(order: List<String>): CompanionDragModel {
        val byId = tiles.associateBy(CompanionDragTileSnapshot::id)
        return copy(tiles = order.map { byId.getValue(it) })
    }

    private fun rollback(
        machine: CompanionDragMachine,
        transaction: CompanionDragTransaction,
    ): CompanionDragReduction {
        val rollbackToken = machine.nextToken
        val pageCount = transactionPageCount(transaction)
        val originPage = transaction.originPage.coerceIn(0, pageCount - 1)
        return CompanionDragReduction(
            machine.copy(
                phase = CompanionDragPhase.RollingBack(transaction, rollbackToken),
                nextToken = rollbackToken + 1L,
            ),
            listOf(
                CompanionDragEffect.Rollback(
                    transactionId = transaction.id,
                    rollbackToken = rollbackToken,
                    authoritativeOrder = transaction.newestModel.order,
                ),
                CompanionDragEffect.SnapPage(originPage),
                CompanionDragEffect.RenderOrder(transaction.newestModel.order),
            ),
        )
    }

    private fun moveToPoint(
        transaction: CompanionDragTransaction,
        point: DragPoint,
        geometry: CompanionDragGeometry,
        force: Boolean,
    ): CompanionDragTransaction? {
        val hit = hitSlot(point, geometry, requireInside = !force) ?: return null
        return moveToSlot(transaction, hit.slot)
    }

    private fun moveToSlot(
        transaction: CompanionDragTransaction,
        slot: Int,
    ): CompanionDragTransaction {
        val from = transaction.workingOrder.indexOf(transaction.draggedId)
        if (from < 0 || transaction.workingOrder.isEmpty()) return transaction
        val destination = slot.coerceIn(0, transaction.workingOrder.lastIndex)
        if (destination == from) return transaction
        val moved = transaction.workingOrder.toMutableList().apply {
            add(destination, removeAt(from))
        }
        check(moved.size == moved.distinct().size)
        check(moved.toSet() == transaction.originOrder.toSet())
        return transaction.copy(workingOrder = moved)
    }

    private data class SlotHit(val slot: Int, val rect: DragRect, val face: DragRect?)

    private fun hitSlot(
        point: DragPoint,
        geometry: CompanionDragGeometry,
        requireInside: Boolean,
    ): SlotHit? {
        val slots = geometry.slotRects
            .asSequence()
            .filter { (slot, _) -> slot in pageSlotRange(geometry.settledPage) }
        val candidates = if (requireInside) slots.filter { (_, rect) -> rect.contains(point) } else slots
        return candidates
            .minByOrNull { (_, rect) -> point.distanceTo(rect.center) }
            ?.let { (slot, rect) -> SlotHit(slot, rect, geometry.faceRects[slot]) }
    }

    /**
     * The merge hotspot: a disc about the slot's centre, [MergeDeepFraction] of its span wide. When the surface hands
     * over the slot's visible [face] (a non-empty one), the disc is about the FACE's centre instead and never wider
     * than the face, so a drag held over the label band under the face cannot arm a merge and one held over the face
     * can.
     */
    private fun isDeep(point: DragPoint, rect: DragRect, face: DragRect?): Boolean {
        if (!rect.contains(point)) return false
        val span = minOf(rect.width, rect.height).coerceAtLeast(1f)
        val radius = span * MergeDeepFraction
        if (face == null || face.width <= 0f || face.height <= 0f) {
            return point.distanceTo(rect.center) <= radius
        }
        return point.distanceTo(face.center) <= minOf(radius, minOf(face.width, face.height) / 2f)
    }

    private fun edgeDirection(
        point: DragPoint,
        geometry: CompanionDragGeometry,
        settledPage: Int,
    ): Int {
        val rail = geometry.railBounds
        if (point.y !in rail.top..rail.bottom || rail.width <= 0f) return 0
        val margin = rail.width * EdgeZoneFraction
        return when {
            point.x <= rail.left + margin && settledPage > 0 -> -1
            point.x >= rail.right - margin && settledPage < geometry.pageCount - 1 -> 1
            else -> 0
        }
    }

    private fun geometryMatches(
        transaction: CompanionDragTransaction,
        geometry: CompanionDragGeometry,
    ): Boolean = geometry.modelVersion == transaction.newestModel.version &&
        geometry.generation >= transaction.layoutGeneration &&
        geometry.settledPage == transaction.settledPage &&
        geometry.settledPage in 0 until geometry.pageCount

    /** The surface's own count when its geometry is not to hand: the tiles AND the "+ Tome" cell after them (companionLatticePageCount), so a turn onto the page that holds only the "+" is not refused. */
    private fun transactionPageCount(transaction: CompanionDragTransaction): Int =
        (transaction.newestModel.order.size + SlotsPerPage) / SlotsPerPage

    private fun pageSlotRange(page: Int): IntRange {
        val first = page * SlotsPerPage
        return first until first + SlotsPerPage
    }
}

/**
 * The armed transaction a reduction ENDED without any of the three lines the surface already logs for an ending --
 * `commit-order`, `commit-merge`, `rollback` -- or null when no terminal line is owed.
 *
 * Observed: `MimirCompanionDrag: armed ... dragged=gbc`, then nothing. A drag released on the slot it
 * was lifted from ends in `resolveTerminal`'s drop branch with its order unchanged, so it emits `RenderOrder` alone and
 * none of the logged effects. A transaction still `Dragging` or `Turning`, or gone on to `RollingBack` (whose
 * `rollback` line is already written), is not owed one; neither is a press that never armed.
 */
internal fun companionDragEndedWithoutTerminalLine(
    before: CompanionDragPhase,
    after: CompanionDragPhase,
    effects: List<CompanionDragEffect>,
): CompanionDragTransaction? {
    val armed = when (before) {
        is CompanionDragPhase.Dragging -> before.transaction
        is CompanionDragPhase.Turning -> before.transaction
        else -> return null
    }
    val continues = when (after) {
        is CompanionDragPhase.Dragging -> after.transaction.id == armed.id
        is CompanionDragPhase.Turning -> after.transaction.id == armed.id
        is CompanionDragPhase.RollingBack -> after.transaction.id == armed.id
        else -> false
    }
    if (continues) return null
    val alreadyLogged = effects.any {
        it is CompanionDragEffect.CommitOrder ||
            it is CompanionDragEffect.CommitMerge ||
            it is CompanionDragEffect.Rollback
    }
    return armed.takeUnless { alreadyLogged }
}
