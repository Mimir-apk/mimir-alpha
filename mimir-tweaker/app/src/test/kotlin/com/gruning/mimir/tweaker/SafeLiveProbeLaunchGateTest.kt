package com.gruning.mimir.tweaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLiveProbeLaunchGateTest {

    @Test
    fun `opens only for debug SAFE no-write triple`() {
        assertTrue(SafeLiveProbeLaunchGate.allows(true, TweakerPostureTruth.SAFE, false))
    }

    @Test
    fun `rejects each missing admission condition`() {
        assertFalse(SafeLiveProbeLaunchGate.allows(false, TweakerPostureTruth.SAFE, false))
        assertFalse(SafeLiveProbeLaunchGate.allows(true, TweakerPostureTruth.LAB, false))
        assertFalse(SafeLiveProbeLaunchGate.allows(true, TweakerPostureTruth.SAFE, true))
    }
}
