package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.PreDispatchOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTweakerNativeDispatchTest {
    @Test
    fun `started native game activates only after completed preparation`() = runBlocking {
        val calls = mutableListOf("dispatch")

        settleHomeTweakerAfterNativeDispatch(
            started = true,
            preDispatch = PreDispatchOutcome.Completed,
            activate = { calls += "activate" },
            presentDegraded = { calls += "degraded" },
            abandon = { calls += "abandon" },
        )

        assertEquals(listOf("dispatch", "activate"), calls)
    }

    @Test
    fun `started native game retires degraded preparation without activation`() = runBlocking {
        val calls = mutableListOf("dispatch")

        settleHomeTweakerAfterNativeDispatch(
            started = true,
            preDispatch = PreDispatchOutcome.TimedOut(9_500L),
            activate = { calls += "must-not-activate" },
            presentDegraded = { calls += "degraded" },
            abandon = { calls += "abandon" },
        )

        assertEquals(listOf("dispatch", "degraded"), calls)
    }

    @Test
    fun `failed native dispatch abandons completed preparation`() = runBlocking {
        val calls = mutableListOf("dispatch-failed")

        settleHomeTweakerAfterNativeDispatch(
            started = false,
            preDispatch = PreDispatchOutcome.Completed,
            activate = { calls += "must-not-activate" },
            presentDegraded = { calls += "degraded" },
            abandon = { calls += "abandon" },
        )

        assertEquals(listOf("dispatch-failed", "abandon"), calls)
    }
}
