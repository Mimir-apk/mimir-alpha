package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessCapabilityTest {
    @Test
    fun androidMainBrightnessKeepsItsOwnZeroTo255Codec() {
        assertEquals(0.05f, BrightnessMath.systemFraction("0")!!, 0.0001f)
        assertEquals(128f / 255f, BrightnessMath.systemFraction("128")!!, 0.0001f)
        assertEquals(1f, BrightnessMath.systemFraction("255")!!, 0.0001f)
        assertEquals(13, BrightnessMath.systemLevel(0f))
        assertEquals(128, BrightnessMath.systemLevel(0.5f))
        assertNull(BrightnessMath.systemFraction("256"))
        assertNull(BrightnessMath.systemFraction("not-a-level"))
        assertTrue(BrightnessMath.isManualBrightnessMode("0"))
        assertFalse(BrightnessMath.isManualBrightnessMode("1"))
        assertFalse(BrightnessMath.isManualBrightnessMode(null))
    }

    @Test
    fun thorSecondaryUsesTheVendorZeroTo100Codec() {
        assertEquals(0.74f, BrightnessMath.vendorPercentFraction("74")!!, 0.0001f)
        assertEquals(90, BrightnessMath.vendorPercentLevel(0.9f))
        assertEquals(5, BrightnessMath.vendorPercentLevel(0f))
        assertNull(BrightnessMath.vendorPercentFraction("101"))
        assertNull(BrightnessMath.vendorPercentFraction("255"))
    }

    @Test
    fun controllerStepsStayInsideEachTargetDomain() {
        val joystickColorSteps = JoystickColorCodec.presets.lastIndex.toFloat()
        assertEquals(0.05f, BrightnessMath.step(BrightnessTarget.PRIMARY, 0.05f, -1), 0.0001f)
        assertEquals(0.55f, BrightnessMath.step(BrightnessTarget.SECONDARY, 0.5f, 1), 0.0001f)
        assertEquals(0f, BrightnessMath.step(BrightnessTarget.JOYSTICK_LIGHT, 0f, -1), 0.0001f)
        assertEquals(1f, BrightnessMath.step(BrightnessTarget.MEDIA_VOLUME, 1f, 1), 0.0001f)
        assertEquals(1f / joystickColorSteps, BrightnessMath.step(BrightnessTarget.JOYSTICK_COLOR, 0f, 1), 0.0001f)
    }

    @Test
    fun screenTimeoutUsesOnlyTheRuledSecondPresetsAndDefaultsOff() {
        assertEquals(0, DefaultScreenTimeoutSeconds)
        assertEquals(listOf(0, 15, 30, 60, 120, 300, 600), BrightnessMath.ScreenTimeoutSeconds)
        assertEquals(0, BrightnessMath.screenTimeoutSeconds(0f))
        assertEquals(600, BrightnessMath.screenTimeoutSeconds(1f))
        assertEquals(30, BrightnessMath.normalizeScreenTimeoutSeconds(28))
        assertEquals("SYSTEM", screenTimeoutLabel(0))
        assertEquals("SYSTEM", screenTimeoutLabel(1))
        assertEquals("60 seconds", screenTimeoutLabel(60))
        assertEquals(
            BrightnessMath.screenTimeoutFraction(15),
            BrightnessMath.step(BrightnessTarget.SCREEN_TIMEOUT, 0f, 1),
            0.0001f,
        )
    }

    @Test
    fun joystickPercentAcceptsObservedFractionAndDefensivePercentForms() {
        assertEquals(0.05f, BrightnessMath.joystickFraction("0.05")!!, 0.0001f)
        assertEquals(0.25f, BrightnessMath.joystickFraction("25")!!, 0.0001f)
        assertEquals("0.35", BrightnessMath.joystickPercentValue(0.35f))
        assertEquals("0", BrightnessMath.joystickPercentValue(0f))
        assertNull(BrightnessMath.joystickFraction("101"))
    }

    @Test
    fun joystickEnableShapePreservesAndVerifiesEveryVendorChannel() {
        assertTrue(BrightnessMath.joystickEnabled("1,1")!!)
        assertFalse(BrightnessMath.joystickEnabled("0,0")!!)
        assertNull(BrightnessMath.joystickEnabled("yes,no"))
        assertEquals("1,1", BrightnessMath.joystickEnabledValue("0,0", enabled = true))
        assertEquals("0,0", BrightnessMath.joystickEnabledValue("1,1", enabled = false))
        assertTrue(BrightnessMath.joystickEnabledMatches("1,1", enabled = true))
        assertFalse(BrightnessMath.joystickEnabledMatches("1,0", enabled = true))
    }

    @Test
    fun joystickColorPreservesWhiteAndTheObservedTwoChannelArgbShape() {
        val observed = "#FFFFFFFF,#ffffbf00"
        val parsed = JoystickColorCodec.parse(observed)!!

        assertEquals(0xffffffff.toInt(), parsed[0])
        assertEquals(0xffffbf00.toInt(), parsed[1])
        assertEquals(0f, JoystickColorCodec.fractionForArgb(0xffffffff.toInt()), 0.0001f)
        assertEquals(
            "#FFFF0000,#FFFF0000",
            JoystickColorCodec.value(observed, 0xffff0000.toInt()),
        )
        assertTrue(JoystickColorCodec.matches("#FFFF0000,#ffff0000", 0xffff0000.toInt()))
        assertNull(JoystickColorCodec.parse("red,blue"))
        assertNull(JoystickColorCodec.parse("#FFFF0000,#bad"))
        assertEquals(25, JoystickColorCodec.presets.size)
        assertEquals(0xffffffff.toInt(), JoystickColorCodec.presets.first())
        JoystickColorCodec.presets.drop(1).forEachIndexed { index, color ->
            assertEquals(index * 15f, JoystickColorCodec.hueFraction(color) * 360f, 0.6f)
        }
    }

    @Test
    fun vendorRowsRequireTheExactPackageAndIndependentlyValidKeys() {
        val settings = vendorLightSettings().apply {
            values[BrightnessSettingsKeys.AYN_SECONDARY] = "74"
        }

        assertEquals(
            VendorBrightnessSupport(secondarySetting = true, joystickLight = true, joystickColor = true),
            BrightnessVendorDetector.detect(aynIdentity(), settings),
        )
        assertEquals(
            VendorBrightnessSupport(secondarySetting = false, joystickLight = true, joystickColor = true),
            BrightnessVendorDetector.detect(retroidIdentity(), settings),
        )
        assertEquals(
            VendorBrightnessSupport(secondarySetting = false, joystickLight = false, joystickColor = false),
            BrightnessVendorDetector.detect(
                BrightnessDeviceIdentity("Google", "google", "generic"),
                settings,
            ),
        )
        assertEquals(
            VendorBrightnessSupport(secondarySetting = false, joystickLight = false, joystickColor = false),
            BrightnessVendorDetector.detect(
                BrightnessDeviceIdentity("AYN", "qti", "AYN Thor", vendorSettingsPackage = null),
                settings,
            ),
        )

        settings.values.remove(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        assertEquals(
            VendorBrightnessSupport(secondarySetting = true, joystickLight = true, joystickColor = false),
            BrightnessVendorDetector.detect(aynIdentity(), settings),
        )
    }

    @Test
    fun installedAppWriterAllowsOnlyThePublicMainBrightnessKey() {
        assertTrue(isSupportedInstalledAppSystemWrite(BrightnessSettingsKeys.PRIMARY))
        assertFalse(isSupportedInstalledAppSystemWrite(BrightnessSettingsKeys.AYN_SECONDARY))
        assertFalse(isSupportedInstalledAppSystemWrite(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT))
        assertFalse(isSupportedInstalledAppSystemWrite(BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED))
        assertFalse(isSupportedInstalledAppSystemWrite(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR))
    }

    @Test
    fun writeSettingsNeverMakesAynVendorSecondaryAdjustable() {
        val vendor = capabilities(
            canWrite = true,
            support = SecondaryBrightnessSupport.AYN_SYSTEM,
            secondaryWindow = true,
            light = true,
            color = true,
        )
        val genericWindow = capabilities(
            canWrite = false,
            support = SecondaryBrightnessSupport.WINDOW_ONLY,
            secondaryWindow = true,
        )

        assertTrue(vendor.hasReadOnlyVendorControls)
        assertFalse(vendor.canAdjustSecondary)
        assertTrue(genericWindow.canAdjustSecondary)
        assertFalse(genericWindow.hasReadOnlyVendorControls)
    }

    @Test
    fun vendorReadinessPromotesOnlyExactVendorContracts() {
        val ready = PrivilegedUtilityPosture(
            status = PrivilegedUtilityStatus.READY,
            canWriteVendorSettings = true,
            canControlSecondaryBrightness = true,
        )
        val vendor = capabilities(
            canWrite = false,
            support = SecondaryBrightnessSupport.AYN_SYSTEM,
            secondaryWindow = true,
            light = true,
            color = true,
            privileged = ready,
        )
        val generic = capabilities(
            canWrite = false,
            support = SecondaryBrightnessSupport.WINDOW_ONLY,
            secondaryWindow = true,
            privileged = ready,
        )

        assertTrue(vendor.canAdjustSecondary)
        assertTrue(vendor.canAdjustJoystickLight)
        assertTrue(vendor.canAdjustJoystickColor)
        assertFalse(vendor.hasReadOnlyVendorControls)
        assertTrue(generic.canAdjustSecondary)
        assertFalse(generic.hasVendorControls)
        assertFalse(generic.shouldOfferPrivilegedAccess)
    }

    @Test
    fun asynchronousVendorRoutingLeavesEveryNonVendorControlOnItsImmediatePath() {
        assertTrue(
            requiresPrivilegedUtilityTarget(
                BrightnessTarget.SECONDARY,
                SecondaryBrightnessSupport.AYN_SYSTEM,
            ),
        )
        assertTrue(
            requiresPrivilegedUtilityTarget(
                BrightnessTarget.JOYSTICK_LIGHT,
                SecondaryBrightnessSupport.UNAVAILABLE,
            ),
        )
        assertTrue(
            requiresPrivilegedUtilityTarget(
                BrightnessTarget.JOYSTICK_COLOR,
                SecondaryBrightnessSupport.UNAVAILABLE,
            ),
        )
        assertFalse(
            requiresPrivilegedUtilityTarget(
                BrightnessTarget.SECONDARY,
                SecondaryBrightnessSupport.WINDOW_ONLY,
            ),
        )
        assertFalse(
            requiresPrivilegedUtilityTarget(
                BrightnessTarget.PRIMARY,
                SecondaryBrightnessSupport.AYN_SYSTEM,
            ),
        )
        assertFalse(
            requiresPrivilegedUtilityTarget(
                BrightnessTarget.MIMIR_HAPTIC,
                SecondaryBrightnessSupport.AYN_SYSTEM,
            ),
        )
        assertFalse(
            requiresPrivilegedUtilityTarget(
                BrightnessTarget.MEDIA_VOLUME,
                SecondaryBrightnessSupport.AYN_SYSTEM,
            ),
        )
    }

    @Test
    fun queuedPrivilegedRepeatsCoalesceWithoutDroppingIntendedSteps() {
        val joystickColorSteps = JoystickColorCodec.presets.lastIndex.toFloat()
        assertEquals(
            0.7f,
            coalescedPrivilegedStepFraction(
                target = BrightnessTarget.SECONDARY,
                firstAppliedFraction = 0.55f,
                direction = 1,
                remainingSteps = 3,
            ),
            0.0001f,
        )
        assertEquals(
            2f / joystickColorSteps,
            coalescedPrivilegedStepFraction(
                target = BrightnessTarget.JOYSTICK_COLOR,
                firstAppliedFraction = 4f / joystickColorSteps,
                direction = -1,
                remainingSteps = 2,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            coalescedPrivilegedStepFraction(
                target = BrightnessTarget.JOYSTICK_LIGHT,
                firstAppliedFraction = 0.95f,
                direction = 1,
                remainingSteps = 8,
            ),
            0.0001f,
        )
    }

    @Test
    fun everyNonReadyVendorStatusFailsClosedEvenWithStaleCapabilityFlags() {
        PrivilegedUtilityStatus.entries
            .filterNot { it == PrivilegedUtilityStatus.READY }
            .forEach { status ->
                val capabilities = capabilities(
                    canWrite = true,
                    support = SecondaryBrightnessSupport.AYN_SYSTEM,
                    secondaryWindow = true,
                    light = true,
                    color = true,
                    privileged = PrivilegedUtilityPosture(
                        status = status,
                        canWriteVendorSettings = true,
                        canControlSecondaryBrightness = true,
                    ),
                )

                assertFalse(status.name, capabilities.canAdjustSecondary)
                assertFalse(status.name, capabilities.canAdjustJoystickLight)
                assertFalse(status.name, capabilities.canAdjustJoystickColor)
            }
    }

    @Test
    fun appResolverAcceptsOnlyOneLivePresentationNonDefaultDisplay() {
        val internal = SecondaryDisplayCandidate(4, isPresentation = true, isValid = true)
        val secondPresentation = SecondaryDisplayCandidate(9, isPresentation = true, isValid = true)

        assertEquals(4, selectSoleSecondaryPresentationDisplayId(listOf(internal)))
        assertNull(selectSoleSecondaryPresentationDisplayId(listOf(internal, secondPresentation)))
        assertEquals(9, selectSoleSecondaryPresentationDisplayId(listOf(secondPresentation)))
        assertNull(
            selectSoleSecondaryPresentationDisplayId(
                listOf(internal, SecondaryDisplayCandidate(7, isPresentation = true, isValid = true)),
            ),
        )
        assertNull(
            selectSoleSecondaryPresentationDisplayId(
                listOf(SecondaryDisplayCandidate(4, isPresentation = true, isValid = false)),
            ),
        )
        assertNull(
            selectSoleSecondaryPresentationDisplayId(
                listOf(SecondaryDisplayCandidate(4, isPresentation = false, isValid = true)),
            ),
        )
    }

    private fun capabilities(
        canWrite: Boolean,
        support: SecondaryBrightnessSupport,
        secondaryWindow: Boolean,
        light: Boolean = false,
        color: Boolean = false,
        privileged: PrivilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported,
    ) = BrightnessCapabilities(
        canWriteSystemSettings = canWrite,
        mainSystemBrightnessActive = canWrite,
        secondaryWindowAvailable = secondaryWindow,
        secondarySupport = support,
        joystickLightSupported = light,
        joystickLightColorSupported = color,
        mimirHapticsSupported = false,
        mimirHapticAmplitudeControl = false,
        mediaVolumeSupported = false,
        mediaVolumeFixed = false,
        privilegedUtilityPosture = privileged,
    )
}

internal fun aynIdentity() = BrightnessDeviceIdentity(
    manufacturer = "AYN",
    brand = "qti",
    model = "AYN Thor",
    vendorSettingsPackage = VendorUtilityPackages.AYN_SETTINGS,
)

internal fun retroidIdentity() = BrightnessDeviceIdentity(
    manufacturer = "Moorechip",
    brand = "qti",
    model = "Retroid Pocket 5",
    vendorSettingsPackage = VendorUtilityPackages.RETROID_SETTINGS,
)

internal fun vendorLightSettings(writeAccess: Boolean = false) = FakeBrightnessSettings(
    values = mutableMapOf(
        BrightnessSettingsKeys.PRIMARY to "128",
        BrightnessSettingsKeys.PRIMARY_MODE to "0",
        BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT to "0.05",
        BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED to "1,1",
        BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR to "#FFFFFFFF,#FFFFFFFF",
    ),
    writeAccess = writeAccess,
)

internal class FakeBrightnessSettings(
    val values: MutableMap<String, String> = mutableMapOf(),
    var writeAccess: Boolean = false,
    val failedKeys: MutableSet<String> = mutableSetOf(),
    var writeTransform: (String, String) -> String = { _, value -> value },
) : BrightnessSettingsGateway {
    val writes = mutableListOf<Pair<String, String>>()
    val attempts = mutableListOf<Pair<String, String>>()

    override fun canWrite(): Boolean = writeAccess
    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String): Boolean {
        attempts += key to value
        if (!writeAccess || key in failedKeys) return false
        writes += key to value
        values[key] = writeTransform(key, value)
        return true
    }

    override fun permissionRequest(): BrightnessPermissionRequest = BrightnessPermissionRequest(
        action = "android.settings.action.MANAGE_WRITE_SETTINGS",
        dataUri = "package:com.gruning.mimir.rewrite",
    )
}
