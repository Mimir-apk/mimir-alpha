package com.gruning.mimir.rewrite.core.audio

import android.media.AudioManager
import android.os.Build
import android.os.Handler
import java.util.concurrent.Executor

/**
 * Pure rules for "Pause during calls". A phone call or a VoIP call is announced by the audio MODE and not always by audio focus: when a
 * game is in front Mimir has let go of its focus request (the game exemption), so no focus loss ever reaches it. The mode is what a call
 * cannot hide.
 */
internal object MusicCallPolicy {
    /** How often the mode is read where the system cannot say when it changes (Android 11), and only while Your music is wanted. */
    const val ModePollMs = 2_000L

    /** A phone call, a VoIP call, or a phone that is ringing. [AudioManager.MODE_NORMAL] and everything else is not a call. */
    fun inCall(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE

    /** The mode is watched only while Your music is wanted (on and playable): otherwise there is no listener, no poll and no wakeup. */
    fun watches(musicWanted: Boolean): Boolean = musicWanted
}

/** What [MusicCallWatch] needs of the system: the mode, a push of its changes where the system has one, and a delayed task for where it has not. */
internal interface AudioModePort {
    /** The system tells when the mode changes (Android 12 and later). Otherwise the watch polls. */
    val pushes: Boolean

    fun mode(): Int

    fun listen(onMode: (Int) -> Unit)

    fun unlisten()

    fun postDelayed(delayMs: Long, task: Runnable)

    fun cancel(task: Runnable)
}

/**
 * Watches the audio mode while Your music is wanted and reports whether a call is up. [setWanted] starts or stops it: starting reads the
 * current mode at once (a call already up holds the music from the start), stopping removes the listener or the poll and reports "no
 * call" so a hold set by the watch does not outlive it. One thread: the control's handler.
 */
internal class MusicCallWatch(private val port: AudioModePort, private val onInCall: (Boolean) -> Unit) {
    private var watching = false

    private val poll = object : Runnable {
        override fun run() {
            if (!watching) return
            read()
            port.postDelayed(MusicCallPolicy.ModePollMs, this)
        }
    }

    fun setWanted(wanted: Boolean) {
        if (wanted == watching) return
        watching = wanted
        if (wanted) {
            if (port.pushes) {
                port.listen { mode -> if (watching) onInCall(MusicCallPolicy.inCall(mode)) }
                read()
            } else {
                port.postDelayed(MusicCallPolicy.ModePollMs, poll)
                read()
            }
        } else {
            if (port.pushes) port.unlisten() else port.cancel(poll)
            onInCall(false)
        }
    }

    private fun read() = onInCall(MusicCallPolicy.inCall(port.mode()))
}

/** The system's side of [AudioModePort]: callbacks and delayed tasks run on [worker], the control's handler. */
internal class AndroidAudioModePort(private val manager: AudioManager, private val worker: Handler) : AudioModePort {
    override val pushes: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The registered `AudioManager.OnModeChangedListener`; typed loosely so this class loads on Android 11, where that type does not exist. */
    private var registered: Any? = null

    override fun mode(): Int = runCatching { manager.mode }.getOrDefault(AudioManager.MODE_NORMAL)

    override fun listen(onMode: (Int) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && registered == null) registered = runCatching { add(onMode) }.getOrNull()
    }

    override fun unlisten() {
        val listener = registered ?: return
        registered = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { remove(listener) }
    }

    override fun postDelayed(delayMs: Long, task: Runnable) {
        worker.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        worker.removeCallbacks(task)
    }

    private fun add(onMode: (Int) -> Unit): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val listener = AudioManager.OnModeChangedListener { mode -> onMode(mode) }
        manager.addOnModeChangedListener(Executor { worker.post(it) }, listener)
        return listener
    }

    private fun remove(listener: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        manager.removeOnModeChangedListener(listener as AudioManager.OnModeChangedListener)
    }
}
