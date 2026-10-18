package com.gruning.mimir.tweaker

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A device with no matching signed manifest re-decoded and re-verified one on EVERY `capabilities()`
 * call, because a negative resolution was deliberately never cached. On the Nova that was a
 * base64 decode, a payload decode, a fingerprint comparison and two logcat warnings, about once a
 * second, forever, for an answer that could not change — 294 warnings in one 8000-line buffer.
 *
 * The reason it was not cached is real and is preserved here, not discarded: AYN/Retroid firmware
 * can publish PServerBinder *after* the direct-boot service starts, so a negative genuinely can be
 * transient. The fix is to key the cache on what makes it transient rather than to refuse to cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ProductQualificationNegativeCacheTest {
    private val logTag = "MimirQualification"

    @Test
    fun `an unmatched manifest is decoded once, not once per call`() {
        ShadowLog.clear()
        val resolver = resolver()
        val caps = novaCapabilities()

        repeat(20) { resolver.resolve(caps) }

        val declines = ShadowLog.getLogs().count { it.tag == logTag }
        assertTrue(
            "20 identical resolves logged $declines times; the negative is not being cached",
            declines <= 4,
        )
    }

    @Test
    fun `the late-PServer transient still re-resolves — the whole reason a negative was never cached`() {
        ShadowLog.clear()
        val resolver = resolver()
        // Direct boot: PServerBinder has not been published yet.
        resolver.resolve(novaCapabilities(pServerAvailable = false))
        val afterFirst = ShadowLog.getLogs().count { it.tag == logTag }

        // Firmware publishes it. This MUST NOT be served from the negative cached a moment ago.
        resolver.resolve(novaCapabilities(pServerAvailable = true))
        val afterFlip = ShadowLog.getLogs().count { it.tag == logTag }

        assertTrue(
            "a PServerBinder flip must miss the cache and resolve again, not serve the stale negative",
            afterFlip > afterFirst,
        )
    }

    @Test
    fun `a status change also misses, so an UNSUPPORTED probe cannot pin a later answer`() {
        ShadowLog.clear()
        val resolver = resolver()
        resolver.resolve(novaCapabilities(status = CapabilityStatus.UNSUPPORTED))
        val afterFirst = ShadowLog.getLogs().count { it.tag == logTag }
        resolver.resolve(novaCapabilities(status = CapabilityStatus.CALIBRATION_REQUIRED))
        assertTrue(ShadowLog.getLogs().count { it.tag == logTag } > afterFirst)
    }

    @Test
    fun `different hardware still resolves independently`() {
        ShadowLog.clear()
        val resolver = resolver()
        resolver.resolve(novaCapabilities())
        val afterFirst = ShadowLog.getLogs().count { it.tag == logTag }
        resolver.resolve(novaCapabilities(socModel = "CQ8725S"))
        assertTrue(ShadowLog.getLogs().count { it.tag == logTag } > afterFirst)
    }

    @Test
    fun `the cached negative is the same answer, not merely a quieter one`() {
        val resolver = resolver()
        val caps = novaCapabilities()
        val first = resolver.resolve(caps)
        val second = resolver.resolve(caps)
        assertEquals(first.available, second.available)
        assertTrue("an unmatched manifest must stay unavailable", !second.available)
    }

    /**
     * Real trust material carrying a manifest for a DIFFERENT device — exactly the Nova's real
     * situation, where the installed APK ships the Odin's sequence-11 manifest and every resolve
     * dies on `CANDIDATE_FINGERPRINT_MISMATCH`.
     *
     * The first draft of this fixture passed empty base64 for both, which returns at the
     * `TRUST_MATERIAL_EMPTY` guard **before** the cache is ever consulted — so the test failed
     * against a correct fix. Kept as a note because the failure was informative: the cache covers
     * the expensive candidate-decode path, not the trust-material guards above it, and a fixture
     * that never reaches the decode cannot pin anything about it.
     */
    /**
     * THE POISONING CASE, in the order that actually poisons.
     *
     * `QualificationPlanPolicy.allows` reads `gpuNodeAvailable` (`QualificationManifest.kt:907`),
     * a LIVE per-probe sysfs read (`AndroidHardware.kt:637`) that is in neither
     * [HardwareFingerprint] nor the old `(fingerprint, status, pServerAvailable)` key.
     *
     * **Order matters.** Resolving healthy-then-degraded proves
     * nothing: the healthy positive is already cached, the degraded probe HITS that cache, `allows`
     * never runs, and the test passes against the broken key. The poisoning order is the real
     * device order — a COLD cache at daemon start, where the very first probe is the one that
     * catches the GPU node not yet ready. That negative is then cached under a key the healthy
     * probe shares, and the device is stock-only until the process dies.
     */
    @Test
    fun `a degraded first probe must not poison every later healthy one`() {
        val matching = matchingResolver(keyPair())
        val healthy = novaCapabilities()

        // Cold cache. First probe of the boot catches the GPU node absent.
        val degraded = healthy.copy(gpuNodeAvailable = false, supportedAxes = setOf(TuningAxis.CPU))
        assertTrue("the degraded probe must resolve NEGATIVE or this pins nothing", !matching.resolve(degraded).available)

        // The node appears. Same fingerprint, same status, same pServerAvailable — so a narrow key
        // serves the poisoned negative forever.
        assertTrue(
            "a healthy probe must re-resolve, not inherit the cold-start negative",
            matching.resolve(healthy).available,
        )
    }

    @Test
    fun `a degraded fan axis on the first probe also does not poison`() {
        val matching = matchingResolver(keyPair())
        val healthy = novaCapabilities()
        val degraded = healthy.copy(fanNodeAvailable = false, supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU))
        matching.resolve(degraded)
        assertTrue(matching.resolve(healthy).available)
    }

    @Test
    fun `a policy frequency bound moving does not poison, and it is not in the fingerprint either`() {
        // minKhz/maxKhz feed allows() at QualificationManifest.kt:904 and are absent from
        // HardwareFingerprint, which takes only id/cpuIds/availableFrequenciesKhz
        // (CalibrationModels.kt:463-467).
        val matching = matchingResolver(keyPair())
        val healthy = novaCapabilities()
        val moved = healthy.copy(cpuPolicies = healthy.cpuPolicies.map { it.copy(maxKhz = 1) })
        matching.resolve(moved)
        assertTrue(matching.resolve(healthy).available)
    }

    @Test
    fun `the live currentKhz still hits, or the cache would be pure overhead`() {
        val matching = matchingResolver(keyPair())
        val healthy = novaCapabilities()
        assertTrue(matching.resolve(healthy).available)
        ShadowLog.clear()
        // probe() refreshes currentKhz from sysfs on EVERY call (AndroidHardware.kt:620). If that
        // were in the key the cache would miss every time and cost more than it saves.
        repeat(10) { i ->
            matching.resolve(healthy.copy(cpuPolicies = healthy.cpuPolicies.map { it.copy(currentKhz = 300_000 + i) }))
        }
        assertEquals("a moved currentKhz must not miss the cache", 0, ShadowLog.getLogs().count { it.tag == logTag })
    }

    /** A resolver whose manifest genuinely matches this device, so resolution succeeds. */
    private fun matchingResolver(keyPair: KeyPair): ProductQualificationResolver {
        val caps = novaCapabilities()
        val signed = signedManifest(
            keyPair = keyPair,
            hardwareFingerprint = HardwareFingerprint.create(caps),
            deviceProfile = caps.deviceProfileId,
            sequence = 1,
            // A real fan mode, deliberately. With `fanMode = null` the FAN leg of
            // QualificationPlanPolicy.allows (:912-919) accepts on BOTH branches, so dropping the
            // fan axis would reject nothing and the fan poisoning test would be vacuous — it
            // passed against the broken key exactly that way on the first attempt.
            fanMode = TuningPlanner.FAN_SMART,
        )
        return BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            manifestsBase64 = Base64.getEncoder().encodeToString(signed),
        )
    }

    private fun resolver(): ProductQualificationResolver {
        val keyPair = keyPair()
        val signed = signedManifestForAnotherDevice(keyPair)
        return BuildInjectedProductQualificationResolver(
            context = RuntimeEnvironment.getApplication(),
            signedManifestRequired = true,
            trustAnchorsBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            manifestsBase64 = Base64.getEncoder().encodeToString(signed),
        )
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun signedManifestForAnotherDevice(keyPair: KeyPair): ByteArray = signedManifest(
        keyPair = keyPair,
        hardwareFingerprint = "f".repeat(64),
        deviceProfile = "ayn-odin3-cq8725s-pserver",
        sequence = 11,
    )

    private fun signedManifest(
        keyPair: KeyPair,
        hardwareFingerprint: String,
        deviceProfile: String,
        sequence: Long,
        fanMode: Int? = null,
    ): ByteArray {
        val profile = QualifiedProfilePlan.create(
            profileId = "quiet",
            cpuMaxKhz = mapOf(0 to 1_000),
            gpuMaxPowerLevel = 1,
            fanMode = fanMode,
            evidenceIndexSha256 = "a".repeat(64),
            guardianRollbackEvidenceSha256 = "b".repeat(64),
        )
        val manifest = QualificationManifest(
            keyId = qualificationKeyId(keyPair.public.encoded),
            sequence = sequence,
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

    private fun novaCapabilities(
        pServerAvailable: Boolean = true,
        status: CapabilityStatus = CapabilityStatus.CALIBRATION_REQUIRED,
        socModel: String = "SM8550",
    ) = HardwareCapabilities(
        status = status,
        manufacturer = "Retroid",
        model = "Retroid Pocket Nova",
        socModel = socModel,
        buildFingerprint = "kalama/test",
        deviceProfileId = "retroid-qcs8550-pserver",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = pServerAvailable,
        cpuPolicies = listOf(
            CpuPolicy(0, "/policy0", listOf(0), listOf(1_000, 2_000), 1_000, 2_000, 2_000),
        ),
        gpuFrequenciesKhz = listOf(300, 600),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )
}
