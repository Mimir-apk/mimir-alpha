package com.gruning.mimir.rewrite.core.resolver.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SD01 — the enumeration-cache flush contract. AndroidLocalArtAccess memoizes whole-directory
 * listings (the fix for the 4,420-game SNES dir never painting); newly written media is only
 * discoverable if BOTH invalidate paths flush those caches. Pinned here through the seam.
 */
class LocalArtSourceInvalidateCachesTest {

    private class CountingAccess : LocalArtAccess {
        var invalidations = 0
        override fun existingFileUri(path: String): String? = null
        override fun documentDisplayName(documentUri: String): String? = null
        override fun parentDocumentUri(documentUri: String): String? = null
        override fun existingChildUri(
            parentDocumentUri: String,
            displayName: String,
            requiredMimeType: String?,
        ): String? = null

        override fun invalidateCaches() {
            invalidations++
        }
    }

    @Test
    fun `per-game invalidate flushes the enumeration caches`() {
        val access = CountingAccess()
        val source = LocalArtSource(access)
        source.invalidate(sourceDocumentUri = "content://tree/x/document/x%2Fgame.sfc", managedPath = null)
        assertEquals(1, access.invalidations)
    }

    @Test
    fun `invalidateAll flushes the enumeration caches`() {
        val access = CountingAccess()
        val source = LocalArtSource(access)
        source.invalidateAll()
        assertEquals(1, access.invalidations)
    }

    /**
     * The audio slot has its own memo, so each flush has to reach it: a sound Enrich has just written must be found
     * without a restart, whichever way the caller invalidates (one game after a per-game Enrich, or all after a batch).
     */
    private class SoundAccess : LocalArtAccess {
        var present = false
        override fun existingFileUri(path: String): String? =
            "file:///games/media_art/G-audio.m4a".takeIf { present && path.endsWith("-audio.m4a") }

        override fun documentDisplayName(documentUri: String): String? = null
        override fun parentDocumentUri(documentUri: String): String? = null
        override fun existingChildUri(
            parentDocumentUri: String,
            displayName: String,
            requiredMimeType: String?,
        ): String? = null
    }

    @Test
    fun `per-game invalidate drops the memoized audio miss`() {
        val access = SoundAccess()
        val source = LocalArtSource(access)
        assertNull(source.audioUriFor(null, "/games/G.iso"))
        access.present = true
        assertNull("still the memoized miss", source.audioUriFor(null, "/games/G.iso"))

        source.invalidate(sourceDocumentUri = null, managedPath = "/games/G.iso")

        assertEquals("file:///games/media_art/G-audio.m4a", source.audioUriFor(null, "/games/G.iso"))
    }

    @Test
    fun `invalidateAll drops the memoized audio miss of every game`() {
        val access = SoundAccess()
        val source = LocalArtSource(access)
        assertNull(source.audioUriFor(null, "/games/G.iso"))
        assertNull(source.audioUriFor(null, "/games/H.iso"))
        access.present = true

        source.invalidateAll()

        assertEquals("file:///games/media_art/G-audio.m4a", source.audioUriFor(null, "/games/G.iso"))
        assertEquals("file:///games/media_art/G-audio.m4a", source.audioUriFor(null, "/games/H.iso"))
    }

    @Test
    fun `a keyless invalidate is a no-op`() {
        val access = CountingAccess()
        val source = LocalArtSource(access)
        source.invalidate(sourceDocumentUri = null, managedPath = null)
        assertEquals(0, access.invalidations)
    }
}
