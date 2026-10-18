package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.model.library.addedGameIds
import com.gruning.mimir.rewrite.feature.library.GameDetailScope
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewGameEnrichPromptTest {
    @Test
    fun `the offer waits for new games and a settled hash, and is asked once`() {
        assertTrue(shouldOfferEnrichForNewGames(2, hashSettled = true, alreadyOfferedForRevision = false))
        assertFalse("nothing new means nothing to ask about", shouldOfferEnrichForNewGames(0, true, false))
        assertFalse(
            "the whole point is that this lands WHEN the hash finishes, so it cannot land before",
            shouldOfferEnrichForNewGames(2, hashSettled = false, alreadyOfferedForRevision = false),
        )
        assertFalse(
            "one question per scan; answering it must not re-ask",
            shouldOfferEnrichForNewGames(2, hashSettled = true, alreadyOfferedForRevision = true),
        )
    }

    /**
     * This test previously asserted the defect.
     *
     * It pinned `artOptedIn = false` to "do not offer" -- so a player with no ScreenScraper account was
     * never asked whether they wanted artwork, and the card instead appeared at whatever unrelated later
     * moment an account got connected. The regression report described that as the menu appearing at random; it was
     * keyed to the wrong event, and this test held the wrong event in place.
     *
     * The parameter is gone rather than inverted: whether an account exists decides what the card SAYS
     * (`ConfirmNewGames.hasAccount`) and where its button goes, not whether the question is asked.
     */
    @Test
    fun `having no artwork account is not a reason to skip the question`() {
        assertTrue(
            "a first run has no account by definition -- that is who most needs to be asked",
            shouldOfferEnrichForNewGames(3, hashSettled = true, alreadyOfferedForRevision = false),
        )
    }

    @Test
    fun `leaving the accounts step with no account is the onboarding skip, and nothing else is`() {
        assertTrue(onboardingAccountsSkipped(OnboardingStep.METADATA, null))
        assertTrue(onboardingAccountsSkipped(OnboardingStep.METADATA, " "))
        assertFalse("an account answers yes", onboardingAccountsSkipped(OnboardingStep.METADATA, "player"))
        assertFalse("other steps say nothing about artwork", onboardingAccountsSkipped(OnboardingStep.LIBRARY, null))
    }

    @Test
    fun `added game ids are the after-only ids`() {
        assertEquals(setOf("new"), addedGameIds(setOf("old", "kept"), setOf("kept", "new")))
    }

    // ── The card the question is asked with ──────────────────────────────────────────────────────────

    /**
     * A bare A never starts network work.
     *
     * Found on the emulator on 2026-09-22 by walking onboarding as a player would: every card before this
     * one advances on a bare A, and this one seeded its PRIMARY unconditionally -- which, with no account,
     * is Connect, a ScreenScraper sign-in. F7 (Thor re-check on combined16, 2026-10-08) closed the other half: with an
     * account the primary is "Fetch artwork", a batch on the player's own ScreenScraper account, and the card arrived
     * over Library Search with it pre-focused. Whatever the primary does, "Not now" holds the default and the primary is
     * one deliberate move away.
     */
    @Test
    fun `a bare A never starts network work`() {
        assertEquals(
            "the default is Not now, with or without an account",
            NewGameEnrichDismissKey,
            newGameEnrichInitialFocus(),
        )
        // The two keys must stay distinct, or the rule above is unobservable.
        assertNotEquals(NewGameEnrichConfirmKey, NewGameEnrichDismissKey)
    }

    @Test
    fun `on the card as it lands one A press answers not now and only a move up reaches fetch`() {
        fun landed() = DefaultFocusAuthority().apply {
            pushScope(NewGameEnrichFocusScope)
            register(NewGameEnrichConfirmKey, NewGameEnrichFocusScope, FocusBounds(0f, 0f, 200f, 40f))
            register(NewGameEnrichDismissKey, NewGameEnrichFocusScope, FocusBounds(0f, 60f, 200f, 100f))
            requestFocus(newGameEnrichInitialFocus())
        }
        val card = ArtworkFetchUi.ConfirmNewGames(count = 8, hasAccount = true)

        var fetched = 0
        var dismissed = 0
        val bare = landed()
        dispatchArtworkFetch(ControllerAction.CONFIRM, card, {}, { dismissed++ }, { fetched++ }, bare)
        assertEquals("one A press must not start the batch", 0, fetched)
        assertEquals("it answered Not now", 1, dismissed)

        val deliberate = landed()
        dispatchArtworkFetch(ControllerAction.NAV_UP, card, {}, { dismissed++ }, { fetched++ }, deliberate)
        assertEquals("a move alone starts nothing", 0, fetched)
        dispatchArtworkFetch(ControllerAction.CONFIRM, card, {}, { dismissed++ }, { fetched++ }, deliberate)
        assertEquals("up, then A is the deliberate yes", 1, fetched)
        assertEquals("and did not also dismiss", 1, dismissed)
    }

    @Test
    fun `with no account the card asks to connect, not to fetch`() {
        // The defect this half prevents: the question now arrives on time whether or not an account
        // exists, so the button must not offer an enrich that ArtworkFetchController would answer with
        // GateClosed for every game -- a progress bar that completes having downloaded nothing.
        val copy = newGameEnrichCopy(count = 3, hasAccount = false)
        assertEquals("Connect", copy.primaryLabel)
        assertTrue("the player must be told where the art comes from", copy.body.contains("ScreenScraper"))
        assertTrue("and that it costs nothing", copy.body.contains("free account"))
    }

    @Test
    fun `with an account the card offers the fetch itself`() {
        val copy = newGameEnrichCopy(count = 3, hasAccount = true)
        assertEquals("Fetch artwork", copy.primaryLabel)
        assertFalse(
            "having connected already, the sign-in must not be mentioned again",
            copy.body.contains("account"),
        )
    }

    @Test
    fun `the card counts games rather than saying a number and a bare noun`() {
        assertTrue(newGameEnrichCopy(1, hasAccount = true).body.startsWith("1 game are"))
        assertTrue(newGameEnrichCopy(4, hasAccount = true).body.startsWith("4 games are"))
    }

    /**
     * The art kinds are NOT promised here on purpose.
     *
     * The request specified for 3D boxes, cartridges and hero art downloaded together and consistent; that work
     * is open. Naming them in this card would make the product claim something it does not yet do, and this
     * test fails the day someone adds the claim without the behaviour.
     */
    @Test
    fun `the card does not promise art kinds the fetch does not yet guarantee`() {
        listOf(true, false).forEach { hasAccount ->
            val copy = newGameEnrichCopy(2, hasAccount)
            val text = copy.title + copy.body
            listOf("box", "cartridge", "hero", "3D").forEach { promise ->
                assertFalse(
                    "\"$promise\" is promised before the fetch guarantees it (hasAccount=$hasAccount)",
                    text.contains(promise, ignoreCase = true),
                )
            }
        }
    }

    // ── F6: the offer waits for a keyboard or another modal to clear ─────────────────────────────────────

    @Test
    fun `a keyboard or any other overlay covers the offer but the card never covers itself`() {
        assertFalse(
            "nothing up and focus on the shelf -- the offer may land",
            newGameEnrichOfferCovered(emptyList(), FocusScope.Root),
        )
        assertFalse(
            "the card counting itself would defer it the frame it appeared and loop",
            newGameEnrichOfferCovered(listOf(MimirOverlayKind.ARTWORK_FETCH), FocusScope.Root),
        )
        assertTrue(
            "thor-10 -- the Tome library was open with its naming keyboard",
            newGameEnrichOfferCovered(listOf(MimirOverlayKind.HOME_TOME_LIBRARY), FocusScope.Root),
        )
        assertTrue(
            "a keyboard outside the overlay stack (Library search, onboarding) still covers it",
            newGameEnrichOfferCovered(emptyList(), MimirKeyboardFocusScope),
        )
        assertTrue(
            "the card with something else still up is covered",
            newGameEnrichOfferCovered(
                listOf(MimirOverlayKind.ARTWORK_FETCH, MimirOverlayKind.BRIGHTNESS),
                FocusScope.Root,
            ),
        )
    }

    @Test
    fun `an offer that arrives under a cover is queued and returns when the cover clears`() {
        val hold = NewGameEnrichOfferHold()
        val offer = NewGameEnrichPending(scanRevision = 3L, gameIds = setOf("a", "b"))
        assertEquals("uncovered, the offer shows at once", offer, hold.admit(offer))

        hold.cover(true, showing = null) { true }
        assertNull("covered, the offer is held back", hold.admit(offer))
        assertEquals(
            "the cover clearing brings the queued offer back by itself",
            offer,
            hold.cover(false, showing = null) { it == offer },
        )
        assertNull(
            "and it came back once, not twice",
            hold.cover(false, showing = null) { true },
        )
    }

    @Test
    fun `a queued offer is not re-shown once a newer scan replaced it`() {
        val hold = NewGameEnrichOfferHold()
        val stale = NewGameEnrichPending(scanRevision = 3L, gameIds = setOf("a"))
        hold.cover(true, showing = null) { true }
        hold.admit(stale)
        assertNull(
            "pending moved on to another revision, so the old question has nothing left to ask",
            hold.cover(false, showing = null) { false },
        )
    }

    @Test
    fun `a cover arriving over a showing card queues it rather than dropping it`() {
        val hold = NewGameEnrichOfferHold()
        val showing = NewGameEnrichPending(scanRevision = 5L, gameIds = setOf("x"))
        assertNull(
            "Utility opened over the card -- the card steps aside",
            hold.cover(true, showing = showing) { true },
        )
        assertEquals(
            "and is there again when Utility closes, still unanswered",
            showing,
            hold.cover(false, showing = null) { it == showing },
        )
    }

    @Test
    fun `forgetting the queue after an answer means nothing comes back`() {
        val hold = NewGameEnrichOfferHold()
        val offer = NewGameEnrichPending(scanRevision = 7L, gameIds = setOf("y"))
        hold.cover(true, showing = null) { true }
        hold.admit(offer)
        hold.forget()
        assertNull(hold.cover(false, showing = null) { true })
    }

    // ── F7: the question waits for a resting, quiet Home or Library ──────────────────────────────────────

    private val searchScope = FocusScope("library/search")

    @Test
    fun `Library Search and a text entry cover the offer, and so does every surface one level in`() {
        assertTrue(
            "F7 -- the stall path raised Fetch it now over a query being typed; Search is its own scope, not a keyboard",
            newGameEnrichOfferCovered(emptyList(), searchScope, MimirDestination.LIBRARY),
        )
        assertTrue(
            "a touch-typed query keeps the shelf's scope for a frame; the open keyboard alone covers the offer",
            newGameEnrichOfferCovered(emptyList(), LibraryShelfScope, MimirDestination.LIBRARY, textEntryOpen = true),
        )
        assertTrue("the Search keyboard in its own scope", newGameEnrichOfferCovered(emptyList(), MimirKeyboardFocusScope, MimirDestination.LIBRARY))
        assertTrue("a gamelist is one level in", newGameEnrichOfferCovered(emptyList(), GamelistScope, MimirDestination.LIBRARY))
        assertTrue("a game's page is one level in", newGameEnrichOfferCovered(emptyList(), GameDetailScope, MimirDestination.LIBRARY))
        assertTrue("a launch card is not a resting root", newGameEnrichOfferCovered(emptyList(), HomeLaunchCardScope, MimirDestination.HOME))
        assertTrue("Search's filter picker", newGameEnrichOfferCovered(emptyList(), FocusScope("library/search/filterPicker"), MimirDestination.LIBRARY))
        listOf(MimirDestination.SETTINGS, MimirDestination.ONBOARDING, MimirDestination.BROWSE, MimirDestination.DIAGNOSTICS).forEach { destination ->
            assertTrue(
                "$destination is not Home or the Library, even when its focus scope is the shell's root",
                newGameEnrichOfferCovered(emptyList(), FocusScope.Root, destination),
            )
        }
    }

    @Test
    fun `a resting Home or Library, and the card itself, do not cover the offer`() {
        listOf(HomeShelfScope, HomeShelfQuickRailScope, LibraryShelfScope, FocusScope.Root, NewGameEnrichFocusScope).forEach { scope ->
            assertFalse("$scope", newGameEnrichOfferCovered(emptyList(), scope, MimirDestination.HOME))
            assertFalse("$scope", newGameEnrichOfferCovered(emptyList(), scope, MimirDestination.LIBRARY))
        }
        assertFalse(
            "the showing card holds its own scope and its own overlay; counting either would queue it the frame it appeared",
            newGameEnrichOfferCovered(listOf(MimirOverlayKind.ARTWORK_FETCH), NewGameEnrichFocusScope, MimirDestination.LIBRARY),
        )
    }


    @Test
    fun `the production hold starts closed so an offer ready before the first look cannot beat the watcher`() {
        val offer = NewGameEnrichPending(scanRevision = 9L, gameIds = setOf("a"))
        val hold = NewGameEnrichOfferHold(startCovered = true, startQuiet = false)
        assertNull("nothing has declared the surface resting and quiet yet", hold.admit(offer))
        assertNull("clear of a cover is not enough: the surface has not been quiet", hold.cover(false, showing = null) { it == offer })
        assertEquals("it returns the moment the surface is declared quiet", offer, hold.settle(true, showing = null) { it == offer })
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/NewGameEnrichPrompt.kt").readText()
        assertTrue(
            "the singleton the app actually uses is built closed",
            "private val hold = NewGameEnrichOfferHold(startCovered = true, startQuiet = false)" in source,
        )
    }

    // ── P2-2: an offer that arrives while the player is moving waits for the quiet too ───────────────────

    @Test
    fun `an offer that arrives while the surface is not quiet is queued until it is`() {
        val hold = NewGameEnrichOfferHold()
        val offer = NewGameEnrichPending(scanRevision = 4L, gameIds = setOf("a", "b"))
        assertEquals("quiet and uncovered, it shows at once", offer, hold.admit(offer))

        assertNull("scrolling the shelf is not a cover and shows nothing by itself", hold.settle(false, showing = null) { true })
        assertNull("an offer arriving mid-scroll is held although nothing covers the surface", hold.admit(offer))
        assertEquals("the quiet beat releases it", offer, hold.settle(true, showing = null) { it == offer })
    }

    @Test
    fun `activity never hides a card that is already showing`() {
        val hold = NewGameEnrichOfferHold()
        val showing = NewGameEnrichPending(scanRevision = 5L, gameIds = setOf("x"))
        assertEquals("the player moves inside the card: it stays", showing, hold.settle(false, showing = showing) { true })
    }

    @Test
    fun `a quiet beat does not release an offer while a cover still holds it`() {
        val hold = NewGameEnrichOfferHold(startCovered = true, startQuiet = false)
        val offer = NewGameEnrichPending(scanRevision = 6L, gameIds = setOf("y"))
        hold.admit(offer)
        assertNull("quiet alone is not enough", hold.settle(true, showing = null) { true })
        assertEquals("the cover leaving, with the surface already quiet, is", offer, hold.cover(false, showing = null) { it == offer })
    }

    private fun sample(covered: Boolean, focused: String? = "k") = NewGameEnrichCoverSample(covered, focused?.let(::FocusKey))

    private data class Applied(val covered: Boolean, val quiet: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cover holds the offer at once and a clear opens it only after the surface has been quiet`() = runTest {
        val samples = MutableSharedFlow<NewGameEnrichCoverSample>()
        val applied = mutableListOf<Applied>()
        val watcher = launch { watchNewGameEnrichCover(samples, idleMillis = 1_000L) { covered, quiet -> applied += Applied(covered, quiet) } }
        runCurrent()

        samples.emit(sample(covered = true))
        runCurrent()
        assertEquals("a cover is applied with no wait", listOf(Applied(covered = true, quiet = false)), applied)

        samples.emit(sample(covered = false))
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(
            "the player has just backed out: uncovered now, but not quiet, so the card is not for them yet",
            listOf(Applied(true, false), Applied(false, false)),
            applied,
        )
        advanceTimeBy(2L)
        runCurrent()
        assertEquals("one quiet second later it is", Applied(covered = false, quiet = true), applied.last())
        watcher.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `every focus move clears quiet and restarts the wait, and a new cover cancels it`() = runTest {
        val samples = MutableSharedFlow<NewGameEnrichCoverSample>()
        val applied = mutableListOf<Applied>()
        val watcher = launch { watchNewGameEnrichCover(samples, idleMillis = 1_000L) { covered, quiet -> applied += Applied(covered, quiet) } }
        runCurrent()

        samples.emit(sample(covered = false, focused = "a"))
        advanceTimeBy(900L)
        samples.emit(sample(covered = false, focused = "b"))
        advanceTimeBy(900L)
        runCurrent()
        assertTrue("still moving: 1.8 s passed but never 1 s of quiet: $applied", applied.none { it.quiet })
        assertEquals("each move cleared quiet", 2, applied.count { it == Applied(false, false) })
        advanceTimeBy(101L)
        runCurrent()
        assertEquals(Applied(false, true), applied.last())

        samples.emit(sample(covered = false, focused = "c"))
        advanceTimeBy(500L)
        samples.emit(sample(covered = true, focused = "c"))
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals("the search opened mid-wait: quiet is never set again, only the cover lands", Applied(true, false), applied.last())
        assertEquals("one quiet in all", 1, applied.count { it.quiet })
        watcher.cancel()
    }

    // ── P3-2: a reflex press as the card lands does not answer it ────────────────────────────────────────

    @Test
    fun `A and B are ignored for 300 ms after the card lands and a move is not`() {
        assertFalse("the landing frame", newGameEnrichInputArmed(shownAtElapsedMs = 10_000L, nowElapsedMs = 10_000L))
        assertFalse("299 ms in", newGameEnrichInputArmed(10_000L, 10_299L))
        assertTrue("300 ms in", newGameEnrichInputArmed(10_000L, 10_300L))

        val card = ArtworkFetchUi.ConfirmNewGames(count = 8, hasAccount = true)
        val authority = DefaultFocusAuthority().apply {
            mountNewGameEnrichFocusScope(this)
            register(NewGameEnrichConfirmKey, NewGameEnrichFocusScope, FocusBounds(0f, 0f, 200f, 40f))
            register(NewGameEnrichDismissKey, NewGameEnrichFocusScope, FocusBounds(0f, 60f, 200f, 100f))
        }
        var fetched = 0
        var dismissed = 0
        listOf(ControllerAction.CONFIRM, ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
            assertTrue(
                "$action is swallowed, not passed through",
                dispatchArtworkFetch(action, card, {}, { dismissed++ }, { fetched++ }, authority, inputArmed = { false }),
            )
        }
        assertEquals("a reflex A answered nothing", 0, fetched)
        assertEquals("and a reflex B or Back dismissed nothing", 0, dismissed)

        dispatchArtworkFetch(ControllerAction.NAV_UP, card, {}, { dismissed++ }, { fetched++ }, authority, inputArmed = { false })
        assertEquals("a move is not an answer, so it lands during the grace", NewGameEnrichConfirmKey, authority.state.value.focused)

        dispatchArtworkFetch(ControllerAction.CANCEL, card, {}, { dismissed++ }, { fetched++ }, authority, inputArmed = { true })
        assertEquals("armed, B answers as it always did", 1, dismissed)
    }

    // ── P2-1: the card's first focus is Not now ──────────────────────────────────────────────────────────

    @Test
    fun `the card lands on Not now before the first frame even when focus memory points at Fetch`() {
        val authority = DefaultFocusAuthority()
        // An earlier showing was left with focus on Fetch: this is the scope's remembered key when the card mounts again.
        authority.rememberFocusForScope(NewGameEnrichFocusScope, NewGameEnrichConfirmKey)

        mountNewGameEnrichFocusScope(authority)
        // Each button registers from its own layout, Fetch first because it sits above Not now.
        authority.register(NewGameEnrichConfirmKey, NewGameEnrichFocusScope, FocusBounds(0f, 0f, 200f, 40f))
        assertNotEquals("Fetch must never hold focus, not even for the frames before the landing request", NewGameEnrichConfirmKey, authority.state.value.focused)
        authority.register(NewGameEnrichDismissKey, NewGameEnrichFocusScope, FocusBounds(0f, 60f, 200f, 100f))
        assertEquals(NewGameEnrichDismissKey, authority.state.value.focused)
        assertEquals(NewGameEnrichFocusScope, authority.state.value.scope)

        var fetched = 0
        var dismissed = 0
        dispatchArtworkFetch(
            ControllerAction.CONFIRM,
            ArtworkFetchUi.ConfirmNewGames(count = 8, hasAccount = true),
            {},
            { dismissed++ },
            { fetched++ },
            authority,
        )
        assertEquals("an A press on the very first frame cannot start the batch", 0, fetched)
        assertEquals(1, dismissed)

        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchOverlay.kt").readText()
        assertTrue(
            "the overlay mounts the scope through the function that remembers Not now first",
            "mountNewGameEnrichFocusScope(authority)" in source.substringAfter("DisposableEffect(authority, isConfirm)").substringBefore("onDispose"),
        )
    }
}
