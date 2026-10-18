package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTileArtStoreTest {
    @Test
    fun explicitReplacementChangesRenderIdentityWhenFilesystemVersionIsUnchanged() {
        val persisted = "file:/platform-snes.png?v=1200"

        val replacement = platformArtRenderUri(
            currentRenderUri = persisted,
            persistedUri = persisted,
            contentChanged = true,
            liveRevision = 7L,
        )

        assertNotEquals(persisted, replacement)
        assertEquals(persisted, platformArtPersistedUri(replacement))
        assertTrue(replacement.endsWith("&mimirLive=7"))
    }

    @Test
    fun passiveHydrationKeepsNewestLiveIdentityForTheSamePersistedFile() {
        val persisted = "file:/platform-snes.png?v=1200"
        val current = "$persisted&mimirLive=7"

        assertEquals(
            current,
            platformArtRenderUri(
                currentRenderUri = current,
                persistedUri = persisted,
                contentChanged = false,
                liveRevision = 8L,
            ),
        )
    }

    @Test
    fun changedFilesystemVersionSupersedesAnOlderLiveIdentityDuringHydration() {
        val oldPersisted = "file:/platform-snes.png?v=1200"
        val changedPersisted = "file:/platform-snes.png?v=1300"

        assertEquals(
            changedPersisted,
            platformArtRenderUri(
                currentRenderUri = "$oldPersisted&mimirLive=7",
                persistedUri = changedPersisted,
                contentChanged = false,
                liveRevision = 8L,
            ),
        )
    }
}
