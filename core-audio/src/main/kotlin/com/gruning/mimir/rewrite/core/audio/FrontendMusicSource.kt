package com.gruning.mimir.rewrite.core.audio

/**
 * Why the player chose Your music but the frontend music is playing the theme bed instead. Null in
 * [MimirFrontendMusic.customFallback] means nothing is wrong: the source is Mimir's music, or Your music is playing.
 *
 * Settings > Music's Source row says it in words, so a folder that cannot play is never a silent bed or a silent nothing.
 */
enum class CustomMusicFallback {
    /** Your music is chosen but no folder is. */
    NO_FOLDER,

    /** The folder is readable but holds no track Mimir can play. */
    EMPTY,

    /** The system no longer lets Mimir read the folder (revoked grant, removed volume). Choosing it again fixes it. */
    GRANT_LOST,

    /** The folder or its tracks could not be listed or opened. A rescan may fix it. */
    UNREADABLE,
}

/** The indexed tracks of the chosen folder, in index order. [loaded] is false until the index has been read or scanned. */
internal data class MusicPlaylist(
    val tree: String? = null,
    val scannedAtMillis: Long? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val loaded: Boolean = true,
)

/** What the frontend music plays right now: the theme's bed, or the folder's tracks in index order. */
internal sealed interface FrontendMusicPlan {
    data class Bed(val bed: MimirFrontendBed) : FrontendMusicPlan

    /**
     * [scannedAtMillis] names the index these tracks came from, so a playback failure is held against that index only. [start] is a pick from the Artist / Album tree: the
     * track the queue opens on (null for the folder's own order, which opens on its first track or, shuffled, a random one).
     */
    data class Folder(val tree: String, val scannedAtMillis: Long?, val tracks: List<MusicTrack>, val start: MusicStart? = null) : FrontendMusicPlan
}

/**
 * A pick from the Artist / Album tree: the plan opens on [trackId]. [serial] is the pick's own number, so a second pick of the very same track is a new request
 * and the same pick met again after a rescan is not.
 */
internal data class MusicStart(val trackId: String, val serial: Long)

/** The index in [FrontendMusicPlan.Folder.tracks] the plan opens on, or null when it names none (or names a track that is not in the queue). */
internal fun FrontendMusicPlan.Folder.startIndex(): Int? =
    start?.let { wanted -> tracks.indexOfFirst { it.id == wanted.trackId }.takeIf { it >= 0 } }

/**
 * Whether switching from [other] to this plan changes nothing audible: the same folder with the same tracks, whatever the
 * index stamp and whatever pick the plan carries (a new pick of a track in the queue is a jump, not a reload; see the engine's applyPlan).
 * A rescan that finds nothing new must not restart the music (the player may press Rescan freely).
 */
internal fun FrontendMusicPlan.playsTheSameAs(other: FrontendMusicPlan?): Boolean =
    this is FrontendMusicPlan.Folder && other is FrontendMusicPlan.Folder && tree == other.tree && tracks == other.tracks

/** The plan and, when it is the theme bed despite Your music being chosen, why. */
internal data class FrontendMusicChoice(val plan: FrontendMusicPlan, val fallback: CustomMusicFallback?)

/**
 * Tracks of [tree]'s index [scannedAtMillis] that could not be opened. It is cleared by a rescan (a new index) or a new
 * folder, so a failed folder does not retry on its own; Settings says why and what to do.
 */
internal data class FolderPlaybackFailure(val tree: String, val scannedAtMillis: Long?, val reason: CustomMusicFallback)

internal object FrontendMusicSourceResolver {
    /**
     * Which plan the frontend music plays, as a pure rule.
     *
     * Null means "not decidable yet": Your music is chosen with a folder whose index is still being read or rescanned, and
     * starting the bed now would only be swapped for the folder a moment later. Anything that cannot play falls back to the theme bed
     * with its reason, never to silence.
     */
    fun resolve(
        source: MimirMusicSource,
        bed: MimirFrontendBed,
        library: MusicLibraryState,
        playlist: MusicPlaylist,
        failure: FolderPlaybackFailure?,
        /** The player's last pick from the Artist / Album tree; it plays instead of the folder's own order while the folder still holds it. */
        selection: MusicSelection? = null,
    ): FrontendMusicChoice? {
        val themeBed = FrontendMusicPlan.Bed(bed)
        if (source == MimirMusicSource.MIMIR) return FrontendMusicChoice(themeBed, null)
        val tree = library.treeUri ?: return FrontendMusicChoice(themeBed, CustomMusicFallback.NO_FOLDER)
        // A scan in flight holds whatever is playing: the decision lands when it finishes, so a rescan never flickers the music.
        if (library.scanning) return null
        if (playlist.tree != tree || !playlist.loaded) return null
        library.error?.let { return FrontendMusicChoice(themeBed, it.asFallback()) }
        failure?.takeIf { it.tree == tree && it.scannedAtMillis == playlist.scannedAtMillis }
            ?.let { return FrontendMusicChoice(themeBed, it.reason) }
        if (playlist.tracks.isEmpty()) return FrontendMusicChoice(themeBed, CustomMusicFallback.EMPTY)
        selectedFolder(selection, tree, playlist)?.let { return FrontendMusicChoice(it, null) }
        return FrontendMusicChoice(FrontendMusicPlan.Folder(tree, playlist.scannedAtMillis, generalTracks(playlist.tracks)), null)
    }

    /**
     * The plan for a pick from the Artist / Album tree: its album's tracks the index still holds, in the album's order, opening on the track pressed. Null
     * (the folder's own order plays) when the pick is for another folder or its track is gone: a rescan that took the album away drops the pick, it never
     * plays a queue that is not there.
     */
    internal fun selectedFolder(selection: MusicSelection?, tree: String, playlist: MusicPlaylist): FrontendMusicPlan.Folder? {
        if (selection == null || selection.tree != tree) return null
        val byId = playlist.tracks.associateBy { it.id }
        val queue = selection.trackIds.mapNotNull { byId[it] }
        if (queue.none { it.id == selection.startId }) return null
        return FrontendMusicPlan.Folder(tree, playlist.scannedAtMillis, queue, MusicStart(selection.startId, selection.serial))
    }

    /**
     * What general Your-music playback plays: the tracks in the folder's root, in index order. Platform folders (music/snes,
     * music/ps2) are reserved for giving a platform its own music in a later slice. A folder holding only platform folders
     * plays all of them rather than reading as empty, since it does have music.
     */
    internal fun generalTracks(tracks: List<MusicTrack>): List<MusicTrack> =
        tracks.filter { it.folder.isEmpty() }.ifEmpty { tracks }

    private fun MusicLibraryError.asFallback(): CustomMusicFallback = when (this) {
        MusicLibraryError.GRANT_LOST -> CustomMusicFallback.GRANT_LOST
        MusicLibraryError.UNREADABLE -> CustomMusicFallback.UNREADABLE
    }
}

/** A track's title as the Now playing row and the chip read it: its file name without the extension. */
internal fun musicTrackTitle(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return (if (dot > 0) fileName.substring(0, dot) else fileName).trim().ifEmpty { fileName }
}

/**
 * What the player is on, for the Now playing row: the bed, or the folder track at [cursor]. Null when there is none. [labelOf] names a folder track:
 * its title tag where the library has read one, else its file name without the extension, and its artist tag where it has one.
 */
internal fun FrontendMusicPlan.nowPlayingAt(cursor: Int, labelOf: (MusicTrack) -> YourMusicLabel = { YourMusicLabel(musicTrackTitle(it.name)) }): MimirNowPlaying? = when (this) {
    is FrontendMusicPlan.Bed -> MimirNowPlaying(bed.title, MimirMusicSource.MIMIR)
    is FrontendMusicPlan.Folder -> tracks.getOrNull(cursor)?.let {
        val label = labelOf(it)
        MimirNowPlaying(label.title, MimirMusicSource.CUSTOM, singleTrack = tracks.size == 1, trackId = it.id, artist = label.artist)
    }
}

/** The track after [cursor] in index order, wrapping at the end. A bed has one piece, so it is always 0. */
internal fun FrontendMusicPlan.nextCursor(cursor: Int): Int = when (this) {
    is FrontendMusicPlan.Bed -> 0
    is FrontendMusicPlan.Folder -> if (tracks.isEmpty()) 0 else (cursor + 1) % tracks.size
}

/** The track before [cursor] in index order, wrapping at the start. A bed has one piece, so it is always 0. */
internal fun FrontendMusicPlan.previousCursor(cursor: Int): Int = when (this) {
    is FrontendMusicPlan.Bed -> 0
    is FrontendMusicPlan.Folder -> if (tracks.isEmpty()) 0 else (cursor - 1 + tracks.size) % tracks.size
}

/** A "previous" press more than this far into a track restarts it; sooner goes to the track before. */
internal const val PreviousRestartsAfterMs = 3_000
