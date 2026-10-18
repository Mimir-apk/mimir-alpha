package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.gruning.mimir.rewrite.feature.library.homeShelfCoverUri

/**
 * The last place a clear logo could reach a box's position.
 *
 * The 2026-09-17 consistency ruling — box-3D always, fall back only within a kind — was applied to
 * `mimirShelfFace`, which stopped the ladder crossing between covers and cartridges. It did not reach the
 * value FED to that ladder's cover tier: `artworkUri` was `artUri ?: flatArtUri` for every platform, and
 * `flatArtUri` is the LOGO slot. So a game with no cover still painted its clear logo where a box goes,
 * and the shelf stayed mixed for a reason one layer above the fix.
 *
 * `pc_games` is why the fallback existed and why it is scoped rather than deleted: it is absent from
 * `ScreenScraperSystemMap`, no cover is ever fetched for it, and its logo is genuinely the face.
 */
class HomeShelfCoverUriTest {

    private val cover = "file:///media_art/zelda-box.png"
    private val logo = "file:///media_art/zelda-wheel.png"

    @Test
    fun `an emulated game with a cover uses it`() {
        assertEquals(cover, homeShelfCoverUri(PlatformId("snes"), cover, logo))
    }

    @Test
    fun `an emulated game without a cover shows nothing rather than its logo`() {
        assertNull(
            "a clear logo must not occupy a box-art position",
            homeShelfCoverUri(PlatformId("snes"), artUri = null, flatArtUri = logo),
        )
    }

    @Test
    fun `every emulated platform behaves the same way, so no shelf is a special case`() {
        listOf("snes", "ps1", "ps2", "psp", "gamecube", "dreamcast", "arcade", "switch").forEach { id ->
            assertNull(
                "$id must not substitute a logo for a cover",
                homeShelfCoverUri(PlatformId(id), artUri = null, flatArtUri = logo),
            )
        }
    }

    @Test
    fun `a PC game still falls back to its logo, because no cover is ever fetched for one`() {
        // pc_games has no ScreenScraper system id, so the COVER slot is never filled from the provider.
        // Dropping this would blank every PC tile, which is why the fallback is scoped, not deleted.
        assertEquals(logo, homeShelfCoverUri(PlatformId.PC_GAMES, artUri = null, flatArtUri = logo))
    }

    @Test
    fun `a PC cover the owner placed himself still outranks the logo`() {
        // his own PC cover outranks the automatic tile everywhere.
        assertEquals(cover, homeShelfCoverUri(PlatformId.PC_GAMES, cover, logo))
    }

    @Test
    fun `nothing at all stays nothing, on either side of the scope`() {
        assertNull(homeShelfCoverUri(PlatformId("snes"), null, null))
        assertNull(homeShelfCoverUri(PlatformId.PC_GAMES, null, null))
        // An Android app row carries no platform at all (HomeFavoriteProjection.kt:217-225); it must take
        // the emulated branch rather than the PC one, or every app tile inherits a logo fallback.
        assertNull(homeShelfCoverUri(platformId = null, artUri = null, flatArtUri = logo))
    }
}
