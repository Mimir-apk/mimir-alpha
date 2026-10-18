package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Utility's "Tweaker" row opens the Tweaker surface on the host that is showing the row.
 *
 * Measured 2026-09-11 on the Thor with a game on Main: M (Bottom) → UTILITY → tap the Tweaker row made
 * the Bottom panel vanish. `openTweakerSurface()` called `open(TWEAKER)` with the PRIMARY default, so
 * the coordinator re-homed onto a host that was not showing (MainActivity stopped behind the game, no
 * native primary command in flight) and sat "open" there until the game ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class UtilityTweakerRowTargetTest {

    @Test
    fun `the Tweaker row on the Bottom Utility keeps the panel on the Bottom`() = runTest {
        val coordinator = coordinator()
        coordinator.open(UtilitySurface.UTILITY, AppBrightnessPresentationTarget.SECONDARY)
        advanceUntilIdle()
        assertTrue(coordinator.isOpen)
        assertEquals(AppBrightnessPresentationTarget.SECONDARY, coordinator.presentationTarget)

        coordinator.openTweakerSurface()
        advanceUntilIdle()

        assertTrue("the row's request must not close the panel", coordinator.isOpen)
        assertEquals(UtilitySurface.TWEAKER, coordinator.surface)
        assertEquals(
            "the Tweaker surface must stay on the display whose row asked for it",
            AppBrightnessPresentationTarget.SECONDARY,
            coordinator.presentationTarget,
        )
    }

    @Test
    fun `the Tweaker row on the Main Utility opens the Tweaker surface on Main`() = runTest {
        val coordinator = coordinator()
        coordinator.open(UtilitySurface.UTILITY, AppBrightnessPresentationTarget.PRIMARY)
        advanceUntilIdle()

        coordinator.openTweakerSurface()
        advanceUntilIdle()

        assertTrue(coordinator.isOpen)
        assertEquals(UtilitySurface.TWEAKER, coordinator.surface)
        assertEquals(AppBrightnessPresentationTarget.PRIMARY, coordinator.presentationTarget)
    }

    @Test
    fun `Back on the Bottom Tweaker surface returns to the Bottom Utility`() = runTest {
        val coordinator = coordinator()
        coordinator.open(UtilitySurface.UTILITY, AppBrightnessPresentationTarget.SECONDARY)
        advanceUntilIdle()
        coordinator.openTweakerSurface()
        advanceUntilIdle()
        assertEquals("the footer must offer a route return", "Back", coordinator.backActionLabel)

        coordinator.handleSecondaryBack()
        advanceUntilIdle()

        assertTrue("Back must not close the panel (surface map F2)", coordinator.isOpen)
        assertEquals(UtilitySurface.UTILITY, coordinator.surface)
        assertEquals(AppBrightnessPresentationTarget.SECONDARY, coordinator.presentationTarget)
        assertEquals("back at Utility the footer reads Close again", "Close", coordinator.backActionLabel)
    }

    @Test
    fun `Close on the Bottom Utility still dismisses the panel`() = runTest {
        val coordinator = coordinator()
        coordinator.open(UtilitySurface.UTILITY, AppBrightnessPresentationTarget.SECONDARY)
        advanceUntilIdle()
        assertEquals("Close", coordinator.backActionLabel)

        coordinator.handleSecondaryBack()
        advanceUntilIdle()

        assertFalse(coordinator.isOpen)
    }

    @Test
    fun `a request with no open panel falls back to the primary host`() {
        assertEquals(
            AppBrightnessPresentationTarget.PRIMARY,
            tweakerSurfaceOpenTarget(isOpen = false, presentationTarget = AppBrightnessPresentationTarget.SECONDARY),
        )
        assertEquals(
            AppBrightnessPresentationTarget.SECONDARY,
            tweakerSurfaceOpenTarget(isOpen = true, presentationTarget = AppBrightnessPresentationTarget.SECONDARY),
        )
        assertEquals(
            AppBrightnessPresentationTarget.PRIMARY,
            tweakerSurfaceOpenTarget(isOpen = true, presentationTarget = AppBrightnessPresentationTarget.PRIMARY),
        )
    }

    /** A Thor-shaped controller whose privileged route is READY, so the Tweaker route decision is OPEN. */
    private fun TestScope.coordinator(): AppBrightnessCoordinator {
        val settings = vendorLightSettings(writeAccess = false).apply {
            values[BrightnessSettingsKeys.AYN_SECONDARY] = "74"
        }
        val controller = BrightnessController(
            settings = settings,
            preferences = object : WindowBrightnessPreferenceStore {
                override fun read(target: BrightnessTarget): Float? = null
                override fun write(target: BrightnessTarget, fraction: Float) = Unit
                override fun clear(target: BrightnessTarget) = Unit
            },
            identity = aynIdentity(),
            secondaryWindowAvailable = { false },
            privilegedUtility = ReadyPrivilegedGateway(settings.values),
        )
        assertEquals(
            "fixture: the privileged route must read READY for the Tweaker row to route at all",
            PrivilegedUtilityStatus.READY,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        return AppBrightnessCoordinator(
            context = RuntimeEnvironment.getApplication(),
            controller = controller,
            authority = DefaultFocusAuthority(),
            scope = this,
            privilegedIoDispatcher = StandardTestDispatcher(testScheduler),
            tweakerPort = ReadyTweakerPort,
        )
    }
}

private object ReadyTweakerPort : TweakerProfilePort {
    override fun utilityRowState(): TweakerUtilityRowState = TweakerUtilityRowState(
        availability = TweakerUtilityAvailability.READY,
        selectedProfileId = "stock",
        selectableProfileIds = listOf("stock", "quiet", "balanced", "performance"),
    )

    override suspend fun refreshCapabilities() = Unit

    override suspend fun selectSystemProfileSettled(
        profileId: String,
        admittedProfileIds: Set<String>,
        control: TweakerProfileSelectionControl,
    ): TweakerSystemProfileSelectionResult = TweakerSystemProfileSelectionResult.Active(profileId, changed = false)
}

/** READY vendor route whose readbacks mirror the settings fixture, so the controller keeps READY. */
private class ReadyPrivilegedGateway(private val values: Map<String, String>) : PrivilegedUtilityGateway {
    override fun posture(): PrivilegedUtilityPosture = PrivilegedUtilityPosture(
        status = PrivilegedUtilityStatus.READY,
        canWriteVendorSettings = true,
        canControlSecondaryBrightness = false,
    )

    override fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean = false
    override fun readVendorSetting(key: String): String? = values[key]
    override fun writeVendorSetting(key: String, value: String): Boolean = false
    override fun readDisplayBrightness(displayId: Int): Float? = null
    override fun setSecondaryBrightness(displayId: Int, fraction: Float, mirrorPercent: Int): Boolean = false
    override fun close() = Unit
}
