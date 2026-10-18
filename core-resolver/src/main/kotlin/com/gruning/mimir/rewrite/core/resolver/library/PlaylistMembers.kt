package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import java.io.File

/**
 * The discs an `.m3u` playlist lists -- so deleting the playlist can say that they stay.
 *
 * **A playlist's members are never deleted with it.** The playlist is a text file that names discs; the discs are the
 * game. Deleting the `.m3u` removes the playlist and nothing else, and the card says how many discs it left, so a player
 * who expected a clean sweep is told the discs are still there rather than finding them later.
 *
 * Parsed with the scanner's own syntax ([M3uPlaylistResolver.parseEntries] and [M3uPlaylistResolver.normalizeRelative]),
 * not a second parser: a playlist must mean the same thing here as it does to the library. Nothing here moves or
 * deletes anything; it only reads (a file of at most [M3uPlaylistResolver.MAX_PLAYLIST_BYTES]) and counts.
 */
object PlaylistMembers {

    /** What a delete of some playlists leaves: how many playlists are in it, and how many distinct listed discs stay. */
    data class Staying(val playlists: Int, val members: Int)

    /**
     * The existing files [playlist] lists. A bare or relative entry resolves against the playlist's own folder
     * (traversal above it is refused by `normalizeRelative`), an absolute or `file://` entry names its own path, and a
     * `content://` entry, a missing file or the playlist itself is not a member.
     */
    fun membersOf(playlist: File): List<File> {
        if (!playlist.isFile || playlist.length() > M3uPlaylistResolver.MAX_PLAYLIST_BYTES) return emptyList()
        val folder = playlist.parentFile ?: return emptyList()
        val body = runCatching { playlist.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return M3uPlaylistResolver.parseEntries(body)
            .mapNotNull { entry ->
                when {
                    entry.startsWith("content://") -> null
                    entry.startsWith("file://") -> File(entry.removePrefix("file://"))
                    entry.startsWith('/') -> File(entry)
                    else -> M3uPlaylistResolver.normalizeRelative(entry)?.let { File(folder, it) }
                }
            }
            .filter { it.isFile && !it.absoluteFile.normalize().path.equals(playlist.absoluteFile.normalize().path, ignoreCase = true) }
            .distinctBy { it.absoluteFile.normalize().path.lowercase() }
    }

    /**
     * Of the playlists in [operation] (a delete's whole list), the discs that stay: every listed file the delete is not
     * also taking. Null when the delete includes no playlist, or none of its playlists lists anything that is there.
     */
    fun staying(operation: List<String>): Staying? {
        val playlists = operation.filter { it.endsWith(".m3u", ignoreCase = true) }
        if (playlists.isEmpty()) return null
        val deleting = operation.mapTo(HashSet()) { File(it).absoluteFile.normalize().path.lowercase() }
        val members = playlists
            .flatMap { membersOf(File(it)) }
            .map { it.absoluteFile.normalize().path }
            .filter { it.lowercase() !in deleting }
            .distinctBy { it.lowercase() }
        return if (members.isEmpty()) null else Staying(playlists.size, members.size)
    }
}
