package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppSwitchingSetupTest {
    @Test
    fun bindingCopyStaysNeutralUntilTheAuthoritySettles() {
        assertEquals(
            "Checking Android app switching…",
            androidAppSwitchingSupportingCopy(PrivilegedUtilityStatus.BINDING),
        )
        assertEquals("Checking", androidAppSwitchingStateLabel(PrivilegedUtilityStatus.BINDING))
    }

    @Test
    fun builtInAuthorityUsesBuiltInCopy() {
        val permission = androidAppSwitchingSupportingCopy(
            status = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
        )
        val ready = androidAppSwitchingSupportingCopy(
            status = PrivilegedUtilityStatus.READY,
            authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
        )

        assertTrue(permission.contains("built-in"))
        assertTrue(ready.contains("built-in verified"))
        assertTrue(ready.contains("built-in verified"))
    }

    @Test
    fun explicitSetupRequestReportsReadyWhenGatewayConvergesImmediately() {
        val gateway = RecordingSystemRecentsGateway(
            requestAccepted = true,
            status = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            statusAfterAcceptedRequest = PrivilegedUtilityStatus.READY,
        )

        val result = requestAndroidAppSwitchingSetup(
            gateway,
            expectedStatus = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
        )

        assertEquals(AndroidAppSwitchingSetupFeedback.READY, result.feedback)
        assertTrue(result.accepted)
        assertEquals(1, gateway.requestCount)
        assertEquals(1, gateway.actionCount)
        assertEquals(1, gateway.statusReadCount)
        assertEquals(PrivilegedUtilityStatus.PERMISSION_REQUIRED, gateway.lastExpectedStatus)
    }

    @Test
    fun explicitSetupRequestReportsStartedWhileConsentFlowIsPending() {
        val gateway = RecordingSystemRecentsGateway(
            requestAccepted = true,
            status = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
        )

        val result = requestAndroidAppSwitchingSetup(
            gateway,
            expectedStatus = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
        )

        assertEquals(AndroidAppSwitchingSetupFeedback.STARTED, result.feedback)
        assertTrue(result.accepted)
        assertEquals(1, gateway.requestCount)
        assertEquals(1, gateway.actionCount)
        assertEquals(1, gateway.statusReadCount)
    }

    @Test
    fun unavailableGatewayCannotMasqueradeAsAStartedSetup() {
        val gateway = RecordingSystemRecentsGateway(
            requestAccepted = false,
            status = PrivilegedUtilityStatus.UNSUPPORTED_DEVICE,
        )

        val result = requestAndroidAppSwitchingSetup(
            gateway,
            expectedStatus = PrivilegedUtilityStatus.UNSUPPORTED_DEVICE,
        )

        assertEquals(AndroidAppSwitchingSetupFeedback.UNAVAILABLE, result.feedback)
        assertFalse(result.accepted)
        assertEquals(1, gateway.requestCount)
        assertEquals(1, gateway.actionCount)
        assertEquals(1, gateway.statusReadCount)
    }

    @Test
    fun staleRenderedStatusRefreshesWithoutStartingAConsentAction() {
        val gateway = RecordingSystemRecentsGateway(
            requestAccepted = true,
            status = PrivilegedUtilityStatus.SERVICE_STOPPED,
        )

        val result = requestAndroidAppSwitchingSetup(
            gateway,
            expectedStatus = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
        )

        assertEquals(AndroidAppSwitchingSetupFeedback.UPDATED, result.feedback)
        assertFalse(result.accepted)
        assertEquals(1, gateway.requestCount)
        assertEquals(0, gateway.actionCount)
        assertEquals(1, gateway.statusReadCount)
    }

    @Test
    fun staleRenderedStatusReportsReadyWithoutStartingAConsentActionWhenAlreadyConverged() {
        val gateway = RecordingSystemRecentsGateway(
            requestAccepted = true,
            status = PrivilegedUtilityStatus.READY,
        )

        val result = requestAndroidAppSwitchingSetup(
            gateway,
            expectedStatus = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
        )

        assertEquals(AndroidAppSwitchingSetupFeedback.READY, result.feedback)
        assertTrue(result.accepted)
        assertEquals(1, gateway.requestCount)
        assertEquals(0, gateway.actionCount)
        assertEquals(1, gateway.statusReadCount)
    }

    private class RecordingSystemRecentsGateway(
        private val requestAccepted: Boolean,
        private var status: PrivilegedUtilityStatus,
        private val statusAfterAcceptedRequest: PrivilegedUtilityStatus = status,
    ) : MButtonSystemRecentsGateway {
        var requestCount = 0
            private set
        var actionCount = 0
            private set
        var statusReadCount = 0
            private set
        var lastExpectedStatus: PrivilegedUtilityStatus? = null
            private set

        override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus {
            statusReadCount += 1
            return status
        }

        override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean {
            requestCount += 1
            lastExpectedStatus = expectedStatus
            if (status != expectedStatus) return false
            actionCount += 1
            if (requestAccepted) status = statusAfterAcceptedRequest
            return requestAccepted
        }

        override fun openSystemRecents(): Boolean = false
    }
}
