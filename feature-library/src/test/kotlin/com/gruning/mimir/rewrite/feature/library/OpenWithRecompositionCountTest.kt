package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.bodiesOf
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.oursInOrder
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.oursTotal
import com.gruning.mimir.rewrite.feature.library.ComposableInvocationTally.Companion.runsOf
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frame-time target: p90 at or under 8.3 ms at 120 Hz, and the Library launch card's OPEN WITH was not. Thor, Balanced, `gfxinfo framestats`: the key-down frame spent 17 ms recomposing, the NEXT frame spent 11-27 ms recomposing again, and the close
 * spent 17 ms once more, against ACHIEVEMENTS' 0.2 ms on the frame after its press. Nothing the player sees changes on the second of those frames.
 *
 * **What these count** is composable bodies that actually run ([ComposableInvocationTally]: the compiler brackets every body with a trace event, a skipped call
 * fires none), by name, on the real [LibraryScreen] with its real card and the real picker over it, frame by frame at 16 ms. They are the proof that a
 * frame did less; whether it did it in 8 ms is the Thor's to say.
 *
 * **Why the second frame existed.** The picker's rows register their bounds during the key-down frame's layout, which seats the first row as focus
 * (`maybeSeedFocusOnRegister`): a second emission of the authority's state, one frame after the first, to change nothing but one row's ring. Every surface that
 * unwrapped that state in its body (the Library screen and the gamelist under the card, the card, the picker itself) was re-run by it, and by the one that
 * pushed the scope, and by the one that popped it, and by every pad move inside the picker and every chip move on the card. They now ask in their own scopes.
 *
 * Counts on the tree before the change (the same scenario): the press frame 771 bodies (159 of the project's), the next frame 380 (69), the close frame 479 (89).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenWithRecompositionCountTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val widthDp = 1920f / density369
    private val heightDp = 1080f / density369

    private fun candidate(id: String, label: String, auto: Boolean = false) = OpenWithCandidate(
        emulatorId = EmulatorId(id),
        label = label,
        packageName = "emu.$id",
        installed = true,
        isCurrentOverride = false,
        isAutoPick = auto,
        isLaunchPick = auto,
    )

    private val options = OpenWithOptions(
        hasOverride = false,
        candidates = listOf(candidate("duckstation", "DuckStation", auto = true), candidate("epsxe", "ePSXe"), candidate("retroarch", "RetroArch")),
    )

    private inner class Port : LibraryLaunchPort {
        override suspend fun openWithOptions(game: GameRow): OpenWithOptions = options
        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) = Unit
        override suspend fun clearEmulatorOverride(game: GameRow) = Unit
        override suspend fun launch(game: GameRow, preferredDisc: Int, launchMemberEntryKey: EntryKey?): LaunchResult =
            LaunchResult(LaunchReadiness.Unsupported(game.platformId))
    }

    private val authority = DefaultFocusAuthority()
    private val tally = ComposableInvocationTally()
    private lateinit var viewModel: LibraryViewModel
    private lateinit var game: GameRow

    /** One state write the way a press makes it, applied before the next frame. */
    private fun press(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long = 16L) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
    }

    /** The Library gamelist of one platform with a game's launch card open over it, the pad on OPEN WITH, as the Thor had it before the press. */
    private fun mountOpenCard() {
        val repository = FakeLibraryRepository()
        viewModel = LibraryViewModel(repository, Port())
        game = runBlocking { repository.observeGames(PlatformId.PS1).first().first() }
        val detail = LibraryDetailActionState(target = LibraryDetailTarget.Emulated(game), isOnShelf = false)
        viewModel.openPlatform(PlatformId.PS1)
        viewModel.openGameDetail(game)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        LibraryScreen(
                            viewModel = viewModel,
                            authority = authority,
                            detailActionState = detail,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        authority.pushScope(GamelistScope)
        authority.rememberFocusForScope(GameDetailScope, LibraryDetailPlayKey)
        authority.pushScope(GameDetailScope)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals("the pad starts on OPEN WITH", LibraryLaunchCardFocusContract.openWithKey, authority.state.value.focused)
        assertTrue("the options are loaded, as they are once a card has been open for a moment", viewModel.openWith.value != null)
        compose.mainClock.autoAdvance = false
    }

    /** The frames after [action], [count] of them at 16 ms; the first one carries the press. */
    private fun framesAfter(count: Int, action: () -> Unit): List<Map<String, Int>> {
        val out = ArrayList<Map<String, Int>>()
        out += tally.measure {
            press(action)
            step(16L)
        }
        repeat(count - 1) { out += tally.measure { step(16L) } }
        return out
    }

    private fun List<Map<String, Int>>.runs(fragment: String, frames: IntRange = indices): Int =
        slice(frames).sumOf { it.runsOf(fragment) }

    private fun List<Map<String, Int>>.describe(label: String): String =
        mapIndexed { i, f -> "$label f$i: ${f.oursTotal()} of ours\n${f.oursInOrder()}" }.joinToString("\n")

    private fun openPicker() = framesAfter(8) {
        if (viewModel.loadOpenWith(game)) authority.pushScope(OpenWithScope)
    }

    /** Nothing but the picker's own rows may run: not the screen, the game shelf, the gamelist header, the card, or the picker's panel. */
    private fun assertOnlyRowsRan(frames: List<Map<String, Int>>, label: String, range: IntRange) {
        val why = frames.describe(label)
        listOf("LibraryScreen", "GameShelf", "GamelistHeader", "HomeLaunchCard", "MimirSelectOverlay", "MimirLiquidGlassPanel", "OpenWithOverlay")
            .forEach { name -> assertEquals("$name ran in $label frames $range\n$why", 0, frames.runs(name, range)) }
    }

    @Test
    fun `the frame after the one that mounts the picker re-runs nothing but a row that landed`() {
        mountOpenCard()
        val frames = openPicker()
        println("OWPERF open: press frame ${frames[0].values.sum()} bodies (${frames[0].oursTotal()} of ours), next frame ${frames[1].values.sum()} (${frames[1].oursTotal()})\n${frames[0].oursInOrder()}")

        // The press frame places the sheet that was built ahead; its rows register their bounds in that frame's layout and the first one is seated as focus.
        assertEquals("the first row is seated by the registration, in the press frame", OpenWithAutoKey, authority.state.value.focused)
        assertOnlyRowsRan(frames, "OPEN", 1..7)
        // A decoded app icon landing is a row's own recomposition (the fade-in of an icon that was not cached yet), one per icon at most.
        assertTrue(
            "only the rows' icon landings may run after the press frame\n${frames.describe("OPEN")}",
            frames.slice(1..7).sumOf { it.oursTotal() } <= 12,
        )
    }

    @Test
    fun `the press frame leaves the gamelist under the card and the card's title block and description alone`() {
        mountOpenCard()
        val frames = openPicker()
        val why = frames.describe("OPEN")

        // The sheet was built while the card was up (OpenWithPrewarmTest holds that to account), so the press frame builds none of it.
        assertEquals("the sheet was built before the press\n$why", 0, frames.runs("MimirSelectOverlay", 0..0))
        listOf("GamelistHeader", "GamelistSearchBar", "GameShelf", "HomeLaunchCardHeader", "HomeLaunchCardMetadata", "HomeLaunchCardWide", "HomeLaunchCardCompact")
            .forEach { name -> assertEquals("$name ran in the press frame\n$why", 0, frames.runs(name, 0..0)) }
        assertTrue(
            "the screen is re-run at most once, for the modal it mounts\n$why",
            frames[0].bodiesOf("com.gruning.mimir.rewrite.feature.library.LibraryScreen") <= 1,
        )
    }

    @Test
    fun `moving the pad inside the picker re-runs two rows and the counter, and nothing else`() {
        mountOpenCard()
        openPicker()
        step(400L)
        val before = authority.state.value.focused

        val frames = framesAfter(3) { authority.moveFocus(ControllerAction.NAV_DOWN) }
        val why = frames.describe("MOVE")

        assertNotEquals("the pad moved to the next row\n$why", before, authority.state.value.focused)
        listOf("LibraryScreen", "GameShelf", "GamelistHeader", "HomeLaunchCard", "MimirSelectOverlay", "MimirLiquidGlassPanel", "OpenWithOverlay")
            .forEach { name -> assertEquals("$name ran for a pad move\n$why", 0, frames.runs(name)) }
        assertTrue("the ring leaves one row and arrives on another\n$why", frames.runs("SelectRowSeat") in 1..2)
        assertTrue("the counter is the only other thing that changes\n$why", frames.runs("SelectCounter") <= 1)
        assertTrue("a pad move re-runs a handful of bodies, not a screen\n$why", frames.sumOf { it.oursTotal() } <= 40)
    }

    @Test
    fun `moving between the card's chips re-runs the buttons and not the screen behind them or the card's body above them`() {
        mountOpenCard()
        val frames = framesAfter(3) { authority.moveFocus(ControllerAction.NAV_RIGHT) }
        val why = frames.describe("CHIP")

        assertEquals("the pad moved to ACHIEVEMENTS\n$why", LibraryLaunchCardFocusContract.achievementsKey, authority.state.value.focused)
        listOf("LibraryScreen", "GameShelf", "GamelistHeader", "HomeLaunchCardHeader", "HomeLaunchCardMetadata", "HomeLaunchCardWide", "HomeLaunchCardCompact")
            .forEach { name -> assertEquals("$name ran for a chip move\n$why", 0, frames.runs(name)) }
        assertTrue("the buttons' rings are what moved\n$why", frames.runs("HomeLaunchActionButton") in 1..3)
    }

    @Test
    fun `closing the picker re-runs neither the gamelist under the card nor the card's body, in the close or in the hold that follows`() {
        mountOpenCard()
        openPicker()
        step(400L)

        // The close frame, then the 130 ms hold under it ("dissolve, then clear", 08-15) releases a moment later: 24 frames cover both.
        val frames = framesAfter(24) {
            viewModel.closeOpenWith()
            authority.popScope()
        }
        val why = frames.describe("CLOSE")
        println("OWPERF close: close frame ${frames[0].values.sum()} bodies (${frames[0].oursTotal()} of ours), hold release ${frames.drop(1).maxOf { it.values.sum() }} (${frames.drop(1).maxOf { it.oursTotal() }})")

        assertEquals("the pad is back on OPEN WITH\n$why", LibraryLaunchCardFocusContract.openWithKey, authority.state.value.focused)
        listOf("GamelistHeader", "GamelistSearchBar", "GameShelf", "HomeLaunchCardHeader", "HomeLaunchCardMetadata", "HomeLaunchCardWide", "HomeLaunchCardCompact")
            .forEach { name -> assertEquals("$name ran while the picker closed\n$why", 0, frames.runs(name)) }
        assertTrue(
            "the screen is re-run for the scope popping and for the hold releasing, no more\n$why",
            frames.count { it.bodiesOf("com.gruning.mimir.rewrite.feature.library.LibraryScreen") > 0 } <= 2,
        )
    }
}
