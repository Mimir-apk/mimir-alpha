package com.gruning.mimir.rewrite

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor's way to the Activity page's pad ([CompanionActivityPadRoute]): the rule it applies (no sink is no event consumed; a sink answers for itself), and the
 * order the floor asks in (the open menu's pad owner, then the page, then Main's sink, for keys and for motion), pinned on the floor's source the way the menu's own
 * order is.
 */
class CompanionActivityPadRouteTest {

    private fun floorSource(): String =
        (File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionSecondaryHomeActivity.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionSecondaryHomeActivity.kt")).readText()

    @Test
    fun `with no page registered nothing is consumed, and a registered page answers for itself`() {
        assertFalse(CompanionActivityPadRoute.route<String>(null, "event"))
        assertTrue(CompanionActivityPadRoute.route({ it == "event" }, "event"))
        assertFalse("a page that declines leaves the event to Main", CompanionActivityPadRoute.route({ false }, "event"))
        val seen = mutableListOf<String>()
        CompanionActivityPadRoute.route({ seen += it; true }, "once")
        assertEquals(listOf("once"), seen)
    }

    @Test
    fun `the floor asks the menu's pad owner, then the page, then Main, for keys and for motion`() {
        val source = floorSource()
        val keyOwner = source.indexOf("BottomPadInput.routeKey(event)")
        val keyPage = source.indexOf("CompanionActivityPadRoute.routeKey(event)")
        val keySink = source.indexOf("MimirMainInputSink.routeKey(event)")
        assertTrue("the open menu is asked first", keyOwner in 0 until keyPage)
        assertTrue("then the page, before Main's sink", keyPage in 0 until keySink)
        val motionOwner = source.indexOf("BottomPadInput.routeMotion(event)")
        val motionPage = source.indexOf("CompanionActivityPadRoute.routeMotion(event)")
        val motionSink = source.indexOf("MimirMainInputSink.routeMotion(event)")
        assertTrue(motionOwner in 0 until motionPage)
        assertTrue(motionPage in 0 until motionSink)
        assertTrue("a consumed event returns true", "if (CompanionActivityPadRoute.routeKey(event)) return true" in source)
        assertTrue("same for motion", "if (CompanionActivityPadRoute.routeMotion(event)) return true" in source)
        assertTrue(
            "a system key still goes to super before anyone is asked",
            source.indexOf("if (isMimirSystemKey(event.keyCode)) return super.dispatchKeyEvent(event)") < keyOwner,
        )
    }

    @Test
    fun `the page's pad sources keep the repo's focus and colour rules`() {
        listOf("CompanionActivityPad.kt", "CompanionActivityAchievementHub.kt", "CompanionActivityAchievementFilterHeader.kt").forEach { name ->
            val text = (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile }
                ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()
            assertFalse("$name never uses Compose focus", text.contains("focusable()") || text.contains("focusTarget()"))
            assertFalse("$name never names a Compose focus requester", text.contains("FocusRequester"))
            assertFalse("$name has no hex colour literal", Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(text))
        }
    }

    private fun source(name: String): String =
        (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()

    @Test
    fun `a window is live only while it is neither parked nor dark, and its flow starts from the current answer`() {
        val parked = MutableStateFlow(false)
        val screen = MutableStateFlow(true)
        val live = CompanionWindowLive(parked, screen)
        assertTrue(live.isLive)
        parked.value = true
        assertFalse("parked behind another app", live.isLive)
        parked.value = false
        assertTrue("a reveal", live.isLive)
        screen.value = false
        assertFalse("screen off", live.isLive)
        parked.value = true
        screen.value = true
        assertFalse("the screen coming back does not reveal a window that is still parked", live.isLive)
        assertEquals("the flow starts from the current answer", false, runBlocking { live.liveFlow.first() })
        parked.value = false
        assertEquals(true, runBlocking { live.liveFlow.first() })
        assertTrue("a host that is not the Bottom's window is always live", CompanionWindowLive.Always.isLive)
    }

    @Test
    fun `the screen is off after SCREEN_OFF, on after SCREEN_ON, and unchanged by anything else`() {
        assertFalse(screenOnAfter("android.intent.action.SCREEN_OFF", true))
        assertTrue(screenOnAfter("android.intent.action.SCREEN_ON", false))
        assertTrue(screenOnAfter("some.other.action", true))
        assertFalse(screenOnAfter(null, false))
    }

    @Test
    fun `the window's liveness is the Presentation's own parked state and the screen, handed to the surface and the hub`() {
        val presentation = source("MimirCompanionPresentation.kt")
        assertTrue("the one parked state", presentation.contains("private val parkedBehindSecondaryHost = MutableStateFlow(false)"))
        assertTrue("liveness reads it and the screen", presentation.contains("private val windowLive = CompanionWindowLive(parkedBehindSecondaryHost, screenOn)"))
        val park = presentation.substringAfter("internal fun parkBehindSecondaryHost(): Boolean {").substringBefore("private fun <T : Any> armFrameCommit")
        assertTrue("a park flips it", park.contains("parkedBehindSecondaryHost.value = true") && park.contains("host.park()"))
        val reveal = presentation.substringAfter("internal fun rebindRenderOperation(operationId: UUID): Boolean {").substringBefore("internal fun currentRenderOperationId()")
        assertTrue("a reveal flips it back", reveal.contains("parkedBehindSecondaryHost.value = false") && reveal.contains("host.resume()"))
        val stop = presentation.substringAfter("override fun onStop() {").substringBefore("host.destroy()")
        assertTrue("a dismissed window is not live", stop.contains("parkedBehindSecondaryHost.value = true"))
        assertTrue("the screen is watched from creation", presentation.contains("stopWatchingScreen = watchScreenState(context, screenOn)"))
        assertTrue("and let go of at the end", stop.contains("stopWatchingScreen?.invoke()"))
        assertTrue("the surface that holds the hub is given the liveness", presentation.contains("windowLive = windowLive,"))
        val surface = source("CompanionWorkspaceSurface.kt")
        assertTrue("and passes it on, with the scene's say", surface.contains("padLive = workspaceScene == CompanionActivityScene.ACHIEVEMENTS,") && surface.contains("windowLive = windowLive,"))
        val hub = source("CompanionActivityAchievementHub.kt")
        assertTrue("the hub holds its route through the one effect", hub.contains("CompanionActivityPadRouteEffect(router, padLive, windowLive)"))
        assertFalse("and no longer registers on pane liveness alone", hub.contains("CompanionActivityPadRoute.register("))
    }

    @Test
    fun `a search the keyboard no longer exists for is never the pad's keyboard`() {
        val hub = source("CompanionActivityAchievementHub.kt")
        assertTrue("the keyboard follows the header", hub.contains("val keyboardShown = showHeader && keyboardOpen"))
        assertTrue("the pad's host follows it", hub.contains("rememberMimirKeyboardHost(pad.authority, active = keyboardShown && keyboardByPad)"))
        assertTrue("the pad's routing follows it", hub.contains("if (keyboardShown) {"))
        assertTrue("and what is drawn", hub.contains("open = keyboardShown,"))
        assertTrue("B still closes a keyboard that cannot hear the pad", hub.contains("if (!handled && action == ControllerAction.CANCEL) keyboardOpen = false"))
    }
}
