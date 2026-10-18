package com.gruning.mimir.rewrite

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded Odin 3 proof.
 *
 * The wrapper delegates attestation and reads to the physical PServer Binder but rejects every
 * mutation. A passing same-value write therefore proves the production no-op path without changing
 * device state.
 */
@RunWith(AndroidJUnit4::class)
class CertifiedVendorOdinDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exactProductConsentProjectionNeverCollapsesToUnavailableDevice() {
        assumeExactOdin3()
        val identity = BrightnessDeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            vendorSettingsPackage = VendorUtilityPackages.AYN_SETTINGS,
        )
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = context,
            identity = identity,
        )

        val status = gateway.systemRecentsAccessStatus()
        Log.i(ProductProofTag, "ANDROID_SWITCHING_PRODUCT_STATUS status=$status")
        assertTrue(
            "Exact admitted Odin must expose either consent or ready, not device unavailable: $status",
            status == PrivilegedUtilityStatus.PERMISSION_REQUIRED ||
                status == PrivilegedUtilityStatus.READY,
        )
        gateway.close()
    }

    @Test
    fun exactContractOpensSystemRecentsThroughBuiltInAuthority() {
        assumeExactOdin3()
        val identity = BrightnessDeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            vendorSettingsPackage = VendorUtilityPackages.AYN_SETTINGS,
        )
        val transport = AndroidPServerTransport()
        val profile = CertifiedVendorAdmissionPolicy.admit(
            currentCertifiedVendorEvidence(context, identity, transport),
        )
        assertNotNull(profile)
        val vendorGateway = AndroidCertifiedVendorPrivilegeGateway(
            context = context,
            identity = identity,
            transport = transport,
            consent = GrantedOdinConsentStore(requireNotNull(profile).consentKey),
            evidenceReader = { currentCertifiedVendorEvidence(context, identity, transport) },
        )
        // The Shizuku fallback this test once proved untouched is gone (no Shizuku route): the built-in authority is the
        // only one.
        val gateway = CertifiedPrivilegeGateway(
            vendor = vendorGateway,
            vendorRecents = vendorGateway,
        )

        assertEquals(PrivilegedUtilityStatus.READY, gateway.systemRecentsAccessStatus())
        assertTrue(gateway.openSystemRecents())
        gateway.close()
    }

    @Test
    fun exactContractAttestsAndSameValueNeedsNoMutation() {
        assumeExactOdin3()
        assertEquals("ayn", Build.MANUFACTURER.lowercase())
        assertEquals("Odin3", Build.MODEL)
        assertEquals("CQ8725S", Build.SOC_MODEL)

        val identity = BrightnessDeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            vendorSettingsPackage = VendorUtilityPackages.AYN_SETTINGS,
        )
        val transport = ReadOnlyCountingVendorTransport(AndroidPServerTransport())
        val evidence = currentCertifiedVendorEvidence(context, identity, transport)
        assertEquals(
            "qti/sun/sun:15/AQ3A.250728.001/eng.Odin3.20260616.191954:user/dev-keys",
            evidence.buildFingerprint,
        )
        assertEquals(setOf(Odin3Signer), evidence.vendorPackageSignerSha256)
        assertEquals(Odin3PServerSha, evidence.serviceAttestation?.executableSha256)
        val profile = CertifiedVendorAdmissionPolicy.admit(evidence)
        assertNotNull(profile)

        // built-in access is structural only -- no consent key, no
        // fingerprint pin, no signed qualification. DeniedOdinConsentStore is passed only because
        // the constructor still accepts a consent seam; posture() never reads it, so a denied
        // store must admit exactly like a granted one.
        val deniedConsentGateway = AndroidCertifiedVendorPrivilegeGateway(
            context = context,
            identity = identity,
            transport = transport,
            consent = DeniedOdinConsentStore,
            evidenceReader = { currentCertifiedVendorEvidence(context, identity, transport) },
        )
        assertEquals(PrivilegedUtilityStatus.READY, deniedConsentGateway.posture().status)
        assertEquals(
            PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            deniedConsentGateway.posture().authority,
        )

        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = context,
            identity = identity,
            transport = transport,
            consent = GrantedOdinConsentStore(requireNotNull(profile).consentKey),
            evidenceReader = { currentCertifiedVendorEvidence(context, identity, transport) },
        )
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, gateway.posture().authority)

        val settled = listOf(
            BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT,
            BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED,
            BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR,
        ).firstNotNullOfOrNull { key ->
            gateway.readVendorSetting(key)?.let { value -> key to value }
        }
        assertNotNull("Odin exposed no normalized fixed-contract setting", settled)
        val (key, value) = requireNotNull(settled)
        assertTrue(gateway.writeVendorSetting(key, value))
        assertEquals(0, transport.mutationAttempts)
        assertEquals(value, gateway.readVendorSetting(key))
        gateway.close()
    }

    private fun assumeExactOdin3() {
        assumeTrue(
            "Certified vendor device proof is Odin 3 only",
            Build.MANUFACTURER.equals("ayn", ignoreCase = true) &&
                Build.MODEL == "Odin3" &&
                Build.SOC_MODEL == "CQ8725S",
        )
    }

    private companion object {
        const val Odin3Signer =
            "9e1339d601b394a0a2a4d21712ab2754011e7a85a2590e2180bf9d7ff1a7b5f6"
        const val Odin3PServerSha =
            "e1a4168f6045ce2c89a49bdc82cdcb8e2c9f6816308235a1c1c43d55bf5ed443"
        const val ProductProofTag = "MimirVendorProof"
    }
}

private data object DeniedOdinConsentStore : CertifiedVendorConsentStore {
    override fun isGranted(consentKey: String): Boolean = false
    override fun grant(consentKey: String) = Unit
}

private class GrantedOdinConsentStore(
    private val consentKey: String,
) : CertifiedVendorConsentStore {
    override fun isGranted(consentKey: String): Boolean = consentKey == this.consentKey
    override fun grant(consentKey: String) = Unit
}

private class ReadOnlyCountingVendorTransport(
    private val delegate: CertifiedVendorTransport,
) : CertifiedVendorTransport {
    var mutationAttempts: Int = 0
        private set

    override fun isAlive(): Boolean = delegate.isAlive()
    override fun attest(): CertifiedVendorServiceAttestation? = delegate.attest()
    override fun readSystemSetting(key: String): VendorTransportRead =
        delegate.readSystemSetting(key)

    override fun writeSystemSetting(
        key: String,
        normalizedValue: String,
    ): VendorTransportMutation {
        mutationAttempts += 1
        return VendorTransportMutation.FAILED
    }

    override fun deleteSystemSetting(key: String): VendorTransportMutation {
        mutationAttempts += 1
        return VendorTransportMutation.FAILED
    }

    override fun openSystemRecents(): VendorTransportMutation {
        mutationAttempts += 1
        return VendorTransportMutation.FAILED
    }

    override fun forceStopAllowedPackage(packageName: String): VendorTransportMutation {
        mutationAttempts += 1
        return VendorTransportMutation.FAILED
    }

    override fun close() = delegate.close()
}
