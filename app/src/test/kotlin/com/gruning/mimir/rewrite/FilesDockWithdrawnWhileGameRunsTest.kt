package com.gruning.mimir.rewrite

import android.os.Looper
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * While a game runs, withdraw the Files row from the rail, focus order and press dispatch;
 * restore it when the game ends. The Utility row honoured it; the dock's Files seat did not (combined43 sweep F1,
 * emulator 2026-10-15): with a game YIELDED (running behind Mimir, or HOME-parked, which keeps the ledger YIELDED on purpose) the seat was
 * still drawn, still in the D-pad ring, and a press on it toasted "OPEN MIMIR TO BROWSE FILES", the copy that belongs to the native overlay
 * host and is false inside MainActivity. `openFilesManager` folded the game into the host gate, so a withdrawn row read as "no host".
 *
 * Robolectric, like `FilesDockAfterGameEndsTest`: the press goes through the real coordinator, the toast through `ShadowToast`, the browser
 * mount through the real store. The Home dock takes the same answer in feature-library (`HomeDockFilesSeatWithdrawnTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    application = android.app.Application::class,
    shadows = [ShadowAllFilesAccess::class],
)
class FilesDockWithdrawnWhileGameRunsTest {

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
        ShadowAllFilesAccess.granted = true
        FileBrowserStore.close()
        MainGameLedgerFixture.endAnyLive()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        MainGameLedgerFixture.endAnyLive()
        FileBrowserStore.close()
        ShadowAllFilesAccess.granted = false
    }

    @Test
    fun aYieldedGameWithdrawsTheDockSeatFromTheRailTheRingAndThePressAndTheSeatComesBackWhenTheGameEnds() {
        val coordinator = coordinator()
        coordinator.refreshOnResume()
        assertTrue("precondition: all-files access is held, so only R2 can withhold the browser", coordinator.allFilesAccessGranted)

        // The emulator took the screen (or HOME parked it: the same ledger phase, kept on purpose).
        val operation = MainGameLedgerFixture.start()
        assertTrue(MimirSessionAuthority.gameYielded(operation))
        val running = filesRouteGameRunning(MimirSessionAuthority.state.value)
        assertTrue("YIELDED holds the screen", running)

        // The rail: the seat is not offered, on either Library rail, and the rest of the dock is untouched.
        val withGame = libraryQuickRailActions(homeEnabled = true, filesSeatOffered = !running)
        assertFalse(MimirQuickRailAction.FILES in withGame)
        assertFalse(MimirQuickRailAction.FILES in libraryQuickRailActions(homeEnabled = false, filesSeatOffered = !running))
        assertEquals(libraryQuickRailActions(homeEnabled = true).dropLast(1), withGame)

        // The focus order: LEFT from the rail's first seat wraps to QUICK LAUNCH, not to a seat that is not drawn. The withdrawn seat is not
        // registered at all (LibraryShelfQuickRail registers it only while offered), so the ring is the drawn rail.
        val authority = authorityOnLibraryHero(filesSeatOffered = !running)
        dispatchLibraryQuickRail(authority, ControllerAction.NAV_DOWN, homeEnabled = false, filesSeatOffered = !running, onAction = {})
        assertEquals(libraryQuickRailKey(MimirQuickRailAction.LIBRARY), authority.state.value.focused)
        dispatchLibraryQuickRail(authority, ControllerAction.NAV_LEFT, homeEnabled = false, filesSeatOffered = !running, onAction = {})
        assertEquals(libraryQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH), authority.state.value.focused)

        // The press: a stray one (the dock calls exactly this, MimirQuickRailAction.FILES) opens nothing and says nothing.
        coordinator.openFilesManager()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("R2: a game is running, so the press opens no browser", FileBrowserStore.controller.value)
        assertNull("and no toast: the no-host copy is the native overlay's, never MainActivity's", ShadowToast.getTextOfLatestToast())
        assertFalse(coordinator.filesRouteOffered)

        // The game ends: the seat is back, and the same press opens the manager.
        MainGameLedgerFixture.end(operation)
        assertTrue(MimirQuickRailAction.FILES in libraryQuickRailActions(homeEnabled = true, filesSeatOffered = !filesRouteGameRunning(MimirSessionAuthority.state.value)))
        coordinator.openFilesManager()
        assertNotNull("the game ended: the same seat opens the manager", awaitBrowserMount())
        assertTrue(coordinator.filesRouteOffered)
    }

    @Test
    fun theDecisionHasItsOwnGameRunningArmAndTheNoHostCopyBelongsToTheNativeOverlayAlone() {
        assertEquals(FilesRouteDecision.GAME_RUNNING, filesRouteDecision(hostsInAppSurfaces = true, allFilesAccessGranted = true, gameRunning = true))
        assertEquals("the game outranks the access ask: nothing to ask for while it is withdrawn", FilesRouteDecision.GAME_RUNNING, filesRouteDecision(hostsInAppSurfaces = true, allFilesAccessGranted = false, gameRunning = true))
        assertEquals("the host gate outranks the game: a window with no browser has nothing to withdraw", FilesRouteDecision.UNAVAILABLE, filesRouteDecision(hostsInAppSurfaces = false, allFilesAccessGranted = true, gameRunning = true))
        assertEquals(FilesRouteDecision.OPEN, filesRouteDecision(hostsInAppSurfaces = true, allFilesAccessGranted = true, gameRunning = false))
        assertEquals("the two-argument call is the old answer, unchanged", FilesRouteDecision.OPEN, filesRouteDecision(hostsInAppSurfaces = true, allFilesAccessGranted = true))

        val source = appSource("AppBrightnessCoordinator.kt").readText()
        val press = source.substringAfter("internal fun openFilesManager() {").substringBefore("private fun transferAnnouncement(")
        assertEquals("the no-host copy is written once in the coordinator", 1, Regex("OPEN MIMIR TO BROWSE FILES").findAll(source).count())
        val gameArm = press.substringAfter("FilesRouteDecision.GAME_RUNNING ->").substringBefore("FilesRouteDecision.UNAVAILABLE ->")
        assertFalse("the game-running arm shows no toast", gameArm.contains("Toast"))
        assertTrue("the no-host copy sits under the native host's arm alone", press.substringAfter("FilesRouteDecision.UNAVAILABLE ->").contains("OPEN MIMIR TO BROWSE FILES"))
    }

    /**
     * Lead (fix round, 2026-10-15): a seat withdrawn while registered kept a dead focus (nothing lit, A dead). The screen registers the seat
     * only while it is offered, so its unregistration is the authority's own fall to the nearest neighbour; from there the D-pad travels the
     * drawn ring, and LEFT/RIGHT from a seat the rail does not know go to the neighbour in the pressed direction, on this dock as on Home's.
     */
    @Test
    fun aSeatWithdrawnUnderTheFocusFallsToItsNeighbourBecauseItIsNoLongerRegistered() {
        val authority = authorityOnLibraryHero(filesSeatOffered = true)
        dispatchLibraryQuickRail(authority, ControllerAction.NAV_DOWN, homeEnabled = false, filesSeatOffered = true, onAction = {})
        dispatchLibraryQuickRail(authority, ControllerAction.NAV_LEFT, homeEnabled = false, filesSeatOffered = true, onAction = {})
        assertEquals("the end-cap holds the focus", libraryQuickRailKey(MimirQuickRailAction.FILES), authority.state.value.focused)

        // The game yields: the screen's Files modifier is a bare Modifier now and its registration is disposed.
        authority.unregister(libraryQuickRailKey(MimirQuickRailAction.FILES))
        assertEquals("the focus falls to the neighbour, lit", libraryQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH), authority.state.value.focused)
        assertFalse(libraryQuickRailKey(MimirQuickRailAction.FILES) in authority.registeredKeysInScope(LibraryQuickRailScope))

        val activated = mutableListOf<MimirQuickRailAction>()
        dispatchLibraryQuickRail(authority, ControllerAction.CONFIRM, homeEnabled = false, filesSeatOffered = false, onAction = activated::add)
        assertEquals("A works where the focus landed", listOf(MimirQuickRailAction.QUICK_LAUNCH), activated)
        dispatchLibraryQuickRail(authority, ControllerAction.NAV_RIGHT, homeEnabled = false, filesSeatOffered = false, onAction = {})
        assertEquals("the ring is the drawn rail", libraryQuickRailKey(MimirQuickRailAction.LIBRARY), authority.state.value.focused)

        // A focus the rail does not know (a key in the rail scope that is no seat) travels to the neighbour in the pressed direction.
        val ghost = FocusKey("library/rail/ghost")
        authority.registerVirtual(ghost, LibraryQuickRailScope, Bounds.copy(left = 900f, right = 964f))
        authority.requestFocus(ghost)
        dispatchLibraryQuickRail(authority, ControllerAction.NAV_LEFT, homeEnabled = false, filesSeatOffered = false, onAction = {})
        assertEquals(libraryQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH), authority.state.value.focused)
        authority.requestFocus(ghost)
        dispatchLibraryQuickRail(authority, ControllerAction.NAV_RIGHT, homeEnabled = false, filesSeatOffered = false, onAction = {})
        assertEquals(libraryQuickRailKey(MimirQuickRailAction.BRIGHTNESS), authority.state.value.focused)
    }

    @Test
    fun theSeatIsRegisteredOnlyWhileOfferedAndBothDocksTravelAlike() {
        val library = appSource("AppShelfQuickRail.kt").readText()
        val filesModifier = library.substringAfter("val filesModifier = ").substringBefore("MimirRunicQuickRail(")
        assertTrue("the Library dock registers Files only while it is offered", filesModifier.startsWith("if (filesSeatOffered) {"))
        assertTrue(filesModifier.substringBefore("} else {").contains("Modifier.mimirFocusable("))
        assertTrue(filesModifier.substringAfter("} else {").trim().startsWith("Modifier\n"))
        assertEquals("one Files registration on the Library dock", 1, Regex(Regex.escape("libraryQuickRailKey(MimirQuickRailAction.FILES),\n            scope = LibraryQuickRailScope,")).findAll(library).count())

        val home = featureLibrarySource("HomeShelfScreen.kt").readText()
        val filesRailModifier = home.substringAfter("val filesRailModifier = ").substringBefore("MimirRunicQuickRail(")
        assertTrue("the Home dock registers Files only while it is offered", filesRailModifier.startsWith("if (filesSeatOffered) {"))
        assertTrue(filesRailModifier.substringBefore("} else {").contains("Modifier.mimirFocusable("))

        val travel = "(current + delta).floorMod(actions.size)"
        assertTrue(library.substringAfter("ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT ->").contains(travel))
        val homeModels = featureLibrarySource("HomeShelfModels.kt").readText()
        assertTrue("the Home dock travels on the Library's formula", homeModels.substringAfter("private fun moveHomeQuickRail(").contains(travel))
    }

    private fun featureLibrarySource(fileName: String): java.io.File = listOf(
        java.io.File("../feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$fileName"),
        java.io.File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")

    private fun coordinator() = AppBrightnessCoordinator(
        context = RuntimeEnvironment.getApplication(),
        controller = null,
        authority = DefaultFocusAuthority(),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        hostsInAppSurfaces = true,
    )

    /**
     * As `AppShelfQuickRailTest.authorityOnLibraryHero`: a focused Library tile, with the OFFERED rail seats registered, as the screen does:
     * PHYSICALLY (`mimirFocusable` registers in `onGloballyPositioned` and unregisters in its DisposableEffect), so the bench's
     * `unregister` is the screen's own disposal and falls the same way.
     */
    private fun authorityOnLibraryHero(filesSeatOffered: Boolean) = DefaultFocusAuthority().apply {
        pushScope(LibraryShelfScope)
        registerVirtual(HeroKey, LibraryShelfScope, Bounds)
        requestFocus(HeroKey)
        libraryQuickRailActions(homeEnabled = false, filesSeatOffered = filesSeatOffered).forEachIndexed { index, action ->
            register(libraryQuickRailKey(action), LibraryQuickRailScope, Bounds.copy(left = index * 80f, right = index * 80f + 64f))
        }
    }

    /** As `UtilityFilesRouteTest.awaitBrowserMount`: `FileBrowserStore.open()` mounts asynchronously, so drain Main and wait on Room's executor. */
    private fun awaitBrowserMount(timeoutMs: Long = 5_000L): FileBrowserController? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            FileBrowserStore.controller.value?.let { return it }
            Thread.sleep(5L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return FileBrowserStore.controller.value
    }

    private fun appSource(fileName: String): java.io.File = listOf(
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")

    private companion object {
        val HeroKey = FocusKey("library/shelf/snes")
        val Bounds = FocusBounds(0f, 0f, 64f, 64f)
    }
}
