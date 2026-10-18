package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SD02 pins: the swap-detection decision core must stay pure and predictable. */
class VolumeRootMatcherTest {

    @Test
    fun `SAF tree root on an SD card maps to its volume uuid`() {
        assertEquals(
            "6E0E-FBB0",
            VolumeRootMatcher.volumeUuidOf(
                "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS",
            ),
        )
        // Tree+document form (persisted grants often carry both segments).
        assertEquals(
            "6E0E-FBB0",
            VolumeRootMatcher.volumeUuidOf(
                "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS/document/6E0E-FBB0%3AROMS%2Fsnes",
            ),
        )
    }

    @Test
    fun `primary and internal roots carry no volume`() {
        assertNull(
            VolumeRootMatcher.volumeUuidOf(
                "content://com.android.externalstorage.documents/tree/primary%3AROMs",
            ),
        )
        assertNull(VolumeRootMatcher.volumeUuidOf("/storage/emulated/0/ROMs"))
        assertNull(VolumeRootMatcher.volumeUuidOf("/storage/self/primary/ROMs"))
    }

    @Test
    fun `plain storage path maps to its volume`() {
        assertEquals("6E0E-FBB0", VolumeRootMatcher.volumeUuidOf("/storage/6E0E-FBB0/ROMS"))
    }

    @Test
    fun `unknown providers and malformed uris carry no volume`() {
        assertNull(VolumeRootMatcher.volumeUuidOf("content://com.example.other/tree/6E0E-FBB0%3AROMS"))
        assertNull(VolumeRootMatcher.volumeUuidOf("content://com.android.externalstorage.documents/root"))
        assertNull(VolumeRootMatcher.volumeUuidOf("content://com.android.externalstorage.documents/tree/"))
        assertNull(VolumeRootMatcher.volumeUuidOf(""))
    }

    @Test
    fun `rootPresent flips only removable roots whose volume is absent`() {
        val mounted = setOf("6E0E-FBB0")
        val cardRoot = "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS"
        val otherCard = "content://com.android.externalstorage.documents/tree/1234-ABCD%3AROMS"
        val internal = "content://com.android.externalstorage.documents/tree/primary%3AROMs"
        assertTrue(VolumeRootMatcher.rootPresent(cardRoot, mounted))
        assertFalse(VolumeRootMatcher.rootPresent(otherCard, mounted))
        // Internal + unparseable roots NEVER flip — a bad parse must not strand a library.
        assertTrue(VolumeRootMatcher.rootPresent(internal, mounted))
        assertTrue(VolumeRootMatcher.rootPresent("garbage", emptySet()))
        // Card root with nothing mounted at all = absent.
        assertFalse(VolumeRootMatcher.rootPresent(cardRoot, emptySet()))
    }

    @Test
    fun `volume uuid comparison is case-insensitive via uppercase normalization`() {
        assertTrue(
            VolumeRootMatcher.rootPresent(
                "content://com.android.externalstorage.documents/tree/6e0e-fbb0%3AROMS",
                setOf("6E0E-FBB0"),
            ),
        )
    }
}
