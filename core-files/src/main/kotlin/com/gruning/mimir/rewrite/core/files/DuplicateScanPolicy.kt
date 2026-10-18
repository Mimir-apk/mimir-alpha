package com.gruning.mimir.rewrite.core.files

import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState

/**
 * MIMIR ADDITION — not vendored from upstream `SysAdminDoc/FileExplorer`; that clone has no
 * duplicate-detection surface of any kind. The pure half of the file manager's duplicates review:
 * what may be scanned, how candidates are pruned, and how confirmed digests become reviewable
 * groups. The I/O half — canonicalising paths and actually hashing — lives in [DuplicateScanner],
 * mirroring this module's own split between pure policy ([LocalListingPolicy],
 * [ExtractDestinationPolicy]) and the classes that touch the filesystem ([LocalFileRepository],
 * [ManagedFileOperations]).
 *
 * ## Why this exists now
 *
 * The manager is gaining a move/copy destination that targets the folder a platform's library
 * already lives in. **A copy means the file then exists twice, and the scanner reads both
 * locations.** The adopted design anticipated this: its Storage screen carries a duplicates review
 * whose own copy is *"Nothing is removed until you pick which copy stays"*
 * .
 *
 * That sentence is this file's hardest constraint, so it is worth being blunt about the shape of
 * the code rather than only its comments: **there is no removal path here, of any kind.** No
 * delete, no "resolve", no winner, no loser, no auto-pick. This object and [DuplicateScanner]
 * produce a *reviewable description of what is on disk* and nothing else. If removal is ever
 * wired, it goes through the existing staged, journalled, Undo-able queue
 * ([ManagedFileOperations.stageDelete] → [FileQueueState.UndoAvailable] → [FileOperationQueue]),
 * exactly like every other destructive verb in this module — never through a new path invented
 * here. Note deliberately that the vocabulary below carries no winner/loser words at all; that
 * language belongs to the *library's* automatic fold (`GameDao.stampGroupWinner`/`foldIntoGroup`,
 * `DuplicateGroupKind`), which is a different feature with the opposite posture — it collapses and
 * hides without asking, and this screen promises the reverse.
 *
 * ## What "duplicate" means here, precisely — and what it deliberately does not
 *
 * Three different questions could wear this word. Only the first is answered here:
 *
 *  1. **Same bytes** — ANSWERED. Two files are duplicates when they are the same size and their
 *     whole-file [DIGEST_ALGORITHM] digests agree. Nothing else counts, and nothing weaker is ever
 *     called a duplicate: a size match alone is a *candidate*, never a result.
 *  2. **Same bytes, different name** — ANSWERED, and this is load-bearing rather than incidental.
 *     A file's name is not part of its identity here. It cannot be, because the manager's own
 *     conflict handling manufactures exactly this case: *Keep both*
 *     ([FileConflictChoice.KEEP_BOTH]) resolves a collision by writing the incoming file under a
 *     deterministic [ConflictNamePolicy] suffix — same bytes, different name, by construction. A
 *     duplicates review added *because copying makes a file exist twice* that could not see
 *     copying's own collision artifacts would be self-defeating.
 *  3. **Same game, different dump** — NOT ANSWERED, deliberately, and it is a genuinely harder
 *     question than the one asked. It is also already answered elsewhere in this tree, by an
 *     engine this module must not rebuild: `RomFingerprinter`'s own KDoc
 *     (`core-metadata/.../RomFingerprinter.kt`) names `datHash` → `collapseKey` as *"the correct,
 *     already-existing channel for 'these are the same game', distinct from 'these two files are
 *     the identical bytes I found lying on disk twice.'"* This object implements the second of
 *     those two, in the second of those two senses.
 *
 *     There is also a safety argument, independent of ownership. Byte-identity is the only
 *     definition under which *"pick which copy stays"* is a safe question to put to a player: two
 *     byte-identical files are genuinely redundant, so keeping either loses nothing. Two different
 *     dumps of one game are **not** redundant — they differ in region, revision, header convention
 *     or trim — and offering one of them for removal review would be data loss dressed up as
 *     cleanup.
 *
 * ## The design's recorded rule is narrower than this, and that divergence is DISCLOSED
 *
 * The surviving transcription of the design defines the feature as **"Duplicate detection — same
 * name and checksum in two folders"** .
 * That is *narrower* than byte-identity: it additionally requires the names to match.
 *
 * This engine implements the broader relation and exposes the design's narrower one as a named,
 * pinned predicate over the result ([isSameNameAcrossFolders]) — rather than the other way round —
 * for three reasons, all recorded so a reviewer can overturn them on evidence rather than taste:
 *
 *  - The narrower rule **cannot see the artifacts the motivating feature creates.** *Keep both*
 *    produces same-checksum-different-name pairs; a literal same-name rule would show the player
 *    nothing after exactly the operation that prompted the section.
 *  - Name-plus-checksum is **derivable** from byte-identity; the reverse is not. Building the
 *    subset would foreclose the broader reading permanently, whereas building the superset with
 *    the subset named keeps both available for the presentation decision.
 *  - The design line is a **one-line table cell in a second-hand transcription** of a comp that is
 *    no longer on disk (the file's absence was verified; claims about its contents remain second-hand). Its accuracy remains uncertain. Contrast *"Nothing is removed until you pick which copy
 *    stays"* from the same document, which remains binding.
 *
 * **Which relation the review shows remains an unresolved presentation decision.**
 * Both readings are reachable from one scan; nothing here picks between them.
 *
 * ## What this costs
 *
 * Hashing every file in a real library is not free, so the scan is pruned by a filter that is
 * *provably sound* rather than heuristic: a file whose size is unique among the candidates cannot
 * be byte-identical to any of them, so it is skipped without ever being opened, and no duplicate
 * can be missed by doing so. How much that actually saves on the reference card is **UNKNOWN** —
 * cartridge platforms dump at fixed power-of-two sizes, so size collisions in a ROM set are
 * expected to be common rather than rare, and no measurement of the collision rate exists.
 *
 * The only device-grounded envelope for a hash-everything pass over the example library is the identify
 * pass: **5,529 files in ~8 min 20 s at 11.1 files/s** on the dirty card
 * . That is a **loose upper
 * bound, not a hashing throughput number** — that pass also did DAT matching and Room writes, and
 * the recorded residual ceiling was *"Room-side query churn"* with the hashers
 * sitting *"~idle"*, none of which a duplicate scan does. Pure hashing throughput on the device is
 * **UNKNOWN**; no pure-hashing device measurement is available.
 *
 * The fallback when hashing is too slow is [DuplicateHashBudget]: a caller-supplied byte cap that
 * stops the scan and **says so** ([DuplicateScanResult.isComplete]) rather than quietly returning
 * a short list. No partial-file or head-only digest tier is implemented — only full-digest
 * equality ever earns the word "duplicate" here, and a prefix match is not that.
 */
object DuplicateScanPolicy {

    /**
     * The digest every duplicate claim in this module is proven with.
     *
     * SHA-1, matching `FileBrowserStore.CHECKSUM_ALGORITHM` — the manager already made this choice
     * for its inspector's Verify button, and a duplicates review that hashed with a different
     * algorithm would make the two surfaces incomparable. The original grounding holds:
     * [LocalFileRepository.getChecksum] defaults to **SHA-256**, and the bundled DAT corpus is
     * keyed on SHA-1/CRC32, so the default matches nothing in it
     * (pinned in this module by
     * `LocalFileRepositoryTest.getChecksum computes real digests, and its default is not the DAT
     * corpus algorithm`).
     *
     * **Necessary but not sufficient to talk to that corpus, and nothing here tries to.** Reading
     * the adoption note as "duplicate detection comes from the DAT corpus" would be a mistake this
     * comment exists to head off. A whole-file SHA-1 is *not* the DAT hash for several real
     * formats: `RomFingerprinter` strips the 16-byte iNES header before hashing a `.nes`, reads a
     * `.chd`'s combined SHA-1 out of its header, and fingerprints the single ROM entry inside a
     * one-ROM `.zip` rather than the container. Consulting the corpus is therefore deliberately
     * out of scope — the digest below is the *byte-identity* family, the same notion as
     * `RomFingerprint.exactRawSha1` ("hashing this file's bytes exactly as they sit on disk, with
     * zero transformation") and the `raw-sha1:` provenance tag it is persisted under, computed
     * independently here. It is emitted as bare lowercase hex, exactly as
     * [LocalFileRepository.getChecksum] returns it, and is deliberately **not** tagged `raw-sha1:`
     * — that prefix is `core-storage`'s persistence vocabulary and this result is transient.
     *
     * One honesty note, since a reviewer will otherwise raise it: SHA-1 is not collision-resistant
     * against a *deliberate* adversary. That is irrelevant here and is not why it was chosen — the
     * inputs are the player's own files, a size match is required as well, and this scanner removes
     * nothing on the strength of a digest.
     */
    const val DIGEST_ALGORITHM: String = "SHA-1"

    /**
     * Whether [item] may enter a duplicate scan at all. Pure, no I/O, and **public on purpose** so
     * a future review surface can explain why one specific row is absent without the engine having
     * to emit a list of every file it declined to consider.
     *
     * Items rejected here are simply out of scope; they are not reported as skips
     * ([DuplicateScanResult.skipped] would otherwise be dominated by every directory on the card).
     *
     * Four exclusions, each for its own reason:
     *
     *  - **Directories.** A directory is not a copy of a file. Recursive tree comparison is a
     *    different feature and is not implemented.
     *  - **Symlinks.** A symlink is a pointer, not a copy; hashing one and calling the result a
     *    redundant duplicate would invite removing the only real file. Note this catches only the
     *    case where the *listed entry itself* is a link — the far more dangerous case, where an
     *    *ancestor* directory is a link, is invisible to [FileItem.isSymlink] and is handled by
     *    [DuplicateScanner]'s canonicalisation stage instead.
     *  - **Empty files.** Every zero-byte file is byte-identical to every other zero-byte file, so
     *    admitting them herds every empty save placeholder and stub on the volume into one enormous
     *    "duplicate" group that is technically true and completely useless. This also excludes most pseudo-filesystem entries, which report size 0; that is a side
     * benefit rather than the reason for excluding empty files.
     *  - **Unreadable files.** They cannot be hashed, so they can never be proven duplicate;
     *    admitting them only to fail later would fill the skip list with foreseeable failures.
     *
     * Content class is deliberately NOT consulted: two identical box-art PNGs are as real a
     * duplicate as two identical ROMs, and [LocalListingPolicy]'s support/media-art vocabulary is
     * about *display demotion*, not about what exists twice. Which files reach a scan at all is the
     * caller's scoping decision — this engine never enumerates anything itself.
     */
    fun isScanCandidate(item: FileItem): Boolean =
        !item.isDirectory &&
            !item.isSymlink &&
            item.isReadable &&
            item.size > 0L

    /**
     * The sound prune. Groups [items] by [FileItem.size] and returns only those size classes with
     * at least two members — the sets in which a byte-identical pair could possibly exist. Pure,
     * no I/O, nothing opened.
     *
     * A file whose size is unique among the candidates provably has no byte-identical partner
     * among them, so dropping it cannot cause a miss. This is the whole cost argument for the
     * scan, and it is a correctness-preserving prune rather than a heuristic.
     *
     * Sizes come from the caller's inventory rather than a fresh `stat`. A stale size can only
     * cause a **miss** — two identical files sorted into different classes and never compared —
     * never a false duplicate, because full-digest equality is what actually earns the word.
     *
     * Deterministic: classes are ordered by size ascending, and members within a class by path.
     */
    fun sizeCollisions(items: List<FileItem>): List<List<FileItem>> =
        items.filter(::isScanCandidate)
            .groupBy(FileItem::size)
            .toSortedMap()
            .values
            .filter { it.size >= 2 }
            .map { members -> members.sortedBy(FileItem::path) }

    /**
     * Turns confirmed digests into reviewable groups. Pure — [digests] is a path-keyed map of
     * already-computed digests, so the whole grouping decision is drivable from a plain JUnit test
     * with no filesystem and no hashing at all.
     *
     * A file with no entry in [digests] (it failed, or the budget deferred it) simply does not
     * participate. A digest shared by fewer than two files is not a group.
     *
     * [candidates] must already have passed [isScanCandidate] — this function trusts that rather
     * than re-filtering, so a caller that hand-fed it a directory with a digest entry would get a
     * directory in a group. [DuplicateScanner] always applies admission and canonicalisation first,
     * so no scan can reach that state; the note is for anyone driving these pure functions directly.
     *
     * Groups are ordered by digest ascending and their members by path — a purely mechanical total
     * order, chosen because it encodes no presentation claim. How the review *displays* them
     * (largest reclaim first, most copies first, by folder) is the surface's decision, not this
     * engine's.
     */
    fun groupByDigest(candidates: List<FileItem>, digests: Map<String, String>): List<DuplicateGroup> =
        candidates.filter { digests.containsKey(it.path) }
            .groupBy { digests.getValue(it.path) }
            .filterValues { it.size >= 2 }
            .map { (digest, files) ->
                val members = files.sortedBy(FileItem::path)
                DuplicateGroup(
                    digest = digest,
                    sizeBytes = members.first().size,
                    files = members,
                )
            }
            .sortedBy(DuplicateGroup::digest)

    /**
     * **The design's own recorded rule, named and pinned rather than assumed:** *"Duplicate
     * detection — same name and checksum in two folders"*
     * .
     *
     * True when every file in [group] shares one name and they live in at least two distinct
     * parent folders. Since the group is already proven byte-identical, "and checksum" is
     * satisfied by the group's existence.
     *
     * **The two conjuncts are not independent: same-name implies two-folders.** A filesystem
     * cannot hold two entries with one name in one directory, so after [DuplicateScanner]'s
     * canonicalisation stage has collapsed path aliases, a same-named group necessarily spans
     * folders. Both are still evaluated, for literal fidelity to the recorded wording and so that a
     * reviewer can see the redundancy is known rather than overlooked.
     *
     * That aliasing caveat is exactly why canonicalisation is not optional: `/sdcard/x.gba` and
     * `/storage/emulated/0/x.gba` are literally *"same name and checksum in two folders"* — and
     * they are **one file**. Canonicalisation is what makes the design's reading safe, not merely
     * this file's broader one.
     *
     * Name comparison is exact. Whether it should be case-insensitive on FAT/FUSE volumes, where
     * `POKEMON.GBA` and `Pokemon.gba` cannot coexist in one directory anyway, is **unresolved** and is
     * folded into the pending grouping policy rather than decided here.
     */
    fun isSameNameAcrossFolders(group: DuplicateGroup): Boolean =
        group.files.map(FileItem::name).distinct().size == 1 && group.folders.size >= 2
}

/**
 * A set of files proven byte-identical: same [sizeBytes], same [digest]
 * ([DuplicateScanPolicy.DIGEST_ALGORITHM]), at least two members.
 *
 * Carries no notion of a preferred, winning or default copy, and no removal affordance. The design
 * promises the player picks which copy stays; a field here nominating one would quietly pre-empt
 * that choice.
 */
data class DuplicateGroup(
    /** Lowercase hex [DuplicateScanPolicy.DIGEST_ALGORITHM] digest shared by every member. */
    val digest: String,
    /** Byte size shared by every member. */
    val sizeBytes: Long,
    /** At least two files, ordered by path. Every one of them is a real, separate file on disk. */
    val files: List<FileItem>,
) {
    /** How many copies exist. Always >= 2 for a group this engine emits. */
    val copyCount: Int get() = files.size

    /** Distinct parent folders these copies live in, ordered, for a review that groups by location. */
    val folders: List<String> get() = files.map { it.path.substringBeforeLast('/', "") }.distinct().sorted()

    /**
     * Bytes that would stop being spent twice if the player kept exactly one copy — arithmetic
     * describing the group, **not** a recommendation to remove anything and not a promise anything
     * will be.
     *
     * **Hard links would make this an overstatement, and they are not detected.** Two hard links to
     * one inode are byte-identical and have *different* real paths, so [DuplicateScanner]'s
     * canonicalisation deliberately does not collapse them — unlike a symlink alias, they really are
     * two directory entries, and removing one genuinely leaves the other working. The overstatement
     * is therefore about *reclaimed space*, not about data loss.
     *
     * Judged proportionate to disclose rather than solve: a FAT32/exFAT card cannot represent a hard
     * link at all, which is what a player's SD card is formatted as. Solving it properly means
     * identity by device+inode (`BasicFileAttributes.fileKey()`) instead of real path, and whether
     * that is stable across Android's FUSE shared-storage layer is **UNKNOWN** — it has not been established on a device, and swapping to an identity that might silently return null on the
     * target filesystem would trade a small overstatement for a broken alias defence. The identity
     * function is a constructor seam ([DuplicateScanner]) precisely so a device-verified
     * implementation can replace it without touching this file.
     */
    val redundantBytes: Long get() = sizeBytes * (copyCount - 1)
}

/** Why a candidate that entered the scan did not end up in a group. */
enum class DuplicateSkipReason {
    /**
     * This path resolved to the same real file as another path already in the scan — an alias, not
     * a copy. Collapsing it is what stops the review from offering the player a choice between one
     * file and itself.
     */
    ALIAS_OF_ANOTHER_PATH,

    /** The path could not be resolved to a real file at all (vanished, or unreadable ancestor). */
    PATH_UNRESOLVED,

    /** Opening or hashing the file failed. Reported, never fatal to the rest of the scan. */
    DIGEST_FAILED,

    /** [DuplicateHashBudget] ran out before this file's size class could be hashed. */
    BUDGET_EXHAUSTED,
}

/** One candidate that entered the scan and did not reach a group, with the reason. */
data class DuplicateScanSkip(val path: String, val reason: DuplicateSkipReason)

/**
 * What a scan found, and — just as importantly — what it could not see.
 *
 * There is no removal API on this type or anywhere else in this lane. It describes disk.
 */
data class DuplicateScanResult(
    /** Byte-identical sets, at least two files each. Ordered by digest. */
    val groups: List<DuplicateGroup>,
    /** Candidates that entered the scan but reached no group, each with its reason. */
    val skipped: List<DuplicateScanSkip>,
    /** How many files were actually opened and digested. */
    val filesHashed: Int,
    /** How many bytes were actually read to produce those digests. */
    val bytesHashed: Long,
) {
    /** Paths collapsed as aliases of another path in the same scan. Correctness, not loss. */
    val aliased: List<String>
        get() = skipped.filter { it.reason == DuplicateSkipReason.ALIAS_OF_ANOTHER_PATH }.map { it.path }

    /** Paths that could not be read or resolved. The picture is incomplete by that much. */
    val unreadable: List<String>
        get() = skipped.filter {
            it.reason == DuplicateSkipReason.DIGEST_FAILED || it.reason == DuplicateSkipReason.PATH_UNRESOLVED
        }.map { it.path }

    /** Paths the budget never got to. Deferred, not judged. */
    val deferred: List<String>
        get() = skipped.filter { it.reason == DuplicateSkipReason.BUDGET_EXHAUSTED }.map { it.path }

    /**
     * Whether every candidate was actually examined.
     *
     * **An alias collapse does not make a scan incomplete** — it makes it correct, so
     * [DuplicateSkipReason.ALIAS_OF_ANOTHER_PATH] deliberately does not count against this. Anything
     * unread or deferred does.
     *
     * A review surface showing `false` must say so rather than presenting the list as exhaustive.
     */
    val isComplete: Boolean
        get() = skipped.none { it.reason != DuplicateSkipReason.ALIAS_OF_ANOTHER_PATH }
}

/**
 * The byte cap on a scan, and the honest answer to "what if hashing is too slow".
 *
 * Shaped after this module's own [ArchiveExtractionBudget], with one deliberate deviation: that
 * budget **throws** when a limit is crossed, because a half-extracted archive is a failure. This
 * one does not. Running out of budget here is a normal, expected outcome that yields a smaller but
 * still entirely truthful answer, reported through [DuplicateScanResult.isComplete] — an exception
 * would throw away the groups already proven.
 *
 * No default cap is shipped: choosing a byte limit remains an unresolved tuning decision.
 * [unlimited] keeps the full-scan behavior:
 * the review is a screen the player deliberately opened, and hashing everything is what it means to
 * answer their question. [ofBytes] exists so a cap can be applied once one is selected. Picking
 * a number here and calling it a default would be exactly the invented constant this tree forbids.
 *
 * Not thread-safe; one scan owns one budget, the same way one extraction owns one
 * [ArchiveExtractionBudget].
 */
class DuplicateHashBudget private constructor(private val maxBytes: Long) {

    var bytesHashed: Long = 0L
        private set

    var filesHashed: Int = 0
        private set

    /**
     * Whether [bytes] more may be read. The subtraction is arranged so the comparison cannot
     * overflow on a large cap, the same idiom [ArchiveExtractionBudget.register] uses.
     */
    fun canAfford(bytes: Long): Boolean =
        maxBytes == UNLIMITED || bytes <= maxBytes - bytesHashed

    /** Record that [bytes] were read for one file. */
    fun record(bytes: Long) {
        bytesHashed += bytes
        filesHashed++
    }

    companion object {
        /** Sentinel for "no cap"; [canAfford] short-circuits on it rather than doing arithmetic. */
        const val UNLIMITED: Long = -1L

        /** Hash whatever the scan asks for. The engine's shipped behaviour until a cap is selected. */
        fun unlimited(): DuplicateHashBudget = DuplicateHashBudget(UNLIMITED)

        /** Stop after [maxBytes] have been read, and report the rest as deferred. */
        fun ofBytes(maxBytes: Long): DuplicateHashBudget =
            DuplicateHashBudget(maxBytes.coerceAtLeast(0L))
    }
}
