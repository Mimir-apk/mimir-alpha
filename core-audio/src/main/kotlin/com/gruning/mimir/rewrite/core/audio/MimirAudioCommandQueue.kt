package com.gruning.mimir.rewrite.core.audio

/**
 * Commands consumed by [MimirAudio]'s one audio worker.
 *
 * [Release] is terminal: pending cues are discarded and later offers are ignored.
 */
internal sealed interface MimirAudioCommand {
    data class Play(val event: MimirSoundEvent) : MimirAudioCommand
    data object Release : MimirAudioCommand
}

/**
 * Small thread-safe handoff between UI callers and the serial SoundPool worker.
 *
 * Action/status cues retain FIFO order. A pending navigation-focus cue is latest-wins: another
 * focus move removes that one stale marker and appends a fresh marker at the current logical point
 * in the queue. This prevents a controller hold from building delayed ticks while retaining the
 * ordering of non-navigation cues around the newest focus move.
 */
internal class MimirAudioCommandQueue(
    private val schedule: (() -> Unit) -> Boolean,
    private val consume: (MimirAudioCommand) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val commands = ArrayDeque<MimirAudioCommand>()
    private var drainScheduled = false
    private var closed = false

    fun offer(event: MimirSoundEvent) {
        val shouldSchedule = synchronized(lock) {
            if (closed) return
            if (event == MimirSoundEvent.NavFocusMove) {
                commands.remove(MimirAudioCommand.Play(MimirSoundEvent.NavFocusMove))
            }
            commands.addLast(MimirAudioCommand.Play(event))
            markDrainScheduled()
        }
        scheduleDrainIfNeeded(shouldSchedule)
    }

    fun release() {
        val shouldSchedule = synchronized(lock) {
            if (closed) return
            closed = true
            commands.clear()
            commands.addLast(MimirAudioCommand.Release)
            markDrainScheduled()
        }
        scheduleDrainIfNeeded(shouldSchedule)
    }

    private fun markDrainScheduled(): Boolean {
        if (drainScheduled) return false
        drainScheduled = true
        return true
    }

    private fun scheduleDrainIfNeeded(shouldSchedule: Boolean) {
        if (!shouldSchedule) return
        val accepted = runCatching { schedule(::drain) }
            .onFailure(onFailure)
            .getOrDefault(false)
        if (!accepted) {
            synchronized(lock) {
                commands.clear()
                drainScheduled = false
                closed = true
            }
        }
    }

    private fun drain() {
        while (true) {
            val command = synchronized(lock) {
                if (commands.isEmpty()) {
                    drainScheduled = false
                    return
                }
                commands.removeFirst()
            }
            runCatching { consume(command) }.onFailure(onFailure)
            if (command == MimirAudioCommand.Release) return
        }
    }
}
