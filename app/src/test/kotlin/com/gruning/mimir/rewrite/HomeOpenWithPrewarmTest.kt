package com.gruning.mimir.rewrite

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
import com.gruning.mimir.rewrite.ComposableInvocationTally.Companion.bodiesOf
import com.gruning.mimir.rewrite.ComposableInvocationTally.Companion.oursInOrder
import com.gruning.mimir.rewrite.ComposableInvocationTally.Companion.runsOf
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.OpenWithAutoKey
import com.gruning.mimir.rewrite.feature.library.OpenWithScope
import com.gruning.mimir.rewrite.feature.library.openWithFocusKey
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectPanelTestTag
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * OPEN WITH on Home's launch card takes the Library door's path. The REAL [HomeLaunchCardCoordinator], the real focus authority and the real [HomeLaunchOpenWithOverlay] (the composable `MimirAppHost`
 * puts in its direct overlay host's standby slot), driven the way the card's A drives them, and held to what `OpenWithPrewarmTest` (feature-library) holds the Library door to:
 *
 * 1. **Built ahead, and nothing of it is open.** The sheet's bodies have run; it is not drawn, not in the semantics tree, has registered no row and owns no Back.
 * 2. **A composes no sheet.** The press frame runs no panel, no viewport, no row, no icon: the sheet is placed, its rows register in that frame's layout, the first is seated.
 * 3. **A close returns it to waiting; a pick does not recompose it in its exit** (the options the pick writes reach the standing sheet one deadline after the pop, once).
 * 4. **The same frames as the Library door**, every one of them: the block between the markers below is read back against `OpenWithPrewarmTest`'s.
 *
 * What this does not reach: the direct overlay host itself (`MimirPixelFadeHost` is private and needs the app's whole bindings), so that it composes the standby, and keeps the
 * slot's position across the target flip, is pinned in source here and proved on the Library's own host there. The glass is not a frame sequence a test can read without pixels:
 * its snapshot rule is held to the Library's below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeOpenWithPrewarmTest {

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

    private val game = GameRow(gameId = GameId("ps1/xenogears"), title = "Xenogears", platformId = PlatformId.PS1, memberCount = 1, duplicateKind = null)

    private val authority = DefaultFocusAuthority()
    private val tally = ComposableInvocationTally()
    private lateinit var card: HomeLaunchCardCoordinator

    /** The coordinator only holds its launch controller, and these paths never reach it but through a launch that fails and is dropped; an instance without a constructor call is enough. */
    private fun untouchedLaunchController(): LaunchController {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LaunchController::class.java) as LaunchController
    }

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

    /** A card opened over the Home shelf on a game whose options are known (as a card for a game shown before is), and the sheet mounted as the host mounts it: standing. */
    private fun mountCard() {
        val cache = HomeLaunchCardOptionsCache().also { it[game.gameId] = options }
        // A launch that fails (the controller is not wired) is dropped, as it would be; nothing in these paths needs it to land.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })
        card = HomeLaunchCardCoordinator(authority, untouchedLaunchController(), scope, openWithCache = cache)
        val row = libraryHeroPickerRow(game)
        authority.register(row.focusKey, HomeShelfScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.pushScope(HomeShelfScope)
        authority.requestFocus(row.focusKey)
        assertTrue("the card opens from its tile", card.open(row, game, HomeShelfScope, row.focusKey))
        assertEquals("and starts with its options", options, card.openWithOptions)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        HomeLaunchOpenWithOverlay(card, authority)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun panelNodes(unmerged: Boolean) =
        compose.onAllNodes(hasTestTag(MimirSelectPanelTestTag), useUnmergedTree = unmerged).fetchSemanticsNodes()

    private fun panelOpen() = panelNodes(unmerged = false).isNotEmpty() && panelNodes(unmerged = true).any { it.layoutInfo.isPlaced }

    private fun backIsOwned() = compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()

    private fun List<Map<String, Int>>.runs(fragment: String, frames: IntRange = indices): Int = slice(frames).sumOf { it.runsOf(fragment) }

    /** A on the OPEN WITH chip, as the pad sends it: the focus on the chip, then the card's own dispatch. */
    private fun pressA() {
        authority.requestFocus(HomeLaunchCardOpenWithKey)
        card.dispatch(ControllerAction.CONFIRM, onPlay = {}, onPlayEmulated = { _, _ -> }, onEdit = {}, onEditTitle = {}, onAchievements = {})
    }

    private fun openSheet() = press { pressA() }

    private fun frames(action: () -> Unit, count: Int = 8): List<Map<String, Int>> = ArrayList<Map<String, Int>>().also { out ->
        out += tally.measure {
            press(action)
            step(16L)
        }
        repeat(count - 1) { out += tally.measure { step(16L) } }
    }

    @Test
    fun `while the card is up the sheet stands built, and none of it is open`() {
        mountCard()
        val ran = tally.measure { settle(320L) }
        // The mount ran it (the tally above started after), so look at what the tree holds.
        assertEquals("not in the semantics tree a test or a screen reader reads", 0, panelNodes(unmerged = false).size)
        assertTrue("and what the unmerged tree still holds is not placed: not drawn, not hit, not registered", panelNodes(unmerged = true).none { it.layoutInfo.isPlaced })
        assertTrue("it has been laid out (measured) already, so it is a sheet and not a promise", panelNodes(unmerged = true).isNotEmpty())
        assertEquals("no row of it registered with the authority", emptyList<Any>(), authority.registeredKeysInScope(OpenWithScope))
        assertFalse("it owns no Back, so the card behind it keeps the system Back", backIsOwned())
        assertEquals("and nothing runs while it waits: ${ran.oursInOrder()}", 0, ran.runsOf("feature.settings"))
    }

    @Test
    fun `A on OPEN WITH composes no sheet on the press frame, places it, and its rows register and seat`() {
        mountCard()
        val out = frames({ pressA() })
        val why = out.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.joinToString("\n")

        listOf("MimirLiquidGlassPanel", "rememberMeasuredViewport", "rememberAppIcon", "OpenWithOverlay", "MimirSelectOverlay", "OpenWithStandingSheet", "HomeLaunchOpenWithOverlay")
            .forEach { name -> assertEquals("$name ran in the press frame\n$why", 0, out.runs(name, 0..0)) }
        assertEquals("no row is built by the press\n$why", 0, out[0].bodiesOf("com.gruning.mimir.rewrite.feature.settings.SelectRow"))
        assertTrue("what runs is the few bodies that read the activation\n$why", out[0].runsOf("feature.settings") <= 4)
        assertTrue("the sheet is placed: in the tree", panelOpen())
        assertEquals("the rows registered in the press frame's layout and the first is seated", OpenWithAutoKey, authority.state.value.focused)
        assertTrue(
            "every row registered",
            authority.registeredKeysInScope(OpenWithScope).containsAll(listOf(OpenWithAutoKey, openWithFocusKey("duckstation"), openWithFocusKey("epsxe"), openWithFocusKey("retroarch"))),
        )
        assertTrue("Back is the sheet's now", backIsOwned())
        assertTrue("the seating after the press is a handful of bodies, not a sheet\n$why", out.slice(1..7).sumOf { it.runsOf("feature.settings") } <= 30)
    }

    @Test
    fun `closing returns the sheet to waiting, and opening it again composes nothing either`() {
        mountCard()
        openSheet()
        settle(800L)
        assertTrue(panelOpen())

        val closing = frames({ card.dispatchOpenWith(ControllerAction.CANCEL) }, count = 40)
        val why = closing.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.filter { it.contains("settings") || it.contains("OpenWith") }.joinToString("\n")
        listOf("MimirLiquidGlassPanel", "rememberMeasuredViewport", "OpenWithOverlay", "MimirSelectOverlay", "OpenWithStandingSheet")
            .forEach { name -> assertEquals("$name ran for a close\n$why", 0, closing.runs(name)) }
        assertTrue("a close re-runs the ring leaving one row, a handful of bodies\n$why", closing.runs("feature.settings") <= 12)
        assertEquals("the sheet is out of the tree again", 0, panelNodes(unmerged = false).size + panelNodes(unmerged = true).count { it.layoutInfo.isPlaced })
        assertEquals("nothing of it is registered", emptyList<Any>(), authority.registeredKeysInScope(OpenWithScope))
        assertFalse("and Back is the card's again", backIsOwned())

        val again = frames({ pressA() })
        val whyAgain = again.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.joinToString("\n")
        listOf("MimirLiquidGlassPanel", "rememberMeasuredViewport", "OpenWithOverlay", "MimirSelectOverlay")
            .forEach { name -> assertEquals("$name ran when it was opened again\n$whyAgain", 0, again.runs(name, 0..0)) }
        assertEquals(0, again[0].bodiesOf("com.gruning.mimir.rewrite.feature.settings.SelectRow"))
        assertEquals(OpenWithAutoKey, authority.state.value.focused)
    }

    @Test
    fun `choosing an emulator leaves the sheet alone while its exit runs, then hands it the picked options once`() {
        mountCard()
        openSheet()
        settle(800L)
        press { card.dispatchOpenWith(ControllerAction.NAV_DOWN) }
        step(16L)
        press { card.dispatchOpenWith(ControllerAction.NAV_DOWN) }
        step(16L)
        assertEquals(openWithFocusKey("epsxe"), authority.state.value.focused)

        val out = frames({ card.dispatchOpenWith(ControllerAction.CONFIRM) }, count = 40)
        val why = out.mapIndexed { i, f -> "f$i: ${f.oursInOrder()}" }.filter { it.contains("settings") || it.contains("OpenWith") }.joinToString("\n")
        assertTrue("the pick wrote its options at once", card.openWithOptions?.candidates?.firstOrNull { it.emulatorId.value == "epsxe" }?.isCurrentOverride == true)

        val exit = 0..(MimirMotion.OverlayMs / 16)
        // The sheet itself is not run; what runs on the pick frame is the card's wrapper and the standing slot reading the new options (a few bodies, no panel, no row).
        val sheet = "com.gruning.mimir.rewrite.feature.library.OpenWithOverlay"
        assertEquals("the sheet ran while the pick's exit was on screen\n$why", 0, out.slice(exit).sumOf { it.bodiesOf(sheet) })
        assertEquals("MimirSelectOverlay ran while the pick's exit was on screen\n$why", 0, out.runs("MimirSelectOverlay", exit))
        assertTrue("the slot reads the options once on the pick frame\n$why", out.runs("OpenWithStandingSheet", exit) <= 2)
        assertTrue("the ring leaving one row, a handful of bodies\n$why", out.runs("feature.settings", exit) <= 12)
        val after = (exit.last + 1) until out.size
        assertEquals("the sheet takes the options once, after the exit\n$why", 1, out.slice(after).sumOf { it.bodiesOf(sheet) })
        assertFalse("and the sheet is not open", panelOpen())
    }

    // ---- the door's sequence --------------------------------------------------------------------------------------------------------------------------------

    private fun doorFrame(): String {
        val focused = authority.state.value.focused
        val focus = when (focused) {
            null -> "none"
            OpenWithAutoKey -> "auto"
            HomeLaunchCardOpenWithKey -> "chip"
            else -> options.candidates.firstOrNull { openWithFocusKey(it.emulatorId.value) == focused }?.let { "row:${it.emulatorId.value}" } ?: "other"
        }
        val rows = authority.registeredKeysInScope(OpenWithScope).size
        return "placed=${panelOpen()} rows=$rows back=${backIsOwned()} scope=${authority.state.value.scope == OpenWithScope} focus=$focus"
    }

    /** The frame the press lands in and the seven after it. */
    private fun doorFrames(action: () -> Unit): List<String> {
        val out = ArrayList<String>()
        press(action)
        step(16L)
        out += doorFrame()
        repeat(7) {
            step(16L)
            out += doorFrame()
        }
        return out
    }

    @Test
    fun `the Home door opens, cancels and picks in the same frames as the Library door`() {
        mountCard()
        val closed = doorFrame()
        val open = doorFrames { pressA() }
        settle(800L)
        val cancel = doorFrames { card.dispatchOpenWith(ControllerAction.CANCEL) }
        settle(800L)
        doorFrames { pressA() }
        settle(800L)
        press { card.dispatchOpenWith(ControllerAction.NAV_DOWN) }
        step(16L)
        press { card.dispatchOpenWith(ControllerAction.NAV_DOWN) }
        step(16L)
        val pick = doorFrames { card.dispatchOpenWith(ControllerAction.CONFIRM) }

        val observed = mapOf("closed" to listOf(closed), "open" to open, "cancel" to cancel, "pick" to pick)
        DOOR_SEQUENCE.forEach { (phase, line) ->
            observed.getValue(phase).forEachIndexed { i, frame -> assertEquals("the Home door, $phase, frame $i", line, frame) }
        }
    }

    @Test
    fun `the lines above are the lines the Library door is held to`() {
        val library = listOf(
            File("feature-library/src/test/kotlin/com/gruning/mimir/rewrite/feature/library/OpenWithPrewarmTest.kt"),
            File("../feature-library/src/test/kotlin/com/gruning/mimir/rewrite/feature/library/OpenWithPrewarmTest.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("OpenWithPrewarmTest.kt not found from ${File(".").absolutePath}")
        val here = listOf(
            File("app/src/test/kotlin/com/gruning/mimir/rewrite/HomeOpenWithPrewarmTest.kt"),
            File("src/test/kotlin/com/gruning/mimir/rewrite/HomeOpenWithPrewarmTest.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("HomeOpenWithPrewarmTest.kt not found from ${File(".").absolutePath}")

        fun block(text: String) = text.substringAfter("// DOOR-SEQUENCE-" + "BEGIN").substringBefore("// DOOR-SEQUENCE-" + "END").trim()
        assertTrue("the Library test carries the block", block(library).contains("val DOOR_SEQUENCE"))
        assertEquals("one sequence for both doors", block(library), block(here))
    }

    // ---- the glass and the host ---------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the card is held as a snapshot under a picker by the Library's rule, and the rename editor keeps what it had`() {
        val bools = listOf(false, true)
        for (requested in bools) for (arrived in bools) for (leaving in bools) {
            assertEquals(
                "picker=$requested arrived=$arrived leaving=$leaving",
                com.gruning.mimir.rewrite.feature.library.libraryCardSnapshotWanted(requested, arrived, leaving),
                homeLaunchCardSnapshotWanted(titleEditGlass = false, pickerRequested = requested, arrived = arrived, leaving = leaving),
            )
            assertTrue("the rename editor's glass holds the card whatever else is true", homeLaunchCardSnapshotWanted(true, requested, arrived, leaving))
        }
        assertFalse("a committed pick thaws the card at once: no request, the card is live behind the clearing glass", homeLaunchCardSnapshotWanted(false, false, true, false))
    }

    private val host: String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("MimirAppHost.kt not found from ${File(".").absolutePath}")

    @Test
    fun `the direct host keeps the sheet composed in its standby slot and hands the sheet the card and the authority, not the bindings`() {
        val pixelHost = host.substringAfter("private fun <T : Any> MimirPixelFadeHost(").substringBefore("private fun MimirDirectOverlayPixelFadeHost(")
        assertTrue("the standby is composed when there is no target", pixelHost.contains("(target ?: standby)?.let { content(it) }"))
        assertTrue("and never mistaken for the target: the ledger, the layers and the clocks read `target`", !pixelHost.contains("epochs.prepare(target ?: standby"))

        val overlayHost = host.substringAfter("private fun MimirAppOverlayHost(").substringBefore("private fun <T : Any> MimirPixelFadeHost(")
        assertTrue(overlayHost.contains("homeLaunchOpenWithWanted(card, active)"))
        assertTrue(overlayHost.contains("rememberLibraryOpenWithPrewarm("))
        assertTrue(overlayHost.contains("onChip = { homeLaunchOpenWithChipFocused(authority) }"))
        assertTrue(overlayHost.contains("val openWithStandby = MimirDirectOverlayTarget(MimirOverlayKind.HOME_OPEN_WITH).takeIf { openWithBuiltAhead }"))
        assertTrue(overlayHost.contains("MimirDirectOverlayPixelFadeHost(bindings, directTarget, reducedMotion, standby = openWithStandby)"))

        assertTrue(
            "the sheet is handed what is stable across the app's passes, not the bindings that are a new object on each",
            host.contains("MimirOverlayKind.HOME_OPEN_WITH -> HomeLaunchOpenWithOverlay(bindings.homeLaunchCard, bindings.core.focusAuthority)"),
        )
        assertFalse("the second Back handler is gone: the sheet owns its own, and only while it is open", host.contains("private fun HomeLaunchOpenWithOverlay("))
    }

    @Test
    fun `the card's glass holds the card by the one rule and keys it on the arrival like the Library's`() {
        val cardHost = host.substringAfter("private fun HomeLaunchCardOverlayHost(").substringBefore("private fun HomeLaunchCardLiveContent(")
        assertTrue(cardHost.contains("val cardGlassModifier = remember(cardGlass, incomingAlpha) {"))
        assertTrue(cardHost.contains("homeLaunchCardSnapshotWanted("))
        assertTrue(cardHost.contains("pickerRequested = pickerRequestedNow.value,"))
        assertTrue(cardHost.contains("arrived = incomingAlpha.value >= 1f,"))
        assertTrue(cardHost.contains("leaving = outgoingVisibleNow.value,"))
        assertFalse("not frozen whenever the glass is at its peak any more", cardHost.contains("snapshot = { true }"))
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
