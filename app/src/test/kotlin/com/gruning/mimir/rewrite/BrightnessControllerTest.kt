package com.gruning.mimir.rewrite

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessControllerTest {
    @Test
    fun missingWriteAccessStillAppliesAndPersistsMainWindowFallback() {
        val settings = FakeBrightnessSettings(
            values = mutableMapOf(
                BrightnessSettingsKeys.PRIMARY to "128",
                BrightnessSettingsKeys.PRIMARY_MODE to "0",
            ),
            writeAccess = false,
        )
        val preferences = FakeBrightnessPreferences()
        val sink = FakeWindowBrightnessSink()
        val controller = controller(settings, preferences, sink = sink)

        val outcome = controller.setFraction(BrightnessTarget.PRIMARY, 0.7f)

        assertTrue(outcome is BrightnessUpdateOutcome.WriteAccessRequired)
        assertEquals(0.7f, preferences.values[BrightnessTarget.PRIMARY]!!, 0.0001f)
        assertEquals(0.7f, sink.primary!!, 0.0001f)
        assertTrue(settings.writes.isEmpty())
        assertEquals(
            "android.settings.action.MANAGE_WRITE_SETTINGS",
            (outcome as BrightnessUpdateOutcome.WriteAccessRequired).request.action,
        )
    }

    @Test
    fun mainSystemWritesUseAndroidLevelsOnlyAfterApproval() {
        val settings = manualBrightnessSettings(writeAccess = true)
        val controller = controller(settings)

        val outcome = controller.setFraction(BrightnessTarget.PRIMARY, 0.7f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(BrightnessSettingsKeys.PRIMARY to "179", settings.writes.single())
        assertTrue((outcome as BrightnessUpdateOutcome.Applied).systemApplied)
        assertNull(controller.state.primaryWindowFallback)
    }

    @Test
    fun repeatedPointerValuesWithinTheSameStepDoNotRewriteMainSetting() {
        val settings = manualBrightnessSettings(writeAccess = true)
        val controller = controller(settings)

        controller.setFraction(BrightnessTarget.PRIMARY, 0.71f)
        controller.setFraction(BrightnessTarget.PRIMARY, 0.72f)
        controller.setFraction(BrightnessTarget.PRIMARY, 0.69f)

        assertEquals(listOf(BrightnessSettingsKeys.PRIMARY to "179"), settings.writes)
    }

    @Test
    fun adaptiveBrightnessModeKeepsTheGuaranteedWindowOverrideWithoutChangingMode() {
        val settings = FakeBrightnessSettings(
            values = mutableMapOf(
                BrightnessSettingsKeys.PRIMARY to "128",
                BrightnessSettingsKeys.PRIMARY_MODE to "1",
            ),
            writeAccess = true,
        )
        val sink = FakeWindowBrightnessSink()
        val controller = controller(settings, sink = sink)

        val outcome = controller.setFraction(BrightnessTarget.PRIMARY, 0.7f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertTrue((outcome as BrightnessUpdateOutcome.Applied).windowApplied)
        assertFalse(outcome.systemApplied)
        assertEquals(0.7f, sink.primary!!, 0.0001f)
        assertTrue(settings.writes.isEmpty())
        assertFalse(controller.state.capabilities.mainSystemBrightnessActive)
    }

    @Test
    fun adaptiveModeWithoutWriteAccessUsesTheWindowWithoutOfferingADeadPermissionPath() {
        val settings = FakeBrightnessSettings(
            values = mutableMapOf(
                BrightnessSettingsKeys.PRIMARY to "128",
                BrightnessSettingsKeys.PRIMARY_MODE to "1",
            ),
            writeAccess = false,
        )
        val sink = FakeWindowBrightnessSink()
        val controller = controller(settings, sink = sink)

        val outcome = controller.setFraction(BrightnessTarget.PRIMARY, 0.7f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertTrue((outcome as BrightnessUpdateOutcome.Applied).windowApplied)
        assertFalse(outcome.systemApplied)
        assertFalse(controller.state.capabilities.shouldOfferMainWriteAccess)
        assertEquals(0.7f, sink.primary!!, 0.0001f)
        assertTrue(settings.attempts.isEmpty())
    }

    @Test
    fun coldLaunchWithoutAnExplicitFallbackReleasesTheWindowBrightnessOverride() {
        val settings = FakeBrightnessSettings(
            values = mutableMapOf(
                BrightnessSettingsKeys.PRIMARY to "128",
                BrightnessSettingsKeys.PRIMARY_MODE to "1",
            ),
            writeAccess = true,
        )
        val sink = FakeWindowBrightnessSink()
        val controller = controller(settings, sink = sink)

        val fallbacks = controller.applyWindowFallbacks()

        assertNull(fallbacks.primary)
        assertNull(controller.state.primaryWindowFallback)
        assertNull(sink.primary)
    }

    @Test
    fun mainSystemWriteRequiresImmediateReadback() {
        val settings = FakeBrightnessSettings(
            values = mutableMapOf(
                BrightnessSettingsKeys.PRIMARY to "128",
                BrightnessSettingsKeys.PRIMARY_MODE to "0",
            ),
            writeAccess = true,
            writeTransform = { key, value -> if (key == BrightnessSettingsKeys.PRIMARY) "128" else value },
        )
        val controller = controller(settings)

        assertTrue(controller.setFraction(BrightnessTarget.PRIMARY, 0.7f) is BrightnessUpdateOutcome.WriteFailed)
        assertEquals(0.7f, controller.state.primaryWindowFallback!!, 0.0001f)
        controller.refresh()
        assertEquals(0.7f, controller.state.primaryWindowFallback!!, 0.0001f)

        settings.writeTransform = { _, value -> value }
        val retried = controller.setFraction(BrightnessTarget.PRIMARY, 0.7f)

        assertTrue(retried is BrightnessUpdateOutcome.Applied)
        assertTrue((retried as BrightnessUpdateOutcome.Applied).systemApplied)
        assertNull(controller.state.primaryWindowFallback)
    }

    @Test
    fun thorVendorSecondaryIsLiveReadOnlyAndRetiresLegacyWindowOverride() {
        val settings = thorSettings(writeAccess = true)
        val preferences = FakeBrightnessPreferences(
            mutableMapOf(BrightnessTarget.SECONDARY to 0.8f),
        )
        val sink = FakeWindowBrightnessSink()
        val controller = controller(settings, preferences, sink, identity = aynIdentity(), secondaryWindow = true)

        assertEquals(0.74f, controller.state.secondaryFraction!!, 0.0001f)
        assertFalse(controller.state.capabilities.canAdjustSecondary)
        assertNull(preferences.values[BrightnessTarget.SECONDARY])
        assertNull(controller.state.secondaryWindowFallback)

        controller.applyWindowFallbacks()
        val outcome = controller.setFraction(BrightnessTarget.SECONDARY, 0.9f)

        assertTrue(outcome is BrightnessUpdateOutcome.Unsupported)
        assertNull(sink.secondary)
        assertEquals("74", settings.values[BrightnessSettingsKeys.AYN_SECONDARY])
        assertTrue(settings.attempts.isEmpty())
    }

    @Test
    fun permissionGrantPromotesOnlyMainWhileThorVendorSecondaryStaysReadOnly() {
        val settings = thorSettings(writeAccess = false)
        val preferences = FakeBrightnessPreferences(
            mutableMapOf(BrightnessTarget.SECONDARY to 0.8f),
        )
        val sink = FakeWindowBrightnessSink()
        val controller = controller(
            settings,
            preferences,
            sink,
            identity = aynIdentity(),
            secondaryWindow = true,
        )

        controller.setFraction(BrightnessTarget.PRIMARY, 0.7f)
        assertTrue(
            controller.setFraction(BrightnessTarget.SECONDARY, 0.8f) is BrightnessUpdateOutcome.Unsupported,
        )
        settings.writeAccess = true
        controller.refresh()
        controller.applyWindowFallbacks()

        assertEquals(0.7f, controller.state.primaryWindowFallback!!, 0.0001f)
        assertNull(controller.state.secondaryWindowFallback)
        assertEquals(0.74f, controller.state.secondaryFraction!!, 0.0001f)
        assertEquals(0.7f, sink.primary!!, 0.0001f)
        assertNull(sink.secondary)

        val primary = controller.setFraction(BrightnessTarget.PRIMARY, 0.7f)
        val secondary = controller.setFraction(BrightnessTarget.SECONDARY, 0.8f)

        assertTrue((primary as BrightnessUpdateOutcome.Applied).systemApplied)
        assertTrue(secondary is BrightnessUpdateOutcome.Unsupported)
        assertNull(preferences.values[BrightnessTarget.PRIMARY])
        assertNull(preferences.values[BrightnessTarget.SECONDARY])
        assertNull(controller.state.primaryWindowFallback)
        assertNull(controller.state.secondaryWindowFallback)
        assertNull(sink.primary)
        assertNull(sink.secondary)
        assertEquals(
            listOf(BrightnessSettingsKeys.PRIMARY to "179"),
            settings.writes,
        )
    }

    @Test
    fun genericSecondaryPresentationStaysWindowOnly() {
        val settings = FakeBrightnessSettings(writeAccess = false)
        val sink = FakeWindowBrightnessSink()
        val controller = controller(settings, sink = sink, secondaryWindow = true)

        assertEquals(SecondaryBrightnessSupport.WINDOW_ONLY, controller.state.capabilities.secondarySupport)
        val outcome = controller.setFraction(BrightnessTarget.SECONDARY, 0.8f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(0.8f, sink.secondary!!, 0.0001f)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun absentSecondaryCapabilityStaysHiddenAndRejectsUpdates() {
        val controller = controller(FakeBrightnessSettings())

        assertEquals(SecondaryBrightnessSupport.UNAVAILABLE, controller.state.capabilities.secondarySupport)
        assertNull(controller.state.secondaryFraction)
        assertTrue(controller.setFraction(BrightnessTarget.SECONDARY, 0.8f) is BrightnessUpdateOutcome.Unsupported)
    }

    @Test
    fun retroidLightContractDoesNotInventASecondaryDisplay() {
        val settings = vendorLightSettings(writeAccess = true)
        val controller = controller(settings, identity = retroidIdentity())

        assertEquals(SecondaryBrightnessSupport.UNAVAILABLE, controller.state.capabilities.secondarySupport)
        assertTrue(controller.state.capabilities.joystickLightSupported)
        assertTrue(controller.state.capabilities.joystickLightColorSupported)
    }

    @Test
    fun vendorLightRowsAreLiveReadOnlyWithOrWithoutWriteSettings() {
        listOf(false, true).forEach { writeAccess ->
            val settings = thorSettings(writeAccess = writeAccess)
            val controller = controller(settings, identity = aynIdentity(), secondaryWindow = true)

            assertEquals(0.05f, controller.state.joystickLightFraction!!, 0.0001f)
            assertEquals(0xffffffff.toInt(), controller.state.joystickColorArgb)
            assertTrue(
                controller.setFraction(BrightnessTarget.JOYSTICK_LIGHT, 0.35f) is
                    BrightnessUpdateOutcome.Unsupported,
            )
            assertTrue(
                controller.setFraction(BrightnessTarget.JOYSTICK_COLOR, 1f / 12f) is
                    BrightnessUpdateOutcome.Unsupported,
            )
            assertEquals("0.05", settings.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
            assertEquals("1,1", settings.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED])
            assertEquals(
                "#FFFFFFFF,#FFFFFFFF",
                settings.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR],
            )
            assertTrue("read-only means no provider mutation attempt", settings.attempts.isEmpty())
        }
    }

    @Test
    fun vendorAccessRequestRunsOnlyAfterTheDedicatedExplicitRequest() {
        val settings = vendorLightSettings(writeAccess = true)
        val privileged = FakePrivilegedUtilityGateway(
            currentPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
            values = settings.values.toMutableMap(),
        )
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        controller.refresh()
        controller.reconcileThemeAwareJoystickLight()
        assertEquals(0, privileged.requestCount)

        assertTrue(
            controller.requestPrivilegedAccess(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
        )
        assertEquals(1, privileged.requestCount)
        assertEquals(1, privileged.actionCount)
        assertEquals(PrivilegedUtilityStatus.PERMISSION_REQUIRED, privileged.requestedStatus)
    }


    @Test
    fun staleCapturedExpectedRequestsOnceWithoutAdmittedActionAndRefreshesState() {
        val settings = vendorLightSettings(writeAccess = true)
        val privileged = FakePrivilegedUtilityGateway(
            currentPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.SERVICE_STOPPED),
            values = settings.values.toMutableMap(),
        )
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        val started = controller.requestPrivilegedAccess(PrivilegedUtilityStatus.PERMISSION_REQUIRED)

        assertFalse(started)
        assertEquals(1, privileged.requestCount)
        assertEquals(0, privileged.actionCount)
        assertEquals(PrivilegedUtilityStatus.PERMISSION_REQUIRED, privileged.requestedStatus)
        assertEquals(
            PrivilegedUtilityStatus.SERVICE_STOPPED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
    }

    @Test
    fun invalidPrivilegedVendorSnapshotIsAConnectionVerificationFailureNotMissingPermission() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "NaN"
        }

        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        assertEquals(
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        assertFalse(controller.state.capabilities.canAdjustJoystickLight)
        assertFalse(controller.state.capabilities.canAdjustJoystickColor)
    }

    @Test
    fun repeatedInvalidReadbackThroughReadyBridgeBecomesUnsatisfiableAndDropsRetry() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "NaN"
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        assertEquals(
            "the first contract miss must stay a retryable verification failure",
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        assertTrue(controller.state.capabilities.shouldOfferPrivilegedAccess)

        controller.refresh()

        assertEquals(
            PrivilegedUtilityStatus.READBACK_UNSATISFIABLE,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        assertFalse(
            "a retry that cannot succeed must not be offered",
            controller.state.capabilities.shouldOfferPrivilegedAccess,
        )
        assertFalse(controller.state.capabilities.canAdjustJoystickLight)
        assertFalse(controller.state.capabilities.canAdjustJoystickColor)
    }

    @Test
    fun postureCycleResetsTheReadbackVerdictAndAllowsOneFreshRetryCycle() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "NaN"
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )
        controller.refresh()
        assertEquals(
            PrivilegedUtilityStatus.READBACK_UNSATISFIABLE,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )

        privileged.currentPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.SERVICE_STOPPED)
        controller.refresh()
        assertEquals(
            PrivilegedUtilityStatus.SERVICE_STOPPED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )

        privileged.currentPosture = PrivilegedUtilityPosture(
            status = PrivilegedUtilityStatus.READY,
            canWriteVendorSettings = true,
            canControlSecondaryBrightness = true,
        )
        controller.refresh()
        assertEquals(
            "a real posture cycle earns one fresh retryable verification failure",
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        assertTrue(controller.state.capabilities.shouldOfferPrivilegedAccess)

        privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.41"
        controller.refresh()
        assertEquals(
            PrivilegedUtilityStatus.READY,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        assertTrue(controller.state.capabilities.canAdjustJoystickLight)
    }

    @Test
    fun livenessHeartbeatKeepsTheUnsatisfiableVerdictWithoutReprobingVendorKeys() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "NaN"
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )
        controller.refresh()
        assertEquals(
            PrivilegedUtilityStatus.READBACK_UNSATISFIABLE,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )

        val readsBefore = privileged.vendorReadCount
        val heartbeat = controller.refreshPrivilegedLiveness()

        assertEquals(
            PrivilegedUtilityStatus.READBACK_UNSATISFIABLE,
            heartbeat.capabilities.privilegedUtilityPosture.status,
        )
        assertEquals(
            "the modal heartbeat must not spam vendor readbacks against a settled verdict",
            readsBefore,
            privileged.vendorReadCount,
        )
    }

    @Test
    fun requestedThemeFollowPausesWithoutVendorAccessAndCanAlwaysBeTurnedOffLocally() {
        val settings = thorSettings(writeAccess = true)
        val originalColor = settings.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR]
        val utility = FakeUtilityPreferences().apply { themeAware = true }
        val controller = controller(
            settings,
            identity = aynIdentity(),
            utility = utility,
            themeColor = { 0xff224466.toInt() },
        )

        assertTrue(utility.themeAware)
        assertFalse(controller.state.themeAwareJoystickLight)
        assertTrue(controller.state.themeAwareJoystickLightRequested)
        assertTrue(controller.setThemeAwareJoystickLight(true) is BrightnessUpdateOutcome.Unsupported)
        assertTrue(controller.applyThemeColor(0xffabcdef.toInt()) is BrightnessUpdateOutcome.Unsupported)
        assertTrue(controller.reconcileThemeAwareJoystickLight() is BrightnessUpdateOutcome.Unsupported)
        assertTrue(controller.setThemeAwareJoystickLight(false) is BrightnessUpdateOutcome.Applied)

        assertFalse(utility.themeAware)
        assertFalse(controller.state.themeAwareJoystickLight)
        assertFalse(controller.state.themeAwareJoystickLightRequested)
        assertEquals(originalColor, settings.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR])
        assertTrue("theme observer and resume reconciliation must not reach the app provider", settings.attempts.isEmpty())
    }

    @Test
    fun readyVendorControlsPhysicalSecondaryAndSynchronizesItsMirror() {
        val settings = thorSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.74f)
        val sink = FakeWindowBrightnessSink()
        val controller = controller(
            settings = settings,
            sink = sink,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 9,
            privileged = privileged,
        )

        assertTrue(controller.state.capabilities.canAdjustSecondary)
        assertEquals(0.74f, controller.state.secondaryFraction!!, 0.0001f)

        val outcome = controller.setFraction(BrightnessTarget.SECONDARY, 0.7f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertTrue((outcome as BrightnessUpdateOutcome.Applied).systemApplied)
        assertEquals(listOf(Triple(9, 0.7f, 70)), privileged.secondaryWrites)
        assertEquals(0.7f, privileged.displayBrightness!!, 0.0001f)
        assertEquals("70", privileged.values[BrightnessSettingsKeys.AYN_SECONDARY])
        assertNull(sink.secondary)
        assertTrue("normal WRITE_SETTINGS is not the vendor route", settings.attempts.isEmpty())
    }

    @Test
    fun readySecondaryRequiresPhysicalAndMirrorAgreementWithoutHeartbeatReloadStorm() {
        val settings = thorSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.52f)
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 9,
            privileged = privileged,
        )

        assertEquals(PrivilegedUtilityStatus.READY, controller.state.capabilities.privilegedUtilityPosture.status)
        assertTrue(controller.state.capabilities.privilegedUtilityPosture.canWriteVendorSettings)
        assertFalse(controller.state.capabilities.privilegedUtilityPosture.canControlSecondaryBrightness)
        assertFalse(controller.state.capabilities.canAdjustSecondary)
        assertEquals(0.52f, controller.state.secondaryFraction!!, 0.0001f)
        val vendorReads = privileged.vendorReadCount
        val displayReads = privileged.displayReadCount

        repeat(4) { controller.refreshPrivilegedLiveness() }

        assertEquals(vendorReads, privileged.vendorReadCount)
        assertEquals(displayReads, privileged.displayReadCount)
    }

    @Test
    fun heartbeatReconcilesSecondaryRemovalAndAdditionOncePerTopologyChange() {
        val settings = thorSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.74f)
        var windowAvailable = true
        var displayId: Int? = 9
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindowProvider = { windowAvailable },
            secondaryIdProvider = { displayId },
            privileged = privileged,
        )
        assertTrue(controller.state.capabilities.canAdjustSecondary)

        windowAvailable = false
        displayId = null
        controller.refreshPrivilegedLiveness()

        assertFalse(controller.state.capabilities.secondaryWindowAvailable)
        assertFalse(controller.state.capabilities.canAdjustSecondary)
        val vendorReadsAfterRemoval = privileged.vendorReadCount
        val displayReadsAfterRemoval = privileged.displayReadCount
        repeat(4) { controller.refreshPrivilegedLiveness() }
        assertEquals(vendorReadsAfterRemoval, privileged.vendorReadCount)
        assertEquals(displayReadsAfterRemoval, privileged.displayReadCount)

        windowAvailable = true
        displayId = 10
        controller.refreshPrivilegedLiveness()

        assertTrue(controller.state.capabilities.secondaryWindowAvailable)
        assertTrue(controller.state.capabilities.canAdjustSecondary)
        assertTrue(privileged.vendorReadCount > vendorReadsAfterRemoval)
        assertTrue(privileged.displayReadCount > displayReadsAfterRemoval)
    }

    @Test
    fun secondaryReadbackMismatchFailsAndShowsTheAuthoritativePhysicalValue() {
        val settings = thorSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.74f).apply {
            displayWriteTransform = { 0.74f }
        }
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 7,
            privileged = privileged,
        )

        val outcome = controller.setFraction(BrightnessTarget.SECONDARY, 0.7f)

        assertTrue(outcome is BrightnessUpdateOutcome.WriteFailed)
        assertEquals(0.74f, controller.state.secondaryFraction!!, 0.0001f)
        assertEquals("Change was not applied.", controller.state.privilegedFailureMessage)
    }

    @Test
    fun readyVendorUsesVerifiedVendorLightTransactions() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        assertTrue(controller.state.capabilities.canAdjustJoystickLight)
        assertTrue(controller.state.capabilities.canAdjustJoystickColor)
        assertTrue(controller.setFraction(BrightnessTarget.JOYSTICK_LIGHT, 0.35f) is BrightnessUpdateOutcome.Applied)
        assertEquals("0.35", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals("1,1", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED])

        assertTrue(
            controller.setFraction(
                BrightnessTarget.JOYSTICK_COLOR,
                1f / JoystickColorCodec.presets.lastIndex,
            ) is
                BrightnessUpdateOutcome.Applied,
        )
        assertEquals(
            "#FFFF0000,#FFFF0000",
            privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR],
        )
        assertTrue(settings.attempts.isEmpty())
    }

    @Test
    fun privilegedStepUsesLiveHardwareInsteadOfTheRenderedLightCache() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )
        privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.6"
        privileged.writeAttempts.clear()

        val outcome = controller.step(BrightnessTarget.JOYSTICK_LIGHT, 1)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals("0.65", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals(0.65f, controller.state.joystickLightFraction!!, 0.0001f)
    }

    @Test
    fun privilegedSecondaryStepStartsFromTheLivePhysicalPanel() {
        val settings = thorSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.74f)
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 9,
            privileged = privileged,
        )
        privileged.displayBrightness = 0.5f
        privileged.values[BrightnessSettingsKeys.AYN_SECONDARY] = "50"
        privileged.secondaryWrites.clear()

        val outcome = controller.step(BrightnessTarget.SECONDARY, 1)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(listOf(Triple(9, 0.55f, 55)), privileged.secondaryWrites)
        assertEquals(0.55f, controller.state.secondaryFraction!!, 0.0001f)
    }

    @Test
    fun privilegedSetDoesNotNoOpAgainstAStaleRenderedLightValue() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )
        privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.7"
        privileged.writeAttempts.clear()

        val outcome = controller.setFraction(BrightnessTarget.JOYSTICK_LIGHT, 0.05f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(
            listOf(
                BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT to "0.05",
                BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED to "1,1",
            ),
            privileged.writeAttempts,
        )
        assertEquals("0.05", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
    }

    @Test
    fun privilegedReadbackMismatchDoesNotAdvanceTheLightUi() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            ignoredWrites += BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        val outcome = controller.setFraction(BrightnessTarget.JOYSTICK_LIGHT, 0.35f)

        assertTrue(outcome is BrightnessUpdateOutcome.WriteFailed)
        assertEquals(0.05f, controller.state.joystickLightFraction!!, 0.0001f)
        assertEquals("0.05", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals("Change was not applied.", controller.state.privilegedFailureMessage)
        assertEquals(PrivilegedUtilityStatus.READY, controller.state.capabilities.privilegedUtilityPosture.status)
        assertTrue(controller.state.capabilities.canAdjustJoystickLight)
    }

    @Test
    fun uncertainLightRollbackDemotesPrivilegedControlsUntilReconnect() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            scriptedWriteValues[1] = "0,0"
            scriptedWriteValues[2] = "0.8"
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        val outcome = controller.setFraction(BrightnessTarget.JOYSTICK_LIGHT, 0.35f)

        assertTrue(outcome is BrightnessUpdateOutcome.WriteFailed)
        assertEquals(
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        assertFalse(controller.state.capabilities.canAdjustJoystickLight)
        assertEquals(
            // Typographic apostrophe (U+2019), not ASCII — release UX/UI content gate, mechanical fix
            // ( this glyph has regressed
            // three times in this programme, per the standing house style already used pervasively in
            // core-ui/MimirCopy.kt and elsewhere). Not a wording change — same string, correct glyph.
            "Mimir couldn’t confirm the change. Reconnect special access, then reopen Utility.",
            controller.state.privilegedFailureMessage,
        )
    }

    @Test
    fun uncertainColorRollbackDemotesPrivilegedControlsUntilReconnect() {
        val settings = vendorLightSettings(writeAccess = false)
        val original = settings.values.getValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            scriptedWriteValues[0] = original
            scriptedWriteValues[1] = "#FF000000,#FF000000"
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        val outcome = controller.setFraction(BrightnessTarget.JOYSTICK_COLOR, 1f / 12f)

        assertTrue(outcome is BrightnessUpdateOutcome.WriteFailed)
        assertEquals(
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
        assertFalse(controller.state.capabilities.canAdjustJoystickColor)
        assertEquals(
            // Typographic apostrophe (U+2019), not ASCII — release UX/UI content gate, mechanical fix
            // ( this glyph has regressed
            // three times in this programme, per the standing house style already used pervasively in
            // core-ui/MimirCopy.kt and elsewhere). Not a wording change — same string, correct glyph.
            "Mimir couldn’t confirm the change. Reconnect special access, then reopen Utility.",
            controller.state.privilegedFailureMessage,
        )
    }

    @Test
    fun themeFollowWritesOnlyWhileReadyAndBinderLossPausesWithoutPrompting() {
        val settings = vendorLightSettings(writeAccess = false)
        val utility = FakeUtilityPreferences()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        assertTrue(controller.setThemeAwareJoystickLight(true) is BrightnessUpdateOutcome.Applied)
        assertTrue(utility.themeAware)
        assertTrue(controller.state.themeAwareJoystickLight)
        assertEquals(
            "#FF224466,#FF224466",
            privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR],
        )

        privileged.currentPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.SERVICE_STOPPED)
        controller.refresh()
        val attemptsBefore = privileged.writeAttempts.size

        assertTrue(controller.applyThemeColor(0xffabcdef.toInt()) is BrightnessUpdateOutcome.Unsupported)
        assertEquals(attemptsBefore, privileged.writeAttempts.size)
        assertTrue(controller.state.themeAwareJoystickLightRequested)
        assertFalse(controller.state.themeAwareJoystickLight)
        assertTrue(controller.setThemeAwareJoystickLight(false) is BrightnessUpdateOutcome.Applied)
        assertFalse(utility.themeAware)
        assertTrue(utility.restorePending)

        privileged.currentPosture = PrivilegedUtilityPosture(
            status = PrivilegedUtilityStatus.READY,
            canWriteVendorSettings = true,
            canControlSecondaryBrightness = true,
        )
        controller.refresh()
        assertTrue(controller.reconcileThemeAwareJoystickLight() is BrightnessUpdateOutcome.Applied)
        assertFalse(utility.restorePending)
        assertEquals(
            "#FFFFFFFF,#FFFFFFFF",
            privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR],
        )
    }

    @Test
    fun interruptedThemeTransactionRestoresManualColorOnReconcile() {
        val settings = vendorLightSettings(writeAccess = false)
        val manual = settings.values.getValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR] = "#FF224466,#FF224466"
        }
        val utility = FakeUtilityPreferences().apply {
            manualColorValue = manual
            restorePending = true
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        val outcome = controller.reconcileThemeAwareJoystickLight()

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(manual, privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR])
        assertFalse(utility.themeAware)
        assertFalse(utility.restorePending)
        assertFalse(controller.state.themeAwareJoystickLight)
        assertFalse(controller.state.themeAwareJoystickLightRequested)
        assertFalse(controller.state.themeAwareJoystickLightRestorePending)
    }

    @Test
    fun pendingThemeRestoreWinsOverAConflictingRequestedFlag() {
        val settings = vendorLightSettings(writeAccess = false)
        val manual = settings.values.getValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR] = "#FF224466,#FF224466"
        }
        val utility = FakeUtilityPreferences().apply {
            themeAware = true
            manualColorValue = manual
            restorePending = true
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        val outcome = controller.applyThemeColor(0xffabcdef.toInt())

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(manual, privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR])
        assertFalse(utility.themeAware)
        assertFalse(utility.restorePending)
        assertFalse(controller.state.themeAwareJoystickLightRequested)
    }

    @Test
    fun failedThemeEnableClearsRecoveryMarkerOnlyAfterVerifiedRollback() {
        val settings = vendorLightSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            ignoredWrites += BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR
        }
        val utility = FakeUtilityPreferences()
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        val outcome = controller.setThemeAwareJoystickLight(true)

        assertTrue(outcome is BrightnessUpdateOutcome.WriteFailed)
        assertFalse(utility.themeAware)
        assertFalse(utility.restorePending)
        assertFalse(controller.state.themeAwareJoystickLightRestorePending)
        assertEquals("Change was not applied.", controller.state.privilegedFailureMessage)
        assertEquals(PrivilegedUtilityStatus.READY, controller.state.capabilities.privilegedUtilityPosture.status)
    }

    @Test
    fun failedThemeEnableKeepsRecoveryMarkerWhenRollbackIsUnverifiable() {
        val settings = vendorLightSettings(writeAccess = false)
        val original = settings.values.getValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            scriptedWriteValues[0] = original
            scriptedWriteValues[1] = "#FF000000,#FF000000"
        }
        val utility = FakeUtilityPreferences()
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        val outcome = controller.setThemeAwareJoystickLight(true)

        assertTrue(outcome is BrightnessUpdateOutcome.WriteFailed)
        assertFalse(utility.themeAware)
        assertTrue(utility.restorePending)
        assertTrue(controller.state.themeAwareJoystickLightRestorePending)
        assertEquals(
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
    }

    @Test
    fun explicitEnableAfterInterruptedThemeTransactionRestoresThenEnables() {
        val settings = vendorLightSettings(writeAccess = false)
        val manual = settings.values.getValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR] = "#FF112233,#FF112233"
        }
        val utility = FakeUtilityPreferences().apply {
            manualColorValue = manual
            restorePending = true
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        val outcome = controller.setThemeAwareJoystickLight(true)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(
            "#FF224466,#FF224466",
            privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR],
        )
        assertTrue(utility.themeAware)
        assertFalse(utility.restorePending)
        assertTrue(controller.state.themeAwareJoystickLight)
        assertTrue(controller.state.themeAwareJoystickLightRequested)
    }

    @Test
    fun hapticStrengthIsMimirLocalAndPreviewsTheAppliedValue() {
        val haptics = FakeHaptics(fraction = 0.4f, amplitudeControl = true)
        val controller = controller(FakeBrightnessSettings(), haptics = haptics)

        val outcome = controller.setFraction(BrightnessTarget.MIMIR_HAPTIC, 0.65f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertFalse((outcome as BrightnessUpdateOutcome.Applied).systemApplied)
        assertEquals(0.65f, haptics.fraction, 0.0001f)
        assertEquals(1, haptics.previewCount)
        assertEquals(0.65f, controller.state.mimirHapticFraction!!, 0.0001f)
    }

    @Test
    fun ambientSoundIsMimirLocalAndUpdatesItsLivePreferenceLevel() {
        val ambient = FakeAmbientSound(fraction = 0.22f)
        val controller = controller(FakeBrightnessSettings(), ambient = ambient)

        val outcome = controller.setFraction(BrightnessTarget.AMBIENT_SOUND, 0.55f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertFalse((outcome as BrightnessUpdateOutcome.Applied).systemApplied)
        assertEquals(0.55f, ambient.fraction, 0.0001f)
        assertEquals(0.55f, controller.state.ambientSoundFraction!!, 0.0001f)
    }

    @Test
    fun mediaVolumeRereadsTheActualDiscreteStreamLevel() {
        val media = FakeMediaVolume(fraction = 0.2f, fixed = false)
        val controller = controller(FakeBrightnessSettings(), media = media)

        val outcome = controller.setFraction(BrightnessTarget.MEDIA_VOLUME, 0.5f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(8f / 15f, controller.state.mediaVolumeFraction!!, 0.0001f)
    }

    @Test
    fun mediaVolumeDoesNotNoOpAgainstAStaleCachedLevel() {
        val media = FakeMediaVolume(fraction = 0.2f, fixed = false)
        val controller = controller(FakeBrightnessSettings(), media = media)
        media.fraction = 0.8f // Hardware volume keys changed the stream while Utility remained open.

        controller.setFraction(BrightnessTarget.MEDIA_VOLUME, 0.2f)

        assertEquals(0.2f, media.fraction, 0.0001f)
        media.fraction = 0.8f
        controller.step(BrightnessTarget.MEDIA_VOLUME, 1)
        assertEquals(13f / 15f, media.fraction, 0.0001f)
    }

    @Test
    fun mediaControllerStepMovesExactlyOneDiscreteStreamLevel() {
        val media = FakeMediaVolume(fraction = 8f / 15f, fixed = false)
        val controller = controller(FakeBrightnessSettings(), media = media)

        val outcome = controller.step(BrightnessTarget.MEDIA_VOLUME, -1)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(7f / 15f, media.fraction, 0.0001f)
        assertEquals(7f / 15f, controller.state.mediaVolumeFraction!!, 0.0001f)
    }

    @Test
    fun fixedVolumeDeviceShowsStateButRejectsAdjustment() {
        val media = FakeMediaVolume(fraction = 1f, fixed = true)
        val controller = controller(FakeBrightnessSettings(), media = media)

        assertTrue(controller.state.capabilities.mediaVolumeSupported)
        assertFalse(controller.state.capabilities.canAdjustMediaVolume)
        assertTrue(controller.setFraction(BrightnessTarget.MEDIA_VOLUME, 0.5f) is BrightnessUpdateOutcome.Unsupported)
    }

    @Test
    fun recreationReappliesStoredWindowOnlyValuesWithoutSystemWrites() {
        val settings = FakeBrightnessSettings(writeAccess = false)
        val preferences = FakeBrightnessPreferences(
            mutableMapOf(BrightnessTarget.PRIMARY to 0.25f, BrightnessTarget.SECONDARY to 0.85f),
        )
        val sink = FakeWindowBrightnessSink()
        val controller = controller(settings, preferences, sink, secondaryWindow = true)
        settings.writes.clear()

        val fallbacks = controller.applyWindowFallbacks()

        assertEquals(WindowBrightnessFallbacks(0.25f, 0.85f), fallbacks)
        assertEquals(0.25f, sink.primary!!, 0.0001f)
        assertEquals(0.85f, sink.secondary!!, 0.0001f)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun idleDimJournalsAndRestoresExactJoystickLightValues() {
        val settings = vendorLightSettings()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.65"
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED] = "1,0"
        }
        val utility = FakeUtilityPreferences()
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
        )

        val dimGeneration = controller.prepareIdleJoystickTransition(true)
        assertTrue(controller.applyIdleJoystickTransition(dimGeneration))
        assertEquals("0.01", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals("1,0", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED])
        assertEquals(IdleJoystickLightRestore("0.65", "1,0"), utility.idleJoystickRestore)

        val wakeGeneration = controller.prepareIdleJoystickTransition(false)
        assertTrue(controller.applyIdleJoystickTransition(wakeGeneration))
        assertEquals("0.65", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals("1,0", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED])
        assertNull(utility.idleJoystickRestore)
    }

    @Test
    fun idleDimNeverEnablesOrBrightensAnOffOrAlreadyOnePercentLight() {
        listOf(
            "0.65" to "0,0",
            "0.01" to "1,1",
        ).forEach { (percent, enabled) ->
            val settings = vendorLightSettings()
            val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
                values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = percent
                values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED] = enabled
            }
            val utility = FakeUtilityPreferences()
            val controller = controller(
                settings = settings,
                identity = retroidIdentity(),
                utility = utility,
                privileged = privileged,
            )

            val generation = controller.prepareIdleJoystickTransition(true)
            assertTrue(controller.applyIdleJoystickTransition(generation))
            assertTrue(privileged.writeAttempts.isEmpty())
            assertNull(utility.idleJoystickRestore)
            assertEquals(percent, privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
            assertEquals(enabled, privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED])
        }
    }

    @Test
    fun coldRecoveryRestoresAProcessDeathIdleJournalBeforeClearingIt() {
        val settings = vendorLightSettings()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.01"
        }
        val utility = FakeUtilityPreferences().apply {
            idleJoystickRestore = IdleJoystickLightRestore("0.65", "1,1")
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
        )

        val generation = controller.prepareIdleJoystickTransition(false)
        assertTrue(controller.applyIdleJoystickTransition(generation))

        assertEquals("0.65", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals("1,1", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED])
        assertNull(utility.idleJoystickRestore)
    }

    @Test
    fun joystickStepRestoresIdleJournalBeforeReadingAndWritingTheNewLevel() {
        val settings = vendorLightSettings()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values).apply {
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.01"
            values[BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED] = "1,1"
        }
        val utility = FakeUtilityPreferences().apply {
            idleJoystickRestore = IdleJoystickLightRestore("0.65", "1,1")
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
        )
        controller.prepareIdleJoystickTransition(false)

        val outcome = controller.step(BrightnessTarget.JOYSTICK_LIGHT, 1)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals("0.7", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals(0.7f, controller.state.joystickLightFraction!!, 0.0001f)
        assertNull(utility.idleJoystickRestore)
    }

    @Test
    fun bindingThenReadyResumeRetryRestoresColdIdleJournal() {
        val settings = vendorLightSettings()
        val privileged = FakePrivilegedUtilityGateway(
            currentPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.BINDING),
            values = settings.values.toMutableMap().apply {
                put(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT, "0.01")
                put(BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED, "1,1")
            },
        )
        val utility = FakeUtilityPreferences().apply {
            idleJoystickRestore = IdleJoystickLightRestore("0.65", "1,1")
        }
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
        )
        controller.prepareIdleJoystickTransition(false)

        controller.reconcileIdleJoystickLightAfterReady()
        assertEquals("0.01", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertEquals(IdleJoystickLightRestore("0.65", "1,1"), utility.idleJoystickRestore)

        privileged.currentPosture = PrivilegedUtilityPosture(
            status = PrivilegedUtilityStatus.READY,
            canWriteVendorSettings = true,
            canControlSecondaryBrightness = true,
        )
        controller.refreshPrivilegedLiveness()
        controller.reconcileIdleJoystickLightAfterReady()

        assertEquals("0.65", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertNull(utility.idleJoystickRestore)
    }

    @Test
    fun deferredInitialProbePublishesPassiveStateUntilOffMainRefreshOwnsPrivilegedIo() {
        val settings = vendorLightSettings()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
            deferInitialPrivilegedProbe = true,
        )

        assertEquals(0, privileged.postureCount)
        assertEquals(0, privileged.vendorReadCount)
        assertEquals(
            PrivilegedUtilityStatus.BINDING,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )

        controller.refresh()

        assertTrue(privileged.postureCount > 0)
        assertTrue(privileged.vendorReadCount > 0)
        assertEquals(
            PrivilegedUtilityStatus.READY,
            controller.state.capabilities.privilegedUtilityPosture.status,
        )
    }

    @Test
    fun aNewerWakeGenerationCancelsAQueuedJoystickDimWithoutHardwareWrites() {
        val settings = vendorLightSettings()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            privileged = privileged,
        )

        val staleDim = controller.prepareIdleJoystickTransition(true)
        controller.prepareIdleJoystickTransition(false)

        assertFalse(controller.applyIdleJoystickTransition(staleDim))
        assertTrue(privileged.writeAttempts.isEmpty())
    }

    /**
     * "Dim displays after" must dim the MAIN panel too, and it cannot do that through a window override.
     *
     * Measured on the Thor 2026-09-06 across wake -> idle -> wake: `panel1-backlight` moved
     * 1562 -> 19 -> 1562 while `panel0-backlight` stayed at 2450 the entire time, even though
     * `mScreenBrightnessOverrideFromWindowManager` went NaN -> 0.01 -> NaN. The framework HAD the
     * override; the main panel does not follow it, because a successful `updatePrimary` puts the primary
     * on `Settings.System` and then releases the window override. So the setting only ever dimmed the
     * second display, exactly as reported.
     */
    @Test
    fun `idle dim moves the primary through the system setting and gives it back on wake`() {
        val settings = FakeBrightnessSettings(
            values = mutableMapOf(
                BrightnessSettingsKeys.PRIMARY_MODE to "0",
                BrightnessSettingsKeys.PRIMARY to "153",
            ),
            writeAccess = true,
        )
        val utility = FakeUtilityPreferences()
        val controller = controller(settings = settings, utility = utility)
        controller.setFraction(BrightnessTarget.PRIMARY, 0.6f)
        assertTrue(
            "this test is meaningless unless the primary really is on the system path",
            controller.state.capabilities.mainSystemBrightnessActive,
        )
        val applied = settings.values[BrightnessSettingsKeys.PRIMARY]

        controller.applyIdlePrimarySystemBrightness(controller.prepareIdleJoystickTransition(true))

        assertEquals(
            BrightnessMath.systemLevel(MimirIdleDimFraction).toString(),
            settings.values[BrightnessSettingsKeys.PRIMARY],
        )
        assertEquals("the player's own brightness is owed back", applied, utility.idleSystemBrightnessRestore)

        controller.applyIdlePrimarySystemBrightness(controller.prepareIdleJoystickTransition(false))

        assertEquals(applied, settings.values[BrightnessSettingsKeys.PRIMARY])
        assertNull("nothing is owed once it is given back", utility.idleSystemBrightnessRestore)
    }

    /**
     * The dangerous one. If a process dies while dimmed, the stored brightness is the DIM level. A second
     * dim that snapshots before healing would record 1% as "the player's brightness" and the main screen
     * would never come back — the exact failure the idle joystick light journal exists to prevent.
     */
    @Test
    fun `a dim journal left by a dead process is healed before the next snapshot`() {
        val settings = FakeBrightnessSettings(
            values = mutableMapOf(
                BrightnessSettingsKeys.PRIMARY_MODE to "0",
                BrightnessSettingsKeys.PRIMARY to "153",
            ),
            writeAccess = true,
        )
        val utility = FakeUtilityPreferences()
        val controller = controller(settings = settings, utility = utility)
        controller.setFraction(BrightnessTarget.PRIMARY, 0.6f)
        val real = settings.values[BrightnessSettingsKeys.PRIMARY]
        // A previous session dimmed and died: the journal owes the real value, the SETTING holds the dim.
        utility.idleSystemBrightnessRestore = real
        settings.values[BrightnessSettingsKeys.PRIMARY] =
            BrightnessMath.systemLevel(MimirIdleDimFraction).toString()

        controller.applyIdlePrimarySystemBrightness(controller.prepareIdleJoystickTransition(true))

        assertEquals(
            "the dim level must never become the value owed back",
            real,
            utility.idleSystemBrightnessRestore,
        )
    }

    /**
     *
     *
     * Measured on the Thor at 100 ms across a full wake -> idle -> wake cycle, on the build carrying the
     * morning's main-screen fix: at the dim onset `panel0-backlight` ramped 2450 -> 44 while
     * `panel1-backlight` sat at 1562 THE WHOLE TIME; at the wake, panel1 flashed 1562 -> 17 -> 1562 over
     * ~1.5 s. Display 4's power controller held Mimir's 0.01 window override as PENDING while enforcing
     * 0.6 "manual" -- the vendor's own `dual_screen_brightness_level=60`. Writing that key alone moved
     * nothing. So on this build the Presentation's window override is not a lever for the second screen.
     * A dark flash on every wake was measured; its source is not established, so whether dropping the
     * override removes it is device-unproven. The lever Mimir already has is the privileged panel transaction
     * the Utility Screen-2 slider uses (display write + vendor mirror, verified by read-back), which
     * floors at 5%. Journalled and healed like the primary, because a per-display brightness write
     * persists.
     */
    @Test
    fun `idle dim moves the second screen through the privileged panel write and gives it back on wake`() {
        val settings = thorSettings(writeAccess = false)
        // thorSettings mirrors 74; the physical panel must agree or the controller will not call it adjustable.
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.74f)
        val utility = FakeUtilityPreferences()
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 4,
            privileged = privileged,
            utility = utility,
        )
        assertTrue("meaningless unless the second screen really is vendor-owned and adjustable", controller.state.capabilities.canAdjustSecondary)

        assertTrue(controller.applyIdleSecondarySystemBrightness(controller.prepareIdleJoystickTransition(true)))

        assertEquals(listOf(Triple(4, IdleSecondaryDimFraction, 5)), privileged.secondaryWrites)
        assertEquals("5", privileged.values[BrightnessSettingsKeys.AYN_SECONDARY])
        assertEquals("the player's own second-screen brightness is owed back", "0.74", utility.idleSecondaryBrightnessRestore)

        assertTrue(controller.applyIdleSecondarySystemBrightness(controller.prepareIdleJoystickTransition(false)))

        assertEquals(Triple(4, 0.74f, 74), privileged.secondaryWrites.last())
        assertEquals(0.74f, privileged.displayBrightness!!, 0.0001f)
        assertEquals("74", privileged.values[BrightnessSettingsKeys.AYN_SECONDARY])
        assertNull("nothing is owed once it is given back", utility.idleSecondaryBrightnessRestore)
    }

    @Test
    fun `a second-screen dim journal left by a dead process is healed before the next snapshot`() {
        val settings = thorSettings(writeAccess = false)
        // A previous session dimmed and died: the journal owes the real value, the PANEL and its mirror hold the dim.
        settings.values[BrightnessSettingsKeys.AYN_SECONDARY] = "5"
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = IdleSecondaryDimFraction)
        val utility = FakeUtilityPreferences().apply { idleSecondaryBrightnessRestore = "0.74" }
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 4,
            privileged = privileged,
            utility = utility,
        )

        assertTrue(controller.applyIdleSecondarySystemBrightness(controller.prepareIdleJoystickTransition(true)))

        assertEquals(
            "heal first, then snapshot, then dim",
            listOf(Triple(4, 0.74f, 74), Triple(4, IdleSecondaryDimFraction, 5)),
            privileged.secondaryWrites,
        )
        assertEquals("the dim level must never become the value owed back", "0.74", utility.idleSecondaryBrightnessRestore)
    }

    @Test
    fun `the idle dim leaves the Presentation window alone where the second screen is vendor-owned`() {
        val thorSink = FakeWindowBrightnessSink()
        val thor = controller(
            settings = thorSettings(writeAccess = false),
            sink = thorSink,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 4,
            privileged = FakePrivilegedUtilityGateway.ready(mapOf(BrightnessSettingsKeys.AYN_SECONDARY to "60"), displayBrightness = 0.6f),
        )
        thor.setIdleDimmed(true)
        assertEquals(MimirIdleDimFraction, thorSink.primary!!, 0.0001f)
        assertNull("the window override did nothing on the Thor's second screen at the dim onset", thorSink.secondary)

        val windowSink = FakeWindowBrightnessSink()
        val windowOnly = controller(settings = manualBrightnessSettings(writeAccess = true), sink = windowSink, secondaryWindow = true)
        windowOnly.setIdleDimmed(true)
        assertEquals("a window-only second screen keeps its window dim", MimirIdleDimFraction, windowSink.secondary!!, 0.0001f)
    }

    /** Green-only pin: no red arm was run against production for this guard. */
    @Test
    fun `a Screen-2 slider write pays an owed idle restore first so the restore cannot undo it`() {
        val settings = thorSettings(writeAccess = false)
        settings.values[BrightnessSettingsKeys.AYN_SECONDARY] = "5"
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = IdleSecondaryDimFraction)
        val utility = FakeUtilityPreferences().apply { idleSecondaryBrightnessRestore = "0.74" }
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 4,
            privileged = privileged,
            utility = utility,
        )

        val outcome = controller.setFraction(BrightnessTarget.SECONDARY, 0.7f)

        assertTrue(outcome is BrightnessUpdateOutcome.Applied)
        assertEquals(listOf(Triple(4, 0.74f, 74), Triple(4, 0.7f, 70)), privileged.secondaryWrites)
        assertNull("the debt is paid, not carried past the player's own write", utility.idleSecondaryBrightnessRestore)
    }

    /**
     * RED FIRST. Before this fix `updateSecondary` called
     * [BrightnessController.restoreIdleSecondarySystemBrightness] and ignored its result entirely --
     * a restore that FAILED left the debt sitting in the journal, but the slider write proceeded
     * anyway and reported Applied. That let a later restore land on top of the value the player had
     * just set and silently undo it. The fix checks whether debt remains right after the restore
     * attempt (not the attempt's own Boolean, which is also false when nothing was owed) and refuses
     * the slider write, before ever touching the display, while debt is still outstanding.
     */
    @Test
    fun `a Screen-2 slider write is refused while a failed idle restore still owes debt`() {
        val settings = thorSettings(writeAccess = false)
        settings.values[BrightnessSettingsKeys.AYN_SECONDARY] = "5"
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = IdleSecondaryDimFraction).apply {
            // Only the RESTORE's own write (0.74, the owed value) fails -- the player's new slider
            // value (0.7) must never even be attempted while that debt is unresolved, which is
            // exactly what the fix is required to guarantee.
            secondaryWriteFailsForFraction = 0.74f
        }
        val utility = FakeUtilityPreferences().apply { idleSecondaryBrightnessRestore = "0.74" }
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 4,
            privileged = privileged,
            utility = utility,
        )

        val outcome = controller.setFraction(BrightnessTarget.SECONDARY, 0.7f)

        assertTrue(
            "debt that survived the restore attempt must produce the privileged-failure outcome",
            outcome is BrightnessUpdateOutcome.WriteFailed,
        )
        assertTrue(
            "the new slider value must never be written while old debt can still land on top of it",
            privileged.secondaryWrites.isEmpty(),
        )
        assertEquals(
            "a failed restore's debt must still be owed afterward, not silently dropped",
            "0.74",
            utility.idleSecondaryBrightnessRestore,
        )
    }

    /**
     * Confirmation pin (behaviour unchanged by this fix): reading whether debt remains after a
     * restore attempt must never reject an ordinary slider request when nothing was owed in the
     * first place -- [BrightnessController.restoreIdleSecondarySystemBrightness] also returns false
     * on the no-debt path (`?: return false`), so gating on that Boolean alone would break every
     * request here. This is green before and after the fix; it guards against that regression.
     */
    @Test
    fun `a Screen-2 slider write applies normally when no idle restore is owed`() {
        val settings = thorSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.74f)
        val utility = FakeUtilityPreferences()
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 9,
            privileged = privileged,
            utility = utility,
        )

        val outcome = controller.setFraction(BrightnessTarget.SECONDARY, 0.7f)

        assertTrue(
            "no debt means nothing to pay first; the slider request must apply exactly as before",
            outcome is BrightnessUpdateOutcome.Applied,
        )
        assertEquals(listOf(Triple(9, 0.7f, 70)), privileged.secondaryWrites)
        assertNull(utility.idleSecondaryBrightnessRestore)
    }

    @Test
    fun `without privileged access the second screen is not dimmed and nothing is owed`() {
        val settings = thorSettings(writeAccess = false)
        val privileged = FakePrivilegedUtilityGateway(
            currentPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
            values = settings.values,
            displayBrightness = 0.6f,
        )
        val utility = FakeUtilityPreferences()
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 4,
            privileged = privileged,
            utility = utility,
        )

        assertFalse(controller.applyIdleSecondarySystemBrightness(controller.prepareIdleJoystickTransition(true)))

        assertTrue(privileged.secondaryWrites.isEmpty())
        assertNull(utility.idleSecondaryBrightnessRestore)
    }

    /**
     * Round-5 B7, Thor, 2026-09-11. The Utility sliders read the idle-dim level: MAIN 5% and SECONDARY 5%
     * while dimmed (end-user frames 221 and 223), then SECONDARY 60% with MAIN still 5% after waking
     * (222, 224, 225). The orchestrator's device read on the round-4 build: `screen_brightness` 153 awake,
     * 13 after 66 s without Main input. The dim is temporary hardware state; the sliders report the level
     * the player chose, which the idle journal owes back.
     *
     * The dimmed, refreshed-while-dimmed and woken readings are compared as the whole-percent labels the
     * rows render, in one assertion, so a failure reports every stage instead of stopping at the first.
     */
    @Test
    fun `the Thor sliders keep the player levels through idle dim refresh and wake`() {
        // The Thor fixture of thorVendorSecondaryIsLiveReadOnlyAndRetiresLegacyWindowOverride at the device's
        // own values (screen_brightness 153, secondary 60), plus READY privileged access: that fixture alone
        // is read-only, and the second screen dims only through the privileged panel write.
        val settings = thorSettings(writeAccess = true).apply {
            values[BrightnessSettingsKeys.PRIMARY] = "153"
            values[BrightnessSettingsKeys.AYN_SECONDARY] = "60"
        }
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values, displayBrightness = 0.6f)
        val utility = FakeUtilityPreferences()
        val controller = controller(
            settings = settings,
            identity = aynIdentity(),
            secondaryWindow = true,
            secondaryId = 4,
            privileged = privileged,
            utility = utility,
        )
        fun sliders(): String {
            val main = (controller.state.primaryFraction * 100).roundToInt()
            val secondary = controller.state.secondaryFraction?.let { (it * 100).roundToInt() }
            return "$main/$secondary"
        }
        assertTrue(
            "meaningless unless MAIN is on the system path",
            controller.state.capabilities.mainSystemBrightnessActive,
        )
        assertTrue(
            "meaningless unless SECONDARY is vendor-owned and adjustable",
            controller.state.capabilities.canAdjustSecondary,
        )
        assertEquals("60/60", sliders())

        val dim = controller.prepareIdleJoystickTransition(true)
        assertTrue(controller.applyIdlePrimarySystemBrightness(dim))
        assertTrue(controller.applyIdleSecondarySystemBrightness(dim))
        // Both panels really are dimmed (13 is what the Thor read), or the trajectory below proves nothing.
        assertEquals(
            BrightnessMath.systemLevel(MimirIdleDimFraction).toString(),
            settings.values[BrightnessSettingsKeys.PRIMARY],
        )
        assertEquals(IdleSecondaryDimFraction, privileged.displayBrightness!!, 0.0001f)
        val dimmed = sliders()
        controller.refresh()
        val refreshedWhileDimmed = sliders()

        val wake = controller.prepareIdleJoystickTransition(false)
        assertTrue(controller.applyIdleSecondarySystemBrightness(wake))
        assertTrue(controller.applyIdlePrimarySystemBrightness(wake))
        assertEquals("153", settings.values[BrightnessSettingsKeys.PRIMARY])
        assertEquals(0.6f, privileged.displayBrightness!!, 0.0001f)
        val woken = sliders()

        assertEquals(
            "MAIN/SECONDARY percent: dimmed, refreshed while dimmed, woken",
            listOf("60/60", "60/60", "60/60"),
            listOf(dimmed, refreshedWhileDimmed, woken),
        )
    }

    private fun controller(
        settings: FakeBrightnessSettings,
        preferences: FakeBrightnessPreferences = FakeBrightnessPreferences(),
        sink: FakeWindowBrightnessSink = FakeWindowBrightnessSink(),
        identity: BrightnessDeviceIdentity = BrightnessDeviceIdentity("Google", "google", "generic"),
        secondaryWindow: Boolean = false,
        utility: FakeUtilityPreferences = FakeUtilityPreferences(),
        haptics: FakeHaptics = FakeHaptics(available = false),
        ambient: FakeAmbientSound = FakeAmbientSound(available = false),
        media: FakeMediaVolume = FakeMediaVolume(available = false),
        themeColor: () -> Int = { 0xffffffff.toInt() },
        privileged: PrivilegedUtilityGateway = PrivilegedUtilityGateway.None,
        secondaryId: Int? = null,
        secondaryWindowProvider: () -> Boolean = { secondaryWindow },
        secondaryIdProvider: () -> Int? = { secondaryId },
        deferInitialPrivilegedProbe: Boolean = false,
    ) = BrightnessController(
        settings = settings,
        preferences = preferences,
        identity = identity,
        secondaryWindowAvailable = secondaryWindowProvider,
        windowSink = sink,
        utilityPreferences = utility,
        haptics = haptics,
        ambientSound = ambient,
        mediaVolume = media,
        themeColorArgb = themeColor,
        privilegedUtility = privileged,
        secondaryDisplayId = secondaryIdProvider,
        deferInitialPrivilegedProbe = deferInitialPrivilegedProbe,
    )

    private fun thorSettings(writeAccess: Boolean) = vendorLightSettings(writeAccess).apply {
        values[BrightnessSettingsKeys.AYN_SECONDARY] = "74"
    }

    private fun manualBrightnessSettings(writeAccess: Boolean) = FakeBrightnessSettings(
        values = mutableMapOf(BrightnessSettingsKeys.PRIMARY_MODE to "0"),
        writeAccess = writeAccess,
    )

    /**
     * Nova joystick-light brightness regression, 2026-08-29.
     *
     * Measured on the device: `joystick_light_enabled=1,1` with a real colour and
     * `led_light_brightness_percent=0.0` — the sticks were enabled, coloured, and physically dark,
     * because Mimir's theme light only ever wrote COLOUR. Switching it on returned Applied and could
     * not possibly show anything. The technical requirement is that Mimir should own the brightness too.
     */
    @Test
    fun themeLightRaisesAVendorBrightnessThatCannotIlluminate() {
        val settings = vendorLightSettings(writeAccess = false)
        val utility = FakeUtilityPreferences()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.0"
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        assertTrue(controller.setThemeAwareJoystickLight(true) is BrightnessUpdateOutcome.Applied)

        assertEquals(
            "a dark lamp must be raised to the floor",
            BrightnessMath.joystickPercentValue(ThemeAwareJoystickLightFloor),
            privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT],
        )
        assertEquals("and the original journalled for the way back", "0.0", utility.themePercentRestore)
    }

    /** A level he set himself is his. Mimir raises from below the floor and never turns his sticks up. */
    @Test
    fun themeLightNeverTurnsUpABrightnessTheOwnerAlreadyChose() {
        val settings = vendorLightSettings(writeAccess = false)
        val utility = FakeUtilityPreferences()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.80"
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )

        controller.setThemeAwareJoystickLight(true)

        assertEquals("0.80", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertNull("nothing was raised, so nothing is owed back", utility.themePercentRestore)
    }

    /** Owning it means giving it back: switching the theme light off restores what Mimir raised. */
    @Test
    fun switchingTheThemeLightOffGivesTheBrightnessBack() {
        val settings = vendorLightSettings(writeAccess = false)
        val utility = FakeUtilityPreferences()
        val privileged = FakePrivilegedUtilityGateway.ready(settings.values)
        privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT] = "0.0"
        val controller = controller(
            settings = settings,
            identity = retroidIdentity(),
            utility = utility,
            privileged = privileged,
            themeColor = { 0xff224466.toInt() },
        )
        controller.setThemeAwareJoystickLight(true)
        assertEquals(
            BrightnessMath.joystickPercentValue(ThemeAwareJoystickLightFloor),
            privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT],
        )

        controller.setThemeAwareJoystickLight(false)

        assertEquals("0.0", privileged.values[BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT])
        assertNull(utility.themePercentRestore)
    }
}

private class FakeBrightnessPreferences(
    val values: MutableMap<BrightnessTarget, Float> = mutableMapOf(),
) : WindowBrightnessPreferenceStore {
    override fun read(target: BrightnessTarget): Float? = values[target]
    override fun write(target: BrightnessTarget, fraction: Float) {
        values[target] = fraction
    }

    override fun clear(target: BrightnessTarget) {
        values.remove(target)
    }
}

private class FakeWindowBrightnessSink : WindowBrightnessSink {
    var primary: Float? = null
    var secondary: Float? = null

    override fun applyPrimary(fraction: Float?) {
        primary = fraction
    }

    override fun applySecondary(fraction: Float?) {
        secondary = fraction
    }
}

private class FakeUtilityPreferences : UtilityPreferenceStore {
    var themeAware = false
    var restorePending = false
    var manualColorValue: String? = null
    var idleJoystickRestore: IdleJoystickLightRestore? = null
    var themePercentRestore: String? = null
    var idleSystemBrightnessRestore: String? = null
    var idleSecondaryBrightnessRestore: String? = null

    override fun readIdleSecondaryBrightnessRestore(): String? = idleSecondaryBrightnessRestore
    override fun beginIdleSecondaryBrightnessRestore(value: String): Boolean {
        idleSecondaryBrightnessRestore = value
        return true
    }

    override fun clearIdleSecondaryBrightnessRestore(): Boolean {
        idleSecondaryBrightnessRestore = null
        return true
    }

    override fun readIdleSystemBrightnessRestore(): String? = idleSystemBrightnessRestore
    override fun beginIdleSystemBrightnessRestore(value: String): Boolean {
        idleSystemBrightnessRestore = value
        return true
    }

    override fun clearIdleSystemBrightnessRestore(): Boolean {
        idleSystemBrightnessRestore = null
        return true
    }

    override fun readThemeAwareJoystickPercentRestore(): String? = themePercentRestore
    override fun beginThemeAwareJoystickPercentRestore(percent: String): Boolean {
        themePercentRestore = percent
        return true
    }

    override fun clearThemeAwareJoystickPercentRestore(): Boolean {
        themePercentRestore = null
        return true
    }

    override fun isThemeAwareJoystickLight(): Boolean = themeAware
    override fun setThemeAwareJoystickLight(enabled: Boolean) {
        themeAware = enabled
    }

    override fun readManualJoystickColor(): String? = manualColorValue
    override fun writeManualJoystickColor(value: String) {
        manualColorValue = value
    }

    override fun isThemeAwareJoystickLightRestorePending(): Boolean = restorePending
    override fun setThemeAwareJoystickLightRestorePending(pending: Boolean) {
        restorePending = pending
    }
    override fun beginThemeAwareJoystickLightRestore(manualColor: String) {
        manualColorValue = manualColor
        themeAware = false
        restorePending = true
    }
    override fun commitThemeAwareJoystickLightEnabled() {
        themeAware = true
        restorePending = false
    }
    override fun readIdleJoystickLightRestore(): IdleJoystickLightRestore? = idleJoystickRestore
    override fun beginIdleJoystickLightRestore(restore: IdleJoystickLightRestore): Boolean {
        idleJoystickRestore = restore
        return true
    }
    override fun clearIdleJoystickLightRestore(): Boolean {
        idleJoystickRestore = null
        return true
    }
}

private class FakeHaptics(
    var available: Boolean = true,
    var amplitudeControl: Boolean = true,
    var fraction: Float = 0.4f,
) : MimirHapticGateway {
    var previewCount = 0
    var navigationCount = 0

    override fun isAvailable(): Boolean = available
    override fun hasAmplitudeControl(): Boolean = amplitudeControl
    override fun readFraction(): Float? = if (available) fraction else null
    override fun writeFraction(fraction: Float): Boolean {
        if (!available) return false
        this.fraction = if (amplitudeControl) fraction else if (fraction <= 0f) 0f else 1f
        return true
    }

    override fun preview() {
        previewCount++
    }

    override fun navigationTick() {
        navigationCount++
    }
}

private class FakeAmbientSound(
    var available: Boolean = true,
    var fraction: Float = 0.22f,
) : AmbientSoundGateway {
    override fun readFraction(): Float? = if (available) fraction else null
    override fun writeFraction(fraction: Float): Boolean {
        if (!available) return false
        this.fraction = fraction.coerceIn(0f, 1f)
        return true
    }
}

private class FakeMediaVolume(
    var available: Boolean = true,
    var fixed: Boolean = false,
    var fraction: Float = 0.4f,
) : MediaVolumeGateway {
    override fun isAvailable(): Boolean = available
    override fun isFixedVolume(): Boolean = fixed
    override fun readFraction(): Float? = if (available) fraction else null
    override fun writeFraction(fraction: Float): Boolean {
        if (!available || fixed) return false
        this.fraction = (fraction * 15f).roundToInt() / 15f
        return true
    }

    override fun step(direction: Int): Float? {
        if (!available || fixed) return null
        val level = (fraction * 15f).roundToInt()
        fraction = (level + direction.coerceIn(-1, 1)).coerceIn(0, 15) / 15f
        return fraction
    }
}

private class FakePrivilegedUtilityGateway(
    var currentPosture: PrivilegedUtilityPosture,
    val values: MutableMap<String, String>,
    var displayBrightness: Float? = null,
) : PrivilegedUtilityGateway {
    val writeAttempts = mutableListOf<Pair<String, String>>()
    val ignoredWrites = mutableSetOf<String>()
    val scriptedWriteValues = mutableMapOf<Int, String?>()
    val secondaryWrites = mutableListOf<Triple<Int, Float, Int>>()
    var displayWriteTransform: (Float) -> Float = { it }
    /** When set, only a [setSecondaryBrightness] write for exactly this fraction fails; others succeed. */
    var secondaryWriteFailsForFraction: Float? = null
    var requestCount = 0
    var actionCount = 0
    var requestedStatus: PrivilegedUtilityStatus? = null
    var requestAccepted = true
    var vendorReadCount = 0
    var displayReadCount = 0
    var postureCount = 0

    override fun posture(): PrivilegedUtilityPosture {
        postureCount++
        return currentPosture
    }

    override fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean {
        requestCount++
        requestedStatus = expectedStatus
        if (currentPosture.status != expectedStatus) return false
        actionCount++
        return requestAccepted
    }

    override fun readVendorSetting(key: String): String? {
        vendorReadCount++
        return if (currentPosture.status == PrivilegedUtilityStatus.READY) values[key] else null
    }

    override fun writeVendorSetting(key: String, value: String): Boolean {
        val attemptIndex = writeAttempts.size
        writeAttempts += key to value
        if (currentPosture.status != PrivilegedUtilityStatus.READY) return false
        if (scriptedWriteValues.containsKey(attemptIndex)) {
            scriptedWriteValues[attemptIndex]?.let { values[key] = it }
        } else if (key !in ignoredWrites) {
            values[key] = value
        }
        return true
    }

    override fun readDisplayBrightness(displayId: Int): Float? {
        displayReadCount++
        return if (currentPosture.status == PrivilegedUtilityStatus.READY) displayBrightness else null
    }

    override fun setSecondaryBrightness(displayId: Int, fraction: Float, mirrorPercent: Int): Boolean {
        if (currentPosture.status != PrivilegedUtilityStatus.READY || displayBrightness == null) return false
        if (secondaryWriteFailsForFraction == fraction) return false
        secondaryWrites += Triple(displayId, fraction, mirrorPercent)
        displayBrightness = displayWriteTransform(fraction)
        values[BrightnessSettingsKeys.AYN_SECONDARY] = mirrorPercent.toString()
        return true
    }

    override fun close() = Unit

    companion object {
        fun ready(
            source: Map<String, String>,
            displayBrightness: Float? = null,
        ) = FakePrivilegedUtilityGateway(
            currentPosture = PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canWriteVendorSettings = true,
                canControlSecondaryBrightness = true,
            ),
            values = source.toMutableMap(),
            displayBrightness = displayBrightness,
        )
    }
}
