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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectPanelTestTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * OPEN WITH built before A is pressed (Thor,2026-10-15: the compiled beta spent 30.7 ms on the key-down frame and 27.7 on the next, and
 * simpleperf put the cost in building the sheet on that frame, not in what the screen behind it re-ran). The Library keeps the sheet composed and measured, not placed, from
 * a moment after its card settles (or the pad reaches the OPEN WITH chip) until the card closes, so that A changes only the scope and the slot places what is already there.
 *
 * What these hold the code to, on the real [LibraryScreen]:
 * 1. **Built ahead, and nothing of it is open.** While the card is up the sheet's bodies have run, and it is not drawn, not hit-tested, not in the semantics tree, has
 *    registered no row with the focus authority, and owns no Back.
 * 2. **A composes no sheet.** The key-down frame runs no panel, no viewport, no icon, no row: the sheet is placed, its rows register in that frame's layout and the first one is
 *    seated as it always was; the only bodies that run are the few that read the activation.
 * 3. **It goes back to waiting, and the next opening is as cheap.** Closing returns the sheet to the unplaced state with nothing left registered or owning Back; opening it again
 *    composes nothing.
 * 4. **Before it is built nothing changed.** An A that beats the build mounts the sheet on its own frame, exactly as before.
 * 5. **A card that closes takes its sheet with it.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenWithPrewarmTest {

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

    /** A port that keeps what was written, as the real one does: the reload after a pick says what the pick said, so the write and its reload are one change to the sheet and not two. */
    private inner class Port : LibraryLaunchPort {
        @Volatile private var picked: OpenWithCandidate? = null
        override suspend fun openWithOptions(game: GameRow): OpenWithOptions = picked?.let { openWithOptionsAfterPick(options, it) } ?: options
        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) { picked = candidate }
        override suspend fun clearEmulatorOverride(game: GameRow) { picked = null }
        override suspend fun launch(game: GameRow, preferredDisc: Int, launchMemberEntryKey: EntryKey?): LaunchResult =
            LaunchResult(LaunchReadiness.Unsupported(game.platformId))
    }

    private val authority = DefaultFocusAuthority()
    private val tally = ComposableInvocationTally()
    private lateinit var viewModel: LibraryViewModel
    private lateinit var game: GameRow

    private fun press(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long = 16L) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
    }

    private fun settle(ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) {
            step(16L)
            elapsed += 16L
        }
    }

    /** The Library gamelist of one platform with its game selected, the pad on nothing yet: the card is not open. Frames are driven by hand from here on. */
    private fun mountGamelist() {
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
                        LibraryScreen(viewModel = viewModel, authority = authority, detailActionState = detail)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        authority.pushScope(GamelistScope)
        authority.rememberFocusForScope(GameDetailScope, LibraryDetailPlayKey)
        compose.mainClock.autoAdvance = false
    }

    /** The options are loaded on a worker thread; frames are given real time to see them land. */
    private fun awaitOptions() {
        val by = System.nanoTime() + 15_000_000_000L
        while (viewModel.openWith.value == null && System.nanoTime() < by) {
            Thread.sleep(10L)
            step(16L)
        }
        assertTrue("the options are loaded", viewModel.openWith.value != null)
    }

    /** The card up, the pad on PLAY (not on the chip): the sheet is built ahead once the card has settled. Returns what ran while that happened. */
    private fun openCardAndWaitForTheBuild(): Map<String, Int> {
        val ran = tally.measure {
            press { authority.pushScope(GameDetailScope) }
            awaitOptions()
            settle(1_500L)
        }
        assertEquals("the pad is on PLAY, not on the chip", LibraryDetailPlayKey, authority.state.value.focused)
        return ran
    }

    private fun panelNodes(unmerged: Boolean) =
        compose.onAllNodes(hasTestTag(MimirSelectPanelTestTag), useUnmergedTree = unmerged).fetchSemanticsNodes()

    private fun panelOpen() = panelNodes(unmerged = false).isNotEmpty() && panelNodes(unmerged = true).any { it.layoutInfo.isPlaced }

    private fun backIsOwned() = compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()

    private fun List<Map<String, Int>>.runs(fragment: String, frames: IntRange = indices): Int = slice(frames).sumOf { it.runsOf(fragment) }

    private fun pressOpenWith() = ArrayList<Map<String, Int>>().also { out ->
        out += tally.measure {
            press {
                authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey)
                if (viewModel.loadOpenWith(viewModel.detailGame.value ?: game)) authority.pushScope(OpenWithScope)
            }
            step(16L)
        }
        repeat(7) { out += tally.measure { step(16L) } }
    }

    @Test
    fun `while the card is up the sheet is built, and none of it is open`() {
        mountGamelist()
        val backBefore = backIsOwned()
        val ran = openCardAndWaitForTheBuild()

        assertTrue("the sheet's bodies ran while the card was up: ${ran.oursInOrder()}", ran.runsOf("MimirSelectOverlay") >= 1 && ran.bodiesOf("com.gruning.mimir.rewrite.core.ui.select.SelectRow") >= 3)
        assertEquals("not in the semantics tree a test or a screen reader reads", 0, panelNodes(unmerged = false).size)
        assertTrue("and what the unmerged tree still holds is not placed: not drawn, not hit, not registered", panelNodes(unmerged = true).none { it.layoutInfo.isPlaced })
        assertEquals("no row of it registered with the authority", emptyList<Any>(), authority.registeredKeysInScope(OpenWithScope))
        assertEquals("it owns no Back, so the card behind it keeps the system Back", backBefore, backIsOwned())
        assertEquals("and the pad was never moved by it", LibraryDetailPlayKey, authority.state.value.focused)
    }

    @Test
    fun `reaching the OPEN WITH chip builds it at once when the card has not settled yet`() {
        mountGamelist()
        press { authority.pushScope(GameDetailScope) }
        awaitOptions()
        settle(100L)
        val before = tally.measure { settle(0L) }
        assertEquals(0, before.runsOf("MimirSelectOverlay"))

        val ran = tally.measure {
            press { authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey) }
            settle(96L)
        }
        assertTrue("the pad on the chip builds the sheet within a few frames: ${ran.oursInOrder()}", ran.runsOf("MimirSelectOverlay") >= 1)
        assertEquals(0, panelNodes(unmerged = false).size)
    }

    @Test
    fun `A on OPEN WITH composes no sheet on the key-down frame, places it, and its rows register and seat as they always did`() {
        mountGamelist()
        openCardAndWaitForTheBuild()
        val backBefore = backIsOwned()

        val frames = pressOpenWith()
        val why = frames.mapIndexed { i, f -> "f$i: ${f.oursTotal()} of ours\n${f.oursInOrder()}" }.joinToString("\n")
        println("OWPREWARM key-down frame: ${frames[0].values.sum()} bodies (${frames[0].oursTotal()} of ours), next ${frames[1].values.sum()} (${frames[1].oursTotal()})\n$why")

        listOf("MimirLiquidGlassPanel", "rememberMeasuredViewport", "rememberAppIcon", "OpenWithOverlay", "MimirSelectOverlay", "MimirIntentText")
            .forEach { name -> assertEquals("$name ran in the key-down frame\n$why", 0, frames.runs(name, 0..0)) }
        assertEquals("no row is built by the press\n$why", 0, frames[0].bodiesOf("com.gruning.mimir.rewrite.core.ui.select.SelectRow"))
        assertTrue("what runs is the few bodies that read the activation\n$why", frames[0].runsOf("core.ui.select") <= 4)

        assertTrue("the sheet is placed: in the tree", panelOpen())
        assertEquals("the rows registered in the press frame's layout and the first is seated", OpenWithAutoKey, authority.state.value.focused)
        assertTrue("every row registered", authority.registeredKeysInScope(OpenWithScope).containsAll(listOf(OpenWithAutoKey, openWithFocusKey("duckstation"), openWithFocusKey("epsxe"), openWithFocusKey("retroarch"))))
        assertTrue("Back is the sheet's now (it was the card's before)", backIsOwned() && !backBefore)
        assertTrue("the seating after the press is a handful of bodies, not a sheet\n$why", frames.slice(1..7).sumOf { it.runsOf("core.ui.select") } <= 30)
    }

    @Test
    fun `closing returns the sheet to waiting, and opening it again composes nothing either`() {
        mountGamelist()
        openCardAndWaitForTheBuild()
        pressOpenWith()
        settle(800L)
        assertTrue(panelOpen())

        press {
            viewModel.closeOpenWith()
            authority.popScope()
        }
        settle(1_000L)
        assertEquals("the sheet is out of the tree again", 0, panelNodes(unmerged = false).size + panelNodes(unmerged = true).count { it.layoutInfo.isPlaced })
        assertEquals("nothing of it is registered", emptyList<Any>(), authority.registeredKeysInScope(OpenWithScope))
        assertFalse("and Back is the card's again", backIsOwned())
        assertEquals("the pad is back on the chip", LibraryLaunchCardFocusContract.openWithKey, authority.state.value.focused)

        val frames = pressOpenWith()
        val why = frames.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.joinToString("\n")
        listOf("MimirLiquidGlassPanel", "rememberMeasuredViewport", "OpenWithOverlay", "MimirSelectOverlay")
            .forEach { name -> assertEquals("$name ran when it was opened again\n$why", 0, frames.runs(name, 0..0)) }
        assertEquals(0, frames[0].bodiesOf("com.gruning.mimir.rewrite.core.ui.select.SelectRow"))
        assertEquals(OpenWithAutoKey, authority.state.value.focused)
    }

    @Test
    fun `an A that beats the build mounts the sheet on its own frame, as it always did`() {
        mountGamelist()
        press { authority.pushScope(GameDetailScope) }
        awaitOptions()
        // The pad is on the chip and A comes in the same beat: nothing has been built.
        val frames = ArrayList<Map<String, Int>>()
        frames += tally.measure {
            press {
                authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey)
                if (viewModel.loadOpenWith(viewModel.detailGame.value ?: game)) authority.pushScope(OpenWithScope)
            }
            step(16L)
        }
        repeat(7) { frames += tally.measure { step(16L) } }
        val why = frames.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.joinToString("\n")

        assertTrue("the press frame builds the sheet\n$why", frames[0].runsOf("MimirSelectOverlay") >= 1)
        assertTrue("and it opens", panelOpen())
        assertEquals(OpenWithAutoKey, authority.state.value.focused)
        assertTrue(backIsOwned())
    }

    @Test
    fun `a card that closes takes its sheet with it`() {
        mountGamelist()
        openCardAndWaitForTheBuild()
        val withSheet = tally.measure { settle(0L) }
        assertEquals(0, withSheet.runsOf("MimirSelectOverlay"))

        press { authority.popScope() }
        settle(1_200L)
        // A later card is a fresh one: its sheet is built again only after it has settled, so a press at once builds it on that frame.
        press { authority.pushScope(GameDetailScope) }
        val frames = ArrayList<Map<String, Int>>()
        frames += tally.measure {
            press {
                authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey)
                if (viewModel.loadOpenWith(viewModel.detailGame.value ?: game)) authority.pushScope(OpenWithScope)
            }
            step(16L)
        }
        assertTrue("a sheet from the closed card was not kept: this one is built on the press", frames[0].runsOf("MimirSelectOverlay") >= 1)
    }

    private fun libraryPress(action: com.gruning.mimir.rewrite.core.input.ControllerAction) {
        press { dispatchToLibrary(authority, viewModel, action, onExit = {}, onRescanFolder = {}) }
        step(16L)
    }

    /** The frames after [action]: the first carries the press, then real time for the view model's worker thread to land what it writes. */
    private fun closingFrames(action: () -> Unit): List<Map<String, Int>> {
        val out = ArrayList<Map<String, Int>>()
        out += tally.measure {
            press(action)
            step(16L)
        }
        repeat(40) {
            Thread.sleep(8L)
            out += tally.measure { step(16L) }
        }
        return out
    }

    @Test
    fun `closing leaves the sheet that goes back to waiting alone, apart from the ring leaving its row`() {
        mountGamelist()
        openCardAndWaitForTheBuild()
        pressOpenWith()
        settle(800L)

        val frames = closingFrames {
            viewModel.closeOpenWith()
            authority.popScope()
        }
        val why = frames.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.filter { it.contains("settings") || it.contains("LibraryOpenWith") }.joinToString("\n")
        println("OWPREWARM close (cancel): ${frames[0].values.sum()} bodies (${frames[0].oursTotal()} of ours); sheet bodies over all frames: ${frames.runs("core.ui.select")}\n$why")

        listOf("MimirLiquidGlassPanel", "rememberMeasuredViewport", "OpenWithOverlay", "MimirSelectOverlay", "LibraryOpenWithSheet")
            .forEach { name -> assertEquals("$name ran for a close\n$why", 0, frames.runs(name)) }
        assertTrue("a close re-runs the ring leaving one row, a handful of bodies\n$why", frames.runs("core.ui.select") <= 12)
    }

    @Test
    fun `choosing an emulator leaves the sheet alone while its exit runs, then hands it the picked options once`() {
        mountGamelist()
        openCardAndWaitForTheBuild()
        pressOpenWith()
        settle(800L)
        libraryPress(com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_DOWN)
        libraryPress(com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_DOWN)
        assertEquals(openWithFocusKey("epsxe"), authority.state.value.focused)

        val frames = closingFrames { dispatchToLibrary(authority, viewModel, com.gruning.mimir.rewrite.core.input.ControllerAction.CONFIRM, onExit = {}, onRescanFolder = {}) }
        val why = frames.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.filter { it.contains("settings") || it.contains("LibraryOpenWith") }.joinToString("\n")
        println("OWPREWARM close (pick): ${frames[0].values.sum()} bodies (${frames[0].oursTotal()} of ours); sheet bodies over all frames: ${frames.runs("core.ui.select")}\n$why")

        // The exit carries the sheet's pixels for MimirMotion.OverlayMs (180 ms: 12 frames of 16); the pick's options (optimistic, then the reload) are not handed to it before then.
        val exit = 0..(com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs / 16)
        listOf("OpenWithOverlay", "MimirSelectOverlay", "LibraryOpenWithSheet")
            .forEach { name -> assertEquals("$name ran while the pick's exit was on screen\n$why", 0, frames.runs(name, exit)) }
        assertTrue("the ring leaving one row, a handful of bodies\n$why", frames.runs("core.ui.select", exit) <= 12)
        // And then the sheet that stands built takes the picked options, once, whether one write or two landed in between.
        val after = (exit.last + 1) until frames.size
        assertEquals("the sheet takes the options once, after the exit\n$why", 1, frames.runs("LibraryOpenWithSheet", after))
    }

    // ---- the door's sequence --------------------------------------------------------------------------------------------------------------------------------
    // What the player and the focus authority see of the picker, frame by frame from the press that opens it and from the press that closes it. Home's door is held to
    // the very same lines by `HomeOpenWithPrewarmTest` (app), which reads the block between the markers below and fails if it is not the one it asserts.

    private fun doorFrame(): String {
        val focused = authority.state.value.focused
        val focus = when (focused) {
            null -> "none"
            OpenWithAutoKey -> "auto"
            LibraryLaunchCardFocusContract.openWithKey -> "chip"
            else -> options.candidates.firstOrNull { openWithFocusKey(it.emulatorId.value) == focused }?.let { "row:${it.emulatorId.value}" } ?: "other"
        }
        val rows = authority.registeredKeysInScope(OpenWithScope).size
        return "placed=${panelOpen()} rows=$rows back=${backIsOwned()} scope=${authority.state.value.scope == OpenWithScope} focus=$focus"
    }

    /** The frame the press lands in and the seven after it. Real time between frames, so that the view model's worker lands what it writes. */
    private fun doorFrames(action: () -> Unit): List<String> {
        val out = ArrayList<String>()
        press(action)
        step(16L)
        out += doorFrame()
        repeat(7) {
            Thread.sleep(8L)
            step(16L)
            out += doorFrame()
        }
        return out
    }

    @Test
    fun `the Library door opens, cancels and picks in the frame sequence the Home door is held to`() {
        mountGamelist()
        openCardAndWaitForTheBuild()
        val closed = doorFrame()
        val open = doorFrames {
            authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey)
            if (viewModel.loadOpenWith(viewModel.detailGame.value ?: game)) authority.pushScope(OpenWithScope)
        }
        settle(800L)
        val cancel = doorFrames { dispatchToLibrary(authority, viewModel, com.gruning.mimir.rewrite.core.input.ControllerAction.CANCEL, onExit = {}, onRescanFolder = {}) }
        settle(800L)
        doorFrames {
            authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey)
            if (viewModel.loadOpenWith(viewModel.detailGame.value ?: game)) authority.pushScope(OpenWithScope)
        }
        settle(800L)
        libraryPress(com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_DOWN)
        libraryPress(com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_DOWN)
        val pick = doorFrames { dispatchToLibrary(authority, viewModel, com.gruning.mimir.rewrite.core.input.ControllerAction.CONFIRM, onExit = {}, onRescanFolder = {}) }

        val observed = mapOf(
            "closed" to listOf(closed),
            "open" to open,
            "cancel" to cancel,
            "pick" to pick,
        )
        println("OWDOOR sequence\n" + observed.entries.joinToString("\n") { (phase, frames) -> "$phase: ${frames.distinct()} x${frames.size}" })
        DOOR_SEQUENCE.forEach { (phase, line) ->
            observed.getValue(phase).forEachIndexed { i, frame -> assertEquals("the Library door, $phase, frame $i", line, frame) }
        }
    }

    private companion object {
        // DOOR-SEQUENCE-BEGIN
        // Every frame of the phase reads the line: the press frame and the seven after it.
        val DOOR_SEQUENCE: Map<String, String> = mapOf(
            "closed" to "placed=false rows=0 back=false scope=false focus=other",
            "open" to "placed=true rows=4 back=true scope=true focus=auto",
            "cancel" to "placed=false rows=0 back=false scope=false focus=chip",
            "pick" to "placed=false rows=0 back=false scope=false focus=chip",
        )
        // DOOR-SEQUENCE-END
    }
}
