package com.gruning.mimir.rewrite

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

class FinalShelfDestinationPolicyTest {
    @Test
    fun `disabled startup maps persisted malformed and launch-return Home requests to Library`() {
        val homeOrigin = PrimaryReturnOrigin(MimirDestination.HOME.name)

        assertEquals(
            MimirDestination.LIBRARY,
            resolveStartupDestination(homeOrigin, firstRun = false, finalShelfEnabled = false),
        )
        assertEquals(
            MimirDestination.LIBRARY,
            resolveStartupDestination(
                origin = PrimaryReturnOrigin(MimirDestination.SETTINGS.name),
                firstRun = false,
                homeLaunchReturnPending = true,
                finalShelfEnabled = false,
            ),
        )
        assertEquals(
            MimirDestination.LIBRARY,
            resolveStartupDestination(
                PrimaryReturnOrigin("removed-destination"),
                firstRun = false,
                finalShelfEnabled = false,
            ),
        )
    }

    @Test
    fun `disabled restored and runtime Home assignments are normalized at the state boundary`() {
        val restoredBacking = mutableStateOf(MimirDestination.HOME)
        val destination = FinalShelfDestinationState(restoredBacking, finalShelfEnabled = false)

        assertEquals(MimirDestination.LIBRARY, destination.value)

        destination.value = MimirDestination.HOME
        assertEquals(MimirDestination.LIBRARY, destination.value)
        assertEquals(MimirDestination.LIBRARY, restoredBacking.value)

        destination.value = MimirDestination.SETTINGS
        assertEquals(MimirDestination.SETTINGS, destination.value)
    }

    @Test
    fun `disabled policy preserves onboarding and all non-Home destinations`() {
        MimirDestination.entries
            .filterNot { it == MimirDestination.HOME || it == MimirDestination.DIAGNOSTICS }
            .forEach { requested ->
                assertEquals(requested, normalizeMimirDestination(requested, finalShelfEnabled = false))
            }
    }

    @Test
    fun `diagnostics is excluded from normal release navigation`() {
        assertEquals(
            MimirDestination.SETTINGS,
            normalizeMimirDestination(MimirDestination.DIAGNOSTICS, finalShelfEnabled = true),
        )
        val backing = mutableStateOf(MimirDestination.DIAGNOSTICS)
        assertEquals(
            MimirDestination.SETTINGS,
            FinalShelfDestinationState(backing, finalShelfEnabled = true).value,
        )
    }

    @Test
    fun `enabled policy preserves Home for debug behavior`() {
        assertEquals(
            MimirDestination.HOME,
            normalizeMimirDestination(MimirDestination.HOME, finalShelfEnabled = true),
        )
        assertEquals(
            MimirDestination.HOME,
            resolveStartupDestination(
                PrimaryReturnOrigin(MimirDestination.HOME.name),
                firstRun = false,
                finalShelfEnabled = true,
            ),
        )
    }
}
