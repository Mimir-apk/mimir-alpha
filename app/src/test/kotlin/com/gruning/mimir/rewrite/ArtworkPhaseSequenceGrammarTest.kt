package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreExcludedCategory
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreResult
import com.gruning.mimir.rewrite.core.model.recovery.RestoreStorePlan
import com.gruning.mimir.rewrite.core.resolver.migration.ImportPlan
import com.gruning.mimir.rewrite.core.resolver.migration.ImportResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A jittery loading-to-result phase sequence was reported. The fix is that a phase change inside [ArtworkFetchOverlay]/[GetArtOverlay] is ONE surface
 * advancing (fade + resize on one clock, page identity via `contentKey`), not a fresh host-level crossfade
 * on every phase (spec §5, "Move 1" + "Move 2").
 *
 * Two classes of proof here, deliberately separated by what a JVM test can actually certify:
 *  - [ArtworkFetchPageKeyTest]/[GetArtPageKeyTest]: the page-key functions are pure and Compose-free, so
 *    they are exercised directly -- this is the strobe guard (spec §7's highest-listed risk): a progress
 *    tick inside `Running` must collapse to the SAME key, or an in-panel `AnimatedContent` would restart
 *    the transition on every ScreenScraper result.
 *  - [PhaseSequenceGrammarSourceTest]: everything that lives inside a `transitionSpec`/`AnimatedContent`
 *    content lambda runs on a Compose animation clock this module's Robolectric harness does not reliably
 *    sample mid-flight (`HomeLaunchCardBlurScopeMechanismTest`'s own KDoc documents this as a harness
 *    limitation, not a claim about production behaviour; `HomeTomeLibraryHeaderStatusMotionTest` hits the
 *    identical wall for the exact same `AnimatedContent` shape and deliberately falls back to source
 *    characterization for the same reason). So the wiring -- the shared clock, the explicit `SizeTransform`
 *    instead of the bare spring-carrying form, `contentKey`, and the inert-departure guard -- is pinned as
 *    real file text, matching this package's own established pattern for exactly this situation.
 *
 * NOT provable here, and not claimed: that the 4% -> 27% backdrop pump actually stops, that the resize
 * genuinely reads as monotonic, or that a stale tap during a REAL animated transition is dropped on
 * device. Those are §7's device-capture list.
 *
 * The same grammar extends to two more app-module
 * surfaces, MIGRATION and (the phase-map arm for) LIBRARY_PLATFORM_TILE_MENU -- see [MigrationPageKeyTest]
 * and the additional [PhaseSequenceGrammarSourceTest] methods below, added rather than duplicated into a
 * new file since they read the SAME `mimirDirectOverlayTarget` slice and reuse the SAME
 * `assertOneClockGrammar`/anchor-slice discipline this file already established. GAMELIST_ACHIEVEMENTS'S
 * Move 2 lives in `core-ui` (`MimirAchievementCard.kt`), a different Gradle module this file's
 * `sourceFile()` helper cannot reach -- its equivalent proof is in
 * `core-ui/src/test/kotlin/.../MimirAchievementCardPhaseSequenceGrammarTest.kt`.
 */
class ArtworkFetchPageKeyTest {
    @Test
    fun `every Running progress tick collapses to the same page key`() {
        val first = artworkFetchPageKey(ArtworkFetchUi.Running(done = 0, total = 0, matched = 0))
        val mid = artworkFetchPageKey(ArtworkFetchUi.Running(done = 3, total = 61, matched = 1))
        val last = artworkFetchPageKey(ArtworkFetchUi.Running(done = 61, total = 61, matched = 58))
        assertEquals(
            "a progress tick must not look like a new page -- this is the strobe guard",
            first,
            mid,
        )
        assertEquals(first, last)
        assertEquals(ArtworkFetchPageKey.RUNNING, first)
    }

    @Test
    fun `Done with and without writeBlocked share one page key, deliberately`() {
        // Spec §5b: "Done(writeBlocked = true) renders different copy but the same shape; keeping it
        // inside DONE is deliberate" -- not a second page, no second transition.
        assertEquals(
            artworkFetchPageKey(ArtworkFetchUi.Done(total = 10, matched = 5, writeBlocked = false)),
            artworkFetchPageKey(ArtworkFetchUi.Done(total = 10, matched = 5, writeBlocked = true)),
        )
    }

    @Test
    fun `every real phase boundary is a distinct page key`() {
        val running = artworkFetchPageKey(ArtworkFetchUi.Running(done = 1, total = 10, matched = 0))
        val stopping = artworkFetchPageKey(ArtworkFetchUi.Stopping(matched = 1))
        val done = artworkFetchPageKey(ArtworkFetchUi.Done(total = 10, matched = 5))
        val cancelled = artworkFetchPageKey(ArtworkFetchUi.Cancelled(matched = 2))
        val keys = listOf(running, stopping, done, cancelled)
        assertEquals(
            "RUNNING/STOPPING/DONE/CANCELLED must be four DISTINCT keys, or a real phase boundary " +
                "would silently stop animating",
            4,
            keys.toSet().size,
        )
    }
}

class GetArtPageKeyTest {
    @Test
    fun `every GetArtUi page is a distinct key`() {
        val keys = listOf(
            getArtPageKey(GetArtUi.Working),
            getArtPageKey(GetArtUi.Found),
            getArtPageKey(GetArtUi.NotFound),
            getArtPageKey(GetArtUi.NotSearchable),
            getArtPageKey(GetArtUi.Failed),
            getArtPageKey(GetArtUi.NeedsAccount),
            getArtPageKey(GetArtUi.ProviderUnavailable),
        )
        assertEquals(7, keys.toSet().size)
    }

    @Test
    fun `the same singleton page maps to the same key on repeated calls`() {
        // GetArtUi members are all `data object`s -- already stable identity -- this pins that the key
        // function adds no accidental instability (e.g. from javaClass.hashCode or similar).
        assertEquals(getArtPageKey(GetArtUi.Working), getArtPageKey(GetArtUi.Working))
        assertNotEquals(getArtPageKey(GetArtUi.Working), getArtPageKey(GetArtUi.Failed))
    }
}

/**
 * Source-text pins. Anchored (`sliceBetween` fails loudly on a missing anchor) so a rename or reflow
 * reddens instead of silently degrading to a no-op pass -- the pattern `MimirAppHostMotionTest` and
 * `HomeTomeLibraryHeaderStatusMotionTest` already use in this package.
 */
class PhaseSequenceGrammarSourceTest {
    private val hostSource: String by lazy { sourceFile("MimirAppHost.kt") }
    private val fetchSource: String by lazy { sourceFile("ArtworkFetchOverlay.kt") }
    private val getArtSource: String by lazy { sourceFile("GetArtOverlay.kt") }
    private val migrationSource: String by lazy { sourceFile("MigrationImport.kt") }
    private val tileMenuSource: String by lazy { sourceFile("HomeTileMenuOverlay.kt") }

    // ---- Move 1: the host no longer retargets on a phase change for these two kinds ----

    @Test
    fun `mimirDirectOverlayTarget's phase map no longer carries ARTWORK_GET or ARTWORK_FETCH arms`() {
        val phaseMap = hostSource.sliceBetween(
            "private fun mimirDirectOverlayTarget(",
            "return MimirDirectOverlayTarget(kind, phase?.javaClass?.name)",
        )
        assertFalse(
            "ARTWORK_GET must fall to `else -> null` now -- a phase change must not retarget the host " +
                "crossfade, or the in-panel AnimatedContent (Move 2) regresses into a double animation",
            phaseMap.contains("MimirOverlayKind.ARTWORK_GET -> bindings.artwork.state.getArtUi"),
        )
        assertFalse(
            phaseMap.contains("MimirOverlayKind.ARTWORK_FETCH -> bindings.artwork.fetchUi"),
        )
        // Non-vacuity: prove the slice can still see arms that ARE supposed to be there, so an
        // over-broad match (or an empty slice) could not make the assertions above pass for free.
        assertTrue(
            "the slice must still be reading the real phase map -- ARTWORK_CONNECT must remain",
            phaseMap.contains("MimirOverlayKind.ARTWORK_CONNECT -> bindings.artwork.state.connectUi"),
        )
        assertTrue(
            phaseMap.contains(
                "MimirOverlayKind.RETROACHIEVEMENTS_KEY -> bindings.settings.retroAchievementsConnectUi",
            ),
        )
    }

    @Test
    fun `both overlay kinds still bump the host epoch on open and close`() {
        // Move 1 removes the PHASE arm, not the KIND -- entry/exit must still be a real host crossfade
        // (a surface change retains the existing host crossfade). mimirOverlayUsesGlass's
        // allowlist membership is the load-bearing proof of this and is untouched by this change.
        assertTrue(mimirOverlayUsesGlass(MimirOverlayKind.ARTWORK_FETCH))
        assertTrue(mimirOverlayUsesGlass(MimirOverlayKind.ARTWORK_GET))
    }

    // ---- Pass two : three more arms deleted ----

    @Test
    fun `mimirDirectOverlayTarget's phase map no longer carries MIGRATION, GAMELIST_ACHIEVEMENTS or LIBRARY_PLATFORM_TILE_MENU arms`() {
        // MIGRATION and GAMELIST_ACHIEVEMENTS convert to an in-panel AnimatedContent (Move 2, this SAME
        // commit -- see the tests below and MimirAchievementCardPhaseSequenceGrammarTest in core-ui).
        // LIBRARY_PLATFORM_TILE_MENU is a DEFECT FIX, not a conversion: HomeTileMenuOverlay already had its
        // own in-panel AnimatedContent before this arm existed (Move 2 was already built there); this arm's
        // presence double-animated every phase change (a frozen host-crossfade snapshot for 180ms racing
        // the in-panel transition over the SAME boundary).
        val phaseMap = hostSource.sliceBetween(
            "private fun mimirDirectOverlayTarget(",
            "return MimirDirectOverlayTarget(kind, phase?.javaClass?.name)",
        )
        assertFalse(
            "MIGRATION must fall to `else -> null` now -- its phase motion moved into " +
                "MigrationImportOverlay's own AnimatedContent",
            phaseMap.contains("MimirOverlayKind.MIGRATION -> bindings.migration.state"),
        )
        assertFalse(
            "GAMELIST_ACHIEVEMENTS must fall to `else -> null` now -- its phase motion moved into " +
                "MimirAchievementCard's own AnimatedContent",
            phaseMap.contains("MimirOverlayKind.GAMELIST_ACHIEVEMENTS -> bindings.achievements.state.cardState()"),
        )
        assertFalse(
            "LIBRARY_PLATFORM_TILE_MENU must fall to `else -> null` now -- HomeTileMenuOverlay already " +
                "owns its own phase AnimatedContent; this arm double-animated the same boundary",
            phaseMap.contains("MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU -> bindings.navigation.platformTileMenu?.phase"),
        )
        // Non-vacuity, same discipline as pass one's own test above.
        assertTrue(
            "the slice must still be reading the real phase map -- ARTWORK_CONNECT must remain",
            phaseMap.contains("MimirOverlayKind.ARTWORK_CONNECT -> bindings.artwork.state.connectUi"),
        )
        assertTrue(
            phaseMap.contains(
                "MimirOverlayKind.RETROACHIEVEMENTS_KEY -> bindings.settings.retroAchievementsConnectUi",
            ),
        )
    }

    // No `mimirOverlayUsesGlass` epoch-bump analog for pass two's three kinds, deliberately: MIGRATION is
    // NOT a member of that allowlist (it falls to that function's own `else -> false`) even before this
    // change, so "still a member" would not be evidence of anything for it. GAMELIST_ACHIEVEMENTS and
    // LIBRARY_PLATFORM_TILE_MENU ARE members and are untouched by this change (only the phase-map arm was
    // touched, not `mimirOverlayUsesGlass`), so re-asserting that membership here would just re-test a
    // constant this lane never edited.

    // ---- Move 2: both overlays wrap their phase `when` in the Tome-library AnimatedContent shape ----

    private fun assertOneClockGrammar(overlaySource: String, overlayName: String) {
        val block = overlaySource.sliceBetween("AnimatedContent(", "label = ")
        assertTrue(
            "$overlayName must key on a stable page enum, never the raw state (that is the strobe risk)",
            block.contains("contentKey = ::"),
        )
        assertTrue(
            "$overlayName's fade must run on the house duration token",
            block.contains("tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)"),
        )
        assertTrue(
            "$overlayName must supply an explicit SizeTransform so the resize shares the SAME clock as " +
                "the fade, per HomeTomeLibraryOverlay.kt:1301-1320's reference shape",
            block.contains(".using(") && block.contains("SizeTransform(clip = false)"),
        )
        // The clock must appear THREE times in this block: incoming fade, outgoing fade, and the
        // SizeTransform's own spec -- one clock, three channels, exactly the reference sentence.
        val clockOccurrences = Regex("tween\\(MimirMotion\\.OverlayMs, easing = MimirMotion\\.Easing\\)")
            .findAll(block).count()
        assertEquals(
            "$overlayName must run the incoming fade, the outgoing fade AND the resize on the SAME " +
                "tween(OverlayMs, Easing) -- found $clockOccurrences occurrences, expected 3",
            3,
            clockOccurrences,
        )
    }

    /**
     * 5b675e876 (2026-09-29, "Menus glide back on a return, and no swap snaps its size") moved Get art's and the
     * artwork fetch's steps from the one-clock crossfade to the swap tier (core-ui `mimirSwapTransform`): the old step
     * out, the new one in from the 130 ms cover, the resize on the arrival's clock and never the default spring.
     * MenuReturnGlideTest pins the call; this keeps the stable page key and the ban on a bare `togetherWith`.
     */
    private fun assertSwapTierGrammar(overlaySource: String, overlayName: String) {
        val block = overlaySource.sliceBetween("AnimatedContent(", "label = ")
        assertTrue(
            "$overlayName must key on a stable page enum, never the raw state (that is the strobe risk)",
            block.contains("contentKey = ::"),
        )
        assertTrue(
            "$overlayName's steps swap on the house swap tier",
            block.contains("transitionSpec = { mimirSwapTransform(stepReducedMotion) },"),
        )
        assertFalse(
            "$overlayName: no bare togetherWith, so no default spring on the size",
            block.contains("togetherWith"),
        )
    }

    @Test
    fun `ArtworkFetchOverlay's phase AnimatedContent swaps on the house tier, never the default spring`() {
        assertSwapTierGrammar(fetchSource, "ArtworkFetchOverlay")
    }

    @Test
    fun `GetArtOverlay's phase AnimatedContent swaps on the house tier, never the default spring`() {
        assertSwapTierGrammar(getArtSource, "GetArtOverlay")
    }

    /**
     * Audit 2026-10-14, RC-51 (S-53 (2)): Migration's phases moved from the one-clock crossfade to the swap tier (core-ui `mimirSwapTransform`, as Get art's and the fetch's
     * steps did in 5b675e876), with the pages fading themselves: `fadeIn` and `fadeOut` faded each page through a page-sized layer that cut every ring on its buttons for 180 ms.
     * This keeps the stable page key, the ban on a bare `togetherWith`, the page's own ring-safe alpha, and the input gate on the page arriving.
     */
    @Test
    fun `MigrationImportOverlay's phase AnimatedContent is a ring-safe swap with no default spring`() {
        val block = migrationSource.sliceBetween("AnimatedContent(", "label = ")
        assertTrue("MigrationImportOverlay must key on a stable page enum, never the raw state", block.contains("contentKey = ::"))
        assertTrue(
            "the phases swap on the house swap tier, the pages fading themselves",
            block.contains("transitionSpec = { mimirSwapTransform(phaseReducedMotion, fade = false) },"),
        )
        assertFalse("no bare togetherWith, so no default spring on the size", block.contains("togetherWith"))
        val page = migrationSource.sliceBetween(") { page ->", "@Composable\nprivate fun ReadyBody(")
        assertTrue(page.contains("val pageAlpha = mimirSwapFadeAlpha(phaseReducedMotion)"))
        assertTrue("a ring on a page's buttons is never cut by a page-sized layer", page.contains(".mimirRingSafeFade(pageAlpha)"))
        assertTrue("a press waits for the page it would act on", page.contains("MimirSwapPageGate(pageAlpha)"))
    }

    // Spring size-clock review, 2026-08-20:
    // HomeTileMenuOverlay.kt has TWO AnimatedContent blocks -- an OUTER one at fillMaxSize() (§4c:
    // already correct, no size change engaged, deliberately untouched by this lane) and an INNER one
    // around the phase `when` that swaps the visible menu body. The inner one carried a bare
    // `togetherWith`, which let Compose's ContentTransform default-arg SizeTransform through --
    // spring(dampingRatio = 1f, stiffness = 400f), clip = true -- racing the tween fade on a second,
    // unbounded clock. Scope past the outer block's own `label = ` anchor FIRST so
    // assertOneClockGrammar's internal `sliceBetween("AnimatedContent(", "label = ")` cannot
    // accidentally match the untouched outer block instead of the fixed inner one.
    /**
     * Motion audit batch A (2026-09-28): the tile menu's pages moved from the one-clock crossfade to the swap tier
     * (core-ui `mimirSwapTransform`): out, then in from the 130 ms lead, the resize on the arrival's clock and never the
     * default spring. The pages fade themselves with ModulateAlpha, because their rows' rings reach past the page.
     */
    @Test
    fun `HomeTileMenuOverlay's inner phase AnimatedContent is a ring-safe swap with no default spring`() {
        assertTrue(
            "the outer AnimatedContent's own label anchor must be present, or this slice would not be " +
                "scoped past it and could accidentally validate the untouched outer block instead",
            tileMenuSource.contains("label = \"tileMenuSurfaceFade\","),
        )
        val pastOuterBlock = tileMenuSource.substringAfter("label = \"tileMenuSurfaceFade\",")
        val block = pastOuterBlock.sliceBetween("AnimatedContent(", "label = ")
        assertTrue(block.contains("contentKey = ::"))
        // A return glides the page back using the return travel.
        val swap = block.substringAfter("transitionSpec = {").substringBefore("}")
        assertTrue(swap.contains("mimirSwapTransform("))
        listOf("swapReducedMotion,", "fade = false,", "returning = swapReturning,", "returnTravelPx = surfaceGlidePx,")
            .forEach { argument -> assertTrue(argument, swap.contains(argument)) }
        assertFalse("no bare togetherWith", block.contains("togetherWith"))
        val page = pastOuterBlock.sliceBetween("label = \"tileMenuPhaseFade\",", "when (phase)")
        assertTrue(page.contains("val pageAlpha = mimirSwapFadeAlpha(swapReducedMotion, returning = swapReturning)"))
        assertTrue(page.contains("modifier = Modifier.mimirRingSafeFade(pageAlpha).then(pageInput),"))
        // Presses wait until the arriving page shows : HomeTileMenuSwapInputGateTest.
        assertTrue(page.contains("MimirTransitionInputGate.hold(pageGateOwner)"))
    }

    @Test
    fun `HomeTileMenuOverlay's outer surface-fade AnimatedContent stays untouched, no SizeTransform`() {
        // §4c names this block ALREADY CORRECT: it sits at Modifier.fillMaxSize(), so no size clock is
        // engaged and adding a SizeTransform here would be noise. Pin that it stays a bare
        // fade-only togetherWith with no `.using(`/`SizeTransform` -- proof this lane did not touch it.
        val outerBlock = tileMenuSource.sliceBetween("AnimatedContent(", "label = \"tileMenuSurfaceFade\",")
        assertTrue(outerBlock.contains("modifier = Modifier.fillMaxSize()"))
        assertFalse(
            "the outer block must stay a bare togetherWith -- no size clock is engaged at " +
                "fillMaxSize(), so a SizeTransform here would be noise the spec explicitly rejects",
            outerBlock.contains(".using(") || outerBlock.contains("SizeTransform"),
        )
    }

    // ---- Invariant: the departing page is input-inert ----

    private fun assertInertDeparture(overlaySource: String, overlayName: String) {
        val contentLambda = overlaySource.sliceBetween(") { page ->", "\n            }\n        }\n    }\n}")
        assertTrue(
            "$overlayName must compute liveness by comparing the lambda's own page against the live " +
                "external state, not merely accept every callback live",
            contentLambda.contains("val isLive ="),
        )
        assertTrue(
            "$overlayName must clear semantics on a departing (non-live) page, reproducing the host's " +
                "own outgoing-layer treatment (MimirAppHost.kt:702-714)",
            contentLambda.contains("Modifier.clearAndSetSemantics"),
        )
        assertTrue(
            "$overlayName must gate its callbacks on isLive so a stale tap on a departing page cannot " +
                "fire (e.g. Running's Cancel resurrecting Stopping after Done has already arrived)",
            contentLambda.contains("if (isLive)"),
        )
    }

    @Test
    fun `ArtworkFetchOverlay makes its departing page input-inert`() {
        assertInertDeparture(fetchSource, "ArtworkFetchOverlay")
    }

    @Test
    fun `GetArtOverlay makes its departing page input-inert`() {
        assertInertDeparture(getArtSource, "GetArtOverlay")
    }

    // MigrationImportOverlay's own version: its content lambda's trailing brace depth differs from the two
    // ArtworkFetchOverlay/GetArtOverlay overlays above (an extra Column level), so it cannot reuse
    // `assertInertDeparture`'s hardcoded closing-brace anchor -- `ReadyBody`'s declaration, which follows
    // immediately, is a stable and unique anchor instead.
    @Test
    fun `MigrationImportOverlay makes its departing page input-inert`() {
        val contentLambda = migrationSource.sliceBetween(") { page ->", "@Composable\nprivate fun ReadyBody(")
        assertTrue(
            "MigrationImportOverlay must compute liveness by comparing the lambda's own page against the " +
                "live external state, not merely accept every callback live",
            contentLambda.contains("val isLive ="),
        )
        assertTrue(
            "MigrationImportOverlay must clear semantics on a departing (non-live) page, reproducing the " +
                "host's own outgoing-layer treatment (MimirAppHost.kt:702-714)",
            contentLambda.contains("Modifier.clearAndSetSemantics"),
        )
    }

    @Test
    fun `MigrationImportOverlay gates ActionButton's whole clickable, not merely its onClick, on liveness`() {
        // A neutered onClick alone would not be enough here: ActionButton's clickable also calls
        // authority.requestFocus(key) as a side effect -- gating only the callback would still let a stale
        // tap on a departing page steal focus for the rest of that page's composed lifetime before the
        // next page's own LaunchedEffect re-lands it. The whole clickable must be conditional on `live`.
        assertTrue(
            "ActionButton must accept a `live` parameter",
            migrationSource.contains("live: Boolean = true"),
        )
        val actionButtonBody = migrationSource.sliceBetween(
            "private fun ActionButton(",
            "contentAlignment = Alignment.Center,",
        )
        // Ring migration 2026-09-28: the tap goes through mimirActivatable, whose `enabled = false` returns the modifier
        // untouched (no click handling at all, so no requestFocus side effect) -- the same whole-clickable gate.
        assertTrue(
            "ActionButton's activation must be gated on `live` as a whole, not merely its onClick",
            actionButtonBody.contains(".mimirActivatable(label = label, enabled = live, authority = authority, key = key, onActivate = onClick)"),
        )
        assertFalse(
            "no ungated .clickable call remains",
            actionButtonBody.contains(".clickable {") || actionButtonBody.contains(".clickable("),
        )
        assertFalse(
            "the OLD unconditional form (a bare .clickable outside any `live` guard) must be gone",
            actionButtonBody.contains("\n            .clickable { authority.requestFocus(key); onClick() }\n"),
        )
    }

    // ---- Do-not-disturb: the credential flow test's pin still finds exactly what it always found ----

    @Test
    fun `the credential flow source-text pin's two anchors are still present verbatim`() {
        // CredentialFlowGlassContinuityTest.directOverlayTargetWiringReadsTheLiveCredentialUiNotACoarserValue
        // slices the SAME function and asserts these two lines are present. This is not a re-run of that
        // test (it lives in its own file and its own gate) -- it is proof, from THIS lane's own source
        // read, that the removal above could not have touched it: the anchors it depends on are untouched
        // text, not regenerated or reformatted.
        val phaseMap = hostSource.sliceBetween(
            "private fun mimirDirectOverlayTarget(",
            "return MimirDirectOverlayTarget(kind, phase?.javaClass?.name)",
        )
        assertTrue(phaseMap.contains("MimirOverlayKind.ARTWORK_CONNECT -> bindings.artwork.state.connectUi"))
        assertTrue(
            phaseMap.contains(
                "MimirOverlayKind.RETROACHIEVEMENTS_KEY -> bindings.settings.retroAchievementsConnectUi",
            ),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
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

/**
 * [migrationPageKey] is pure and Compose-free -- exercised directly, same discipline as
 * [ArtworkFetchPageKeyTest]/[GetArtPageKeyTest] above.
 */
class MigrationPageKeyTest {
    @Test
    fun `all eight MigrationUiState subclasses are distinct page keys`() {
        val keys = listOf(
            migrationPageKey(MigrationUiState.Reading),
            migrationPageKey(MigrationUiState.Ready(samplePlan())),
            migrationPageKey(MigrationUiState.Applying),
            migrationPageKey(MigrationUiState.Done(sampleResult())),
            migrationPageKey(MigrationUiState.Failed("bad file")),
            migrationPageKey(MigrationUiState.RestoreReady(samplePreparedRestore())),
            migrationPageKey(MigrationUiState.RestoreApplying),
            migrationPageKey(MigrationUiState.RestoreDone(sampleRestoreResult())),
        )
        assertEquals(
            "none of this state's eight subclasses share the ArtworkFetchUi.Done(writeBlocked) " +
                "same-shape-same-buttons collapsing precedent -- " +
                "every real phase boundary must animate",
            8,
            keys.toSet().size,
        )
    }

    @Test
    fun `RestoreReady's pendingLibraryScan branch does not fork the page key`() {
        // Same class, but two different BUTTON COUNTS (one Close vs. a Restore/Cancel row) -- this fails
        // pass one's own "same shape, same buttons" collapsing test, so it is deliberately NOT split into
        // a second key. It also never needs to be: pendingLibraryScan is fixed at construction and never
        // flips while one RestoreReady instance is mounted, so there is no live transition between the two
        // shapes for a page key to distinguish.
        val pending = MigrationUiState.RestoreReady(
            samplePreparedRestore(samplePlan = sampleRestorePlan(pendingLibraryScan = true)),
        )
        val ready = MigrationUiState.RestoreReady(
            samplePreparedRestore(samplePlan = sampleRestorePlan(pendingLibraryScan = false)),
        )
        assertEquals(migrationPageKey(pending), migrationPageKey(ready))
    }

    @Test
    fun `repeated calls on the same subclass are stable`() {
        assertEquals(migrationPageKey(MigrationUiState.Reading), migrationPageKey(MigrationUiState.Reading))
        assertEquals(MigrationPageKey.READING, migrationPageKey(MigrationUiState.Reading))
    }

    private fun samplePlan(): ImportPlan = ImportPlan(
        favorites = emptyList(),
        hidden = emptyList(),
        overrides = emptyList(),
        theme = null,
        ignoredThemeId = null,
        sourceRootHints = emptyList(),
        profileDisplayName = null,
    )

    private fun sampleResult(): ImportResult = ImportResult(
        favoritesRestored = 1,
        favoritesNotFound = 0,
        hiddenRestored = 0,
        hiddenNotFound = 0,
        overridesRestored = 0,
        overridesSkipped = 0,
        themeApplied = false,
        ignoredThemeId = null,
        sourceRootsToRegrant = emptyList(),
        notFoundTitles = emptyList(),
    )

    private fun sampleRestorePlan(
        pendingLibraryScan: Boolean = false,
    ): RecoveryRestorePlan = RecoveryRestorePlan(
        favorites = RestoreStorePlan(decisions = emptyList()),
        hidden = RestoreStorePlan(decisions = emptyList()),
        overrides = RestoreStorePlan(decisions = emptyList()),
        shelf = RestoreStorePlan(decisions = emptyList()),
        sourceRootHints = emptyList(),
        notRestored = RecoveryRestoreExcludedCategory.requiredDisclosures,
        pendingLibraryScan = pendingLibraryScan,
        archiveRestoreRecordCount = 0,
    )

    private fun samplePreparedRestore(
        samplePlan: RecoveryRestorePlan = sampleRestorePlan(),
    ): PreparedRecoveryRestore = PreparedRecoveryRestore(
        plan = samplePlan,
        runId = "00000000-0000-0000-0000-000000000001",
        importAuthorizationId = "00000000-0000-0000-0000-000000000002",
        applyAuthorizationId = "00000000-0000-0000-0000-000000000003",
        manifestSha256 = "manifest",
    )

    private fun sampleRestoreResult(): RecoveryRestoreResult = RecoveryRestoreResult(
        favoritesRestored = 1,
        favoritesSkipped = 0,
        favoritesUnmatched = 0,
        hiddenRestored = 0,
        hiddenSkipped = 0,
        hiddenUnmatched = 0,
        overridesRestored = 0,
        overridesSkipped = 0,
        overridesUnmatched = 0,
        shelfRestored = 0,
        shelfSkipped = 0,
        shelfUnmatched = 0,
        sourceRootHints = emptyList(),
        notRestored = RecoveryRestoreExcludedCategory.requiredDisclosures,
        pendingLibraryScan = false,
    )
}
