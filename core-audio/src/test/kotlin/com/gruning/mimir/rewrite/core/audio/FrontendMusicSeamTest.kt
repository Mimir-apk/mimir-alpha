package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The explicit-start loop seam, pinned off-device.
 *
 * `MimirFrontendMusic` used to hand over with `MediaPlayer.setNextMediaPlayer`. That left a NuPlayer
 * renderer thread inside `mediaserver` spinning at ~600 kHz forever from the first seam onward —
 * roughly two-thirds of the CPU the device burned sitting idle. Commit `b32d976d` replaced it with
 * an explicit `start()` on the deck that was already prepared, plus a failure arm.
 *
 * Nothing exercised that seam. `MediaPlayerLoopSeekGapDeviceTest` measures `setLooping(true)` and
 * the old `setNextMediaPlayer` hand-over, but has no arm for the mechanism actually shipping, so the
 * ordering and the failure arms were carried on reading alone.
 *
 * **What this test can and cannot prove.** The size of the audible gap at the seam is wall-clock
 * behaviour of a real `MediaPlayer` and cannot be observed from the JVM; that is measured by
 * `MediaPlayerLoopSeekGapDeviceTest.explicit_start_handover_is_measured_on_the_same_probe` and
 * remains device-only. What IS provable here is everything that decides whether a seam happens at
 * all and in what order — which is where a silent bed comes from, and which no device run can
 * enumerate exhaustively.
 */
class FrontendMusicSeamTest {

    /** Every effect the seam performed, in order, with the start outcome forced by the test. */
    private class Recorder(private val startSucceeds: Boolean = true) : FrontendMusicSeamOperations {
        val steps = mutableListOf<String>()

        override fun declarePromoted() { steps += "declarePromoted" }
        override fun retireFinished() { steps += "retireFinished" }
        override fun startPromoted(): Boolean {
            steps += "startPromoted"
            return startSucceeds
        }
        override fun chainNextDeck() { steps += "chainNextDeck" }
        override fun noteSeam() { steps += "noteSeam" }
        override fun reportStartFailure() { steps += "reportStartFailure" }
        override fun discardPromoted() { steps += "discardPromoted" }
        override fun reenterFromSilence() { steps += "reenterFromSilence" }
    }

    private fun run(
        playing: Boolean = true,
        finishedIsCurrent: Boolean = true,
        hasUpcoming: Boolean = true,
        startSucceeds: Boolean = true,
    ): List<String> {
        val recorder = Recorder(startSucceeds)
        FrontendMusicSeam.handOver(playing, finishedIsCurrent, hasUpcoming, recorder)
        return recorder.steps
    }

    @Test
    fun `a healthy seam declares, retires, starts, then chains the next deck`() {
        assertEquals(
            listOf("declarePromoted", "retireFinished", "startPromoted", "chainNextDeck", "noteSeam"),
            run(),
        )
    }

    /**
     * The ordering is the defect this replaced, not decoration.
     *
     * [MimirMediaBed] correlates each token to a live playback configuration. Retiring the finished
     * deck before declaring the promoted one opens a window the courtesy gate reads as
     * `relevant=2 owned=1` — a stranger playing — and it blocks the bed on Mimir's own seam.
     */
    @Test
    fun `the promoted deck is declared before the finished one is retired`() {
        val steps = run()
        assertTrue(
            "declare must precede retire, got $steps",
            steps.indexOf("declarePromoted") < steps.indexOf("retireFinished"),
        )
    }

    /** A deck that is not yet on air must never be started before it is declared and current. */
    @Test
    fun `the promoted deck is started only after it is declared and current`() {
        val steps = run()
        assertTrue(
            "start must follow declare and retire, got $steps",
            steps.indexOf("startPromoted") > steps.indexOf("retireFinished"),
        )
    }

    /** The seam must always leave a prepared deck behind it, or the next wrap is a cold start. */
    @Test
    fun `a healthy seam prepares the deck for the seam after it`() {
        assertTrue("chainNextDeck" in run())
    }

    /**
     * The arm the replacement added, and the one most likely to rot: a refused start must be SAID,
     * not swallowed into a silent bed that looks like a missing asset.
     */
    @Test
    fun `a refused start is reported rather than swallowed`() {
        val steps = run(startSucceeds = false)
        assertTrue("a refused start must be reported, got $steps", "reportStartFailure" in steps)
        assertTrue(
            "the failure must be reported before the deck is discarded, got $steps",
            steps.indexOf("reportStartFailure") < steps.indexOf("discardPromoted"),
        )
    }

    @Test
    fun `a refused start discards the half-promoted deck and re-enters from silence`() {
        assertEquals(
            listOf(
                "declarePromoted",
                "retireFinished",
                "startPromoted",
                "reportStartFailure",
                "discardPromoted",
                "reenterFromSilence",
            ),
            run(startSucceeds = false),
        )
    }

    /** A refused start must not chain a deck onto a seam that did not happen. */
    @Test
    fun `a refused start neither chains nor notes a seam`() {
        val steps = run(startSucceeds = false)
        assertTrue("chainNextDeck" !in steps)
        assertTrue("noteSeam" !in steps)
    }

    /**
     * `chainNextDeck` can fail to prepare a deck, so the seam can arrive with nothing to promote.
     * That must fall back to a cold start rather than ending playback.
     */
    @Test
    fun `a seam with nothing prepared retires the finished deck and re-enters from silence`() {
        assertEquals(listOf("retireFinished", "reenterFromSilence"), run(hasUpcoming = false))
    }

    /**
     * **No path may leave both decks stopped with no way back.**
     *
     * This is the invariant that matters to a listener: whatever goes wrong at a seam, the engine
     * either ends up sounding or ends up asking for volume again. Silence with no re-entry is the
     * one outcome that is unrecoverable without restarting the process.
     */
    @Test
    fun `every seam either sounds or re-enters, never both decks stopped`() {
        val cases = listOf(
            "healthy" to run(),
            "start refused" to run(startSucceeds = false),
            "nothing prepared" to run(hasUpcoming = false),
            "nothing prepared and refused" to run(hasUpcoming = false, startSucceeds = false),
        )
        for ((label, steps) in cases) {
            val sounding = "chainNextDeck" in steps
            val recovering = "reenterFromSilence" in steps
            assertTrue(
                "$label left the bed stopped with no re-entry: $steps",
                sounding != recovering,
            )
        }
    }

    /**
     * A completion delivered after teardown is not our seam.
     *
     * `discardDeck` clears the listener, but a callback already posted to the worker can still land
     * after `playing` went false. Acting on it would start a deck the engine has stopped owning.
     */
    @Test
    fun `a completion after teardown does nothing`() {
        assertEquals(emptyList<String>(), run(playing = false))
        assertEquals(emptyList<String>(), run(playing = false, hasUpcoming = false))
    }

    /**
     * A late callback from a deck that was already retired must not promote a second time — that
     * would strand the deck currently on air and double-declare its replacement.
     */
    @Test
    fun `a completion from a deck that is no longer current does nothing`() {
        assertEquals(emptyList<String>(), run(finishedIsCurrent = false))
    }

    /** The seam never seeks: promotion is a start on a prepared deck, which is the whole fix. */
    @Test
    fun `the seam promotes a prepared deck rather than restarting the finished one`() {
        val steps = run()
        assertTrue(
            "the finished deck must be retired, never reused, got $steps",
            "retireFinished" in steps && "startPromoted" in steps,
        )
    }
}
