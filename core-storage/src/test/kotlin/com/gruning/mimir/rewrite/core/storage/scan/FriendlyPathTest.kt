package com.gruning.mimir.rewrite.core.storage.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the plain-file-path branch of [FriendlyPath] (the design constraint
 * — the path must read "SD card › Mimir › Roms", never a raw storage UUID). The SAF `content://` branch
 * uses `android.net.Uri` and is device/instrumented-validated; here we cover the file-path rendering
 * that needs no Android framework.
 */
class FriendlyPathTest {

    @Test
    fun `internal storage path renders friendly with volume label`() {
        assertEquals(
            "Internal storage › Mimir › Roms",
            FriendlyPath.of("/storage/emulated/0/Mimir/Roms"),
        )
    }

    @Test
    fun `sdcard alias renders as internal storage`() {
        assertEquals("Internal storage › Roms", FriendlyPath.of("/sdcard/Roms"))
    }

    @Test
    fun `removable SD card UUID is never shown raw`() {
        val friendly = FriendlyPath.of("/storage/1234-5678/Mimir/Roms")
        assertTrue("UUID must be hidden", !friendly.contains("1234-5678"))
        assertEquals("SD card › Mimir › Roms", friendly)
    }

    @Test
    fun `deep path keeps all folder segments`() {
        assertEquals(
            "Internal storage › Mimir › local › Roms › PSX",
            FriendlyPath.of("/storage/emulated/0/Mimir/local/Roms/PSX"),
        )
    }

    @Test
    fun `unknown-shape path degrades to its trailing segments`() {
        val friendly = FriendlyPath.of("/data/user/0/app/files/roms")
        assertTrue(friendly.isNotBlank())
        assertTrue(friendly.contains("roms"))
    }

    // ---- SAF tree URIs. -----------------------------------------------------------------------
    // The reason these exist: FriendlyPath used to decode through `android.net.Uri.decode`, which throws
    // "not mocked" under plain JUnit, so every SAF-shaped case was unreachable from a JVM test and none
    // was written. The decoder was replaced with SourceRootIdentity's specifically to make them testable
    // — and then the tests were still not written. This is that owed coverage.

    @Test
    fun `SAF tree URI on a removable card renders friendly and hides the volume UUID`() {
        val friendly = FriendlyPath.of(
            "content://com.android.externalstorage.documents/tree/1234-5678%3AMimir%2FRoms",
        )
        assertEquals("SD card › Mimir › Roms", friendly)
        assertTrue("the raw volume UUID must never reach the player", !friendly.contains("1234-5678"))
        assertTrue("nothing percent-encoded may survive into a label", !friendly.contains("%"))
    }

    @Test
    fun `SAF tree URI on the primary volume reads as internal storage`() {
        assertEquals(
            "Internal storage › Mimir › Roms",
            FriendlyPath.of("content://com.android.externalstorage.documents/tree/primary%3AMimir%2FRoms"),
        )
    }

    /**
     * The whole point of decoding a percent-escape RUN rather than one character at a time. A four-byte
     * UTF-8 sequence like 🎮 arrives as `%F0%9F%8E%AE`; decoded per character each byte becomes its own
     * unit and the folder name comes out as mojibake. This is the case a per-character decoder gets wrong
     * while still looking correct for every ASCII folder anyone tests with.
     */
    @Test
    fun `a multi-byte folder name survives percent decoding intact`() {
        assertEquals(
            "Internal storage › Games 🎮",
            FriendlyPath.of("content://com.android.externalstorage.documents/tree/primary%3AGames%20%F0%9F%8E%AE"),
        )
    }

    /** Two folders differing only in the LAST byte of a 4-byte sequence must not collapse together. */
    @Test
    fun `two emoji folder names that differ in one byte stay distinct`() {
        val pad = "content://com.android.externalstorage.documents/tree/primary%3A"
        val gamepad = FriendlyPath.of(pad + "%F0%9F%8E%AE")
        val bullseye = FriendlyPath.of(pad + "%F0%9F%8E%AF")

        assertEquals("Internal storage › 🎮", gamepad)
        assertEquals("Internal storage › 🎯", bullseye)
        assertTrue("a decoder that drops the low byte would make these equal", gamepad != bullseye)
    }

    @Test
    fun `a malformed escape degrades to the raw form instead of throwing`() {
        val friendly = FriendlyPath.of(
            "content://com.android.externalstorage.documents/tree/primary%3AMim%ZZir",
        )
        assertTrue("must still produce something", friendly.isNotBlank())
    }
}
