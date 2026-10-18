package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.AthenaContainerIdentityPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityContainerCapabilityLatchTest {

    @Test
    fun `matching capability stamp is a no-op`() = runBlocking {
        val events = mutableListOf<String>()

        val result = reconcileIdentityContainerCapability(
            storedStamp = AthenaContainerIdentityPolicy.STAMP,
            currentStamp = AthenaContainerIdentityPolicy.STAMP,
            resetContainerIdentity = { events += "reset"; 1 },
            persistStamp = { events += "persist"; true },
        )

        assertFalse(result.changed)
        assertEquals(0, result.reopened)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `container reset and durable enqueue finish before capability stamp`() = runBlocking {
        val events = mutableListOf<String>()

        val result = reconcileIdentityContainerCapability(
            storedStamp = "old",
            currentStamp = AthenaContainerIdentityPolicy.STAMP,
            resetContainerIdentity = {
                resetContainerIdentityForCapabilityChange(
                    clearContainerIdentity = { events += "clear"; 9 },
                    enqueueTrailingPass = { events += "enqueue-await" },
                )
            },
            persistStamp = { stamp -> events += "persist:$stamp"; true },
        )

        assertTrue(result.changed)
        assertEquals(9, result.reopened)
        assertEquals(
            listOf(
                "clear",
                "enqueue-await",
                "persist:${AthenaContainerIdentityPolicy.STAMP}",
            ),
            events,
        )
    }

    @Test
    fun `reset receives the superseded stamp so skipped-version replay can take the full closure`() = runBlocking {
        var receivedStoredStamp: String? = "unset"

        val result = reconcileIdentityContainerCapability(
            storedStamp = AthenaContainerIdentityPolicy.PREVIOUS_A1C_STAMP,
            currentStamp = AthenaContainerIdentityPolicy.STAMP,
            resetContainerIdentity = { storedStamp ->
                receivedStoredStamp = storedStamp
                3
            },
            persistStamp = { true },
        )

        assertTrue(result.changed)
        assertEquals(3, result.reopened)
        assertEquals(AthenaContainerIdentityPolicy.PREVIOUS_A1C_STAMP, receivedStoredStamp)
        assertEquals(
            ContainerCapabilityReplay.FULL,
            containerCapabilityReplayKind(AthenaContainerIdentityPolicy.PREVIOUS_A1C_STAMP),
        )
    }

    @Test
    fun `no skipped-version stamp replays only CHD`() {
        assertFalse(containerCapabilityReplayIsNarrowChdReopen(null))
        assertFalse(containerCapabilityReplayIsNarrowChdReopen("athena-container-v2:legacy"))
        assertFalse(
            containerCapabilityReplayIsNarrowChdReopen(
                AthenaContainerIdentityPolicy.PREVIOUS_A1C_STAMP,
            ),
        )
        assertFalse(containerCapabilityReplayIsNarrowChdReopen(AthenaContainerIdentityPolicy.STAMP))
        assertFalse(
            containerCapabilityReplayIsNarrowChdReopen(
                AthenaContainerIdentityPolicy.PREVIOUS_A1C_STAMP + ":tampered",
            ),
        )
    }

    @Test
    fun `exact superseded A1D stamp replays terminal ZIP attempts only`() {
        assertEquals(
            ContainerCapabilityReplay.NARROW_ZIP,
            containerCapabilityReplayKind(AthenaContainerIdentityPolicy.PREVIOUS_A1D_STAMP),
        )
        assertEquals(
            ContainerCapabilityReplay.FULL,
            containerCapabilityReplayKind(AthenaContainerIdentityPolicy.PREVIOUS_A1C_STAMP),
        )
        assertEquals(
            ContainerCapabilityReplay.FULL,
            containerCapabilityReplayKind(AthenaContainerIdentityPolicy.STAMP),
        )
        assertEquals(
            ContainerCapabilityReplay.FULL,
            containerCapabilityReplayKind(
                AthenaContainerIdentityPolicy.PREVIOUS_A1D_STAMP + ":tampered",
            ),
        )
    }

    @Test
    fun `full replay composes quarantine then terminal ZIP reopen and counts both`() = runBlocking {
        val events = mutableListOf<String>()

        val reopened = executeFullContainerCapabilityReplay(
            quarantineA1C = {
                events += "quarantine-a1c"
                4
            },
            reopenTerminalZip = {
                events += "reopen-terminal-zip"
                3
            },
        )

        assertEquals(7, reopened)
        assertEquals(listOf("quarantine-a1c", "reopen-terminal-zip"), events)
    }

    @Test
    fun `enqueue or persistence failure keeps the old capability retryable`() = runBlocking {
        var persisted = false
        val enqueueFailure = runCatching {
            reconcileIdentityContainerCapability(
                storedStamp = null,
                currentStamp = AthenaContainerIdentityPolicy.STAMP,
                resetContainerIdentity = {
                    resetContainerIdentityForCapabilityChange(
                        clearContainerIdentity = { 2 },
                        enqueueTrailingPass = { error("work database unavailable") },
                    )
                },
                persistStamp = { persisted = true; true },
            )
        }.exceptionOrNull()
        val persistenceFailure = runCatching {
            reconcileIdentityContainerCapability(
                storedStamp = null,
                currentStamp = AthenaContainerIdentityPolicy.STAMP,
                resetContainerIdentity = { 2 },
                persistStamp = { false },
            )
        }.exceptionOrNull()

        assertTrue(enqueueFailure is IllegalStateException)
        assertFalse(persisted)
        assertTrue(persistenceFailure is IllegalStateException)
    }
}
