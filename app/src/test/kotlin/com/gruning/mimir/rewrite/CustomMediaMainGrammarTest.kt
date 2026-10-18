package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Customize media page's pure rules after a Main-screen visual review : exactly one tile is "in use" (and wears the check mark),
 * a found tile with no dimensions says no size, and the preview does not repeat the DEFAULT its tile's badge already says.
 */
class CustomMediaMainGrammarTest {

    private fun hero(n: Int, width: Int = 0, height: Int = 0, saved: EnrichSavedAsset? = null, previewPath: String? = null) = HomeCustomEnrichResult.Hero(
        CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://cdn2.steamgriddb.com/hero-$n.png",
            ordinal = n,
            width = width,
            height = height,
            previewPath = previewPath,
        ),
        saved,
    )

    private fun record(n: Int, label: String = "ScreenScraper · 600×800") = EnrichSavedAsset(
        assetId = "saved-$n",
        source = EnrichSavedSource.SCREENSCRAPER,
        label = label,
        localPath = "/saved/$n.png",
        previewPath = "/saved/$n.png",
        width = 600,
        height = 800,
        bytes = 1L,
        addedAt = n.toLong(),
    )

    private fun state(
        activeId: String? = null,
        saved: List<EnrichSavedAsset> = emptyList(),
        results: List<HomeCustomEnrichResult> = emptyList(),
    ) = HomeCustomEnrichState(
        sessionId = "grammar",
        kind = EnrichMediaKind.HERO,
        cacheKey = "game-1",
        gameTitle = "Final Fantasy IV",
        queryDraft = "Final Fantasy IV",
        saved = saved,
        activeAssetId = activeId,
        results = results,
    )

    private fun inUse(state: HomeCustomEnrichState): List<String> =
        state.assets.filter { homeCustomEnrichAssetIsActive(state, it) }.map { it.stableId }

    // ── The check mark: ONE tile, the one in use now ────────────────────────────────────────────────

    @Test
    fun `with Default in use, Default is the one tile in use and no unsaved result is`() {
        // A reported screenshot: Default in use, six results found and none saved, and every one of them wore the check mark.
        val found = (1..6).map { hero(it, 1920, 1080) }
        val s = state(activeId = null, results = found)
        assertEquals(listOf("auto"), inUse(s))
        found.forEach { assertFalse(it.stableId, homeCustomEnrichAssetIsActive(s, HomeCustomEnrichAsset.Result(it))) }
        // Nor does saved-and-downloaded alone make a result "in use" while Default is.
        val bound = hero(7, 600, 800, saved = record(1))
        assertEquals(listOf("auto"), inUse(state(activeId = null, saved = listOf(record(1)), results = found + bound)))
    }

    @Test
    fun `with a saved choice in use, that one tile is, even when the same record also stands among the results`() {
        val saved = listOf(record(1), record(2))
        val twice = hero(7, 600, 800, saved = record(1))
        assertEquals(listOf("saved-2"), inUse(state(activeId = "saved-2", saved = saved, results = listOf(hero(1), twice))))
        // The same record in the Saved rail and in Results (policy): the Saved tile is the one in use, never both.
        assertEquals(listOf("saved-1"), inUse(state(activeId = "saved-1", saved = saved, results = listOf(hero(1), twice))))
    }

    @Test
    fun `a found tile bound to the active record the Saved rail does not hold is the one in use`() {
        val fresh = hero(8, 600, 800, saved = record(9))
        val s = state(activeId = "saved-9", saved = listOf(record(1)), results = listOf(hero(1), fresh))
        assertEquals(listOf("result-${fresh.stableId}"), inUse(s))
    }

    @Test
    fun `an unsaved result reads FOUND, never CURRENT, while Default is in use`() {
        val result = hero(1, 1920, 1080, previewPath = "/found/1.png")
        val s = state(activeId = null, results = listOf(result))
        assertEquals("FOUND", homeCustomEnrichPreviewStatus(s, HomeCustomEnrichAsset.Result(result)))
    }

    // ── The preview does not say DEFAULT a second time ─────────────────────────────────────────────

    @Test
    fun `the preview caption never repeats DEFAULT for the Default tile, and still says everything else`() {
        assertEquals("DEFAULT", HomeCustomEnrichPreviewDefaultStatus)
        assertFalse(homeCustomEnrichPreviewCaptionShown(HomeCustomEnrichAsset.Automatic, HomeCustomEnrichPreviewDefaultStatus))
        // Default while a search runs still says so: that is news, not a repeat.
        assertTrue(homeCustomEnrichPreviewCaptionShown(HomeCustomEnrichAsset.Automatic, "SEARCHING"))
        val asset = HomeCustomEnrichAsset.Saved(record(1))
        listOf("SAVED", "CURRENT", "FOUND").forEach { assertTrue(it, homeCustomEnrichPreviewCaptionShown(asset, it)) }
    }

    // ── A found tile with no dimensions says no size ───────────────────────────────────────────────

    @Test
    fun `a found tile with no dimensions carries no size filler, with them it carries them`() {
        val name = hero(1).candidate.displayLabel
        assertEquals(name, hero(1).label)
        assertEquals("$name · 1920×1080", hero(1, 1920, 1080).label)
        assertFalse(HomeCustomEnrichAsset.Result(hero(1)).label.contains("unknown"))
    }

    @Test
    fun `a choice saved with the old size filler reads without it`() {
        val old = record(1, label = "SteamGridDB · Image 1 · size unknown")
        assertEquals("SteamGridDB · Image 1", HomeCustomEnrichAsset.Saved(old).label)
        assertFalse(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.Saved(old)))
        assertEquals("SteamGridDB · Image 1", enrichLabelWithoutSizeFiller("SteamGridDB · Image 1 · size unknown"))
        assertEquals("Uploaded image", enrichLabelWithoutSizeFiller("Uploaded image"))
    }

    @Test
    fun `which tiles know their size`() {
        assertFalse(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.Result(hero(1))))
        assertFalse(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.Result(hero(1, width = 1920))))
        assertTrue(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.Result(hero(1, 1920, 1080))))
        assertTrue(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.Saved(record(1))))
        assertTrue(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.Saved(record(2, label = "Uploaded image"))))
        assertTrue(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.Automatic))
        assertTrue(homeCustomEnrichAssetSizeKnown(HomeCustomEnrichAsset.More))
    }

    @Test
    fun `a tile that knows no size says no size in its bar and keeps the provider`() {
        val perChar: (String) -> Float = { it.length.toFloat() }
        // Plenty of room: the whole label would fit the bar, and still it says no size (there is none to say): the provider stands alone.
        assertEquals(
            EnrichTileLabelPlan(null, "STEAMGRIDDB"),
            enrichTileLabelPlan("SteamGridDB · Image 1", true, 100f, perChar, perChar, sizeKnown = false),
        )
        // The provider falls back to its short form, and to nothing where even that has no room.
        assertEquals(
            EnrichTileLabelPlan(null, "SGDB"),
            enrichTileLabelPlan("SteamGridDB · Image 1", true, 5f, perChar, perChar, sizeKnown = false),
        )
        assertEquals(
            EnrichTileLabelPlan(null, null),
            enrichTileLabelPlan("SteamGridDB · Image 1", true, 3f, perChar, perChar, sizeKnown = false),
        )
        // A tile that knows its size is as it was.
        assertEquals(
            EnrichTileLabelPlan("SteamGridDB · 1920×1080", null),
            enrichTileLabelPlan("SteamGridDB · 1920×1080", true, 100f, perChar, perChar),
        )
        // The Default tile's plan is its own word or nothing; a tile with no size and a long label says no size either.
        assertEquals(EnrichTileLabelPlan(null, null), enrichTileLabelPlan("Default", false, 100f, perChar, perChar, sizeKnown = false))
        assertNull(enrichTileLabelPlan("Uploaded image", true, 100f, perChar, perChar, sizeKnown = false).text)
    }
}
