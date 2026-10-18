package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The kinds of place media sound goes to, as the Equalizer's header names them. */
enum class MediaOutputKind { SPEAKER, WIRED_HEADPHONES, USB_AUDIO, BLUETOOTH, HDMI, OTHER }

/**
 * Where media sound goes now. [name] is the device's own name and is kept for Bluetooth only: a speaker, a jack and a cable have no
 * name worth printing, and a vendor's product string for them is not the player's to read.
 */
data class MediaOutput(val kind: MediaOutputKind, val name: String? = null)

/** One output the platform lists: its [AudioDeviceInfo] type and product name. Plain values, so the choice among them is a JVM test. */
data class OutputDeviceInfo(val type: Int, val productName: String?)

/**
 * The mapping from what the platform lists to the one place media sound goes, and to the words for it. Pure.
 *
 * On API 33 and above the platform itself says which device(s) a media stream is routed to (`getAudioDevicesForAttributes`), so the list is
 * already the answer and [choose] only picks the first that is a sink for media. Below 33 it can only list every connected output, so
 * [choose] ranks them: Bluetooth, then wired, then USB, then HDMI, then the speaker. That ranking is a best guess, not the platform's own
 * routing (which follows the most recently connected removable device), and it is the reason the live callback re-reads on every change.
 */
object MediaOutputText {
    /** The kind for an [AudioDeviceInfo] `TYPE_*`, or null for a type that is not a place media goes (the earpiece, a phone line). */
    fun kindOf(type: Int): MediaOutputKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        -> MediaOutputKind.SPEAKER

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> MediaOutputKind.WIRED_HEADPHONES

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> MediaOutputKind.USB_AUDIO

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> MediaOutputKind.BLUETOOTH

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        -> MediaOutputKind.HDMI

        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_TELEPHONY,
        -> null

        else -> MediaOutputKind.OTHER
    }

    /** The one output media goes to among [devices] (see the object's note for the order), or null when none is a media sink. */
    fun choose(devices: List<OutputDeviceInfo>, ranked: Boolean = false): MediaOutput? {
        val sinks = devices.mapNotNull { device -> kindOf(device.type)?.let { it to device } }
        val (kind, device) = (if (ranked) sinks.minByOrNull { (kind, _) -> kind.rank } else sinks.firstOrNull()) ?: return null
        val name = device.productName?.trim()?.takeIf { it.isNotEmpty() && kind == MediaOutputKind.BLUETOOTH }
        return MediaOutput(kind, name)
    }

    private val MediaOutputKind.rank: Int
        get() = when (this) {
            MediaOutputKind.BLUETOOTH -> 0
            MediaOutputKind.WIRED_HEADPHONES -> 1
            MediaOutputKind.USB_AUDIO -> 2
            MediaOutputKind.HDMI -> 3
            MediaOutputKind.SPEAKER -> 4
            MediaOutputKind.OTHER -> 5
        }

    /** "Speaker", "Wired headphones", "USB audio", "Bluetooth · WH-1000XM5" (just "Bluetooth" with no name), "HDMI", "Other output"; null when nothing is known. */
    fun label(output: MediaOutput?): String? = when (output?.kind) {
        null -> null
        MediaOutputKind.SPEAKER -> "Speaker"
        MediaOutputKind.WIRED_HEADPHONES -> "Wired headphones"
        MediaOutputKind.USB_AUDIO -> "USB audio"
        MediaOutputKind.BLUETOOTH -> output.name?.let { "Bluetooth · $it" } ?: "Bluetooth"
        MediaOutputKind.HDMI -> "HDMI"
        MediaOutputKind.OTHER -> "Other output"
    }
}

/**
 * Watches where media sound goes, live, for as long as it is started. Started and stopped by whatever shows it (the Equalizer editor):
 * nothing runs, and no callback is held, when no one is looking.
 *
 * It reads once on [start] and again on every device the platform adds or removes ([AudioDeviceCallback]); a change of route that adds
 * or removes nothing (a player picking another output) is not announced to apps, so the line may lag it until the next device event or
 * the next time the editor opens.
 */
class MediaOutputWatcher(context: Context) {
    private val audioManager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
    private val _output = MutableStateFlow<MediaOutput?>(null)

    /** Where media sound goes now; null until read, and when the platform cannot say. */
    val output: StateFlow<MediaOutput?> = _output.asStateFlow()

    private var registered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    /** Reads the output now and follows it (idempotent). Call on any thread; the callback arrives on the main looper. */
    fun start() {
        val manager = audioManager ?: return
        refresh()
        if (registered) return
        registered = runCatching { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
            .onFailure { Log.w(Tag, "could not watch the audio devices", it) }
            .isSuccess
    }

    fun stop() {
        val manager = audioManager ?: return
        if (!registered) return
        registered = false
        runCatching { manager.unregisterAudioDeviceCallback(callback) }
    }

    private fun refresh() {
        _output.value = read()
    }

    private fun read(): MediaOutput? {
        val manager = audioManager ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val routed = manager.getAudioDevicesForAttributes(MediaAttributes)
                MediaOutputText.choose(routed.map(::info))
            } else {
                MediaOutputText.choose(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::info), ranked = true)
            }
        }.onFailure { Log.w(Tag, "could not read the media output", it) }.getOrNull()
    }

    private fun info(device: AudioDeviceInfo) = OutputDeviceInfo(device.type, device.productName?.toString())

    private companion object {
        const val Tag = "MimirMediaOutput"

        /** What a music app or a game plays as: the usage the platform routes by. */
        val MediaAttributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    }
}
