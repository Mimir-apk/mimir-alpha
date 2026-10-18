package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.files.toJobKind
import com.gruning.mimir.rewrite.core.files.ArchiveHelper
import com.gruning.mimir.rewrite.core.files.ConflictResolution
import com.gruning.mimir.rewrite.core.files.DiscSetWarning
import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy
import com.gruning.mimir.rewrite.core.files.ExtractionCancelOutcome
import com.gruning.mimir.rewrite.core.files.FileConflictChoice
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.files.FileFailureReasons
import com.gruning.mimir.rewrite.core.files.FileOperationConflictException
import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.files.HeldDeleteListing
import com.gruning.mimir.rewrite.core.files.InterruptedExtraction
import com.gruning.mimir.rewrite.core.files.InterruptedExtractions
import com.gruning.mimir.rewrite.core.files.LocalFileRepository
import com.gruning.mimir.rewrite.core.files.ManagedFileOperations
import com.gruning.mimir.rewrite.core.files.PlatformDestination
import com.gruning.mimir.rewrite.core.files.PlatformDestinationPolicy
import com.gruning.mimir.rewrite.core.files.PlatformFolderCandidate
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import com.gruning.mimir.rewrite.core.files.StorageVolume
import com.gruning.mimir.rewrite.core.files.StorageVolumeHelper
import com.gruning.mimir.rewrite.core.files.StorageVolumeListing
import com.gruning.mimir.rewrite.core.files.fileSearchMatchesTokens
import com.gruning.mimir.rewrite.core.files.fileSearchQueryTokens
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.model.library.LibraryDepartureReason
import com.gruning.mimir.rewrite.core.model.library.LibraryRelocation
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import com.gruning.mimir.rewrite.core.resolver.library.DiscCompanionFiles
import com.gruning.mimir.rewrite.feature.files.FileBrowserChecksumState
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import com.gruning.mimir.rewrite.feature.files.FileBrowserRecognition
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion

/**
 * Owns the one checksum read that may be in flight for the browser.
 *
 * The browser can press Verify repeatedly while the read is still active. A repeated request for the
 * same path is therefore a no-op; a request for another path cancels and replaces the old read. Every
 * result is checked against both the request id and the opaque session identity, so cancellation that
 * cannot interrupt an already-blocked filesystem read still cannot publish stale state or callbacks.
 */
internal class FileBrowserChecksumRequestCoordinator(
    private val scope: CoroutineScope,
    private val algorithm: String,
    private val compute: suspend (String) -> String,
    private val publish: (FileBrowserChecksumState) -> Unit,
) {

    private data class Request(
        val id: Long,
        val path: String,
        val session: Any,
    )

    private var nextRequestId = 0L
    private var current: Request? = null
    private var currentJob: Job? = null

    fun request(
        path: String,
        session: Any,
        onChecksum: (String) -> Unit,
        onChecksumFailed: (Throwable) -> Unit,
    ) {
        if (currentJob?.isActive == true && current?.session === session && current?.path == path) return

        currentJob?.cancel()
        val request = Request(
            id = ++nextRequestId,
            path = path,
            session = session,
        )
        current = request
        publish(FileBrowserChecksumState.Checking(path))

        currentJob = scope.launch {
            try {
                val digest = try {
                    compute(path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isCurrent(request)) return@launch
                    publish(FileBrowserChecksumState.Failed(path, "Couldn't read this file"))
                    onChecksumFailed(e)
                    return@launch
                }
                if (!isCurrent(request)) return@launch
                publish(FileBrowserChecksumState.Checked(path, algorithm, digest))
                onChecksum(digest)
            } finally {
                if (current?.id == request.id) {
                    current = null
                    currentJob = null
                }
            }
        }
    }

    fun clear() {
        currentJob?.cancel()
        currentJob = null
        current = null
        publish(FileBrowserChecksumState.NotChecked)
    }

    private fun isCurrent(request: Request): Boolean =
        current?.id == request.id &&
            current?.path == request.path &&
            current?.session === request.session
}

/**
 * Owns the live file browser, so the shell and the settings surface can both see it.
 *
 * Slice 6  swap: this replaces the retired
 * `feature-library` `FolderBrowserStore`, which owned a `feature.library.FolderBrowserController`
 * backed by `FolderBrowserEnumeration`'s plain `File.listFiles()`. This store owns a `feature-files`
 * [FileBrowserController] instead, backed by `core-files`'s vendored engine.
 *
 * A store rather than coordinator-local state, following `ControllerMappingStore`: the overlay
 * coordinator is recomposed, and a browser that is mid-navigation must outlive those recompositions or
 * the player's path stack resets underneath them.
 *
 * Deliberately holds the controller itself rather than a copy of its state. Presence IS the overlay's
 * visibility — `folderBrowser = folderBrowser() != null` in `MimirOverlayCoordinator`'s snapshot — so
 * there is one truth about whether the browser is open, not two that can disagree.
 *
 * **Does NOT call [FileBrowserController.open].** Unlike the retired controller (whose `.open()` this
 * store called eagerly, synchronously, at the moment the browser opened), [FileBrowserController.open]
 * is deliberately owned by `FileBrowserSurface`'s own `DisposableEffect` -- see that class's own KDoc:
 * "Call open() at most once per mount -- FileBrowserSurface already does this for you." Calling it here
 * too would push its `FocusScope` twice. `MimirOverlayCoordinator`'s `FOLDER_BROWSER` dispatch branch
 * absorbs input unconditionally for exactly the one-frame gap this creates between "the store holds a
 * controller" and "that controller's scope has actually landed" -- see that file's comment on the
 * `FOLDER_BROWSER` branch. [switchVolume] leans on the same ownership rule rather than carving an
 * exception out of it.
 *
 * **Volume switching**. This store used
 * to be handed a single `rootPath` -- `FolderBrowserEnumeration.storageRoots().firstOrNull()`, the FIRST
 * root only -- and [FileBrowserController] cannot navigate above its own root by construction, so an SD
 * card was unreachable. It now enumerates the device's real volumes through `core-files`'
 * [StorageVolumeHelper] (vendored in slice 2 and, until this change, wired to nothing) and roots the
 * browser through [StorageVolumeListing.browserRoot]: at the volume chooser when the device has more
 * than one drive, directly at the single drive otherwise, so a one-drive device sees no change at all.
 * See `core-files/StorageVolumeListing.kt`'s own KDoc for why the chooser's rows are synthesised instead
 * of read from a real `/storage` listing.
 */
internal object FileBrowserStore {

    private val repository = LocalFileRepository()
    private val archiveHelper = ArchiveHelper()

    /**
     * **F-01's destructive engine — non-null ONLY while a [FileBrowserMode.MANAGE] session is open.**
     *
     * This field is the enforcement point of the PICK-mode non-destructive contract, and it is deliberately a
     * nullable field set in [open] rather than a `val` beside [repository]. Two independent things have
     * to hold for a delete to be possible, and both are visible here:
     *
     *  1. [ManagedFileOperations] is a **different class** from [repository] — see its own KDoc. There
     *     is no `deleteFiles` on `LocalFileRepository` to call, so a PICK session's engine is incapable
     *     of destruction rather than merely discouraged from it.
     *  2. This field is **only ever assigned** in [open]'s `mode == MANAGE` branch, so in PICK it is
     *     null and every entry point below returns early.
     *
     * Pinned by `FileBrowserStorePickSafetyTest`. If that test ever fails, a player adding ROMs can
     * delete their library — treat it as a P0, not a flaky assertion.
     */
    private var operations: ManagedFileOperations? = null

    private val mutableQueue = MutableStateFlow<List<FileQueueEntry>>(emptyList())

    /**
     * The operation queue (F-01). Empty in PICK, always — nothing in PICK can enqueue.
     *
     * A `StateFlow` on the store for the same reason the controller lives here: the Utility panel and
     * the browser surface are recomposed independently, and a queue that died with either of them would
     * lose an Undo the player can still see.
     */
    val queue: StateFlow<List<FileQueueEntry>> = mutableQueue.asStateFlow()

    private var nextQueueId = 1L

    /** The running jobs a card's Cancel can stop, and which of them have been told to. */
    private val jobControl = FileBrowserJobControl()

    /**
     * The digest the inspector's "Verify" computes. **SHA-1, named explicitly, never the default.**
     *
     * [LocalFileRepository.getChecksum] defaults to SHA-256, and the corpus this verification exists to
     * check against is keyed on SHA-1 (`core-metadata/BundledDatSource.kt:17` -- "the frozen corpus has
     * SHA-1 for every admitted source row") and CRC32 (`core-metadata/DatIdentitySource.kt:5`). Taking
     * the default would compute a real, correct, PERMANENTLY UNMATCHABLE hash: a Verify button that can
     * never say yes, whose failure would read as missing catalogue data rather than as the wrong
     * algorithm. Pinned in `core-files` by `LocalFileRepositoryTest.getChecksum computes real digests,
     * and its default is not the DAT corpus algorithm`, which fails the moment this method stops
     * honouring its algorithm argument.
     *
     * `internal` rather than `private` so the choice itself is pinnable -- see
     * `FileBrowserStoreChecksumTest`, and that test's own disclosure of what it still cannot see.
     */
    internal const val CHECKSUM_ALGORITHM = "SHA-1"

    // Process-lifetime scope for extraction: a player can trigger SECONDARY on an archive and
    // immediately back out of the browser, and the extraction should still land rather than being
    // cancelled along with whichever composable happened to trigger it. Matches this codebase's own
    // precedent for singleton-owned background work that must outlive its triggering composable
    // (StorageVolumeMonitor.kt, GameIdentityRemapReplayer.kt). Main, not IO: ArchiveHelper.extract
    // already hops itself to Dispatchers.IO internally, so the onExtracted/onExtractFailed callbacks
    // below -- which MimirSettingsCoordinator uses to show a Toast -- land back on Main for free
    // instead of crashing off it. LocalFileRepository.getChecksum is `suspend` + withContext(IO) for the
    // same reason, so onChecksum/onChecksumFailed land on Main too.
    /** Extraction and checksum work outlive the browser surface, so contain failures at this boundary. */
    internal val scopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e("MimirFileBrowser", "background browser work failed in $context", throwable)
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + scopeFailureHandler)

    /**
     * One directory's rows, remembered for as long as the browser session lives.
     *
     * **This is what makes backing out of a folder free.** Up returns to a directory that was listed
     * seconds ago; without a memo it paid the full filesystem read again on the main thread,
     * measured at ~90 ms of a parked main thread on the Odin, 2026-08-15.
     *
     * **Main-confined.** Every read and write happens on [scope]'s dispatcher (`Dispatchers.Main`):
     * [listDirectoryAsync]'s fast path runs undispatched on main, [revalidate]'s publish resumes on main,
     * and eviction is called from [enqueue]/[setState], which are main too. A plain map is therefore
     * correct and a synchronised one would only hide a threading mistake.
     *
     * **Stale-while-revalidate, never stale-forever.** A hit is served immediately AND re-read in the
     * background; a difference publishes through [FileBrowserController.refresh]. Anything this process
     * changes itself evicts outright rather than waiting for that — see [evictListings].
     */
    private val listingCache = mutableMapOf<String, List<FileItem>>()

    /** Paths with a background re-read already running, so a repeated visit cannot stack them up. */
    private val revalidating = mutableSetOf<String>()

    private val mutableController = MutableStateFlow<FileBrowserController?>(null)
    val controller: StateFlow<FileBrowserController?> = mutableController.asStateFlow()

    /**
     * Whether the file browser owns the screen right now — **either mode, PICK or MANAGE.**
     *
     * **Both PICK and MANAGE must suppress unrelated controller shortcuts and macros.**
     * Only inputs needed by the active browser should pass through.
     *
     * The browser's own dispatch table is a hard modal and swallows every unbound action precisely so
     * nothing falls through. Controller SHORTCUTS never went through that table at all — they are
     * resolved before it, in `InputRouter`, and published straight to the M-button command bus. So the
     * modal was airtight and the macro walked around it.
     *
     * Reads `session != null` as well as the controller, on `open`'s own reasoning one screen down: there
     * is a window after `open()` where the session exists and the controller has not mounted yet, and a
     * shortcut arriving in that window is exactly the kind of edge a `!= null` on the controller alone
     * would let through.
     */
    val screenOwned: Boolean get() = mutableController.value != null || session != null

    private val mutableChecksum = MutableStateFlow<FileBrowserChecksumState>(FileBrowserChecksumState.NotChecked)

    private val checksumRequests = FileBrowserChecksumRequestCoordinator(
        scope = scope,
        algorithm = CHECKSUM_ALGORITHM,
        compute = { path -> repository.getChecksum(path, CHECKSUM_ALGORITHM) },
        publish = { state -> mutableChecksum.value = state },
    )

    /**
     * The inspector's Checksum row state — **F-05**. Held here rather than in the controller because
     * nothing may self-transition: a controller-side latch would flip to "Checking…" on press and, under
     * `onVerifyChecksum`'s no-op default, stay there forever. That is the same fabricated outcome, one state further along.
     */
    val checksum: StateFlow<FileBrowserChecksumState> = mutableChecksum.asStateFlow()

    private val mutableVolumes = MutableStateFlow<List<StorageVolume>>(emptyList())

    /**
     * The device's mounted drives, refreshed on every enumeration ([open], and every visit to the
     * chooser). Exposed so a surface can offer an explicit drive switcher: [StorageVolume] carries
     * `totalBytes`/`freeBytes`/`usagePercent`, which is what the adoption spec's Storage segments want.
     * Nothing renders this yet -- the chooser root is what makes drive switching reachable today, and
     * [switchVolume] is the entry point a rail would call.
     */
    val volumes: StateFlow<List<StorageVolume>> = mutableVolumes.asStateFlow()

    /**
     * Everything a re-root needs to rebuild the controller identically.
     *
     * [FileBrowserController.rootPath] is a `val` by design, so re-rooting means a new instance, not a
     * mutation -- and a new instance needs every callback the original was constructed with. Holding
     * them here is what lets [switchVolume] take a bare path instead of making its caller re-supply the
     * whole browser configuration.
     */
    private class Session(
        val context: Context,
        val authority: FocusAuthority,
        val mode: FileBrowserMode,
        val onChoose: (String) -> Unit,
        val onExtracted: (archivePath: String, destination: String, count: Int) -> Unit,
        // §6.2 : move/copy slice 3. Shaped exactly like
        // [onExtracted] above -- (subject, destination, count) -- with `label` standing in for
        // `archivePath` because [transfer] operates over a `List<String>`, not one archive; `label` is
        // the same string already shown as the queue entry's own title, so the sink and the card agree
        // by construction rather than by two authors matching wording. `destination` is the RAW full
        // path, same convention [onExtracted] uses -- the coordinator truncates it with `File(...).name`
        // for display, not this store.
        // `sources` is the raw source path list, and it is here for R1 rather than for display: a Move
        // rescans the folders TOUCHED, and a move touches two --
        // the destination gains a file and **the origin loses one**. The origin is the load-bearing
        // half: a moved ROM's stale `games` row is pruned by rescanning the root it left, not the one
        // it arrived in (spec §8.3), and without that the tile survives on the shelf pointing at a path
        // that no longer exists. `destination` alone cannot express that, so the sink carries both ends.
        val onMoved: (label: String, sources: List<String>, destination: String, count: Int) -> Unit,
        val onCopied: (label: String, sources: List<String>, destination: String, count: Int) -> Unit,
        val onDeleted: (label: String, count: Int) -> Unit,
        /** A rename finished: the old path and the new one, so the library can be told to look again. */
        val onRenamed: (oldPath: String, newPath: String) -> Unit,
        val onExtractFailed: (archivePath: String, error: Throwable) -> Unit,
        val onChecksum: (path: String, digest: String) -> Unit,
        val onChecksumFailed: (path: String, error: Throwable) -> Unit,
        val classifyPlatform: (FileItem) -> FileBrowserRecognition,
    )

    private var session: Session? = null

    /**
     * Open the browser at whichever root the device's storage justifies -- see this object's KDoc.
     *
     * @param context needed only for volume enumeration ([StorageVolumeHelper] wraps `StorageManager` /
     *   `getExternalFilesDirs` / `StatFs`). That `Context` requirement is exactly why volume work lives
     *   in this module and not in `feature-files`, whose controller is deliberately ignorant of anything
     *   past [FileItem].
     * @param onChecksum the "Verify" result, as a [CHECKSUM_ALGORITHM] hex digest.
     * @param classifyPlatform recognises a listing row as a platform; feeds the tome/file glyphs and the
     *   inspector's "Launches" row. See `FileBrowserPlatformClassifier.kt`.
     */
    fun open(
        context: Context,
        authority: FocusAuthority,
        /**
         * [FileBrowserMode.PICK] (default) for add-games: browse, extract, and commit a folder.
         * [FileBrowserMode.MANAGE] for the Utility entry: the file manager proper, with no commit
         * affordance. [FileBrowserMode.PICK_IMAGE] for the Customize-media picker's Upload
         * (`MimirAppCoordinator.startImageImport`): browse like PICK and commit one image FILE; the
         * MANAGE gate below leaves it exactly as incapable of a delete as PICK.
         *
         * Capability splits by MODE, not by
         * screen. The destructive half — move, delete, the queue — will live only in MANAGE, reached
         * deliberately through Utility. **A player adding ROMs can never delete anything**, because the
         * picker cannot reach those operations at all rather than merely hiding them.
         */
        mode: FileBrowserMode = FileBrowserMode.PICK,
        onChoose: (String) -> Unit,
        onExtracted: (archivePath: String, destination: String, count: Int) -> Unit = { _, _, _ -> },
        // §6.2, slice 3. Defaulted no-op like every sink here: MimirSettingsCoordinator's PICK-mode call
        // site never supplies one (see that file's own comment), exactly mirroring `onDeleted`'s existing
        // silent omission there -- `transfer()` cannot fire either sink outside MANAGE (`operations ?:
        // return`), so an unsupplied sink there is inert by construction, not a gap.
        onMoved: (label: String, sources: List<String>, destination: String, count: Int) -> Unit =
            { _, _, _, _ -> },
        onCopied: (label: String, sources: List<String>, destination: String, count: Int) -> Unit =
            { _, _, _, _ -> },
        onDeleted: (label: String, count: Int) -> Unit = { _, _ -> },
        onRenamed: (oldPath: String, newPath: String) -> Unit = { _, _ -> },
        onExtractFailed: (archivePath: String, error: Throwable) -> Unit = { _, _ -> },
        onChecksum: (path: String, digest: String) -> Unit = { _, _ -> },
        onChecksumFailed: (path: String, error: Throwable) -> Unit = { _, _ -> },
        classifyPlatform: (FileItem) -> FileBrowserRecognition = { FileBrowserRecognition.None },
    ) {
        // Re-opening while already open would strand the previous controller's pushed focus scope.
        // `session != null` closes a SECOND window the fix below opens: between this call setting
        // `session` (right below) and its scope.launch actually mounting, mutableController is still
        // null, so the old `mutableController.value != null` guard alone would let a second open() call
        // slip through during that gap and race two sessions over one controller slot.
        if (mutableController.value != null || session != null) return
        val opened = Session(
            context = context,
            authority = authority,
            mode = mode,
            onChoose = onChoose,
            onExtracted = onExtracted,
            onMoved = onMoved,
            onCopied = onCopied,
            onDeleted = onDeleted,
            onRenamed = onRenamed,
            onExtractFailed = onExtractFailed,
            onChecksum = onChecksum,
            onChecksumFailed = onChecksumFailed,
            classifyPlatform = classifyPlatform,
        )
        session = opened
        // F-01 / policy §3c. The ONE line that decides whether this session can destroy anything.
        // PICK gets null, and every destructive entry point below returns early on null -- on top of
        // ManagedFileOperations being a class the PICK path never names at all.
        operations = if (mode == FileBrowserMode.MANAGE) ManagedFileOperations(repository) else null
        // Where an extraction leaves its pointer while it runs, so a LATER start can find one this process
        // did not finish (E2 P2). Both modes: the picker extracts too, even though only MANAGE has a queue.
        InterruptedExtractions.configure(java.io.File(context.noBackupFilesDir, "file-operations"))
        operations?.let { ops -> reportInterruptedExtractions(opened) { InterruptedExtractions.reconcile(ops.heldRoots()) } }
        val rootPath = StorageVolumeListing.browserRoot(enumerateVolumes(context))
        // F-06's smart default reads the real library, and the mount now WAITS on it. A missing extraction suggestion was reported on 2026-08-14. This
        // used to fire FileBrowserRomFolders.refresh() with scope.launch and mount() synchronously in
        // the same breath, never awaiting the refresh, so a player fast enough to reach SECONDARY on an
        // archive before that Room read landed hit an empty FileBrowserRomFolders and got the
        // honest-but-wrong "nothing to suggest" instead of the real platform folder. mutableController
        // stays null -- nothing can dispatch SECONDARY, nothing can reach extractOptions -- until the
        // refresh has landed. Still off the input thread: this whole block runs on Dispatchers.Main via
        // a coroutine, not a blocking call on it. See openAfterRomFolderRefresh and
        // FileBrowserRomFolders' own KDoc for the two halves of this fix.
        scope.launch {
            openAfterRomFolderRefresh(
                refresh = { FileBrowserRomFolders.refresh(context) },
                stillCurrent = { session === opened },
                mount = { mount(opened, rootPath) },
            )
        }
        // The destination LIST's other half , and deliberately NOT part of the
        // sequence above. Discovery reads directories, and the mount must not wait on a filesystem walk:
        // that is exactly the cost the async listing seam was built to remove from the path that opens
        // this browser. It lands within a second, long before a player has navigated to an archive and
        // pressed X, and if it has not, the list is shorter for that press rather than absent -- the
        // library's own folders are already there from the awaited refresh.
        scope.launch {
            if (session === opened) FileBrowserRomFolders.discover(context)
        }
    }

    /**
     * [open]'s pre-mount sequencing: await [refresh], then run [mount] -- but only if [stillCurrent]
     * still says so by the time [refresh] returns. Lifted out of [open] so the ordering itself is
     * directly testable with fakes -- no `Context`, no real `Session` -- following [runExtraction]'s own
     * precedent for pulling this store's async flow bodies somewhere a plain JUnit test can drive them.
     *
     * **[mount] is skipped, not merely delayed, when [stillCurrent] is false once [refresh] completes.**
     * The player can back out of the browser -- or the shell can tear it down around them -- while the
     * read is still in flight ([close] / [clearSession] null out `session`), and running [mount] after
     * that would resurrect a browser the player already dismissed. [open] supplies `{ session === opened }`
     * as [stillCurrent]: an identity check against the exact `Session` this coroutine was launched for,
     * not merely "is *some* session open" -- a player who closes this browser and opens a new one before
     * the old refresh lands must not have the NEW session's mount stolen by the old coroutine either.
     *
     * **Disclosed gap, [runExtraction]'s own precedent again:** this proves the sequence -- refresh
     * completes before mount runs, and a torn-down session is never resurrected. It does not prove [open]
     * wires it to the REAL [FileBrowserRomFolders.refresh] and the real [mount] rather than two fakes that
     * happen to compile; that is a source-level check, the same idiom `FileBrowserStorePickSafetyTest`
     * uses for F-01.
     */
    internal suspend fun openAfterRomFolderRefresh(
        refresh: suspend () -> Unit,
        stillCurrent: () -> Boolean,
        mount: () -> Unit,
    ) {
        refresh()
        if (stillCurrent()) mount()
    }

    // ---- F-01: the queue, and the manager's destructive actions. MANAGE only. ----

    /**
     * Delete [paths], leaving the queue holding **Undo**.
     *
     * No-op in PICK. The entry lands in [FileQueueState.UndoAvailable] on success, which is the state
     * that carries the live [com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion] handle — so the Undo
     * button cannot exist without the means to honour it.
     *
     * The player's files are off their listing immediately (that is what they asked for) and intact
     * under `.mimir-staging` until [releaseUndo]. A crash in between restores them.
     */
    fun deleteInManager(paths: List<String>) {
        val ops = operations ?: return
        if (paths.isEmpty()) return
        val id = nextQueueId++
        // The card counts what the player picked, not the companions that ride along (see the job below).
        val label = if (paths.size == 1) File(paths.single()).name else "${paths.size} files"
        enqueue(
            FileQueueEntry(
                id = id,
                kind = JobKind.DELETE,
                title = label,
                // A-2. This line stated the saves claim unconditionally, for every delete. It is now
                // decided by [fileDeleteSubtitle], because `stageDelete` moves whatever path it is handed
                // and directories are supported -- so a player deleting their `saves` folder was told
                // their saves were kept.
                //
                // `File(it).isFile` is one `stat` per selected path, on the calling thread, deliberately
                // synchronous: the card's second line is written once at enqueue and never rewritten, and
                // deciding it asynchronously would change the copy under the player's eye -- the same
                // staleness class F-05's checksum row had to close. It is also strictly less work than
                // the listing this file already does synchronously (see [listDirectory]'s OWED note),
                // over a list the player hand-picked rather than a whole directory.
                subtitle = fileDeleteSubtitle(paths) { File(it).isFile },
                // F-7: null, not 0f -- at enqueue nothing has been staged yet, so 0f would assert a
                // measurement that has not started (the same defect F-6 already fixed for move/copy; see
                // FileQueueState.Running's own KDoc). stageDelete calls its callback once per requested
                // path that actually gets staged, so this promotes to a real completed/total fraction
                // below as entries land, instead of sitting at a false zero for the whole operation.
                state = FileQueueState.Running(fraction = null),
            ),
        )
        scope.launch {
            // **A disc takes its own libcrypt `.sbi` / `.sub` with it** . Left behind they are orphans no scan
            // will ever show again, and a game re-added later would silently pick up a stale one. The same resolver
            // the move and the disc-set warning use, so it is only ever a same-name file in the disc's own folder --
            // the delete stays inside the folder the player chose -- and it is staged with the disc, so one Undo
            // returns both. A `.cue` brings the tracks it lists  except one a staying cue
            // still lists; an `.m3u` brings NOTHING -- its discs stay, and the card says how many. Looked up here, off
            // Main: it stats the disc's name under every disc extension and reads the cues and playlists.
            val plan = withContext(Dispatchers.IO) { planDelete(paths) }
            val everything = plan.everything
            // Known once the lookup is done: how many entries were asked for. stageDelete silently skips a path
            // that no longer exists (its own `if (!Files.exists(source)) continue`), so `staged` can undercount
            // `total` by that amount -- the fraction still reads as real progress against what was requested,
            // and the terminal setState in `fold` below replaces it before that could ever be seen stuck short
            // of 1.0.
            val total = everything.size
            var staged = 0
            ops.stageDelete(everything) {
                staged++
                // Invoked from stageDelete's own withContext(Dispatchers.IO). [setState] reads the queue, replaces
                // one entry and stores the list back, which loses an update when two threads do it -- so the
                // report is posted to Main, as extraction's is . `staged` itself is only touched here.
                postProgress(id, FileQueueState.Running(if (total > 0) staged.toFloat() / total else null))
            }.fold(
                onSuccess = { deletion ->
                    if (deletion.isEmpty) {
                        setState(id, FileQueueState.Failed("Nothing to delete"))
                    } else {
                        setState(id, FileQueueState.UndoAvailable(deletion))
                        // delete gets a voice, and it names the recovery route.
                        // Extract has always announced itself ("Extracted 3 items to Roms") while delete --
                        // the only verb that can lose anything -- said nothing at all.
                        //
                        // Fired HERE, not at the press, and the timing is the point: this is the instant
                        // the staged payload exists and Undo is genuinely available. Announcing at the
                        // press would promise a recovery that is still being written, and there is a real
                        // window where a fast B-at-root closes the browser before staging completes.
                        //
                        // It TELLS rather than MOVES. Sending the player to the queue on every delete
                        // would pull them out of the listing mid-task and make clearing a folder one
                        // round-trip per file; the CLOSE redirect (W-1) already covers the moment that
                        // can actually lose data.
                        // The toast counts what the player picked: a cue's tracks and a disc's `.sbi` ride in the same
                        // held delete, but "Deleted 4 items" for one picked file would be news to them.
                        session?.onDeleted?.invoke(label, deletedPickedCount(deletion.entries, paths))
                        plan.notes.forEach { noteOnCard(id, it) }
                    }
                    mutableController.value?.refresh()
                },
                onFailure = { error -> setState(id, FileQueueState.Failed(error.playerFacingReason())) },
            )
        }
    }

    /**
     * The queue entry's Undo button: put the files back, then drop the entry. No-op in PICK.
     *
     * **Two shapes behind one word**, and the state decides which — a staged delete comes back out of the
     * staging journal, and a completed move goes back as an ordinary move. See [undoMove] for why the
     * second one is not a bespoke restore.
     */
    fun undo(entryId: Long) {
        val ops = operations ?: return
        val entry = mutableQueue.value.firstOrNull { it.id == entryId } ?: return
        (entry.state as? FileQueueState.ReverseAvailable)?.let { return undoMove(entryId, it.reverse) }
        val deletion = (entry.state as? FileQueueState.UndoAvailable)?.deletion ?: return
        scope.launch {
            val result = ops.undoDelete(deletion)
            // Before the refresh, not after: `refresh` re-reads through the cache, and a cache still
            // holding the post-delete listing would hand the restored files straight back out as missing.
            evictListings()
            val error = result.exceptionOrNull()
            if (error != null) {
                // Not put back. The engine has kept the delete held and left its files in staging, so the card and
                // its Undo stay, and the card says so -- dropping it here made a failed Undo look like a finished one.
                mutableQueue.value = mutableQueue.value.map {
                    if (it.id == entryId) it.copy(subtitle = error.playerFacingReason()) else it
                }
                mutableController.value?.refresh()
                return@launch
            }
            mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, entryId)
            mutableController.value?.refresh()
        }
    }

    /**
     * Release an entry's Undo and make its delete permanent.
     *
     * The player dismissing the card, not a timer. Nothing in this store expires an Undo — see
     * [FileOperationQueue]'s own KDoc for why that is the point.
     */
    fun releaseUndo(entryId: Long) {
        val ops = operations ?: return
        val entry = mutableQueue.value.firstOrNull { it.id == entryId } ?: return
        val deletion = (entry.state as? FileQueueState.UndoAvailable)?.deletion ?: return
        scope.launch {
            ops.finalizeDelete(deletion)
            // Finalising empties `.mimir-staging`, which is inside a directory this cache may describe.
            evictListings()
            mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, entryId)
            // After the queue entry is gone: the "Deleting…" row is read from the queue's held list, so it leaves
            // the folder on this refresh and not the next one.
            mutableController.value?.refresh()
        }
    }

    /**
     * Drop a settled card off the queue — **terminal states only**; [fileQueueDismissable] is the rule.
     *
     * The gap `FileBrowserQueue.kt`'s KDoc recorded: [FileOperationQueue.remove] was reachable only from
     * [undo] and [releaseUndo], both of which need a live [FileQueueState.UndoAvailable] handle, so a
     * Done, Failed or Blocked card had no way to leave the queue and accumulated until the session ended.
     *
     * **Deliberately carries no `operations ?: return` guard, unlike every function above it.** This one
     * frees nothing and touches no filesystem: it removes a card whose files are already settled, and the
     * queue is empty by construction in PICK because nothing there can enqueue. Adding it to
     * `FileBrowserStorePickSafetyTest`'s destructive list would dilute what that list means — the property
     * that matters here is [fileQueueDismissable]'s refusal, and it is pinned separately.
     */
    fun dismiss(entryId: Long) {
        mutableQueue.value = fileQueueAfterDismiss(mutableQueue.value, entryId)
    }

    /**
     * The queue card's **Cancel**: stop a running copy, move or extract at its next safe point. No-op in PICK.
     *
     * Policy 2026-10-08. The card reads "Cancelling…" at once and "Cancelled", with what was kept,
     * when the job has wound down -- the engine stops between files and chunks, never inside the step that
     * commits an item, so a move is whole or rolled back and its source is never lost, a copy removes its
     * half-written file, and an extract takes back what it placed (its journal is the list).
     *
     * `operations ?: return` for the same reason every entry point here has it: PICK has no queue, and
     * the cancel of a job the picker could never have started has nothing to reach.
     */
    fun cancel(entryId: Long) {
        operations ?: return
        val running = mutableQueue.value.firstOrNull { it.id == entryId }?.state as? FileQueueState.Running ?: return
        if (jobControl.requestCancel(entryId)) setState(entryId, running.copy(cancelling = true))
    }

    /** A job ended by cancellation before it wrote its own final line: say so rather than spin forever. */
    private fun settleAbandonedCard(id: Long) {
        val entry = mutableQueue.value.firstOrNull { it.id == id } ?: return
        if (entry.state is FileQueueState.Running) setState(id, FileQueueState.Cancelled("Nothing was changed."))
    }

    /** What a stopped copy or move left, written on its card; a move that landed anything tells the library. */
    private fun settleCancelledTransfer(
        id: Long,
        operation: FileOperation,
        picked: List<String>,
        paths: List<String>,
        destination: String,
        landings: Map<String, String>,
        skipped: List<String>,
    ) {
        // The card and the toast count what the player picked; the companions that rode along are not "files" to them.
        val pickedSet = picked.toSet()
        val movedPicked = landings.keys.filter { it in pickedSet }
        if (operation == FileOperation.MOVE && landings.isNotEmpty()) {
            val moved = landings.keys.toList()
            tellLibraryTheFilesMoved(id, moved, destination, landings)
            val counted = movedPicked.ifEmpty { moved }
            val label = if (counted.size == 1) File(counted.single()).name else "${counted.size} files"
            session?.onMoved?.invoke(label, moved, destination, moved.size)
        }
        setState(id, cancelledTransferCard(operation, picked, paths, destination, landings, skipped))
        mutableController.value?.refresh()
    }

    /**
     * Rename the file or folder at [path] to [newName], in the folder it is in. No-op in PICK.
     *
     * Policy 2026-10-08. The name has already been through the keyboard's own checks
     * ([com.gruning.mimir.rewrite.core.files.RenameNamePolicy]); the engine checks again. A name that is
     * already taken stops at the same Keep both / Replace / Skip card a move raises. A finished rename is
     * undoable ([FileQueueState.ReverseAvailable]), and the library is told through [Session.onRenamed] so
     * a ROM renamed inside a library folder is rescanned like one that was moved.
     */
    fun renameInManager(path: String, newName: String) {
        operations ?: return
        rename(path, newName, choice = null, undoable = true)
    }

    private class PendingRename(val path: String, val newName: String, val undoing: ReversibleMove? = null)

    /** Renames parked on an unanswered name clash, so [resolveConflict] can re-run the right one. */
    private val pendingRenames = mutableMapOf<Long, PendingRename>()

    private fun rename(path: String, newName: String, choice: FileConflictChoice?, undoable: Boolean, undoing: ReversibleMove? = null) {
        val ops = operations ?: return
        val id = nextQueueId++
        val from = File(path)
        enqueue(
            FileQueueEntry(
                id = id,
                kind = JobKind.MOVE,
                title = from.name,
                subtitle = renameSubtitle(from.name, newName),
                state = FileQueueState.Running(fraction = null),
            ),
        )
        val job = scope.launch {
            // **A disc's own libcrypt `.sbi` / `.sub` are renamed with it** : the emulator finds them by the
            // disc's name, so a disc renamed alone loses its protection data ("no SBI file"). What was renamed is
            // recorded on the Undo handle, and Undo reverses exactly that -- see [renameWithCompanions].
            val result = try {
                renameWithCompanions(ops, path, newName, choice?.resolution ?: ConflictResolution.ASK, undoing)
            } catch (_: CancellationException) {
                settleAbandonedCard(id)
                return@launch
            }
            result.fold(
                onSuccess = { renamed ->
                    val landed = renamed.landed
                    if (landed == null) {
                        mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, id)
                    } else {
                        setState(id, renameSettledState(from, landed, undoable, renamed.companionRenames))
                        renamed.stayed.forEach { noteOnCard(id, "$it kept its old name") }
                        mutableController.value?.refresh()
                        session?.onRenamed?.invoke(path, landed)
                    }
                },
                onFailure = { error ->
                    if (error is FileOperationConflictException) {
                        pendingRenames[id] = PendingRename(path, newName, undoing)
                        mutableQueue.value = FileOperationQueue.withState(
                            mutableQueue.value,
                            id,
                            FileQueueState.AwaitingConflict,
                        ).map { entry ->
                            if (entry.id != id) entry else entry.copy(subtitle = "A file named $newName is already there.")
                        }
                    } else {
                        setState(id, FileQueueState.Failed(error.playerFacingReason()))
                    }
                },
            )
        }
        jobControl.track(id, job) { settleAbandonedCard(id) }
    }

    /** Undo of a rename: rename the landed path back to the name it had. The original card leaves first. */
    private fun undoRename(entryId: Long, reverse: ReversibleMove, previousName: String) {
        operations ?: return
        val landed = reverse.landedPaths.singleOrNull() ?: return
        mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, entryId)
        // Exactly the companions the forward rename recorded go back with it -- never a re-search by the new name.
        rename(landed, previousName, choice = null, undoable = false, undoing = reverse)
    }

    /**
     * Move [paths] into [destination]. No-op in PICK. Conflicts stop the entry, never resolve silently.
     *
     * The `operations ?: return` here is deliberately redundant with [transfer]'s own — defence in depth
     * on the one code path where a missing guard costs a player their ROMs. An expression-bodied
     * delegate would put the only guard one refactor away from this function's name, and
     * `FileBrowserStorePickSafetyTest` is written to refuse exactly that shape.
     */
    fun moveInManager(paths: List<String>, destination: String) {
        operations ?: return
        // **A disc's own libcrypt `.sbi` / `.sub` travel with it** , including a single-disc game the library
        // does not call a set, and including "Move just this one" -- the companion belongs to that disc, not to the
        // set. A whole-set move already carries them and is not repeated. [transfer] looks them up, off Main.
        transfer(FileOperation.MOVE, paths, destination, choice = null, carryCompanions = true)
    }

    /**
     * Send a finished move back where it came from.
     *
     * ## It is a move, not a restore
     *
     * This runs [transfer] in the ordinary way, with [FileOperation.MOVE], from the landed files to the
     * folder they left. Everything that makes a move safe therefore applies to the reverse for free:
     * staging with a rollback journal, a conflict card if something has since taken the old name (rather
     * than a silent overwrite of whatever is there now), a progress card, and — the one that matters most
     * here — [tellLibraryTheFilesMoved] on completion, so the library follows the file *back* too.
     *
     * A bespoke "restore" would be a second mover to keep correct, and the first thing it would get wrong
     * is the collision. `ManagedFileOperations.move` deletes its own staging on success, so there is no
     * journal to replay; the only truthful reverse is a new move.
     *
     * ## The original card leaves
     *
     * Dropped before the reverse is queued, exactly as the delete Undo drops its own: two cards for one
     * round trip, one of them offering to undo something that has already been undone, is a queue that
     * lies. The reverse gets its own card, which is honest — it is a real transfer that can itself fail,
     * collide, or take time.
     *
     * ## What it cannot put back
     *
     * **A game that DEPARTED the library is not resurrected by this**, and the reverse move's own
     * relocation is what says so: the row was deleted when it left, so `relocate` on the way back answers
     * `NotInLibrary` and stays quiet. The file is home; the shelf entry returns at the next scan of that
     * root. That is a real gap and it is stated on the card rather than papered over — see the note this
     * leaves for a reverse whose subject had departed.
     */
    private fun undoMove(entryId: Long, reverse: ReversibleMove) {
        // A rename is undone by renaming back, not by moving to another folder.
        reverse.previousName?.let { return undoRename(entryId, reverse, it) }
        // **This is a real MOVE commit that does NOT pass the disc-set warning, and that is correct.**
        // Written down because an audit found this door and it reads like a hole: a player who moved one
        // disc out and pressed Undo is REPAIRING the set, and a screen telling them the move is about to
        // break their game would be the warning firing backwards, on the one press that fixes it.
        // `FileBrowserController.beginMove` gates player-initiated FORWARD moves; this is neither.
        operations ?: return
        mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, entryId)
        transfer(
            FileOperation.MOVE,
            reverse.landedPaths,
            reverse.originFolder,
            choice = null,
            // Said on COMPLETION, not at the press: until the files are actually back, "it'll return to
            // your shelf" is a promise about a transfer that could still collide or fail.
            noteOnSuccess = if (reverse.departedFromLibrary) {
                "The file is back. Your library will re-add the game the next time it scans."
            } else {
                null
            },
        )
    }

    /** Copy [paths] into [destination]. No-op in PICK. Same conflict discipline as [moveInManager]. */
    fun copyInManager(paths: List<String>, destination: String) {
        operations ?: return
        transfer(FileOperation.COPY, paths, destination, choice = null)
    }

    /**
     * Checks for a multi-disc game BEFORE moving one disc, to offer moving the full set.
     *
     * `suspend`, and the caller must keep it off the press frame. This is a Room read plus a small file
     * read. A previous `runBlocking` call on the composition thread cost 33 ms to list three files.
     * The controller uses the same injected-suspend seam as async listing.
     *
     * **Null is the answer for "not a set" AND for "could not ask", deliberately.** A warning that fired
     * because the library was unreachable would train the player to dismiss it, and there is already an
     * honest place where an unreachable library is stated: [noteLibraryUnreachable] fires on the same
     * move's own card, from [tellLibraryTheFilesMoved], for the same underlying cause. One statement, in
     * the surface the player is already looking at, beats a second warning that says "I don't know".
     */
    suspend fun discSetWarningFor(subjectPath: String): DiscSetWarning? {
        val context = session?.context ?: return null
        // **`runCatching` catches `CancellationException`, and null here DISPATCHES THE MOVE.** The
        // controller rethrows it (`FileBrowserController.beginMove`) and its KDoc names this exact
        // trap -- and then this function, one module down, swallowed it before the controller could
        // ever see one, leaving the generation guard as the sole live protection and the documented
        // defence-in-depth half illusory. Gatekeeper finding 5.
        val repository = try {
            LibraryInjection.repository(context)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return null
        val set = try {
            repository.discSetFor(subjectPath)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return null
        return DiscSetWarning(
            title = set.title,
            discNumber = set.subjectDiscNumber,
            discCount = set.discPaths.size,
            subjectPath = subjectPath,
            setPaths = set.allPaths,
            hasPlaylist = set.hasPlaylist,
            wholeSetCanTravel = set.carryable,
        )
    }

    /**
     * Answer an [FileQueueState.AwaitingConflict] entry with the player's *Keep both / Replace / Skip*.
     *
     * Re-runs the same transfer — or, since D-1 , the same **extraction** — with
     * the chosen [FileConflictChoice.resolution]. The re-run is deliberate rather than resuming a parked
     * operation: nothing was written when the conflict was raised (see
     * [ManagedFileOperations.conflictsFor], and [ArchiveHelper.extract]'s own [ConflictResolution.ASK]
     * refusal for the extraction case), so there is no partial state to resume, and re-deciding from the
     * current filesystem is more honest than acting on a snapshot the player has been looking at for a
     * while.
     *
     * Checks [pendingTransfers] first, then [pendingExtractions] — an entry id belongs to exactly one of
     * the two maps, since [transfer] and [dispatchExtraction] each mint their own ids from the same
     * [nextQueueId] counter and neither ever writes the other's map.
     */
    fun resolveConflict(entryId: Long, choice: FileConflictChoice) {
        if (operations == null) return
        val entry = mutableQueue.value.firstOrNull { it.id == entryId } ?: return
        if (!entry.awaitingConflict) return
        val pendingTransfer = pendingTransfers.remove(entryId)
        if (pendingTransfer != null) {
            mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, entryId)
            transfer(
                pendingTransfer.operation,
                pendingTransfer.paths,
                pendingTransfer.destination,
                choice,
                carryCompanions = pendingTransfer.carryCompanions,
            )
            return
        }
        pendingRenames.remove(entryId)?.let { pending ->
            mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, entryId)
            // Skip leaves the existing file alone and there is nothing to report: the card just goes.
            if (choice != FileConflictChoice.SKIP) {
                rename(pending.path, pending.newName, choice, undoable = true, undoing = pending.undoing)
            }
            return
        }
        val pendingExtraction = pendingExtractions.remove(entryId) ?: return
        val current = session ?: return
        mutableQueue.value = FileOperationQueue.remove(mutableQueue.value, entryId)
        dispatchExtraction(
            archivePath = pendingExtraction.archivePath,
            destination = pendingExtraction.destination,
            session = current,
            resolution = choice.resolution,
        )
    }

    private class PendingTransfer(
        val operation: FileOperation,
        val paths: List<String>,
        val destination: String,
        val carryCompanions: Boolean = false,
    )

    /** Transfers parked on an unanswered conflict, so [resolveConflict] can re-run the right one. */
    private val pendingTransfers = mutableMapOf<Long, PendingTransfer>()

    private class PendingExtraction(
        val archivePath: String,
        val destination: String,
    )

    /**
     * Extractions parked on an unanswered conflict (D-1), so [resolveConflict] can re-dispatch the right
     * one. Filled only by [dispatchExtraction]'s MANAGE branch — PICK never enqueues a card in the first
     * place (see that function's KDoc), so this map stays empty for the whole life of a PICK session.
     */
    private val pendingExtractions = mutableMapOf<Long, PendingExtraction>()

    /**
     * @param noteOnSuccess one sentence appended to the card when — and only when — the transfer
     *   actually lands. Used by [undoMove] for the fact it alone knows: that the game had already left
     *   the library, so putting the file back does not put the shelf entry back. Applied in the success
     *   arm rather than at enqueue because until the bytes arrive it is a promise, not a fact.
     * @param picked what the player picked -- the card counts and names these. When [carryCompanions] is set the job
     *   moves them AND each disc's `.sbi` / `.sub` , which are looked up inside the job, off Main, and travel as
     *   followers of their disc: they land under the disc's final name or stay behind with a line on the card.
     */
    private fun transfer(
        operation: FileOperation,
        picked: List<String>,
        destination: String,
        choice: FileConflictChoice?,
        noteOnSuccess: String? = null,
        carryCompanions: Boolean = false,
    ) {
        val ops = operations ?: return
        if (picked.isEmpty()) return
        val id = nextQueueId++
        // §6.4 : "one string", past tense, for the card's
        // whole life -- not a tense that flips on completion. Matches the spec's own ruled text exactly
        // ("Moved from Downloads to PS2"), so this is no longer the present-tense verb the pre-slice-3
        // subtitle used.
        val verb = if (operation == FileOperation.MOVE) "Moved" else "Copied"
        val label = if (picked.size == 1) File(picked.single()).name else "${picked.size} files"
        val destFolder = File(destination).name
        // The origin is the whole point of §6.4: a completed transfer's only walk-back is knowing both
        // endpoints (spec §8.2 mitigation 2). `originFolder` answers only when every source path agrees
        // on a parent -- see [commonParentFolderName]'s own KDoc for why disagreement falls back to
        // naming the count instead of guessing.
        val originFolder = commonParentFolderName(picked)
        enqueue(
            FileQueueEntry(
                id = id,
                kind = operation.toJobKind(),
                title = label,
                subtitle = if (originFolder != null) {
                    "$verb from $originFolder to $destFolder"
                } else {
                    "$verb $label to $destFolder"
                },
                // F-6: null, not 0f -- at enqueue nothing is known yet about whether this transfer will
                // stream (a real fraction) or land in one accounted step (a rename, a small file, a
                // skip), and 0f would assert a measurement that has not started. See
                // FileQueueState.Running's own KDoc for the full reasoning.
                state = FileQueueState.Running(fraction = null),
            ),
        )
        // ASK is the default and the safe one: with no explicit player choice, a collision refuses.
        val resolution = choice?.resolution ?: ConflictResolution.ASK
        val job = scope.launch {
            // **R-22, create the folder on commit.** The destination list now
            // offers the recognised platform's folder before it exists, so a game Mimir has already
            // identified is never left with nowhere to go. This is the other half of that: the row said
            // the folder would be made, and this is where it is made.
            //
            // `mkdirs` and not a check-then-create: it is idempotent, it answers false for "already
            // there" without throwing, and every other destination reaching this line already exists, so
            // the common case costs one stat. A failure is not handled here on purpose -- the transfer
            // below fails honestly through `requireDestination` and surfaces as a Failed card, rather
            // than being pre-empted by a second error path saying the same thing in different words.
            runCatching { File(destination).mkdirs() }

            // What actually moves: the picked files plus, for a move, each disc's companions -- found here, off Main
            // (it stats the disc's name under every disc extension), never at the press.
            val companionSet = if (carryCompanions) {
                withContext(Dispatchers.IO) { DiscCompanionFiles.companionSet(picked) }
            } else {
                null
            }
            val paths = companionSet?.paths ?: picked
            // A companion is a FOLLOWER: an `.sbi` takes the name its disc landed under, a `.cue`'s track keeps the name
            // the sheet lists, and either stays behind when that name is taken (Keep both would otherwise call it
            // `Game (copy).sbi` beside a `Game.chd`, or `Game (copy).bin` where no sheet names it).
            val followers = companionSet?.let(::moveFollowersOf) ?: emptyMap()
            val leftBehind = mutableListOf<String>()
            val skippedSources = mutableListOf<String>()

            // F-6: a REAL fraction only when bytes were actually observed moving. `total > 0` guards a
            // division by zero, and it is the same fabrication class as the atomic/skip cases below --
            // an empty or not-yet-sized transfer has no honest fraction either.
            //
            // Both callbacks run on the engine's IO thread, so they post to Main rather than write the queue from
            // there  -- see [postProgress].
            val onProgress: (Long, Long, String) -> Unit = { done, total, _ ->
                postProgress(id, FileQueueState.Running(if (total > 0) done.toFloat() / total else null))
            }
            // The engine calls this instead of onProgress wherever the advance is bookkeeping rather than
            // an observation -- a skipped source, a same-folder rename, a small file copied in one call
            // (ManagedFileOperations.copy/move's own KDoc). Rendering it as a fraction would be exactly
            // the "doesn't probe live data" defect the regression report described; null keeps the card honestly
            // indeterminate instead.
            val onAccounted: (Long, Long, String) -> Unit = { _, _, _ ->
                postProgress(id, FileQueueState.Running(fraction = null))
            }
            // F-6(a): the engine's `count` is a HANDLED total that conflates transferred and skipped
            // (ManagedFileOperations.move/copy's own KDoc). This recovers the split so the announcement
            // below can name what actually moved, not what was merely decided.
            var skippedCount = 0
            val onSkipped: (String) -> Unit = {
                skippedCount++
                skippedSources += it
                if (it in followers) leftBehind += it
            }
            // Where each file ACTUALLY landed, from the engine that resolved it. Never inferred from
            // `destination + originalName` -- see ManagedFileOperations.move's own KDoc
            val landings = linkedMapOf<String, String>()
            val result = try {
                if (operation == FileOperation.MOVE) {
                    ops.move(
                        paths, destination, resolution, onProgress, onAccounted, onSkipped, followers,
                    ) { source, landed ->
                        landings[source] = landed
                    }
                } else {
                    ops.copy(
                        paths, destination, resolution, onProgress, onAccounted, onSkipped,
                    ) { source, landed ->
                        landings[source] = landed
                    }
                }
            } catch (_: CancellationException) {
                // Cancel (E2 policy 2026-10-08). The engine has already put the current item back or
                // finished it, so what is on disk is exactly `landings`. A move that landed something owes the
                // library the same news a finished one does.
                settleCancelledTransfer(id, operation, picked, paths, destination, landings, skippedSources)
                return@launch
            }
            result.fold(
                onSuccess = { count ->
                    // F-6(a): what actually moved, never what the engine merely finished deciding about.
                    val transferredCount = count - skippedCount
                    // A move that can be walked back settles as `ReverseAvailable` rather than `Done`, so
                    // the card carries its own Undo -- policy 2026-08-16. `reversibleMove` answers
                    // null for everything it cannot honestly reverse (a copy, a Skip, sources that came
                    // from different folders, a file renamed on arrival), and null means the old
                    // behaviour exactly: a plain `Done` card.
                    val reverse = if (operation == FileOperation.MOVE && resolution != ConflictResolution.SKIP) {
                        reversibleMove(paths - leftBehind.toSet(), destination, landings)
                    } else {
                        null
                    }
                    // **A completed progress bar must not claim a move when nothing transferred.**
                    //
                    // `Done` used to be unconditional here, and the announcement twenty lines below was
                    // not -- it already guards on `transferredCount > 0`, with a comment explaining at
                    // length that the engine's `count` conflates transferred and skipped and that
                    // announcing off it could claim a file moved while it still sits in Downloads.
                    //
                    // That reasoning was applied to the WORDS and never to the STATE. F-6 stopped the
                    // card fabricating a fraction and F-6(a) stopped it fabricating a sentence; the
                    // terminal state went on reporting success for an operation that moved nothing, so
                    // the card settled at 100% over an untouched filesystem. Both now read the same
                    // number, because there is only one honest answer to "did anything happen".
                    //
                    // Skipped, not Failed: nothing went wrong. The operation was handled and moved
                    // nothing, and those are different outcomes with different words.
                    val terminal = when {
                        transferredCount == 0 -> FileQueueState.Skipped
                        reverse != null -> FileQueueState.ReverseAvailable(reverse)
                        else -> FileQueueState.Done
                    }
                    setState(id, terminal)
                    noteOnSuccess?.let { noteOnCard(id, it) }
                    // Under Skip the player chose to leave the clashing file, and a follower stays with the file it
                    // follows: there is nothing to explain, so [leftBehindNotes] says nothing.
                    leftBehindNotes(resolution, leftBehind, companionSet?.followers.orEmpty()).forEach { noteOnCard(id, it) }
                    mutableController.value?.refresh()
                    // §6.2: the completed transfer gets a voice, on extract's own model -- see
                    // Session.onMoved/onCopied's own KDoc for the shape and why `label` stands in for
                    // `archivePath`. Fired here, not inside moveInManager/copyInManager, because this is
                    // the SUCCESS arm -- the same timing rule onDeleted's own firing point already
                    // established (announce when the effect is real, never at the press).
                    // ...but NOT when nothing actually transferred. F-6(a) closes what used to be OWED
                    // here: `count` is the engine's HANDLED count, not its TRANSFERRED count --
                    // ManagedFileOperations' `target == null` arm increments it and `continue`s without
                    // writing a byte (`ManagedFileOperations.kt`'s copy/move, `resolveTransferTarget`
                    // returning null under SKIP) -- so announcing off `count` could say "Moved Foo.iso to
                    // PS2" about a file still sitting in Downloads. `transferredCount` is the real number,
                    // recovered from the engine's new `onSkipped` callback, so the guard now reads the
                    // actual outcome instead of inferring it from the player's conflict CHOICE -- which
                    // also makes it correct for a future multi-path list where some sources transfer and
                    // others are skipped in the same dispatch, not just today's single-path case.
                    //
                    // Known limitation: extract's Toast has the identical defect ("Extracted 3 items" after skipping
                    // all three); its count comes from ArchiveHelper.kt.
                    if (transferredCount > 0) {
                        if (operation == FileOperation.MOVE) {
                            tellLibraryTheFilesMoved(id, paths - leftBehind.toSet(), destination, landings)
                            session?.onMoved?.invoke(label, paths, destination, transferredCount)
                        } else {
                            session?.onCopied?.invoke(label, paths, destination, transferredCount)
                        }
                    }
                },
                onFailure = { error ->
                    if (error is FileOperationConflictException) {
                        // Nothing was written -- ManagedFileOperations refuses BEFORE touching the
                        // filesystem when the resolution is ASK, so parking here is safe and the
                        // player's answer decides what actually happens.
                        pendingTransfers[id] = PendingTransfer(operation, picked, destination, carryCompanions)
                        mutableQueue.value = FileOperationQueue.withState(
                            mutableQueue.value,
                            id,
                            FileQueueState.AwaitingConflict,
                        ).map { entry ->
                            if (entry.id != id) {
                                entry
                            } else {
                                entry.copy(
                                    subtitle = "A file named ${error.conflicts.first().name} is already there.",
                                )
                            }
                        }
                    } else {
                        setState(id, FileQueueState.Failed(error.playerFacingReason()))
                    }
                },
            )
        }
        jobControl.track(id, job) { settleAbandonedCard(id) }
    }

    /**
     * The parent folder name every path in [paths] agrees on, or null when they do not all agree —
     * **§6.4's origin naming, decidable with no filesystem** (the same testability shape
     * [fileDeleteSubtitle] below already uses: `File(String).parentFile?.name` is pure string surgery,
     * nothing is `stat`ed).
     *
     * `paths` is always a singleton in production today — [moveInManager]/[copyInManager] take a list
     * only because the engine has always been multi-path and selection mode (G3) is not yet built (see
     * their own KDoc) — so this is unreachable-by-more-than-one-element code for now. It stays list-safe
     * anyway: [transfer] has always taken the whole list, and answering null on disagreement rather than
     * guessing from `paths.first()` is [fileDeleteSubtitle]'s own rule applied here — an unclaimable fact
     * gets no claim, not a possibly-wrong one.
     *
     * `internal` rather than `private`, matching [CHECKSUM_ALGORITHM]'s own reasoning for the same
     * choice: so the rule itself is directly pinnable by a plain JUnit test with real value cases
     * (single path, agreeing paths, disagreeing paths) the way [fileDeleteSubtitle] already is in
     * `FileBrowserStoreQueueHonestyTest.kt` — **OWED, not done here**: that file is outside this lane's
     * ownership, so only the WIRING fact
     * (this is actually called, and its result actually branches the subtitle) is pinned in
     * `FileBrowserQueueHostWiringTest.kt`, not the value-level policy.
     */
    internal fun commonParentFolderName(paths: List<String>): String? {
        // Agreement is decided on the parent's full PATH, not its name: `/sd/Downloads/a.iso` and
        // `/emulated/0/Downloads/b.iso` are two different folders that happen to share a leaf name, and
        // "from Downloads" would name a single origin that never existed. The NAME is still what gets
        // returned, because that is what the card shows -- the full path is only how agreement is judged.
        val firstParent = File(paths.firstOrNull() ?: return null).parentFile ?: return null
        val allAgree = paths.all { File(it).parentFile?.path == firstParent.path }
        return if (allAgree) firstParent.name.takeUnless { it.isEmpty() } else null
    }

    /**
     * The two choke points every filesystem change in this store passes through — which is exactly why
     * [evictListings] is called from here rather than from each of the nine callers.
     *
     * Every stage, completion, conflict resolution and failure lands on one of these two functions, so a
     * cached listing cannot outlive a change to the files it describes. An enqueue evicts too: staging
     * moves the payload under `.mimir-staging` the moment the operation starts, so the directory is
     * already different by the time the card says "working".
     */
    private fun enqueue(entry: FileQueueEntry) {
        evictListings()
        mutableQueue.value = FileOperationQueue.enqueue(mutableQueue.value, entry)
    }

    /**
     * A progress report from the engine's own thread. The queue is written on Main -- [setState] reads the list,
     * replaces one entry and stores it back, and two threads doing that lose one of the updates -- so the
     * report is posted there. And it is written only while the card is still running: a report that arrives
     * after the card settled (Done, Failed, Cancelled) is stale and must not overwrite the word the player is
     * reading.
     */
    private fun postProgress(id: Long, state: FileQueueState.Running) {
        scope.launch {
            if (mutableQueue.value.firstOrNull { it.id == id }?.state is FileQueueState.Running) setState(id, state)
        }
    }

    private fun setState(id: Long, state: FileQueueState) {
        evictListings()
        mutableQueue.value = FileOperationQueue.withState(mutableQueue.value, id, jobControl.keepCancelling(id, state))
    }

    /**
     *  Tell the library where its file
     * went, so the row follows instead of naming a path with nothing at it.
     *
     * ## Only for files that landed under their own name
     *
     * `ManagedFileOperations.move` answers a COUNT, not the paths it wrote, and under *Keep both* it
     * renames the destination (`ConflictNamePolicy.fileName`, and then `resolveConflict` again if even
     * that name is taken) — so the final path is not always derivable here. Rather than guess it, this
     * asks the filesystem the only question that matters: **is the file at `destination/<its own
     * name>`?** If it is, that is where it went. If it is not, it was renamed on arrival — and a rename
     * is identity-affecting anyway (`title:` gameIds are filename-derived), so the honest answer is to
     * leave it to the next scan rather than relocate a row onto a path that may not be the file.
     *
     * ## Fired, never awaited
     *
     * A Room rewrite must not hold the queue's completion. The library is one row behind for the length
     * of a coroutine hop, and a crash inside that window leaves the pre-move row — which the next scan
     * corrects, and which is the direction to be wrong in.
     *
     * **MOVE only.** A copy leaves the original where the library indexed it and creates a file the
     * scanner will pick up on its own terms; nothing about the existing row became false.
     */
    private fun tellLibraryTheFilesMoved(
        id: Long,
        paths: List<String>,
        destination: String,
        landings: Map<String, String>,
    ) {
        // Every arm below that used to `return` without a word
        // is now a sentence on the card. The failure they all shared: the player watches a card say
        // "Moved Zelda.gba to ps2", the library is never told, and the game disappears at the next scan
        // with nothing anywhere connecting the two events. Being one row behind is survivable -- the next
        // scan corrects it -- but being one row behind SILENTLY is what makes it look like data loss.
        val context = session?.context
        if (context == null) {
            noteLibraryUnreachable(id)
            return
        }
        scope.launch {
            val repository = runCatching { LibraryInjection.repository(context) }.getOrNull()
            if (repository == null) {
                noteLibraryUnreachable(id)
                return@launch
            }
            paths.forEach { from ->
                // **The landing comes from the engine, never from `destination + originalName`.** The
                // predicate this replaces was `!File(destination, name).exists()`, which is FALSE in
                // precisely the case it was written for: a *Keep both* rename leaves the INCUMBENT on
                // that path, which is the rename's own cause. So the arm never fired, and `relocate`
                // was handed the incumbent's path -- a delete-then-upsert on the incumbent's own
                // entryKey, rewriting that row with the moved game's identity while the file that
                // actually moved went unindexed. Two silent wrong outcomes, in the commit ordered to
                // close silent wrong outcomes. Gatekeeper finding 8.
                val reported = landings[from]
                val landed = File(destination, File(from).name)
                if (reported == null || !File(reported).namesSameFileAs(landed) || !landed.exists()) {
                    // It did not arrive under its own name. A renamed file has a different identity
                    // (`title:` ids are filename-derived), so the row is stale until the next scan
                    // rewrites it -- and relocating to the renamed path would WRITE that wrong identity
                    // rather than wait for the scan to work it out. Following the rename honestly is
                    // owed, and is not this fix.
                    noteRenamedOnArrival(id)
                    return@forEach
                }
                val outcome = runCatching { repository.relocate(from, landed.path) }
                // **A game that left the library must SAY so.** The relocation decides silently and
                // correctly; a player who watches a card say "Moved Zelda.gba to ps2" and then cannot
                // find the game anywhere has been told the opposite of what happened. The card is where
                // this store already corrects itself (the conflict subtitle uses the same seam), so the
                // outcome lands there rather than in a second surface that has to be discovered.
                when (val result = outcome.getOrNull()) {
                    is LibraryRelocation.Departed -> noteDeparture(id, result.reason)
                    // Silence is the correct answer here and only here. `NotInLibrary` is the ORDINARY
                    // case -- screenshots, saves, BIOS images, archives -- and `Relocated` is the feature
                    // working. A card that announced either would be chatter on every move, which is how
                    // a player learns to stop reading the one that matters.
                    is LibraryRelocation.NotInLibrary, is LibraryRelocation.Relocated -> Unit
                    // `relocate` threw. Same honest statement as an unreachable repository: the row is
                    // stale, the next scan fixes it, and the player hears about it now rather than
                    // finding a gap on the shelf later.
                    null -> noteLibraryUnreachable(id)
                }
            }
        }
    }

    /**
     * Whether two paths name the same file, compared the way the ENGINE names them.
     *
     * `ManagedFileOperations.requireDestination` canonicalises its destination
     * (`ManagedFileOperations.kt:411`), so every landing it reports is a real path — while the
     * `destination` this store was handed is whatever the browser was showing. A string comparison
     * between the two is wrong wherever the browse path reached the folder through a symlink, and on
     * Android that is the ordinary case: `/sdcard` is a link to `/storage/emulated/0`. It would have
     * refused every landing there, which reads as "Undo just stopped appearing" and nothing else.
     *
     * Found by `ManagedFileOperationsLandingReportTest` on the first run, where macOS' own temp root
     * (`/var` → `/private/var`) is the same shape. Falls back to the raw comparison rather than
     * throwing: an uncanonicalisable path is one this store should refuse anyway, and the caller's next
     * step is a refusal either way.
     */
    private fun File.namesSameFileAs(other: File): Boolean =
        runCatching { canonicalPath == other.canonicalPath }.getOrDefault(path == other.path)

    /**
     * The handle that lets a finished move be sent back — [FileQueueState.ReverseAvailable]'s content.
     *
     * **Null on every uncertainty, and each null is a case where "put it back" could not be honoured:**
     *
     * - **Sources from different folders.** There is no single "back", and picking one would return some
     *   files to a place they were never in. `commonParentFolderName` already refuses the same way for
     *   the card's own subtitle; this refuses harder, because that one only mis-names a card and this one
     *   would move a player's files somewhere they have never been.
     * - **A file the engine did not report landing under its own name.** Either it was renamed on
     *   arrival (*Keep both*), or the resolution skipped it; either way the handle would name a path
     *   that is not this move's file, and Undo would move the wrong thing or nothing.
     *
     * ## Why the landing comes from the ENGINE and not from `exists()`
     *
     * This function used to build `destination/<name>` and accept it if it existed. Under *Keep both*
     * that check passes **through the incumbent**: `ManagedFileOperations` renames the ARRIVING file and
     * leaves the file that was already there on the assumed path. The handle then named the player's own
     * pre-existing file, and Undo moved it out of its folder while the file the player actually moved
     * stayed behind under a suffixed name. [ReversibleMove]'s own KDoc had already forbidden exactly this
     * construction -- *"never by assuming `destination + originalName`"* -- and the implementation was
     * the assumed name anyway. Found by the gatekeeper's source review of the transfer contract.
     *
     * So [landings] is the engine's own report, and this refuses on anything else. Reversing a renamed
     * arrival is *possible* -- the report names it -- and is deliberately NOT done here: the reverse
     * would have to restore the original name too, which is a second decision (the name may now be
     * taken back at the origin) and belongs to its own ruling.
     *
     * **`exists()` is still evaluated HERE, at settle time.** It is what makes an Undo pressed against a
     * stale card self-heal: the reverse runs as an ordinary transfer, and an ordinary transfer of a file
     * that is no longer there fails visibly instead of corrupting anything.
     */
    private fun reversibleMove(
        paths: List<String>,
        destination: String,
        landings: Map<String, String>,
    ): ReversibleMove? {
        val origin = File(paths.firstOrNull() ?: return null).parent ?: return null
        if (paths.any { File(it).parent != origin }) return null
        if (origin == destination.trimEnd('/')) return null
        val landed = paths.map { path ->
            val reported = File(landings[path] ?: return null)
            if (!reported.namesSameFileAs(File(destination, File(path).name))) return null
            reported
        }
        if (landed.any { !it.exists() }) return null
        return ReversibleMove(landedPaths = landed.map { it.path }, originFolder = origin)
    }

    /**
     * Append the library's own answer to a completed move's card.
     *
     * **Copy is deliberately plain and deliberately not a warning.** Nothing went wrong: the player moved
     * a file and the library did what a scan would do about it. What they need is the fact, in the place
     * they are already looking, so the absence from the shelf a moment later is explained rather than
     * discovered. **Final wording remains a design decision**; this states the mechanism.
     */
    private fun noteDeparture(id: Long, reason: LibraryDepartureReason) {
        noteOnCard(
            id,
            when (reason) {
                LibraryDepartureReason.OUTSIDE_SOURCES ->
                    "That folder isn't one of your game sources, so it left your library."
                LibraryDepartureReason.IDENTITY_CHANGED ->
                    "Your library doesn't list it as that platform's game any more."
            },
        )
        // **Mark the Undo handle too, not just the card.** A departed game's row is deleted, so moving the
        // file back does NOT bring the shelf entry back -- the reverse finds nothing to relocate. Undo is
        // still worth offering (the file goes home, and the next scan re-adds the game), but it must not
        // imply a restoration it cannot perform, and the only place that can be said is the card the
        // reverse produces. Carried as state so the wording can change without changing the behaviour.
        mutableQueue.value = mutableQueue.value.map { entry ->
            val state = entry.state
            if (entry.id != id || state !is FileQueueState.ReverseAvailable) {
                entry
            } else {
                entry.copy(state = state.copy(reverse = state.reverse.copy(departedFromLibrary = true)))
            }
        }
    }

    /**
     * The library could not be asked at all — no context, no repository, or [LibraryRepository.relocate]
     * threw.
     *
     * **Says what is true and what happens next, and does not apologise.** Nothing was lost: the file is
     * where the player put it and the row still points at the old path, which the next scan of that root
     * corrects on its own. What the player must not be left with is a game quietly missing from the shelf
     * and no idea why.
     */
    private fun noteLibraryUnreachable(id: Long) =
        noteOnCard(id, "Mimir couldn't update your library — it'll catch up next time it scans.")

    /**
     * The file arrived under a different name, so the row was left alone.
     *
     * Only reachable through *Keep both*: the player answered a collision, `ConflictNamePolicy` renamed
     * the arriving file, and a `title:` identity is filename-derived — so this is a different entry at a
     * different path, not the same game somewhere else, and inventing a relocation for it would put a row
     * in the index the next scan contradicts.
     */
    private fun noteRenamedOnArrival(id: Long) =
        noteOnCard(id, "It was saved under a new name, so your library will re-add it on the next scan.")

    /**
     * Append one sentence to a card's subtitle, once.
     *
     * **Idempotent by content**, because a multi-file transfer calls this once per path and three
     * identical "couldn't update your library" clauses on one card is noise the player has to parse to
     * find the one fact. Appending rather than replacing is deliberate: the card's first half ("Moved
     * Track 2.bin from … to dreamcast") is still true, and a note that erased it would take away the only
     * record of what the operation was.
     */
    private fun noteOnCard(id: Long, note: String) {
        mutableQueue.value = withNote(mutableQueue.value, id, note)
    }

    /** What a failed card says (E2 O3): the engine's own reason, in a sentence. See [FileFailureReasons]. */
    private fun Throwable.playerFacingReason(): String = FileFailureReasons.describe(this)

    // ---- F-06: extract destinations ----

    /**
     * The Extract action's two options for [archivePath] (F-06).
     *
     * Available in BOTH modes: extraction is not destructive and PICK has always offered it. Only the
     * *destination choice* is new.
     *
     * The platform folder comes from the real library, resolved by [FileBrowserRomFolders] — and is
     * absent whenever that resolution is not unambiguous, per the policy that a wrong folder is
     * worse than none. See [ExtractDestinationPolicy.suggestedPlatformFolder] for the four ways it
     * declines.
     */
    fun extractOptions(archivePath: String): ExtractDestinationOptions {
        val current = session
        val platformId = current?.classifyPlatform?.invoke(
            FileItem(name = File(archivePath).name, path = archivePath),
        )?.platformIdValue
        val volumes = mutableVolumes.value
        return ExtractDestinationPolicy.options(
            archivePath = archivePath,
            platformId = platformId,
            platformFolders = FileBrowserRomFolders.foldersFor(platformId),
            volumeRootOf = { path -> StorageVolumeListing.volumeRootFor(path, volumes) },
        )
    }

    /**
     * Every platform folder [subjectPath] could be placed into, for explicit player selection.
     *
     * The one seam both choosers use. Extract asks it for "where do the contents go", Move and Copy ask
     * it for "where does this file go", and they get the same list in the same order for the same reason
     * the design standing gives for everything else: *every surface must speak the same language*.
     *
     * **Recognition is still fail-closed and still only preselects.** `classifyPlatform` answers null for
     * a zipped PS2 game (PS2's content contract does not list `zip`) and for anything sitting outside a
     * platform-named folder. Null no
     * longer means an empty screen: it means nothing is preselected.
     */
    fun platformDestinations(subjectPath: String): List<PlatformDestination> {
        val current = session
        val platformId = current?.classifyPlatform?.invoke(
            FileItem(name = File(subjectPath).name, path = subjectPath),
        )?.platformIdValue
        val volumes = mutableVolumes.value
        return PlatformDestinationPolicy.destinations(
            subjectPath = subjectPath,
            recognisedPlatformId = platformId,
            libraryFolders = FileBrowserRomFolders.libraryFolders(),
            discovered = FileBrowserRomFolders.discoveredFolders(),
            volumeRootOf = { path -> StorageVolumeListing.volumeRootFor(path, volumes) },
            // **R-22, policy 2026-08-18.** The folder the recognised platform SHOULD have, so a game
            // Mimir has already badged and named a runtime for is never left with nowhere to go. Null
            // whenever any of the three facts is missing -- nothing recognised, no agreed ROM root, or a
            // platform the registry has no folder name for -- because each of those is a guess, and a
            // proposal landing on the wrong drive is worse than no proposal: he would move a game
            // somewhere he never looks.
            proposedForRecognised = platformId?.let { id ->
                val root = FileBrowserRomFolders.proposalRoot() ?: return@let null
                val folder = ExtensionPlatformClassifier().folderNameFor(id) ?: return@let null
                PlatformFolderCandidate(platformId = id, path = "$root/$folder")
            },
        )
    }

    /** Extract [archivePath] into an explicitly chosen [destination] — F-06's "Extract in…". */
    fun extractTo(archivePath: String, destination: String) {
        val current = session ?: return
        dispatchExtraction(archivePath, destination, current)
    }

    /**
     * Run [archivePath]'s extraction into [destination], parking on a collision **only in MANAGE**.
     *
     * Extraction uses the same conflict card as other file operations. It mirrors
     * [transfer]'s own shape instead of inventing a second one — enqueue a [FileQueueState.Running] card,
     * launch on [scope], park as [FileQueueState.AwaitingConflict] on a [FileOperationConflictException],
     * answer through [resolveConflict] exactly like a move or copy. The queue entry is tagged
     * [FileOperation.COPY], never `FileOperation.EXTRACT` — the tag is bookkeeping only (nothing in
     * `feature-files`' `FileBrowserQueue.kt` renders off `.operation`; every card is keyed off `.state`),
     * and COPY is the closer true shape: extraction writes new files and deletes no source.
     *
     * **The card is MANAGE-only, gated on the same `operations != null` test every destructive entry
     * point already uses** — not a narrower reading of "enqueue one", but the only reading the rest of
     * this file leaves open: an [FileQueueState.AwaitingConflict] card can never be dismissed
     * ([fileQueueDismissable] refuses it), [resolveConflict] refuses to answer one while `operations` is
     * null (pinned by `FileBrowserStorePickSafetyTest`), and the Queue screen does not exist in PICK at
     * all ([com.gruning.mimir.rewrite.feature.files.fileBrowserQueueAvailable] is MANAGE-only). Enqueueing
     * unconditionally would strand a permanently-stuck, invisible, unanswerable card in a PICK session.
     * Extraction ITSELF stays available in both modes, unchanged — only its participation in the queue is
     * new, and only in MANAGE.
     *
     * **A PICK collision instead falls straight to [Session.onExtractFailed]** — [runExtraction]'s
     * `onConflict` default, which this function's `id == null` branch relies on rather than overriding.
     * That is PICK's *pre-existing* behaviour (`ArchiveHelper.extract` already defaulted to
     * [ConflictResolution.ASK] and any failure already reached `onExtractFailed`); this lane pins it
     * rather than changing it.
     *
     * [resolution] is [ConflictResolution.ASK] for a first attempt and the player's real answer for
     * [resolveConflict]'s re-dispatch, the same parameter [ArchiveHelper.extract] itself takes.
     */
    private fun dispatchExtraction(
        archivePath: String,
        destination: String,
        session: Session,
        resolution: ConflictResolution = ConflictResolution.ASK,
    ) {
        val id = if (operations != null) nextQueueId++ else null
        if (id != null) {
            enqueue(
                FileQueueEntry(
                    id = id,
                    kind = JobKind.COPY,
                    title = File(archivePath).name,
                    subtitle = "Extract → ${File(destination).name}",
                    // Enqueued with no fraction: at this point nothing is known about how much there
                    // is to do, and `0f` claimed a measurement of zero rather than the absence of one.
                    state = FileQueueState.Running(null),
                ),
            )
        }
        val job = scope.launch {
            if (id == null) {
                // PICK: no card exists to update, and no override is passed -- runExtraction's own
                // onConflict default routes a collision straight into onExtractFailed, exactly as any
                // other extraction failure already does.
                runExtraction(
                    archivePath = archivePath,
                    destination = destination,
                    extract = { path, dest ->
                        archiveHelper.extract(
                            path,
                            dest,
                            conflictResolution = resolution,
                        )
                    },
                    currentController = { mutableController.value },
                    onExtracted = session.onExtracted,
                    onExtractFailed = session.onExtractFailed,
                )
            } else {
                val onProgress: (Long, Long, String) -> Unit = { done, total, _ ->
                    // `null`, not `0f`, when the engine has no total to divide by. F-6 made the
                    // difference expressible and F-7 found the same false zero in delete; this is the
                    // third and last one in this store. Zero is a MEASUREMENT of no progress; absence is
                    // the honest answer when there is nothing to measure against, and the queue surface
                    // already draws no bar for it.
                    postProgress(id, extractUnpackingState(done, total))
                }
                // The second half of the job, in counts: see [extractPlacingState].
                var lastPlaced = -1
                var lastPlaceTotal = -1
                var cancelOutcome: ExtractionCancelOutcome? = null
                val onPlacing: (Int, Int) -> Unit = { placed, total ->
                    lastPlaced = placed
                    lastPlaceTotal = total
                    postProgress(id, extractPlacingState(placed, total))
                }
                try {
                    runExtraction(
                        archivePath = archivePath,
                        destination = destination,
                        extract = { path, dest ->
                            archiveHelper.extract(
                                path,
                                dest,
                                conflictResolution = resolution,
                                onProgress = onProgress,
                                onPlacing = onPlacing,
                                onCancelled = { cancelOutcome = it },
                            )
                        },
                        currentController = { mutableController.value },
                        onExtracted = { path, dest, count ->
                            setState(id, FileQueueState.Done)
                            session.onExtracted(path, dest, count)
                        },
                        onExtractFailed = { path, error ->
                            setState(id, FileQueueState.Failed(error.playerFacingReason()))
                            session.onExtractFailed(path, error)
                        },
                        onConflict = { error ->
                            // Nothing was written -- ArchiveHelper.extract refuses BEFORE touching the
                            // filesystem when the resolution is ASK (see its own KDoc), so parking here is
                            // safe and the player's answer decides what actually happens. Same guarantee
                            // transfer()'s own park relies on, same player-facing sentence.
                            pendingExtractions[id] = PendingExtraction(archivePath, destination)
                            mutableQueue.value = FileOperationQueue.withState(
                                mutableQueue.value,
                                id,
                                FileQueueState.AwaitingConflict,
                            ).map { entry ->
                                if (entry.id != id) {
                                    entry
                                } else {
                                    entry.copy(
                                        subtitle = "A file named ${error.conflicts.first().name} is already there.",
                                    )
                                }
                            }
                        },
                    )
                } catch (_: CancellationException) {
                    // Cancel. The engine has taken back what this job placed before it threw, so the state is
                    // settled on the disk by the time it is written on the card.
                    setState(
                        id,
                        cancelledExtractionState(
                            cancelOutcome,
                            allPlaced = lastPlaceTotal > 0 && lastPlaced >= lastPlaceTotal,
                        ),
                    )
                    mutableController.value?.refresh()
                    // A stop that landed after the last file was placed took nothing back: the files are there, so the
                    // library hears about them like any other extraction (its rescan runs from this callback).
                    if (cancelOutcome == null && lastPlaceTotal > 0 && lastPlaced >= lastPlaceTotal) {
                        session.onExtracted(archivePath, destination, lastPlaceTotal)
                    }
                    return@launch
                }
                // This job's own start-up sweep may have read an EARLIER, abandoned extraction in the
                // same folder (E2 P2): whatever it reported becomes a card now, not at some later visit.
                enqueueInterruptedExtractions(withContext(Dispatchers.IO) { InterruptedExtractions.drain() })
            }
        }
        if (id != null) jobControl.track(id, job) { settleAbandonedCard(id) }
    }

    // ---- F-08: search ----

    /**
     * Search names on the volume the player is currently in (F-08).
     *
     * **Scoped, never global.** The root is the volume containing [FileBrowserController.currentPath],
     * falling back to the browser's own root — never `/`, and never a device-wide sweep. That scoping is
     * the design's own player-facing promise and half of the cost bound; see `FileSearchPolicy.kt`.
     *
     * A cold `Flow`: nothing is walked until it is collected, and a collector that stops cancels the
     * walk. Available in both modes — search reads, it does not write.
     */
    fun search(query: String, filter: FileSearchFilter = FileSearchFilter.ALL): Flow<FileItem> {
        val current = session ?: return emptyFlow()
        val controller = mutableController.value ?: return emptyFlow()
        // Falls back to the directory the player is standing in, NEVER upward. `volumeRootFor` answers
        // null in exactly two situations -- the player is at the drive chooser (`/storage`, which has no
        // files to search), or they are somewhere no enumerated volume claims -- and in both, narrowing
        // to the current directory keeps the scope promise the design makes in player-facing copy. The
        // browser's own mount root would be the other candidate, but `FileBrowserController.rootPath` is
        // `internal` to `feature-files` and this lane may not widen it; it is on the contract list.
        val root = StorageVolumeListing.volumeRootFor(controller.currentPath, mutableVolumes.value)
            ?: controller.currentPath
        val classify = current.classifyPlatform
        // R6-P2-07 review finding 1, 2026-09-15: folded and split ONCE per search, not once per hit
        // below -- `fileSearchMatches` used to re-fold `query` for every item this filter saw, on top
        // of `LocalFileRepository.search` already having done so once per candidate on its own raw
        // walk. `fileSearchMatchesTokens` takes the pre-folded list instead.
        val queryTokens = fileSearchQueryTokens(query)
        // Filtering previously lagged because this `.filter`
        // sits AFTER `LocalFileRepository.search`'s own `.flowOn(Dispatchers.IO)`
        // (`core-files/.../LocalFileRepository.kt:209`), and `flowOn` only moves the dispatcher for
        // operators UPSTREAM of it -- everything downstream, this filter included, ran on whatever
        // context the collector uses, which is `FileBrowserSearchCollector`'s `LaunchedEffect`, i.e.
        // Main. Every candidate's `fileSearchMatchesTokens` -- and, under the Games/Archives/BIOS
        // chips, `classify`'s real platform classification -- was therefore parking the main thread
        // once per file on the walked volume. A second `flowOn(Dispatchers.IO)` below this operator
        // pulls it (and `classify`) back onto IO, matching `LocalFileRepository.search`'s own
        // dispatcher rather than fighting it. `classify` (`FileBrowserPlatformClassifier.forBrowser`)
        // is registry-backed extension/name lookup with its own `@Synchronized` cache
        // (`ParentFolderHintCache`) -- no Android UI-thread requirement -- so moving it off Main
        // changes only which thread pays for it. BUILT / DEVICE-UNCERTIFIED: this pulls the work off
        // Main by construction, but the actual lag reduction is not yet measured on Thor.
        return repository.search(root, query)
            .filter { item ->
                fileSearchMatchesTokens(
                    item = item,
                    queryTokens = queryTokens,
                    filter = filter,
                    platformOf = { item -> classify(item).platformIdValue },
                    isBios = FileBrowserRomFolders::isBiosPayload,
                )
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Re-root the open browser onto [volumePath] -- an explicit drive switch, for a surface that offers
     * one directly instead of sending the player back to the chooser.
     *
     * **Deliberately does not close the outgoing controller here.** `FileBrowserSurface`'s
     * `DisposableEffect(controller)` owns that lifecycle (see this object's KDoc). When the swapped
     * controller reaches composition, Compose forgets the old effect (`close()` -- unregisters every
     * virtual slot, pops the scope) before remembering the new one (`open()` -- pushes it), so the
     * shared `FocusScope` ends at depth 1 either way. Closing it here too would be harmless
     * (`FileBrowserController.close` is idempotent) but would split ownership of a lifecycle this file
     * insists has exactly one owner. Assumes the surface is composed, which is true of any caller that
     * IS the surface. No-op when the browser is not open.
     */
    fun switchVolume(volumePath: String) {
        val current = session ?: return
        if (mutableController.value == null) return
        mount(current, volumePath)
    }

    private fun mount(session: Session, rootPath: String) {
        mutableController.value = FileBrowserController(
            authority = session.authority,
            rootPath = rootPath,
            mode = session.mode,
            listDirectory = { path -> listDirectory(session.context, path) },
            onChoose = { chosen ->
                clearSession()
                session.onChoose(chosen)
            },
            onDismiss = { clearSession() },
            onExtract = { archivePath -> extract(archivePath, session) },
            onVerifyChecksum = { path -> verifyChecksum(path, session) },
            // F-01: SELECT deletes the focused tile. A constructor parameter rather than a setter because
            // `FileBrowserController.deleteAvailable` is `mode == MANAGE && onDelete != null` -- the sink's
            // presence IS the capability, so it has to be settled before the first frame rather than
            // arriving later and un-deadening a binding the footer had already declined to advertise.
            //
            // Passed unconditionally and gated downstream, twice over: the MANAGE check above, and
            // [deleteInManager]'s own `operations ?: return` (null in PICK, where nothing can enqueue).
            // The controller hands one path; [deleteInManager] takes a list because the engine has always
            // been multi-path -- selection mode is unbuilt, so today that list is always a single file.
            onDelete = { path -> deleteInManager(listOf(path)) },
            // F-06: extract gets its destinations. Both sinks or neither -- `extractChoiceAvailable` is
            // `resolveExtractOptions != null && onExtractTo != null`, so a resolver alone would draw two
            // rows, take focus, advertise a verb and do nothing on A.
            //
            // These were deliberately held back for one round while the chooser SCREEN was being built:
            // opening a chooser nothing renders pushes a focus scope behind an invisible modal, over a
            // browse listing that still looks live. `FileBrowserExtractSurface` now exists, so the door
            // is real and the sinks can land.
            //
            // Extraction is NOT destructive -- it writes new files and removes none -- so unlike the
            // delete sink this is wired in BOTH modes. PICK's incapability is about destroying the
            // player's files, not about refusing to unpack one.
            resolveExtractOptions = ::extractOptions,
            onExtractTo = { archivePath, destination -> extractTo(archivePath, destination) },
            // Move/Copy slice 1 (QA gap G2): both sinks or neither. A sink with no partner draws rows,
            // takes focus, advertises a verb and does nothing.
            //
            // `transferChoiceAvailable` is `mode == MANAGE && onMoveTo != null && onCopyTo != null` --
            // deliberately NOT the shape `extractChoiceAvailable` uses, and the difference is the whole
            // reason the gate exists. Extraction is un-gated because it writes new files and removes
            // none. MOVE REMOVES ITS SOURCE, so it belongs with `deleteAvailable`'s destructive class,
            // which is mode-gated for exactly that reason. The extraction-sink pattern does not apply here;
            // `extractChoiceAvailable` has a different non-destructive contract.
            //
            // Passed unconditionally, not gated by a `session.mode == MANAGE` check here: [moveInManager]
            // and [copyInManager] already open with `operations ?: return`, which is null in PICK, so the
            // picker stays exactly as incapable of a transfer as it is of a delete -- without a second
            // place that has to remember to say so.
            //
            // The controller hands one path; [moveInManager]/[copyInManager] take a list for the same
            // reason [onDelete] does -- the engine has always been multi-path, selection mode is unbuilt,
            // so today that list is always a single file.
            onMoveTo = { path, destination -> moveInManager(listOf(path), destination) },
            onCopyTo = { path, destination -> copyInManager(listOf(path), destination) },
            // Rename and Cancel : MANAGE-only through `operations ?: return`, exactly as the
            // transfers above -- the picker cannot reach either.
            onRename = { path, newName -> renameInManager(path, newName) },
            onCancelEntry = { entryId -> cancel(entryId) },
            // Warn before moving a disc set and offer to move all discs. The set arrives as a
            // list and goes through the SAME engine call as a single move -- [moveInManager] has always
            // taken a list, so a set is not a second kind of transfer with a second set of bugs, it is one
            // transfer of several files with one card, one conflict discipline and one library follow-up.
            onMoveSetTo = { paths, destination -> moveInManager(paths, destination) },
            classifyPlatform = session.classifyPlatform,
            // Offer the player's platform folders as move destinations. A plain function
            // reference: everything that decides WHICH folders is in the policy and in
            // FileBrowserRomFolders, and nothing about it is a per-session value.
            resolvePlatformDestinations = ::platformDestinations,
            // The pre-flight the same ruling asks for. Suspends, and reaches the library -- so it rides
            // the same `loadScope` the async listing does rather than a scope of its own, and for the
            // same reason: it must be cancellable with the browser and must never touch a composition.
            resolveDiscSet = ::discSetWarningFor,
            // The async listing seam requires both halves or neither:
            // the controller falls back to the synchronous [listDirectory] unless it has the pair, which
            // is what keeps `feature-files`' own tests on a deterministic, dispatcher-free path.
            //
            // `scope` is Main, and it must be: the apply half writes Compose snapshot state and drives
            // FocusAuthority. It is also the same process-lifetime scope the extraction work uses, so a
            // listing in flight when the browser is torn down dies with `FileBrowserController.close`'s
            // own cancellation rather than with a composable.
            listDirectoryAsync = { path -> listDirectoryAsync(session.context, path) },
            loadScope = scope,
            // Contract C: the drive chooser is a real directory but committing it as a ROM source is
            // meaningless. Suppress the affordance rather than refusing after the fact -- the browser
            // closes BEFORE onChoose runs, so a Toast here would eject the player and then say no.
            commitAllowed = { path -> !StorageVolumeListing.isVolumeRoot(path) },
        )
    }

    /**
     * One directory's rows -- or the drive chooser's, at [StorageVolumeListing.VOLUME_ROOT_PATH].
     *
     * This is the per-path caching bridge described by `FileBrowserController`,
     * proven against a real filesystem by feature-files's own
     * `FileBrowserControllerRepositoryIntegrationTest`, which this mirrors verbatim.
     * `LocalFileRepository.listFiles` already applies `LocalListingPolicy.isPlumbingPath` (ruling 2) and
     * lists empty rather than throwing on an unreadable directory (ruling 3) -- see that method's own
     * header comment. Ruling 1 (`isExtractableArchive`) is applied per row by `fileBrowserPartition`.
     *
     * **The chooser branch re-enumerates on every visit, deliberately**, rather than reading the
     * snapshot [open] captured: a card inserted while the browser is open must appear when the player
     * backs out to the chooser, and a card removed must stop being offered. The cost is one
     * `getExternalFilesDirs` plus a `StatFs` per volume, paid only on the chooser screen itself.
     *
     * **The `runBlocking` here is now the FIRST load only**.
     * `FileBrowserController` takes the pair [listDirectoryAsync] + `loadScope` and uses this synchronous
     * function only until it has a listing on screen -- because an async first load would compose an empty
     * browser and pop the rows in afterwards, which is a design change rather than a perf fix. Every load
     * after that goes through the suspend path: instant from [listingCache] on a return, off the main
     * thread on a cold directory. The result is written into the cache here too, so the very first Up is
     * already a hit.
     */
    private fun listDirectory(context: Context, path: String): List<FileItem> =
        if (StorageVolumeListing.isVolumeRoot(path)) {
            StorageVolumeListing.volumeRows(enumerateVolumes(context))
        } else {
            sweepInterruptedOperations(path)
            withHeldDeletes(path, runBlocking { repository.listFiles(path).first() }.also { listingCache[path] = it })
        }

    /**
     * The same listing, allowed to suspend — **the fix for the ~90 ms parked main thread on the return
     * path**, measured on the Odin 2026-08-15.
     *
     * Three paths, and which one a press takes is the whole design:
     *
     * 1. **The drive chooser** re-enumerates, exactly as [listDirectory] does and for the same reason
     *    stated there. Cheap and deliberately uncached: a card inserted while the browser is open must
     *    appear. (`getExternalFilesDirs` + a `StatFs` per volume, still on the calling thread — bounded by
     *    the number of drives, not by the number of files, and OWED as the one remaining main-thread read.)
     * 2. **A cache hit returns WITHOUT SUSPENDING.** `FileBrowserController.load` starts its coroutine
     *    undispatched, so this path runs inline on the calling thread and the listing is applied in the
     *    same frame as the press — the return costs nothing at all, not "less". The background re-read is
     *    fired, not awaited.
     * 3. **A miss suspends** at `listFiles`, which is already `flowOn(Dispatchers.IO)`. No `withContext`
     *    is needed or wanted here: the flow's own `flowOn` does the hop, and wrapping it would add a
     *    second one. The player's thread is free while the directory is read, and the browser holds the
     *    listing it is already showing until the rows arrive — the same picture the blocking version drew,
     *    minus the freeze.
     */
    private suspend fun listDirectoryAsync(context: Context, path: String): List<FileItem> {
        if (StorageVolumeListing.isVolumeRoot(path)) {
            return StorageVolumeListing.volumeRows(enumerateVolumes(context))
        }
        sweepInterruptedOperations(path)
        listingCache[path]?.let { cached ->
            revalidate(path)
            return withHeldDeletes(path, cached)
        }
        return withHeldDeletes(path, repository.listFiles(path).first().also { listingCache[path] = it })
    }

    /**
     * [rows] of [path] with the deletes still held for Undo shown in it, marked "Deleting…".
     *
     * Applied on the way OUT of [listingCache], never into it: the cache stays what the disk says, so
     * [revalidate]'s "did it change" comparison is not fooled by a row the disk does not have, and a delete
     * released or undone shows correctly on the very next read. Built from the queue's held list -- the same
     * one Undo and [clearSession] read -- and not from `.mimir-staging`, which the listing keeps hidden.
     */
    private fun withHeldDeletes(path: String, rows: List<FileItem>): List<FileItem> =
        HeldDeleteListing.overlay(path, rows, FileOperationQueue.heldDeletions(mutableQueue.value))

    /**
     * Re-read a directory that was served from memory, and publish only if it actually changed.
     *
     * **What this is for, and what it is NOT for.** It is not for this process's own writes — those evict
     * ([evictListings]) rather than wait for a re-read, because serving a deleted file for one frame is a
     * lie about the filesystem. It is for everything else: a companion app, a USB host, an OTG card, the
     * player's own PC. Before the cache existed, every visit re-read the directory and those changes
     * appeared on the next visit; without this they would not, and that would be a real regression sold as
     * a perf win.
     *
     * **Publishes through [FileBrowserController.refresh]**, and only when the player is still standing in
     * that exact directory — a re-read that lands after they have moved on has nothing to update, and the
     * next visit will hit the freshly-written cache anyway. `refresh` keeps focus where it is
     * ([FileBrowserController.refresh]'s contract A), so a publish that does land is not a navigation.
     *
     * Silent on failure, deliberately: `listFiles` answers empty rather than throwing for an unreadable
     * directory (ruling 3), so a throw here is an unexpected fault, and replacing a listing the player can
     * see with the consequences of one is worse than keeping it.
     */
    private fun revalidate(path: String) {
        if (!revalidating.add(path)) return
        scope.launch {
            try {
                val fresh = repository.listFiles(path).first()
                val known = listingCache[path]
                if (known != null && fresh != known) {
                    listingCache[path] = fresh
                    mutableController.value?.takeIf { it.currentPath == path }?.refresh()
                }
            } catch (_: Exception) {
                // Keep what the player is looking at. See the KDoc.
            } finally {
                revalidating.remove(path)
            }
        }
    }

    /**
     * Forget every cached listing — **called wherever this process changes the filesystem**.
     *
     * Deliberately whole-cache rather than per-path. A move touches two directories, an extraction writes
     * into a third, a recovery sweep restores into a fourth, and getting that arithmetic subtly wrong
     * would show the player a file that no longer exists. The cost of over-evicting is one cold re-list
     * on the next visit, off the main thread; the cost of under-evicting is a lie about their files.
     */
    private fun evictListings() {
        listingCache.clear()
    }

    /**
     * **G1 — reconcile journals a previous process abandoned in [path].**
     *
     * `ManagedFileOperations.recoverInterrupted` documents itself as *"the session-start sweep"* and had
     * **zero callers anywhere** until this line (QA lane, 2026-08-14, ranked above F-09). That was not a
     * cosmetic gap: **the earlier change removed the delete confirm dialog**, and it justified the removal on the
     * engine rather than on taste — *"Survives | a battery pull (rollback journal, not memory)"*.
     *
     * Trace what actually happened without this. `stageDelete` moves the payload under `.mimir-staging`
     * with a `STATE_STAGED` ROLLBACK manifest and holds the operation root in the **in-memory** `held`
     * map. The process dies; the next process starts with `held` empty. `recoverOrphaned` does run — but
     * only from inside `copy`/`move`/`stageDelete`, and only against that operation's own destination. So
     * the player's deleted files were **neither restored nor actually gone**: they sat under
     * `.mimir-staging` behind a journal nothing read, and no surface mentioned them. **A confirm dialog
     * was removed on the strength of a guarantee whose entry point was dead code.**
     *
     * **Why per-directory rather than once at open.** `FileOperationStaging.recoverOrphaned` sweeps
     * `baseDirectory/.mimir-staging` and does not walk — so a single sweep at session start could only
     * ever reconcile one directory, and MANAGE opens at the drive chooser, where no journal can exist. A
     * journal lives beside the files it holds, so the sweep has to run where the player returns to. This
     * is that place.
     *
     * **MANAGE only, by construction:** `operations` is null in PICK, so the picker cannot reconcile a
     * journal any more than it can create one.
     *
     * Fired, never awaited, and deliberately so: this must not join `listDirectory`'s disclosed
     * `runBlocking` debt on the input path. The cost when there is nothing to recover is one `listFiles`
     * on an absent directory. A recovery that lands after the listing is drawn refreshes it, which is the
     * same contract-A refresh an extraction already uses.
     */
    private fun sweepInterruptedOperations(path: String) {
        val ops = operations ?: return
        scope.launch {
            val recovered = ops.recoverInterrupted(File(path))
            // The same sweep also reads abandoned EXTRACTION journals (E2 P2): whatever it found is
            // waiting as a report, and becomes a failed card here.
            val interrupted = withContext(Dispatchers.IO) { InterruptedExtractions.drain() }
            enqueueInterruptedExtractions(interrupted)
            if (recovered > 0 || interrupted.isNotEmpty()) {
                // A recovery restored or removed real files. Evict BEFORE the refresh or the refresh
                // re-serves the listing the recovery just invalidated.
                evictListings()
                mutableController.value?.refresh()
            }
        }
    }

    /**
     * **E2 P2: an interrupted extract is never silent.** Collect the extractions a previous process did
     * not finish ([collect] sweeps the folders their pointers name) and put each on the queue as a failed
     * card saying how far it got and what was kept. MANAGE only -- [operations] is null in PICK, which has
     * no queue to show a card on; the reports stay on disk until a MANAGE session can.
     */
    private fun reportInterruptedExtractions(
        opened: Session,
        collect: () -> List<InterruptedExtraction>,
    ) {
        scope.launch {
            val reports = withContext(Dispatchers.IO) { runCatching(collect).getOrDefault(emptyList()) }
            if (session !== opened) return@launch
            enqueueInterruptedExtractions(reports)
            if (reports.isNotEmpty()) mutableController.value?.refresh()
        }
    }

    private fun enqueueInterruptedExtractions(reports: List<InterruptedExtraction>) {
        if (operations == null) return
        interruptedExtractionEntries(reports) { nextQueueId++ }.forEach(::enqueue)
    }

    /**
     * Read the device's volumes and publish them to [volumes].
     *
     * Failure is empty, never a throw: [StorageVolumeHelper] touches `StorageManager`/`StatFs`, and a
     * browser that crashes because one volume misbehaved is worse than one that falls back to
     * [StorageVolumeListing.PRIMARY_FALLBACK_PATH] -- the same "empty rather than throw" posture ruling
     * 3 already established for unreadable directories.
     */
    private fun enumerateVolumes(context: Context): List<StorageVolume> =
        runCatching { StorageVolumeHelper(context).getStorageVolumes() }
            .getOrDefault(emptyList())
            .also { mutableVolumes.value = it }

    /**
     * Extract the archive at [archivePath] into a fresh sibling directory named after it (matching
     * `ArchiveHelperExtractionTest`'s own convention) -- e.g. `.../Roms/bundle.zip` extracts into
     * `.../Roms/bundle/`. `ArchiveHelper.extract` stages the write and rolls back on failure
     * (`StagingRecoveryPolicy.ROLLBACK`), which is what makes firing this without awaiting the result
     * safe. Only zip/7z rows ever reach here (`LocalListingPolicy.EXTRACTABLE_ARCHIVE_EXTENSIONS`,
     * already enforced before `onExtract` is invoked at all -- see `FileBrowserRow.extractable` /
     * `fileBrowserCommand`'s `SECONDARY` branch), so there is no double-extension ambiguity to resolve.
     *
     * Known limitation: the browser does not refresh its current listing after extraction, so newly
     * extracted content stays invisible until the directory is re-entered. A `FileBrowserController`
     * refresh hook (`fun refresh() { load(currentPath) }`) called from the success branch remains pending.
     *
     * D-1, delegates to [dispatchExtraction], which is what actually raises the
     * *Keep both / Replace / Skip* card on a collision -- see that function's own KDoc.
     */
    private fun extract(archivePath: String, session: Session) {
        val archiveFile = File(archivePath)
        val destination = File(archiveFile.parentFile, archiveFile.nameWithoutExtension).path
        dispatchExtraction(archivePath, destination, session)
    }

    /**
     * The extraction outcome, lifted out of [extract]'s `scope.launch` so it is reachable from a JVM
     * unit test. **Ruling 4, 2026-08-14.**
     *
     * The refresh call used to sit inline, and deleting it failed no test at all — extraction-visibility
     * would have regressed silently on the next refactor of this file. It now lives in a plain suspend
     * function a test can drive with a real controller and a counting `listDirectory`.
     *
     * **Residual gap, disclosed rather than papered over:** the `scope.launch { runExtraction(...) }`
     * delegation in [dispatchExtraction] itself is still unpinned — it dispatches on Main, which a JVM
     * unit test cannot reach without a Compose/Robolectric harness this module does not have.
     */
    internal suspend fun runExtraction(
        archivePath: String,
        destination: String,
        extract: suspend (String, String) -> Result<Int>,
        currentController: () -> FileBrowserController?,
        onExtracted: (archivePath: String, destination: String, count: Int) -> Unit,
        onExtractFailed: (archivePath: String, error: Throwable) -> Unit,
        /**
         * D-1, policy 2026-08-15. A [FileOperationConflictException] is routed here instead of to
         * [onExtractFailed]. **Defaults to forwarding into [onExtractFailed] anyway** -- which is exactly
         * PICK's behaviour, since [dispatchExtraction] never supplies an override for its `id == null`
         * (PICK) branch: PICK cannot enqueue a card (the queue is
         * MANAGE-only), so a collision has to surface as an ordinary failure rather than as a card nothing
         * can show or answer. MANAGE supplies a real override that parks the entry instead of finishing
         * it. Both paths are pinned directly in `FileBrowserExtractRefreshTest`.
         */
        onConflict: (FileOperationConflictException) -> Unit = { onExtractFailed(archivePath, it) },
    ) {
        extract(archivePath, destination).fold(
            onSuccess = { count ->
                // The extracted files landed in the directory being browsed. Without this the listing
                // keeps its pre-extraction rows, which reads as "extract did nothing". refresh() keeps
                // the player's focused tile rather than jumping them back to index 0.
                currentController()?.refresh()
                onExtracted(archivePath, destination, count)
            },
            onFailure = { error ->
                if (error is FileOperationConflictException) {
                    onConflict(error)
                } else {
                    onExtractFailed(archivePath, error)
                }
            },
        )
    }

    /**
     * The inspector's "Verify" button: compute [CHECKSUM_ALGORITHM] over the focused row and report it.
     *
     * `CancellationException` is rethrown ahead of the broad catch rather than being reported as a
     * checksum failure -- swallowing a cancellation into a user-visible "couldn't read this file" would
     * be a lie about the filesystem. Same shape `LocalFileRepository.listFiles` and
     * `ArchiveHelper.extract` already use for exactly this hazard.
     *
     * OWED: the digest is reported, not compared. Matching it against the bundled DAT corpus (adoption
     * spec §7, "identity confirmation against the bundled DAT inventory") needs `core-metadata`'s lookup
     * wired in; showing it in the inspector's Checksum row rather than a Toast needs `feature-files`.
     */
    private fun verifyChecksum(path: String, session: Session) {
        if (this.session !== session) return
        // F-05,  The digest lands in the inspector's
        // own Checksum row via [checksum]; a Toast could not hold a 40-character SHA-1 anyway.
        checksumRequests.request(
            path = path,
            session = session,
            onChecksum = { digest -> session.onChecksum(path, digest) },
            onChecksumFailed = { error -> session.onChecksumFailed(path, error) },
        )
    }

    /**
     * Force the browser shut -- used when the shell tears down around it without the player choosing
     * or dismissing (`MimirAppHost`'s own dispose-time safety net for `FileBrowserSurface`).
     * Idempotent: [FileBrowserController.close] is safe to call more than once, and a second call here
     * after the controller already nulled itself out (via `onChoose`/`onDismiss`) finds nothing to do.
     */
    fun close() {
        mutableController.value?.close()
        clearSession()
    }

    /**
     * Close only if [controller] is still the one this store holds.
     *
     * Ordering-independent teardown for `MimirAppHost`'s dispose-time safety net. [switchVolume] swaps
     * the controller in place, which makes the OUTGOING controller's `DisposableEffect` fire while a
     * live, freshly mounted controller sits in the store -- an unconditional [close] there would tear
     * down the browser the player just switched into. Identity comparison, not equality: two controllers
     * rooted at the same path are still two different mounts.
     */
    fun closeIfCurrent(controller: FileBrowserController) {
        if (mutableController.value === controller) close()
    }

    /**
     * Tear the session down, and **settle every delete the queue is still holding**.
     *
     * F-01. A held delete owns a real journal under `.mimir-staging` with the player's files inside it.
     * Leaving one behind when the browser closes would strand both: the bytes never actually free the
     * player's card, and the journal sits there until some later sweep decides what it means.
     *
     * **They are FINALISED, not undone, and the asymmetry with a crash is deliberate.** Closing the
     * manager is a deliberate act by the player who asked for the delete, so the delete stands. A
     * process that dies is not a decision at all, so `FileOperationStaging`'s ROLLBACK policy puts the
     * files back — the recoverable direction when nobody chose. Both are defensible; what would not be
     * is silently doing one while documenting the other.
     *
     * Runs on the process-lifetime [scope], not the caller's, so a browser torn down mid-frame still
     * finishes settling.
     */
    private fun clearSession() {
        checksumRequests.clear()
        val ops = operations
        val held = FileOperationQueue.heldDeletions(mutableQueue.value)
        if (ops != null && held.isNotEmpty()) {
            scope.launch { held.forEach { ops.finalizeDelete(it) } }
        }
        mutableQueue.value = emptyList()
        pendingTransfers.clear()
        pendingExtractions.clear()
        pendingRenames.clear()
        // The cache is session-scoped, not process-scoped: between two browser sessions the player has
        // been away from this surface for an unbounded time, and everything they did in between --
        // including in another app -- is invisible to it.
        evictListings()
        revalidating.clear()
        operations = null
        FileBrowserRomFolders.clear()
        mutableController.value = null
        session = null
    }
}

/**
 * Whether a queue card may be dismissed — **the ruling behind [FileBrowserStore.dismiss]**.
 *
 * Exhaustive over [FileQueueState] with **no `else`**, so a state added later cannot inherit a default:
 * the compiler forces the next author to decide whether it is safe to drop.
 *
 * **[FileQueueState.UndoAvailable] answers false, and that refusal is the whole point.** Undo is now the
 * only protection on a delete (no confirm dialog), so a button that quietly
 * discarded it would be worse than no button — the player thinks they are tidying a card away and instead
 * finalise a delete. And it would not even finalise: `FileBrowserStore.clearSession` reads the held
 * deletions out of the QUEUE itself (`FileOperationQueue.heldDeletions(mutableQueue.value)`), so a card
 * removed this way is invisible to teardown, `ManagedFileOperations.held` keeps retaining the operation
 * root, and `FileOperationStaging`'s ROLLBACK policy resurrects the files after the process dies.
 * [FileBrowserStore.releaseUndo] stays the one deliberate way to finalise.
 *
 * [FileQueueState.AwaitingConflict] refuses for a second reason: its `pendingTransfers` or
 * `pendingExtractions` entry (D-1, policy 2026-08-15, for the latter) is keyed by id, so dropping
 * the card would strand that map entry and leave the player no way to answer.
 */
internal fun fileQueueDismissable(state: FileQueueState): Boolean = when (state) {
    // Skipped dismisses like Done and Failed: it is terminal, holds no journal, and strands no pending
    // answer. Nothing is being decided, so there is nothing to protect the player from putting away.
    FileQueueState.Done, FileQueueState.Skipped, is FileQueueState.Failed, is FileQueueState.Blocked -> true
    // A cancelled card is terminal too: the job has wound down, holds no journal, and strands no answer.
    is FileQueueState.Cancelled -> true
    is FileQueueState.UndoAvailable -> false
    // **True, and NOT by inheriting the line above.** The delete's refusal is about a decision the player
    // has not made yet: dismissing it finalises. A move is already final -- the bytes are at the
    // destination and the library has already followed them -- so Undo here is an OFFER, and refusing to
    // let the player put the card away would strand every completed move on the queue for the session.
    // See `FileQueueState.ReverseAvailable`'s own table.
    is FileQueueState.ReverseAvailable -> true
    is FileQueueState.Running, FileQueueState.AwaitingConflict -> false
}

/**
 * [entries] with [entryId] removed if — and only if — [fileQueueDismissable] permits it. Unknown id is a
 * no-op, matching every other id-keyed entry point in [FileBrowserStore].
 *
 * Lifted out of [FileBrowserStore.dismiss] for the same reason `runExtraction` was: the store is an
 * `object` with process-lifetime state and a `Context` requirement, so nothing can put an entry into its
 * real queue without a Robolectric harness this module does not have. Here the rule is drivable by a
 * plain JUnit test with real values.
 *
 * Removal itself delegates to [FileOperationQueue.remove] rather than filtering here — `core-files` owns
 * the queue's vocabulary; this file adds only the decision.
 */
internal fun fileQueueAfterDismiss(entries: List<FileQueueEntry>, entryId: Long): List<FileQueueEntry> {
    val entry = entries.firstOrNull { it.id == entryId } ?: return entries
    return if (fileQueueDismissable(entry.state)) FileOperationQueue.remove(entries, entryId) else entries
}

/**
 * The delete card's second line — **A-2**. The saves-and-states claim is made only when it is derivable
 * from the paths themselves.
 *
 * `ManagedFileOperations.stageDelete` moves whatever path it is handed and supports directories
 * (`atomicMove` on the same volume; `finalizeDelete` branches on `root.isDirectory`). So the claim holds
 * exactly when every path is a plain file: that delete moves those files and nothing else. A directory
 * takes its whole subtree with it, and a player deleting a `saves` folder must not be told their saves
 * were kept.
 *
 * When it is not derivable this **says less, not more** — the bare verb, no second clause. Same precedent
 * as `MimirPlatformShelfmark`: an unnameable platform gets NO plate rather than a meaningless one. A
 * warning about folder contents would be new player-facing copy, which still needs copy review.
 *
 * An empty list is guarded explicitly: `all {}` is vacuously true, so without it a delete of nothing
 * would make the strongest claim in the file.
 *
 * @param isPlainFile whether one path is a regular file that exists. **Injected, not read here**, so this
 *   function is decidable with no filesystem; [FileBrowserStore.deleteInManager] passes `File(it).isFile`.
 *   `isFile` rather than `!isDirectory` on purpose: a path that no longer exists answers false to both,
 *   and an unverifiable path must get no claim rather than the benefit of the doubt.
 */
internal fun fileDeleteSubtitle(paths: List<String>, isPlainFile: (String) -> Boolean): String =
    if (paths.isNotEmpty() && paths.all(isPlainFile)) "Delete · saves and states kept" else "Delete"
