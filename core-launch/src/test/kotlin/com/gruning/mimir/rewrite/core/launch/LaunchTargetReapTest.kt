package com.gruning.mimir.rewrite.core.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTargetReapTest {
    @Test
    fun `accepted privileged request suppresses the background-only fallback`() {
        val fallback = mutableListOf<String>()
        val result = performLaunchTargetReap(
            packageName = "org.example.emulator",
            privileged = { LaunchTargetReapResult.REQUEST_ACCEPTED },
            bestEffort = fallback::add,
        )

        assertEquals(LaunchTargetReapResult.REQUEST_ACCEPTED, result)
        assertTrue(fallback.isEmpty())
    }

    @Test
    fun `missing and failed authority preserve best-effort dispatch cleanup`() {
        listOf(
            LaunchTargetReapResult.AUTHORITY_UNAVAILABLE,
            LaunchTargetReapResult.FAILED,
        )
            .forEach { outcome ->
                val fallback = mutableListOf<String>()
                assertEquals(
                    outcome,
                    performLaunchTargetReap(
                        "org.example.emulator",
                        privileged = { outcome },
                        bestEffort = fallback::add,
                    ),
                )
                assertEquals(listOf("org.example.emulator"), fallback)
            }
    }

    @Test
    fun `explicit allowlist refusal performs no process control`() {
        val fallback = mutableListOf<String>()
        assertEquals(
            LaunchTargetReapResult.NOT_ALLOWED,
            performLaunchTargetReap(
                "org.example.unrelated",
                privileged = { LaunchTargetReapResult.NOT_ALLOWED },
                bestEffort = fallback::add,
            ),
        )
        assertTrue(fallback.isEmpty())
    }

    @Test
    fun `privileged exception cannot block fallback or later intent dispatch`() {
        val fallback = mutableListOf<String>()
        assertEquals(
            LaunchTargetReapResult.FAILED,
            performLaunchTargetReap(
                "org.example.emulator",
                privileged = { error("binder died") },
                bestEffort = fallback::add,
            ),
        )
        assertEquals(listOf("org.example.emulator"), fallback)
    }
}
