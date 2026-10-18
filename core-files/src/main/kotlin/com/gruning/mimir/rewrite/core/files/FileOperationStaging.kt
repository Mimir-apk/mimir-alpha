package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.storage`; unmodified otherwise. Upstream path:
 * `core/storage/src/main/java/com/explorer/fileexplorer/core/storage/FileOperationStaging.kt`.
 *
 * The reason slice 2 exists: `ArchiveHelper.kt` (vendored in a later slice, the extraction code
 * this fork was taken for) imports [FileOperationStaging], [StagedEntry], [StagingManifest], and
 * [StagingRecoveryPolicy] from this file and nothing else in upstream `core/storage`. Verified by
 * grep against the upstream tree: `LocalFileRepository.kt` is its only other consumer.
 *
 * Read-only-plus-extract policy — where this class sits relative to it: `createOperation()`,
 * `writeManifest()`, and `recoverOrphaned()` writing and cleaning up their own
 * `.fileexplorer-staging` directories is extraction plumbing, not user-file mutation, and is
 * squarely in scope. `recoverRollback()`'s restoration of a staged copy back to its
 * `originalPath` after an interrupted operation is a safety net *for* that policy (it undoes
 * partial damage from an interruption), not a violation of it.
 *
 * The scope-sensitive surface remains:
 * `atomicMove()`, `deleteRecursively()`, and `deleteIfExists()` are public, general-purpose, and
 * operation-name-agnostic — `createOperation(baseDirectory, operation: String, ...)` does not
 * restrict `operation` to `"extract"`, and upstream's own `FileOperationStagingTest.kt` stages
 * `"move"` and `"copy"` operations, not only `"extract"`. The read-only-plus-extract gate
 * therefore has to live at each call site, not in this class. `ArchiveHelper.kt`'s call sites use
 * it only for extraction. Any future vendoring of `LocalFileRepository.kt` (general-purpose
 * copy/move/delete over user files) on top of this same class needs its own separate scrutiny
 * against the read-only policy — this file merely provides the mechanism.
 *
 * No network surface, no Hilt/Dagger annotations upstream, no root dependency, no minSdk
 * 26-29 compat branches (this file does not branch on `Build.VERSION`).
 */
enum class StagingRecoveryPolicy {
    CLEAN,
    ROLLBACK,
}

data class StagedEntry(
    val originalPath: String,
    val stagedPath: String,
    val committedPath: String? = null,
    val state: String = FileOperationStaging.STATE_PLANNED,
)

data class StagingManifest(
    val operation: String,
    val recoveryPolicy: StagingRecoveryPolicy,
    val entries: List<StagedEntry> = emptyList(),
)

/**
 * Durable, filesystem-local staging for operations that touch more than one path.
 * The manifest is replaced atomically before and after every visible mutation.
 */
object FileOperationStaging {
    // MIMIR DEVIATION from upstream : upstream names this
    // ".fileexplorer-staging". This directory is created at the EXTRACTION DESTINATION, so on a
    // handheld it is visible to the player on their SD card next to their ROMs. Foreign branding in
    // a player's storage is a defect, so the literal carries Mimir's name. Renamed before any code
    // wrote it to disk, so there is no legacy directory to migrate.
    const val ROOT_DIRECTORY = ".mimir-staging"
    const val STATE_PLANNED = "PLANNED"
    const val STATE_STAGED = "STAGED"
    const val STATE_BACKUP_MOVED = "BACKUP_MOVED"
    const val STATE_TARGET_COMMITTED = "TARGET_COMMITTED"
    const val STATE_COMMITTED = "COMMITTED"

    private const val MANIFEST_NAME = "manifest"
    private const val TEMP_MANIFEST_NAME = ".manifest.tmp"
    // MIMIR DEVIATION from upstream (manifest format): upstream's tag is "FILEEXPLORER-STAGING-1". This
    // is the manifest's format discriminator — a recovery pass reads it to decide whether it
    // understands an interrupted operation's on-disk state. Renaming it is safe ONLY because nothing
    // has written a manifest yet; changing it after a release would strand in-flight recoveries.
    private const val FORMAT = "MIMIR-STAGING-1"

    /**
     * Make a new operation folder under [baseDirectory]'s staging root and write its first journal.
     *
     * @param live mark the folder as a running job's BEFORE its journal exists. Marking it afterwards left
     *   a gap in which another job's sweep of the same folder found a journal (or an empty folder) it did
     *   not recognise as running, and rolled it back underneath it.
     */
    fun createOperation(
        baseDirectory: File,
        operation: String,
        recoveryPolicy: StagingRecoveryPolicy,
        live: Boolean = false,
    ): File {
        require(operation.isNotBlank()) { "Staging operation must not be blank" }
        if (!baseDirectory.exists() && !baseDirectory.mkdirs() && !baseDirectory.isDirectory) {
            throw IllegalStateException("Unable to create staging base: ${baseDirectory.path}")
        }
        val root = baseDirectory.resolve(ROOT_DIRECTORY)
        // Two tries: a finishing job prunes an EMPTY staging root ([pruneRoot]), and that can land in the
        // gap between this function making the root and creating the operation folder inside it. The
        // second try simply makes the root again.
        var attempt = 0
        while (true) {
            if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
                throw IllegalStateException("Unable to create staging directory: ${root.path}")
            }
            val operationRoot = try {
                Files.createTempDirectory(root.toPath(), ".$operation-").toFile()
            } catch (error: java.nio.file.NoSuchFileException) {
                if (++attempt >= 3) throw error
                continue
            }
            if (live) markLive(operationRoot)
            try {
                writeManifest(operationRoot, StagingManifest(operation, recoveryPolicy))
            } catch (error: Throwable) {
                unmarkLive(operationRoot)
                throw error
            }
            return operationRoot
        }
    }

    // ---- Mimir's own working folders: what they are called, and who must never see or use them ----

    private val OPERATION_FOLDER_NAME = Regex("^\\.(extract|copy|move|delete|compress)-\\d+$")

    /** Whether [name] is the staging root itself (`.mimir-staging`). */
    fun isStagingRootName(name: String): Boolean = name.equals(ROOT_DIRECTORY, ignoreCase = true)

    /**
     * Whether [name] is one of Mimir's working folders: the staging root, or one operation folder
     * inside it (`.extract-1927038605654158004`). **Matched exactly, never by prefix**, so a folder the
     * player made and called `.extract-notes` is not mistaken for machinery and hidden from them.
     */
    fun isWorkingFolderName(name: String): Boolean =
        isStagingRootName(name) || OPERATION_FOLDER_NAME.matches(name)

    /**
     * Whether [path] is the staging root or anywhere inside it. Used to refuse a destination: copying
     * or extracting INTO Mimir's own working folder would be accepted, then swept away by the next
     * recovery pass (E2 observation O2).
     */
    fun isInsideStaging(path: String): Boolean =
        path.split('/', '\\').any { isStagingRootName(it) }

    /**
     * Remove [baseDirectory]'s staging root **if, and only if, it is empty**. `File.delete()` on a
     * directory fails when it has any child, which is the whole safety property: a held delete's payload,
     * or a live job's folder, keeps the root alive. Every finished job calls this (observed: 28 empty `.mimir-staging` folders were left behind, one inside a library platform folder).
     */
    fun pruneRoot(baseDirectory: File) {
        val root = baseDirectory.resolve(ROOT_DIRECTORY)
        if (root.isDirectory && !Files.isSymbolicLink(root.toPath())) root.delete()
    }

    /** [pruneRoot] for the folder an operation root sits in (`<base>/.mimir-staging/<operation>`). */
    fun pruneRootOf(operationRoot: File) {
        operationRoot.absoluteFile.parentFile?.parentFile?.let(::pruneRoot)
    }

    // ---- Live jobs: operation roots this process is still working in ----

    private val liveRoots: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Mark [operationRoot] as belonging to a job that is running right now in this process, so a
     * recovery sweep of the same folder (another extraction into it, or the player browsing to it)
     * leaves it alone. Without this a second job's start-up sweep read the first job's journal as an
     * abandoned one and rolled it back underneath it.
     */
    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    fun markLive(operationRoot: File) {
        liveRoots += canonicalPath(operationRoot)
    }

    fun unmarkLive(operationRoot: File) {
        liveRoots -= canonicalPath(operationRoot)
    }

    /** The operation roots of the jobs running in this process right now (canonical paths). */
    fun liveRootPaths(): Set<String> = liveRoots.toSet()

    /**
     * Roll back and remove ONE operation root this process owns, without reporting it as an interruption
     * and without touching any other journal in the folder. The in-process failure path: the caller
     * already knows the job failed and will say so on its own card.
     */
    fun rollbackOperation(operationRoot: File): Int {
        unmarkLive(operationRoot)
        val manifest = readManifest(operationRoot)
        var recovered = 0
        if (manifest != null && manifest.recoveryPolicy == StagingRecoveryPolicy.ROLLBACK) {
            recovered = recoverRollback(manifest)
        }
        deleteRecursively(operationRoot)
        pruneRootOf(operationRoot)
        return recovered
    }

    fun writeManifest(operationRoot: File, manifest: StagingManifest) {
        if (!operationRoot.exists() && !operationRoot.mkdirs() && !operationRoot.isDirectory) {
            throw IllegalStateException("Unable to create operation journal: ${operationRoot.path}")
        }
        val temporary = operationRoot.resolve(TEMP_MANIFEST_NAME)
        temporary.outputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.appendLine(FORMAT)
            writer.appendLine(
                listOf(
                    encode(manifest.operation),
                    manifest.recoveryPolicy.name,
                ).joinToString("|"),
            )
            manifest.entries.forEach { entry ->
                writer.appendLine(
                    listOf(
                        encode(entry.originalPath),
                        encode(entry.stagedPath),
                        encode(entry.committedPath ?: ""),
                        entry.state,
                    ).joinToString("|"),
                )
            }
        }
        forceFile(temporary)
        atomicMove(temporary.toPath(), operationRoot.resolve(MANIFEST_NAME).toPath(), replaceExisting = true)
        forceDirectory(operationRoot)
    }

    fun readManifest(operationRoot: File): StagingManifest? {
        val manifestFile = operationRoot.resolve(MANIFEST_NAME)
        if (!manifestFile.isFile) return null
        return runCatching {
            val lines = manifestFile.readLines(StandardCharsets.UTF_8)
            require(lines.firstOrNull() == FORMAT) { "Unsupported staging manifest" }
            val header = lines.getOrNull(1)?.split('|') ?: error("Missing staging manifest header")
            require(header.size == 2) { "Invalid staging manifest header" }
            val policy = StagingRecoveryPolicy.valueOf(header[1])
            val entries = lines.drop(2).filter { it.isNotBlank() }.map { line ->
                val fields = line.split('|')
                require(fields.size == 4) { "Invalid staging manifest entry" }
                StagedEntry(
                    originalPath = decode(fields[0]),
                    stagedPath = decode(fields[1]),
                    committedPath = decode(fields[2]).ifBlank { null },
                    state = fields[3].also { require(it.isNotBlank()) },
                )
            }
            StagingManifest(decode(header[0]), policy, entries)
        }.getOrNull()
    }

    /**
     * Reconciles abandoned journals from a previous process. ROLLBACK journals restore
     * entries that were moved out of their original location; CLEAN journals discard
     * private staging data. Malformed journals are removed rather than exposed as files.
     *
     * **MIMIR ADDITION: [retain] — absolute operation-root paths this sweep must not
     * touch. Defaults to empty, so every existing caller (`ArchiveHelper`) behaves exactly as before.**
     *
     * This parameter exists because Undo is an *intentionally unfinished* delete. A delete that
     * still holds Undo is a ROLLBACK journal parked at [STATE_STAGED] with its payload moved aside —
     * which is bit-for-bit the shape [recoverRollback] exists to undo. Without [retain], the very next
     * operation in the same parent directory would sweep the browsed folder, see that journal, decide it
     * was an interrupted delete, and **put the "deleted" files back on screen with no player action** —
     * a silent resurrection, and a Undo affordance still offering to undo something already undone.
     * [ManagedFileOperations] owns every held root (it is the only thing that creates one) and passes
     * them here.
     *
     * **The polarity stays ROLLBACK, deliberately.** A process that dies while a delete is held must
     * restore the files, never complete the delete: the recoverable direction is the safe one for an
     * operation over the player's ROM library. So a crash mid-Undo is *supposed* to resurrect — what
     * [retain] prevents is resurrection while this process is still alive and still showing the player
     * an Undo button for it.
     */
    fun recoverOrphaned(baseDirectory: File, retain: Set<String> = emptySet()): Int {
        val root = baseDirectory.resolve(ROOT_DIRECTORY)
        // A retained root is remembered under the path it was made with, but the sweeping caller may name the same
        // folder through its canonical path (ManagedFileOperations resolves every destination to one): a Windows short
        // name, or an Android /sdcard symlink. Match on either form, or a held delete is read as abandoned and its
        // payload is swept out from under the Undo button.
        val retained = retain.flatMapTo(HashSet()) { listOf(it, canonicalPath(File(it))) }
        val operations = root.listFiles().orEmpty()
            .filter { it.isDirectory }
            // MIMIR ADDITION (F-01, 2026-08-14) -- see this parameter's note in the KDoc above.
            .filterNot { it.absolutePath in retained || canonicalPath(it) in retained }
            // A job running in THIS process owns its folder; only a previous process's folders are orphans.
            .filterNot { canonicalPath(it) in liveRoots }
        var recovered = 0
        operations.forEach { operationRoot ->
            val manifest = readManifest(operationRoot)
            if (manifest == null) {
                // An extraction killed before its first journal write has nothing worth a journal, but
                // the job still happened and the player is still owed a sentence about it.
                if (operationRoot.name.startsWith(".extract-")) {
                    InterruptedExtractions.recovered(operationRoot, baseDirectory, null)
                }
                deleteRecursively(operationRoot)
                return@forEach
            }
            // The sentence is built from the journal BEFORE the rollback reads it away.
            if (manifest.operation == InterruptedExtractions.OPERATION) {
                InterruptedExtractions.recovered(operationRoot, baseDirectory, manifest)
            }
            when (manifest.recoveryPolicy) {
                StagingRecoveryPolicy.CLEAN -> Unit
                StagingRecoveryPolicy.ROLLBACK -> recoverRollback(manifest).also { recovered += it }
            }
            deleteRecursively(operationRoot)
        }
        pruneRoot(baseDirectory)
        return recovered
    }

    private fun recoverRollback(manifest: StagingManifest): Int {
        var recovered = 0
        val restoredBackups = hashSetOf<String>()
        manifest.entries
            .filter { it.state == STATE_BACKUP_MOVED }
            .forEach { entry ->
                val original = File(entry.originalPath)
                val staged = File(entry.stagedPath)
                if (staged.exists()) {
                    deleteIfExists(original)
                    original.parentFile?.mkdirs()
                    runCatching { atomicMove(staged.toPath(), original.toPath(), replaceExisting = false) }
                        .onSuccess {
                            recovered++
                            restoredBackups += original.path
                        }
                }
            }

        manifest.entries.forEach { entry ->
            if (entry.state == STATE_COMMITTED) {
                deleteIfExists(File(entry.stagedPath))
                return@forEach
            }
            val original = File(entry.originalPath)
            val staged = File(entry.stagedPath)
            when (entry.state) {
                STATE_TARGET_COMMITTED -> {
                    val committed = entry.committedPath?.let(::File)
                    if (manifest.operation == "move" && !original.exists() && committed?.exists() == true) {
                        original.parentFile?.mkdirs()
                        runCatching { atomicMove(committed.toPath(), original.toPath(), replaceExisting = false) }
                            .onSuccess { recovered++ }
                    } else {
                        if (committed?.path !in restoredBackups) committed?.let(::deleteIfExists)
                    }
                    if (staged.exists()) deleteRecursively(staged)
                }
                STATE_STAGED -> {
                    if (manifest.operation in setOf("delete", "move") && !original.exists() && staged.exists()) {
                        original.parentFile?.mkdirs()
                        runCatching { atomicMove(staged.toPath(), original.toPath(), replaceExisting = false) }
                            .onSuccess { recovered++ }
                    } else if (staged.exists()) {
                        deleteRecursively(staged)
                    }
                }
                else -> {
                    if (staged.exists()) deleteRecursively(staged)
                }
            }
        }
        return recovered
    }

    // ---- Cancel: taking back what ONE job published, and nothing else ----

    /** What [rollbackPublished] did: files this job had placed that are gone again, and ones it could not remove. */
    data class PublishedRollback(val removed: Int, val remaining: Int)

    private const val CREATED_DIRECTORIES_NAME = "created-dirs"

    /**
     * Note, inside [operationRoot], the directories this job is about to create in the player's folder
     * (those that do not exist yet). [rollbackPublished] reads the list back to remove the ones that are
     * still empty, so a cancelled extract does not leave a tree of empty folders where its files were.
     * Written before the first directory is made: a note about a folder never made is harmless, a folder
     * made with no note would be a husk nobody removes.
     */
    fun recordCreatedDirectories(operationRoot: File, directories: Collection<File>) {
        if (directories.isEmpty()) return
        val temporary = operationRoot.resolve("$CREATED_DIRECTORIES_NAME.tmp")
        temporary.outputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            directories.forEach { writer.appendLine(encode(it.path)) }
        }
        atomicMove(temporary.toPath(), operationRoot.resolve(CREATED_DIRECTORIES_NAME).toPath(), replaceExisting = true)
    }

    private fun readCreatedDirectories(operationRoot: File): List<File> {
        val file = operationRoot.resolve(CREATED_DIRECTORIES_NAME)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }.map { File(decode(it)) }
        }.getOrDefault(emptyList())
    }

    /**
     * **Cancel an extraction that has started placing files: remove what THIS job placed, put back what it
     * replaced, and drop its staging.**
     *
     * The journal is the whole list. Every file this job published has a `COMMITTED` (or, mid-move,
     * `TARGET_COMMITTED`) entry with an empty `originalPath`, and that entry's `committedPath` is the file.
     * Nothing else is ever deleted: not a file the player already had at that name (it was moved to a
     * backup first and is restored by the ordinary rollback this ends with), not a sibling, not a folder
     * with anything left in it. A file that cannot be removed is counted in [PublishedRollback.remaining]
     * so the card can say what is still there rather than claim a clean undo.
     *
     * Folders this job created ([recordCreatedDirectories]) are removed deepest first and only while
     * empty: `File.delete()` refuses a directory with any child.
     */
    fun rollbackPublished(operationRoot: File): PublishedRollback {
        val manifest = readManifest(operationRoot)
        val createdDirectories = readCreatedDirectories(operationRoot)
        var removed = 0
        var remaining = 0
        manifest?.entries.orEmpty()
            .filter { it.originalPath.isEmpty() && (it.state == STATE_COMMITTED || it.state == STATE_TARGET_COMMITTED) }
            .mapNotNull { it.committedPath?.let(::File) }
            .forEach { placed ->
                if (!placed.exists() && !Files.isSymbolicLink(placed.toPath())) return@forEach
                runCatching { deleteIfExists(placed) }
                if (placed.exists() || Files.isSymbolicLink(placed.toPath())) remaining++ else removed++
            }
        // Restores whatever this job replaced (its backups are still held), then removes the staging.
        rollbackOperation(operationRoot)
        createdDirectories
            .sortedByDescending { it.path.length }
            .forEach { directory ->
                if (directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) directory.delete()
            }
        return PublishedRollback(removed = removed, remaining = remaining)
    }

    fun deleteIfExists(file: File) {
        if (file.exists() || Files.isSymbolicLink(file.toPath())) deleteRecursively(file)
    }

    fun deleteRecursively(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath())
            return
        }
        file.listFiles().orEmpty().forEach(::deleteRecursively)
        Files.deleteIfExists(file.toPath())
    }

    /**
     * Move [source] to [target]. With [replaceExisting] false an existing target is a refusal.
     *
     * Said out loud because the filesystem does not say it: `ATOMIC_MOVE` is `rename(2)` on Linux and Android
     * (and `MoveFileEx` on Windows), which REPLACES an existing target and ignores the absence of
     * `REPLACE_EXISTING`. Every caller that passed false and expected a failure -- Undo restoring over a file
     * made since, an extraction landing on a name a second job took first -- was silently getting an
     * overwrite. The check is not atomic with the move, but it turns a certain loss into a window of microseconds.
     */
    fun atomicMove(source: Path, target: Path, replaceExisting: Boolean) {
        if (!replaceExisting && Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.FileAlreadyExistsException(target.toString())
        }
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source, target, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source, target, *fallback)
        }
    }

    fun forceFile(file: File) {
        if (!file.isFile) return
        runCatching {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel -> channel.force(true) }
        }
    }

    fun forceDirectory(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    private fun encode(value: String): String = Base64.getEncoder()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = String(
        Base64.getDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}
