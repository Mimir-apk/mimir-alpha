package com.gruning.mimir.rewrite.core.audio

import java.util.Locale

/** What the tree calls an artist and an album whose tag is missing. Used only for a missing tag: a tag that says "Unknown Artist" is grouped as any other name is. */
const val UnknownArtistName = "Unknown Artist"
const val UnknownAlbumName = "Unknown Album"

/** The artist a compilation's tracks are filed under when they name no album artist: the usual name for it, so an album of many artists is one entry and not one per track. */
const val VariousArtistsName = "Various Artists"

/**
 * The library as Artists > Albums > Tracks , built from the catalog by [buildMusicTree] as a pure function.
 *
 * Every group has a [key] that is stable across rebuilds (tags arriving, a rescan): the folded name, so a cursor that remembers "the artist I was on" finds
 * it again after the tags around it change. A name differing only in case or spacing is the same group ("ABBA" and "Abba "); the spelling shown is the
 * first one the index met.
 */
data class MusicTree(val artists: List<MusicTreeArtist> = emptyList()) {
    val trackCount: Int get() = artists.sumOf { it.trackCount }

    val isEmpty: Boolean get() = artists.isEmpty()

    fun artist(key: String?): MusicTreeArtist? = key?.let { wanted -> artists.firstOrNull { it.key == wanted } }

    /** The album that holds the track [trackId], or null when no album does. The queue behind a press on a track is its album. */
    fun albumOf(trackId: String): MusicTreeAlbum? =
        artists.firstNotNullOfOrNull { artist -> artist.albums.firstOrNull { album -> album.tracks.any { it.id == trackId } } }

    /** The artist whose album holds the track [trackId], or null when none does. */
    fun artistOf(trackId: String): MusicTreeArtist? =
        artists.firstOrNull { artist -> artist.albums.any { album -> album.tracks.any { it.id == trackId } } }
}

data class MusicTreeArtist(
    val key: String,
    val name: String,
    /** The tags named no artist for these tracks. */
    val isUnknown: Boolean,
    val albums: List<MusicTreeAlbum>,
) {
    val trackCount: Int get() = albums.sumOf { it.tracks.size }

    fun album(key: String?): MusicTreeAlbum? = key?.let { wanted -> albums.firstOrNull { it.key == wanted } }

    /** The first track, in order, known to carry a picture: the artist's art, if any. */
    val artTrackId: String? get() = albums.firstNotNullOfOrNull { it.artTrackId }
}

data class MusicTreeAlbum(
    val key: String,
    val name: String,
    /** The tags named no album for these tracks. */
    val isUnknown: Boolean,
    /** In play order: disc, then track number, then title. */
    val tracks: List<MusicCatalogTrack>,
) {
    /** More than one disc: the track list numbers them "2-05". */
    val discCount: Int get() = tracks.mapTo(HashSet()) { it.discNumber ?: 1 }.size

    val totalDurationMs: Long? get() = tracks.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()

    /** The first track known to carry a picture, else null. */
    val artTrackId: String? get() = tracks.firstOrNull { it.hasEmbeddedArt }?.id
}

/**
 * Groups [tracks] by artist, then album. A track's artist is its album artist tag, else [VariousArtistsName] when the file is marked as one of a compilation,
 * else its artist tag, else [UnknownArtistName]; its album is its album tag else [UnknownAlbumName]. (The album artist tag wins over the compilation mark: an album
 * that names its own artist is that artist's, whatever its tracks say.) Artists and albums are in name order (ignoring case, digits compared as numbers so "Album 2"
 * comes before "Album 10"), the unknown ones last; an album's tracks are ordered by disc (a missing disc is disc 1), then track number (a missing number after the
 * numbered ones), then title. A track id appears once: a repeat is dropped, so a row keyed by it can never be keyed twice.
 */
fun buildMusicTree(tracks: List<MusicCatalogTrack>): MusicTree {
    class AlbumBuilder(val key: String, val name: String, val unknown: Boolean) {
        val tracks = ArrayList<MusicCatalogTrack>()
    }

    class ArtistBuilder(val key: String, val name: String, val unknown: Boolean) {
        val albums = LinkedHashMap<String, AlbumBuilder>()
    }

    val artists = LinkedHashMap<String, ArtistBuilder>()
    for (track in tracks.distinctBy { it.id }) {
        val artistName = MusicNames.clean(track.albumArtist)
            ?: if (track.compilation) VariousArtistsName else MusicNames.clean(track.artist)
        val artistKey = artistName?.let(MusicNames::fold) ?: UnknownKey
        val artist = artists.getOrPut(artistKey) { ArtistBuilder(artistKey, artistName ?: UnknownArtistName, artistName == null) }
        val albumName = MusicNames.clean(track.album)
        val albumKey = albumName?.let(MusicNames::fold) ?: UnknownKey
        artist.albums.getOrPut(albumKey) { AlbumBuilder(albumKey, albumName ?: UnknownAlbumName, albumName == null) }.tracks += track
    }
    val built = artists.values.map { artist ->
        MusicTreeArtist(
            key = artist.key,
            name = artist.name,
            isUnknown = artist.unknown,
            albums = artist.albums.values
                .map { MusicTreeAlbum(it.key, it.name, it.unknown, it.tracks.sortedWith(TrackOrder)) }
                .sortedWith(compareBy<MusicTreeAlbum> { it.isUnknown }.thenComparator { a, b -> MusicNames.compare(a.key, b.key) }),
        )
    }.sortedWith(compareBy<MusicTreeArtist> { it.isUnknown }.thenComparator { a, b -> MusicNames.compare(a.key, b.key) })
    return MusicTree(built)
}

/** The key of the group a missing tag falls in. It cannot collide with a folded name: those are never empty. */
private const val UnknownKey = ""

/** Disc, then track number, then title, then file name: the order an album plays in. Total, so the tree is the same on every rebuild. */
private val TrackOrder: Comparator<MusicCatalogTrack> = Comparator { a, b ->
    val disc = (a.discNumber ?: 1).compareTo(b.discNumber ?: 1)
    if (disc != 0) return@Comparator disc
    val number = (a.trackNumber ?: Int.MAX_VALUE).compareTo(b.trackNumber ?: Int.MAX_VALUE)
    if (number != 0) return@Comparator number
    val title = MusicNames.compare(MusicNames.fold(a.title), MusicNames.fold(b.title))
    if (title != 0) return@Comparator title
    val file = MusicNames.compare(MusicNames.fold(a.fileName), MusicNames.fold(b.fileName))
    if (file != 0) return@Comparator file
    a.id.compareTo(b.id)
}

/** Name handling for the tree: cleaning, folding for grouping, and a natural order. */
internal object MusicNames {
    /** [raw] trimmed with inner runs of whitespace collapsed, or null when blank. */
    fun clean(raw: String?): String? {
        val collapsed = raw?.trim()?.replace(WhitespaceRun, " ").orEmpty()
        return collapsed.ifEmpty { null }
    }

    /** The grouping key of a cleaned name: case-folded, so "ABBA" and "Abba" are one artist. */
    fun fold(name: String): String = name.lowercase(Locale.ROOT)

    private val WhitespaceRun = Regex("\\s+")

    /** Natural order of two folded names: letters by code point, runs of digits by value ("album 2" < "album 10"). */
    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]
            val y = b[j]
            if (x.isDigit() && y.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val left = a.substring(i, ie).trimStart('0')
                val right = b.substring(j, je).trimStart('0')
                if (left.length != right.length) return left.length.compareTo(right.length)
                val cmp = left.compareTo(right)
                if (cmp != 0) return cmp
                i = ie
                j = je
            } else {
                if (x != y) return x.compareTo(y)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
