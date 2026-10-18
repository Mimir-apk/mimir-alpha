package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.MusicCatalogTrack
import com.gruning.mimir.rewrite.core.audio.MusicTree
import com.gruning.mimir.rewrite.core.audio.MusicTreeAlbum
import com.gruning.mimir.rewrite.core.audio.MusicTreeArtist

/**
 * The Artist / Album browser's pure side : where the player is in Artists > Albums > Tracks, what rows that shows, and what each press and each Back
 * does. No Compose and no Android types, so all of it runs on the JVM; [MusicLibraryBrowserPage] draws it and the music panel's controller (Main) and the Bottom
 * player drive it.
 *
 * The cursor is kept by stable key (an artist's or album's folded name, a track's id), never by index: tags keep arriving while the browser is open and the
 * rows re-sort around the cursor, and Back lands on the very artist or album the player came from.
 */
internal enum class MusicBrowseLevel(val eyebrow: String) {
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    TRACKS("Tracks"),
}

/** Where the browser is. [selectedKey] is the row under the cursor at [level]; [artistKey] and [albumKey] say which group the level is inside. */
internal data class MusicBrowseState(
    val level: MusicBrowseLevel = MusicBrowseLevel.ARTISTS,
    val artistKey: String? = null,
    val albumKey: String? = null,
    val selectedKey: String? = null,
)

/**
 * The key of the "All music" row, the first row of Artists. It holds a newline, which the tree's cleaned names never do (they collapse every run of whitespace to a space),
 * so no artist's key can equal it.
 */
internal const val MusicBrowseAllKey = "\nall-music\n"

/** The "All music" row's words. Chrome, not an artist's name: the theme draws it as it draws every control word, and no artist called "All music" can be mistaken for it. */
internal const val MusicBrowseAllTitle = "All music"

/**
 * One row of the list. [key] is stable across rebuilds; [artTrackId] is the track whose picture stands for the row, if any is known. [picked] marks the row the player's
 * current pick lives under (the artist or the album that is the queue; "All music" while no pick is active): it draws as the selected row, apart from the cursor.
 */
internal data class MusicBrowseRow(
    val key: String,
    val title: String,
    /** The title is a name (drawn in its own case); false for a word of the panel's own ("All music"). */
    val titleAuthored: Boolean = true,
    val picked: Boolean = false,
    /** A second line of chrome (counts, durations); null for none. */
    val subtitle: String? = null,
    /** The subtitle is a name (an artist: its own case); false for chrome counts and durations. */
    val subtitleAuthored: Boolean = false,
    /** At the right edge: a track's length. Null for none. */
    val trailing: String? = null,
    /** At the left edge of a track row: its number ("3", or "2-05" on a multi-disc album). Null for artists and albums. */
    val number: String? = null,
    val artTrackId: String? = null,
    /** The row is the track on air. */
    val playing: Boolean = false,
    /** A press opens the next level (an artist, an album); false for a track, which plays. */
    val opens: Boolean = true,
)

/** What a press on a row does. */
internal sealed interface MusicBrowseAction {
    data class Go(val state: MusicBrowseState) : MusicBrowseAction

    /** Play [trackId], with the rest of its album behind it (the library's playFromLibrary). */
    data class Play(val trackId: String) : MusicBrowseAction

    /** "All music": clear the pick and play the whole folder again, in general playback's own order. */
    data object PlayAll : MusicBrowseAction

    data object None : MusicBrowseAction
}

/** The browser as the screen draws it. */
internal data class MusicBrowseUi(
    val level: MusicBrowseLevel,
    val rows: List<MusicBrowseRow>,
    val selectedKey: String?,
    /** The artist's or album's name under the level word (authored: drawn in its own case); null at the top. */
    val context: String? = null,
    /** Under the context at Tracks: the album's artist. */
    val contextSub: String? = null,
    /** "Reading tags 120 of 446" while the tags are still arriving; null when they have. */
    val reading: String? = null,
    /** What to say when [rows] is empty; null when there are rows. */
    val empty: String? = null,
    val canGoBack: Boolean = false,
)

internal const val MusicBrowseEmptyCopy = "No music to browse yet."

/** "1 album", "3 albums". */
internal fun musicBrowseCount(count: Int, singular: String): String = if (count == 1) "1 $singular" else "$count ${singular}s"

/** A length as the track rows show it: m:ss, or h:mm:ss from an hour. Null for an unknown length. */
internal fun musicBrowseDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val seconds = (durationMs + 500L) / 1000L
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

/** Two digits, locale-free: the platform formatter would draw another script's digits on some devices. */
private fun two(n: Long): String = n.toString().padStart(2, '0')

private fun allMusicRow(tree: MusicTree, pickActive: Boolean) = MusicBrowseRow(
    key = MusicBrowseAllKey,
    title = MusicBrowseAllTitle,
    titleAuthored = false,
    picked = !pickActive,
    subtitle = musicBrowseCount(tree.trackCount, "track"),
    opens = false,
)

private fun artistRow(artist: MusicTreeArtist, picked: Boolean) = MusicBrowseRow(
    key = artist.key,
    title = artist.name,
    picked = picked,
    subtitle = musicBrowseCount(artist.albums.size, "album") + " · " + musicBrowseCount(artist.trackCount, "track"),
    artTrackId = artist.artTrackId,
)

private fun albumRow(album: MusicTreeAlbum, picked: Boolean) = MusicBrowseRow(
    key = album.key,
    title = album.name,
    picked = picked,
    subtitle = listOfNotNull(musicBrowseCount(album.tracks.size, "track"), musicBrowseDuration(album.totalDurationMs)).joinToString(" · "),
    artTrackId = album.artTrackId,
)

private fun trackRow(track: MusicCatalogTrack, album: MusicTreeAlbum, artist: MusicTreeArtist, nowPlayingId: String?): MusicBrowseRow {
    val number = track.trackNumber?.let { n ->
        if (album.discCount > 1) "${track.discNumber ?: 1}-${two(n.toLong())}" else n.toString()
    }
    // A compilation's tracks name their own artist when it is not the album's.
    val own = track.artist?.trim()?.takeIf { it.isNotEmpty() && !it.equals(artist.name, ignoreCase = true) }
    return MusicBrowseRow(
        key = track.id,
        title = track.title,
        subtitle = own,
        subtitleAuthored = true,
        trailing = musicBrowseDuration(track.durationMs),
        number = number,
        artTrackId = track.id.takeIf { track.hasEmbeddedArt },
        playing = track.id == nowPlayingId,
        opens = false,
    )
}

/**
 * The state made true to [tree]: a group that has gone (a rescan removed it, a tag moved its tracks) pops the browser up to the level that still exists, and a
 * cursor on a row that has gone falls to the first row. Applied every time the tree changes, so the browser never points into nothing.
 */
internal fun MusicBrowseState.resolved(tree: MusicTree): MusicBrowseState {
    var state = this
    if (state.level != MusicBrowseLevel.ARTISTS && tree.artist(state.artistKey) == null) {
        state = MusicBrowseState(MusicBrowseLevel.ARTISTS, selectedKey = state.artistKey)
    }
    if (state.level == MusicBrowseLevel.TRACKS && tree.artist(state.artistKey)?.album(state.albumKey) == null) {
        state = MusicBrowseState(MusicBrowseLevel.ALBUMS, state.artistKey, selectedKey = state.albumKey)
    }
    val rows = musicBrowseRows(tree, state, nowPlayingId = null)
    return if (rows.any { it.key == state.selectedKey }) state else state.copy(selectedKey = rows.firstOrNull()?.key)
}

/**
 * The rows [state] shows over [tree]. [pickedTrackId] is the track the player pressed in the tree, whose album is the queue now (null while no pick is active and the
 * whole folder plays): the artist and album it lives under are the rows that draw as selected, and "All music" is selected while there is no pick. A pick whose track the
 * tree no longer holds counts as no pick. Every row key appears once, however the tags repeat.
 */
internal fun musicBrowseRows(tree: MusicTree, state: MusicBrowseState, nowPlayingId: String?, pickedTrackId: String? = null): List<MusicBrowseRow> {
    val artist = tree.artist(state.artistKey)
    val pickedArtist = pickedTrackId?.let(tree::artistOf)
    val pickedAlbum = pickedTrackId?.let(tree::albumOf)
    val rows = when (state.level) {
        // An empty library has no "All music" either: there is nothing for it to play.
        MusicBrowseLevel.ARTISTS -> if (tree.isEmpty) emptyList() else listOf(allMusicRow(tree, pickActive = pickedArtist != null)) +
            tree.artists.map { artistRow(it, picked = it.key == pickedArtist?.key) }
        MusicBrowseLevel.ALBUMS -> artist?.albums.orEmpty().map { albumRow(it, picked = artist?.key == pickedArtist?.key && it.key == pickedAlbum?.key) }
        MusicBrowseLevel.TRACKS -> {
            val album = artist?.album(state.albumKey)
            if (artist == null || album == null) emptyList() else album.tracks.map { trackRow(it, album, artist, nowPlayingId) }
        }
    }
    return rows.distinctBy { it.key }
}

/**
 * Everything the screen draws. [tagsRead] of [trackCount] is the progress of the tag pass: while it is short, the top says so, because an artist or an album
 * may still change (a track with no tag read yet is filed under Unknown Artist until it is).
 */
internal fun musicBrowseUi(
    tree: MusicTree,
    state: MusicBrowseState,
    nowPlayingId: String?,
    tagsRead: Int,
    trackCount: Int,
    pickedTrackId: String? = null,
): MusicBrowseUi {
    val artist = tree.artist(state.artistKey)
    val album = artist?.album(state.albumKey)
    val rows = musicBrowseRows(tree, state, nowPlayingId, pickedTrackId)
    return MusicBrowseUi(
        level = state.level,
        rows = rows,
        selectedKey = state.selectedKey,
        context = when (state.level) {
            MusicBrowseLevel.ARTISTS -> null
            MusicBrowseLevel.ALBUMS -> artist?.name
            MusicBrowseLevel.TRACKS -> album?.name
        },
        contextSub = if (state.level == MusicBrowseLevel.TRACKS) artist?.name else null,
        reading = if (trackCount > 0 && tagsRead < trackCount) "Reading tags $tagsRead of $trackCount" else null,
        empty = if (rows.isEmpty()) MusicBrowseEmptyCopy else null,
        canGoBack = state.level != MusicBrowseLevel.ARTISTS,
    )
}

/** The cursor [delta] rows along (down is positive), held at the first and last row: the panel never wraps. */
internal fun MusicBrowseState.moved(rows: List<MusicBrowseRow>, delta: Int): MusicBrowseState {
    if (rows.isEmpty()) return this
    val at = rows.indexOfFirst { it.key == selectedKey }.let { if (it < 0) 0 else it }
    val to = (at + delta).coerceIn(0, rows.lastIndex)
    return copy(selectedKey = rows[to].key)
}

/** What a press on the row [key] does: an artist opens its albums, an album its tracks, a track plays. */
internal fun MusicBrowseState.pressed(tree: MusicTree, key: String?, nowPlayingId: String?): MusicBrowseAction {
    if (key == null) return MusicBrowseAction.None
    return when (level) {
        MusicBrowseLevel.ARTISTS -> {
            if (key == MusicBrowseAllKey) return MusicBrowseAction.PlayAll
            val artist = tree.artist(key) ?: return MusicBrowseAction.None
            MusicBrowseAction.Go(MusicBrowseState(MusicBrowseLevel.ALBUMS, artistKey = artist.key, selectedKey = artist.albums.firstOrNull()?.key))
        }
        MusicBrowseLevel.ALBUMS -> {
            val album = tree.artist(artistKey)?.album(key) ?: return MusicBrowseAction.None
            // An album that holds the track on air opens with the cursor on it.
            val start = album.tracks.firstOrNull { it.id == nowPlayingId } ?: album.tracks.firstOrNull()
            MusicBrowseAction.Go(MusicBrowseState(MusicBrowseLevel.TRACKS, artistKey, album.key, start?.id))
        }
        MusicBrowseLevel.TRACKS -> {
            val album = tree.artist(artistKey)?.album(albumKey) ?: return MusicBrowseAction.None
            if (album.tracks.any { it.id == key }) MusicBrowseAction.Play(key) else MusicBrowseAction.None
        }
    }
}

/** One level up with the cursor on the group the player came from, or null from the top (the browser closes). */
internal fun MusicBrowseState.back(): MusicBrowseState? = when (level) {
    MusicBrowseLevel.ARTISTS -> null
    MusicBrowseLevel.ALBUMS -> MusicBrowseState(MusicBrowseLevel.ARTISTS, selectedKey = artistKey)
    MusicBrowseLevel.TRACKS -> MusicBrowseState(MusicBrowseLevel.ALBUMS, artistKey, selectedKey = albumKey)
}
