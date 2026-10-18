package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 *  the "Reconnecting…" hold used to be cleared ONLY by the payload-push
 * effect inside MainActivity's composition — which is paused while a game covers the primary display, so
 * screen 2 held the hold forever. The hold must now expire on its own, in this process-scoped object the
 * always-visible Presentation reads directly.
 *
 * No `kotlinx-coroutines-test` in this module, so the deadline is driven for real on a tiny timeout.
 */
class CompanionStateHoldTest {

    private lateinit var scope: CoroutineScope

    private val stable = CompanionPayload(
        mode = CompanionMode.HOME,
        themeId = "midnight",
        statsLabel = "6 consoles",
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        CompanionState.holdScopeOverride = scope
        CompanionState.holdTimeoutMs = 60L
        CompanionState.dualScreenGameActive = false
        CompanionState.set(CompanionPayload.idle())
    }

    @After
    fun tearDown() {
        scope.cancel()
        CompanionState.holdScopeOverride = null
        CompanionState.holdTimeoutMs = 3_000L
    }

    @Test
    fun `the hold expires on its own and restores the last stable payload`() {
        CompanionState.set(stable)
        CompanionState.holdForReclaim()
        assertTrue("the hold must render immediately", CompanionState.payload.value.isLoading)

        awaitNotLoading()

        assertEquals(stable, CompanionState.payload.value)
    }

    @Test
    fun `a fresh push during the hold wins and is never reverted by the expiry`() {
        CompanionState.set(stable)
        CompanionState.holdForReclaim()

        val fresh = stable.copy(statsLabel = "7 consoles")
        CompanionState.set(fresh)

        Thread.sleep(CompanionState.holdTimeoutMs * 4)

        assertEquals("the expiry must not clobber a payload that actually landed", fresh, CompanionState.payload.value)
    }

    @Test
    fun `with no stable payload behind it the hold decays to idle, never to a stuck spinner`() {
        CompanionState.holdForReclaim()

        awaitNotLoading()

        assertEquals(CompanionMode.IDLE, CompanionState.payload.value.mode)
        assertTrue(!CompanionState.payload.value.isLoading)
    }

    private fun awaitNotLoading() {
        val deadline = System.currentTimeMillis() + 2_000L
        while (CompanionState.payload.value.isLoading && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("the hold outlived its deadline", !CompanionState.payload.value.isLoading)
    }
}
