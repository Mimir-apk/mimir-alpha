package com.gruning.mimir.rewrite

import android.app.Application
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pad on the REAL [CompanionActivityAchievementHub], at the Bottom's 537.7 x 468.3 dp at 369 dpi: pad keys and hat samples go through a real [InputRouter] (with a
 * fake frame scheduler, so the A that Android hands over ahead of a hat press can be replayed in the order a Thor hands it over), exactly where the floor's route
 * would hand them. The walk is read from what each A does: a chip changes the filter, a tile jumps the pager, a badge opens its detail, B leaves the page. Pixel values
 * and a real controller are device-owed.
 */
/** The hub while [show], nothing after: its own function so the condition has a full body. */
@Composable
private fun PadHubSlot(show: Boolean, content: @Composable () -> Unit) {
    if (show) content()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionActivityHubPadRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private val padNow = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    // Default order (recent activity): Game 1 ... Game 6, so game n is page n. Only game 3 has its detail (five badges, the first two earned).
    private fun padGame(n: Int) = CompanionActivityAchievementSnapshot(
        gameId = "g$n",
        account = "me",
        unlocked = n,
        total = 40,
        gameTitle = "Game $n",
        checkedAtEpochMillis = 1L,
        rowsKnown = false,
        raGameId = 100 + n,
        consoleName = if (n % 2 == 0) "SNES" else "Genesis",
        lastAwardedAtMillis = padNow - n * day,
    )

    private val games = (1..6).map(::padGame)
    private val unlock = ActivityRecentUnlock(31, "Badge 31", "Do thing 31", 10, "/Badge/00031.png", 103, "Game 3", padNow - 31_000L, hardcore = false)
    private val loadedGame3 = ActivityCardDetail.Loaded(
        RetroAchievementsProgress(
            gameId = 103,
            title = "Game 3",
            achievements = (1..5).map {
                RetroAchievementsAchievement(it, "Ach $it", "Do $it", 5, unlocked = it <= 2, unlockedHardcore = false, badgeName = "%05d".format(it))
            },
        ),
        atMillis = padNow,
    )
    private val unknown: MimirAchievementCardState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet)
    private var backs = 0

    private var clock = 5_000L
    private val frames = mutableListOf<() -> Unit>()
    private lateinit var router: InputRouter
    private var visible by mutableStateOf(true)

    // The page's own inputs a test moves under it: the games it holds, the swap's say on whether it is the scene on show, and the window's two states (the same two flows
    // the Presentation's [CompanionWindowLive] reads: parked behind another app, screen on).
    private var snapshots by mutableStateOf(games)
    private var padLive by mutableStateOf(true)
    private val windowParked = MutableStateFlow(false)
    private val screenOn = MutableStateFlow(true)
    private val windowLive = CompanionWindowLive(windowParked, screenOn)

    // The page's blur, read through the hub's test seam: 0 = none, 1 = the picker's.
    private var glass: (() -> Float)? = null

    @Before
    fun setUp() {
        clock = 5_000L
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { clock }
        router = InputRouter(
            interactionRoot = InteractionRoot.COMPANION,
            inputAuthority = DefaultInputAuthority(clock = { clock }),
            padFrameScheduler = PadFrameScheduler { block -> frames += block },
        )
    }

    @After
    fun tearDown() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun mount(holder: ActivityAchievementFilterHolder, navigationEnabled: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        PadHubSlot(visible, hub(holder, navigationEnabled))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun hub(holder: ActivityAchievementFilterHolder, navigationEnabled: Boolean): @Composable () -> Unit = {
        CompanionActivityAchievementHub(
            snapshots = snapshots,
            unknownState = unknown,
            navigationEnabled = navigationEnabled,
            onBack = { backs += 1 },
            details = MutableStateFlow<Map<Int, ActivityCardDetail>>(mapOf(103 to loadedGame3)),
            requestDetail = {},
            filterHolder = holder,
            nowMillis = { padNow },
            recent = listOf(unlock),
            padRouter = router,
            padLive = padLive,
            windowLive = windowLive,
            glassProbe = { glass = it },
        )
    }

    private fun advance(ms: Long = 300L) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun frame() {
        val due = frames.toList()
        frames.clear()
        due.forEach { it() }
    }

    /** One pad button: down, the frame (an A waits for it), up, and the page's recomposition. */
    private fun press(keyCode: Int) {
        clock += 400
        router.onRawKeyInput(keyCode = keyCode, down = true, eventTimeMs = clock)
        frame()
        clock += 40
        router.onRawKeyInput(keyCode = keyCode, down = false, eventTimeMs = clock)
        advance()
    }

    private fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN)

    private fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT)

    private fun a() = press(KeyEvent.KEYCODE_BUTTON_A)

    private fun b() = press(KeyEvent.KEYCODE_BUTTON_B)

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun desc(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun texts(): List<String> = ArrayList<String>().also { out ->
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    }

    private fun runes(): String? = desc(AchievementHubRunesTag)

    // ── the walk ────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the pad walks the top row, the strip, the card and its badges, and B steps back out and then leaves`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder)
        assertEquals("Game 1 of 6", runes())

        down() // lights the ring on the search pill and does nothing else
        assertEquals(ActivityAchievementStatus.ALL, holder.state.value.status)
        right() // the Show chip
        a()
        assertTrue("A on the Show chip opens its list", exists(AchievementFilterShowListTag))
        down() // In progress, under the chosen All
        a()
        assertEquals("A on a row does what its tap does", ActivityAchievementStatus.IN_PROGRESS, holder.state.value.status)
        assertFalse("and closes the list", exists(AchievementFilterShowListTag))

        down() // the strip's one tile
        a()
        assertEquals("A on a tile jumps the pager to that game's card", "Game 3 of 6", runes())
        assertTrue("and opens that badge's details", activityRecentDetail(unlock).meta in texts())

        down() // the card
        right()
        assertEquals("right on the card pages the cards", "Game 4 of 6", runes())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Game 3 of 6", runes())

        down() // the card's first badge
        a()
        assertTrue("A on a badge opens its details: ${texts()}", "Ach 1" in texts())
        right()
        a()
        assertTrue("the next badge", "Ach 2" in texts())

        b()
        assertEquals("B steps out of the badges and does not leave the page", 0, backs)
        b()
        assertEquals("B on the card leaves the page, as the Back pill does", 1, backs)
    }

    @Test
    fun `a hat press then A on a badge opens the badge the hat moved to, however Android orders them`() {
        mount(ActivityAchievementFilterHolder())
        press(KeyEvent.KEYCODE_BUTTON_R1)
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("the shoulders page the cards", "Game 3 of 6", runes())
        down() // lights the ring
        down() // the strip
        down() // the card
        down() // the first badge
        clock += 400
        val base = clock
        // The Thor hands the A over the moment it arrives and the hat at the next frame, so a RIGHT then A that were pressed in that order arrive as A then RIGHT.
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = base + 100)
        assertFalse("the A waits for the frame's pad input", "Ach 1" in texts())
        router.onRawMotionSample(axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f), eventTimeMs = base + 60)
        router.onRawMotionSample(axes = mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f), eventTimeMs = base + 100)
        frame()
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = false, eventTimeMs = base + 140)
        advance()
        assertTrue("the A acted on the badge the hat moved to: ${texts()}", "Ach 2" in texts())
        assertFalse("and not on the one it was pressed over", "Ach 1" in texts())
    }

    @Test
    fun `A on the search pill opens the keyboard for the pad, and B closes it without leaving the page`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder)
        down() // lights the ring on the search pill
        a()
        assertTrue("the keyboard is open", exists(AchievementFilterKeyboardTag))
        b()
        assertFalse("B closes the keyboard", exists(AchievementFilterKeyboardTag))
        assertEquals("and does not leave the page", 0, backs)
    }

    @Test
    fun `the console chip opens its list, the pad walks it, A picks, and B closes it`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder)
        down() // lights the ring on the search pill
        right() // the Show chip
        right() // the console chip
        a()
        assertTrue("the list is open", exists(AchievementFilterConsoleListTag))
        down() // the first console under "All consoles"
        a()
        val first = ActivityAchievementIndex(games).consoles.first().name
        assertEquals(first, holder.state.value.console)
        assertFalse("A closes the list", exists(AchievementFilterConsoleListTag))
        a() // focus went back to the console chip: open it again
        assertTrue(exists(AchievementFilterConsoleListTag))
        b()
        assertFalse("B closes the list", exists(AchievementFilterConsoleListTag))
        assertEquals("and does not leave the page", 0, backs)
        // Focus is back on the console chip after B too (not on the card, nor the first key): the next A opens the picker again, and B closes it again.
        a()
        assertTrue("B gave focus back to the console chip, so A opens its picker", exists(AchievementFilterConsoleListTag))
        b()
        assertFalse(exists(AchievementFilterConsoleListTag))
    }

    @Test
    fun `the Show chip opens its list on the chosen row, the pad walks it, B closes it and gives focus back to the chip`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder)
        down() // lights the ring on the search pill
        right() // the Show chip
        a()
        assertTrue("the list is open", exists(AchievementFilterShowListTag))
        assertTrue("the rows are the six words: ${texts()}", ActivityAchievementStatus.entries.all { it.label in texts() })
        down() // under the chosen All: In progress
        a()
        assertEquals(ActivityAchievementStatus.IN_PROGRESS, holder.state.value.status)
        assertFalse("A closes the list", exists(AchievementFilterShowListTag))
        a() // focus went back to the Show chip: open it again
        assertTrue(exists(AchievementFilterShowListTag))
        b()
        assertFalse("B closes the list", exists(AchievementFilterShowListTag))
        assertEquals("and does not leave the page", 0, backs)
        a()
        assertTrue("B gave focus back to the Show chip, so A opens its list again", exists(AchievementFilterShowListTag))
        b()
        assertEquals("the choice is still In progress", ActivityAchievementStatus.IN_PROGRESS, holder.state.value.status)
    }

    @Test
    fun `only one list is open at a time, and a tap outside the Show list closes it`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder)
        compose.onNodeWithTag(AchievementFilterShowTag, useUnmergedTree = true).performTouchInput { click() }
        advance()
        assertTrue(exists(AchievementFilterShowListTag))
        assertFalse(exists(AchievementFilterConsoleListTag))
        compose.onNodeWithTag(AchievementFilterShowScrimTag, useUnmergedTree = true).performTouchInput { click(Offset(6f, 6f)) }
        advance()
        assertFalse("a tap outside closes the Show list", exists(AchievementFilterShowListTag))
        compose.onNodeWithTag(AchievementFilterConsoleTag, useUnmergedTree = true).performTouchInput { click() }
        advance()
        assertTrue(exists(AchievementFilterConsoleListTag))
        assertFalse(exists(AchievementFilterShowListTag))
    }

    @Test
    fun `a tap outside the picker closes it and hands focus back to the console chip, so the second press after the touch reopens it`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder)
        down() // lights the ring on the search pill
        right() // the Show chip
        right() // the console chip
        a()
        assertTrue("the list is open", exists(AchievementFilterConsoleListTag))
        compose.onNodeWithTag(AchievementFilterConsoleScrimTag, useUnmergedTree = true).performTouchInput { click(Offset(6f, 6f)) }
        advance()
        assertFalse("a tap outside closes the picker", exists(AchievementFilterConsoleListTag))
        a() // the first press after a touch only lights the dark ring again
        assertFalse("and does nothing else", exists(AchievementFilterConsoleListTag))
        a() // the second acts on the key focus is on: the console chip, so the picker opens again
        assertTrue("focus went back to the console chip, not to the card or the first key", exists(AchievementFilterConsoleListTag))
    }

    @Test
    fun `while navigation is held off the pad is consumed and does nothing`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder, navigationEnabled = false)
        down()
        down()
        a()
        b()
        assertEquals(ActivityAchievementStatus.ALL, holder.state.value.status)
        assertEquals(0, backs)
    }

    // ── the floor's route ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `while the page is live the floor's route reaches the pad, and when the page goes the pad is Main's again`() {
        mount(ActivityAchievementFilterHolder())
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("registered while live", CompanionActivityPadRoute.routeKey(event))
        visible = false
        advance()
        assertFalse("released when the page leaves", CompanionActivityPadRoute.routeKey(event))
    }

    // ── the window the page is in ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun floorKey() = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)

    private fun settle() {
        advance()
        advance()
    }

    @Test
    fun `a window parked behind another app while the page is live gives the pad to Main at once, and a reveal takes it again`() {
        mount(ActivityAchievementFilterHolder())
        assertTrue("the page has the pad while the window is live", CompanionActivityPadRoute.routeKey(floorKey()))
        windowParked.value = true
        assertFalse("the very next floor event is Main's, before anything has run", CompanionActivityPadRoute.routeKey(floorKey()))
        settle()
        assertFalse("and it stays Main's once the page has let go", CompanionActivityPadRoute.routeKey(floorKey()))
        windowParked.value = false
        settle()
        assertTrue("a reveal gives it back to the page", CompanionActivityPadRoute.routeKey(floorKey()))
    }

    @Test
    fun `a screen that goes off gives the pad to Main, and the screen coming on gives it back to the page`() {
        mount(ActivityAchievementFilterHolder())
        assertTrue(CompanionActivityPadRoute.routeKey(floorKey()))
        screenOn.value = false
        assertFalse("the very next floor event is Main's", CompanionActivityPadRoute.routeKey(floorKey()))
        settle()
        assertFalse(CompanionActivityPadRoute.routeKey(floorKey()))
        screenOn.value = true
        settle()
        assertTrue("the screen on again", CompanionActivityPadRoute.routeKey(floorKey()))
    }

    @Test
    fun `a screen that comes back on does not give the pad to a window that is still parked`() {
        mount(ActivityAchievementFilterHolder())
        windowParked.value = true
        screenOn.value = false
        settle()
        screenOn.value = true
        settle()
        assertFalse("still parked: still Main's", CompanionActivityPadRoute.routeKey(floorKey()))
        windowParked.value = false
        settle()
        assertTrue(CompanionActivityPadRoute.routeKey(floorKey()))
    }

    @Test
    fun `a page the swap is carrying away has already given the pad back, and a page that is the scene on show has it`() {
        mount(ActivityAchievementFilterHolder())
        assertTrue(CompanionActivityPadRoute.routeKey(floorKey()))
        padLive = false
        settle()
        assertFalse("another scene is on show", CompanionActivityPadRoute.routeKey(floorKey()))
        padLive = true
        settle()
        assertTrue("the page is the scene on show again", CompanionActivityPadRoute.routeKey(floorKey()))
    }

    @Test
    fun `parking the window drops the A that was waiting for its frame, and the pace the page had spent`() {
        val holder = ActivityAchievementFilterHolder()
        mount(holder)
        down() // lights the ring on the search pill
        down() // the first status chip
        right() // In progress
        val lastMove = clock - 40
        assertFalse("the last move spent the directional pace", MimirDirectionalPace.tryAccept(eventTimeMs = lastMove + 1))
        clock += 400
        // An A on the chip, held for the frame the page's pad input is read at: it would filter, as the first walk shows.
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = clock)
        windowParked.value = true
        settle()
        frame()
        advance()
        assertEquals("the A that was waiting never acts", ActivityAchievementStatus.ALL, holder.state.value.status)
        assertTrue("and the pace is Main's to use again", MimirDirectionalPace.tryAccept(eventTimeMs = lastMove + 1))
    }

    // ── a search that loses its header ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pad search whose games drop to one is closed with its header, B leaves the page, and the header coming back does not bring the keyboard back`() {
        mount(ActivityAchievementFilterHolder())
        down() // lights the ring on the search pill
        a()
        assertTrue("the keyboard is open", exists(AchievementFilterKeyboardTag))
        snapshots = games.take(1)
        settle()
        assertFalse("with fewer than two games the header and its keyboard are gone", exists(AchievementFilterKeyboardTag))
        b()
        assertEquals("B is the page's again: it leaves", 1, backs)
        snapshots = games
        settle()
        assertFalse("and the header coming back is not a keyboard coming back", exists(AchievementFilterKeyboardTag))
    }

    // ── a picker left open when the page stops being drivable ───────────────────────────────────────────────────────────────────

    /** The console picker opened with the pad, its blur settled on. */
    private fun openPicker(holder: ActivityAchievementFilterHolder) {
        mount(holder)
        down() // lights the ring on the search pill
        right() // the Show chip
        right() // the console chip
        a()
        settle()
        assertTrue("the picker is open", exists(AchievementFilterConsoleListTag))
        assertTrue("and the page behind it is blurred: ${glass?.invoke()}", (glass?.invoke() ?: 0f) > 0.9f)
    }

    /**
     * The page stops being drivable ([lose]) with the picker open and comes back ([regain]): the picker is closed, nothing is blurred, and focus is on the console chip - so the
     * next press acts on the chip (it opens the picker again) and not on the search pill, the card or a console row.
     */
    private fun assertPickerClosedAcrossLoss(what: String, lose: () -> Unit, regain: () -> Unit) {
        val holder = ActivityAchievementFilterHolder()
        openPicker(holder)
        lose()
        settle()
        assertFalse("$what: the picker is closed while the page cannot be driven", exists(AchievementFilterConsoleListTag))
        assertEquals("$what: and nothing is blurred", 0f, glass!!.invoke(), 0.001f)
        regain()
        settle()
        assertFalse("$what: it does not come back with the page", exists(AchievementFilterConsoleListTag))
        assertEquals("$what: nor does the blur", 0f, glass!!.invoke(), 0.001f)
        // The pad's ring may be dark after the loss (the first press only lights it again): at most one press goes to waking it, the next acts on the key focus is on.
        a()
        if (!exists(AchievementFilterConsoleListTag)) a()
        assertTrue("$what: focus is on the console chip, so A opens its picker", exists(AchievementFilterConsoleListTag))
        assertFalse("$what: and not the search pill's keyboard", exists(AchievementFilterKeyboardTag))
        assertEquals("$what: nothing was filtered on the way", null, holder.state.value.console)
        assertEquals(ActivityAchievementStatus.ALL, holder.state.value.status)
        b()
        assertFalse("$what: B closes it again", exists(AchievementFilterConsoleListTag))
        assertEquals("$what: and does not leave the page", 0, backs)
    }

    @Test
    fun `a window parked behind another app with the picker open closes the picker, clears the blur and puts focus on the console chip`() {
        assertPickerClosedAcrossLoss("parked", lose = { windowParked.value = true }, regain = { windowParked.value = false })
    }

    @Test
    fun `a screen that goes off with the picker open closes the picker, clears the blur and puts focus on the console chip`() {
        assertPickerClosedAcrossLoss("screen off", lose = { screenOn.value = false }, regain = { screenOn.value = true })
    }

    @Test
    fun `a swap that carries the page away with the picker open closes the picker, clears the blur and puts focus on the console chip`() {
        assertPickerClosedAcrossLoss("swapped away", lose = { padLive = false }, regain = { padLive = true })
    }
}
