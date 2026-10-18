package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Admission uses SoC family and structural attestation rather than a per-model device list.
 * Unlisted devices with the required PServer/vendor access may qualify.
 *
 * The suite is deliberately organised as two halves that have to hold together: what the policy
 * **opens** (unseen devices qualify) and what it **does not** open (every structural
 * axis still fails closed). A change that satisfies only the first half is the bug this file exists
 * to catch.
 */
class CertifiedVendorAdmissionPolicyTest {
    @Test
    fun `classifies every supported Qualcomm family alias`() {
        assertEquals(
            CertifiedQualcommFamily.SNAPDRAGON_865,
            CertifiedVendorAdmissionPolicy.family("SM8250"),
        )
        assertEquals(
            CertifiedQualcommFamily.SNAPDRAGON_8_GEN_2,
            CertifiedVendorAdmissionPolicy.family("SM8550"),
        )
        assertEquals(
            CertifiedQualcommFamily.SNAPDRAGON_8_GEN_2,
            CertifiedVendorAdmissionPolicy.family("QCS8550"),
        )
        assertEquals(
            CertifiedQualcommFamily.SNAPDRAGON_8_ELITE,
            CertifiedVendorAdmissionPolicy.family("SM8750"),
        )
        assertEquals(
            CertifiedQualcommFamily.SNAPDRAGON_8_ELITE,
            CertifiedVendorAdmissionPolicy.family("CQ8725S"),
        )
        assertEquals(
            CertifiedQualcommFamily.SNAPDRAGON_8_ELITE,
            CertifiedVendorAdmissionPolicy.family("QCS8750"),
        )
    }

    // ---------------------------------------------------------------- what the ruling opened

    @Test
    fun `every grounded handheld is admitted with no per-device row`() {
        // RP5 -- Snapdragon 865.
        val rp5 = requireNotNull(CertifiedVendorAdmissionPolicy.admit(evidence()))
        assertEquals(CertifiedHandheldVendor.RETROID, rp5.vendor)
        assertEquals(CertifiedQualcommFamily.SNAPDRAGON_865, rp5.family)

        // Odin 3 -- device test device, fingerprint and digest measured 2026-08-22.
        val odin3 = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                evidence(
                    manufacturer = "ayn",
                    brand = "qti",
                    model = "Odin3",
                    soc = "CQ8725S",
                    packageName = VendorUtilityPackages.AYN_SETTINGS,
                    fingerprint =
                        "qti/sun/sun:15/AQ3A.250728.001/eng.Odin3.20260616.191954:user/dev-keys",
                    attestation = testAttestation(executableSha256 = Odin3PServerSha),
                ),
            ),
        )
        assertEquals(CertifiedHandheldVendor.AYN, odin3.vendor)
        assertEquals(CertifiedQualcommFamily.SNAPDRAGON_8_ELITE, odin3.family)

        // RC-14. The Retroid Pocket Nova -- device test unit, fingerprint and digest measured
        // 2026-08-22. It has com.rp.settings, /system/bin/pservice and a registered PServerBinder;
        // it was refused only because no RETROID x SNAPDRAGON_8_GEN_2 contract row existed, which
        // sent it down the Shizuku path. This assertion IS RC-14's resolution, and it is satisfied
        // without adding a row for the Nova anywhere.
        val nova = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                evidence(
                    manufacturer = "Moorechip",
                    brand = "qti",
                    model = "Retroid Pocket Nova",
                    soc = "QCS8550",
                    packageName = VendorUtilityPackages.RETROID_SETTINGS,
                    fingerprint =
                        "qti/kalama/kalama:13/TKQ1.231222.001/eng.RPN.20260722.081626:user/release-keys",
                    attestation = testAttestation(executableSha256 = NovaPServerSha),
                ),
            ),
        )
        assertEquals(CertifiedHandheldVendor.RETROID, nova.vendor)
        assertEquals(CertifiedQualcommFamily.SNAPDRAGON_8_GEN_2, nova.family)
    }

    @Test
    fun `a handheld from an unknown vendor on a supported SoC is admitted with no code change`() {
        // An uncharacterized device with vendor access. Unknown manufacturer, unknown brand, unknown model, no vendor
        // settings package this build has ever heard of, unknown firmware, unknown binary digest.
        val konkr = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                evidence(
                    manufacturer = "Konkr",
                    brand = "konkr",
                    model = "Pocket Fir",
                    soc = "SM8750",
                    packageName = null,
                    fingerprint = "konkr/fir/fir:15/UNKNOWN.000000.000:user/release-keys",
                    attestation = testAttestation(executableSha256 = "7c".repeat(32)),
                ),
            ),
        )
        assertNull(konkr.vendor)
        assertNull(konkr.settingsPackage)
        assertEquals(CertifiedQualcommFamily.SNAPDRAGON_8_ELITE, konkr.family)
        assertTrue(konkr.profileId.startsWith(CertifiedVendorAdmissionPolicy.GenericVendorLabel))
    }

    // ------------------------------------------------------------ what the ruling did NOT open

    @Test
    fun `every structural axis of the attestation still fails closed`() {
        val baseline = evidence()
        assertNotNull(CertifiedVendorAdmissionPolicy.admit(baseline))

        // No attestation at all -- the fail-closed default. A device that never answered is never
        // admitted, which is what keeps a non-handheld out.
        assertNull(CertifiedVendorAdmissionPolicy.admit(baseline.copy(serviceAttestation = null)))

        val axes = listOf(
            "not root uid" to baseline.attestation().copy(uid = "2000"),
            "not root gid" to baseline.attestation().copy(gid = "2000"),
            "shell domain" to baseline.attestation().copy(selinuxDomain = "u:r:shell:s0"),
            "untrusted_app domain" to
                baseline.attestation().copy(selinuxDomain = "u:r:untrusted_app:s0"),
            "wrong executable" to baseline.attestation().copy(executablePath = "/system/bin/sh"),
            "wrong service name" to baseline.attestation().copy(serviceName = "SomeOtherBinder"),
            "wrong protocol id" to baseline.attestation().copy(protocolId = "pserver-v0"),
            "absent digest" to baseline.attestation().copy(executableSha256 = ""),
            "truncated digest" to baseline.attestation().copy(executableSha256 = "f44773c8"),
            "non-hex digest" to baseline.attestation().copy(executableSha256 = "g".repeat(64)),
        )
        axes.forEach { (label, broken) ->
            assertNull(
                "structural axis should have refused: $label",
                CertifiedVendorAdmissionPolicy.admit(
                    baseline.copy(serviceAttestation = broken),
                ),
            )
        }
    }

    @Test
    fun `uncharacterised silicon is admitted for access and recorded as uncharacterised`() {
        // Vendor capability grants the sealed command set, independently of Tweaker tuning. The SoC family table
        // is KEPT -- we only claim silicon we have reasoned about -- but it no longer gates access.
        // Before this change every case below returned null on the first line of admit().
        val baseline = evidence()
        listOf("SM8450", "Tensor G3", "", "   ", "MT6983").forEach { soc ->
            val admitted = CertifiedVendorAdmissionPolicy.admit(baseline.copy(socModel = soc))
            assertNotNull("Uncharacterised SoC must still get vendor access: '$soc'", admitted)
            assertNull(
                "Uncharacterised SoC must record family=null, not a guessed family: '$soc'",
                admitted?.family,
            )
            assertTrue(
                "profileId must say 'uncharacterised' out loud, since it lands in consentKey: " +
                    "'$soc' -> ${admitted?.profileId}",
                admitted?.profileId?.contains(
                    CertifiedVendorAdmissionPolicy.UncharacterisedFamilyPrefix,
                ) == true,
            )
        }
    }

    @Test
    fun `two different uncharacterised chips do not share a consent key`() {
        // Pooling every unknown SoC into one bucket would make two unseen chips indistinguishable
        // in a log line and a bug report. The SoC token is folded into profileId for that reason.
        // (Before 2026-09-11 the stake was higher -- one key meant approving one chip approved the
        // next -- but distinguishability is still the property, now for diagnostics.)
        val baseline = evidence()
        val first = requireNotNull(CertifiedVendorAdmissionPolicy.admit(baseline.copy(socModel = "SM8450")))
        val second = requireNotNull(CertifiedVendorAdmissionPolicy.admit(baseline.copy(socModel = "MT6983")))
        assertNotEquals(first.profileId, second.profileId)
        assertNotEquals(first.consentKey, second.consentKey)
        // ...and a characterised chip is never confused with an uncharacterised one.
        val known = requireNotNull(CertifiedVendorAdmissionPolicy.admit(baseline))
        assertFalse(
            known.profileId.contains(CertifiedVendorAdmissionPolicy.UncharacterisedFamilyPrefix),
        )
        assertEquals(CertifiedQualcommFamily.SNAPDRAGON_865, known.family)
    }

    @Test
    fun `an unparseable SoC string cannot corrupt the consent key`() {
        // socModel is a vendor-supplied system property. It reaches SharedPreferences via
        // consentKey, so it is reduced to a bounded token rather than embedded verbatim.
        val baseline = evidence()
        val hostile = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                baseline.copy(socModel = "  ../../etc/passwd | rm -rf /  " + "X".repeat(200)),
            ),
        )
        assertFalse(hostile.profileId.contains('/'))
        assertFalse(hostile.profileId.contains(' '))
        assertFalse(hostile.profileId.contains('|'))
        assertTrue(hostile.profileId.length < 120)
    }

    @Test
    fun `a present vendor package that is tampered with is still refused`() {
        val baseline = evidence()
        // Present-but-wrong is a tamper signal and refuses. Absent is merely unrecognised and does
        // not -- that asymmetry is the whole of the vendor-package rule.
        assertNull(CertifiedVendorAdmissionPolicy.admit(baseline.copy(vendorPackageEnabled = false)))
        assertNull(CertifiedVendorAdmissionPolicy.admit(baseline.copy(vendorPackageIsSystem = false)))
        assertNull(
            CertifiedVendorAdmissionPolicy.admit(
                baseline.copy(vendorPackageMatchesPlatformSignature = false),
            ),
        )
        // A Retroid reporting AYN's settings package contradicts itself.
        assertNull(
            CertifiedVendorAdmissionPolicy.admit(
                baseline.copy(vendorSettingsPackage = VendorUtilityPackages.AYN_SETTINGS),
            ),
        )
    }

    @Test
    fun `identity that used to gate access no longer does`() {
        val baseline = evidence()
        // The three pins the ruling retired. Each of these refused before the change; each must be
        // admitted now, or the ruling is only half-applied.
        assertNotNull(
            "vendor app signer digest must no longer gate access",
            CertifiedVendorAdmissionPolicy.admit(
                baseline.copy(vendorPackageSignerSha256 = setOf("00".repeat(32))),
            ),
        )
        assertNotNull(
            "build fingerprint must no longer gate access",
            CertifiedVendorAdmissionPolicy.admit(baseline.copy(buildFingerprint = "generic/test")),
        )
        assertNotNull(
            "pservice binary digest must no longer gate access",
            CertifiedVendorAdmissionPolicy.admit(
                baseline.copy(
                    serviceAttestation = baseline.attestation().copy(
                        executableSha256 = "5e".repeat(32),
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------- the consent key is now diagnostics only
    //
    //
    //
    // `consentKey` survives as the one token that names "this profile, on this binary, on this
    // firmware" for a log line or a bug report. The three tests below still pin its derivation --
    // it must distinguish what it claims to distinguish, or it is a misleading diagnostic -- but
    // none of them may be read as an access decision any more. The posture tests that used to pair
    // with them live in CertifiedPrivilegeGatewayTest and now assert READY without any record.

    @Test
    fun `a firmware change gives the diagnostic key a new identity under the same profile`() {
        val first = CertifiedVendorAdmissionPolicy.admit(
            evidence(
                fingerprint =
                    "qti/kona/kona:13/TKQ1.240621.001/eng.RP5.20251128.173135:user/release-keys",
            ),
        )
        val updated = CertifiedVendorAdmissionPolicy.admit(
            evidence(
                fingerprint =
                    "qti/kona/kona:13/TKQ1.240621.001/eng.RP5.20251201.090000:user/release-keys",
            ),
        )
        assertNotNull(first)
        assertNotNull(updated)
        assertEquals(first?.profileId, updated?.profileId)
        assertNotEquals(first?.consentKey, updated?.consentKey)
    }

    @Test
    fun `a changed pservice binary is a new diagnostic identity, not a lost grant`() {
        // This assertion used to mean "a swapped binary drops the device back to
        // PERMISSION_REQUIRED". It no longer does, and that demotion is exactly what the 2026-09-11
        // ruling forbids: the device still passes the live structural probe, so it stays READY and
        // the changed digest is reported on the MimirPrivilege log instead. What is still worth
        // pinning is that the two identities are *distinguishable* -- a diagnostic that collapsed a
        // binary swap into the old token would be worse than no diagnostic.
        val original = requireNotNull(CertifiedVendorAdmissionPolicy.admit(evidence()))
        val tampered = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                evidence(attestation = testAttestation(executableSha256 = "de".repeat(32))),
            ),
        )
        assertEquals(original.profileId, tampered.profileId)
        assertEquals(original.buildFingerprint, tampered.buildFingerprint)
        assertNotEquals(original.consentKey, tampered.consentKey)
    }

    @Test
    fun `the diagnostic key is case-insensitive in the digest so probe casing reads as one identity`() {
        // Probe casing is an artefact of whichever `sha256sum` answered, not a change of binary. A
        // diagnostic that reported two identities for one binary would send a reader hunting a
        // firmware event that never happened.
        val lower = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                evidence(attestation = testAttestation(executableSha256 = RetroidPServerSha)),
            ),
        )
        val upper = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                evidence(
                    attestation = testAttestation(
                        executableSha256 = RetroidPServerSha.uppercase(),
                    ),
                ),
            ),
        )
        assertEquals(lower.consentKey, upper.consentKey)
    }

    // ------------------------------------------------------------------------ sealed operations

    @Test
    fun `command policy has no generic command constructor and rejects executable values`() {
        assertNotNull(VendorPServerOperation.readSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT))
        assertNull(VendorPServerOperation.readSetting("adb_enabled"))
        assertNull(
            VendorPServerOperation.writeSetting(
                BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR,
                "#ffffbf00; id",
            ),
        )
        assertNull(VendorPServerOperation.forceStop("com.example.fake"))

        val safe = VendorPServerOperation.writeSetting(
            BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT,
            "0.5",
        )
        assertNotNull(safe)
        assertTrue(safe?.render()?.contains(VendorPServerOperation.SuccessMarker) == true)
        assertFalse(safe?.render()?.contains("id") == true)
    }

    private fun CertifiedVendorEvidence.attestation(): CertifiedVendorServiceAttestation =
        requireNotNull(serviceAttestation)

    private fun evidence(
        manufacturer: String = "Moorechip",
        brand: String = "Retroid",
        model: String = "Retroid Pocket 5",
        soc: String = "SM8250",
        packageName: String? = VendorUtilityPackages.RETROID_SETTINGS,
        fingerprint: String =
            "qti/kona/kona:13/TKQ1.240621.001/eng.RP5.20251128.173135:user/release-keys",
        signerSha256: Set<String> = setOf(RetroidSigner),
        attestation: CertifiedVendorServiceAttestation? = testAttestation(),
    ): CertifiedVendorEvidence = CertifiedVendorEvidence(
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        socModel = soc,
        buildFingerprint = fingerprint,
        vendorSettingsPackage = packageName,
        vendorPackageEnabled = packageName != null,
        vendorPackageIsSystem = packageName != null,
        vendorPackageMatchesPlatformSignature = packageName != null,
        vendorPackageSignerSha256 = signerSha256,
        serviceAttestation = attestation,
    )
}

private fun testAttestation(
    executableSha256: String = RetroidPServerSha,
): CertifiedVendorServiceAttestation = CertifiedVendorServiceAttestation(
    serviceName = CertifiedVendorAdmissionPolicy.ServiceName,
    binderDescriptor = "",
    protocolId = CertifiedVendorAdmissionPolicy.ProtocolId,
    uid = "0",
    gid = "0",
    selinuxDomain = "u:r:pservice:s0",
    executablePath = "/system/bin/pservice",
    executableSha256 = executableSha256,
)

private const val RetroidSigner =
    "83f7d0dd6c66a14e39def325422336f6d14552f8a16dabbdaa195b3bd7146544"
private const val RetroidPServerSha =
    "f44773c8dbdc03904dc604d94c2a6837562d14c808eb6b52ff3b644b19817960"
private const val Odin3PServerSha =
    "e1a4168f6045ce2c89a49bdc82cdcb8e2c9f6816308235a1c1c43d55bf5ed443"
private const val NovaPServerSha =
    "8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4"
