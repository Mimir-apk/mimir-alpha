package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_STOCK_PROFILE_ID
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlStrategy
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

/**
 *  The fan readout on the PROFILE row
 * ("Automatic fan · 25% · 62°C") sits behind two 3.5s freshness gates
 * (`TweakerBridge.freshUtilityFanControl` / `freshUtilityFanGpuTempC`) so it must hold the LAST
 * KNOWN reading across a momentarily-stale sample rather than disappear, for as long as a
 * Mimir-owned fan session (a non-Stock profile) is selected -- and clear the moment that session
 * ends, so a held reading is never shown as though it belonged to a different profile or a
 * different (reopened) panel session.
 *
 * These tests drive [AppBrightnessCoordinator.tweakerRow]`.fanControl` through
 * [TweakerProfilePort], the same fake-port seam `AppBrightnessCoordinatorProfileDraftTest` uses.
 * `tweakerFanGpuTempC` / `tweakerFanCpuTempC` are deliberately not asserted here: they come from the
 * real `TweakerBridge.utilityFanGpuTempC()` / `utilityFanCpuTempC()` singletons, which have no bound
 * telemetry session under Robolectric and are therefore always null in this harness. Their retention
 * is NO LONGER the same shape as `fanControl` below, as of the fan-control temperature readout
 *: the two temperature fields
 * now stamp acceptance and expire after `FAN_TEMPERATURE_RETENTION_HOLD_MS` via
 * `boundedRetainedFanTempC`, while `retainedFanControl` (this file's `fanControl` subject) stays
 * unbounded by staleness on purpose -- that fix is routed to a later ticket (spec §11). The bound
 * itself is pinned as a pure function plus a source-characterization wiring check in
 * `TweakerFanTemperatureReadoutTest`, since this harness cannot drive it behaviourally either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class AppBrightnessCoordinatorFanReadoutRetentionTest {

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
    }

    private val readingA = TweakerFanControlReadout(
        profileId = "balanced",
        strategy = TweakerFanControlStrategy.AUTO_CURVE,
        targetTempC = 65,
        calibrationPowerReferenceMw = 11_250,
        dutyPercent = 25,
        rpm = 0,
    )

    private val readingB = TweakerFanControlReadout(
        profileId = "performance",
        strategy = TweakerFanControlStrategy.AUTO_CURVE,
        targetTempC = 70,
        calibrationPowerReferenceMw = 12_000,
        dutyPercent = 60,
        rpm = 0,
    )

    /** Differential: without retention this reverts to null the moment the sample goes stale. */
    @Test
    fun heldReadingSurvivesACurrentSampleGoingMomentarilyStale() = runTest {
        val port = FakeFanReadoutPort(readyRow(selected = "balanced", fanControl = readingA))
        val coordinator = fanReadoutCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()
        assertEquals(readingA, coordinator.tweakerRow.fanControl)

        // Telemetry lags: TweakerBridge.freshUtilityFanControl would now return null for one poll,
        // same profile still selected. The port models exactly that authoritative null.
        port.value = readyRow(selected = "balanced", fanControl = null)
        coordinator.refreshTweakerUtilityState()
        advanceUntilIdle()

        assertEquals(
            "a stale sample under the SAME profile must not blank the held reading",
            readingA,
            coordinator.tweakerRow.fanControl,
        )
    }

    @Test
    fun heldReadingClearsTheMomentTheProfileChanges() = runTest {
        val port = FakeFanReadoutPort(readyRow(selected = "balanced", fanControl = readingA))
        val coordinator = fanReadoutCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()
        assertEquals(readingA, coordinator.tweakerRow.fanControl)

        // A genuine profile switch, with the new profile's first sample not in yet.
        port.value = readyRow(selected = "performance", fanControl = null)
        coordinator.refreshTweakerUtilityState()
        advanceUntilIdle()

        assertNull(
            "readingA belonged to balanced and must never be shown under performance",
            coordinator.tweakerRow.fanControl,
        )

        // The new profile's own sample now lands and is adopted normally.
        port.value = readyRow(selected = "performance", fanControl = readingB)
        coordinator.refreshTweakerUtilityState()
        advanceUntilIdle()
        assertEquals(readingB, coordinator.tweakerRow.fanControl)
    }

    @Test
    fun heldReadingClearsWhenTheProfileSwitchesToStock() = runTest {
        val port = FakeFanReadoutPort(readyRow(selected = "balanced", fanControl = readingA))
        val coordinator = fanReadoutCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()
        assertEquals(readingA, coordinator.tweakerRow.fanControl)

        // Stock has no Mimir-owned fan session; the port models this the same way TweakerBridge
        // does -- selectedProfileId flips to Stock and fanControl is unconditionally null.
        port.value = readyRow(selected = TWEAKER_STOCK_PROFILE_ID, fanControl = null)
        coordinator.refreshTweakerUtilityState()
        advanceUntilIdle()

        assertNull(
            "Stock has no fan session; a Balanced-session reading must not survive onto it",
            coordinator.tweakerRow.fanControl,
        )
    }

    /** Differential: without this, Close leaves the held value in place for the next open(). */
    @Test
    fun heldReadingClearsOnCloseAndDoesNotSurviveIntoTheNextSession() = runTest {
        val port = FakeFanReadoutPort(readyRow(selected = "balanced", fanControl = readingA))
        val coordinator = fanReadoutCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()
        assertEquals(readingA, coordinator.tweakerRow.fanControl)

        coordinator.close()

        // Reopen on the exact same profile, but the service has not answered with a fresh sample
        // yet -- if the held value survived Close it would render here as though it were current.
        port.value = readyRow(selected = "balanced", fanControl = null)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertNull(
            "a reading captured before Close must never render as current in a reopened session",
            coordinator.tweakerRow.fanControl,
        )
    }
}

private fun readyRow(selected: String, fanControl: TweakerFanControlReadout?) =
    TweakerUtilityRowState(
        availability = TweakerUtilityAvailability.READY,
        selectedProfileId = selected,
        selectableProfileIds = listOf(TWEAKER_STOCK_PROFILE_ID, "balanced", "performance"),
        fanControl = fanControl,
    )

private class FakeFanReadoutPort(
    var value: TweakerUtilityRowState,
) : TweakerProfilePort {
    override fun utilityRowState(): TweakerUtilityRowState = value

    override suspend fun refreshCapabilities() = Unit

    override suspend fun selectSystemProfileSettled(
        profileId: String,
        admittedProfileIds: Set<String>,
        control: TweakerProfileSelectionControl,
    ): TweakerSystemProfileSelectionResult = TweakerSystemProfileSelectionResult.Active(profileId, changed = true)
}

private fun TestScope.fanReadoutCoordinator(port: FakeFanReadoutPort) = AppBrightnessCoordinator(
    context = RuntimeEnvironment.getApplication(),
    controller = fanReadoutTestController(),
    authority = DefaultFocusAuthority(),
    scope = this,
    privilegedIoDispatcher = StandardTestDispatcher(testScheduler),
    tweakerPort = port,
)

private fun fanReadoutTestController(): BrightnessController = BrightnessController(
    settings = FakeBrightnessSettings(
        values = mutableMapOf(BrightnessSettingsKeys.PRIMARY_MODE to "0"),
        writeAccess = true,
    ),
    preferences = object : WindowBrightnessPreferenceStore {
        override fun read(target: BrightnessTarget): Float? = null
        override fun write(target: BrightnessTarget, fraction: Float) = Unit
        override fun clear(target: BrightnessTarget) = Unit
    },
    identity = BrightnessDeviceIdentity(
        manufacturer = "Moorechip",
        brand = "Retroid",
        model = "Retroid Pocket 5",
        vendorSettingsPackage = VendorUtilityPackages.RETROID_SETTINGS,
    ),
    secondaryWindowAvailable = { false },
    deferInitialPrivilegedProbe = true,
)
