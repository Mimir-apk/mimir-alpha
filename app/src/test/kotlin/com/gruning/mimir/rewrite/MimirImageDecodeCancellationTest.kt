package com.gruning.mimir.rewrite

import coil.decode.DecodeResult
import coil.decode.Decoder
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * QA 2026-09-11 D17: every cancelled art decode printed a 40-line trace, because the platform's BitmapFactory stream
 * adaptor describes any exception a Java `read` throws into it. Pins the two halves that keep a CANCELLATION out of
 * native code while leaving every other failure on today's path. JVM-only: nothing is decoded here, and whether the
 * trace is gone from a device logcat is device-only.
 */
class MimirImageDecodeCancellationTest {

    @After
    fun clearInterruptFlag() {
        Thread.interrupted()
    }

    @Test
    fun `bytes pass through untouched`() {
        val source = CancellationQuietSource(Buffer().writeUtf8("cover-bytes"))
        assertEquals("cover-bytes", source.buffer().readUtf8())
        assertNull(source.cancellation)
    }

    @Test
    fun `a cancelled read answers end of stream and never reads upstream again`() {
        val upstream = InterruptibleSource()
        val source = CancellationQuietSource(upstream)
        Thread.currentThread().interrupt()

        assertEquals(-1L, source.read(Buffer(), 8_192L))
        assertSame(upstream.thrown, source.cancellation)
        assertEquals(-1L, source.read(Buffer(), 8_192L))
        assertEquals(1, upstream.reads)
    }

    @Test
    fun `an okio deadline on a thread that is not interrupted still throws`() {
        val deadline = InterruptedIOException("deadline reached")
        val source = CancellationQuietSource(FailingSource(deadline))
        assertSame(deadline, readFailure(source))
        assertNull(source.cancellation)
    }

    @Test
    fun `a real io failure still throws even on an interrupted thread`() {
        val broken = IOException("storage went away")
        val source = CancellationQuietSource(FailingSource(broken))
        Thread.currentThread().interrupt()
        assertSame(broken, readFailure(source))
        assertNull(source.cancellation)
    }

    @Test
    fun `the decoder rethrows the cancellation over the null bitmap a truncated stream produced`() {
        val upstream = InterruptibleSource()
        val source = CancellationQuietSource(upstream)
        val decoder = CancellationQuietDecoder(
            delegate = object : Decoder {
                override suspend fun decode(): DecodeResult? {
                    cancelledRead(source)
                    throw IllegalStateException("BitmapFactory returned a null bitmap.")
                }
            },
            source = source,
        )
        assertSame(upstream.thrown, decode(decoder).exceptionOrNull())
    }

    @Test
    fun `the decoder rethrows the cancellation over whatever a truncated stream decoded to`() {
        val upstream = InterruptibleSource()
        val source = CancellationQuietSource(upstream)
        val decoder = CancellationQuietDecoder(
            delegate = object : Decoder {
                override suspend fun decode(): DecodeResult? {
                    cancelledRead(source)
                    return null
                }
            },
            source = source,
        )
        assertSame(upstream.thrown, decode(decoder).exceptionOrNull())
    }

    @Test
    fun `without a cancellation a real decode failure passes through unchanged`() {
        val corrupt = IllegalStateException("BitmapFactory returned a null bitmap.")
        val source = CancellationQuietSource(Buffer().writeUtf8("not an image"))
        val decoder = CancellationQuietDecoder(
            delegate = object : Decoder {
                override suspend fun decode(): DecodeResult? {
                    source.read(Buffer(), 8_192L)
                    throw corrupt
                }
            },
            source = source,
        )
        assertSame(corrupt, decode(decoder).exceptionOrNull())
    }

    @Test
    fun `without a cancellation the delegate result passes through`() {
        val decoder = CancellationQuietDecoder(
            delegate = object : Decoder {
                override suspend fun decode(): DecodeResult? = null
            },
            source = CancellationQuietSource(Buffer()),
        )
        val outcome = decode(decoder)
        assertNull(outcome.exceptionOrNull())
        assertNull(outcome.getOrNull())
    }

    /** One interrupted read, then the flag cleared -- the state `runInterruptible` leaves a decode thread in. */
    private fun cancelledRead(source: CancellationQuietSource) {
        Thread.currentThread().interrupt()
        try {
            assertEquals(-1L, source.read(Buffer(), 8_192L))
        } finally {
            Thread.interrupted()
        }
    }

    private fun readFailure(source: Source): Throwable? =
        runCatching { source.read(Buffer(), 8_192L) }.exceptionOrNull()

    /** Runs a decoder that never suspends without kotlinx.coroutines, so the thrown instance reaches the assert as-is. */
    private fun decode(decoder: Decoder): Result<DecodeResult?> {
        var outcome: Result<DecodeResult?>? = null
        suspend { decoder.decode() }.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) { "the decoder suspended" }
    }

    /** Upstream that behaves like okio's InputStreamSource: a read on an interrupted thread throws. */
    private class InterruptibleSource : Source {
        val thrown = InterruptedIOException("interrupted")
        var reads = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            reads++
            if (Thread.currentThread().isInterrupted) throw thrown
            return -1L
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private class FailingSource(private val failure: IOException) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = throw failure

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}
