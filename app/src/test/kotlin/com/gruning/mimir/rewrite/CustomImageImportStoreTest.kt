package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomImageImportStoreTest {
    @Test
    fun `icon import decode is bounded without upscaling`() {
        assertEquals(CustomImageSize(2_048, 1_024), customImageDecodeSize(4_096, 2_048, CustomImageSlot.ICON))
        assertEquals(CustomImageSize(512, 512), customImageDecodeSize(512, 512, CustomImageSlot.ICON))
    }

    /** Reported 10-09: a 2:1 cover upload was cut to a square at its sides at ingest. A cover keeps its own shape; only its longest edge is capped. */
    @Test
    fun `a cover import keeps its shape, brought down by its longest edge and never cropped or upscaled`() {
        assertEquals(CustomImageSize(2_048, 1_024), customImageDecodeSize(4_096, 2_048, CustomImageSlot.COVER))
        assertEquals(CustomImageSize(400, 200), customImageDecodeSize(400, 200, CustomImageSlot.COVER))
        // The stored size: a 2:1 stays 2:1, a portrait stays a portrait, a square a square, and nothing is enlarged.
        assertEquals(CustomImageSize(400, 200), customImageCoverSize(400, 200, 1_024))
        assertEquals(CustomImageSize(1_024, 512), customImageCoverSize(2_048, 1_024, 1_024))
        assertEquals(CustomImageSize(768, 1_024), customImageCoverSize(1_536, 2_048, 1_024))
        assertEquals(CustomImageSize(1_024, 1_024), customImageCoverSize(4_000, 4_000, 1_024))
        assertEquals(CustomImageSize(1_024, 1), customImageCoverSize(20_000, 1, 1_024))
        assertEquals(CustomImageSize(1, 1), customImageCoverSize(0, 10, 1_024))
        // The icon slot is unchanged: its decode bound is the cover's, its crop is its own.
        assertEquals(CustomImageSize(2_048, 1_024), customImageDecodeSize(4_096, 2_048, CustomImageSlot.ICON))
    }

    @Test
    fun `hero import fits inside 1080 class without changing aspect`() {
        assertEquals(CustomImageSize(1_920, 1_080), customImageDecodeSize(3_840, 2_160, CustomImageSlot.HERO))
        assertEquals(CustomImageSize(608, 1_080), customImageDecodeSize(1_216, 2_160, CustomImageSlot.HERO))
        assertEquals(CustomImageSize(960, 540), customImageDecodeSize(960, 540, CustomImageSlot.HERO))
    }

    /**
     *  PLATFORM icons only.
     *
     * The scoping is the whole point of this test. One import path serves platform tiles, Home game tiles and
     * Tome physical art, so the obvious "simplification" — lowering the shared icon cap to 512 — would halve
     * unrelated game art. If someone does that, the game-key assertions below fail.
     */
    @Test
    fun `only platform icons take the 512 apply cap`() {
        val platform = SteamGridDbTileFetcher.platformKey(PlatformId("snes"))
        assertEquals(512, CustomImageImportStore.iconMaxEdgePx(platform))

        // Game tiles, Android rows and Tome art keep the original cap.
        assertEquals(1_024, CustomImageImportStore.iconMaxEdgePx("some-game-id"))
        assertEquals(1_024, CustomImageImportStore.iconMaxEdgePx(SteamGridDbTileFetcher.androidKey("com.foo")))
        assertEquals(1_024, CustomImageImportStore.iconMaxEdgePx(null))
    }

    /** The namespace predicate the cap depends on — a game id must never be mistaken for a platform. */
    @Test
    fun `platform keys are distinguishable from game and android keys`() {
        assertEquals(true, SteamGridDbTileFetcher.isPlatformKey(SteamGridDbTileFetcher.platformKey(PlatformId("psx"))))
        assertEquals(false, SteamGridDbTileFetcher.isPlatformKey("hash:abc123"))
        assertEquals(false, SteamGridDbTileFetcher.isPlatformKey(SteamGridDbTileFetcher.androidKey("com.foo")))
        assertEquals(false, SteamGridDbTileFetcher.isPlatformKey(""))
    }
}
