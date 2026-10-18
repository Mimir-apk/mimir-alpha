package com.gruning.mimir.rewrite

import android.app.Application
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.MimirEtchedProgressArrivalMs
import com.gruning.mimir.rewrite.core.ui.MimirEtchedProgressShownSemantics
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A set of [count] badges, the first [earned] of them earned (the first of those in hardcore), each 5 points, with digits-only badge names. */
private fun mainProgress(count: Int = 12, earned: Int = 4, title: String = "Main Game") = RetroAchievementsProgress(
    gameId = 77,
    title = title,
    achievements = (1..count).map { id ->
        RetroAchievementsAchievement(
            id = id,
            title = "Badge $id",
            description = "Do thing $id",
            points = 5,
            unlocked = id <= earned,
            unlockedHardcore = id == 1,
            badgeName = "%05d".format(id),
            earnedAt = if (id <= earned) "2023-05-0$id 10:00:00" else null,
            earnedHardcoreAt = if (id == 1) "2023-05-01 12:34:56" else null,
        )
    },
)

/** The widest numbers a header can hold: 500 badges, all earned in hardcore, worth 9999 points between them. */
private fun hugeProgress() = RetroAchievementsProgress(
    gameId = 77,
    title = "Huge Game",
    achievements = (1..500).map { id ->
        RetroAchievementsAchievement(
            id = id,
            title = "Badge $id",
            description = "Do thing $id",
            points = if (id == 500) 19 else 20,
            unlocked = true,
            unlockedHardcore = true,
            badgeName = "%05d".format(id),
            earnedAt = "2023-05-01 10:00:00",
            earnedHardcoreAt = "2023-05-01 10:00:00",
        )
    },
)

/** The overlay's mount of the page, or nothing: its own function so the condition has a full body. */
@Composable
private fun MainPageSlot(show: Boolean, content: @Composable () -> Unit) {
    if (show) content()
}

/**
 * Main's rich achievement page ([MimirMainAchievementPage]) on the REAL composables the Bottom's card shares: every badge of the set in a grid (earned in colour, locked as
 * its `_lock` art, each asked of the bounded cache by its own path), a tap or the pad's A shows a badge's details, the pad walks the grid through the one focus authority
 * (a real [InputRouter] with the frame-ordered A, through the overlay's own [dispatchAchievementCard]), and the page is laid out without clipping at Thor Main's 832 x 468 dp,
 * a compact handheld, the 700 x 350 floor and a phone, each at font 1.0, 1.3 and 2.0. Pixel values and a real controller are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirMainAchievementPageRenderTest {

    @get:Rule
    val utc = UtcDefaultTimeZoneRule()

    @get:Rule
    val compose = createComposeRule()

    private val asked = CopyOnWriteArrayList<String>()
    private val artAsked = CopyOnWriteArrayList<CompanionActivityAchievementSnapshot>()
    private val artResolver = ActivityAchievementArtResolver { snapshot ->
        artAsked += snapshot
        ActivityAchievementArtSources(null, null)
    }
    private val badgeSource = ActivityBadgeSource { path ->
        asked += path
        null
    }

    private var density by mutableStateOf(2.625f)
    private var fontScale by mutableStateOf(1.0f)
    private var hostWidthDp by mutableStateOf(832f)
    private var hostHeightDp by mutableStateOf(468f)
    private var show by mutableStateOf(true)
    private var reduced = false
    private var backs = 0
    private var installed: ((ControllerAction) -> Boolean)? = null
    private var fence = 0L
    private val authority = DefaultFocusAuthority()
    private val homeKey = FocusKey("home/tile")
    private lateinit var router: InputRouter
    private var clock = 5_000L
    private val frames = mutableListOf<() -> Unit>()
    private val gridState = LazyGridState()

    @Before
    fun setUp() {
        clock = 5_000L
        fence = 0L
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { clock }
        // Where focus is when the card opens: a tile of the screen under it, in the root scope.
        authority.register(homeKey, FocusScope.Root, FocusBounds(0f, 0f, 10f, 10f))
        router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(clock = { clock }),
            padFrameScheduler = PadFrameScheduler { block -> frames += block },
        )
        // The overlay's own wiring: its dispatcher with the page's grid, and the router's A that waits one frame on the page's scope.
        router.handler = { action -> dispatchAchievementCard(action, onScroll = {}, onBack = { backs += 1 }, grid = installed) }
        router.confirmWaitsForPadFrame = { mainPadAWaitsForItsFrame(authority.state.value.scope) }
        router.navMoveProbe = { authority.state.value }
        // The coordinator's fence (MimirAchievementsState.cardFence) advances when the page lets go of its walk: an A still waiting for its frame is dropped then.
        router.confirmFence = { fence }
    }

    @After
    fun tearDown() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun mount(progress: RetroAchievementsProgress = mainProgress(), width: Float = 832f, height: Float = 468f, d: Float = 2.625f, font: Float = 1.0f) {
        hostWidthDp = width
        hostHeightDp = height
        density = d
        fontScale = font
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp), contentAlignment = Alignment.Center) {
                        MainPageSlot(show) {
                            MimirMainAchievementPage(
                                progress = progress,
                                artSnapshot = mainAchievementArtSnapshot(progress, "g77"),
                                artResolver = artResolver,
                                badgeSource = badgeSource,
                                reducedMotion = reduced,
                                authority = authority,
                                onBack = { backs += 1 },
                                // The overlay's own frame: 24 dp around the page, at most 880 dp wide.
                                modifier = Modifier.padding(MimirSpacing.xl).widthIn(max = MainAchievementPageMaxWidth).fillMaxSize(),
                                installGrid = { installed = it },
                                releaseGrid = { grid ->
                                    if (installed === grid) {
                                        installed = null
                                        fence += 1L
                                    }
                                },
                                artContext = EmptyCoroutineContext,
                                gridState = gridState,
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun advance(ms: Long = 300L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun settle() {
        advance()
        advance()
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
        settle()
    }

    private fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN)

    private fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT)

    private fun a() = press(KeyEvent.KEYCODE_BUTTON_A)

    private fun b() = press(KeyEvent.KEYCODE_BUTTON_B)

    private fun bounds(tag: String): Rect? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            ?.let { Rect(it.left / density, it.top / density, it.right / density, it.bottom / density) }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun texts(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun descriptions(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun focusedBadge(): FocusKey? = authority.state.value.takeIf { it.scope == MainAchievementPadScope }?.focused

    /** How many tiles the grid lays out in its first row (the pad's rows are the grid's own). */
    private fun firstRowCount(order: List<RetroAchievementsAchievement>): Int {
        val tops = order.mapNotNull { bounds(achievementPanelBadgeTag(it.id))?.top }
        return tops.count { abs(it - tops.first()) < 1f }
    }

    // ── what the page shows ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Main shows the game's art slot, its count and points, the etched bar and a tile for every badge, earned in colour and locked as lock art`() {
        val progress = mainProgress(count = 12, earned = 4)
        mount(progress)
        compose.waitUntil(5_000L) { asked.size >= 12 }
        val seen = asked.toList()
        // Earned: colour art. Locked: the _lock art. Never the other way round.
        listOf(1, 2, 3, 4).forEach { id -> assertTrue("earned badge $id in colour: $seen", "/Badge/%05d.png".format(id) in seen) }
        listOf(5, 8, 12).forEach { id -> assertTrue("locked badge $id as lock art: $seen", "/Badge/%05d_lock.png".format(id) in seen) }
        assertFalse(seen.any { it == "/Badge/00005.png" || it == "/Badge/00001_lock.png" })
        (1..12).forEach { id -> assertTrue("a tile for badge $id", exists(achievementPanelBadgeTag(id))) }
        compose.waitUntil(5_000L) { artAsked.isNotEmpty() }
        assertEquals("the art behind the page is asked for this game (its hero, else RA's art)", "g77", artAsked.first().gameId)
        assertEquals(77, artAsked.first().raGameId)
        assertTrue("the etched bar", exists(AchievementPanelProgressTag))
        assertTrue("the count and the points: ${texts()}", "4 of 12 achievements · 20 of 60 points" in texts())
        assertTrue("the hardcore mark: ${texts()}", "HC 1" in texts())
        assertTrue("a hint before any selection: ${texts()}", MainAchievementSelectHint in texts())
        assertTrue("the honesty line stays: ${texts()}", com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy.EarnedInEmulator in texts())
    }

    @Test
    fun `a tap on a badge shows its title, description, points and earned date, and a locked one says so`() {
        mount()
        compose.onNodeWithTag(achievementPanelBadgeTag(2), useUnmergedTree = true).performClick()
        settle()
        var all = texts()
        assertTrue("its title: $all", "Badge 2" in all)
        assertTrue("its description: $all", "Do thing 2" in all)
        assertTrue("its points and earned date: $all", "5 points · Earned 2023-05-02 10:00:00" in all)
        compose.onNodeWithTag(achievementPanelBadgeTag(1), useUnmergedTree = true).performClick()
        settle()
        all = texts()
        assertTrue("a hardcore badge says so: $all", "5 points · Earned in hardcore 2023-05-01 12:34:56" in all)
        compose.onNodeWithTag(achievementPanelBadgeTag(6), useUnmergedTree = true).performClick()
        settle()
        all = texts()
        assertTrue("a locked badge says so: $all", "5 points · Locked" in all)
        assertFalse("the earlier badge's line is gone", "5 points · Earned in hardcore 2023-05-01 12:34:56" in all)
        assertEquals("the ring follows the touch", ActivityPadKeys.badge(6), focusedBadge())
    }

    @Test
    fun `the lit badge wears the product ring on Main, a pad move carries it along, and a touch leaves it on the badge that was tapped`() {
        val order = activityBadgeOrder(mainProgress().achievements)
        mount()
        fun ringLit(id: Int): Boolean? =
            compose.onNodeWithTag(activityBadgeRingTag(achievementPanelBadgeTag(id)), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(ActivityBadgeRingLit)
        assertEquals("the pad lands lit on the first badge", true, ringLit(order[0].id))
        assertEquals(false, ringLit(order[1].id))
        right()
        assertEquals("the ring moved with the pad", true, ringLit(order[1].id))
        assertEquals("and left the first badge", false, ringLit(order[0].id))
        // A locked badge, tapped: the pad's ring goes dark and the same ring rests on the tapped badge (full colour above its art, never a plain box).
        compose.onNodeWithTag(achievementPanelBadgeTag(6), useUnmergedTree = true).performClick()
        settle()
        assertEquals("a tap leaves the ring on the tapped locked badge", true, ringLit(6))
        assertEquals(false, ringLit(order[1].id))
    }

    @Test
    fun `the card announces its game, count and points and each badge announces earned or locked`() {
        mount()
        val said = descriptions()
        assertTrue("the card: $said", "Main Game, 4 of 12 achievements, 20 points" in said)
        assertTrue("an earned badge: $said", "Badge 4, earned" in said)
        assertTrue("a locked badge: $said", "Badge 9, locked" in said)
    }

    @Test
    fun `the art is resolved for the library game, then RA's title image, then its icon`() {
        val progress = mainProgress().copy(imageTitlePath = "/Images/000111.png", imageIngamePath = "/Images/000222.png", imageIconPath = "/Images/000333.png")
        val snapshot = mainAchievementArtSnapshot(progress, "g77")
        assertEquals("g77", snapshot.gameId)
        assertEquals("RA's title image first", "/Images/000111.png", snapshot.artPath)
        assertEquals("/Images/000333.png", snapshot.iconPath)
        assertEquals(77, snapshot.raGameId)
        assertEquals("the in-game image when there is no title image", "/Images/000222.png", mainAchievementArtSnapshot(progress.copy(imageTitlePath = null), "g77").artPath)
        assertNull(mainAchievementArtSnapshot(mainProgress(), "g77").artPath)
    }

    // ── the pad ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the pad lands lit on the first badge on Main's own authority, and leaving the page gives focus back where it was`() {
        val order = activityBadgeOrder(mainProgress().achievements)
        assertEquals("the screen under the card holds focus first", homeKey, authority.state.value.focused)
        mount()
        assertEquals("the page's scope is up", MainAchievementPadScope, authority.state.value.scope)
        assertEquals("the first badge is lit", ActivityPadKeys.badge(order.first().id), focusedBadge())
        assertNotNull("the overlay's dispatcher has the page's walk", installed)
        show = false
        settle()
        assertEquals("the scope is gone", FocusScope.Root, authority.state.value.scope)
        assertEquals("and focus is back on the screen under the card", homeKey, authority.state.value.focused)
        assertNull("the dispatcher lets go of the page's walk", installed)
    }

    @Test
    fun `the D-pad walks the grid, A shows the details of the badge under the ring, and B closes the page`() {
        val progress = mainProgress(count = 12, earned = 4)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        val columns = firstRowCount(order)
        assertTrue("the grid has a second row to walk to: $columns columns", columns in 1..11)

        a()
        assertTrue("A on the lit badge shows it: ${texts()}", activityBadgeDetail(order[0]).meta in texts())
        assertTrue(order[0].title in texts())

        right()
        a()
        assertEquals("right moves along the row", ActivityPadKeys.badge(order[1].id), focusedBadge())
        assertTrue("and A shows that badge: ${texts()}", activityBadgeDetail(order[1]).meta in texts())
        assertTrue(order[1].description in texts())

        down()
        assertEquals("down moves a row and keeps the column", ActivityPadKeys.badge(order[columns + 1].id), focusedBadge())
        a()
        assertTrue("a locked badge's details: ${texts()}", activityBadgeDetail(order[columns + 1]).meta in texts())

        assertEquals("nothing has left the page yet", 0, backs)
        b()
        assertEquals("B closes the page, through the overlay's own back", 1, backs)
    }

    @Test
    fun `a hat press then A acts on the badge the hat moved to, however Android orders them`() {
        val order = activityBadgeOrder(mainProgress().achievements)
        mount()
        clock += 400
        val base = clock
        // The Thor hands the A over the moment it arrives and the hat at the next frame, so a RIGHT then A that were pressed in that order arrive as A then RIGHT.
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = base + 100)
        assertFalse("the A waits for the frame's pad input", activityBadgeDetail(order[0]).meta in texts() || activityBadgeDetail(order[1]).meta in texts())
        router.onRawMotionSample(axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f), eventTimeMs = base + 60)
        router.onRawMotionSample(axes = mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f), eventTimeMs = base + 100)
        frame()
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = false, eventTimeMs = base + 140)
        settle()
        assertTrue("the A acted on the badge the hat moved to: ${texts()}", activityBadgeDetail(order[1]).meta in texts())
        assertFalse("and not on the one it was pressed over", activityBadgeDetail(order[0]).meta in texts())
    }

    @Test
    fun `every badge of a large set is reachable by the pad though the lazy grid composes only the rows in view`() {
        val progress = mainProgress(count = 120, earned = 10)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        val columns = firstRowCount(order)
        val far = order[columns * 8]
        assertFalse("a badge eight rows down is not composed yet", exists(achievementPanelBadgeTag(far.id)))
        repeat(8) { down() }
        assertEquals("the pad is on it", ActivityPadKeys.badge(far.id), focusedBadge())
        settle()
        assertTrue("and the grid scrolled to it, so it is composed", exists(achievementPanelBadgeTag(far.id)))
        compose.waitUntil(5_000L) { asked.any { it.contains("%05d".format(far.id)) } }
        a()
        assertTrue("A shows it: ${texts()}", activityBadgeDetail(far).meta in texts())
    }

    // ── a burst of moves ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** One frame of the paused clock, with the state writes made since the last one applied first. */
    private fun frameStep() = advance(16L)

    /** The grid's real viewport (dp), top and bottom: its bounds grown by how far it reaches past them, read off its own measured size. */
    private fun gridViewport(): Pair<Float, Float> {
        val grid = bounds(MainAchievementGridTag)!!
        val reach = (gridState.layoutInfo.viewportSize.height / density - grid.height) / 2f
        return (grid.top - reach) to (grid.bottom + reach)
    }

    /**
     * How far (dp) the tile of badge [id] lies outside the grid's viewport: 0 when it is whole inside; null when the grid has not laid it out (it is not in view at all). Read from
     * the grid's own layout, in its own coordinates: a semantics node's bounds are clipped to the viewport, so a tile that is half out of it reports only the half that shows.
     */
    private fun overhang(id: Int): Float? {
        val info = gridState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return null
        val top = item.offset.y.toFloat()
        val bottom = top + item.size.height
        return maxOf(0f, info.viewportStartOffset - top, bottom - info.viewportEndOffset) / density
    }

    /**
     *  A held or fast-repeated down move, one every four frames (about 64 ms,
     * far faster than a row's scroll settles, on a paused clock): the grid's scroll position, sampled every frame, only ever advances (no step back, no lurch of a row or more in
     * one frame: that was the lit row leaping to the top when it outran the glide), the pad ends on the row ten down, and the grid stops exactly where that badge is whole with its
     * ring's air around it (it did not run past it). No frame steps more than 80 px. And the lit TILE is back inside the viewport before the next press arrives (the fourth frame after each
     * move): composed, placed and whole inside the grid's cut, at every move of the burst.
     */
    @Test
    fun `a burst of down moves glides the grid forward row by row, never back, never in a lurch and never past the lit row`() {
        val progress = mainProgress(count = 200, earned = 10)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        val columns = firstRowCount(order)
        val pitchPx = (bounds(achievementPanelBadgeTag(order[columns].id))!!.top - bounds(achievementPanelBadgeTag(order.first().id))!!.top) * density
        fun scrollPx(): Float = (gridState.firstVisibleItemIndex / columns) * pitchPx + gridState.firstVisibleItemScrollOffset
        val samples = mutableListOf(scrollPx())
        val moves = 10
        // The worst overhang (dp) of the lit tile past the viewport, by the frame after a move it was seen on (1 = the frame right after the move); not composed counts as a full row.
        val worst = FloatArray(4)
        repeat(moves) { move ->
            assertTrue("the page takes the move", installed!!.invoke(ControllerAction.NAV_DOWN))
            val litId = order[(move + 1) * columns].id
            repeat(4) { frame ->
                frameStep()
                samples += scrollPx()
                val out = overhang(litId)
                worst[frame] = maxOf(worst[frame], out ?: (pitchPx / density))
                if (frame == 3) assertTrue("move ${move + 1}: the lit tile is whole inside the viewport before the next press arrives (overhang $out dp)", out != null && out <= 1.5f)
            }
        }
        repeat(40) {
            frameStep()
            samples += scrollPx()
        }
        compose.waitForIdle()
        println("MAIN-BURST worst overhang (dp) of the lit tile on frames 1..4 after a move: ${worst.toList()}")
        val lit = order[moves * columns]
        assertEquals("the pad is ten rows down", ActivityPadKeys.badge(lit.id), focusedBadge())
        val steps = samples.zipWithNext { before, after -> after - before }
        println("MAIN-BURST pitch=$pitchPx columns=$columns start=${samples.first()} end=${samples.last()} biggestStep=${steps.max()} smallestStep=${steps.min()}")
        assertTrue("the grid scrolled to follow the pad: ${samples.first()} -> ${samples.last()}", samples.last() > samples.first() + 4 * pitchPx)
        assertTrue("it never steps back: smallest step ${steps.min()} px", steps.min() >= -1.5f)
        assertTrue("it never lurches in one frame: biggest step ${steps.max()} px vs a row of $pitchPx px", steps.max() < pitchPx)
        assertTrue("and no single frame steps more than 80 px (a one-frame catch-up was 123): biggest step ${steps.max()} px", steps.max() <= 80f)
        assertTrue("it never runs past where it ends: highest ${samples.max()} px vs last ${samples.last()} px", samples.max() <= samples.last() + 1.5f)
        val tile = bounds(achievementPanelBadgeTag(lit.id))!!
        val (viewportTop, viewportBottom) = gridViewport()
        val air = ActivityBadgePaintReach.value
        assertTrue("the lit badge shows with its ring's air above: ${tile.top - air} vs $viewportTop", tile.top - air >= viewportTop - 1.5f)
        assertTrue("and below: ${tile.bottom + air} vs $viewportBottom", tile.bottom + air <= viewportBottom + 1.5f)
        assertEquals("and the grid stopped when it did (the lit row is flush with the bottom air, not past it)", viewportBottom, tile.bottom + air, 2.5f)
    }

    /** Going back up the same way: the walk up is as smooth, and the top row's ring is whole when it arrives. */
    @Test
    fun `a burst of up moves glides the grid back, never forward and never in a lurch, and the top row arrives whole`() {
        val progress = mainProgress(count = 200, earned = 10)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        val columns = firstRowCount(order)
        val pitchPx = (bounds(achievementPanelBadgeTag(order[columns].id))!!.top - bounds(achievementPanelBadgeTag(order.first().id))!!.top) * density
        fun scrollPx(): Float = (gridState.firstVisibleItemIndex / columns) * pitchPx + gridState.firstVisibleItemScrollOffset
        repeat(10) {
            installed!!.invoke(ControllerAction.NAV_DOWN)
            repeat(4) { frameStep() }
        }
        repeat(40) { frameStep() }
        assertTrue("it went down first", scrollPx() > 4 * pitchPx)
        val samples = mutableListOf(scrollPx())
        repeat(10) { move ->
            assertTrue("the page takes the move", installed!!.invoke(ControllerAction.NAV_UP))
            val litId = order[(9 - move) * columns].id
            repeat(4) { frame ->
                frameStep()
                samples += scrollPx()
                val out = overhang(litId)
                if (frame == 3) assertTrue("move ${move + 1} up: the lit tile is whole inside the viewport before the next press arrives (overhang $out dp)", out != null && out <= 1.5f)
            }
        }
        repeat(40) {
            frameStep()
            samples += scrollPx()
        }
        compose.waitForIdle()
        assertEquals("the pad is back on the first badge", ActivityPadKeys.badge(order.first().id), focusedBadge())
        val steps = samples.zipWithNext { before, after -> after - before }
        assertTrue("it never steps forward: biggest step ${steps.max()} px", steps.max() <= 1.5f)
        assertTrue("it never lurches in one frame: smallest step ${steps.min()} px vs a row of $pitchPx px", steps.min() > -pitchPx)
        assertTrue("and no single frame steps more than 80 px: smallest step ${steps.min()} px", steps.min() >= -80f)
        assertEquals("the first row is back at the start of the list", 0f, samples.last(), 1.5f)
    }

    /**
     * Review round: after a glide settles the lit row sits one ring-air inside the edge, so ONE press reveals the next row a whole row-pitch away - and the one-frame catch-up moved
     * it about three quarters of a row (123 px) in a single frame, the reported jump, now on a single press. One press after the glide has settled: no frame steps more than 80 px,
     * the grid moves by exactly the one row it was asked to, and the lit tile is back inside within five frames.
     */
    @Test
    fun `one down press after the glide has settled moves the grid in steps of 80 px or less, by one row, and has the lit tile inside within five frames`() {
        val progress = mainProgress(count = 200, earned = 10)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        val columns = firstRowCount(order)
        val pitchPx = (bounds(achievementPanelBadgeTag(order[columns].id))!!.top - bounds(achievementPanelBadgeTag(order.first().id))!!.top) * density
        fun scrollPx(): Float = (gridState.firstVisibleItemIndex / columns) * pitchPx + gridState.firstVisibleItemScrollOffset
        // Slowly down to the seventh row, so that every glide has settled before the next press.
        repeat(6) {
            installed!!.invoke(ControllerAction.NAV_DOWN)
            repeat(40) { frameStep() }
        }
        val settled = scrollPx()
        assertTrue("the grid has scrolled to follow: $settled", settled > 2 * pitchPx)
        installed!!.invoke(ControllerAction.NAV_DOWN)
        val lit = order[7 * columns]
        val samples = mutableListOf(settled)
        val overhangs = mutableListOf<Float?>()
        repeat(40) {
            frameStep()
            samples += scrollPx()
            overhangs += overhang(lit.id)
        }
        val steps = samples.zipWithNext { before, after -> after - before }
        println("MAIN-SINGLE settled=$settled end=${samples.last()} pitch=$pitchPx biggestStep=${steps.max()} smallestStep=${steps.min()} overhangByFrame=${overhangs.take(8)}")
        assertEquals("the pad is a row further", ActivityPadKeys.badge(lit.id), focusedBadge())
        assertTrue("no frame steps more than 80 px: biggest ${steps.max()} px", steps.max() <= 80f)
        assertTrue("and none steps back: smallest ${steps.min()} px", steps.min() >= -1.5f)
        assertTrue("it moved: biggest ${steps.max()} px", steps.max() > 10f)
        assertEquals("by the one row the press asked for", pitchPx, samples.last() - settled, 3f)
        assertTrue("the lit tile is whole inside the viewport within five frames: ${overhangs.take(5)}", overhangs.take(5).any { it != null && it <= 1.5f })
    }

    /** A size change that leaves the lit badge past the viewport's edge (the font grows the header and the grid loses height) is followed once the page has settled. */
    @Test
    fun `a change of the grid's height that leaves the lit badge past the edge is followed once the page has settled`() {
        val progress = mainProgress(count = 200, earned = 10)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        val columns = firstRowCount(order)
        repeat(8) {
            installed!!.invoke(ControllerAction.NAV_DOWN)
            repeat(4) { frameStep() }
        }
        repeat(40) { frameStep() }
        val lit = order[8 * columns]
        assertEquals("the pad is eight rows down", ActivityPadKeys.badge(lit.id), focusedBadge())
        val before = gridViewport()
        assertTrue("the lit badge is whole inside the viewport before: ${overhang(lit.id)}", (overhang(lit.id) ?: 999f) <= 1.5f)
        fontScale = 2.0f
        repeat(60) { frameStep() }
        val after = gridViewport()
        println("MAIN-RESIZE viewport before=$before after=$after overhang=${overhang(lit.id)}")
        assertTrue("the larger font took height from the grid: $before -> $after", (after.second - after.first) < (before.second - before.first) - 10f)
        assertTrue("and the lit badge is whole inside the smaller viewport: ${overhang(lit.id)}", (overhang(lit.id) ?: 999f) <= 1.5f)
    }

    /**
     * A move the pad makes while a finger holds the grid cannot scroll it (the finger has the scroll), so the lit badge is left past the edge; once the finger lets go it is brought
     * into view. (A glide the finger cut short is not resumed: only a new move of the pad follows again.)
     */
    @Test
    fun `a pad move made while a finger holds the grid is followed once the finger lets go`() {
        val progress = mainProgress(count = 200, earned = 10)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        val columns = firstRowCount(order)
        compose.onNodeWithTag(MainAchievementGridTag, useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -90f))
        }
        frameStep()
        // The first press after a touch only lights the ring again; the six after it move it six rows.
        repeat(7) {
            installed!!.invoke(ControllerAction.NAV_DOWN)
            repeat(4) { frameStep() }
        }
        val lit = order[6 * columns]
        assertEquals("the pad is six rows down", ActivityPadKeys.badge(lit.id), focusedBadge())
        println("MAIN-FINGER with the finger down: overhang=${overhang(lit.id)} scrolling=${gridState.isScrollInProgress}")
        // A slow release: no fling.
        compose.onNodeWithTag(MainAchievementGridTag, useUnmergedTree = true).performTouchInput {
            advanceEventTime(300L)
            up()
        }
        repeat(60) { frameStep() }
        println("MAIN-FINGER after the release: overhang=${overhang(lit.id)} scrolling=${gridState.isScrollInProgress}")
        assertTrue("the lit badge is brought into view once the finger lets go: ${overhang(lit.id)}", (overhang(lit.id) ?: 999f) <= 1.5f)
    }

    /**
     *  The header's text ends above the bar (a column cannot do otherwise, and it is pinned here at both font scales),
     * and the grid no longer cuts its rows flush with the bar's bottom edge: they are cut at least [MimirSpacing.sm] (8 dp) below it, measured off the grid's own laid-out viewport.
     * A pad move to the first row's neighbour at the end of the walk keeps the lit ring inside that cut at both scales.
     */
    @Test
    fun `the header's text ends above the bar and the grid cuts its rows with air below the bar at font 1_0 and 2_0`() {
        val progress = mainProgress(count = 60, earned = 6, title = "A Long Game Title That Runs Past One Line On Any Handheld Screen At Any Font Size")
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        listOf(1.0f, 2.0f).forEach { font ->
            fontScale = font
            settle()
            val header = bounds(MainAchievementHeaderTag)!!
            val bar = bounds(AchievementPanelProgressTag)!!
            val (viewportTop, _) = gridViewport()
            println("MAIN-CLIP font=$font header=$header bar=$bar viewportTop=$viewportTop")
            assertTrue("font $font: the header's text ends above the bar: ${header.bottom} vs ${bar.top}", header.bottom <= bar.top + 0.75f)
            assertTrue(
                "font $font: the grid cuts its rows ${MimirSpacing.sm.value} dp or more below the bar: cut at $viewportTop, bar ends ${bar.bottom}",
                viewportTop - bar.bottom >= MimirSpacing.sm.value - 0.75f,
            )
            // Walk to the third row and back: the lit badge's ring stays whole inside the cut at this scale.
            val columns = firstRowCount(order)
            repeat(3) {
                installed!!.invoke(ControllerAction.NAV_DOWN)
                repeat(60) { frameStep() }
            }
            val tile = bounds(achievementPanelBadgeTag(order[3 * columns].id))!!
            val (cutTop, cutBottom) = gridViewport()
            assertEquals("font $font: the pad is on the third row", ActivityPadKeys.badge(order[3 * columns].id), focusedBadge())
            assertTrue("font $font: its ring is inside the cut above: ${tile.top - ActivityBadgePaintReach.value} vs $cutTop", tile.top - ActivityBadgePaintReach.value >= cutTop - 1.5f)
            assertTrue("font $font: and below: ${tile.bottom + ActivityBadgePaintReach.value} vs $cutBottom", tile.bottom + ActivityBadgePaintReach.value <= cutBottom + 1.5f)
            repeat(3) {
                installed!!.invoke(ControllerAction.NAV_UP)
                repeat(60) { frameStep() }
            }
        }
    }

    // ── the detail's scroll ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun detailRange() = compose.onNodeWithTag(AchievementPanelDetailTag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test
    fun `L1 and R1 scroll a long detail by a bounded step to its end and back`() {
        val long = (1..60).joinToString(" ") { "Sentence $it of a very long description that keeps going." }
        val base = mainProgress(count = 12, earned = 4)
        mount(base.copy(achievements = base.achievements.map { if (it.id == 2 || it.id == 3) it.copy(description = long) else it }))
        compose.onNodeWithTag(achievementPanelBadgeTag(2), useUnmergedTree = true).performClick()
        settle()
        assertTrue("the description is shown: ${texts().size}", long in texts())
        val end = detailRange().maxValue()
        assertTrue("the description overflows its pane, so there is something to scroll: $end", end > 0f)
        assertEquals("it starts at its top", 0f, detailRange().value(), 0.5f)
        var presses = 0
        while (detailRange().value() < end - 0.5f && presses < 40) {
            val before = detailRange().value()
            press(KeyEvent.KEYCODE_BUTTON_R1)
            presses += 1
            assertTrue("R1 moves it forward: $before -> ${detailRange().value()}", detailRange().value() > before)
        }
        assertEquals("R1 reaches the end of the description", end, detailRange().value(), 0.5f)
        assertTrue("in bounded steps, not one jump: $presses presses for $end px", presses in 2..39)
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("and stays at the end", end, detailRange().value(), 0.5f)
        repeat(presses + 1) { press(KeyEvent.KEYCODE_BUTTON_L1) }
        assertEquals("L1 brings it back to the top", 0f, detailRange().value(), 0.5f)
        assertEquals("the ring stayed on the badge: the shoulders never moved it", ActivityPadKeys.badge(2), focusedBadge())
        // Another badge's detail starts at its top.
        press(KeyEvent.KEYCODE_BUTTON_R1)
        press(KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithTag(achievementPanelBadgeTag(3), useUnmergedTree = true).performClick()
        settle()
        assertEquals("a new badge's detail is at its top", 0f, detailRange().value(), 0.5f)
    }

    // ── the page's life ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an A queued for its frame is dropped when the page goes away before the frame comes`() {
        mount()
        val under = mutableListOf<ControllerAction>()
        // Whatever answers the router now is the screen that comes back underneath.
        router.handler = { action ->
            under += action
            true
        }
        clock += 400
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = clock)
        assertEquals("the A waits for its frame", 1, frames.size)
        // An OS Back lands before the frame: the page leaves.
        show = false
        settle()
        assertNull(installed)
        frame()
        assertEquals("the A never reaches the screen underneath", emptyList<ControllerAction>(), under)
    }

    @Test
    fun `an A queued for its frame goes through when the page is still there`() {
        mount()
        val seen = mutableListOf<ControllerAction>()
        router.handler = { action ->
            seen += action
            true
        }
        clock += 400
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = clock)
        frame()
        assertEquals(listOf(ControllerAction.CONFIRM), seen)
    }

    // ── the numbers are never cut ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun statsLayout(): TextLayoutResult {
        val node = compose.onAllNodes(hasText("500 of 500 achievements", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first()
        val out = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.first()
    }

    private fun checkStatsWhole(label: String) {
        assertTrue("$label: the count and the points are all there: ${texts()}", "500 of 500 achievements · 9999 of 9999 points" in texts())
        assertTrue("$label: the hardcore mark is there: ${texts()}", "HC 500" in texts())
        val layout = statsLayout()
        // What the layout says, so a failure names its cause: the node's size, the paragraph's, and the constraints the text was given.
        val said = "size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} constraints=${layout.layoutInput.constraints} lines=${layout.lineCount}"
        assertFalse("$label: the stats are cut vertically: $said", layout.didOverflowHeight)
        assertFalse("$label: the stats are cut: $said", layout.hasVisualOverflow)
        assertTrue("$label: no line of the stats is ellipsized: $said", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        assertEquals("$label: every character of the stats is shown: $said", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        val page = bounds(MainAchievementPageTag)!!
        val header = bounds(MainAchievementHeaderTag)!!
        assertTrue("$label: the header is inside the page: $header vs $page", header.left >= page.left - 0.75f && header.right <= page.right + 0.75f && header.bottom <= page.bottom + 0.75f)
    }

    @Test
    fun `a 500 badge set with 9999 points and hardcore keeps every number whole on Thor Main at font 2_0`() {
        val progress = hugeProgress()
        mount(progress, 832f, 468f, 2.625f, 2.0f)
        checkStatsWhole("Thor Main font 2.0")
        checkGeometry("Thor Main huge set font 2.0", 2.625f, activityBadgeOrder(progress.achievements))
    }

    @Test
    fun `a 500 badge set keeps every number whole at the 700 x 350 floor at font 2_0`() {
        mount(hugeProgress(), 700f, 350f, 1.75f, 2.0f)
        checkStatsWhole("floor font 2.0")
    }

    @Test
    fun `a 500 badge set keeps every number whole on a compact handheld at font 2_0`() {
        mount(hugeProgress(), 640f, 360f, 2.0f, 2.0f)
        checkStatsWhole("compact font 2.0")
    }

    @Test
    fun `a 500 badge set wraps its numbers rather than cut them on a phone at font 2_0`() {
        mount(hugeProgress(), 360f, 780f, 2.75f, 2.0f)
        checkStatsWhole("phone font 2.0")
        assertTrue("narrow, the numbers take more than one line", statsLayout().lineCount >= 2)
    }

    // ── the bar ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun shown(): Float = compose.onNodeWithTag(AchievementPanelProgressTag, useUnmergedTree = true).fetchSemanticsNode().config[MimirEtchedProgressShownSemantics]

    @Test
    fun `under reduced motion the fill is there at once`() {
        reduced = true
        mount(mainProgress(count = 12, earned = 4))
        assertEquals(4f / 12f, shown(), 0.001f)
    }

    @Test
    fun `the fill arrives once and then stands still`() {
        mount(mainProgress(count = 12, earned = 4))
        compose.mainClock.advanceTimeBy(MimirEtchedProgressArrivalMs + 200L)
        assertEquals(4f / 12f, shown(), 0.001f)
        compose.mainClock.advanceTimeBy(2_000L)
        assertEquals("static afterwards", 4f / 12f, shown(), 0.0001f)
    }

    // ── the geometry ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun checkGeometry(label: String, d: Float, order: List<RetroAchievementsAchievement>) {
        val wDp = hostWidthDp
        val hDp = hostHeightDp
        val page = bounds(MainAchievementPageTag)!!
        val header = bounds(MainAchievementHeaderTag)!!
        val bar = bounds(AchievementPanelProgressTag)!!
        val grid = bounds(MainAchievementGridTag)!!
        val detail = bounds(MainAchievementDetailPaneTag)!!
        val tile = bounds(achievementPanelBadgeTag(order.first().id))!!
        val eps = 0.75f
        assertEquals("$label: the page fills the overlay's frame (width)", min(wDp - 48f, 880f), page.width, 1f)
        assertEquals("$label: the page fills the overlay's frame (height)", hDp - 48f, page.height, 1f)
        fun inside(inner: Rect, name: String) = assertTrue(
            "$label: $name inside the page: $inner vs $page",
            inner.left >= page.left - eps && inner.top >= page.top - eps && inner.right <= page.right + eps && inner.bottom <= page.bottom + eps,
        )
        inside(header, "header")
        inside(bar, "bar")
        inside(grid, "grid")
        inside(detail, "detail")
        inside(tile, "first tile")
        assertTrue("$label: the header is above the grid: $header vs $grid", header.bottom <= grid.top + eps)
        assertTrue("$label: the bar is above the grid: $bar vs $grid", bar.bottom <= grid.top + eps)
        assertTrue("$label: the grid shows at least a whole row of tiles: ${grid.height} vs ${tile.height}", grid.height + eps >= tile.height)
        assertTrue("$label: the first tile is inside the grid: $tile vs $grid", tile.left >= grid.left - eps && tile.top >= grid.top - eps && tile.right <= grid.right + eps && tile.bottom <= grid.bottom + eps)
        assertTrue("$label: a tile is a 48 dp target at least: ${tile.width} x ${tile.height}", tile.width + eps >= 48f && tile.height + eps >= 48f)
        val scale = ceil(48f * d / 64f)
        assertEquals("$label: a whole-number scale of the 64 px art", 64f * scale, tile.width * d, 1f)
        assertEquals("$label: square", tile.width, tile.height, 0.5f)
        assertTrue("$label: the detail has room: ${detail.width} x ${detail.height}", detail.width > 40f && detail.height > 40f)
        val wide = page.width - 32f >= MainAchievementWideBreakpoint.value
        if (wide) {
            assertTrue("$label: wide, the detail is beside the grid: $detail vs $grid", detail.left >= grid.right - eps)
        } else {
            assertTrue("$label: narrow, the detail is under the grid: $detail vs $grid", detail.top >= grid.bottom - eps)
        }
    }

    private fun geometryAt(label: String, width: Float, height: Float, d: Float) {
        val order = activityBadgeOrder(mainProgress(count = 24, earned = 6).achievements)
        mount(mainProgress(count = 24, earned = 6, title = "A Long Game Title That Runs Past One Line On Any Handheld Screen At Any Font Size"), width, height, d, 1.0f)
        listOf(1.0f, 1.3f, 2.0f).forEach { font ->
            fontScale = font
            settle()
            checkGeometry("$label font $font", d, order)
        }
    }

    /**
     * Ledger leftover (10-07 polish lane): the ring on the first row's badges is whole at the peak of its arrival flare ([ActivityBadgePaintReach], 20 dp) and clears its
     * neighbours. Measured as the tile's box grown by the reach: it stays below the progress bar, inside the grid's own viewport clip (which was cutting up to 7.5 dp of it),
     * inside the card, and short of the detail pane (3.5 dp of it reached in). Thor Main, at font 1.0 and 2.0.
     */
    @Test
    fun `the ring at the peak of its flare is whole on the first row and reaches neither the bar, the card's edge nor the detail pane`() {
        val progress = mainProgress(count = 24, earned = 6)
        val order = activityBadgeOrder(progress.achievements)
        mount(progress)
        listOf(1.0f, 2.0f).forEach { font ->
            fontScale = font
            settle()
            val reach = ActivityBadgePaintReach.value
            val eps = 0.75f
            val page = bounds(MainAchievementPageTag)!!
            val bar = bounds(AchievementPanelProgressTag)!!
            val grid = bounds(MainAchievementGridTag)!!
            val detail = bounds(MainAchievementDetailPaneTag)!!
            val columns = firstRowCount(order)
            val row = order.take(columns).map { bounds(achievementPanelBadgeTag(it.id))!! }
            val first = row.first()
            println("MAIN-FLARE font=$font reach=$reach bar=$bar grid=$grid detail=$detail page=$page firstRow=$row")
            assertTrue("font $font: the first row's ring clears the progress bar: top ${first.top - reach} vs bar bottom ${bar.bottom}", first.top - reach >= bar.bottom - eps)
            assertTrue(
                "font $font: and is not cut by the grid's viewport (it reaches ${MainAchievementGridRingRoom.value} dp past its bounds): ${first.top - reach} vs ${grid.top - MainAchievementGridRingRoom.value}",
                first.top - reach >= grid.top - MainAchievementGridRingRoom.value - eps,
            )
            assertTrue("font $font: the first column's ring stays inside the card: ${row.minOf { it.left } - reach} vs ${page.left}", row.minOf { it.left } - reach >= page.left - eps)
            assertTrue("font $font: the last column's ring stops short of the detail pane: ${row.maxOf { it.right } + reach} vs ${detail.left}", row.maxOf { it.right } + reach <= detail.left + eps)
        }
    }

    @Test
    fun `the grid keeps the whole ring's reach as its padding, above and below and to each side`() {
        assertEquals(ActivityBadgePaintReach.value, MainAchievementGridPaintRoom.value, 0f)
        assertTrue("the vertical padding holds the flare past the gap the grid reaches into", MainAchievementGridPaintRoom.value - MainAchievementGridRingRoom.value >= ActivityBadgePaintReach.value - MimirSpacing.md.value)
        assertEquals("across the grid the flare needs what the 16 dp margin to the card and the pane does not give", ActivityBadgePaintReach.value - MimirSpacing.lg.value, MainAchievementGridSideRoom.value, 0f)
    }

    @Test
    fun `Thor Main at 832 x 468 dp has no clipping at font 1_0, 1_3 and 2_0`() = geometryAt("Thor Main", 832f, 468f, 2.625f)

    @Test
    fun `a compact handheld at 640 x 360 dp has no clipping at font 1_0, 1_3 and 2_0`() = geometryAt("compact", 640f, 360f, 2.0f)

    @Test
    fun `the 700 x 350 floor has no clipping at font 1_0, 1_3 and 2_0`() = geometryAt("floor", 700f, 350f, 1.75f)

    @Test
    fun `a phone at 360 x 780 dp is one column with no clipping at font 1_0, 1_3 and 2_0`() = geometryAt("phone", 360f, 780f, 2.75f)
}

/**
 * The rest of Main's wiring, as rules and source pins: the overlay's dispatcher gives the page's walk the pad (and keeps B and the OS Back its own), a ledger frame without a
 * page still scrolls with up and down, Main's router holds an A for its frame on the page's scope as it does for the keyboard's, and the page keeps the repo's focus, colour and
 * logging rules.
 */
class MimirMainAchievementWiringTest {

    private fun source(name: String): String =
        (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()

    @Test
    fun `with the page mounted the dispatcher gives it the pad and keeps back for the overlay`() {
        val walked = mutableListOf<ControllerAction>()
        var backs = 0
        var scrolls = 0
        val grid: (ControllerAction) -> Boolean = { walked += it; true }
        listOf(ControllerAction.NAV_UP, ControllerAction.NAV_DOWN, ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT, ControllerAction.CONFIRM, ControllerAction.PAGE_LEFT).forEach {
            assertTrue(dispatchAchievementCard(it, onScroll = { scrolls += 1 }, onBack = { backs += 1 }, grid = grid))
        }
        assertEquals(listOf(ControllerAction.NAV_UP, ControllerAction.NAV_DOWN, ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT, ControllerAction.CONFIRM, ControllerAction.PAGE_LEFT), walked)
        assertEquals("up and down walk the grid, they do not scroll the ledger", 0, scrolls)
        assertEquals(0, backs)
        assertTrue(dispatchAchievementCard(ControllerAction.CANCEL, onScroll = {}, onBack = { backs += 1 }, grid = grid))
        assertTrue(dispatchAchievementCard(ControllerAction.SYSTEM_BACK, onScroll = {}, onBack = { backs += 1 }, grid = grid))
        assertEquals("B and the OS Back close the card, and never reach the grid", 2, backs)
        assertEquals(6, walked.size)
    }

    @Test
    fun `without a page the ledger frame still scrolls with up and down and B closes it`() {
        var up = 0
        var down = 0
        var backs = 0
        dispatchAchievementCard(ControllerAction.NAV_UP, onScroll = { if (it == MimirAchievementScrollDirection.UP) up += 1 }, onBack = { backs += 1 })
        dispatchAchievementCard(ControllerAction.NAV_DOWN, onScroll = { if (it == MimirAchievementScrollDirection.DOWN) down += 1 }, onBack = { backs += 1 })
        dispatchAchievementCard(ControllerAction.CANCEL, onScroll = {}, onBack = { backs += 1 })
        assertEquals(1, up)
        assertEquals(1, down)
        assertEquals(1, backs)
    }

    @Test
    fun `an A waits for its frame on the keyboard's scope and on the page's, and nowhere else`() {
        assertTrue(mainPadAWaitsForItsFrame(MainAchievementPadScope))
        assertTrue(mainPadAWaitsForItsFrame(com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope))
        assertFalse(mainPadAWaitsForItsFrame(FocusScope.Root))
        assertFalse(mainPadAWaitsForItsFrame(ActivityPadScope))
        assertTrue("Main's router uses it", source("MimirAppCoordinator.kt").contains("mainPadAWaitsForItsFrame(currentBindings.value.core.focusAuthority.state.value.scope)"))
        assertTrue("and fences the held A to the card's life", source("MimirAppCoordinator.kt").contains("inputRouter.confirmFence = { currentBindings.value.achievements.state.cardFence }"))
    }

    @Test
    fun `an A held for its frame is dropped when the life it was pressed in moved, and goes through when it did not`() {
        var clock = 1_000L
        val frames = mutableListOf<() -> Unit>()
        val seen = mutableListOf<ControllerAction>()
        var life = 7L
        val router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(clock = { clock }),
            padFrameScheduler = PadFrameScheduler { block -> frames += block },
        )
        router.handler = { action ->
            seen += action
            true
        }
        router.confirmWaitsForPadFrame = { true }
        router.confirmFence = { life }
        clock = 1_100L
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = clock)
        assertEquals(1, frames.size)
        life += 1L
        frames.toList().forEach { it() }
        frames.clear()
        assertEquals("an A pressed in a life that has ended acts on nothing", emptyList<ControllerAction>(), seen)
        clock = 1_140L
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = false, eventTimeMs = clock)
        clock = 1_600L
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = clock)
        frames.toList().forEach { it() }
        assertEquals("the next A, in the life it was pressed in, goes through", listOf(ControllerAction.CONFIRM), seen)
    }

    @Test
    fun `the overlay shows the page for a loaded set from the lane's own answer, and the dispatcher gets the page's walk`() {
        val host = source("MimirAppHost.kt")
        assertTrue("the page is chosen by the lane's own answer, through the one swap (RC-33)", host.contains("AchievementCardPageSwap(\n            loaded = achievements.cardProgress(),"))
        assertTrue(host.contains("MimirMainAchievementCard("))
        assertTrue("the ledger frame is every other state's", host.contains("MimirAchievementCard(\n                    state = achievements.cardState(),"))
        assertTrue("the shared authority is Main's one", host.contains("authority = bindings.core.focusAuthority,\n                    reducedMotion = reducedMotion,"))
        assertTrue(source("MimirOverlayCoordinator.kt").contains("grid = achievements.state.cardGrid,"))
        val lane = source("MimirAchievementsCoordinator.kt")
        assertTrue("the probe's loaded answer seeds the shared cache", lane.contains("cardDetails.seed(it.progress, epoch)"))
    }

    @Test
    fun `the page uses the Bottom's shared pieces and the repo's focus, colour and logging rules`() {
        val page = source("MimirMainAchievementPage.kt")
        listOf("ActivityAchievementArt(", "MimirEtchedProgressBar(", "ActivityAchievementHeader(", "ActivityBadgeDetailBlock(", "ActivityBadge(", "StoreActivityBadgeSource(", "ActivityHubPad(").forEach {
            assertTrue("the page reuses $it", page.contains(it))
        }
        assertTrue("the shoulders scroll the detail by the ledger's bounded step", page.contains("mimirAchievementScrollStep(") && page.contains("override fun page(delta: Int): Boolean = onPage(delta)"))
        assertTrue("the numbers are never cut", page.contains("statsMaxLines = Int.MAX_VALUE"))
        assertTrue("one authority: the page pushes and discards its scope on Main's", page.contains("authority.pushScope(MainAchievementPadScope)") && page.contains("authority.discardScope(MainAchievementPadScope)"))
        listOf("MimirMainAchievementPage.kt", "CompanionActivityAchievementPanel.kt").forEach { name ->
            val text = source(name)
            assertFalse("$name never uses Compose focus", text.contains("focusable()") || text.contains("focusTarget()"))
            assertFalse("$name never names a Compose focus requester", text.contains("FocusRequester"))
            assertFalse("$name has no hex colour literal", Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(text))
            assertFalse("$name logs nothing", text.contains("Log."))
        }
    }
}
