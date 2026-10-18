package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.data`. Upstream path:
 * `core/data/src/test/java/com/explorer/fileexplorer/core/data/ArchiveEntryPathPolicyTest.kt`.
 *
 * Rewritten from `kotlin.test` (`@Test`, `assertEquals`, `assertNull`) to `org.junit` — this
 * project's exclusive test convention (verified: zero `kotlin.test` imports anywhere in the tree;
 * `core-model`/`core-storage` both use `org.junit.Test` + `org.junit.Assert.*`). Assertion logic
 * is otherwise unmodified.
 *
 * Temp-directory prefix renamed `fileexplorer-rar-policy` -> `mimir-archive-policy-test`: a
 * cosmetic tidy, not a MIMIR DEVIATION on the scale of the staging-directory rename in
 * `FileOperationStaging.kt` — this string only ever touches the JVM's own temp directory during a
 * test run and is asserted against nowhere, so there is no player-facing storage concern (contrast
 * `FileOperationStaging.ROOT_DIRECTORY`, which lands on a player's SD card at the extraction
 * destination). Renamed anyway for two reasons: no upstream branding needed to survive outside a
 * provenance header comment, and upstream's own name said "rar" for a policy test that is not
 * RAR-specific — doubly stale now that RAR support is deleted.
 */
class ArchiveEntryPathPolicyTest {
    @Test
    fun allowsNestedEntryInsideDestination() {
        val root = Files.createTempDirectory("mimir-archive-policy-test")
        try {
            assertEquals(
                root.resolve("folder").resolve("file.txt").toFile().canonicalFile,
                ArchiveEntryPathPolicy.safeDestination(root.toString(), "folder/file.txt"),
            )
        } finally {
            root.deleteIfExists()
        }
    }

    @Test
    fun rejectsParentTraversalAndSiblingPrefixEscapes() {
        val root = Files.createTempDirectory("mimir-archive-policy-test")
        try {
            assertNull(ArchiveEntryPathPolicy.safeDestination(root.toString(), "../outside.txt"))
            assertNull(ArchiveEntryPathPolicy.safeDestination(root.toString(), "../${root.fileName}-sibling/outside.txt"))
        } finally {
            root.deleteIfExists()
        }
    }

    /**
     * MIMIR ADDITION — not vendored from upstream. Added 2026-08-13 after a differential test found
     * a real coverage hole.
     *
     * [ArchiveEntryPathPolicy.safeDestination] defends in two independent layers:
     *   1. [ArchiveEntryPathPolicy.normalizeEntryName] rejects `..`, absolute paths and drive letters
     *   2. the final containment check — `canonicalFile` must still sit under `root`
     *
     * For `..`-style entry names those layers are redundant, and the differential proved it: deleting
     * layer 2 entirely left ALL TEN tests green, so nothing pinned it. That is precisely the
     * "removing it keeps everything green" hazard this programme has been bitten by before.
     *
     * Layer 2 is not redundant in general. It is the ONLY defence against a **symlink escape**: an
     * entry name containing no `..` at all, which layer 1 therefore accepts, but whose canonical path
     * resolves through a pre-existing symlink to somewhere outside the destination. That is a real
     * attack against extracting into a directory a player already has on their SD card.
     *
     * This test fails if layer 2 is removed. Verified by differential, not assumed.
     */
    @Test
    fun rejectsSymlinkEscapeThatContainsNoParentTraversal() {
        val root = Files.createTempDirectory("mimir-archive-policy-symlink-root")
        val outside = Files.createTempDirectory("mimir-archive-policy-outside")
        val link = root.resolve("innocent")
        try {
            try {
                Files.createSymbolicLink(link, outside)
            } catch (unsupported: UnsupportedOperationException) {
                return // filesystem without symlink support — nothing to assert
            } catch (denied: java.io.IOException) {
                return // no privilege to create symlinks here
            }

            // Contains no "..", so normalizeEntryName (layer 1) accepts it outright.
            assertEquals("innocent/escaped.txt", ArchiveEntryPathPolicy.normalizeEntryName("innocent/escaped.txt"))

            // Layer 2 must still refuse it: the canonical path lands outside root via the symlink.
            assertNull(ArchiveEntryPathPolicy.safeDestination(root.toString(), "innocent/escaped.txt"))
        } finally {
            link.deleteIfExists()
            root.deleteIfExists()
            outside.deleteIfExists()
        }
    }
}
