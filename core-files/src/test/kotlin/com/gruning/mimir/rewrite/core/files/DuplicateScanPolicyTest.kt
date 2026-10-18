package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the duplicates review. Every decision here is driven with hand-built [FileItem]s
 * and a hand-built digest map — no filesystem, no hashing, no Robolectric (this module configures
 * none).
 *
 * `uri` is left at its `null` default throughout: `android.net.Uri` is an Android SDK type and this
 * module's unit tests run on the plain JVM, matching `LocalListingPolicyTest`'s own fixtures.
 */
class DuplicateScanPolicyTest {

    private fun item(
        path: String,
        size: Long = 100L,
        isDirectory: Boolean = false,
        isSymlink: Boolean = false,
        isReadable: Boolean = true,
    ) = FileItem(
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isDirectory = isDirectory,
        isSymlink = isSymlink,
        isReadable = isReadable,
    )

    // ---- what "duplicate" means ----

    /**
     * The motivating case, and the reason a file's NAME is not part of its identity here.
     *
     * "Copy into a platform's folder" that collides is resolved by *Keep both*
     * ([FileConflictChoice.KEEP_BOTH]), which writes the incoming file under a deterministic
     * [ConflictNamePolicy] suffix — so the artifact copying produces is same-bytes-DIFFERENT-name by
     * construction. A grouping key that included the name would miss exactly the files the feature
     * exists to find.
     */
    @Test
    fun `same bytes under different names are one group`() {
        val original = item("/sd/Roms/GBA/Pokemon Emerald.gba")
        val keptBoth = item("/sd/Roms/GBA/Pokemon Emerald (copy-4f2a91).gba")

        val groups = DuplicateScanPolicy.groupByDigest(
            candidates = listOf(original, keptBoth),
            digests = mapOf(original.path to "abc123", keptBoth.path to "abc123"),
        )

        assertEquals(
            "one digest shared by two files is one group, whatever the files are called",
            1,
            groups.size,
        )
        assertEquals(
            "both copies belong to it -- a name-inclusive key would have split them and found nothing",
            listOf(keptBoth.path, original.path),
            groups.single().files.map(FileItem::path),
        )
    }

    /** A size match is a candidate, never a result. Only full-digest equality earns the word. */
    @Test
    fun `two files of the same size with different bytes are never a group`() {
        val a = item("/sd/Roms/GBA/a.gba")
        val b = item("/sd/Roms/GBA/b.gba")

        val groups = DuplicateScanPolicy.groupByDigest(
            candidates = listOf(a, b),
            digests = mapOf(a.path to "aaaaaa", b.path to "bbbbbb"),
        )

        assertTrue("equal size is a candidate, not a duplicate: $groups", groups.isEmpty())
    }

    /**
     * The disclosed divergence, pinned in both directions so neither reading can be lost silently.
     *
     * The design's surviving transcription defines the feature as *"same name and checksum in two
     * folders"* . This engine detects the
     * broader byte-identity relation and exposes that narrower rule as a filter over the result — so
     * a *Keep both* artifact is still FOUND (it is a real duplicate), and is still correctly
     * reported as not matching the design's literal same-name wording.
     *
     * Which relation the review shows remains unresolved. This test
     * keeps both options available: build only the narrow rule and the broad one is gone.
     */
    @Test
    fun `the design's same-name rule filters byte-identity, and does not replace it`() {
        // The plain case: tidying a loose ROM into the platform's own folder, no collision.
        val looseInRomsRoot = item("/sd/Roms/Pokemon Emerald.gba")
        val copiedIn = item("/sd/Roms/GBA/Pokemon Emerald.gba")
        // The Keep-both case: the copy collided, so it landed under a ConflictNamePolicy suffix.
        // DIFFERENT folder AND different name -- so the name is the only thing that can decide it,
        // which is what makes this fixture discriminating rather than decorative.
        val keptBoth = item("/sd/Roms/GBA/Pokemon Emerald (copy-4f2a91).gba")

        val plainGroup = DuplicateScanPolicy.groupByDigest(
            candidates = listOf(looseInRomsRoot, copiedIn),
            digests = mapOf(looseInRomsRoot.path to "dup", copiedIn.path to "dup"),
        ).single()
        assertTrue(
            "one name across two folders is exactly the design's recorded rule",
            DuplicateScanPolicy.isSameNameAcrossFolders(plainGroup),
        )

        val keptBothGroup = DuplicateScanPolicy.groupByDigest(
            candidates = listOf(looseInRomsRoot, keptBoth),
            digests = mapOf(looseInRomsRoot.path to "dup", keptBoth.path to "dup"),
        ).single()
        assertEquals(
            "the Keep-both artifact really does span two folders, so the folder conjunct is " +
                "satisfied and the NAME is the only thing left to decide the rule",
            2,
            keptBothGroup.folders.size,
        )
        assertFalse(
            "a Keep-both artifact does NOT satisfy the design's same-name wording",
            DuplicateScanPolicy.isSameNameAcrossFolders(keptBothGroup),
        )
        assertEquals(
            "...but it IS still detected as a duplicate -- the broad relation is what makes the " +
                "narrow rule a choice rather than a ceiling",
            2,
            keptBothGroup.copyCount,
        )
    }

    /**
     * Pins the *second* conjunct of the cross-folder rule as live rather than
     * decorative.
     *
     * The group below **cannot exist on a real filesystem** — two entries cannot share one name in
     * one directory — and it is hand-built for exactly that reason: it is the only shape that
     * isolates the folder requirement, since in every reachable state same-name already implies
     * two-folders. That redundancy is documented on [DuplicateScanPolicy.isSameNameAcrossFolders];
     * this test is what stops it from being *assumed* redundant.
     */
    @Test
    fun `the two-folder conjunct is evaluated, not merely implied`() {
        val impossibleOnDisk = DuplicateGroup(
            digest = "abc",
            sizeBytes = 100L,
            files = listOf(item("/sd/Roms/same.gba"), item("/sd/Roms/same.gba")),
        )

        assertEquals("one folder, by construction", 1, impossibleOnDisk.folders.size)
        assertFalse(
            "the folder conjunct must actually be evaluated, even where nothing on disk can reach it",
            DuplicateScanPolicy.isSameNameAcrossFolders(impossibleOnDisk),
        )
    }

    // ---- the prune, and what never enters a scan ----

    /**
     * The cost argument, and the reason it is sound rather than heuristic: a file whose size is
     * unique among the candidates provably has no byte-identical partner among them, so skipping it
     * cannot cause a miss. It is never opened.
     */
    @Test
    fun `a file with a unique size is pruned before anything is opened`() {
        val classes = DuplicateScanPolicy.sizeCollisions(
            listOf(
                item("/sd/a.gba", size = 4_194_304L),
                item("/sd/b.gba", size = 4_194_304L),
                item("/sd/lonely.gba", size = 8_388_608L),
            ),
        )

        assertEquals("only the colliding size class survives the prune", 1, classes.size)
        assertEquals(
            "and it is the pair, never the file no partner can exist for",
            listOf("/sd/a.gba", "/sd/b.gba"),
            classes.single().map(FileItem::path),
        )
    }

    /**
     * Four admission refusals, each for its own stated reason (see [DuplicateScanPolicy.isScanCandidate]).
     *
     * The zero-byte rule is the one worth naming: every empty file is byte-identical to every other
     * empty file, so admitting them would herd every empty save placeholder on the card into one
     * enormous, useless "duplicate" group.
     */
    @Test
    fun `directories, symlinks, empty files and unreadable files never enter a scan`() {
        assertTrue(
            "an ordinary readable file with bytes is the only thing admitted",
            DuplicateScanPolicy.isScanCandidate(item("/sd/Roms/a.gba")),
        )
        assertFalse(
            "a directory is not a copy of a file",
            DuplicateScanPolicy.isScanCandidate(item("/sd/Roms", isDirectory = true)),
        )
        assertFalse(
            "a symlink is a pointer, not a copy -- calling it redundant invites removing the real file",
            DuplicateScanPolicy.isScanCandidate(item("/sd/Roms/link.gba", isSymlink = true)),
        )
        assertFalse(
            "every empty file is byte-identical to every other one; the group would be useless",
            DuplicateScanPolicy.isScanCandidate(item("/sd/Roms/empty.sav", size = 0L)),
        )
        assertFalse(
            "an unreadable file can never be proven duplicate, so it is out of scope, not a failure",
            DuplicateScanPolicy.isScanCandidate(item("/sd/Roms/locked.gba", isReadable = false)),
        )
    }

    /** Content class is deliberately not consulted: two identical box arts are two identical files. */
    @Test
    fun `media art and support files are scanned like anything else`() {
        val art = item("/sd/Roms/GBA/media/boxart.png")
        assertTrue(
            "LocalListingPolicy's demotion vocabulary is about display, not about what exists twice",
            DuplicateScanPolicy.isScanCandidate(art),
        )
        assertEquals(
            "and it is genuinely classified as media art -- this is not a misclassification loophole",
            FileContentClass.MEDIA_ART,
            LocalListingPolicy.classifyContent(art),
        )
    }

    // ---- the algorithm ----

    /**
     * A VALUE pin. [LocalFileRepository.getChecksum] defaults to SHA-256, and the bundled DAT corpus
     * is keyed on SHA-1/CRC32, so the default matches nothing in it — the same finding
     * `LocalFileRepositoryTest` pins one layer down, restated here because this is the constant a
     * future wiring site will actually read.
     */
    @Test
    fun `the digest algorithm is the corpus family, not getChecksum's default`() {
        assertEquals(
            "SHA-1 -- matching FileBrowserStore.CHECKSUM_ALGORITHM, so Verify and this review agree",
            "SHA-1",
            DuplicateScanPolicy.DIGEST_ALGORITHM,
        )
        assertNotEquals(
            "getChecksum's SHA-256 default matches nothing in the bundled corpus",
            "SHA-256",
            DuplicateScanPolicy.DIGEST_ALGORITHM,
        )
    }

    // ---- ordering, arithmetic, and the standing invariant ----

    @Test
    fun `groups and their members are deterministically ordered`() {
        val z = item("/sd/z.gba")
        val a = item("/sd/a.gba")
        val m = item("/sd/m.gba")
        val n = item("/sd/n.gba")

        val groups = DuplicateScanPolicy.groupByDigest(
            candidates = listOf(z, a, m, n),
            digests = mapOf(z.path to "bbb", a.path to "bbb", m.path to "aaa", n.path to "aaa"),
        )

        assertEquals("groups ascend by digest", listOf("aaa", "bbb"), groups.map(DuplicateGroup::digest))
        assertEquals(
            "members ascend by path, so a rerun reports the same rows in the same order",
            listOf("/sd/a.gba", "/sd/z.gba"),
            groups.last().files.map(FileItem::path),
        )
    }

    @Test
    fun `redundantBytes describes the group without nominating a copy`() {
        val group = DuplicateGroup(
            digest = "abc",
            sizeBytes = 4_194_304L,
            files = listOf(item("/sd/a.gba"), item("/sd/b.gba"), item("/sd/c.gba")),
        )

        assertEquals("three copies", 3, group.copyCount)
        assertEquals(
            "keeping one would stop two from being spent twice -- arithmetic, not a recommendation",
            8_388_608L,
            group.redundantBytes,
        )
    }

    /**
     * Duplicate scanning is read-only; removal requires an explicit selection of the copy to keep, so **no type here may
     * expose a removal, resolution or winner affordance at all**.
     *
     * Reflection, following `LocalFileRepositoryReadOnlyTest`'s own idiom for the same class of
     * guarantee. This fails the moment anyone adds `deleteDuplicates`, `resolveGroup`, `keepOnly` or
     * a `winner` field — which is exactly when someone should be told that removal belongs in the
     * staged, journalled, Undo-able queue ([ManagedFileOperations.stageDelete]) and not here.
     */
    @Test
    fun `no type in the duplicates lane exposes a removal or winner affordance`() {
        val forbidden = listOf(
            "delete", "remove", "purge", "erase", "trash", "discard",
            "winner", "loser", "keeponly", "resolve", "apply", "commit",
        )
        val types = listOf(
            DuplicateScanPolicy::class.java,
            DuplicateScanner::class.java,
            DuplicateGroup::class.java,
            DuplicateScanResult::class.java,
            DuplicateHashBudget::class.java,
            DuplicateScanSkip::class.java,
        )

        val offenders = types.flatMap { type ->
            (type.declaredMethods.map { it.name } + type.declaredFields.map { it.name })
                .filter { member -> forbidden.any { member.lowercase().contains(it) } }
                .map { "${type.simpleName}.$it" }
        }

        assertTrue(
            "this lane detects and describes; it must never be able to remove anything. Route " +
                "removal through ManagedFileOperations.stageDelete instead. Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    // ---- the budget ----

    @Test
    fun `the shipped budget is unlimited and affords any file`() {
        val budget = DuplicateHashBudget.unlimited()

        assertTrue(
            "no cap is shipped -- the cap value is a tuning decision this lane does not own",
            budget.canAfford(Long.MAX_VALUE),
        )
        budget.record(4_194_304L)
        assertTrue("and it stays affordable after spending", budget.canAfford(Long.MAX_VALUE))
        assertEquals("spending is still counted honestly", 4_194_304L, budget.bytesHashed)
        assertEquals("as is the file count", 1, budget.filesHashed)
    }

    @Test
    fun `a capped budget stops exactly at its cap and cannot overflow`() {
        val budget = DuplicateHashBudget.ofBytes(100L)

        assertTrue("the cap itself is affordable", budget.canAfford(100L))
        budget.record(100L)
        assertFalse("one byte past the cap is not", budget.canAfford(1L))

        assertTrue(
            "a cap near Long.MAX_VALUE must not overflow into a false refusal",
            DuplicateHashBudget.ofBytes(Long.MAX_VALUE).canAfford(Long.MAX_VALUE),
        )
        assertFalse(
            "a file larger than the whole cap is honestly refused rather than silently allowed",
            DuplicateHashBudget.ofBytes(10L).canAfford(11L),
        )

        // The discriminating overflow case: it needs bytes ALREADY SPENT, which the two assertions
        // above do not have. A naive `bytesHashed + bytes <= maxBytes` wraps to a negative here and
        // wrongly reports the file as affordable; the subtraction form this class uses cannot.
        val nearlyMaxed = DuplicateHashBudget.ofBytes(Long.MAX_VALUE)
        nearlyMaxed.record(100L)
        assertFalse(
            "after spending, a file the size of the entire cap must NOT be affordable -- naive " +
                "addition would overflow past Long.MAX_VALUE and silently allow it",
            nearlyMaxed.canAfford(Long.MAX_VALUE),
        )
    }
}
