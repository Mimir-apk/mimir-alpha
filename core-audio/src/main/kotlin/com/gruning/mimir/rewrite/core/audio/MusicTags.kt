package com.gruning.mimir.rewrite.core.audio

/**
 * What a track's own tags say, each field null when the tag is missing or blank. A missing tag is never replaced here: the caller falls back
 * (the title to the file name, the artist and album to "Unknown Artist" and "Unknown Album" in the tree, see [buildMusicTree]), so "the tag was
 * absent" stays distinguishable from "the tag said Unknown".
 */
data class MusicTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
    /** The file says it is one of a compilation (an album of many artists): iTunes' `cpil`, ID3's `TCMP`. Only read as a hint: an album-artist tag still wins ([buildMusicTree]). */
    val compilation: Boolean = false,
)

/**
 * How a tag read ended for one file. [Read] can carry no tags at all (a file without any): that is still an answer, and it is remembered so a
 * rescan does not ask again. [Failed] means this file could not be read for tags (corrupt, unsupported) and is remembered the same way.
 * [Unavailable] means the file could not be reached at all (the folder grant was lost, the card was removed): that says nothing about the file, so it is
 * never remembered and the pass stops.
 */
internal sealed interface MusicTagResult {
    data class Read(val tags: MusicTags, val hasEmbeddedArt: Boolean) : MusicTagResult

    data object Failed : MusicTagResult

    data object Unavailable : MusicTagResult
}

/** Where a tag read finds the file's bytes: a plain file's path, or a document URI of a picked tree. */
internal sealed interface MusicTagSource {
    data class Path(val absolutePath: String) : MusicTagSource

    data class Document(val uri: String) : MusicTagSource
}

/** Reads the tags of one track. The Android edge ([AndroidMusicTagReader]) implements it with `MediaMetadataRetriever`; tests supply their own. Blocking: call on IO. */
internal interface MusicTagReader {
    fun read(source: MusicTagSource): MusicTagResult
}

/**
 * Turns the retriever's raw strings into [MusicTags], as pure rules so they can be asserted on the JVM.
 *
 * The retriever hands back whatever the container held: padded or blank strings, "3/12" for a track that is the third of twelve, a duration in
 * milliseconds as text. [keys] is the platform's `METADATA_KEY_*` set, named here so no Android class loads on the JVM.
 */
internal object MusicTagParse {
    const val KeyTrackNumber = 0
    const val KeyAlbum = 1
    const val KeyArtist = 2
    const val KeyDuration = 9
    const val KeyTitle = 7
    const val KeyAlbumArtist = 13
    const val KeyDiscNumber = 14
    const val KeyCompilation = 15

    /** A tag's text trimmed, or null when it is missing or blank. Control characters inside a tag (a stray NUL from a bad encoder) are dropped. */
    fun text(raw: String?): String? {
        val cleaned = raw?.filterNot { it == '\u0000' || (it.isISOControl() && it != ' ') }?.trim().orEmpty()
        return cleaned.ifEmpty { null }
    }

    /** "3", "03", "3/12" and " 3 / 12 " are track 3; zero, negative and non-numbers are no number. */
    fun number(raw: String?): Int? {
        val head = raw?.substringBefore('/')?.trim().orEmpty()
        return head.toIntOrNull()?.takeIf { it > 0 }
    }

    /** The duration in milliseconds, or null when it is missing, not a number or not positive. */
    fun durationMs(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    /** The compilation flag: "1" (what the platform reports for iTunes' `cpil` and ID3's `TCMP`), or "true"/"yes" from a container that spells it out. Anything else, or missing, is no. */
    fun compilation(raw: String?): Boolean = raw?.trim()?.lowercase() in setOf("1", "true", "yes")

    /** The tags of one file, asking [value] for each platform key. */
    fun tags(value: (Int) -> String?): MusicTags = MusicTags(
        title = text(value(KeyTitle)),
        artist = text(value(KeyArtist)),
        album = text(value(KeyAlbum)),
        albumArtist = text(value(KeyAlbumArtist)),
        trackNumber = number(value(KeyTrackNumber)),
        discNumber = number(value(KeyDiscNumber)),
        durationMs = durationMs(value(KeyDuration)),
        compilation = compilation(value(KeyCompilation)),
    )
}

/**
 * A file's identity for the tag cache: size and last-modified time, with the path (the track id) as the key beside them. Either may be unknown
 * (a provider that does not report it): a stamp with an unknown part is [complete] false and never proves a file is unchanged.
 */
internal data class MusicFileStamp(val sizeBytes: Long, val modifiedMillis: Long) {
    val complete: Boolean get() = sizeBytes >= 0L && modifiedMillis > 0L

    companion object {
        val Unknown = MusicFileStamp(sizeBytes = -1L, modifiedMillis = 0L)
    }
}

/** How far one file's tag read has got. */
internal enum class MusicTagState {
    /** Not read yet (a fresh scan, or the file changed since the read). */
    UNREAD,

    /** Read: [MusicTrackMeta.tags] is the answer, possibly empty. */
    READ,

    /** Tried and could not be read: not tried again until the file changes. */
    FAILED,
}

/**
 * Everything the index keeps beside a track's name: the stamp the cache is keyed on, how far the tag read has got, the tags, and whether the file
 * carries an embedded picture (so the UI knows before decoding whether there is any art to wait for).
 */
internal data class MusicTrackMeta(
    val stamp: MusicFileStamp = MusicFileStamp.Unknown,
    val state: MusicTagState = MusicTagState.UNREAD,
    val tags: MusicTags = MusicTags(),
    val hasEmbeddedArt: Boolean = false,
) {
    val needsRead: Boolean get() = state == MusicTagState.UNREAD
}

/**
 * The tag cache rules, pure. [carryOver] gives a fresh scan the answers an earlier index already holds for files that have not changed;
 * [tagPass] reads what is left.
 */
internal object MusicTagCache {
    /**
     * [fresh] with every track's meta taken from [previous] when the track id is the same and both stamps are complete and equal. Everything else is
     * UNREAD with the fresh stamp. A track whose stamp is incomplete is read again on each rescan: nothing proves it unchanged.
     */
    fun carryOver(previous: Map<String, MusicTrackMeta>, fresh: MusicIndex): MusicIndex {
        val merged = HashMap<String, MusicTrackMeta>(fresh.tracks.size * 2)
        for (track in fresh.tracks) {
            val stamp = fresh.meta[track.id]?.stamp ?: MusicFileStamp.Unknown
            val before = previous[track.id]
            merged[track.id] =
                if (before != null && stamp.complete && before.stamp == stamp && before.state != MusicTagState.UNREAD) {
                    before
                } else {
                    MusicTrackMeta(stamp = stamp)
                }
        }
        return fresh.copy(meta = merged)
    }

    /** The tracks of [index] whose tags still have to be read, in index order. */
    fun pending(index: MusicIndex): List<MusicTrack> = index.tracks.filter { index.meta[it.id]?.needsRead != false }

    /** [meta] with [result] applied, keeping its stamp. An unreachable file is left as it was. */
    fun applied(meta: MusicTrackMeta?, result: MusicTagResult): MusicTrackMeta {
        val base = meta ?: MusicTrackMeta()
        return when (result) {
            is MusicTagResult.Read -> base.copy(state = MusicTagState.READ, tags = result.tags, hasEmbeddedArt = result.hasEmbeddedArt)
            MusicTagResult.Failed -> base.copy(state = MusicTagState.FAILED, tags = MusicTags(), hasEmbeddedArt = false)
            MusicTagResult.Unavailable -> base
        }
    }
}
