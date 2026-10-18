package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchStatusLatestEventPolicyTest {
    private val confirmation = TweakerLaunchOverlayMessage.Confirmation(
        headline = "TWEAKER · PERFORMANCE · ACTIVE",
        supportingText = "READY FOR Pokemon Z-A.",
    )
    private val warning = TweakerLaunchOverlayMessage.Warning("Tweaker unavailable — launch continues without tuning")

    @Test
    fun `latest event wins and resets the display window`() {
        val policy = LaunchStatusLatestEventPolicy(displayDurationMs = 2_200L)
        val first = policy.present(eventId = 1L, message = confirmation, nowMs = 100L)
        val second = policy.present(eventId = 2L, message = warning, nowMs = 500L)

        assertTrue(policy.isCurrent(second))
        assertFalse(policy.isCurrent(first))
        assertEquals(2_700L, second.expiresAtMs)
        assertEquals(warning, policy.current(nowMs = 600L)?.message)
    }

    @Test
    fun `expired presentation is not current`() {
        val policy = LaunchStatusLatestEventPolicy(displayDurationMs = 2_200L)
        val presentation = policy.present(eventId = 1L, message = confirmation, nowMs = 0L)
        assertEquals(confirmation, policy.current(nowMs = 2_199L)?.message)
        assertNull(policy.current(nowMs = 2_200L))
    }
}
