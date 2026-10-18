package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What must stay true while the favourites step's two pages overlap.
 *
 * asked for the glide; `AnimatedContent` delivers it by keeping BOTH pages composed
 * for the whole [com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs] window. That window did not exist
 * when this step was a hard cut, so every hazard below is one the transition itself introduced.
 *
 * Source-reviewed for input behavior on 2026-09-18, which confirmed the disjoint-FocusKey reasoning and
 * found two gaps in the first cut of the gating. Both are pinned here, because both were invisible to the
 * behavioural tests: they are about what a DEPARTING composition does, and a unit test only ever sees one.
 *
 * Source-text assertions, the same idiom [OnboardingAccessMotionSourceTest] uses to pin `OnboardingPill`'s
 * own gating, and for the same reason -- the property is "this composable cannot do X while departing",
 * which no single-composition test can observe.
 */
class OnboardingFavoritesHandoverTest {
    private val source: String by lazy { sourceFile("OnboardingFlow.kt") }

    /** The router is what decides which page is leaving; both pages must be told. */
    @Test
    fun `the router tells each page whether it is the one leaving`() {
        val block = source.sliceBetween(
            "label = \"onboardingFavoritesPageGlide\",",
            "\n}\n",
        )
        assertTrue(
            "the router must derive liveness from the page actually on stage",
            block.contains("val live = presented == filter.page"),
        )
        assertEquals(
            "both favourites pages must receive interactive = live -- a page that is not told it is " +
                "leaving cannot stop acting",
            2,
            Regex("interactive = live,").findAll(block).count(),
        )
    }

    /**
     * Touch-gate finding. The chip's touch handler calls `authority.requestFocus` BEFORE it calls back, so
     * gating the callback in the router left the focus move running. A stray touch on the departing chip
     * parked focus on a key the arriving INTRO page does not own, and neither INTRO pill painted as focused.
     *
     * The gate must therefore wrap the WHOLE lambda, not the `onOpen()` line inside it.
     */
    @Test
    fun `the platform chip gates its focus move, not merely its callback`() {
        val chip = source.sliceBetween(
            "private fun OnboardingFavoritesPlatformFilterChip(",
            "MimirAnimatedChromeText(",
        )
        assertTrue("the chip must accept interactive itself", chip.contains("interactive: Boolean = true,"))
        val guarded = chip.sliceBetween("if (interactive) {", "}")
        assertTrue(
            "authority.requestFocus must sit INSIDE the interactive guard -- outside it, a departing chip " +
                "still moves focus to a key the arriving page does not have",
            guarded.contains("authority.requestFocus(OnboardingFavoritesPlatformFilterKey)"),
        )
        // Matched on the call and not on `onOpen()` verbatim: since 2026-09-21 it carries the key focus
        // was standing on before the tap moved it, so the page can return there when the list closes.
        // What this pins is unchanged -- the call sits inside the guard, so a departing chip fires nothing.
        assertTrue("and onOpen with it", guarded.contains("onOpen("))
    }

    /**
     * Return-focus finding. The controller's B out of the Tome defers a focus target and bumps
     * `focusIntent.revision`; that bump re-fires the DEPARTING page's landing effect, whose `landFocus`
     * asks `pendingTarget` for a target against the departing page's own key list -- and `pendingTarget`
     * CLEARS the pending target as a side effect of finding it does not belong there. The arriving page
     * then finds nothing pending.
     *
     * It still landed correctly, but only because INTRO's `entryFocus` happened to equal the wiped key.
     * That is a coincidence, not a mechanism, and it is the kind that survives until someone changes one of
     * the two values.
     *
     * `interactive` must be BOTH a guard and a key: a guard so the departing effect does nothing, and a key
     * so the Tome's up-to-8-frame `requestFocus` bootstrap is CANCELLED rather than left dragging focus
     * back to a page the player is leaving.
     */
    @Test
    fun `only the arriving page lands focus for the favourites surface`() {
        val intro = source.sliceBetween(
            "private fun OnboardingFavoritesIntroScreen(",
            "private fun OnboardingFavoritesTomeScreen(",
        )
        assertTrue(
            "INTRO's landing effect must be keyed on interactive",
            intro.contains("LaunchedEffect(focusIntent.revision, interactive) {"),
        )
        assertTrue(
            "INTRO must not land focus while it is the page leaving",
            intro.contains("if (!interactive) return@LaunchedEffect"),
        )

        val tome = source.sliceBetween(
            "private fun OnboardingFavoritesTomeScreen(",
            "// Every visible game keeps a focus slot",
        )
        assertTrue(
            "the Tome's landing effect must be keyed on interactive, so its multi-frame requestFocus " +
                "bootstrap is cancelled when the page starts leaving -- not merely skipped next time",
            tome.contains("LaunchedEffect(focusIntent.revision, visible.isEmpty(), interactive) {"),
        )
        assertTrue(
            "the Tome must not land focus while it is the page leaving",
            tome.contains("if (!interactive) return@LaunchedEffect"),
        )
    }

    /**
     * Walk-32d (Nova, 2026-09-30), "Doubled sky": each page drew its own sky, so the glide slid two starfields past
     * each other. One sky now, the step's, under both pages; the Tome's glass on it; its overlays over it.
     */
    @Test
    fun `the favourites step draws one sky under both pages`() {
        val router = source.sliceBetween("private fun OnboardingFavoritesScreen(", "\n}\n")
        val sky = router.indexOf("MimirShelfBackground(")
        assertTrue("the step draws the sky", sky >= 0)
        assertTrue("under the pages", sky < router.indexOf("AnimatedContent("))
        assertTrue(
            "the Tome's glass rides on it",
            // RC-27 (audit 2026-10-14): a draw-phase progress now, not the Float overload that recomposed the page per frame.
            router.contains("modifier = Modifier.fillMaxSize().mimirGlassBackdropProgress(filterGlass),"),
        )
        assertTrue(
            "the overlays sit over the sky and its glass, after the pages",
            router.indexOf("OnboardingFavoritesTomeOverlays(") > router.indexOf("label = \"onboardingFavoritesPageGlide\","),
        )
        val intro = source.sliceBetween(
            "private fun OnboardingFavoritesIntroScreen(",
            "private fun OnboardingFavoritesTomeScreen(",
        )
        assertTrue("INTRO draws no sky of its own", intro.contains("drawsSky = false,"))
        val tome = source.sliceBetween("private fun OnboardingFavoritesTomeScreen(", "\n}\n")
        for (own in listOf(
            "MimirShelfBackground(",
            "mimirGlassBackdrop(",
            "mimirGlassBackdropProgress(",
            "OnboardingKeyboardLayer(",
            "OnboardingFavoritesPlatformDropdown(",
        )) {
            assertFalse("the Tome page draws no $own of its own", tome.contains(own))
        }
    }

    /**
     * Walk-32d, "Keyboard pops": both onboarding keyboards arrive and leave as a sheet, on one clock, and a keyboard
     * that is leaving takes no pad, no Back and no touch.
     */
    @Test
    fun `the onboarding keyboards arrive and leave as a sheet, inert while they leave`() {
        val layer = source.sliceBetween("private fun OnboardingKeyboardLayer(", "\n}\n")
        assertTrue(layer.contains("AnimatedVisibility(\n        visible = open,"))
        assertEquals(
            "fade in, fade out and the keyboard's travel on one clock",
            3,
            Regex("mimirSheetSpec\\(sheetReducedMotion\\)").findAll(layer).count(),
        )
        assertTrue("no host once it leaves", layer.contains("rememberMimirKeyboardHost(authority, active = open)"))
        assertTrue("no Back once it leaves", layer.contains("BackHandler(enabled = open, onBack = onClose)"))
        assertTrue("no touch once it leaves", layer.contains("Modifier.onboardingHandoverTouchShield()"))
        assertEquals(
            "PROFILE's and the Tome's keyboards are both the sheet",
            2,
            Regex("OnboardingKeyboardLayer\\(\\n\\s+open = ").findAll(source).count(),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading to a no-op pass. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        val fromAfter = substringAfter(after)
        assertTrue("anchor missing: $before", fromAfter.contains(before))
        return fromAfter.substringBefore(before)
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
