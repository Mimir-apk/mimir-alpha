package com.gruning.mimir.rewrite.core.resolver.scan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha cert 2026-10-08, E3: the production lister's answer to "can a listing of this root be believed?". Revoking
 * "All files access" leaves a shared folder that still exists, still opens and lists empty, so the grant itself is
 * the proof (see [SourceLister.rootReadable]).
 */
class SourceWalkerRootReadableTest {

    private val own = "com.gruning.mimir.rewrite"
    private val mounted = "mounted"

    private fun walker(
        allFilesAccess: Boolean,
        storageState: (String) -> String = { mounted },
    ) = SourceWalker(
        SourceWalkLimits(),
        allFilesAccess = { allFilesAccess },
        ownPackage = own,
        storageState = storageState,
    )

    @Test
    fun `a shared storage folder is readable only while All files access is held`() = runBlocking {
        assertTrue(walker(allFilesAccess = true).rootReadable("/storage/emulated/0/ROMs"))
        assertFalse(walker(allFilesAccess = false).rootReadable("/storage/emulated/0/ROMs"))
        assertFalse(walker(allFilesAccess = false).rootReadable("/storage/6A7F-FCFE/ROMs"))
    }

    @Test
    fun `a SAF tree and Mimir's own folders do not depend on the grant`() = runBlocking {
        val withoutGrant = walker(allFilesAccess = false)

        assertTrue(
            withoutGrant.rootReadable("content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs"),
        )
        assertTrue(withoutGrant.rootReadable("/storage/emulated/0/Android/data/$own/files/ROMs"))
        assertTrue(withoutGrant.rootReadable("/data/user/0/$own/files/ROMs"))
    }

    @Test
    fun `another app's folder and a look-alike of Mimir's are not readable without the grant`() = runBlocking {
        // Cert review P2-1: such a folder lists empty, and a prune would take that for a deletion.
        val withoutGrant = walker(allFilesAccess = false)

        assertFalse(withoutGrant.rootReadable("/storage/emulated/0/Android/media/com.other.app/ROMs"))
        assertFalse(withoutGrant.rootReadable("/storage/emulated/0/ROMs/Android/media/$own/x"))
        assertTrue(walker(allFilesAccess = true).rootReadable("/storage/emulated/0/Android/media/com.other.app/ROMs"))
    }

    @Test
    fun `a card that is mid-mount is not readable even with the grant, and a read-only card is`() = runBlocking {
        val card = "/storage/6A7F-FCFE/ROMs"

        assertTrue(walker(allFilesAccess = true) { mounted }.rootReadable(card))
        assertTrue(walker(allFilesAccess = true) { "mounted_ro" }.rootReadable(card))
        assertFalse(walker(allFilesAccess = true) { "checking" }.rootReadable(card))
        assertFalse(walker(allFilesAccess = true) { "unmounted" }.rootReadable(card))
        assertFalse(walker(allFilesAccess = true) { "unknown" }.rootReadable(card))
    }

    @Test
    fun `internal storage is never asked for a mount state`() = runBlocking {
        val asked = mutableListOf<String>()
        val internal = walker(allFilesAccess = true) { path -> asked += path; "checking" }

        assertTrue(internal.rootReadable("/storage/emulated/0/ROMs"))
        assertTrue(internal.rootReadable("/sdcard/Roms"))
        assertTrue("only a removable volume is asked", asked.isEmpty())
    }
}
