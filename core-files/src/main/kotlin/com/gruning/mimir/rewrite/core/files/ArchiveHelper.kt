package com.gruning.mimir.rewrite.core.files

import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.*
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.data`. Upstream path:
 * `core/data/src/main/java/com/explorer/fileexplorer/core/data/ArchiveHelper.kt`.
 *
 * The extraction layer provides real zip-slip defence and zip-bomb defence, via [ArchiveEntryPathPolicy] /
 * [ArchiveExtractionBudget] / [ArchiveExtractionException] (vendored alongside this file, from
 * upstream's `ArchiveEntryPathPolicy.kt` in the same upstream directory).
 *
 * De-Hilted: upstream was `@Singleton class ArchiveHelper @Inject constructor()`. Mimir has no DI
 * framework; the `@Singleton`/`@Inject` annotations and their `javax.inject` imports are removed.
 * The constructor took no parameters, so this is a plain, zero-argument `class ArchiveHelper`.
 *
 * Cross-module imports collapse to same-package: upstream imported [FileOperationStaging],
 * [StagedEntry], [StagingManifest], [StagingRecoveryPolicy] from `core/storage` and [FileItem]
 * from `core/model`; all four are already vendored into this same package (slices 1-2).
 *
 * The supported-format policy is enforced here, at the extraction call site (see `FileItem.kt`'s `ARCHIVE_EXTENSIONS` comment):
 *
 *  - **RAR: DELETED, not disabled.** Upstream's RAR path used `com.github.junrar`
 *    (NOASSERTION — the UnRAR licence verbatim; forbids reimplementing RAR compression and is not
 *    a normal open licence). Removed entirely: `listRarEntries()`, `extractRar()`, the
 *    `FileHeader.toFileItem()` extension, the `com.github.junrar.*` imports, the `ext == "rar"`
 *    branches in `listArchive()` / `extract()`, and `"rar"` from `isArchive()`'s recognised-format
 *    set. Upstream's `isArchive()` returned true for `.rar` (recognised-but-read-only); with the
 *    read path gone entirely, keeping that would falsely advertise a format this class can no
 *    longer act on at all. `junrar` is not a dependency of `:core-files` — see `build.gradle.kts`.
 *
 *  - **7z: KEPT, fully functional.** Upstream's 7z support is `commons-compress`'s own `sevenz`
 *    package (`SevenZFile` / `SevenZOutputFile`), which is Apache-2.0 — it is **not**
 *    `sevenzipjbinding`. **Correction to the adoption spec's § 3 table**, verified independently by
 *    grep across the *entire* upstream clone (not just this file, not just `core/data`):
 *    `sevenzipjbinding` is never imported by any `.kt` file anywhere in the repository, and its
 *    `gradle/libs.versions.toml` version pin (`sevenzipjbinding = "16.02-2.01"`) is never wired to
 *    a `[libraries]` entry or to any `build.gradle.kts` dependency — including `core/data`'s own,
 *    which names only `commons-compress`, `zip4j`, `junrar`, `xz`, `zstd-jni`. It is a dead,
 *    orphaned catalog entry. There was no sevenzipjbinding code path in this file to delete, and
 *    none is added here or in `:core-files`'s `build.gradle.kts`. Already-vendored
 *    `RepositoryCapabilityMatrix.archiveAssessment()` (slice 1) and the retired (pre-fork)
 *    `BrowserListing.EXTRACTABLE_EXTENSIONS` in `core-storage` both already treat 7z as
 *    offerable/extractable and only special-case `.rar` — this file matches that precedent.
 *
 *    "Fully functional" is true only because `org.tukaani:xz` is vendored (next bullet) — a
 *    second, more consequential correction found the same way as the first: not by reading, but by
 *    actually running the ported test suite.
 *
 *  - **TAR / GZIP / BZIP2: KEPT, fully functional** — pure `commons-compress`, no extra dependency.
 *
 *  - **XZ: an additional dependency established by runtime verification.** The
 *    initial dependency list included only zip4j and commons-compress. A real
 *    JUnit run — not a reading — proved that is not enough: `commons-compress`'s
 *    `sevenz.Coders` class touches `org.tukaani.xz.FilterOptions` in its own static initializer for
 *    *any* `SevenZFile`/`SevenZOutputFile` use, so every 7z read or write throws
 *    `NoClassDefFoundError` without `org.tukaani:xz` on the classpath — not merely an edge case
 *    for `.xz`/`.txz` TAR files, which is where this was first (wrongly) assumed to be scoped.
 *    `org.tukaani:xz` (0BSD, verified from its own POM) is therefore vendored, and
 *    `openTarInputStream()`'s `"xz", "txz"` branch is restored to upstream's call, unmodified —
 *    its original "codec not vendored" refusal rationale evaporated the moment the dependency was
 *    added, so keeping the throw would have been a pointless deviation with a now-false comment.
 *    Upstream's own `core/data/build.gradle.kts` already declares `implementation(libs.xz)`
 *    unconditionally (not as an optional extra), confirming this was a real requirement, not an
 *    upstream oversight this fork introduced. `.txz` extraction is restored per upstream but is not
 *    exercised by any ported test (only `.tar.gz`, via [ArchiveFormat.TAR_GZ], is).
 *
 *  - **Zstandard (`.zst`): branch kept, codec refused.** `isTarCompressed()` / `isArchive()` still
 *    recognise `.zst` unmodified, but `openTarInputStream()`'s Zstandard case throws
 *    [ArchiveExtractionException] instead of calling into `commons-compress`'s codec factory.
 *    MIMIR DEVIATION from upstream: that factory needs the OPTIONAL `com.github.luben:zstd-jni`
 *    artifact (`optional=true` in `commons-compress`'s own POM, so Gradle does not pull it
 *    transitively) on the runtime classpath, which this slice does not vendor — nothing in the kept
 *    test surface exercises `.zst`, unlike XZ/7z above, so there was no empirical pressure to add
 *    it. Left calling upstream's factory unmodified, a `.zst` file would throw
 *    `NoClassDefFoundError` — a JVM `Error`, not an `Exception` — which escapes `extract()`'s
 *    `catch (e: Exception)` and crashes the caller instead of returning `Result.failure`. Refused
 *    explicitly instead. OWED: add `zstd-jni` (BSD-2-Clause, permissive, but not licence-reviewed
 *    for this project yet) or drop `.zst` support outright, in a future slice.
 *
 *  - **`extract()`'s unsupported-format branch now throws instead of silently "succeeding."**
 *    Upstream's `when` fell through every unrecognised extension to `else -> 0` —
 *    `Result.success(0)`, indistinguishable from "extracted an archive that happened to be empty."
 *    That now includes `.rar` (deleted above) as well as every other genuinely-unrecognised
 *    extension. MIMIR DEVIATION: that branch now throws [ArchiveExtractionException] naming the
 *    extension, so an unsupported format fails honestly instead of masquerading as a successful
 *    no-op — the same must-consume principle `tools/mimir_ui_invariants_gate.py` enforces
 *    mechanically over `feature-*`, applied by hand here since this file is `core-files`, outside
 *    that gate's scan scope. `listArchive()`'s parallel `else -> emptyList()` is left as upstream
 *    wrote it: not `Result`-wrapped, a weaker claim ("nothing visible" vs. "operation succeeded"),
 *    and upstream already returns an empty listing for every unrecognised format — not a behaviour
 *    newly introduced by the RAR removal.
 *
 *  - **Collisions raise the queue's conflict card instead of overwriting in silence (D-1, policy
 *    2026-08-15).** Upstream's commit loop backed an existing target up, moved the archive's entry in,
 *    and then deleted the backup — permanent, unasked, and the only one of the three transfer verbs
 *    that behaved that way. [extract] now takes the same [ConflictResolution] answer
 *    [ManagedFileOperations.copy] and [ManagedFileOperations.move] take, defaulting to
 *    [ConflictResolution.ASK], which refuses with [FileOperationConflictException]. Backups also now
 *    outlive the commit loop so an interruption mid-way is recoverable. **All of it lives in
 *    [commitStagedExtraction] — read its KDoc, not this bullet, for the rationale and the disclosed
 *    costs.**
 *
 * minSdk 30 here vs. upstream's 26: no `Build.VERSION` branches exist in this file or in
 * `ArchiveEntryPathPolicy.kt`, so there is no API 26-29 dead code to delete in this slice.
 *
 * No network surface: every backend here (zip4j, commons-compress, org.tukaani:xz) operates on
 * local `File` paths only; nothing in this file performs I/O beyond the local filesystem.
 *
 * Handles archive browsing (as virtual folders), extraction, and creation.
 * Supports: ZIP (via zip4j for encryption), TAR, GZ, BZ2, 7z. RAR is not supported — see above.
 */
class ArchiveHelper {
    /** List entries in an archive at a given internal path. */
    suspend fun listArchive(
        archivePath: String,
        internalPath: String = "",
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val ext = archivePath.substringAfterLast('.').lowercase()
        val allEntries = when {
            ext == "zip" || ext == "jar" || ext == "war" || ext == "ear" -> listZipEntries(archivePath)
            ext == "7z" -> list7zEntries(archivePath)
            ext in setOf("tar", "tgz", "tbz2", "txz") || isTarCompressed(archivePath) -> listTarEntries(archivePath)
            else -> emptyList()
        }

        // Filter to show only entries at the requested internal path level
        val prefix = if (internalPath.isEmpty()) "" else internalPath.trimEnd('/') + "/"
        val directChildren = mutableMapOf<String, FileItem>()

        for (entry in allEntries) {
            val relativePath = if (prefix.isEmpty()) entry.path else {
                if (!entry.path.startsWith(prefix)) continue
                entry.path.removePrefix(prefix)
            }
            if (relativePath.isEmpty()) continue

            val parts = relativePath.trimEnd('/').split('/')
            val childName = parts.first()

            if (parts.size == 1) {
                // Direct child file or empty directory
                directChildren[childName] = entry.copy(
                    name = childName,
                    path = if (prefix.isEmpty()) childName else "$prefix$childName",
                )
            } else if (childName !in directChildren) {
                // Implicit directory from nested entries
                directChildren[childName] = FileItem(
                    name = childName,
                    path = if (prefix.isEmpty()) childName else "$prefix$childName",
                    isDirectory = true,
                    mimeType = "inode/directory",
                )
            }
        }

        directChildren.values.toList().sortedWith(
            compareBy<FileItem> { if (it.isDirectory) 0 else 1 }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * Extract specific entries or all entries from an archive.
     *
     * @param conflictResolution what happens when an extracted entry's name is **already taken** in
     *   [destination]. Defaults to [ConflictResolution.ASK], which **refuses**: nothing is overwritten,
     *   nothing is written at all, and the call fails with [FileOperationConflictException] carrying
     *   every colliding path so the queue can raise one *Keep both / Replace / Skip* card. See
     *   [commitStagedExtraction] for the full conflict rationale and for what each answer does. *Keep both*
     *   names the arriving file `name (copy).ext`, then `name (copy 2).ext` ([ConflictNamePolicy]).
     * @param onProgress bytes unpacked into staging so far. **Staging is only the first half of the job**:
     *   when it reaches its total, every file is still waiting in a hidden folder and none is where the
     *   player asked for it. Do not draw that as "100%".
     * @param onPlacing the second half, reported as work that has demonstrably happened: `placed` files of
     *   `total` have been moved into the destination (called once with `placed = 0` when the move
     *   starts, then once per file). Observed: the card read 100% for over 90 seconds while about
     *   1,400 of 2,000 files were still waiting to be placed.
     * @param onCancelled called once, only when the job is cancelled, with what taking back its published files
     *   found ([ExtractionCancelOutcome]). The cancellation itself is then rethrown unchanged.
     *
     * The returned count is unchanged by conflict handling: it is the number of archive entries read,
     * and a [ConflictResolution.SKIP]ped entry still counts as handled — the same accounting
     * [ManagedFileOperations.copy] uses for its own SKIP branch, so a queue's progress does not stall
     * on a decision the player already made.
     *
     * **Whatever ends the job leaves nothing of Mimir's behind**: success, failure and cancellation all
     * remove the operation's staging folder, and the (now empty) `.mimir-staging` folder with it. A
     * destination folder this call created and could not fill is removed too, if it is empty. A process
     * that dies mid-job cannot run any of that, which is what [InterruptedExtractions] is for.
     */
    suspend fun extract(
        archivePath: String,
        destination: String,
        entriesToExtract: List<String>? = null, // null = extract all
        password: CharArray? = null,
        conflictResolution: ConflictResolution = ConflictResolution.ASK,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        onPlacing: (placed: Int, total: Int) -> Unit = { _, _ -> },
        onCancelled: (ExtractionCancelOutcome) -> Unit = {},
    ): Result<Int> = withContext(Dispatchers.IO) {
        val archive = File(archivePath)
        val ext = archivePath.substringAfterLast('.').lowercase()
        var destinationRoot: File? = null
        var createdDestination = false
        var operationRoot: File? = null
        var ticket: InterruptedExtractions.Ticket? = null
        var committed = false
        try {
            // E2 O5: a zero-byte file is not an archive. Nothing has been created yet, so nothing needs
            // undoing -- not even the destination folder the next lines would have made.
            if (archive.isFile && archive.length() == 0L) {
                throw ArchiveExtractionException("Empty archive file: $archivePath", ArchiveFailureKind.EMPTY_FILE)
            }
            requireNotStaging(destination)
            createdDestination = !File(destination).exists()
            val prepared = prepareDestination(destination)
            destinationRoot = prepared
            FileOperationStaging.recoverOrphaned(prepared, ManagedFileOperations.heldRootPaths())
            val root = FileOperationStaging.createOperation(
                baseDirectory = prepared,
                operation = InterruptedExtractions.OPERATION,
                recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                live = true,
            )
            operationRoot = root
            ticket = InterruptedExtractions.begin(archivePath, prepared, root)
            val stagingRoot = root.resolve("payload").apply { mkdirs() }
            val extractedCount = when {
                ext == "zip" || ext == "jar" -> extractZip(
                    archivePath,
                    stagingRoot,
                    entriesToExtract,
                    password,
                    onProgress,
                )
                ext == "7z" -> extract7z(archivePath, stagingRoot, entriesToExtract, onProgress)
                ext in setOf("tar", "tgz", "tbz2", "txz") || isTarCompressed(archivePath) ->
                    extractTar(archivePath, stagingRoot, entriesToExtract, onProgress)
                // MIMIR DEVIATION from upstream: upstream's else branch silently returned 0 --
                // a "success" indistinguishable from extracting an archive that happened to be
                // empty. This now covers `.rar` (RAR support deleted by the supported-format policy -- see this
                // file's header) as well as every other genuinely-unrecognised extension. See
                // the header comment for the full reasoning.
                else -> throw ArchiveExtractionException(
                    "Unsupported archive format: .$ext",
                    ArchiveFailureKind.UNSUPPORTED,
                )
            }
            commitStagedExtraction(
                stagingRoot = stagingRoot,
                destinationRoot = prepared,
                operationRoot = root,
                resolution = conflictResolution,
                onPlacing = onPlacing,
                checkCancelled = { ensureActive() },
            )
            committed = true
            Result.success(extractedCount)
        } catch (e: CancellationException) {
            // The player pressed Cancel (or the scope went away). Take back what THIS job published -- its own
            // journal is the list -- and report what the rollback found through [onCancelled] before rethrowing, so
            // the card can describe the disk as it now is. Plain blocking code, so it runs fine on a cancelled job.
            val rolledBack = operationRoot?.let { runCatching { FileOperationStaging.rollbackPublished(it) }.getOrNull() }
            onCancelled(
                ExtractionCancelOutcome(
                    removed = rolledBack?.removed ?: 0,
                    remaining = rolledBack?.remaining ?: 0,
                    folder = destinationRoot?.name,
                ),
            )
            throw e
        } catch (e: Exception) {
            val classified = ArchiveFailureClassifier.classify(e, archive, ext)
            // Failed AFTER some files were already in place: the rollback leaves those where they are
            // (it never deletes what a finished placement wrote), so the failure has to say so. Read from
            // the journal, before the rollback below: an entry that replaced an existing file is put back.
            val (keptInPlace, plannedTotal) = InterruptedExtractions.keptAndPlanned(
                operationRoot?.let(FileOperationStaging::readManifest),
            )
            val reported = if (keptInPlace > 0 && classified !is FileOperationConflictException) {
                ArchiveExtractionException(
                    classified.message ?: "Extraction stopped part-way",
                    ArchiveFailureKind.PARTIAL,
                    classified,
                    placed = keptInPlace,
                    total = plannedTotal,
                    folder = destinationRoot?.name,
                )
            } else {
                classified
            }
            Result.failure(reported)
        } finally {
            val root = operationRoot
            if (committed) {
                // The pointer first: a process that dies between these two steps leaves a journal whose
                // every entry is COMMITTED, which a later sweep reads as "finished" and says nothing about.
                InterruptedExtractions.end(ticket)
                if (root != null) {
                    FileOperationStaging.unmarkLive(root)
                    FileOperationStaging.deleteRecursively(root)
                    FileOperationStaging.pruneRootOf(root)
                }
            } else {
                if (root != null) FileOperationStaging.rollbackOperation(root)
                InterruptedExtractions.end(ticket)
                // `File.delete()` removes a directory only when it is empty, which is the whole point: a
                // folder this call made and put nothing in is a husk; a folder holding anything stays.
                if (createdDestination) destinationRoot?.delete()
            }
        }
    }

    /**
     * Create an archive from files.
     *
     * **It never takes a name something else owns, as every other job refuses to.** Two cases, both checked before
     * anything is written and again just before the commit, because a long compress runs while other jobs finish:
     *
     *  - a delete still holds the name for Undo ([ManagedFileOperations.refuseIfNameHeld]): that file is in staging
     *    and will be put back at exactly this path;
     *  - a file is already there -- another job's output, or the player's own. This used to move it to a backup and
     *    delete the backup on success, so a name collision silently destroyed it. Compress has no Keep both /
     *    Replace / Skip to offer, so it raises the engine's own [FileOperationConflictException] and changes nothing.
     */
    suspend fun createArchive(
        outputPath: String,
        sourcePaths: List<String>,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        password: CharArray? = null,
        compressionLevel: Int = 5,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val output = File(outputPath).canonicalFile
        val outputParent = requireNotNull(output.parentFile)
        val operationRoot = runCatching {
            refuseIfTaken(output)
            FileOperationStaging.recoverOrphaned(outputParent, ManagedFileOperations.heldRootPaths())
            FileOperationStaging.createOperation(
                baseDirectory = outputParent,
                operation = "compress",
                recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
            )
        }.getOrElse { return@withContext Result.failure(it) }
        val stagedOutput = operationRoot.resolve("payload").resolve("${output.name}.part")
        requireNotNull(stagedOutput.parentFile).mkdirs()
        try {
            when (format) {
                ArchiveFormat.ZIP -> createZip(stagedOutput.path, sourcePaths, password, compressionLevel, onProgress)
                ArchiveFormat.SEVEN_Z -> create7z(stagedOutput.path, sourcePaths, compressionLevel, onProgress)
                ArchiveFormat.TAR_GZ -> createTarGz(stagedOutput.path, sourcePaths, onProgress)
            }
            FileOperationStaging.forceFile(stagedOutput)
            refuseIfTaken(output)
            commitStagedFile(stagedOutput, output, operationRoot)
            FileOperationStaging.deleteRecursively(operationRoot)
            Result.success(Unit)
        } catch (e: CancellationException) {
            FileOperationStaging.recoverOrphaned(outputParent, ManagedFileOperations.heldRootPaths())
            throw e
        } catch (e: Exception) {
            FileOperationStaging.recoverOrphaned(outputParent, ManagedFileOperations.heldRootPaths())
            Result.failure(e)
        }
    }

    /** Check if a file is a supported archive. */
    fun isArchive(path: String): Boolean {
        val ext = path.substringAfterLast('.').lowercase()
        // MIMIR DEVIATION from upstream: "rar" removed. Upstream's RAR path was
        // read-only-but-present; ours is deleted entirely (see the supported-format policy in the header comment), so
        // recognising `.rar` here would advertise a format this class can no longer list or
        // extract at all.
        return ext in setOf("zip", "jar", "war", "ear", "7z", "tar", "gz", "tgz",
            "bz2", "tbz2", "xz", "txz", "zst")
    }

    /**
     * A copy INTO `.mimir-staging` was accepted, then swept away by the next recovery pass. The
     * staging folder is Mimir's own working space, not a place for the player's files, so it is refused
     * as a destination -- here, for extraction, and in [ManagedFileOperations] for copy and move.
     */
    private fun requireNotStaging(destination: String) {
        if (FileOperationStaging.isInsideStaging(destination)) {
            throw ArchiveExtractionException(
                "Extraction destination ${FileOperationStaging.ROOT_DIRECTORY} ${FileFailureReasons.RESERVED_DESTINATION_MESSAGE}: $destination",
                ArchiveFailureKind.RESERVED_DESTINATION,
            )
        }
    }

    private fun cannotCreateFolder(folder: File) = ArchiveExtractionException(
        "Unable to create extraction directory: ${folder.path}",
        ArchiveFailureKind.CANNOT_CREATE_FOLDER,
    )

    private fun prepareDestination(destination: String): File {
        val root = File(destination).canonicalFile
        if (root.exists()) {
            if (!root.isDirectory) throw ArchiveExtractionException(
                "Extraction destination is not a directory: $destination",
                ArchiveFailureKind.DESTINATION_NOT_A_FOLDER,
            )
        } else if (!root.mkdirs() && !root.isDirectory) {
            throw ArchiveExtractionException(
                "Unable to create extraction destination: $destination",
                ArchiveFailureKind.CANNOT_CREATE_FOLDER,
            )
        }
        return root
    }

    private fun normalizedSelection(entries: List<String>?): Set<String>? {
        return entries?.map { entry ->
            ArchiveEntryPathPolicy.normalizeEntryName(entry)
                ?: throw ArchiveExtractionException("Invalid requested archive entry: $entry", ArchiveFailureKind.UNSAFE_PATH)
        }?.toSet()
    }

    private fun stagedEntry(
        stagingRoot: File,
        entryName: String,
        declaredSize: Long,
        budget: ArchiveExtractionBudget,
    ): File {
        val normalizedName = ArchiveEntryPathPolicy.normalizeEntryName(entryName)
            ?: throw ArchiveExtractionException("Unsafe archive entry path: $entryName", ArchiveFailureKind.UNSAFE_PATH)
        val output = ArchiveEntryPathPolicy.safeDestination(stagingRoot.path, normalizedName)
            ?: throw ArchiveExtractionException("Unsafe archive entry path: $entryName", ArchiveFailureKind.UNSAFE_PATH)
        budget.register(normalizedName, declaredSize)
        return output
    }

    private suspend fun copyArchiveEntry(
        input: InputStream,
        outputFile: File,
        entryName: String,
        declaredSize: Long,
        budget: ArchiveExtractionBudget,
        onProgress: (Long, Long, String) -> Unit,
        closeInput: Boolean = true,
    ) {
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw cannotCreateFolder(parent)
            }
        }
        FileOutputStream(outputFile).use { fileOutput ->
            val boundedOutput = BoundedArchiveOutputStream(
                delegate = fileOutput,
                entryName = entryName,
                declaredSize = declaredSize,
                budget = budget,
                coroutineContext = coroutineContext,
                onProgress = onProgress,
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val copy: suspend () -> Unit = {
                while (true) {
                    coroutineContext.ensureActive()
                    val length = input.read(buffer)
                    if (length == -1) break
                    boundedOutput.write(buffer, 0, length)
                }
            }
            if (closeInput) input.use { copy() } else copy()
            boundedOutput.finish()
        }
    }

    /** One staged file with the destination path it is actually going to, after conflict handling. */
    private class PlannedCommit(val source: File, val target: File, val backup: Boolean)

    /**
     * Move the staged payload into the real destination — **the only path in this class that can
     * overwrite a file the player already has.** `extract()` calls it unconditionally, so there is no
     * side route around any of this.
     *
     * ## MIMIR CHANGE — D-1, extract asks, exactly like move and copy
     *
     * This function previously silently
     * destroying a ROM the player already had: an existing target was moved to `backup/<index>`, the
     * archive's entry was moved in, and the backup was then deleted — no question asked, no undo, while
     * [ManagedFileOperations.copy] and [ManagedFileOperations.move] both raise *Keep both / Replace /
     * Skip* for the identical situation. Extract was the one verb that overwrote in silence.
     *
     * Extraction uses the same conflict card as Move and Copy, so [resolution] arrives here with the same [ConflictResolution] vocabulary and
     * the same meanings [ManagedFileOperations.resolveTransferTarget] gives it:
     *
     *  - [ConflictResolution.ASK] (the default) — **refuses.** Every collision is collected and thrown
     *    as one [FileOperationConflictException] with the complete list, from **pass A below, before a
     *    single byte under `destinationRoot` is touched**. Same deviation note `ManagedFileOperations`
     *    carries: *ASK plus a real collision is a refusal, never a silent rename.*
     *  - [ConflictResolution.SKIP] — the existing file is left exactly as it is and the staged entry is
     *    discarded with the operation root.
     *  - [ConflictResolution.RENAME] (*Keep both*) — the incoming entry is retargeted through
     *    [ConflictNamePolicy], and the retargeted name is re-checked by
     *    [ArchiveEntryPathPolicy.safeDestination] so a rename can never escape the destination.
     *  - [ConflictResolution.OVERWRITE] (*Replace*) — the pre-existing backup-and-replace, now reached
     *    only because the player pressed *Replace*.
     *
     * ## Why this is two passes, and why the backups now outlive the loop
     *
     * **Pass A validates and plans, and writes nothing under `destinationRoot`.** That ordering is the
     * fix, not a tidy-up: a refusal that had already created directories would leave the empty,
     * game-named husk observed on a card. Nothing — not the directory pass, not a
     * backup, not a commit — happens until every entry has been classified.
     *
     * **Pass B commits, and no backup is deleted until every entry has landed.** Upstream deleted each
     * backup immediately after committing its own entry, which is the second half of D-1: a throw on
     * entry 3 of 7 left entries 1-3 overwritten with their originals *already destroyed*, and
     * [FileOperationStaging.recoverRollback] treats a `STATE_COMMITTED` entry as cleanup-only, so
     * nothing put them back. Holding every backup at [FileOperationStaging.STATE_BACKUP_MOVED] until
     * the loop finishes means an interrupted commit leaves exactly the shape `recoverRollback`'s first
     * phase exists to reverse — it deletes the newly-written file and moves the original back. This
     * covers **process death as well as a throw**: the journal on disk is what the next
     * [FileOperationStaging.recoverOrphaned] sweep reads, so it does not depend on `extract`'s
     * `finally` running at all.
     *
     * The cost, disclosed rather than hidden: peak disk now holds every colliding original until the
     * commit ends, instead of one at a time. That is bounded by the size of the files being replaced,
     * which the extraction budget does **not** cap.
     */
    private fun commitStagedExtraction(
        stagingRoot: File,
        destinationRoot: File,
        operationRoot: File,
        resolution: ConflictResolution,
        onPlacing: (placed: Int, total: Int) -> Unit,
        checkCancelled: () -> Unit = {},
    ) {
        val stagedFiles = stagingRoot.walkTopDown()
            .filter { it != stagingRoot && it.isFile }
            .toList()
            .sortedBy { it.toPath().nameCount }
        val stagedDirectories = stagingRoot.walkTopDown()
            .filter { it != stagingRoot && it.isDirectory }
            .toList()
            .sortedBy { it.toPath().nameCount }

        // ---- Pass A: validate and plan. NOTHING under destinationRoot is written here. ----

        stagedDirectories.forEach { source ->
            if (Files.isSymbolicLink(source.toPath())) {
                throw ArchiveExtractionException("Staged symbolic links are not supported: ${source.name}", ArchiveFailureKind.UNSAFE_PATH)
            }
            val target = destinationTargetFor(stagingRoot, destinationRoot, source)
            if (target.exists() && !target.isDirectory) {
                throw ArchiveExtractionException(
                    "Extraction target is not a directory: ${target.path}",
                    ArchiveFailureKind.FILE_FOLDER_CLASH,
                )
            }
        }

        val planned = mutableListOf<PlannedCommit>()
        val conflicts = mutableListOf<FileConflict>()
        // Every destination path this plan has already spoken for. A Keep-both rename must not land on
        // a name another entry of the SAME extraction is about to create -- Files.exists() cannot see a
        // planned-but-unwritten sibling, so the plan has to remember them itself.
        val claimed = mutableSetOf<String>()
        for (source in stagedFiles) {
            if (Files.isSymbolicLink(source.toPath())) {
                throw ArchiveExtractionException("Staged symbolic links are not supported: ${source.name}", ArchiveFailureKind.UNSAFE_PATH)
            }
            val relativeName = stagingRoot.toPath().relativize(source.toPath()).toString()
            val target = destinationTargetFor(stagingRoot, destinationRoot, source)
            if (Files.isSymbolicLink(target.toPath())) {
                throw ArchiveExtractionException("Refusing to overwrite a symbolic link: ${target.path}", ArchiveFailureKind.UNSAFE_PATH)
            }
            // A directory standing where a file must go is a refusal in every mode, never a conflict
            // card: Replace must never be able to delete a directory tree. Hoisted into pass A so it
            // fires before anything is written, rather than mid-commit.
            if (target.exists() && target.isDirectory) {
                throw ArchiveExtractionException(
                    "Extraction target is a directory: ${target.path}",
                    ArchiveFailureKind.FILE_FOLDER_CLASH,
                )
            }
            if (!target.exists()) {
                // A name a delete held for Undo still owns: Undo will put that file back there.
                ManagedFileOperations.refuseIfNameHeld(target.toPath())
                planned += PlannedCommit(source = source, target = target, backup = false)
                claimed += target.path
                continue
            }
            conflicts += FileConflict(sourcePath = relativeName, targetPath = target.path)
            when (resolution) {
                // Collected, not thrown yet: the queue's card is raised once with the WHOLE list, the
                // same way FileOperationConflictException carries every colliding path for move/copy.
                ConflictResolution.ASK -> Unit
                // The staged payload is simply never committed; it goes away with the operation root.
                ConflictResolution.SKIP -> Unit
                ConflictResolution.OVERWRITE -> {
                    ManagedFileOperations.refuseIfNameHeld(target.toPath())
                    planned += PlannedCommit(source = source, target = target, backup = true)
                    claimed += target.path
                }
                ConflictResolution.RENAME -> {
                    val renamed = keepBothTarget(destinationRoot, relativeName, claimed)
                    planned += PlannedCommit(source = source, target = renamed, backup = false)
                    claimed += renamed.path
                }
            }
        }
        if (resolution == ConflictResolution.ASK && conflicts.isNotEmpty()) {
            throw FileOperationConflictException(conflicts.toList())
        }

        // ---- Pass B: commit. From here on destinationRoot is mutated. ----

        // The last stop before anything is written: a Cancel pressed while unpacking or planning ends here with
        // nothing placed. Between files below there is one more; there is none inside a single file's move.
        checkCancelled()
        // Folders this job is about to make, noted first so a cancel can remove the empty ones again.
        val createdDirectories = linkedSetOf<File>()
        fun noteMissing(directory: File?) {
            var current = directory
            while (current != null && current.path != destinationRoot.path && !current.exists()) {
                createdDirectories += current
                current = current.parentFile
            }
        }
        stagedDirectories.forEach { noteMissing(destinationTargetFor(stagingRoot, destinationRoot, it)) }
        planned.forEach { noteMissing(it.target.parentFile) }
        FileOperationStaging.recordCreatedDirectories(operationRoot, createdDirectories)

        stagedDirectories.forEach { source ->
            val target = destinationTargetFor(stagingRoot, destinationRoot, source)
            if (!target.exists() && !target.mkdirs() && !target.isDirectory) {
                throw cannotCreateFolder(target)
            }
        }

        val journalEntries = planned.map { plan ->
            StagedEntry(originalPath = "", stagedPath = plan.source.path, committedPath = plan.target.path)
        }.toMutableList()
        fun manifest() = StagingManifest(
            operation = "extract",
            recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
            entries = journalEntries.toList(),
        )
        FileOperationStaging.writeManifest(operationRoot, manifest())

        // Backup journal entries are APPENDED, so a planned entry's own index stays valid throughout.
        val backupIndices = mutableListOf<Int>()
        // The placing phase starts here: unpacking is finished and none of these files is where the
        // player asked for it yet. Said once at zero so a card can stop reading as "done unpacking".
        if (planned.isNotEmpty()) onPlacing(0, planned.size)
        for ((index, plan) in planned.withIndex()) {
            // Between files, never inside one: a file is either placed (journal COMMITTED) or not (journal
            // PLANNED), so a stop here leaves the journal exactly describing the disk.
            checkCancelled()
            plan.target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw cannotCreateFolder(parent)
                }
            }
            if (plan.backup) {
                val backup = operationRoot.resolve("backup").resolve(index.toString())
                journalEntries += StagedEntry(
                    originalPath = plan.target.path,
                    stagedPath = backup.path,
                    state = FileOperationStaging.STATE_BACKUP_MOVED,
                )
                backupIndices += journalEntries.lastIndex
                FileOperationStaging.writeManifest(operationRoot, manifest())
                backup.parentFile?.mkdirs()
                FileOperationStaging.atomicMove(plan.target.toPath(), backup.toPath(), replaceExisting = false)
            }
            journalEntries[index] = journalEntries[index].copy(state = FileOperationStaging.STATE_TARGET_COMMITTED)
            FileOperationStaging.writeManifest(operationRoot, manifest())
            try {
                FileOperationStaging.atomicMove(plan.source.toPath(), plan.target.toPath(), replaceExisting = false)
            } catch (error: java.nio.file.FileAlreadyExistsException) {
                // Another job got to this name first (two Keep-both extractions into one folder plan the
                // same `name (copy)`). The move did not happen, so nothing of THIS job stands at the
                // target: say so in the journal BEFORE the rollback reads it, or the rollback deletes the
                // other job's file as if it were ours.
                takeBackClaim(operationRoot, journalEntries, index, ::manifest)
                throw ArchiveExtractionException(
                    "Extraction target was taken while extracting: ${plan.target.path}",
                    ArchiveFailureKind.TARGET_TAKEN,
                    error,
                )
            } catch (error: Throwable) {
                takeBackClaim(operationRoot, journalEntries, index, ::manifest)
                throw error
            }
            journalEntries[index] = journalEntries[index].copy(state = FileOperationStaging.STATE_COMMITTED)
            FileOperationStaging.writeManifest(operationRoot, manifest())
            // NO backup deletion here. See this function's KDoc: every backup stays at
            // STATE_BACKUP_MOVED until the whole loop lands, so an interruption is recoverable.
            // After the journal says COMMITTED, so the count a card shows is never ahead of the disk.
            onPlacing(index + 1, planned.size)
        }

        // Every planned entry is in place. Only now may the originals go.
        if (backupIndices.isNotEmpty()) {
            backupIndices.forEach { backupIndex ->
                journalEntries[backupIndex] =
                    journalEntries[backupIndex].copy(state = FileOperationStaging.STATE_COMMITTED)
            }
            FileOperationStaging.writeManifest(operationRoot, manifest())
            // runCatching: the extraction has fully landed on disk by this point, so a failure to tidy
            // up a backup must not be reported to the caller as a failed extraction. The leftover is
            // inside operationRoot and is removed with it.
            backupIndices.forEach { backupIndex ->
                runCatching { FileOperationStaging.deleteRecursively(File(journalEntries[backupIndex].stagedPath)) }
            }
        }
    }

    /**
     * A placement's move failed after its journal entry already said TARGET_COMMITTED (written first so a
     * crash mid-move is recoverable). The move did not happen, so the entry goes back to PLANNED: the
     * rollback must not treat whatever stands at the target as this job's to delete. Best effort -- the
     * failure being reported is the one that matters, not a second one from the journal write.
     */
    private fun takeBackClaim(
        operationRoot: File,
        entries: MutableList<StagedEntry>,
        index: Int,
        manifest: () -> StagingManifest,
    ) {
        entries[index] = entries[index].copy(state = FileOperationStaging.STATE_PLANNED)
        runCatching { FileOperationStaging.writeManifest(operationRoot, manifest()) }
    }

    private fun destinationTargetFor(stagingRoot: File, destinationRoot: File, source: File): File {
        val relativeName = stagingRoot.toPath().relativize(source.toPath()).toString()
        return ArchiveEntryPathPolicy.safeDestination(destinationRoot.path, relativeName)
            ?: throw ArchiveExtractionException("Unsafe archive entry path: $relativeName", ArchiveFailureKind.UNSAFE_PATH)
    }

    /**
     * *Keep both*: where [relativeName]'s entry goes when its own name is taken.
     *
     * Named by [ConflictNamePolicy] -- `name (copy).ext`, then `name (copy 2).ext` -- the first name that is
     * free, the same way [ManagedFileOperations] names a kept copy of a moved or copied file, so every verb
     * speaks one language. "Free" means free on disk AND not already claimed by another entry of this same
     * extraction ([claimed]): `Files.exists()` cannot see a planned-but-unwritten sibling.
     *
     * Every candidate is rebuilt as a *relative* name and re-run through
     * [ArchiveEntryPathPolicy.safeDestination]: renaming happens after the containment check, so it gets
     * its own.
     */
    private fun keepBothTarget(
        destinationRoot: File,
        relativeName: String,
        claimed: Set<String>,
    ): File {
        val separator = relativeName.lastIndexOfAny(charArrayOf('/', '\\'))
        val parent = if (separator >= 0) relativeName.substring(0, separator + 1) else ""
        val name = if (separator >= 0) relativeName.substring(separator + 1) else relativeName

        fun resolve(candidateName: String): File =
            ArchiveEntryPathPolicy.safeDestination(destinationRoot.path, parent + candidateName)
                ?: throw ArchiveExtractionException(
                    "Unsafe archive entry path: $parent$candidateName",
                    ArchiveFailureKind.UNSAFE_PATH,
                )

        val free = ConflictNamePolicy.firstFree(name) { candidateName ->
            val resolved = resolve(candidateName)
            resolved.exists() || resolved.path in claimed || ManagedFileOperations.nameIsHeld(resolved.toPath())
        } ?: throw ArchiveExtractionException(
            "Unable to keep both copies of $relativeName: every alternative name is taken",
            ArchiveFailureKind.NO_FREE_COPY_NAME,
        )
        return resolve(free)
    }

    private fun refuseIfTaken(output: File) {
        ManagedFileOperations.refuseIfNameHeld(output.toPath())
        if (output.exists()) {
            throw FileOperationConflictException(listOf(FileConflict(sourcePath = output.name, targetPath = output.path)))
        }
    }

    /**
     * Land [staged] at [target], which must be free.
     *
     * **There is no backup branch, because there is nothing to back up.** [refuseIfTaken] has already refused a name
     * that is taken, and a file that appears in the instants after it is not this job's to replace: the move below
     * refuses an existing target (`replaceExisting = false`), and the job fails with a conflict, changing nothing.
     *
     * **The journal claims the target only for the move itself and gives it back when the move is refused.** A
     * `TARGET_COMMITTED` entry tells the rollback "the file at this path is mine, delete it", and the sweep that
     * follows a failed compress runs exactly that rollback -- so a refusal that left the entry claimed would have the
     * sweep delete the very file that made the move refuse.
     */
    internal fun commitStagedFile(staged: File, target: File, operationRoot: File) {
        val entries = mutableListOf(
            StagedEntry(
                originalPath = "",
                stagedPath = staged.path,
                committedPath = target.path,
                state = FileOperationStaging.STATE_STAGED,
            ),
        )
        fun manifest() = StagingManifest(
            operation = "compress",
            recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
            entries = entries.toList(),
        )
        FileOperationStaging.writeManifest(operationRoot, manifest())
        entries[0] = entries[0].copy(state = FileOperationStaging.STATE_TARGET_COMMITTED)
        FileOperationStaging.writeManifest(operationRoot, manifest())
        try {
            FileOperationStaging.atomicMove(staged.toPath(), target.toPath(), replaceExisting = false)
        } catch (e: Exception) {
            entries[0] = entries[0].copy(state = FileOperationStaging.STATE_STAGED)
            runCatching { FileOperationStaging.writeManifest(operationRoot, manifest()) }
            if (e is java.nio.file.FileAlreadyExistsException) {
                throw FileOperationConflictException(listOf(FileConflict(sourcePath = target.name, targetPath = target.path)))
            }
            throw e
        }
        entries[0] = entries[0].copy(state = FileOperationStaging.STATE_COMMITTED)
        FileOperationStaging.writeManifest(operationRoot, manifest())
    }

    private class BoundedArchiveOutputStream(
        private val delegate: OutputStream,
        private val entryName: String,
        private val declaredSize: Long,
        private val budget: ArchiveExtractionBudget,
        private val coroutineContext: CoroutineContext,
        private val onProgress: (Long, Long, String) -> Unit,
    ) : OutputStream() {
        private var entryBytes = 0L

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            coroutineContext.ensureActive()
            if (length == 0) return
            val nextEntryBytes = entryBytes + length
            budget.consume(entryName, nextEntryBytes, declaredSize, length)
            delegate.write(bytes, offset, length)
            entryBytes = nextEntryBytes
            onProgress(budget.writtenBytes, budget.progressTotal, entryName)
        }

        fun finish() {
            budget.finish(entryName, entryBytes, declaredSize)
        }
    }

    private class SevenZEntryInputStream(
        private val archive: SevenZFile,
    ) : InputStream() {
        override fun read(): Int = archive.read()

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = archive.read(bytes, offset, length)

        override fun close() {
            // The enclosing SevenZFile owns the archive stream and closes it.
        }
    }

    // -- ZIP (via zip4j for encryption support) --

    private fun listZipEntries(path: String): List<FileItem> {
        val zipFile = ZipFile(path)
        return zipFile.fileHeaders.map { header ->
            val name = header.fileName.trimEnd('/')
            val ext = name.substringAfterLast('.', "")
            val mime = if (header.isDirectory) "inode/directory"
            else mimeTypeForExtension(ext)
            FileItem(
                name = name.substringAfterLast('/'),
                path = header.fileName.trimEnd('/'),
                size = header.uncompressedSize,
                lastModified = header.lastModifiedTimeEpoch,
                isDirectory = header.isDirectory,
                mimeType = mime,
                extension = ext,
            )
        }
    }

    private suspend fun extractZip(
        archivePath: String, stagingRoot: File,
        entries: List<String>?, password: CharArray?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val selection = normalizedSelection(entries)
        val budget = ArchiveExtractionBudget()
        var count = 0
        ZipFile(archivePath).use { zipFile ->
            if (password != null) zipFile.setPassword(password)
            // Nothing in Mimir asks for a password, so an encrypted entry can only fail. Said before a
            // byte is staged, from the header the archive itself carries, rather than discovered as an
            // exception from inside the reader (observed with three password archives, all reported as "Failed").
            if (password == null && zipFile.fileHeaders.any { it.isEncrypted && !it.isDirectory }) {
                throw ArchiveExtractionException("Archive is password protected", ArchiveFailureKind.PASSWORD)
            }
            for (header in zipFile.fileHeaders) {
                val entryName = header.fileName.trimEnd('/')
                if (entryName.isEmpty() || (selection != null && entryName !in selection)) continue

                val outputFile = stagedEntry(stagingRoot, entryName, header.uncompressedSize, budget)
                if (header.isDirectory) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw cannotCreateFolder(outputFile)
                    }
                } else {
                    copyArchiveEntry(
                        input = zipFile.getInputStream(header),
                        outputFile = outputFile,
                        entryName = entryName,
                        declaredSize = header.uncompressedSize,
                        budget = budget,
                        onProgress = onProgress,
                    )
                }
                count++
            }
        }
        return count
    }

    private fun createZip(
        outputPath: String, sourcePaths: List<String>,
        password: CharArray?, level: Int,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val zipFile = ZipFile(outputPath)
        if (password != null) zipFile.setPassword(password)

        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = when {
                level <= 1 -> CompressionLevel.FASTEST
                level <= 3 -> CompressionLevel.FAST
                level <= 7 -> CompressionLevel.NORMAL
                else -> CompressionLevel.MAXIMUM
            }
            if (password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }

        for (sourcePath in sourcePaths) {
            val file = File(sourcePath)
            onProgress(0, 0, file.name)
            if (file.isDirectory) {
                zipFile.addFolder(file, params)
            } else {
                zipFile.addFile(file, params)
            }
        }
    }

    // -- 7z (via Commons Compress) --

    private fun list7zEntries(path: String): List<FileItem> {
        val entries = mutableListOf<FileItem>()
        SevenZFile.builder().setFile(File(path)).get().use { sevenZ ->
            var entry: SevenZArchiveEntry?
            while (sevenZ.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                val name = e.name.trimEnd('/')
                val ext = name.substringAfterLast('.', "")
                val mime = if (e.isDirectory) "inode/directory"
                else mimeTypeForExtension(ext)
                entries.add(
                    FileItem(
                        name = name.substringAfterLast('/'),
                        path = e.name.trimEnd('/'),
                        size = e.size,
                        lastModified = e.lastModifiedDate?.time ?: 0L,
                        isDirectory = e.isDirectory,
                        mimeType = mime,
                        extension = ext,
                    )
                )
            }
        }
        return entries
    }

    private suspend fun extract7z(
        archivePath: String, stagingRoot: File,
        entries: List<String>?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val selection = normalizedSelection(entries)
        val budget = ArchiveExtractionBudget()
        var count = 0
        SevenZFile.builder().setFile(File(archivePath)).get().use { sevenZ ->
            var entry: SevenZArchiveEntry?
            while (sevenZ.nextEntry.also { entry = it } != null) {
                coroutineContext.ensureActive()
                val e = entry ?: continue
                val entryName = e.name.trimEnd('/')
                if (entryName.isEmpty() || (selection != null && entryName !in selection)) continue

                val outFile = stagedEntry(stagingRoot, entryName, e.size, budget)
                if (e.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw cannotCreateFolder(outFile)
                    }
                } else {
                    copyArchiveEntry(
                        input = SevenZEntryInputStream(sevenZ),
                        outputFile = outFile,
                        entryName = entryName,
                        declaredSize = e.size,
                        budget = budget,
                        onProgress = onProgress,
                        closeInput = false,
                    )
                }
                count++
            }
        }
        return count
    }

    private fun create7z(
        outputPath: String, sourcePaths: List<String>,
        level: Int,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val output = org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(File(outputPath))
        output.use { out ->
            for (sourcePath in sourcePaths) {
                addTo7z(out, File(sourcePath), "", onProgress)
            }
        }
    }

    private fun addTo7z(
        out: org.apache.commons.compress.archivers.sevenz.SevenZOutputFile,
        file: File, base: String,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"
        if (file.isDirectory) {
            val entry = out.createArchiveEntry(file, "$entryName/")
            out.putArchiveEntry(entry)
            out.closeArchiveEntry()
            file.listFiles()?.forEach { child -> addTo7z(out, child, entryName, onProgress) }
        } else {
            val entry = out.createArchiveEntry(file, entryName)
            out.putArchiveEntry(entry)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var len: Int
                while (fis.read(buf).also { len = it } != -1) {
                    out.write(buf, 0, len)
                }
            }
            out.closeArchiveEntry()
            onProgress(0, 0, file.name)
        }
    }

    // -- TAR (with compression auto-detection) --

    private fun isTarCompressed(path: String): Boolean {
        val ext = path.substringAfterLast('.').lowercase()
        return ext in setOf("gz", "bz2", "xz", "zst") ||
                path.substringBeforeLast('.').substringAfterLast('.').lowercase() == "tar"
    }

    private fun openTarInputStream(path: String): InputStream {
        val fis = BufferedInputStream(FileInputStream(path))
        val ext = path.substringAfterLast('.').lowercase()
        return when (ext) {
            "gz", "tgz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, fis)
            "bz2", "tbz2" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, fis)
            // Restored to upstream's call: org.tukaani:xz IS vendored (see this file's header --
            // it turned out to be required for 7z too, not just here). Unmodified from upstream.
            "xz", "txz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, fis)
            // MIMIR DEVIATION from upstream: upstream called straight into commons-compress's
            // Zstandard codec factory here. It needs an OPTIONAL artifact (com.github.luben:zstd-jni)
            // on the runtime classpath that this slice does not vendor -- nothing in the kept test
            // surface exercises `.zst` (see this file's header comment). Left as upstream wrote it,
            // a missing codec throws NoClassDefFoundError (a JVM Error, not an Exception), which
            // would escape extract()'s `catch (e: Exception)` and crash instead of failing
            // gracefully. Refused explicitly instead, so `.zst` fails the same honest way as any
            // other unsupported input.
            "zst" -> throw ArchiveExtractionException("Zstandard-compressed TAR is not supported: the zstd-jni codec dependency is not vendored", ArchiveFailureKind.UNSUPPORTED)
            else -> fis // Plain .tar
        }
    }

    private fun listTarEntries(path: String): List<FileItem> {
        val entries = mutableListOf<FileItem>()
        val stream = TarArchiveInputStream(openTarInputStream(path))
        stream.use { tar ->
            var entry: ArchiveEntry?
            while (tar.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                val name = e.name.trimEnd('/')
                if (name.isEmpty()) continue
                val ext = name.substringAfterLast('.', "")
                val mime = if (e.isDirectory) "inode/directory"
                else mimeTypeForExtension(ext)
                entries.add(
                    FileItem(
                        name = name.substringAfterLast('/'),
                        path = e.name.trimEnd('/'),
                        size = e.size,
                        lastModified = e.lastModifiedDate?.time ?: 0L,
                        isDirectory = e.isDirectory,
                        mimeType = mime,
                        extension = ext,
                    )
                )
            }
        }
        return entries
    }

    private suspend fun extractTar(
        archivePath: String, stagingRoot: File,
        entries: List<String>?,
        onProgress: (Long, Long, String) -> Unit,
    ): Int {
        val selection = normalizedSelection(entries)
        val budget = ArchiveExtractionBudget()
        var count = 0
        val stream = TarArchiveInputStream(openTarInputStream(archivePath))
        stream.use { tar ->
            var entry: ArchiveEntry?
            while (tar.nextEntry.also { entry = it } != null) {
                coroutineContext.ensureActive()
                val e = entry ?: continue
                val entryName = e.name.trimEnd('/')
                if (entryName.isEmpty() || (selection != null && entryName !in selection)) continue
                val tarEntry = e as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                    ?: throw ArchiveExtractionException("Unsupported TAR entry: $entryName", ArchiveFailureKind.UNSUPPORTED)
                if (tarEntry.isSymbolicLink || tarEntry.isLink || !tarEntry.isDirectory && !tarEntry.isFile) {
                    throw ArchiveExtractionException("Symbolic, hard-link, or special TAR entry is not supported: $entryName", ArchiveFailureKind.UNSUPPORTED)
                }

                val outFile = stagedEntry(stagingRoot, entryName, e.size, budget)

                if (e.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw cannotCreateFolder(outFile)
                    }
                } else {
                    copyArchiveEntry(
                        input = tar,
                        outputFile = outFile,
                        entryName = entryName,
                        declaredSize = e.size,
                        budget = budget,
                        onProgress = onProgress,
                        closeInput = false,
                    )
                }
                count++
            }
        }
        return count
    }

    private fun createTarGz(
        outputPath: String, sourcePaths: List<String>,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val fos = FileOutputStream(outputPath)
        val gzos = CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.GZIP, fos)
        val tarOut = ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.TAR, gzos)
            as org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
        tarOut.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
        tarOut.setBigNumberMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.BIGNUMBER_POSIX)

        tarOut.use { out ->
            for (sourcePath in sourcePaths) {
                addToTar(out, File(sourcePath), "", onProgress)
            }
        }
    }

    private fun mimeTypeForExtension(extension: String): String = runCatching {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }.getOrNull() ?: "application/octet-stream"

    private fun addToTar(
        out: org.apache.commons.compress.archivers.tar.TarArchiveOutputStream,
        file: File, base: String,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"
        if (file.isDirectory) {
            val entry = out.createArchiveEntry(file, "$entryName/")
            out.putArchiveEntry(entry)
            out.closeArchiveEntry()
            file.listFiles()?.forEach { child -> addToTar(out, child, entryName, onProgress) }
        } else {
            val entry = out.createArchiveEntry(file, entryName)
            out.putArchiveEntry(entry)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var len: Int
                while (fis.read(buf).also { len = it } != -1) {
                    out.write(buf, 0, len)
                }
            }
            out.closeArchiveEntry()
            onProgress(0, 0, file.name)
        }
    }
}

enum class ArchiveFormat(val extension: String) {
    ZIP("zip"),
    SEVEN_Z("7z"),
    TAR_GZ("tar.gz"),
}
