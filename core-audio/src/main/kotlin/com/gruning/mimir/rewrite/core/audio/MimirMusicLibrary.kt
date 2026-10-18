package com.gruning.mimir.rewrite.core.audio

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Which top-level folders of the Custom music folder are a platform's (`snes`, `ps2`, `pcgames`): [idFor] answers a folder's NAME with the platform's id, or null for any
 * other folder. The scan labels a platform folder's tracks with that id so general playback can leave them to the platform; a name that is not a platform's (an artist's
 * folder: `Queen`, `ABBA`, `U2`) is ordinary music. The app supplies the answer from the platform registry the game scanner already uses ([MimirMusicLibrary.load]),
 * because this module knows no platform; the default knows none, so nothing is reserved.
 */
fun interface MusicPlatformFolders {
    fun idFor(folderName: String): String?

    companion object {
        /** Knows no platform: every folder is plain music. */
        val None = MusicPlatformFolders { null }
    }
}

/** Why the Custom music folder cannot be read right now. Null in [MusicLibraryState.error] means nothing is wrong. */
enum class MusicLibraryError {
    /** The system no longer lets Mimir read the folder (revoked, or the volume was removed). Choosing it again fixes it. */
    GRANT_LOST,

    /** The folder was reachable but listing it failed. A rescan may succeed. */
    UNREADABLE,
}

/**
 * What the Custom music card shows about the folder the player chose.
 *
 * [trackCount] counts the tracks at the folder's root AND in its platform folders: every file of a
 * supported format (mp3, m4a, ogg, flac, wav). [lastScanMillis] is null until a scan has finished.
 */
data class MusicLibraryState(
    val treeUri: String? = null,
    val trackCount: Int = 0,
    val lastScanMillis: Long? = null,
    val scanning: Boolean = false,
    val error: MusicLibraryError? = null,
)

/**
 * Minimum visible duration for the "Scanning…" state. A small folder scans in milliseconds, so an
 * immediate reset flashed the row and let every press start another scan. The retry delay matches
 * `HomeCustomEnrichRetryFloorMs`; presses within that interval are absorbed.
 */
internal const val MusicRescanFloorMs = 900L

/** How much longer a scan that took [elapsedMs] holds before it publishes, so the whole run lasts at least [floorMs]. */
internal fun musicRescanHoldMs(floorMs: Long, elapsedMs: Long): Long = (floorMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)

/**
 * The index of the player's Custom music folder (Settings > Music > Custom music).
 *
 * It holds the folder choice, the count and the rescan, and publishes the indexed tracks as [playlist], which the frontend
 * music plays when the source is Your music (see [FrontendMusicSourceResolver]). A scan lists names, sizes and times on an IO thread, publishes
 * the playlist at once (so the music never waits for a tag), and writes a compact app-private index.
 *
 * **Tags.** After the playlist is out, a second pass reads each track's tags (`MediaMetadataRetriever`, on IO, a couple of files at a time) and
 * publishes them as [catalog]. The index remembers what it read, keyed by the track's id and its file's size and time, so a rescan reads only the
 * files that are new or changed. Tags never touch [playlist]: the player's queue is the same list before and after, so a tag arriving cannot
 * restart the song.
 *
 * **Two shapes of folder.** The system picker (`OpenDocumentTree`) gives a `content://` tree URI, listed through
 * `DocumentsContract` and kept readable by a persisted grant. Mimir's own file browser (with all-files access) gives a plain
 * folder, stored as a `file://` URI and listed as a directory; it has no grant to take or release.
 */
object MimirMusicLibrary {
    private val _state = MutableStateFlow(MusicLibraryState())
    val state: StateFlow<MusicLibraryState> = _state.asStateFlow()

    private val _playlist = MutableStateFlow(MusicPlaylist())

    /** The chosen folder's indexed tracks in index order, for the player. Not [state]: that is the card's counts, this is the list. */
    internal val playlist: StateFlow<MusicPlaylist> = _playlist.asStateFlow()

    /** The tags the index holds, per track id, for the folder [playlist] describes. Never published ahead of the playlist it belongs to. */
    private val _meta = MutableStateFlow(emptyMap<String, MusicTrackMeta>())

    private val _catalog = MutableStateFlow(MusicCatalog())

    /** The chosen folder's tracks with their tags, for the music UI (the Artist / Album tree, the player's art). Rebuilt as tags arrive. */
    val catalog: StateFlow<MusicCatalog> = _catalog.asStateFlow()

    private val _view = MutableStateFlow(MusicLibraryView())

    /** The same tracks as the Artist / Album tree the browser draws, rebuilt with [catalog] (off the main thread). */
    val view: StateFlow<MusicLibraryView> = _view.asStateFlow()

    private val _selection = MutableStateFlow<MusicSelection?>(null)

    /** The player's last pick from the tree (its album, opening on the track pressed), for the player to play in place of the folder's own order. Not kept across a restart. */
    internal val selection: StateFlow<MusicSelection?> = _selection.asStateFlow()
    private var selectionSerial = 0L

    /** Which top-level folders are a platform's, as the app told [load]. */
    @Volatile
    private var platformFolders: MusicPlatformFolders = MusicPlatformFolders.None

    private val _tagsRevision = MutableStateFlow(0L)

    /** Moves every time tags land, for a watcher that only needs to know the labels or the art hints may have changed (the Now playing row, the media session, the player card). */
    val tagsRevision: StateFlow<Long> = _tagsRevision.asStateFlow()

    // A process-lifetime scope: a failure here is logged by class and never takes the launcher with it (no message, which can hold a path).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.e(Tag, "music library work failed (${error.javaClass.simpleName})")
        },
    )
    private var scanJob: Job? = null
    private var tagJob: Job? = null

    /** Guards the state above. Held only for the quick reads and swaps: never across disk work (the index file is written outside it, see [persistIndex]). */
    private val lock = Any()
    private val stagedFileCounter = AtomicLong(0L)

    /** What the media session and the Now playing card show for [track] now: its title (tag, else file name) and its artist and album tags. */
    internal fun labelOf(track: MusicTrack): YourMusicLabel = yourMusicLabel(track, _meta.value[track.id])

    /** Whether [trackId]'s file carries an embedded picture: known once its tags have been read, [EmbeddedArtHint.UNKNOWN] before. */
    fun artHint(trackId: String): EmbeddedArtHint {
        val meta = _meta.value[trackId]
        return when {
            meta == null || meta.state == MusicTagState.UNREAD -> EmbeddedArtHint.UNKNOWN
            meta.hasEmbeddedArt -> EmbeddedArtHint.YES
            else -> EmbeddedArtHint.NO
        }
    }

    /**
     * [trackId]'s embedded art for a square [drawnPx] wide, if it is already decoded and in memory (a track with no art, known to have none, is also answered:
     * null with [artKnownMissing] true). Never reads a file, so a composition can ask it freely.
     */
    fun cachedEmbeddedArt(trackId: String, drawnPx: Int): Bitmap? =
        (MimirMusicArt.cached(trackId, _meta.value[trackId]?.stamp ?: MusicFileStamp.Unknown, drawnPx) as? MimirMusicArt.Cached.Art)?.bitmap

    /** The track is known to carry no art at this stamp (a decode found none). */
    fun artKnownMissing(trackId: String, drawnPx: Int): Boolean =
        MimirMusicArt.cached(trackId, _meta.value[trackId]?.stamp ?: MusicFileStamp.Unknown, drawnPx) == MimirMusicArt.Cached.None

    /**
     * [trackId]'s embedded picture decoded for a square [drawnPx] wide, on IO, or null when the file has none. Downsampled to the size it is drawn at and
     * cached, so a second ask (another surface, the same track again) costs nothing. Cancellable: a caller that moves on stops waiting.
     */
    suspend fun embeddedArt(context: Context, trackId: String, drawnPx: Int): Bitmap? {
        val tree = _playlist.value.tree ?: return null
        val stamp = _meta.value[trackId]?.stamp ?: MusicFileStamp.Unknown
        return MimirMusicArt.load(context, tagSourceOf(tree, MusicTrack("", "", trackId)), trackId, stamp, drawnPx)
    }

    /**
     * Restore the folder choice and the last index at startup, after [AudioPreferences.load]. The choice is read at once;
     * the index file (names only, but possibly thousands of them) is read off the main thread, and only if it describes the
     * folder that is chosen now. Never touches the folder itself. [platformFolders] says which top-level folders are a platform's (see [MusicPlatformFolders]) for every scan
     * from here on.
     */
    fun load(context: Context, platformFolders: MusicPlatformFolders = MusicPlatformFolders.None) {
        val appContext = context.applicationContext
        this.platformFolders = platformFolders
        val tree = AudioPreferences.customMusicTreeUri.value
        synchronized(lock) {
            _state.value = MusicLibraryState(treeUri = tree)
            // Not loaded until the index file has been read: the player waits rather than start on the wrong source.
            _playlist.value = MusicPlaylist(tree = tree, loaded = tree == null)
            _meta.value = emptyMap()
            _catalog.value = MusicCatalog(tree = tree, loaded = tree == null)
            _view.value = MusicLibraryView(loaded = tree == null)
        }
        if (tree == null) return
        scope.launch {
            // A write that was cut short by the process dying leaves its staging file behind: nothing reads it, so it goes.
            runCatching {
                appContext.filesDir.listFiles { _, name -> name.startsWith(IndexFileName) && name.endsWith(StagedSuffix) }?.forEach { it.delete() }
            }
            val index = runCatching { indexFile(appContext).takeIf { it.isFile }?.readText() }.getOrNull()
                ?.let(MusicIndexCodec::decode)
                ?.takeIf { it.tree == tree }
                // The platform labels in the file are never trusted: an index an earlier build wrote labelled every one-word folder (Queen, ABBA) a platform's.
                ?.withPlatformLabels(platformFolders)
            var adopted = false
            synchronized(lock) {
                // Loaded either way, unless a choice or a finished scan already replaced it while the file was read.
                val pending = _playlist.value
                if (pending.tree == tree && !pending.loaded) {
                    _playlist.value = MusicPlaylist(tree, index?.scannedAtMillis, index?.tracks.orEmpty(), loaded = true)
                    _meta.value = index?.meta.orEmpty()
                    adopted = true
                }
            }
            if (adopted) {
                publishCatalog(tree, index?.tracks.orEmpty(), index?.meta.orEmpty(), loaded = true)
                // An index from before tags (or one cut short by the process dying) has files left to read: carry on from where it stopped.
                if (index != null) startTagPass(appContext, index)
            }
            if (index == null) return@launch
            synchronized(lock) {
                // A choice made, or a scan finished, while the file was being read is newer than the file.
                if (_state.value.treeUri == tree && _state.value.lastScanMillis == null && !_state.value.scanning) {
                    _state.value = _state.value.copy(
                        trackCount = index.trackCount,
                        lastScanMillis = index.scannedAtMillis,
                    )
                }
            }
        }
    }

    /**
     * Remember [treeUri] as the Custom music folder and index it: either the `content://` tree URI the system picker
     * returned or a `file://` URI for a plain folder from Mimir's own browser.
     *
     * For a picker tree the caller takes the persistable read grant first (the `OpenDocumentTree` result handler) and this
     * takes it too, as a net; a plain folder has none to take. The choice is stored in
     * [AudioPreferences.customMusicTreeUri] and a [rescan] starts. Picking a new folder replaces the old one and its index.
     */
    fun setTree(context: Context, treeUri: String) {
        val appContext = context.applicationContext
        // Idempotent, and the one line that keeps the folder readable after a reboot: a tree URI without a
        // persisted grant stops working silently. The picker's result handler takes it too; this is the net.
        // Skipped for a plain folder, where there is nothing to take (and the call would throw).
        if (MusicTreeUri.takesPersistableGrant(treeUri)) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        AudioPreferences.setCustomMusicTreeUri(appContext, treeUri)
        synchronized(lock) {
            scanJob?.cancel()
            scanJob = null
            tagJob?.cancel()
            tagJob = null
            _state.value = MusicLibraryState(treeUri = treeUri)
            _playlist.value = MusicPlaylist(tree = treeUri, loaded = false)
            // A different folder has none of the old folder's tags or art.
            MimirMusicArt.clear()
            _meta.value = emptyMap()
            _catalog.value = MusicCatalog(tree = treeUri, loaded = false)
            _view.value = MusicLibraryView()
            _selection.value = null
        }
        runCatching { indexFile(appContext).delete() }
        rescan(appContext)
    }

    /**
     * Index the folder again, off the main thread; [MusicLibraryState.scanning] is true meanwhile. Safe to
     * call with no folder chosen (it does nothing) and while a scan is already running (it does nothing).
     */
    fun rescan(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val tree = _state.value.treeUri ?: return
            // Presses during the scan, its floor included, are absorbed here.
            if (scanJob?.isActive == true) return
            _state.value = _state.value.copy(scanning = true, error = null)
            scanJob = scope.launch { scanAndPublish(appContext, tree, floorMs = MusicRescanFloorMs) }
        }
    }

    /** Forget the folder: clears the choice and the index and releases the grant. The files are untouched. */
    fun forget(context: Context) {
        val appContext = context.applicationContext
        val old = synchronized(lock) {
            scanJob?.cancel()
            scanJob = null
            tagJob?.cancel()
            tagJob = null
            _playlist.value = MusicPlaylist()
            _meta.value = emptyMap()
            _catalog.value = MusicCatalog(loaded = true)
            _view.value = MusicLibraryView(loaded = true)
            _selection.value = null
            MimirMusicArt.clear()
            _state.value.treeUri.also { _state.value = MusicLibraryState() }
        }
        if (old != null && MusicTreeUri.takesPersistableGrant(old)) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        AudioPreferences.setCustomMusicTreeUri(appContext, null)
        runCatching { indexFile(appContext).delete() }
    }

    private suspend fun scanAndPublish(appContext: Context, tree: String, floorMs: Long = 0L) {
        val startedAtNanos = System.nanoTime()
        val thisJob = currentCoroutineContext()[Job]
        val result = try {
            scanTree(appContext, tree, System.currentTimeMillis()) { thisJob?.isActive == false }
        } catch (cancelled: CancellationException) {
            // A scan whose job was cancelled (a new folder, forget) ends here: it is not an unreadable folder, and it publishes nothing.
            throw cancelled
        } catch (_: Exception) {
            MusicScanResult.Unreadable
        }
        delay(musicRescanHoldMs(floorMs, elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L))
        // delay(0) does not look at cancellation: without this a scan cancelled by choosing the SAME folder again could still publish over the new one.
        val scanContext = currentCoroutineContext()
        scanContext.ensureActive()
        // The disk work first and OUTSIDE the lock: what an earlier index already knows about a file that has not changed (same id, size and time) is carried over (only new and
        // changed files are read again), and the index is written to a staging file of its own. The lock below only moves that file into place and swaps the state.
        val prior = synchronized(lock) {
            // Forgotten or replaced while it ran: this scan describes a folder that is no longer the choice.
            if (_state.value.treeUri != tree) return
            _meta.value
        }
        val merged = (result as? MusicScanResult.Scanned)?.let { MusicTagCache.carryOver(prior, it.index) }
        val staged = merged?.let { runCatching { stageIndex(appContext, it) }.getOrNull() }
        var published: MusicIndex? = null
        try {
            synchronized(lock) {
                scanContext.ensureActive()
                if (_state.value.treeUri != tree) return
                when (result) {
                    is MusicScanResult.Scanned -> {
                        val index = checkNotNull(merged)
                        staged?.let { moveIntoPlace(it, indexFile(appContext)) }
                        _state.value = MusicLibraryState(
                            treeUri = tree,
                            trackCount = index.trackCount,
                            lastScanMillis = index.scannedAtMillis,
                        )
                        _playlist.value = MusicPlaylist(tree, index.scannedAtMillis, index.tracks, loaded = true)
                        _meta.value = index.meta
                        published = index
                    }
                    // The last good counts stay, with the reason beside them: a folder that cannot be read right now is not
                    // a folder with no music in it. The player reads the error and plays the theme bed meanwhile.
                    MusicScanResult.GrantLost -> {
                        _state.value = _state.value.copy(scanning = false, error = MusicLibraryError.GRANT_LOST)
                        _playlist.value = _playlist.value.copy(loaded = true)
                    }
                    MusicScanResult.Unreadable -> {
                        _state.value = _state.value.copy(scanning = false, error = MusicLibraryError.UNREADABLE)
                        _playlist.value = _playlist.value.copy(loaded = true)
                    }
                }
            }
        } finally {
            // Moved into place, there is nothing left of it; a scan that was overtaken or cancelled removes its staging file here.
            staged?.delete()
        }
        // Out of the lock: the playlist is published (the music can start), and now the tags follow.
        published?.let { index ->
            publishCatalog(tree, index.tracks, index.meta, loaded = true)
            startTagPass(appContext, index)
        }
    }

    /**
     * The player pressed [trackId] in the Artist / Album tree: its album becomes the queue, in the album's order, and the player opens on that track. A track the
     * tree does not hold (the folder changed under the browser) does nothing. Every press is a new pick, so pressing the track that is already on air plays it again.
     */
    fun playFromLibrary(trackId: String) {
        synchronized(lock) {
            val tree = _playlist.value.tree ?: return
            val album = _view.value.tree.albumOf(trackId) ?: return
            selectionSerial += 1L
            _selection.value = MusicSelection(tree, album.tracks.map { it.id }, trackId, selectionSerial)
            _view.value = _view.value.copy(pickedTrackId = trackId)
        }
    }

    /**
     * The player pressed "All music" in the tree: the pick is cleared and the folder plays as a whole again, in general playback's own order (the Order setting's shuffle or
     * sequence, from the folder's first track or a random one). No pick to clear changes nothing.
     */
    fun clearSelection() {
        synchronized(lock) {
            if (_selection.value == null) return
            _selection.value = null
            _view.value = _view.value.copy(pickedTrackId = null)
        }
    }

    // ── tags ─────────────────────────────────────────────────────────────────────────────────────────

    /** Rebuilds [catalog] from the given tracks and tags, and moves [tagsRevision]. Safe from any thread; the newest call wins. */
    private fun publishCatalog(tree: String?, tracks: List<MusicTrack>, meta: Map<String, MusicTrackMeta>, loaded: Boolean) {
        val catalog = musicCatalogOf(tree, tracks, meta, loaded)
        val built = buildMusicTree(catalog.tracks)
        val ids = catalog.tracks.mapTo(HashSet()) { it.id }
        synchronized(lock) {
            // A catalog built for a folder that has since been replaced is dropped.
            if (_playlist.value.tree != tree) return
            // A pick whose track the library no longer holds (a rescan took its album away) is gone: the player is already back on the folder's own order, and the
            // browser must not keep marking an album that is not there.
            val pick = _selection.value?.takeIf { it.tree == tree && it.startId in ids }
            if (pick == null && _selection.value != null) _selection.value = null
            _catalog.value = catalog
            _view.value = MusicLibraryView(built, catalog.tagsRead, catalog.tracks.size, loaded, pickedTrackId = pick?.startId)
            _tagsRevision.value = _tagsRevision.value + 1L
        }
    }

    /** Where [track]'s bytes are for a tag read: its absolute path in a plain folder, its document URI in a picked tree. */
    private fun tagSourceOf(tree: String, track: MusicTrack): MusicTagSource =
        if (MusicTreeUri.plainFolderPath(tree) != null) {
            MusicTagSource.Path(track.id)
        } else {
            MusicTagSource.Document(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), track.id).toString())
        }

    /** Starts (or restarts) the tag pass over what [index] has not had read. Nothing to read starts nothing. */
    private fun startTagPass(context: Context, index: MusicIndex) {
        val pending = MusicTagCache.pending(index)
        synchronized(lock) {
            tagJob?.cancel()
            tagJob = null
            if (pending.isEmpty()) return
            tagJob = scope.launch { runTagPass(context, AndroidMusicTagReader(context), index, pending) }
        }
    }

    /**
     * Reads [pending] with [reader] and publishes as it goes: the tags to [_meta] and the catalog (at most about once a second), the index file every
     * [IndexWriteEvery] files and at the end, so a process that dies mid-pass loses little. Every publish first checks that [index] is still the folder
     * and scan the library describes; a rescan or a new folder in the meantime ends this pass.
     */
    private suspend fun runTagPass(context: Context, reader: MusicTagReader, index: MusicIndex, pending: List<MusicTrack>) {
        val meta = HashMap(index.meta)
        var lastPublishAt = 0L
        var sinceWrite = 0
        fun current(): Boolean = _playlist.value.let { it.tree == index.tree && it.scannedAtMillis == index.scannedAtMillis }
        fun snapshot(): MusicIndex = index.copy(meta = HashMap(meta))
        val reachedAll = MusicTagPass.run(pending, { tagSourceOf(index.tree, it) }, reader) { batch ->
            for ((track, result) in batch) meta[track.id] = MusicTagCache.applied(meta[track.id], result)
            sinceWrite += batch.size
            val written = snapshot()
            synchronized(lock) {
                if (!current()) throw CancellationException("the library moved on")
                _meta.value = written.meta
            }
            val at = System.currentTimeMillis()
            if (at - lastPublishAt >= CatalogPublishEveryMs) {
                lastPublishAt = at
                publishCatalog(index.tree, index.tracks, written.meta, loaded = true)
            }
            if (sinceWrite >= IndexWriteEvery) {
                sinceWrite = 0
                runCatching { persistIndex(context, written) }
            }
        }
        val finished = snapshot()
        publishCatalog(index.tree, index.tracks, finished.meta, loaded = true)
        runCatching { persistIndex(context, finished) }
        if (!reachedAll) Log.w(Tag, "tag pass stopped: the folder could not be reached")
    }

    private fun scanTree(context: Context, tree: String, nowMillis: Long, isCancelled: () -> Boolean): MusicScanResult {
        val plainPath = MusicTreeUri.plainFolderPath(tree)
        if (plainPath != null) return MusicLibraryScanner.scanPlainDirectory(tree, plainPath, nowMillis, platformFolders, isCancelled)
        val treeUri = Uri.parse(tree)
        val resolver = context.contentResolver
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: RuntimeException) {
            return MusicScanResult.Unreadable
        }
        return MusicLibraryScanner.scan(
            tree,
            rootId,
            nowMillis,
            list = { documentId -> listTreeChildren(resolver, treeUri, documentId) },
            platformFolders = platformFolders,
            isCancelled = isCancelled,
        )
    }

    /**
     * The children of [parentDocumentId] in a system-picker tree. Null when the provider returns no cursor; a revoked grant
     * surfaces as the [SecurityException] the scanner reads as GRANT_LOST.
     */
    private fun listTreeChildren(resolver: ContentResolver, treeUri: Uri, parentDocumentId: String): List<MusicEntry>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val entries = ArrayList<MusicEntry>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                // Size and time are the tag cache's key; a provider that does not report them leaves the stamp unknown (never a guess).
                entries += MusicEntry(
                    id = id,
                    name = name,
                    isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                    modifiedMillis = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                )
            }
            return entries
        }
        return null
    }

    private fun indexFile(context: Context): File = File(context.filesDir, IndexFileName)

    /**
     * Writes [index] to the app-private file **without holding [lock] while the disk works**: the index is encoded and written to a staging file of its own (a 20,000-track
     * index is megabytes), and only the move into place is done under the lock, and only if the library still describes this very index. A scan or a tag pass that has been
     * overtaken (a newer scan, another folder, Forget) finds that false and throws its file away, so a stale write can never replace a newer one. The lock is what
     * [playFromLibrary] takes on the main thread, so it is never made to wait on a disk.
     */
    private fun persistIndex(context: Context, index: MusicIndex) {
        val staged = stageIndex(context, index)
        try {
            synchronized(lock) {
                val live = _playlist.value
                if (live.tree == index.tree && live.scannedAtMillis == index.scannedAtMillis) moveIntoPlace(staged, indexFile(context))
            }
        } finally {
            // Moved into place, there is nothing left of it; thrown away (stale), this removes it.
            staged.delete()
        }
    }

    /** Encodes [index] and writes it to a staging file of its own beside the index file. Disk work: never call it holding [lock]. */
    private fun stageIndex(context: Context, index: MusicIndex): File {
        val target = indexFile(context)
        val staged = File(target.parentFile, "$IndexFileName.${stagedFileCounter.incrementAndGet()}$StagedSuffix")
        try {
            staged.writeText(MusicIndexCodec.encode(index))
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
        return staged
    }

    /** The one quick step the lock covers: the staged file becomes the index file. */
    private fun moveIntoPlace(staged: File, target: File) {
        if (!staged.renameTo(target)) {
            target.delete()
            staged.renameTo(target)
        }
    }

    private const val IndexFileName = "music_library_index.txt"
    private const val StagedSuffix = ".tmp"
    private const val Tag = "MimirMusicLibrary"

    /** The catalog is rebuilt from the tags at most this often while they arrive (a 20,000-track rebuild is real work). */
    private const val CatalogPublishEveryMs = 900L

    /** The index file is rewritten after this many more files have been read, so a process that dies mid-pass keeps most of it. */
    private const val IndexWriteEvery = 1_500
}
