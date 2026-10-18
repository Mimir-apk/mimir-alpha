package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * MIMIR ADDITION — the I/O half of the duplicates review. [DuplicateScanPolicy] owns every decision
 * that can be made without touching disk; this class owns the two things that cannot be: resolving
 * a path to the real file behind it, and hashing.
 *
 * **It removes nothing, and there is no code path here that could.** See [DuplicateScanPolicy]'s
 * header for the full definition of "duplicate" this implements, what it deliberately does not
 * answer, and the design copy that governs it (*"Nothing is removed until you pick which copy
 * stays"*).
 *
 * ## Both seams are injected, so the policy is drivable from plain JUnit
 *
 * This module configures no Robolectric (verified: no `testOptions` block in its
 * `build.gradle.kts`), and more to the point a scan's *decisions* should be testable without
 * needing a filesystem that can produce a symlink or a permission error on demand. So both
 * filesystem touches are constructor parameters with real defaults:
 *
 *  - [digestOf] defaults, via [using], to [LocalFileRepository.getChecksum] with
 *    [DuplicateScanPolicy.DIGEST_ALGORITHM]. **Production must go through that method, not a second
 *    digest loop** — one hashing implementation in this module, one place for a bug to live. There
 *    is deliberately no `MessageDigest` import in this file.
 *  - [canonicalPathOf] defaults to `Path.toRealPath()`.
 *
 * ## Why canonicalisation is a stage and not a footnote
 *
 * On Android the same file is reachable by several paths: `/sdcard` is a link to
 * `/storage/self/primary`, which is a link to `/storage/emulated/0`. So `/sdcard/Roms/x.gba` and
 * `/storage/emulated/0/Roms/x.gba` are **one file with two names**, and neither [FileItem] is
 * itself a symlink — the link is an *ancestor*, which [FileItem.isSymlink] cannot see.
 *
 * Without this stage the scan would report that file as a duplicate of itself, and a review whose
 * question is *"which copy stays"* would then invite the player to remove the only copy. That is
 * the one failure mode in which a detector that deletes nothing still causes data loss, by lying
 * about there being two copies — so it is handled here rather than disclosed as a caveat. It is
 * also what makes the *design's own* narrower reading safe, not merely this engine's broader one:
 * two aliases of one file are literally "same name and checksum in two folders".
 *
 * In practice the browser lists one root at a time, so both aliases rarely appear in one inventory;
 * this is the safety net for a scan that spans roots, not the common case.
 *
 * A path that cannot be resolved at all — it vanished mid-scan, or an ancestor is unreadable — is
 * **excluded and reported** ([DuplicateSkipReason.PATH_UNRESOLVED]) rather than admitted on its
 * literal path. The ambiguous branch fails toward not claiming a duplicate it cannot prove is a
 * separate file.
 *
 * ## Failure and cancellation
 *
 * One unreadable file never kills a scan: it is recorded as
 * [DuplicateSkipReason.DIGEST_FAILED] and the walk continues, the same posture
 * [LocalFileRepository.search] takes for a permission-denied directory and [LocalFileRepository.listFiles]
 * takes for an unreadable one.
 *
 * [CancellationException] is rethrown **before** the broad catch, and that ordering is load-bearing
 * for the same reason it is in [LocalFileRepository.search] and `ArchiveHelper.extract`: a player
 * who closes the review aborts through the suspending [digestOf], from inside this very `try`, and
 * swallowing that into "skip this file and continue" would keep the device hashing a whole volume
 * for a screen nobody is looking at.
 */
class DuplicateScanner(
    private val digestOf: suspend (String) -> String,
    private val canonicalPathOf: (String) -> String? = ::realPathOrNull,
) {

    /**
     * Scan [inventory] for byte-identical files.
     *
     * [inventory] is supplied by the caller and never enumerated here — this engine has no opinion
     * about which folders are in scope, and no dependency on the platform-folder index (which lives
     * in `app/`, not this module). Feeding it is a caller contract.
     *
     * [onProgress] receives `(filesHashed, filesToHash)` after each digest, for a review that wants
     * to show progress on a large card. `filesToHash` is known up front because the size prune runs
     * before any file is opened.
     */
    suspend fun scan(
        inventory: List<FileItem>,
        budget: DuplicateHashBudget = DuplicateHashBudget.unlimited(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): DuplicateScanResult = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<DuplicateScanSkip>()

        // Stage 1 -- one path per real file. See this class's header: this is what stops the review
        // from offering the player a choice between a file and itself.
        val distinct = mutableListOf<FileItem>()
        val seenIdentities = mutableSetOf<String>()
        for (item in inventory.filter(DuplicateScanPolicy::isScanCandidate).sortedBy(FileItem::path)) {
            ensureActive()
            val identity = canonicalPathOf(item.path)
            when {
                identity == null ->
                    skipped += DuplicateScanSkip(item.path, DuplicateSkipReason.PATH_UNRESOLVED)
                !seenIdentities.add(identity) ->
                    // Deterministically the path-ascending first alias survives. Which one it is
                    // does not matter -- they are the same bytes on the same disk -- but that it is
                    // stable does, so a rerun of the same scan reports the same row.
                    skipped += DuplicateScanSkip(item.path, DuplicateSkipReason.ALIAS_OF_ANOTHER_PATH)
                else -> distinct += item
            }
        }

        // Stage 2 -- the sound prune. Nothing has been opened yet.
        val classes = DuplicateScanPolicy.sizeCollisions(distinct)
        val filesToHash = classes.sumOf { it.size }

        // Cheapest size class first, so a capped budget completes as many whole classes as it can.
        // With the shipped unlimited() budget this ordering cannot affect the result at all; it only
        // decides what survives a cap, and the cap value is itself a pending policy decision.
        val ordered = classes.sortedWith(compareBy({ it.sumOf(FileItem::size) }, { it.first().size }))

        val digests = mutableMapOf<String, String>()
        for (sizeClass in ordered) {
            ensureActive()
            val classBytes = sizeClass.sumOf(FileItem::size)

            // Budget is spent per size class, all or nothing, DELIBERATELY. Hashing 2 of a class's 5
            // members would emit a group claiming 2 copies when 5 exist -- and "pick which copy
            // stays" answered against an understated group is wrong in the one direction that
            // matters. So the budget can shrink the number of groups reported, but it can never
            // make a reported group understate its own membership. (An unreadable member still can;
            // that is an environmental fact this engine reports rather than a choice it makes --
            // see DuplicateScanResult.isComplete.)
            if (!budget.canAfford(classBytes)) {
                sizeClass.forEach {
                    skipped += DuplicateScanSkip(it.path, DuplicateSkipReason.BUDGET_EXHAUSTED)
                }
                continue
            }

            for (item in sizeClass) {
                ensureActive()
                try {
                    digests[item.path] = digestOf(item.path)
                    budget.record(item.size)
                } catch (e: CancellationException) {
                    // MUST stay ahead of the broad catch -- see this class's header. A closed review
                    // aborts through digestOf, and treating that as "unreadable file, keep going"
                    // would hash the rest of the volume for a screen nobody is looking at.
                    throw e
                } catch (_: Exception) {
                    skipped += DuplicateScanSkip(item.path, DuplicateSkipReason.DIGEST_FAILED)
                }
                onProgress(budget.filesHashed, filesToHash)
            }
        }

        DuplicateScanResult(
            groups = DuplicateScanPolicy.groupByDigest(distinct, digests),
            skipped = skipped.sortedWith(compareBy({ it.path }, { it.reason })),
            filesHashed = budget.filesHashed,
            bytesHashed = budget.bytesHashed,
        )
    }

    companion object {
        /**
         * The production wiring: digests come from [LocalFileRepository.getChecksum] at
         * [DuplicateScanPolicy.DIGEST_ALGORITHM], never from a second digest implementation.
         */
        fun using(repository: LocalFileRepository = LocalFileRepository()): DuplicateScanner =
            DuplicateScanner(
                digestOf = { path -> repository.getChecksum(path, DuplicateScanPolicy.DIGEST_ALGORITHM) },
            )
    }
}

/**
 * Default canonical identity: the real file behind a path, with every symlink on the way resolved.
 *
 * Null when the path cannot be resolved — it vanished, or an ancestor is unreadable — which the
 * scan treats as "cannot prove this is a separate file" and excludes. Free-standing rather than a
 * member so a test can reference it directly, and so the constructor default reads as the seam it is.
 */
internal fun realPathOrNull(path: String): String? =
    runCatching { Paths.get(path).toRealPath().toString() }.getOrNull()
