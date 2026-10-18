package com.gruning.mimir.rewrite.core.audio

import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * One track the scan found: where it lives in the folder ([folder], empty for the folder's own root and for any folder that is not a platform
 * folder, and the platform's id for a platform folder), its file [name], and the [id] a later playback session opens it by (the document id for
 * a system-picker tree, the absolute path for a Mimir-browser folder). This is the track's PLAYBACK identity and nothing else: its tags live beside it
 * in [MusicIndex.meta], so reading them never changes what the player is playing.
 */
internal data class MusicTrack(val folder: String, val name: String, val id: String)

/**
 * What a scan of the Custom music folder found. [meta] holds, per track id, the file stamp the tag cache is keyed on and whatever tags have been read
 * ([MusicTrackMeta]); a fresh scan carries stamps only, and [MusicTagCache.carryOver] fills in what an earlier index already knew.
 */
internal data class MusicIndex(
    val tree: String,
    val scannedAtMillis: Long,
    val tracks: List<MusicTrack>,
    val meta: Map<String, MusicTrackMeta> = emptyMap(),
) {
    val trackCount: Int get() = tracks.size
}

/**
 * One child of a folder, as the two backends (a system tree, a plain directory) both report it. [sizeBytes] and [modifiedMillis] are the tag cache's key: unknown (-1, 0) when the backend does not say.
 * [canonicalId] is what a folder IS for the walk's visited set when [id] is not enough: a plain folder reached through a link has a different path each way round, its canonical path
 * is one. Null means [id] already is the identity (a document id).
 */
internal data class MusicEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = -1L,
    val modifiedMillis: Long = 0L,
    val canonicalId: String? = null,
) {
    /** The key the walk records a folder under, so a folder reached twice (a provider's link back to a parent, a symlink loop) is read once. */
    val visitKey: String get() = canonicalId ?: id
}

/** How a scan ended. */
internal sealed interface MusicScanResult {
    data class Scanned(val index: MusicIndex) : MusicScanResult

    /** The system no longer lets Mimir read the folder. */
    data object GrantLost : MusicScanResult

    /** The folder could not be listed, for any other reason. */
    data object Unreadable : MusicScanResult
}

/**
 * The scan of the Custom music folder, over an abstract directory listing so the SAME rules run on a system-picker tree, on
 * a plain folder from Mimir's own browser, and in a JVM test.
 *
 * **The layout.** Every folder under the picked one is read, to [MaxScanDepth] levels, so `Artist/Album/01 Song.mp3` is found. A folder that holds a
 * `.nomedia` file is left out with everything under it, the picked folder included (the Android convention for "not music"). Files directly in the
 * folder are general tracks; an immediate sub-folder that [MusicPlatformFolders] says is a platform's (`snes`, `ps2`, `pcgames`: the names the game
 * scanner knows) holds that platform's tracks, everything under it too, and they carry its id in [MusicTrack.folder]. **Only the known platform names
 * count**: an artist's folder (`Queen`, `ABBA`, `U2`) is ordinary music wherever it sits, and its tracks play in general playback. A band whose name is also a platform's
 * (`Genesis`, `Saturn`) does read as that platform's folder: its tracks are still indexed and browsable, but general playback (see [FrontendMusicSourceResolver])
 * leaves them to the platform slice when the picked folder has tracks of its own. Formats: mp3, m4a, ogg, flac, wav, opus, oga, aac, adts, m4b and mp4. A `.mp4` that holds
 * video is indexed like any other and fails at playback, where the player skips it.
 *
 * **Bounds.** At most [MaxIndexedTracks] tracks, [MaxScanDepth] folder levels and [MaxVisitedEntries] listed entries (files and folders of any kind),
 * so a vast tree ends in finite time with the first of it indexed. A folder is read **once**, however many ways the provider reaches it (a link back to
 * a parent, a symlink loop, the same folder under two parents), and so is a track id; and the walk stops at once when the scan's job is cancelled.
 */
internal object MusicLibraryScanner {
    /** A folder with more tracks than this keeps the first of them; the index is a list of names, not a database. */
    const val MaxIndexedTracks = 20_000

    /** How many folder levels below the picked folder are read: the picked folder is level 0, so `Artist/Album/Disc 1` is level 3. */
    const val MaxScanDepth = 6

    /** How many directory entries (any kind) one scan looks at before it stops. */
    const val MaxVisitedEntries = 100_000

    /** The marker file that keeps a folder and everything under it out of the library. */
    const val NoMediaName = ".nomedia"

    private val SupportedExtensions = setOf("mp3", "m4a", "ogg", "flac", "wav", "opus", "oga", "aac", "adts", "m4b", "mp4")

    fun isSupportedMusicFile(name: String): Boolean {
        // Hidden files and the `._name` resource forks a Mac leaves beside every file are not music, whatever they end in.
        if (name.startsWith(".")) return false
        val dot = name.lastIndexOf('.')
        if (dot < 1 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in SupportedExtensions
    }

    private fun hasNoMedia(entries: List<MusicEntry>): Boolean =
        entries.any { !it.isDirectory && it.name.equals(NoMediaName, ignoreCase = true) }

    /** What one scan has gathered so far, and how much of its budget is left. */
    private class Walk(val platformFolders: MusicPlatformFolders, val isCancelled: () -> Boolean) {
        val tracks = ArrayList<MusicTrack>()
        val meta = HashMap<String, MusicTrackMeta>()
        var visited = 0

        /** The folders already read (by [MusicEntry.visitKey]) and the track ids already indexed: each is walked or indexed once. */
        val seenFolders = HashSet<String>()
        val seenTracks = HashSet<String>()

        val full: Boolean get() = tracks.size >= MaxIndexedTracks || visited >= MaxVisitedEntries
    }

    /**
     * Scans [rootId] with [list], which returns a folder's children or null when it cannot be read, and may throw
     * [SecurityException] for a revoked grant. An unreadable ROOT ends the scan; an unreadable sub-folder is skipped and the
     * rest is still indexed. [platformFolders] names the top-level folders that are a platform's; [isCancelled] is asked before every entry and a true
     * ends the walk with a `CancellationException` (a scan whose job was cancelled has no one to hand an index to). [rootKey] is the picked folder's own
     * visit key, so a link back to it is not read again.
     */
    fun scan(
        tree: String,
        rootId: String,
        nowMillis: Long,
        list: (String) -> List<MusicEntry>?,
        platformFolders: MusicPlatformFolders = MusicPlatformFolders.None,
        isCancelled: () -> Boolean = { false },
        rootKey: String = rootId,
    ): MusicScanResult {
        val root = try {
            list(rootId) ?: return MusicScanResult.Unreadable
        } catch (_: SecurityException) {
            return MusicScanResult.GrantLost
        } catch (_: RuntimeException) {
            return MusicScanResult.Unreadable
        }
        val walk = Walk(platformFolders, isCancelled)
        walk.seenFolders += rootKey
        if (!hasNoMedia(root)) walkFolder(root, depth = 0, platform = "", walk = walk, list = list)
        return MusicScanResult.Scanned(MusicIndex(tree, nowMillis, walk.tracks, walk.meta))
    }

    /**
     * Indexes [entries], the children of a folder [depth] levels below the picked one, in name order with each folder's tracks in its place.
     * [platform] is the id the tracks carry.
     */
    private fun walkFolder(entries: List<MusicEntry>, depth: Int, platform: String, walk: Walk, list: (String) -> List<MusicEntry>?) {
        for (entry in entries.sortedBy { it.name.lowercase() }) {
            if (walk.isCancelled()) throw CancellationException("the music scan was cancelled")
            if (walk.full) return
            walk.visited += 1
            when {
                !entry.isDirectory && isSupportedMusicFile(entry.name) -> {
                    // A document the provider lists twice is one track.
                    if (!walk.seenTracks.add(entry.id)) continue
                    walk.tracks += MusicTrack(platform, entry.name, entry.id)
                    walk.meta[entry.id] = MusicTrackMeta(stamp = MusicFileStamp(entry.sizeBytes, entry.modifiedMillis))
                }
                entry.isDirectory && depth < MaxScanDepth -> {
                    // A folder already read (a link back to a parent, a symlink loop, the same folder under two parents) is not read again.
                    if (!walk.seenFolders.add(entry.visitKey)) continue
                    val children = try {
                        list(entry.id)
                    } catch (_: RuntimeException) {
                        null
                    } ?: continue
                    if (hasNoMedia(children)) continue
                    // Only an immediate sub-folder can be a platform folder; deeper folders carry their parent's id.
                    val childPlatform = if (depth == 0) walk.platformFolders.idFor(entry.name.trim()) ?: "" else platform
                    walkFolder(children, depth + 1, childPlatform, walk, list)
                }
            }
        }
    }

    /** The listing of a plain folder: the backend for a path from Mimir's own browser. Null when it cannot be read. */
    fun listPlainDirectory(directoryPath: String): List<MusicEntry>? =
        File(directoryPath).takeIf { it.isDirectory }?.listFiles()?.map {
            MusicEntry(
                id = it.absolutePath,
                name = it.name,
                isDirectory = it.isDirectory,
                sizeBytes = if (it.isDirectory) -1L else it.length(),
                modifiedMillis = it.lastModified(),
                // A link can reach one folder by a different path each time round: the canonical path is what it is.
                canonicalId = if (it.isDirectory) runCatching { it.canonicalPath }.getOrNull() else null,
            )
        }

    /** Scans a plain folder. The [tree] is whatever string the caller stores for it (a `file://` URI). */
    fun scanPlainDirectory(
        tree: String,
        directoryPath: String,
        nowMillis: Long,
        platformFolders: MusicPlatformFolders = MusicPlatformFolders.None,
        isCancelled: () -> Boolean = { false },
    ): MusicScanResult =
        scan(
            tree,
            directoryPath,
            nowMillis,
            list = ::listPlainDirectory,
            platformFolders = platformFolders,
            isCancelled = isCancelled,
            rootKey = runCatching { File(directoryPath).canonicalPath }.getOrDefault(directoryPath),
        )
}

/**
 * The two shapes a stored folder can have, told apart without Android: a `file://` URI is a plain folder from Mimir's own
 * browser, anything else is a system-picker tree. A plain folder has no persistable grant to take or release.
 */
internal object MusicTreeUri {
    fun isPlainFolder(uri: String): Boolean = uri.startsWith("file:", ignoreCase = true)

    /** The folder's path for a `file://` URI (percent-decoded), or null for any other URI. */
    fun plainFolderPath(uri: String): String? {
        if (!isPlainFolder(uri)) return null
        val decoded = try {
            java.net.URI(uri).path
        } catch (_: java.net.URISyntaxException) {
            null
        }
        // An unencoded space or similar fails to parse as a URI; the path is then the text after the scheme and authority.
        val path = decoded ?: uri.removePrefix(uri.substring(0, 5)).removePrefix("//")
        return path.takeIf { it.isNotEmpty() }
    }

    /** Whether storing [uri] should take the system's persistable read grant: only a picker tree has one to take. */
    fun takesPersistableGrant(uri: String): Boolean = !isPlainFolder(uri)
}

/**
 * Where a track sits under the picked folder, worked out from its [MusicTrack.id] alone (the id is the absolute path for a plain folder, the document id for a
 * system-picker tree), so a platform label never has to be taken from a file on disk: [TopFolder] names the picked folder's immediate sub-folder that holds it, [Root] is a
 * file directly in the picked folder, [Unknown] is an id this cannot place (a provider whose document ids are not the folder path, an id from a different folder).
 */
internal sealed interface MusicTrackPlace {
    data class TopFolder(val name: String) : MusicTrackPlace

    data object Root : MusicTrackPlace

    data object Unknown : MusicTrackPlace

    companion object {
        fun of(tree: String, id: String): MusicTrackPlace {
            val relative = relativeTo(tree, id) ?: return Unknown
            val separator = relative.indexOf(separatorOf(tree))
            return if (separator < 0) Root else TopFolder(relative.substring(0, separator)).takeIf { it.name.isNotEmpty() } ?: Unknown
        }

        private fun separatorOf(tree: String): Char = if (MusicTreeUri.isPlainFolder(tree)) java.io.File.separatorChar else '/'

        /** What follows the picked folder in [id], or null when [id] is not under it. */
        private fun relativeTo(tree: String, id: String): String? {
            if (MusicTreeUri.isPlainFolder(tree)) {
                // The scan built each id as `File.absolutePath` of a child of the picked folder, so the picked folder's own absolute path leads it.
                val root = java.io.File(MusicTreeUri.plainFolderPath(tree) ?: return null).absolutePath.trimEnd(java.io.File.separatorChar)
                return id.takeIf { it.startsWith(root + java.io.File.separatorChar) }?.substring(root.length + 1)
            }
            // A system-picker tree: `.../tree/<root document id, percent-encoded>`, and a child's document id is the root's followed by `/` and the child's name.
            val start = tree.indexOf("/tree/").takeIf { it >= 0 }?.plus("/tree/".length) ?: return null
            val encoded = tree.substring(start).substringBefore('/')
            val root = try {
                java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (root.isEmpty()) return null
            // A volume's own root (`primary:`) is followed directly by the child's name.
            val lead = if (root.endsWith(":")) root else "$root/"
            return id.takeIf { it.startsWith(lead) }?.substring(lead.length)
        }
    }
}

/**
 * The index with every track's platform label recomputed from where the track sits and the platform ids as they are NOW, so a label that an earlier build wrote to disk (one that
 * took `Queen` for a platform's folder) is never trusted: nothing about the folder is read, and the tag cache, ids and names are untouched. A track this cannot place keeps its
 * label, because there is nothing to compute it from; the next rescan labels it. Returns the same instance when nothing changes.
 */
internal fun MusicIndex.withPlatformLabels(folders: MusicPlatformFolders): MusicIndex {
    var changed = false
    val relabelled = tracks.map { track ->
        val folder = when (val place = MusicTrackPlace.of(tree, track.id)) {
            is MusicTrackPlace.TopFolder -> folders.idFor(place.name.trim()) ?: ""
            MusicTrackPlace.Root -> ""
            MusicTrackPlace.Unknown -> track.folder
        }
        if (folder == track.folder) track else track.copy(folder = folder).also { changed = true }
    }
    return if (changed) copy(tracks = relabelled) else this
}

/**
 * The index as the compact app-private file Custom playback will read: a three-line header (format, scan time, folder) then one tab-separated line per
 * track, each field escaped so a name can hold any character.
 *
 * **Format 3** (`MIMIRMUSIC3`) is the track's `folder`, `name`, `id`, then the tag cache's fields: file size, last-modified time, the read state
 * (`u` unread, `r` read, `f` failed), title, artist, album, album artist, track number, disc number, duration in milliseconds, `1` when the file
 * carries an embedded picture and `1` when it is marked as one of a compilation. An unknown tag is empty. **Format 2** (`MIMIRMUSIC2`, the same without the
 * compilation flag) and **format 1** (`MIMIRMUSIC1`, names only) still decode, as tracks whose compilation flag is off and, for format 1, with nothing read, so an
 * installed index survives the upgrade and the first rescan reads what is missing. A track's `folder` on disk is whatever the scan that wrote it decided, and it is NOT
 * trusted: [withPlatformLabels] recomputes it at every load from the track's id and the platform ids of the day.
 */
internal object MusicIndexCodec {
    private const val Header = "MIMIRMUSIC3"
    private const val HeaderTagged = "MIMIRMUSIC2"
    private const val HeaderNamesOnly = "MIMIRMUSIC1"
    private const val NamesOnlyFields = 3
    private const val TaggedFields = 14
    private const val CompilationFields = 15

    fun encode(index: MusicIndex): String = buildString {
        append(Header).append('\n')
        append(index.scannedAtMillis).append('\n')
        append(escape(index.tree)).append('\n')
        for (track in index.tracks) {
            val meta = index.meta[track.id] ?: MusicTrackMeta()
            val tags = meta.tags
            append(escape(track.folder)).append('\t').append(escape(track.name)).append('\t').append(escape(track.id))
            append('\t').append(meta.stamp.sizeBytes)
            append('\t').append(meta.stamp.modifiedMillis)
            append('\t').append(
                when (meta.state) {
                    MusicTagState.UNREAD -> 'u'
                    MusicTagState.READ -> 'r'
                    MusicTagState.FAILED -> 'f'
                },
            )
            append('\t').append(escape(tags.title.orEmpty()))
            append('\t').append(escape(tags.artist.orEmpty()))
            append('\t').append(escape(tags.album.orEmpty()))
            append('\t').append(escape(tags.albumArtist.orEmpty()))
            append('\t').append(tags.trackNumber?.toString().orEmpty())
            append('\t').append(tags.discNumber?.toString().orEmpty())
            append('\t').append(tags.durationMs?.toString().orEmpty())
            append('\t').append(if (meta.hasEmbeddedArt) '1' else '0')
            append('\t').append(if (tags.compilation) '1' else '0')
            append('\n')
        }
    }

    /** The index, or null for anything that is not a well-formed file of this format (never throws). */
    fun decode(text: String): MusicIndex? {
        val lines = text.split('\n')
        if (lines.size < 3) return null
        val fields = when (lines[0]) {
            Header -> CompilationFields
            HeaderTagged -> TaggedFields
            HeaderNamesOnly -> NamesOnlyFields
            else -> return null
        }
        val scannedAt = lines[1].toLongOrNull() ?: return null
        val tree = unescape(lines[2]) ?: return null
        val tracks = ArrayList<MusicTrack>()
        val meta = HashMap<String, MusicTrackMeta>()
        for (line in lines.drop(3)) {
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size != fields) return null
            val track = MusicTrack(
                folder = unescape(parts[0]) ?: return null,
                name = unescape(parts[1]) ?: return null,
                id = unescape(parts[2]) ?: return null,
            )
            tracks += track
            if (fields >= TaggedFields) meta[track.id] = decodeMeta(parts) ?: return null
        }
        return MusicIndex(tree, scannedAt, tracks, meta)
    }

    private fun decodeMeta(parts: List<String>): MusicTrackMeta? {
        val size = parts[3].toLongOrNull() ?: return null
        val modified = parts[4].toLongOrNull() ?: return null
        val state = when (parts[5]) {
            "u" -> MusicTagState.UNREAD
            "r" -> MusicTagState.READ
            "f" -> MusicTagState.FAILED
            else -> return null
        }
        fun text(at: Int): String? = unescape(parts[at])?.ifEmpty { null }
        // An empty or unreadable number is no number: a tag cache entry is never worth failing the whole index for.
        fun optionalInt(at: Int): Int? = parts[at].toIntOrNull()
        fun optionalLong(at: Int): Long? = parts[at].toLongOrNull()
        return MusicTrackMeta(
            stamp = MusicFileStamp(size, modified),
            state = state,
            tags = MusicTags(
                title = text(6),
                artist = text(7),
                album = text(8),
                albumArtist = text(9),
                trackNumber = optionalInt(10),
                discNumber = optionalInt(11),
                durationMs = optionalLong(12),
                compilation = parts.getOrNull(14) == "1",
            ),
            hasEmbeddedArt = parts[13] == "1",
        )
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String? {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (char != '\\') {
                out.append(char)
                i++
                continue
            }
            if (i + 1 >= value.length) return null
            when (value[i + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> return null
            }
            i += 2
        }
        return out.toString()
    }
}
