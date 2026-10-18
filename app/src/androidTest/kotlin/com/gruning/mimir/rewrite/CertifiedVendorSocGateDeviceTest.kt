package com.gruning.mimir.rewrite

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RC-14, certified on hardware: built-in access is gated on SoC family plus a structural `pservice`
 * attestation, never on device identity.
 *
 *
 *
 * **Unlike [CertifiedVendorIdentityProbeDeviceTest], this is a gate, not a capture tool.** That probe
 * exists to read values off a device so a human could author a per-device contract row; this file
 * exists because there are no rows to author any more. It asserts the outcome instead of reporting
 * the inputs.
 *
 * **It carries its own negative control, on purpose.** A device run that only proves "the Odin still
 * works" proves nothing about whether the gate still has teeth -- a gate that returned a profile
 * unconditionally would pass that test too. So every refusal case below is built by corrupting **the
 * real attestation this device just produced**, one axis at a time, and asserting the policy refuses
 * it. Same evidence, same code path, one field different. If the fabricated-rejection block ever
 * passes vacuously it will be because `serviceAttestation` was null, and that case returns early
 * with a distinct log line rather than silently reporting success.
 */
@RunWith(AndroidJUnit4::class)
class CertifiedVendorSocGateDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun socGateAdmitsThisHandheldAndStillRefusesAFabricatedAttestation() {
        Log.i(Tag, "==== RC-14 SoC-gate certification start ====")
        Log.i(Tag, "MODEL=${Build.MODEL} MANUFACTURER=${Build.MANUFACTURER} BRAND=${Build.BRAND}")
        Log.i(Tag, "FINGERPRINT=${Build.FINGERPRINT}")

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            ""
        }
        val family = CertifiedVendorAdmissionPolicy.family(socModel)
        Log.i(Tag, "SOC_MODEL=$socModel family=$family")

        // Whichever vendor settings package this device actually has, or none. Nothing downstream
        // requires one to be present -- that is the point of the ruling.
        val vendorPackage = listOf(
            VendorUtilityPackages.AYN_SETTINGS,
            VendorUtilityPackages.RETROID_SETTINGS,
        ).firstOrNull { candidate ->
            runCatching { context.packageManager.getApplicationInfo(candidate, 0) }.isSuccess
        }
        Log.i(Tag, "vendorSettingsPackage=${vendorPackage ?: "NONE INSTALLED"}")

        val identity = BrightnessDeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            vendorSettingsPackage = vendorPackage,
        )
        val transport = AndroidPServerTransport()
        val evidence = try {
            currentCertifiedVendorEvidence(context, identity, transport)
        } finally {
            transport.close()
        }

        Log.i(Tag, "vendorPackageEnabled=${evidence.vendorPackageEnabled}")
        Log.i(Tag, "vendorPackageIsSystem=${evidence.vendorPackageIsSystem}")
        Log.i(Tag, "platformSignature=${evidence.vendorPackageMatchesPlatformSignature}")
        Log.i(Tag, "postureAdmissible=${CertifiedVendorAdmissionPolicy.vendorPackagePostureAdmissible(evidence)}")

        val attestation = evidence.serviceAttestation
        if (attestation == null) {
            // Legitimate on any unit with no vendor route. Logged loudly so a vacuous run can never
            // be mistaken for a certification -- see the class doc.
            Log.i(
                Tag,
                "NO ATTESTATION on this unit — no built-in vendor route, Shizuku is the honest " +
                    "answer here. This run certifies NOTHING about the gate; re-run on a handheld " +
                    "with a live PServerBinder.",
            )
            Log.i(Tag, "==== RC-14 SoC-gate certification end (INAPPLICABLE) ====")
            return
        }

        Log.i(Tag, "ATTESTATION PRESENT")
        Log.i(Tag, "  uid=${attestation.uid} gid=${attestation.gid}")
        Log.i(Tag, "  selinuxDomain=${attestation.selinuxDomain}")
        Log.i(Tag, "  executablePath=${attestation.executablePath}")
        Log.i(Tag, "  executableSha256=${attestation.executableSha256}")
        Log.i(Tag, "  binderDescriptor='${attestation.binderDescriptor}'")

        // -------------------------------------------------------------------- positive
        // The chicken-and-egg is what this line really tests. Before the ruling was applied,
        // currentCertifiedVendorEvidence only attested when a registry contract ALREADY matched this
        // firmware, so on the Nova `attestation` above would have been null and this test would have
        // returned INAPPLICABLE. Reaching here at all on the Nova is the fix.
        val admitted = CertifiedVendorAdmissionPolicy.admit(evidence)
        Log.i(Tag, "admit() -> ${admitted?.profileId ?: "REFUSED"}")
        assertNotNull(
            "A handheld with a live, structurally valid pservice on a supported SoC must be " +
                "admitted with no per-device registry row. SOC_MODEL=$socModel family=$family",
            admitted,
        )
        val profile = requireNotNull(admitted)
        Log.i(Tag, "  profileId=${profile.profileId}")
        Log.i(Tag, "  attestedExecutableSha256=${profile.attestedExecutableSha256}")
        Log.i(Tag, "  consentKey=${profile.consentKey}")
        // built-in access is structural only -- no consent key, no
        // fingerprint pin, no signed qualification.
        assertEquals(
            "The profile still records the measured executable digest for diagnostics only; " +
                "the digest does not gate access.",
            attestation.executableSha256.lowercase(),
            profile.attestedExecutableSha256,
        )
        assertTrue(
            "consentKey must carry the measured digest",
            profile.consentKey.contains(profile.attestedExecutableSha256),
        )

        // -------------------------------------------------------------------- negative control
        // Every case below is this device's OWN attestation with exactly one field corrupted.
        val fabricated = listOf(
            "uid is not root" to attestation.copy(uid = "2000"),
            "gid is not root" to attestation.copy(gid = "2000"),
            "shell SELinux domain" to attestation.copy(selinuxDomain = "u:r:shell:s0"),
            "untrusted_app SELinux domain" to
                attestation.copy(selinuxDomain = "u:r:untrusted_app:s0"),
            "executable is a shell" to attestation.copy(executablePath = "/system/bin/sh"),
            "executable is toybox" to attestation.copy(executablePath = "/system/bin/toybox"),
            "wrong service name" to attestation.copy(serviceName = "NotPServerBinder"),
            "wrong protocol id" to attestation.copy(protocolId = "pserver-v0"),
            "digest absent" to attestation.copy(executableSha256 = ""),
            "digest truncated" to attestation.copy(executableSha256 = "8a0b75b4"),
            "digest not hex" to attestation.copy(executableSha256 = "z".repeat(64)),
        )
        fabricated.forEach { (label, broken) ->
            val verdict = CertifiedVendorAdmissionPolicy.admit(
                evidence.copy(serviceAttestation = broken),
            )
            Log.i(Tag, "fabricated[$label] -> ${verdict?.profileId ?: "REFUSED (correct)"}")
            assertNull(
                "Fabricated attestation must be refused on hardware: $label",
                verdict,
            )
        }

        // Absent service is still a fail-closed path.
        //
        // An "unsupported SoC must be refused" assertion stood here until 2026-08-22 and was
        // CORRECT under the previous admission requirement. The updated requirement admits unlisted
        // silicon with a genuine pservice now earns access. It is not merely deleted -- it is
        // inverted, and the inverted form is the caveat block further down, which asserts that the
        // very same SM8450 evidence IS admitted and is recorded as uncharacterised. Removing an
        // assertion without replacing what it was guarding is how a gate quietly loses a duty.
        assertNull(
            "Absent attestation must be refused",
            CertifiedVendorAdmissionPolicy.admit(evidence.copy(serviceAttestation = null)),
        )

        // A present vendor package that has been tampered with still refuses.
        if (vendorPackage != null) {
            assertNull(
                "Disabled vendor package must be refused",
                CertifiedVendorAdmissionPolicy.admit(evidence.copy(vendorPackageEnabled = false)),
            )
            assertNull(
                "Non-system vendor package must be refused",
                CertifiedVendorAdmissionPolicy.admit(evidence.copy(vendorPackageIsSystem = false)),
            )
            assertNull(
                "Vendor package not signed with the platform key must be refused",
                CertifiedVendorAdmissionPolicy.admit(
                    evidence.copy(vendorPackageMatchesPlatformSignature = false),
                ),
            )
        }

        Log.i(
            Tag,
            "NEGATIVE CONTROL PASSED — ${fabricated.size} fabricated attestations refused, " +
                "built from this device's real attestation.",
        )

        // Uncharacterised silicon with genuine vendor attestation retains privileged access.
        // Simulate silicon this build has not characterised, keeping this device's attestation.
        // Access must be granted;
        // the profile must record family=null and say "uncharacterised" in an id that lands in the
        // consent key. Before 2026-08-22 every one of these returned null on admit()'s first line.
        //
        // The tuning half of the caveat cannot be asserted from this process: it lives in the
        // separate mimir-tweaker Gradle build (SupportedUncertifiedProfiles' profileTreatment gate
        // and TuningPlanner's family-keyed target tables) and is covered by a differential in that
        // build's JVM suite. Nothing in the app module can grant tuning, which is the point.
        listOf("SM8450", "QCS9075", "MT6983", "Tensor G3").forEach { unlistedSoc ->
            val simulated = CertifiedVendorAdmissionPolicy.admit(
                evidence.copy(socModel = unlistedSoc),
            )
            Log.i(
                Tag,
                "uncharacterised '$unlistedSoc' -> access=${simulated?.profileId ?: "REFUSED"} " +
                    "family=${simulated?.family ?: "null (uncharacterised)"}",
            )
            assertNotNull(
                "Uncharacterised silicon must still be granted vendor access: $unlistedSoc",
                simulated,
            )
            assertNull(
                "Uncharacterised silicon must record family=null, never a guessed family",
                simulated?.family,
            )
            assertTrue(
                "profileId must name the uncharacterised state, since it lands in consentKey",
                simulated?.profileId?.contains(
                    CertifiedVendorAdmissionPolicy.UncharacterisedFamilyPrefix,
                ) == true,
            )
        }
        Log.i(Tag, "CAVEAT PASSED — uncharacterised silicon is admitted for access on hardware.")

        // ------------------------------------------------------- end-to-end through the gateway
        // Admission alone is not "access works". This block drives the real gateway over the real
        // vendor binder: consent withheld must still read READY under the CERTIFIED_VENDOR
        // authority -- never PERMISSION_REQUIRED and never "unsupported device" -- per the
        // 2026-09-11 ruling that built-in access is structural only. Consent granted must also
        // read READY, and a real vendor setting must come back through the vendor transport.
        //
        // Deliberately read-only. The Odin's own end-to-end proof
        // (CertifiedVendorOdinDeviceTest) additionally exercises openSystemRecents() and an
        // idempotent same-value write; this file stays inside a no-mutation envelope so it is safe
        // to run on any handheld, including one whose vendor route has never been exercised.
        val liveTransport = AndroidPServerTransport()
        try {
            val denied = AndroidCertifiedVendorPrivilegeGateway(
                context = context,
                identity = identity,
                transport = liveTransport,
                consent = DeniedConsent,
                evidenceReader = { currentCertifiedVendorEvidence(context, identity, liveTransport) },
            )
            val deniedPosture = denied.posture()
            Log.i(Tag, "posture(consent withheld)=${deniedPosture.status} authority=${deniedPosture.authority}")
            // built-in access is structural only -- no consent key, no
            // fingerprint pin, no signed qualification. Consent withheld must still admit on
            // structural evidence alone: READY, or CONNECTION_FAILED only if the transport itself
            // died -- PERMISSION_REQUIRED is not reachable for this authority any more.
            assertTrue(
                "Consent withheld must be READY (or CONNECTION_FAILED if the transport died), " +
                    "never PERMISSION_REQUIRED: ${deniedPosture.status}",
                deniedPosture.status == PrivilegedUtilityStatus.READY ||
                    deniedPosture.status == PrivilegedUtilityStatus.CONNECTION_FAILED,
            )
            assertEquals(
                PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
                deniedPosture.authority,
            )

            val ready = AndroidCertifiedVendorPrivilegeGateway(
                context = context,
                identity = identity,
                transport = liveTransport,
                consent = GrantedConsent(profile.consentKey),
                evidenceReader = { currentCertifiedVendorEvidence(context, identity, liveTransport) },
            )
            val readyPosture = ready.posture()
            Log.i(Tag, "posture(consent granted)=${readyPosture.status} authority=${readyPosture.authority}")
            assertEquals(
                "A consented, attested handheld must reach READY on built-in authority",
                PrivilegedUtilityStatus.READY,
                readyPosture.status,
            )
            assertEquals(
                PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
                readyPosture.authority,
            )

            val settled = listOf(
                BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT,
                BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED,
                BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR,
            ).firstNotNullOfOrNull { key ->
                ready.readVendorSetting(key)?.let { value -> key to value }
            }
            // Not an assertion: a handheld may legitimately expose none of these three keys, and
            // that is a fact about its firmware rather than a failure of the access route, which
            // READY above has already proven.
            Log.i(
                Tag,
                "privileged read over vendor binder: " +
                    (settled?.let { "${it.first}=${it.second}" } ?: "no fixed-contract key exposed"),
            )
        } finally {
            liveTransport.close()
        }

        Log.i(Tag, "==== RC-14 SoC-gate certification end (CERTIFIED) ====")
    }

    private companion object {
        const val Tag = "MimirVendorSocGate"
    }
}

private data object DeniedConsent : CertifiedVendorConsentStore {
    override fun isGranted(consentKey: String): Boolean = false
    override fun grant(consentKey: String) = Unit
}

/** Grants exactly the one key under test, so a wrong key cannot pass by accident. */
private class GrantedConsent(private val expected: String) : CertifiedVendorConsentStore {
    override fun isGranted(consentKey: String): Boolean = consentKey == expected
    override fun grant(consentKey: String) = Unit
}
