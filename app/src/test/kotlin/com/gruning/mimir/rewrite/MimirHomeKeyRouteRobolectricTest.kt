package com.gruning.mimir.rewrite

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O1: the routing around [decideMainHomeKey], with real Intents. The recovery start is built by the product's own
 * `AndroidMainHomeRecoveryPlatform` and read back from the started-activity queue, so the marker the router ignores is the
 * marker the recovery really sends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class MimirHomeKeyRouteRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The command bus is process-wide; another test's leftover must not read as this one's, nor ours as theirs. */
    @Before
    @After
    fun clearPublishedCommand() {
        MButtonNavigationCommands.pending.value?.let { MButtonNavigationCommands.acknowledge(it.id) }
    }

    private class FakePlatform(
        var started: Boolean = true,
        var focused: Boolean = true,
        var mainDisplay: Boolean = true,
        var firstRun: Boolean = false,
        var sessions: MimirSessionSnapshot = MimirSessionSnapshot(),
        override val source: MainHomeKeySource = MainHomeKeySource.MAIN,
        var mainIsResumed: Boolean = true,
    ) : MainHomeKeyPlatform {
        var published = 0
        val lines = mutableListOf<String>()
        override fun startedBeforeIntent() = started
        override fun hadWindowFocusBeforeIntent() = focused
        override fun onMainDisplay() = mainDisplay
        override fun mainResumed() = mainIsResumed
        override fun firstRun() = firstRun
        override fun sessions() = sessions
        override fun publishHome() {
            published++
        }
        override fun log(line: String) {
            lines += line
        }
    }

    /** What the system delivers for the HOME key: MAIN + HOME, NEW_TASK | RESET_TASK_IF_NEEDED, the resolved component set. */
    private fun systemHomePress(extraFlags: Int = 0) = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setComponent(ComponentName(context.packageName, "${context.packageName}.MainActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or extraFlags)

    /** The intent Mimir's recovery really sends, captured from the product's own platform half. */
    private fun recoveryStart(): Intent {
        AndroidMainHomeRecoveryPlatform(context).start(mainHomeStartRequest(context.packageName))
        return shadowOf(context as Application).nextStartedActivity
    }

    @Test
    fun `a user HOME press with Mimir in front publishes exactly one Home command`() {
        val platform = FakePlatform()
        assertEquals(MainHomeKeyDecision.RETURN_HOME, routeMainHomeKeyIntent(systemHomePress(), platform))
        assertEquals(1, platform.published)
        assertEquals(listOf("home key decision=reset reason=return_home source=main"), platform.lines)
    }

    @Test
    fun `the same press from a game, a stopped Mimir or the Bottom's display does nothing`() {
        val cases = listOf(
            "not_in_front" to FakePlatform(focused = false),
            "not_in_front" to FakePlatform(started = false, focused = false),
            "other_display" to FakePlatform(mainDisplay = false),
        )
        cases.forEach { (reason, platform) ->
            routeMainHomeKeyIntent(systemHomePress(), platform)
            assertEquals(reason, 0, platform.published)
            assertEquals(listOf("home key decision=skip reason=$reason source=main"), platform.lines)
        }
    }

    @Test
    fun `a task the start itself brought to the front is left as the platform arranged it`() {
        val platform = FakePlatform()
        val decision = routeMainHomeKeyIntent(systemHomePress(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT), platform)
        assertEquals(MainHomeKeyDecision.BROUGHT_TO_FRONT, decision)
        assertEquals(0, platform.published)
    }

    @Test
    fun `Mimir's own recovery start is ignored even with Mimir in front`() {
        val recovery = recoveryStart()
        assertEquals(MainHomeIntentKind.INTERNAL_HOME, recovery.mainHomeIntentKind())
        val platform = FakePlatform()
        assertEquals(MainHomeKeyDecision.INTERNAL_START, routeMainHomeKeyIntent(recovery, platform))
        assertEquals(0, platform.published)
        assertEquals(listOf("home key decision=skip reason=internal_start source=main"), platform.lines)
    }

    @Test
    fun `the marker did not change the recovery start's shape`() {
        val recovery = recoveryStart()
        assertEquals(Intent.ACTION_MAIN, recovery.action)
        assertEquals(setOf(Intent.CATEGORY_HOME), recovery.categories)
        assertNull("a component would retype the start STANDARD (see mainHomeStartRequest)", recovery.component)
        assertEquals(context.packageName, recovery.`package`)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, recovery.flags)
        assertTrue(recovery.getBooleanExtra(InternalHomeStartExtra, false))
    }

    @Test
    fun `a system HOME press carries no marker`() {
        val press = systemHomePress()
        assertFalse(press.hasExtra(InternalHomeStartExtra))
        assertEquals(MainHomeIntentKind.USER_HOME, press.mainHomeIntentKind())
    }

    @Test
    fun `onboarding and a launch in flight are never reset`() {
        val firstRun = FakePlatform(firstRun = true)
        assertEquals(MainHomeKeyDecision.FIRST_RUN, routeMainHomeKeyIntent(systemHomePress(), firstRun))
        assertEquals(0, firstRun.published)

        val launching = FakePlatform(
            sessions = MimirSessionSnapshot(
                primaryAppSession = MimirAppSession(
                    operationId = "app:1",
                    sequence = 1L,
                    packageName = "com.example.app",
                    label = "Example",
                    placement = MimirAppPlacement.PRIMARY,
                    returnAnchor = PrimaryReturnOrigin(destination = MimirDestination.HOME.name),
                    startedAtEpochMs = 1L,
                    phase = MimirSessionPhase.PREPARING,
                ),
            ),
        )
        assertEquals(MainHomeKeyDecision.HAND_OFF, routeMainHomeKeyIntent(systemHomePress(), launching))
        assertEquals(0, launching.published)
    }

    @Test
    fun `an intent that is not a HOME start costs nothing and logs nothing`() {
        val platform = FakePlatform()
        val launcherIcon = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val mButtonCommand = Intent()
            .setClassName(context.packageName, "${context.packageName}.MainActivity")
            .putExtra(MButtonIntentAuthority.ExtraCommandToken, "token")
        listOf(launcherIcon, mButtonCommand, Intent(), Intent(Intent.ACTION_VIEW)).forEach { intent ->
            assertEquals(MainHomeKeyDecision.NOT_HOME_INTENT, routeMainHomeKeyIntent(intent, platform))
        }
        assertEquals(0, platform.published)
        assertTrue(platform.lines.isEmpty())
    }

    @Test
    fun `the live platform reads the Activity and publishes the HOME command the coordinator consumes`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val platform = ActivityMainHomeKeyPlatform(activity)
        assertFalse("not started before create", platform.startedBeforeIntent())
        controller.setup()
        assertTrue("started once the Activity is up", platform.startedBeforeIntent())
        assertTrue("display 0 is Main", platform.onMainDisplay())
        assertTrue("a fresh install has not finished onboarding", platform.firstRun())

        platform.publishHome()
        val pending = MButtonNavigationCommands.pending.value
        assertNotNull(pending)
        assertEquals(MButtonPrimaryCommand.HOME, pending!!.command)
    }

    // ---- the floor: HOME pressed while display 4 holds the focus (Thor 2026-10-08) ----

    /** Thor log: `START u0 {act=MAIN cat=[SECONDARY_HOME] ... cmp=.../.CompanionSecondaryHomeActivity}` on display 4. */
    private fun systemFloorHomePress(extraFlags: Int = 0) = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_SECONDARY_HOME)
        .setComponent(ComponentName(context.packageName, "${context.packageName}.CompanionSecondaryHomeActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or extraFlags)

    private fun floor(
        started: Boolean = true,
        focused: Boolean = true,
        firstRun: Boolean = false,
        sessions: MimirSessionSnapshot = MimirSessionSnapshot(),
        mainResumed: Boolean = true,
    ) = FakePlatform(
        started = started,
        focused = focused,
        firstRun = firstRun,
        sessions = sessions,
        source = MainHomeKeySource.FLOOR,
        mainIsResumed = mainResumed,
    )

    @Test
    fun `a focused floor with Main resumed turns the Bottom's HOME press into the same Home command`() {
        val platform = floor()
        assertEquals(MainHomeKeyDecision.RETURN_HOME, routeMainHomeKeyIntent(systemFloorHomePress(), platform))
        assertEquals(1, platform.published)
        assertEquals(listOf("home key decision=reset reason=return_home source=floor"), platform.lines)
    }

    @Test
    fun `a floor that did not hold the focus leaves the system's own SECONDARY_HOME behaviour alone`() {
        // A third-party app on the Bottom held the focus; or the floor is stopped behind it.
        listOf(floor(focused = false), floor(started = false, focused = false)).forEach { platform ->
            assertEquals(MainHomeKeyDecision.NOT_IN_FRONT, routeMainHomeKeyIntent(systemFloorHomePress(), platform))
            assertEquals(0, platform.published)
            assertEquals(listOf("home key decision=skip reason=not_in_front source=floor"), platform.lines)
        }
    }

    @Test
    fun `no resumed Main on display 0 means nothing to reset`() {
        val platform = floor(mainResumed = false)
        assertEquals(MainHomeKeyDecision.MAIN_NOT_RESUMED, routeMainHomeKeyIntent(systemFloorHomePress(), platform))
        assertEquals(0, platform.published)
        assertEquals(listOf("home key decision=skip reason=main_not_resumed source=floor"), platform.lines)
    }

    @Test
    fun `Mimir's own floor-front start is ignored by the floor, even when the floor is focused`() {
        val floorFront = companionFloorFrontIntent(context.packageName)
        assertEquals(MainHomeIntentKind.INTERNAL_HOME, floorFront.mainHomeIntentKind(MainHomeKeySource.FLOOR))
        val platform = floor()
        assertEquals(MainHomeKeyDecision.INTERNAL_START, routeMainHomeKeyIntent(floorFront, platform))
        assertEquals(0, platform.published)
        assertEquals(listOf("home key decision=skip reason=internal_start source=floor"), platform.lines)
    }

    @Test
    fun `the marker did not change the floor-front start's shape`() {
        val floorFront = companionFloorFrontIntent(context.packageName)
        assertEquals(Intent.ACTION_MAIN, floorFront.action)
        assertEquals(setOf(Intent.CATEGORY_SECONDARY_HOME), floorFront.categories)
        assertNull("a component would retype the start STANDARD (companionFloorFrontIntent)", floorFront.component)
        assertEquals(context.packageName, floorFront.`package`)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION, floorFront.flags)
        assertTrue(floorFront.getBooleanExtra(InternalHomeStartExtra, false))
    }

    @Test
    fun `the floor keeps the same guards as Main`() {
        val firstRun = floor(firstRun = true)
        assertEquals(MainHomeKeyDecision.FIRST_RUN, routeMainHomeKeyIntent(systemFloorHomePress(), firstRun))
        assertEquals(0, firstRun.published)

        val launching = floor(
            sessions = MimirSessionSnapshot(
                secondaryAppSession = MimirAppSession(
                    operationId = "app:2",
                    sequence = 2L,
                    packageName = "com.example.app",
                    label = "Example",
                    placement = MimirAppPlacement.SECONDARY,
                    returnAnchor = PrimaryReturnOrigin(destination = MimirDestination.HOME.name),
                    startedAtEpochMs = 1L,
                    phase = MimirSessionPhase.DISPATCHED,
                ),
            ),
        )
        assertEquals(MainHomeKeyDecision.HAND_OFF, routeMainHomeKeyIntent(systemFloorHomePress(), launching))
        assertEquals(0, launching.published)

        val moved = floor()
        assertEquals(
            MainHomeKeyDecision.BROUGHT_TO_FRONT,
            routeMainHomeKeyIntent(systemFloorHomePress(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT), moved),
        )
        assertEquals(0, moved.published)
    }

    @Test
    fun `each window reads only its own HOME category`() {
        // A SECONDARY_HOME start is not a HOME press on MainActivity; the floor takes either category.
        assertEquals(MainHomeKeyDecision.NOT_HOME_INTENT, routeMainHomeKeyIntent(systemFloorHomePress(), FakePlatform()))
        assertEquals(MainHomeIntentKind.USER_HOME, systemFloorHomePress().mainHomeIntentKind(MainHomeKeySource.FLOOR))
        assertEquals(MainHomeIntentKind.USER_HOME, systemHomePress().mainHomeIntentKind(MainHomeKeySource.FLOOR))
        assertEquals(MainHomeIntentKind.OTHER, systemFloorHomePress().mainHomeIntentKind(MainHomeKeySource.MAIN))
    }

    @Test
    fun `the live floor platform reads the floor's own window and only a MainActivity on display 0 counts as Main`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        var resumed: android.app.Activity? = null
        val platform = FloorMainHomeKeyPlatform(controller.get()) { resumed }
        assertEquals(MainHomeKeySource.FLOOR, platform.source)
        assertFalse("the floor is not started before create", platform.startedBeforeIntent())
        controller.setup()
        assertTrue(platform.startedBeforeIntent())
        assertTrue("the press belongs to Main's display, checked on Main itself", platform.onMainDisplay())

        assertFalse("nothing resumed", platform.mainResumed())
        resumed = controller.get()
        assertFalse("a resumed Activity that is not MainActivity is not Main", platform.mainResumed())

        platform.publishHome()
        assertEquals(MButtonPrimaryCommand.HOME, MButtonNavigationCommands.pending.value?.command)
    }
}
