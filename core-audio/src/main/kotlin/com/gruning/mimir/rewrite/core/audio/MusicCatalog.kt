package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One track as the music UI sees it: the playback identity ([id], [fileName], [folder]) with its tags laid over it. [title] is always something to show:
 * the title tag, else the file name without its extension. [artist], [album] and [albumArtist] stay null when the tag is missing, so the tree can say
 * "Unknown Artist" only for a track that really has none ([buildMusicTree]).
 */
data class MusicCatalogTrack(
    /** The id the player opens the track by (a document id or an absolute path). Stable across rescans of an unchanged file. */
    val id: String,
    val fileName: String,
    /** Empty for a general track, a lowercase platform id for a platform folder's. */
    val folder: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
    /** The file carries an embedded picture; false until its tags have been read. */
    val hasEmbeddedArt: Boolean = false,
    /** The file is marked as one of a compilation: with no album-artist tag its album is filed under [VariousArtistsName] ([buildMusicTree]). */
    val compilation: Boolean = false,
)

/**
 * The chosen folder's tracks with their tags, for the music UI. [tagsRead] of [tracks] have had their tags read (or tried): while it is less than the
 * track count the tags are still arriving and an artist or album may yet change. [loaded] is false until the index has been read from disk or scanned.
 */
data class MusicCatalog(
    val tree: String? = null,
    val tracks: List<MusicCatalogTrack> = emptyList(),
    val tagsRead: Int = 0,
    val loaded: Boolean = false,
) {
    val reading: Boolean get() = tagsRead < tracks.size
}

/**
 * The library as the browser draws it: the Artist / Album [tree], and how far the tag pass is ([tagsRead] of [trackCount]; while it is short an artist or an
 * album may still change). [loaded] is false until the index has been read or scanned. [pickedTrackId] is the track the player pressed in the tree, whose album is now the
 * queue; null when no pick is active and the whole folder plays ("All music").
 */
data class MusicLibraryView(
    val tree: MusicTree = MusicTree(),
    val tagsRead: Int = 0,
    val trackCount: Int = 0,
    val loaded: Boolean = false,
    val pickedTrackId: String? = null,
)

/**
 * The player's pick from the Artist / Album tree: [trackIds] are the pressed track's album in play order, [startId] the track pressed, [tree] the folder it was
 * made in. [serial] numbers the picks, so a second press of the very same track is a new request ([MusicStart]).
 */
internal data class MusicSelection(val tree: String, val trackIds: List<String>, val startId: String, val serial: Long)

/** The title a track is called everywhere: its tag, else the file name without its extension. */
internal fun musicDisplayTitle(track: MusicTrack, meta: MusicTrackMeta?): String =
    meta?.tags?.title ?: musicTrackTitle(track.name)

/** What the media session shows for a track: its title, and its artist and album when the tags name them. */
internal data class YourMusicLabel(val title: String, val artist: String? = null, val album: String? = null)

internal fun yourMusicLabel(track: MusicTrack, meta: MusicTrackMeta?): YourMusicLabel =
    YourMusicLabel(musicDisplayTitle(track, meta), meta?.tags?.artist ?: meta?.tags?.albumArtist, meta?.tags?.album)

internal fun musicCatalogOf(tree: String?, tracks: List<MusicTrack>, meta: Map<String, MusicTrackMeta>, loaded: Boolean): MusicCatalog {
    var read = 0
    val out = ArrayList<MusicCatalogTrack>(tracks.size)
    // A track id is listed once: the screen keys its rows by it, so a repeat (an index a provider's loop made) must never reach a list.
    val seen = HashSet<String>(tracks.size * 2)
    for (track in tracks) {
        if (!seen.add(track.id)) continue
        val m = meta[track.id]
        if (m != null && m.state != MusicTagState.UNREAD) read += 1
        val tags = m?.tags
        out += MusicCatalogTrack(
            id = track.id,
            fileName = track.name,
            folder = track.folder,
            title = musicDisplayTitle(track, m),
            artist = tags?.artist,
            album = tags?.album,
            albumArtist = tags?.albumArtist,
            trackNumber = tags?.trackNumber,
            discNumber = tags?.discNumber,
            durationMs = tags?.durationMs,
            hasEmbeddedArt = m?.hasEmbeddedArt == true,
            compilation = tags?.compilation == true,
        )
    }
    return MusicCatalog(tree, out, read, loaded)
}

/**
 * Reads the tags the index still lacks, a few files at a time, in batches. It knows nothing of Android: [reader] opens the files and [sourceOf] says
 * where each one is, so the JVM tests drive it with a fake reader.
 */
internal object MusicTagPass {
    /** How many files are read before the caller is told: small enough that the UI sees progress, large enough that it is not woken per file. */
    const val BatchSize = 48

    /** How many files are open at once. The retriever is I/O bound on a card; more would only queue behind it. */
    const val Parallelism = 2

    /** How long one file's tag read may take. Far past any real file on a slow card or a network provider; a file still reading then is hostile or hung. */
    const val ReadTimeoutMs = 20_000L

    /** One read, bounded: the reader's answer, [MusicTagResult.Failed] for a file that threw or timed out, [MusicTagResult.Unavailable] when too many hung reads are still blocked to start another. */
    private suspend fun readBounded(reader: MusicTagReader, source: MusicTagSource, timeoutMs: Long): MusicTagResult =
        when (val outcome = AbandonableWork.run(timeoutMs) { reader.read(source) }) {
            is AbandonableOutcome.Done -> outcome.value
            is AbandonableOutcome.Threw -> MusicTagResult.Failed
            AbandonableOutcome.TimedOut -> MusicTagResult.Failed
            AbandonableOutcome.Refused -> MusicTagResult.Unavailable
        }

    /**
     * Reads [pending] and reports each batch through [onBatch] (track and result, in index order). Stops at the first [MusicTagResult.Unavailable]
     * (a lost grant, a removed card) without reporting it or anything after it, so those files stay unread and a later pass retries them, and at
     * cancellation between batches. Returns whether every file was reached.
     *
     * **A read that never returns.** The retriever's native call cannot be interrupted, so a hostile file could hold a read slot for ever. Each read runs under
     * [readTimeoutMs] ([AbandonableWork]): past it the file is [MusicTagResult.Failed] (remembered, so the next pass does not hang on it again), its slot is free and the pass
     * goes on. The stuck thread itself cannot be stopped; once [AbandonableWork.MaxStuck] of them are still blocked the pass stops like a lost grant (the files after
     * stay unread and a later pass, or the next start, tries them) rather than start one more.
     */
    suspend fun run(
        pending: List<MusicTrack>,
        sourceOf: (MusicTrack) -> MusicTagSource,
        reader: MusicTagReader,
        readTimeoutMs: Long = ReadTimeoutMs,
        onBatch: suspend (List<Pair<MusicTrack, MusicTagResult>>) -> Unit,
    ): Boolean = coroutineScope {
        val gate = Semaphore(Parallelism)
        for (batch in pending.chunked(BatchSize)) {
            ensureActive()
            val results = batch.map { track ->
                async(Dispatchers.IO) {
                    gate.withPermit { track to readBounded(reader, sourceOf(track), readTimeoutMs) }
                }
            }.awaitAll()
            val reached = results.takeWhile { it.second !is MusicTagResult.Unavailable }
            if (reached.isNotEmpty()) onBatch(reached)
            if (reached.size < results.size) return@coroutineScope false
        }
        true
    }
}
