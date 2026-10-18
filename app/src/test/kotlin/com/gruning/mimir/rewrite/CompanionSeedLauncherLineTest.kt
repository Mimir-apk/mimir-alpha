package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 5b: the NOW_PLAYING card's "Opens with" line on the two launch seeds ([CompanionSeedLauncherLine]).
 *
 * Found on the Thor (47d5af6b): a game launched from the Library read "OPENS WITH RetroArch 64 / Gambatte", and the
 * same game launched from the Bottom card had no line, because only the Library push ever set the label.
 *
 * The read scope is a [StandardTestDispatcher], so nothing launched into it runs until a test advances it. That makes
 * "the seed is published before the read" something these tests observe rather than infer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionSeedLauncherLineTest {

    private val gbc = PlatformId("gbc")
    private val statusLines = mapOf(gbc to "Launch · RetroArch 64 / Gambatte")
    private val platformDefault = "RetroArch 64 / Gambatte"
    private val overrideLabel = "Pizza Boy GBC"

    private val scope = TestScope(StandardTestDispatcher())
    private var reads = 0

    @Before
    fun reset() {
        CompanionState.clearRetainedNowPlaying()
        CompanionState.set(CompanionPayload.idle())
        reads = 0
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    private fun game(
        override: Boolean = false,
        id: String = "zelda-oracle-of-ages",
        title: String = "The Legend of Zelda: Oracle of Ages",
        platform: PlatformId = gbc,
    ) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = platform,
        memberCount = 1,
        duplicateKind = null,
        hasEmulatorOverride = override,
    )

    private fun homeRow(game: GameRow) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(game.gameId),
        rank = 0L,
        addedAtMillis = 0L,
        title = game.title,
        // The Home projection carries an emulated row's platform id here as well as on the typed field.
        supportingLabel = game.platformId.value,
        platformId = game.platformId,
    )

    private val androidRow = HomeShelfRow(
        itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.androidgame")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "An Android Game",
        supportingLabel = "Android game",
    )

    private fun candidate(
        label: String,
        installed: Boolean = true,
        override: Boolean = false,
        auto: Boolean = false,
        launchable: Boolean = true,
    ) = OpenWithCandidate(
        emulatorId = EmulatorId("emu.${label.filter(Char::isLetterOrDigit).lowercase()}"),
        label = label,
        packageName = "pkg.${label.filter(Char::isLetterOrDigit).lowercase()}",
        installed = installed,
        dispatchSupported = launchable,
        isCurrentOverride = override,
        isAutoPick = auto,
    )

    /** The platform default is the auto pick; the game's override is [overrideLabel]. */
    private fun overrideOptions(overrideInstalled: Boolean = true, overrideLaunchable: Boolean = true) = OpenWithOptions(
        hasOverride = true,
        candidates = listOf(
            candidate(platformDefault, auto = true),
            candidate(overrideLabel, installed = overrideInstalled, override = true, launchable = overrideLaunchable),
        ),
    )

    private fun reading(options: OpenWithOptions): suspend (GameRow) -> OpenWithOptions = { reads++; options }

    private fun CompanionSeedLauncherLine.publishSeed(
        seed: CompanionPayload,
        game: GameRow?,
        resolveOpenWith: suspend (GameRow) -> OpenWithOptions,
    ) = publish(seed, game, statusLines, scope, resolveOpenWith)

    private val onScreen: CompanionPayload get() = CompanionState.payload.value

    // ── The line both seeds dropped ──────────────────────────────────────────────────────────────

    @Test
    fun `a Bottom card seed names the launcher the Library names for a game with no override`() {
        val game = game()
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(game, themeId = "midnight"), game, reading(overrideOptions()))

        assertEquals(CompanionMode.NOW_PLAYING, onScreen.mode)
        assertTrue("still the in-flight seed", onScreen.isLoading)
        assertEquals(platformDefault, onScreen.launcherLabel)
        scope.testScheduler.advanceUntilIdle()
        assertEquals("a game with no override needs no launch-controller read", 0, reads)
    }

    @Test
    fun `a Home shelf row seed names the same launcher`() {
        val game = game()
        CompanionSeedLauncherLine().publishSeed(homeLaunchSeedPayload(homeRow(game), themeId = "midnight"), game, reading(overrideOptions()))

        assertEquals(game.gameId, onScreen.gameId)
        assertEquals(platformDefault, onScreen.launcherLabel)
    }

    @Test
    fun `the card republished once the emulator is in front keeps the line, from either seed`() {
        val game = game()
        listOf(
            companionLaunchSeedPayload(game, themeId = "midnight"),
            homeLaunchSeedPayload(homeRow(game), themeId = "midnight"),
        ).forEach { seed ->
            reset()
            CompanionSeedLauncherLine().publishSeed(seed, game, reading(overrideOptions()))
            // A Home launch publishes HOME as soon as it settles; the emulator's foreground brings the card back.
            CompanionState.set(CompanionPayload(mode = CompanionMode.HOME, themeId = "midnight"))

            assertTrue(CompanionState.republishRetainedNowPlaying())
            assertFalse(onScreen.isLoading)
            assertEquals(platformDefault, onScreen.launcherLabel)
        }
    }

    // ── An override: its own label, never the platform default ───────────────────────────────────

    @Test
    fun `an override game has no line until its override resolves, then the override's label`() {
        val game = game(override = true)
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(game, themeId = "midnight"), game, reading(overrideOptions()))

        assertEquals(CompanionMode.NOW_PLAYING, onScreen.mode)
        assertNull("the platform default must never stand in for an override", onScreen.launcherLabel)

        scope.testScheduler.advanceUntilIdle()
        assertEquals(overrideLabel, onScreen.launcherLabel)
        assertTrue(CompanionState.republishRetainedNowPlaying())
        assertEquals(overrideLabel, onScreen.launcherLabel)
    }

    @Test
    fun `an override that does not resolve leaves no line -- not the platform default, not the auto pick`() {
        val game = game(override = true)
        // The override's emulator is gone. A launch skips it for Auto, so no name is safe here.
        CompanionSeedLauncherLine().publishSeed(
            companionLaunchSeedPayload(game, themeId = "midnight"),
            game,
            reading(overrideOptions(overrideInstalled = false)),
        )
        scope.testScheduler.advanceUntilIdle()
        assertEquals(1, reads)
        assertNull(onScreen.launcherLabel)

        // A failed read is the same answer.
        reset()
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(game, themeId = "midnight"), game) {
            error("room blew up")
        }
        scope.testScheduler.advanceUntilIdle()
        assertNull(onScreen.launcherLabel)
        assertTrue(CompanionState.republishRetainedNowPlaying())
        assertNull(onScreen.launcherLabel)
    }

    @Test
    fun `an installed override the launch cannot dispatch names no launcher, on the seeds and the Library card`() {
        // The launch prefers an override only when it is installed AND dispatch-supported (resolveInstalledEmulator),
        // and skips one that is not for Auto. Naming it would name an emulator that will not start.
        val unlaunchable = overrideOptions(overrideLaunchable = false)
        assertNull("the shared selector", companionInstalledOverrideLabel(unlaunchable))

        val game = game(override = true)
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(game, themeId = "midnight"), game, reading(unlaunchable))
        scope.testScheduler.advanceUntilIdle()
        assertNull("the seed", onScreen.launcherLabel)

        val library = runBlocking {
            companionLibraryLauncherLabel(game, statusLines, mutableMapOf(), reading(unlaunchable))
        }
        assertNull("the Library card", library)
    }

    @Test
    fun `the shared selector names only the installed current override`() {
        assertEquals(overrideLabel, companionInstalledOverrideLabel(overrideOptions()))
        assertNull(companionInstalledOverrideLabel(overrideOptions(overrideInstalled = false)))
        assertNull(
            "the auto pick is not an override",
            companionInstalledOverrideLabel(OpenWithOptions(false, listOf(candidate(platformDefault, auto = true)))),
        )
    }

    // ── The retained card ────────────────────────────────────────────────────────────────────────

    @Test
    fun `an override label that resolves after the seed was replaced still reaches the retained card`() {
        val game = game(override = true)
        val read = CompletableDeferred<OpenWithOptions>()
        CompanionSeedLauncherLine().publishSeed(homeLaunchSeedPayload(homeRow(game), themeId = "midnight"), game) {
            read.await()
        }
        scope.testScheduler.runCurrent()
        // A Home launch publishes HOME as soon as it settles, which a slow read can lose to.
        val shelf = CompanionPayload(mode = CompanionMode.HOME, themeId = "midnight")
        CompanionState.set(shelf)

        read.complete(overrideOptions())
        scope.testScheduler.advanceUntilIdle()

        assertEquals("landing the label must not bring the card back over the shelf", shelf, onScreen)
        assertTrue(CompanionState.republishRetainedNowPlaying())
        assertEquals(overrideLabel, onScreen.launcherLabel)
    }

    @Test
    fun `a late read changes nothing once the session is over or another game holds the card`() {
        val game = game(override = true)
        val read = CompletableDeferred<OpenWithOptions>()
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(game, themeId = "midnight"), game) {
            read.await()
        }
        scope.testScheduler.runCurrent()
        // The launch failed, or the game came back: the retained card is dead truth.
        CompanionState.clearRetainedNowPlaying()
        val shelf = CompanionPayload(mode = CompanionMode.HOME, themeId = "midnight")
        CompanionState.set(shelf)
        read.complete(overrideOptions())
        scope.testScheduler.advanceUntilIdle()
        assertEquals(shelf, onScreen)
        assertFalse("no card may come back to carry a late label", CompanionState.republishRetainedNowPlaying())

        reset()
        val staleRead = CompletableDeferred<OpenWithOptions>()
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(game, themeId = "midnight"), game) {
            staleRead.await()
        }
        scope.testScheduler.runCurrent()
        // No status line for gba, so the second game has no launcher to name.
        val other = game(id = "kirby-tilt-n-tumble", title = "Kirby Tilt 'n' Tumble", platform = PlatformId("gba"))
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(other, themeId = "midnight"), other, reading(overrideOptions()))
        staleRead.complete(overrideOptions())
        scope.testScheduler.advanceUntilIdle()
        assertEquals(other.gameId, onScreen.gameId)
        assertNull("the first game's override must not describe the second", onScreen.launcherLabel)
        assertTrue(CompanionState.republishRetainedNowPlaying())
        assertNull(onScreen.launcherLabel)
    }

    // ── Never waits ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the seed is on screen before its override read starts`() {
        val game = game(override = true)
        val read = CompletableDeferred<OpenWithOptions>()
        var readStarted = false
        CompanionSeedLauncherLine().publishSeed(companionLaunchSeedPayload(game, themeId = "midnight"), game) {
            readStarted = true
            read.await()
        }

        // publish has returned, and the scope has not run a single step.
        assertEquals(CompanionMode.NOW_PLAYING, onScreen.mode)
        assertEquals(game.gameId, onScreen.gameId)
        assertTrue(onScreen.isLoading)
        assertFalse(readStarted)

        scope.testScheduler.runCurrent()
        assertTrue("the read runs after the seed, in the scope it was handed", readStarted)
        assertEquals("a read in flight must not take the card down", game.gameId, onScreen.gameId)

        read.complete(overrideOptions())
        scope.testScheduler.advanceUntilIdle()
        assertEquals(overrideLabel, onScreen.launcherLabel)
    }

    @Test
    fun `re-publishing a Home seed keeps the label this launch resolved and reads once`() {
        val game = game(override = true)
        val line = CompanionSeedLauncherLine()
        val seed = homeLaunchSeedPayload(homeRow(game), themeId = "midnight")
        line.publishSeed(seed, game, reading(overrideOptions()))
        scope.testScheduler.advanceUntilIdle()

        // The Home push re-runs on any key change while the launch is in flight: focus, session state, theme.
        line.publishSeed(seed, game, reading(overrideOptions()))
        scope.testScheduler.advanceUntilIdle()

        assertEquals(overrideLabel, onScreen.launcherLabel)
        assertEquals(1, reads)
    }

    // ── No launcher, no line ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an Android row on the Home shelf gets no line`() {
        val seed = homeLaunchSeedPayload(androidRow, themeId = "midnight")
        assertNull("an Android row's seed carries no game identity", seed.gameId)

        // What the Home push hands it: the row-to-game lookup answers only for an emulated row.
        CompanionSeedLauncherLine().publishSeed(seed, null, reading(overrideOptions()))
        assertEquals(CompanionMode.NOW_PLAYING, onScreen.mode)
        assertNull(onScreen.launcherLabel)

        // A lookup that answered anyway still cannot describe it: the line belongs to the seed's own game.
        CompanionSeedLauncherLine().publishSeed(seed, game(), reading(overrideOptions()))
        assertNull(onScreen.launcherLabel)
        CompanionSeedLauncherLine().publishSeed(seed, game(override = true), reading(overrideOptions()))
        scope.testScheduler.advanceUntilIdle()
        assertNull(onScreen.launcherLabel)
        assertEquals("no read for a card with no game", 0, reads)
    }

    // ── The Library card: the same rule (round 5b follow-up) ─────────────────────────────────────

    @Test
    fun `the Library card names no launcher when the override emulator is not installed`() {
        // A stored override that is not installed launches Auto: LaunchController.resolveReadiness never consults the
        // platform default once an override is stored, so the platform default line would name the wrong emulator.
        val label = runBlocking {
            companionLibraryLauncherLabel(
                game(override = true),
                statusLines,
                mutableMapOf(),
                reading(overrideOptions(overrideInstalled = false)),
            )
        }
        assertNull("never the platform default for an override", label)
    }

    @Test
    fun `a failed override read names no launcher on the Library card either`() {
        val label = runBlocking {
            companionLibraryLauncherLabel(game(override = true), statusLines, mutableMapOf()) { error("room blew up") }
        }
        assertNull(label)
    }

    @Test
    fun `the Library card still names an installed override, and the platform default for a game with none`() {
        runBlocking {
            assertEquals(
                overrideLabel,
                companionLibraryLauncherLabel(game(override = true), statusLines, mutableMapOf(), reading(overrideOptions())),
            )
            assertEquals(
                platformDefault,
                companionLibraryLauncherLabel(game(), statusLines, mutableMapOf(), reading(overrideOptions())),
            )
        }
        assertEquals("only the override game needs a read", 1, reads)
    }

    @Test
    fun `an override that names no launcher is read once per games list, not on every focus move`() {
        val memo = mutableMapOf<String, String?>()
        val stale = reading(overrideOptions(overrideInstalled = false))
        runBlocking {
            // The push re-runs on every focus move while this game is selected.
            repeat(3) { assertNull(companionLibraryLauncherLabel(game(override = true), statusLines, memo, stale)) }
        }
        assertEquals(1, reads)
    }

    // ── Wiring: both launch sites publish through the line (Android-bound code no JVM test drives) ──

    @Test
    fun `the Home push publishes its launch seed through the line, with the launching row's game`() {
        val homeBranch = sourceFile("CompanionPayloadPush.kt")
            .substringAfter("if (destination == MimirDestination.HOME) {")
            .substringBefore("return@LaunchedEffect")
        val branchLines = homeBranch.lines().map(String::trim)
        assertTrue("the Home seed must publish through the launcher line", "homeSeedLauncherLine.publish(" in branchLines)
        assertTrue("game = launchingGame," in branchLines)
        assertFalse(
            "a bare publish is the defect: it drops the launcher",
            homeBranch.contains("CompanionState.set(homeLaunchSeedPayload("),
        )

        val effects = sourceFile("MimirAppCoordinator.kt")
            .substringAfter("private fun MimirCompanionEffects(")
            .substringBefore("MimirColdStartWarmup(")
        assertTrue(
            "launchingGame = bindings.homeShelf.launchingRow?.let(bindings.homeShelf.gameForAchievements)," in
                effects.lines().map(String::trim),
        )
    }

    @Test
    fun `the Bottom card launch publishes its seed through the line, after admission and before dispatch`() {
        val source = sourceFile("MimirAppCoordinator.kt")
        val launch = source.substringAfter("private suspend fun launchCompanionPickedGame(")
        val launchLines = launch.lines().map(String::trim)
        val admission = launchLines.indexOfFirst { it.startsWith("val operation = MimirSessionAuthority.prepareHomeGame(") }
        val seed = launchLines.indexOf(
            "publishCompanionPickedGameSeed(game, themeId, platforms, shelfLaunchStatusLines, finishScope, launchController)",
        )
        val dispatch = launchLines.indexOfFirst { it.startsWith("val result = settleEmulatedHomeLaunch(") }
        assertTrue(
            "admission ($admission) < seed ($seed) < dispatch ($dispatch)",
            admission >= 0 && seed > admission && dispatch > seed,
        )
        assertFalse(
            "a bare publish is the defect: it drops the launcher",
            launch.contains("CompanionState.set(companionLaunchSeedPayload("),
        )

        val caller = source
            .substringAfter("private fun MimirCompanionEffects(")
            .substringAfter("launchCompanionPickedGame(")
            .substringBefore("finishScope = companionLaunchScope,")
        assertTrue(
            "shelfLaunchStatusLines = bindings.settingsRead.shelfLaunchStatusLines," in caller.lines().map(String::trim),
        )
    }

    @Test
    fun `the Library push names its launcher through the shared helper, with no platform-default fallback`() {
        val library = sourceFile("CompanionPayloadPush.kt")
            .substringAfter("val launcherLabel = selectedGame?.let { game ->")
            .substringBefore("coroutineContext.ensureActive()")
        assertTrue(
            "companionLibraryLauncherLabel(game, shelfLaunchStatusLines, companionOverrideLabels) {" in
                library.lines().map(String::trim),
        )
        assertFalse(
            "the platform default is not what a launch with a stale override starts",
            library.contains("fallback = companionLauncherLabel("),
        )
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
