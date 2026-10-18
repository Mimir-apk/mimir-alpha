package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The PROFILE row drafts, exactly like the two rows beneath it.
 *
 * Before this contract the row committed on every step, so Stock → Quiet → Balanced → Performance ran
 * THREE complete Guardian settlements — each paying the full verification window — to reach one
 * destination. These tests pin the property that made that possible as gone: stepping touches no
 * transaction at all, and exactly one settlement happens, at APPLY.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class AppBrightnessCoordinatorProfileDraftTest {
    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
    }

    /** Spec test 1. The differential: revert the draft so stepping commits again and this fails. */
    @Test
    fun steppingThreeProfilesDraftsAndRunsNoTransaction() = runTest {
        val port = FakeTweakerProfilePort(readyRow())
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertTrue(coordinator.stepTweakerProfile(1))
        assertTrue(coordinator.stepTweakerProfile(1))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), port.requested)
        assertNull(coordinator.tweakerProfileChange)
        assertTrue(coordinator.tweakerProfileDraftPending)
        assertEquals("performance", coordinator.tweakerProfilePreviewId)
        // The device is still running what it was running. Only the preview travelled.
        assertEquals("stock", coordinator.tweakerRow.effectiveProfileId)
    }

    /** Spec test 2. */
    @Test
    fun confirmAppliesTheDraftedProfileExactlyOnceHoweverFarItTravelled() = runTest {
        val port = FakeTweakerProfilePort(
            readyRow(),
            TweakerSystemProfileSelectionResult.Active("performance", changed = true),
        )
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        repeat(3) { assertTrue(coordinator.stepTweakerProfile(1)) }
        // Scheduling a settlement is not itself an authoritative hardware change, so it must not fire
        // success navigation feedback. The settlement overlay is the feedback surface.
        assertFalse(coordinator.applyTweakerProfileDraft())

        // Set synchronously, before the settlement job is ever dispatched: the panel commits to the
        // destination the moment the press is accepted, and the draft is gone in the same commit.
        assertNotNull(coordinator.tweakerProfileChange)
        assertEquals("performance", coordinator.tweakerProfileChange?.requestedProfileId)
        assertEquals("stock", coordinator.tweakerProfileChange?.priorProfileId)
        assertFalse(coordinator.tweakerProfileDraftPending)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("performance"), port.requested)
        assertNull(coordinator.tweakerProfileChange)
        assertEquals("PERFORMANCE · ACTIVE", coordinator.tweakerMessage?.text)
    }

    /** Spec test 3. */
    @Test
    fun cancelAndBackRevertToTheLiveProfileWithNoTransaction() = runTest {
        val port = FakeTweakerProfilePort(readyRow())
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertTrue(coordinator.cancelTweakerProfileDraft())
        assertFalse(coordinator.tweakerProfileDraftPending)
        assertNull(coordinator.tweakerProfilePreviewId)

        // Back owns the pending decision first, and only closes the panel once nothing is drafted.
        assertTrue(coordinator.stepTweakerProfile(1))
        coordinator.handleBack()
        assertFalse(coordinator.tweakerProfileDraftPending)
        assertTrue(coordinator.isOpen)
        coordinator.handleBack()
        assertFalse(coordinator.isOpen)

        advanceUntilIdle()
        assertEquals(emptyList<String>(), port.requested)
    }

    @Test
    fun controllerBackAndCloseFooterReturnFromRoutedTweakerButDirectTweakerCloses() = runTest {
        val coordinator = profileCoordinator(FakeTweakerProfilePort(readyRow()))
        val closeKey = brightnessFocusKey(BrightnessControlKey.CLOSE)
        coordinator.authority.register(closeKey, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))

        coordinator.open(UtilitySurface.UTILITY)
        coordinator.authority.requestFocus(closeKey)
        coordinator.open(UtilitySurface.TWEAKER)
        coordinator.authority.requestFocus(closeKey)

        // The footer's CONFIRM is routed through the same callback as controller CANCEL/SYSTEM_BACK.
        assertTrue(coordinator.dispatch(ControllerAction.CONFIRM))
        assertTrue(coordinator.isOpen)
        assertEquals(UtilitySurface.UTILITY, coordinator.surface)

        coordinator.close()
        coordinator.open(UtilitySurface.TWEAKER)
        coordinator.authority.requestFocus(closeKey)
        assertTrue(coordinator.dispatch(ControllerAction.SYSTEM_BACK))
        assertFalse(coordinator.isOpen)
    }

    /** Spec test 4, first half: travelling the ring back onto the live profile leaves nothing drafted. */
    @Test
    fun steppingBackOntoTheLiveProfileClearsTheDraftAndConfirmRunsNothing() = runTest {
        val port = FakeTweakerProfilePort(readyRow())
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        repeat(4) { assertTrue(coordinator.stepTweakerProfile(1)) }

        assertFalse(coordinator.tweakerProfileDraftPending)
        assertNull(coordinator.tweakerProfilePreviewId)
        assertFalse(coordinator.applyTweakerProfileDraft())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), port.requested)
    }

    /** Spec test 4, second half: the world catching up to a held draft is a no-op, not a transaction. */
    @Test
    fun applyingTheProfileAlreadyLiveConsumesThePressWithoutATransaction() = runTest {
        val port = FakeTweakerProfilePort(readyRow())
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertEquals("quiet", coordinator.tweakerProfilePreviewId)

        // Something else — a per-game selection settling on return, say — makes the drafted profile the
        // running one before the player confirms.
        port.value = readyRow(selected = "quiet")
        coordinator.refreshTweakerUtilityState()

        assertTrue(coordinator.applyTweakerProfileDraft())
        runCurrent()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), port.requested)
        assertNull(coordinator.tweakerProfileChange)
        assertFalse(coordinator.tweakerProfileDraftPending)
        assertEquals("EFFICIENCY · ALREADY ACTIVE", coordinator.tweakerMessage?.text)
    }

    /** Spec test 5. */
    @Test
    fun aDraftedProfileThatLosesQualificationRetiresAndSaysSo() = runTest {
        val port = FakeTweakerProfilePort(readyRow())
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertEquals("quiet", coordinator.tweakerProfilePreviewId)

        port.value = readyRow(selectable = listOf("stock", "balanced", "performance"))
        coordinator.refreshTweakerUtilityState()

        assertFalse(coordinator.tweakerProfileDraftPending)
        assertNull(coordinator.tweakerProfilePreviewId)
        assertEquals("EFFICIENCY · NO LONGER AVAILABLE", coordinator.tweakerMessage?.text)

        advanceUntilIdle()
        assertEquals(emptyList<String>(), port.requested)
    }

    /** Spec test 5, capability loss rather than decertification. */
    @Test
    fun aDraftedProfileRetiresWhenTheRowStopsBeingReady() = runTest {
        val port = FakeTweakerProfilePort(readyRow())
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))

        port.value = TweakerUtilityRowState(availability = TweakerUtilityAvailability.UNAVAILABLE)
        coordinator.refreshTweakerUtilityState()

        assertFalse(coordinator.tweakerProfileDraftPending)
        assertEquals("EFFICIENCY · NO LONGER AVAILABLE", coordinator.tweakerMessage?.text)
    }

    @Test
    fun aHeldRestoreRetiresProfilePreviewWithoutDispatchOrResurrection() = runTest {
        val port = FakeTweakerProfilePort(readyRow())
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertEquals("quiet", coordinator.tweakerProfilePreviewId)

        // The daemon still publishes READY and the same profile list, but admission is held until
        // its unfinished restore settles. This is the row the coordinator actually receives.
        port.value = readyRow().copy(heldByUnfinishedRestore = true)
        coordinator.refreshTweakerUtilityState()
        runCurrent()

        assertTrue(coordinator.tweakerRow.heldByUnfinishedRestore)
        assertFalse(coordinator.tweakerProfileDraftPending)
        assertNull(coordinator.tweakerProfilePreviewId)
        assertEquals("EFFICIENCY · NO LONGER AVAILABLE", coordinator.tweakerMessage?.text)
        assertFalse(coordinator.applyTweakerProfileDraft())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), port.requested)

        // Clearing the hold is a fresh live row; a retired preview must not come back from it.
        port.value = readyRow()
        coordinator.refreshTweakerUtilityState()
        runCurrent()
        assertFalse(coordinator.tweakerProfileDraftPending)
        assertNull(coordinator.tweakerProfilePreviewId)
        assertEquals(emptyList<String>(), port.requested)
    }

    @Test
    fun heldRestoreMakesPureProfileApplyDecisionUnavailable() {
        val row = readyRow().copy(heldByUnfinishedRestore = true)

        assertEquals(
            TweakerProfileApplyDecision.Unavailable("quiet"),
            tweakerProfileApplyDecision(row, TweakerProfileDraft("quiet")),
        )
    }

    /** Spec test 6. */
    @Test
    fun aFailedApplyReturnsTheRowToTheLiveProfileNeverTheDraft() = runTest {
        val port = FakeTweakerProfilePort(
            readyRow(),
            TweakerSystemProfileSelectionResult.Rejected("PROFILE_NOT_CERTIFIED"),
        )
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertFalse(coordinator.applyTweakerProfileDraft())
        advanceUntilIdle()

        assertEquals(listOf("quiet"), port.requested)
        assertFalse(coordinator.tweakerProfileDraftPending)
        assertNull(coordinator.tweakerProfilePreviewId)
        // The row shows what is running, which is still Stock — never the profile that was refused.
        assertEquals("stock", coordinator.tweakerRow.effectiveProfileId)
        // retires the jargon, not the distinction: this code no longer says
        // "NOT QUALIFIED", but it must also not fall through to the generic rejection line -- that
        // collapse is what AppBrightnessCoordinator's own comment records as having cost a full
        // device investigation. It names the outcome in the player's words instead.
        assertEquals("PROFILE NOT AVAILABLE ON THIS DEVICE", coordinator.tweakerMessage?.text)
        assertNotEquals("PROFILE CHANGE REJECTED", coordinator.tweakerMessage?.text)
    }

    @Test
    fun theRefusalCodeForAPlanNotAdmittedReadsTheSameWhetherNewOrFromAnOlderDaemon() = runTest {
        listOf("PLAN_NOT_ADMITTED", "CALIBRATION_REQUIRED").forEach { code ->
            val port = FakeTweakerProfilePort(readyRow(), TweakerSystemProfileSelectionResult.Rejected(code))
            val coordinator = profileCoordinator(port)
            coordinator.open(UtilitySurface.TWEAKER)
            advanceUntilIdle()

            assertTrue(coordinator.stepTweakerProfile(1))
            assertFalse(coordinator.applyTweakerProfileDraft())
            advanceUntilIdle()

            assertEquals(code, "PROFILE NOT AVAILABLE ON THIS DEVICE", coordinator.tweakerMessage?.text)
        }
    }
    /**
     * **The ceiling refusal has no bespoke line any more, and must not grow one back.**
     *
     * For two days it did. A Thor test device, 2026-09-19, produced four refusals in three minutes, all
     * `CPU_TARGET_ABOVE_LIVE_CEILING`, all shown as the generic line: the daemon was holding the shared
     * CPU ceiling at the previous profile's plan, so no profile above it could be admitted, Stock
     * included. The first fix was copy — tell the player to reboot. The 2026-09-21 admission change retired the refusal itself. A target off the operating-point table is snapped down onto
     * it, so a ceiling Mimir wrote can no longer forbid its own removal and this code cannot arrive.
     *
     * Kept as a test rather than deleted with the copy, because the failure it guards is a plausible
     * one: someone reads the run-13 story, sees no line for the code, and helpfully adds it back —
     * shipping copy that promises a reboot for a state the product no longer has. The generic line is
     * the correct answer for a code this app does not recognise.
     */
    @Test
    fun theRetiredCeilingRefusalHasNoBespokeLineToShow() = runTest {
        val port = FakeTweakerProfilePort(
            readyRow(),
            TweakerSystemProfileSelectionResult.Rejected("CPU_TARGET_ABOVE_LIVE_CEILING"),
        )
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertFalse(coordinator.applyTweakerProfileDraft())
        advanceUntilIdle()

        assertEquals("stock", coordinator.tweakerRow.effectiveProfileId)
        assertEquals("PROFILE CHANGE REJECTED", coordinator.tweakerMessage?.text)
    }

    /**
     * **A daemon that did not answer in time is not a verdict.** The Tweaker's own SERVICE_BUSY means its transaction
     * worker was still running, so the profile change it was asked for may yet land. "PROFILE CHANGE REJECTED" would
     * tell the player a thing the daemon never said; the same Busy line the bridge shows when it holds the session
     * is the honest one. The pure mapping and the coordinator's own message are both pinned.
     */
    @Test
    fun aDaemonThatDidNotAnswerInTimeReadsAsBusyNotRejected() = runTest {
        assertEquals(
            "TWEAKER IS BUSY · TRY AGAIN",
            TweakerSystemProfileSelectionResult.Rejected("SERVICE_BUSY").systemProfileToastCaps(),
        )
        assertEquals(
            TweakerSystemProfileSelectionResult.Busy.systemProfileToastCaps(),
            TweakerSystemProfileSelectionResult.Rejected("SERVICE_BUSY").systemProfileToastCaps(),
        )

        val port = FakeTweakerProfilePort(readyRow(), TweakerSystemProfileSelectionResult.Rejected("SERVICE_BUSY"))
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertFalse(coordinator.applyTweakerProfileDraft())
        advanceUntilIdle()

        assertEquals("TWEAKER IS BUSY · TRY AGAIN", coordinator.tweakerMessage?.text)
        assertNotEquals("PROFILE CHANGE REJECTED", coordinator.tweakerMessage?.text)
    }

    /** The one refusal that stays generic: the daemon refused before writing anything, so "REJECTED" is accurate. */
    @Test
    fun anUnconfirmedCpuRouteStillReadsAsRejected() = runTest {
        assertEquals(
            "PROFILE CHANGE REJECTED",
            TweakerSystemProfileSelectionResult.Rejected("CPU_TRANSPORT_UNCONFIRMED").systemProfileToastCaps(),
        )

        val port = FakeTweakerProfilePort(
            readyRow(),
            TweakerSystemProfileSelectionResult.Rejected("CPU_TRANSPORT_UNCONFIRMED"),
        )
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertFalse(coordinator.applyTweakerProfileDraft())
        advanceUntilIdle()

        assertEquals("PROFILE CHANGE REJECTED", coordinator.tweakerMessage?.text)
    }

    /**
     * The daemon refuses a profile apply in front of a discovered ceiling with `CEILING_HELD` (one is held and could not
     * be given back) or `CEILING_UNCONFIRMED` (it could not establish whether one is). Both are refusals sent before any
     * write, so both read as "REJECTED" and neither is mistaken for Busy or for an applied change.
     */
    @Test
    fun theDaemonsTwoCeilingRefusalsBothReadAsRejected() = runTest {
        listOf("CEILING_HELD", "CEILING_UNCONFIRMED").forEach { code ->
            assertEquals(
                code,
                "PROFILE CHANGE REJECTED",
                TweakerSystemProfileSelectionResult.Rejected(code).systemProfileToastCaps(),
            )
            val port = FakeTweakerProfilePort(readyRow(), TweakerSystemProfileSelectionResult.Rejected(code))
            val coordinator = profileCoordinator(port)
            coordinator.open(UtilitySurface.TWEAKER)
            advanceUntilIdle()

            assertTrue(coordinator.stepTweakerProfile(1))
            assertFalse(code, coordinator.applyTweakerProfileDraft())
            advanceUntilIdle()

            assertEquals(code, "PROFILE CHANGE REJECTED", coordinator.tweakerMessage?.text)
        }
    }

    /** Spec test 7. */
    @Test
    fun draftSteppingIsFreeDuringASettlementButApplyIsNot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeTweakerProfilePort(
            readyRow(),
            TweakerSystemProfileSelectionResult.Active("quiet", changed = true),
            gate = gate,
        )
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER)
        advanceUntilIdle()

        assertTrue(coordinator.stepTweakerProfile(1))
        assertFalse(coordinator.applyTweakerProfileDraft())
        runCurrent()
        assertNotNull(coordinator.tweakerProfileChange)

        // A draft starts no transaction, so the guard that exists to prevent overlapping transactions
        // has nothing to protect here.
        assertTrue(coordinator.stepTweakerProfile(1))
        assertTrue(coordinator.tweakerProfileDraftPending)
        assertEquals("quiet", coordinator.tweakerProfilePreviewId)

        // APPLY still respects it, and says why.
        assertFalse(coordinator.applyTweakerProfileDraft())
        assertEquals("PROFILE CHANGE IN PROGRESS", coordinator.tweakerMessage?.text)
        assertEquals(1, port.requested.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("quiet"), port.requested)
        assertNull(coordinator.tweakerProfileChange)
    }

    @Test
    fun deferredProfileSettlementSurvivesHostRebindAndWritesOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeTweakerProfilePort(
            readyRow(),
            TweakerSystemProfileSelectionResult.Active("quiet", changed = true),
            gate = gate,
        )
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER, AppBrightnessPresentationTarget.SECONDARY)
        advanceUntilIdle()
        assertTrue(coordinator.stepTweakerProfile(1))
        assertFalse(coordinator.applyTweakerProfileDraft())
        runCurrent()
        assertNotNull(coordinator.tweakerProfileChange)

        // Activity recreation changes the host authority, but the same process-retained operation owns
        // the only write and remains the source of terminal feedback.
        coordinator.rebindHost(
            RuntimeEnvironment.getApplication(),
            profileTestController(),
            DefaultFocusAuthority(),
        )
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("quiet"), port.requested)
        assertNull(coordinator.tweakerProfileChange)
        assertEquals("EFFICIENCY · ACTIVE", coordinator.tweakerMessage?.text)
    }

    @Test
    fun secondaryCancellationUncertainStaysOpenAndVisible() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeTweakerProfilePort(
            readyRow(),
            TweakerSystemProfileSelectionResult.Uncertain("readback unavailable"),
            gate = gate,
        )
        val coordinator = profileCoordinator(port)
        coordinator.open(UtilitySurface.TWEAKER, AppBrightnessPresentationTarget.SECONDARY)
        advanceUntilIdle()
        assertTrue(coordinator.stepTweakerProfile(1))
        assertFalse(coordinator.applyTweakerProfileDraft())
        runCurrent()
        assertTrue(coordinator.dismissFromSecondaryPresentation())
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(coordinator.isOpen)
        assertEquals("PROFILE STATUS UNCONFIRMED", coordinator.tweakerMessage?.text)
    }

    @Test
    fun stalePrimaryHostCannotCloseAReboundCoordinator() = runTest {
        val coordinator = profileCoordinator(FakeTweakerProfilePort(readyRow()))
        val old = CompanionProcessRuntime.BrightnessHostRegistration(
            kind = CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
            controller = profileTestController(),
            authority = DefaultFocusAuthority(),
            context = RuntimeEnvironment.getApplication(),
            beforeExternalActivity = {},
            hostsInAppSurfaces = false,
            token = 41L,
        )
        val successor = old.copy(
            controller = profileTestController(),
            authority = DefaultFocusAuthority(),
            token = 42L,
        )
        coordinator.rebindHost(old)
        coordinator.open(UtilitySurface.TWEAKER)
        coordinator.rebindHost(successor)

        coordinator.closeIfCurrent(old.token)
        assertTrue(coordinator.isOpen)
        assertTrue(coordinator.isPrimaryHostActive(CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY))

        coordinator.closeIfCurrent(successor.token)
        assertFalse(coordinator.isOpen)
    }

    @Test
    fun processRegistryRestoresRecreatedActivityAfterExactOverlayRelease() {
        val context = RuntimeEnvironment.getApplication()
        val firstActivityController = profileTestController()
        val firstActivityAuthority = DefaultFocusAuthority()
        val coordinator = CompanionProcessRuntime.brightnessCoordinator(
            context = context,
            controller = firstActivityController,
            authority = firstActivityAuthority,
            hostKind = CompanionProcessRuntime.BrightnessHostKind.ACTIVITY,
        )

        val firstOverlayController = profileTestController()
        val firstOverlayAuthority = DefaultFocusAuthority()
        CompanionProcessRuntime.brightnessCoordinator(
            context = context,
            controller = firstOverlayController,
            authority = firstOverlayAuthority,
            hostsInAppSurfaces = false,
            hostKind = CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
        )
        val staleOverlayToken = requireNotNull(
            CompanionProcessRuntime.currentBrightnessHost(
                CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
            ),
        )

        val recreatedActivityController = profileTestController()
        val recreatedActivityAuthority = DefaultFocusAuthority()
        CompanionProcessRuntime.brightnessCoordinator(
            context = context,
            controller = recreatedActivityController,
            authority = recreatedActivityAuthority,
            hostKind = CompanionProcessRuntime.BrightnessHostKind.ACTIVITY,
        )
        CompanionProcessRuntime.unregisterActivityBrightnessHost(
            firstActivityController,
            firstActivityAuthority,
        )
        assertTrue(coordinator.isPrimaryHostActive(CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY))

        CompanionProcessRuntime.brightnessCoordinator(
            context = context,
            controller = profileTestController(),
            authority = DefaultFocusAuthority(),
            hostsInAppSurfaces = false,
            hostKind = CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
        )

        val currentOverlayToken = requireNotNull(
            CompanionProcessRuntime.currentBrightnessHost(
                CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
            ),
        )
        assertNotEquals(staleOverlayToken, currentOverlayToken)
        CompanionProcessRuntime.unregisterOverlayBrightnessHost(staleOverlayToken)
        assertTrue(coordinator.isPrimaryHostActive(CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY))
        CompanionProcessRuntime.unregisterOverlayBrightnessHost(currentOverlayToken)
        assertTrue(coordinator.isPrimaryHostActive(CompanionProcessRuntime.BrightnessHostKind.ACTIVITY))

        CompanionProcessRuntime.unregisterActivityBrightnessHost(
            recreatedActivityController,
            recreatedActivityAuthority,
        )
    }

}

private fun readyRow(
    selected: String = "stock",
    selectable: List<String> = listOf("stock", "quiet", "balanced", "performance"),
) = TweakerUtilityRowState(
    availability = TweakerUtilityAvailability.READY,
    selectedProfileId = selected,
    selectableProfileIds = selectable,
)

/**
 * Counts settlements. An empty [requested] list is the whole claim of "this press ran no transaction",
 * and [value] is the row the panel renders, so a decertification can be staged without a device.
 */
private class FakeTweakerProfilePort(
    var value: TweakerUtilityRowState,
    private val result: TweakerSystemProfileSelectionResult =
        TweakerSystemProfileSelectionResult.Active("quiet", changed = true),
    private val gate: CompletableDeferred<Unit>? = null,
) : TweakerProfilePort {
    val requested = mutableListOf<String>()

    override fun utilityRowState(): TweakerUtilityRowState = value

    override suspend fun refreshCapabilities() = Unit

    override suspend fun selectSystemProfileSettled(
        profileId: String,
        admittedProfileIds: Set<String>,
        control: TweakerProfileSelectionControl,
    ): TweakerSystemProfileSelectionResult {
        requested += profileId
        gate?.await()
        return result
    }
}

private fun TestScope.profileCoordinator(
    port: FakeTweakerProfilePort,
) = AppBrightnessCoordinator(
    context = RuntimeEnvironment.getApplication(),
    controller = profileTestController(),
    authority = DefaultFocusAuthority(),
    scope = this,
    privilegedIoDispatcher = StandardTestDispatcher(testScheduler),
    tweakerPort = port,
)

private fun profileTestController(): BrightnessController = BrightnessController(
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
