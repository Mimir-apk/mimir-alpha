package com.gruning.mimir.tweaker

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProductQualificationRuntimeTest {
    @Test
    fun `public build input derives its own key id and rejects malformed bundles atomically`() {
        val publicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
        val encoded = Base64.getEncoder().encodeToString(publicKey)
        val anchor = ProductQualificationBuildInputs.trustAnchor(encoded)

        assertNotNull(anchor)
        assertNotNull(anchor!!.key(qualificationKeyId(publicKey)))
        assertTrue(ProductQualificationBuildInputs.trustAnchor("")!!.isEmpty())
        assertNull(ProductQualificationBuildInputs.trustAnchor("$encoded,"))
        assertNull(ProductQualificationBuildInputs.trustAnchor(" $encoded"))
        assertNull(ProductQualificationBuildInputs.trustAnchor("not-base64"))
    }

    @Test
    fun `signed manifest bundle rejects whitespace empty fields and invalid base64`() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val encoded = listOf(first, second).joinToString(",") {
            Base64.getEncoder().encodeToString(it)
        }

        val decoded = ProductQualificationBuildInputs.signedManifests(encoded)
        assertTrue(decoded != null && decoded[0].contentEquals(first) && decoded[1].contentEquals(second))
        assertTrue(ProductQualificationBuildInputs.signedManifests("")!!.isEmpty())
        assertNull(ProductQualificationBuildInputs.signedManifests("$encoded,"))
        assertNull(ProductQualificationBuildInputs.signedManifests(encoded.replace(",", ",\n")))
        assertNull(ProductQualificationBuildInputs.signedManifests("%%%"))
    }

    @Test
    fun `qualification follows PServer loss and recovery for the same fingerprint`() {
        val capabilities = capabilities()
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val profile = QualifiedProfilePlan.create(
            profileId = "quiet",
            cpuMaxKhz = mapOf(0 to 1_000),
            gpuMaxPowerLevel = 1,
            fanMode = null,
            evidenceIndexSha256 = "a".repeat(64),
            guardianRollbackEvidenceSha256 = "b".repeat(64),
        )
        val manifest = QualificationManifest(
            keyId = qualificationKeyId(keyPair.public.encoded),
            sequence = 1,
            hardwareFingerprint = HardwareFingerprint.create(capabilities),
            deviceProfile = capabilities.deviceProfileId,
            profiles = listOf(profile),
        )
        val payload = QualificationManifestCodec.encodePayload(manifest)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        val signed = QualificationManifestCodec.encodeSigned(
            SignedQualificationManifest(payload, signature),
        )
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            manifestsBase64 = Base64.getEncoder().encodeToString(signed),
            sequenceStore = QualificationSequenceStore { _, _ ->
                QualificationSequenceAdmission.ACCEPTED_NEW
            },
        )

        val bootRace = capabilities.copy(
            status = CapabilityStatus.UNSUPPORTED,
            pServerAvailable = false,
        )
        assertFalse(resolver.resolve(bootRace).available)
        assertTrue(resolver.resolve(capabilities).available)
        assertFalse(resolver.resolve(bootRace).available)
        assertTrue(resolver.resolve(capabilities).available)
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "AYN",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "odin/test",
        deviceProfileId = "ayn-odin3-cq8725s-pserver",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/policy0", listOf(0), listOf(1_000, 2_000), 1_000, 2_000, 2_000),
        ),
        gpuFrequenciesKhz = listOf(300, 600),
        gpuNodeAvailable = true,
        fanNodeAvailable = false,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )
}
