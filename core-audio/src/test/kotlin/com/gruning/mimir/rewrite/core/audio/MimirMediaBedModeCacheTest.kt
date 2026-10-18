package com.gruning.mimir.rewrite.core.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `MimirMediaBed` made the blocking `AudioManager.getMode()` binder call on the main thread every time it published the environment. Where the platform
 * reports mode changes (Android 12+) the mode is kept and read for free; where it does not (Android 11) the live read stays, so no behaviour moves.
 */
class MimirMediaBedModeCacheTest {
    @Test
    fun aListenerBackedCacheNeverMakesTheBlockingRead() {
        val cache = MimirMediaBedModeCache()
        cache.seed(AudioManager.MODE_NORMAL, listenerBacked = true)
        var liveReads = 0
        val live = { liveReads += 1; AudioManager.MODE_IN_CALL }

        assertEquals(AudioManager.MODE_NORMAL, cache.current(live))
        assertEquals(AudioManager.MODE_NORMAL, cache.current(live))
        assertEquals("the blocking getMode() is not made", 0, liveReads)
    }

    @Test
    fun aReportedChangeIsWhatTheNextSampleReads() {
        val cache = MimirMediaBedModeCache()
        cache.seed(AudioManager.MODE_NORMAL, listenerBacked = true)

        cache.onModeChanged(AudioManager.MODE_IN_CALL)
        assertEquals(AudioManager.MODE_IN_CALL, cache.current { error("no live read") })
        assertEquals(
            "a call interrupts",
            CallInterruptionState.Interrupted,
            callInterruptionStateForMode(cache.current { error("no live read") }),
        )

        cache.onModeChanged(AudioManager.MODE_NORMAL)
        assertEquals(AudioManager.MODE_NORMAL, cache.current { error("no live read") })
    }

    @Test
    fun theSeedIsTheModeTheCacheStartsFrom() {
        val cache = MimirMediaBedModeCache()
        cache.seed(AudioManager.MODE_RINGTONE, listenerBacked = true)
        assertEquals(AudioManager.MODE_RINGTONE, cache.current { error("no live read") })
    }

    @Test
    fun withoutAListenerEverySampleStillReadsTheLiveMode() {
        val cache = MimirMediaBedModeCache()
        cache.seed(AudioManager.MODE_NORMAL, listenerBacked = false)
        var mode = AudioManager.MODE_NORMAL
        val live = { mode }

        assertEquals(AudioManager.MODE_NORMAL, cache.current(live))
        mode = AudioManager.MODE_IN_COMMUNICATION
        assertEquals("the live mode, as before the cache", AudioManager.MODE_IN_COMMUNICATION, cache.current(live))
    }

    @Test
    fun anUnseededCacheReadsLiveSoNothingIsAssumed() {
        val cache = MimirMediaBedModeCache()
        assertEquals(AudioManager.MODE_IN_CALL, cache.current { AudioManager.MODE_IN_CALL })
    }
}
