package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SteamGridDbTileFetcher.hasTile] (ENRICH-for-`pc_games`): whether a tile
 * already exists for a cache key, added so `ArtworkFetchController.fetchOnePcArtwork` can report
 * [ArtworkFetchController.OneResult.Matched] for a game that already has art WITHOUT calling
 * `fetchMissing` (which itself only silently SKIPS an existing tile — a bare `count > 0` from it would
 * wrongly read as `NoMatch` for an already-illustrated game).
 *
 * Genuinely runtime-testable: unlike the DB/Keystore-bound routing in `ArtworkFetchController`
 * (characterized as source text in [PcEnrichSteamGridDbRoutingWiringTest]), [hasTile] only touches
 * `context.filesDir`, which a Robolectric [android.app.Application] context provides for real — no Room,
 * no SteamGridDB key/Keystore involved. Must use the EXACT path [SteamGridDbTileFetcher.fetchMissing]
 * itself checks (`filesDir/sgdb2/<hex-encoded key>.png`), or the presence check and the real skip could
 * silently diverge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class SteamGridDbTileHasTileTest {

    @Test
    fun `automatic fetch cannot install over a selected pick even while its target is absent`() {
        assertFalse(
            automaticTileFetchMayInstall(
                targetExists = false,
                selection = GameIdentityMediaSelection.SELECTED,
            ),
        )
        assertTrue(
            automaticTileFetchMayInstall(
                targetExists = false,
                selection = GameIdentityMediaSelection.AUTOMATIC,
            ),
        )
        assertFalse(
            automaticTileFetchMayInstall(
                targetExists = true,
                selection = GameIdentityMediaSelection.NEVER_CHOSEN,
            ),
        )
    }

    @Test
    fun `hasTile is false when no tile file exists for the key`() {
        val context = RuntimeEnvironment.getApplication()

        assertFalse(SteamGridDbTileFetcher.hasTile(context, "pc-game-with-no-tile-yet"))
    }

    @Test
    fun `hasTile is true once a tile file exists at the exact path fetchMissing itself checks`() {
        val context = RuntimeEnvironment.getApplication()
        val cacheKey = "pc-game-already-illustrated"

        val target = SteamGridDbTileFetcher.canonicalTileIconFile(context, cacheKey)
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(SteamGridDbTileFetcher.hasTile(context, cacheKey))
    }

    @Test
    fun `hasTile does not confuse two different cache keys`() {
        val context = RuntimeEnvironment.getApplication()
        val target = SteamGridDbTileFetcher.canonicalTileIconFile(context, "pc-game-a")
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(9))

        assertFalse(SteamGridDbTileFetcher.hasTile(context, "pc-game-b"))
    }
}
