package com.gruning.mimir.rewrite

import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.BitmapFactoryDecoder
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import java.io.InterruptedIOException
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * Keeps a CANCELLED art decode from printing a stack trace to System.err (QA 2026-09-11 D17), and changes nothing else about
 * decoding.
 *
 * **Who printed it: neither Mimir nor Coil.** [buildLocalOnlyImageLoader] installs no Coil logger, and nothing in the
 * app prints the trace. The printer is the platform's native InputStream adaptor underneath
 * `BitmapFactory.decodeStream`: in the session capture (`SESSION-full-logcat-threadtime-1301-1421.txt.gz`) the
 * 40-line `W System.err` trace at 13:26:27.984 is followed on the same thread one millisecond later by
 * `D OpenGLRenderer: ---- read threw an exception` -- the adaptor describing the Java exception to System.err, then
 * swallowing it and treating the stream as ended. The exception is the cancellation itself: Coil 2.7.0's
 * `BitmapFactoryDecoder` decodes inside `runInterruptible`, a cancelled request interrupts the decode thread, and
 * okio 3.9.0's `Timeout.throwIfReached` throws `InterruptedIOException("interrupted")` from the stream's next `read`
 * while the thread's interrupt flag is set (both read from the cached jars' bytecode). Coil's own
 * `ExceptionCatchingSource` records that exception and rethrows it once `decodeStream` has returned.
 *
 * **So the one lever is to keep a cancellation from being thrown INTO native code.** [CancellationQuietSource] sits
 * beneath Coil's catcher and answers end-of-stream instead -- what the adaptor made of the exception anyway -- and
 * [CancellationQuietDecoder] rethrows that same exception when the delegate returns, so Coil's pipeline receives the
 * identical exception from the same `decoder.decode()` call. Anything that is not a cancellation (a real I/O failure
 * mid-read, an okio deadline, a corrupt image) takes exactly today's path, printed trace included.
 *
 * Bytes and decode options are untouched: the delegate is Coil's own [BitmapFactoryDecoder] factory at its defaults
 * (`DEFAULT_MAX_PARALLELISM`, `ExifOrientationPolicy.RESPECT_PERFORMANCE` -- what `ImageLoaderOptions` hands a
 * builder that sets neither, as this one does not), reading the same source, with its metadata, MIME type and data
 * source carried across. As a custom component it is consulted before Coil's default decoder factory, which therefore
 * no longer runs for this loader.
 */
internal class CancellationQuietDecoderFactory(
    private val delegate: Decoder.Factory = BitmapFactoryDecoder.Factory(),
) : Decoder.Factory {
    @OptIn(ExperimentalCoilApi::class)
    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val quiet = CancellationQuietSource(result.source.source())
        val quietResult = result.copy(
            source = ImageSource(quiet.buffer(), options.context, result.source.metadata),
        )
        val decoder = delegate.create(quietResult, options, imageLoader) ?: return null
        return CancellationQuietDecoder(decoder, quiet)
    }
}

/**
 * An okio [Source] that turns a decode CANCELLATION -- an [InterruptedIOException] raised while the reading thread is
 * still flagged interrupted, which is how `runInterruptible` cancels -- into end-of-stream, remembering the exception
 * in [cancellation]. Every other failure is rethrown untouched, and once cancelled it never reads upstream again.
 */
internal class CancellationQuietSource(delegate: Source) : ForwardingSource(delegate) {
    @Volatile
    var cancellation: InterruptedIOException? = null
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (cancellation != null) return -1L
        return try {
            super.read(sink, byteCount)
        } catch (interrupted: InterruptedIOException) {
            // okio's own deadline ("deadline reached") is an InterruptedIOException too; only one raised on a thread
            // whose interrupt flag is still set is a cancellation. okio reads the flag without clearing it.
            if (!Thread.currentThread().isInterrupted) throw interrupted
            cancellation = interrupted
            -1L
        }
    }
}

/**
 * Rethrows [source]'s recorded cancellation once [delegate] returns -- whether the delegate built a result from the
 * truncated stream or threw over it (`BitmapFactory returned a null bitmap`) -- so the pipeline gets the exception it
 * got before. With no cancellation recorded, the delegate's own result or failure passes through unchanged.
 */
internal class CancellationQuietDecoder(
    private val delegate: Decoder,
    private val source: CancellationQuietSource,
) : Decoder {
    override suspend fun decode(): DecodeResult? {
        val result = try {
            delegate.decode()
        } catch (failure: Throwable) {
            throw source.cancellation ?: failure
        }
        source.cancellation?.let { throw it }
        return result
    }
}
