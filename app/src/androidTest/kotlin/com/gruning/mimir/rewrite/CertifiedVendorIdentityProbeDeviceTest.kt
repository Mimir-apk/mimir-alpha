package com.gruning.mimir.rewrite

import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads signer, firmware lineage and process fields to diagnose structural vendor-admission failures.
 * Admission uses [CertifiedVendorAdmissionPolicy.admit] over [CertifiedVendorStructuralContract],
 * verified on hardware by `CertifiedVendorSocGateDeviceTest`. There is no per-device registry,
 * contract row or digest to author: `CertifiedVendorContractRegistry` and `CertifiedVendorContract`
 * no longer exist. Do not use this output to add device-specific entries.
 *
 * [CertifiedVendorAdmissionPolicy.family] recognised `QCS8550`/`SM8550` as
 * [CertifiedQualcommFamily.SNAPDRAGON_8_GEN_2], but the former registry lacked that family and
 * always refused it regardless of vendor detection. Structural admission removes that dependency.
 *
 * This capture tool passes on any device, including ones without a vendor route. It asserts no
 * specific signer, fingerprint, SoC or executable hash. Structured [Log.i] lines under [Tag]
 * contain the raw per-field values needed to identify a failed structural axis.
 *
 * The reused identity `"ayn-qcs8550-pserver"` denotes AYN, whereas a `retroid`-branded
 * `Build.BRAND`/`MODEL` resolves to [CertifiedHandheldVendor.RETROID]. This probe does not assume
 * either vendor: it checks both [VendorUtilityPackages.AYN_SETTINGS] and
 * [VendorUtilityPackages.RETROID_SETTINGS] and reports installed, enabled, platform-signed packages.
 *
 * Calls [AndroidPServerTransport.attest] directly. [currentCertifiedVendorEvidence] previously
 * attested only after matching a registry contract, preventing capture for a new family. It now
 * attests on supported SoCs with admissible vendor-package posture. Direct `attest()` also keeps
 * this probe useful on silicon not listed in the family table.
 */
@RunWith(AndroidJUnit4::class)
class CertifiedVendorIdentityProbeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun captureDeviceIdentityForANewCertifiedVendorContractRow() {
        Log.i(Tag, "==== Vendor identity capture start ====")

        Log.i(Tag, "MANUFACTURER=${Build.MANUFACTURER}")
        Log.i(Tag, "BRAND=${Build.BRAND}")
        Log.i(Tag, "MODEL=${Build.MODEL}")
        Log.i(Tag, "DEVICE=${Build.DEVICE}")
        Log.i(Tag, "PRODUCT=${Build.PRODUCT}")

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            "unavailable (API ${Build.VERSION.SDK_INT} < 31)"
        }
        Log.i(Tag, "SOC_MODEL=$socModel")
        val family = CertifiedVendorAdmissionPolicy.family(socModel)
        Log.i(
            Tag,
            "CertifiedVendorAdmissionPolicy.family(SOC_MODEL)=$family " +
                "(null means CertifiedQualcommFamily/family() needs a new branch too, not just a " +
                "contract row — check CertifiedVendorPrivilege.kt:19-23,205-212 before authoring one)",
        )

        Log.i(Tag, "FINGERPRINT=${Build.FINGERPRINT}")
        Log.i(
            Tag,
            "fingerprintPrefix/fingerprintSuffix are NOT computed here on purpose — splitting out the " +
                "volatile per-build datestamp in the middle is a human judgement call. Pick the split by " +
                "eye against the two existing contracts' shape in CertifiedVendorPrivilege.kt:119-162 " +
                "(e.g. Odin 3's prefix ends right before its build datestamp, suffix is the signing " +
                "class \":user/dev-keys\").",
        )

        // Both vendor settings packages are checked independently — see the class doc for why neither is
        // assumed. A package that isn't installed is reported and skipped, not treated as a failure.
        for ((packageName, vendor) in listOf(
            VendorUtilityPackages.AYN_SETTINGS to CertifiedHandheldVendor.AYN,
            VendorUtilityPackages.RETROID_SETTINGS to CertifiedHandheldVendor.RETROID,
        )) {
            val installed = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0)
            }.isSuccess
            if (!installed) {
                Log.i(Tag, "vendor candidate ($vendor) package=$packageName NOT INSTALLED on this device")
                continue
            }
            val identity = BrightnessDeviceIdentity(
                manufacturer = Build.MANUFACTURER,
                brand = Build.BRAND,
                model = Build.MODEL,
                vendorSettingsPackage = packageName,
            )
            val evidenceTransport = AndroidPServerTransport()
            val evidence = try {
                currentCertifiedVendorEvidence(context, identity, evidenceTransport)
            } finally {
                evidenceTransport.close()
            }
            Log.i(Tag, "vendor candidate ($vendor) package=$packageName installed")
            Log.i(Tag, "  vendorPackageEnabled=${evidence.vendorPackageEnabled}")
            Log.i(Tag, "  vendorPackageIsSystem=${evidence.vendorPackageIsSystem}")
            Log.i(
                Tag,
                "  vendorPackageMatchesPlatformSignature=${evidence.vendorPackageMatchesPlatformSignature}",
            )
            Log.i(Tag, "  vendorPackageSignerSha256=${evidence.vendorPackageSignerSha256}")
        }

        // Direct attest() — deliberately bypasses currentCertifiedVendorEvidence's own mayAttest gate; see
        // the class doc for why that gate can never open for a family with zero existing contracts.
        //
        // The binder's OWN presence is logged separately from attest()'s verdict, on purpose: attest()
        // collapses "no such service" and "service exists but failed a validation check" into the same
        // null return (AndroidCertifiedVendorPrivilegeGateway.kt: bindForAttestation()'s early return
        // vs. attest()'s own poison-and-return-null branch at :330-338). Those are two different
        // findings — one says "no built-in route exists," the other says "one exists but doesn't match
        // this protocol's fixed expectations, which is worth a closer look, not a Shizuku verdict." A
        // probe that only logs attest()'s final answer cannot tell them apart, so it is instrumented
        // here at the leaf AND at the binder-resolution step above it (same discipline as `instrument
        // the whole chain, not the leaf` — one signed device certification has already been burned in
        // this project by checking only the last hop).
        val binder = resolvePServerBinderForProbe()
        when {
            binder == null -> Log.i(
                Tag,
                "PServerBinder service lookup: ABSENT — ServiceManager.getService(\"PServerBinder\") " +
                    "returned null. No built-in vendor service exists on this firmware at all.",
            )
            !runCatching { binder.pingBinder() }.getOrDefault(false) -> Log.i(
                Tag,
                "PServerBinder service lookup: PRESENT but not alive — pingBinder()=false. A dead " +
                    "reference, not a genuine absence; worth a second capture if reproducible.",
            )
            else -> Log.i(Tag, "PServerBinder service lookup: ALIVE — binder resolved and pingBinder()=true.")
        }

        val attestationTransport = AndroidPServerTransport()
        val attestation = try {
            runCatching { attestationTransport.attest() }.getOrNull()
        } finally {
            attestationTransport.close()
        }
        if (attestation == null) {
            // Three-way, matching the lookup above exactly — do not collapse "dead reference" or "the
            // connection failed mid-probe" into the same confident claim the nonconforming-service case
            // makes; only that specific case can be asserted, the other two are genuinely ambiguous here.
            val diagnosis = when {
                binder == null -> "consistent with the lookup above: no service, so nothing to attest."
                !runCatching { binder.pingBinder() }.getOrDefault(false) ->
                    "consistent with the lookup above: the binder reference was dead " +
                        "(pingBinder()=false) by the time attest() ran, not a clean absence."
                else -> "the service resolved and was alive above, but attest() still returned null — " +
                    "either it answered with an uid/gid/selinux domain/executable path this protocol " +
                    "did not expect (AndroidCertifiedVendorPrivilegeGateway.kt:330-338 lists the exact " +
                    "checks), or the connection failed mid-probe. This is NOT the same finding as 'no " +
                    "vendor route' — the raw per-field values aren't surfaced by attest() when it " +
                    "rejects, so a follow-up capture through the raw protocol would be needed to see " +
                    "exactly what it reported before concluding Shizuku is the only route on this unit."
            }
            Log.i(
                Tag,
                "PServerBinder attestation: ABSENT — $diagnosis If the lookup above also read ABSENT, " +
                    "this unit has no built-in vendor route at all and Shizuku is the correct, honest " +
                    "answer for it — that is a real finding, not a probe failure.",
            )
        } else {
            Log.i(Tag, "PServerBinder attestation: PRESENT")
            Log.i(Tag, "  serviceName=${attestation.serviceName}")
            Log.i(Tag, "  binderDescriptor=${attestation.binderDescriptor}")
            Log.i(Tag, "  protocolId=${attestation.protocolId}")
            Log.i(Tag, "  uid=${attestation.uid}")
            Log.i(Tag, "  gid=${attestation.gid}")
            Log.i(Tag, "  selinuxDomain=${attestation.selinuxDomain}")
            Log.i(Tag, "  executablePath=${attestation.executablePath}")
            Log.i(Tag, "  executableSha256=${attestation.executableSha256}")
        }

        Log.i(Tag, "==== RC-14 identity capture end ====")

        // The only hard assertion this probe makes: that Build.* was actually readable, so a total
        // instrumentation failure doesn't silently masquerade as "captured nothing interesting." Every
        // other outcome above — package absent, attestation absent, family unrecognised — is a valid,
        // logged finding, not a test failure.
        assertTrue(
            "Build.FINGERPRINT must be readable for this probe's capture to mean anything",
            Build.FINGERPRINT.isNotBlank(),
        )
    }

    private companion object {
        const val Tag = "MimirVendorIdentityProbe"
    }
}

/**
 * Deliberately duplicated from the private `resolvePServerBinder` in
 * `AndroidCertifiedVendorPrivilegeGateway.kt` (same reflection, same service name) rather than making
 * that function `internal` to widen production visibility for a diagnostic-only need. This probe needs
 * the raw binder BEFORE [AndroidPServerTransport.attest]'s own validate-or-null collapse, to log service
 * presence and [CertifiedVendorIdentityProbeDeviceTest]'s ABSENT/nonconforming distinction separately —
 * see that test's doc.
 */
private fun resolvePServerBinderForProbe(): IBinder? = runCatching {
    val serviceManager = Class.forName("android.os.ServiceManager")
    serviceManager.getDeclaredMethod("getService", String::class.java)
        .apply { isAccessible = true }
        .invoke(null, CertifiedVendorAdmissionPolicy.ServiceName) as? IBinder
}.getOrNull()
