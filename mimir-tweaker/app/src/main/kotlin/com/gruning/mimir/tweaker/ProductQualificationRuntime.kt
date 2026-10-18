package com.gruning.mimir.tweaker

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Base64

internal data class ProductQualificationResolution(
    val authority: ProductQualificationAuthority,
    val source: String,
) {
    val available: Boolean
        get() = source == TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST

    companion object {
        val Unavailable = ProductQualificationResolution(
            ProductQualificationAuthority.Unavailable,
            TweakerPostureTruth.QUALIFICATION_NONE,
        )
    }
}

/**
 * Strict parser for public, build-injected qualification material. Private signing material is
 * never accepted by this boundary. Entries are comma-separated standard Base64 without
 * whitespace; an invalid entry rejects the complete bundle instead of being skipped.
 */
internal object ProductQualificationBuildInputs {
    fun trustAnchor(encoded: String): QualificationTrustAnchor? {
        val keys = decodeEntries(encoded) ?: return null
        if (keys.isEmpty()) return QualificationTrustAnchor(emptyMap())
        return runCatching {
            QualificationTrustAnchor(keys.associateBy(::qualificationKeyId))
        }.getOrNull()
    }

    fun signedManifests(encoded: String): List<ByteArray>? = decodeEntries(encoded)

    private fun decodeEntries(encoded: String): List<ByteArray>? {
        if (encoded.isEmpty()) return emptyList()
        if (encoded.any(Char::isWhitespace)) return null
        val fields = encoded.split(',')
        if (fields.any(String::isEmpty)) return null
        return runCatching {
            fields.map { field ->
                Base64.getDecoder().decode(field).also { require(it.isNotEmpty()) }
            }
        }.getOrNull()
    }
}

internal fun qualificationKeyId(encodedPublicKey: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(encodedPublicKey)
        .joinToString("") { "%02x".format(it) }

/**
 * TR-02: [BuildInjectedProductQualificationResolver.resolve] used to return
 * [ProductQualificationResolution.Unavailable] from six distinct sites with zero output -- a
 * manifest resolution failure was indistinguishable from "feature not enabled on this build" from
 * "trust material is corrupt" from "nothing matched this exact device," and reading the wrong
 * hardcoded log field once produced a false certification-loss finding that had to be retracted.
 * Every value here is logged at the `MimirQualification` tag (see
 * [BuildInjectedProductQualificationResolver]'s `logDecline`) -- never a secret, key, or manifest
 * blob, reasons/ids/numbers only -- exactly like the existing verifier-rejection log this mirrors.
 */
internal object ProductQualificationDeclineReason {
    /** Build flavor does not require a signed manifest at all (compile-time posture). */
    const val SIGNED_MANIFEST_NOT_REQUIRED = "SIGNED_MANIFEST_NOT_REQUIRED"
    const val TRUST_ANCHOR_UNPARSEABLE = "TRUST_ANCHOR_UNPARSEABLE"
    const val MANIFESTS_UNPARSEABLE = "MANIFESTS_UNPARSEABLE"
    const val TRUST_MATERIAL_EMPTY = "TRUST_MATERIAL_EMPTY"
    /** Malformed at the signed-envelope layer, before a payload could even be decoded. */
    const val CANDIDATE_SIGNED_MANIFEST_MALFORMED = "CANDIDATE_SIGNED_MANIFEST_MALFORMED"
    /** Believed unreachable today -- decodeSigned's own postcondition already requires this to
     * decode -- kept for defensive symmetry if that coupling ever changes. */
    const val CANDIDATE_PAYLOAD_MALFORMED = "CANDIDATE_PAYLOAD_MALFORMED"
    /** Matched deviceProfile but not this exact hardware fingerprint. */
    const val CANDIDATE_FINGERPRINT_MISMATCH = "CANDIDATE_FINGERPRINT_MISMATCH"
    /** Matched hardwareFingerprint but not this exact deviceProfile. */
    const val CANDIDATE_DEVICE_PROFILE_MISMATCH = "CANDIDATE_DEVICE_PROFILE_MISMATCH"
    /** Every manifest was dropped before verify() ever ran; nothing reached the existing
     * per-candidate Rejected log below. */
    const val NO_CANDIDATE_SURVIVED = "NO_CANDIDATE_SURVIVED"
}

internal interface ProductQualificationResolver {
    fun resolve(capabilities: HardwareCapabilities): ProductQualificationResolution

    fun qualifiedManualFanPlan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
    ): QualifiedManualFanPlan? {
        val resolution = resolve(capabilities)
        if (!resolution.available) return null
        return resolution.authority.qualifiedManualFanPlan(profileId, packageName, capabilities)
    }

    /**
     * WAVE2 Stage B: the SIGNED plan for rung [rung] of [profileId], or null when that rung is
     * not signed for the exact active hardware/profile -- the caller must degrade to "remain"
     * (Stage A behaviour), never fall back to Restore, exactly as an unsigned base plan already
     * leaves a profile unavailable rather than falling back to something unqualified.
     */
    fun qualifiedDerateRungPlan(
        profileId: String,
        packageName: String,
        rung: Int,
        capabilities: HardwareCapabilities,
    ): TuningPlan? {
        val resolution = resolve(capabilities)
        if (!resolution.available) return null
        return resolution.authority.qualifiedDerateRungPlan(profileId, packageName, rung, capabilities)
    }
}

internal object UnavailableProductQualificationResolver : ProductQualificationResolver {
    override fun resolve(capabilities: HardwareCapabilities) = ProductQualificationResolution.Unavailable
}

/**
 * Exact-device product authority. Untrusted payload fields are used only to select candidates;
 * [QualificationManifestVerifier] performs the signature, topology, plan, and monotonic-sequence
 * decisions before an authority can escape this class.
 */
internal class BuildInjectedProductQualificationResolver(
    context: Context,
    private val signedManifestRequired: Boolean = BuildConfig.SIGNED_MANIFEST_REQUIRED,
    trustAnchorsBase64: String = BuildConfig.QUALIFICATION_TRUST_ANCHORS_BASE64,
    manifestsBase64: String = BuildConfig.QUALIFICATION_MANIFESTS_BASE64,
    private val sequenceStore: QualificationSequenceStore = FileQualificationSequenceStore(
        File(
            context.applicationContext.createDeviceProtectedStorageContext().filesDir,
            QUALIFICATION_DIRECTORY,
        ).absoluteFile,
    ),
) : ProductQualificationResolver {
    private val trustAnchor = ProductQualificationBuildInputs.trustAnchor(trustAnchorsBase64)
    private val signedManifests = ProductQualificationBuildInputs.signedManifests(manifestsBase64)
    /**
     * Keyed on every input that can move the answer, so a NEGATIVE is cacheable too.
     *
     * The previous shape cached only a positive and cleared the cache on every negative, with this
     * reasoning: *"AYN/Retroid firmware can publish PServerBinder after Tweaker's direct-boot
     * service starts. A negative result is therefore transient and must never poison the exact same
     * hardware fingerprint for the lifetime of the process."* The hazard is real; refusing to cache
     * was the wrong instrument for it. It made the steady state on any device WITHOUT a matching
     * manifest re-decode and re-verify a signed manifest on every single call -- on a measured Nova
     * that meant a base64 decode, a payload decode, a fingerprint comparison and two logcat warnings
     * every time, forever, for an answer that could not change.
     *
     * `pServerAvailable` and `status` are what actually make that negative transient, and neither is
     * part of [HardwareFingerprint] (a SHA-256 over manufacturer, model, SoC, device profile, OS
     * build fingerprint and the frequency tables). Putting them IN the key protects the late-PServer
     * case **by construction**: the moment PServerBinder appears, the key differs, the cache misses,
     * and the manifest is resolved again. No TTL, no poisoning, and no re-derivation of an answer
     * that provably has not moved.
     *
     * ## The key is the WHOLE capability set, and that is the point
     *
     * The first version of this keyed on `(fingerprint, status, pServerAvailable)` and argued that
     * a negative could not become positive under an unchanged key. **That argument was false and
     * the gate caught it.** `QualificationPlanPolicy.allows` -- which decides Accepted against
     * `ILLEGAL_PLAN` -- reads `supportedAxes`, `gpuNodeAvailable` and `fanNodeAvailable`
     * (`QualificationManifest.kt:907,913`) and each policy's `minKhz..maxKhz` (`:904`), and
     * [HardwareFingerprint] covers **none** of those (`CalibrationModels.kt:456-470`). Those node
     * flags are deliberately LIVE per-probe reads -- `AndroidHardware.kt:637,639`, whose own
     * comment insists an axis "must never be offered on the strength of a stale read". So one probe
     * where `gpuNodeAvailable` transiently read false would reject a perfectly good manifest, and
     * the old narrow key would have pinned that negative for the entire process lifetime. The
     * previous code self-healed from exactly that by refusing to cache negatives at all; a
     * completeness argument replaced a self-healing property, and the argument was incomplete.
     *
     * So the key is now every capability field, with the ONE field that is both known to move on
     * every probe and known not to be read here normalised out: `CpuPolicy.currentKhz`, which
     * `HardwareProbe.probe()` refreshes from sysfs each call (`AndroidHardware.kt:620`) and which
     * nothing on this path reads.
     *
     * **The asymmetry is the whole design.** A key that is too WIDE degrades to "no cache" -- the
     * behaviour that shipped for months. A key that is too NARROW poisons a device until the
     * process dies. Those failure modes are not comparable, so this errs wide by construction
     * rather than by enumeration, and stops depending on someone having listed every reader
     * correctly. Enumerating is what failed the first time.
     */
    private fun resolutionKey(capabilities: HardwareCapabilities): HardwareCapabilities =
        capabilities.copy(
            cpuPolicies = capabilities.cpuPolicies.map { it.copy(currentKhz = 0) },
        )

    private var cachedKey: HardwareCapabilities? = null
    private var cachedResolution: ProductQualificationResolution? = null

    @Synchronized
    override fun resolve(capabilities: HardwareCapabilities): ProductQualificationResolution {
        if (!signedManifestRequired) {
            // Expected, routine state on any flavor that does not require a signed manifest --
            // info, not warn, so it never reads as an anomaly on those flavors.
            logDecline(
                capabilities,
                ProductQualificationDeclineReason.SIGNED_MANIFEST_NOT_REQUIRED,
                warn = false,
            )
            return ProductQualificationResolution.Unavailable
        }
        val anchor = trustAnchor ?: run {
            logDecline(capabilities, ProductQualificationDeclineReason.TRUST_ANCHOR_UNPARSEABLE)
            return ProductQualificationResolution.Unavailable
        }
        val manifests = signedManifests ?: run {
            logDecline(capabilities, ProductQualificationDeclineReason.MANIFESTS_UNPARSEABLE)
            return ProductQualificationResolution.Unavailable
        }
        if (anchor.isEmpty() || manifests.isEmpty()) {
            logDecline(
                capabilities,
                ProductQualificationDeclineReason.TRUST_MATERIAL_EMPTY,
                detail = "anchorEmpty=${anchor.isEmpty()} manifestsEmpty=${manifests.isEmpty()}",
            )
            return ProductQualificationResolution.Unavailable
        }

        val fingerprint = HardwareFingerprint.create(capabilities)
        val key = resolutionKey(capabilities)
        if (cachedKey == key) {
            cachedResolution?.let { return it }
        }
        val candidates = manifests.mapIndexedNotNull { index, bytes ->
            val signed = QualificationManifestCodec.decodeSigned(bytes) ?: run {
                logDecline(
                    capabilities,
                    ProductQualificationDeclineReason.CANDIDATE_SIGNED_MANIFEST_MALFORMED,
                    detail = "index=$index",
                )
                return@mapIndexedNotNull null
            }
            val manifest = QualificationManifestCodec.decodePayload(signed.payload) ?: run {
                logDecline(
                    capabilities,
                    ProductQualificationDeclineReason.CANDIDATE_PAYLOAD_MALFORMED,
                    detail = "index=$index",
                )
                return@mapIndexedNotNull null
            }
            // A manifest matching hardwareFingerprint but not deviceProfile (or vice versa) is
            // dropped right here, before verify() ever runs -- the two mismatches are kept
            // distinct so that distinction survives into the log instead of collapsing into one
            // opaque "candidate filtered" line.
            if (manifest.hardwareFingerprint != fingerprint) {
                logDecline(
                    capabilities,
                    ProductQualificationDeclineReason.CANDIDATE_FINGERPRINT_MISMATCH,
                    detail = "sequence=${manifest.sequence} key=${manifest.keyId.take(12)}",
                )
                return@mapIndexedNotNull null
            }
            if (manifest.deviceProfile != capabilities.deviceProfileId) {
                logDecline(
                    capabilities,
                    ProductQualificationDeclineReason.CANDIDATE_DEVICE_PROFILE_MISMATCH,
                    detail = "sequence=${manifest.sequence} key=${manifest.keyId.take(12)}",
                )
                return@mapIndexedNotNull null
            }
            Triple(manifest.sequence, bytes, manifest.keyId)
        }.sortedWith(
            compareByDescending<Triple<Long, ByteArray, String>> { it.first }
                .thenBy { it.third },
        )

        val resolution = candidates.firstNotNullOfOrNull { (sequence, bytes, keyId) ->
            when (
                val verified = QualificationManifestVerifier.verify(
                    signedBytes = bytes,
                    trustAnchor = anchor,
                    sequenceStore = sequenceStore,
                    capabilities = capabilities,
                )
            ) {
                is QualificationVerification.Accepted -> ProductQualificationResolution(
                    authority = verified.authority,
                    source = TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST,
                )
                is QualificationVerification.Rejected -> {
                    Log.w(
                        QualificationLogTag,
                        "Rejected sequence=$sequence key=${keyId.take(12)} " +
                            "profile=${capabilities.deviceProfileId} reason=${verified.reason}",
                    )
                    null
                }
            }
        } ?: run {
            // Every per-candidate Rejected outcome above already logged its own reason. This
            // extra line only fires when NOTHING reached verify() at all -- candidates was
            // empty -- which is the one sub-case the per-candidate logging above cannot label by
            // itself (there is no candidate to log against).
            if (candidates.isEmpty()) {
                logDecline(
                    capabilities,
                    ProductQualificationDeclineReason.NO_CANDIDATE_SURVIVED,
                    detail = "manifests=${manifests.size} candidates=0",
                )
            }
            ProductQualificationResolution.Unavailable
        }
        // Both outcomes are cached; see [ResolutionKey] for why a negative is safe to keep and what
        // makes it re-resolve. The comment that stood here said a negative "must never poison the
        // exact same hardware fingerprint for the lifetime of the process" -- still true, and now
        // guaranteed by the key rather than by refusing to cache: the fingerprint ALONE is no longer
        // the key, so the PServerBinder flip this protected against is a miss, not a stale hit.
        cachedKey = key
        cachedResolution = resolution
        return resolution
    }

    /**
     * TR-02 observability seam for every zero-output `Unavailable` return above. Matches the
     * existing per-candidate `Rejected` log's shape (tag, `profile=`, `reason=` tokens) so both
     * read the same way in logcat. Never a secret, key, or manifest blob -- reasons/ids/numbers
     * only. `warn=false` is used exactly once, for the one reason that is routine build posture
     * rather than an anomaly (see [ProductQualificationDeclineReason.SIGNED_MANIFEST_NOT_REQUIRED]).
     */
    private fun logDecline(
        capabilities: HardwareCapabilities,
        reason: String,
        detail: String? = null,
        warn: Boolean = true,
    ) {
        val message = "Unavailable profile=${capabilities.deviceProfileId} reason=$reason" +
            if (detail == null) "" else " $detail"
        runCatching {
            if (warn) Log.w(QualificationLogTag, message) else Log.i(QualificationLogTag, message)
        }
    }

    private companion object {
        const val QUALIFICATION_DIRECTORY = "product_qualification"
        const val QualificationLogTag = "MimirQualification"
    }
}
