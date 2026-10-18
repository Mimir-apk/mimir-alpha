package com.gruning.mimir.rewrite.core.model.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtSourceContractTest {
    @Test
    fun `cover lambda remains a SAM and clear logo defaults to absent`() {
        val source = ArtSource { _, managedPath -> managedPath?.let { "file://$it.png" } }

        assertEquals("file:///games/Metroid.png", source.artUriFor(null, "/games/Metroid"))
        assertNull(source.logoUriFor(null, "/games/Metroid"))
    }

    @Test
    fun `game row appends an absent flat art default`() {
        val row = GameRow(
            gameId = GameId("gba/metroid-fusion"),
            title = "Metroid Fusion",
            platformId = PlatformId.GBA,
            memberCount = 1,
            duplicateKind = null,
        )

        assertNull(row.flatArtUri)
    }
}
