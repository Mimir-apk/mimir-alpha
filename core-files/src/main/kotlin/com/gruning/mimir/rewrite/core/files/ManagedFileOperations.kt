package com.gruning.mimir.rewrite.core.files

import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val PROVIDER = "local"

/**
 * Below this size a file is copied in ONE `Files.copy` call, exactly as before. A file that finishes in
 * a frame or two cannot show a stale bar, and the platform's own transfer beats a hand-rolled loop.
 */
private const val StreamedCopyThresholdBytes = 2L * 1024 * 1024

/** Stream chunk. Large enough that the loop is not syscall-bound, small enough to stay off the heap's back. */
private const val CopyBufferBytes = 256 * 1024

/**
 * Progress floor. The real step is `max(this, totalBytes / 100)`, so a big transfer emits ~100 times and
 * a small one emits per megabyte -- never per chunk. Each emission costs a StateFlow hop and a layout
 * pass on the queue's `fillMaxWidth(fraction)` bar.
 */
private const val ProgressStepMinBytes = 1L * 1024 * 1024

/**
 * **The destructive half of the file manager. Move, copy, delete — and nothing else in this tree may
 * hold it.** F-01,
 *
 * ## Why this is a separate class, and not `LocalFileRepository` again
 *
 * Destructive operations stay separate from `LocalFileRepository` so a player adding ROMs
 * cannot reach a delete operation. Structural separation is stronger than a hidden UI control.
 *
 * `FileBrowserStore` holds **one** `LocalFileRepository` instance and hands it to both modes
 * (`FileBrowserStore.kt:63`, `private val repository = LocalFileRepository()`). Putting `deleteFiles`
 * back on that class would give the PICK session an object with a delete method on it, kept away from
 * the player by a mode check somewhere else — "hidden from", which does not provide structural safety. Landing
 * the restored code on its own class instead makes the property structural: **the picker's engine has
 * no destructive method to call, so no mode check can be forgotten, bypassed, or refactored away.**
 * `LocalFileRepository` stays read-only, and its existing read-only pin stays honest instead of being
 * reversed.
 *
 * The code itself IS upstream's, restored as §1 requires — `copyFiles`/`moveFiles`/`deleteFiles` and
 * the private `copyRecursive`/`resolveTransferTarget`/`stageAndCommitCopy`/`forceTree`/`deleteRecursive`
 * /`resolveConflict` helpers, ported from
 * `core/data/src/main/java/com/explorer/fileexplorer/core/data/LocalFileRepository.kt` in the upstream
 * clone (lines 69-404). Only the placement and the four deviations below differ.
 *
 * `rename`, `createDirectory` and `createFile` are outside this restored move/copy/delete surface.
 * Extra destructive capabilities would increase the risk to the player's files.
 *
 * ## Deviation 1 — ASK never resolves silently
 *
 * Upstream's `resolveTransferTarget` treats [ConflictResolution.ASK] identically to
 * [ConflictResolution.RENAME]: a collision under ASK silently produced a `(copy-<hash>)` file. **The
 * design forbids that** — a conflict raises a queue entry offering *Keep both / Replace / Skip*
 * (`Mimir Files.dc.html:254-259`), and the player picks. So ASK here **refuses**: [copy]/[move] fail with
 * [FileOperationConflictException] naming the colliding paths, and the caller turns that into an
 * `AwaitingConflict` queue entry. Nothing is written before the player answers — [conflictsFor] lets a
 * caller ask first, without touching the filesystem at all.
 *
 * ## Deviation 2 — delete stages and STOPS, so Undo is a state and not a promise
 *
 * Upstream's `deleteFiles` stages the payload aside, immediately writes [FileOperationStaging.STATE_COMMITTED],
 * and deletes the staged copy — after which nothing can undo it. Delete must hold an **Undo available** queue state rather than only a transient toast. So [stageDelete] performs upstream's sequence
 * only as far as [FileOperationStaging.STATE_STAGED] and **stops there**. The files are off the player's
 * disk (the delete looks done, which is what they asked for) and are sitting intact under
 * `.mimir-staging`, reachable by [undoDelete] for as long as the queue entry holds Undo. [finalizeDelete]
 * is the second half, run when the entry is released.
 *
 * **This is why the machinery had to be `FileOperationStaging` rather than a bespoke trash folder:** the
 * held state is a real ROLLBACK journal, so a battery pull mid-Undo is answered by
 * [FileOperationStaging.recoverOrphaned] putting the files back — Undo survives the process dying, which
 * a toast could never claim. See [FileOperationStaging.recoverOrphaned]'s `retain` parameter for the
 * resurrection hazard that same property creates while the process is alive, and how it is closed.
 *
 * ## Deviation 3 — no `recoverOrphaned` sweep inside each operation
 *
 * Upstream calls `FileOperationStaging.recoverOrphaned(destinationDirectory)` at the top of `copyFiles`
 * and `moveFiles`. Kept, but always with [heldRoots] passed as `retain` — see deviation 2.
 *
 * ## Deviation 4 — de-Hilted, no `Context`, no `FileRepository` interface
 *
 * Same as `LocalFileRepository`'s own vendoring: no `@Inject`/`@Singleton`, no `capabilities` property
 * (replaced by the file-private `PROVIDER` label), no `DeleteCapabilities`.
 *
 * **No network surface**: `java.nio.file` only, same as every other file in this module.
 */
class ManagedFileOperations(
    private val repository: LocalFileRepository = LocalFileRepository(),
) {

    /** Operation roots that must survive a recovery sweep — see [FileOperationStaging.recoverOrphaned]. */
    fun heldRoots(): Set<String> = synchronized(held) { held.keys.toSet() }

    /** The deletes held for Undo right now -- what the listing shows as "Deleting…" ([HeldDeleteListing]). */
    fun heldDeletions(): List<StagedDeletion> = synchronized(held) { held.values.toList() }

    /**
     * **A folder cannot be renamed or moved while a delete is held for Undo, or a job is working, inside it.**
     *
     * Both keep their journal in `<folder>/.mimir-staging`, and both are remembered by that PATH
     * ([heldRoots], [FileOperationStaging.liveRootPaths]). Rename the folder and the journal travels with it
     * while the remembered path stays behind: the next sweep of the renamed folder reads the held delete as
     * an abandoned one and removes its payload, and Undo then returns nothing. A refusal, with a sentence,
     * beats a delete that quietly became permanent.
     *
     * Compared as paths, never as strings, so `gb` does not claim `gba/...`.
     */
    private fun refuseIfHoldsOperations(source: Path) {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) return
        val folders = pathForms(source)
        fun inside(roots: Set<String>) = roots.any { root ->
            pathForms(Paths.get(root)).any { candidate -> folders.any { candidate.startsWith(it) } }
        }
        when {
            inside(heldRoots()) ->
                throw FileRefusedException("Finish or undo the pending delete in this folder first.")
            inside(FileOperationStaging.liveRootPaths()) ->
                throw FileRefusedException("Wait for the copy, move or extract in this folder to finish first.")
        }
    }

    /** A path as written and as the filesystem names it, so a short Windows name or a symlink cannot hide a match. */
    private fun pathForms(path: Path): List<Path> = listOfNotNull(
        path.toAbsolutePath().normalize(),
        runCatching { path.toRealPath() }.getOrNull(),
    )

    /** Whether [deletion] is still undoable. False once [undoDelete] or [finalizeDelete] has run. */
    fun isHeld(deletion: StagedDeletion): Boolean =
        synchronized(held) { held.containsKey(deletion.operationRootPath) }

    /**
     * Which of [sources] would collide in [destination], **without writing anything**.
     *
     * The pre-flight the queue needs to raise *Keep both / Replace / Skip* before any bytes move. A
     * source that is already exactly where it is going is not a conflict (moving a file onto itself is a
     * no-op, which is upstream's own behaviour in `moveFiles`).
     */
    suspend fun conflictsFor(sources: List<String>, destination: String): List<FileConflict> =
        withContext(Dispatchers.IO) {
            val destinationDirectory = Paths.get(destination)
            sources.mapNotNull { source ->
                val sourcePath = Paths.get(source)
                val target = destinationDirectory.resolve(sourcePath.fileName.toString())
                when {
                    target.normalize() == sourcePath.normalize() -> null
                    !Files.exists(target) -> null
                    else -> FileConflict(sourcePath = source, targetPath = target.toString())
                }
            }
        }

    /**
     * Copy [sources] into [destination]. Restored from upstream `copyFiles` (clone lines 69-112).
     *
     * @param conflictResolution [ConflictResolution.ASK] refuses on a real collision — see deviation 1.
     * @param onProgress fired only for bytes a caller actually watched move — see [onAccounted]'s KDoc
     *   for the honesty split this pair exists to make possible.
     * @param onAccounted fired instead of [onProgress] wherever the returned `(Long, Long, String)` is
     *   a bookkeeping advance rather than an observed one: a skipped source (below), and — inside
     *   [copyOneFile] — a small file below [StreamedCopyThresholdBytes], where `Files.copy` transfers
     *   the whole file in one blocking call, so the callback can only ever fire *after* it is already
     *   done. A caller that folds both callbacks into one determinate fraction reintroduces
     *   exactly that bug — the whole reason for two callbacks is to let a caller draw an honest
     *   indeterminate state for the calls this one carries, instead of a fabricated jump.
     * @param onSkipped fired once per source the player chose to leave alone under
     *   [ConflictResolution.SKIP] — see its own call site below. `count` in the returned [Result] stays
     *   the engine's HANDLED total (unchanged, for source compatibility); this is the callback a caller
     *   uses to recover transferred-vs-skipped, which `count` alone cannot express.
     */
    suspend fun copy(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution = ConflictResolution.ASK,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        onAccounted: (Long, Long, String) -> Unit = { _, _, _ -> },
        onSkipped: (String) -> Unit = {},
        onLanded: (String, String) -> Unit = { _, _ -> },
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val destinationDirectory = requireDestination(destination, "Copy")
            // Cancel is cooperative and lands at a safe point: between sources and between chunks of a file,
            // never after the commit has begun -- see [commitStaged].
            val checkCancelled: () -> Unit = { ensureActive() }
            refuseUnresolvedConflicts(sources, destination, conflictResolution)
            FileOperationStaging.recoverOrphaned(destinationDirectory, heldRoots())
            val totalSize = repository.calculateSize(sources)
            var count = 0
            var transferred = 0L
            for (source in sources) {
                checkCancelled()
                val sourcePath = Paths.get(source)
                val requestedTarget = destinationDirectory.toPath().resolve(sourcePath.fileName.toString())
                val target = resolveTransferTarget(requestedTarget, conflictResolution)
                // Replace is decided HERE, from the plan, and carried to the commit. Deciding it at the commit from what is
                // on disk then backed up (and deleted) a Keep-both name another job took during the size walk below.
                val replacing = target != null && conflictResolution == ConflictResolution.OVERWRITE && Files.exists(target)
                target?.let { refuseIfNameHeld(it) }
                val sourceSize = repository.calculateSize(listOf(source))
                if (target == null) {
                    // SKIP: the player chose to leave the existing file alone. Counted as handled, so
                    // the queue's progress does not stall on a decision that was made -- but nothing was
                    // OBSERVED, so the advance is reported through onAccounted, never onProgress, and
                    // onSkipped names which source this was (F-6's transferred/skipped split).
                    transferred += sourceSize
                    onAccounted(transferred, totalSize, sourcePath.fileName.toString())
                    onSkipped(source)
                    count++
                    continue
                }
                val operation = stageAndCommitCopy(
                    source = sourcePath,
                    target = target,
                    startBytes = transferred,
                    totalBytes = totalSize,
                    onProgress = onProgress,
                    onAccounted = onAccounted,
                    moveSource = false,
                    replaceTarget = replacing,
                    checkCancelled = checkCancelled,
                )
                FileOperationStaging.deleteRecursively(operation)
                FileOperationStaging.pruneRootOf(operation)
                // After the commit, never before: see move().
                onLanded(source, target.toString())
                transferred += sourceSize
                count++
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileOperationConflictException) {
            // Deliberately NOT wrapped: asRepositoryException would flatten this into a generic
            // RepositoryException and the queue would lose the very list it needs to ask Keep both /
            // Replace / Skip about. A conflict is a question, not an error.
            Result.failure(e)
        } catch (e: FileRefusedException) {
            // A sentence Mimir already wrote for the player (no free "(copy)" name, a folder with a pending
            // delete in it): wrapping it would throw the sentence away.
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(PROVIDER, RepositoryOperation.COPY))
        }
    }

    /**
     * Move [sources] into [destination]. Restored from upstream `moveFiles` (clone lines 157-215).
     *
     * **[onLanded] reports where each source actually ended up, and it exists because callers cannot
     * work that out afterwards.** Under [ConflictResolution.RENAME] this engine renames the *arriving*
     * file and leaves the incumbent on `destination/<name>` (see [resolveTransferTarget]) -- so a caller
     * that infers the landing from `destination + originalName` and checks `exists()` is reading the
     * INCUMBENT, and the existence check passes for exactly the reason it should have failed. That
     * inference shipped in 49acd461 and produced two silent wrong outcomes: an Undo handle naming a file
     * the operation never touched, and a library row rewritten onto the incumbent's own path.
     *
     * Fired once per source that was really written, with the resolved target. **Not fired for a source
     * the resolution skipped**, so a caller can tell "landed under its own name" from "did not land" by
     * absence rather than by guessing.
     *
     * @param onAccounted the observed/accounted split — see [copy]'s own KDoc, which this mirrors. Also
     *   covers a source already at its destination (below): it DID land, but zero bytes moved, so the
     *   advance is bookkeeping, not a measurement.
     * @param onSkipped one call per source the player chose to leave alone under [ConflictResolution.SKIP], and
     *   one per [followers] entry left behind (below).
     * @param followers sources that travel WITH another source and must keep that source's name -- a disc's `.sbi`
     *   beside its disc, which the emulator finds only by the disc's own name. They move after everything else, land
     *   as [MoveFollower.nameFor] says of where their leader landed, and are **left where they are, and reported
     *   to [onSkipped], whenever that cannot be honoured**: the leader did not move, or -- under
     *   [ConflictResolution.RENAME] -- the name they need is taken. Keep both would otherwise rename the follower on
     *   its own, `Game (copy).sbi` beside a `Game.chd`, which no disc can find.
     */
    suspend fun move(
        sources: List<String>,
        destination: String,
        conflictResolution: ConflictResolution = ConflictResolution.ASK,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        onAccounted: (Long, Long, String) -> Unit = { _, _, _ -> },
        onSkipped: (String) -> Unit = {},
        followers: Map<String, MoveFollower> = emptyMap(),
        onLanded: (String, String) -> Unit = { _, _ -> },
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val destinationDirectory = requireDestination(destination, "Move")
            // Before anything moves, so a refusal on the third of five folders cannot leave two already gone.
            sources.forEach { refuseIfHoldsOperations(Paths.get(it)) }
            // See copy(): cooperative, between sources and chunks, never once a commit has begun -- so a move
            // finishes or rolls back its current item atomically and the source is never lost.
            val checkCancelled: () -> Unit = { ensureActive() }
            refuseUnresolvedConflicts(sources, destination, conflictResolution)
            FileOperationStaging.recoverOrphaned(destinationDirectory, heldRoots())
            var count = 0
            // ONE total for the whole move, and a running `transferred` across sources -- copy() has
            // always done this and move() never did. Every source was passed `startBytes = 0L` and its
            // OWN size as the total, so a multi-file move drove the queue's fraction 0 -> 1 once PER
            // FILE: the bar filled, snapped back to empty, and filled again. A resetting bar does not report the operation the card names.
            val totalSize = repository.calculateSize(sources)
            var transferred = 0L
            // Followers go last, so the source each one follows has already landed -- or been skipped -- when the
            // follower is asked about. Stable: everything else keeps the order it was given in.
            val ordered = if (followers.isEmpty()) {
                sources
            } else {
                sources.filter { it !in followers } + sources.filter { it in followers }
            }
            val landedAt = HashMap<String, String>()
            val reportLanded: (String, String) -> Unit = { from, to ->
                landedAt[from] = to
                onLanded(from, to)
            }
            for (source in ordered) {
                checkCancelled()
                val sourcePath = Paths.get(source)
                val follower = followers[source]
                val leaderLanded = follower?.let { landedAt[it.leader] }
                val requestedTarget = destinationDirectory.toPath().resolve(
                    if (follower != null && leaderLanded != null) follower.nameFor(leaderLanded) else sourcePath.fileName.toString(),
                )
                val sourceSize = repository.calculateSize(listOf(source))
                // A follower never lands under a name of its own choosing: with no leader it goes nowhere, and with
                // its leader's name taken it stays put rather than be renamed apart from the file it belongs to.
                val leftBehind = follower != null && (
                    leaderLanded == null ||
                        (
                            conflictResolution == ConflictResolution.RENAME &&
                                requestedTarget.normalize() != sourcePath.normalize() &&
                                Files.exists(requestedTarget)
                            )
                    )
                if (!leftBehind && requestedTarget.normalize() == sourcePath.normalize()) {
                    // Already where it was asked to go. It DID land, under its own name -- reporting it
                    // keeps "absent from the report" meaning "skipped" and nothing else. Zero bytes moved,
                    // so the advance is accounted, not observed -- onProgress would claim a transfer that
                    // never happened.
                    reportLanded(source, sourcePath.toString())
                    transferred += sourceSize
                    onAccounted(transferred, totalSize, sourcePath.fileName.toString())
                    count++
                    continue
                }
                val target = if (leftBehind) null else resolveTransferTarget(requestedTarget, conflictResolution)
                val replacing = target != null && conflictResolution == ConflictResolution.OVERWRITE && Files.exists(target)
                target?.let { refuseIfNameHeld(it) }
                if (target == null) {
                    // SKIP, or a follower left behind. Nothing was written, so nothing is reported to onLanded -- `count` still
                    // increments (the conflated-total defect this store already documents as OWED, and
                    // the reason onSkipped exists: a caller that wants the real transferred total
                    // subtracts what this reports). Nothing was observed either, so onAccounted carries
                    // the bookkeeping advance, never onProgress.
                    transferred += sourceSize
                    onAccounted(transferred, totalSize, sourcePath.fileName.toString())
                    onSkipped(source)
                    count++
                    continue
                }
                val stageByMove = target.parent == sourcePath.parent && !Files.exists(target)
                val operation = stageAndCommitCopy(
                    source = sourcePath,
                    target = target,
                    startBytes = transferred,
                    totalBytes = totalSize,
                    onProgress = onProgress,
                    replaceTarget = replacing,
                    onAccounted = onAccounted,
                    moveSource = true,
                    stageByMove = stageByMove,
                    // A same-folder move is an atomic rename: no bytes stream, so nothing would report it
                    // at all. This is the value it lands on -- THIS source complete, not the whole
                    // operation complete, which is what `totalBytes` would have meant once the total
                    // above became the operation's rather than this file's.
                    completionBytes = transferred + sourceSize,
                    checkCancelled = checkCancelled,
                )
                FileOperationStaging.deleteRecursively(operation)
                FileOperationStaging.pruneRootOf(operation)
                // AFTER the commit, never before: a landing reported for a write that then threw would
                // be the same untrue claim, one step earlier.
                reportLanded(source, target.toString())
                transferred += sourceSize
                count++
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileOperationConflictException) {
            // See copy() -- a conflict is a question, not an error.
            Result.failure(e)
        } catch (e: FileRefusedException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(PROVIDER, RepositoryOperation.MOVE))
        }
    }

    /**
     * **Rename the file or folder at [source] to [newName], in the folder it already sits in.**
     *
     * The name is checked by [RenameNamePolicy] first (the keyboard has already shown the player the same
     * sentence, so this is the last line of defence rather than the first). The rename itself goes through
     * the same journalled staging every move uses, so an interruption puts the original name back, and a
     * *Replace* keeps the file it replaced until the rename has fully landed.
     *
     * A collision is a question, exactly as for move and copy: [ConflictResolution.ASK] with a real
     * collision throws [FileOperationConflictException] and writes nothing. *Keep both* names the arriving
     * item `name (copy).ext`; *Skip* leaves everything as it is and answers `null`.
     *
     * **Replace never covers a folder**, in either direction: it would delete one wholesale on the strength
     * of a button the player pressed to resolve a name.
     *
     * A change of letter case alone (`game.gba` to `Game.gba`) is not a collision, even on a filesystem
     * that cannot tell the two names apart.
     *
     * @return the path the item has now, or null when nothing was renamed (the player chose Skip).
     */
    suspend fun rename(
        source: String,
        newName: String,
        conflictResolution: ConflictResolution = ConflictResolution.ASK,
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val sourcePath = Paths.get(source)
            val parent = sourcePath.parent ?: throw FileRefusedException("There is no folder to rename this in.")
            val name = RenameNamePolicy.normalise(newName)
            RenameNamePolicy.check(name, sourcePath.fileName.toString())?.let { throw FileRefusedException(it.sentence) }
            val directory = requireDestination(parent.toString(), "Rename")
            if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) throw NoSuchFileException(source)
            refuseIfHoldsOperations(sourcePath)
            val requested = parent.resolve(name)
            val taken = Files.exists(requested, LinkOption.NOFOLLOW_LINKS)
            val sameItem = taken && runCatching { Files.isSameFile(sourcePath, requested) }.getOrDefault(false)
            val collision = taken && !sameItem
            if (collision && conflictResolution == ConflictResolution.ASK) {
                throw FileOperationConflictException(
                    listOf(FileConflict(sourcePath = source, targetPath = requested.toString())),
                )
            }
            val target = when {
                !collision -> requested
                conflictResolution == ConflictResolution.SKIP -> return@withContext Result.success(null)
                conflictResolution == ConflictResolution.RENAME -> resolveConflict(requested)
                else -> {
                    if (Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                        throw FileRefusedException("A folder can't replace or be replaced. Use Keep both, or pick another name.")
                    }
                    requested
                }
            }
            refuseIfNameHeld(target)
            FileOperationStaging.recoverOrphaned(directory, heldRoots())
            val operation = stageAndCommitCopy(
                source = sourcePath,
                target = target,
                replaceTarget = collision && conflictResolution == ConflictResolution.OVERWRITE,
                startBytes = 0L,
                totalBytes = 0L,
                onProgress = { _, _, _ -> },
                onAccounted = { _, _, _ -> },
                moveSource = true,
                stageByMove = true,
                checkCancelled = { ensureActive() },
            )
            FileOperationStaging.deleteRecursively(operation)
            FileOperationStaging.pruneRootOf(operation)
            Result.success(target.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileOperationConflictException) {
            Result.failure(e)
        } catch (e: FileRefusedException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(PROVIDER, RepositoryOperation.RENAME))
        }
    }

    /**
     * **Delete [paths] as far as Undo-available, and stop.** The first half of upstream `deleteFiles`
     * (clone lines 217-274) — see deviation 2.
     *
     * On return the paths are gone from the player's listing and the payload is intact under
     * `.mimir-staging`. The caller MUST eventually call [undoDelete] or [finalizeDelete]; until it does,
     * this deletion's operation root is in [heldRoots] and every sweep this class performs will leave it
     * alone.
     *
     * All-or-nothing per call is deliberately NOT attempted: each path gets its own journal, so a
     * failure on the third of five leaves the first two individually undoable rather than rolling back
     * work the player already saw succeed.
     */
    suspend fun stageDelete(
        paths: List<String>,
        onProgress: (String) -> Unit = {},
    ): Result<StagedDeletion> = withContext(Dispatchers.IO) {
        try {
            val entries = mutableListOf<StagedDeletionEntry>()
            var operationRoot: File? = null
            for (path in paths) {
                val source = Paths.get(path)
                if (!Files.exists(source)) continue
                val parent = source.parent?.toFile()
                    ?: throw IOException("Cannot stage filesystem root: $path")
                if (operationRoot == null) {
                    FileOperationStaging.recoverOrphaned(parent, heldRoots())
                    operationRoot = FileOperationStaging.createOperation(
                        baseDirectory = parent,
                        operation = DELETE_OPERATION,
                        recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                    )
                }
                val staged = operationRoot.toPath().resolve("payload").resolve(source.fileName.toString()).toFile()
                staged.parentFile?.mkdirs()
                entries += StagedDeletionEntry(originalPath = source.toString(), stagedPath = staged.toString())
                writeDeleteManifest(operationRoot, entries, FileOperationStaging.STATE_PLANNED)
                FileOperationStaging.atomicMove(source, staged.toPath(), replaceExisting = false)
                writeDeleteManifest(operationRoot, entries, FileOperationStaging.STATE_STAGED)
                onProgress(source.fileName.toString())
            }
            val root = operationRoot
                ?: return@withContext Result.success(StagedDeletion("", emptyList()))
            // STOPS HERE. Upstream's STATE_COMMITTED write and the two deleteRecursively calls that
            // follow it are the second half, and they live in finalizeDelete().
            val deletion = StagedDeletion(root.absolutePath, entries.toList())
            synchronized(held) { held[deletion.operationRootPath] = deletion }
            Result.success(deletion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(PROVIDER, RepositoryOperation.DELETE))
        }
    }

    /**
     * **Put the files back.** The queue entry's Undo button.
     *
     * Restores each staged payload to its original path, then discards the journal. Returns how many
     * paths were restored. A no-op returning 0 for a deletion already undone or finalised — idempotent,
     * because a queue entry can be pressed twice and the second press must not fail loudly.
     *
     * Undo returns the player's file; it never overwrites one they made afterwards. If something else has the
     * original name by now, the item is restored under the first free Keep-both name beside it (`name (copy).ext`).
     * And staging is discarded only when everything landed: an entry that could not be put back leaves the delete
     * HELD, exactly as it was, and the result is a failure the caller must read -- the staged copy is the only one.
     */
    suspend fun undoDelete(deletion: StagedDeletion): Result<Int> = withContext(Dispatchers.IO) {
        // Live for the whole call: between leaving the held map and landing the files, a sweep of the same folder must
        // not read this journal as an abandoned one and destroy the payload.
        FileOperationStaging.markLive(File(deletion.operationRootPath))
        try {
            synchronized(held) { held.remove(deletion.operationRootPath) }
                ?: return@withContext Result.success(0)
            var restored = 0
            var unsettled = 0
            deletion.entries.forEach { entry ->
                val staged = File(entry.stagedPath)
                // Nothing left in staging for this entry: an earlier Undo put it back.
                if (!Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                val landing = restoreTarget(File(entry.originalPath))
                val landed = landing != null && runCatching {
                    landing.parentFile?.mkdirs()
                    FileOperationStaging.atomicMove(staged.toPath(), landing.toPath(), replaceExisting = false)
                }.isSuccess
                if (landed) restored++ else unsettled++
            }
            if (unsettled > 0) {
                // Something is still in staging, so the delete is held again exactly as it was and staging is left
                // alone: it is the only copy. Entries that did land have no payload left, so a second Undo skips them.
                synchronized(held) { held[deletion.operationRootPath] = deletion }
                return@withContext Result.failure(FileRefusedException(UNDO_INCOMPLETE))
            }
            FileOperationStaging.deleteRecursively(File(deletion.operationRootPath))
            FileOperationStaging.pruneRootOf(File(deletion.operationRootPath))
            Result.success(restored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(PROVIDER, RepositoryOperation.DELETE))
        } finally {
            FileOperationStaging.unmarkLive(File(deletion.operationRootPath))
        }
    }

    /**
     * **Release the Undo and make the delete permanent.** Upstream `deleteFiles`' second half.
     *
     * Writes [FileOperationStaging.STATE_COMMITTED] BEFORE discarding the payload, deliberately: a crash
     * between the two leaves a journal that [FileOperationStaging.recoverRollback] reads as committed and
     * cleans up, rather than one it reads as staged and resurrects. Idempotent, same reason as
     * [undoDelete].
     */
    suspend fun finalizeDelete(deletion: StagedDeletion): Result<Int> = withContext(Dispatchers.IO) {
        try {
            synchronized(held) { held.remove(deletion.operationRootPath) }
                ?: return@withContext Result.success(0)
            val root = File(deletion.operationRootPath)
            if (root.isDirectory) {
                writeDeleteManifest(root, deletion.entries, FileOperationStaging.STATE_COMMITTED)
            }
            deletion.entries.forEach { FileOperationStaging.deleteRecursively(File(it.stagedPath)) }
            FileOperationStaging.deleteRecursively(root)
            FileOperationStaging.pruneRootOf(root)
            Result.success(deletion.entries.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.asRepositoryException(PROVIDER, RepositoryOperation.DELETE))
        }
    }

    /**
     * Reconcile journals abandoned by a previous process under [directory] — the session-start sweep.
     *
     * Passes [heldRoots] as well, so calling it while this session holds an Undo cannot resurrect that
     * session's own deletion.
     */
    suspend fun recoverInterrupted(directory: File): Int = withContext(Dispatchers.IO) {
        runCatching { FileOperationStaging.recoverOrphaned(directory, heldRoots()) }.getOrDefault(0)
    }

    // -- Internal helpers, ported from upstream --

    private fun requireDestination(destination: String, verb: String): File {
        val directory = Paths.get(destination).toFile().canonicalFile
        if (FileOperationStaging.isInsideStaging(directory.path)) {
            throw IOException(
                "$verb destination ${FileOperationStaging.ROOT_DIRECTORY} ${FileFailureReasons.RESERVED_DESTINATION_MESSAGE}: $destination",
            )
        }
        if (!directory.isDirectory) throw IOException("$verb destination is not a directory: $destination")
        return directory
    }

    /** Deviation 1: ASK plus a real collision is a refusal, never a silent rename. */
    private suspend fun refuseUnresolvedConflicts(
        sources: List<String>,
        destination: String,
        resolution: ConflictResolution,
    ) {
        if (resolution != ConflictResolution.ASK) return
        val conflicts = conflictsFor(sources, destination)
        if (conflicts.isNotEmpty()) throw FileOperationConflictException(conflicts)
    }

    private fun writeDeleteManifest(operationRoot: File, entries: List<StagedDeletionEntry>, state: String) {
        FileOperationStaging.writeManifest(
            operationRoot,
            StagingManifest(
                operation = DELETE_OPERATION,
                recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                entries = entries.map { StagedEntry(it.originalPath, it.stagedPath, state = state) },
            ),
        )
    }

    /**
     * Upstream `resolveTransferTarget` (clone lines 285-299), with [ConflictResolution.ASK] removed from
     * the RENAME branch — deviation 1. Reaching this function with ASK and an existing target is a bug
     * in the caller, which [refuseUnresolvedConflicts] has already made impossible for [copy]/[move].
     */
    private fun resolveTransferTarget(
        target: Path,
        resolution: ConflictResolution,
    ): Path? = when {
        !Files.exists(target) -> target
        resolution == ConflictResolution.SKIP -> null
        // Keep both: `name (copy).ext`, then `name (copy 2).ext` (no hash in the file name).
        resolution == ConflictResolution.RENAME -> resolveConflict(target)
        resolution == ConflictResolution.ASK -> throw FileOperationConflictException(
            listOf(FileConflict(sourcePath = target.toString(), targetPath = target.toString())),
        )
        else -> target
    }

    private fun copyRecursive(
        source: Path,
        target: Path,
        resolution: ConflictResolution,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (Long, Long, String) -> Unit,
        onAccounted: (Long, Long, String) -> Unit,
        checkCancelled: () -> Unit,
    ): Long {
        var transferred = startBytes
        // Every file and folder of a tree is a stop, so a cancel mid-way through a big folder does not wait for it.
        checkCancelled()
        if (Files.isDirectory(source)) {
            if (!Files.exists(target)) Files.createDirectories(target)
            Files.newDirectoryStream(source).use { stream ->
                for (entry in stream) {
                    transferred = copyRecursive(
                        entry, target.resolve(entry.fileName), resolution, transferred, totalBytes,
                        onProgress, onAccounted, checkCancelled,
                    )
                }
            }
        } else {
            // SKIP/RENAME below are unreachable through the only live caller of this function --
            // stageAndCommitCopy always passes ConflictResolution.OVERWRITE (conflicts are resolved one
            // level up, before staging starts) -- so they are left wired to onProgress, unchanged, rather
            // than threaded onto a distinction that can never observe them exercised.
            val options = when (resolution) {
                ConflictResolution.OVERWRITE -> arrayOf(StandardCopyOption.REPLACE_EXISTING)
                ConflictResolution.SKIP -> {
                    if (Files.exists(target)) return transferred + Files.size(source)
                    emptyArray()
                }
                ConflictResolution.RENAME -> {
                    val finalTarget = resolveConflict(target)
                    return copyOneFile(source, finalTarget, false, transferred, totalBytes, onProgress, onAccounted, checkCancelled)
                }
                else -> emptyArray()
            }
            transferred = copyOneFile(
                source = source,
                target = target,
                replaceExisting = options.isNotEmpty(),
                startBytes = transferred,
                totalBytes = totalBytes,
                onProgress = onProgress,
                onAccounted = onAccounted,
                checkCancelled = checkCancelled,
            )
        }
        return transferred
    }

    /**
     * Copy ONE file, reporting progress **as it goes**.
     *
     *  It was
     * a stale graphic, and this is why. `Files.copy` transfers a whole file inside a single blocking call,
     * so the old code could only report progress **between** files. The queue's percentage and bar are
     * both honest about what they are given — [FileOperationQueue.chipLabel] renders a real
     * `Running.fraction` and the store really does call back — but on the dominant case in this product,
     * **one large ROM or one disc image, the fraction went 0 -> 1 with nothing in between.** The player
     * watched "0%" for the entire transfer and then saw "Done". Nothing was faked; nothing was sampled
     * either.
     *
     * Small files keep the old path deliberately. Below [StreamedCopyThresholdBytes] a single
     * `Files.copy` is faster (it can use a platform transfer) and there is no staleness worth fixing —
     * a file that copies in one frame cannot show a stale bar. Streaming every 4 KB icon would trade a
     * real speed loss for an invisible gain.
     *
     * **Emissions are throttled, not per-chunk**: every callback is a `setState` -> StateFlow ->
     * recomposition, and the bar is a `fillMaxWidth(fraction)`, so an un-throttled loop would put a
     * layout pass on the UI thread for every 64 KB moved. The step is one percent of the whole transfer
     * or [ProgressStepMinBytes], whichever is larger, which bounds a transfer of any size at ~100 updates.
     *
     * **Attribute parity is the trap here** and is handled explicitly: `Files.copy` was carrying
     * `COPY_ATTRIBUTES`, and a hand-rolled stream carries nothing. The modification time is restored
     * below. It is restored on a best-effort basis because many Android external-storage mounts
     * (sdcardfs/FUSE) reject `setLastModifiedTime` outright, and a transfer that SUCCEEDED must not be
     * reported as failed because a timestamp would not stick.
     *
     * **Partial writes are already safe, and that is not new.** Every byte written here lands in the
     * staging directory that [stageAndCommitCopy] created, so a failure mid-file leaves an orphan that
     * `FileOperationStaging.recoverOrphaned` already sweeps — the destination never sees a half-file.
     * That existing guarantee is what makes streaming defensible on a data path at all.
     *
     * **F-6: the small-file branch below reports through [onAccounted], never [onProgress].** `Files.copy`
     * transfers the whole file inside one blocking call, so this callback can only ever fire once, after
     * the file is already fully written — the same "reports an accounting, not a measurement" shape as a
     * skipped source, just for a different reason (speed, not a player decision). [onProgress] is reserved
     * for the streamed branch below, where the loop is genuinely live and a caller can honestly draw a
     * determinate fraction from it.
     */
    private fun copyOneFile(
        source: Path,
        target: Path,
        replaceExisting: Boolean,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (Long, Long, String) -> Unit,
        onAccounted: (Long, Long, String) -> Unit,
        checkCancelled: () -> Unit,
    ): Long {
        val name = source.fileName.toString()
        val size = Files.size(source)
        if (size < StreamedCopyThresholdBytes) {
            val options = if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            } else {
                arrayOf(StandardCopyOption.COPY_ATTRIBUTES)
            }
            Files.copy(source, target, *options)
            val done = startBytes + size
            onAccounted(done, totalBytes, name)
            return done
        }
        val openOptions = if (replaceExisting) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        } else {
            arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        // One percent of the WHOLE transfer, not of this file: the fraction the player sees is over the
        // whole queue entry, so a step sized to one file would emit at a different rate per file.
        val step = maxOf(ProgressStepMinBytes, totalBytes / 100)
        var written = 0L
        var lastEmitted = startBytes
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(target, *openOptions).use { output ->
                val buffer = ByteArray(CopyBufferBytes)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    val soFar = startBytes + written
                    if (soFar - lastEmitted >= step) {
                        lastEmitted = soFar
                        onProgress(soFar, totalBytes, name)
                    }
                }
                output.flush()
            }
        }
        runCatching { Files.setLastModifiedTime(target, Files.getLastModifiedTime(source)) }
        // Close on the exact total, so the bar cannot finish a byte short of the step boundary -- but only
        // if the loop did not already land there. A file whose size is an exact multiple of the step
        // (every synthetic test file, and plenty of real disc images) would otherwise emit its final
        // value twice, and a duplicate terminal frame is how a progress feed starts looking like a replay.
        val done = startBytes + size
        if (done != lastEmitted) onProgress(done, totalBytes, name)
        return done
    }

    /**
     * Upstream `stageAndCommitCopy` (clone lines 301-380). [onAccounted] is threaded down to
     * [copyOneFile]'s small-file branch, and used directly below for the [stageByMove] branch, which is
     * the other one-shot-completion shape — an atomic rename carries no bytes to observe either.
     */
    private fun stageAndCommitCopy(
        source: Path,
        target: Path,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (Long, Long, String) -> Unit,
        onAccounted: (Long, Long, String) -> Unit,
        moveSource: Boolean,
        replaceTarget: Boolean,
        stageByMove: Boolean = false,
        completionBytes: Long = totalBytes,
        checkCancelled: () -> Unit = {},
    ): File {
        val operationName = if (moveSource) "move" else "copy"
        val operation = FileOperationStaging.createOperation(
            baseDirectory = target.parent.toFile(),
            operation = operationName,
            recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
            // Live from the moment it exists: a folder this job is landing in cannot be renamed or moved from
            // under it, and another job's start-up sweep cannot read this journal as an abandoned one.
            live = true,
        )
        try {
            return commitStaged(
                operation, operationName, source, target, startBytes, totalBytes,
                onProgress, onAccounted, moveSource, stageByMove, completionBytes, checkCancelled, replaceTarget,
            )
        } catch (error: Throwable) {
            // Whatever stopped the job -- a failure or a cancellation -- its staging folder goes with it,
            // rolled back through its own journal, instead of waiting for some later sweep to find it
            // (E2 O1: the folder was left behind after every job). A copy's half-written staged file
            // is discarded here; a move's source is put back if the move had gone further than staging.
            runCatching { FileOperationStaging.rollbackOperation(operation) }
            throw error
        } finally {
            FileOperationStaging.unmarkLive(operation)
        }
    }

    /**
     * [replaceTarget] is the PLAN: true only when the caller resolved a collision as Replace. The disk is not asked.
     * A target that is there now but was not planned belongs to someone else -- another job's Keep-both name --
     * and the move below then fails and rolls back instead of backing it up and deleting it.
     */
    internal fun commitStaged(
        operation: File,
        operationName: String,
        source: Path,
        target: Path,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (Long, Long, String) -> Unit,
        onAccounted: (Long, Long, String) -> Unit,
        moveSource: Boolean,
        stageByMove: Boolean,
        completionBytes: Long,
        checkCancelled: () -> Unit,
        replaceTarget: Boolean,
    ): File {
        val staged = operation.toPath().resolve("payload").resolve(target.fileName).toFile()
        val backup = operation.toPath().resolve("backup").resolve(target.fileName).toFile()
        val entries = mutableListOf(
            StagedEntry(
                originalPath = source.toString(),
                stagedPath = staged.toString(),
                committedPath = target.toString(),
            ),
        )
        if (!moveSource && Files.exists(target) && source.normalize() == target.normalize()) {
            throw IOException("Cannot copy a file over itself")
        }
        if (replaceTarget) {
            entries += StagedEntry(originalPath = target.toString(), stagedPath = backup.toString())
        }
        fun manifest() = StagingManifest(operationName, StagingRecoveryPolicy.ROLLBACK, entries.toList())

        FileOperationStaging.writeManifest(operation, manifest())
        staged.parentFile?.mkdirs()
        if (stageByMove) {
            checkCancelled()
            FileOperationStaging.atomicMove(source, staged.toPath(), replaceExisting = false)
        } else {
            copyRecursive(
                source, staged.toPath(), ConflictResolution.OVERWRITE, startBytes, totalBytes,
                onProgress, onAccounted, checkCancelled,
            )
            forceTree(staged.toPath())
        }
        entries[0] = entries[0].copy(state = FileOperationStaging.STATE_STAGED)
        FileOperationStaging.writeManifest(operation, manifest())

        // THE last stop. A cancel pressed any time up to here rolls back through the journal (a copy's staged
        // payload is discarded; a same-folder move's source is put back where it was). From the next line the
        // item is committed to the destination and there is no checkpoint, so a move is whole or undone -- the
        // source is never left half-gone.
        checkCancelled()

        // Only a planned Replace may move the target aside. A target that was free when this was planned but is
        // taken now belongs to someone else (a second job picked the same `name (copy)`): the move below then
        // fails, and the rollback puts our own item back and leaves theirs alone.
        if (entries.size > 1 && Files.exists(target)) {
            backup.parentFile?.mkdirs()
            FileOperationStaging.atomicMove(target, backup.toPath(), replaceExisting = false)
            entries[1] = entries[1].copy(state = FileOperationStaging.STATE_BACKUP_MOVED)
            FileOperationStaging.writeManifest(operation, manifest())
        }

        FileOperationStaging.atomicMove(staged.toPath(), target, replaceExisting = false)
        entries[0] = entries[0].copy(state = FileOperationStaging.STATE_TARGET_COMMITTED)
        FileOperationStaging.writeManifest(operation, manifest())
        if (moveSource && !stageByMove) deleteRecursive(source)
        // Accounted, not observed: an atomic rename moves no bytes a caller could have watched, so this
        // is the SAME shape as a skipped source or a small-file Files.copy, reported through the same
        // channel (F-6).
        if (stageByMove) onAccounted(completionBytes, totalBytes, source.fileName.toString())
        entries.indices.forEach { index -> entries[index] = entries[index].copy(state = FileOperationStaging.STATE_COMMITTED) }
        FileOperationStaging.writeManifest(operation, manifest())
        entries.drop(1).forEach { entry -> FileOperationStaging.deleteRecursively(File(entry.stagedPath)) }
        return operation
    }

    private fun forceTree(path: Path) {
        if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path).use { stream -> stream.forEach { forceTree(it) } }
        } else {
            FileOperationStaging.forceFile(path.toFile())
        }
        FileOperationStaging.forceDirectory(path.toFile().parentFile ?: path.toFile())
    }

    private fun deleteRecursive(path: Path) {
        if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path).use { stream -> for (entry in stream) deleteRecursive(entry) }
        }
        Files.deleteIfExists(path)
    }

    /**
     * Where Undo puts a deleted item back: its own name, or -- when something else has taken that name since
     * (a file renamed or created there after the delete) -- the first free Keep-both name beside it. Undo
     * returns the player's file; it neither overwrites what is there now nor throws the staged copy away.
     * Null only when every Keep-both name is taken as well.
     */
    private fun restoreTarget(original: File): File? {
        val path = original.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return original
        val free = ConflictNamePolicy.firstFree(original.name) {
            val candidate = path.resolveSibling(it)
            Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || nameIsHeld(candidate)
        } ?: return null
        return path.resolveSibling(free).toFile()
    }

    private fun resolveConflict(target: Path): Path {
        if (!Files.exists(target)) return target
        val name = target.fileName.toString()
        // A name a held delete owns is taken too: Keep both must not hand it to something new.
        val free = ConflictNamePolicy.firstFree(name) {
            Files.exists(target.resolveSibling(it)) || nameIsHeld(target.resolveSibling(it))
        }
            ?: throw FileRefusedException("All the (copy) names for $name are already taken, so both can't be kept.")
        return target.resolveSibling(free)
    }

    internal companion object {
        /**
         * Must stay `"delete"`: [FileOperationStaging.recoverRollback] matches this exact literal
         * (`manifest.operation in setOf("delete", "move")`) to decide that a [FileOperationStaging.STATE_STAGED]
         * entry should be restored to its original path rather than discarded. Change it and a crash
         * during a held Undo stops putting the player's files back.
         */
        const val DELETE_OPERATION = "delete"

        /**
         * Live held deletes, keyed by [StagedDeletion.operationRootPath].
         *
         * **PROCESS-level, not instance-level, and that is a safety fix rather than a convenience.**
         * `heldRoots()` feeds every [FileOperationStaging.recoverOrphaned] call, which is what stops a
         * held Undo from being resurrected mid-session. An instance-level map reopens exactly that hole
         * through a timing window: `FileBrowserStore.clearSession` finalises held deletes
         * *asynchronously* and drops its instance immediately, so a player who closes the manager and
         * reopens it before that lands gets a FRESH instance with an empty map — whose first sweep of
         * the same parent directory sees the previous session's still-STAGED journal, reads it as an
         * interrupted delete, and puts the files back with no player action.
         *
         * Process-level matches the store's own ownership model: `FileBrowserStore` is an `object` whose
         * extraction scope is already process-lifetime for the same "must outlive its triggering
         * surface" reason. Entries are removed by [undoDelete]/[finalizeDelete], both keyed by root
         * path and both idempotent, so nothing accumulates. Guarded because the store's queue callbacks
         * land on Main while the operations themselves run on IO.
         */
        val held = LinkedHashMap<String, StagedDeletion>()

        /** The operation roots of the deletes held for Undo -- what every sweep of a folder must leave alone. */
        fun heldRootPaths(): Set<String> = synchronized(held) { held.keys.toSet() }

        /** What a refused name says. One sentence for rename, copy, move and extract alike. */
        const val NAME_WAITING_ON_UNDO = "That name is waiting on an Undo. Try again in a moment."

        /**
         * Whether a delete still held for Undo owns the name at [path]: it was deleted from exactly there and its
         * payload is still in staging. Undo will put that file back at that name, so nothing new may take it.
         * Compared as paths, so `x.gba` in `gb` is not `x.gba` in `gba`.
         */
        fun nameIsHeld(path: Path): Boolean {
            val deletions = synchronized(held) { held.values.toList() }
            if (deletions.isEmpty()) return false
            val wanted = runCatching { spellings(path) }.getOrNull() ?: return false
            return deletions.any { deletion ->
                deletion.entries.any { entry ->
                    runCatching {
                        Files.exists(Paths.get(entry.stagedPath), LinkOption.NOFOLLOW_LINKS) &&
                            spellings(Paths.get(entry.originalPath)).any { it in wanted }
                    }.getOrDefault(false)
                }
            }
        }

        /**
         * A path as written and with its folder as the filesystem names it. A held delete remembers the path it was
         * made with; copy, move and extract resolve their destination to its canonical form first (a Windows short
         * name, an Android /sdcard link), so the same file can arrive under either spelling. The file itself need
         * not exist -- only its folder is resolved -- because the name being asked about is usually free.
         */
        private fun spellings(path: Path): List<Path> {
            val absolute = path.toAbsolutePath().normalize()
            val name = absolute.fileName ?: return listOf(absolute)
            val real = runCatching { absolute.parent?.toRealPath()?.resolve(name) }.getOrNull()
            return listOfNotNull(absolute, real)
        }

        /** Refuse [path] as the name of something new while a held delete owns it. */
        fun refuseIfNameHeld(path: Path) {
            if (nameIsHeld(path)) throw FileRefusedException(NAME_WAITING_ON_UNDO)
        }

        /** What Undo says when something could not be put back: the delete is still held, so Undo is still there. */
        const val UNDO_INCOMPLETE = "Couldn't put everything back yet. It is still held, so Undo is still there."
    }
}

/**
 * A file that travels with another and must keep that file's name -- see [ManagedFileOperations.move]'s `followers`.
 *
 * @param leader the source this one follows.
 * @param nameFor the file name this one takes, given the path its [leader] landed at.
 */
class MoveFollower(val leader: String, val nameFor: (leaderLanded: String) -> String)

/** One file that already exists where an operation wants to put something. */
data class FileConflict(val sourcePath: String, val targetPath: String) {
    val name: String get() = targetPath.substringAfterLast('/')
}

/**
 * Raised instead of resolving a collision silently — see [ManagedFileOperations]' deviation 1. Carries
 * every colliding path so the queue can raise one *Keep both / Replace / Skip* decision per entry.
 */
class FileOperationConflictException(
    val conflicts: List<FileConflict>,
) : IOException("Destination already has ${conflicts.size} of these names")

