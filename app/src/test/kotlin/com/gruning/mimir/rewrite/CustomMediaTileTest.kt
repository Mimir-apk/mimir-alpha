package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a gallery tile says about itself : one line, whole, chosen by measuring. The measured widths here are stand-ins
 * (one dp per character), so the ladder's decisions are what is under test; [CustomMediaTileRenderTest] draws the real tiles.
 */
class CustomMediaTileTest {
    private val perChar: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `a label splits at its last dot into the whole and the dimensions`() {
        assertEquals(EnrichTileLabelParts("ScreenScraper · 600×800", "600×800"), enrichTileLabelParts("ScreenScraper · 600×800"))
        assertEquals(
            EnrichTileLabelParts("ScreenScraper · Video · 3840×2160", "3840×2160"),
            enrichTileLabelParts("ScreenScraper · Video · 3840×2160"),
        )
        assertEquals(EnrichTileLabelParts("Uploaded image", null), enrichTileLabelParts("Uploaded image"))
    }

    @Test
    fun `the provider badge is the whole name first, then the short form people know`() {
        assertEquals(listOf("SCREENSCRAPER", "SS"), enrichTileBadgeCandidates("ScreenScraper · 600×800"))
        assertEquals(listOf("STEAMGRIDDB", "SGDB"), enrichTileBadgeCandidates("SteamGridDB · 1920×1080"))
        assertEquals(listOf("UPLOADED IMAGE", "UPLOAD"), enrichTileBadgeCandidates("Uploaded image"))
        assertEquals(listOf("EXISTING SELECTION", "SAVED"), enrichTileBadgeCandidates("Existing selection"))
        assertEquals(listOf("BOX ART"), enrichTileBadgeCandidates("Box art · 600×800"))
    }

    @Test
    fun `the bar shows the whole label if it fits, else the provider and the dimensions, else the dimensions, else the provider`() {
        val label = "ScreenScraper · 600×800" // 23 characters; the dimensions are 7. A badge is set in a smaller face: half a dp a character; the gap between a badge and the line is 5.
        val badgeHalf: (String) -> Float = { it.length / 2f }
        fun plan(room: Float) = enrichTileLabelPlan(label, true, room, perChar, badgeHalf)

        assertEquals("the whole label fits, so no badge is needed", EnrichTileLabelPlan(label, null), plan(room = 23f))
        assertEquals("the provider and the size, in the bar (6.5 + 5 + 7)", EnrichTileLabelPlan("600×800", "SCREENSCRAPER"), plan(room = 19f))
        assertEquals("the provider falls back to its short form (1 + 5 + 7)", EnrichTileLabelPlan("600×800", "SS"), plan(room = 18f))
        assertEquals("then the size alone", EnrichTileLabelPlan("600×800", null), plan(room = 12f))
        assertEquals("nothing but the provider fits the bar", EnrichTileLabelPlan(null, "SS"), plan(room = 6f))
        assertEquals("nothing fits at all: nothing is cut to fit", EnrichTileLabelPlan(null, null), plan(room = 0.5f))
    }

    @Test
    fun `a label with no dimensions is shown whole or as its provider, or not at all`() {
        assertEquals(EnrichTileLabelPlan("Uploaded image", null), enrichTileLabelPlan("Uploaded image", true, 14f, perChar, perChar))
        assertEquals(EnrichTileLabelPlan(null, "UPLOAD"), enrichTileLabelPlan("Uploaded image", true, 6f, perChar, perChar))
        assertEquals(EnrichTileLabelPlan(null, null), enrichTileLabelPlan("Uploaded image", true, 5f, perChar, perChar))
    }

    @Test
    fun `the Default tile's bar says DEFAULT, whole or not at all, and nothing else`() {
        assertEquals(EnrichTileLabelPlan(null, null), enrichTileLabelPlan("Default", false, 100f, perChar, perChar))
        assertEquals(EnrichTileLabelPlan(null, "DEFAULT"), enrichTileLabelPlan("Default", false, 100f, perChar, perChar, fixedBadge = "DEFAULT"))
        assertEquals(EnrichTileLabelPlan(null, "DEFAULT"), enrichTileLabelPlan("Default", false, 7f, perChar, perChar, fixedBadge = "DEFAULT"))
        assertEquals("no room for the word: no word is cut", EnrichTileLabelPlan(null, null), enrichTileLabelPlan("Default", false, 6f, perChar, perChar, fixedBadge = "DEFAULT"))
    }

    // ── A cover tile is the shape of its art ────────────────────────────────────────────────────────

    private fun saved(width: Int, height: Int) = EnrichSavedAsset(
        assetId = "s",
        source = EnrichSavedSource.SCREENSCRAPER,
        label = "ScreenScraper · ${width}×$height",
        localPath = "/local/s",
        previewPath = "/local/s",
        width = width,
        height = height,
        bytes = 1L,
        addedAt = 1L,
    )

    private fun found(width: Int, height: Int) = HomeCustomEnrichResult.Hero(
        CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://cdn2.steamgriddb.com/cover.png",
            ordinal = 1,
            width = width,
            height = height,
        ),
        null,
    )

    @Test
    fun `a cover tile keeps the strip's height, and takes its width from the art's own aspect between a portrait and 2 to 1`() {
        // The certified height budget is untouched: the band less its 6 dp of air above and below.
        assertEquals(112, homeCustomEnrichCoverTileHeightDp(compactSquare = false))
        assertEquals(136, homeCustomEnrichCoverTileHeightDp(compactSquare = true))
        // The narrowest is the old portrait tile, the widest an icon tile (a square at that height).
        assertEquals(80, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = null))
        assertEquals(112, homeCustomEnrichTileWidthDp(EnrichMediaKind.ICON, compactSquare = false))
        // A Nintendo DS cover (about 0.9) fills a tile that is 0.9 of the strip's height: no crop, no bars.
        assertEquals(101, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 0.9f))
        assertEquals(122, homeCustomEnrichCoverTileWidthDp(compactSquare = true, aspect = 0.9f))
        assertEquals(112, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 1f))
        assertEquals(81, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 0.72f))
        // Clamped at both ends (the widest is a 2:1 picture's own shape, so a wide upload is shown whole), and an aspect that is nothing falls back to the portrait tile.
        assertEquals(80, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 0.5f))
        assertEquals(224, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 2f))
        assertEquals(224, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 3f))
        assertEquals(179, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 1.6f))
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            assertEquals("aspect $bad", 80, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = bad))
        }
        // The tile is the art's own shape to within the half dp a whole-dp width rounds by, unless it was clamped.
        assertTrue(homeCustomEnrichCoverTileFitsAspect(101, 0.9f, compactSquare = false))
        assertTrue(homeCustomEnrichCoverTileFitsAspect(112, 1f, compactSquare = false))
        assertTrue(homeCustomEnrichCoverTileFitsAspect(224, 2f, compactSquare = false))
        assertFalse("clamped at 2:1: a 3:1 picture is wider than the tile", homeCustomEnrichCoverTileFitsAspect(224, 3f, compactSquare = false))
        assertFalse("no aspect, no claim", homeCustomEnrichCoverTileFitsAspect(80, null, compactSquare = false))
        // A small viewport still shrinks the portrait tile and never lets a shaped one pass the portrait floor it was given.
        assertTrue(homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 0.9f, galleryViewportWidthDp = 200) >= 80)
    }

    @Test
    fun `a tile's aspect is its art's dimensions, the Default tile's once its art has loaded, and nothing for More`() {
        assertEquals(0.75f, homeCustomEnrichAssetAspect(HomeCustomEnrichAsset.Saved(saved(600, 800)), null)!!, 0.0001f)
        assertEquals(1f, homeCustomEnrichAssetAspect(HomeCustomEnrichAsset.Result(found(512, 512)), null)!!, 0.0001f)
        assertNull("a found tile with no dimensions has no aspect", homeCustomEnrichAssetAspect(HomeCustomEnrichAsset.Result(found(0, 0)), null))
        assertNull("the Default tile has none until its art loads", homeCustomEnrichAssetAspect(HomeCustomEnrichAsset.Automatic, null))
        assertEquals(0.9f, homeCustomEnrichAssetAspect(HomeCustomEnrichAsset.Automatic, 0.9f)!!, 0.0001f)
        assertNull(homeCustomEnrichAssetAspect(HomeCustomEnrichAsset.Automatic, Float.NaN))
        assertNull(homeCustomEnrichAssetAspect(HomeCustomEnrichAsset.More, 0.9f))
    }

    @Test
    fun `the follow-scroll seat with tiles of different widths is the uniform law with each tile's own width`() {
        // Equal widths: exactly the law the uniform tiles have always used.
        (0..5).forEach { index ->
            assertEquals(
                "index $index",
                homeCustomEnrichGalleryScrollOffsetDp(index, 112, 16),
                homeCustomEnrichGalleryScrollOffsetDp(index, List(6) { 112 }, 16),
                0f,
            )
        }
        // Mixed widths (Default 101, then 80, 112, 90, with a 16 dp gap and the 14 dp ring clearance): the previous tile stays peeked.
        val widths = listOf(101, 80, 112, 90)
        assertEquals(0f, homeCustomEnrichGalleryScrollOffsetDp(0, widths, 16), 0f)
        assertEquals(0f, homeCustomEnrichGalleryScrollOffsetDp(1, widths, 16), 0f)
        assertEquals(117f, homeCustomEnrichGalleryScrollOffsetDp(2, widths, 16), 0f)
        assertEquals(213f, homeCustomEnrichGalleryScrollOffsetDp(3, widths, 16), 0f)
        // An index past the list, or before it, is nowhere.
        assertEquals(0f, homeCustomEnrichGalleryScrollOffsetDp(9, widths, 16), 0f)
        assertEquals(0f, homeCustomEnrichGalleryScrollOffsetDp(-1, widths, 16), 0f)
    }

    @Test
    fun `a wide cover tile never passes what its strip lets a ring-safe tile be`() {
        val wide = homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 2f, galleryViewportWidthDp = 200)
        assertTrue("a 2:1 tile in a 200 dp strip: $wide", wide in 80..(200 - EnrichRingClearanceDp * 2))
        assertEquals("with room for it the tile is the picture's own 2:1 shape", 224, homeCustomEnrichCoverTileWidthDp(compactSquare = false, aspect = 2f, galleryViewportWidthDp = 800))
        assertEquals("grown by a bar, the art keeps its certified height and the 2:1 tile its width", 224, homeCustomEnrichCoverTileWidthDp(false, 2f, 0, 21, 21))
        assertEquals("with no growth the bar is carved out of the height, and the width follows", kotlin.math.round((112 - 21) * 2f).toInt(), homeCustomEnrichCoverTileWidthDp(false, 2f, 0, 21))
    }

    /** the Default cover was cut at its top (its rule gone, the title under the ring). The whole book is contained in its art box. */
    @Test
    fun `the title book is contained whole in its art box, and its tile is the book's own shape`() {
        listOf(112f to 112f, 84f to 112f, 90f to 120f, 224f to 112f, 60f to 112f, 148f to 148f, 112f to 136f).forEach { (w, h) ->
            val book = homeCustomEnrichTitleBookWidthDp(w, h)
            assertTrue("the book is never wider than its box: $book vs $w", book <= w + 0.001f)
            assertTrue("nor taller than it: ${book * 4f / 3f} vs $h", book * 4f / 3f <= h + 0.001f)
        }
        assertEquals("a square box holds the 3:4 book at its full height", 84f, homeCustomEnrichTitleBookWidthDp(112f, 112f), 0.001f)
        val book = HomeCustomEnrichDefaultArt.TitleBook("Tetris", null)
        // The tile is the book's shape at the art's height, whatever the kind's own width: no band beside the book, nothing cropped.
        listOf(EnrichMediaKind.ICON, EnrichMediaKind.TOME_PHYSICAL, EnrichMediaKind.COVER).forEach { kind ->
            assertEquals("$kind", 84, homeCustomEnrichDefaultTileWidthDp(kind, book, null, artHeightDp = 112, maxWidthDp = 112))
        }
        assertEquals(86, homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, book, null, artHeightDp = 115, maxWidthDp = 115))
        // A hero and a clip never wear a book; their tiles keep their one width.
        assertNull(homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.HERO, book, null, 81, 144))
        assertNull(homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.CLIP, book, null, 81, 144))
    }

    @Test
    fun `an icon Default picture is contained, the tile is its shape up to the square, and a cover has its own law`() {
        val art = HomeCustomEnrichDefaultArt.Automatic("file:///cover.png")
        // Not loaded yet: the kind's own width. Loaded: the picture's shape at the art's height, never wider than the square and never a sliver.
        assertNull(homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, art, null, 112, 112))
        assertEquals("a square picture is the square tile", 112, homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, art, 1f, 112, 112))
        assertEquals("a portrait box render is its own shape", 81, homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, art, 0.72f, 112, 112))
        assertEquals("a wide picture is held at the square", 112, homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.TOME_PHYSICAL, art, 2f, 112, 112))
        assertEquals("a sliver is held at half the height", 56, homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, art, 0.2f, 112, 112))
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            assertNull("aspect $bad", homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, art, bad, 112, 112))
        }
        // A cover's loaded picture keeps homeCustomEnrichCoverTileWidthDp's law; a console's line art and an unavailable default keep the kind's width.
        assertNull(homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.COVER, art, 0.9f, 112, 112))
        assertNull(homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"), null, 112, 112))
        assertNull(homeCustomEnrichDefaultTileWidthDp(EnrichMediaKind.ICON, HomeCustomEnrichDefaultArt.Unavailable, null, 112, 112))
    }

    /** Reported 10-09: a wide cover was cut at the strip's right edge because the tile before it was kept in view. It is seated whole, whatever it costs the peek. */
    @Test
    fun `a wide focused tile is seated whole in the strip, the peek giving up what it needs`() {
        val widths = listOf(97, 230)
        assertEquals("with no viewport the peek law is as it was", 0f, homeCustomEnrichGalleryScrollOffsetDp(1, widths, 12), 0f)
        // A 338 dp strip: the tile starts at 123, ends at 353 and needs 14 dp of ring: 29 dp of scroll puts all of it in view.
        assertEquals(29f, homeCustomEnrichGalleryScrollOffsetDp(1, widths, 12, viewportDp = 338), 0f)
        // A tile that cannot fit whole still shows its own start.
        assertEquals(109f, homeCustomEnrichGalleryScrollOffsetDp(1, listOf(97, 400), 12, viewportDp = 338), 0f)
        // Where two tiles fit, the law is the peek law exactly.
        (0..5).forEach { index ->
            assertEquals("index $index", homeCustomEnrichGalleryScrollOffsetDp(index, List(6) { 112 }, 16), homeCustomEnrichGalleryScrollOffsetDp(index, List(6) { 112 }, 16, viewportDp = 700), 0f)
        }
        // Whatever the tiles, the focused tile and its ring stand inside the window it is given.
        listOf(listOf(97, 230, 80), listOf(80, 224, 224, 101), listOf(144, 144, 144)).forEach { tiles ->
            tiles.indices.forEach { index ->
                val offset = homeCustomEnrichGalleryScrollOffsetDp(index, tiles, 12, viewportDp = 338)
                val childX = EnrichRingClearanceDp + tiles.take(index).sum() + 12 * index
                assertTrue("$tiles[$index]: starts in view ($offset)", childX - EnrichRingClearanceDp >= offset - 0.01f)
                assertTrue("$tiles[$index]: ends in view ($offset)", childX + tiles[index] + EnrichRingClearanceDp <= offset + 338f + 0.01f || index == 0)
            }
        }
    }
}
