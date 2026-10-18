package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundImages
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoded-copy cache is as large as the saved list (gatekeeper S2). A ground is always one of the saved backgrounds,
 * so a cache smaller than the list evicts its own picker prefetch: at four slots the picker's prefetch of six evicted
 * itself, and a D-pad sweep across the tiles decoded on every move. The two numbers live in two modules, so this is the
 * one place that ties them together.
 */
class AppearanceGroundCacheCapacityTest {

    @Test
    fun `the decode cache holds every saved background at once`() {
        assertTrue(
            "MaxEntries=${MimirSceneGroundImages.MaxEntries} < saved cap=${AppearanceImageKind.BACKGROUND.capacity}: " +
                "the picker's prefetch would evict itself",
            MimirSceneGroundImages.MaxEntries >= AppearanceImageKind.BACKGROUND.capacity,
        )
    }

    @Test
    fun `and stays inside the memory ceiling`() {
        // Each RGB_565 copy is about 4 MB at 1080p: seven of them is the most the brief allows for.
        assertTrue(MimirSceneGroundImages.MaxEntries <= 7)
    }
}
