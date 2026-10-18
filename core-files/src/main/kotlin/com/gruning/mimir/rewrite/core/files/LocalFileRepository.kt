package com.gruning.mimir.rewrite.core.files

import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.security.MessageDigest

// Replaces upstream's `capabilities.provider` (see the class doc comment below, "Capabilities"
// paragraph) -- the only thing every asRepositoryException(...) call site here actually needed.
private const val PROVIDER = "local"

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.data`. Upstream path:
 * `core/data/src/main/java/com/explorer/fileexplorer/core/data/LocalFileRepository.kt`.
 *
 * This is the enumerator:
 * `core-files` had the vocabulary, the staging layer, and extraction, but could not yet list a
 * directory. [listFiles] is that.
 *
 * De-Hilted: upstream was `@Singleton class LocalFileRepository @Inject constructor(
 * @ApplicationContext private val context: Context) : FileRepository`. Mimir has no DI framework;
 * `@Singleton`/`@Inject`/`@ApplicationContext` and their `javax.inject`/`dagger.hilt` imports are
 * removed. **`context` was verified UNUSED** — grepped for `\bcontext\b` across the entire 566-line
 * upstream file and the only match is the constructor parameter declaration itself; no method body
 * reads it. So this is not merely "de-Hilted", it drops `Context` (and therefore any Android SDK
 * dependency beyond [MimeTypeMap]) entirely: a plain, zero-argument `class LocalFileRepository`.
 * `FileRepository` (the interface upstream implemented) is not vendored either — see "Capabilities"
 * below — so this class implements nothing and stands alone, matching [ArchiveHelper]'s own
 * bare-class precedent.
 *
 * READ-ONLY + EXTRACT is the policy: no delete, rename, move or copy of user files. Removed
 * from upstream's 21 members, deleted not disabled:
 *
 *  - `copyFiles`, `moveFiles`, `deleteFiles`, `rename` (none exist in this file any more, so left
 *    as plain code text rather than `[links]` that would dangle) — copy/move/delete/rename of user
 *    files, excluded by the policy.
 *  - `createDirectory`, `createFile` — also removed. Not named explicitly in the policy's
 *    prose, but [RepositoryCapabilityMatrix.writeAssessment]'s `writeOperations` set
 *    (`RepositoryCapabilityMatrix.kt:226-233`) already classifies `CREATE_DIRECTORY` and
 *    `CREATE_FILE` alongside `COPY`/`MOVE`/`DELETE`/`RENAME` as the six operations that make a
 *    provider a *write* provider — that is the vendored vocabulary's own taxonomy of "write", not
 *    a boundary invented for this file. A read-only browser does not offer either.
 *  - `capabilities` (val) and `deleteCapabilities(paths)` — removed together. Upstream's
 *    `capabilities = RepositoryCapabilities.local("local")` advertises all twelve
 *    [RepositoryOperation]s including the six write ones just removed; keeping it verbatim would
 *    have this class claim operations it no longer implements. `deleteCapabilities` describes
 *    delete support that no longer exists, and its return type `DeleteCapabilities` lives in the
 *    NOT-vendored `FileRepository.kt` (see below). Replaced by the file-private [PROVIDER] string
 *    constant, which is all the surviving methods actually need for
 *    [Throwable.asRepositoryException]'s provider label. Honest capability wiring via
 *    [RepositoryCapabilityMatrix] is OWED for whichever future slice gives this class a UI
 *    consumer — `RepositoryCapabilities.local(...)`/`RepositoryCapabilityMatrix.from(...)` remain
 *    unused by any production code even after this slice, exactly as they were after slice 1.
 *  - `copyRecursive`, `deleteRecursive`, `resolveTransferTarget`, `stageAndCommitCopy`,
 *    `forceTree`, `resolveConflict` (all private) — removed as dead code once their only callers
 *    (the methods above) are gone. `stageAndCommitCopy`'s removal is why this file needs none of
 *    [FileOperationStaging] / [StagedEntry] / [StagingManifest] / [StagingRecoveryPolicy]: every
 *    surviving method is a pure read, and that machinery was upstream's write-path plumbing only —
 *    slice 2's header comment already flagged this exact file as needing "its own separate
 *    scrutiny against the read-only policy" when it eventually got vendored; this is that scrutiny,
 *    and the answer is it needs none of it.
 *  - `atomicMoveWithJournal` (private) — removed. Dead code already in upstream: grepped the whole
 *    566-line file for its name and the only match is its own `private fun` declaration — zero
 *    callers, upstream or here.
 *  - `searchStreaming` — removed. A public wrapper that only calls `search(...)` with the same
 *    arguments; grepped the ENTIRE upstream repository (not just this file) for its name and the
 *    only match is its own declaration — zero callers anywhere upstream, so this is redundant dead
 *    code, not surface this slice cut.
 *
 * Kept, because it is listing, reading, or (per [search]) read-only filtering — none of it mutates
 * anything: [listFiles], [getFileInfo], [exists], [calculateSize], [search], [getChecksum], and the
 * private [pathToFileItem] helper they all share.
 *
 * Listing policies re-applied here, on top of
 * this vendored lister, exactly as § 6 anticipated ("their tests are the starting point"):
 *
 *  - **Ruling 2 (plumbing roots excluded):** [listFiles] now filters its result through
 *    [LocalListingPolicy.isPlumbingPath] before emitting (MIMIR ADDITION). [search] applies the
 *    same predicate to its traversal stack, not just its output — reusing the identical predicate
 *    so the two surfaces cannot disagree about what counts as plumbing, and because leaving it
 *    unguarded would let a search rooted at `/` walk into `/proc`, a real device hazard
 *    (permission storms against an effectively unbounded pseudo-filesystem), not merely a display
 *    concern. This second application is disclosed hardening beyond the adoption spec's literal
 *    "the listing must not surface" wording, not one of the three differentially-pinned rulings.
 *  - **Ruling 3 (unreadable directories list empty, never throw):** MIMIR DEVIATION from upstream
 *    in [listFiles]. Upstream's `catch (e: Exception) { throw e.asRepositoryException(...) }`
 *    converts a permission-denied `Files.newDirectoryStream` into a thrown [RepositoryException]
 *    that escapes this `Flow`. A permission-denied directory is routine on a real device (a ROM
 *    tree the app cannot fully enter), and the ruling requires empty instead. The replacement catch
 *    explicitly rethrows [CancellationException] BEFORE the broad `catch (e: Exception)` — this is
 *    new code emitting `emptyList()` directly, not routed through `asRepositoryException` (whose
 *    own `if (this is CancellationException) throw this` guard, `RepositoryContract.kt:199`, is
 *    what already makes upstream's unrelated catches in [calculateSize]/[search]/[getChecksum]
 *    correct as-is — verified by reading that guard, not assumed. No change was needed or made
 *    there). Skipping the explicit rethrow here specifically would silently swallow a coroutine
 *    cancellation into a false "empty directory" result, which is a real bug ArchiveHelper.kt's own
 *    `extract()` avoids the same way (`catch (e: CancellationException) { throw e }` before its own
 *    broad catch) — this mirrors that established, already-reviewed pattern rather than inventing a
 *    new one.
 *  - **Ruling 1 (zip + 7z extractable, not rar)** has no call site in this file: it is a
 *    classification a caller applies per [FileItem], not a filter on the listing itself. See
 *    [LocalListingPolicy.isExtractableArchive].
 *
 * minSdk 30 here vs. upstream's 26: no `Build.VERSION` branches exist anywhere in the 566-line
 * upstream file, so there is no API 26-29 dead code to delete in this slice.
 *
 * No network surface: every method here operates on `java.nio.file` local paths only.
 */
class LocalFileRepository {

    /**
     * List files in a directory. Streaming `Flow` per upstream's own contract ("streaming large
     * directories"), even though this implementation emits exactly once — unmodified from
     * upstream's own shape.
     */
    fun listFiles(path: String): Flow<List<FileItem>> = flow {
        val dir = Paths.get(path)
        if (!Files.isDirectory(dir)) {
            emit(emptyList())
            return@flow
        }
        val items = mutableListOf<FileItem>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (entry in stream) {
                    // Mimir's own working folders are never rows: the player cannot enter, move
                    // into or "tidy" a folder Mimir may be mid-job in.
                    if (FileOperationStaging.isWorkingFolderName(entry.fileName.toString())) continue
                    items.add(pathToFileItem(entry))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // MIMIR DEVIATION (ruling 3) -- see this file's header. Upstream rethrew via
            // asRepositoryException(...); a permission-denied directory must list empty here.
            emit(emptyList())
            return@flow
        }
        // MIMIR ADDITION (ruling 2) -- see this file's header and LocalListingPolicy.kt.
        emit(items.filterNot { LocalListingPolicy.isPlumbingPath(it.path) })
    }.flowOn(Dispatchers.IO)

    suspend fun getFileInfo(path: String): FileItem? = withContext(Dispatchers.IO) {
        val p = Paths.get(path)
        if (!Files.exists(p)) return@withContext null
        pathToFileItem(p, detailed = true)
    }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(Paths.get(path))
    }

    /** Calculate total size of files/directories recursively. */
    suspend fun calculateSize(paths: List<String>): Long = try {
        withContext(Dispatchers.IO) {
            var total = 0L
            for (path in paths) {
                val p = Paths.get(path)
                if (Files.isDirectory(p)) {
                    Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            total += attrs.size()
                            return FileVisitResult.CONTINUE
                        }
                    })
                } else {
                    total += Files.size(p)
                }
            }
            total
        }
    } catch (e: Exception) {
        throw e.asRepositoryException(PROVIDER, RepositoryOperation.SIZE)
    }

    /**
     * Search for files matching query. Streams results as found.
     *
     * **Names only, on the volume [rootPath] names, nothing sent anywhere** — the whole contract, and
     * its player-facing wording, lives in `FileSearchPolicy.kt` (F-08). This method
     * reads `entry.fileName` and never opens a stream on a candidate.
     *
     * **MIMIR DEVIATION from upstream, F-08: one unreadable directory no longer kills the whole
     * search.** Upstream's `catch (e: Exception) { throw e.asRepositoryException(...) }` sat INSIDE the
     * traversal loop, so the first permission-denied subdirectory anywhere under [rootPath] aborted the
     * entire walk and the player got a failure instead of the results already found. On a real device a
     * volume-wide search crosses such directories routinely — that is the same routine hazard ruling 3
     * already answered for [listFiles] ("a permission-denied directory is routine ... the ruling
     * requires empty instead"), and the same answer applies here: skip that directory, keep walking.
     * Applied per-directory, so the failure is contained to the branch that actually failed.
     *
     * **[CancellationException] is rethrown BEFORE the broad catch, and that ordering is load-bearing.**
     * `Flow` collectors that stop early — `first()`, `take(n)`, a search screen the player closed —
     * abort by throwing a `CancellationException` subtype out of `emit()`, from inside this very `try`.
     * Swallowing it into "skip this directory and continue" would turn every bounded collector into a
     * full-volume walk that never terminates. Upstream was accidentally safe here only because
     * `asRepositoryException` rethrows cancellations itself (`RepositoryContract.kt:199`); replacing
     * that call removes the guard, so it is restated explicitly. Same shape [listFiles] and
     * `ArchiveHelper.extract` already use.
     */
    fun search(
        rootPath: String,
        query: String,
        regex: Boolean = false,
        includeHidden: Boolean = false,
    ): Flow<FileItem> = flow {
        val root = Paths.get(rootPath)
        val pattern = if (regex) Regex(query, RegexOption.IGNORE_CASE) else null
        // R6-P2-07 review finding 1, 2026-09-15: folded and split ONCE per search, not once per entry
        // below -- the query never changes over the walk, and re-folding it per entry on a 30k-entry
        // card cost an extra 0.2-0.6s of IO CPU (two Normalizer.normalize calls, three regex matcher
        // allocations and ~six strings per entry that used to buy nothing new). Unused, harmlessly, in
        // the regex branch.
        val queryTokens = fileSearchQueryTokens(query)
        val stack = ArrayDeque<Path>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            try {
                Files.newDirectoryStream(current).use { stream ->
                    for (entry in stream) {
                        val name = entry.fileName.toString()
                        // Always, hidden files included: a staged payload is not the player's file.
                        if (FileOperationStaging.isWorkingFolderName(name)) continue
                        if (!includeHidden && name.startsWith(".")) continue

                        //  shares fileNameMatchesQueryTokens
                        // with FileSearchPolicy.kt's fileSearchNameMatches (the re-applied filter) rather
                        // than each doing its own plain .contains, so the two can never disagree about
                        // what a query finds -- see that function's own KDoc for the diacritic-fold and
                        // multi-token rules. Takes the pre-folded queryTokens (above), not query itself --
                        // R6-P2-07 review finding 1 -- so folding happens once per search, not per entry.
                        val matches = if (pattern != null) pattern.containsMatchIn(name)
                        else fileNameMatchesQueryTokens(name, queryTokens)

                        if (matches) emit(pathToFileItem(entry))
                        // MIMIR ADDITION (ruling 2 hardening) -- see this file's header: same
                        // predicate as listFiles(), applied to the traversal itself so a search
                        // rooted above a plumbing mount cannot walk into it.
                        if (Files.isDirectory(entry) &&
                            !LocalListingPolicy.isPlumbingPath(entry.toAbsolutePath().toString())
                        ) {
                            stack.addLast(entry)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // MUST stay ahead of the broad catch below -- see this method's header. A collector
                // that stopped early aborts through emit() with a CancellationException subtype, and
                // treating that as "unreadable directory, keep going" would make first()/take() walk
                // the whole volume and never return.
                throw e
            } catch (_: Exception) {
                // MIMIR DEVIATION (F-08) -- see this method's header. Upstream threw here, so one
                // permission-denied directory failed the entire search. Skip this branch, keep the
                // results already emitted, keep walking the rest of the volume.
                continue
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Get file hash (MD5, SHA-256, etc). */
    suspend fun getChecksum(path: String, algorithm: String = "SHA-256"): String = try {
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance(algorithm)
            val buffer = ByteArray(8192)
            Files.newInputStream(Paths.get(path)).use { stream ->
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    ensureActive()
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        throw e.asRepositoryException(PROVIDER, RepositoryOperation.CHECKSUM)
    }

    // -- Internal helpers --

    /**
     * @param detailed compute the fields only a SINGLE inspected file needs — the POSIX attribute read
     *   (which resolves uid/gid to owner and group names), the symlink target, and writability.
     *
     * **Default false, and that fixes the reported navigation delay.** A `Back` into a large directory sat
     * ~100 ms before anything moved. Perfetto showed the main thread in state `S` with no slices, which
     * looked like it was doing nothing — it was parked inside `FileBrowserStore`'s
     * `runBlocking { repository.listFiles(path).first() }` while the IO dispatcher did this work once per
     * entry. Measured on 441 entries: the full field set costs **11.4 ms** of syscalls, of which
     * `Files.isWritable` ≈3.5 ms, the POSIX read ≈1.4 ms, and the symlink read the remainder — all of it
     * for fields **no production code reads**. Skipping them costs 6.8 ms instead, a **41 % cut**, and the
     * device's emulated storage is slower per call than the machine that measured it.
     *
     * `isReadable` is NOT skipped: `DuplicateScanPolicy` reads it. `childCount` is not skipped either —
     * it is one `readdir` per DIRECTORY row and it is what draws every tome's "N items".
     *
     * **Nothing is faked.** A lean row leaves [FileItem.isWritable] at the data class's own default rather
     * than reporting a measured value, so `LocalFileRepositoryLeanListingTest` pins that no production
     * code reads it, or owner/group/permissions/symlinkTarget, off a listing row. The day someone needs
     * one, that test fails and tells them to ask for `detailed = true` instead of silently handing them a
     * default. Absence of a computation needs its own gate — this is that gate.
     */
    private fun pathToFileItem(path: Path, detailed: Boolean = false): FileItem {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) { null }

        val posix = if (!detailed) null else try {
            Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) { null }

        val name = path.fileName?.toString() ?: path.toString()
        val ext = name.substringAfterLast('.', "")
        val isDir = attrs?.isDirectory ?: false
        // MIMIR DEVIATION from upstream: upstream called MimeTypeMap.getSingleton() unguarded.
        // Wrapped in runCatching, matching ArchiveHelper.kt's own mimeTypeForExtension() (same
        // module, same precedent) -- found because this module's unit tests run on the plain JVM
        // with no Robolectric configured (verified: no `testOptions` block in build.gradle.kts), so
        // android.jar's stub MimeTypeMap.getSingleton() throws RuntimeException("not mocked") for
        // every non-directory entry, which ruling 3's new catch-all in listFiles() was silently
        // turning into a false "empty directory" -- caught by this file's own test suite failing,
        // not guessed. On a real device MimeTypeMap.getSingleton() never throws (it returns null for
        // an unrecognised extension), so this is strictly a safety net, not a behaviour change there.
        val mime = if (isDir) "inode/directory"
        else runCatching {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
        }.getOrNull() ?: "application/octet-stream"

        return FileItem(
            name = name,
            path = path.toAbsolutePath().toString(),
            size = attrs?.size() ?: 0L,
            lastModified = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
            isDirectory = isDir,
            isHidden = name.startsWith("."),
            isSymlink = attrs?.isSymbolicLink ?: false,
            isReadable = Files.isReadable(path),
            // Only when asked: one access(2) per row, ~3.5ms per 441 entries, and nothing reads it.
            isWritable = if (detailed) Files.isWritable(path) else true,
            mimeType = mime,
            extension = ext,
            permissions = posix?.permissions() ?: emptySet(),
            ownerName = posix?.owner()?.name,
            groupName = posix?.group()?.name,
            symlinkTarget = if (detailed && attrs?.isSymbolicLink == true) {
                try { Files.readSymbolicLink(path).toString() } catch (_: Exception) { null }
            } else null,
            childCount = if (isDir) directoryChildCount(path) else null,
        )
    }

    /**
     * MIMIR ADDITION  — the only writer of [FileItem.childCount].
     *
     * [FileItem.displaySize] has always read this field (`FileItem.kt:54`, `childCount?.let { "$it
     * items" } ?: ""`) and nothing has ever written it, so every tome's meta line rendered blank on
     * the device — a data gap `feature-files`' own [FileBrowserTile] KDoc already documented as owed.
     *
     * **Cheap by construction, because this runs per row on a still-synchronous listing thread.**
     * `File.list()` is one `readdir` sweep returning bare names; it never stats a child, never
     * recurses, and allocates one `String[]`. A `Files.walk`/`calculateSize`-style descent here would
     * turn one directory listing into an unbounded tree walk on the composition thread (see
     * [listFiles]'s own OWED note in `FileBrowserStore.kt` about that thread). This also runs for
     * [getFileInfo] and [search] rows, which is the same bounded one-call-per-emitted-directory cost.
     *
     * **`null` for unreadable, never 0** — the two are different claims, and [FileItem.displaySize]
     * renders them differently on purpose: `null` draws nothing, `0` would draw "0 items". `File.list()`
     * answers `null` for exactly the permission-denied / I-O-error case (the same routine hazard ruling
     * 3 handles one level up in [listFiles]), so a ROM folder the app cannot enter says nothing about
     * its contents rather than claiming it is empty. An empty-but-readable directory still returns 0.
     */
    private fun directoryChildCount(path: Path): Int? =
        try {
            path.toFile().list()?.count { !FileOperationStaging.isWorkingFolderName(it) }
        } catch (_: Exception) {
            null
        }
}
