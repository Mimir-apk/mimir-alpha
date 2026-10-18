package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirAudioCommandQueueTest {

    @Test
    fun offerSchedulesWorkWithoutConsumingOnCaller() {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<MimirAudioCommand>()
        val queue = queue(scheduled, consumed)

        queue.offer(MimirSoundEvent.ConfirmLaunch)

        assertTrue(consumed.isEmpty())
        assertEquals(1, scheduled.size)

        scheduled.removeFirst().invoke()

        assertEquals(
            listOf(MimirAudioCommand.Play(MimirSoundEvent.ConfirmLaunch)),
            consumed,
        )
    }

    @Test
    fun pendingNavMovesConflateToLatestQueuePosition() {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<MimirAudioCommand>()
        val queue = queue(scheduled, consumed)

        queue.offer(MimirSoundEvent.NavFocusMove)
        queue.offer(MimirSoundEvent.ConfirmLaunch)
        queue.offer(MimirSoundEvent.NavFocusMove)
        queue.offer(MimirSoundEvent.Toggle)
        queue.offer(MimirSoundEvent.NavFocusMove)

        assertEquals(1, scheduled.size)
        scheduled.removeFirst().invoke()

        assertEquals(
            listOf(
                MimirAudioCommand.Play(MimirSoundEvent.ConfirmLaunch),
                MimirAudioCommand.Play(MimirSoundEvent.Toggle),
                MimirAudioCommand.Play(MimirSoundEvent.NavFocusMove),
            ),
            consumed,
        )
    }

    @Test
    fun navArrivingDuringBlockedConsumptionLeavesOnlyOneFollowUpTick() {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<MimirAudioCommand>()
        lateinit var queue: MimirAudioCommandQueue
        queue = MimirAudioCommandQueue(
            schedule = { task ->
                scheduled.addLast(task)
                true
            },
            consume = { command ->
                consumed += command
                if (consumed.size == 1) {
                    repeat(20) { queue.offer(MimirSoundEvent.NavFocusMove) }
                }
            },
        )

        queue.offer(MimirSoundEvent.NavFocusMove)
        scheduled.removeFirst().invoke()

        assertEquals(
            listOf(
                MimirAudioCommand.Play(MimirSoundEvent.NavFocusMove),
                MimirAudioCommand.Play(MimirSoundEvent.NavFocusMove),
            ),
            consumed,
        )
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun nonNavCommandsRemainFifo() {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<MimirAudioCommand>()
        val queue = queue(scheduled, consumed)

        queue.offer(MimirSoundEvent.BackCancel)
        queue.offer(MimirSoundEvent.Error)
        queue.offer(MimirSoundEvent.PageTrigger)
        scheduled.removeFirst().invoke()

        assertEquals(
            listOf(
                MimirAudioCommand.Play(MimirSoundEvent.BackCancel),
                MimirAudioCommand.Play(MimirSoundEvent.Error),
                MimirAudioCommand.Play(MimirSoundEvent.PageTrigger),
            ),
            consumed,
        )
    }

    @Test
    fun releaseDiscardsPendingCuesAndRejectsLaterOffers() {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<MimirAudioCommand>()
        val queue = queue(scheduled, consumed)

        queue.offer(MimirSoundEvent.ConfirmLaunch)
        queue.release()
        queue.offer(MimirSoundEvent.Error)
        scheduled.removeFirst().invoke()

        assertEquals(listOf(MimirAudioCommand.Release), consumed)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun consumerFailureDoesNotStrandFollowingCommand() {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<MimirAudioCommand>()
        val failures = mutableListOf<Throwable>()
        val queue = MimirAudioCommandQueue(
            schedule = { task ->
                scheduled.addLast(task)
                true
            },
            consume = { command ->
                consumed += command
                if (command == MimirAudioCommand.Play(MimirSoundEvent.Error)) {
                    error("synthetic audio failure")
                }
            },
            onFailure = failures::add,
        )

        queue.offer(MimirSoundEvent.Error)
        queue.offer(MimirSoundEvent.Toggle)
        scheduled.removeFirst().invoke()

        assertEquals(2, consumed.size)
        assertEquals(1, failures.size)
    }

    @Test
    fun rejectedSchedulerClosesQueueWithoutInlinePlayback() {
        val consumed = mutableListOf<MimirAudioCommand>()
        val queue = MimirAudioCommandQueue(
            schedule = { false },
            consume = consumed::add,
        )

        queue.offer(MimirSoundEvent.ConfirmLaunch)
        queue.offer(MimirSoundEvent.Error)

        assertTrue(consumed.isEmpty())
        assertFalse(consumed.contains(MimirAudioCommand.Release))
    }

    private fun queue(
        scheduled: ArrayDeque<() -> Unit>,
        consumed: MutableList<MimirAudioCommand>,
    ) = MimirAudioCommandQueue(
        schedule = { task ->
            scheduled.addLast(task)
            true
        },
        consume = consumed::add,
    )
}
