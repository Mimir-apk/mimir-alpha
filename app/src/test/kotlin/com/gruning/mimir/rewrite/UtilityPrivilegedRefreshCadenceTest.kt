package com.gruning.mimir.rewrite

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilityPrivilegedRefreshCadenceTest {

    @Test
    fun `utility polls only foreground-converging states and keeps ready heartbeat sparse`() {
        assertEquals(
            UtilityBindingRefreshMillis,
            utilityPrivilegedRefreshIntervalMillis(PrivilegedUtilityStatus.BINDING),
        )
        assertEquals(
            UtilityAccessRefreshMillis,
            utilityPrivilegedRefreshIntervalMillis(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
        )
        assertEquals(
            UtilityAccessRefreshMillis,
            utilityPrivilegedRefreshIntervalMillis(PrivilegedUtilityStatus.CONNECTION_FAILED),
        )
        assertEquals(
            UtilityReadyHeartbeatMillis,
            utilityPrivilegedRefreshIntervalMillis(PrivilegedUtilityStatus.READY),
        )
        assertTrue(UtilityReadyHeartbeatMillis >= UtilityBindingRefreshMillis * 30)

        val resumeOrTerminalStates = PrivilegedUtilityStatus.entries - setOf(
            PrivilegedUtilityStatus.BINDING,
            PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            PrivilegedUtilityStatus.READY,
        )
        resumeOrTerminalStates.forEach { status ->
            assertNull(status.name, utilityPrivilegedRefreshIntervalMillis(status))
        }
    }

    @Test
    fun `closed utility alone owns bounded binding recovery`() {
        assertFalse(
            shouldScheduleUtilityRecoveryHandshake(
                utilityOpen = true,
                status = PrivilegedUtilityStatus.BINDING,
            ),
        )
        assertTrue(
            shouldScheduleUtilityRecoveryHandshake(
                utilityOpen = false,
                status = PrivilegedUtilityStatus.BINDING,
            ),
        )
        assertFalse(
            shouldScheduleUtilityRecoveryHandshake(
                utilityOpen = false,
                status = PrivilegedUtilityStatus.READY,
            ),
        )
    }

    @Test
    fun `opening utility cancels an already active recovery owner`() {
        val recovery = Job()
        assertTrue(recovery.isActive)

        assertTrue(cancelUtilityRecoveryJob(recovery))
        assertTrue(recovery.isCancelled)
        assertFalse(cancelUtilityRecoveryJob(null))
    }
}
