package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text characterization of the ONE call site wiring [GetArtOverlay]'s two `NeedsAccount` actions to
 * their real destinations (`MimirAppHost.kt`'s `MimirArtworkOverlayHost`). the
 * `pc_games` card's Connect pill must go where its own sentence points -- the SteamGridDB key entry the
 * product already has (Settings › Appearance › Enrichment & artwork, the SAME destination
 * `MimirSettingsCoordinator.kt`'s `"appearance/enrichment/steamgriddb"` row opens at line ~598) -- not the
 * ScreenScraper login flow the pill unconditionally opened before this fix.
 *
 * `GetArtOverlayProviderCopyTest`/`NeedsAccountCtaTest` prove [GetArtOverlay] itself picks the right label
 * and action for a given [GetArtProvider] -- but both drive the composable with plain test lambdas. Nothing
 * in either test proves `MimirAppHost.kt`'s real call site passes `onAddSteamGridDbKey` the SteamGridDB
 * destination rather than, say, `connectFromGetArt` again (a copy/paste of the pre-fix wiring), or that the
 * two callbacks are not swapped. `MimirAppHost.kt` has no JVM-reachable seam for a call site buried this
 * deep in one giant `@Composable` function's coordinator graph -- this repo's own precedent for exactly
 * that gap is `PcEnrichSteamGridDbRoutingWiringTest`, which this test mirrors: pin the call site as source
 * text, with every window guarded so a missed anchor cannot pass any assertion vacuously.
 *
 * Differential: revert `onAddSteamGridDbKey` back to `artwork.state::connectFromGetArt`, or swap the two
 * callbacks, or point the key-editor open at anything other than `settings.setSteamGridDbKeyEditing(true)`,
 * and one of the tests below fails.
 */
class GetArtOverlayConnectWiringSourceTest {

    private fun mimirAppHostSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppHost.kt not found from ${File(".").absolutePath}")
    }

    /**
     * Bounded from the `GetArtOverlay(` call through its `onDismiss` argument (the last positional
     * argument in source, present unconditionally) -- avoids naively pairing parentheses, which the
     * lambda bodies of the arguments in between (each containing their own `(...)` calls) would break.
     */
    private fun getArtOverlayCallSite(): String {
        val body = mimirAppHostSource()
        val start = body.indexOf("GetArtOverlay(")
        assertTrue("GetArtOverlay( call site not found -- every assertion below would pass vacuously", start >= 0)
        val onDismissAnchor = "onDismiss = artwork.state::dismissGetArt,"
        val onDismissIndex = body.indexOf(onDismissAnchor, start)
        assertTrue(
            "could not find the onDismiss anchor closing the call site -- the window below would be " +
                "unbounded or empty, and every assertion would pass vacuously",
            onDismissIndex > start,
        )
        return body.substring(start, onDismissIndex + onDismissAnchor.length)
    }

    @Test
    fun `onConnect still reaches connectFromGetArt, unchanged for every ScreenScraper-served platform`() {
        val callSite = getArtOverlayCallSite()

        assertTrue(
            "search window missed the onConnect wiring -- the assertion below would pass vacuously",
            callSite.contains("onConnect ="),
        )
        assertTrue(
            "the ScreenScraper route must retain its existing wiring",
            callSite.contains("onConnect = artwork.state::connectFromGetArt"),
        )
    }

    @Test
    fun `onAddSteamGridDbKey reaches the SteamGridDB key editor via the coordinator's own dismiss-and-open`() {
        val callSite = getArtOverlayCallSite()

        assertTrue(
            "search window missed the onAddSteamGridDbKey wiring -- assertions below would pass vacuously",
            callSite.contains("onAddSteamGridDbKey"),
        )
        assertTrue(
            "the pc_games key-entry pill must reach addSteamGridDbKeyFromGetArt, mirroring " +
                "connectFromGetArt's own shape",
            callSite.contains("artwork.state.addSteamGridDbKeyFromGetArt"),
        )
        assertTrue(
            "addSteamGridDbKeyFromGetArt must open the SAME destination the existing Settings row uses " +
                "(MimirSettingsCoordinator.kt's \"appearance/enrichment/steamgriddb\" row) -- no new " +
                "screen, no new navigation concept",
            callSite.contains("bindings.settings.setSteamGridDbKeyEditing(true)"),
        )
    }

    @Test
    fun `the two destinations are not swapped`() {
        val callSite = getArtOverlayCallSite()
        val betweenOnConnectAndOnAddKey = callSite
            .substringAfter("onConnect =")
            .substringBefore("onAddSteamGridDbKey")
        val afterOnAddKey = callSite.substringAfter("onAddSteamGridDbKey")

        assertFalse(
            "onConnect must never call setSteamGridDbKeyEditing -- that would send every non-pc_games " +
                "platform's Connect pill to the SteamGridDB key editor instead of the ScreenScraper login",
            betweenOnConnectAndOnAddKey.contains("setSteamGridDbKeyEditing"),
        )
        assertFalse(
            "onAddSteamGridDbKey must never call connectFromGetArt -- that would silently route the " +
                "pc_games key-entry pill back to the ScreenScraper login flow the sentence above it " +
                "never mentions",
            afterOnAddKey.substringBefore("onDismiss =").contains("connectFromGetArt"),
        )
    }
}
