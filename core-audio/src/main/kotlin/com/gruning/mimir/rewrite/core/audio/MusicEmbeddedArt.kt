package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException

/** Whether a track is known to carry an embedded picture. [UNKNOWN] until its tags have been read; art may still be there, so a draw asks. */
enum class EmbeddedArtHint { YES, NO, UNKNOWN }

/**
 * The sizes embedded art is decoded to, as pure rules so they can be asserted on the JVM.
 *
 * A cover is often far larger than any place it is drawn (a 4000 x 4000 JPEG is 64 MB decoded in full), so it is never decoded whole: the decoder
 * samples it down by a power of two while it still has at least the wanted number of pixels on its shorter side, and the result is scaled to exactly
 * that size. The wanted size is one of a few [Buckets] and not the exact drawn size, so the same cover drawn at 96 dp on Main and at 56 dp in a tile is
 * decoded and kept once or twice, not once per pixel size the font scale makes.
 */
internal object MusicArtSize {
    /** The decoded sizes (the shorter side, in pixels), smallest first. The largest is the Bottom player's art at a high density. */
    val Buckets: IntArray = intArrayOf(128, 192, 256, 384, 640)

    /** A picture bigger than this many bytes is not decoded: the tag is lying, or it is not a cover. */
    const val MaxPictureBytes = 24 * 1024 * 1024

    /** The smallest bucket that covers [drawnPx] (at least the drawn size, so it is never blown up), else the largest. */
    fun bucket(drawnPx: Int): Int = Buckets.firstOrNull { it >= drawnPx } ?: Buckets.last()

    /** The `inSampleSize` for a [width] x [height] picture wanted at [targetPx] on its shorter side: the largest power of two that keeps it at least that large. */
    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        val shorter = minOf(width, height)
        if (shorter <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (shorter / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    /** The size to scale a decoded [width] x [height] picture to so its shorter side is [targetPx], or null when it is already that size or smaller (never enlarged). */
    fun scaledSize(width: Int, height: Int, targetPx: Int): Pair<Int, Int>? {
        val shorter = minOf(width, height)
        if (shorter <= targetPx || shorter <= 0 || targetPx <= 0) return null
        // Integer arithmetic: the shorter side lands on the target exactly, which a float scale can miss by one.
        return maxOf(1L, width.toLong() * targetPx / shorter).toInt() to maxOf(1L, height.toLong() * targetPx / shorter).toInt()
    }
}

/**
 * A least-recently-used cache that holds at most [budgetBytes] of values, whatever they are: [sizeOf] says how big one is. Not thread-safe by itself;
 * callers lock.
 */
internal class BudgetLru<K : Any, V : Any>(private val budgetBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var used = 0L

    val size: Int get() = map.size

    val usedBytes: Long get() = used

    operator fun get(key: K): V? = map[key]

    operator fun contains(key: K): Boolean = map.containsKey(key)

    fun put(key: K, value: V) {
        map.put(key, value)?.let { used -= sizeOf(it) }
        used += sizeOf(value)
        val eldest = map.entries.iterator()
        // The newest entry is never evicted by its own insertion, so a value larger than the budget still stands alone for a moment.
        while (used > budgetBytes && map.size > 1 && eldest.hasNext()) {
            val oldest = eldest.next()
            if (oldest.key == key) continue
            used -= sizeOf(oldest.value)
            eldest.remove()
        }
    }

    fun clear() {
        map.clear()
        used = 0L
    }
}

/**
 * Embedded cover art, decoded on IO at the size it is drawn at and kept. One decode runs at a time (a picture is read whole from the file, so two
 * together would double the peak), and a track with no picture is remembered as such, so a list that scrolls past it never asks again.
 *
 * **A decode that never returns.** The retriever's native call cannot be interrupted, so one hostile file would hold the gate for ever and every later cover would
 * wait behind it. Both ends are bounded: a decode runs under [DecodeTimeoutMs] ([AbandonableWork]; past it the file has no art and the gate is free, though the
 * stuck thread cannot be stopped and is left to finish on its own), and a caller waits at most [GateWaitMs] for the gate and shows no art if it is still taken.
 *
 * The cache is keyed by the track's id AND its file's size and time, so a file whose art was replaced is decoded afresh after the rescan sees it change.
 */
internal object MimirMusicArt {
    private const val Tag = "MimirMusicArt"

    /** How long a caller waits behind another decode before it shows no art. */
    const val GateWaitMs = 8_000L

    /** How long one decode may run: a cover is a few hundred KB, so a decode still going then is hung. */
    const val DecodeTimeoutMs = 10_000L

    /** About 40 pictures at 256 px, or 9 at the largest bucket. */
    private const val BudgetBytes = 12L * 1024 * 1024

    private sealed interface Entry {
        class Art(val bitmap: Bitmap) : Entry

        data object None : Entry
    }

    private val lock = Any()
    private val decodeGate = BoundedGate()
    private val cache = BudgetLru<String, Entry>(BudgetBytes) { (it as? Entry.Art)?.bitmap?.allocationByteCount?.toLong() ?: 64L }

    private fun key(trackId: String, stamp: MusicFileStamp, bucket: Int): String = "$trackId|${stamp.sizeBytes}|${stamp.modifiedMillis}|$bucket"

    /** The decoded art if it is already in memory: [Cached.Art], [Cached.None] when the track is known to have none, or null when nobody has asked yet. */
    sealed interface Cached {
        class Art(val bitmap: Bitmap) : Cached

        data object None : Cached
    }

    fun cached(trackId: String, stamp: MusicFileStamp, drawnPx: Int): Cached? = synchronized(lock) {
        when (val hit = cache[key(trackId, stamp, MusicArtSize.bucket(drawnPx))]) {
            is Entry.Art -> Cached.Art(hit.bitmap)
            Entry.None -> Cached.None
            null -> null
        }
    }

    fun clear() = synchronized(lock) { cache.clear() }

    /** Decodes [trackId]'s embedded picture for a [drawnPx] square, or returns it from the cache. Null when the file has no picture or it cannot be decoded. */
    suspend fun load(context: Context, source: MusicTagSource, trackId: String, stamp: MusicFileStamp, drawnPx: Int): Bitmap? {
        val bucket = MusicArtSize.bucket(drawnPx)
        val key = key(trackId, stamp, bucket)
        (cached(trackId, stamp, drawnPx) as? Cached.Art)?.let { return it.bitmap }
        if (cached(trackId, stamp, drawnPx) == Cached.None) return null
        val gated = decodeGate.withinGate(GateWaitMs) {
            // Another caller may have decoded it while this one waited for the gate.
            synchronized(lock) {
                when (val hit = cache[key]) {
                    is Entry.Art -> return@withinGate hit.bitmap
                    Entry.None -> return@withinGate null
                    null -> Unit
                }
            }
            when (val outcome = AbandonableWork.run(DecodeTimeoutMs) { decode(context.applicationContext, source, bucket) }) {
                is AbandonableOutcome.Done -> outcome.value.also { bitmap ->
                    synchronized(lock) { cache.put(key, if (bitmap != null) Entry.Art(bitmap) else Entry.None) }
                }
                // A decode that threw (an Error the decoder did not catch) or never returned is a file with no usable picture: remembered, so a list scrolling past it does not hang on it again.
                is AbandonableOutcome.Threw, AbandonableOutcome.TimedOut -> {
                    synchronized(lock) { cache.put(key, Entry.None) }
                    null
                }
                // Too many hung decodes are still blocked to start another: no art for now, and nothing remembered, so a later ask tries again.
                AbandonableOutcome.Refused -> null
            }
        }
        // Gave up waiting behind a decode that has not returned: no art for this ask, and nothing remembered (the file may be fine).
        return when (gated) {
            is BoundedGate.Gated.Ran -> gated.value
            BoundedGate.Gated.GaveUp -> null
        }
    }

    /** Blocking: reads the picture and decodes it down to [bucket]. */
    private fun decode(context: Context, source: MusicTagSource, bucket: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            when (source) {
                is MusicTagSource.Path -> retriever.setDataSource(source.absolutePath)
                is MusicTagSource.Document -> retriever.setDataSource(context, Uri.parse(source.uri))
            }
            val bytes = retriever.embeddedPicture ?: return null
            if (bytes.isEmpty() || bytes.size > MusicArtSize.MaxPictureBytes) return null
            decodeBytes(bytes, bucket)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The class only: the message can carry the file's path.
            Log.w(Tag, "no art for a track (${error.javaClass.simpleName})")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Never got a source.
            }
        }
    }

    private fun decodeBytes(bytes: ByteArray, bucket: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = MusicArtSize.sampleSize(bounds.outWidth, bounds.outHeight, bucket)
        }
        return try {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val scaled = MusicArtSize.scaledSize(decoded.width, decoded.height, bucket)
            if (scaled == null) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, scaled.first, scaled.second, true).also { if (it !== decoded) decoded.recycle() }
            }
        } catch (_: OutOfMemoryError) {
            // Art is never worth the launcher: a cover that will not fit is no cover.
            null
        }
    }
}
