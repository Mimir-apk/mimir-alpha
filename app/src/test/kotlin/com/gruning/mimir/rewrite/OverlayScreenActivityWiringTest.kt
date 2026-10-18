package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-5 triage B2: MainActivity owns the screen-idle authority, and the Bottom's ᛗ, its menu and the overlay-hosted
 * Tabs drawer are native windows none of its dispatchers see. These pins cover the two app-side joins: the Activity
 * routes the overlay's screen activity into its idle controller and takes the observer back on teardown, and the
 * drawer host reports its own touches. Both live in window and lifecycle code a JVM test cannot drive; the routing is
 * tested in [MimirScreenIdleControllerTest].
 */
class OverlayScreenActivityWiringTest {

    private fun source(name: String): String = File("src/main/kotlin/com/gruning/mimir/rewrite/$name").readText()

    private val activity: String by lazy { source("MainActivity.kt") }

    @Test
    fun `the observer is installed as soon as the idle controller exists and routes into it`() {
        val after = activity.substringAfter("screenIdleController = idleController", "")
        assertTrue("screenIdleController = idleController not found", after.isNotEmpty())
        val wiring = after.substringBefore("val idleRestoreGeneration", "")
        assertTrue(
            "the observer must route into this Activity's idle controller",
            wiring.contains("screenIdleController?.onOverlayScreenActivity(activity)"),
        )
        assertTrue(
            "and be handed to the overlay right after the controller exists",
            wiring.lines().any { it.trim() == "companionOverlayController?.setScreenActivityObserver(screenActivityObserver)" },
        )
        assertTrue(
            "the instance keeps its own observer for teardown",
            wiring.lines().any { it.trim() == "overlayScreenActivityObserver = screenActivityObserver" },
        )
    }

    @Test
    fun `teardown clears only this instance's observer, before the idle controller goes`() {
        val start = activity.indexOf("override fun onDestroy()")
        assertTrue("onDestroy not found", start >= 0)
        val body = activity.substring(start).substringBefore("super.onDestroy()")
        val clear = body.indexOf(
            "overlayScreenActivityObserver?.let { companionOverlayController?.clearScreenActivityObserver(it) }",
        )
        assertTrue("onDestroy must hand back its own observer by identity", clear >= 0)
        assertFalse(
            "a null install would unhook a successor whose onCreate already ran",
            body.contains("setScreenActivityObserver(null)"),
        )
        assertTrue(
            "cleared before the idle controller it routes into is dropped",
            clear < body.indexOf("screenIdleController = null"),
        )
    }

    @Test
    fun `the performance HUD reports its first down before building the gesture arbiter`() {
        // R6-P1-05 follow-up: PerformanceOverlayPresenter's own WindowManager window has no FLAG_NOT_TOUCHABLE,
        // so its drag plate takes real touches the Main display dispatch never sees. It must report them
        // through the same process-level bridge the Tabs drawer uses above, on the first down -- before the
        // tap/drag arbiter is even built, so this is unconditional and never depends on the arbiter's outcome.
        val presenter = source("PerformanceOverlayPresenter.kt")
        val gesture = presenter.substringAfter("val down = awaitFirstDown(requireUnconsumed = false)", "")
            .substringBefore("val arbiter = PerformanceOverlayGestureArbiter(", "")
        assertTrue("the span between the first down and the gesture arbiter was not found", gesture.isNotEmpty())
        assertTrue(
            "an admitted first down must report screen activity, the same bridge the Tabs drawer uses",
            gesture.contains("notifyScreenActivity(OverlayScreenActivity.DIRECT)"),
        )
    }

    @Test
    fun `the overlay-hosted drawer reports its touches from a root around the ComposeView`() {
        val host = source("CompanionRecentsOverlayHost.kt")
        val root = host.substringAfter("val panelRoot = object : FrameLayout(appContext) {", "")
        assertTrue("the drawer must attach a root that sees each touch before Compose consumes it", root.isNotEmpty())
        val dispatch = root.substringBefore("return super.dispatchTouchEvent(event)", "")
        assertTrue(
            "that root routes each event through the overlay's screen-activity hook",
            dispatch.contains(
                "overlayScreenActivityFor(event.actionMasked)?.let(this@CompanionRecentsOverlayHost.overlay::notifyScreenActivity)",
            ),
        )
        assertTrue("the panel attached is that root", host.contains("overlay.attachSecondaryPanel(panelRoot)"))
        assertFalse("never the ComposeView itself", host.contains("overlay.attachSecondaryPanel(cv)"))
        val setup = root.substringBefore("val candidate = MountedPanel(", "")
        // Compose builds the window recomposer from the window's root view and throws when that view has no
        // ViewTreeLifecycleOwner ("ViewTreeLifecycleOwner not found from"), so the wrapper needs the owners too.
        for (owner in listOf(
            "setViewTreeLifecycleOwner(h)",
            "setViewTreeViewModelStoreOwner(viewModelStoreOwner)",
            "setViewTreeSavedStateRegistryOwner(h)",
        )) {
            assertTrue("the root needs $owner, or attaching the drawer window crashes", setup.contains(owner))
        }
        assertEquals("the ComposeView is added to the root once", 1, Regex("""addView\(\s*cv\b""").findAll(setup).count())
    }
}
