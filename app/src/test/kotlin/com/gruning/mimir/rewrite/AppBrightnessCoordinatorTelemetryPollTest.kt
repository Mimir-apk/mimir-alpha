package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_STOCK_PROFILE_ID
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlStrategy
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 *  -- the
 * PROFILE row's fan readout held its LAST reading forever instead of tracking the daemon, because
 * nothing distinguished "no fresher sample yet" from "the periodic poll never actually landed a
 * fresh telemetry read." [AppBrightnessCoordinatorFanReadoutRetentionTest] already pins the
 * retention half of that contract (a stale/momentarily-null sample must not blank the reading).
 * This file pins the other half live polling actually needs: repeated calls to
 * [AppBrightnessCoordinator.refreshTweakerUtilityState] -- the same call
 * `AppBrightnessCoordinatorHost`'s `while (coordinator.isOpen) { delay(...); refreshTweakerUtilityState() }`
 * loop makes once per [UTILITY_TWEAKER_TELEMETRY_REFRESH_MS] while the panel is open -- (a) pick up
 * a genuinely fresher reading under the same profile, and (b) never stack a second in-flight poll
 * behind a slow one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class AppBrightnessCoordinatorTelemetryPollTest {

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
    }

    /** Pins the ~1s cadence the request specified for: the daemon's own fan monitor ticks at 1s. */
    @Test
    fun pollCadenceMatchesTheDaemonsOneSecondSampler() {
        assertEquals(1_000L, UTILITY_TWEAKER_TELEMETRY_REFRESH_MS)
    }

    /**
     * Differential: before this fix, TweakerBridge.refreshCapabilities() silently no-op'd whenever
     * the process-wide session reservation was held elsewhere (most durably by
     * PerformanceOverlayService's long-lived telemetry observation), so every periodic poll after
     * the first left `telemetry` -- and therefore this row's fanControl -- exactly where it was.
     * Retention then hid that freeze perfectly: the value never blinked, it just never changed. This
     * test drives the coordinator's own poll entry point directly against a port that hands back a
     * genuinely new reading on the second call, the same way a live daemon sample would.
     */
    @Test
    fun freshReadingUnderTheSameProfileReplacesTheHeldValueOnTheNextPoll() = runTest {
        val readingAtT0 = TweakerFanControlReadout(
            profileId = "balanced",
            strategy = TweakerFanControlStrategy.AUTO_CURVE,
            targetTempC = 65,
            calibrationPowerReferenceMw = 11_250,
            dutyPercent = 25,
            rpm = 0,
        )
        val readingAtT1 = readingAtT0.copy(dutyPercent = 40)

        val port = CountingTelemetryPort(readyRow(selected = "balanced", fanControl = readingAtT0))
        val coordinator = telemetryPollCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()
        assertEquals(readingAtT0, coordinator.tweakerRow.fanControl)
        assertEquals(1, port.refreshCapabilitiesCallCount)

        // Simulates the next 1s tick: same profile, but the daemon's fan duty moved.
        port.value = readyRow(selected = "balanced", fanControl = readingAtT1)
        coordinator.refreshTweakerUtilityState()
        advanceUntilIdle()

        assertEquals(
            "a genuinely fresh sample under the same profile must overwrite the held reading, " +
                "not just re-confirm the old one",
            readingAtT1,
            coordinator.tweakerRow.fanControl,
        )
        assertEquals(2, port.refreshCapabilitiesCallCount)
    }

    /**
     * Nova 2026-10-01 21:49:34-21:49:51: with Utility open, the panel's one-second loop ran the FULL capability projection
     * every second (a bind, five queries, a standing-rearm observation and two log lines), when the live fan readout needs a
     * telemetry sample and nothing more. The loop's beat is now the port's panel tick; opening and resuming still do the
     * full refresh.
     */
    @Test
    fun theOnceASecondBeatIsThePanelTickAndOpeningStillRefreshesInFull() = runTest {
        val port = CountingTelemetryPort(readyRow(selected = "balanced", fanControl = null))
        val coordinator = telemetryPollCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()
        assertEquals("open refreshes in full", 1, port.refreshCapabilitiesCallCount)
        assertEquals(0, port.panelTickCallCount)

        repeat(3) {
            coordinator.refreshTweakerUtilityState(periodic = true)
            advanceUntilIdle()
        }

        assertEquals("the periodic beat never repeats the full projection", 1, port.refreshCapabilitiesCallCount)
        assertEquals(3, port.panelTickCallCount)
    }

    /**
     * Differential: without the `tweakerRefreshJob?.isActive` guard in
     * [AppBrightnessCoordinator.refreshTweakerUtilityState], a slow poll response would not stop a
     * second tick of the panel's 1s loop from starting another one behind it -- exactly the shape of
     * bug already paid for tonight on the input path (a slow Binder call must not queue more
     * requests behind it).
     */
    @Test
    fun aSlowInFlightPollIsNeverStackedBehindBySubsequentTicks() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = CountingTelemetryPort(readyRow(selected = "balanced", fanControl = null), gate = gate)
        val coordinator = telemetryPollCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        // open() only schedules the poll job; run it up to its suspension point on `gate`.
        advanceUntilIdle()
        assertEquals(1, port.refreshCapabilitiesCallCount)

        // Two more simulated 1s ticks land while the first poll is still in flight.
        coordinator.refreshTweakerUtilityState()
        coordinator.refreshTweakerUtilityState()
        assertEquals(
            "a poll still in flight must not be stacked behind by later ticks",
            1,
            port.refreshCapabilitiesCallCount,
        )

        gate.complete(Unit)
        advanceUntilIdle()

        // The job has finished, so the next tick is free to run.
        coordinator.refreshTweakerUtilityState()
        advanceUntilIdle()
        assertEquals(2, port.refreshCapabilitiesCallCount)
    }

    /**
     * A recreated secondary Presentation must not let its predecessor cancel the successor's
     * foreground refresh. The final stop still has to cancel a refresh that is actually in flight;
     * the cancellation count makes that positive assertion non-vacuous.
     */
    @Test
    fun staleSecondaryPresentationStopLeavesSuccessorRefreshAliveButCurrentStopCancelsIt() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val port = CountingTelemetryPort(readyRow(selected = "balanced", fanControl = null), gate = firstGate)
        val coordinator = telemetryPollCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()
        assertEquals(1, port.refreshCapabilitiesCallCount)

        val ownerA = UUID.randomUUID()
        val ownerB = UUID.randomUUID()
        coordinator.attachSecondaryPresentation(ownerA)
        coordinator.attachSecondaryPresentation(ownerB)

        // The old Presentation tears down after B is attached. Its late stop must be ignored.
        coordinator.onSecondaryPresentationStopped(ownerA)
        advanceUntilIdle()
        assertEquals("stale owner must not cancel B's active refresh", 0, port.cancellationCount)
        assertEquals(1, port.refreshCapabilitiesCallCount)

        // B's refresh can still complete after the stale stop.
        firstGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, port.cancellationCount)

        // Start another genuinely gated refresh so the current-owner cancellation is observable.
        val secondGate = CompletableDeferred<Unit>()
        port.gate = secondGate
        coordinator.refreshTweakerUtilityState()
        advanceUntilIdle()
        assertEquals(2, port.refreshCapabilitiesCallCount)

        coordinator.onSecondaryPresentationStopped(ownerB)
        advanceUntilIdle()
        assertEquals("current owner must cancel its active refresh", 1, port.cancellationCount)
    }
}

private fun readyRow(selected: String, fanControl: TweakerFanControlReadout?) =
    TweakerUtilityRowState(
        availability = TweakerUtilityAvailability.READY,
        selectedProfileId = selected,
        selectableProfileIds = listOf(TWEAKER_STOCK_PROFILE_ID, "balanced", "performance"),
        fanControl = fanControl,
    )

/** Counts calls to [refreshCapabilities], with an optional gate to hold one call open. */
private class CountingTelemetryPort(
    var value: TweakerUtilityRowState,
    var gate: CompletableDeferred<Unit>? = null,
) : TweakerProfilePort {
    var refreshCapabilitiesCallCount = 0
        private set
    var panelTickCallCount = 0
        private set
    var cancellationCount = 0
        private set

    override fun utilityRowState(): TweakerUtilityRowState = value

    override suspend fun refreshForPanelTick() {
        panelTickCallCount += 1
    }

    override suspend fun refreshCapabilities() {
        refreshCapabilitiesCallCount += 1
        try {
            gate?.await()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            cancellationCount += 1
            throw cancellation
        }
    }

    override suspend fun selectSystemProfileSettled(
        profileId: String,
        admittedProfileIds: Set<String>,
        control: TweakerProfileSelectionControl,
    ): TweakerSystemProfileSelectionResult = TweakerSystemProfileSelectionResult.Active(profileId, changed = true)
}

private fun TestScope.telemetryPollCoordinator(port: CountingTelemetryPort) = AppBrightnessCoordinator(
    context = RuntimeEnvironment.getApplication(),
    controller = telemetryPollTestController(),
    authority = DefaultFocusAuthority(),
    scope = this,
    privilegedIoDispatcher = StandardTestDispatcher(testScheduler),
    tweakerPort = port,
)

private fun telemetryPollTestController(): BrightnessController = BrightnessController(
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
