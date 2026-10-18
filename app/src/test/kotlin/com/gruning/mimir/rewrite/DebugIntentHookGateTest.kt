package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugIntentHookGateTest {

    @Test
    fun `release build cannot execute exported activity proof hooks`() {
        var calls = 0

        DebugIntentHookGate(debugBuild = false).run { calls += 1 }

        assertEquals(0, calls)
    }

    @Test
    fun `debug build preserves the adb proof harness`() {
        var calls = 0

        DebugIntentHookGate(debugBuild = true).run { calls += 1 }

        assertEquals(1, calls)
    }
}
