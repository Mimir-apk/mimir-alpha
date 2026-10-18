package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Activity page's pad walk ([ActivityHubPad]) without a screen: the focus order across the header, the strip, the card and its badges; left and right paging
 * the cards on the card; A on each kind of key; B stepping back out, then out of the page; the ring dark until a press and again after a touch; a modal list;
 * a focused key that leaves the page; and a hat press then A on a badge through the real router (the hat's frame-late delivery is Android's and cannot happen on
 * a JVM; these pin the router's half, with events handed over in the order a Thor hands them over).
 */
class ActivityHubPadTest {

    private class FakeHost : ActivityPadHost {
        var enabled = true
        val activated = mutableListOf<FocusKey>()
        var activateReturns: (FocusKey) -> FocusKey? = { null }
        val pages = mutableListOf<Int>()
        var leaves = 0
        var dismissals = 0
        var dismissReturns: FocusKey? = null
        var cardActing = false

        override fun navigationEnabled(): Boolean = enabled

        override fun cardActs(): Boolean = cardActing

        override fun activate(key: FocusKey): FocusKey? {
            activated += key
            return activateReturns(key)
        }

        override fun page(delta: Int): Boolean {
            pages += delta
            return true
        }

        override fun leave() {
            leaves += 1
        }

        override fun dismissModal(): FocusKey? {
            dismissals += 1
            return dismissReturns
        }
    }

    private val search = ActivityPadKeys.Search
    private val show = ActivityPadKeys.Show
    private val console = ActivityPadKeys.Console
    private val top = listOf(search, show, console)
    private val tiles = listOf(31, 32, 33).map { ActivityPadKeys.recent(it) }
    private val card = ActivityPadKeys.Card
    private val badges = listOf(10, 11, 12, 13, 14).map { ActivityPadKeys.badge(it) }
    private val full = ActivityPadRing(header = listOf(top), recent = tiles, card = card, badges = badges)

    private val host = FakeHost()
    private val authority = DefaultFocusAuthority()
    private val pad = ActivityHubPad(authority, host).also { it.setRing(full) }

    private fun focused(): FocusKey? = authority.state.value.focused

    private fun press(vararg actions: ControllerAction) = actions.forEach { assertTrue(pad.dispatch(it)) }

    private val down = ControllerAction.NAV_DOWN
    private val up = ControllerAction.NAV_UP
    private val left = ControllerAction.NAV_LEFT
    private val right = ControllerAction.NAV_RIGHT

    /** Lights the ring (the first press does nothing else), so a test starts where a player who has pressed once is. */
    private fun lit() {
        press(down)
        assertTrue(pad.engaged)
        assertEquals(search, focused())
    }

    // ── the ring ────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the ring is dark until the first press, which lights the first key and does nothing else`() {
        assertFalse(pad.engaged)
        press(down)
        assertTrue(pad.engaged)
        assertEquals("the search pill is where the walk starts", search, focused())
        assertTrue(host.activated.isEmpty())
        press(down)
        assertEquals("the second press moves down to the strip's first tile", tiles.first(), focused())
    }

    @Test
    fun `a touch darkens the ring and the next press lights it where it was and moves nothing`() {
        lit()
        press(right)
        assertEquals(show, focused())
        pad.onTouch()
        assertFalse(pad.engaged)
        assertEquals("the place is kept", show, focused())
        press(right)
        assertTrue(pad.engaged)
        assertEquals("a press on a dark ring only lights it", show, focused())
        press(right)
        assertEquals(console, focused())
    }

    @Test
    fun `A on a dark ring only lights it`() {
        press(ControllerAction.CONFIRM)
        assertTrue(pad.engaged)
        assertTrue(host.activated.isEmpty())
        press(ControllerAction.CONFIRM)
        assertEquals(listOf(search), host.activated)
    }

    @Test
    fun `every key has a slot before it is composed, so a badge the lazy row has not shown can take focus`() {
        val slots = authority.registeredKeysInScope(ActivityPadScope).toSet()
        assertEquals(full.rows.flatten().toSet(), slots)
        lit()
        press(down, down, down)
        assertEquals(badges.first(), focused())
        press(right, right, right, right)
        assertEquals("the last badge, never composed in this test", badges.last(), focused())
    }

    // ── the focus order ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the walk goes top row, strip, card, badges, and the column goes with the player`() {
        lit()
        press(right, right)
        assertEquals("the console chip ends the top row (search, show, console)", console, focused())
        press(right)
        assertEquals("a row stops at its end", console, focused())
        press(down)
        assertEquals("the strip's row is shorter: it takes its last tile", tiles.last(), focused())
        press(left)
        assertEquals(tiles[1], focused())
        press(down)
        assertEquals(card, focused())
        press(down)
        assertEquals("down from the card enters the badges at the first", badges.first(), focused())
        press(right, right)
        assertEquals(badges[2], focused())
        press(left, left, left)
        assertEquals("a row stops at its start", badges.first(), focused())
        press(up)
        assertEquals("up from the badges returns to the card", card, focused())
        press(up)
        assertEquals("the column the player was walking in is remembered", tiles[1], focused())
        press(up)
        assertEquals("and so is it in the top row", show, focused())
        press(left)
        assertEquals(search, focused())
        press(up)
        assertEquals("the top is the top", search, focused())
    }

    @Test
    fun `a page with no strip and no header goes straight to the card`() {
        val bare = ActivityHubPad(DefaultFocusAuthority(), host).also { it.setRing(ActivityPadRing(card = card, badges = badges.take(2))) }
        bare.dispatch(down)
        assertTrue(bare.engaged)
        assertEquals(card, bare.authority.state.value.focused)
        bare.dispatch(down)
        assertEquals(badges.first(), bare.authority.state.value.focused)
        bare.dispatch(up)
        bare.dispatch(up)
        assertEquals("nothing above the card", card, bare.authority.state.value.focused)
    }

    @Test
    fun `left and right on the card page the cards and the card keeps focus`() {
        lit()
        press(down, down)
        assertEquals(card, focused())
        assertTrue(host.pages.isEmpty())
        press(right)
        assertEquals(listOf(1), host.pages)
        press(left, left)
        assertEquals(listOf(1, -1, -1), host.pages)
        assertEquals("paging does not move the ring", card, focused())
    }

    @Test
    fun `the shoulders page the cards from anywhere without lighting the ring`() {
        press(ControllerAction.PAGE_RIGHT)
        press(ControllerAction.PAGE_LEFT)
        assertEquals(listOf(1, -1), host.pages)
        assertFalse(pad.engaged)
    }

    // ── A ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `A acts on the focused key, and the card is not a button`() {
        lit()
        press(ControllerAction.CONFIRM)
        press(right, ControllerAction.CONFIRM)
        press(down, ControllerAction.CONFIRM)
        assertEquals(listOf(search, show, tiles[1]), host.activated)
        press(down)
        assertEquals(card, focused())
        press(ControllerAction.CONFIRM)
        assertEquals("A on the card does nothing", 3, host.activated.size)
        press(down, ControllerAction.CONFIRM)
        assertEquals(badges.first(), host.activated.last())
    }

    @Test
    fun `A on a card whose badges failed to load acts, and on any other card it still does nothing`() {
        lit()
        press(down, down)
        assertEquals(card, focused())
        press(ControllerAction.CONFIRM)
        assertTrue("a card with badges is not a button", host.activated.isEmpty())
        host.cardActing = true
        host.activateReturns = { null }
        press(ControllerAction.CONFIRM)
        assertEquals("a failed card is asked to try again", listOf(card), host.activated)
        host.cardActing = false
        press(ControllerAction.CONFIRM)
        assertEquals("and stops being a button once its badges are back", 1, host.activated.size)
    }

    @Test
    fun `where A says focus goes next, focus goes`() {
        lit()
        host.activateReturns = { if (it == search) console else null }
        press(ControllerAction.CONFIRM)
        assertEquals(console, focused())
        press(ControllerAction.CONFIRM)
        assertEquals("a null leaves focus where it is", console, focused())
    }

    // ── B ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `B steps from a badge back to the card, and only then out of the page`() {
        lit()
        press(down, down, down, down, down, down)
        assertEquals(badges.first(), focused())
        press(ControllerAction.CANCEL)
        assertEquals(card, focused())
        assertEquals("stepping out of the badges does not leave the page", 0, host.leaves)
        press(ControllerAction.CANCEL)
        assertEquals(1, host.leaves)
    }

    @Test
    fun `B from a chip, a tile or a dark ring leaves the page at once`() {
        press(ControllerAction.CANCEL)
        assertEquals("a dark ring", 1, host.leaves)
        lit()
        press(right)
        press(ControllerAction.CANCEL)
        assertEquals("a chip", 2, host.leaves)
        press(down)
        press(ControllerAction.CANCEL)
        assertEquals("a tile", 3, host.leaves)
    }

    // ── held off, other actions ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `while navigation is held off a press is consumed and does nothing`() {
        lit()
        host.enabled = false
        press(down, right, ControllerAction.CONFIRM, ControllerAction.CANCEL, ControllerAction.PAGE_RIGHT)
        assertEquals(search, focused())
        assertTrue(host.activated.isEmpty())
        assertEquals(0, host.leaves)
        assertTrue(host.pages.isEmpty())
    }

    @Test
    fun `an action that is not the pad's is left alone`() {
        assertFalse(pad.dispatch(ControllerAction.TERTIARY))
        assertFalse(pad.dispatch(ControllerAction.SYSTEM_START))
        assertFalse(pad.engaged)
    }

    // ── the ring changing under the walk ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a focused badge that leaves the page falls to the card, and the old slots go`() {
        lit()
        press(down, down, down, down, down, right, right)
        assertEquals(badges[2], focused())
        val newBadges = listOf(20, 21).map { ActivityPadKeys.badge(it) }
        pad.setRing(full.copy(badges = newBadges))
        assertEquals("the page changed: the card is where the walk lands", card, focused())
        val slots = authority.registeredKeysInScope(ActivityPadScope).toSet()
        assertTrue(newBadges.all { it in slots })
        assertTrue(badges.none { it in slots })
    }

    @Test
    fun `a ring that did not change is not registered again`() {
        lit()
        press(right)
        pad.setRing(full.copy())
        assertEquals(show, focused())
    }

    // ── a modal list ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a modal list is walked up and down, left and right do nothing, A picks and B closes it`() {
        val items = listOf("all", "snes", "genesis").map { ActivityPadKeys.consoleItem(it) }
        val modal = ActivityHubPad(DefaultFocusAuthority(), host).also {
            it.setRing(ActivityPadRing(header = items.map { item -> listOf(item) }, entry = items[1], modal = true))
        }
        modal.dispatch(down)
        assertEquals("the first press lights the selected item", items[1], modal.authority.state.value.focused)
        modal.dispatch(down)
        assertEquals(items[2], modal.authority.state.value.focused)
        modal.dispatch(right)
        modal.dispatch(left)
        assertEquals(items[2], modal.authority.state.value.focused)
        modal.dispatch(up)
        modal.dispatch(up)
        assertEquals(items[0], modal.authority.state.value.focused)
        modal.dispatch(ControllerAction.CONFIRM)
        assertEquals(listOf(items[0]), host.activated)
        host.dismissReturns = items[1]
        modal.dispatch(ControllerAction.CANCEL)
        assertEquals(1, host.dismissals)
        assertEquals("B closes the list and does not leave the page", 0, host.leaves)
        assertEquals(items[1], modal.authority.state.value.focused)
    }

    // ── hat then A through the real router ──────────────────────────────────────────────────────────────────────────────────────

    private var clock = 1_000L
    private val frames = mutableListOf<() -> Unit>()

    @Before
    fun paceOn() {
        clock = 1_000L
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { clock }
    }

    @After
    fun paceOff() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun router() = InputRouter(
        interactionRoot = InteractionRoot.COMPANION,
        inputAuthority = DefaultInputAuthority(clock = { clock }),
        padFrameScheduler = PadFrameScheduler { block -> frames += block },
    ).also {
        it.handler = pad::dispatch
        it.confirmWaitsForPadFrame = { true }
    }

    private fun frame() {
        val due = frames.toList()
        frames.clear()
        due.forEach { it() }
    }

    private fun hatRight(router: InputRouter, atMs: Long) {
        router.onRawMotionSample(axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f), eventTimeMs = atMs)
        router.onRawMotionSample(axes = mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f), eventTimeMs = atMs + 40)
    }

    private fun onFirstBadge() {
        lit()
        press(down, down, down, down, down, down)
        assertEquals(badges.first(), focused())
    }

    @Test
    fun `RIGHT then A on a badge, handed over as A then RIGHT, opens the badge the ring moved to`() {
        onFirstBadge()
        val router = router()
        clock = 1_100
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = 1_100)
        assertTrue("A waits for the frame's pad input", host.activated.isEmpty())
        hatRight(router, atMs = 1_060) // pressed first, read by the frame's input stage
        frame()
        assertEquals("the A acts on the badge the hat moved to", listOf(badges[1]), host.activated)
        assertEquals(badges[1], focused())
    }

    @Test
    fun `A then RIGHT keeps its order and opens the badge it was pressed on`() {
        onFirstBadge()
        val router = router()
        clock = 1_100
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = 1_100)
        hatRight(router, atMs = 1_140)
        assertEquals(listOf(badges[0]), host.activated)
        assertEquals(badges[1], focused())
        frame()
        assertEquals("released once", listOf(badges[0]), host.activated)
    }

    @Test
    fun `a lone A on a badge lands on the next frame and acts once`() {
        onFirstBadge()
        val router = router()
        clock = 1_100
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = 1_100)
        assertNull(host.activated.firstOrNull())
        frame()
        assertEquals(listOf(badges[0]), host.activated)
    }
}
