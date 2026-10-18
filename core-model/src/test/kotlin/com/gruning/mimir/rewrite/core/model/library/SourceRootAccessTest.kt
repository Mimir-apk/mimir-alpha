package com.gruning.mimir.rewrite.core.model.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which registered folders a scan may only trust while "All files access" is held. */
class SourceRootAccessTest {

    private val own = "com.gruning.mimir.rewrite"

    @Test
    fun `shared storage folders need the grant on internal storage and on a card`() {
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/ROMs", own))
        assertTrue(sourceRootNeedsAllFilesAccess("/sdcard/Roms", own))
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/6A7F-FCFE/ROMs", own))
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0", own))
    }

    @Test
    fun `a storage-looking name that is not storage does not`() {
        assertFalse(sourceRootNeedsAllFilesAccess("/sdcardish/ROMs", own))
        assertFalse(sourceRootNeedsAllFilesAccess("/storagebox/ROMs", own))
    }

    @Test
    fun `a SAF tree is judged by its provider, not by the grant`() {
        assertFalse(
            sourceRootNeedsAllFilesAccess("content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs", own),
        )
    }

    @Test
    fun `Mimir's own Android data and media folders are readable without the grant`() {
        assertFalse(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/data/$own/files/ROMs", own))
        assertFalse(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/media/$own/ROMs", own))
        assertFalse(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/obb/$own", own))
        assertFalse(sourceRootNeedsAllFilesAccess("/storage/6A7F-FCFE/Android/data/$own/files", own))
        assertFalse(sourceRootNeedsAllFilesAccess("/sdcard/Android/media/$own/ROMs", own))
    }

    @Test
    fun `another app's folder needs the grant, and so does the folder that holds them all`() {
        // Cert review P2-1: another app's folder lists empty without the grant, and a prune would take that for a deletion.
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/media/com.other.app/ROMs", own))
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/data/com.other.app/files", own))
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/data", own))
    }

    @Test
    fun `a look-alike of Mimir's folder needs the grant`() {
        // Nested deeper in the path, not directly under the volume's root.
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/ROMs/Android/media/$own/x", own))
        // A package that only starts with Mimir's name.
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/data/$own.devlab/files", own))
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/data/${own}x", own))
    }

    @Test
    fun `with no package named nothing is exempt`() {
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/data/$own/files/ROMs"))
        assertTrue(sourceRootNeedsAllFilesAccess("/storage/emulated/0/Android/data/$own/files/ROMs", null))
    }

    @Test
    fun `a private app path never needs the grant`() {
        assertFalse(sourceRootNeedsAllFilesAccess("/data/user/0/$own/files/ROMs", own))
        assertFalse(sourceRootNeedsAllFilesAccess("ROMs", own))
    }

    @Test
    fun `only a card or a drive is a removable volume`() {
        assertTrue(sourceRootIsOnRemovableVolume("/storage/6A7F-FCFE/ROMs"))
        assertTrue(sourceRootIsOnRemovableVolume("/storage/6A7F-FCFE"))
        assertTrue(sourceRootIsOnRemovableVolume("/mnt/media_rw/6A7F-FCFE/ROMs"))
        assertFalse(sourceRootIsOnRemovableVolume("/storage/emulated/0/ROMs"))
        assertFalse(sourceRootIsOnRemovableVolume("/storage/self/primary/ROMs"))
        assertFalse(sourceRootIsOnRemovableVolume("/sdcard/Roms"))
        assertFalse(sourceRootIsOnRemovableVolume("content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs"))
    }
}
