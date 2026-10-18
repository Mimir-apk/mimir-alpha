package com.gruning.mimir.rewrite

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What one Enrich batch cost, measured for ONE run.
 * Count the information needed to compare launch-sound download costs and emit one line per batch.
 *
 * **Counts, durations and bytes only.** Nothing in here can hold a URL, a credential, a game name or a file name: the only
 * inputs are integers and the batch's own label. That is what lets the line be logged, and pasted, freely.
 *
 * **Lane time is SUMMED, not wall time.** Up to six lanes run at once, so `coverMs`, `audioDownloadMs` and the rest add each
 * lane's own time together and can exceed `wallMs`. Read them as shares of the batch's work against each other (and against
 * `lookupMs`), not as time the player waited. `wallMs` is the wait.
 *
 * Thread-safe (atomics), because the lanes of a batch report concurrently. [clock] is injectable so the format and the
 * arithmetic are testable without a real clock.
 */
internal class EnrichCostAccumulator(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    @Volatile
    private var batch = "unknown"

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var requestsBefore: Int? = null

    private val lookups = AtomicInteger()
    private val lookupMs = AtomicLong()

    private val slotMs = Array(SlotCount) { AtomicLong() }

    private val audioMs = AtomicLong()
    private val audioClips = AtomicInteger()
    private val audioBytes = AtomicLong()
    private val audioDownloadMs = AtomicLong()
    /** The AAC copy path's time (and the look at the clip that chose the path). */
    private val audioRemuxMs = AtomicLong()

    /** The decode-and-encode path's time, for the clips whose audio track was not AAC. */
    private val audioTranscodeMs = AtomicLong()
    private val audioTranscoded = AtomicInteger()

    /** Source track MIME type (sanitised, see [sanitizeCodecMime]) to how many clips carried it. */
    private val codecs = ConcurrentHashMap<String, AtomicInteger>()
    private val outcomes = Array(LaunchAudioStepOutcome.entries.size) { AtomicInteger() }

    fun now(): Long = clock()

    /** Zeroes every figure and starts the wall clock. [requestsBefore] is the connect card's `requestsToday`, when known. */
    fun begin(batch: String, requestsBefore: Int?) {
        this.batch = batch
        this.requestsBefore = requestsBefore
        lookups.set(0)
        lookupMs.set(0)
        slotMs.forEach { it.set(0) }
        audioMs.set(0)
        audioClips.set(0)
        audioBytes.set(0)
        audioDownloadMs.set(0)
        audioRemuxMs.set(0)
        audioTranscodeMs.set(0)
        audioTranscoded.set(0)
        codecs.clear()
        outcomes.forEach { it.set(0) }
        startedAt = clock()
    }

    fun wallMs(): Long = clock() - startedAt

    fun addLookup(ms: Long) {
        lookups.incrementAndGet()
        lookupMs.addAndGet(ms.coerceAtLeast(0L))
    }

    /** One image slot's whole step for one game: the download and the install. */
    fun addSlot(slot: ArtworkFetchController.ManagedMediaSlot, ms: Long) {
        slotMs[slot.ordinal].addAndGet(ms.coerceAtLeast(0L))
    }

    /** A game whose record carried a clip and whose sound step actually ran (not skipped by the breaker). */
    fun audioClip() {
        audioClips.incrementAndGet()
    }

    fun audioDownloaded(bytes: Long, ms: Long) {
        audioBytes.addAndGet(bytes.coerceAtLeast(0L))
        audioDownloadMs.addAndGet(ms.coerceAtLeast(0L))
    }

    /** Time spent on a download that did not finish: it still cost the lane, so it is still counted. */
    fun audioDownloadTime(ms: Long) {
        audioDownloadMs.addAndGet(ms.coerceAtLeast(0L))
    }

    /** Time the AAC copy path took for one clip (including the look at its tracks that chose the path). */
    fun audioRemuxTime(ms: Long) {
        audioRemuxMs.addAndGet(ms.coerceAtLeast(0L))
    }

    /** Time the decode-and-encode path took for one clip, whether or not it produced a sound. */
    fun audioTranscodeTime(ms: Long) {
        audioTranscodeMs.addAndGet(ms.coerceAtLeast(0L))
    }

    /** One sound that was made by transcoding (it is also counted as Written). */
    fun audioTranscodedOne() {
        audioTranscoded.incrementAndGet()
    }

    /**
     * One clip whose first audio track carried [mime]. Only the MIME type string of the track is kept, and only after
     * [sanitizeCodecMime], because it comes out of a downloaded file: the line it is printed in is split on spaces.
     */
    fun audioCodec(mime: String) {
        val key = sanitizeCodecMime(mime)
        val counter = codecs[key]
            ?: if (codecs.size >= MaxDistinctCodecs) codecs.getOrPut(OtherCodec) { AtomicInteger() } else codecs.getOrPut(key) { AtomicInteger() }
        counter.incrementAndGet()
    }

    /** The tally as `mime:count,mime:count` (most common first, then by name), or `none` before any clip was probed. */
    fun audioCodecsField(): String {
        if (codecs.isEmpty()) return "none"
        return codecs.entries
            .map { it.key to it.value.get() }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .joinToString(",") { (mime, count) -> "$mime:$count" }
    }

    /** The whole sound step for one game (download, remux, install), for the per-slot share. */
    fun audioStepTime(ms: Long) {
        audioMs.addAndGet(ms.coerceAtLeast(0L))
    }

    fun audioOutcome(outcome: LaunchAudioStepOutcome) {
        outcomes[outcome.ordinal].incrementAndGet()
    }

    fun audioOutcomeCount(outcome: LaunchAudioStepOutcome): Int = outcomes[outcome.ordinal].get()

    fun audioClipCount(): Int = audioClips.get()

    /**
     * The one line per batch, logged under the `MimirEnrichCost` tag. Fixed key order, `key=value` separated by single
     * spaces, so a grep or a split on spaces reads it. `unknown` stands for a figure that could not be read.
     *
     * [wallMs] is passed in (read before the `requestsToday` call that follows it) so that call is not counted as batch time.
     * [requestsAfter] costs one extra `ssuserInfos` request at the end of a batch; the delta is reported as read, so if that
     * request itself counts against the day, it counts on both sides of the subtraction and cancels.
     */
    fun summary(
        games: Int,
        matched: Int,
        wallMs: Long,
        requestsAfter: Int?,
        audioBreakerTripped: Boolean,
    ): String {
        val before = requestsBefore
        val delta = if (before != null && requestsAfter != null) requestsAfter - before else null
        return buildString {
            append("batch=").append(batch)
            append(" games=").append(games)
            append(" matched=").append(matched)
            append(" wallMs=").append(wallMs)
            append(" lookups=").append(lookups.get())
            append(" lookupMs=").append(lookupMs.get())
            append(" coverMs=").append(slotMs[ArtworkFetchController.ManagedMediaSlot.COVER.ordinal].get())
            append(" logoMs=").append(slotMs[ArtworkFetchController.ManagedMediaSlot.LOGO.ordinal].get())
            append(" heroMs=").append(slotMs[ArtworkFetchController.ManagedMediaSlot.HERO.ordinal].get())
            append(" physicalMs=").append(slotMs[ArtworkFetchController.ManagedMediaSlot.PHYSICAL.ordinal].get())
            append(" audioMs=").append(audioMs.get())
            append(" audioClips=").append(audioClips.get())
            append(" audioBytes=").append(audioBytes.get())
            append(" audioCodecs=").append(audioCodecsField())
            append(" audioDownloadMs=").append(audioDownloadMs.get())
            append(" audioRemuxMs=").append(audioRemuxMs.get())
            append(" audioTranscodeMs=").append(audioTranscodeMs.get())
            append(" audioWritten=").append(audioOutcomeCount(LaunchAudioStepOutcome.Written))
            append(" audioTranscoded=").append(audioTranscoded.get())
            append(" audioNoTrack=").append(audioOutcomeCount(LaunchAudioStepOutcome.NoAudioTrack))
            // Non-AAC tracks are now transcoded, so what lands here is a track the
            // device has no decoder for.
            append(" audioNoDecoder=").append(audioOutcomeCount(LaunchAudioStepOutcome.UnsupportedCodec))
            append(" audioTooLarge=").append(audioOutcomeCount(LaunchAudioStepOutcome.TooLarge))
            append(" audioRemuxFailed=").append(audioOutcomeCount(LaunchAudioStepOutcome.RemuxFailed))
            append(" audioDownloadFailed=").append(audioOutcomeCount(LaunchAudioStepOutcome.DownloadUnavailable))
            append(" audioWriteFailed=").append(audioOutcomeCount(LaunchAudioStepOutcome.WriteFailed))
            append(" audioNotWritten=").append(audioOutcomeCount(LaunchAudioStepOutcome.NotWritten))
            append(" audioStepFailed=").append(audioOutcomeCount(LaunchAudioStepOutcome.StepFailed))
            append(" audioSkipped=").append(audioOutcomeCount(LaunchAudioStepOutcome.SkippedByBreaker))
            append(" audioBreakerTripped=").append(audioBreakerTripped)
            append(" requestsTodayBefore=").append(before ?: "unknown")
            append(" requestsTodayAfter=").append(requestsAfter ?: "unknown")
            append(" requestsTodayDelta=").append(delta ?: "unknown")
            // Appended at the END (walk 32l) so a grep or split that already reads the line above still parses it: clips whose
            // transcode hit codec, muxer or deadline trouble. They record no negative; the next batch tries them again.
            append(" audioTranscodeFailed=").append(audioOutcomeCount(LaunchAudioStepOutcome.TranscodeFailed))
        }
    }

    private companion object {
        val SlotCount = ArtworkFetchController.ManagedMediaSlot.entries.size

        /** More distinct MIME strings than this in one batch are all counted as [OtherCodec]. */
        const val MaxDistinctCodecs = 12
    }
}

/** What a MIME string that is not a plain `type/subtype` is tallied as. */
internal const val OtherCodec = "other"

private val PlainMimeType = Regex("^[a-z0-9][a-z0-9.+_-]*/[a-z0-9][a-z0-9.+_-]*$")

private const val MaxCodecMimeLength = 48

/**
 * A track's MIME type made safe to print in the cost line. It comes from inside a downloaded file, so it is lower-cased and
 * kept only if it is a plain `type/subtype` of letters, digits and `. + _ -` and short; anything else (a space, a comma, a
 * colon, a newline, an `=`, something absurdly long) is tallied as [OtherCodec], so a clip can never inject a field into a
 * line that is split on spaces or add a path, a name or a URL to a line guaranteed to hold counts only.
 */
internal fun sanitizeCodecMime(mime: String): String {
    val lowered = mime.trim().lowercase()
    return if (lowered.length <= MaxCodecMimeLength && PlainMimeType.matches(lowered)) lowered else OtherCodec
}
