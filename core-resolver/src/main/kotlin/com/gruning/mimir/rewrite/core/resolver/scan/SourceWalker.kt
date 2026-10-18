package com.gruning.mimir.rewrite.core.resolver.scan

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.gruning.mimir.rewrite.core.model.library.SourceFailureKind
import com.gruning.mimir.rewrite.core.model.library.sourceRootIsOnRemovableVolume
import com.gruning.mimir.rewrite.core.model.library.sourceRootNeedsAllFilesAccess
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One physical file seen during a source scan — the raw facts BEFORE recognition. Read IN PLACE
 * (the walk never moves/copies/deletes; it only lists).
 *
 * @property displayName the file's name incl. extension.
 * @property relativePath path relative to the scanned root (used for stable cross-root keying).
 * @property sizeBytes file size in bytes (0 when unknown).
 * @property documentUri the exact SAF `content://` *document* URI (null for a plain-file walk).
 * @property filePath the plain absolute path (null for a SAF walk).
 */
data class WalkedFile(
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val documentUri: String?,
    val filePath: String?,
)

/**
 * Lists the files under a source root, in place. An interface so the repository can be unit-tested with a
 * stub lister (no Android `Context`), while production uses the SAF/file [SourceWalker].
 */
interface SourceLister {
    /** True when [rootUri] is a SAF tree/content URI (vs a plain filesystem path). */
    fun isSafTree(rootUri: String): Boolean = rootUri.startsWith("content://")

    /**
     * List every file (recursively) under [rootUri]. Directories are descended, not emitted.
     *
     * An empty [SourceWalkResult.Complete] is a real, fully traversed empty source. Failures must not
     * be represented as an empty list: callers use this distinction before reconciling an index.
     */
    suspend fun walk(rootUri: String): SourceWalkResult

    /**
     * As [walk], reporting how many files have been found so far.
     *
     * **Defaulted to the plain [walk] on purpose**, exactly like [readUtf8] below: a test lister that
     * does not care about progress stays inert and needs no edit. Only the production [SourceWalker]
     * overrides it.
     *
     * [onProgress] receives a running COUNT, never a fraction. the
     * traversal has no total until it finishes, so there is no honest bar to draw here — a count of
     * files actually found is the most this phase can truthfully say. It is called on a throttle, not
     * per file; see `SourceWalker.ProgressWalkList`.
     */
    suspend fun walk(rootUri: String, onProgress: (Int) -> Unit): SourceWalkResult = walk(rootUri)

    /** Bounded local text read used only for small manifests such as `.m3u`; test listers may stay inert. */
    suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? = null

    /**
     * Positive proof that a listing of [rootUri] can be believed right now. A scan asks this BEFORE it walks and
     * never prunes the index of a root that answers false: it ends as an unavailable source and every row, favourite,
     * hidden flag and launch record under it stays.
     *
     * It exists because an unreadable folder does not always fail loudly. Without Android's "All files access" a
     * shared-storage directory still exists and still opens, and its listing comes back empty or filtered, which is
     * indistinguishable from a folder that really holds no games (observed: 195 rows deleted by a
     * rescan after the grant was revoked). **Defaulted to true on purpose**, like [readUtf8]: a test lister that does
     * not model access stays inert. Only the production [SourceWalker] overrides it.
     */
    suspend fun rootReadable(rootUri: String): Boolean = true
}

/** `Environment.getExternalStorageState` answers for a volume whose listing can be believed (read-only is still readable). */
private val MountedStorageStates = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

/** The terminal state of one source traversal. */
sealed interface SourceWalkResult {
    /** The source was fully traversed, including when it genuinely contains no files. */
    data class Complete(val files: List<WalkedFile>) : SourceWalkResult

    /** A failed terminal traversal with typed registry/index-removal authority. */
    sealed interface Failure : SourceWalkResult {
        val reason: String
        val kind: SourceFailureKind
    }

    /**
     * The source could not be traversed. This is deliberately non-destructive even when [reason]
     * contains words such as "missing" or "does not exist"; free-form text is never a presence oracle.
     */
    data class Unavailable(override val reason: String) : Failure {
        override val kind: SourceFailureKind = SourceFailureKind.UNAVAILABLE
    }

    /** The traversal failed for a reason not safely classified as retryable availability. */
    data class Failed(override val reason: String) : Failure {
        override val kind: SourceFailureKind = SourceFailureKind.FAILED
    }

    /**
     * A separate presence authority proved the source no longer exists. Production traversal does not
     * infer this from SAF/provider/File errors; callers must supply explicit proof.
     */
    data class Missing(override val reason: String) : Failure {
        override val kind: SourceFailureKind = SourceFailureKind.CONFIRMED_MISSING
    }
}

/**
 * Hard resource limits for one source traversal.
 *
 * The defaults deliberately sit well above the measured corpus while bounding memory, recursion, and
 * provider work on hostile or accidentally cyclic trees. A limit rejection is a non-destructive
 * [SourceWalkResult.Failed]: callers retain the previous registry/index snapshot.
 */
data class SourceWalkLimits(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxDepth >= 0) { "maxDepth must be non-negative" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 64
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}

/**
 * One traversal-owned guard shared by the SAF and plain-path backends. Node identity is the provider
 * document id for SAF and canonical path for files, so aliases/cycles cannot recurse or emit twice.
 */
internal class SourceTraversalGuard(private val limits: SourceWalkLimits) {
    private val visitedNodes = HashSet<String>()
    private val visitedRelativePaths = HashSet<String>()
    private var entriesSeen = 0

    fun beginRoot(identity: String) {
        if (!visitedNodes.add(identity)) rejectRepeatedNode()
    }

    fun observeNode(
        identity: String,
        relativePath: String,
        depth: Int,
        isDirectory: Boolean,
    ) {
        entriesSeen += 1
        if (entriesSeen > limits.maxEntries) {
            throw SourceTraversalRejectedException(
                "Source traversal exceeded the ${limits.maxEntries}-entry safety limit",
            )
        }
        if (isDirectory && depth > limits.maxDepth) {
            throw SourceTraversalRejectedException(
                "Source traversal exceeded the ${limits.maxDepth}-level directory safety limit",
            )
        }
        if (!visitedNodes.add(identity)) rejectRepeatedNode()
        if (!visitedRelativePaths.add(relativePath)) {
            throw SourceTraversalRejectedException("Source traversal repeated a relative path")
        }
    }

    private fun rejectRepeatedNode(): Nothing =
        throw SourceTraversalRejectedException("Source traversal repeated a node identity")
}

internal class SourceTraversalRejectedException(message: String) : IllegalStateException(message)

/**
 * Walks a source root and lists its files. Two backends, one interface:
 *  - a SAF tree URI (`content://…/tree/…`) → walked via the framework [DocumentsContract] +
 *    [android.content.ContentResolver] (NO `androidx.documentfile` dependency — that artifact is not in
 *    this build's offline cache; the platform APIs suffice and avoid the extra dep).
 *  - a plain absolute path → walked with [java.io.File] (the managed/library fallback).
 *
 * Roots are user-selected sources and are deliberately not scoped to `/Mimir`: an external-volume
 * `/storage/<volume>/ROMs` tree is as valid as any other granted/readable folder. Local-only, read-only.
 * Never touches the network and never moves, copies, deletes, or writes source content.
 */
class SourceWalker private constructor(
    private val contentResolver: ContentResolver?,
    private val limits: SourceWalkLimits,
    private val allFilesAccess: () -> Boolean,
    private val ownPackage: String?,
    private val storageState: (path: String) -> String,
) : SourceLister {

    constructor(
        context: Context,
        limits: SourceWalkLimits = SourceWalkLimits(),
    ) : this(
        context.contentResolver,
        limits,
        { Environment.isExternalStorageManager() },
        context.packageName,
        { path -> Environment.getExternalStorageState(File(path)) },
    )

    /** Plain-path test seam; SAF traversal always requires the production [Context] constructor. */
    internal constructor(limits: SourceWalkLimits) : this(null, limits, { true }, null, { Environment.MEDIA_MOUNTED })

    /** Plain-path test seam with the grant, the app's package and a volume's mount state under the test's control. */
    internal constructor(
        limits: SourceWalkLimits,
        allFilesAccess: () -> Boolean,
        ownPackage: String? = null,
        storageState: (path: String) -> String = { Environment.MEDIA_MOUNTED },
    ) : this(null, limits, allFilesAccess, ownPackage, storageState)

    /**
     * A SAF tree answers true: its provider throws once the grant is gone, and the walk already turns that into an
     * unavailable source. A plain shared-storage path is readable only while "All files access" is held, whatever
     * `exists()` and `canRead()` say about it (only Mimir's OWN `Android/{data,media,obb}/<package>` is exempt). A card
     * that is mid-mount ("checking", "unmounted") lists empty too, so a removable volume must be mounted, read-only
     * included, before its listing is believed. Existence is left to the walk itself, which names the reason.
     */
    override suspend fun rootReadable(rootUri: String): Boolean {
        if (isSafTree(rootUri)) return true
        if (sourceRootIsOnRemovableVolume(rootUri) && storageState(rootUri) !in MountedStorageStates) return false
        return !sourceRootNeedsAllFilesAccess(rootUri, ownPackage) || allFilesAccess()
    }

    /**
     * List every file (recursively) under [rootUri]. Directories are descended, not emitted. A missing or
     * unreadable root, malformed provider row, or failed directory query is a failed traversal, never an
     * empty or partial success.
     */
    override suspend fun walk(rootUri: String): SourceWalkResult = walk(rootUri) {}

    override suspend fun walk(rootUri: String, onProgress: (Int) -> Unit): SourceWalkResult = try {
        currentCoroutineContext().ensureActive()
        val files = if (isSafTree(rootUri)) {
            walkSafTree(rootUri, onProgress)
        } else {
            walkFilePath(rootUri, onProgress)
        }
        SourceWalkResult.Complete(files)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        val reason = error.message ?: "Unable to traverse source root"
        when (error) {
            is SourceUnavailableException,
            is SecurityException,
            is IOException,
            is OperationCanceledException -> SourceWalkResult.Unavailable(reason)
            else -> SourceWalkResult.Failed(reason)
        }
    }

    override suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? {
        if (maxBytes <= 0L || file.sizeBytes > maxBytes) return null
        currentCoroutineContext().ensureActive()
        return try {
            when {
                file.documentUri != null -> readSafUtf8(Uri.parse(file.documentUri), maxBytes)
                file.filePath != null -> {
                    val resources = CancellableReadResources()
                    withReadCancellation(resources) {
                        val stream = File(file.filePath).inputStream()
                        resources.attach(stream)
                        readBoundedUtf8(stream, maxBytes)
                    }
                }
                else -> null
            }
        } catch (failure: Exception) {
            // Closing a descriptor-backed stream is what unblocks a hostile provider read, but that
            // blocked read may resume as IOException. Preserve the coroutine cancellation contract
            // before M3uPlaylistResolver can classify ordinary provider errors as an optional miss.
            currentCoroutineContext().ensureActive()
            throw failure
        }
    }

    /**
     * Opens the provider descriptor with a framework CancellationSignal, then keeps the descriptor-backed
     * stream registered with the coroutine. Cancellation both signals a blocked provider open and closes
     * a stream blocked in read; neither operation depends on the occupied IO thread reaching a poll.
     */
    private suspend fun readSafUtf8(uri: Uri, maxBytes: Long): String? {
        val resolver = contentResolver ?: return null
        val resources = CancellableReadResources(CancellationSignal())
        return withReadCancellation(resources) {
            // ContentResolver.openFileDescriptor routes ordinary reads through the provider's typed
            // asset compatibility path, where Android can discard the supplied CancellationSignal.
            // A direct client open reaches ContentProvider.openFile(uri, mode, signal) instead.
            val descriptor = resolver.acquireUnstableContentProviderClient(uri)?.use { client ->
                client.openFile(uri, "r", resources.signal)
            } ?: return@withReadCancellation null
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            resources.attach(stream)
            currentCoroutineContext().ensureActive()
            readBoundedUtf8(stream, maxBytes)
        }
    }

    private suspend fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Long): String? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8L * 1024L).toInt())
        val buffer = ByteArray(4 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private suspend fun <T> withReadCancellation(
        resources: CancellableReadResources,
        block: suspend () -> T,
    ): T {
        val relay = CompletableDeferred<Unit>(currentCoroutineContext()[Job])
        val handle = relay.invokeOnCompletion { cause ->
            if (cause is CancellationException) resources.cancel()
        }
        return try {
            block()
        } finally {
            resources.close()
            handle.dispose()
            relay.complete(Unit)
        }
    }

    private class CancellableReadResources(
        val signal: CancellationSignal? = null,
    ) : Closeable {
        private val cancelled = AtomicBoolean(false)
        private val active = AtomicReference<Closeable?>()

        fun attach(resource: Closeable) {
            check(active.compareAndSet(null, resource)) { "M3U read resource already attached" }
            if (cancelled.get()) closeActive()
        }

        fun cancel() {
            cancelled.set(true)
            signal?.cancel()
            closeActive()
        }

        override fun close() = closeActive()

        private fun closeActive() {
            runCatching { active.getAndSet(null)?.close() }
        }
    }

    // ── SAF tree walk (framework DocumentsContract) ──────────────────────────

    private suspend fun walkSafTree(
        treeUriString: String,
        onProgress: (Int) -> Unit,
    ): List<WalkedFile> {
        val resolver = contentResolver
            ?: throw SourceTraversalRejectedException("SAF traversal requires an Android context")
        val treeUri = Uri.parse(treeUriString)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri).takeIf(String::isNotBlank)
            ?: throw SourceTraversalRejectedException("SAF provider returned an empty root document id")
        val guard = SourceTraversalGuard(limits)
        guard.beginRoot("saf:$rootDocId")
        val out = ProgressWalkList(onProgress)
        withSafCancellationSignal { cancellationSignal ->
            walkSafDir(
                treeUri = treeUri,
                parentDocId = rootDocId,
                relativePrefix = "",
                depth = 0,
                resolver = resolver,
                cancellationSignal = cancellationSignal,
                guard = guard,
                out = out,
            )
        }
        currentCoroutineContext().ensureActive()
        out.sortBy(WalkedFile::relativePath)
        return out
    }

    /**
     * Keep one provider [CancellationSignal] live for the complete recursive cursor walk. The relay
     * has no work or children of its own, so parent cancellation completes it immediately and runs
     * [CancellationSignal.cancel] even when this coroutine's IO thread is blocked inside `query()` or
     * `moveToNext()`.
     */
    private suspend fun <T> withSafCancellationSignal(
        block: suspend (CancellationSignal) -> T,
    ): T {
        val signal = CancellationSignal()
        val relay = CompletableDeferred<Unit>(currentCoroutineContext()[Job])
        val handle = relay.invokeOnCompletion { cause ->
            if (cause is CancellationException) signal.cancel()
        }
        return try {
            block(signal)
        } finally {
            handle.dispose()
            relay.complete(Unit)
        }
    }

    private suspend fun walkSafDir(
        treeUri: Uri,
        parentDocId: String,
        relativePrefix: String,
        depth: Int,
        resolver: ContentResolver,
        cancellationSignal: CancellationSignal,
        guard: SourceTraversalGuard,
        out: MutableList<WalkedFile>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null, cancellationSignal)
            ?: fail("SAF provider returned no cursor for $childrenUri")
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val docId = cursor.getString(idIdx)?.takeIf(String::isNotBlank)
                    ?: throw SourceTraversalRejectedException(
                        "SAF provider returned a child without a document id",
                    )
                val name = cursor.getString(nameIdx)?.takeIf(::isSafePathComponent)
                    ?: throw SourceTraversalRejectedException(
                        "SAF provider returned an invalid display name",
                    )
                val mime = cursor.getString(mimeIdx)
                    ?: throw SourceTraversalRejectedException(
                        "SAF provider returned a child without a MIME type",
                    )
                val relative = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDirectory && isMimirStagingFolder(name)) continue
                guard.observeNode(
                    identity = "saf:$docId",
                    relativePath = relative,
                    depth = depth + 1,
                    isDirectory = isDirectory,
                )
                if (isDirectory) {
                    walkSafDir(
                        treeUri = treeUri,
                        parentDocId = docId,
                        relativePrefix = relative,
                        depth = depth + 1,
                        resolver = resolver,
                        cancellationSignal = cancellationSignal,
                        guard = guard,
                        out = out,
                    )
                } else {
                    val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    out += WalkedFile(
                        displayName = name,
                        relativePath = relative,
                        sizeBytes = size,
                        documentUri = docUri.toString(),
                        filePath = null,
                    )
                }
            }
        }
    }

    // ── Plain-file walk (managed/library fallback) ───────────────────────────

    private suspend fun walkFilePath(path: String, onProgress: (Int) -> Unit): List<WalkedFile> {
        val root = File(path)
        if (!root.exists()) fail("Source root does not exist: $path")
        if (!root.canRead()) fail("Source root is not readable: $path")
        // **R-21: a single game IS a source.** A field report showed Add games rejecting a single
        // `Blasphemous 2.steam` in Downloads.
        //
        // This line used to read `if (!root.isDirectory) fail(...)`, and it was the last of four gates
        // between him and that file. The first three were in feature-files -- CONFIRM consumed a FILE,
        // CommitSource read currentPath, and the dispatch had no way to know it was a source pick. Opening
        // those let the path reach here, where the walker refused it for a fourth reason nobody had looked
        // at yet, because "a source is a folder" had been true since the beginning.
        //
        // A one-file walk rather than a special case downstream: the file becomes its own root, its
        // relativePath is its own name, and every consumer past this point -- identity, grouping,
        // relocation, the resident key -- sees the shape it already understands. The alternative,
        // enrolling the parent folder and filtering, would have enrolled everything else in Downloads.
        if (!root.isDirectory) {
            currentCoroutineContext().ensureActive()
            return listOf(
                WalkedFile(
                    displayName = root.name,
                    relativePath = root.name,
                    sizeBytes = root.length(),
                    documentUri = null,
                    filePath = root.absoluteFile.path,
                ),
            )
        }
        val canonicalRoot = root.toPath().toRealPath().toFile()
        val guard = SourceTraversalGuard(limits)
        guard.beginRoot("file:${canonicalRoot.path}")
        val out = ProgressWalkList(onProgress)
        walkFileDir(
            canonicalRoot = canonicalRoot,
            dir = root.absoluteFile,
            relativePrefix = "",
            depth = 0,
            guard = guard,
            out = out,
        )
        currentCoroutineContext().ensureActive()
        out.sortBy(WalkedFile::relativePath)
        return out
    }

    private suspend fun walkFileDir(
        canonicalRoot: File,
        dir: File,
        relativePrefix: String,
        depth: Int,
        guard: SourceTraversalGuard,
        out: MutableList<WalkedFile>,
    ) {
        Files.newDirectoryStream(dir.toPath()).use { children ->
            for (childPath in children) {
                currentCoroutineContext().ensureActive()
                val child = childPath.toFile()
                val relative = if (relativePrefix.isEmpty()) child.name else "$relativePrefix/${child.name}"
                val canonicalChild = child.toPath().toRealPath().toFile()
                val isDirectory = canonicalChild.isDirectory
                if (!canonicalChild.isWithin(canonicalRoot)) {
                    throw SourceTraversalRejectedException("Source traversal escaped the selected root")
                }
                // Mimir's own working folder: a staged payload is a file mid-journey, not a game on the shelf.
                if (isDirectory && isMimirStagingFolder(child.name)) continue
                guard.observeNode(
                    identity = "file:${canonicalChild.path}",
                    relativePath = relative,
                    depth = depth + 1,
                    isDirectory = isDirectory,
                )
                if (isDirectory) {
                    walkFileDir(
                        canonicalRoot = canonicalRoot,
                        dir = child,
                        relativePrefix = relative,
                        depth = depth + 1,
                        guard = guard,
                        out = out,
                    )
                } else {
                    out += WalkedFile(
                        displayName = child.name,
                        relativePath = relative,
                        sizeBytes = child.length(),
                        documentUri = null,
                        filePath = child.absolutePath,
                    )
                }
            }
        }
    }

    private fun File.isWithin(root: File): Boolean {
        if (path == root.path) return true
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        return path.startsWith(rootPrefix)
    }

    private fun isSafePathComponent(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            '/' !in value && '\\' !in value && '\u0000' !in value

    private fun fail(message: String): Nothing = throw SourceUnavailableException(message)

    private class SourceUnavailableException(message: String) : IllegalStateException(message)
}

/**
 * The folder Mimir stages its own file operations in, next to the files they touch.
 *
 * **The library scan never descends into it**. It holds files in transit: an
 * extraction's unpacked payload before it is placed, and a delete held for Undo. Indexing one would put a
 * game on the shelf at a path that vanishes seconds later, or that the player has just deleted. The name is
 * the literal `core-files`' `FileOperationStaging.ROOT_DIRECTORY` carries; this module does not depend on
 * `core-files`, so `app`'s `StagingFolderNameParityTest` pins the two together.
 */
const val MIMIR_STAGING_FOLDER_NAME = ".mimir-staging"

internal fun isMimirStagingFolder(name: String): Boolean = name.equals(MIMIR_STAGING_FOLDER_NAME, ignoreCase = true)

/**
 * The accumulator both traversals fill, which reports its own size as it grows.
 *
 * **Why a list subclass rather than a callback threaded through the recursion.** Both walks descend
 * through helpers that already pass `out` down (`walkFileDir`, and the SAF cursor loop), so putting
 * the reporting on the list touches two construction sites instead of every recursive signature and
 * call. Nothing about traversal order, bounds, or the visited-node guards changes.
 *
 * **Throttled, deliberately.** A [StateFlow] write per file on a tens-of-thousands-entry library is
 * the once-a-second-noise mistake at a different scale. [PROGRESS_STEP] files between reports keeps a
 * big scan legible and a small one nearly free; the scan's final count is published by the caller
 * from the completed list, so the throttle can never cause the last number to be wrong.
 */
private class ProgressWalkList(
    private val onProgress: (Int) -> Unit,
) : ArrayList<WalkedFile>() {
    override fun add(element: WalkedFile): Boolean {
        val added = super.add(element)
        if (size % PROGRESS_STEP == 0) onProgress(size)
        return added
    }

    private companion object {
        const val PROGRESS_STEP = 64
    }
}
