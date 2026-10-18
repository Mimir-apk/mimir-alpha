package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import com.gruning.mimir.rewrite.core.companionnative.PanelRejectReason
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CompanionRecentsStateTest {
    @Test
    fun overlayMustAcknowledgeDetachBeforePresentationCanRender() {
        val coordinator = coordinator()
        val overlay = leaseId(1)
        val render = UUID(0L, 101L)
        coordinator.show(entries())
        assertFalse(coordinator.ui.value.visible)
        assertTrue(coordinator.attachOverlayHost(overlay))
        assertTrue(coordinator.registerPresentationHost(render))

        assertFalse(coordinator.selectPresentation(render))
        assertTrue(coordinator.releaseOverlayHost(overlay, reason = null))
        assertTrue(coordinator.selectPresentation(render))

        assertTrue(coordinator.ui.value.isVisibleOnPresentation(render))
        assertTrue(coordinator.ui.value.visible)
        assertFalse(coordinator.ui.value.isVisibleOnOverlay(overlay))
    }

    @Test
    fun staleOverlayAcknowledgementCannotHideReplacement() {
        val coordinator = coordinator()
        val a = leaseId(1)
        val b = leaseId(2)
        coordinator.show(entries())
        coordinator.attachOverlayHost(a)
        coordinator.attachOverlayHost(b)

        assertFalse(coordinator.releaseOverlayHost(a, reason = null))
        assertEquals(CompanionDrawerHost.Overlay(b), coordinator.ui.value.drawerHost)
    }

    @Test
    fun permissionFailureKeepsDrawerEligibleForTokenizedRemount() {
        val coordinator = coordinator()
        val lease = leaseId(7)
        coordinator.show(entries())

        coordinator.noteOverlayUnavailable(PanelRejectReason.OVERLAY_PERMISSION_MISSING)
        assertTrue(coordinator.ui.value.awaitingOverlayPermission)
        assertTrue(coordinator.shouldAttachOverlay())
        assertTrue(coordinator.attachOverlayHost(lease))

        assertFalse(coordinator.ui.value.awaitingOverlayPermission)
        assertTrue(coordinator.ui.value.isVisibleOnOverlay(lease))
    }

    @Test
    fun activityHostReleaseKeepsVisibleDrawerEligibleForReplacementHost() {
        val coordinator = coordinator()
        val oldLease = leaseId(8)
        coordinator.show(entries())
        assertTrue(coordinator.attachOverlayHost(oldLease))

        assertTrue(coordinator.releaseOverlayHost(oldLease, reason = null))

        assertTrue(coordinator.shouldAttachOverlay())
        assertFalse(coordinator.ui.value.awaitingOverlayPermission)
        assertTrue(coordinator.ui.value.requestedVisible)
    }

    @Test
    fun presentationProjectionCanMoveToOverlayWithoutRetiringLivePresentation() {
        val scheduler = FakeDeadlineScheduler()
        val operationId = UUID(0L, 803L)
        val coordinator = coordinator(operationId = operationId, scheduler = scheduler)
        val render = UUID(0L, 801L)
        val overlay = leaseId(802L)
        val replacementRender = UUID(0L, 804L)
        coordinator.show(entries())
        assertTrue(coordinator.registerPresentationHost(render))
        assertTrue(coordinator.selectPresentation(render))
        assertEquals(operationId, coordinator.requestLaunch("org.example.game") { _, _ -> true })
        val pendingBeforeMigration = coordinator.ui.value.launchState as CompanionDrawerLaunchState.LaunchPending
        val deadlineBeforeMigration = pendingBeforeMigration.deadlineElapsedRealtimeMs

        assertTrue(coordinator.clearPresentationDrawer(render))
        assertEquals(CompanionDrawerHost.Hidden, coordinator.ui.value.drawerHost)
        assertTrue(render in coordinator.livePresentationHosts.value)
        assertLaunchStillPending(coordinator, operationId, deadlineBeforeMigration)
        assertTrue(coordinator.attachOverlayHost(overlay))
        assertFalse(coordinator.clearPresentationDrawer(render))
        assertEquals(CompanionDrawerHost.Overlay(overlay), coordinator.ui.value.drawerHost)
        assertTrue(coordinator.releaseOverlayHost(overlay, reason = null))
        assertTrue(coordinator.registerPresentationHost(replacementRender))
        assertTrue(coordinator.selectPresentation(replacementRender))
        assertFalse(coordinator.clearPresentationDrawer(render))
        assertEquals(
            CompanionDrawerHost.Presentation(replacementRender),
            coordinator.ui.value.drawerHost,
        )
        assertLaunchStillPending(coordinator, operationId, deadlineBeforeMigration)
        assertEquals(1, scheduler.tasks.size)

        scheduler.runAll()
        assertEquals(CompanionDrawerLaunchState.LaunchFailed("Launch timed out."), coordinator.ui.value.launchState)
        assertFalse(coordinator.ui.value.launchLocked)
    }

    @Test
    fun hostPolicyUsesStoppedPrimaryOnlyForMirrorPresentedAndNoneOtherwise() {
        val mirror = mirrorSnapshot()
        val yielded = mirror.copy(
            disposition = SecondaryDisplayDisposition.Yielded(lease = null, evidence = YieldEvidence.HOST_COVERED),
            mirrorActive = false,
        )

        assertEquals(
            CompanionDrawerHostSelection.PRESENTATION,
            companionDrawerHostSelection(mirror, primaryActivityStopped = false),
        )
        assertEquals(
            CompanionDrawerHostSelection.OVERLAY,
            companionDrawerHostSelection(mirror, primaryActivityStopped = true),
        )
        assertEquals(
            CompanionDrawerHostSelection.OVERLAY,
            companionDrawerHostSelection(yielded, primaryActivityStopped = false),
        )
        assertEquals(
            CompanionDrawerHostSelection.OVERLAY,
            companionDrawerHostSelection(yielded, primaryActivityStopped = true),
        )

        val transientOrRecoveryStates = listOf<SecondaryDisplayDisposition>(
            SecondaryDisplayDisposition.NoDisplay,
            SecondaryDisplayDisposition.Presenting(
                attempt = renderAttempt(),
                cause = PresentCause.DISPLAY_AVAILABLE,
            ),
            SecondaryDisplayDisposition.LaunchPending(
                lease = hostLease(),
                phase = LaunchPhase.HOST_REQUESTED,
            ),
            SecondaryDisplayDisposition.ReclaimPending(
                lease = reclaimLease(),
                phase = ReclaimPhase.GUARD_REQUESTED,
            ),
            SecondaryDisplayDisposition.RecoveryNeeded(
                operationId = OperationId.create(),
                displayId = 4,
                fallback = PrimaryReturnOrigin.LegacyCurrent,
                failure = FailureReason(FailureCode.RENDER_FAILED, "test recovery"),
            ),
        )
        transientOrRecoveryStates.forEach { disposition ->
            val snapshot = snapshotFor(disposition)
            assertEquals(
                "stopped=false disposition=$disposition",
                CompanionDrawerHostSelection.NONE,
                companionDrawerHostSelection(snapshot, primaryActivityStopped = false),
            )
            assertEquals(
                "stopped=true disposition=$disposition",
                CompanionDrawerHostSelection.NONE,
                companionDrawerHostSelection(snapshot, primaryActivityStopped = true),
            )
        }

        val coldRecovery = SecondaryDisplayDisposition.RecoveryNeeded(
            operationId = OperationId.create(),
            displayId = 4,
            fallback = PrimaryReturnOrigin.LegacyCurrent,
            failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, "saved host"),
        )
        assertEquals(
            CompanionDrawerHostSelection.NONE,
            companionDrawerHostSelection(snapshotFor(coldRecovery), primaryActivityStopped = false),
        )
        assertEquals(
            CompanionDrawerHostSelection.OVERLAY,
            companionDrawerHostSelection(
                snapshotFor(coldRecovery).copy(availableDisplayId = 4),
                primaryActivityStopped = false,
            ),
        )
    }

    @Test
    fun aLiveDualScreenGameMovesTheMirrorDrawerToTheOverlayWindowWhateverTheStateOfMimirsMain() {
        // R1 : a dual-screen emulator adds its own window on the Bottom, above Mimir's Presentation, so a drawer drawn
        // in the Presentation is under it even while Mimir's Main activity is resumed (a parked game). The overlay window is above both.
        val mirror = mirrorSnapshot()
        for (stopped in listOf(false, true)) {
            assertEquals(
                "stopped=$stopped with a dual-screen game",
                CompanionDrawerHostSelection.OVERLAY,
                companionDrawerHostSelection(mirror, primaryActivityStopped = stopped, dualScreenGameLive = true),
            )
        }
        assertEquals(
            "no game: Mimir in front keeps the Presentation, as before",
            CompanionDrawerHostSelection.PRESENTATION,
            companionDrawerHostSelection(mirror, primaryActivityStopped = false, dualScreenGameLive = false),
        )
        // The flag is only a reason to prefer the overlay for a mirror the Presentation could have drawn; it makes no host of a screen
        // that has none.
        val noDisplay = snapshotFor(SecondaryDisplayDisposition.NoDisplay)
        assertEquals(
            CompanionDrawerHostSelection.NONE,
            companionDrawerHostSelection(noDisplay, primaryActivityStopped = false, dualScreenGameLive = true),
        )
    }

    @Test
    fun ownMirrorDrawerEdgeBackRequiresMirrorDrawerAndControllerAdmission() {
        val coordinator = coordinator()
        val render = UUID(0L, 901L)
        coordinator.show(entries())
        assertTrue(coordinator.registerPresentationHost(render))
        assertTrue(coordinator.selectPresentation(render))
        assertTrue(coordinator.ui.value.visible)
        assertTrue(
            companionOwnMirrorDrawerEdgeBackEnabled(
                mirrorActive = true,
                drawerVisible = true,
                controllerActive = true,
            ),
        )
        assertFalse(
            companionOwnMirrorDrawerEdgeBackEnabled(
                mirrorActive = false,
                drawerVisible = true,
                controllerActive = true,
            ),
        )
        assertFalse(
            companionOwnMirrorDrawerEdgeBackEnabled(
                mirrorActive = true,
                drawerVisible = false,
                controllerActive = true,
            ),
        )
        assertFalse(
            companionOwnMirrorDrawerEdgeBackEnabled(
                mirrorActive = true,
                drawerVisible = true,
                controllerActive = false,
            ),
        )
        // R6-P1-02 : overruled the `activeMainDualScreenGame` admission — a live
        // dual-screen game no longer arms the own-mirror edge-back exception; it is blocked outright by
        // AndroidCompanionOverlayController's separate `dualScreenGameOwnsBottom` gate instead. The cell
        // that pinned `activeMainDualScreenGame = true → true` (and its `= false → false` twin) is gone
        // with the parameter.
        assertTrue(coordinator.releasePresentationHost(render))
        assertFalse(coordinator.ui.value.visible)
        assertFalse(
            companionOwnMirrorDrawerEdgeBackEnabled(
                mirrorActive = true,
                drawerVisible = coordinator.ui.value.visible,
                controllerActive = true,
            ),
        )
    }

    // the native CP03 strip is a separate window that consumes DOWN/MOVE/UP outright —
    // arming it for a folder/layer over Mimir's OWN mirror does not just make it swipeable, it makes the
    // whole right 32dp tap-dead, since the only pass-through is FLAG_NOT_TOUCHABLE. This admission must
    // stay false for every folder/layer-open state; the Compose edge-back gesture on the Presentation
    // itself (MimirCompanionPresentation's root Box) is what closes the layer now.
    @Test
    fun ownMirrorFolderNeverArmsTheNativeStrip() {
        assertFalse(
            companionOwnMirrorFolderEdgeBackEnabled(
                mirrorActive = true,
                folderOpen = true,
                controllerActive = true,
                mainDualScreenGameActive = false,
            ),
        )
        // Every other admission combination stays false too — this is not a narrower gate, it is off.
        assertFalse(
            companionOwnMirrorFolderEdgeBackEnabled(
                mirrorActive = false,
                folderOpen = true,
                controllerActive = true,
                mainDualScreenGameActive = false,
            ),
        )
        assertFalse(
            companionOwnMirrorFolderEdgeBackEnabled(
                mirrorActive = true,
                folderOpen = false,
                controllerActive = true,
                mainDualScreenGameActive = false,
            ),
        )
        assertFalse(
            companionOwnMirrorFolderEdgeBackEnabled(
                mirrorActive = true,
                folderOpen = true,
                controllerActive = false,
                mainDualScreenGameActive = false,
            ),
        )
        assertFalse(
            companionOwnMirrorFolderEdgeBackEnabled(
                mirrorActive = true,
                folderOpen = true,
                controllerActive = true,
                mainDualScreenGameActive = true,
            ),
        )
    }

    @Test
    fun stalePresentationReleaseCannotHideReplacement() {
        val coordinator = coordinator()
        val oldRender = UUID(0L, 101L)
        val replacement = UUID(0L, 102L)
        coordinator.show(entries())
        assertTrue(coordinator.registerPresentationHost(oldRender))
        assertTrue(coordinator.selectPresentation(oldRender))
        assertTrue(coordinator.registerPresentationHost(replacement))
        assertTrue(coordinator.selectPresentation(replacement))

        assertFalse(coordinator.releasePresentationHost(oldRender))
        assertEquals(CompanionDrawerHost.Presentation(replacement), coordinator.ui.value.drawerHost)
        assertTrue(coordinator.releasePresentationHost(replacement))
        assertTrue(coordinator.shouldAttachOverlay())
    }

    @Test
    fun drawerIntentAfterMirrorSettlesReemitsRuntimeAndSelectsItsPresentation() = runBlocking {
        val coordinator = coordinator()
        val snapshot = mirrorSnapshot()
        val runtime = MutableStateFlow(snapshot)
        val drawer = MutableStateFlow(CompanionRecentsState.Ui())
        val emissions = mutableListOf<CompanionRuntimeSnapshot>()
        val firstEmission = CompletableDeferred<Unit>()
        val collector = launch {
            companionDrawerHostReconciliation(runtime, drawer, coordinator.livePresentationHosts)
                .take(2).collect { emitted ->
                emissions += emitted
                if (emissions.size == 1) firstEmission.complete(Unit)
            }
        }
        withTimeout(1_000L) { firstEmission.await() }

        coordinator.show(entries())
        drawer.value = coordinator.ui.value
        withTimeout(1_000L) { collector.join() }

        assertEquals(listOf(snapshot, snapshot), emissions)
        val renderId = UUID.fromString(
            (snapshot.disposition as SecondaryDisplayDisposition.MirrorPresented).attempt.operationId.toString(),
        )
        assertTrue(coordinator.registerPresentationHost(renderId))
        assertTrue(coordinator.selectPresentation(emissions.last()))
        assertTrue(coordinator.ui.value.isVisibleOnPresentation(renderId))
    }

    @Test
    fun primaryLifecycleChangeReemitsHostReconciliation() = runBlocking {
        val snapshot = mirrorSnapshot()
        val runtime = MutableStateFlow(snapshot)
        val drawer = MutableStateFlow(CompanionRecentsState.Ui())
        val primaryStopped = MutableStateFlow(false)
        val emissions = mutableListOf<CompanionRuntimeSnapshot>()
        val collector = launch {
            companionDrawerHostReconciliation(
                runtime,
                drawer,
                CompanionRecentsCoordinator().livePresentationHosts,
                primaryStopped,
            ).take(2).collect { emissions += it }
        }

        withTimeout(1_000L) {
            while (emissions.size < 1) kotlinx.coroutines.yield()
        }
        primaryStopped.value = true
        withTimeout(1_000L) { collector.join() }

        assertEquals(listOf(snapshot, snapshot), emissions)
    }

    @Test
    fun liveReconciliationCannotReacquireAStoppedPresentationToken() = runBlocking {
        val coordinator = coordinator()
        val snapshot = mirrorSnapshot()
        val renderId = UUID.fromString(
            (snapshot.disposition as SecondaryDisplayDisposition.MirrorPresented).attempt.operationId.toString(),
        )
        coordinator.show(entries())
        assertTrue(coordinator.registerPresentationHost(renderId))
        assertTrue(coordinator.selectPresentation(snapshot))
        val runtime = MutableStateFlow(snapshot)
        val drawer = MutableStateFlow(coordinator.ui.value)
        val selections = mutableListOf<Boolean>()
        val firstEmission = CompletableDeferred<Unit>()
        val collector = launch {
            companionDrawerHostReconciliation(runtime, drawer, coordinator.livePresentationHosts)
                .take(2).collect { emitted ->
                selections += coordinator.selectPresentation(emitted)
                if (selections.size == 1) firstEmission.complete(Unit)
            }
        }
        withTimeout(1_000L) { firstEmission.await() }

        assertTrue(coordinator.releasePresentationHost(renderId))
        drawer.value = coordinator.ui.value
        withTimeout(1_000L) { collector.join() }

        assertEquals(listOf(true, false), selections)
        assertEquals(CompanionDrawerHost.Hidden, coordinator.ui.value.drawerHost)
        assertTrue(coordinator.shouldAttachOverlay())
    }

    @Test
    fun fastHostCoverClearsMatchingLaunchAcrossOverlayToPresentationMigration() {
        val scheduler = FakeDeadlineScheduler()
        val operationId = UUID(0L, 201L)
        val coordinator = coordinator(operationId = operationId, scheduler = scheduler)
        val overlay = leaseId(1)
        val render = UUID(0L, 102L)
        coordinator.show(entries())
        coordinator.attachOverlayHost(overlay)
        val launched = coordinator.requestLaunch("org.example.game") { _, _ -> true }
        assertEquals(operationId, launched)

        coordinator.releaseOverlayHost(overlay, reason = null)
        coordinator.registerPresentationHost(render)
        coordinator.selectPresentation(render)
        assertTrue(coordinator.clearLaunchLock(operationId, CompanionLaunchUnlockReason.HOST_COVERED))

        assertFalse(coordinator.ui.value.launchLocked)
        assertFalse(coordinator.ui.value.requestedVisible)
        assertTrue(scheduler.tasks.single().cancelled)
        scheduler.runAll()
        assertEquals(CompanionDrawerLaunchState.Unlocked, coordinator.ui.value.launchState)
    }

    @Test
    fun preparedRuntimeOperationIsPublishedBeforeTargetDispatch() {
        val scheduler = FakeDeadlineScheduler()
        val runtimeOperationId = UUID(0L, 250L)
        val coordinator = coordinator(operationId = UUID(0L, 999L), scheduler = scheduler)
        coordinator.show(entries())
        var publishedBeforeDispatch = false

        val launched = coordinator.requestPreparedLaunch("org.example.game") {
            CompanionPreparedLaunch(runtimeOperationId, dispatch = {
                publishedBeforeDispatch =
                    (coordinator.ui.value.launchState as? CompanionDrawerLaunchState.LaunchPending)
                        ?.operationId == runtimeOperationId
                true
            })
        }

        assertEquals(runtimeOperationId, launched)
        assertTrue(publishedBeforeDispatch)
    }

    @Test
    fun missingOrStaleOperationCannotUnlockNewerFlight() {
        val scheduler = FakeDeadlineScheduler()
        val ids = ArrayDeque(listOf(UUID(0L, 301L), UUID(0L, 302L)))
        val coordinator = CompanionRecentsCoordinator(
            elapsedRealtimeMs = { 10L },
            operationIdFactory = { ids.removeFirst() },
            deadlineScheduler = scheduler,
        )
        coordinator.show(entries())
        val first = coordinator.requestLaunch("org.example.game") { _, _ -> true }!!
        assertFalse(coordinator.clearNonPendingLaunchState())
        assertTrue(coordinator.failLaunch(first, "failed"))
        val second = coordinator.requestLaunch("org.example.game") { _, _ -> true }!!

        assertFalse(coordinator.clearLaunchLock(first, CompanionLaunchUnlockReason.HOST_COVERED))
        assertTrue(coordinator.ui.value.launchLocked)
        assertEquals(second, (coordinator.ui.value.launchState as CompanionDrawerLaunchState.LaunchPending).operationId)
    }

    @Test
    fun closeStatusIsTokenOwnedAndClearsOnlyAfterConfirmedRemoval() {
        val ids = ArrayDeque(listOf(UUID(0L, 501L), UUID(0L, 502L)))
        val coordinator = CompanionRecentsCoordinator(
            operationIdFactory = { ids.removeFirst() },
        )
        coordinator.show(entries())

        val first = coordinator.beginClose("org.example.game")!!
        assertEquals(first, (coordinator.ui.value.closeState as CompanionDrawerCloseState.Pending).operationId)
        assertTrue(coordinator.ui.value.closePending)
        assertEquals("Returning to shelf…", coordinator.ui.value.closeMessage)
        assertNull(coordinator.beginClose("org.example.game"))
        assertFalse(coordinator.failClose(UUID(0L, 599L), "org.example.game", "stale"))
        assertTrue(coordinator.failClose(first, "org.example.game", "The tab stayed open. Try again."))
        assertFalse(coordinator.ui.value.closePending)
        assertEquals("The tab stayed open. Try again.", coordinator.ui.value.closeMessage)

        val retry = coordinator.beginClose("org.example.game")!!
        assertTrue(coordinator.completeClose(retry, "org.example.game"))
        assertEquals(CompanionDrawerCloseState.Idle, coordinator.ui.value.closeState)
        assertTrue(coordinator.ui.value.entries.isEmpty())
        assertFalse(coordinator.completeClose(first, "org.example.game"))
    }

    @Test
    fun staleCloseCleanupCannotClearReplacementOperation() {
        val ids = ArrayDeque(listOf(UUID(0L, 503L), UUID(0L, 504L)))
        val coordinator = CompanionRecentsCoordinator(operationIdFactory = { ids.removeFirst() })
        coordinator.show(entries())

        val first = coordinator.beginClose("org.example.game")!!
        assertTrue(coordinator.clearClose(first))
        val replacement = coordinator.beginClose("org.example.game")!!
        assertFalse(coordinator.clearClose(first))
        assertEquals(
            replacement,
            (coordinator.ui.value.closeState as CompanionDrawerCloseState.Pending).operationId,
        )
    }

    @Test
    fun processDeadlineSurvivesHostMigrationAndClearsOnlyItsFlight() {
        val scheduler = FakeDeadlineScheduler()
        val operationId = UUID(0L, 401L)
        val coordinator = coordinator(operationId = operationId, scheduler = scheduler)
        coordinator.show(entries())
        coordinator.attachOverlayHost(leaseId(1))
        assertNotNull(coordinator.requestLaunch("org.example.game") { _, _ -> true })
        coordinator.releaseOverlayHost(leaseId(1), reason = null)
        val render = UUID(0L, 103L)
        coordinator.registerPresentationHost(render)
        coordinator.selectPresentation(render)

        scheduler.runAll()

        assertFalse(coordinator.ui.value.launchLocked)
        assertFalse(coordinator.ui.value.requestedVisible)
        assertEquals(
            CompanionDrawerLaunchState.LaunchFailed("Launch timed out."),
            coordinator.ui.value.launchState,
        )
    }

    @Test
    fun `I5 the drawer launch deadline does not fire before the runtime's own dispatch and coverage deadlines`() {
        val scheduler = FakeDeadlineScheduler()
        val coordinator = coordinator(scheduler = scheduler)
        coordinator.show(entries())

        assertNotNull(coordinator.requestLaunch("org.example.game") { _, _ -> true })

        val runtimeTimeouts = CompanionRuntimeTimeouts()
        val runtimeGivesUpAfterMs = runtimeTimeouts.launchMillis + runtimeTimeouts.targetCoverageMillis
        val drawerDeadlineMs = scheduler.tasks.single().delayMs
        assertTrue(
            "drawer deadline ${drawerDeadlineMs}ms must be at or after the runtime's own " +
                "${runtimeGivesUpAfterMs}ms dispatch+coverage deadline, or the drawer unlocks while the " +
                "runtime still holds the operation (I5)",
            drawerDeadlineMs >= runtimeGivesUpAfterMs,
        )
    }

    @Test
    fun `P2 tab close log messages carry the package and operation id`() {
        val op = UUID(0L, 909L)
        assertEquals(
            "tab close requested package=org.example.game op=$op",
            tabCloseRequestedLogMessage("org.example.game", op),
        )
        assertEquals(
            "tab close failed package=org.example.game op=$op message=The tab stayed open. Try again.",
            tabCloseFailedLogMessage("org.example.game", op, "The tab stayed open. Try again."),
        )
        assertEquals(
            "tab close completed package=org.example.game op=$op",
            tabCloseCompletedLogMessage("org.example.game", op),
        )
        assertEquals(
            "tab close cleared package=org.example.game op=$op",
            tabCloseClearedLogMessage("org.example.game", op),
        )
    }

    @Test
    fun `P3 secondary launch log messages carry the package operation id and reason`() {
        val op = UUID(0L, 910L)
        assertEquals(
            "secondary launch timed out package=org.example.game op=$op",
            secondaryLaunchTimedOutLogMessage("org.example.game", op),
        )
        assertEquals(
            "secondary launch failed package=org.example.game op=$op message=Launch unavailable.",
            secondaryLaunchFailedLogMessage("org.example.game", op, "Launch unavailable."),
        )
        assertEquals(
            "secondary launch unlocked op=$op reason=HOST_COVERED",
            secondaryLaunchUnlockedLogMessage(op, CompanionLaunchUnlockReason.HOST_COVERED),
        )
    }

    private fun coordinator(
        operationId: UUID = UUID(0L, 1L),
        scheduler: FakeDeadlineScheduler = FakeDeadlineScheduler(),
    ) = CompanionRecentsCoordinator(
        elapsedRealtimeMs = { 1_000L },
        operationIdFactory = { operationId },
        deadlineScheduler = scheduler,
    )

    private fun entries() = listOf(CompanionAppEntry("org.example.game", "Example"))

    private fun mirrorSnapshot(): CompanionRuntimeSnapshot {
        val root = OperationId.create()
        val attempt = RenderAttempt(OperationId.create(), root, RendererOwnerId.create(), displayId = 4)
        return CompanionRuntimeSnapshot(
            stateVersion = 3L,
            displayId = 4,
            disposition = SecondaryDisplayDisposition.MirrorPresented(attempt),
            rendererOwnerId = attempt.rendererOwnerId,
            mirrorActive = true,
            launchLockOperationId = null,
            launchSettlement = null,
        )
    }

    private fun snapshotFor(disposition: SecondaryDisplayDisposition) = CompanionRuntimeSnapshot(
        stateVersion = 1L,
        displayId = 4,
        disposition = disposition,
        rendererOwnerId = null,
        mirrorActive = disposition is SecondaryDisplayDisposition.MirrorPresented,
        launchLockOperationId = null,
        launchSettlement = null,
    )

    private fun renderAttempt() =
        RenderAttempt(OperationId.create(), OperationId.create(), RendererOwnerId.create(), displayId = 4)

    private fun hostLease() = HostLease(
        operationId = OperationId.create(),
        packageName = "org.example.game",
        displayId = 4,
        launchMode = "secondary_app",
        primaryOrigin = PrimaryReturnOrigin.LegacyCurrent,
    )

    private fun reclaimLease() = ReclaimLease(
        operationId = OperationId.create(),
        parentHostOperationId = OperationId.create(),
        displayId = 4,
        primaryOrigin = PrimaryReturnOrigin.LegacyCurrent,
        reason = "test recovery",
    )

    private fun assertLaunchStillPending(
        coordinator: CompanionRecentsCoordinator,
        operationId: UUID,
        deadlineElapsedRealtimeMs: Long,
    ) {
        val pending = coordinator.ui.value.launchState as CompanionDrawerLaunchState.LaunchPending
        assertEquals(operationId, pending.operationId)
        assertEquals(deadlineElapsedRealtimeMs, pending.deadlineElapsedRealtimeMs)
        assertTrue(coordinator.ui.value.launchLocked)
    }

    private fun leaseId(value: Long) = PanelLeaseId(UUID(0L, value))

    private class FakeDeadlineScheduler : LaunchDeadlineScheduler {
        data class Task(val delayMs: Long, val action: () -> Unit, var cancelled: Boolean = false)

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): LaunchDeadlineHandle {
            val task = Task(delayMs, action)
            tasks += task
            return LaunchDeadlineHandle { task.cancelled = true }
        }

        fun runAll() {
            tasks.filterNot(Task::cancelled).forEach { it.action() }
        }
    }
}
