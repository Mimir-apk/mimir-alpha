package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CompanionReturnCoordinatorGatewayTest {
    @Test
    fun `missing malformed and legacy untokened callbacks cannot settle current launch`() {
        val runtime = CompanionRuntimeCoordinator(NoOpScheduler)
        assertTrue(runtime.displayAvailable(4))
        val origin = PrimaryReturnOrigin(MimirDestination.LIBRARY.name, mapOf("focusKey" to "platform:gba"))
        CompanionReturnCoordinator.installRuntime(runtime)
        CompanionReturnCoordinator.installPrimaryOriginProvider { origin }
        try {
            val rawId = CompanionReturnCoordinator.noteHostLaunchStarting(
                packageName = "org.example.game",
                displayId = 4,
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
            )
            assertNotNull(rawId)

            assertNull(
                CompanionReturnCoordinator.resolveReturnOperation(
                    rawOperationId = null,
                    packageName = "org.example.game",
                    displayId = 4,
                    launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
                ),
            )
            assertNull(
                CompanionReturnCoordinator.resolveReturnOperation(
                    rawOperationId = "not-a-uuid",
                    packageName = "org.example.game",
                    displayId = 4,
                    launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
                ),
            )
            assertFalse(
                CompanionReturnCoordinator.noteHostStarted(
                    "org.example.game",
                    4,
                    CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
                ),
            )
            assertFalse(CompanionReturnCoordinator.launchFailed(null, "untokened"))

            val valid = CompanionReturnCoordinator.resolveReturnOperation(
                rawOperationId = rawId,
                packageName = "org.example.game",
                displayId = 4,
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
            )
            assertNotNull(valid)
            assertTrue(runtime.recognizes(valid!!.operationId))
        } finally {
            CompanionReturnCoordinator.resetTestOverrides()
        }
    }

    @Test
    fun `exact secondary launch failure revokes its return arm without clearing on-main state`() {
        val runtime = CompanionRuntimeCoordinator(NoOpScheduler)
        assertTrue(runtime.displayAvailable(4))
        val app = CompanionRunningApp("org.example.game", "Example")
        CompanionRunningAppState.set(app)
        CompanionReturnCoordinator.installRuntime(runtime)
        try {
            val rawId = requireNotNull(
                CompanionReturnCoordinator.noteHostLaunchStarting(
                    packageName = app.packageName,
                    displayId = 4,
                    launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
                ),
            )

            assertTrue(CompanionReturnCoordinator.launchFailed(rawId, "dispatch rejected"))
            assertFalse(confirmCompanionRunningAppSecondaryReturn(UUID.fromString(rawId), app.packageName))
            assertEquals(app, CompanionRunningAppState.running.value)
        } finally {
            resetCompanionRunningApp()
            CompanionReturnCoordinator.resetTestOverrides()
        }
    }

    @Test
    fun `P1 formatRuntimeLogEvent prints pkg mode reason and fail after the existing from-to shape`() {
        val root = OperationId.parseOrNull("00000000-0000-0000-0000-000000000001")
        val event = CompanionRuntimeLogEvent(
            stateVersion = 4L,
            event = "LaunchRequested",
            rootOperationId = root,
            renderOperationId = null,
            rendererOwnerId = null,
            from = "MirrorPresented",
            to = "LaunchPending",
            pkg = "example.a",
            mode = "secondary_app",
            reason = null,
            fail = null,
        )

        val line = CompanionReturnCoordinator.formatRuntimeLogEvent(event)

        assertEquals(
            "v=4 event=LaunchRequested root=00000000-0000-0000-0000-000000000001 render=null owner=null " +
                "MirrorPresented->LaunchPending pkg=example.a mode=secondary_app reason=null fail=null",
            line,
        )
    }

    @Test
    fun `P16 dispositionLogSummary carries the class name and lease identity, not the full toString`() {
        val lease = HostLease(
            operationId = OperationId.create(),
            packageName = "example.a",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
        )
        val disposition = SecondaryDisplayDisposition.LaunchPending(lease, LaunchPhase.HOST_REQUESTED)

        val summary = CompanionReturnCoordinator.dispositionLogSummary(disposition)

        assertEquals("LaunchPending package=example.a op=${lease.operationId}", summary)
    }

    private object NoOpScheduler : CompanionRuntimeScheduler {
        override fun schedule(
            delayMillis: Long,
            operationId: OperationId,
            action: () -> Unit,
        ): RuntimeCancellation = RuntimeCancellation {}
    }

    @Test
    fun `D2 a floor reclaim refused for want of the display observation is kept, and replayed only for that display`() {
        val origin = PrimaryReturnOrigin("HOME")
        val recovered = SecondaryDisplayDisposition.RecoveryNeeded(
            operationId = OperationId.create(),
            displayId = 4,
            fallback = origin,
            failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, "saved host"),
        )
        val unobserved = CompanionRuntimeSnapshot(
            stateVersion = 1L, displayId = 4, disposition = recovered, rendererOwnerId = null,
            mirrorActive = false, launchLockOperationId = null, launchSettlement = null, availableDisplayId = null,
        )
        assertTrue(floorReclaimDeferralApplies(unobserved, 4))
        assertFalse("a different display's recovery is not this floor's", floorReclaimDeferralApplies(unobserved, 7))
        assertFalse(
            "once observed, a refusal has another cause and must not be replayed",
            floorReclaimDeferralApplies(unobserved.copy(availableDisplayId = 4), 4),
        )
        val renderFailed = recovered.copy(failure = FailureReason(FailureCode.RENDER_FAILED, "x"))
        assertFalse(floorReclaimDeferralApplies(unobserved.copy(disposition = renderFailed), 4))

        assertTrue(floorReclaimReplayDue(deferredDisplay = 4, observedDisplay = 4, disposition = recovered))
        assertFalse(floorReclaimReplayDue(deferredDisplay = 4, observedDisplay = 7, disposition = recovered))
        assertFalse(floorReclaimReplayDue(deferredDisplay = null, observedDisplay = 4, disposition = recovered))
        assertFalse(
            "a recovery that already resolved (a manual swipe reclaimed) has nothing to replay",
            floorReclaimReplayDue(deferredDisplay = 4, observedDisplay = 4, disposition = SecondaryDisplayDisposition.NoDisplay),
        )
    }
}
