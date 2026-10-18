package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The covering rule, pinned against the Retroid Pocket Nova run of 2026-09-11.
 *
 * Ground truth for [nova]: `source_roots` held exactly one row,
 * `rootUri=content://com.android.externalstorage.documents/tree/<volume-id>%3AROMs`,
 * `locationKind=SAF_TREE_GRANT`, `friendlyLabel="SD card › ROMs"`, with all 19 games hanging off it,
 * while `dumpsys activity permissions` showed that tree persisted to the sibling package
 * `com.gruning.mimir.rewrite.devlab` and NOTHING persisted to `com.gruning.mimir.rewrite`. The
 * production process therefore saw an empty `persistedUriPermissions` list — which is the first case
 * below, and the one that produced 255 `Permission Denial` lines in 25 minutes.
 */
class StorageGrantAuditTest {

    private val nova = StoredRoot(
        rootUri = "content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs",
        locationKind = "SAF_TREE_GRANT",
        friendlyLabel = "SD card › ROMs",
    )

    private fun read(uri: String) = PersistedGrant(uri = uri, read = true)

    @Before
    fun reset() = StorageGrantAuditCoordinator.resetForTest()

    @Test
    fun `no persisted grants at all means every SAF root is lost`() {
        // The device case verbatim: the sibling .devlab package holds the tree, we hold nothing.
        assertEquals(listOf(nova), auditStorageGrants(listOf(nova), emptyList()))
    }

    @Test
    fun `a read grant on the exact tree covers the root`() {
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs"))
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(nova), grants))
    }

    @Test
    fun `a decoded grant uri covers an encoded root uri and vice versa`() {
        // Same grant, two spellings: a picker result arrives percent-encoded, a hand-built probe URI (or a
        // restored backup row) can arrive decoded. Comparing raw strings would read these as two unrelated
        // trees and report a healthy library as lost.
        val decodedGrant = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932:ROMs"))
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(nova), decodedGrant))

        val decodedRoot = nova.copy(
            rootUri = "content://com.android.externalstorage.documents/tree/7BF4-F932:ROMs",
        )
        val encodedGrant = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs"))
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(decodedRoot), encodedGrant))
    }

    @Test
    fun `an ancestor grant covers a root nested under it`() {
        val nested = nova.copy(
            rootUri = "content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs%2Fgamecube",
            friendlyLabel = "SD card › ROMs › gamecube",
        )
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs"))
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(nested), grants))
    }

    @Test
    fun `a volume-root grant covers a root on that volume`() {
        // On external storage the document id is "<volume>:<path>", so the volume root carries no trailing
        // slash and ':' is itself the separator.
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3A"))
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(nova), grants))
    }

    @Test
    fun `a grant on a descendant does not cover its parent root`() {
        // The direction that actually fails on device: access to ROMs/ps2 is not access to ROMs.
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs%2Fps2"))
        assertEquals(listOf(nova), auditStorageGrants(listOf(nova), grants))
    }

    @Test
    fun `a grant on a sibling folder does not cover the root`() {
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3ABIOS"))
        assertEquals(listOf(nova), auditStorageGrants(listOf(nova), grants))
    }

    @Test
    fun `ancestry is by path segment, never by raw string prefix`() {
        // "ROMs" must not cover "ROMsBackup" just because one string starts with the other.
        val backup = nova.copy(
            rootUri = "content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMsBackup",
            friendlyLabel = "SD card › ROMsBackup",
        )
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs"))
        assertEquals(listOf(backup), auditStorageGrants(listOf(backup), grants))
    }

    @Test
    fun `a grant on a different volume does not cover the root`() {
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs"))
        assertEquals(listOf(nova), auditStorageGrants(listOf(nova), grants))
    }

    @Test
    fun `a grant from a different provider authority does not cover the root`() {
        val grants = listOf(read("content://com.android.providers.downloads.documents/tree/7BF4-F932%3AROMs"))
        assertEquals(listOf(nova), auditStorageGrants(listOf(nova), grants))
    }

    @Test
    fun `a write-only grant does not count as covering`() {
        val writeOnly = listOf(
            PersistedGrant(
                uri = "content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs",
                read = false,
            ),
        )
        assertEquals(listOf(nova), auditStorageGrants(listOf(nova), writeOnly))
    }

    @Test
    fun `non-SAF roots are never reported`() {
        val managed = StoredRoot(
            rootUri = "/storage/emulated/0/Android/data/com.gruning.mimir.rewrite/files/library",
            locationKind = "MANAGED_PATH",
            friendlyLabel = "Mimir library",
        )
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(managed), emptyList()))
        // …and a managed root beside a lost SAF root does not dilute the answer either.
        assertEquals(listOf(nova), auditStorageGrants(listOf(managed, nova), emptyList()))
    }

    @Test
    fun `a SAF root whose uri cannot be parsed as a tree is reported lost, not skipped`() {
        // An unknown audit result must never render as a successful grant check.
        val broken = nova.copy(rootUri = "content://com.android.externalstorage.documents/document/7BF4-F932%3AROMs")
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs"))
        assertEquals(listOf(broken), auditStorageGrants(listOf(broken), grants))
    }

    @Test
    fun `a real device grant with spaces and brackets in its document id parses and matches`() {
        // Captured UID 10133: percent-escapes for slash, colon and space occur in one id, with raw parentheses.
        val biosUri = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADownload%2Fps2%20bios%20usa%2FSCPH-90001_BIOS_V18_USA_230_(NTSC)"
        val biosRoot = StoredRoot(
            rootUri = biosUri,
            locationKind = "SAF_TREE_GRANT",
            friendlyLabel = "Internal › Download › ps2 bios usa",
        )
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(biosRoot), listOf(read(biosUri))))
        assertEquals(
            SafTreeIdentity(
                authority = "com.android.externalstorage.documents",
                documentId = "primary:Download/ps2 bios usa/SCPH-90001_BIOS_V18_USA_230_(NTSC)",
            ),
            safTreeIdentity(biosUri),
        )
        // …and the parent folder's grant covers it, since real device grants are prefix grants.
        val parent = read("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        assertEquals(emptyList<StoredRoot>(), auditStorageGrants(listOf(biosRoot), listOf(parent)))
    }

    @Test
    fun `an unparseable grant is ignored rather than covering everything`() {
        val junk = listOf(read("file:///storage/7BF4-F932/ROMs"), read("not a uri"))
        assertEquals(listOf(nova), auditStorageGrants(listOf(nova), junk))
    }

    @Test
    fun `reported order follows the registry order so the primary source is offered first`() {
        val second = nova.copy(
            rootUri = "content://com.android.externalstorage.documents/tree/AAAA-BBBB%3AGames",
            friendlyLabel = "USB › Games",
        )
        assertEquals(listOf(nova, second), auditStorageGrants(listOf(nova, second), emptyList()))
    }

    @Test
    fun `percent decoding is path-shaped, so a plus sign stays a plus sign`() {
        // URLDecoder would turn "Mario+Luigi" into "Mario Luigi" and stop matching its own grant.
        assertEquals("Mario+Luigi", decodePercentEncoded("Mario+Luigi"))
        assertEquals("7BF4-F932:ROMs/gamecube", decodePercentEncoded("7BF4-F932%3AROMs%2Fgamecube"))
        assertEquals("Pokémon", decodePercentEncoded("Pok%C3%A9mon"))
        // A malformed escape is left alone rather than throwing.
        assertEquals("100%zz", decodePercentEncoded("100%zz"))
    }

    @Test
    fun `the ancestry primitive states the rule directly`() {
        assertTrue(isDocumentAncestorOrSame("7BF4-F932:ROMs", "7BF4-F932:ROMs"))
        assertTrue(isDocumentAncestorOrSame("7BF4-F932:ROMs", "7BF4-F932:ROMs/gamecube"))
        assertTrue(isDocumentAncestorOrSame("7BF4-F932:", "7BF4-F932:ROMs"))
        assertFalse(isDocumentAncestorOrSame("7BF4-F932:ROMs/ps2", "7BF4-F932:ROMs"))
        assertFalse(isDocumentAncestorOrSame("7BF4-F932:ROMs", "7BF4-F932:ROMsBackup"))
        assertFalse(isDocumentAncestorOrSame("", "7BF4-F932:ROMs"))
    }

    @Test
    fun `the coordinator publishes the verdict and the prompt honours LATER for the process`() {
        StorageGrantAuditCoordinator.publish(listOf(nova), emptyList())
        assertEquals(listOf(nova), StorageGrantAuditCoordinator.lostRoots.value)
        assertEquals(
            nova,
            storageGrantPromptRoot(
                StorageGrantAuditCoordinator.lostRoots.value,
                StorageGrantAuditCoordinator.promptDeferred.value,
            ),
        )

        StorageGrantAuditCoordinator.deferForProcess()
        assertTrue(StorageGrantAuditCoordinator.promptDeferred.value)
        assertNull(
            "LATER must silence the prompt without pretending the root came back",
            storageGrantPromptRoot(
                StorageGrantAuditCoordinator.lostRoots.value,
                StorageGrantAuditCoordinator.promptDeferred.value,
            ),
        )
        assertEquals(
            "the audit verdict itself must survive a LATER — only the prompt is hidden",
            listOf(nova),
            StorageGrantAuditCoordinator.lostRoots.value,
        )
    }

    @Test
    fun `a healthy library publishes an empty verdict and shows no prompt`() {
        val grants = listOf(read("content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs"))
        StorageGrantAuditCoordinator.publish(listOf(nova), grants)
        assertEquals(emptyList<StoredRoot>(), StorageGrantAuditCoordinator.lostRoots.value)
        assertNull(storageGrantPromptRoot(StorageGrantAuditCoordinator.lostRoots.value, false))
    }
}
