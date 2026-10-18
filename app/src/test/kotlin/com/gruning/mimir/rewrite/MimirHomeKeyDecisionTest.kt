package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O1, a HOME press with Mimir already in front closes every overlay and returns to Home; a HOME press
 * from a game or another app, the first run, a launch in flight and Mimir's own recovery start never do. These pin the decision
 * ([decideMainHomeKey]) branch by branch, and the source wiring no JVM test can drive (the Activity's lifecycle and window focus).
 */
class MimirHomeKeyDecisionTest {

    /** A user HOME press on a started, focused Main with nothing in the way. */
    private val inFront = MainHomeKeyFacts(
        intentKind = MainHomeIntentKind.USER_HOME,
        startedBeforeIntent = true,
        hadWindowFocusBeforeIntent = true,
        broughtToFront = false,
        onMainDisplay = true,
        firstRun = false,
        handOffInProgress = false,
    )

    @Test
    fun `a HOME press with Mimir in front returns home`() {
        assertEquals(MainHomeKeyDecision.RETURN_HOME, decideMainHomeKey(inFront))
    }

    @Test
    fun `a HOME press from a game or another app keeps today's behaviour`() {
        // The game holds window focus; Mimir is still started behind it, or already stopped.
        assertEquals(
            MainHomeKeyDecision.NOT_IN_FRONT,
            decideMainHomeKey(inFront.copy(hadWindowFocusBeforeIntent = false)),
        )
        assertEquals(
            MainHomeKeyDecision.NOT_IN_FRONT,
            decideMainHomeKey(inFront.copy(startedBeforeIntent = false, hadWindowFocusBeforeIntent = false)),
        )
        assertEquals(MainHomeKeyDecision.NOT_IN_FRONT, decideMainHomeKey(inFront.copy(startedBeforeIntent = false)))
    }

    @Test
    fun `a start that itself moved the task to the front is not a press inside Mimir`() {
        assertEquals(MainHomeKeyDecision.BROUGHT_TO_FRONT, decideMainHomeKey(inFront.copy(broughtToFront = true)))
    }

    @Test
    fun `Mimir's own recovery start never resets, even with Mimir in front`() {
        assertEquals(
            MainHomeKeyDecision.INTERNAL_START,
            decideMainHomeKey(inFront.copy(intentKind = MainHomeIntentKind.INTERNAL_HOME)),
        )
    }

    @Test
    fun `an intent that is not a HOME start is never read as one`() {
        assertEquals(
            MainHomeKeyDecision.NOT_HOME_INTENT,
            decideMainHomeKey(inFront.copy(intentKind = MainHomeIntentKind.OTHER)),
        )
    }

    @Test
    fun `onboarding is never reset`() {
        assertEquals(MainHomeKeyDecision.FIRST_RUN, decideMainHomeKey(inFront.copy(firstRun = true)))
    }

    @Test
    fun `a launch or hand-off in progress is never reset`() {
        assertEquals(MainHomeKeyDecision.HAND_OFF, decideMainHomeKey(inFront.copy(handOffInProgress = true)))
    }

    @Test
    fun `a MainActivity that is not on display 0 is not reset`() {
        assertEquals(MainHomeKeyDecision.OTHER_DISPLAY, decideMainHomeKey(inFront.copy(onMainDisplay = false)))
    }

    @Test
    fun `a press that landed on the floor has nothing to reset without a resumed Main`() {
        assertEquals(MainHomeKeyDecision.MAIN_NOT_RESUMED, decideMainHomeKey(inFront.copy(mainResumed = false)))
        // Main itself is the activity that was in front, so the fact defaults to true and changes nothing for it.
        assertEquals(MainHomeKeyDecision.RETURN_HOME, decideMainHomeKey(inFront))
    }

    @Test
    fun `the intent decides first, so a refusal names the real reason`() {
        val everythingWrong = inFront.copy(
            startedBeforeIntent = false,
            hadWindowFocusBeforeIntent = false,
            broughtToFront = true,
            onMainDisplay = false,
            firstRun = true,
            handOffInProgress = true,
        )
        assertEquals(
            MainHomeKeyDecision.NOT_HOME_INTENT,
            decideMainHomeKey(everythingWrong.copy(intentKind = MainHomeIntentKind.OTHER)),
        )
        assertEquals(
            MainHomeKeyDecision.INTERNAL_START,
            decideMainHomeKey(everythingWrong.copy(intentKind = MainHomeIntentKind.INTERNAL_HOME)),
        )
    }

    @Test
    fun `only a HOME start without Mimir's own marker is a user press`() {
        assertEquals(
            MainHomeIntentKind.USER_HOME,
            classifyMainHomeIntent(android.content.Intent.ACTION_MAIN, homeCategory = true, internalMarker = false),
        )
        assertEquals(
            MainHomeIntentKind.INTERNAL_HOME,
            classifyMainHomeIntent(android.content.Intent.ACTION_MAIN, homeCategory = true, internalMarker = true),
        )
        assertEquals(
            "the launcher icon is not HOME",
            MainHomeIntentKind.OTHER,
            classifyMainHomeIntent(android.content.Intent.ACTION_MAIN, homeCategory = false, internalMarker = false),
        )
        assertEquals(
            MainHomeIntentKind.OTHER,
            classifyMainHomeIntent("android.intent.action.VIEW", homeCategory = true, internalMarker = false),
        )
        assertEquals(MainHomeIntentKind.OTHER, classifyMainHomeIntent(null, homeCategory = true, internalMarker = false))
    }

    // ---- hand-off: what counts as "a launch or return still owns the screen" ----

    private fun game(phase: MimirSessionPhase, placement: MimirGamePlacement = MimirGamePlacement.MAIN) = MimirGameSession(
        operation = MimirGameSessionOperation("op-homekey"),
        sequence = 1,
        gameId = com.gruning.mimir.rewrite.core.model.GameId("g1"),
        platformId = com.gruning.mimir.rewrite.core.model.PlatformId("genesis"),
        returnAnchor = MimirGameReturnAnchor.Library(
            com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                "shelf",
            ),
        ),
        startedAtEpochMs = 1,
        placement = placement,
        dualScreen = false,
        phase = phase,
    )

    private fun app(phase: MimirSessionPhase, placement: MimirAppPlacement) = MimirAppSession(
        operationId = "app:1",
        sequence = 1L,
        packageName = "com.example.app",
        label = "Example",
        placement = placement,
        returnAnchor = PrimaryReturnOrigin(destination = MimirDestination.LIBRARY.name),
        startedAtEpochMs = 1L,
        phase = phase,
    )

    private val handOffPhases = listOf(
        MimirSessionPhase.PREPARING,
        MimirSessionPhase.DISPATCHED,
        MimirSessionPhase.RETURNING,
        MimirSessionPhase.RECOVERING,
    )

    private val settledPhases = listOf(
        MimirSessionPhase.FOREGROUND_PROVEN,
        MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        MimirSessionPhase.ENDED,
        MimirSessionPhase.FAILED,
        MimirSessionPhase.STALE,
    )

    @Test
    fun `no session is no hand-off`() {
        assertFalse(mainHandOffInProgress(MimirSessionSnapshot()))
    }

    @Test
    fun `a game or app still launching or returning is a hand-off`() {
        handOffPhases.forEach { phase ->
            assertTrue("game $phase", mainHandOffInProgress(MimirSessionSnapshot(gameSession = game(phase))))
            assertTrue(
                "bottom game $phase",
                mainHandOffInProgress(MimirSessionSnapshot(gameSession = game(phase, MimirGamePlacement.BOTTOM))),
            )
            assertTrue(
                "primary app $phase",
                mainHandOffInProgress(MimirSessionSnapshot(primaryAppSession = app(phase, MimirAppPlacement.PRIMARY))),
            )
            assertTrue(
                "secondary app $phase",
                mainHandOffInProgress(MimirSessionSnapshot(secondaryAppSession = app(phase, MimirAppPlacement.SECONDARY))),
            )
        }
    }

    @Test
    fun `a settled session does not block a HOME press, so browsing Main with a Bottom game running still gets Home`() {
        settledPhases.forEach { phase ->
            assertFalse("game $phase", mainHandOffInProgress(MimirSessionSnapshot(gameSession = game(phase))))
            assertFalse(
                "bottom game $phase",
                mainHandOffInProgress(MimirSessionSnapshot(gameSession = game(phase, MimirGamePlacement.BOTTOM))),
            )
            assertFalse(
                "primary app $phase",
                mainHandOffInProgress(MimirSessionSnapshot(primaryAppSession = app(phase, MimirAppPlacement.PRIMARY))),
            )
        }
    }

    // ---- source wiring: the Activity's lifecycle and window cannot be driven from the JVM ----

    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/$name")
        assertTrue("$name must be readable (${file.absolutePath})", file.isFile)
        return file.readText()
    }

    @Test
    fun `MainActivity routes a delivered intent after adopting it, behind the database-recovery gate`() {
        val body = source("MainActivity.kt")
            .substringAfter("override fun onNewIntent(intent: android.content.Intent) {", "")
            .substringBefore("private fun consumeMButtonCommandIntent(", "")
        val gate = body.indexOf("if (!databaseRecoveryAllowsRuntimeIntents(DatabaseStartupGateState.failure)) return")
        val adopt = body.indexOf("setIntent(intent)")
        val route = body.indexOf("routeMainHomeKeyIntent(intent, ActivityMainHomeKeyPlatform(this))")
        assertTrue("the route exists in onNewIntent", route > 0)
        assertTrue("it runs behind the recovery gate and after the intent is adopted", route > adopt && adopt > gate && gate > 0)
        assertEquals("exactly one route", 1, Regex("""routeMainHomeKeyIntent\(""").findAll(body).count())
    }

    @Test
    fun `already in front is Launcher3's test, never a RESUMED check the temporary pause would defeat`() {
        val platform = source("MimirHomeKeyRoute.kt")
            .substringAfter("internal class ActivityMainHomeKeyPlatform(", "")
        assertTrue(platform.contains("activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)"))
        assertTrue(platform.contains("activity.window.decorView.hasWindowFocus()"))
        assertTrue(platform.contains("Display.DEFAULT_DISPLAY"))
        assertFalse(
            "ActivityThread pauses a resumed activity around onNewIntent, so RESUMED would never be true here",
            platform.contains("State.RESUMED"),
        )
        val route = source("MimirHomeKeyRoute.kt").substringAfter("internal fun routeMainHomeKeyIntent(", "")
        assertTrue(
            "the BROUGHT_TO_FRONT test reads the delivered intent's flags",
            route.contains("intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0"),
        )
    }

    @Test
    fun `a reset is the existing HOME command, which the coordinator runs through every overlay's own Back path`() {
        assertTrue(source("MimirHomeKeyRoute.kt").contains("MButtonNavigationCommands.publish(MButtonPrimaryCommand.HOME)"))
        val consumer = source("MimirAppCoordinator.kt")
            .substringAfter("LaunchedEffect(mButtonNavigationRequest?.id) {", "")
            .substringBefore("MimirColdStartTrace.markOnce(\"span coordinator body done\")", "")
        val home = consumer.substringAfter("runPrimaryMRequestAfterTomeCancel(homeShelf.cancelTomeReturn) {", "")
        assertTrue("HOME has a branch at all", home.isNotEmpty())
        listOf(
            "overlays.dismissConflictingModals()",
            "core.brightness.close()",
            "nativeShelf.supersedeLaunchReturn()",
            "homeShelf.closeTome()",
            "navigation.applyMButtonHomeNavigation()",
        ).forEach { step ->
            assertTrue("the HOME branch must run $step", home.contains(step))
        }
        assertFalse(
            "HOME is not diverted by the SETTINGS, Quick Launch, Utility or Tweaker branches ahead of it",
            consumer.substringBefore("runPrimaryMRequestAfterTomeCancel(").contains("MButtonPrimaryCommand.HOME"),
        )
    }


    @Test
    fun `the floor routes its own HOME press through the same decision, from its own focus, after super`() {
        val floor = source("CompanionSecondaryHomeActivity.kt")
            .substringAfter("override fun onNewIntent(intent: Intent) {", "")
            .substringBefore("override fun dispatchKeyEvent(", "")
        assertTrue("the floor overrides onNewIntent", floor.isNotEmpty())
        val sup = floor.indexOf("super.onNewIntent(intent)")
        val route = floor.indexOf("routeMainHomeKeyIntent(intent, FloorMainHomeKeyPlatform(this))")
        assertTrue("it routes after super", sup in 0 until route)
        assertFalse("the floor starts nothing itself", floor.contains("startActivity"))

        val platform = source("MimirHomeKeyRoute.kt").substringAfter("internal class FloorMainHomeKeyPlatform(", "")
        assertTrue("the floor's own started / focus state, not Main's", platform.contains("private val main = ActivityMainHomeKeyPlatform(floor)"))
        assertTrue(platform.contains("override fun hadWindowFocusBeforeIntent(): Boolean = main.hadWindowFocusBeforeIntent()"))
        assertTrue("what is reset is the MainActivity ResumedHostActivity reports", platform.contains("resumedMain() as? MainActivity"))
        assertTrue(platform.contains("{ ResumedHostActivity.current() }"))
        assertTrue("and it must be on display 0", platform.contains("== Display.DEFAULT_DISPLAY"))
        assertTrue(platform.contains("override val source: MainHomeKeySource get() = MainHomeKeySource.FLOOR"))
    }

    /**
     * Every place the app starts an activity with a HOME or SECONDARY_HOME intent of its own must either carry
     * [InternalHomeStartExtra] or be allow-listed with a reason. Without this, a new internal HOME start would arrive at
     * MainActivity or the floor looking exactly like a user's HOME press and reset a Mimir that is in front.
     *
     * The scan is by call site: a `startActivity(` / `startActivityAsUser(` / `startActivities(` whose surrounding code (40 lines
     * before, 12 after, comments ignored) names a HOME category or one of the helpers that build such an intent. Queries
     * (`queryIntentActivities`, `resolveActivity`) are not starts and are not matched.
     */
    @Test
    fun `every start with a HOME or SECONDARY_HOME intent carries the internal marker or is allow-listed with a reason`() {
        val root = File("..").canonicalFile
        val modules = root.listFiles { f -> f.isDirectory && (f.name == "app" || f.name.startsWith("core-") || f.name.startsWith("feature-")) }!!
        val sources = modules.flatMap { module ->
            File(module, "src/main/kotlin").takeIf(File::isDirectory)
                ?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()
        }
        assertTrue("fixture: the scan found the app's sources (${sources.size})", sources.size > 200)

        val start = Regex("""\bstartActivity(AsUser)?\(|\bstartActivities\(""")
        val homeShaped = Regex("""CATEGORY_HOME|CATEGORY_SECONDARY_HOME|mainHomeStartRequest|companionFloorFrontIntent|request\.category""")
        val sites = mutableListOf<Pair<String, Boolean>>()
        sources.forEach { file ->
            val code = file.readLines().map { if (it.trimStart().let { t -> t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }) "" else it }
            code.forEachIndexed { i, line ->
                if (!start.containsMatchIn(line)) return@forEachIndexed
                val window = code.subList(maxOf(0, i - 40), minOf(code.size, i + 13))
                if (window.none { homeShaped.containsMatchIn(it) }) return@forEachIndexed
                sites += "${file.name}:${i + 1}" to window.any { it.contains("InternalHomeStartExtra") }
            }
        }
        assertTrue("fixture: the scan found the known HOME-shaped starts (${sites.size})", sites.size >= 3)

        // file name -> why its start is safe without the marker in sight. Each reason is pinned by the assertions below.
        val allowList = mapOf(
            "CompanionDisplayController.kt" to "builds its intent with companionFloorFrontIntent, which carries the marker",
        )
        val unmarked = sites.filter { (site, marked) -> !marked && allowList.keys.none { site.startsWith("$it:") } }
        assertTrue(
            "A HOME-shaped start without InternalHomeStartExtra would reset Main when the player did not press HOME. " +
                "Mark it, or allow-list it here with a reason:\n" + unmarked.joinToString("\n") { it.first },
            unmarked.isEmpty(),
        )
        assertTrue(
            "stale allow-list entry: ${allowList.keys.filter { key -> sites.none { it.first.startsWith("$key:") } }}",
            allowList.keys.all { key -> sites.any { it.first.startsWith("$key:") } },
        )

        val floorFront = source("CompanionDisplayController.kt")
            .substringAfter("internal fun companionFloorFrontIntent(", "")
            .substringBefore("internal fun floorFrontPrecheckFailure(", "")
        assertTrue("the allow-listed helper marks its intent", floorFront.contains(".putExtra(InternalHomeStartExtra, true)"))
        // The recovery gate and MButtonMainRecents.returnToMimir both start through mainHomeStartRequest -> start(), which marks it.
        assertTrue(source("MButtonMainRecents.kt").contains("platform.start(mainHomeStartRequest(platform.selfPackage))"))
        assertTrue(
            source("MimirMainHomeRecovery.kt").substringAfter("override fun start(request: MainHomeStartRequest) {", "")
                .contains(".putExtra(InternalHomeStartExtra, true)"),
        )
        assertTrue(
            "the game-parking start of Main is marked",
            source("CompanionProcessRuntime.kt").substringAfter("addCategory(Intent.CATEGORY_HOME)", "").substringBefore("addFlags(", "")
                .contains(".putExtra(InternalHomeStartExtra, true)"),
        )
    }
}
