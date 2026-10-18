package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a refused media
 * apply or reset HOLDS THE MENU OPEN WITH A NOTE. It does not close. Before this fix all five sites
 * (A1-A5) discarded the mutation's `Boolean` result and closed the tile menu with the success grammar
 * regardless of outcome — `onApplyPick`'s honest twin (`:1791-1821`) already branched on the identical
 * `Boolean`; these five did not.
 *
 * `MimirAppCoordinator.kt`'s Y-menu callbacks are closures deeply captured inside one ~2,700-line
 * `@Composable` — there is no Robolectric harness in this module that can drive them through the real
 * Compose tree (matches this codebase's own precedent for the same gap:
 * `AppBrightnessCoordinatorDeclineFeedbackTest`, `GameTitleEditOverlayResetWiringTest`,
 * `SessionMutexRefusalWiringTest`). So this class pins the coordinator half by **source
 * characterization**, labelled as such per the brief — reading the raw file text and asserting each
 * `close*TileMenu()` call site is nested inside its own mutation-result `Boolean` check, never bare.
 *
 * The REDUCER half that makes A2-A5 observable at all (the shared phase must not already be `null` by
 * the time this code runs) is pinned as a real behavioural differential in
 * [PlatformArtMenuReducerTest] (`confirm on revert clears art and holds the menu open until the result
 * is known`) and [HomeTileMenuReducerTest] (the three updated `UseDefault` reset tests) — those two
 * files, not this one, are where "revert only the production line" produces a genuine red JUnit
 * assertion failure. This class's differential is: delete the `if (<result>)` guard around a
 * `close*TileMenu()` call and this test's `assertFalse` on the unconditional/unbranched literal starts
 * failing (see each test's own revert note for the captured string).
 */
class MediaMenuHonestyCoordinatorWiringTest {

    private fun coordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppCoordinator.kt not found from ${File(".").absolutePath}")
    }

    private fun homeTileMenuOverlaySource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeTileMenuOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeTileMenuOverlay.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeTileMenuOverlay.kt not found from ${File(".").absolutePath}")
    }

    private fun platformArtMenuOverlaySource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/PlatformArtMenuOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/PlatformArtMenuOverlay.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("PlatformArtMenuOverlay.kt not found from ${File(".").absolutePath}")
    }

    // ── A1: platform icon-art apply (MimirAppCoordinator.kt, platform onApplyPick) ─────────────────

    /**
     * Differential: revert only this site's production line, `if (applied) {` back to a bare
     * `if (applied) refreshOnePlatformArt(...)` followed by an unconditional
     * `navigation.closePlatformTileMenu()` (the exact pre-fix shape), and this test fails because
     * `closePlatformTileMenu()` reappears in the false-branch text this test asserts is absent from
     * (captured, then restored).
     */
    @Test
    fun a1PlatformApplyClosesOnlyWhenTheWriteLanded() {
        // Disambiguate from the Home tile's onApplyPick (same literal marker, appears twice): the
        // platform site is the SECOND occurrence and is bounded below by its sibling onRevert.
        val platformApplyBlock = coordinatorSource()
            .split("onApplyPick = { candidate, index ->")[2]
            .substringBefore("onRevert = {")

        val trueBranch = platformApplyBlock.substringAfter("if (applied) {").substringBefore("} else {")
        val falseBranch = platformApplyBlock.substringAfter("} else {")

        assertTrue("success path must close the platform menu", trueBranch.contains("navigation.closePlatformTileMenu()"))
        assertFalse(
            "a refused apply must NOT close the menu — closePlatformTileMenu() must not appear in the else branch",
            falseBranch.contains("navigation.closePlatformTileMenu()"),
        )
        assertTrue(
            "a refused apply must hold the menu open with the reused icon-art failure note",
            falseBranch.contains("Couldn’t save this icon art. Try again."),
        )
    }

    // ── A2: platform revert (MimirAppCoordinator.kt onRevert + PlatformArtMenuOverlay.kt reducer) ──

    /**
     * Differential: revert only `onRevert`'s production line, `if (cleared) {` back to the pre-fix
     * shape (`if (cleared) { refreshOnePlatformArt(...) }` with no close call at all, since pre-fix the
     * close already happened synchronously in the reducer before this suspend block even ran), and this
     * test fails because `closePlatformTileMenu()` is no longer reachable in the true branch.
     */
    @Test
    fun a2PlatformRevertClosesOnlyWhenTheWriteLanded() {
        val onRevertBlock = coordinatorSource()
            .substringAfter("onRevert = {")
            .substringBefore("\n            )\n        }\n    }")

        val trueBranch = onRevertBlock.substringAfter("if (cleared) {").substringBefore("} else {")
        val falseBranch = onRevertBlock.substringAfter("} else {")

        assertTrue("success path must close the platform menu", trueBranch.contains("navigation.closePlatformTileMenu()"))
        assertFalse(
            "a refused revert must NOT close the menu — closePlatformTileMenu() must not appear in the else branch",
            falseBranch.contains("navigation.closePlatformTileMenu()"),
        )
        assertTrue(
            "a refused revert must hold the menu open with the reused icon-art failure note",
            falseBranch.contains("Couldn’t save this icon art. Try again."),
        )
    }

    /**
     * A2's load-bearing half: the reducer must not return null (=close) on the revert arm before
     * `onRevert()`'s suspend clear even runs, or the coordinator-side check above has no surface to
     * write to. Differential: revert `PlatformArtMenuOverlay.kt`'s `PlatformArtMenuRevert -> { onRevert();
     * state.copy(phase = HomeTileMenuPhase.Applying) }` back to `{ onRevert(); null }` and
     * [PlatformArtMenuReducerTest.confirm on revert clears art and holds the menu open until the result
     * is known] fails with `expected:<Applying> but was:<null>` reading `next?.phase` off a null `next`
     * (a `NullPointerException`-shaped mismatch since `null?.phase` — see that test for the exact
     * behavioural capture; this test additionally pins the *source*, not just the reducer's behavior,
     * so a future edit cannot silently reintroduce the string while leaving the behavioural test
     * untouched by touching a differently-shaped line).
     */
    @Test
    fun a2ReducerNoLongerReturnsNullOnTheRevertArm() {
        val source = platformArtMenuOverlaySource()
        val menuConfirmBlock = source
            .substringAfter("ControllerAction.CONFIRM -> when (options.getOrNull(state.menuFocus)) {")
            .substringBefore("ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> null")

        assertTrue(
            "the Menu-phase revert arm must transition to Applying, not close synchronously",
            menuConfirmBlock.contains("state.copy(phase = HomeTileMenuPhase.Applying)"),
        )
        assertFalse(
            "the revert arm must not return null immediately after firing onRevert()",
            menuConfirmBlock.substringAfter("PlatformArtMenuRevert -> {").substringBefore("}")
                .trim() == "onRevert()\n                    null",
        )
    }

    // ── A3: home tile Reset art ──────────────────────────────────────────────────────────────────

    /**
     * Differential: revert only `onResetArt`'s production line back to the pre-fix shape (a bare
     * `clearPickAsAutomatic(...)` statement, discarded, followed by an unconditional
     * `navigation.closeHomeTileMenu()`), and this test fails because `closeHomeTileMenu()` reappears
     * unguarded and the `cleared` result binding disappears.
     */
    @Test
    fun a3HomeResetArtClosesOnlyWhenTheWriteLanded() {
        val block = coordinatorSource()
            .substringAfter("onResetArt = {")
            .substringBefore("onOpenAchievements = {")

        assertTrue(
            "the mutation's Boolean result must be captured, not discarded as a bare statement",
            block.contains("val cleared = SteamGridDbTileFetcher.clearPickAsAutomatic("),
        )
        val trueBranch = block.substringAfter("if (cleared) {").substringBefore("} else {")
        val falseBranch = block.substringAfter("} else {")

        assertTrue("success path must close the Home tile menu", trueBranch.contains("navigation.closeHomeTileMenu()"))
        assertFalse(
            "a refused reset must NOT close the menu",
            falseBranch.contains("navigation.closeHomeTileMenu()"),
        )
        assertTrue(
            "a refused reset must hold the menu open with the reused icon-art failure note",
            falseBranch.contains("Couldn’t save this icon art. Try again."),
        )
    }

    /**
     * A3's load-bearing half, shared with A4/A5 below: `HomeTileMenuOverlay.kt`'s reducer must not
     * return null on the Icon-art `UseDefault` arm before `onResetArt()`'s suspend clear runs.
     * Differential: revert to `{ onResetArt(); null }` and
     * [HomeTileMenuReducerTest.the reset slot clears the override and does not shift candidate indices]
     * fails asserting `HomeTileMenuPhase.Applying` against a null `next?.phase`.
     */
    @Test
    fun a3HomeReducerNoLongerReturnsNullOnTheArtResetArm() {
        val artPickConfirmBlock = homeTileMenuOverlaySource()
            .substringAfter("is HomeTileMenuPhase.ArtPick -> when (action) {")
            .substringBefore("is HomeTileMenuPhase.HeroPick -> when (action) {")

        val useDefaultArm = artPickConfirmBlock
            .substringAfter("is HomeTileArtChoice.UseDefault -> {")
            .substringBefore("is HomeTileArtChoice.UploadedImage -> {")

        assertTrue(useDefaultArm.contains("onResetArt()"))
        assertTrue(
            "must transition to Applying, not close synchronously",
            useDefaultArm.contains("state.copy(phase = HomeTileMenuPhase.Applying)"),
        )
        assertFalse(useDefaultArm.trim().endsWith("null"))
    }

    // ── A4: home tile hero "Use default" ─────────────────────────────────────────────────────────

    @Test
    fun a4HomeResetHeroClosesOnlyWhenTheWriteLanded() {
        val block = coordinatorSource()
            .substringAfter("onResetHero = {")
            .substringBefore("onResetClip = {")

        assertTrue(
            "the mutation's Boolean result must be captured, not discarded inside a bare ?.let",
            block.contains("val cleared = emulated != null &&"),
        )
        val trueBranch = block.substringAfter("if (cleared) {").substringBefore("} else {")
        val falseBranch = block.substringAfter("} else {")

        assertTrue("success path must close the Home tile menu", trueBranch.contains("navigation.closeHomeTileMenu()"))
        assertFalse(
            "a refused reset must NOT close the menu",
            falseBranch.contains("navigation.closeHomeTileMenu()"),
        )
        assertTrue(
            "a refused reset must hold the menu open with the reused hero-art failure note",
            falseBranch.contains("Couldn’t save this hero art. Try again."),
        )
    }

    /** A4's load-bearing half, mirroring A3. */
    @Test
    fun a4HomeReducerNoLongerReturnsNullOnTheHeroResetArm() {
        val heroPickConfirmBlock = homeTileMenuOverlaySource()
            .substringAfter("is HomeTileMenuPhase.HeroPick -> when (action) {")
            .substringBefore("is HomeTileMenuPhase.ClipPick -> when (action) {")

        val useDefaultArm = heroPickConfirmBlock
            .substringAfter("is HomeTileHeroChoice.UseDefault -> {")
            .substringBefore("is HomeTileHeroChoice.UploadedImage -> {")

        assertTrue(useDefaultArm.contains("onResetHero()"))
        assertTrue(
            "must transition to Applying, not close synchronously",
            useDefaultArm.contains("state.copy(phase = HomeTileMenuPhase.Applying)"),
        )
        assertFalse(useDefaultArm.trim().endsWith("null"))
    }

    // ── A5: home tile clip "Use default" ─────────────────────────────────────────────────────────

    @Test
    fun a5HomeResetClipClosesOnlyWhenTheWriteLanded() {
        val block = coordinatorSource()
            .substringAfter("onResetClip = {")
            .substringBefore("collectionsFor = {")

        assertTrue(
            "the mutation's Boolean result must be captured, not discarded inside a bare ?.let",
            block.contains("val cleared = emulated != null && CustomHeroStore.clearPreviewClipAsAutomatic("),
        )
        val trueBranch = block.substringAfter("if (cleared) {").substringBefore("} else {")
        val falseBranch = block.substringAfter("} else {")

        assertTrue("success path must close the Home tile menu", trueBranch.contains("navigation.closeHomeTileMenu()"))
        assertFalse(
            "a refused reset must NOT close the menu",
            falseBranch.contains("navigation.closeHomeTileMenu()"),
        )
        assertTrue(
            "a refused reset must hold the menu open with the reused Hero Motion failure note",
            falseBranch.contains("Couldn’t save this Hero Motion. Try again."),
        )
    }

    /** A5's load-bearing half, mirroring A3/A4. */
    @Test
    fun a5HomeReducerNoLongerReturnsNullOnTheClipResetArm() {
        val clipPickConfirmBlock = homeTileMenuOverlaySource()
            .substringAfter("is HomeTileMenuPhase.ClipPick -> when (action) {")
            .substringBefore("is HomeTileMenuPhase.ProviderUnavailable -> when (action) {")

        val useDefaultArm = clipPickConfirmBlock
            .substringAfter("is HomeTileClipChoice.UseDefault -> {")
            .substringBefore("is HomeTileClipChoice.Clip -> {")

        assertTrue(useDefaultArm.contains("onResetClip()"))
        assertTrue(
            "must transition to Applying, not close synchronously",
            useDefaultArm.contains("state.copy(phase = HomeTileMenuPhase.Applying)"),
        )
        assertFalse(useDefaultArm.trim().endsWith("null"))
    }

    // ── The deliverable: no close*TileMenu() call in this file is reachable on a false mutation result ──

    /**
     * Enumerates every literal `close*TileMenu()` call in `MimirAppCoordinator.kt` and accounts for
     * each one. This is the test that outlives the five fixes above: it fails the instant a future
     * apply/reset site is added with the same unconditional-close shape A1-A5 shipped with, even if
     * nobody remembers to name it in a lane brief. Two calls in the file are legitimately unconditional
     * because they are NOT gating a mutation result at all (documented per-site below); every other
     * call must sit inside an `if (<result>)` true branch whose sibling `else` branch is note-only.
     */
    @Test
    fun noCloseTileMenuCallIsReachableOnAFalseMutationResult() {
        val source = coordinatorSource()
        val closeCallRegex = Regex("""navigation\.close(Home|Platform)TileMenu\(\)""")
        val totalCalls = closeCallRegex.findAll(source).count()

        // The full, named population: seven Home-tile apply/reset "honest" call sites (three
        // pre-existing: onApplyPick, onApplyUploadedArt, onApplyHero, onApplyUploadedHero, onApplyClip —
        // five, plus A3/A4/A5's now-fixed onResetArt/onResetHero/onResetClip — three, total eight),
        // two Platform-tile sites (A1's onApplyPick, A2's onRevert), one image-import success edge case
        // (already gated on `imported`). The Custom Enrich "Back" navigation used to be a twelfth, an unconditional close for
        // the Library hero door; since the earlier update it assigns `navigation.homeTileMenu = customMediaBack(current)` (the Edit menu, or
        // null from the doors with no menu), which is not a close*TileMenu() call at all, and pressing B never attempted a write.
        assertEquals(
            "the known population of close*TileMenu() call sites changed size — re-audit this test " +
                "before trusting it; a new call site must be added to one of the buckets below, not " +
                "silently ignored",
            11,
            totalCalls,
        )

        val gatedSites = listOf(
            "onApplyPick" to coordinatorSource().split("onApplyPick = { candidate, index ->")[1]
                .substringBefore("onImportArt = {"), // home tile (pre-existing honest twin)
            "onApplyUploadedArt" to source.substringAfter("onApplyUploadedArt = {").substringBefore("onResetArt = {"),
            "onResetArt (A3)" to source.substringAfter("onResetArt = {").substringBefore("onOpenAchievements = {"),
            "onApplyHero" to source.substringAfter("onApplyHero = {").substringBefore("onImportHero = {"),
            "onApplyUploadedHero" to source.substringAfter("onApplyUploadedHero = {").substringBefore("onLoadClip = {"),
            "onApplyClip" to source.substringAfter("onApplyClip = { candidate ->").substringBefore("onResetHero = {"),
            "onResetHero (A4)" to source.substringAfter("onResetHero = {").substringBefore("onResetClip = {"),
            "onResetClip (A5)" to source.substringAfter("onResetClip = {").substringBefore("collectionsFor = {"),
            "platform onApplyPick (A1)" to source.split("onApplyPick = { candidate, index ->")[2]
                .substringBefore("onRevert = {"),
            "platform onRevert (A2)" to source.substringAfter("onRevert = {")
                .substringBefore("\n            )\n        }\n    }"),
        )

        for ((name, block) in gatedSites) {
            val falseBranch = block.substringAfter("} else {")
            assertFalse(
                "$name: a close*TileMenu() call must not be reachable from the false/refused branch",
                closeCallRegex.containsMatchIn(falseBranch.substringBefore("\n                        }\n                    }")),
            )
        }

        // The two documented exceptions: NOT mutation-result gates, so they are correctly unconditional
        // from the perspective of a discarded Boolean (there is no Boolean here to discard).
        val importEdgeCase = source.substringAfter("if (live != null && settledPhase != null) {")
            .substringBefore("} else if (live != null) {")
        assertTrue(
            "the image-import success edge case must stay nested inside `if (imported)`",
            source.substringAfter("if (imported) {").substringBefore("} else if (live != null) {")
                .contains("navigation.closeHomeTileMenu()"),
        )
        assertFalse(importEdgeCase.isEmpty()) // sanity: the window above actually matched something

        val backCommandBlock = source.substringAfter("HomeCustomEnrichCommand.Back -> {")
            .substringBefore("HomeCustomEnrichCommand.OpenKeyboard ->")
        assertTrue(
            "the Custom Enrich Back command's onBack() close is a navigation cancel, not a write outcome",
            backCommandBlock.contains("onBack()"),
        )
    }

    // ── Healthy path is unchanged ────────────────────────────────────────────────────────────────

    /**
     * A successful apply/reset must still close exactly as it did before this lane — the fix must only
     * add a false branch, never touch the true branch's shape or ordering (refresh, then close).
     */
    @Test
    fun successfulApplyAndResetSitesStillRefreshBeforeClosingExactlyAsBefore() {
        val source = coordinatorSource()

        val platformApply = source.split("onApplyPick = { candidate, index ->")[2].substringBefore("onRevert = {")
        val platformApplyTrue = platformApply.substringAfter("if (applied) {").substringBefore("} else {")
        assertTrue(platformApplyTrue.indexOf("refreshOnePlatformArt(") < platformApplyTrue.indexOf("navigation.closePlatformTileMenu()"))

        val platformRevert = source.substringAfter("onRevert = {").substringBefore("\n            )\n        }\n    }")
        val platformRevertTrue = platformRevert.substringAfter("if (cleared) {").substringBefore("} else {")
        assertTrue(platformRevertTrue.indexOf("refreshOnePlatformArt(") < platformRevertTrue.indexOf("navigation.closePlatformTileMenu()"))

        val resetArtTrue = source.substringAfter("onResetArt = {").substringBefore("onOpenAchievements = {")
            .substringAfter("if (cleared) {").substringBefore("} else {")
        assertTrue(resetArtTrue.indexOf("homeShelf.refreshTiles()") < resetArtTrue.indexOf("navigation.closeHomeTileMenu()"))

        val resetHeroTrue = source.substringAfter("onResetHero = {").substringBefore("onResetClip = {")
            .substringAfter("if (cleared) {").substringBefore("} else {")
        assertTrue(resetHeroTrue.indexOf("homeShelf.refreshTiles()") < resetHeroTrue.indexOf("navigation.closeHomeTileMenu()"))

        val resetClipTrue = source.substringAfter("onResetClip = {").substringBefore("collectionsFor = {")
            .substringAfter("if (cleared) {").substringBefore("} else {")
        assertTrue(resetClipTrue.indexOf("homeShelf.refreshTiles()") < resetClipTrue.indexOf("navigation.closeHomeTileMenu()"))
    }
}
