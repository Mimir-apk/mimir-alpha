package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The LIST view's half of the ring-swap law.
 *
 * **What this pins, and what it deliberately does NOT claim.** A platform-switch throb was measured on Odin
 * (build `5117171e`) in the **hero backdrop**, separate from this list-position defect. Across four consecutive switches the backdrop band drops from ~40 to ~8 luminance
 * in the cut frame, holds a **0.00 frame-to-frame diff for 22–27 frames (~370–450 ms)**, then ramps back
 * over ~350 ms. Nothing in this file addresses that.
 *
 * What this file pins is the defect **class** the carousel already closed and the list still carried:
 * a ring identity replacement that inherits the departed ring's position.
 * `rememberCoverflowLinearMotion` (`CoverflowMotion.kt:485`) remembers the carousel's centre on
 * `identityOrder` and constructs it already at the destination's index. `GamelistView`'s `LazyListState`
 * was remembered on **nothing**, so a platform switch handed the destination the source's scroll offset
 * and left `ScrollFocusedLazyItemIntoView` to correct it after the list had already been laid out there.
 *
 * It is reachable on the measured hardware in exactly one direction — **into** GameCube, the only platform on
 * the device whose 8 games make a list that genuinely scrolls (frames 29/30 of the contact sheet show it
 * scrolled) — while GameCube's remembered lane focus sits on a lower row. Out of GameCube into a 2-game
 * platform it cannot show, which is why the capture could not see it.
 *
 * Render wiring is not JVM-observable and this module has no Compose test harness on the JVM, so the
 * decision is pinned as arithmetic and the wiring is characterised from source text — this repo's
 * established convention (`GamelistListFocusClearanceTest`, `GamelistCardArtHugTest`, `GhostFreeIdentityTest`).
 */
class GamelistListRingSwapScrollOriginTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    /** `GamelistView`'s body alone — the sibling pickers below it own their own `rememberLazyListState()`. */
    private fun gamelistViewBody(): String {
        val src = sourceOf("LibraryScreen.kt")
        val start = src.indexOf("internal fun GamelistView(")
        val end = src.indexOf("internal fun ScrollFocusedLazyItemIntoView(")
        assertTrue("GamelistView must precede ScrollFocusedLazyItemIntoView", start in 0 until end)
        return src.substring(start, end)
    }

    // ---------------------------------------------------------------- the decision, as arithmetic

    /**
     * The load-bearing case. Gamelist focus is remembered per (platform, favoritesOnly) lane
     * (`LibraryViewModel.gamelistFocusMemory`), so a switch BACK to a visited platform restores focus to a
     * row that may be far down. Being born at index 0 would only trade the source's wrong offset for the
     * destination's wrong offset — the destination would paint at its top and then jump.
     */
    @Test
    fun `the seat is preserved, not flattened to the top`() {
        assertEquals(
            "a destination whose remembered focus is row 7 must be BORN at row 7; seating it at 0 and " +
                "letting the reveal pass jump there is the same visible correction by another name",
            7,
            gamelistListScrollOrigin(scrollTargetIndex = 7, itemCount = 8),
        )
    }

    /**
     * The ordinary beat this must not mistake for a seat: a scope commonly composes one frame before
     * FocusAuthority publishes its restored key, and `scrollTargetIndex` is -1 for that frame.
     */
    @Test
    fun `an unpublished focus means the top, never a negative scroll index`() {
        assertEquals(0, gamelistListScrollOrigin(scrollTargetIndex = -1, itemCount = 8))
    }

    /** An index past the end cannot be seated at all; the bounds belong to the list, not the caller. */
    @Test
    fun `a seat past the end clamps to the last row`() {
        assertEquals(
            "the 8-game source's row 7 must not be carried into a 2-game destination as row 7",
            1,
            gamelistListScrollOrigin(scrollTargetIndex = 7, itemCount = 2),
        )
    }

    @Test
    fun `an empty ring has no seat`() {
        assertEquals(0, gamelistListScrollOrigin(scrollTargetIndex = 3, itemCount = 0))
        assertEquals(0, gamelistListScrollOrigin(scrollTargetIndex = -1, itemCount = 0))
    }

    // ---------------------------------------------------------------- the wiring, characterised

    /**
     * Differential: drop the `key(...)` wrapper — restore `rememberLazyListState()` remembered on nothing —
     * and this fails. That is precisely the reverted state in which a platform switch inherits the source's
     * scroll offset.
     */
    @Test
    fun `the list's scroll state is remembered on the ring identity and born at the seat`() {
        val body = gamelistViewBody()
        assertFalse(
            "a bare rememberLazyListState() in GamelistView is remembered on NOTHING, so the same state " +
                "— and the same scroll offset — survives a platform switch",
            body.contains("rememberLazyListState()"),
        )
        assertTrue(
            "the scroll state must be keyed on the SAME ring identity ScrollFocusedLazyItemIntoView " +
                "already treats as a content replacement (listIdentity), or the two disagree about when " +
                "the content changed",
            body.contains("key(listIdentity) {") &&
                body.substringAfter("key(listIdentity) {").trimStart().startsWith("rememberLazyListState("),
        )
        assertTrue(
            "walk-32i: that identity is the departure frame's lineage, which a dissolving removal does not change, " +
                "and falls back to the keys where no frame is supplied: keyed on the keys alone the list is re-created " +
                "the moment a game leaves and the reveal pass seeks, which is the jump",
            body.contains("val listIdentity: Any = departureFrame?.lineage ?: gameFocusKeys"),
        )
        assertTrue(
            "and it must be BORN at the seat, through the shared predicate rather than a literal 0",
            body.contains("initialFirstVisibleItemIndex = gamelistListScrollOrigin("),
        )
    }

    /**
     * The identity the scroll origin keys on must stay the identity the reveal pass calls a content
     * replacement. If these two ever drift apart, the list is reborn on one definition of "new content"
     * and re-seated on another.
     */
    @Test
    fun `the scroll origin and the reveal epoch read the same content identity`() {
        val body = gamelistViewBody()
        assertTrue(
            "ScrollFocusedLazyItemIntoView's contentIdentity must remain the list identity the scroll state is keyed on",
            body.contains("contentIdentity = listIdentity,"),
        )
    }

    // ------------------------------------------- the shared loader gate, applied to the LIST

    /**
     *
     * [libraryGamelistShowsLoader] was written for it and wired to the CAROUSEL only, so the LIST kept
     * printing *"No games on this platform yet."* about a platform that has games, for the beat the
     * stale-list guard holds `games` empty. Same loader gate, the field beside the one it was applied to.
     *
     * Differential: remove the `isLoading` argument from the LIST call site and this fails.
     */
    @Test
    fun `the LIST view is given the same loader answer the carousel is given`() {
        val src = sourceOf("LibraryScreen.kt")
        val listCall = src.substringAfter("GamelistViewMode.LIST -> GamelistView(")
            .substringBefore("bottomControlInset = listBottomControlInset,")
        assertTrue(
            "the LIST must resolve its loader through the SAME predicate as the carousel, not answer " +
                "the question a second way",
            listCall.contains("isLoading = libraryGamelistShowsLoader("),
        )
        assertTrue(
            "and GamelistView must honour it instead of printing the empty message over an unsettled query",
            gamelistViewBody().contains("if (isLoading) {"),
        )
    }
}
