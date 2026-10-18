package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Thor Bottom revamp B4, the surface's half: the two panes of the workspace strip on the REAL [CompanionSurface] at the Thor Bottom's size, on a paused
 * clock with real touch events. The host here is a few lines of state standing in for the Presentation's strip (the strip's own logic is
 * `CompanionWorkspaceStripTest`); what this pins is what the SURFACE does with the three facts the host gives it: the neighbour, whether the strip is
 * panning, and where it is.
 *
 *  - Before the first pan only the shown scene is built (the neighbour costs nothing until it is asked for).
 *  - A right drag at page 0 reaches the host's seam, and once the host pans, both scenes are composed and drawn where the strip puts them, one width
 *    apart, translation only.
 *  - When the strip lands and the host swaps which scene is the shown one, the panes keep their compositions (no remount, no pop) and the scene that
 *    was a silent neighbour begins its receipts then.
 *  - D24: once the strip lets go, the scene that was left is PARKED, not disposed: still built, not placed, with no feed, ticker or timer running, and
 *    every later swipe un-parks it rather than composing it again.
 *  - A host with no seam gets exactly the scene it always did, at the origin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionWorkspacePaneTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369 // the Bottom draws 537.7 x 468.3 dp (1240 x 1080 px at 369 dpi), not the 445 dp window
    private val tiles = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private val payload = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = "19 platforms",
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
    )

    private var stripX by mutableFloatStateOf(0f)
    private var panActive by mutableStateOf(false)
    private var activityShown by mutableStateOf(false)
    private var snap by mutableStateOf(false)
    private var density = 0f
    private var mounts = 0
    private val deltas = mutableListOf<Float>()
    private var ends = 0
    private var cancels = 0
    private var disposes = 0

    // D24 instruments: what a pane's own content sees of its parking.
    private var builds = 0
    private var neighbourLive = true
    private var ticks = 0
    private var subscribers = 0
    private var emissions = 0
    private var fedSeen = 0
    private var hostKey by mutableIntStateOf(0)
    private var neighbourMotionLive = true

    // D24 EdgeBack: a Home with a Tome, and what the host is told and asked.
    private var homePayload by mutableStateOf<CompanionPayload?>(null)
    private var closeRequest by mutableStateOf<Long?>(null)
    private var bookOpen = false
    private val renamed = mutableListOf<Pair<String, String>>()

    private fun createTome(): String {
        val id = "tome.1"
        val home = homePayload ?: payload
        val lane = home.homeLanes.first()
        homePayload = home.copy(homeLanes = listOf(lane.copy(tiles = lane.tiles + CompanionHomeTile(id, CompanionHomeTileKind.FOLDER, "Book"))))
        return id
    }

    private val neighbour: @Composable () -> Unit = {
        remember { builds++ }
        DisposableEffect(Unit) {
            mounts += 1
            onDispose { disposes += 1 }
        }
        val live = LocalCompanionPaneLive.current
        val motionLive = LocalMimirMotionLive.current
        SideEffect {
            neighbourLive = live
            neighbourMotionLive = motionLive.value
        }
        // A ticker and a feed, the two kinds of thing a pane can own: the media tile's 250 ms loop and a store's flow. Each follows the pane's flag.
        LaunchedEffectWhileLive(hostKey) {
            while (true) {
                ticks += 1
                delay(250L)
            }
        }
        val fed by remember {
            flow {
                subscribers += 1
                try {
                    emissions += 1
                    emit(emissions)
                    awaitCancellation()
                } finally {
                    subscribers -= 1
                }
            }
        }.collectAsStateWhileLive(0)
        SideEffect { fedSeen = fed }
        // The Activity page's own receipt, the one a Bottom writes when the page is on screen.
        BottomUiSurfaceReceipt(surface = BottomSurface.ACTIVITY_MEDIA, visible = true)
        Box(Modifier.fillMaxSize().testTag(NeighbourTag))
    }

    private fun mount(seam: Boolean = true, surfacePayload: CompanionPayload = payload, latticePayload: CompanionPayload? = null) {
        ShadowLog.clear()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, 1f), LocalBottomUiDisplay provides DISPLAY) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionSurface(
                        payload = homePayload ?: surfacePayload,
                        latticePayload = latticePayload,
                        cursorId = "p1",
                        restoredPage = 0,
                        sceneOverride = neighbour.takeIf { activityShown },
                        sceneTransitionIdentity = activityShown,
                        neighbourScene = neighbour,
                        scenePanActive = panActive,
                        sceneStripX = { stripX },
                        sceneTransitionSnap = snap,
                        // The seam takes what it is offered, as the strip does: it starts panning and moves with the finger up to one width.
                        onWorkspaceDrag = if (seam) ({ delta: Float ->
                            deltas += delta
                            val next = (stripX + delta).coerceIn(0f, width())
                            val used = next - stripX
                            stripX = next
                            if (used != 0f) panActive = true
                            delta - used
                        }) else null,
                        onWorkspaceDragEnd = if (seam) ({ _: Float -> ends += 1; true }) else null,
                        onWorkspaceDragCancel = if (seam) ({ cancels += 1 }) else null,
                        onCreateFolder = { createTome() },
                        onRenameFolder = { id, name -> renamed += id to name },
                        onBookOpenChanged = { bookOpen = it },
                        closeBookRequest = closeRequest,
                        showActivitySwipeHint = false,
                        monotonicUptimeMillis = { compose.mainClock.currentTime },
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun width(): Float = widthDp * density

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun neighbourNodes() = compose.onAllNodesWithTag(NeighbourTag, useUnmergedTree = true).fetchSemanticsNodes()

    private fun latticeNodes() = compose.onAllNodesWithTag(companionSlotTag(0), useUnmergedTree = true).fetchSemanticsNodes()

    private fun activityEnters(): List<String> =
        ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }
            .filter { "display=$DISPLAY" in it && it.startsWith("ev=surface.enter") && "surface=activity-media" in it }

    /** The nodes a finger or a screen could reach: a parked pane's are not placed (and are usually not in the tree at all). */
    private fun placed(nodes: List<androidx.compose.ui.semantics.SemanticsNode>) = nodes.count { it.layoutInfo.isPlaced }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        frames(100)
    }

    /** The host's strip, as it moves for a swipe to the Activity page: it takes the finger, lands, and lets go. */
    private fun swipeToActivity() {
        stripX = width() * 0.5f
        panActive = true
        settle()
        stripX = width()
        settle()
        snap = true
        activityShown = true
        settle()
        panActive = false
        snap = false
        settle()
    }

    /** And back: the strip carries the lattice home, lands, and lets go. */
    private fun swipeToLattice() {
        stripX = width() * 0.5f
        panActive = true
        settle()
        stripX = 0f
        settle()
        snap = true
        activityShown = false
        settle()
        panActive = false
        snap = false
        settle()
    }

    /** One swipe's worth of receipts: every surface edge written on the Bottom, in order. */
    private fun surfaceLines(): List<String> =
        ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }
            .filter { "display=$DISPLAY" in it && it.startsWith("ev=surface.") }

    /**
     * Before the first pan only the shown scene is built; the neighbour costs nothing until the host asks for it. (D24 rewrote this test: it used to claim
     * that at ANY rest only the shown scene is composed, which is no longer so. After a pane has been shown it stays built, parked.)
     */
    @Test
    fun `before the first pan only the shown scene is built, and a pane that has been shown stays built, parked`() {
        mount()
        assertEquals("the lattice is the shown scene", 1, latticeNodes().size)
        assertEquals("the Activity page costs nothing until the strip pans", 0, neighbourNodes().size)
        assertEquals(0, mounts)
        assertEquals(0, builds)

        swipeToActivity()
        assertEquals("built by the first pan, once", 1, builds)
        assertEquals("the Activity page is shown", 1, placed(neighbourNodes()))
        assertEquals("the lattice is parked: not placed, so not drawn and not hit", 0, placed(latticeNodes()))

        swipeToLattice()
        assertEquals("the lattice is shown again", 1, placed(latticeNodes()))
        assertEquals("the Activity page is parked: still built, not placed", 0, placed(neighbourNodes()))
        assertEquals("kept, never rebuilt", 1, builds)
        assertEquals(1, mounts)
        assertEquals("and never disposed", 0, disposes)
    }

    /** D24: ten swipes each way build the Activity page once and the lattice never again, and the lattice is the same layout node each time. */
    @Test
    fun `ten swipes each way keep both panes built, and the lattice's identity is stable`() {
        mount()
        val latticeId = latticeNodes().single().id
        repeat(10) {
            swipeToActivity()
            swipeToLattice()
            assertEquals("the lattice is the same node after swipe ${it + 1}", latticeId, latticeNodes().single { n -> n.layoutInfo.isPlaced }.id)
        }
        assertEquals("the Activity page was built once", 1, builds)
        assertEquals(1, mounts)
        assertEquals("and nothing was disposed in twenty swipes", 0, disposes)
    }

    /** D24: parked means not live, and a feed, a ticker and a timer all stand down; being shown or dragged means live. */
    @Test
    fun `a parked pane runs nothing and a shown or dragged one runs`() {
        mount()
        swipeToActivity()
        assertTrue("shown", neighbourLive)
        assertTrue(neighbourMotionLive)
        assertEquals("one subscription while shown", 1, subscribers)
        assertTrue("the ticker has run", ticks >= 1)

        swipeToLattice()
        assertFalse("parked", neighbourLive)
        assertFalse("and the clocks that follow the pane (the keyboard caret, a ring gleam) are told to stand down", neighbourMotionLive)
        assertEquals("the feed is not collected while parked", 0, subscribers)
        assertEquals("and the last value it held is kept", 1, fedSeen)
        val ticksAtPark = ticks
        frames(5_000)
        assertEquals("no timer runs while parked", ticksAtPark, ticks)
        assertEquals(0, subscribers)

        // A drag un-parks it: placed, live, resubscribed, the ticker started again from its top.
        stripX = width() * 0.3f
        panActive = true
        settle()
        assertTrue("dragged", neighbourLive)
        assertTrue("and the clocks that follow the pane (the keyboard caret, a ring gleam) are told it is live", neighbourMotionLive)
        assertEquals("placed again", 1, placed(neighbourNodes()))
        assertEquals("resubscribed", 1, subscribers)
        assertTrue("the ticker started again", ticks > ticksAtPark)
    }

    /** D24: a change of the host's key while the pane is parked starts no timer (a game launch, a track change, midnight). */
    @Test
    fun `a host change while parked starts no timer and no feed`() {
        mount()
        swipeToActivity()
        swipeToLattice()
        val ticksAtPark = ticks
        assertEquals(0, subscribers)
        hostKey += 1
        Snapshot.sendApplyNotifications()
        frames(5_000)
        assertEquals("the changed key was recomposed and started nothing", ticksAtPark, ticks)
        assertEquals(0, subscribers)
        assertFalse(neighbourLive)
    }

    /**
     * D24: receipts for a swipe that lands on a parked pane are the ones a swipe that built it writes. The first round trip builds both panes; the
     * second lands on parked ones. Every surface edge (the enter and exit, their `from=`, `to=` and `transition=`) must read the same.
     */
    @Test
    fun `receipts for a swipe onto a parked pane are byte-identical to the swipe that built it`() {
        mount()
        ShadowLog.clear()
        swipeToActivity()
        swipeToLattice()
        val first = surfaceLines()
        ShadowLog.clear()
        swipeToActivity()
        swipeToLattice()
        val second = surfaceLines()
        assertTrue("fixture: the Activity page wrote its enter and its exit: $first", first.any { "surface=activity-media" in it && it.startsWith("ev=surface.exit") })
        assertTrue("fixture: and its enter", first.any { "surface=activity-media" in it && it.startsWith("ev=surface.enter") })
        assertEquals("the second round trip writes exactly what the first did", first, second)

        // Explicit counts (the review asked for a once-per-exit pin): the Activity page's own receipt is one enter and one exit per round trip, and no surface
        // at all exits more than once, so a kept pane writes no extra exit when it parks and none more when it comes back.
        fun edges(lines: List<String>, kind: String, surface: String) = lines.count { it.startsWith("ev=surface.$kind") && "surface=$surface " in "$it " }
        for ((name, lines) in listOf("first" to first, "second" to second)) {
            assertEquals("$name: activity-media enters once: $lines", 1, edges(lines, "enter", "activity-media"))
            assertEquals("$name: activity-media exits once: $lines", 1, edges(lines, "exit", "activity-media"))
            val surfaces = lines.mapNotNull { Regex("surface=([A-Za-z0-9_.-]+)").find(it)?.groupValues?.get(1) }.distinct()
            for (surface in surfaces) {
                assertTrue("$name: $surface exits at most once: $lines", edges(lines, "exit", surface) <= 1)
            }
        }
    }

    /** D24: the Bottom is touch-only, and a parked pane registers no pad target: nothing in the surface touches the pad's focus authority. */
    @Test
    fun `no file a pane is built from registers a focus target, so a parked pane cannot`() {
        // Every file of this module, and the app's files the Activity pane is made of: nothing here may register with the pad's focus authority.
        val roots = listOf(
            java.io.File("src/main/kotlin"),
            java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite"),
        )
        val appPaneFiles = Regex("Companion(Workspace|Activity|MusicPanelScene|MusicSeekBar|EngineMediaTile|LaunchCard).*[.]kt")
        val files = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && (root.path.startsWith("src") || appPaneFiles.matches(it.name)) }.toList()
        }
        assertTrue("fixture: the scan saw the surface and the Activity pane's files (${files.size})", files.size > 10 && files.any { it.name == "CompanionSurface.kt" } && files.any { it.name == "CompanionWorkspaceSurface.kt" })
        for (file in files) {
            val code = file.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }.joinToString("\n")
            // The ONE exemption (8e0c3f996): the Activity page's pad keeps a page-local FocusAuthority of its own and its elements register with it through
            // `mimirFocusable`. That is allowed because the registration is not permanent: the route to it is released when the pane parks, when the window parks
            // and when the screen goes off ([CompanionActivityPad.kt]'s route effect, pinned by the next test), so a parked pane still gives the pad back to Main.
            // No other pane file may use it, and the exempt file keeps every other ban below.
            if (file.name != ActivityPadFile) assertFalse("${file.name}: mimirFocusable", "mimirFocusable(" in code)
            assertFalse("${file.name}: focusTarget", "focusTarget(" in code)
            assertFalse("${file.name}: focusable()", ".focusable(" in code)
            assertFalse("${file.name}: registers with a FocusAuthority", Regex("(?<!Default)FocusAuthority[.]|authority[.]register[(]").containsMatchIn(code))
        }
        assertTrue("fixture: the exempt file is in the scan, so the exemption names a real file", files.any { it.name == ActivityPadFile })
    }

    /**
     * The other half of the exemption above: the Activity page's pad may register focus targets only because its route is held by the pane's, the scene's and
     * the window's liveness and released the moment any of them goes. Pinned on the file's source (the behaviour is `CompanionActivityPadRouteTest` in :app): the effect
     * collects the window's liveness, the registered lambdas ask `isLive` again on every event, and the route and the router are given back in a `finally`.
     */
    @Test
    fun `the Activity pad that may register focus targets is released when its pane or window parks`() {
        val pad = listOf(java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$ActivityPadFile"), java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/$ActivityPadFile"))
            .first { it.isFile }.readText()
        val effect = pad.substringAfter("internal fun CompanionActivityPadRouteEffect(").substringBefore("/** Registers the route with the exact lambdas")
        assertTrue("the effect follows the pane's liveness", effect.contains("val paneLive = LocalMimirMotionLive.current") && effect.contains("paneLive.value && pageLiveState.value"))
        assertTrue("and the window's, through the flow that needs no recomposition", effect.contains("combine(shown, window.liveFlow)") && effect.contains("shownNow && windowLive"))
        assertTrue("the route is held only while all of them are live", effect.contains("collectLatest { live -> if (live) holdActivityPadRoute(router, window) }"))
        val hold = pad.substringAfter("private suspend fun holdActivityPadRoute(")
        assertTrue("each registered lambda asks the window again on every event", hold.contains("window.isLive &&") && hold.split("window.isLive &&").size - 1 == 2)
        val release = hold.substringAfter("CompanionActivityPadRoute.register(keys, motion)")
        assertTrue("the route is registered until cancelled, then released with the same lambdas", release.contains("awaitCancellation()") && release.contains("finally {") && release.contains("CompanionActivityPadRoute.release(keys, motion)"))
        assertTrue("and the router is reset, so nothing the page was in the middle of runs late", release.substringAfter("finally {").contains("router.reset()"))
        val hub = java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementHub.kt").takeIf { it.isFile }
            ?: java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementHub.kt")
        assertTrue("the hub holds the route through that one effect, with the window's liveness", hub.readText().contains("CompanionActivityPadRouteEffect(router, padLive, windowLive)"))
    }

    private fun plateShowing(): Boolean =
        compose.onAllNodesWithTag(CompanionRenamePlateTag, useUnmergedTree = true).fetchSemanticsNodes().any { it.layoutInfo.isPlaced }

    /**
     * parking is not disposal, and disposal is what used to close an open book and drop a rename draft when the workspace left the lattice.
     * An automatic Activity entry (a game starting) parks the lattice with the rename plate up. The pane must then close the book WITHOUT committing the
     * rename, tell the host no layer is open, and take no EdgeBack that arrives afterwards: that request belongs to the pane
     * the player can see.
     */
    @Test
    fun `parking a pane with a rename open discards the rename, and a later EdgeBack reaches only the live pane`() {
        val home = payload.copy(homeLanes = listOf(payload.homeLanes.first().copy(tiles = tiles.take(3))))
        homePayload = home
        mount()
        compose.onNodeWithTag(CompanionCreateTomeFaceTag, useUnmergedTree = true).performClick()
        frames(600)
        assertTrue("fixture: the rename plate is up on the new Tome", plateShowing())
        assertTrue("fixture: the host knows a layer is open", bookOpen)

        swipeToActivity()
        assertFalse("the host is told no layer is open", bookOpen)

        closeRequest = 1L
        settle()
        frames(500)
        assertEquals("the hidden pane committed nothing", emptyList<Pair<String, String>>(), renamed)
        assertFalse("and told the host nothing is open", bookOpen)

        swipeToLattice()
        frames(600)
        assertFalse("the discarded rename is not waiting for the player", plateShowing())
        assertFalse(bookOpen)
        assertEquals("still nothing was renamed", emptyList<Pair<String, String>>(), renamed)
    }

    private val feedLive = mutableStateOf(true)
    private var feedSubs = 0
    private var feedSeen = 0
    private var feedRecompositions = 0

    /** The host of the helper test: it reads the flag, as the surface does, and hands the pane a stable body, so a flip recomposes nothing of the body's own. */
    @Composable
    private fun FeedHost() {
        CompanionPaneLiveScope(feedLive.value) { Feed() }
    }

    @Composable
    private fun Feed() {
        val value by remember {
            flow {
                feedSubs += 1
                try {
                    emit(listOf(1, 2))
                    awaitCancellation()
                } finally {
                    feedSubs -= 1
                }
            }
        }.collectAsStateWhileLive(listOf(0))
        SideEffect {
            feedRecompositions += 1
            feedSeen = value.size
        }
    }

    /** D24: the helper alone. No subscriber while parked, the last value kept, and data that comes back equal rebuilds nothing. */
    @Test
    fun `the live collector pauses while parked, keeps its last value, and unchanged data rebuilds nothing`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { FeedHost() }
        compose.mainClock.advanceTimeBy(100L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals(1, feedSubs)
        assertEquals("the feed's value arrived", 2, feedSeen)
        val recompositionsLive = feedRecompositions

        feedLive.value = false
        Snapshot.sendApplyNotifications()
        frames(300)
        assertEquals("no subscriber while parked", 0, feedSubs)
        assertEquals("the last value is kept", 2, feedSeen)
        assertEquals("parking recomposed nothing of the body", recompositionsLive, feedRecompositions)

        feedLive.value = true
        Snapshot.sendApplyNotifications()
        frames(300)
        assertEquals("subscribed again", 1, feedSubs)
        assertEquals("the same data came back and rebuilt nothing", recompositionsLive, feedRecompositions)
    }

    @Test
    fun `a right drag at page 0 reaches the seam, and both panes follow the finger one width apart`() {
        mount()
        val rest = latticeNodes().single().boundsInRoot.left
        val drag = width() * 0.4f
        // Injected through the ROOT: the touch injection keeps the finger in the tagged node's own coordinates, so a node that the strip is moving
        // would carry the injected finger away with it.
        val root = compose.onRoot(useUnmergedTree = true)
        root.performTouchInput {
            down(Offset(width / 2f, centerY))
            repeat(10) { moveBy(Offset(drag / 10f, 0f), delayMillis = 60) }
        }
        // This host leaves the strip where the finger put it, as a strip held for its settle would.
        root.performTouchInput { up() }
        frames(100)
        assertEquals("the release asked the host", 1, ends)
        assertTrue("the host took the finger, a touch slop behind at most: $stripX of $drag; deltas=$deltas", stripX in drag * 0.85f..drag * 1.02f)
        assertTrue("and is panning", panActive)
        assertEquals("the neighbour is composed now", 1, neighbourNodes().size)
        val lattice = latticeNodes().single().boundsInRoot.left
        val activity = compose.onNodeWithTag(NeighbourTag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertEquals("the lattice pane is at the strip's x", stripX, lattice - rest, 2f)
        assertEquals("the Activity pane is one width to its left", stripX - width(), activity, 2f)
        assertEquals("a width apart, no more and no less", width(), lattice - rest - activity, 2f)
        assertTrue("silent while it is only a neighbour: ${activityEnters()}", activityEnters().isEmpty())
    }

    /**
     * D24 rewrote the last step of this test: it used to assert that the scene that was left is DISPOSED once the strip lets go (the lattice node count
     * fell to zero). It is now parked: built and kept, but not placed.
     */
    @Test
    fun `when the strip lands the panes keep their compositions, the neighbour begins its receipts, and the left scene is parked at the end`() {
        mount()
        // The host is panning, at the Activity page's rest (the strip has just landed there).
        stripX = width()
        panActive = true
        Snapshot.sendApplyNotifications()
        frames(100)
        assertEquals(1, mounts)
        assertEquals("both composed while the strip holds", 1, neighbourNodes().size)
        assertEquals(1, latticeNodes().size)
        assertTrue("still a neighbour, so still silent: ${activityEnters()}", activityEnters().isEmpty())

        // The host shows Activity: the same frame, the finger's change takes no handoff of its own.
        snap = true
        activityShown = true
        Snapshot.sendApplyNotifications()
        frames(100)
        assertEquals("the page that was the neighbour was not remounted", 1, mounts)
        assertEquals(0, disposes)
        assertEquals("the lattice is still composed under the strip's hold", 1, latticeNodes().size)
        assertEquals("the Activity page now draws at the origin", 0f, compose.onNodeWithTag(NeighbourTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 2f)
        assertTrue("and its receipts begin at the landing: ${activityEnters()}", activityEnters().isNotEmpty())

        // The strip lets go: the lattice pane is parked (kept, not placed), the Activity page is the only one on screen and was never rebuilt.
        val latticeId = latticeNodes().single().id
        panActive = false
        snap = false
        Snapshot.sendApplyNotifications()
        frames(100)
        assertEquals("the lattice is parked: nothing of it is placed", 0, placed(latticeNodes()))
        assertEquals(1, mounts)
        assertEquals(0, disposes)
        assertEquals(1, neighbourNodes().size)
        assertEquals(1, placed(neighbourNodes()))
        // Unparked by the next pan, it is the node it was: nothing was composed again.
        panActive = true
        stripX = width() * 0.5f
        Snapshot.sendApplyNotifications()
        frames(100)
        assertEquals("the lattice is placed again and is the same layout node", latticeId, latticeNodes().single { it.layoutInfo.isPlaced }.id)
    }

    @Test
    fun `a host with no seam gets the scene it always did, at the origin`() {
        activityShown = true
        mount(seam = false)
        assertEquals(1, neighbourNodes().size)
        assertEquals(0f, compose.onNodeWithTag(NeighbourTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals("no lattice under it", 0, latticeNodes().size)
        assertFalse(panActive)
    }

    @Test
    fun `at rest the strip needs no measurement, and the host's number wins only while it pans`() {
        val w = 1240f
        assertEquals("no host, lattice shown", 0f, companionStripX(null, true, w), 0f)
        assertEquals("no host, Activity shown", w, companionStripX(null, false, w), 0f)
        assertEquals("a host at rest says NaN: the same answer", w, companionStripX(Float.NaN, false, w), 0f)
        assertEquals(0f, companionStripX(Float.NaN, true, w), 0f)
        assertEquals("a panning host's x is used whichever scene is shown", 300f, companionStripX(300f, true, w), 0f)
        assertEquals(300f, companionStripX(300f, false, w), 0f)
        // A cold start straight onto Activity: the host has measured nothing, and the Activity pane (x less one layer width) still sits at the origin.
        assertEquals(0f, companionStripX(Float.NaN, false, 777f) - 777f, 0f)
    }

    /** The revealed Activity pane has no pointer input of its own here: a second finger on it is seen only by the surface's root watch. */
    @Test
    fun `a second finger on the revealed Activity pane cancels the held strip and the lift commits nothing`() {
        mount()
        val root = compose.onRoot(useUnmergedTree = true)
        root.performTouchInput {
            down(Offset(width / 2f, centerY))
            repeat(10) { moveBy(Offset(width * 0.06f, 0f), delayMillis = 60) }
        }
        frames(800)
        root.performTouchInput { moveBy(Offset(1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the strip is past half: $stripX", stripX > width() * 0.5f)
        // Left of the lattice's edge: where the Activity pane now is.
        root.performTouchInput { down(1, Offset(width * 0.1f, centerY)) }
        frames(100)
        assertEquals("cancelled at the second finger's touch", 1, cancels)
        root.performTouchInput { up(0); up(1) }
        frames(300)
        assertEquals("and the lift asked for no switch", 0, ends)
    }

    /** A remount directly onto Activity: the payload it is handed carries no lattice, so the lattice pane takes the host's retained Home, never an empty one. */
    @Test
    fun `a lane-less payload on the Activity page still gives the lattice pane the hosts retained Home`() {
        activityShown = true
        val nowPlaying = CompanionPayload(mode = CompanionMode.NOW_PLAYING, gameId = com.gruning.mimir.rewrite.core.model.GameId("g"), title = "Game")
        mount(surfacePayload = nowPlaying, latticePayload = payload)
        assertEquals("Activity is shown, the lattice is not composed at rest", 0, latticeNodes().size)
        stripX = width() * 0.5f
        panActive = true
        Snapshot.sendApplyNotifications()
        frames(200)
        assertEquals("the back-drag reveals a populated lattice", 1, latticeNodes().size)
        assertTrue("with its tiles: ${compose.onAllNodesWithTag(companionTileTag("p1"), useUnmergedTree = true).fetchSemanticsNodes().size}",
            compose.onAllNodesWithTag(companionTileTag("p1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `without the host's retained Home the same payload reveals no lattice tiles (the red case)`() {
        activityShown = true
        val nowPlaying = CompanionPayload(mode = CompanionMode.NOW_PLAYING, gameId = com.gruning.mimir.rewrite.core.model.GameId("g"), title = "Game")
        mount(surfacePayload = nowPlaying, latticePayload = null)
        stripX = width() * 0.5f
        panActive = true
        Snapshot.sendApplyNotifications()
        frames(200)
        assertTrue("an empty lattice: no tile", compose.onAllNodesWithTag(companionTileTag("p1"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val DISPLAY = 4
        const val NeighbourTag = "workspace-neighbour"

        /** The one pane file that may register focus targets, with its own page-local authority (8e0c3f996); see the scan above. */
        const val ActivityPadFile = "CompanionActivityPad.kt"
    }
}
