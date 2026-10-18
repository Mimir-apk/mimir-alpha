package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-50, pinned at the source as well as at the value.
 *
 * **A value pin alone cannot fail here, and that is the whole point of this file.** The defect is not an
 * arithmetic one — every number on the shelf is already correct on the commit frame. What is wrong is
 * *which subtree exists*: three chrome elements are gated on FocusAuthority's LIVE scope, and `openTome`
 * changes that scope on the same synchronous commit that mounts the Folio, at glass progress 0. A pure
 * reducer test proves the new rule is stated; only a structural pin proves the rule is WIRED, and only a
 * count pin proves nobody quietly wired a fourth site later. All three live below.
 *
 * The source-text technique is this module's own precedent for the missing-Compose-harness gap
 * (`HomeShelfHeroTileConservesFocusThroughScopePushTest`, `HomeLaunchCardTest.homeLaunchCardSource()`),
 * bounded on the same unique top-level declaration so a match can only come from inside `HomeShelfScreen`.
 *
 * The sibling file `HomeShelfHeroTileConservesFocusThroughScopePushTest` is deliberately left untouched
 * and must stay green: its assertions are the proof that the dock and the Utility modal did not move.
 */
class HomeShelfChromeSurvivesTomeCommitFrameTest {

    private fun homeShelfScreenSource(): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeShelfScreen.kt not found from ${File(".").absolutePath}")
    }

    /** Same bound as the sibling pin: `homeShelfTitleTrailingReserve` occurs exactly once in the file. */
    private fun homeShelfScreenFunctionBody(): String = homeShelfScreenSource()
        .substringAfter("fun HomeShelfScreen(")
        .substringBefore("internal fun homeShelfTitleTrailingReserve(")

    /**
     * The ring and the runes moved from `HomeShelfScreen` into the shared strip (GameShelf.kt, 2026-10-10), so two of the
     * three conserved sites now live in `GameShelf`'s body; the third, the tile hint, stays in the screen. The strip takes
     * the scope and the Folio's hold as parameters, and `HomeShelfScreen` is pinned below to hand it Home's.
     */
    private fun gameShelfBody(): String = gameShelfStripBody()

    // ── The rule ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the shelf keeps its live chrome while the Folio covers it`() {
        assertTrue(
            "the tome commit frame: scope has moved to the Folio and glass progress is still 0, so the " +
                "chrome must still paint or the shelf visibly re-renders under a transparent cover",
            homeShelfLiveChromeLit(scopeOwnsInput = false, folioActive = true),
        )
    }

    @Test
    fun `the dock and Utility still take the chrome with them`() {
        assertFalse(
            "a pushed scope that is NOT the Folio leaves the shelf visible beside it -- a ring left lit " +
                "there is the reported device ghost trail",
            homeShelfLiveChromeLit(scopeOwnsInput = false, folioActive = false),
        )
    }

    @Test
    fun `an ordinary lit shelf is unchanged in both folio states`() {
        assertTrue(homeShelfLiveChromeLit(scopeOwnsInput = true, folioActive = false))
        assertTrue(
            "the settled Folio and the shelf-owns-input frame must agree, or the chrome flickers at the " +
                "seam instead of at the commit",
            homeShelfLiveChromeLit(scopeOwnsInput = true, folioActive = true),
        )
    }

    /**
     * The open and the return are each ONE commit frame carrying every term at once, so the only thing
     * that can break continuity is a frame where both terms are false. Replayed as the coordinator
     * actually sequences it (`openTome` :2357/:2383 · `restoreRootEntry` :2454/:2459/:2481).
     */
    @Test
    fun `the channel is continuous across the open and the return`() {
        // (scopeOwnsInput, folioActive) at each commit, in order.
        val timeline = listOf(
            "home, before the open" to (true to false),
            "openTome commit: scope pushed AND session assigned on one frame" to (false to true),
            "folio settled" to (false to true),
            "restoreRootEntry commit: scope popped AND session nulled on one frame" to (true to false),
            "glass still unwinding" to (true to false),
            "home, at rest" to (true to false),
        )
        timeline.forEach { (moment, terms) ->
            assertTrue(
                "the chrome went dark at \"$moment\" -- that frame IS the re-render regression",
                homeShelfLiveChromeLit(scopeOwnsInput = terms.first, folioActive = terms.second),
            )
        }
    }

    // ── The wiring ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `all three live-scope chrome sites route through the reducer`() {
        val body = homeShelfScreenFunctionBody()
        val strip = gameShelfBody()

        // The ring and the runes now sit in the shared strip, which reads the scope and the Folio's hold it is handed;
        // the tile hint below is outside the band and did not move.
        assertTrue(
            "the stationary focus ring is RC-50's named site: it unmounts on the tome's first commit frame",
            strip.contains(
                "homeShelfLiveChromeLit(\n" +
                    "                    scopeOwnsInput = authority.isLitFocusIn(scope, " +
                    "selectedItem.focusKey, focusState),\n" +
                    "                    folioActive = chromeHeldByCover,\n" +
                    "                )",
            ),
        )
        assertTrue(
            "the continuation runes flank the same band and flip on the same read, so they pop on the " +
                "same frame",
            strip.contains(
                "homeShelfLiveChromeLit(\n" +
                    "                                    scopeOwnsInput = authority.isLitFocusIn(" +
                    "scope, it, focusState),\n" +
                    "                                    folioActive = chromeHeldByCover,\n" +
                    "                                )",
            ),
        )
        // And Home hands the strip the scope it conserves for and the Folio's hold, which is what the two sites above read.
        // Asked of the call itself: `HomeShelfScope` is also named by the screen's focus registration, which is not the shelf's scope.
        val call = gameShelfCallInHome(body)
        assertTrue(
            "Home's shelf must be given Home's scope, or the strip's lit read is not Home's",
            call.contains("scope = HomeShelfScope,"),
        )
        assertTrue(
            "Home's shelf must be given the Folio's hold, or the ring and the runes unmount on the tome's commit frame",
            call.contains("chromeHeldByCover = tomeFolioActive,"),
        )
        assertTrue(
            "the focused tile's button hint is the third: today the return re-arms its whole ~1.6s timer",
            body.contains(
                "homeShelfLiveChromeLit(\n" +
                    "                            scopeOwnsInput = focusState.scope == HomeShelfScope,\n" +
                    "                            folioActive = tomeFolioActive,\n" +
                    "                        )",
            ),
        )
        assertTrue(
            "the hint must read the CONSERVED key -- under the Folio the live focused key is a Folio " +
                "member, so a live read resolves to null and the hint drops anyway",
            body.contains("val chromeFocusKey = authority.renderedFocusIn(HomeShelfScope, focusState)"),
        )
        assertFalse(
            "the pre-fix live-only hint gate must be gone",
            body.contains("val focusedDisplayItem = if (focusState.scope == HomeShelfScope) {"),
        )
    }

    @Test
    fun `exactly three sites are conserved, and the dock seat is not one of them`() {
        val body = homeShelfScreenFunctionBody()

        assertEquals(
            "RC-50 conserves the ring, the runes and the tile hint -- and nothing else. A fourth call " +
                "site broadens the conservation scope, which is the regression this test is an " +
                "instance of: check the surface before reusing the words. The ring and the runes are the " +
                "strip's, the tile hint the screen's.",
            3,
            Regex("homeShelfLiveChromeLit\\(").findAll(body + gameShelfBody()).count(),
        )

        val dockCall = body.substringAfter("MimirRunicQuickRail(")
        assertFalse(
            "the Home dock is persistent visible chrome, never covered by the Folio -- its seat dies " +
                "with its own scope and must never be conserved",
            dockCall.contains("homeShelfLiveChromeLit("),
        )
        assertTrue(
            "and it still reads the raw lit channel",
            dockCall.contains("authority.litFocusIn(HomeShelfQuickRailScope, focusState)"),
        )
        assertFalse(
            "HomeShelfEmptyAction lives in the orderedItems.isEmpty() branch, which no Folio can be " +
                "open over -- conserving it would be an unverified claim, not a fix",
            body.substringAfter("HomeShelfEmptyAction(")
                .substringBefore(")")
                .contains("homeShelfLiveChromeLit("),
        )
    }
}
