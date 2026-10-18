package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import kotlinx.coroutines.CompletableDeferred
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

/**
 * The Library's page changes. Opening a platform holds
 * the shelf until the gamelist's first covers are ready (at most 0.4 s), then the shelf leaves on 180 ms and the
 * gamelist glides in from the 130 ms lead on 540 ms; presses wait until it shows. The way back keeps today's 160 ms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibraryPageMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val page = mutableStateOf("shelf")
    private val cancel = mutableLongStateOf(0L)
    private val probe = LibraryPageProbe()
    private var settle = CompletableDeferred<Unit>()
    private var motion: (String, String) -> LibraryPageMotion = { _, _ -> LibraryPageMotion.Dissolve }
    private lateinit var leaf: LibraryBookLeaf
    private var uptime = 0L
    private var presses = 0

    @Before
    fun clock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { uptime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            leaf = rememberLibraryBookLeaf()
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ImmediateLibrarySnapshotTransition(
                    targetState = page.value,
                    transitionCancellationEpoch = cancel.longValue,
                    motion = { from, to -> motion(from, to) },
                    probe = probe,
                ) {
                    Box(
                        Modifier.size(100.dp).pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) {
                                        presses++
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        frame()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        uptime += 16
    }

    private fun go(to: String) {
        page.value = to
        Snapshot.sendApplyNotifications()
        frame()
    }

    private fun run(ms: Long, each: (Long) -> Unit = {}) {
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            frame()
            each(compose.mainClock.currentTime - start)
        }
    }

    @Test
    fun `an entry holds the old page still and the pad waits until the covers are ready`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }) }
        host()
        go("gamelist")
        run(300)
        assertEquals("the shelf holds", 1f, probe.outgoingAlpha(), 0f)
        assertEquals("the gamelist is hidden", 0f, probe.incomingAlpha(), 0f)
        assertTrue(probe.settling())
        assertFalse(probe.arrived())
        assertTrue("presses wait", MimirTransitionInputGate.holding)
    }

    @Test
    fun `once ready the old page leaves before the new one glides in, and they never show together`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }) }
        host()
        go("gamelist")
        run(100)
        settle.complete(Unit)
        var bothVisible = 0
        var gateOpenedAt = -1L
        run(900) { t ->
            val out = probe.outgoingAlpha()
            val incoming = probe.incomingAlpha()
            if (out > 0.15f && incoming > 0.15f) bothVisible++
            if (t < MimirPageMotion.LeadMs - 16) assertEquals("hidden before the lead at $t", 0f, incoming, 0.001f)
            if (t in 140L..160L) assertTrue("the shelf is nearly gone at $t: $out", out <= 0.06f)
            if (gateOpenedAt < 0 && !MimirTransitionInputGate.holding) gateOpenedAt = t
        }
        assertEquals("frames with both pages above 0.15", 0, bothVisible)
        assertTrue("presses reach the page once it shows: $gateOpenedAt", gateOpenedAt in 130L..200L)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals("at rest", 0f, probe.incomingTravel(), 0f)
        assertTrue(probe.arrived())
    }

    @Test
    fun `the arrival glides in from the right and the shelf leaves to the left, and L mirrors it`() {
        val travel = MimirMotion.GlideTravelDp.toFloat()
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1) }
        host()
        go("gamelist")
        assertEquals(travel, probe.incomingTravel(), 0.5f)
        run(170)
        assertTrue("the shelf moves left: ${probe.outgoingTravel()}", probe.outgoingTravel() < -travel * 0.8f)

        motion = { _, _ -> LibraryPageMotion.Entry(direction = -1) }
        run(1_000)
        go("shelf")
        assertEquals("L brings it in from the left", -travel, probe.incomingTravel(), 0.5f)
    }

    @Test
    fun `a page that never settles shows anyway at the cap`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }) }
        host()
        go("gamelist")
        var exitStarted = -1L
        run(1_300) { t -> if (exitStarted < 0 && probe.outgoingAlpha() < 1f) exitStarted = t }
        assertTrue("the shelf starts to leave at the 400 ms cap: $exitStarted", exitStarted in 400L..460L)
        assertEquals(1f, probe.incomingAlpha(), 0f)
    }

    /** RG-2: the same stall as the platform switch's, on the page entry. The gate's uptime runs ahead before the cap. */
    @Test
    fun `a capped settle that ends 200 ms late still holds the pad until the page shows`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }) }
        host()
        go("gamelist")
        var stalled = false
        var shownAt = -1L
        var gateOpenedAt = -1L
        var unseenAndOpen = -1L
        run(1_000) { t ->
            if (!stalled && t >= 100) {
                uptime += 200
                stalled = true
            }
            if (shownAt < 0 && probe.incomingAlpha() > 0f) shownAt = t
            val open = !MimirTransitionInputGate.holding
            if (gateOpenedAt < 0 && open) gateOpenedAt = t
            if (shownAt < 0 && open && unseenAndOpen < 0) unseenAndOpen = t
        }
        val cap = MimirPageMotion.SettleCapMs.toLong()
        val lead = MimirPageMotion.LeadMs.toLong()
        assertTrue("the page shows a lead after the cap: $shownAt", shownAt in (cap + lead)..(cap + lead + 96))
        assertEquals("the pad was open at $unseenAndOpen ms while the page was still at alpha 0", -1L, unseenAndOpen)
        assertTrue("and it opens as the page shows: $gateOpenedAt", gateOpenedAt in shownAt..(shownAt + 16))
    }

    @Test
    fun `a return keeps today's pace, the destination at rest and the old page gliding back`() {
        motion = { _, _ -> LibraryPageMotion.Return(direction = 1) }
        host()
        go("gamelist")
        assertEquals("the destination is on screen at once", 1f, probe.incomingAlpha(), 0f)
        assertFalse(MimirTransitionInputGate.holding)
        var goneAt = -1L
        run(400) { t -> if (goneAt < 0 && probe.outgoingAlpha() == 0f) goneAt = t }
        assertTrue("gone in about 160 ms: $goneAt", goneAt in 150L..210L)
        assertEquals(MimirMotion.GlideTravelDp.toFloat(), probe.outgoingTravel(), 0.5f)
    }

    private fun interact() {
        cancel.longValue += 1L
        Snapshot.sendApplyNotifications()
        frame()
        frame()
    }

    @Test
    fun `a touch during the wait ends nothing, the shelf holds, the pad waits and the book keeps swinging`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }, book = leaf) }
        host()
        placeTheBook()
        go("gamelist")
        run(100)
        interact()
        assertTrue("presses still wait", MimirTransitionInputGate.holding)
        assertTrue(probe.settling())
        assertFalse(probe.arrived())
        assertEquals("the shelf holds", 1f, probe.outgoingAlpha(), 0f)
        assertEquals("the gamelist is still hidden", 0f, probe.incomingAlpha(), 0f)
        assertTrue("the book is mid-swing", probe.bookShowing())
        val swing = probe.bookTime()
        run(50)
        assertTrue("and keeps swinging: $swing -> ${probe.bookTime()}", probe.bookTime() > swing)
    }

    @Test
    fun `the first press that gets through leaves the arrival gliding the whole way`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }) }
        host()
        go("gamelist")
        run(50)
        settle.complete(Unit)
        val start = compose.mainClock.currentTime
        while (MimirTransitionInputGate.holding) {
            assertTrue("the gate opens", compose.mainClock.currentTime - start < 1_000)
            frame()
        }
        interact()
        assertTrue("barely shown: ${probe.incomingAlpha()}", probe.incomingAlpha() < 0.25f)
        assertTrue("still on its side: ${probe.incomingTravel()}", probe.incomingTravel() > 1f)
        assertFalse(probe.arrived())
        assertEquals("the departing pixels go", 0f, probe.outgoingAlpha(), 0f)
        run(300 - (compose.mainClock.currentTime - start))
        interact()
        val alpha = probe.incomingAlpha()
        assertTrue("a press at 300 ms leaves it mid-glide: $alpha", alpha < 1f && alpha > 0f)
        assertTrue(probe.incomingTravel() > 0f)
        assertFalse(probe.arrived())
        run(50)
        assertTrue("still animating: $alpha -> ${probe.incomingAlpha()}", probe.incomingAlpha() > alpha)
        run(1_000)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals("at rest", 0f, probe.incomingTravel(), 0f)
        assertTrue(probe.arrived())
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `an interaction during a dissolve drops the old pixels at once`() {
        host()
        go("gamelist")
        assertTrue(probe.outgoingAlpha() > 0f)
        interact()
        assertEquals(0f, probe.outgoingAlpha(), 0f)
        assertEquals(1f, probe.incomingAlpha(), 0f)
    }

    private fun placeTheBook() {
        leaf.platformId = "snes"
        leaf.topLeftInRoot = androidx.compose.ui.geometry.Offset(40f, 20f)
        leaf.sizeInRoot = androidx.compose.ui.geometry.Size(30f, 40f)
    }

    @Test
    fun `the cover swings from one frame after the press and plays through the wait`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }, book = leaf) }
        host()
        placeTheBook()
        go("gamelist")
        assertTrue(probe.bookShowing())
        assertEquals("not before the first frame", 0f, probe.bookTime(), 0f)
        run(180)
        assertTrue("half open while the covers settle: ${probe.bookTime()}", probe.bookTime() in 0.4f..0.55f)
        assertTrue(probe.settling())
        run(200)
        assertEquals("fully open at 360 ms", 1f, probe.bookTime(), 0f)
        assertTrue("and still there while the shelf holds", probe.bookShowing())
        assertTrue("the shelf still holds for the covers", probe.outgoingAlpha() > 0.9f)
    }

    @Test
    fun `on a long wait the cover waits open and fades only as the shelf leaves and the games glide in`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }, book = leaf) }
        host()
        placeTheBook()
        go("gamelist")
        run(380)
        assertTrue(probe.settling())
        assertEquals("the shelf still holds", 1f, probe.outgoingAlpha(), 0f)
        assertTrue("the cover is still there over it", probe.bookShowing())
        assertEquals("its fade has not started", 0f, probe.bookFade(), 0f)
        assertEquals(1f, libraryBookLeafPose(probe.bookTime(), probe.bookFade()).alpha, 0f)
        settle.complete(Unit)
        var coverFadedOverStillShelf = 0
        var coverOverArrivingGames = 0
        run(700) {
            val cover = if (probe.bookShowing()) libraryBookLeafPose(probe.bookTime(), probe.bookFade()).alpha else 0f
            if (cover < 1f && probe.bookShowing() && probe.outgoingAlpha() > 0.9f) coverFadedOverStillShelf++
            if (cover > 0.5f && probe.incomingAlpha() > 0.05f) coverOverArrivingGames++
        }
        assertEquals("frames where the cover faded over a still shelf", 0, coverFadedOverStillShelf)
        assertTrue("the cover swings open as the games glide in", coverOverArrivingGames > 0)
        assertFalse("gone once its fade clock ends", probe.bookShowing())
    }

    @Test
    fun `on a warm open the two clocks run together and the book is gone by about 360 ms`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, book = leaf) }
        host()
        placeTheBook()
        go("gamelist")
        run(200)
        assertEquals("the clocks start together", probe.bookTime(), probe.bookFade(), 0.05f)
        run(200)
        assertFalse(probe.bookShowing())
    }

    private fun tap(at: Offset) {
        compose.onRoot().performTouchInput {
            down(at)
            up()
        }
        frame()
    }

    @Test
    fun `on a warm open the page takes touches once it shows, but not through the cover still standing`() {
        // No settle wait: the covers were prewarmed, so the page moves at once and shows long before the book is gone.
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, book = leaf) }
        host()
        placeTheBook()
        go("gamelist")
        run(200)
        assertFalse("the page shows, so presses reach it", MimirTransitionInputGate.holding)
        assertTrue("the cover still stands", probe.bookShowing())
        assertEquals(
            "and is still opaque",
            1f,
            libraryBookLeafPose(probe.bookTime(), probe.bookFade()).alpha,
            0f,
        )
        val cover = Offset(55f + probe.outgoingTravel(), 40f)
        tap(cover)
        assertEquals("a tap on the cover reaches nothing", 0, presses)
        tap(Offset(90f, 90f))
        assertEquals("the rest of the page is live", 1, presses)
        run(250)
        assertFalse("the cover is gone", probe.bookShowing())
        tap(cover)
        assertEquals("where it stood is live again", 2, presses)
    }

    @Test
    fun `B during the wait snaps the open book, and a way back with no book plays none`() {
        motion = { from, _ ->
            if (from == "shelf") LibraryPageMotion.Entry(direction = 1, settle = { settle.await() }, book = leaf)
            else LibraryPageMotion.Return(direction = 1)
        }
        host()
        placeTheBook()
        go("gamelist")
        run(100)
        assertTrue(probe.bookShowing())
        go("shelf")
        assertFalse(probe.bookShowing())
        run(400)
        assertFalse(probe.bookShowing())
    }

    @Test
    fun `the way back swings the closed platform's cover shut on the return clock`() {
        // Closing uses a departure animation for consistency with other page returns.
        motion = { from, _ ->
            if (from == "shelf") LibraryPageMotion.Entry(direction = 1, book = leaf)
            else LibraryPageMotion.Return(direction = 1, book = leaf, bookPlatformId = "snes")
        }
        host()
        placeTheBook()
        go("gamelist")
        run(1_200)
        go("shelf")
        assertTrue("the close plays", probe.bookClosing())
        run(MimirPageMotion.ReturnMs + 64L)
        assertFalse("and is gone with the return", probe.bookClosing())
        assertEquals("shut", 0f, probe.bookSwing(), 0f)
    }

    @Test
    fun `the close never plays for a platform the shelf does not centre`() {
        host()
        placeTheBook()
        assertFalse(leaf.readyFor("n64"))
        assertEquals(
            LibraryPageMotion.Return(direction = 1),
            libraryPrimaryPageMotion(LibraryPrimarySurfaceKey.Gamelist, LibraryPrimarySurfaceKey.Shelf, null, closeBook = leaf),
        )
        assertEquals(
            LibraryPageMotion.Return(direction = 1, book = leaf, bookPlatformId = "snes"),
            libraryPrimaryPageMotion(
                LibraryPrimarySurfaceKey.Gamelist,
                LibraryPrimarySurfaceKey.Shelf,
                null,
                closeBook = leaf,
                closingPlatformId = "snes",
            ),
        )
    }

    @Test
    fun `a book that was never placed, or belongs to another platform, does not open`() {
        motion = { _, _ -> LibraryPageMotion.Entry(direction = 1, book = leaf) }
        host()
        assertFalse(leaf.readyFor("snes"))
        go("gamelist")
        assertFalse("nothing placed: no book", probe.bookShowing())
        placeTheBook()
        assertTrue(leaf.readyFor("snes"))
        assertFalse(leaf.readyFor("n64"))
        assertFalse(leaf.readyFor(null))
    }

    @Test
    fun `the Tome open is the entry, the way back the return, anything else the dissolve`() {
        val settle: suspend () -> Unit = {}
        val open = libraryPrimaryPageMotion(LibraryPrimarySurfaceKey.Shelf, LibraryPrimarySurfaceKey.Gamelist, settle)
        assertEquals(LibraryPageMotion.Entry(direction = 1, settle = settle), open)
        assertEquals(
            "the book rides only the open",
            LibraryPageMotion.Return(direction = 1),
            libraryPrimaryPageMotion(LibraryPrimarySurfaceKey.Gamelist, LibraryPrimarySurfaceKey.Shelf, settle, book = null),
        )
        assertEquals(
            LibraryPageMotion.Return(direction = 1),
            libraryPrimaryPageMotion(LibraryPrimarySurfaceKey.Gamelist, LibraryPrimarySurfaceKey.Shelf, settle),
        )
        assertEquals(
            LibraryPageMotion.Dissolve,
            libraryPrimaryPageMotion(LibraryPrimarySurfaceKey.Gamelist, LibraryPrimarySurfaceKey.Gamelist, settle),
        )
    }

    @Test
    fun `the settle window is the landing card and three either side, every card the carousel paints`() {
        // at two either side the carousel's outer pair always arrived as flat seats.
        val rows = (0 until 10).map { row(it) }
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8), libraryGamelistSettleWindow(rows, GameId("g5")).map { it.id() })
        assertEquals(listOf(0, 1, 2, 3), libraryGamelistSettleWindow(rows, GameId("g0")).map { it.id() })
        assertEquals("no remembered landing: the first card", listOf(0, 1, 2, 3), libraryGamelistSettleWindow(rows, null).map { it.id() })
        assertEquals(emptyList<GameRow>(), libraryGamelistSettleWindow(emptyList(), null))
    }

    private fun row(index: Int) = GameRow(
        gameId = GameId("g$index"),
        title = "Game $index",
        platformId = PlatformId("snes"),
        memberCount = 1,
        duplicateKind = null,
    )

    private fun GameRow.id() = gameId.value.removePrefix("g").toInt()

    @Test
    fun `a settled page change has no motion at all, so a launch return's page is finished under the cover`() {
        // Observed on Odin: back from an Eden that crashed on exit, the Library was reopened from the shelf and the gamelist
        // glided in AFTER the return cover lifted, behind the frosted card: a blurred background that moves. Under the cover the page
        // change must be finished at its first frames.
        motion = { _, _ -> LibraryPageMotion.Settled }
        host()
        go("gamelist")
        run(32)
        assertEquals("the destination is the finished page at once", 1f, probe.incomingAlpha(), 0f)
        assertEquals("on its seat", 0f, probe.incomingTravel(), 0f)
        assertEquals("the departing pixels are gone on the first beat", 0f, probe.outgoingAlpha(), 0f)
        assertFalse("no press waits", MimirTransitionInputGate.holding)
        assertTrue(probe.arrived())
        assertFalse(probe.settling())
    }

    @Test
    fun `a restoring Library takes the settled motion in both directions, an ordinary change keeps its own`() {
        val settle: suspend () -> Unit = {}
        val shelf = LibraryPrimarySurfaceKey.Shelf
        val gamelist = LibraryPrimarySurfaceKey.Gamelist
        assertEquals(LibraryPageMotion.Settled, libraryPrimaryPageMotion(shelf, gamelist, settle, settled = true))
        assertEquals(LibraryPageMotion.Settled, libraryPrimaryPageMotion(gamelist, shelf, null, settled = true))
        assertTrue(libraryPrimaryPageMotion(shelf, gamelist, settle) is LibraryPageMotion.Entry)
        assertTrue(libraryPrimaryPageMotion(gamelist, shelf, settle) is LibraryPageMotion.Return)
        // The product decides it from the RESTORING return target (LibraryViewModel.restoringLaunchReturnTarget): a target under restore, none is a direct player press.
        val target = LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = PlatformId.PS1,
            romPlatformId = PlatformId.PS1,
            gameId = GameId("ps1:g"),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "",
            favoritesOnly = false,
            restoreLaunchCard = true,
        )
        assertTrue(libraryPageRestoreSettled(target))
        assertTrue(libraryPageRestoreSettled(LibraryLaunchReturnTarget.shelf(LibraryShelfMode.ANDROID_GAMES, "pkg")))
        assertFalse(libraryPageRestoreSettled(null))
    }
}
