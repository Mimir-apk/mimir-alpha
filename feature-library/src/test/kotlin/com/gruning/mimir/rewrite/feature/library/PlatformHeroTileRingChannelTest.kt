package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library shelf tile's split of core-input's two-channel focus law.
 *
 * `PlatformHeroTile` is the one site in this programme where a single `focused` flag genuinely drove both
 * channels: `emphasis` (the focus nudge scale, the bloom, the Mannaz alpha, the title colour lerps) and
 * `ring` (the selection ring). It already had two `Animatable`s — they simply shared one input. The emphasis must hold its pose while the dock or Utility owns input, while the ring leaves until return. Hence `ringLit`, defaulted to `focused` so every non-coverflow caller is unchanged.
 *
 * The coverflows are where the two inputs actually diverge: they take the CONSERVED key from the host and
 * compute the LIT one themselves from the authority they already hold. Pinning that is the point — passing
 * the host's key to both is exactly the shape that produced the ghost trail on the Home shelf.
 *
 * Source-text pins: no Compose harness in this module (same precedent as `HomeLaunchCardTest`).
 */
class PlatformHeroTileRingChannelTest {

    private fun librarySource(fileName: String): String = listOf(
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$fileName"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$fileName"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$fileName not reachable from ${File(".").absolutePath}")

    @Test
    fun `the tile's ring keys off ringLit while its emphasis keys off the conserved focused flag`() {
        val source = librarySource("PlatformHeroTile.kt")

        assertTrue(
            "ringLit must exist and default to focused, so every caller without a pushed scope to worry " +
                "about keeps its exact previous behaviour",
            source.contains("ringLit: Boolean = focused"),
        )
        assertTrue(
            "the ring Animatable must be driven by ringLit",
            source.contains("LaunchedEffect(ringLit)"),
        )
        assertTrue(
            "and so must the ring's paint progress",
            source.contains("val ringProgress = if (ringLit)"),
        )
        assertTrue(
            "the emphasis channel must STILL key off focused -- that is the conserved half, and cutting " +
                "it here would re-introduce the original snap on the Library shelf",
            source.contains("LaunchedEffect(focused)") &&
                source.contains("val focusProgress = if (focused)"),
        )
    }

    @Test
    fun `both coverflows compute the lit key from the live scope, not from the host's conserved key`() {
        listOf("ShelfCoverflow.kt", "NativeShelfCoverflow.kt").forEach { fileName ->
            val source = librarySource(fileName)

            assertTrue(
                "$fileName must derive its ring key from the authority's LIVE scope",
                source.contains("authority.litFocusIn(scope, authority.state.collectAsState().value)"),
            )
            assertTrue(
                "$fileName must pass that as ringLit, separately from the conserved focused flag",
                source.contains("ringLit = isRingLit"),
            )
            assertTrue(
                "$fileName's isHero (the conserved channel) must still come from the host's focusedKey, " +
                    "which is what keeps the row centred and the hero emphasised under a scope push",
                source.contains("val isHero = stop.key == focusedKey"),
            )
            assertFalse(
                "$fileName must not hand the same key to both channels",
                source.contains("ringLit = isHero"),
            )
        }
    }
}
