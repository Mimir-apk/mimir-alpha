package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The three strings the player reads, pinned literally — same discipline as [AppCopyTruthTest].
 *
 * Copy is the whole feature here. The Nova defect was not that art failed; it was that art failed with
 * nothing on screen to explain it, so missing artwork did not reveal the permission fault. These sentences
 * name the folder the player chose, state the two consequences the device run actually produced, and
 * offer one repair — no mechanism vocabulary ("SAF", "persisted URI permission", "content provider"),
 * because none of it is actionable from the couch.
 */
class StorageGrantPromptCopyTest {

    @Test
    fun `the prompt states the fault, the folder, and the consequence`() {
        assertEquals("STORAGE ACCESS LOST", StorageGrantLostTitleCopy)
        assertEquals(
            "Mimir can no longer read \"SD card › ROMs\". " +
                "Art and launches from it will fail until you reconnect it.",
            storageGrantLostBodyCopy("SD card › ROMs"),
        )
        assertEquals("RECONNECT", StorageGrantReconnectCopy)
        assertEquals("LATER", StorageGrantLaterCopy)
    }

    @Test
    fun `the body names the friendly label, never a raw uri`() {
        val body = storageGrantLostBodyCopy("USB › Games")
        assertEquals(
            "Mimir can no longer read \"USB › Games\". " +
                "Art and launches from it will fail until you reconnect it.",
            body,
        )
        assertFalse(
            "the player picked a folder, not a content:// tree — the prompt must speak in their terms",
            body.contains("content://") || body.contains("tree/"),
        )
    }

    @Test
    fun `no mechanism vocabulary leaks into the player-facing strings`() {
        val all = listOf(
            StorageGrantLostTitleCopy,
            StorageGrantReconnectCopy,
            StorageGrantLaterCopy,
            storageGrantLostBodyCopy("SD card › ROMs"),
        ).joinToString(" ").lowercase()
        listOf("saf", "uri", "permission", "provider", "grant", "persist").forEach { term ->
            assertFalse(
                "\"$term\" is implementation vocabulary and must not appear in prompt copy",
                all.contains(term),
            )
        }
    }
}
