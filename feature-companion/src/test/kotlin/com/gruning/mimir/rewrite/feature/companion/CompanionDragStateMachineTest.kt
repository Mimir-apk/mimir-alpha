package com.gruning.mimir.rewrite.feature.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDragStateMachineTest {
    @Test
    fun delayedDeliveryUsesCurrentUptimeForRemainingLongPressDelay() {
        val deadline = 10_000L + CompanionDragStateMachine.LongPressMillis
        val staleEventUptime = 10_040L
        val delayedDeliveryUptime = 10_175L

        assertEquals(140L, remainingCompanionLongPressDelayMillis(deadline, staleEventUptime))
        assertEquals(5L, remainingCompanionLongPressDelayMillis(deadline, delayedDeliveryUptime))
        assertEquals(0L, remainingCompanionLongPressDelayMillis(deadline, deadline + 20L))
    }

    @Test
    fun tapBefore180MillisActivatesExactlyOnce() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)

        val effects = harness.up(slot = 0, atMillis = 179L)

        assertEquals(listOf(CompanionDragEffect.ActivateTile("a")), effects)
        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
    }

    @Test
    fun machineModelAheadOfEventGeometryPreservesTapAndLongPressAcrossLayoutGap() {
        val oldModel = model("old", "stable", version = 1L)
        val newModel = model("new", "old", "stable", version = 2L)
        val staleGeometry = gridGeometry(oldModel)
        val currentGeometry = gridGeometry(newModel, generation = 2L)

        val tap = Harness(oldModel)
        tap.publishModel(newModel)
        assertEquals(null, tap.machine.geometry)
        assertEquals(
            listOf(CompanionDragEffect.WakeMotion),
            tap.send(CompanionDragEvent.Down(PointerId, pointForSlot(0), 0L, staleGeometry)),
        )
        val tapToken = (tap.machine.phase as CompanionDragPhase.Pressed).pressToken
        assertEquals(null, (tap.machine.phase as CompanionDragPhase.Pressed).tileId)
        assertTrue(tap.send(CompanionDragEvent.Up(PointerId, pointForSlot(0), 100L, staleGeometry)).isEmpty())
        assertEquals(tapToken, (tap.machine.phase as CompanionDragPhase.Pressed).pressToken)

        assertEquals(
            listOf(CompanionDragEffect.ActivateTile("new")),
            tap.publishGeometry(currentGeometry),
        )
        assertTrue(tap.machine.phase is CompanionDragPhase.Idle)
        assertTrue(tap.publishGeometry(currentGeometry).isEmpty())

        val hold = Harness(oldModel)
        hold.publishModel(newModel)
        hold.send(CompanionDragEvent.Down(PointerId, pointForSlot(0), 0L, staleGeometry))
        val holdToken = (hold.machine.phase as CompanionDragPhase.Pressed).pressToken
        assertTrue(hold.publishGeometry(currentGeometry).isEmpty())
        val resolved = hold.machine.phase as CompanionDragPhase.Pressed
        assertEquals(holdToken, resolved.pressToken)
        assertEquals("new", resolved.tileId)
        assertEquals(
            listOf(
                CompanionDragEffect.ArmDrag(
                    TransactionId,
                    "new",
                    DragPoint(50f, 50f),
                    currentGeometry.slotRects.getValue(0),
                ),
                CompanionDragEffect.RenderOrder(newModel.order),
            ),
            hold.arm(atMillis = CompanionDragStateMachine.LongPressMillis),
        )
        assertEquals("new", hold.dragging().transaction.draggedId)
    }

    @Test
    fun eventGeometryAheadOfReducerPreservesTapAndLongPressUntilModelChanged() {
        val oldModel = model("old", "stable", version = 1L)
        val newModel = model("new", "old", "stable", version = 2L)
        val newGeometry = gridGeometry(newModel, generation = 2L)

        val tap = Harness(oldModel)
        tap.geometry = newGeometry
        tap.send(CompanionDragEvent.Down(PointerId, pointForSlot(0), 0L, newGeometry))
        val tapToken = (tap.machine.phase as CompanionDragPhase.Pressed).pressToken
        assertEquals(null, (tap.machine.phase as CompanionDragPhase.Pressed).tileId)
        assertTrue(tap.up(slot = 0, atMillis = 100L).isEmpty())
        assertEquals(tapToken, (tap.machine.phase as CompanionDragPhase.Pressed).pressToken)
        assertEquals(
            listOf(
                CompanionDragEffect.RenderOrder(newModel.order),
                CompanionDragEffect.ActivateTile("new"),
            ),
            tap.publishModel(newModel),
        )
        assertTrue(tap.machine.phase is CompanionDragPhase.Idle)
        assertTrue(tap.publishModel(newModel).isEmpty())

        val hold = Harness(oldModel)
        hold.geometry = newGeometry
        hold.send(CompanionDragEvent.Down(PointerId, pointForSlot(0), 0L, newGeometry))
        val holdToken = (hold.machine.phase as CompanionDragPhase.Pressed).pressToken
        assertEquals(
            listOf(CompanionDragEffect.RenderOrder(newModel.order)),
            hold.publishModel(newModel),
        )
        val resolved = hold.machine.phase as CompanionDragPhase.Pressed
        assertEquals(holdToken, resolved.pressToken)
        assertEquals("new", resolved.tileId)
        assertEquals(
            listOf(
                CompanionDragEffect.ArmDrag(
                    TransactionId,
                    "new",
                    DragPoint(50f, 50f),
                    newGeometry.slotRects.getValue(0),
                ),
                CompanionDragEffect.RenderOrder(newModel.order),
            ),
            hold.arm(atMillis = CompanionDragStateMachine.LongPressMillis),
        )
        assertEquals("new", hold.dragging().transaction.draggedId)
    }

    @Test
    fun emptyCurrentGeometryKeepsOnePressUntilUsableLayoutArrives() {
        val oldModel = model("old", "stable", version = 1L)
        val newModel = model("new", "old", "stable", version = 2L)
        val staleGeometry = gridGeometry(oldModel)
        val currentGeometry = gridGeometry(newModel, generation = 2L)
        val emptyGeometry = currentGeometry.copy(slotRects = emptyMap())
        val harness = Harness(oldModel)
        harness.publishModel(newModel)
        harness.publishGeometry(emptyGeometry)

        harness.send(CompanionDragEvent.Down(PointerId, pointForSlot(0), 0L, staleGeometry))
        val pressToken = (harness.machine.phase as CompanionDragPhase.Pressed).pressToken
        assertEquals(null, (harness.machine.phase as CompanionDragPhase.Pressed).tileId)
        assertTrue(harness.send(CompanionDragEvent.Up(PointerId, pointForSlot(0), 100L, staleGeometry)).isEmpty())
        assertEquals(pressToken, (harness.machine.phase as CompanionDragPhase.Pressed).pressToken)

        assertEquals(
            listOf(CompanionDragEffect.ActivateTile("new")),
            harness.publishGeometry(currentGeometry),
        )
        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
    }

    @Test
    fun upAtArmBoundaryCannotActivateAndArmedHoldCannotCofire() {
        val boundary = Harness(model("a", "b"))
        boundary.down(slot = 0, atMillis = 0L)
        assertTrue(boundary.up(slot = 0, atMillis = 180L).none { it is CompanionDragEffect.ActivateTile })

        val armed = Harness(model("a", "b"))
        armed.down(slot = 0, atMillis = 0L)
        val armEffects = armed.arm(atMillis = 180L)
        assertEquals(1, armEffects.count { it is CompanionDragEffect.ArmDrag })

        val upEffects = armed.up(slot = 0, atMillis = 181L)
        assertEquals(0, upEffects.count { it is CompanionDragEffect.ActivateTile })
        assertEquals(0, upEffects.persistenceCount())
    }

    @Test
    fun matchingLongPressTokenArmsAt180AndStaleTokenDoesNothing() {
        val harness = Harness(model("a", "b"))
        val pressed = harness.down(slot = 0, atMillis = 0L)

        val early = harness.send(
            CompanionDragEvent.LongPressElapsed(
                pointerId = PointerId,
                pressToken = pressed.pressToken,
                transactionId = "early",
                atMillis = 179L,
                geometry = harness.geometry,
            ),
        )
        assertTrue(early.isEmpty())
        assertTrue(harness.machine.phase is CompanionDragPhase.Pressed)

        val stale = harness.send(
            CompanionDragEvent.LongPressElapsed(
                pointerId = PointerId,
                pressToken = pressed.pressToken + 1L,
                transactionId = "stale",
                atMillis = 180L,
                geometry = harness.geometry,
            ),
        )
        assertTrue(stale.isEmpty())
        assertTrue(harness.machine.phase is CompanionDragPhase.Pressed)

        val blank = harness.send(
            CompanionDragEvent.LongPressElapsed(
                pointerId = PointerId,
                pressToken = pressed.pressToken,
                transactionId = "",
                atMillis = 180L,
                geometry = harness.geometry,
            ),
        )
        assertTrue(blank.isEmpty())
        assertTrue(harness.machine.phase is CompanionDragPhase.Pressed)

        val matching = harness.arm(atMillis = 180L, transactionId = "matching")
        assertEquals(1, matching.count { it is CompanionDragEffect.ArmDrag })
        assertEquals("matching", harness.dragging().transaction.id)
    }

    @Test
    fun preArmMovementUsesLatestPointerForPickupOffset() {
        val harness = Harness(model("a", "b"))
        val down = DragPoint(10f, 18f)
        val latest = DragPoint(14f, 23f)
        harness.down(point = down, atMillis = 0L)
        harness.move(point = latest, atMillis = 100L)

        val arm = harness.arm(atMillis = 180L).filterIsInstance<CompanionDragEffect.ArmDrag>().single()
        val transaction = harness.dragging().transaction

        assertEquals(DragPoint(14f, 23f), arm.pickupOffset)
        assertEquals(DragPoint(0f, 0f), transaction.lastPointer - transaction.pickupOffset)
    }

    @Test
    fun pagerConsumptionBeforeArmOwnsTheWholeGesture() {
        val harness = Harness(model("a", "b"))
        val pressed = harness.down(slot = 0, atMillis = 0L)
        harness.move(slot = 1, atMillis = 80L, pagerConsumed = true)
        assertEquals(CompanionDragPhase.PagerOwned(PointerId), harness.machine.phase)

        val staleArm = harness.send(
            CompanionDragEvent.LongPressElapsed(
                pointerId = PointerId,
                pressToken = pressed.pressToken,
                transactionId = "pager-lost",
                atMillis = 180L,
                geometry = harness.geometry,
            ),
        )
        val up = harness.up(slot = 1, atMillis = 220L)

        assertTrue(staleArm.isEmpty())
        assertEquals(0, up.persistenceCount())
        assertEquals(0, up.count { it is CompanionDragEffect.ActivateTile })
        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
    }

    @Test
    fun armAt180ThenImmediateMoveHasDragAuthorityWithoutGrace() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)

        val preview = harness.move(slot = 2, atMillis = 180L, pagerConsumed = true)
        assertEquals(
            listOf("b", "c", "a"),
            preview.filterIsInstance<CompanionDragEffect.RenderOrder>().single().order,
        )
        assertEquals(listOf("b", "c", "a"), harness.dragging().transaction.workingOrder)

        val terminal = harness.up(slot = 2, atMillis = 181L)
        assertEquals(1, terminal.terminalCount())
        assertEquals(
            listOf("b", "c", "a"),
            terminal.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
    }

    @Test
    fun secondDropCanImmediatelyReverseTheRenderedOrderBeforePayloadRoundTrip() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        val first = harness.up(slot = 2, atMillis = 181L)

        assertEquals(
            listOf("b", "c", "a"),
            first.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
        assertEquals(listOf("b", "c", "a"), harness.machine.latestModel.order)

        harness.down(slot = 2, atMillis = 300L)
        harness.arm(atMillis = 480L)
        val second = harness.up(slot = 0, atMillis = 481L)

        assertEquals(
            listOf("a", "b", "c"),
            second.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
        assertEquals(listOf("a", "b", "c"), harness.machine.latestModel.order)
    }

    @Test
    fun cancelAfterMovesRendersNewestModelAndNeverPersists() {
        val harness = Harness(model("a", "b", "c", "d", version = 1L))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        harness.move(slot = 1, atMillis = 200L)
        harness.move(slot = 2, atMillis = 220L)

        val newest = model("a", "x", "b", "c", "d", version = 2L)
        harness.changeModel(newest, generation = 2L)
        val cancelEffects = harness.send(CompanionDragEvent.Cancel(PointerId))

        assertEquals(0, cancelEffects.persistenceCount())
        assertEquals(
            newest.order,
            cancelEffects.filterIsInstance<CompanionDragEffect.RenderOrder>().single().order,
        )
        val rollback = harness.machine.phase as CompanionDragPhase.RollingBack

        val stale = harness.send(
            CompanionDragEvent.RollbackFinished(rollback.transaction.id, rollback.rollbackToken + 1L),
        )
        assertTrue(stale.isEmpty())
        assertTrue(harness.machine.phase is CompanionDragPhase.RollingBack)

        harness.send(
            CompanionDragEvent.RollbackFinished(rollback.transaction.id, rollback.rollbackToken),
        )
        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
    }

    @Test
    fun mergeAt749DoesNotArmAndReleaseFallsBackToOneReorder() {
        val harness = mergeHarness()
        val schedule = harness.hoverBook(atMillis = 220L)
        val token = schedule.filterIsInstance<CompanionDragEffect.ScheduleMerge>().single().mergeToken

        harness.send(CompanionDragEvent.MergeDwellElapsed(TransactionId, token, 969L))
        assertFalse(harness.dragging().transaction.mergeCandidate!!.armed)
        val effects = harness.up(slot = 1, atMillis = 970L)

        assertEquals(0, effects.count { it is CompanionDragEffect.CommitMerge })
        assertEquals(1, effects.count { it is CompanionDragEffect.CommitOrder })
        assertEquals(
            listOf("book", "a", "c"),
            effects.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
    }

    @Test
    fun matching750MergeTokenCommitsMergeOnceAndNeverCommitsOrder() {
        val harness = mergeHarness()
        val schedule = harness.hoverBook(atMillis = 220L)
        val token = schedule.filterIsInstance<CompanionDragEffect.ScheduleMerge>().single().mergeToken

        val stale = harness.send(
            CompanionDragEvent.MergeDwellElapsed(TransactionId, token + 1L, 970L),
        )
        assertTrue(stale.isEmpty())
        harness.send(CompanionDragEvent.MergeDwellElapsed(TransactionId, token, 970L))
        assertTrue(harness.dragging().transaction.mergeCandidate!!.armed)

        val effects = harness.up(slot = 1, atMillis = 971L)
        val merge = effects.filterIsInstance<CompanionDragEffect.CommitMerge>().single()
        assertEquals(listOf("book", "c"), merge.order)
        assertEquals("a", merge.draggedId)
        assertEquals("book", merge.targetId)
        assertEquals(0, effects.count { it is CompanionDragEffect.CommitOrder })
    }

    @Test
    fun armedMergeRejectsCoalescedFastUpDisplacement() {
        val harness = mergeHarness()
        val token = harness.hoverBook(atMillis = 220L)
            .filterIsInstance<CompanionDragEffect.ScheduleMerge>()
            .single()
            .mergeToken
        harness.send(CompanionDragEvent.MergeDwellElapsed(TransactionId, token, 970L))

        // Still inside the book's deep zone, but 10 px from the last delivered MOVE (> 6 px stillness).
        val effects = harness.up(point = DragPoint(160f, 50f), atMillis = 971L)

        assertEquals(0, effects.count { it is CompanionDragEffect.CommitMerge })
        assertEquals(1, effects.terminalCount())
        assertEquals(
            listOf("book", "a", "c"),
            effects.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
    }

    @Test
    fun draggedBookOverBookPreviewsTheOrderThatUpCommits() {
        val harness = Harness(
            model("book-a", "book-b", "c", books = setOf("book-a", "book-b")),
        )
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)

        val preview = harness.move(slot = 1, atMillis = 220L)
            .filterIsInstance<CompanionDragEffect.RenderOrder>()
            .single()
            .order
        assertEquals(listOf("book-b", "book-a", "c"), preview)
        assertEquals(preview, harness.dragging().transaction.workingOrder)

        val terminal = harness.up(slot = 1, atMillis = 221L)
        assertEquals(1, terminal.terminalCount())
        assertEquals(
            preview,
            terminal.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
        assertEquals(0, terminal.count { it is CompanionDragEffect.CommitMerge })
    }

    @Test
    fun armedMergeThenLeaveAndReleaseNeverMerges() {
        val harness = mergeHarness()
        val token = harness.hoverBook(atMillis = 220L)
            .filterIsInstance<CompanionDragEffect.ScheduleMerge>()
            .single()
            .mergeToken
        harness.send(CompanionDragEvent.MergeDwellElapsed(TransactionId, token, 970L))

        harness.move(slot = 2, atMillis = 980L)
        val effects = harness.up(slot = 2, atMillis = 981L)

        assertEquals(0, effects.count { it is CompanionDragEffect.CommitMerge })
        assertEquals(1, effects.count { it is CompanionDragEffect.CommitOrder })
        assertEquals(
            listOf("book", "c", "a"),
            effects.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
    }

    @Test
    fun firstEdgeTurnRequiresFull450MillisAndMatchingToken() {
        val harness = twoPageHarness()
        val schedule = harness.move(point = EdgePoint, atMillis = 200L)
            .filterIsInstance<CompanionDragEffect.ScheduleEdge>()
            .single()

        val at449 = harness.send(
            CompanionDragEvent.EdgeDwellElapsed(TransactionId, schedule.edgeToken, 649L),
        )
        assertTrue(at449.isEmpty())
        assertTrue(harness.machine.phase is CompanionDragPhase.Dragging)

        val stale = harness.send(
            CompanionDragEvent.EdgeDwellElapsed(TransactionId, schedule.edgeToken + 1L, 650L),
        )
        assertTrue(stale.isEmpty())

        val at450 = harness.send(
            CompanionDragEvent.EdgeDwellElapsed(TransactionId, schedule.edgeToken, 650L),
        )
        assertEquals(1, at450.count { it is CompanionDragEffect.AnimatePage })
        assertTrue(harness.machine.phase is CompanionDragPhase.Turning)
        val turning = harness.machine

        val staleFinish = harness.send(
            CompanionDragEvent.PageTurnFinished(
                transactionId = TransactionId,
                turnToken = (turning.phase as CompanionDragPhase.Turning).turnToken + 1L,
                targetPage = 1,
                succeeded = true,
                atMillis = 900L,
                geometry = gridGeometry(harness.model, settledPage = 1, generation = 2L),
            ),
        )
        assertTrue(staleFinish.isEmpty())
        assertEquals(turning, harness.machine)

        val duplicate = harness.send(
            CompanionDragEvent.EdgeDwellElapsed(TransactionId, schedule.edgeToken, 651L),
        )
        assertTrue(duplicate.isEmpty())
    }

    @Test
    fun stationaryPointerRetargetsDestinationWhenPageSettles() {
        val harness = twoPageHarness()
        val turn = harness.startTurn()
        val targetGeometry = gridGeometry(harness.model, settledPage = 1, generation = 2L)

        val effects = harness.send(
            CompanionDragEvent.PageTurnFinished(
                transactionId = TransactionId,
                turnToken = turn.turnToken,
                targetPage = 1,
                succeeded = true,
                atMillis = 900L,
                geometry = targetGeometry,
            ),
        )

        val transaction = harness.dragging().transaction
        assertEquals(1, transaction.settledPage)
        assertEquals(('b'..'l').map { it.toString() } + "a", transaction.workingOrder)
        assertEquals(
            transaction.workingOrder,
            effects.filterIsInstance<CompanionDragEffect.RenderOrder>().single().order,
        )
    }

    @Test
    fun moveDuringTurnTracksLiftAndInwardSettlementDoesNotScheduleRepeat() {
        val harness = threePageHarness()
        val turn = harness.startTurn()
        val inward = pointForSlot(1)

        val moveEffects = harness.move(point = inward, atMillis = 700L)
        assertTrue(moveEffects.isEmpty())
        assertEquals(inward, (harness.machine.phase as CompanionDragPhase.Turning).transaction.lastPointer)

        val settledEffects = harness.send(
            CompanionDragEvent.PageTurnFinished(
                transactionId = TransactionId,
                turnToken = turn.turnToken,
                targetPage = 1,
                succeeded = true,
                atMillis = 900L,
                geometry = gridGeometry(harness.model, settledPage = 1, generation = 2L),
            ),
        )

        assertEquals(0, settledEffects.count { it is CompanionDragEffect.ScheduleEdge })
        assertEquals(inward, harness.dragging().transaction.lastPointer)
        assertEquals(
            ('b'..'k').map { it.toString() } + "a" + ('l'..'t').map { it.toString() },
            harness.dragging().transaction.workingOrder,
        )
    }

    /**
     * Nine tiles fill page 1 and the "+ Tome" cell opens page 2 . The "+" hides while a tile is lifted, but the page stays: the geometry's
     * count is the idle one (2) for the whole drag, so the edge auto-turn has its target, and a tile dropped on that page lands at the end of the order.
     */
    @Test
    fun aLiftedTileTurnsToTheTrailingPageThatHoldsOnlyThePlusAndDropsAtTheEnd() {
        val ids = ('a'..'i').map { it.toString() }.toTypedArray()
        val harness = Harness(model(*ids))
        val idleCount = 2 // companionLatticePageCount(9)
        harness.geometry = gridGeometry(harness.model).copy(pageCount = idleCount)
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)

        val schedule = harness.move(point = EdgePoint, atMillis = 200L).filterIsInstance<CompanionDragEffect.ScheduleEdge>().single()
        harness.send(CompanionDragEvent.EdgeDwellElapsed(TransactionId, schedule.edgeToken, 650L))
        val turn = harness.machine.phase as CompanionDragPhase.Turning
        assertEquals("the edge turns onto the page that holds only the plus", 1, turn.targetPage)

        val onPlusPage = gridGeometry(harness.model, settledPage = 1, generation = 2L).copy(pageCount = idleCount, settledPage = 1)
        harness.send(
            CompanionDragEvent.PageTurnFinished(
                transactionId = TransactionId,
                turnToken = turn.turnToken,
                targetPage = 1,
                succeeded = true,
                atMillis = 900L,
                geometry = onPlusPage,
            ),
        )
        harness.geometry = onPlusPage
        assertEquals(1, harness.dragging().transaction.settledPage)

        harness.move(slot = 9, atMillis = 950L)
        val effects = harness.up(slot = 9, atMillis = 1_000L)
        assertEquals(
            "dropped on the empty cell of the plus page, the tile lands at the end",
            ('b'..'i').map { it.toString() } + "a",
            effects.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
    }

    @Test
    fun upDuringTurnWaitsForSettlementAndCommitsDestinationOnce() {
        val harness = twoPageHarness()
        val turn = harness.startTurn()

        val upEffects = harness.up(point = EdgePoint, atMillis = 700L)
        assertTrue(upEffects.isEmpty())
        assertTrue((harness.machine.phase as CompanionDragPhase.Turning).pendingUp != null)

        val sourceFinish = harness.send(
            CompanionDragEvent.PageTurnFinished(
                transactionId = TransactionId,
                turnToken = turn.turnToken,
                targetPage = 1,
                succeeded = true,
                atMillis = 900L,
                geometry = gridGeometry(harness.model, settledPage = 1, generation = 2L),
            ),
        )

        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
        assertEquals(1, sourceFinish.count { it is CompanionDragEffect.CommitOrder })
        assertEquals(0, sourceFinish.count { it is CompanionDragEffect.CommitMerge })
        assertEquals(
            ('b'..'l').map { it.toString() } + "a",
            sourceFinish.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
        assertEquals(1, sourceFinish.terminalCount())
    }

    @Test
    fun cancelDuringTurnRollsBackAndLateSettlementCannotCommit() {
        val harness = twoPageHarness()
        val turn = harness.startTurn()

        val cancelEffects = harness.send(CompanionDragEvent.Cancel(PointerId))
        assertEquals(0, cancelEffects.terminalCount())
        val rollingBack = harness.machine.phase as CompanionDragPhase.RollingBack

        val lateFinish = harness.send(
            CompanionDragEvent.PageTurnFinished(
                transactionId = TransactionId,
                turnToken = turn.turnToken,
                targetPage = 1,
                succeeded = true,
                atMillis = 900L,
                geometry = gridGeometry(harness.model, settledPage = 1, generation = 2L),
            ),
        )
        assertTrue(lateFinish.isEmpty())
        assertEquals(rollingBack, harness.machine.phase)

        harness.send(
            CompanionDragEvent.RollbackFinished(
                transactionId = rollingBack.transaction.id,
                rollbackToken = rollingBack.rollbackToken,
            ),
        )
        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
    }

    @Test
    fun failedPageSettlementRollsBackWithoutPersistence() {
        val harness = twoPageHarness()
        val turn = harness.startTurn()

        val effects = harness.send(
            CompanionDragEvent.PageTurnFinished(
                transactionId = TransactionId,
                turnToken = turn.turnToken,
                targetPage = 1,
                succeeded = false,
                atMillis = 900L,
                geometry = gridGeometry(harness.model, settledPage = 0, generation = 2L),
            ),
        )

        assertTrue(harness.machine.phase is CompanionDragPhase.RollingBack)
        assertEquals(0, effects.persistenceCount())
        assertEquals(1, effects.count { it is CompanionDragEffect.Rollback })
        assertEquals(0, effects.filterIsInstance<CompanionDragEffect.SnapPage>().single().page)
    }

    @Test
    fun payloadAddAndRemoveReconcileAgainstNewestAuthoritativeSlots() {
        val add = Harness(model("a", "b", "c", "d", version = 1L))
        add.down(slot = 0, atMillis = 0L)
        add.arm(atMillis = 180L)
        add.move(slot = 2, atMillis = 220L)
        add.changeModel(model("a", "x", "b", "c", "d", "y", version = 2L), generation = 2L)

        val addEffects = add.up(slot = 2, atMillis = 230L)
        assertEquals(
            listOf("b", "x", "c", "a", "d", "y"),
            addEffects.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )

        val remove = Harness(model("a", "b", "c", "d", version = 10L))
        remove.down(slot = 0, atMillis = 0L)
        remove.arm(atMillis = 180L)
        remove.move(slot = 2, atMillis = 220L)
        remove.changeModel(model("a", "x", "b", "d", version = 11L), generation = 2L)

        val removeEffects = remove.up(slot = 2, atMillis = 230L)
        assertEquals(
            listOf("b", "x", "a", "d"),
            removeEffects.filterIsInstance<CompanionDragEffect.CommitOrder>().single().order,
        )
    }

    @Test
    fun draggedIdRemovalCancelsAndMergeTargetRemovalCannotMerge() {
        val removedDragged = Harness(model("a", "b", "c", version = 1L))
        removedDragged.down(slot = 0, atMillis = 0L)
        removedDragged.arm(atMillis = 180L)
        removedDragged.move(slot = 2, atMillis = 220L)
        removedDragged.changeModel(model("b", "c", version = 2L), generation = 2L)

        val removedEffects = removedDragged.up(slot = 1, atMillis = 230L)
        assertEquals(0, removedEffects.persistenceCount())
        assertTrue(removedDragged.machine.phase is CompanionDragPhase.RollingBack)

        val targetRemoved = mergeHarness()
        val mergeToken = targetRemoved.hoverBook(atMillis = 220L)
            .filterIsInstance<CompanionDragEffect.ScheduleMerge>()
            .single()
            .mergeToken
        targetRemoved.send(CompanionDragEvent.MergeDwellElapsed(TransactionId, mergeToken, 970L))
        targetRemoved.changeModel(model("a", "c", version = 2L), generation = 2L)

        val targetEffects = targetRemoved.up(point = DragPoint(150f, 50f), atMillis = 980L)
        assertEquals(0, targetEffects.count { it is CompanionDragEffect.CommitMerge })
        assertEquals(0, targetEffects.persistenceCount())
        assertEquals(
            listOf("a", "c"),
            targetEffects.filterIsInstance<CompanionDragEffect.RenderOrder>().single().order,
        )
    }

    @Test
    fun allEmptyModelDuringDragRemovesDraggedIdAndRollsBackWithoutTerminalEffect() {
        val harness = Harness(model("a", "b", "c", version = 1L))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        harness.move(slot = 2, atMillis = 220L)
        harness.changeModel(model(version = 2L), generation = 2L)

        val effects = harness.up(slot = 2, atMillis = 230L)

        assertEquals(0, effects.terminalCount())
        assertEquals(emptyList<String>(), effects.filterIsInstance<CompanionDragEffect.RenderOrder>().single().order)
        assertTrue(harness.machine.phase is CompanionDragPhase.RollingBack)
    }

    @Test
    fun layoutGenerationChangeRejectsOldRectangles() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        val staleGeometry = harness.geometry
        harness.geometry = gridGeometry(harness.model, settledPage = 0, generation = 2L)
        harness.send(CompanionDragEvent.LayoutChanged(harness.geometry))

        val staleEffects = harness.send(
            CompanionDragEvent.Move(
                pointerId = PointerId,
                point = pointForSlot(2),
                atMillis = 220L,
                geometry = staleGeometry,
            ),
        )

        assertTrue(staleEffects.isEmpty())
        assertEquals(listOf("a", "b", "c"), harness.dragging().transaction.workingOrder)

        val currentEffects = harness.move(slot = 2, atMillis = 230L)
        assertEquals(
            listOf("b", "c", "a"),
            currentEffects.filterIsInstance<CompanionDragEffect.RenderOrder>().single().order,
        )
    }

    @Test
    fun multiTouchAndPointerReplacementCancelSafely() {
        val multiTouch = Harness(model("a", "b", "c"))
        multiTouch.down(slot = 0, atMillis = 0L)
        multiTouch.arm(atMillis = 180L)

        val multiEffects = multiTouch.move(slot = 1, atMillis = 220L, pointerCount = 2)
        assertEquals(0, multiEffects.persistenceCount())
        assertTrue(multiTouch.machine.phase is CompanionDragPhase.RollingBack)

        val replacement = Harness(model("a", "b", "c"))
        replacement.down(slot = 0, atMillis = 0L)
        replacement.arm(atMillis = 180L)
        val replacementEffects = replacement.send(
            CompanionDragEvent.Move(
                pointerId = PointerId + 1L,
                point = pointForSlot(1),
                atMillis = 220L,
                geometry = replacement.geometry,
            ),
        )
        assertEquals(0, replacementEffects.persistenceCount())
        assertTrue(replacement.machine.phase is CompanionDragPhase.RollingBack)
    }

    @Test
    fun unchangedDropEmitsNoPersistenceAndEveryWorkingOrderStaysPermutation() {
        val harness = Harness(model("a", "b", "c", "d"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        val effects = harness.up(slot = 0, atMillis = 220L)

        assertEquals(0, effects.persistenceCount())
        assertEquals(
            listOf("a", "b", "c", "d"),
            effects.filterIsInstance<CompanionDragEffect.RenderOrder>().single().order,
        )
    }

    // ── companionDragEndedWithoutTerminalLine: the one line an armed drag was missing (QA 2026-09-11 D13/D17) ──

    @Test
    fun armedDragReleasedWhereItWasLiftedIsOwedAnEndLine() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        val before = harness.machine.phase

        val effects = harness.up(slot = 0, atMillis = 220L)

        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
        val ended = companionDragEndedWithoutTerminalLine(before, harness.machine.phase, effects)
        assertEquals(TransactionId, ended?.id)
        assertEquals("a", ended?.draggedId)
    }

    @Test
    fun aCancelledDragIsNotOwedASecondLineBesideItsRollback() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        val beforeCancel = harness.machine.phase

        val cancelEffects = harness.send(CompanionDragEvent.Cancel(PointerId))

        assertTrue(cancelEffects.any { it is CompanionDragEffect.Rollback })
        assertNull(companionDragEndedWithoutTerminalLine(beforeCancel, harness.machine.phase, cancelEffects))

        // Nor when the rollback flight lands and the machine settles to Idle.
        val rollback = cancelEffects.filterIsInstance<CompanionDragEffect.Rollback>().single()
        val beforeFinish = harness.machine.phase
        val finishEffects = harness.send(
            CompanionDragEvent.RollbackFinished(
                transactionId = rollback.transactionId,
                rollbackToken = rollback.rollbackToken,
            ),
        )
        assertTrue(harness.machine.phase is CompanionDragPhase.Idle)
        assertNull(companionDragEndedWithoutTerminalLine(beforeFinish, harness.machine.phase, finishEffects))
    }

    @Test
    fun anEndingThatAlreadyLoggedACommitIsNotOwedASecondLine() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        val before = harness.machine.phase
        val released = harness.up(slot = 0, atMillis = 220L)
        val after = harness.machine.phase

        listOf(
            CompanionDragEffect.CommitOrder(listOf("b", "a", "c")),
            CompanionDragEffect.CommitMerge(order = listOf("b", "c"), draggedId = "a", targetId = "b"),
        ).forEach { commit ->
            assertNull("$commit", companionDragEndedWithoutTerminalLine(before, after, released + commit))
        }
    }

    @Test
    fun aDragStillInFlightOrAPressThatNeverArmedIsNotOwedALine() {
        val harness = Harness(model("a", "b", "c"))
        harness.down(slot = 0, atMillis = 0L)
        harness.arm(atMillis = 180L)
        val armed = harness.machine.phase
        val moveEffects = harness.move(slot = 1, atMillis = 200L)
        assertTrue(harness.machine.phase is CompanionDragPhase.Dragging)
        assertNull(companionDragEndedWithoutTerminalLine(armed, harness.machine.phase, moveEffects))

        val tap = Harness(model("a", "b", "c"))
        tap.down(slot = 0, atMillis = 0L)
        val beforeTap = tap.machine.phase
        val tapEffects = tap.up(slot = 0, atMillis = 100L)
        assertTrue(beforeTap is CompanionDragPhase.Pressed)
        assertNull(companionDragEndedWithoutTerminalLine(beforeTap, tap.machine.phase, tapEffects))
    }

    @Test
    fun theMergeHotspotIsTheVisibleFaceNotTheSlotCentreAtEveryFontScale() {
        listOf(1.0f, 2.0f).forEach { fontScale ->
            val cell = solveCompanionLatticeCell(LatticeCellWidth, LatticeCellHeight, fontScale)
            val slot = DragRect(LatticeCellWidth, 0f, 2f * LatticeCellWidth, LatticeCellHeight)
            val face = cell.faceRectIn(slot, density = 1f)
            val faceCentre = face.center
            val bandCentre = DragPoint(
                slot.left + LatticeCellWidth / 2f,
                cell.labelTopDp + cell.bandDp / 2f,
            )
            val at = "at fontScale $fontScale: face $face, band centre $bandCentre"

            val overFace = latticeHarness(fontScale)
            val faceEffects = overFace.hoverAt(faceCentre, atMillis = 220L)
            assertEquals("holding over the face centre arms a merge $at", 1, faceEffects.count { it is CompanionDragEffect.ScheduleMerge })
            assertEquals("book", overFace.dragging().transaction.mergeCandidate?.targetId)

            val overBand = latticeHarness(fontScale)
            val bandEffects = overBand.hoverAt(bandCentre, atMillis = 220L)
            assertEquals("holding over the label band does not arm $at", 0, bandEffects.count { it is CompanionDragEffect.ScheduleMerge })
            assertNull("no candidate over the label band $at", overBand.dragging().transaction.mergeCandidate)
        }
    }

    @Test
    fun theOldSlotCentreNoLongerArmsWhenTheFaceIsTopAlignedAtLargeFontScale() {
        // 151 x 111 at font scale 2.0 (the Thor cell since the lattice header of V3; 151 x 116, y = 19 and 58, in the finding): the face centre is at y = 16.5, the slot centre at y = 55.5.
        val cell = solveCompanionLatticeCell(LatticeCellWidth, LatticeCellHeight, 2.0f)
        val slot = DragRect(LatticeCellWidth, 0f, 2f * LatticeCellWidth, LatticeCellHeight)
        assertEquals(16.5f, cell.faceRectIn(slot, 1f).center.y, 0.01f)

        val harness = latticeHarness(2.0f)
        val effects = harness.hoverAt(slot.center, atMillis = 220L)

        assertEquals(0, effects.count { it is CompanionDragEffect.ScheduleMerge })
    }

    @Test
    fun aSlotWithoutAFaceRectKeepsTheSlotCentreHotspot() {
        val harness = mergeHarness()

        val effects = harness.hoverBook(atMillis = 220L)

        assertEquals(1, effects.count { it is CompanionDragEffect.ScheduleMerge })
    }

    private class Harness(initialModel: CompanionDragModel) {
        var model: CompanionDragModel = initialModel
        var geometry: CompanionDragGeometry = gridGeometry(initialModel)
        var machine: CompanionDragMachine = CompanionDragMachine.initial(initialModel)

        fun send(event: CompanionDragEvent): List<CompanionDragEffect> {
            val reduction = CompanionDragStateMachine.reduce(machine, event)
            machine = reduction.machine
            return reduction.effects
        }

        fun down(
            slot: Int = 0,
            point: DragPoint = pointForSlot(slot),
            atMillis: Long,
        ): CompanionDragPhase.Pressed {
            send(CompanionDragEvent.Down(PointerId, point, atMillis, geometry))
            return machine.phase as CompanionDragPhase.Pressed
        }

        fun arm(
            atMillis: Long,
            transactionId: String = TransactionId,
        ): List<CompanionDragEffect> {
            val pressed = machine.phase as CompanionDragPhase.Pressed
            return send(
                CompanionDragEvent.LongPressElapsed(
                    pointerId = pressed.pointerId,
                    pressToken = pressed.pressToken,
                    transactionId = transactionId,
                    atMillis = atMillis,
                    geometry = geometry,
                ),
            )
        }

        fun move(
            slot: Int,
            atMillis: Long,
            pagerConsumed: Boolean = false,
            pointerCount: Int = 1,
        ): List<CompanionDragEffect> = move(
            point = pointForSlot(slot),
            atMillis = atMillis,
            pagerConsumed = pagerConsumed,
            pointerCount = pointerCount,
        )

        fun move(
            point: DragPoint,
            atMillis: Long,
            pagerConsumed: Boolean = false,
            pointerCount: Int = 1,
        ): List<CompanionDragEffect> = send(
            CompanionDragEvent.Move(
                pointerId = PointerId,
                point = point,
                atMillis = atMillis,
                geometry = geometry,
                pagerConsumed = pagerConsumed,
                pressedPointerCount = pointerCount,
            ),
        )

        fun up(slot: Int, atMillis: Long): List<CompanionDragEffect> =
            up(pointForSlot(slot), atMillis)

        fun up(point: DragPoint, atMillis: Long): List<CompanionDragEffect> = send(
            CompanionDragEvent.Up(PointerId, point, atMillis, geometry),
        )

        fun publishModel(newModel: CompanionDragModel): List<CompanionDragEffect> {
            model = newModel
            return send(CompanionDragEvent.ModelChanged(newModel))
        }

        fun publishGeometry(newGeometry: CompanionDragGeometry): List<CompanionDragEffect> {
            geometry = newGeometry
            return send(CompanionDragEvent.LayoutChanged(newGeometry))
        }

        fun changeModel(newModel: CompanionDragModel, generation: Long) {
            publishModel(newModel)
            publishGeometry(gridGeometry(newModel, settledPage = 0, generation = generation))
        }

        fun dragging(): CompanionDragPhase.Dragging = machine.phase as CompanionDragPhase.Dragging

        fun hoverBook(atMillis: Long): List<CompanionDragEffect> {
            move(slot = 1, atMillis = atMillis - 10L)
            return move(slot = 1, atMillis = atMillis)
        }

        fun hoverAt(point: DragPoint, atMillis: Long): List<CompanionDragEffect> {
            move(point = point, atMillis = atMillis - 10L)
            return move(point = point, atMillis = atMillis)
        }

        fun startTurn(): CompanionDragPhase.Turning {
            val schedule = move(point = EdgePoint, atMillis = 200L)
                .filterIsInstance<CompanionDragEffect.ScheduleEdge>()
                .single()
            send(
                CompanionDragEvent.EdgeDwellElapsed(
                    transactionId = TransactionId,
                    edgeToken = schedule.edgeToken,
                    atMillis = 650L,
                ),
            )
            return machine.phase as CompanionDragPhase.Turning
        }
    }

    private companion object {
        const val PointerId = 7L
        const val TransactionId = "transaction"
        const val LatticeCellWidth = 151f
        const val LatticeCellHeight = 111f

        /** Three 151 x 111 cells in a row, each with the face the lattice solve puts in it at [fontScale]; "book" is slot 1. */
        fun latticeHarness(fontScale: Float): Harness {
            val harness = Harness(model("a", "book", "c", books = setOf("book")))
            val slots = (0 until 9).associate { slot ->
                slot to DragRect(
                    left = (slot % 3) * LatticeCellWidth,
                    top = (slot / 3) * LatticeCellHeight,
                    right = (slot % 3 + 1) * LatticeCellWidth,
                    bottom = (slot / 3 + 1) * LatticeCellHeight,
                )
            }
            val cell = solveCompanionLatticeCell(LatticeCellWidth, LatticeCellHeight, fontScale)
            harness.geometry = harness.geometry.copy(
                railBounds = DragRect(0f, 0f, 3f * LatticeCellWidth, 3f * LatticeCellHeight),
                slotRects = slots,
                faceRects = slots.mapValues { (_, slot) -> cell.faceRectIn(slot, density = 1f) },
            )
            harness.down(point = slots.getValue(0).center, atMillis = 0L)
            harness.arm(atMillis = 180L)
            return harness
        }

        val EdgePoint = DragPoint(299f, 50f)

        fun model(
            vararg ids: String,
            books: Set<String> = emptySet(),
            version: Long = 1L,
        ): CompanionDragModel = CompanionDragModel(
            version = version,
            tiles = ids.map { CompanionDragTileSnapshot(it, it in books) },
        )

        fun mergeHarness(): Harness {
            val harness = Harness(model("a", "book", "c", books = setOf("book")))
            harness.down(slot = 0, atMillis = 0L)
            harness.arm(atMillis = 180L)
            return harness
        }

        fun twoPageHarness(): Harness {
            val ids = ('a'..'l').map { it.toString() }.toTypedArray()
            val harness = Harness(model(*ids))
            harness.down(slot = 0, atMillis = 0L)
            harness.arm(atMillis = 180L)
            return harness
        }

        fun threePageHarness(): Harness {
            val ids = ('a'..'t').map { it.toString() }.toTypedArray()
            val harness = Harness(model(*ids))
            harness.down(slot = 0, atMillis = 0L)
            harness.arm(atMillis = 180L)
            return harness
        }

        fun gridGeometry(
            model: CompanionDragModel,
            settledPage: Int = 0,
            generation: Long = 1L,
        ): CompanionDragGeometry {
            val pageCount = ((model.order.size + 8) / 9).coerceAtLeast(1)
            val first = settledPage * 9
            val rects = (0 until 9).associate { localSlot ->
                val row = localSlot / 3
                val column = localSlot % 3
                first + localSlot to DragRect(
                    left = column * 100f,
                    top = row * 100f,
                    right = (column + 1) * 100f,
                    bottom = (row + 1) * 100f,
                )
            }
            return CompanionDragGeometry(
                generation = generation,
                modelVersion = model.version,
                pageCount = pageCount,
                settledPage = settledPage.coerceIn(0, pageCount - 1),
                railBounds = DragRect(0f, 0f, 300f, 300f),
                slotRects = rects,
            )
        }

        fun pointForSlot(slot: Int): DragPoint {
            val local = slot % 9
            val row = local / 3
            val column = local % 3
            return DragPoint(column * 100f + 50f, row * 100f + 50f)
        }

        fun List<CompanionDragEffect>.persistenceCount(): Int = count {
            it is CompanionDragEffect.CommitOrder || it is CompanionDragEffect.CommitMerge
        }

        fun List<CompanionDragEffect>.terminalCount(): Int = count {
            it is CompanionDragEffect.ActivateTile ||
                it is CompanionDragEffect.CommitOrder ||
                it is CompanionDragEffect.CommitMerge
        }
    }
}
