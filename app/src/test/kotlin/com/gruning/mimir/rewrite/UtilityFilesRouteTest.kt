package com.gruning.mimir.rewrite

import android.os.Environment
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * What the Utility "Files" row actually does when pressed — the route behaviour. The
 * focus-order and eligibility-rule halves live in `UtilityControlOrderTest`.
 *
 * The two refusals matter more than the success here. Policy §3c makes Utility the only door to
 * `FileBrowserMode.MANAGE`, the half of the browser that can move and delete, so the interesting
 * question is not "does it open" but "when does it decline to".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    application = android.app.Application::class,
    shadows = [ShadowAllFilesAccess::class],
)
class UtilityFilesRouteTest {

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
        ShadowAllFilesAccess.granted = false
        // FileBrowserStore is a process singleton and these tests share a JVM with every other test in
        // the module. Starting dirty would make a leaked controller from an earlier test read as this
        // one's success.
        FileBrowserStore.close()
        MainGameLedgerFixture.endAnyLive()
    }

    @After
    fun tearDown() {
        MainGameLedgerFixture.endAnyLive()
        FileBrowserStore.close()
        ShadowAllFilesAccess.granted = false
    }

    /**
     * The lockup guard, stated as behaviour rather than as a comment.
     *
     * `PrimaryMButtonPanelHost` builds this same coordinator with `hostsInAppSurfaces = false`, in a
     * native overlay window with its own `DefaultFocusAuthority` and no MainActivity behind it. If this
     * ever published a controller, `MimirAppHost` would later mount a browser whose focus scope lives on
     * that dead authority while `resolveMimirOverlayStack` ranks FOLDER_BROWSER above everything the
     * player could use to escape it — the divorced-browser input lockup.
     */
    @Test
    fun theNativePanelHostNeverPublishesABrowserController() = runTest {
        ShadowAllFilesAccess.granted = true
        val coordinator = coordinator(hostsInAppSurfaces = false)
        coordinator.refreshOnResume()

        // Precondition, or this test would pass for the wrong reason: the permission is held, so the
        // ONLY thing standing between this press and an opened browser is the host gate.
        assertTrue("precondition: all-files access is held", coordinator.allFilesAccessGranted)

        coordinator.openFilesManager()

        assertNull(
            "a Utility drawn into the native M-button window must never publish a FileBrowserController",
            FileBrowserStore.controller.value,
        )
    }

    /**
     * Without all-files access the browser would enumerate nothing and read as broken, so the press
     * asks Android for the permission instead. Pinning the intent, not just the absence of a browser:
     * "did nothing" and "asked for access" must not be indistinguishable.
     */
    @Test
    fun withoutAllFilesAccessTheRowAsksForItInsteadOfOpeningAnEmptyBrowser() = runTest {
        val application = RuntimeEnvironment.getApplication()
        val coordinator = coordinator(hostsInAppSurfaces = true)
        coordinator.refreshOnResume()
        assertFalse("precondition: access is NOT held", coordinator.allFilesAccessGranted)

        coordinator.openFilesManager()

        assertNull(
            "no browser may open without the access it needs to list anything",
            FileBrowserStore.controller.value,
        )
        val started = shadowOf(application).nextStartedActivity
        assertNotNull("pressing must raise the grant screen, not silently do nothing", started)
        assertEquals(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            started!!.action,
        )
        // Never self-granted — the row reports what Android says, and only refreshOnResume re-reads it.
        assertFalse(coordinator.allFilesAccessGranted)
    }

    /** The permission is re-read on return from the grant screen, or the row lies after it is granted. */
    @Test
    fun theAccessSnapshotIsRefreshedOnReturnFromTheGrantScreen() = runTest {
        val coordinator = coordinator(hostsInAppSurfaces = true)
        coordinator.refreshOnResume()
        assertFalse(coordinator.allFilesAccessGranted)

        ShadowAllFilesAccess.granted = true
        assertFalse(
            "a stale snapshot is the point: it must not update by itself between resumes",
            coordinator.allFilesAccessGranted,
        )

        coordinator.refreshOnResume()
        assertTrue(
            "refreshOnResume must re-read the permission, or the row still says NEEDS ACCESS after " +
                "the player granted it and the only way to fix it is reopening Mimir",
            coordinator.allFilesAccessGranted,
        )
    }


    /**
     * Wait for the browser to publish its controller.
     *
     * **`FileBrowserStore.open()` mounts ASYNCHRONOUSLY as of 2026-08-14**, and this assertion used to be
     * synchronous. The change is deliberate, not a regression: the mount now awaits the ROM-folder index
     * so the extract chooser never consults an empty platform-suggestion index. Room's
     * generated suspend DAO always crosses a real
     * dispatcher boundary, so no correct fix could have preserved synchronous publication — the contract
     * this test pins genuinely moved, and the test moves with it.
     *
     * **`runBlocking { controller.first { it != null } }` DEADLOCKS here and must not be reintroduced.**
     * Robolectric runs this test on the main thread; `FileBrowserStore.scope` is `Dispatchers.Main`, so
     * blocking that thread blocks the very resume being waited on. Draining the looper alone is also not
     * enough — it dispatches the coroutine, but Room's own background executor lands the result later.
     * Both halves are needed: `idle()` for the Main posts, real elapsed time for the executor.
     */
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

    /** With access held and a host that can show it, the press actually opens the browser. */
    @Test
    fun theUtilityRouteOpensTheBrowserWhenAccessIsHeld() = runTest {
        ShadowAllFilesAccess.granted = true
        val coordinator = coordinator(hostsInAppSurfaces = true)
        coordinator.refreshOnResume()
        assertTrue(coordinator.allFilesAccessGranted)

        coordinator.openFilesManager()

        assertNotNull(
            "with access held and a browser surface to draw into, pressing Files must open it",
            awaitBrowserMount(),
        )
    }

    /**
     * **The browser safety property, and the one assertion this module cannot make against
     * a live object.** `FileBrowserController.mode` is `internal` to `feature-files`, so no test in
     * `app` can read the mode off the controller [FileBrowserStore] just published. Asserted against
     * the call site's source instead — the same idiom
     * `UtilityControlOrderTest.frameCapRowIsWithheldFromTheRenderAndTheFocusOrderTogether` already uses
     * for a claim the JVM cannot reach.
     *
     * **Disclosed gap:** this proves the argument is written, not that `FileBrowserController` honours
     * it. That half is `feature-files`' own to pin.
     */
    @Test
    fun theUtilityRouteNamesManageModeAndTheAddGamesPickerStillCannotReachIt() {
        val coordinatorSource = appSource("AppBrightnessCoordinator.kt").readText()
        val openCall = coordinatorSource.substringAfter("FileBrowserStore.open(", "")
        assertTrue(
            "AppBrightnessCoordinator must open the browser at all",
            openCall.isNotEmpty(),
        )
        assertTrue(
            "Utility's route must name FileBrowserMode.MANAGE explicitly. Falling through to the " +
                "PICK default would give the file manager the add-games picker's commit affordance " +
                "and, once the queue lands, silently withhold the operations MANAGE exists to carry.",
            openCall.substringBefore(")").contains("mode = FileBrowserMode.MANAGE"),
        )

        // PICK must stay PICK: adding ROMs must not permit deletion. This holds only while
        // the add-games call site cannot name the destructive mode.
        val pickerSource = appSource("MimirSettingsCoordinator.kt").readText()
        assertFalse(
            "the add-games picker must never reach MANAGE -- that is the whole safety property",
            pickerSource.contains("FileBrowserMode.MANAGE"),
        )
        val storeSource = appSource("FileBrowserStore.kt").readText()
        assertTrue(
            "the store's default must stay PICK, so a caller that names no mode gets the half that " +
                "cannot delete",
            storeSource.contains("mode: FileBrowserMode = FileBrowserMode.PICK"),
        )
    }

    /**
     * Render and focus order must gate on the same flag. A row drawn where the focus order withholds it
     * is unreachable by D-pad; a row in the focus order that is never drawn eats the press that should
     * have reached the row below. Both have shipped on this rail.
     */
    @Test
    fun theFilesRowIsWithheldFromTheRenderAndTheFocusOrderTogether() {
        val overlay = appSource("BrightnessOverlay.kt").readText()
        val gate = overlay.lineSequence().firstOrNull {
            it.contains("filesRouteAvailable") && it.contains("UtilitySurface.UTILITY")
        }
        assertNotNull(
            "the Files row's render must gate on BOTH the Utility surface and filesRouteAvailable, or " +
                "it draws where brightnessControls withholds it",
            gate,
        )
        assertTrue(
            "the row must actually be drawn, not merely gated: $gate",
            overlay.contains("railControlModifier(BrightnessControlKey.OPEN_FILES)"),
        )
    }

    /**
     * R2 : the Files row is hidden while a game is running, and comes back after. One model flag drives the rail,
     * the focus order and the press, so a controller can neither land on a hidden row nor press one.
     */
    @Test
    fun aRunningGameWithdrawsTheFilesRowAndItsPressAndTheRowComesBackAfterwards() = runTest {
        ShadowAllFilesAccess.granted = true
        val coordinator = coordinator(hostsInAppSurfaces = true)
        coordinator.refreshOnResume()
        assertTrue("precondition: nothing is running, so the row is offered", coordinator.filesRouteOffered)
        val withoutGame = brightnessControls(
            state = filesRowState(),
            tweakerAvailability = TweakerUtilityAvailability.READY,
            filesRouteAvailable = coordinator.filesRouteOffered,
        )
        assertTrue(BrightnessControlKey.OPEN_FILES in withoutGame)

        // A real session in the ledger, which the host mirrors into the model (RC-06: the press now re-reads the ledger itself,
        // so a flag written by hand would be overwritten, as a stale one is meant to be).
        val operation = MainGameLedgerFixture.start()
        Snapshot.withMutableSnapshot { coordinator.refreshGameRunning() }

        assertFalse("a game is running: the row is not offered", coordinator.filesRouteOffered)
        val withGame = brightnessControls(
            state = filesRowState(),
            tweakerAvailability = TweakerUtilityAvailability.READY,
            filesRouteAvailable = coordinator.filesRouteOffered,
        )
        assertFalse("nor is it in the focus order", BrightnessControlKey.OPEN_FILES in withGame)
        assertTrue("control: the rest of the rail is untouched", BrightnessControlKey.OPEN_TWEAKER in withGame)
        coordinator.openFilesManager()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("and a press that arrives anyway opens no browser", FileBrowserStore.controller.value)

        MainGameLedgerFixture.end(operation)
        Snapshot.withMutableSnapshot { coordinator.refreshGameRunning() }

        assertTrue("the game ended: the row is back", coordinator.filesRouteOffered)
        coordinator.openFilesManager()
        assertNotNull("and it opens the browser again", awaitBrowserMount())
    }

    @Test
    fun theRowIsOfferedOnlyWhenTheHostHostsBrowsersAndNoGameRuns() {
        assertTrue(utilityFilesRowOffered(hostsInAppSurfaces = true, gameRunning = false))
        assertFalse(utilityFilesRowOffered(hostsInAppSurfaces = true, gameRunning = true))
        assertFalse("the native panel host keeps its own refusal", utilityFilesRowOffered(hostsInAppSurfaces = false, gameRunning = false))
        assertFalse(utilityFilesRowOffered(hostsInAppSurfaces = false, gameRunning = true))
    }

    private fun filesRowState() = BrightnessState(
        capabilities = BrightnessCapabilities(
            canWriteSystemSettings = true,
            mainSystemBrightnessActive = true,
            secondaryWindowAvailable = false,
            secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
            joystickLightSupported = false,
            joystickLightColorSupported = false,
            mimirHapticsSupported = false,
            mimirHapticAmplitudeControl = false,
            mediaVolumeSupported = true,
            mediaVolumeFixed = false,
            mainWriteAccessUseful = true,
            privilegedUtilityPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.READY),
            navSoundSupported = false,
            ambientSoundSupported = false,
        ),
        primaryFraction = 0.5f,
        primaryWindowFallback = null,
        secondaryFraction = null,
        secondaryWindowFallback = null,
        joystickLightFraction = null,
        joystickColorFraction = null,
        joystickColorArgb = null,
        themeAwareJoystickLight = false,
        mimirHapticFraction = null,
        mediaVolumeFraction = 0.4f,
        themeAwareJoystickLightRequested = false,
        themeAwareJoystickLightRestorePending = false,
        ambientSoundFraction = null,
    )

    private fun coordinator(hostsInAppSurfaces: Boolean) = AppBrightnessCoordinator(
        context = RuntimeEnvironment.getApplication(),
        controller = null,
        authority = DefaultFocusAuthority(),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        hostsInAppSurfaces = hostsInAppSurfaces,
    )

    private fun appSource(fileName: String): java.io.File = listOf(
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}

/**
 * `Environment.isExternalStorageManager()` is a static framework call with no Robolectric shadow setter
 * in 4.14.1 (`ShadowEnvironment` exposes `setIsExternalStorageEmulated`/`setIsExternalStorageLegacy`
 * only), so the grant state is shadowed here rather than left at its default. Without it every test
 * above could only ever exercise the denied branch.
 */
@Implements(Environment::class)
class ShadowAllFilesAccess {
    companion object {
        @JvmStatic
        var granted: Boolean = false

        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = granted
    }
}
