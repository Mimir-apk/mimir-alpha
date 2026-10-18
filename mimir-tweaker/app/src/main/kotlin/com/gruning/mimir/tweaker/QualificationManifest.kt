package com.gruning.mimir.tweaker

import com.google.crypto.tink.subtle.Ed25519Verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Collections

internal const val QUALIFICATION_MANIFEST_SCHEMA = 3
internal const val QUALIFICATION_SERVICE_PLAN_SCHEMA = 3
/**
 * WAVE2 Stage B: fixed schema-v2 threshold, deliberately DECOUPLED from
 * [QUALIFICATION_MANIFEST_SCHEMA]/[QUALIFICATION_SERVICE_PLAN_SCHEMA] (now 3, "current").
 *
 * Before schema v3 existed, the manualFanOverride encode/decode gate below compared directly
 * against [QUALIFICATION_MANIFEST_SCHEMA], which was harmless only because "the schema that
 * introduced manualFanOverride" and "the current/latest schema" were the same number (2) at the
 * time. Bumping [QUALIFICATION_MANIFEST_SCHEMA] to 3 without introducing this fixed constant
 * would silently stop encoding/decoding manualFanOverride bytes for a v2 payload (`2 >= 3` is
 * false), corrupting the wire format for every already-signed schema-v2 manifest that carries one
 * -- exactly the "v1 and v2 must remain decodable" regression this lane must not introduce. The
 * manualFanOverride gate must stay pinned at this fixed v2 threshold forever; only the NEW
 * derateRungs gate (added by v3) may use the "current" constant, following the same pattern this
 * field itself established when it was new.
 */
internal const val QUALIFICATION_MANIFEST_SCHEMA_V2 = 2
internal const val QUALIFICATION_SERVICE_PLAN_SCHEMA_V2 = 2
internal const val LEGACY_QUALIFICATION_MANIFEST_SCHEMA = 1
internal const val LEGACY_QUALIFICATION_SERVICE_PLAN_SCHEMA = 1
internal const val MANUAL_FAN_OVERRIDE_MIN_SEQUENCE = 5L

/**
 * Optional, separately evidenced authority for an explicit Auto -> Manual fan transition.
 * The baseline profile remains vendor Smart (`fan_mode=4`); this authority only admits the
 * same signed CPU/GPU plan with Odin's manual passthrough (`fan_mode=6`).
 */
internal data class QualifiedManualFanOverride(
    val fanMode: Int = TuningPlanner.FAN_MANUAL,
    val minimumDutyPercent: Int,
    val planSha256: String,
    val evidenceIndexSha256: String,
    val guardianRollbackEvidenceSha256: String,
) {
    init {
        require(fanMode == TuningPlanner.FAN_MANUAL)
        require(minimumDutyPercent in 0 until FanPwmBounds.MAX_PERCENT)
        requireSha256(planSha256, "manual fan plan")
        requireSha256(evidenceIndexSha256, "manual fan evidence index")
        requireSha256(guardianRollbackEvidenceSha256, "manual fan Guardian rollback evidence")
        require(!evidenceIndexSha256.all { it == '0' })
        require(!guardianRollbackEvidenceSha256.all { it == '0' })
    }

    companion object {
        fun create(
            cpuMaxKhz: Map<Int, Int>,
            gpuMaxPowerLevel: Int?,
            minimumDutyPercent: Int,
            evidenceIndexSha256: String,
            guardianRollbackEvidenceSha256: String,
        ) = QualifiedManualFanOverride(
            minimumDutyPercent = minimumDutyPercent,
            planSha256 = planIdentityHash(
                cpuMaxKhz = cpuMaxKhz,
                gpuMaxPowerLevel = gpuMaxPowerLevel,
                fanMode = TuningPlanner.FAN_MANUAL,
            ),
            evidenceIndexSha256 = evidenceIndexSha256,
            guardianRollbackEvidenceSha256 = guardianRollbackEvidenceSha256,
        )
    }
}

/**
 * WAVE2 Stage B / schema v3: one qualified thermal-derate rung, nested under its profile exactly
 * like [QualifiedManualFanOverride] -- same shape (its own `planSha256` and evidence hashes), same
 * "an invalid entry rejects the complete bundle" decode posture. A derate never writes a value
 * outside what the owner explicitly signed here: it SELECTS one of these already-qualified plans
 * in place of the profile's nominal, it never computes or loosens one.
 *
 * [fanMode] is carried on the wire for the same reason [QualifiedManualFanOverride] carries its
 * own `fanMode` -- a rung is a full alternate plan, not a two-field diff -- but
 * [QualifiedProfilePlan]'s admission (rule 1) forces it to always equal the enclosing profile's
 * own `fanMode` exactly. The fan axis is never derated.
 */
internal data class QualifiedDerateRung(
    val cpuMaxKhz: Map<Int, Int>,
    val gpuMaxPowerLevel: Int?,
    val fanMode: Int?,
    val planSha256: String,
    val evidenceIndexSha256: String,
    val guardianRollbackEvidenceSha256: String,
) {
    init {
        require(cpuMaxKhz.isNotEmpty() && cpuMaxKhz.size <= QualifiedProfilePlan.MAX_CPU_POLICIES)
        require(cpuMaxKhz.keys.toList() == cpuMaxKhz.keys.sorted())
        require(cpuMaxKhz.all { (id, khz) -> id >= 0 && khz > 0 })
        require(gpuMaxPowerLevel == null || gpuMaxPowerLevel >= 0)
        require(fanMode == null || TuningPlanner.isCapturedVendorFanMode(fanMode))
        require(fanMode != TuningPlanner.FAN_MANUAL) {
            "manual fan mode is illegal on a derate rung; the fan axis is never derated"
        }
        requireSha256(planSha256, "derate rung plan")
        requireSha256(evidenceIndexSha256, "derate rung evidence index")
        requireSha256(guardianRollbackEvidenceSha256, "derate rung Guardian rollback evidence")
        require(!evidenceIndexSha256.all { it == '0' })
        require(!guardianRollbackEvidenceSha256.all { it == '0' })
        require(
            planIdentityHash(cpuMaxKhz, gpuMaxPowerLevel, fanMode) == planSha256,
        ) { "derate rung plan hash mismatch" }
    }

    companion object {
        fun create(
            cpuMaxKhz: Map<Int, Int>,
            gpuMaxPowerLevel: Int?,
            fanMode: Int?,
            evidenceIndexSha256: String,
            guardianRollbackEvidenceSha256: String,
        ): QualifiedDerateRung {
            val pinnedCpu = Collections.unmodifiableMap(cpuMaxKhz.toSortedMap())
            return QualifiedDerateRung(
                cpuMaxKhz = pinnedCpu,
                gpuMaxPowerLevel = gpuMaxPowerLevel,
                fanMode = fanMode,
                planSha256 = planIdentityHash(pinnedCpu, gpuMaxPowerLevel, fanMode),
                evidenceIndexSha256 = evidenceIndexSha256,
                guardianRollbackEvidenceSha256 = guardianRollbackEvidenceSha256,
            )
        }
    }
}

/**
 * One exact product plan. Package identity is intentionally absent: the signed hardware plan is
 * reused for the admitted foreground game while the transaction journal still binds that package.
 */
internal data class QualifiedProfilePlan(
    val profileId: String,
    val cpuMaxKhz: Map<Int, Int>,
    val gpuMaxPowerLevel: Int?,
    val fanMode: Int?,
    val planSha256: String,
    val evidenceIndexSha256: String,
    val guardianRollbackEvidenceSha256: String,
    val manualFanOverride: QualifiedManualFanOverride? = null,
    /**
     * WAVE2 Stage B / schema v3: ordered, qualified thermal-derate rungs (spec S3). Index 0 is
     * rung 1, mirroring [ThermalDerateLadder]'s own 1-based `at(rung)` convention -- the ladder is
     * the INDEX, this list is the AUTHORITY; on disagreement the manifest wins.
     */
    val derateRungs: List<QualifiedDerateRung> = emptyList(),
) {
    init {
        require(profileId in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS)
        require(cpuMaxKhz.isNotEmpty() && cpuMaxKhz.size <= MAX_CPU_POLICIES)
        require(cpuMaxKhz.keys.toList() == cpuMaxKhz.keys.sorted())
        require(cpuMaxKhz.all { (id, khz) -> id >= 0 && khz > 0 })
        require(gpuMaxPowerLevel == null || gpuMaxPowerLevel >= 0)
        require(fanMode == null || TuningPlanner.isCapturedVendorFanMode(fanMode))
        require(fanMode != TuningPlanner.FAN_MANUAL) {
            "manual fan mode is legal only through a schema-v2 override"
        }
        requireSha256(planSha256, "plan")
        requireSha256(evidenceIndexSha256, "evidence index")
        requireSha256(guardianRollbackEvidenceSha256, "Guardian rollback evidence")
        require(!evidenceIndexSha256.all { it == '0' })
        require(!guardianRollbackEvidenceSha256.all { it == '0' })
        require(
            planIdentityHash(cpuMaxKhz, gpuMaxPowerLevel, fanMode) == planSha256,
        ) { "qualification plan hash mismatch" }
        manualFanOverride?.let { override ->
            require(fanMode == TuningPlanner.FAN_SMART) {
                "manual fan override requires vendor Smart profile authority"
            }
            require(
                planIdentityHash(cpuMaxKhz, gpuMaxPowerLevel, override.fanMode) ==
                    override.planSha256,
            ) { "manual fan override plan hash mismatch" }
        }
        // WAVE2 Stage B / schema v3 admission (spec S3, rules 1-2; rules 3-4 are already enforced
        // by QualifiedDerateRung's own init above -- planSha256 identity and non-zero evidence).
        // A malformed rung throws here, which QualificationManifestCodec.decodePayload's
        // runCatching turns into a decode failure for the WHOLE payload -- never a skipped rung.
        require(derateRungs.size <= MAX_DERATE_RUNGS)
        // Rule 1: every rung is dominated by the BASE PLAN ITSELF -- CPU slower-or-equal per
        // policy, GPU higher-or-equal (inverted: larger index is slower), fan mode identical
        // (generalized beyond the spec's own concrete "policy6" example to EVERY CPU policy the
        // base plan carries -- strictly safer, and a no-op for today's ladder data since
        // ThermalDerateLadder pins policy0 equal to nominal on every rung already; this guards
        // against any future ladder that varies more than one CPU policy). Checked independently
        // against the FIXED base plan, deliberately NOT the previous rung -- rule 2 below is the
        // separate, independent check for rung-to-rung ordering, so the two failure modes (a rung
        // faster than the signed nominal, vs. rungs merely out of order) stay independently
        // provable and independently revertible.
        derateRungs.forEachIndexed { index, rung ->
            require(rung.cpuMaxKhz.keys == cpuMaxKhz.keys) {
                "derate rung ${index + 1} CPU policy set must match the base plan exactly"
            }
            require(rung.cpuMaxKhz.all { (policyId, khz) -> khz <= cpuMaxKhz.getValue(policyId) }) {
                "derate rung ${index + 1} must never be faster than the base plan on any CPU policy"
            }
            require((gpuMaxPowerLevel == null) == (rung.gpuMaxPowerLevel == null)) {
                "derate rung ${index + 1} GPU axis presence must match the base plan"
            }
            if (rung.gpuMaxPowerLevel != null) {
                require(rung.gpuMaxPowerLevel >= checkNotNull(gpuMaxPowerLevel)) {
                    "derate rung ${index + 1} must never be faster (lower max_pwrlevel) than the base plan"
                }
            }
            require(rung.fanMode == fanMode) {
                "derate rung ${index + 1} fan mode must be identical to the base plan"
            }
        }
        // Rule 2: rungs are ORDERED and monotonically slower -- each rung no faster than the ONE
        // immediately before it. Deliberately compares ONLY adjacent pairs WITHIN [derateRungs]
        // itself (zipWithNext over the rung list alone) and never chains from the base plan the
        // way rule 1 does -- if this loop instead seeded its first comparison from the base (as
        // an earlier draft of this admission did), it would transitively re-derive rule 1's own
        // guarantee for every rung by induction (rung[i] <= rung[i-1] <= ... <= base), making
        // rule 1 mathematically redundant and impossible to revert independently of rule 2. Kept
        // strictly separate so the two failure modes stay independently provable: a lone rung
        // faster than the signed nominal (rule 1) vs. two individually-dominated rungs merely out
        // of order relative to EACH OTHER (rule 2) -- e.g. rung 2 faster than rung 1 while both
        // remain individually slower than nominal, which rule 1 alone would admit. Consecutive
        // rungs may hold either axis flat (see SEQ9's real ladder data, e.g. quiet rung1->rung2
        // keeps CPU flat while only GPU steps) -- "monotonically slower" is never-faster per
        // axis, not "both axes must move on every single step".
        derateRungs.zipWithNext().forEachIndexed { index, (previous, rung) ->
            require(rung.cpuMaxKhz.all { (policyId, khz) -> khz <= previous.cpuMaxKhz.getValue(policyId) }) {
                "derate rung ${index + 2} must never be faster than the rung immediately before it " +
                    "on any CPU policy"
            }
            if (rung.gpuMaxPowerLevel != null) {
                require(rung.gpuMaxPowerLevel >= checkNotNull(previous.gpuMaxPowerLevel)) {
                    "derate rung ${index + 2} must never be faster (lower max_pwrlevel) than the " +
                        "rung immediately before it"
                }
            }
        }
    }

    companion object {
        internal const val MAX_CPU_POLICIES = 32
        /** Generous headroom over the 3-rung ladders in production today; not a measured limit. */
        internal const val MAX_DERATE_RUNGS = 16

        fun create(
            profileId: String,
            cpuMaxKhz: Map<Int, Int>,
            gpuMaxPowerLevel: Int?,
            fanMode: Int?,
            evidenceIndexSha256: String,
            guardianRollbackEvidenceSha256: String,
            manualFanOverride: QualifiedManualFanOverride? = null,
            derateRungs: List<QualifiedDerateRung> = emptyList(),
        ): QualifiedProfilePlan {
            val pinnedCpu = Collections.unmodifiableMap(cpuMaxKhz.toSortedMap())
            return QualifiedProfilePlan(
                profileId = profileId,
                cpuMaxKhz = pinnedCpu,
                gpuMaxPowerLevel = gpuMaxPowerLevel,
                fanMode = fanMode,
                planSha256 = planIdentityHash(pinnedCpu, gpuMaxPowerLevel, fanMode),
                evidenceIndexSha256 = evidenceIndexSha256,
                guardianRollbackEvidenceSha256 = guardianRollbackEvidenceSha256,
                manualFanOverride = manualFanOverride,
                derateRungs = derateRungs,
            )
        }
    }
}

internal data class QualificationManifest(
    val schemaVersion: Int = QUALIFICATION_MANIFEST_SCHEMA,
    val keyId: String,
    val sequence: Long,
    val hardwareFingerprint: String,
    val deviceProfile: String,
    val servicePlanSchema: Int = QUALIFICATION_SERVICE_PLAN_SCHEMA,
    val profiles: List<QualifiedProfilePlan>,
) {
    init {
        require(schemaVersion in LEGACY_QUALIFICATION_MANIFEST_SCHEMA..QUALIFICATION_MANIFEST_SCHEMA)
        requireSha256(keyId, "qualification key ID")
        require(sequence > 0)
        requireSha256(hardwareFingerprint, "hardware fingerprint")
        require(DEVICE_PROFILE.matches(deviceProfile))
        // Extended for schema v3 (WAVE2 Stage B), not replaced: v1 and v2 pairings are unchanged,
        // so a v2 manifest with no rungs stays valid forever regardless of what schema is current.
        require(
            schemaVersion == LEGACY_QUALIFICATION_MANIFEST_SCHEMA &&
                servicePlanSchema == LEGACY_QUALIFICATION_SERVICE_PLAN_SCHEMA ||
                schemaVersion == QUALIFICATION_MANIFEST_SCHEMA_V2 &&
                servicePlanSchema == QUALIFICATION_SERVICE_PLAN_SCHEMA_V2 ||
                schemaVersion == QUALIFICATION_MANIFEST_SCHEMA &&
                servicePlanSchema == QUALIFICATION_SERVICE_PLAN_SCHEMA,
        )
        if (schemaVersion == LEGACY_QUALIFICATION_MANIFEST_SCHEMA) {
            require(profiles.none { it.manualFanOverride != null })
        }
        // derateRungs is a schema-v3 field; v1 and v2 (including v2's manualFanOverride) never
        // carry one, exactly as v1 never carries a manualFanOverride above.
        if (schemaVersion <= QUALIFICATION_MANIFEST_SCHEMA_V2) {
            require(profiles.none { it.derateRungs.isNotEmpty() })
        }
        if (profiles.any { it.manualFanOverride != null }) {
            require(sequence >= MANUAL_FAN_OVERRIDE_MIN_SEQUENCE)
        }
        require(profiles.isNotEmpty() && profiles.size <= TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS.size)
        require(profiles.map { it.profileId } == profiles.map { it.profileId }.sorted())
        require(profiles.map { it.profileId }.distinct().size == profiles.size)
    }

    private companion object {
        val DEVICE_PROFILE = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

internal class SignedQualificationManifest internal constructor(
    payload: ByteArray,
    signature: ByteArray,
) {
    private val pinnedPayload = payload.copyOf()
    private val pinnedSignature = signature.copyOf()

    val payload: ByteArray get() = pinnedPayload.copyOf()
    val signature: ByteArray get() = pinnedSignature.copyOf()
}

/** Strict fixed-order binary grammar. Fixed fields make duplicate-key ambiguity impossible. */
internal object QualificationManifestCodec {
    private const val PAYLOAD_MAGIC = 0x4D514D50 // MQMP
    private const val SIGNED_MAGIC = 0x4D514D53 // MQMS
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 256
    private const val ED25519_SIGNATURE_BYTES = 64
    internal const val MAX_PAYLOAD_BYTES = 32 * 1024
    internal const val MAX_SIGNED_BYTES = MAX_PAYLOAD_BYTES + 128

    fun encodePayload(manifest: QualificationManifest): ByteArray =
        ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeInt(PAYLOAD_MAGIC)
                out.writeInt(VERSION)
                out.writeInt(manifest.schemaVersion)
                out.writeUtf8(manifest.keyId)
                out.writeLong(manifest.sequence)
                out.writeUtf8(manifest.hardwareFingerprint)
                out.writeUtf8(manifest.deviceProfile)
                out.writeInt(manifest.servicePlanSchema)
                out.writeInt(manifest.profiles.size)
                manifest.profiles.forEach { profile ->
                    out.writeUtf8(profile.profileId)
                    out.writeInt(profile.cpuMaxKhz.size)
                    profile.cpuMaxKhz.forEach { (policyId, khz) ->
                        out.writeInt(policyId)
                        out.writeInt(khz)
                    }
                    out.writeNullableInt(profile.gpuMaxPowerLevel)
                    out.writeNullableInt(profile.fanMode)
                    out.writeUtf8(profile.planSha256)
                    out.writeUtf8(profile.evidenceIndexSha256)
                    out.writeUtf8(profile.guardianRollbackEvidenceSha256)
                    // Fixed at the v2 threshold, not "current" -- see QUALIFICATION_MANIFEST_SCHEMA_V2.
                    if (manifest.schemaVersion >= QUALIFICATION_MANIFEST_SCHEMA_V2) {
                        val manual = profile.manualFanOverride
                        out.writeBoolean(manual != null)
                        if (manual != null) {
                            out.writeInt(manual.fanMode)
                            out.writeInt(manual.minimumDutyPercent)
                            out.writeUtf8(manual.planSha256)
                            out.writeUtf8(manual.evidenceIndexSha256)
                            out.writeUtf8(manual.guardianRollbackEvidenceSha256)
                        }
                    }
                    // WAVE2 Stage B / schema v3: ordered qualified derate rungs, appended after
                    // the schema-v2 manualFanOverride block so a v2 payload's bytes are unchanged.
                    if (manifest.schemaVersion >= QUALIFICATION_MANIFEST_SCHEMA) {
                        out.writeInt(profile.derateRungs.size)
                        profile.derateRungs.forEach { rung ->
                            out.writeInt(rung.cpuMaxKhz.size)
                            rung.cpuMaxKhz.forEach { (policyId, khz) ->
                                out.writeInt(policyId)
                                out.writeInt(khz)
                            }
                            out.writeNullableInt(rung.gpuMaxPowerLevel)
                            out.writeNullableInt(rung.fanMode)
                            out.writeUtf8(rung.planSha256)
                            out.writeUtf8(rung.evidenceIndexSha256)
                            out.writeUtf8(rung.guardianRollbackEvidenceSha256)
                        }
                    }
                }
            }
        }.toByteArray().also { require(it.size in 1..MAX_PAYLOAD_BYTES) }

    fun decodePayload(bytes: ByteArray): QualificationManifest? = runCatching {
        require(bytes.size in 1..MAX_PAYLOAD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_MAGIC)
            require(input.readInt() == VERSION)
            val schema = input.readInt()
            val keyId = input.readUtf8(64)
            val sequence = input.readLong()
            val fingerprint = input.readUtf8(64)
            val deviceProfile = input.readUtf8(128)
            val servicePlanSchema = input.readInt()
            val profileCount = input.readInt()
            require(profileCount in 1..TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS.size)
            val profiles = ArrayList<QualifiedProfilePlan>(profileCount)
            repeat(profileCount) {
                val profileId = input.readUtf8(32)
                val cpuCount = input.readInt()
                require(cpuCount in 1..QualifiedProfilePlan.MAX_CPU_POLICIES)
                val cpu = linkedMapOf<Int, Int>()
                var previousPolicy = -1
                repeat(cpuCount) {
                    val policyId = input.readInt()
                    val khz = input.readInt()
                    require(policyId > previousPolicy && khz > 0)
                    previousPolicy = policyId
                    require(cpu.put(policyId, khz) == null)
                }
                val gpuMaxPowerLevel = input.readNullableInt()
                val fanMode = input.readNullableInt()
                val planSha256 = input.readUtf8(64)
                val evidenceIndexSha256 = input.readUtf8(64)
                val guardianRollbackEvidenceSha256 = input.readUtf8(64)
                // Fixed at the v2 threshold, not "current" -- see QUALIFICATION_MANIFEST_SCHEMA_V2.
                val manualFanOverride = if (schema >= QUALIFICATION_MANIFEST_SCHEMA_V2) {
                    if (input.readBoolean()) {
                        QualifiedManualFanOverride(
                            fanMode = input.readInt(),
                            minimumDutyPercent = input.readInt(),
                            planSha256 = input.readUtf8(64),
                            evidenceIndexSha256 = input.readUtf8(64),
                            guardianRollbackEvidenceSha256 = input.readUtf8(64),
                        )
                    } else null
                } else null
                // WAVE2 Stage B / schema v3: mirrors the manualFanOverride block immediately
                // above. A malformed rung throws inside QualifiedDerateRung/QualifiedProfilePlan
                // construction below, which the enclosing runCatching turns into a decode
                // failure for the WHOLE payload -- never a silently skipped rung.
                val derateRungs = if (schema >= QUALIFICATION_MANIFEST_SCHEMA) {
                    val rungCount = input.readInt()
                    require(rungCount in 0..QualifiedProfilePlan.MAX_DERATE_RUNGS)
                    (0 until rungCount).map {
                        val rungCpuCount = input.readInt()
                        require(rungCpuCount in 1..QualifiedProfilePlan.MAX_CPU_POLICIES)
                        val rungCpu = linkedMapOf<Int, Int>()
                        var previousRungPolicy = -1
                        repeat(rungCpuCount) {
                            val policyId = input.readInt()
                            val khz = input.readInt()
                            require(policyId > previousRungPolicy && khz > 0)
                            previousRungPolicy = policyId
                            require(rungCpu.put(policyId, khz) == null)
                        }
                        QualifiedDerateRung(
                            cpuMaxKhz = Collections.unmodifiableMap(rungCpu),
                            gpuMaxPowerLevel = input.readNullableInt(),
                            fanMode = input.readNullableInt(),
                            planSha256 = input.readUtf8(64),
                            evidenceIndexSha256 = input.readUtf8(64),
                            guardianRollbackEvidenceSha256 = input.readUtf8(64),
                        )
                    }
                } else {
                    emptyList()
                }
                profiles += QualifiedProfilePlan(
                    profileId = profileId,
                    cpuMaxKhz = Collections.unmodifiableMap(cpu),
                    gpuMaxPowerLevel = gpuMaxPowerLevel,
                    fanMode = fanMode,
                    planSha256 = planSha256,
                    evidenceIndexSha256 = evidenceIndexSha256,
                    guardianRollbackEvidenceSha256 = guardianRollbackEvidenceSha256,
                    manualFanOverride = manualFanOverride,
                    derateRungs = Collections.unmodifiableList(derateRungs),
                )
            }
            require(input.read() == -1)
            QualificationManifest(
                schemaVersion = schema,
                keyId = keyId,
                sequence = sequence,
                hardwareFingerprint = fingerprint,
                deviceProfile = deviceProfile,
                servicePlanSchema = servicePlanSchema,
                profiles = Collections.unmodifiableList(profiles),
            ).also { decoded ->
                require(encodePayload(decoded).contentEquals(bytes))
            }
        }
    }.getOrNull()

    fun encodeSigned(signed: SignedQualificationManifest): ByteArray {
        val payload = signed.payload
        val signature = signed.signature
        require(payload.size in 1..MAX_PAYLOAD_BYTES)
        require(signature.size == ED25519_SIGNATURE_BYTES)
        return ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeInt(SIGNED_MAGIC)
                out.writeInt(VERSION)
                out.writeInt(payload.size)
                out.write(payload)
                out.writeInt(signature.size)
                out.write(signature)
            }
        }.toByteArray().also { require(it.size in 1..MAX_SIGNED_BYTES) }
    }

    fun decodeSigned(bytes: ByteArray): SignedQualificationManifest? = runCatching {
        require(bytes.size in 1..MAX_SIGNED_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == SIGNED_MAGIC)
            require(input.readInt() == VERSION)
            val payload = input.readBytes(MAX_PAYLOAD_BYTES)
            val signature = input.readBytes(ED25519_SIGNATURE_BYTES)
            require(signature.size == ED25519_SIGNATURE_BYTES)
            require(input.read() == -1)
            require(decodePayload(payload) != null)
            SignedQualificationManifest(payload, signature).also { decoded ->
                require(encodeSigned(decoded).contentEquals(bytes))
            }
        }
    }.getOrNull()

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf8(maxBytes: Int): String {
        val bytes = readBytes(maxBytes)
        val value = bytes.toString(Charsets.UTF_8)
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        return value
    }

    private fun DataInputStream.readBytes(maxBytes: Int): ByteArray {
        val count = readInt()
        require(count in 1..maxBytes)
        return ByteArray(count).also(::readFully)
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        value?.let(::writeInt)
    }

    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null
}

/** Product source slot. It intentionally contains no key until a public key is configured. */
internal object ProductQualificationTrustAnchor {
    val value: QualificationTrustAnchor = QualificationTrustAnchor(emptyMap())
}

internal class QualificationTrustAnchor(keys: Map<String, ByteArray>) {
    private val pinned: Map<String, PublicKey> = Collections.unmodifiableMap(
        keys.mapValues { (_, encoded) -> EncodedEd25519PublicKey(encoded) }.toMap(),
    )

    init {
        pinned.forEach { (keyId, publicKey) ->
            requireSha256(keyId, "qualification key ID")
            require(sha256Hex(publicKey.encoded) == keyId)
        }
    }

    fun key(keyId: String): PublicKey? = pinned[keyId]
    fun isEmpty(): Boolean = pinned.isEmpty()
}

private class EncodedEd25519PublicKey(encoded: ByteArray) : PublicKey {
    private val pinned = encoded.copyOf().also { require(ed25519RawPublicKey(it) != null) }

    override fun getAlgorithm(): String = "Ed25519"
    override fun getFormat(): String = "X.509"
    override fun getEncoded(): ByteArray = pinned.copyOf()
}

internal fun ed25519RawPublicKey(encoded: ByteArray): ByteArray? {
    if (encoded.size != ED25519_SPKI_PREFIX.size + ED25519_PUBLIC_KEY_BYTES) return null
    if (!encoded.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)) return null
    return encoded.copyOfRange(ED25519_SPKI_PREFIX.size, encoded.size)
}

internal enum class QualificationSequenceAdmission {
    ACCEPTED_NEW,
    ACCEPTED_IDEMPOTENT,
    REJECTED_ROLLBACK,
    REJECTED_CONFLICT,
    REJECTED_UNAVAILABLE,
}

internal fun interface QualificationSequenceStore {
    fun admit(sequence: Long, payloadSha256: String): QualificationSequenceAdmission
}

internal object UnavailableQualificationSequenceStore : QualificationSequenceStore {
    override fun admit(sequence: Long, payloadSha256: String) =
        QualificationSequenceAdmission.REJECTED_UNAVAILABLE
}

/** Device-protected caller chooses the directory; malformed or partial truth is never reset. */
internal class FileQualificationSequenceStore(
    private val directory: File,
    private val atomicMove: (File, File) -> Unit = { source, destination ->
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        Unit
    },
) : QualificationSequenceStore {
    private val record = File(directory, RECORD_NAME)
    private val staging = File(directory, ".$RECORD_NAME.tmp")
    private val lock = File(directory, ".$RECORD_NAME.lock")

    init {
        require(directory.isAbsolute)
    }

    @Synchronized
    override fun admit(sequence: Long, payloadSha256: String): QualificationSequenceAdmission {
        if (sequence <= 0 || !isSha256(payloadSha256)) {
            return QualificationSequenceAdmission.REJECTED_UNAVAILABLE
        }
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) {
                return@runCatching QualificationSequenceAdmission.REJECTED_UNAVAILABLE
            }
            require(directory.isDirectory)
            RandomAccessFile(lock, "rw").channel.use { channel ->
                channel.lock().use {
                    admitLocked(sequence, payloadSha256)
                }
            }
        }.getOrDefault(QualificationSequenceAdmission.REJECTED_UNAVAILABLE)
    }

    private fun admitLocked(
        sequence: Long,
        payloadSha256: String,
    ): QualificationSequenceAdmission {
        if (staging.exists()) {
            return QualificationSequenceAdmission.REJECTED_UNAVAILABLE
        }
        val existing = load() ?: if (record.exists()) {
            return QualificationSequenceAdmission.REJECTED_UNAVAILABLE
        } else null
        if (existing != null) {
            return when {
                sequence < existing.sequence -> QualificationSequenceAdmission.REJECTED_ROLLBACK
                sequence == existing.sequence && payloadSha256 == existing.payloadSha256 ->
                    QualificationSequenceAdmission.ACCEPTED_IDEMPOTENT
                sequence == existing.sequence -> QualificationSequenceAdmission.REJECTED_CONFLICT
                else -> persist(sequence, payloadSha256)
            }
        }
        return persist(sequence, payloadSha256)
    }

    private fun persist(sequence: Long, digest: String): QualificationSequenceAdmission = runCatching {
        val encoded = encode(sequence, digest)
        FileOutputStream(staging).use { output ->
            output.write(encoded)
            output.flush()
            output.fd.sync()
        }
        atomicMove(staging, record)
        syncParentBestEffort()
        val exact = load()
        require(exact?.sequence == sequence && exact.payloadSha256 == digest)
        QualificationSequenceAdmission.ACCEPTED_NEW
    }.getOrElse {
        runCatching { if (staging.exists()) staging.delete() }
        QualificationSequenceAdmission.REJECTED_UNAVAILABLE
    }

    private fun load(): SequenceRecord? = runCatching {
        if (!record.exists() || record.length() !in 1..MAX_RECORD_BYTES.toLong()) return@runCatching null
        FileInputStream(record).use { input ->
            val bytes = input.readBytes()
            require(bytes.size == record.length().toInt())
            decode(bytes)
        }
    }.getOrNull()

    private fun syncParentBestEffort() {
        runCatching { FileInputStream(directory).use { it.fd.sync() } }
    }

    private data class SequenceRecord(val sequence: Long, val payloadSha256: String)

    private fun encode(sequence: Long, digest: String): ByteArray =
        ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeInt(SEQUENCE_MAGIC)
                out.writeInt(SEQUENCE_VERSION)
                out.writeLong(sequence)
                val bytes = digest.toByteArray(Charsets.US_ASCII)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }.toByteArray()

    private fun decode(bytes: ByteArray): SequenceRecord = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == SEQUENCE_MAGIC)
        require(input.readInt() == SEQUENCE_VERSION)
        val sequence = input.readLong()
        val count = input.readInt()
        require(sequence > 0 && count == 64)
        val digestBytes = ByteArray(count).also(input::readFully)
        require(input.read() == -1)
        val digest = digestBytes.toString(Charsets.US_ASCII)
        require(isSha256(digest))
        SequenceRecord(sequence, digest).also { require(encode(it.sequence, it.payloadSha256).contentEquals(bytes)) }
    }

    private companion object {
        const val RECORD_NAME = "mimir_product_qualification_sequence.v1"
        const val SEQUENCE_MAGIC = 0x4D515351 // MQSQ
        const val SEQUENCE_VERSION = 1
        const val MAX_RECORD_BYTES = 128
    }
}

internal enum class QualificationRejection {
    MALFORMED,
    UNKNOWN_KEY,
    INVALID_SIGNATURE,
    DEVICE_MISMATCH,
    ILLEGAL_PLAN,
    /**
     * TR-02: this sequence is strictly behind the store's already-admitted sequence for this
     * device -- a genuine replay of an old, previously-superseded manifest.
     */
    SEQUENCE_ROLLBACK,
    /**
     * TR-02: this sequence matches the store's already-admitted sequence but the payload digest
     * does not -- two different manifests claiming the same sequence number.
     */
    SEQUENCE_CONFLICT,
    /**
     * TR-02: the sequence store itself could not admit or deny cleanly -- corrupt/partial record,
     * a leftover staging file, or a persistence failure. Split out from [SEQUENCE_ROLLBACK] /
     * [SEQUENCE_CONFLICT] because it is not evidence of a replay attack: a leftover
     * `.qualification.tmp` staging file used to read identically to one before this split.
     */
    SEQUENCE_STORE_UNAVAILABLE,
}

internal sealed interface QualificationVerification {
    data class Accepted(val authority: ProductQualificationAuthority) : QualificationVerification
    data class Rejected(val reason: QualificationRejection) : QualificationVerification
}

internal interface ProductQualificationAuthority {
    fun certifiedProfileIds(hardwareFingerprint: String): Set<String>
    fun qualifiedPlan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
    ): TuningPlan?

    fun qualifiedManualFanPlan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
    ): QualifiedManualFanPlan? = null

    /**
     * WAVE2 Stage B: the SIGNED plan for rung [rung] (1-based, matching
     * [ThermalDerateLadder.at]'s convention) of [profileId], or null if that rung is not signed
     * for this exact hardware/profile. Actuation SELECTS this plan; it never derives one.
     */
    fun qualifiedDerateRungPlan(
        profileId: String,
        packageName: String,
        rung: Int,
        capabilities: HardwareCapabilities,
    ): TuningPlan? = null

    data object Unavailable : ProductQualificationAuthority {
        override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()
        override fun qualifiedPlan(
            profileId: String,
            packageName: String,
            capabilities: HardwareCapabilities,
        ): TuningPlan? = null
    }
}

internal data class QualifiedManualFanPlan(
    val plan: TuningPlan,
    val minimumDutyPercent: Int,
)

internal object QualificationManifestVerifier {
    fun verify(
        signedBytes: ByteArray,
        trustAnchor: QualificationTrustAnchor,
        sequenceStore: QualificationSequenceStore,
        capabilities: HardwareCapabilities,
    ): QualificationVerification {
        val signed = QualificationManifestCodec.decodeSigned(signedBytes)
            ?: return QualificationVerification.Rejected(QualificationRejection.MALFORMED)
        val payload = signed.payload
        val manifest = QualificationManifestCodec.decodePayload(payload)
            ?: return QualificationVerification.Rejected(QualificationRejection.MALFORMED)
        val key = trustAnchor.key(manifest.keyId)
            ?: return QualificationVerification.Rejected(QualificationRejection.UNKNOWN_KEY)
        val rawKey = ed25519RawPublicKey(key.encoded)
            ?: return QualificationVerification.Rejected(QualificationRejection.UNKNOWN_KEY)
        val signatureValid = runCatching {
            Ed25519Verify(rawKey).verify(signed.signature, payload)
            true
        }.getOrDefault(false)
        if (!signatureValid) {
            return QualificationVerification.Rejected(QualificationRejection.INVALID_SIGNATURE)
        }
        if (
            manifest.hardwareFingerprint != HardwareFingerprint.create(capabilities) ||
            manifest.deviceProfile != capabilities.deviceProfileId
        ) {
            return QualificationVerification.Rejected(QualificationRejection.DEVICE_MISMATCH)
        }
        if (!QualificationPlanPolicy.allows(manifest, capabilities)) {
            return QualificationVerification.Rejected(QualificationRejection.ILLEGAL_PLAN)
        }
        val payloadDigest = sha256Hex(payload)
        // TR-02: REPLAY_OR_SEQUENCE_UNAVAILABLE used to collapse three distinct
        // QualificationSequenceAdmission outcomes into one rejection -- a leftover
        // `.qualification.tmp` staging file (REJECTED_UNAVAILABLE, a permanent local I/O state)
        // read identically to an actual replayed manifest (REJECTED_ROLLBACK). This mapping does
        // not change which outcomes are accepted vs rejected -- only which QualificationRejection
        // a caller sees for an already-rejected sequence.
        val sequenceAdmission = sequenceStore.admit(manifest.sequence, payloadDigest)
        val sequenceRejection = when (sequenceAdmission) {
            QualificationSequenceAdmission.ACCEPTED_NEW,
            QualificationSequenceAdmission.ACCEPTED_IDEMPOTENT,
            -> null
            QualificationSequenceAdmission.REJECTED_ROLLBACK -> QualificationRejection.SEQUENCE_ROLLBACK
            QualificationSequenceAdmission.REJECTED_CONFLICT -> QualificationRejection.SEQUENCE_CONFLICT
            QualificationSequenceAdmission.REJECTED_UNAVAILABLE -> QualificationRejection.SEQUENCE_STORE_UNAVAILABLE
        }
        if (sequenceRejection != null) {
            return QualificationVerification.Rejected(sequenceRejection)
        }
        return QualificationVerification.Accepted(
            VerifiedProductQualificationAuthority(manifest),
        )
    }
}

private object QualificationPlanPolicy {
    fun allows(manifest: QualificationManifest, capabilities: HardwareCapabilities): Boolean {
        if (
            manifest.schemaVersion !in LEGACY_QUALIFICATION_MANIFEST_SCHEMA..QUALIFICATION_MANIFEST_SCHEMA ||
            manifest.servicePlanSchema !in
                LEGACY_QUALIFICATION_SERVICE_PLAN_SCHEMA..QUALIFICATION_SERVICE_PLAN_SCHEMA ||
            capabilities.status == CapabilityStatus.UNSUPPORTED ||
            !capabilities.pServerAvailable
        ) {
            return false
        }
        return allowsProfiles(manifest.profiles, capabilities)
    }

    fun allowsProfiles(
        profiles: Collection<QualifiedProfilePlan>,
        capabilities: HardwareCapabilities,
    ): Boolean {
        if (capabilities.status == CapabilityStatus.UNSUPPORTED || !capabilities.pServerAvailable) {
            return false
        }
        val policies = capabilities.cpuPolicies.associateBy { it.id }
        if (policies.isEmpty()) return false
        return profiles.isNotEmpty() && profiles.all { profile ->
            profile.profileId in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS &&
                profile.cpuMaxKhz.keys == policies.keys &&
                profile.cpuMaxKhz.all { (id, khz) ->
                    val policy = policies[id] ?: return@all false
                    khz in policy.availableFrequenciesKhz && khz in policy.minKhz..policy.maxKhz
                } &&
                when {
                    TuningAxis.GPU in capabilities.supportedAxes && capabilities.gpuNodeAvailable ->
                        profile.gpuMaxPowerLevel != null &&
                            profile.gpuMaxPowerLevel in capabilities.gpuFrequenciesKhz.indices
                    else -> profile.gpuMaxPowerLevel == null
                } &&
                when {
                    TuningAxis.FAN in capabilities.supportedAxes && capabilities.fanNodeAvailable ->
                        profile.fanMode == null ||
                            (
                                profile.fanMode in TuningPlanner.KNOWN_FAN_MODES &&
                                    profile.fanMode != TuningPlanner.FAN_MANUAL
                            )
                    else -> profile.fanMode == null
                } &&
                profile.planSha256 == planIdentityHash(
                    profile.cpuMaxKhz,
                    profile.gpuMaxPowerLevel,
                    profile.fanMode,
                ) && manualOverrideAllowed(profile, capabilities) &&
                derateRungsAllowed(profile, capabilities)
        }
    }

    /**
     * WAVE2 Stage B: the same live-capability re-check [allowsProfiles] already performs on the
     * base plan, extended to every signed rung -- mirrors [manualOverrideAllowed]'s placement and
     * "the whole bundle admits or rejects together" posture exactly. If live capabilities cannot
     * satisfy every signed rung of a profile (for example, a firmware update removed an OPP), the
     * WHOLE manifest is rejected, consistent with how a manualFanOverride mismatch already behaves.
     */
    private fun derateRungsAllowed(
        profile: QualifiedProfilePlan,
        capabilities: HardwareCapabilities,
    ): Boolean {
        if (profile.derateRungs.isEmpty()) return true
        val policies = capabilities.cpuPolicies.associateBy { it.id }
        return profile.derateRungs.all { rung ->
            rung.cpuMaxKhz.keys == policies.keys &&
                rung.cpuMaxKhz.all { (id, khz) ->
                    val policy = policies[id] ?: return@all false
                    khz in policy.availableFrequenciesKhz && khz in policy.minKhz..policy.maxKhz
                } &&
                when {
                    TuningAxis.GPU in capabilities.supportedAxes && capabilities.gpuNodeAvailable ->
                        rung.gpuMaxPowerLevel != null &&
                            rung.gpuMaxPowerLevel in capabilities.gpuFrequenciesKhz.indices
                    else -> rung.gpuMaxPowerLevel == null
                } &&
                rung.planSha256 == planIdentityHash(rung.cpuMaxKhz, rung.gpuMaxPowerLevel, rung.fanMode)
        }
    }

    private fun manualOverrideAllowed(
        profile: QualifiedProfilePlan,
        capabilities: HardwareCapabilities,
    ): Boolean {
        val manual = profile.manualFanOverride ?: return true
        // the 20% floor is retired for every profile, so this
        // manual-override floor is unconditionally FanPwmBounds.MIN_SPIN_PERCENT (0) now. This
        // specific field was already 0 for "quiet" (unchanged for quiet here); balanced/
        // performance move from 20 to 0. Quiet's separate CONTROLLER floor
        // (FanThermalProfile.minSpinPercent, see FanThermalControl.kt) was still 20 before this change and changes too -- do not read this as "quiet's behavior is unchanged". This
        // affects FUTURE manual-override manifests only -- it does not alter planSha256 or any
        // other signed plan identity.
        val requiredFloor = when (profile.profileId) {
            "quiet", "balanced", "performance" -> FanPwmBounds.MIN_SPIN_PERCENT
            else -> return false
        }
        return capabilities.deviceProfileId == CpuCeilingTransport.ODIN_3_PROFILE &&
            profile.fanMode == TuningPlanner.FAN_SMART &&
            manual.fanMode == TuningPlanner.FAN_MANUAL &&
            manual.minimumDutyPercent == requiredFloor &&
            manual.planSha256 == planIdentityHash(
                profile.cpuMaxKhz,
                profile.gpuMaxPowerLevel,
                TuningPlanner.FAN_MANUAL,
            )
    }
}

private class VerifiedProductQualificationAuthority(
    manifest: QualificationManifest,
) : ProductQualificationAuthority {
    private val hardwareFingerprint = manifest.hardwareFingerprint
    private val deviceProfile = manifest.deviceProfile
    private val profiles = Collections.unmodifiableMap(
        manifest.profiles.associateBy { it.profileId }.mapValues { (_, profile) ->
            profile.copy(cpuMaxKhz = Collections.unmodifiableMap(profile.cpuMaxKhz.toSortedMap()))
        },
    )

    override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> =
        if (hardwareFingerprint == this.hardwareFingerprint) {
            Collections.unmodifiableSet(profiles.keys.toSet())
        } else {
            emptySet()
        }

    override fun qualifiedPlan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
    ): TuningPlan? {
        if (
            HardwareFingerprint.create(capabilities) != hardwareFingerprint ||
            capabilities.deviceProfileId != deviceProfile
        ) {
            return null
        }
        if (!QualificationPlanPolicy.allowsProfiles(profiles.values, capabilities)) return null
        val profile = profiles[profileId] ?: return null
        return TuningPlan(
            profileId = profile.profileId,
            packageName = packageName,
            cpuMaxKhz = profile.cpuMaxKhz,
            gpuMaxPowerLevel = profile.gpuMaxPowerLevel,
            fanMode = profile.fanMode,
            calibrated = true,
        )
    }

    override fun qualifiedManualFanPlan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
    ): QualifiedManualFanPlan? {
        if (
            HardwareFingerprint.create(capabilities) != hardwareFingerprint ||
            capabilities.deviceProfileId != deviceProfile ||
            !QualificationPlanPolicy.allowsProfiles(profiles.values, capabilities)
        ) {
            return null
        }
        val profile = profiles[profileId] ?: return null
        val manual = profile.manualFanOverride ?: return null
        return QualifiedManualFanPlan(
            plan = TuningPlan(
                profileId = profile.profileId,
                packageName = packageName,
                cpuMaxKhz = profile.cpuMaxKhz,
                gpuMaxPowerLevel = profile.gpuMaxPowerLevel,
                fanMode = manual.fanMode,
                calibrated = true,
            ),
            minimumDutyPercent = manual.minimumDutyPercent,
        )
    }

    override fun qualifiedDerateRungPlan(
        profileId: String,
        packageName: String,
        rung: Int,
        capabilities: HardwareCapabilities,
    ): TuningPlan? {
        if (
            HardwareFingerprint.create(capabilities) != hardwareFingerprint ||
            capabilities.deviceProfileId != deviceProfile ||
            !QualificationPlanPolicy.allowsProfiles(profiles.values, capabilities)
        ) {
            return null
        }
        val profile = profiles[profileId] ?: return null
        val signedRung = profile.derateRungs.getOrNull(rung - 1) ?: return null
        return TuningPlan(
            profileId = profile.profileId,
            packageName = packageName,
            cpuMaxKhz = signedRung.cpuMaxKhz,
            gpuMaxPowerLevel = signedRung.gpuMaxPowerLevel,
            fanMode = signedRung.fanMode,
            calibrated = true,
        )
    }
}

private fun requireSha256(value: String, label: String) =
    require(isSha256(value)) { "$label SHA-256 invalid" }

private fun isSha256(value: String): Boolean = Regex("[0-9a-f]{64}").matches(value)

private val ED25519_SPKI_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)
private const val ED25519_PUBLIC_KEY_BYTES = 32

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
