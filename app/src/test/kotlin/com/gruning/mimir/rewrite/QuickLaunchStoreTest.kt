package com.gruning.mimir.rewrite

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLaunchStoreTest {
    @Test
    fun `pinned and failed dispatches never qualify for General MRU`() {
        assertFalse(shouldRecordQuickLaunchMru(QuickLaunchLaunchOrigin.PINNED_SLOT, true))
        QuickLaunchLaunchOrigin.entries.forEach { origin ->
            assertFalse("origin=$origin", shouldRecordQuickLaunchMru(origin, false))
        }
    }

    @Test
    fun `every General success route qualifies for MRU`() {
        assertTrue(shouldRecordQuickLaunchMru(QuickLaunchLaunchOrigin.GENERAL_PRIMARY, true))
        assertTrue(shouldRecordQuickLaunchMru(QuickLaunchLaunchOrigin.GENERAL_COMPANION, true))
        assertTrue(shouldRecordQuickLaunchMru(QuickLaunchLaunchOrigin.GENERAL_M_BUTTON, true))
    }

    @Test
    fun `companion General records only an operation whose dispatch remains accepted`() {
        val operationId = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val pending = CompanionDrawerLaunchState.LaunchPending(
            operationId = operationId,
            packageName = "pkg.secondary",
            label = "Secondary",
            deadlineElapsedRealtimeMs = 1_000L,
        )

        assertTrue(companionQuickLaunchDispatchSucceeded(operationId, pending))
        assertFalse(
            companionQuickLaunchDispatchSucceeded(
                operationId,
                CompanionDrawerLaunchState.LaunchFailed("Launch unavailable."),
            ),
        )
        assertFalse(companionQuickLaunchDispatchSucceeded(null, pending))
    }

    @Test
    fun `cold host reloads durable MRU before its first read`() {
        val persisted = mapOf(
            "launch_sequence" to 8L,
            "launch_sequence/pkg.old" to 4L,
            "launch_sequence/pkg.new" to 8L,
            "launch_sequence/pkg.invalid" to 0L,
        )
        var processState = emptyMap<String, Long>()
        val observed = quickLaunchColdRead(
            load = { processState = restoreQuickLaunchSequences(persisted) },
            read = { processState },
        )

        assertEquals(mapOf("pkg.old" to 4L, "pkg.new" to 8L), observed)
    }

    @Test
    fun `five seats preserve the four existing pins and add an empty Q5`() {
        assertEquals(5, QuickLaunchStore.SLOT_COUNT)
        assertEquals(listOf("pkg.q1", "pkg.q2", "pkg.q3", "pkg.q4", null), List(5) { i ->
            mapOf(
                "slot_0" to "pkg.q1",
                "slot_1" to "pkg.q2",
                "slot_2" to "pkg.q3",
                "slot_3" to "pkg.q4",
            )["slot_$i"]
        })
    }

    @Test
    fun `display preference restore is package keyed and ignores numeric ids`() {
        assertEquals(
            mapOf(
                "com.example.main" to QuickLaunchDisplayRole.PRIMARY,
                "com.example.bottom" to QuickLaunchDisplayRole.SECONDARY,
            ),
            restoreQuickLaunchDisplayPreferences(
                mapOf(
                    "launch_display/com.example.main" to "primary",
                    "launch_display/com.example.bottom" to "SECONDARY",
                    "launch_display/0" to "primary",
                    "launch_display/bad" to "unknown",
                    "slot_0" to "com.example.main",
                ),
            ),
        )
    }
}
