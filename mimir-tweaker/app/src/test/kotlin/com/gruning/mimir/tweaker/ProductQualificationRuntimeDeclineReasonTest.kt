package com.gruning.mimir.tweaker

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * TR-02: [BuildInjectedProductQualificationResolver.resolve] used to return
 * [ProductQualificationResolution.Unavailable] from six sites with zero output. This pins that
 * every one of those sites (plus the two additional malformed-candidate sites found while
 * reviewing the malformed-candidate paths) now logs its own distinct
 * [ProductQualificationDeclineReason] at the `MimirQualification` tag, the same tag the existing
 * per-candidate verifier-rejection log already uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ProductQualificationRuntimeDeclineReasonTest {
    private val logTag = "MimirQualification"

    @Test
    fun `compile-time posture not requiring a signed manifest logs its own reason`() {
        ShadowLog.clear()
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = false,
            trustAnchorsBase64 = "",
            manifestsBase64 = "",
        )

        resolver.resolve(capabilities())

        assertReasonLogged(ProductQualificationDeclineReason.SIGNED_MANIFEST_NOT_REQUIRED)
    }

    @Test
    fun `unparseable trust anchor bundle logs its own reason`() {
        ShadowLog.clear()
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = "not-base64",
            manifestsBase64 = "",
        )

        resolver.resolve(capabilities())

        assertReasonLogged(ProductQualificationDeclineReason.TRUST_ANCHOR_UNPARSEABLE)
    }

    @Test
    fun `unparseable manifests bundle logs its own reason`() {
        ShadowLog.clear()
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = "",
            manifestsBase64 = "not-base64",
        )

        resolver.resolve(capabilities())

        assertReasonLogged(ProductQualificationDeclineReason.MANIFESTS_UNPARSEABLE)
    }

    @Test
    fun `empty trust material logs its own reason distinct from unparseable`() {
        ShadowLog.clear()
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = "",
            manifestsBase64 = "",
        )

        resolver.resolve(capabilities())

        assertReasonLogged(ProductQualificationDeclineReason.TRUST_MATERIAL_EMPTY)
    }

    @Test
    fun `candidate matching device profile but not hardware fingerprint logs fingerprint mismatch`() {
        ShadowLog.clear()
        val capabilities = capabilities()
        val keyPair = keyPair()
        val signed = signedManifest(
            capabilities = capabilities,
            keyPair = keyPair,
            hardwareFingerprint = "f".repeat(64),
            deviceProfile = capabilities.deviceProfileId,
        )
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            manifestsBase64 = Base64.getEncoder().encodeToString(signed),
        )

        resolver.resolve(capabilities)

        // A single, entirely-mismatched candidate necessarily also leaves zero candidates for
        // verify() -- both the specific mismatch and the aggregate "nothing survived" fire.
        assertReasonLogged(ProductQualificationDeclineReason.CANDIDATE_FINGERPRINT_MISMATCH)
        assertReasonLogged(ProductQualificationDeclineReason.NO_CANDIDATE_SURVIVED)
    }

    @Test
    fun `candidate matching hardware fingerprint but not device profile logs profile mismatch`() {
        ShadowLog.clear()
        val capabilities = capabilities()
        val keyPair = keyPair()
        val signed = signedManifest(
            capabilities = capabilities,
            keyPair = keyPair,
            hardwareFingerprint = HardwareFingerprint.create(capabilities),
            deviceProfile = "some-other-device-profile",
        )
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            manifestsBase64 = Base64.getEncoder().encodeToString(signed),
        )

        resolver.resolve(capabilities)

        assertReasonLogged(ProductQualificationDeclineReason.CANDIDATE_DEVICE_PROFILE_MISMATCH)
        // The fingerprint half of the OLD combined check must NOT also fire -- the two mismatches
        // stay distinguishable, not just distinct in name.
        assertReasonNotLogged(ProductQualificationDeclineReason.CANDIDATE_FINGERPRINT_MISMATCH)
    }

    @Test
    fun `a malformed candidate manifest logs its own reason and still reaches no candidate survived`() {
        ShadowLog.clear()
        val keyPair = keyPair()
        val garbage = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val resolver = BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            manifestsBase64 = garbage,
        )

        resolver.resolve(capabilities())

        assertReasonLogged(ProductQualificationDeclineReason.CANDIDATE_SIGNED_MANIFEST_MALFORMED)
        assertReasonLogged(ProductQualificationDeclineReason.NO_CANDIDATE_SURVIVED)
    }

    private fun assertReasonLogged(reason: String) {
        val matches = ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains("reason=$reason") }
        assertTrue("expected a $logTag log containing reason=$reason, got: ${ShadowLog.getLogs()}", matches)
    }

    private fun assertReasonNotLogged(reason: String) {
        val matches = ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains("reason=$reason") }
        assertTrue("did not expect a $logTag log containing reason=$reason, got: ${ShadowLog.getLogs()}", !matches)
    }

    private fun signedManifest(
        capabilities: HardwareCapabilities,
        keyPair: KeyPair,
        hardwareFingerprint: String,
        deviceProfile: String,
    ): ByteArray {
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
            hardwareFingerprint = hardwareFingerprint,
            deviceProfile = deviceProfile,
            profiles = listOf(profile),
        )
        val payload = QualificationManifestCodec.encodePayload(manifest)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return QualificationManifestCodec.encodeSigned(SignedQualificationManifest(payload, signature))
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

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
