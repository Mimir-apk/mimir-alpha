package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the launch cover. A window over another app's game is exactly the kind of code whose
 * worst properties (it takes the keys, it never goes away, it draws a blank) cannot be seen in a JVM test of its logic, so
 * these pin the lines that carry them. Each names the safety property or failure it covers.
 *
 * Deliberately fails rather than skips when a file is unreachable: a gate that assumes itself away reads green.
 */
class GameLaunchCoverSourcePinTest {
    private val rewrite = "app/src/main/kotlin/com/gruning/mimir/rewrite"

    private val presenter = source("$rewrite/GameLaunchCoverPresenter.kt")
    private val cover = source("$rewrite/GameLaunchCover.kt")
    private val machine = source("$rewrite/GameLaunchCoverMachine.kt")
    private val mainActivity = source("$rewrite/MainActivity.kt")
    private val home = source("$rewrite/HomeShelfCoordinator.kt")
    private val coreCoordinator = source("$rewrite/MimirAppCoreCoordinator.kt")
    private val intro = source("$rewrite/GameOpeningIntro.kt")

    private val coverFiles = mapOf("presenter" to presenter, "controller" to cover, "machine" to machine)

    // ---- never focusable, and (walk 32k) TOUCHABLE: NOT_TOUCHABLE made Android 12+ clamp the cover's alpha to 0.8

    @Test
    fun `the window is not focusable and is touchable, laid out in the screen, and carries no other flag`() {
        val flags = stripComments(presenter.substringAfter("val flags =").substringBefore("return WindowManager.LayoutParams("))
        assertTrue(flags, flags.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue(flags, flags.contains("WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN"))
        assertFalse(
            "no FLAG_NOT_TOUCHABLE: on Android 12+ the system clamps an untrusted overlay that has it to alpha 0.8, so the " +
                "cover was 80 percent opaque (walk 32k): $flags",
            flags.contains("FLAG_NOT_TOUCHABLE"),
        )
        assertEquals("exactly those two flags", 2, Regex("FLAG_").findAll(flags).count())
        assertFalse(
            "the flag is absent from the cover's code altogether, not merely from this expression",
            stripComments(presenter).contains("FLAG_NOT_TOUCHABLE"),
        )
    }

    @Test
    fun `a touch on the cover is consumed by the window and no handler is attached to pass it on`() {
        // The accepted consequence of the touchable window: a touch during the cover lands on it and does nothing. That is
        // the absence of any touch code, which the next test pins for every cover file; this one pins the documentation.
        assertTrue(
            "the class doc names the consequence and the ceiling that bounds it",
            presenter.contains("lands on the cover and does nothing") && presenter.contains("GameLaunchCoverCeilingMs"),
        )
        assertTrue("and the ceiling it names is the machine's own, unchanged", machine.contains("internal const val GameLaunchCoverCeilingMs: Long = GameOpeningIntroHandoffCapMs"))
    }

    @Test
    fun `nothing in the cover can take focus or a touch`() {
        for ((name, text) in coverFiles) {
            for (forbidden in listOf(
                "focusable(",
                "focusTarget(",
                "FocusRequester",
                "mimirFocusable",
                "requestFocus",
                "isFocusable",
                "isFocusableInTouchMode",
                "FLAG_ALT_FOCUSABLE_IM",
                "FLAG_NOT_TOUCH_MODAL",
                "FLAG_WATCH_OUTSIDE_TOUCH",
                "pointerInput",
                "clickable(",
                "onTouchEvent",
                "setOnTouchListener",
            )) {
                assertFalse("the $name file must not contain `$forbidden`", stripComments(text).contains(forbidden))
            }
        }
    }

    @Test
    fun `the exit changes the content and never the window's alpha, so the window stays opaque to the system's touch rule`() {
        // The window's own alpha is never set: it is the default 1, and (now that the window is touchable) the system has no
        // reason to clamp it. The layout params are built once and never updated; the exit is Compose's graphicsLayer alpha
        // and translation.
        val code = stripComments(presenter)
        assertFalse(code.contains("updateViewLayout"))
        assertFalse(Regex("""(params|attributes|layoutParams)\s*\.\s*alpha""").containsMatchIn(code))
        val params = code.substringAfter("private fun overlayLayoutParams()").substringBefore("private class OverlayHost")
        assertFalse(
            "no window alpha, dim or animation in the layout params: $params",
            Regex("""\balpha\b|dimAmount|FLAG_DIM_BEHIND""").containsMatchIn(params),
        )
        val layer = code.substringAfter(".graphicsLayer {").substringBefore("},")
        assertTrue("the content's alpha is the blend's, 0 before the reveal", layer.contains("alpha = if (revealed) gameLaunchCoverGlideAlpha(glide.value) else 0f"))
        assertTrue(layer, layer.contains("translationX = dx"))
        assertTrue(layer, layer.contains("translationY = dy"))
        assertTrue(
            "the drift and the alpha read the same eased value, so they are one clock",
            layer.contains("gameLaunchCoverGlideOffsetPx(edge, glide.value, size.width, size.height)"),
        )
        assertEquals("one animated value in the layer", 2, Regex("""glide\.value""").findAll(layer).count())
        assertTrue("the blend runs from rest to its end", code.contains("Animatable(0f)") && !code.contains("Animatable(1f)"))
        assertTrue("on the house easing", code.contains("glide.animateTo(1f, tween(glideMs, easing = MimirMotion.Easing))"))
        assertEquals("one animation, so one clock for alpha and drift", 1, Regex("""\.animateTo\(""").findAll(code).count())
    }

    @Test
    fun `the blend is the intro's entrance clock with a named drift, and no tail`() {
        assertTrue(
            "the duration is the intro's own entrance constant, by name, not a second number",
            machine.contains("GameLaunchCoverGlideMs: Long = GameOpeningIntroEnterMs.toLong()"),
        )
        assertTrue(
            "the drift is one named fraction of the window",
            machine.contains("internal const val GameLaunchCoverGlideDriftFraction: Float = 0.25f"),
        )
        assertFalse("the presenter carries no drift number of its own", Regex("""\b0?\.25f?\b""").containsMatchIn(stripComments(presenter)))
        for ((name, text) in coverFiles) {
            assertFalse("$name: the window is removed when the blend ends, there is no tail", stripComments(text).contains("GlideTail"))
        }
    }

    @Test
    fun `the exit's direction is one named constant that the presenter and the controller only read`() {
        assertTrue(
            "the direction is declared once, in the machine file",
            machine.contains("internal val GameLaunchCoverGlideExit: GameLaunchCoverGlideEdge = GameLaunchCoverGlideEdge.LEFT"),
        )
        assertEquals(
            "one declaration of the exit across the cover's files",
            1,
            coverFiles.values.sumOf { Regex("""GameLaunchCoverGlideExit\s*:\s*GameLaunchCoverGlideEdge\s*=""").findAll(stripComments(it)).count() },
        )
        val presenterCode = stripComments(presenter)
        assertTrue("the presenter reads the constant", presenterCode.contains("edge: GameLaunchCoverGlideEdge = GameLaunchCoverGlideExit"))
        for ((name, text) in mapOf("presenter" to presenter, "controller" to cover)) {
            assertFalse(
                "$name names no edge of its own",
                Regex("""GameLaunchCoverGlideEdge\.(LEFT|RIGHT|UP|DOWN)""").containsMatchIn(stripComments(text)),
            )
        }
    }

    @Test
    fun `the window is full-bleed, an application overlay, with no window animation to outlive its removal`() {
        val code = stripComments(presenter)
        assertEquals("width and height", 2, Regex("WindowManager\\.LayoutParams\\.MATCH_PARENT").findAll(code).count())
        assertTrue(code.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertTrue(code.contains("setFitInsetsTypes(0)"))
        assertTrue(code.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS"))
        assertTrue(code.contains("windowAnimations = 0"))
        assertTrue("a window context, as the performance overlay's", code.contains("createWindowContext("))
    }

    // ---- the return: removeViewImmediate, no tail over Mimir

    @Test
    fun `the window is only ever removed with removeViewImmediate`() {
        val code = stripComments(presenter)
        assertTrue(code.contains("removeViewImmediate("))
        assertFalse("a plain removeView is asynchronous and can tail over Mimir's return", Regex("""\.removeView\(""").containsMatchIn(code))
    }

    @Test
    fun `MainActivity onStart ends the cover first and synchronously, before the intro's own return cut`() {
        val onStart = mainActivity.substringAfter("override fun onStart() {").substringBefore("override fun onResume()")
        val cover = onStart.indexOf("GameLaunchCover.hostStarted()")
        val intro = onStart.indexOf("GameOpeningIntroState.hideDispatchedForReturn()")
        assertTrue(onStart, cover >= 0 && intro >= 0)
        assertTrue("the cover is ended before the intro's cut, both before the first frame", cover < intro)
    }

    @Test
    fun `the started path is a removal with no glide, end to end`() {
        assertTrue(cover.contains("fun hostStarted() = dispatch(GameLaunchCoverEvent.HostStarted)"))
        assertTrue(
            "the machine answers HostStarted with a Remove",
            machine.contains("GameLaunchCoverEvent.HostStarted -> return remove(state, GameLaunchCoverEnd.ON_START)"),
        )
        val finish = cover.substringAfter("private fun finish(").substringBefore("private fun schedule(")
        assertTrue(finish, finish.contains("window.removeImmediate()"))
        assertFalse("finish never glides", finish.contains("beginGlide"))
    }

    @Test
    fun `MainActivity onStop tells the cover of the yield, and not for a configuration change or a covered copy`() {
        val onStop = mainActivity.substringAfter("override fun onStop() {").substringBefore("override fun onNewIntent(")
        val block = onStop.substringAfter("if (leavingScreen && !isChangingConfigurations) {")
        assertTrue(onStop, block.substringBefore("val sessions").contains("GameLaunchCover.hostStopped()"))
    }

    @Test
    fun `MainActivity onDestroy tears the cover down when Mimir is finishing, and not for a recreate or a swept copy`() {
        val onDestroy = mainActivity.substringAfter("override fun onDestroy() {").substringBefore("tweakerPackageMonitor?.let")
        assertTrue(
            onDestroy,
            onDestroy.contains(
                "if (isFinishing && !isChangingConfigurations && !MimirMainStartedCopies.anyStarted()) GameLaunchCover.teardown()",
            ),
        )
    }

    // ---- the gate

    @Test
    fun `the cover is gated on the intro running, the overlay permission, Usage Access and a loaded hero`() {
        val arm = cover.substringAfter("internal suspend fun armGameLaunchCoverBeforeDispatch(")
        assertTrue(arm, arm.contains("gameLaunchCoverSkipReason("))
        assertTrue(arm, arm.contains("introRunning = GameOpeningIntroState.current.value?.gameIdValue == gameIdValue"))
        assertTrue(arm, arm.contains("canDrawOverlays = Settings.canDrawOverlays(appContext)"))
        assertTrue(arm, arm.contains("usageAccessGranted = runtime?.foregroundMonitor?.usageAccessState()?.granted == true"))
        assertTrue(arm, arm.contains("heroLoaded = snapshot != null"))
        assertTrue(arm, arm.contains("secondaryDisplayId = runtime?.taskRegistry?.state?.value?.secondaryDisplayId"))
        assertTrue("the rule comes from the profile", arm.contains("launchCoverRuleFor(target)"))
        assertTrue(
            "the window is added through the gate's answer and nowhere else",
            arm.indexOf("gameLaunchCoverSkipReason(") < arm.indexOf("GameLaunchCover.arm("),
        )
        assertEquals("one place adds a cover", 1, Regex("""GameLaunchCover\.arm\(""").findAll(stripComments(cover)).count())
    }

    @Test
    fun `the cover goes up first in both pre-dispatch hooks, ahead of the Tweaker overlay that must draw above it`() {
        val homeHook = home.substringAfter("preDispatchHook = { ready, attemptId ->").substringBefore("postDispatchHook")
        assertTrue(homeHook, homeHook.contains("if (destination is LaunchDispatchDestination.Main)"))
        assertTrue(homeHook, homeHook.indexOf("armGameLaunchCoverBeforeDispatch(context, game, ready.target)") in 0 until homeHook.indexOf("presentHomeTweakerLaunchStatus("))

        val libraryHook = coreCoordinator.substringAfter("preDispatchHook = { game, ready, attemptId ->").substringBefore("postDispatchHook")
        assertTrue(libraryHook, libraryHook.indexOf("armGameLaunchCoverBeforeDispatch(context, game, ready.target)") in 0 until libraryHook.indexOf("presentHomeTweakerLaunchStatus("))
    }

    @Test
    fun `a dispatch that failed ends the cover, on Home and in the Library`() {
        assertTrue(home.substringAfter("onDispatchFailed = { _, _, attemptId ->").substringBefore("destination = destination").contains("GameLaunchCover.dispatchFailed(game.gameId.value)"))
        assertTrue(home.contains("if (!dispatched) GameLaunchCover.dispatchFailed(game.gameId.value)"))
        assertTrue(coreCoordinator.substringAfter("onDispatchFailed = { game, attemptId ->").substringBefore("launchOpening").contains("GameLaunchCover.dispatchFailed(game.gameId.value)"))
    }

    // ---- the frozen snapshot

    @Test
    fun `the cover never reads the live intro state and only the arm step takes a snapshot of it`() {
        assertFalse("the window", stripComments(presenter).contains("GameOpeningIntroState"))
        assertFalse("the machine", stripComments(machine).contains("GameOpeningIntroState"))
        val controller = cover.substringAfter("internal class GameLaunchCoverController(").substringBefore("internal class MediaPlayerCoverAudio")
        assertFalse("the controller", stripComments(controller).contains("GameOpeningIntroState"))
        val arm = cover.substringAfter("internal suspend fun armGameLaunchCoverBeforeDispatch(")
        assertTrue("the one reader is the arm step, before the intent", arm.contains("GameOpeningIntroState.coverSnapshotFor(gameIdValue)"))
    }

    @Test
    fun `the cover draws the intro's own frame composable and the very bitmap the intro loaded`() {
        assertTrue(presenter.contains("GameOpeningIntroFrame("))
        assertTrue(presenter.contains("bitmap = snapshot.hero"))
        assertTrue("the intro reports the bitmap it decoded", intro.contains("GameOpeningIntroState.reportHero(shown.gameIdValue, bitmap.asImageBitmap())"))
        assertFalse("the cover decodes nothing", stripComments(presenter).contains("AsyncImage"))
        assertFalse("and does not animate the zoom", stripComments(presenter).contains("kenBurns"))
    }

    // ---- house rules

    @Test
    fun `no raw clock and no hex colour in the cover`() {
        val literal = Regex("""tween\s*(?:<[^>]*>)?\s*\(\s*(?:durationMillis\s*=\s*)?\d+|delayMillis\s*=\s*\d+""")
        for ((name, text) in coverFiles) {
            val code = stripComments(text)
            assertFalse("$name: a raw tween or delay", literal.containsMatchIn(code))
            assertFalse("$name: a hex colour", Regex("""Color\(\s*0x|#[0-9A-Fa-f]{6}\b|0x[0-9A-Fa-f]{8}""").containsMatchIn(code))
        }
    }

    @Test
    fun `the log lines are the ones the device walk reads, under the opening tag`() {
        assertTrue(cover.contains("""private const val GameLaunchCoverLogTag = "MimirOpening""""))
        assertTrue(cover.contains("cover start game="))
        assertTrue(cover.contains("cover end game="))
        assertTrue("the glide's line, which replaced walk-32i's dissolve line", cover.contains("cover glide game="))
        assertFalse("no dissolve line any more", cover.contains("cover fade game="))
        assertTrue(cover.contains("via=\${end.logName}"))
        assertTrue(cover.contains("elapsedMs="))
        for (via in listOf("ON_START(\"onStart\")", "FOREIGN(\"foreign\")", "GAME(\"game\")", "CEILING(\"ceiling\")")) {
            assertTrue(via, machine.contains(via))
        }
    }

    // ---- fixtures

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
        }
        error("$relative not found from ${File(".").absolutePath}")
    }

    /** Comments name the things a pin forbids all the time (this file's own KDoc says "never focusable"); only code counts. */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }
}
