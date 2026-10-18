package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * tapping "+" on the Bottom lattice opens the rename plate (Mimir's keyboard) at once, so a new Tome gets its name as on Main; dismissing it keeps
 * "Book". This composes the REAL [CompanionSurface] at the Thor Bottom's size and taps the real "+" cell: the host's `onCreateFolder` makes the Tome (the tile lands in
 * the payload, as the app's does), the plate is up on it, and an outside tap commits the Tome's own label.
 *
 * It also pins W2a's 400 ms create debounce on the REAL tap path, on a clock the test owns (the surface's `monotonicUptimeMillis`, which the debounce reads):
 * two taps 100 ms apart make one Tome, two taps 500 ms apart make two. The taps of the debounce cases are dispatched without a frame between them (so the
 * plate the first tap raises has not yet covered the "+"), which is what two quick taps on a device are to the surface; the flow case below lets the frames run
 * and taps 500 ms later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h445dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionCreateTomeRenameTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val widthDp = 1240f / density369
    private val heightDp = 1025f / density369

    private val platforms = (1..3).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private var payload by mutableStateOf(
        CompanionPayload(
            mode = CompanionMode.HOME,
            statsLabel = "3 tiles",
            homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = platforms)),
        ),
    )

    private val created = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private var clockMs = 0L
    private var closable = false
    private var dragging = false
    private var closeRequest by mutableStateOf<Long?>(null)

    /** When true the host's create returns the id at once but the tile reaches the payload only when [landTome] says so (the app's push is asynchronous). */
    private var deferLanding = false
    private val landing = mutableListOf<CompanionHomeTile>()

    private fun tileCount(): Int = payload.homeLanes.first().tiles.size

    /** The host's create: a new empty Tome named "Book" lands at the tail and its id comes back, as the app's does. */
    private fun createTome(): String {
        val id = "tome.${created.size + 1}"
        created += id
        val tile = CompanionHomeTile(id, CompanionHomeTileKind.FOLDER, "Book")
        if (deferLanding) landing += tile else addTile(tile)
        return id
    }

    private fun addTile(tile: CompanionHomeTile) {
        val lane = payload.homeLanes.first()
        payload = payload.copy(homeLanes = listOf(lane.copy(tiles = lane.tiles + tile)))
    }

    private fun landTome() {
        landing.forEach(::addTile)
        landing.clear()
    }

    /** A lane of [count] platforms, and optionally an existing Tome holding one child (a Tome the player can open). */
    private fun resetPayload(count: Int, withFolder: Boolean = false) {
        val ps = (1..count).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
        val folder = CompanionHomeTile("f1", CompanionHomeTileKind.FOLDER, "Shelf Tome", children = listOf(CompanionHomeTile("c1", CompanionHomeTileKind.PLATFORM, "Child one")))
        payload = CompanionPayload(
            mode = CompanionMode.HOME,
            statsLabel = "$count tiles",
            homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = if (withFolder) ps + folder else ps)),
        )
    }

    private fun mount(restoredPage: Int = 0, clock: () -> Long) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    CompanionSurface(
                        payload = payload,
                        cursorId = "p1",
                        restoredPage = restoredPage,
                        onAppTap = { _, _ -> },
                        onWorkspaceDrag = { _: Float -> 0f },
                        onWorkspaceDragEnd = { _: Float -> false },
                        onWorkspaceDragCancel = {},
                        onCreateFolder = { createTome() },
                        onRenameFolder = { id, name -> renamed += id to name },
                        showActivitySwipeHint = false,
                        onDraggingChanged = { dragging = it },
                        onBookOpenChanged = { closable = it },
                        closeBookRequest = closeRequest,
                        monotonicUptimeMillis = clock,
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        frames(2_000L)
    }

    private fun frames(ms: Long) {
        // The pointer coroutines write state off the frame clock: publish those writes BEFORE the frames run, or nothing recomposes (paused-clock note).
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun plateShowing(): Boolean = compose.onAllNodes(hasTestTag(CompanionRenamePlateTag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tapPlus() {
        compose.onNodeWithTag(CompanionCreateTomeFaceTag, useUnmergedTree = true).performClick()
    }

    /** Where a lattice tile's centre is, in the drag root's own coordinates. */
    private fun tileCentre(id: String): Offset {
        val tile = compose.onNodeWithTag(companionTileTag(id), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return Offset(tile.center.x - root.left, tile.center.y - root.top)
    }

    /** A tap on the scrim, clear of the keyboard that is centred on it: the plate's own outside tap. */
    private fun tapOutsideTheKeyboard() {
        compose.onNodeWithTag(CompanionRenamePlateTag, useUnmergedTree = true).performTouchInput { click(Offset(6f, 6f)) }
    }

    @Test
    fun `tapping plus makes a Tome and opens the rename plate on it, and dismissing keeps Book`() {
        mount { compose.mainClock.currentTime }
        assertTrue("no plate before the tap", !plateShowing())
        assertEquals(3, tileCount())

        tapPlus()
        frames(600L)
        println("W2C-TOME created=$created tiles=${tileCount()} plate=${plateShowing()}")
        assertEquals("one new Tome", listOf("tome.1"), created)
        assertEquals("it is in the payload", 4, tileCount())
        assertTrue("the rename plate is showing for it", plateShowing())
        assertEquals("nothing is renamed until the plate is dismissed", emptyList<Pair<String, String>>(), renamed)

        tapOutsideTheKeyboard()
        frames(1_000L)
        assertTrue("the plate is gone", !plateShowing())
        assertEquals("dismissing commits the Tome's own name, Book", listOf("tome.1" to "Book"), renamed)
        assertEquals("and made no second Tome", 1, created.size)
    }

    private fun tapKeyboardControl(description: String) {
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).performClick()
    }

    @Test
    fun `Hide on the rename keyboard closes the plate and leaves the name unchanged, even after typing`() {
        mount { compose.mainClock.currentTime }
        tapPlus()
        frames(600L)
        assertTrue("the rename plate is showing", plateShowing())
        // An edit first (Backspace turns the draft "Book" into "Boo"): Hide is "close without saving", so the edited draft is thrown away too.
        tapKeyboardControl("Backspace")
        frames(100L)
        tapKeyboardControl("Hide keyboard")
        frames(1_000L)
        println("PAD4-RENAME hide renamed=$renamed plate=${plateShowing()}")
        assertTrue("Hide closed the editor", !plateShowing())
        assertEquals("and nothing was saved (pre-fix, Hide fell back to Done and committed)", emptyList<Pair<String, String>>(), renamed)
        assertEquals("the Tome stays, named as it was created", 4, tileCount())
    }

    @Test
    fun `Done on the rename keyboard saves the name and closes`() {
        mount { compose.mainClock.currentTime }
        tapPlus()
        frames(600L)
        assertTrue(plateShowing())
        tapKeyboardControl("Done")
        frames(1_000L)
        println("PAD4-RENAME done renamed=$renamed plate=${plateShowing()}")
        assertTrue("Done closed the editor", !plateShowing())
        assertEquals("and saved the name", listOf("tome.1" to "Book"), renamed)
    }

    @Test
    fun `two taps 100 ms apart make one Tome`() {
        mount { clockMs }
        clockMs = 10_000L
        tapPlus()
        clockMs = 10_100L
        tapPlus()
        frames(600L)
        println("W2C-TOME 100ms created=$created tiles=${tileCount()}")
        assertEquals("the second tap, 100 ms later, was the same tap", listOf("tome.1"), created)
        assertEquals(4, tileCount())
        assertTrue("one plate, on the one Tome", plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        assertEquals(listOf("tome.1" to "Book"), renamed)
    }

    @Test
    fun `two taps 500 ms apart make two Tomes, with one plate`() {
        mount { clockMs }
        clockMs = 10_000L
        tapPlus()
        clockMs = 10_500L
        tapPlus()
        frames(600L)
        println("W2C-TOME 500ms created=$created tiles=${tileCount()}")
        assertEquals("a deliberate second tap makes the next Tome", listOf("tome.1", "tome.2"), created)
        assertEquals(5, tileCount())
        assertTrue("the plate is up", plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        // The first Tome's plate opened the moment it landed; the second Tome's was dropped because a plate was already up (on a device the second tap would
        // have landed on that plate). One plate, kept Book.
        assertEquals("one plate, on the first Tome to land, kept Book", listOf("tome.1" to "Book"), renamed)
        assertTrue("and the second Tome's plate did not reopen later", !plateShowing())
    }

    @Test
    fun `a second plus tap after the plate is dismissed and 500 ms have passed opens the plate on the next Tome`() {
        mount { compose.mainClock.currentTime }
        tapPlus()
        frames(600L)
        assertTrue(plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        assertTrue(!plateShowing())
        tapPlus()
        frames(600L)
        println("W2C-TOME flow created=$created tiles=${tileCount()} plate=${plateShowing()}")
        assertEquals(listOf("tome.1", "tome.2"), created)
        assertTrue("the plate is up on the second Tome", plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        assertEquals(listOf("tome.1" to "Book", "tome.2" to "Book"), renamed)
    }

    @Test
    fun `the plate after plus is a closable layer, and the host's back dismisses it as an outside tap does, keeping Book`() {
        mount { compose.mainClock.currentTime }
        assertTrue("nothing closable before the tap", !closable)
        tapPlus()
        frames(600L)
        assertTrue("the plate is showing", plateShowing())
        println("W2C-TOME closable=$closable")
        assertTrue("the host is told a layer is up (no Tome is open)", closable)
        // The host's edge-back: a new request value.
        closeRequest = 1L
        frames(1_000L)
        assertTrue("back dismissed the plate", !plateShowing())
        assertEquals("it committed the Tome's own name", listOf("tome.1" to "Book"), renamed)
        assertTrue("and the layer is no longer reported closable", !closable)
    }

    @Test
    fun `a plate whose Tome lands while a tile is lifted waits for the drop, then opens once`() {
        deferLanding = true
        mount { compose.mainClock.currentTime }
        tapPlus()
        frames(100L)
        assertEquals(listOf("tome.1"), created)
        assertTrue("the payload has not landed, so no plate", !plateShowing())
        // Lift a tile (a held press past the long-press delay) before the payload lands.
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput { down(tileCentre("p1")) }
        frames(600L)
        println("W2C-TOME lifted dragging=$dragging")
        assertTrue("a tile is lifted", dragging)
        landTome()
        frames(600L)
        assertEquals("the Tome landed", 4, tileCount())
        assertTrue("no plate over a lifted tile", !plateShowing())
        root.performTouchInput { up() }
        frames(2_000L)
        println("W2C-TOME dropped dragging=$dragging plate=${plateShowing()}")
        assertTrue("the drop ended the lift", !dragging)
        assertTrue("after the drop the plate opens for the new Tome", plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        assertEquals("it opened once, on the new Tome", listOf("tome.1" to "Book"), renamed)
        assertTrue(!plateShowing())
    }

    private fun runes(): String =
        compose.onNodeWithTag(CompanionPageRunesTag, useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()

    @Test
    fun `a payload that lands during the pre-lift press opens no plate, the lift that follows opens none, and the drop opens it once`() {
        deferLanding = true
        mount { compose.mainClock.currentTime }
        tapPlus()
        frames(100L)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        // A press on a tile, short of the 180 ms the lift waits: the machine is Pressed, nothing is lifted yet.
        root.performTouchInput { down(tileCentre("p1")) }
        frames(60L)
        assertTrue("not lifted yet", !dragging)
        landTome()
        frames(60L)
        println("W2C-TOME pressed dragging=$dragging plate=${plateShowing()} tiles=${tileCount()}")
        assertEquals("the Tome landed", 4, tileCount())
        assertTrue("no plate while a press could still lift", !plateShowing())
        // The press becomes a lift under the same finger: still no plate.
        frames(600L)
        assertTrue("the press lifted", dragging)
        assertTrue("no plate over the lift", !plateShowing())
        root.performTouchInput { up() }
        frames(2_000L)
        assertTrue("the drop ended the lift", !dragging)
        assertTrue("the plate opens after the drop", plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        assertEquals("once, on the new Tome", listOf("tome.1" to "Book"), renamed)
        assertTrue(!plateShowing())
    }

    @Test
    fun `a page of the player's own drops the pending plate, and the Tome landing later gets none`() {
        deferLanding = true
        resetPayload(10)
        mount(restoredPage = 1) { compose.mainClock.currentTime }
        assertEquals("Page 2 of 2", runes())
        tapPlus()
        frames(100L)
        assertEquals(listOf("tome.1"), created)
        // The player turns back to page 1 before the payload lands.
        compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).performTouchInput {
            swipeWithVelocity(Offset(width * 0.05f, centerY), Offset(width * 0.95f, centerY), endVelocity = 4_000f, durationMillis = 120)
        }
        frames(600L)
        println("W2C-TOME turned runes=${runes()}")
        assertEquals("Page 1 of 2", runes())
        landTome()
        frames(2_000L)
        assertEquals("the Tome landed", 11, tileCount())
        assertTrue("the player moved on, so no plate opens for it", !plateShowing())
        assertEquals(emptyList<Pair<String, String>>(), renamed)
    }

    @Test
    fun `a Tome the player opens drops the pending plate, and the Tome landing later gets none`() {
        deferLanding = true
        resetPayload(3, withFolder = true)
        mount { compose.mainClock.currentTime }
        tapPlus()
        frames(100L)
        assertEquals(listOf("tome.1"), created)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput { down(tileCentre("f1")) }
        frames(60L)
        root.performTouchInput { up() }
        frames(1_000L)
        println("W2C-TOME opened closable=$closable")
        assertTrue("the existing Tome opened", closable)
        landTome()
        frames(1_000L)
        assertEquals("the new Tome landed", 5, tileCount())
        assertTrue("no plate over or after the open Tome", !plateShowing())
        assertEquals(emptyList<Pair<String, String>>(), renamed)
    }

    @Test
    fun `the surface's own reveal to a Tome on another page still opens the plate`() {
        deferLanding = true
        resetPayload(10)
        mount(restoredPage = 1) { compose.mainClock.currentTime }
        tapPlus()
        frames(100L)
        // The new Tome lands behind eight more tiles: it is on a third page, so the reveal turns the pager to it.
        landing.addAll(0, (1..8).map { CompanionHomeTile("x$it", CompanionHomeTileKind.PLATFORM, "Extra $it") })
        landTome()
        frames(2_000L)
        println("W2C-TOME reveal runes=${runes()} plate=${plateShowing()} tiles=${tileCount()}")
        assertEquals("the reveal turned to the Tome's page", "Page 3 of 3", runes())
        assertTrue("and the plate opened for it", plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        assertEquals(listOf("tome.1" to "Book"), renamed)
    }

    @Test
    fun `the pending plate's rule drops on any page the surface did not turn to itself, wherever the Tome now is`() {
        val v = ::companionPendingRenameVerdict
        val open = CompanionPendingRenameVerdict.OPEN
        val wait = CompanionPendingRenameVerdict.WAIT
        val drop = CompanionPendingRenameVerdict.DROP
        // tomeOpen, plateUp, page, baseline, revealScrolling, landed, dragIdle
        assertEquals(open, v(false, false, 1, 1, false, true, true))
        assertEquals("not landed yet", wait, v(false, false, 1, 1, false, false, true))
        assertEquals("a press, lift, pager drag or rollback is not idle", wait, v(false, false, 1, 1, false, true, false))
        assertEquals("a Tome open", drop, v(true, false, 1, 1, false, true, true))
        assertEquals("another plate up", drop, v(false, true, 1, 1, false, true, true))
        assertEquals("a turn of the player's own, whether or not the Tome is on that page now", drop, v(false, false, 2, 1, false, true, true))
        assertEquals("even while still waiting", drop, v(false, false, 2, 1, false, true, false))
        assertEquals("the reveal's own scroll is not the player's", open, v(false, false, 2, 1, true, true, true))
        assertEquals("the reveal waits for idle like any other", wait, v(false, false, 2, 1, true, true, false))
    }

    /**
     * Eight tiles make one page; the ninth (the new Tome) makes a second. The payload lands [landAfterMs] into the press, so the model changes around the 180 ms
     * deadline. This is the end-to-end smoke for the race; the reader's own behaviour with a geometry that is behind at the deadline (which this frame timing does
     * not reach) is pinned in [CompanionShelfGestureReaderTest].
     */
    private fun assertReleaseAfterIncompleteGeometry(landAfterMs: Long) {
        deferLanding = true
        resetPayload(8)
        mount { compose.mainClock.currentTime }
        tapPlus()
        frames(100L)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput { down(tileCentre("p1")) }
        frames(landAfterMs)
        landTome()
        // Well past the deadline, finger still down.
        frames(700L)
        println("W2C-TOME incomplete@$landAfterMs dragging=$dragging plate=${plateShowing()} tiles=${tileCount()}")
        assertEquals("the Tome landed", 9, tileCount())
        assertTrue("no plate under a finger that is still down", !plateShowing())
        root.performTouchInput { up() }
        frames(2_000L)
        println("W2C-TOME incomplete@$landAfterMs released dragging=$dragging plate=${plateShowing()}")
        assertTrue("nothing is left lifted", !dragging)
        assertTrue("the release returned the machine to idle, so the plate opened", plateShowing())
        tapOutsideTheKeyboard()
        frames(1_000L)
        assertEquals("opened once, on the new Tome", listOf("tome.1" to "Book"), renamed)
        assertTrue(!plateShowing())
    }

    @Test fun `a release after a payload that lands as the long-press deadline fires still reaches idle, and the plate opens once`() = assertReleaseAfterIncompleteGeometry(165L)

    @Test
    fun `the backstop gives up on a machine that is not idle with no finger down, and never on a held finger`() {
        assertTrue("stuck: not idle, no finger", companionPendingRenameStalled(dragIdle = false, fingerDown = false))
        assertTrue("a held press or lift is the player's", !companionPendingRenameStalled(dragIdle = false, fingerDown = true))
        assertTrue("idle waits for nothing", !companionPendingRenameStalled(dragIdle = true, fingerDown = false))
        assertTrue(!companionPendingRenameStalled(dragIdle = true, fingerDown = true))
        assertEquals("the bound is the Bottom's three-beat clock", com.gruning.mimir.rewrite.core.ui.MimirMotion.ShineMs.toLong(), CompanionPendingRenameStallMs)
    }

    private class BackstopRun(var givenUpAt: Long? = null, var giveUps: Int = 0, var returnedAt: Long? = null)

    /**
     * Runs the backstop's loop on the paused frame clock, exactly as the surface does: [pending] and [stalled] are read live, the loop's clock is the frame clock, and
     * [events] fire at their millisecond (exact, not rounded up to frames) with no recomposition anywhere in between.
     */
    private fun runBackstop(
        events: Map<Long, () -> Unit>,
        totalMs: Long,
        pending: () -> Boolean,
        stalled: () -> Boolean,
        lastTransition: () -> Long,
    ): BackstopRun {
        val run = BackstopRun()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                companionRunPendingRenameBackstop(
                    pending = pending,
                    stalled = stalled,
                    nowMs = { compose.mainClock.currentTime },
                    lastTransitionMs = lastTransition,
                    windowMs = CompanionPendingRenameStallMs,
                    onGiveUp = {
                        run.giveUps++
                        run.givenUpAt = compose.mainClock.currentTime
                    },
                )
                run.returnedAt = compose.mainClock.currentTime
            }
        }
        fun advance(ms: Long) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
        }
        var now = 0L
        for ((at, action) in events.toSortedMap()) {
            advance(at - now)
            now = at
            action()
        }
        advance(totalMs - now)
        return run
    }

    @Test
    fun `a press spanning the expiry cannot disarm the backstop, and the stalled plate drops one window after the lift`() {
        var fingerDown = false
        var last = Long.MIN_VALUE / 2
        val window = CompanionPendingRenameStallMs
        val run = runBackstop(
            events = mapOf(
                window - 1 to { fingerDown = true; last = window - 1 },
                // Expiry at `window` sees the held finger; the lift comes before any recomposition, and the machine is still stalled.
                window + 5 to { fingerDown = false; last = window + 5 },
            ),
            totalMs = window * 4,
            pending = { true },
            stalled = { companionPendingRenameStalled(dragIdle = false, fingerDown = fingerDown) },
            lastTransition = { last },
        )
        println("W2C-BACKSTOP spanning givenUpAt=${run.givenUpAt} window=$window returnedAt=${run.returnedAt}")
        assertEquals("exactly one window after the lift", window + 5 + window, run.givenUpAt)
        assertEquals(1, run.giveUps)
        assertEquals("and the loop ended", run.givenUpAt, run.returnedAt)
    }

    @Test
    fun `a finger held through several windows is never dropped under`() {
        var fingerDown = false
        var last = Long.MIN_VALUE / 2
        val window = CompanionPendingRenameStallMs
        val run = runBackstop(
            events = mapOf(window - 1 to { fingerDown = true; last = window - 1 }),
            totalMs = window * 5,
            pending = { true },
            stalled = { companionPendingRenameStalled(dragIdle = false, fingerDown = fingerDown) },
            lastTransition = { last },
        )
        assertEquals(0, run.giveUps)
        assertEquals("still waiting", null, run.returnedAt)
    }

    @Test
    fun `an untouched stall gives up after exactly one window`() {
        val window = CompanionPendingRenameStallMs
        val run = runBackstop(events = emptyMap(), totalMs = window * 3, pending = { true }, stalled = { true }, lastTransition = { Long.MIN_VALUE / 2 })
        assertEquals(window, run.givenUpAt)
        assertEquals(1, run.giveUps)
    }

    @Test
    fun `the loop stops when the plate opens, with no later drop`() {
        var pending = true
        val window = CompanionPendingRenameStallMs
        val run = runBackstop(
            events = mapOf(window / 2 to { pending = false }),
            totalMs = window * 4,
            pending = { pending },
            stalled = { true },
            lastTransition = { Long.MIN_VALUE / 2 },
        )
        assertEquals("no drop", 0, run.giveUps)
        assertTrue("and the loop ended", run.returnedAt != null)
    }

    @Test
    fun `a machine that went idle by expiry starts a fresh window and is dropped only if stalled again`() {
        var idle = false
        val window = CompanionPendingRenameStallMs
        val run = runBackstop(
            events = mapOf(window - 1 to { idle = true }, window + 100 to { idle = false }),
            totalMs = window * 4,
            pending = { true },
            stalled = { companionPendingRenameStalled(dragIdle = idle, fingerDown = false) },
            lastTransition = { Long.MIN_VALUE / 2 },
        )
        println("W2C-BACKSTOP idle givenUpAt=${run.givenUpAt}")
        // Not stalled at the first expiry (idle): the next window runs from there and ends one window later.
        assertEquals(window * 2, run.givenUpAt)
    }
}
