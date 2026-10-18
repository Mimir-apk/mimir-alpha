package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Watches Your music's player and publishes the [TrackFormat] of the track on air: the container's declared format
 * (`onAudioInputFormatChanged`), the PCM the player writes (`onAudioTrackInitialized`), and, for a lossless file, its average
 * bitrate, which no extractor declares and so is the file's size x 8 / the track's duration.
 *
 * Everything but the size lookup runs on the main looper, the player's application looper, where its events arrive. The size is
 * read on [Dispatchers.IO] (a content resolver query or a file stat is never main-thread work) and posted back; a result for a
 * track that is no longer current is dropped.
 *
 * This is the one place Your music's format reads the unstable `Format` and `AnalyticsListener` API.
 */
@OptIn(UnstableApi::class)
internal object YourMusicFormatTracker : AnalyticsListener {
    private const val Tag = "MimirYourMusicFormat"

    // Lazy: reading [format] from the facade must not need a Looper (the JVM tests of the facade have none).
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val failureHandler = CoroutineExceptionHandler { _, error -> Log.e(Tag, "size lookup failed", error) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failureHandler)

    private val _format = MutableStateFlow<TrackFormat?>(null)

    /** The track on air's format, or null while nothing is loaded or the player has not said yet. */
    val format: StateFlow<TrackFormat?> = _format.asStateFlow()

    // Main-thread state.
    private var player: ExoPlayer? = null
    private var context: Context? = null
    // The source format of the track on air: a format the renderer read ahead for the next track waits in here for its transition.
    private val inputs = FormatTransitionGate<Format>()
    private var output: OutputFormat? = null
    private var currentId: String? = null
    private var sizeId: String? = null
    private var sizeBytes: Long = 0L
    private var lookup: Job? = null

    /** Starts listening to [exo], the process's one player. Main thread. */
    fun attach(exo: ExoPlayer, appContext: Context) {
        player = exo
        context = appContext.applicationContext
        exo.addAnalyticsListener(this)
    }

    /** Nothing is loaded any more (the playlist was cleared). Main thread. */
    fun reset() {
        lookup?.cancel()
        lookup = null
        inputs.reset()
        output = null
        currentId = null
        sizeId = null
        sizeBytes = 0L
        _format.value = null
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        // The renderer reads the NEXT track's format ahead of the transition: it is held until that track is the one playing.
        inputs.onFormat(mediaIdOf(eventTime), format)
        refresh()
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        output = OutputFormat(
            bitDepth = TrackFormatText.pcmBitDepth(audioTrackConfig.encoding),
            isFloat = TrackFormatText.isFloatEncoding(audioTrackConfig.encoding),
            sampleRateHz = audioTrackConfig.sampleRate.takeIf { it > 0 },
        )
        refresh()
    }

    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId
        if (id == currentId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            refresh()
            return
        }
        currentId = id
        inputs.onTransition(id)
        sizeId = null
        sizeBytes = 0L
        lookup?.cancel()
        lookup = null
        val uri = mediaItem?.localConfiguration?.uri
        if (id != null && uri != null) startSizeLookup(id, uri)
        refresh()
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (state == Player.STATE_READY) refresh()
    }

    override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) = refresh()

    /** The media id of the window an event is about (the one the renderer is reading), or null when the timeline cannot say. */
    private fun mediaIdOf(eventTime: AnalyticsListener.EventTime): String? {
        val timeline = eventTime.timeline
        if (eventTime.windowIndex !in 0 until timeline.windowCount) return null
        return timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
    }

    private fun startSizeLookup(id: String, uri: Uri) {
        val resolverContext = context ?: return
        lookup = ioScope.launch {
            val size = readSize(resolverContext, uri)
            main.post {
                if (id != currentId) return@post
                sizeId = id
                sizeBytes = size
                refresh()
            }
        }
    }

    /** The file's byte size, or 0 when unknown: a `file:` stat, else the provider's `_size` (null for some documents). */
    private fun readSize(context: Context, uri: Uri): Long {
        try {
            if (uri.scheme.equals("file", ignoreCase = true)) return uri.path?.let { File(it).length() } ?: 0L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column).coerceAtLeast(0L)
                }
            }
            return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            Log.w(Tag, "no size for the track", error)
        } catch (error: RuntimeException) {
            Log.w(Tag, "no size for the track", error)
        }
        return 0L
    }

    private fun refresh() {
        val format = inputs.current
        if (format == null) {
            _format.value = null
            return
        }
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val losslessKbps = if (sizeId != null && sizeId == currentId) TrackFormatText.losslessBitrateKbps(sizeBytes, duration) else null
        _format.value = TrackFormatText.sourceFormat(
            sampleMimeType = format.sampleMimeType,
            containerMimeType = format.containerMimeType,
            bitDepthEncoding = format.pcmEncoding,
            sampleRateHz = format.sampleRate,
            channels = format.channelCount,
            averageBitrate = format.averageBitrate,
            bitrate = format.bitrate,
            losslessBitrateKbps = losslessKbps,
            output = output,
        )
    }
}
