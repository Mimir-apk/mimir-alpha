package com.gruning.mimir.rewrite

import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How one streamed download of a game's clip ended. Only what the launch-sound step needs to decide with: a clip too big
 * to keep is a fact about THAT clip, while a refusal or a dead connection says nothing about it.
 */
internal sealed interface ClipDownload {
    /** The whole clip is in the target file. */
    data class Ok(val bytes: Long) : ClipDownload

    /** Declared or streamed past the cap. A fact about this clip: it will be just as big next time. */
    data object TooLarge : ClipDownload

    /**
     * Refused by the host gate, answered with anything but 200, empty, too slow, or cut off. Says nothing about the clip,
     * so it records no negative, and it counts toward the audio breaker.
     */
    data object Unavailable : ClipDownload
}

/** The longest one clip may take to arrive, however slowly the provider trickles it. */
internal const val MaxClipDownloadMillis = 60_000L

/**
 * Copies [input] into [target] and stops the moment more than [maxBytes] have arrived, without ever holding the body in
 * memory: a 12 MB clip goes to disk in 8 KB steps. A [TooLarge][ClipDownload.TooLarge] or
 * [Unavailable][ClipDownload.Unavailable] result deletes the partial [target], so a capped download leaves nothing behind.
 *
 * [nowNanos] is injectable so the deadline is testable without waiting.
 */
internal fun copyBoundedToFile(
    input: InputStream,
    maxBytes: Long,
    target: File,
    maxMillis: Long = MaxClipDownloadMillis,
    nowNanos: () -> Long = System::nanoTime,
): ClipDownload {
    if (maxBytes <= 0L) return ClipDownload.Unavailable
    val startedAt = nowNanos()
    val deadlineNanos = maxMillis * 1_000_000L
    var total = 0L
    var result: ClipDownload? = null
    FileOutputStream(target).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (result == null) {
            val read = input.read(buffer)
            when {
                read < 0 -> break
                read == 0 -> Unit
                total + read > maxBytes -> result = ClipDownload.TooLarge
                else -> {
                    output.write(buffer, 0, read)
                    total += read
                }
            }
            if (result == null && nowNanos() - startedAt > deadlineNanos) result = ClipDownload.Unavailable
        }
    }
    val finished = result ?: if (total > 0L) ClipDownload.Ok(total) else ClipDownload.Unavailable
    if (finished !is ClipDownload.Ok) target.delete()
    return finished
}

/**
 * The MIME type to create the sound's SAF document with.
 *
 * A DocumentsProvider keeps the requested display name only when the type and the extension agree by the PLATFORM's own
 * tables (AOSP `FileUtils.splitFileName`); otherwise it appends the extension it believes the type has, so `X-audio.m4a`
 * could come back as `X-audio.m4a.mp4`, and the writer, which checks the created child's name, would discard it and the
 * sound would never be saved on a SAF library. So this hands the provider the platform's own type for the extension, and
 * `application/octet-stream` (the type a provider assumes for an extension it does not know) when the platform has none.
 * Used for the sound only: the image slots' `image/png` already agrees with its extension and is left exactly as it was.
 */
internal fun safCreateMimeFor(
    extension: String,
    platformMimeForExtension: (String) -> String? = { ext ->
        runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
    },
): String = platformMimeForExtension(extension.lowercase())?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

/**
 * The most transcodes ([LaunchAudioTranscode]) that run at once across a batch's six lanes. A transcode holds a decoder and
 * an encoder (two codec instances, and a device has a limited number of those, shared with the game that is about to
 * launch) and up to a few MB of decoded PCM, about 2.9 MB for 15 s of 48 kHz stereo and roughly double that while it grows.
 * Six lanes at once would be twelve codec instances and a few tens of MB on the first-run batch, for a stage whose result
 * nobody is waiting for. Two keeps the batch moving without that. Only the decode-and-encode phase is held to it: downloads and
 * the AAC copy stay fully parallel.
 */
internal const val MaxConcurrentTranscodes = 2

/**
 * A process-wide bound on concurrent transcodes: [run] holds one of [permits] for the length of [block] and suspends (it does
 * not block a thread) until one is free, and a lane that is cancelled while it waits simply leaves. One instance is shared by
 * every batch ([ArtworkFetchController.launchAudioTranscodeGate]).
 */
internal class TranscodeGate(permits: Int = MaxConcurrentTranscodes) {
    private val semaphore = Semaphore(permits)

    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit { block() }
}

/** Hard failures in a row (not content facts) before a batch stops paying for clip downloads. */
internal const val MaxConsecutiveAudioFailures = 5

/**
 * Stops a batch from spending its slowest stage on a clip endpoint that is plainly not answering.
 *
 * Only a batch trips it: outside one, [recordFailure] does nothing, so a few failed per-game Enrich presses can never leave
 * the sound switched off for the next one. It counts [ClipDownload.Unavailable] downloads in a row. A clip with no audio
 * track, or one that is too big, is a fact about that game and is never a failure here. A success resets the run.
 *
 * Tripping records nothing in [ArtNoMatchStore]: it says nothing about any game's clip, so the next batch tries again.
 */
internal class LaunchAudioBreaker(private val limit: Int = MaxConsecutiveAudioFailures) {
    private val consecutive = AtomicInteger(0)

    @Volatile
    private var active = false

    @Volatile
    private var trippedInBatch = false

    /** True only while the batch that tripped it is still armed: a disarmed breaker can never skip anything. */
    val tripped: Boolean get() = active && trippedInBatch

    fun begin() {
        consecutive.set(0)
        trippedInBatch = false
        active = true
    }

    fun end() {
        active = false
        consecutive.set(0)
        trippedInBatch = false
    }

    fun recordSuccess() {
        consecutive.set(0)
    }

    fun recordFailure() {
        if (active && consecutive.incrementAndGet() >= limit) trippedInBatch = true
    }
}

/**
 * Whether a lookup that asked about the launch sound records a provider-absent negative for it. Only when the game's
 * record carries NO clip URL at all.
 *
 * It is deliberately NOT the rule the image slots use. For an image, "the record has a URL for this slot" clears the slot's
 * negative. For the sound, a URL being present says nothing about whether the clip HAS sound: the negative recorded for
 * "clip with no audio track" would be cleared by the very next lookup and the 12 MB clip downloaded and found silent again.
 * The sound's negative is cleared only by a successful write.
 */
internal fun audioNegativeAfterLookup(asked: Boolean, clipUrlPresent: Boolean): Boolean = asked && !clipUrlPresent

/**
 * Whether a clip that was downloaded but could not become a sound records a negative for it. Content facts do (no audio
 * track, no decoder on this device for its audio track, a bad file, too big); transport, storage and codec trouble do not,
 * because ScreenScraper answering 430 once, or a lane that found every codec instance busy, must not silence a game's
 * sound for fourteen days.
 */
internal fun audioStepRecordsNegative(outcome: LaunchAudioStepOutcome): Boolean = when (outcome) {
    LaunchAudioStepOutcome.NoAudioTrack,
    LaunchAudioStepOutcome.UnsupportedCodec,
    LaunchAudioStepOutcome.RemuxFailed,
    LaunchAudioStepOutcome.TooLarge,
    -> true
    LaunchAudioStepOutcome.Written,
    LaunchAudioStepOutcome.DownloadUnavailable,
    LaunchAudioStepOutcome.WriteFailed,
    LaunchAudioStepOutcome.NotWritten,
    LaunchAudioStepOutcome.SkippedByBreaker,
    LaunchAudioStepOutcome.StepFailed,
    LaunchAudioStepOutcome.TranscodeFailed,
    -> false
}

/** How one game's launch-sound step ended. Drives the negative record and the cost counters. */
internal enum class LaunchAudioStepOutcome {
    Written,
    NoAudioTrack,

    /**
     * The clip's audio track is not AAC and the device has no decoder for it (other tracks are transcoded now). A fact about this clip on this device, so it records the negative.
     */
    UnsupportedCodec,
    RemuxFailed,
    TooLarge,
    DownloadUnavailable,
    WriteFailed,

    /** The sound was made but the install wrote nothing: the game already had one, or the game was replaced under it. */
    NotWritten,
    SkippedByBreaker,

    /** Something unexpected threw (for instance no room for the staging files). Swallowed: the sound is an extra. */
    StepFailed,

    /**
     * The transcode hit codec, muxer or deadline trouble ([LaunchAudioRemuxOutcome.TransientFailure]). Says nothing about the
     * clip, so, like a dropped download, it records no negative: the next batch tries again.
     */
    TranscodeFailed,
}
