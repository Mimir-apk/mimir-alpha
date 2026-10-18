package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * Ledger D12: Mimir's own message in place of an Android toast, on the screen it belongs to. The registry ([MimirDisplayMessages]) routes a message to ONE display's host and
 * hands it back to the system toast when that display has no live host; the host ([MimirDisplayMessageHost]) draws it with the shared rail. On the Bottom (display 4) the message
 * shows on the Bottom's host and never on Main's, at the Thor Bottom's size (537.7 x 468.3 dp), at font 1.0 and 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirDisplayMessagesTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369
    private var density = 0f
    /** Each window's own answer to "is anyone looking": a plain flag, flipped by a test the way the Presentation's park/reveal and the Activity's pause/resume flip theirs. */
    private val bottomLive = MutableStateFlow(true)
    private val mainLive = MutableStateFlow(true)

    /** What the Settings destination hands the rail (its connection feedback), as the app host does. */
    private var settingsToast by mutableStateOf<MimirMessage?>(null)

    @Before
    fun setUp() {
        MimirDisplayMessages.resetForTest()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        MimirDisplayMessages.resetForTest()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Main's screen and the Bottom's, one above the other, each the size of the Thor Bottom, each with its own host. */
    private fun mountBothScreens(fontScale: Float) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    Column {
                        Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag(MainScreen), contentAlignment = Alignment.BottomCenter) {
                            MimirDisplayMessageHost(displayId = MainDisplay, liveness = remember { MimirHostLiveness.of(mainLive) }, bottomInset = 24.dp)
                            MimirForwardMessageToHost(message = settingsToast, onForwarded = { id -> if (settingsToast?.eventId == id) settingsToast = null })
                        }
                        Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag(BottomScreen), contentAlignment = Alignment.BottomCenter) {
                            MimirDisplayMessageHost(
                                displayId = BottomDisplay,
                                liveness = remember { MimirHostLiveness.of(bottomLive) },
                                bottomInset = MimirBottomMessageRailBottomInset,
                                growsToFit = true,
                                railWidthFraction = MimirBottomMessageRailWidthFraction,
                            )
                        }
                    }
                }
            }
        }
        advance(300L)
    }

    /** In two halves: the host takes a posted message in on one frame (a flow collected into state), and draws it on a later one. */
    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms - ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /**
     * The rail speaks its message as ONE live-region node whose description is "<class>. <text>" - the only node with exactly that description. (The card inside it also draws the
     * text through the chrome-text component, whose node carries the bare text as its own description; that node shows in the unmerged tree but is cleared from what a screen reader
     * sees, so it is not a second announcement and not matched here.) Messages posted by these tests are Warnings, the registry's default.
     */
    private fun railMessage(text: String, messageClass: String = "Warning"): SemanticsMatcher = hasContentDescription("$messageClass. $text")

    private fun shownOn(screen: String, text: String, messageClass: String = "Warning"): Int =
        compose.onAllNodes(railMessage(text, messageClass) and hasAnyAncestor(hasTestTag(screen)), useUnmergedTree = true).fetchSemanticsNodes().size

    /** The rail's live-region nodes on [screen], whatever they say: one per rail, so two rails sharing a screen would show two. */
    private fun railNodesOn(screen: String): Int =
        compose.onAllNodes(
            (hasContentDescription("Warning. ", substring = true) or hasContentDescription("Confirmation. ", substring = true) or hasContentDescription("Guidance. ", substring = true)) and
                hasAnyAncestor(hasTestTag(screen)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

    // ── the registry ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a message is posted only to a display with a live host, and only that display's host is given it`() {
        assertFalse("no host, nothing posted", MimirDisplayMessages.post(BottomDisplay, "Nobody home"))
        val detachBottom = MimirDisplayMessages.attach(BottomDisplay)
        assertTrue(MimirDisplayMessages.hasLiveHost(BottomDisplay))
        assertFalse("Main has no host: a Bottom host does not take Main's messages", MimirDisplayMessages.post(MainDisplay, "For Main"))
        assertTrue(MimirDisplayMessages.post(BottomDisplay, "For the Bottom", MimirMessageClass.Guidance, "and a second line"))
        val posted = runBlocking { MimirDisplayMessages.messages(BottomDisplay).first() }
        assertEquals("For the Bottom", posted?.text)
        assertEquals(MimirMessageClass.Guidance, posted?.messageClass)
        assertEquals("and a second line", posted?.supportingText)
        assertNull("and Main's host is given nothing", runBlocking { MimirDisplayMessages.messages(MainDisplay).first() })

        MimirDisplayMessages.delivered(BottomDisplay, posted!!.eventId + 1)
        assertEquals("a delivery for another event takes nothing", "For the Bottom", runBlocking { MimirDisplayMessages.messages(BottomDisplay).first() }?.text)
        MimirDisplayMessages.delivered(BottomDisplay, posted.eventId)
        assertNull("what the host has taken in is not shown again", runBlocking { MimirDisplayMessages.messages(BottomDisplay).first() })

        MimirDisplayMessages.post(BottomDisplay, "Left waiting")
        detachBottom()
        assertFalse("a host that is gone leaves no live host", MimirDisplayMessages.hasLiveHost(BottomDisplay))
        assertNull("and nothing waiting for a later one", runBlocking { MimirDisplayMessages.messages(BottomDisplay).first() })
        assertFalse(MimirDisplayMessages.post(BottomDisplay, "Too late"))
    }

    @Test
    fun `two hosts for one display both count, and the display is live until the last one goes`() {
        val first = MimirDisplayMessages.attach(BottomDisplay)
        val second = MimirDisplayMessages.attach(BottomDisplay)
        first()
        first()
        assertTrue("the second host is still up", MimirDisplayMessages.hasLiveHost(BottomDisplay))
        second()
        assertFalse(MimirDisplayMessages.hasLiveHost(BottomDisplay))
    }

    @Test
    fun `with no live host the message is the system toast it replaced, same text`() {
        showMimirMessage(context(), "Launch unavailable. Try again.")
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("Launch unavailable. Try again.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `with a live host the message goes to the host and no system toast is made`() {
        MimirDisplayMessages.attach(MainDisplay)
        showMimirMessage(context(), "Launch unavailable. Try again.")
        assertEquals("no system toast", 0, ShadowToast.shownToastCount())
        assertEquals("Launch unavailable. Try again.", runBlocking { MimirDisplayMessages.messages(MainDisplay).first() }?.text)
    }

    @Test
    fun `a message made for the Bottom's display is not Main's, and one made for a display with no host is a toast`() {
        MimirDisplayMessages.attach(MainDisplay)
        showMimirMessage(context(), "Bottom only", displayId = BottomDisplay)
        assertNull("Main's host is not given a Bottom message", runBlocking { MimirDisplayMessages.messages(MainDisplay).first() })
        assertEquals("with no Bottom host it is the system toast, as before", "Bottom only", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an application context belongs to the default display`() {
        assertEquals(android.view.Display.DEFAULT_DISPLAY, mimirMessageDisplayId(context()))
    }

    // ── the hosts, on the real Bottom size ──────────────────────────────────────────────────────────────────────────────────────────

    private fun assertBottomMessage(fontScale: Float) {
        mountBothScreens(fontScale)
        val text = "Tweaker is not ready on the bottom screen"
        assertTrue(MimirDisplayMessages.post(BottomDisplay, text))
        advance(400L)
        assertEquals("font $fontScale: the message shows on the Bottom", 1, shownOn(BottomScreen, text))
        assertEquals("font $fontScale: and not on Main", 0, shownOn(MainScreen, text))
        assertEquals(
            "font $fontScale: and a screen reader meets it once: the merged tree holds one node that carries the text",
            1,
            compose.onAllNodes(hasContentDescription(text, substring = true) and hasAnyAncestor(hasTestTag(BottomScreen)), useUnmergedTree = false).fetchSemanticsNodes().size,
        )
        val shown = compose.onAllNodes(railMessage(text) and hasAnyAncestor(hasTestTag(BottomScreen)), useUnmergedTree = true).fetchSemanticsNodes().single()
        val screen = compose.onAllNodes(hasTestTag(BottomScreen), useUnmergedTree = true).fetchSemanticsNodes().single()
        val left = (shown.positionInRoot.x - screen.positionInRoot.x) / density
        val right = left + shown.size.width / density
        val bottom = (shown.positionInRoot.y - screen.positionInRoot.y) / density + shown.size.height / density
        println("D12-RAIL font=$fontScale left=$left right=$right bottom=$bottom scene=${widthDp}x$heightDp")
        // The rail stands clear of the bottom edge by the inset (the dock's handle and the page diamonds are in that strip), inside the middle 65% of the scene (as wide in dp as Main's 42%).
        assertTrue("font $fontScale: held above the dock's strip: bottom $bottom vs ${heightDp - MimirBottomMessageRailBottomInset.value}", bottom <= heightDp - MimirBottomMessageRailBottomInset.value + 0.6f)
        val railLeft = widthDp * (1f - MimirBottomMessageRailWidthFraction) / 2f
        val railRight = widthDp - railLeft
        assertTrue("font $fontScale: inside the rail's width: $left..$right vs $railLeft..$railRight", left >= railLeft - 0.6f && right <= railRight + 0.6f)
        assertTrue("font $fontScale: inside the scene", left >= 0f && right <= widthDp)
    }

    @Test
    fun `a message for the Bottom shows on the Bottom's host and not on Main's at font 1_0`() = assertBottomMessage(1.0f)

    @Test
    fun `a message for the Bottom shows on the Bottom's host and not on Main's at font 2_0`() = assertBottomMessage(2.0f)

    @Test
    fun `a message for Main shows on Main's host and not on the Bottom's`() {
        mountBothScreens(1.0f)
        val text = "Library update in progress"
        assertTrue(MimirDisplayMessages.post(MainDisplay, text))
        advance(400L)
        assertEquals(1, shownOn(MainScreen, text))
        assertEquals(0, shownOn(BottomScreen, text))
    }

    @Test
    fun `a host that is not live takes no message, so a parked or dark window leaves it to the system toast`() {
        bottomLive.value = false
        mountBothScreens(1.0f)
        assertFalse("the parked Bottom window is not a host", MimirDisplayMessages.hasLiveHost(BottomDisplay))
        showMimirMessage(context(), "Seen over the app", displayId = BottomDisplay)
        assertEquals("Seen over the app", ShadowToast.getTextOfLatestToast())
        advance(400L)
        assertEquals(0, shownOn(BottomScreen, "Seen over the app"))
        bottomLive.value = true
        advance(100L)
        assertTrue("and live again once the window is", MimirDisplayMessages.hasLiveHost(BottomDisplay))
    }

    // ── a parked window: liveness is the window's own, asked at the post ────────────────────────────────────────────────────────────

    @Test
    fun `a parked Bottom answers a post from the window's own state with no recomposition - the system toast, and nothing kept for the reveal`() {
        mountBothScreens(1.0f)
        assertTrue("sanity: the Bottom starts live", MimirDisplayMessages.hasLiveHost(BottomDisplay))
        // The Presentation parks (its lifecycle drops to CREATED and its frame clock stops): from here to the reveal no frame is sent and nothing recomposes.
        bottomLive.value = false
        assertFalse("the display is not live at once, before any frame: it is the window's own answer, not what its composition last saw", MimirDisplayMessages.hasLiveHost(BottomDisplay))
        showMimirMessage(context(), "App couldn't be launched", displayId = BottomDisplay)
        assertEquals("a Bottom failure made while an app is parked over it is the system toast", "App couldn't be launched", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
        assertNull("and nothing is kept for the reveal", runBlocking { MimirDisplayMessages.messages(BottomDisplay).first() })

        bottomLive.value = true
        advance(400L)
        assertEquals("the reveal shows no stale message", 0, shownOn(BottomScreen, "App couldn't be launched"))
        assertTrue("and the window takes messages again", MimirDisplayMessages.post(BottomDisplay, "Fresh news"))
        advance(400L)
        assertEquals(1, shownOn(BottomScreen, "Fresh news"))
    }

    @Test
    fun `a message the host had not yet drawn when the window parked is dropped, not shown late on the reveal`() {
        mountBothScreens(1.0f)
        assertTrue(MimirDisplayMessages.post(BottomDisplay, "Late news"))
        bottomLive.value = false
        advance(50L)
        assertNull("what was waiting is dropped when the window goes dark", runBlocking { MimirDisplayMessages.messages(BottomDisplay).first() })
        bottomLive.value = true
        advance(400L)
        assertEquals("and the reveal does not draw it", 0, shownOn(BottomScreen, "Late news"))
    }

    @Test
    fun `a card that was up when the window parked is not on screen when it is revealed`() {
        mountBothScreens(1.0f)
        assertTrue(MimirDisplayMessages.post(BottomDisplay, "Up before the park"))
        advance(400L)
        assertEquals("sanity: the card is up", 1, shownOn(BottomScreen, "Up before the park"))
        bottomLive.value = false
        advance(50L)
        bottomLive.value = true
        advance(20L)
        assertEquals("the rail was remounted on the way: no card, though most of its 2.4 s were left", 0, shownOn(BottomScreen, "Up before the park"))
    }

    @Test
    fun `Main follows its Activity's lifecycle - live only while resumed, asked at the post`() {
        class TestOwner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val liveness = MimirHostLiveness.ofLifecycle(owner.lifecycle)
        assertTrue(liveness.isLiveNow())
        val seen = ArrayList<Boolean>()
        val collecting = CoroutineScope(Dispatchers.Unconfined).launch { liveness.changes.collect { seen += it } }
        owner.registry.currentState = Lifecycle.State.STARTED
        assertFalse("a paused Activity (a game in front of Mimir) is not live", liveness.isLiveNow())
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(liveness.isLiveNow())
        collecting.cancel()
        assertTrue("the change feed said so too, without any recomposition: $seen", seen.contains(false) && seen.last())
    }

    // ── one rail per display ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Settings feedback and a moved message within 2_4 s share Main's one rail - one rail node on every frame, the later message wins`() {
        mountBothScreens(1.0f)
        val settingsText = "Connection successful!"
        val movedText = "Library update in progress"
        settingsToast = MimirMessage(eventId = 7L, text = settingsText, messageClass = MimirMessageClass.Confirmation)
        advance(300L)
        assertEquals("Settings' feedback shows on Main's rail", 1, shownOn(MainScreen, settingsText, "Confirmation"))
        assertNull("and its producer's slot is empty: the host took it", settingsToast)

        // 300 ms later, well inside the first message's 2.4 s window, another Main message arrives.
        assertTrue(MimirDisplayMessages.post(MainDisplay, movedText))
        val railNodes = ArrayList<Int>()
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            railNodes += railNodesOn(MainScreen)
        }
        println("D12-ONE-RAIL rail nodes on Main per frame: $railNodes")
        assertTrue("a single rail on every frame (a second rail at the same spot would show two): $railNodes", railNodes.all { it == 1 })
        assertEquals("the later message is the one on the rail", 1, shownOn(MainScreen, movedText))
        assertEquals("and the earlier is gone, not drawn under it", 0, shownOn(MainScreen, settingsText, "Confirmation"))
        assertEquals("nothing leaked to the Bottom", 0, railNodesOn(BottomScreen))
    }

    @Test
    fun `Settings feedback handed over while Main is not live is dropped, not kept for later`() {
        mountBothScreens(1.0f)
        mainLive.value = false
        settingsToast = MimirMessage(eventId = 9L, text = "Connection successful!", messageClass = MimirMessageClass.Confirmation)
        advance(300L)
        assertNull("the producer is told it was taken (it would have gone unseen)", settingsToast)
        mainLive.value = true
        advance(400L)
        assertEquals("and the Activity coming back does not show it as news", 0, railNodesOn(MainScreen))
    }

    // ── the wiring ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun source(name: String): String =
        (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()

    @Test
    fun `each screen draws its own host, the Bottom's last in its window and live only while the window is`() {
        val presentation = source("MimirCompanionPresentation.kt")
        val host = presentation.substringAfter("MimirDisplayMessageHost(").substringBefore(")\n")
        assertTrue("the Bottom's host is for its own display: $host", host.contains("displayId = receiptDisplayId"))
        assertTrue("and only while its window is live, as the window itself answers: $host", host.contains("liveness = messagesLiveness"))
        assertTrue("with the dock-clear inset: $host", host.contains("bottomInset = MimirBottomMessageRailBottomInset"))
        assertTrue("the band grows to the card, as Main's does, so a larger font's extra line is not cut by the pill: $host", host.contains("growsToFit = true"))
        assertTrue("on the rail that is as wide in dp as Main's: $host", host.contains("railWidthFraction = MimirBottomMessageRailWidthFraction"))
        assertTrue(
            "after the cold-start cover, so it is the last thing the window draws",
            presentation.indexOf("CompanionMannazCover(visible = coldCoverHeld") < presentation.indexOf("MimirDisplayMessageHost("),
        )
        assertTrue("windowLive answers it at the moment of a post, and its flow follows it", presentation.contains("MimirHostLiveness(isLiveNow = { windowLive.isLive }, changes = windowLive.liveFlow)"))
        assertFalse("and no composition-driven flag stands in for it: a parked window's frame clock is stopped", presentation.contains("windowLive.liveFlow.collectAsState"))
        val mainHost = source("MimirAppHost.kt")
        assertTrue("Main's host is mounted at the app host's root, after the overlays", mainHost.indexOf("MimirAppOverlayHost(bindings, activeOverlay, reducedMotion)") < mainHost.indexOf("MimirActivityMessageHost("))
    }

    @Test
    fun `the ROM folder prompt stays a system toast where the system picker opens at once, and is a message where Mimir draws the next screen itself`() {
        val settings = source("MimirSettingsCoordinator.kt")
        val inApp = settings.substringAfter("AddGamesRoute.IN_APP_BROWSER -> {").substringBefore("FileBrowserStore.open(")
        assertTrue("the in-app browser is Mimir's own overlay: the message is seen", inApp.contains("showMimirMessage(context, \"Choose your Mimir ROM folder first\")"))
        val systemPicker = settings.substringAfter("AddGamesRoute.SYSTEM_PICKER -> Unit").substringBefore("primaryTreeLauncher.launch(null)")
        assertTrue(
            "the system folder picker covers Main on the next line, so only a system toast is ever seen",
            systemPicker.contains("Toast.makeText(context, \"Choose your Mimir ROM folder first\", Toast.LENGTH_SHORT).show()"),
        )
        assertFalse("and no message is posted to a rail that is covered at once", systemPicker.contains("showMimirMessage("))
    }

    @Test
    fun `Main has one message rail - Settings and the Library hand theirs to it instead of mounting rails of their own`() {
        val host = source("MimirAppHost.kt")
        assertFalse("no second rail in the app host", host.contains("MimirHintToastRail("))
        assertTrue(host.contains("MimirForwardMessageToHost(message = feedback.toast, onForwarded = feedback::consumeDeliveredMessage)"))
        assertTrue("the Library is told its host draws the refusal", host.contains("parkedRefusalOnHostRail = true"))
        assertTrue(
            "and the host forwards it",
            host.contains("MimirForwardMessageToHost(message = parkedRefusalToast, onForwarded = core.libraryViewModel::consumeDeliveredParkedRefusalToast)"),
        )
        val libraryPath = "feature/library/LibraryScreen.kt"
        val library = (File("../feature-library/src/main/kotlin/com/gruning/mimir/rewrite/$libraryPath").takeIf { it.isFile }
            ?: File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/$libraryPath")).readText()
        assertTrue("a host that draws the refusal says so and the screen draws none", library.contains("if (!parkedRefusalOnHostRail) {"))
        assertTrue("the default keeps the screen's own rail for every other host", library.contains("parkedRefusalOnHostRail: Boolean = false"))
    }

    @Test
    fun `the Bottom's own sites say their message through the registry on the display they belong to`() {
        listOf("CompanionPickedGameRefusal.kt", "SecondaryQuickLaunchTap.kt", "MButtonRuntime.kt").forEach { name ->
            val text = source(name)
            assertTrue("$name says its message through showMimirMessage", text.contains("showMimirMessage("))
            assertFalse("$name makes no system toast of its own", text.contains("Toast.makeText"))
        }
        val presentation = source("MimirCompanionPresentation.kt")
        assertFalse("the Presentation makes no system toast", presentation.contains("Toast.makeText"))
        assertTrue("a platform tile's tap answers while a game runs (D8)", presentation.contains("if (companionLivePlatformTapWaitsForGame()) {"))
        assertTrue(presentation.contains("showMimirMessage(context, CompanionShelfOpensAfterGameCopy, MimirMessageClass.Guidance)"))
    }

    @Test
    fun `a platform tile tapped while a game runs is told the shelf opens when the game ends`() {
        assertTrue(companionPlatformTapWaitsForGame(com.gruning.mimir.rewrite.core.model.CompanionMode.NOW_PLAYING))
        listOf(
            com.gruning.mimir.rewrite.core.model.CompanionMode.HOME,
            com.gruning.mimir.rewrite.core.model.CompanionMode.RUNNING_APP,
            com.gruning.mimir.rewrite.core.model.CompanionMode.IDLE,
        ).forEach { mode ->
            assertFalse("$mode opens the shelf (or is the quiet cold start), so there is nothing to wait for", companionPlatformTapWaitsForGame(mode))
        }
        // The two rules agree: the mode that waits is exactly the one the admission refuses for a platform route.
        val gbc = CompanionLibraryRoute.Destination.Platform("gbc", "Game Boy Color")
        com.gruning.mimir.rewrite.core.model.CompanionMode.values().forEach { mode ->
            val refused = companionPlatformShelfDestination(gbc, mode, reclaimHold = false) == null
            assertEquals("$mode: waiting for the game is the admission's NOW_PLAYING refusal", mode == com.gruning.mimir.rewrite.core.model.CompanionMode.NOW_PLAYING, companionPlatformTapWaitsForGame(mode) && refused)
        }
        assertEquals("The shelf opens when the game ends.", CompanionShelfOpensAfterGameCopy)
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val MainDisplay = 0
        const val BottomDisplay = 4
        const val MainScreen = "main-screen"
        const val BottomScreen = "bottom-screen"
    }
}
