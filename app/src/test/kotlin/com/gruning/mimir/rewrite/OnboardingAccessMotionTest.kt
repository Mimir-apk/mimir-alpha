package com.gruning.mimir.rewrite

import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.unit.IntSize
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grant-state swaps and permission-screen handovers use the shared transition grammar (also used at
 * `ArtworkFetchOverlay.kt`/`GetArtOverlay.kt`/`MigrationImport.kt`/`HomeTileMenuOverlay.kt`): fade-in,
 * fade-out AND the resize run on the SAME `tween(MimirMotion.OverlayMs, MimirMotion.Easing)`, never a bare
 * `togetherWith` (a bare `togetherWith` lets `ContentTransform`'s default-arg `SizeTransform`
 * through, `spring(dampingRatio = 1f, stiffness = 400f)`, a second unbounded clock).
 *
 * Two classes of proof, same split as [ArtworkPhaseSequenceGrammarTest] (this package) and its own KDoc's
 * reasoning: [onboardingAccessMotionSpec] is a pure, Compose-free value function — exercised directly, no
 * harness needed, in [OnboardingAccessMotionSpecTest]. Everything living inside an actual
 * `transitionSpec`/`AnimatedContent` content lambda runs on a Compose animation clock this module's
 * Robolectric harness does not reliably sample mid-flight (`HomeTomeLibraryHeaderStatusMotionTest`,
 * `ArtworkPhaseSequenceGrammarTest`'s own KDoc), so the wiring itself — the shared clock via the helper, the
 * explicit `SizeTransform`, and the departing-page inertness guard — is pinned as real file text in
 * [OnboardingAccessMotionSourceTest], the SAME established pattern this package already uses for those four
 * overlays.
 *
 * NOT provable here, and not claimed: that the fade actually reads as smooth on a real screen, that reduced
 * motion is genuinely detected from the platform's "disable animations" setting on device, or that the
 * departing-page FocusKey guard actually prevents the registration race under a real 180ms transition. Those visual and platform claims need device
 * evidence; this file proves wiring only.
 */
class OnboardingAccessMotionSpecTest {
    @Test
    fun `active motion runs on onboarding's tempo and the house easing`() {
        // Onboarding uses 3x the house transient duration.
        val spec = onboardingAccessMotionSpec<Float>(reducedMotion = false)
        assertEquals(OnboardingMotionMs, spec.durationMillis)
        assertEquals(MimirMotion.OverlayMs * 3, spec.durationMillis)
        assertEquals(MimirMotion.Easing, spec.easing)
    }

    @Test
    fun `reduced motion collapses duration to zero without changing the easing`() {
        // "must not run" is realized as an instant (0ms) tween, not a second skipped-call code path — same
        // idiom as core-ui's mimirHintToastRailFadeSpec / StorageNoticeHost's storageNoticeFadeSpec.
        val spec = onboardingAccessMotionSpec<Float>(reducedMotion = true)
        assertEquals(0, spec.durationMillis)
        assertEquals(MimirMotion.Easing, spec.easing)
    }

    @Test
    fun `the IntSize variant SizeTransform actually uses honours reduced motion identically`() {
        // SizeTransform's own spec lambda returns FiniteAnimationSpec<IntSize> -- the same generic helper,
        // exercised at the OTHER type parameter OnboardingFlow.kt actually instantiates it at.
        val active = onboardingAccessMotionSpec<IntSize>(reducedMotion = false)
        val reduced = onboardingAccessMotionSpec<IntSize>(reducedMotion = true)
        assertEquals(OnboardingMotionMs, active.durationMillis)
        assertEquals(0, reduced.durationMillis)
    }

    @Test
    fun `active and reduced specs differ ONLY in duration`() {
        val active = onboardingAccessMotionSpec<Float>(reducedMotion = false)
        val reduced = onboardingAccessMotionSpec<Float>(reducedMotion = true)
        assertEquals(active.easing, reduced.easing)
        assertTrue(active.durationMillis != reduced.durationMillis)
    }

    /**
     * An in-step handover is
     * one even cross-fade on the swap tier's clock. ONE OverlayMs for the page left and the page arriving, no lead, the same
     * each way, complementary at every frame, so the pane is never emptier than the overlap itself (0.75 at the midpoint).
     */
    @Test
    fun `an in-step handover is one even cross-fade on the swap clock`() {
        val exit = onboardingHandoverExitSpec<Float>(reducedMotion = false) as TweenSpec<Float>
        val enter = onboardingHandoverEnterSpec<Float>(reducedMotion = false) as TweenSpec<Float>
        assertEquals(MimirMotion.OverlayMs, exit.durationMillis)
        assertEquals(MimirMotion.OverlayMs, enter.durationMillis)
        assertEquals("no lead: the pages overlap", 0, exit.delay)
        assertEquals(0, enter.delay)
        assertEquals(MimirMotion.Easing, exit.easing)
        assertEquals(MimirMotion.Easing, enter.easing)

        fun leaving(t: Int) = 1f - exit.easing.transform((t.toFloat() / exit.durationMillis).coerceIn(0f, 1f))
        fun arriving(t: Int) = enter.easing.transform((t.toFloat() / enter.durationMillis).coerceIn(0f, 1f))
        (0..enter.durationMillis).forEach { t ->
            assertEquals("complementary at $t ms", 1f, leaving(t) + arriving(t), 0.001f)
        }
        // Coverage of the pane: what is left of the ground after both pages are laid over it.
        val coverage = (0..enter.durationMillis).minOf { 1f - (1f - leaving(it)) * (1f - arriving(it)) }
        assertTrue("the pane never empties: min coverage $coverage", coverage >= 0.74f)
    }

    @Test
    fun `under reduced motion a handover is immediate, with no lead`() {
        val exit = onboardingHandoverExitSpec<Float>(reducedMotion = true) as TweenSpec<Float>
        val enter = onboardingHandoverEnterSpec<IntSize>(reducedMotion = true) as TweenSpec<IntSize>
        assertEquals(0, exit.durationMillis)
        assertEquals(0, enter.durationMillis)
        assertEquals(0, enter.delay)
    }
}

/** Presence-checked source-text pins, same discipline as [ArtworkPhaseSequenceGrammarTest]'s own. */
class OnboardingAccessMotionSourceTest {
    private val source: String by lazy { sourceFile("OnboardingFlow.kt") }

    private fun assertHandoverGrammar(block: String, siteName: String) {
        assertTrue(
            "$siteName must supply an explicit SizeTransform so the resize rides a named clock, " +
                "per HomeTomeLibraryOverlay.kt:1301-1320's reference shape",
            block.contains(".using(") && block.contains("SizeTransform(clip = false)"),
        )
        assertFalse(
            "$siteName must never use the bare togetherWith form -- it carries Compose's default " +
                "spring(dampingRatio = 1f, stiffness = 400f) SizeTransform, a second unbounded clock " +
                "(PRs #165/#166)",
            Regex("togetherWith\\s*\\)\\.?\\s*(label|content)").containsMatchIn(block),
        )
        // Walk-32d (2026-09-30): the handover's two clocks. The incoming fade and the resize ride the entry, after
        // the lead; the outgoing fade rides the exit. Nothing is left on the old simultaneous clock.
        val entry = Regex("onboardingHandoverEnterSpec\\(reducedMotion\\)").findAll(block).count()
        val exit = Regex("onboardingHandoverExitSpec\\(reducedMotion\\)").findAll(block).count()
        assertEquals("$siteName: the incoming fade and the resize on the entry clock -- found $entry", 2, entry)
        assertEquals("$siteName: the outgoing fade on the exit clock -- found $exit", 1, exit)
        assertFalse("$siteName must not cross-fade on one clock", block.contains("onboardingAccessMotionSpec("))
    }

    @Test
    fun `item 2 -- the grant-state AnimatedContent hands over on the handover clocks`() {
        val block = source.sliceBetween(
            "AnimatedContent(\n            targetState = granted,",
            "label = \"onboardingAccessGrantFade\",",
        )
        assertHandoverGrammar(block, "OnboardingAccessCapabilityScreenBody's grant-state AnimatedContent")
    }

    /**
     *  The one-clock grammar this class was written to guard did not go away -- it moved into
     * [onboardingGlide], which every NAVIGATION boundary now shares. So this site's obligation changed from
     * "spell the grammar correctly" to "do not spell it at all", which is the stronger property: a site that
     * cannot hand-roll a spec cannot hand-roll a wrong one.
     *
     * The grammar itself is asserted once, against the builder, in
     * [`the shared glide builder runs fade, travel and resize on one clock`].
     */
    @Test
    fun `item 3 -- the screen-to-screen AnimatedContent delegates to the shared glide builder`() {
        val block = source.sliceBetween(
            "AnimatedContent(\n            targetState = current,",
            "label = \"onboardingAccessScreenGlide\",",
        )
        assertTrue(
            "OnboardingAccessScreen's AnimatedContent must call onboardingGlide rather than building its " +
                "own ContentTransform",
            block.contains("onboardingGlide("),
        )
        assertTrue(
            "and must take its direction from the sub-sequence's own enum order, which is what makes " +
                "backing out of a capability screen look different from advancing into it",
            block.contains("forward = targetState.ordinal > initialState.ordinal,"),
        )
        assertFalse(
            "no navigation site may spell a fade or a SizeTransform itself -- that is the builder's job, " +
                "and a second spelling is a second chance to get the clock wrong (PRs #165/#166)",
            block.contains("fadeIn(") || block.contains("SizeTransform("),
        )
    }

    /**
     * The grammar, asserted once, where it now lives.
     *
     * FIVE channels, not three: the incoming fade, the incoming travel, the outgoing fade, the outgoing
     * travel, and the resize. Adding travel is exactly what the 2026-09-18 ruling asked for, and it is only
     * safe because it rides the SAME clock -- a slide on its own spring would be the second unbounded clock
     * PRs the earlier update were about, just wearing different clothes.
     */
    @Test
    fun `the shared glide builder runs fade, travel and resize on the handover clocks`() {
        val block = source.sliceBetween(
            "internal fun onboardingGlide(",
            "\n}\n",
        )
        // Walk-32d (2026-09-30): the arrival's fade, travel and the resize on the entry clock, after the lead; the
        // departure's fade and travel on the exit clock. Every channel still rides a named tween.
        val entry = Regex("onboardingHandoverEnterSpec\\(reducedMotion\\)").findAll(block).count()
        val exit = Regex("onboardingHandoverExitSpec\\(reducedMotion\\)").findAll(block).count()
        assertEquals("the incoming fade, the incoming travel and the resize on the entry clock -- found $entry", 3, entry)
        assertEquals("the outgoing fade and the outgoing travel on the exit clock -- found $exit", 2, exit)
        assertFalse("no channel is left on the simultaneous clock", block.contains("onboardingAccessMotionSpec("))
        assertTrue(
            "the SizeTransform must be supplied as a NAMED constructor argument. This is stronger than the " +
                "old `.using(` spelling it replaced: ContentTransform's sizeTransform parameter defaults to " +
                "a spring, and naming it here means there is no default left to leak through",
            block.contains("sizeTransform = SizeTransform(clip = false)"),
        )
        assertFalse(
            "never the bare togetherWith form, which carries that default spring SizeTransform " +
                "(dampingRatio = 1f, stiffness = 400f) -- a second unbounded clock (PRs #165/#166)",
            Regex("togetherWith\\s*\\)").containsMatchIn(block),
        )
    }

    /**
     * Onboarding navigation sequences share one transition builder, so count its call sites rather than
     * inspecting an incomplete remembered list. Three boundaries go through the one builder:
     * the ACCESS sub-sequence, the DONE tail, and the favourites INTRO <-> TOME pages -- the last of which
     * had no transition at all before 2026-09-18.
     *
     * **Three, not every boundary in the file.** The outer `OnboardingStep` boundary is a navigation
     * boundary too and still hard-cuts, excluded by commit `59f96714` for four hazards named in
     * [onboardingGlide]'s own KDoc. This test does not assert that exclusion is right -- it is an unresolved design decision -- only that the three that DO glide all go through one place.
     *
     * A future sequence SHOULD raise this number. It failing is the reminder to route that sequence through
     * the builder too, and to say here which one it is.
     */
    @Test
    fun `every navigation boundary in the file glides, and none of them alone`() {
        // Call sites only: the declaration wraps its parameters too since it gained `fade`.
        val callSites = Regex("(?<!fun )\\bonboardingGlide\\(\\n").findAll(source).count()
        // Raised from 3 to 5 on 2026-09-24 (D-14/D-15 reserved control slot): the ACCESS sub-sequence and the DONE
        // tail each gained a controls host in the bottom slot that hands over in lockstep with its body host, on
        // the same builder -- growth, not regression. 5 -> 3 on 2026-09-28: the DONE tail folded into one FINISH
        // page (policy Q3), so its two hosts had no pages left to hand over between.
        assertEquals(
            "expected 3 boundaries calling onboardingGlide (ACCESS body + controls, favourites INTRO <-> TOME) " +
                "-- found $callSites",
            3,
            callSites,
        )
        listOf(
            "onboardingAccessScreenGlide",
            "onboardingAccessControlsGlide",
            "onboardingFavoritesPageGlide",
            "onboardingLibraryViewGlide",
            "onboardingLibraryControlsGlide",
            "onboardingActionPageGlide",
        ).forEach { label ->
            assertTrue("no AnimatedContent labelled $label -- a sequence lost its handover", source.contains(label))
        }
    }

    @Test
    fun `every AnimatedContent host site reads reducedMotion from mimirReducedMotion, not a hardcoded value`() {
        // OnboardingAccessScreen (item 3's caller) and OnboardingAccessCapabilityScreenBody (item 2's owner)
        // each compute their own `reducedMotion` -- both must trace to mimirReducedMotion(), the
        // ValueAnimator.areAnimatorsEnabled()-backed source the rest of the product reads.
        // A third site joined them when the DONE tail's screen-to-screen fade was added
        // (OnboardingDoneScreen -- see OnboardingTailMotionTest.kt
        // for that site's own pins): the count grew from 2 to 3, not a regression of this one. A fourth
        // joined on 2026-09-18 when the favourites INTRO <-> TOME boundary stopped hard-cutting
        // (OnboardingFavoritesScreen) -- again growth, not regression.
        val occurrences = Regex("val reducedMotion = mimirReducedMotion\\(\\)").findAll(source).count()
        // Every host must read mimirReducedMotion(): page glides, reserved-control grant fades,
        // FINISH eyebrow/legend entry (including the 160ms OnboardingFinishEyebrow), LIBRARY views
        // and action pages. Adding a host that reads
        // the shared source changes this count without weakening the invariant.
        assertEquals(
            "expected exactly 8 sites reading mimirReducedMotion() (OnboardingAccessScreen, " +
                "OnboardingAccessControls, OnboardingAccessCapabilityScreenBody, " +
                "OnboardingAccessCapabilityControls, OnboardingLibraryScreen, OnboardingLibraryPages, " +
                "OnboardingFavoritesScreen, OnboardingFinishEyebrow) -- found $occurrences",
            8,
            occurrences,
        )
    }

    @Test
    fun `OnboardingPill accepts an interactive flag defaulting to true`() {
        assertTrue(
            "OnboardingPill must default interactive to true so every OTHER onboarding screen's pill " +
                "calls (WELCOME/PROFILE/LIBRARY/METADATA/SETTLING/THEME/DONE) are unaffected",
            source.contains("interactive: Boolean = true,"),
        )
    }

    @Test
    fun `OnboardingPill gates mimirFocusable, activation and the focusReveal write on interactive`() {
        val pillBody = source.sliceBetween(
            "private fun OnboardingPill(",
            ".heightIn(min = if (compact) 40.dp else 46.dp)",
        )
        assertTrue(
            "a non-interactive pill must skip mimirFocusable registration entirely (not merely clear " +
                "semantics) -- see OnboardingPill's own doc for why: a departing page's shared FocusKey " +
                "unregister would delete the arriving page's live registration otherwise",
            pillBody.contains("if (!interactive) {") && pillBody.contains("Modifier.mimirFocusable("),
        )
        assertTrue(
            "a non-interactive pill must not write into focusReveal (same shared-key hazard)",
            pillBody.contains("if (!interactive || focusReveal == null) {"),
        )
        assertTrue(
            "a non-interactive pill must clear semantics instead of being activatable",
            pillBody.contains("Modifier.clearAndSetSemantics { }") && pillBody.contains("Modifier.mimirActivatable("),
        )
        // The gate was inline as `focused = focused && interactive,`. Adding a focused
        // gold tint to the same pill made that spelling incomplete: the tint has to be gated on the identical
        // predicate, or a departing crossfade page (composed for the full OverlayMs, holding the shared
        // OnboardingAccessContinueKey, so `focused == true`) paints the focused tint with no ring. Both
        // affordances now read one derived `focusAffordanceActive`. The assertion below pins THAT, and still
        // fails if the ring is ever handed bare `focused` -- which is the property this test was written for.
        assertTrue(
            "the focus ring itself must not read as focused when non-interactive -- it must consume the " +
                "shared focusAffordanceActive predicate, which is the ONLY place `focused && interactive` " +
                "is derived (see OnboardingGoldPillFocusAffordanceTest for the tint half of the pair)",
            pillBody.contains("focused = focusAffordanceActive,") &&
                pillBody.contains("val focusAffordanceActive = focused && interactive"),
        )
    }

    @Test
    fun `both capability-screen bodies wrap their content in a Column for AnimatedContent`() {
        // AnimatedContent lays out multiple direct children like a Box, not a Column -- ArtworkFetchOverlay
        // / GetArtOverlay / MigrationImport all wrap their own page bodies in one Column for exactly this
        // reason. OnboardingAccessOrientationBody and OnboardingAccessCapabilityScreenBody are invoked from
        // inside item 3's AnimatedContent content lambda, so each must do the same.
        val orientationBody = source.sliceBetween(
            "private fun OnboardingAccessOrientationBody(",
            "internal fun OnboardingAccessCapabilityScreenBody(",
        )
        assertTrue(
            "OnboardingAccessOrientationBody must wrap its content in a CenterHorizontally Column",
            orientationBody.contains("Column(horizontalAlignment = Alignment.CenterHorizontally) {"),
        )

        val capabilityBodyHeader = source.sliceBetween(
            "internal fun OnboardingAccessCapabilityScreenBody(",
            "AnimatedContent(\n            targetState = granted,",
        )
        assertTrue(
            "OnboardingAccessCapabilityScreenBody must wrap its OWN top-level content (title/why/whatBreaks " +
                "+ the grant AnimatedContent) in a CenterHorizontally Column, one level above item 2's own " +
                "inner Column",
            capabilityBodyHeader.contains("Column(horizontalAlignment = Alignment.CenterHorizontally) {"),
        )
    }

    /**
     * The pill's ring could clip briefly as a page arrived.
     * `fadeIn`/`fadeOut` fade through an offscreen buffer the size of the page, which cut the ring drawn outside
     * the pill. So every host whose pages hold pills fades them itself, per draw call, and never through those.
     */
    @Test
    fun `every pill host fades its pages per draw call, never through an offscreen buffer`() {
        for (label in listOf(
            "onboardingAccessControlsGlide",
            "onboardingAccessGrantControlsFade",
            "onboardingLibraryControlsGlide",
        )) {
            val anchor = "label = \"$label\","
            val spec = source.substringBefore(anchor).substringAfterLast("transitionSpec = {")
            val page = source.sliceBetween(anchor, "OnboardingPill(")
            assertFalse("$label must not fade through fadeIn/fadeOut", spec.contains("fadeIn(") || spec.contains("fadeOut("))
            assertTrue(
                "$label must turn the glide's own fade off, or not glide",
                spec.contains("fade = false") || !spec.contains("onboardingGlide("),
            )
            assertTrue(
                "$label's pages must fade themselves before the first pill",
                page.contains("onboardingRingSafeFadeAlpha(reducedMotion)") &&
                    page.contains("Modifier.onboardingRingSafeFade(fadeAlpha)"),
            )
        }
        // Promoted to core-ui for the swap tier (motion audit batch A); onboarding's name delegates to it.
        assertTrue(source.contains("internal fun Modifier.onboardingRingSafeFade(alpha: State<Float>): Modifier = mimirRingSafeFade(alpha)"))
        val core = listOf(
            java.io.File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSwapMotion.kt"),
            java.io.File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSwapMotion.kt"),
        ).first { it.isFile }.readText()
        val helper = core.substringAfter("fun Modifier.mimirRingSafeFade(").substringBefore("\n}\n")
        assertTrue(helper.contains("compositingStrategy = CompositingStrategy.ModulateAlpha"))
    }

    /**
     * Alpha audit F1b (2026-09-30): the page an in-step handover brings is live from its
     * first frame but unseen through the lead, so every host whose pages take presses holds the pad and touch until
     * that page paints.
     */
    @Test
    fun `every in-step handover holds presses until its page paints`() {
        for (label in listOf(
            "onboardingAccessControlsGlide",
            "onboardingAccessGrantControlsFade",
            "onboardingLibraryControlsGlide",
        )) {
            val page = source.sliceBetween("label = \"$label\",", "OnboardingPill(")
            assertTrue(
                "$label's pages must hold on their own fade",
                page.contains("onboardingHandoverArrivalHold(reducedMotion, fadeAlpha)"),
            )
        }
        for (label in listOf("onboardingActionPageGlide", "onboardingFavoritesPageGlide")) {
            val page = source.sliceBetween("label = \"$label\",", "\n}\n")
            assertTrue("$label's pages must hold", page.contains("onboardingHandoverArrivalHold(reducedMotion)"))
        }
        val hold = source.sliceBetween("internal fun AnimatedVisibilityScope.onboardingHandoverArrivalHold(", "\n}\n")
        assertTrue("the pad waits on the product's page gate", hold.contains("holdMimirInputUntilShown(gateOwner)"))
        assertTrue(
            "until the page paints, or a reversal takes it off the target",
            hold.contains("alpha.value > 0f || pageTransition.targetState != EnterExitState.Visible"),
        )
        assertTrue(
            "the page a host opens on is already showing and holds nothing",
            hold.contains("if (pageTransition.currentState == EnterExitState.Visible) return@LaunchedEffect"),
        )
        assertTrue("touch waits too", hold.contains("Modifier.onboardingHandoverTouchShield()"))
    }

    @Test
    fun `the reserved-slot control host threads interactive = live into every screen branch`() {
        // Since the reserved control slot (2026-09-24) the ACCESS pills live in their controls host and the
        // bodies hold none. An off-stage page's controls must be inert, so every branch of the host passes
        // interactive = live. The expected count comes from the enum, so a new screen cannot slip past. The DONE
        // tail's host went with the tail on 2026-09-28 (policy Q3): FINISH is one page.
        for (bodyLabel in listOf("onboardingAccessScreenGlide")) {
            val body = source.sliceBetween("label = \"$bodyLabel\",", "controls = {")
            assertFalse("the $bodyLabel body must hold no pills", body.contains("OnboardingPill("))
        }
        val hosts = listOf(
            Triple("ACCESS", "onboardingAccessControlsGlide", OnboardingAccessScreenId.entries.size),
        )
        for ((name, label, expected) in hosts) {
            val block = source.sliceBetween("label = \"$label\",", "\n    }\n}")
            assertTrue("the $name controls host must compute liveness", block.contains("val live = presented == current"))
            assertEquals(
                "every branch of the $name controls host must pass interactive = live, or an off-stage page stays " +
                    "clickable through the fade",
                expected,
                Regex("interactive = live,").findAll(block).count(),
            )
        }
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
