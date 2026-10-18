package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a `pc_games` row needs only a SteamGridDB key. The gamelist media pill's
 * Enrich affordance (`LibraryScreen.kt`'s `GamelistView`) must agree with `MimirArtworkState.fetchOne`
 * (`app/…/MimirArtworkCoordinator.kt`) about whether pressing it will actually fetch -- an enabled pill
 * that then refuses, or a disabled pill over a fetch that would have worked, are both dishonest.
 * [gamelistEnrichAllowed] is the pure predicate both the pill's `enabled` and (by construction, since
 * `fetchOne` uses the identical `isPc` split) the action agree on; these tests pin its truth table
 * directly rather than through a Compose render.
 */
class LibraryScreenGamelistEnrichGateTest {

    @Test
    fun `a pc_games row is enabled by EITHER provider, SteamGridDB's key or a connected ScreenScraper`() {
        assertTrue(
            "a keyed pc_games row must be allowed even with no ScreenScraper account -- the device defect the " +
                "existing Enrich availability remains intact",
            gamelistEnrichAllowed(PlatformId.PC_GAMES, canFetchArt = false, steamGridDbKeyPresent = true),
        )
        assertTrue(
            "a keyless pc_games row with ScreenScraper connected may press Enrich, which " +
                "asks ScreenScraper for the game's logo",
            gamelistEnrichAllowed(PlatformId.PC_GAMES, canFetchArt = true, steamGridDbKeyPresent = false),
        )
        assertFalse(
            "with neither provider there is nothing to ask",
            gamelistEnrichAllowed(PlatformId.PC_GAMES, canFetchArt = false, steamGridDbKeyPresent = false),
        )
    }

    @Test
    fun `every other platform is judged on ScreenScraper alone, never SteamGridDB`() {
        assertTrue(
            gamelistEnrichAllowed(PlatformId.PS1, canFetchArt = true, steamGridDbKeyPresent = false),
        )
        assertFalse(
            "a SteamGridDB key must never leak into a platform SteamGridDB does not serve",
            gamelistEnrichAllowed(PlatformId.PS1, canFetchArt = false, steamGridDbKeyPresent = true),
        )
    }

    // The LIST view's pill must route the identical `gamelistEnrichAllowed(<platform>, canFetchArt, steamGridDbKeyPresent)` invocation through
    // the `enabled = !offerEnrich || …` guard, so the pill cannot hand-roll the condition again (follow-up: two views of the
    // same game once answered "can Enrich fetch?" differently). The CAROUSEL view used to be the second call site; since 2026-10-10 it is the Home
    // shelf, which paints no Enrich pill at all (Enrich is on Y and in the Edit menu), so the LIST view is the only place this pill is drawn.
    // This is a source-text pin (feature-library has no Compose harness for the list card, same convention as PcEnrichSteamGridDbRoutingWiringTest).

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    /** The exact `enabled = !offerEnrich || …` expression, trimmed to just past its own comma/newline. */
    private fun enrichEnabledExpression(source: String): String {
        val anchor = "enabled = !offerEnrich ||"
        val start = source.indexOf(anchor)
        check(start >= 0) { "search window missed the pill's own enabled= line -- assertions would pass vacuously" }
        return source.substring(start, minOf(start + 160, source.length))
    }

    @Test
    fun `the LIST view's media pill resolves Enrich enablement through gamelistEnrichAllowed`() {
        val expression = enrichEnabledExpression(sourceOf("LibraryScreen.kt"))
        assertTrue(
            "LIST-mode pill must call gamelistEnrichAllowed, not re-derive the condition inline -- found: " +
                "<$expression>",
            expression.contains("gamelistEnrichAllowed(selectedGame.platformId, canFetchArt, steamGridDbKeyPresent)"),
        )
        assertEquals(
            "the pill passes the platform, then canFetchArt, then steamGridDbKeyPresent -- in that order",
            ", canFetchArt, steamGridDbKeyPresent",
            expression.substringAfter("gamelistEnrichAllowed(").substringBefore(")").substringAfter(".platformId"),
        )
    }

    @Test
    fun `the shelf view paints no Enrich pill, so there is no second answer to keep in step`() {
        val shelf = sourceOf("LibraryGameShelf.kt")
        assertFalse(shelf.contains("offerEnrich"))
        assertFalse(shelf.contains("GamelistMediaActionChip"))
        assertFalse(shelf.contains("canFetchArt"))
    }
}
