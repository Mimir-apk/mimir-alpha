package com.gruning.mimir.tweaker

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QualificationManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid exact-device signature exposes only manifest plans`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val manifest = manifest(capabilities, keyPair, sequence = 7)
        val accepted = verify(sign(manifest, keyPair), keyPair, capabilities)
        assertTrue(accepted is QualificationVerification.Accepted)
        val authority = (accepted as QualificationVerification.Accepted).authority
        val certification = CertificationStore(NoLocalCalibrationStore, authority)
        val fingerprint = HardwareFingerprint.create(capabilities)

        val productPosture = TweakerPostureTruth.posture == TweakerPostureTruth.PRODUCTION
        val expectedProfiles = if (productPosture) setOf("performance", "quiet") else emptySet()
        assertEquals(expectedProfiles, certification.certifiedProfileIds(fingerprint))
        assertEquals(emptySet<String>(), certification.certifiedProfileIds("0".repeat(64)))
        assertFalse(certification.isProfileCertified(fingerprint, "balanced"))

        val quiet = certification.qualifiedPlan("quiet", "org.example.game", capabilities)
        if (!productPosture) {
            assertNull(quiet)
            return
        }
        assertNotNull(quiet)
        assertEquals("org.example.game", quiet!!.packageName)
        assertEquals(mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800), quiet.cpuMaxKhz)
        assertEquals(1, quiet.gpuMaxPowerLevel)
        assertEquals(TuningPlanner.FAN_SILENT, quiet.fanMode)
        assertTrue(quiet.calibrated)
        assertTrue(certification.isPlanCertified(quiet, capabilities))
        assertFalse(certification.isPlanCertified(quiet.copy(cpuMaxKhz = quiet.cpuMaxKhz + (7 to 2_841_600)), capabilities))
        assertNull(certification.qualifiedPlan("balanced", "org.example.game", capabilities))
    }

    @Test
    fun `empty product anchor and unknown signer fail closed`() {
        assertTrue(ProductQualificationTrustAnchor.value.isEmpty())
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val bytes = sign(manifest(capabilities, keyPair), keyPair)

        assertRejected(
            QualificationRejection.UNKNOWN_KEY,
            QualificationManifestVerifier.verify(
                bytes,
                ProductQualificationTrustAnchor.value,
                sequenceStore(),
                capabilities,
            ),
        )
        assertEquals(emptySet<String>(), CertificationStore.NoOp.certifiedProfileIds(HardwareFingerprint.create(capabilities)))
    }

    @Test
    fun `one-bit signature change and noncanonical framing are rejected`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val signed = sign(manifest(capabilities, keyPair), keyPair)
        val oneBitSignature = signed.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }
        val guardianDigestOffset = signed.indexOf("b".repeat(64).toByteArray(Charsets.US_ASCII))
        assertTrue(guardianDigestOffset >= 0)
        val oneBitPayload = signed.copyOf().also {
            // ASCII `b` xor one bit is canonical lowercase hex `c`, so decoding still succeeds.
            it[guardianDigestOffset] = (it[guardianDigestOffset].toInt() xor 0x01).toByte()
        }

        assertRejected(QualificationRejection.INVALID_SIGNATURE, verify(oneBitSignature, keyPair, capabilities))
        assertRejected(QualificationRejection.INVALID_SIGNATURE, verify(oneBitPayload, keyPair, capabilities))
        assertRejected(QualificationRejection.MALFORMED, verify(signed + byteArrayOf(0), keyPair, capabilities))
        assertRejected(QualificationRejection.MALFORMED, verify(signed.copyOf(signed.size - 1), keyPair, capabilities))
    }

    @Test
    fun `exact fingerprint and device profile are both required`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val signed = sign(manifest(capabilities, keyPair), keyPair)

        assertRejected(
            QualificationRejection.DEVICE_MISMATCH,
            verify(signed, keyPair, capabilities.copy(buildFingerprint = "other/build")),
        )
        assertRejected(
            QualificationRejection.DEVICE_MISMATCH,
            verify(signed, keyPair, capabilities.copy(deviceProfileId = "not-rp5")),
        )
    }

    @Test
    fun `illegal CPU GPU fan and unavailable mutation service are rejected`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()

        val illegalCpu = profiles().map { profile ->
            if (profile.profileId == "quiet") {
                QualifiedProfilePlan.create(
                    profileId = profile.profileId,
                    cpuMaxKhz = profile.cpuMaxKhz + (7 to 999_999),
                    gpuMaxPowerLevel = profile.gpuMaxPowerLevel,
                    fanMode = profile.fanMode,
                    evidenceIndexSha256 = profile.evidenceIndexSha256,
                    guardianRollbackEvidenceSha256 = profile.guardianRollbackEvidenceSha256,
                )
            } else profile
        }
        assertIllegal(manifest(capabilities, keyPair, profiles = illegalCpu), keyPair, capabilities)

        val illegalGpu = profiles().map { profile ->
            if (profile.profileId == "quiet") {
                QualifiedProfilePlan.create(
                    profileId = profile.profileId,
                    cpuMaxKhz = profile.cpuMaxKhz,
                    gpuMaxPowerLevel = 9,
                    fanMode = profile.fanMode,
                    evidenceIndexSha256 = profile.evidenceIndexSha256,
                    guardianRollbackEvidenceSha256 = profile.guardianRollbackEvidenceSha256,
                )
            } else profile
        }
        assertIllegal(manifest(capabilities, keyPair, profiles = illegalGpu), keyPair, capabilities)

        val illegalFan = profiles().map { profile ->
            if (profile.profileId == "quiet") {
                profile.copy(fanMode = 7, planSha256 = planIdentityHash(profile.cpuMaxKhz, profile.gpuMaxPowerLevel, 7))
            } else profile
        }
        assertIllegal(manifest(capabilities, keyPair, profiles = illegalFan), keyPair, capabilities)

        val cpuGpuOnly = profiles().map { profile ->
            QualifiedProfilePlan.create(
                profileId = profile.profileId,
                cpuMaxKhz = profile.cpuMaxKhz,
                gpuMaxPowerLevel = profile.gpuMaxPowerLevel,
                fanMode = null,
                evidenceIndexSha256 = profile.evidenceIndexSha256,
                guardianRollbackEvidenceSha256 = profile.guardianRollbackEvidenceSha256,
            )
        }
        assertTrue(
            verify(
                sign(manifest(capabilities, keyPair, profiles = cpuGpuOnly), keyPair),
                keyPair,
                capabilities,
            ) is QualificationVerification.Accepted,
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            profiles().first().copy(
                fanMode = TuningPlanner.FAN_MANUAL,
                planSha256 = planIdentityHash(
                    profiles().first().cpuMaxKhz,
                    profiles().first().gpuMaxPowerLevel,
                    TuningPlanner.FAN_MANUAL,
                ),
            )
        }

        assertIllegal(
            manifest(capabilities, keyPair),
            keyPair,
            capabilities.copy(pServerAvailable = false),
        )
    }

    @Test
    fun `schema2 manual fan authority starts at sequence5 and keeps base Smart`() {
        val capabilities = testCapabilities().copy(
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        )
        val keyPair = keyPair()
        val cpu = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800)
        val manual = QualifiedManualFanOverride.create(
            cpuMaxKhz = cpu,
            gpuMaxPowerLevel = 1,
            minimumDutyPercent = 0,
            evidenceIndexSha256 = "f".repeat(64),
            guardianRollbackEvidenceSha256 = "1".repeat(64),
        )
        val quiet = QualifiedProfilePlan.create(
            profileId = "quiet",
            cpuMaxKhz = cpu,
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SMART,
            evidenceIndexSha256 = "d".repeat(64),
            guardianRollbackEvidenceSha256 = "e".repeat(64),
            manualFanOverride = manual,
        )

        assertThrows(IllegalArgumentException::class.java) {
            manifest(capabilities, keyPair, sequence = 4, profiles = listOf(quiet))
        }
        val accepted = verify(
            sign(manifest(capabilities, keyPair, sequence = 5, profiles = listOf(quiet)), keyPair),
            keyPair,
            capabilities,
        )
        assertTrue(accepted is QualificationVerification.Accepted)
        val authority = (accepted as QualificationVerification.Accepted).authority
        val manualPlan = authority.qualifiedManualFanPlan(
            "quiet",
            "com.gruning.mimir.rewrite",
            capabilities,
        )
        assertEquals(TuningPlanner.FAN_MANUAL, manualPlan?.plan?.fanMode)
        assertEquals(0, manualPlan?.minimumDutyPercent)
        assertEquals(
            TuningPlanner.FAN_SMART,
            authority.qualifiedPlan("quiet", "com.gruning.mimir.rewrite", capabilities)?.fanMode,
        )
    }

    @Test
    fun `schema v3 round trips with derate rungs and v1 v2 manifests remain byte decodable unmodified`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val fingerprint = HardwareFingerprint.create(capabilities)

        // v1: legacy, no manualFanOverride, no rungs -- unaffected by the v3 bump.
        val v1 = QualificationManifest(
            schemaVersion = LEGACY_QUALIFICATION_MANIFEST_SCHEMA,
            servicePlanSchema = LEGACY_QUALIFICATION_SERVICE_PLAN_SCHEMA,
            keyId = keyId(keyPair),
            sequence = 1,
            hardwareFingerprint = fingerprint,
            deviceProfile = capabilities.deviceProfileId,
            profiles = profiles(),
        )
        assertEquals(v1, QualificationManifestCodec.decodePayload(QualificationManifestCodec.encodePayload(v1)))
        assertTrue(v1.profiles.all { it.derateRungs.isEmpty() })

        // v2: carries a manualFanOverride, no rungs. QUALIFICATION_MANIFEST_SCHEMA_V2 exists
        // precisely so these bytes stay byte-identical to what schema v2 always produced, even
        // though QUALIFICATION_MANIFEST_SCHEMA ("current") is no longer 2.
        val nominalCpu = mapOf(0 to 1_996_800, 6 to 3_072_000)
        val manual = QualifiedManualFanOverride.create(
            cpuMaxKhz = nominalCpu,
            gpuMaxPowerLevel = 0,
            minimumDutyPercent = 0,
            evidenceIndexSha256 = "f".repeat(64),
            guardianRollbackEvidenceSha256 = "1".repeat(64),
        )
        val quietWithManual = QualifiedProfilePlan.create(
            profileId = "quiet",
            cpuMaxKhz = nominalCpu,
            gpuMaxPowerLevel = 0,
            fanMode = TuningPlanner.FAN_SMART,
            evidenceIndexSha256 = "d".repeat(64),
            guardianRollbackEvidenceSha256 = "e".repeat(64),
            manualFanOverride = manual,
        )
        val v2 = QualificationManifest(
            schemaVersion = QUALIFICATION_MANIFEST_SCHEMA_V2,
            servicePlanSchema = QUALIFICATION_SERVICE_PLAN_SCHEMA_V2,
            keyId = keyId(keyPair),
            sequence = 5,
            hardwareFingerprint = fingerprint,
            deviceProfile = capabilities.deviceProfileId,
            profiles = listOf(quietWithManual),
        )
        val v2Decoded = requireNotNull(
            QualificationManifestCodec.decodePayload(QualificationManifestCodec.encodePayload(v2)),
        )
        assertEquals(v2, v2Decoded)
        assertEquals(2, v2Decoded.schemaVersion)
        assertEquals(0, v2Decoded.profiles.single().manualFanOverride?.minimumDutyPercent)
        assertTrue(v2Decoded.profiles.single().derateRungs.isEmpty())

        // v3: default (current) schema -- adds an ordered rung on top of the SAME base plan and
        // manual override, proving the new field is a strict addition, not a replacement.
        val rungCpu = mapOf(0 to 1_996_800, 6 to 2_841_600)
        val rung1 = QualifiedDerateRung.create(
            cpuMaxKhz = rungCpu,
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SMART,
            evidenceIndexSha256 = "2".repeat(64),
            guardianRollbackEvidenceSha256 = "3".repeat(64),
        )
        val v3 = QualificationManifest(
            keyId = keyId(keyPair),
            sequence = 5,
            hardwareFingerprint = fingerprint,
            deviceProfile = capabilities.deviceProfileId,
            profiles = listOf(quietWithManual.copy(derateRungs = listOf(rung1))),
        )
        assertEquals(QUALIFICATION_MANIFEST_SCHEMA, v3.schemaVersion)
        val v3Decoded = requireNotNull(
            QualificationManifestCodec.decodePayload(QualificationManifestCodec.encodePayload(v3)),
        )
        assertEquals(v3, v3Decoded)
        val decodedProfile = v3Decoded.profiles.single()
        assertEquals(0, decodedProfile.manualFanOverride?.minimumDutyPercent)
        assertEquals(1, decodedProfile.derateRungs.size)
        assertEquals(rungCpu, decodedProfile.derateRungs.single().cpuMaxKhz)
        assertEquals(1, decodedProfile.derateRungs.single().gpuMaxPowerLevel)
        assertEquals(TuningPlanner.FAN_SMART, decodedProfile.derateRungs.single().fanMode)
    }

    @Test
    fun `schema3 derate rung authority resolves the signed plan exactly mirroring manual fan override`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        // Policy IDs {0, 4, 7} match testCapabilities()'s own cpuPolicies exactly -- required for
        // QualificationPlanPolicy.allowsProfiles (and therefore verify()) to accept the profile;
        // {0, 6} is the real Odin 3 shape used elsewhere in this file for construction-only tests
        // that never reach live-capability matching.
        val nominalCpu = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600)
        val rungCpu = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800)
        val rung1 = QualifiedDerateRung.create(
            cpuMaxKhz = rungCpu,
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SPORT,
            evidenceIndexSha256 = "2".repeat(64),
            guardianRollbackEvidenceSha256 = "3".repeat(64),
        )
        val performanceWithRung = QualifiedProfilePlan.create(
            profileId = "performance",
            cpuMaxKhz = nominalCpu,
            gpuMaxPowerLevel = 0,
            fanMode = TuningPlanner.FAN_SPORT,
            evidenceIndexSha256 = "a".repeat(64),
            guardianRollbackEvidenceSha256 = "b".repeat(64),
            derateRungs = listOf(rung1),
        )
        val accepted = verify(
            sign(manifest(capabilities, keyPair, profiles = listOf(performanceWithRung)), keyPair),
            keyPair,
            capabilities,
        )
        assertTrue(accepted is QualificationVerification.Accepted)
        val authority = (accepted as QualificationVerification.Accepted).authority

        val rungPlan = authority.qualifiedDerateRungPlan("performance", "org.example.game", 1, capabilities)
        assertEquals(rungCpu, rungPlan?.cpuMaxKhz)
        assertEquals(1, rungPlan?.gpuMaxPowerLevel)
        assertEquals(TuningPlanner.FAN_SPORT, rungPlan?.fanMode)
        assertEquals(true, rungPlan?.calibrated)
        // Rung 0 is nominal (ThermalDerateLadder.at(0)), never itself a signed rung.
        assertEquals(null, authority.qualifiedDerateRungPlan("performance", "org.example.game", 0, capabilities))
        // Rung 2 was never signed for this profile.
        assertEquals(null, authority.qualifiedDerateRungPlan("performance", "org.example.game", 2, capabilities))
        // A different, unsigned profile.
        assertEquals(null, authority.qualifiedDerateRungPlan("quiet", "org.example.game", 1, capabilities))
    }

    @Test
    fun `derate rung admission rejects a rung faster than its base plan on either axis or a divergent fan mode`() {
        val nominalCpu = mapOf(0 to 1_996_800, 6 to 3_072_000)
        val dominatedCpu = mapOf(0 to 1_996_800, 6 to 2_841_600)

        fun rung(cpu: Map<Int, Int>, gpu: Int?, fan: Int?) = QualifiedDerateRung.create(
            cpuMaxKhz = cpu,
            gpuMaxPowerLevel = gpu,
            fanMode = fan,
            evidenceIndexSha256 = "2".repeat(64),
            guardianRollbackEvidenceSha256 = "3".repeat(64),
        )
        fun profileWithRung(rungPlan: QualifiedDerateRung, gpu: Int? = 0) = QualifiedProfilePlan.create(
            profileId = "performance",
            cpuMaxKhz = nominalCpu,
            gpuMaxPowerLevel = gpu,
            fanMode = TuningPlanner.FAN_SPORT,
            evidenceIndexSha256 = "a".repeat(64),
            guardianRollbackEvidenceSha256 = "b".repeat(64),
            derateRungs = listOf(rungPlan),
        )

        // CPU axis: a rung policy target ABOVE the base plan's own target.
        assertThrows(IllegalArgumentException::class.java) {
            profileWithRung(rung(mapOf(0 to 1_996_800, 6 to 3_072_000 + 1), 1, TuningPlanner.FAN_SPORT))
        }
        // GPU axis: a LOWER max_pwrlevel index than the base plan (inverted -- lower is FASTER,
        // so this would overclock the GPU during a thermal event).
        assertThrows(IllegalArgumentException::class.java) {
            profileWithRung(rung(dominatedCpu, 0, TuningPlanner.FAN_SPORT), gpu = 1)
        }
        // Fan axis: a rung whose fan mode diverges from the base plan's own -- the fan axis is
        // never independently derated.
        assertThrows(IllegalArgumentException::class.java) {
            profileWithRung(rung(dominatedCpu, 1, TuningPlanner.FAN_SILENT))
        }
        // GPU axis presence must match the base plan (a rung cannot invent a GPU target the base
        // plan never had, nor drop one it did).
        assertThrows(IllegalArgumentException::class.java) {
            profileWithRung(rung(dominatedCpu, null, TuningPlanner.FAN_SPORT))
        }
        // CPU policy set must match the base plan exactly.
        assertThrows(IllegalArgumentException::class.java) {
            profileWithRung(rung(mapOf(6 to 2_841_600), 1, TuningPlanner.FAN_SPORT))
        }
        // The dominated, fan-identical vector is legal -- proves the failures above are genuinely
        // about the violated axis, not about the vectors being illegal some other way.
        profileWithRung(rung(dominatedCpu, 1, TuningPlanner.FAN_SPORT))
    }

    @Test
    fun `derate rung admission rejects non monotonic ordering across successive rungs`() {
        val nominalCpu = mapOf(0 to 1_996_800, 6 to 3_072_000)
        val slowerRungCpu = mapOf(0 to 1_996_800, 6 to 2_500_000)
        val fasterRungCpu = mapOf(0 to 1_996_800, 6 to 2_800_000) // faster than slowerRungCpu

        fun rung(cpu: Map<Int, Int>) = QualifiedDerateRung.create(
            cpuMaxKhz = cpu,
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SPORT,
            evidenceIndexSha256 = "2".repeat(64),
            guardianRollbackEvidenceSha256 = "3".repeat(64),
        )
        fun profileWithRungs(rungs: List<QualifiedDerateRung>) = QualifiedProfilePlan.create(
            profileId = "performance",
            cpuMaxKhz = nominalCpu,
            gpuMaxPowerLevel = 0,
            fanMode = TuningPlanner.FAN_SPORT,
            evidenceIndexSha256 = "a".repeat(64),
            guardianRollbackEvidenceSha256 = "b".repeat(64),
            derateRungs = rungs,
        )

        // Rung 2 is FASTER than rung 1 -- both are individually dominated by the NOMINAL, so this
        // failure is specifically about ordering between successive rungs, not domination over
        // the base plan (which rule 1's own dedicated test above already covers).
        assertThrows(IllegalArgumentException::class.java) {
            profileWithRungs(listOf(rung(slowerRungCpu), rung(fasterRungCpu)))
        }
        // The SAME two rungs, correctly ordered slowest-last, are accepted.
        profileWithRungs(listOf(rung(fasterRungCpu), rung(slowerRungCpu)))
    }

    @Test
    fun `derate rung plan hash and evidence digests are mandatory exactly like a base plan`() {
        val rung = QualifiedDerateRung.create(
            cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 2_841_600),
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SPORT,
            evidenceIndexSha256 = "2".repeat(64),
            guardianRollbackEvidenceSha256 = "3".repeat(64),
        )
        assertThrows(IllegalArgumentException::class.java) {
            rung.copy(planSha256 = "f".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            rung.copy(evidenceIndexSha256 = "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            rung.copy(guardianRollbackEvidenceSha256 = "0".repeat(64))
        }
    }

    @Test
    fun `evidence and Guardian rollback digests are mandatory and plan hash is exact`() {
        val quiet = profiles().single { it.profileId == "quiet" }
        assertThrows(IllegalArgumentException::class.java) {
            quiet.copy(evidenceIndexSha256 = "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            quiet.copy(guardianRollbackEvidenceSha256 = "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            quiet.copy(planSha256 = "f".repeat(64))
        }
    }

    // TR-02: this test used to assert the single collapsed QualificationRejection.
    // REPLAY_OR_SEQUENCE_UNAVAILABLE for both the genuine sequence-rollback case AND the
    // same-sequence-different-payload conflict case below -- a real replay attack read
    // identically to a same-sequence conflict. Rewritten (not deleted) to pin the split:
    // SEQUENCE_ROLLBACK vs SEQUENCE_CONFLICT.
    @Test
    fun `sequence reload is idempotent while rollback and same-sequence conflict fail distinctly`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val store = sequenceStore()
        val sequenceNine = sign(manifest(capabilities, keyPair, sequence = 9), keyPair)
        val anchor = anchor(keyPair)

        assertTrue(
            QualificationManifestVerifier.verify(sequenceNine, anchor, store, capabilities) is
                QualificationVerification.Accepted,
        )
        assertTrue(
            QualificationManifestVerifier.verify(sequenceNine, anchor, store, capabilities) is
                QualificationVerification.Accepted,
        )
        assertRejected(
            QualificationRejection.SEQUENCE_ROLLBACK,
            QualificationManifestVerifier.verify(
                sign(manifest(capabilities, keyPair, sequence = 8), keyPair),
                anchor,
                store,
                capabilities,
            ),
        )
        val conflictingProfiles = profiles().map { profile ->
            if (profile.profileId == "performance") {
                profile.copy(evidenceIndexSha256 = "c".repeat(64))
            } else profile
        }
        assertRejected(
            QualificationRejection.SEQUENCE_CONFLICT,
            QualificationManifestVerifier.verify(
                sign(manifest(capabilities, keyPair, sequence = 9, profiles = conflictingProfiles), keyPair),
                anchor,
                store,
                capabilities,
            ),
        )
    }

    // TR-02: this test used to assert QualificationRejection.REPLAY_OR_SEQUENCE_UNAVAILABLE for
    // both a corrupt sequence record and a leftover staging (.tmp) file -- exactly the ticket's
    // own motivating example: "a leftover `.qualification.tmp` staging file yields a permanent
    // REJECTED_UNAVAILABLE that currently reads identically to a replay attack." Both are local
    // store I/O failures, not replays; rewritten (not deleted) to pin SEQUENCE_STORE_UNAVAILABLE
    // for both, now distinct from SEQUENCE_ROLLBACK/SEQUENCE_CONFLICT above.
    @Test
    fun `corrupt or partial sequence truth never resets admission`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val directory = temporaryFolder.newFolder("corrupt-sequence").absoluteFile
        File(directory, "mimir_product_qualification_sequence.v1").writeText("corrupt")
        assertRejected(
            QualificationRejection.SEQUENCE_STORE_UNAVAILABLE,
            QualificationManifestVerifier.verify(
                sign(manifest(capabilities, keyPair), keyPair),
                anchor(keyPair),
                FileQualificationSequenceStore(directory),
                capabilities,
            ),
        )

        val stagingDirectory = temporaryFolder.newFolder("partial-sequence").absoluteFile
        File(stagingDirectory, ".mimir_product_qualification_sequence.v1.tmp").writeText("partial")
        assertRejected(
            QualificationRejection.SEQUENCE_STORE_UNAVAILABLE,
            QualificationManifestVerifier.verify(
                sign(manifest(capabilities, keyPair), keyPair),
                anchor(keyPair),
                FileQualificationSequenceStore(stagingDirectory),
                capabilities,
            ),
        )
    }

    // TR-02: rewritten (not deleted) from asserting the collapsed
    // REPLAY_OR_SEQUENCE_UNAVAILABLE to the split SEQUENCE_STORE_UNAVAILABLE -- a persistence
    // failure is a store-availability fault, not a replay.
    @Test
    fun `sequence persistence failure exposes no authority and leaves no accepted record`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val directory = temporaryFolder.newFolder("failed-sequence-write").absoluteFile
        val store = FileQualificationSequenceStore(directory) { _, _ ->
            error("injected atomic-move failure")
        }

        assertRejected(
            QualificationRejection.SEQUENCE_STORE_UNAVAILABLE,
            QualificationManifestVerifier.verify(
                sign(manifest(capabilities, keyPair), keyPair),
                anchor(keyPair),
                store,
                capabilities,
            ),
        )
        assertFalse(File(directory, "mimir_product_qualification_sequence.v1").exists())
        assertFalse(File(directory, ".mimir_product_qualification_sequence.v1.tmp").exists())
    }

    @Test
    fun `manifest requires sorted unique qualifiable profiles and matching trust key ids`() {
        val capabilities = testCapabilities()
        val keyPair = keyPair()
        val fingerprint = HardwareFingerprint.create(capabilities)

        assertThrows(IllegalArgumentException::class.java) {
            QualificationManifest(
                keyId = keyId(keyPair),
                sequence = 1,
                hardwareFingerprint = fingerprint,
                deviceProfile = capabilities.deviceProfileId,
                profiles = profiles().reversed(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QualificationManifest(
                keyId = keyId(keyPair),
                sequence = 1,
                hardwareFingerprint = fingerprint,
                deviceProfile = capabilities.deviceProfileId,
                profiles = listOf(profiles().first(), profiles().first()),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QualificationTrustAnchor(mapOf("f".repeat(64) to keyPair.public.encoded))
        }
    }

    private fun assertIllegal(
        manifest: QualificationManifest,
        keyPair: KeyPair,
        capabilities: HardwareCapabilities,
    ) {
        assertRejected(
            QualificationRejection.ILLEGAL_PLAN,
            verify(sign(manifest, keyPair), keyPair, capabilities),
        )
    }

    private fun verify(
        bytes: ByteArray,
        keyPair: KeyPair,
        capabilities: HardwareCapabilities,
    ): QualificationVerification = QualificationManifestVerifier.verify(
        bytes,
        anchor(keyPair),
        sequenceStore(),
        capabilities,
    )

    private fun assertRejected(
        reason: QualificationRejection,
        verification: QualificationVerification,
    ) {
        assertTrue(verification is QualificationVerification.Rejected)
        assertEquals(reason, (verification as QualificationVerification.Rejected).reason)
    }

    private fun manifest(
        capabilities: HardwareCapabilities,
        keyPair: KeyPair,
        sequence: Long = 1,
        profiles: List<QualifiedProfilePlan> = profiles(),
    ) = QualificationManifest(
        keyId = keyId(keyPair),
        sequence = sequence,
        hardwareFingerprint = HardwareFingerprint.create(capabilities),
        deviceProfile = capabilities.deviceProfileId,
        profiles = profiles.sortedBy { it.profileId },
    )

    private fun profiles(): List<QualifiedProfilePlan> = listOf(
        QualifiedProfilePlan.create(
            profileId = "performance",
            cpuMaxKhz = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600),
            gpuMaxPowerLevel = 0,
            fanMode = TuningPlanner.FAN_SPORT,
            evidenceIndexSha256 = "a".repeat(64),
            guardianRollbackEvidenceSha256 = "b".repeat(64),
        ),
        QualifiedProfilePlan.create(
            profileId = "quiet",
            cpuMaxKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SILENT,
            evidenceIndexSha256 = "d".repeat(64),
            guardianRollbackEvidenceSha256 = "e".repeat(64),
        ),
    )

    private fun sign(manifest: QualificationManifest, keyPair: KeyPair): ByteArray {
        val payload = QualificationManifestCodec.encodePayload(manifest)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return QualificationManifestCodec.encodeSigned(
            SignedQualificationManifest(payload, signature),
        )
    }

    private fun anchor(keyPair: KeyPair) =
        QualificationTrustAnchor(mapOf(keyId(keyPair) to keyPair.public.encoded))

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun keyId(keyPair: KeyPair): String = MessageDigest.getInstance("SHA-256")
        .digest(keyPair.public.encoded)
        .joinToString("") { "%02x".format(it) }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        return (0..size - needle.size).firstOrNull { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        } ?: -1
    }

    private fun sequenceStore() =
        FileQualificationSequenceStore(temporaryFolder.newFolder().absoluteFile)

    private fun testCapabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "rp5/test",
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/policy0", listOf(0), listOf(300_000, 1_804_800), 300_000, 1_804_800, 1_804_800),
            CpuPolicy(4, "/policy4", listOf(4), listOf(710_400, 2_419_200), 710_400, 2_419_200, 2_419_200),
            CpuPolicy(7, "/policy7", listOf(7), listOf(844_800, 2_841_600), 844_800, 2_841_600, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private object NoLocalCalibrationStore : CalibrationReportStore {
        override fun load(): CalibrationReport? = null
        override fun save(report: CalibrationReport): Boolean = false
        override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? = null
        override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()
    }
}
