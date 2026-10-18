package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * The real UI sound engine — a preloaded [SoundPool], gated through [MimirAudioPolicy] on every
 * [play] call.
 *
 * Reached in production through [MimirUiSounds] (one process-wide instance), never constructed
 * per-call.
 *
 * Threading contract: [play] only offers a command to [commands]. SoundPool creation, loading,
 * callbacks, stop/play, and release all run on [audioThread]. This is required on RP5: its vendor
 * audio stack made SoundPool control calls synchronously block the caller in audioserver for up to
 * 101 ms. Keeping one worker preserves single-thread SoundPool ownership without putting those
 * Binder waits in a frame or input dispatch.
 *
 * Debug logging: every load and every [play] decision emits a `Log.d(TAG, …)` (TAG = `MimirUiAudio`)
 * so dispatch is provable over ADB (`logcat -s MimirUiAudio`) even though audibility is not. Cheap,
 * debug-level, and on an event-driven path.
 *
 * @param soundResources one `res/raw` sound id per [MimirSoundEvent]. An event missing from the map
 *   is silently skipped (safe no-op) — lets call sites wire ahead of an asset existing.
 */
class MimirAudio(
    context: Context,
    soundResources: Map<MimirSoundEvent, Int>,
) {
    private val appContext = context.applicationContext
    private val audioThread = HandlerThread(AUDIO_THREAD_NAME).apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val commands = MimirAudioCommandQueue(
        schedule = { task -> audioHandler.post(task) },
        consume = ::consume,
        onFailure = { error -> Log.e(TAG, "audio worker failure", error) },
    )

    // Worker-owned state. No field below is read or mutated by a UI caller.
    private var audioManager: AudioManager? = null
    private var pool: SoundPool? = null
    private val loadedSoundIds = mutableMapOf<MimirSoundEvent, Int>()
    private val pendingLoadIds = mutableSetOf<Int>()
    private var lastNavStreamId = 0

    /**
     * Per-event loudness reconciliation.
     * The shipped assets peak between −9.3 dB (ui_context) and −15.8 dB (ui_nav_tick) — a 6.5 dB spread.
     * The earlier pass left nav/toggle ~1–2 dB under the rest, leaving an audible step. Every event
     * now targets ONE −12 dB effective level — no per-surface character
     * carve-outs. Derived from PROVENANCE.md peaks: gain = 10^((target − peak)/20).
     */
    private val eventGain: Map<MimirSoundEvent, Float> = mapOf(
        MimirSoundEvent.NavFocusMove to 1.55f, // −15.8 → −12
        MimirSoundEvent.BackCancel to 1.00f, // −12.0 → −12
        MimirSoundEvent.ConfirmLaunch to 0.90f, // −11.1 → −12
        MimirSoundEvent.ContextSwitch to 0.73f, // −9.3 → −12
        MimirSoundEvent.PageTrigger to 0.73f, // shared ui_context asset (R-4)
        MimirSoundEvent.Error to 0.99f, // −11.9 → −12
        MimirSoundEvent.Toggle to 1.38f, // −14.8 → −12
    )

    init {
        audioHandler.post {
            runCatching { initializeSoundPool(soundResources) }
                .onFailure { error ->
                    Log.e(TAG, "SoundPool initialization failed", error)
                    commands.release()
                }
        }
    }

    private fun initializeSoundPool(soundResources: Map<MimirSoundEvent, Int>) {
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val initializedPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // on the RP5's fork the SONIFICATION usage routes to a
                    // stream that never reaches the speaker, while Thor played fine. UI cues ride
                    // USAGE_MEDIA and remain governed by Mimir's Media volume Utility row.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        pool = initializedPool
        initializedPool.setOnLoadCompleteListener { _, sampleId, status ->
            pendingLoadIds.remove(sampleId)
            if (status != 0) {
                loadedSoundIds.entries.removeAll { it.value == sampleId }
            }
            Log.d(TAG, "load complete sampleId=$sampleId status=$status pending=${pendingLoadIds.size}")
        }
        soundResources.forEach { (event, resId) ->
            val soundId = runCatching {
                initializedPool.load(appContext, resId, LOAD_PRIORITY)
            }.onFailure { error ->
                Log.e(TAG, "load failed event=$event resId=$resId", error)
            }.getOrDefault(0)
            if (soundId == 0) {
                Log.e(TAG, "load rejected event=$event resId=$resId")
                return@forEach
            }
            loadedSoundIds[event] = soundId
            pendingLoadIds += soundId
            Log.d(TAG, "load start event=$event resId=$resId soundId=$soundId")
        }
    }

    /** O(1) UI-thread handoff; policy and every Android audio call execute on [audioThread]. */
    fun play(event: MimirSoundEvent) {
        commands.offer(event)
    }

    private fun consume(command: MimirAudioCommand) {
        when (command) {
            is MimirAudioCommand.Play -> playOnAudioThread(command.event)
            MimirAudioCommand.Release -> releaseOnAudioThread()
        }
    }

    private fun playOnAudioThread(event: MimirSoundEvent) {
        val activePool = pool
        if (activePool == null) {
            Log.d(TAG, "play event=$event skipped=not-ready")
            return
        }
        val soundId = loadedSoundIds[event]
        if (soundId == null) {
            Log.d(TAG, "play event=$event skipped=no-sample")
            return
        }
        if (soundId in pendingLoadIds) {
            Log.d(TAG, "play event=$event skipped=still-loading")
            return
        }
        val decision = MimirAudioPolicy.decide(
            enabled = AudioPreferences.enabled.value,
            volume = AudioPreferences.volume.value,
            focus = MimirAudioFocus.state.value,
            callState = callInterruptionStateForMode(audioManager?.mode ?: AudioManager.MODE_NORMAL),
        )
        if (decision is PlayDecision.Play) {
            // BM03: navigation-class cues carry their own user level on top of the master UI-sound
            // volume so navigation sounds can be adjusted independently. Action/status cues
            // (confirm, toggle, error) stay on the master alone.
            val navScale = if (event in NavClassEvents) AudioPreferences.navVolume.value else 1f
            val volume = (decision.volume * (eventGain[event] ?: 1f) * navScale).coerceIn(0f, 1f)
            if (volume <= 0f) {
                Log.d(TAG, "play event=$event SUPPRESS (navVolume=0)")
                return
            }
            // BM02 : during bursts the nav tick is MONO — each move CUTS the previous
            // tick instead of stacking over it during burst movement, so a
            // hold-scroll reads as one crisp tick per move rather than an overlapping mush.
            if (event == MimirSoundEvent.NavFocusMove && lastNavStreamId != 0) {
                activePool.stop(lastNavStreamId)
            }
            val streamId =
                activePool.play(soundId, volume, volume, LOAD_PRIORITY, /* loop = */ 0, /* rate = */ 1f)
            if (event == MimirSoundEvent.NavFocusMove) lastNavStreamId = streamId
            Log.d(TAG, "play event=$event PLAY vol=$volume soundId=$soundId")
        } else {
            Log.d(TAG, "play event=$event SUPPRESS (enabled=${AudioPreferences.enabled.value})")
        }
    }

    /** Terminal asynchronous release; safe from any caller and idempotent through the command queue. */
    fun release() {
        commands.release()
    }

    private fun releaseOnAudioThread() {
        try {
            pool?.release()
            pool = null
            loadedSoundIds.clear()
            pendingLoadIds.clear()
            lastNavStreamId = 0
        } finally {
            audioThread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "MimirUiAudio"
        const val AUDIO_THREAD_NAME = "MimirUiAudio"
        const val MAX_STREAMS = 4
        const val LOAD_PRIORITY = 1

        /** the cues the "Navigation sounds" level governs — movement grammar, not actions. */
        val NavClassEvents = setOf(
            MimirSoundEvent.NavFocusMove,
            MimirSoundEvent.BackCancel,
            MimirSoundEvent.PageTrigger,
            MimirSoundEvent.ContextSwitch,
        )
    }
}
