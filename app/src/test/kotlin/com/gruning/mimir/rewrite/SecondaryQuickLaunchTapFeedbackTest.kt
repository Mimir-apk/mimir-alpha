package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.view.Display
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * Round 5 (5b): one toast per quick-launch tap on the Bottom, on the display tapped, with the most specific reason.
 *
 * Main's route stays silent on a Bottom request's refusal. It used to answer the Bottom surface with a bare Boolean, so the
 * surface showed its generic "Launch unavailable. Try again." over the route's own reason: a player whose game held Main was
 * told to try again. The route now answers with a [QuickLaunchPrimaryRouteResult], and every Bottom surface sends its tap
 * through [routeSecondaryQuickLaunchTap] or [settleSecondaryQuickLaunchChoiceTap].
 *
 * Driven through the real [CompanionQuickLaunchPrimaryRoute] with a stand-in for the handler Main's composition installs;
 * that closure, and the surface lambdas that call these helpers, are source-pinned in [QuickLaunchPrimaryRouteFeedbackTest].
 * Each request here is sent the way the surfaces send theirs. The toast's display is read from the context it was made with,
 * which [ContextRecordingShadowToast] keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    application = Application::class,
    shadows = [ContextRecordingShadowToast::class],
)
class SecondaryQuickLaunchTapFeedbackTest {

    private lateinit var app: Application
    private lateinit var bottomContext: Context
    private var bottomDisplayId = Display.INVALID_DISPLAY
    private var installed: ((String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult)? = null
    private val mainRequests = mutableListOf<Triple<String, Boolean, Boolean>>()

    /** What a Bottom surface's `onPrimaryQuickLaunch` does. */
    private val onPrimaryQuickLaunch: (String, Boolean) -> QuickLaunchPrimaryRouteResult = { packageName, fromGeneral ->
        CompanionQuickLaunchPrimaryRoute.dispatch(packageName, fromGeneral)
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
        bottomContext = companionFeedbackContext(app, bottomDisplayId)
        assertEquals("premise: the tapped display's context is on the Bottom", bottomDisplayId, displayIdOf(bottomContext))
    }

    @After
    fun tearDown() {
        installed?.let(CompanionQuickLaunchPrimaryRoute::clear)
        CompanionQuickLaunchChoiceState.clear()
        QuickLaunchStore.clearDisplayPreference(app, MainApp)
        ShadowDisplayManager.removeDisplay(bottomDisplayId)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a refused Main request from a Bottom rail shows one toast, with the route's reason, on the Bottom`() {
        QuickLaunchStore.setDisplayPreference(app, MainApp, QuickLaunchDisplayRole.PRIMARY)
        installMainRoute(QuickLaunchPrimaryRouteResult.Refused(gameLaunchSessionHeldMessage(GameLaunchSessionHold.RUNNING)))

        val accepted = railTap()

        assertEquals("premise: the tap reached Main's route once, as Quick launch", listOf(Triple(MainApp, false, false)), mainRequests)
        assertFalse("premise: the route refused", accepted)
        assertEquals("one tap, one toast", 1, ShadowToast.shownToastCount())
        assertEquals("the toast carries the route's own reason", gameLaunchSessionHeldMessage(GameLaunchSessionHold.RUNNING), ShadowToast.getTextOfLatestToast())
        assertEquals("made from a context on the Bottom display", bottomDisplayId, latestToastDisplayId())
        val lines = tapLog()
        assertEquals("one log line names the route's answer, was $lines", 1, lines.size)
        assertTrue("the line says the route's reason was shown: ${lines.single()}", lines.single().endsWith(" copy=route_reason"))
        // O15's device signature (Thor, 2026-09-12): a game running on Main, then a Main pin tapped on the Bottom.
        assertEquals(
            "quick launch tap not accepted package=$MainApp " +
                "main=Refused(reason=A game or app is still open. Close it first, then try again.) copy=route_reason",
            lines.single(),
        )
    }

    @Test
    fun `a refused Main choice on a Bottom sheet shows one toast, with the route's reason, on the Bottom`() {
        installMainRoute(QuickLaunchPrimaryRouteResult.Refused(gameLaunchSessionHeldMessage(GameLaunchSessionHold.RUNNING)))
        assertTrue("premise: the sheet opened", CompanionQuickLaunchChoiceState.request(MainApp, "Main app"))
        val choice = checkNotNull(CompanionQuickLaunchChoiceState.pending.value)

        val accepted = settleSecondaryQuickLaunchChoiceTap(
            context = app,
            choice = choice,
            destination = CompanionQuickLaunchDestination.MAIN,
            onPrimary = onPrimaryQuickLaunch,
            onSecondary = { _, _ -> throw AssertionError("Main was chosen") },
            feedbackContext = { bottomContext },
            unavailableCopy = SurfaceCopy,
        )

        assertEquals("premise: the choice reached Main's route once", listOf(Triple(MainApp, true, false)), mainRequests)
        assertFalse("premise: the route refused", accepted)
        assertEquals("one tap, one toast", 1, ShadowToast.shownToastCount())
        assertEquals("the toast carries the route's own reason", gameLaunchSessionHeldMessage(GameLaunchSessionHold.RUNNING), ShadowToast.getTextOfLatestToast())
        assertEquals("made from a context on the Bottom display", bottomDisplayId, latestToastDisplayId())
    }

    @Test
    fun `control - with no Main route to take the request, the surface's own copy is shown once on the Bottom`() {
        QuickLaunchStore.setDisplayPreference(app, MainApp, QuickLaunchDisplayRole.PRIMARY)
        // No handler installed: Main's composition is not up to take the request.

        val accepted = railTap()

        assertFalse(accepted)
        assertEquals("one tap, one toast", 1, ShadowToast.shownToastCount())
        assertEquals("the surface's own copy", SurfaceCopy, ShadowToast.getTextOfLatestToast())
        assertEquals("made from a context on the Bottom display", bottomDisplayId, latestToastDisplayId())
        assertTrue("the line says the surface's copy was shown: ${tapLog()}", tapLog().single().endsWith(" copy=surface"))
    }

    @Test
    fun `control - an accepted Main request shows no toast`() {
        QuickLaunchStore.setDisplayPreference(app, MainApp, QuickLaunchDisplayRole.PRIMARY)
        installMainRoute(QuickLaunchPrimaryRouteResult.Accepted)

        val accepted = railTap()

        assertEquals(listOf(Triple(MainApp, false, false)), mainRequests)
        assertTrue(accepted)
        assertEquals("a start that fails later is the route's own to report", 0, ShadowToast.shownToastCount())
    }

    /** A pinned rail seat's tap, sent the way the Bottom surfaces send it. */
    private fun railTap(): Boolean = routeSecondaryQuickLaunchTap(
        packageName = MainApp,
        label = "Main app",
        fromGeneral = false,
        onPrimary = onPrimaryQuickLaunch,
        onSecondary = { _, _ -> throw AssertionError("an app saved for Main never launches on the Bottom") },
        feedbackContext = { bottomContext },
        unavailableCopy = SurfaceCopy,
    )

    private fun installMainRoute(answer: QuickLaunchPrimaryRouteResult) {
        val handler: (String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult = { packageName, fromGeneral, noAnimation ->
            mainRequests += Triple(packageName, fromGeneral, noAnimation)
            answer
        }
        CompanionQuickLaunchPrimaryRoute.install(handler)
        installed = handler
    }

    private fun tapLog(): List<String> = ShadowLog.getLogsForTag("MimirCompanionRuntime")
        .map { it.msg }
        .filter { it.startsWith("quick launch tap not accepted package=$MainApp ") }

    private fun latestToastDisplayId(): Int {
        val toast = checkNotNull(ShadowToast.getLatestToast()) { "no toast was shown" }
        val madeWith = checkNotNull(Shadow.extract<ContextRecordingShadowToast>(toast).madeWith) {
            "the toast recorded no context"
        }
        return displayIdOf(madeWith)
    }

    /** `Context.getDisplayId()` is hidden API, so it is read from the framework jar Robolectric runs. */
    private fun displayIdOf(context: Context): Int =
        Context::class.java.getMethod("getDisplayId").invoke(context) as Int

    private companion object {
        const val MainApp = "com.example.saved.for.main"
        const val SurfaceCopy = "Launch unavailable. Try again."
    }
}
