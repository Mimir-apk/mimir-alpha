package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOptionsTestTag
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The other half of [OpenWithRecompositionCountTest] (review P3 #3): every assertion there is an upper bound or a no-run check, so it would stay green if the
 * picker or the card stopped UPDATING. These drive the PRODUCTION composables (the real [LibraryScreen] with its real card and picker, and, for the Home door,
 * the real [HomeLaunchCard] and [OpenWithOverlay] over the real [HomeShelfScreen]) and read what they DRAW:
 *
 * - **A pad move moves the ring.** The rendered focused row is read as pixels, not as a count: each row's label rectangle is cropped out of the picker's own
 *   capture before and after a pad DOWN, and exactly the row the ring left and the row it arrived on may differ (the wash and the ring sit under the label;
 *   a row two away is left as it was). The counter says "2 / 4" the frame the ring is on the second row. A UP back returns every row to what it was.
 * - **The card's EMULATOR fact follows the pick.** On the Library door through the real dispatch (DOWN, DOWN, CONFIRM) and the real view model: the fact names the
 *   pick on the very frame the picker closes (the optimistic write) and keeps naming it through the reload, and Automatic brings the auto pick back. On the Home
 *   door, whose coordinator lives in `:app`, through the card composable fed the way the host feeds it (`rememberEqualInstance` around a freshly built state):
 *   "Checking…" while the options are pending, the pick once they land, and a pass that changes nothing leaves what is drawn alone.
 *
 * `MimirAppHost`'s own composable is private and needs the whole app's bindings, so the Home door is reached through the card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenWithStaleUiGuardTest {

    /**
     * The outermost rule, applied before the compose rule launches anything. On the Linux CI runners these PixelCopy frame tests never
     * finish: run 38796079835 hung 60 min, and in run 38807575626 a class-level assumption skipped only the first test before the next
     * one was killed. They run on the Windows host gate, where they pass, and are skipped elsewhere.
     */
    @get:Rule(order = Int.MIN_VALUE)
    val windowsHostOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
                base.evaluate()
            }
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val widthDp = 1920f / density369
    private val heightDp = 1080f / density369

    private val authority = DefaultFocusAuthority()

    /** How long a write on the view model's worker thread (the port, then the reload) is given to land before a test calls it lost: a busy machine is slow, not wrong. */
    private val WorkerPatienceNanos = 15_000_000_000L

    private fun press(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long = 16L) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
    }

    /** Frames at 16 ms until [ms] have passed (the ring's settle, the sheet's enter and exit). */
    private fun settle(ms: Long = 900L) {
        var elapsed = 0L
        while (elapsed < ms) {
            step(16L)
            elapsed += 16L
        }
    }

    private fun candidate(id: String, label: String, auto: Boolean = false, override: Boolean = false, launchPick: Boolean = auto) = OpenWithCandidate(
        emulatorId = EmulatorId(id),
        label = label,
        packageName = "emu.$id",
        installed = true,
        isCurrentOverride = override,
        isAutoPick = auto,
        isLaunchPick = launchPick,
    )

    /** The three candidates the game offers; [override] is the one the player picked, none for Automatic (the auto pick is then the one the launch starts). */
    private fun optionsFor(override: EmulatorId?) = OpenWithOptions(
        hasOverride = override != null,
        candidates = listOf(
            candidate("duckstation", "DuckStation", auto = true, override = override == EmulatorId("duckstation"), launchPick = override == null || override == EmulatorId("duckstation")),
            candidate("epsxe", "ePSXe", override = override == EmulatorId("epsxe"), launchPick = override == EmulatorId("epsxe")),
            candidate("retroarch", "RetroArch", override = override == EmulatorId("retroarch"), launchPick = override == EmulatorId("retroarch")),
        ),
    )

    /** The picker's rows top to bottom: Automatic first, then the candidates. */
    private val rowLabels = listOf("Automatic", "DuckStation", "ePSXe", "RetroArch")

    // ---- what the picker and the card draw ---------------------------------------------------------------------------------------------------------------

    private fun labelNodes(label: String): List<SemanticsNode> =
        compose.onAllNodes(hasText(label) or hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNodes()

    private fun shown(text: String): Boolean = labelNodes(text).isNotEmpty()

    /**
     * The window as drawn, cropped to each row's label rectangle (the wash and the ring are drawn under the label, so a row's ring change is a change there).
     * Taken with `PixelCopy` and an idled main looper: `captureToImage` waits on a redraw the paused looper never delivers, and `view.draw` into a software canvas
     * (as the card-only render tests do) throws on the screens' page handoff layer ("Software rendering doesn't support drawRenderNode").
     */
    private fun rowLabelPixels(): List<IntArray> {
        val view = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var copied = -1
        PixelCopy.request(compose.activity.window, frame, { copied = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(copied == PixelCopy.SUCCESS) { "PixelCopy result $copied" }
        val bounds = compose.onNodeWithTag(MimirSelectOptionsTestTag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        return rowLabels.map { label ->
            // The label as the PICKER draws it: the one inside the options column, not the card's fact of the same name behind it.
            val node = labelNodes(label).first { n ->
                val b = n.boundsInWindow
                b.left >= bounds.left && b.right <= bounds.right && b.top >= bounds.top && b.bottom <= bounds.bottom
            }
            val b = node.boundsInWindow
            val left = b.left.roundToInt().coerceIn(0, frame.width - 1)
            val top = b.top.roundToInt().coerceIn(0, frame.height - 1)
            val width = b.width.roundToInt().coerceIn(1, frame.width - left)
            val height = b.height.roundToInt().coerceIn(1, frame.height - top)
            IntArray(width * height).also { frame.getPixels(it, 0, width, left, top, width, height) }
        }
    }

    /** The rows whose label rectangle differs between two captures. */
    private fun changedRows(before: List<IntArray>, after: List<IntArray>): List<String> =
        rowLabels.filterIndexed { i, _ -> !before[i].contentEquals(after[i]) }

    private fun counterShown(text: String) = labelNodes(text).isNotEmpty()

    /** The value under the card's EMULATOR label, as drawn. */
    private fun emulatorFact(): String? {
        val label = labelNodes("EMULATOR").firstOrNull() ?: return null
        val at = label.boundsInWindow
        // The value is the text set directly under its label, on the label's left edge (the fact is a column of two; the tree is flat to the card).
        val value = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { n -> n.id != label.id && kotlin.math.abs(n.boundsInWindow.left - at.left) < 3f && n.boundsInWindow.top >= at.bottom - 2f }
            .minByOrNull { it.boundsInWindow.top } ?: return null
        return value.config[SemanticsProperties.Text].joinToString("") { it.text }
    }

    /** The pad moves one row and every drawn thing that should follow does: the counter, and exactly the two rows the ring left and arrived on. */
    private fun assertRingFollowsDown(move: (ControllerAction) -> Unit) {
        // Rest: the ring on the first row, as the sheet seats it.
        settle()
        assertTrue("the counter says the first of four at rest", counterShown("1 / 4"))
        val rest = rowLabelPixels()
        assertTrue("two captures of a settled sheet are the same (otherwise nothing below means anything)", changedRows(rest, rowLabelPixels()).isEmpty())

        move(ControllerAction.NAV_DOWN)
        settle()
        assertEquals("the pad is on the second row", openWithFocusKey("duckstation"), authority.state.value.focused)
        assertTrue("the counter follows: 2 / 4", counterShown("2 / 4"))
        assertFalse("and the old counter is gone", counterShown("1 / 4"))
        val second = rowLabelPixels()
        assertEquals("the ring left Automatic and arrived on DuckStation, and no other row was touched", listOf("Automatic", "DuckStation"), changedRows(rest, second))

        move(ControllerAction.NAV_DOWN)
        settle()
        assertEquals("the pad is on the third row", openWithFocusKey("epsxe"), authority.state.value.focused)
        assertTrue("3 / 4", counterShown("3 / 4"))
        val third = rowLabelPixels()
        assertEquals("the ring left DuckStation and arrived on ePSXe", listOf("DuckStation", "ePSXe"), changedRows(second, third))
        assertEquals("against rest: Automatic lost the ring and ePSXe has it; DuckStation and RetroArch were never touched", listOf("Automatic", "ePSXe"), changedRows(rest, third))

        move(ControllerAction.NAV_UP)
        move(ControllerAction.NAV_UP)
        settle()
        assertEquals("the pad is back on the first row", OpenWithAutoKey, authority.state.value.focused)
        assertTrue("1 / 4 again", counterShown("1 / 4"))
        assertTrue("and every row is drawn as it was at rest", changedRows(rest, rowLabelPixels()).isEmpty())
    }

    // ---- the Library door -------------------------------------------------------------------------------------------------------------------------------

    @Volatile
    private var pickedOverride: EmulatorId? = null

    private inner class StatefulPort : LibraryLaunchPort {
        override suspend fun openWithOptions(game: GameRow): OpenWithOptions = optionsFor(pickedOverride)
        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) {
            pickedOverride = candidate.emulatorId
        }
        override suspend fun clearEmulatorOverride(game: GameRow) {
            pickedOverride = null
        }
        override suspend fun launch(game: GameRow, preferredDisc: Int, launchMemberEntryKey: EntryKey?): LaunchResult =
            LaunchResult(LaunchReadiness.Unsupported(game.platformId))
    }

    private lateinit var viewModel: LibraryViewModel
    private lateinit var game: GameRow

    private fun mountLibrary() {
        pickedOverride = null
        val repository = FakeLibraryRepository()
        viewModel = LibraryViewModel(repository, StatefulPort())
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
        authority.pushScope(GameDetailScope)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        authority.requestFocus(LibraryLaunchCardFocusContract.openWithKey)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertTrue("the options are loaded, as they are once a card has been open for a moment", viewModel.openWith.value != null)
        compose.mainClock.autoAdvance = false
    }

    /** One press the way the app shell feeds it: through the Library's single dispatch, which routes by the scope on top. */
    private fun libraryPress(action: ControllerAction) {
        press { dispatchToLibrary(authority, viewModel, action, onExit = {}, onRescanFolder = {}) }
        step(16L)
    }

    private fun openLibraryPicker() {
        press { if (viewModel.loadOpenWith(game)) authority.pushScope(OpenWithScope) }
        settle()
    }

    @Test
    fun `a pad DOWN in the Library card's picker moves the ring to the next row, and the counter and the rows are drawn there`() {
        mountLibrary()
        openLibraryPicker()
        assertEquals("the sheet seats its first row", OpenWithAutoKey, authority.state.value.focused)

        assertRingFollowsDown(move = ::libraryPress)
    }

    @Test
    fun `picking an emulator in the Library picker puts its name on the card's EMULATOR fact at once, keeps it through the reload, and Automatic brings the auto pick back`() {
        mountLibrary()
        assertEquals("the fact names the emulator the launch starts", "DuckStation", emulatorFact())
        openLibraryPicker()

        libraryPress(ControllerAction.NAV_DOWN)
        libraryPress(ControllerAction.NAV_DOWN)
        assertEquals(openWithFocusKey("epsxe"), authority.state.value.focused)
        libraryPress(ControllerAction.CONFIRM)
        assertEquals("the picker closed on the pick", GameDetailScope, authority.state.value.scope)

        // The press frame itself already names the pick, and so does every frame after it: the optimistic write, then the reload's truth (a worker thread, so the
        // frames are given real time to see it land).
        val facts = ArrayList<String?>()
        facts += emulatorFact()
        val landBy = System.nanoTime() + WorkerPatienceNanos
        var frames = 0
        while (frames++ < 60 || (pickedOverride == null && System.nanoTime() < landBy)) {
            Thread.sleep(15L)
            step(16L)
            facts += emulatorFact()
        }
        assertEquals("the pick reached the port", EmulatorId("epsxe"), pickedOverride)
        assertTrue("the fact named ePSXe on every frame from the press, never DuckStation again: $facts", facts.all { it == "ePSXe" })

        // Back to Automatic: no optimistic write, so the old pick stays until the reload lands, then the auto pick is named.
        openLibraryPicker()
        assertEquals("the picker opens on the current pick", openWithFocusKey("epsxe"), authority.state.value.focused)
        libraryPress(ControllerAction.NAV_UP)
        libraryPress(ControllerAction.NAV_UP)
        assertEquals(OpenWithAutoKey, authority.state.value.focused)
        libraryPress(ControllerAction.CONFIRM)
        var named: String? = emulatorFact()
        val namedBy = System.nanoTime() + WorkerPatienceNanos
        while (named != "DuckStation" && System.nanoTime() < namedBy) {
            Thread.sleep(15L)
            step(16L)
            named = emulatorFact()
        }
        assertEquals("Automatic names the auto pick again", "DuckStation", named)
        assertEquals("and the port was told", null, pickedOverride)
    }

    // ---- the Home door ----------------------------------------------------------------------------------------------------------------------------------

    private val homeRow = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("home-game-1")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "Crisis Core: Final Fantasy VII",
        supportingLabel = "psp",
    )

    private var homePickerOpen by mutableStateOf(false)

    private fun mountHomePicker() {
        val options = optionsFor(null)
        val cardState = HomeLaunchCardUiState(row = homeRow, platformLabel = "PlayStation Portable", emulatorLabel = "DuckStation", lastPlayedLabel = "Yesterday")
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        HomeShelfScreen(
                            rows = listOf(homeRow),
                            authority = authority,
                            onLaunch = {},
                            onOpenLibrary = {},
                            onQuickAction = {},
                            surfaceLabel = "Home",
                        )
                        HomeLaunchCard(state = cardState, onPlay = {}, onAchievements = {}, onOpenWith = {}, onClose = {}, authority = authority)
                        if (homePickerOpen) {
                            OpenWithOverlay(authority = authority, options = options, onChooseAuto = {}, onChoose = {}, onDismiss = {})
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        enterHomeLaunchCard(authority, emulated = true)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        authority.requestFocus(HomeLaunchCardOpenWithKey)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** Home's overlay dispatch routes NAV_UP / NAV_DOWN in the picker's scope to the authority's own move, as the coordinator does. */
    private fun homePress(action: ControllerAction) {
        press { authority.moveFocus(action) }
        step(16L)
    }

    @Test
    fun `a pad DOWN in the Home card's picker moves the ring to the next row, and the counter and the rows are drawn there`() {
        mountHomePicker()
        press {
            authority.rememberFocusForScope(OpenWithScope, OpenWithAutoKey)
            homePickerOpen = true
            authority.pushScope(OpenWithScope)
        }
        settle()
        assertEquals("the sheet seats its first row", OpenWithAutoKey, authority.state.value.focused)

        assertRingFollowsDown(move = ::homePress)
    }

    // The host half of the Home door: `MimirAppHost` builds the card's state in a body that runs for every change of every store behind it and hands it over
    // through `rememberEqualInstance`; the options come from its coordinator (`:app`), whose pick is `openWithOptionsAfterPick` and whose label rule is
    // `launchCardEmulatorLabel` with "Checking…" while a game with an override has no options yet.

    private var homeOptions by mutableStateOf<OpenWithOptions?>(null)
    private var hostPasses by mutableStateOf(0)

    private val checking = "Checking…"

    @Composable
    private fun HostBody() {
        @Suppress("UNUSED_VARIABLE")
        val pass = hostPasses
        val options = homeOptions
        val label = if (options == null) checking else launchCardEmulatorLabel(options)
        val state = rememberEqualInstance(
            HomeLaunchCardUiState(
                row = homeRow,
                platformLabel = "PlayStation Portable",
                emulatorLabel = label,
                lastPlayedLabel = "Yesterday",
                extraFacts = listOf("REGION" to "USA", "SIZE" to "1.2 GB"),
            ),
        )
        HomeLaunchCard(state = state, onPlay = {}, onAchievements = {}, onOpenWith = {}, onClose = {}, authority = authority)
    }

    @Test
    fun `the Home card's EMULATOR fact says Checking while the options are pending, then the pick once they land, and a pass that changes nothing leaves it alone`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme { Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) { HostBody() } }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        enterHomeLaunchCard(authority, emulated = true)
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        assertEquals("pending", checking, emulatorFact())

        val loaded = optionsFor(null)
        press { homeOptions = loaded }
        settle(200L)
        assertEquals("the options landed: the emulator the launch starts", "DuckStation", emulatorFact())

        repeat(3) {
            press { hostPasses += 1 }
            step(16L)
        }
        assertEquals("passes that changed nothing it draws leave the fact as it was", "DuckStation", emulatorFact())

        val ePSXe = loaded.candidates.first { it.emulatorId == EmulatorId("epsxe") }
        press { homeOptions = openWithOptionsAfterPick(loaded, ePSXe) }
        step(16L)
        assertEquals("the pick is named on the very next frame", "ePSXe", emulatorFact())
        assertFalse("and the old name is gone", shown("DuckStation"))

        press { homeOptions = null }
        step(16L)
        assertEquals("options dropped (the reload failed, the card reopened): pending again", checking, emulatorFact())
    }
}
