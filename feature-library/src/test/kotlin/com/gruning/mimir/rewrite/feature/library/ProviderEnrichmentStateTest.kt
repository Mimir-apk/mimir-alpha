package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptOutcome
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEnrichmentStateTest {
    @Test
    fun `unverified game alone offers Enrich`() {
        val state = providerEnrichmentState(null, null, "file:///cover.png", "file:///logo.png")
        assertEquals(ProviderEnrichmentState.NEVER_REQUESTED, state)
        // The seat's own direct-tap action: NEVER_REQUESTED is the ONLY state that performs Enrich without
        // opening a menu first (restores Enrich for VERIFIED_PARTIAL too, but only
        // inside the menu -- see the two tests below and LibraryHeroPickerTest in :app).
        assertTrue(state.shouldOfferEnrich)
    }

    /**
     * Renamed 2026-08-19 -- this used to be titled "does not repeat Enrich after an art change", which is
     * no longer true at the product level: policy 2026-08-19
     *  restores Enrich for verified-partial games,
     * on idempotency grounds ("re-pressing Enrich is safe") -- inside the Settings/Customize-media menu
     * this same seat opens (`HomeTileMenuOverlay.kt`'s `HomeTileMenuAction.ENRICH`, gated by
     * `HomeTileMenuState.offerEnrich`). `shouldOfferEnrich` itself is unchanged: it still gates only the
     * SEAT's own direct-tap action, which stays Settings/Customize-media for VERIFIED_PARTIAL. See
     * `LibraryHeroPickerTest` (`:app`) for the menu-level pin of Enrich actually returning.
     */
    @Test
    fun `verified partial record's seat still opens Settings, not a second direct Enrich tap`() {
        val state = providerEnrichmentState(
            description = null,
            descriptionSource = "screenscraper",
            coverArtUri = null,
            flatArtUri = "file:///custom-logo.png",
        )
        assertEquals(ProviderEnrichmentState.VERIFIED_PARTIAL, state)
        assertFalse(state.shouldOfferEnrich)
    }

    // ── a FINISHED Enrich hides the button; only a FAILED one keeps it ───────────────────

    @Test
    fun `a finished attempt with no provider record hides the direct Enrich seat, even with no logo or cover`() {
        for (finished in listOf(EnrichAttemptOutcome.MATCHED, EnrichAttemptOutcome.NO_MATCH, EnrichAttemptOutcome.NO_TITLE)) {
            val state = providerEnrichmentState(null, null, null, null, enrichAttempt = finished)
            assertEquals("$finished", ProviderEnrichmentState.ATTEMPTED, state)
            assertFalse("$finished: the seat must not perform Enrich again", state.shouldOfferEnrich)
        }
    }

    @Test
    fun `a failed attempt keeps the direct Enrich seat, and so does no attempt at all`() {
        val failed = providerEnrichmentState(null, null, null, null, enrichAttempt = EnrichAttemptOutcome.FAILED)
        assertEquals(ProviderEnrichmentState.NEVER_REQUESTED, failed)
        assertTrue(failed.shouldOfferEnrich)
        assertTrue(providerEnrichmentState(null, null, null, null, enrichAttempt = null).shouldOfferEnrich)
    }

    @Test
    fun `the attempt never changes a game that has a provider record`() {
        for (attempt in EnrichAttemptOutcome.entries) {
            assertEquals(
                "$attempt",
                ProviderEnrichmentState.VERIFIED_PARTIAL,
                providerEnrichmentState(null, "screenscraper", "file:///cover.png", null, enrichAttempt = attempt),
            )
            assertEquals(
                "$attempt",
                ProviderEnrichmentState.VERIFIED_COMPLETE,
                providerEnrichmentState("A synopsis", "screenscraper", "file:///cover.png", "file:///logo.png", enrichAttempt = attempt),
            )
        }
    }

    @Test
    fun `a game row carries its attempt into the state, so every door reads the same answer`() {
        val row = GameRow(
            gameId = GameId("ps1/lonely-game"),
            title = "Lonely Game",
            platformId = PlatformId.PS1,
            memberCount = 1,
            duplicateKind = null,
        )
        assertTrue(row.providerEnrichmentState().shouldOfferEnrich)
        assertFalse(row.copy(enrichAttempt = EnrichAttemptOutcome.NO_MATCH).providerEnrichmentState().shouldOfferEnrich)
        assertTrue(row.copy(enrichAttempt = EnrichAttemptOutcome.FAILED).providerEnrichmentState().shouldOfferEnrich)
    }

    @Test
    fun `only a failed attempt is unfinished`() {
        assertEquals(listOf(true, true, true, false), EnrichAttemptOutcome.entries.map { it.finished })
        assertEquals(EnrichAttemptOutcome.NO_MATCH, EnrichAttemptOutcome.fromName("NO_MATCH"))
        assertEquals(null, EnrichAttemptOutcome.fromName("nope"))
        assertEquals(null, EnrichAttemptOutcome.fromName(null))
    }

    @Test
    fun `verified complete record stays complete`() {
        val state = providerEnrichmentState(
            description = "A complete synopsis",
            descriptionSource = "screenscraper",
            coverArtUri = "file:///cover.png",
            flatArtUri = "file:///logo.png",
        )
        assertEquals(ProviderEnrichmentState.VERIFIED_COMPLETE, state)
        // Unchanged by the menu-scope policy -- the seat stays Settings-only, and the menu it opens never
        // gains an Enrich entry (nothing is missing to retry).
        assertFalse(state.shouldOfferEnrich)
    }

    // ── providerScrapeGapPills: the honest-tag vocabulary  ─────────────────

    @Test
    fun `a never-scraped game shows no pills at all`() {
        assertEquals(
            emptyList<String>(),
            providerScrapeGapPills(
                description = null,
                descriptionSource = null,
                coverArtUri = null,
                flatArtUri = null,
            ),
        )
        // Blank, not just null, source still counts as "never scraped" (matches providerEnrichmentState's
        // own isNullOrBlank gate).
        assertEquals(
            emptyList<String>(),
            providerScrapeGapPills(
                description = null,
                descriptionSource = "   ",
                coverArtUri = null,
                flatArtUri = null,
            ),
        )
    }

    @Test
    fun `a complete verified record shows no pills`() {
        assertEquals(
            emptyList<String>(),
            providerScrapeGapPills(
                description = "A complete synopsis",
                descriptionSource = "screenscraper",
                coverArtUri = "file:///cover.png",
                flatArtUri = "file:///logo.png",
            ),
        )
    }

    @Test
    fun `each missing field gets its own exact pill, in synopsis-cover-logo order`() {
        assertEquals(
            listOf("No synopsis"),
            providerScrapeGapPills(null, "screenscraper", "file:///cover.png", "file:///logo.png"),
        )
        assertEquals(
            listOf("No cover"),
            providerScrapeGapPills("A synopsis", "screenscraper", null, "file:///logo.png"),
        )
        assertEquals(
            listOf("No logo"),
            providerScrapeGapPills("A synopsis", "screenscraper", "file:///cover.png", null),
        )
        assertEquals(
            listOf("No synopsis", "No cover", "No logo"),
            providerScrapeGapPills(null, "screenscraper", null, null),
        )
    }

    @Test
    fun `blank fields count as absent, same as null`() {
        assertEquals(
            listOf("No synopsis", "No cover", "No logo"),
            providerScrapeGapPills("   ", "screenscraper", "", "   "),
        )
    }

    /**
     * Guards the "pill line and button state can never disagree" claim within its defined scope: every
     * blank/non-blank combination of the three fields (2^3 = 8 cells) is exercised, and each pill's
     * presence is asserted against that exact cell -- not just against a couple of hand-picked examples
     * that could pass vacuously if the window shifted. descriptionSource is fixed non-blank throughout (the
     * "was this scraped at all" gate is covered separately above).
     */
    @Test
    fun `pill presence matches field blankness across every combination`() {
        val blank = ""
        val present = "value"
        var cellsChecked = 0
        for (descBlank in listOf(true, false)) {
            for (coverBlank in listOf(true, false)) {
                for (flatBlank in listOf(true, false)) {
                    val pills = providerScrapeGapPills(
                        description = if (descBlank) blank else present,
                        descriptionSource = "screenscraper",
                        coverArtUri = if (coverBlank) blank else present,
                        flatArtUri = if (flatBlank) blank else present,
                    )
                    assertEquals("descBlank=$descBlank pills=$pills", descBlank, "No synopsis" in pills)
                    assertEquals("coverBlank=$coverBlank pills=$pills", coverBlank, "No cover" in pills)
                    assertEquals("flatBlank=$flatBlank pills=$pills", flatBlank, "No logo" in pills)
                    cellsChecked++
                }
            }
        }
        assertEquals(8, cellsChecked)
    }

    // The wiring pin that lived here read the old GameDetailScreen quick-facts chips block. That block was dead code (nothing composed it: the Library's card is the
    // one shared HomeLaunchCard, which draws no scrape-gap pills) and went with the one-game-card change (2026-10-13), so the pin went too. The pure vocabulary above
    // stays tested; a surface that wants the pills again calls GameRow.providerScrapeGapPills() and re-adds a pin of its own.
}
