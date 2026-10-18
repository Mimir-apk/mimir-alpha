package com.gruning.mimir.tweaker

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class QualificationManifestGeneratorCompatibilityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `host generator is byte exact with runtime and resolves through public build inputs`() {
        assertEquals(QUALIFICATION_MANIFEST_SCHEMA, QualificationManifestGenerator.MANIFEST_SCHEMA)
        assertEquals(QUALIFICATION_SERVICE_PLAN_SCHEMA, QualificationManifestGenerator.SERVICE_PLAN_SCHEMA)
        assertEquals(QUALIFICATION_MANIFEST_SCHEMA_V2, QualificationManifestGenerator.MANIFEST_SCHEMA_V2)
        assertEquals(QUALIFICATION_SERVICE_PLAN_SCHEMA_V2, QualificationManifestGenerator.SERVICE_PLAN_SCHEMA_V2)
        assertEquals(QualificationManifestCodec.MAX_PAYLOAD_BYTES, QualificationManifestGenerator.MAX_PAYLOAD_BYTES)
        assertEquals(QualificationManifestCodec.MAX_SIGNED_BYTES, QualificationManifestGenerator.MAX_SIGNED_BYTES)

        val capabilities = capabilities()
        val keyPair = keyPair()
        val request = request(capabilities, sequence = 41L)
        val generated = QualificationManifestGenerator.generate(request, keyPair.private, keyPair.public)
        val runtimeManifest = runtimeManifest(request, generated.keyId())

        assertArrayEquals(QualificationManifestCodec.encodePayload(runtimeManifest), generated.payload())
        val decodedSigned = requireNotNull(QualificationManifestCodec.decodeSigned(generated.signedManifest()))
        assertArrayEquals(generated.payload(), decodedSigned.payload)
        assertArrayEquals(generated.signedManifest(), QualificationManifestCodec.encodeSigned(decodedSigned))
        assertEquals(runtimeManifest, QualificationManifestCodec.decodePayload(decodedSigned.payload))

        val anchor = QualificationTrustAnchor(mapOf(generated.keyId() to generated.publicKeySpki()))
        val verified = QualificationManifestVerifier.verify(
            generated.signedManifest(),
            anchor,
            QualificationSequenceStore { _, _ -> QualificationSequenceAdmission.ACCEPTED_NEW },
            capabilities,
        )
        assertTrue(verified is QualificationVerification.Accepted)

        val anchorBase64 = generated.trustAnchorBase64()
        val manifestBase64 = generated.manifestBase64()
        assertFalse(anchorBase64.any(Char::isWhitespace))
        assertFalse(manifestBase64.any(Char::isWhitespace))
        assertArrayEquals(
            generated.publicKeySpki(),
            requireNotNull(ProductQualificationBuildInputs.trustAnchor(anchorBase64))
                .key(generated.keyId())
                ?.encoded,
        )
        assertArrayEquals(
            generated.signedManifest(),
            requireNotNull(ProductQualificationBuildInputs.signedManifests(manifestBase64)).single(),
        )

        val context = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
        context.filesDir.resolve("product_qualification").deleteRecursively()
        val resolution = BuildInjectedProductQualificationResolver(
            context = context,
            signedManifestRequired = true,
            trustAnchorsBase64 = anchorBase64,
            manifestsBase64 = manifestBase64,
        ).resolve(capabilities)
        assertTrue(resolution.available)
        assertEquals(TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST, resolution.source)

        val certified = CertificationStore(NoLocalCalibrationStore, resolution.authority)
            .certifiedProfileIds(HardwareFingerprint.create(capabilities))
        val expected = if (TweakerPostureTruth.posture == TweakerPostureTruth.PRODUCTION) {
            setOf("quiet", "balanced", "performance")
        } else {
            emptySet()
        }
        assertEquals(expected, certified)
    }

    @Test
    fun `boundary vectors preserve canonical order null axes and long sequence limits`() {
        val cpu = linkedMapOf<Int, Int>().apply {
            repeat(32) { id -> put(id, 100_000 + id) }
        }
        val profiles = listOf(
            profile("balanced", cpu, gpu = null, fan = 4),
            profile("performance", cpu, gpu = 0, fan = null),
            profile("quiet", cpu, gpu = null, fan = null),
        )
        val keyPair = keyPair()
        val maximum = QualificationManifestGenerator.Request(
            Long.MAX_VALUE,
            "a".repeat(64),
            "d".repeat(128),
            profiles,
        )
        val maximumGenerated = QualificationManifestGenerator.generate(maximum, keyPair.private, keyPair.public)
        assertArrayEquals(
            QualificationManifestCodec.encodePayload(runtimeManifest(maximum, maximumGenerated.keyId())),
            maximumGenerated.payload(),
        )

        val minimum = QualificationManifestGenerator.Request(
            1L,
            maximum.hardwareFingerprint(),
            maximum.deviceProfile(),
            listOf(profiles.last()),
        )
        val minimumGenerated = QualificationManifestGenerator.generate(minimum, keyPair.private, keyPair.public)
        assertArrayEquals(
            QualificationManifestCodec.encodePayload(runtimeManifest(minimum, minimumGenerated.keyId())),
            minimumGenerated.payload(),
        )

        assertGeneratorError("SEQUENCE_INVALID") {
            QualificationManifestGenerator.Request(0, "a".repeat(64), "device", listOf(profiles.last()))
        }
        assertGeneratorError("CPU_TARGETS_TOO_MANY") {
            profile("quiet", cpu + (32 to 200_000), gpu = null, fan = null)
        }
    }

    @Test
    fun `manual override requires schema2-or-later sequence5 and preserves legacy schema1 bytes`() {
        val capabilities = capabilities()
        val keyPair = keyPair()
        val legacyText = requestText(
            sequence = "3",
            profileLines = listOf(
                "profile=quiet",
                "cpu.0=1000000",
                "cpu.6=1500000",
                "gpuMaxPowerLevel=1",
                "fanMode=4",
                "evidenceIndexSha256=${"b".repeat(64)}",
                "guardianRollbackEvidenceSha256=${"c".repeat(64)}",
                "endProfile",
            ),
        )
        val legacy = QualificationManifestGenerator.parseRequest(
            Files.write(
                temporaryFolder.newFile("legacy-v1.txt").toPath(),
                legacyText.toByteArray(StandardCharsets.UTF_8),
            ),
        )
        assertEquals(QualificationManifestGenerator.LEGACY_MANIFEST_SCHEMA, legacy.manifestSchema())
        val legacyGenerated = QualificationManifestGenerator.generate(legacy, keyPair.private, keyPair.public)
        assertArrayEquals(
            QualificationManifestCodec.encodePayload(runtimeManifest(legacy, legacyGenerated.keyId())),
            legacyGenerated.payload(),
        )

        val manualQuiet = QualificationManifestGenerator.ProfilePlan(
            "quiet",
            linkedMapOf(0 to 1_000_000, 6 to 1_500_000),
            1,
            TuningPlanner.FAN_SMART,
            "b".repeat(64),
            "c".repeat(64),
            0,
            "d".repeat(64),
            "e".repeat(64),
        )
        assertGeneratorError("MANUAL_SEQUENCE_INVALID") {
            QualificationManifestGenerator.Request(
                4,
                HardwareFingerprint.create(capabilities),
                CpuCeilingTransport.ODIN_3_PROFILE,
                listOf(manualQuiet),
            )
        }
        // The schema boundary this test is named for: manual fields are illegal below schema v2
        // (fixed threshold, not "current" -- see MANIFEST_SCHEMA_V2's kdoc) even at an otherwise
        // admissible sequence and device.
        assertGeneratorError("MANUAL_SCHEMA_INVALID") {
            QualificationManifestGenerator.Request(
                QualificationManifestGenerator.LEGACY_MANIFEST_SCHEMA,
                5,
                HardwareFingerprint.create(capabilities),
                CpuCeilingTransport.ODIN_3_PROFILE,
                listOf(manualQuiet),
            )
        }
        val admitted = QualificationManifestGenerator.Request(
            5,
            HardwareFingerprint.create(capabilities),
            CpuCeilingTransport.ODIN_3_PROFILE,
            listOf(manualQuiet),
        )
        val generated = QualificationManifestGenerator.generate(admitted, keyPair.private, keyPair.public)
        assertArrayEquals(
            QualificationManifestCodec.encodePayload(runtimeManifest(admitted, generated.keyId())),
            generated.payload(),
        )
        assertEquals(
            0,
            QualificationManifestCodec.decodePayload(generated.payload())
                ?.profiles?.single()?.manualFanOverride?.minimumDutyPercent,
        )
    }

    @Test
    fun `V2 request header mints true schema2 bytes and regeneration stays byte identical under an unchanged sequence`() {
        val keyPair = keyPair()
        val v2Text = requestText(
            sequence = "5",
            header = "MIMIR_QUALIFICATION_REQUEST_V2",
            profileLines = listOf(
                "profile=quiet",
                "cpu.0=1000000",
                "gpuMaxPowerLevel=1",
                "fanMode=4",
                "evidenceIndexSha256=${"b".repeat(64)}",
                "guardianRollbackEvidenceSha256=${"c".repeat(64)}",
                "manualFanMinimumDutyPercent=null",
                "manualFanEvidenceIndexSha256=null",
                "manualFanGuardianRollbackEvidenceSha256=null",
                "endProfile",
            ),
        )
        val requestFile = temporaryFolder.newFile("v2-request.txt").toPath()
        Files.write(requestFile, v2Text.toByteArray(StandardCharsets.UTF_8))

        val firstParse = QualificationManifestGenerator.parseRequest(requestFile)
        assertEquals(QualificationManifestGenerator.MANIFEST_SCHEMA_V2, firstParse.manifestSchema())
        val firstGenerated = QualificationManifestGenerator.generate(firstParse, keyPair.private, keyPair.public)
        assertArrayEquals(
            QualificationManifestCodec.encodePayload(runtimeManifest(firstParse, firstGenerated.keyId())),
            firstGenerated.payload(),
        )
        val decoded = requireNotNull(QualificationManifestCodec.decodePayload(firstGenerated.payload()))
        assertEquals(QUALIFICATION_MANIFEST_SCHEMA_V2, decoded.schemaVersion)
        assertEquals(QUALIFICATION_SERVICE_PLAN_SCHEMA_V2, decoded.servicePlanSchema)
        assertTrue(decoded.profiles.single().derateRungs.isEmpty())

        // Re-running the generator against the SAME, unchanged request file -- e.g. after a lost
        // artifact, or under a rotated signing key -- must mint a byte-identical PAYLOAD (the
        // signature need not match key-to-key, but the signed content must). Before this fix, a V2
        // request silently upgraded to schema-3 bytes, which would change payloadSha256 under an
        // UNCHANGED sequence; the on-device sequence store treats that as a conflict, not a re-issue.
        val secondParse = QualificationManifestGenerator.parseRequest(requestFile)
        val secondGenerated = QualificationManifestGenerator.generate(secondParse, keyPair.private, keyPair.public)
        assertArrayEquals(firstGenerated.payload(), secondGenerated.payload())

        val sequenceStore = FileQualificationSequenceStore(temporaryFolder.newFolder("qual-sequence").absoluteFile)
        assertEquals(
            QualificationSequenceAdmission.ACCEPTED_NEW,
            sequenceStore.admit(firstParse.sequence(), firstGenerated.payloadSha256()),
        )
        assertEquals(
            QualificationSequenceAdmission.ACCEPTED_IDEMPOTENT,
            sequenceStore.admit(secondParse.sequence(), secondGenerated.payloadSha256()),
        )

        // The same profile content minted as schema v3 is a genuinely different payload (schema v3
        // always carries the derateRungs-count field v2 never had), proving v2's bytes are a
        // distinct, stable shape -- never a silent upgrade to "whatever is current".
        val v3Equivalent = QualificationManifestGenerator.Request(
            QualificationManifestGenerator.MANIFEST_SCHEMA,
            firstParse.sequence(),
            firstParse.hardwareFingerprint(),
            firstParse.deviceProfile(),
            firstParse.profiles(),
        )
        val v3Generated = QualificationManifestGenerator.generate(v3Equivalent, keyPair.private, keyPair.public)
        assertFalse(firstGenerated.payload().contentEquals(v3Generated.payload()))
    }

    @Test
    fun `derate rungs round trip byte exact between Java and Kotlin and the decoded manifest carries them`() {
        val capabilities = capabilities()
        val keyPair = keyPair()
        val performanceCpu = linkedMapOf(0 to 2_000_000, 6 to 3_000_000)
        val rung1 = QualificationManifestGenerator.RungPlan(
            linkedMapOf(0 to 2_000_000, 6 to 1_500_000),
            1,
            "f".repeat(64),
            "1".repeat(64),
        )
        val performanceWithRungs = QualificationManifestGenerator.ProfilePlan(
            "performance",
            performanceCpu,
            0,
            5,
            "b".repeat(64),
            "c".repeat(64),
            null,
            null,
            null,
            listOf(rung1),
        )
        val request = QualificationManifestGenerator.Request(
            QualificationManifestGenerator.MANIFEST_SCHEMA,
            41L,
            HardwareFingerprint.create(capabilities),
            capabilities.deviceProfileId,
            listOf(
                profile("balanced", linkedMapOf(0 to 1_500_000, 6 to 2_000_000), gpu = 1, fan = 4),
                performanceWithRungs,
                profile("quiet", linkedMapOf(0 to 1_000_000, 6 to 1_500_000), gpu = 1, fan = 1),
            ),
        )
        val generated = QualificationManifestGenerator.generate(request, keyPair.private, keyPair.public)
        assertArrayEquals(
            QualificationManifestCodec.encodePayload(runtimeManifest(request, generated.keyId())),
            generated.payload(),
        )

        val decoded = requireNotNull(QualificationManifestCodec.decodePayload(generated.payload()))
        assertEquals(QUALIFICATION_MANIFEST_SCHEMA, decoded.schemaVersion)
        val decodedPerformance = decoded.profiles.single { it.profileId == "performance" }
        assertEquals(1, decodedPerformance.derateRungs.size)
        val decodedRung = decodedPerformance.derateRungs.single()
        assertEquals(mapOf(0 to 2_000_000, 6 to 1_500_000), decodedRung.cpuMaxKhz)
        assertEquals(1, decodedRung.gpuMaxPowerLevel)
        assertEquals(5, decodedRung.fanMode)
        assertEquals(
            planIdentityHash(decodedRung.cpuMaxKhz, decodedRung.gpuMaxPowerLevel, decodedRung.fanMode),
            decodedRung.planSha256,
        )
        assertTrue(decoded.profiles.filter { it.profileId != "performance" }.all { it.derateRungs.isEmpty() })

        val anchor = QualificationTrustAnchor(mapOf(generated.keyId() to generated.publicKeySpki()))
        val verified = QualificationManifestVerifier.verify(
            generated.signedManifest(),
            anchor,
            QualificationSequenceStore { _, _ -> QualificationSequenceAdmission.ACCEPTED_NEW },
            capabilities,
        )
        assertTrue(verified is QualificationVerification.Accepted)
        val authority = (verified as QualificationVerification.Accepted).authority
        val rungPlan = authority.qualifiedDerateRungPlan("performance", "org.example.game", 1, capabilities)
        assertEquals(mapOf(0 to 2_000_000, 6 to 1_500_000), rungPlan?.cpuMaxKhz)
        assertEquals(1, rungPlan?.gpuMaxPowerLevel)
        assertEquals(5, rungPlan?.fanMode)
        assertEquals(true, rungPlan?.calibrated)
        assertEquals(null, authority.qualifiedDerateRungPlan("performance", "org.example.game", 2, capabilities))
        assertEquals(null, authority.qualifiedDerateRungPlan("balanced", "org.example.game", 1, capabilities))
    }

    @Test
    fun `generator refuses a rung faster than its base plan or out of order`() {
        val performanceCpu = linkedMapOf(0 to 2_000_000, 6 to 3_000_000)

        // Rule 1: CPU axis faster than the base plan.
        assertGeneratorError("DERATE_RUNG_CPU_NOT_DOMINATED") {
            QualificationManifestGenerator.ProfilePlan(
                "performance", performanceCpu, 0, 5, "b".repeat(64), "c".repeat(64),
                null, null, null,
                listOf(
                    QualificationManifestGenerator.RungPlan(
                        linkedMapOf(0 to 2_000_000, 6 to 3_500_000),
                        1,
                        "f".repeat(64),
                        "1".repeat(64),
                    ),
                ),
            )
        }
        // Rule 1: GPU axis faster (lower max_pwrlevel) than the base plan.
        assertGeneratorError("DERATE_RUNG_GPU_NOT_DOMINATED") {
            QualificationManifestGenerator.ProfilePlan(
                "performance", performanceCpu, 1, 5, "b".repeat(64), "c".repeat(64),
                null, null, null,
                listOf(
                    QualificationManifestGenerator.RungPlan(
                        linkedMapOf(0 to 2_000_000, 6 to 1_500_000),
                        0,
                        "f".repeat(64),
                        "1".repeat(64),
                    ),
                ),
            )
        }
        // Rule 2: rung 2 faster than rung 1 (non-monotonic).
        assertGeneratorError("DERATE_RUNG_CPU_NOT_DOMINATED") {
            QualificationManifestGenerator.ProfilePlan(
                "performance", performanceCpu, 0, 5, "b".repeat(64), "c".repeat(64),
                null, null, null,
                listOf(
                    QualificationManifestGenerator.RungPlan(
                        linkedMapOf(0 to 2_000_000, 6 to 1_500_000),
                        1,
                        "f".repeat(64),
                        "1".repeat(64),
                    ),
                    QualificationManifestGenerator.RungPlan(
                        linkedMapOf(0 to 2_000_000, 6 to 2_000_000),
                        1,
                        "f".repeat(64),
                        "1".repeat(64),
                    ),
                ),
            )
        }
        // A rung whose CPU policy set diverges from the base plan is refused outright.
        assertGeneratorError("DERATE_RUNG_CPU_POLICY_MISMATCH") {
            QualificationManifestGenerator.ProfilePlan(
                "performance", performanceCpu, 0, 5, "b".repeat(64), "c".repeat(64),
                null, null, null,
                listOf(
                    QualificationManifestGenerator.RungPlan(
                        linkedMapOf(6 to 1_500_000),
                        1,
                        "f".repeat(64),
                        "1".repeat(64),
                    ),
                ),
            )
        }
        // Zero-evidence rungs are refused, matching every other evidence digest in this file.
        assertGeneratorError("DERATE_RUNG_EVIDENCE_DIGEST_ZERO") {
            QualificationManifestGenerator.RungPlan(
                linkedMapOf(0 to 2_000_000, 6 to 1_500_000),
                1,
                "0".repeat(64),
                "1".repeat(64),
            )
        }
        // A schema-v2-shaped request (no V3 header) may never carry rungs.
        assertGeneratorError("DERATE_RUNGS_SCHEMA_INVALID") {
            QualificationManifestGenerator.Request(
                QualificationManifestGenerator.LEGACY_MANIFEST_SCHEMA,
                1L,
                "a".repeat(64),
                "device",
                listOf(
                    QualificationManifestGenerator.ProfilePlan(
                        "performance", performanceCpu, 0, 5, "b".repeat(64), "c".repeat(64),
                        null, null, null,
                        listOf(
                            QualificationManifestGenerator.RungPlan(
                                linkedMapOf(0 to 2_000_000, 6 to 1_500_000),
                                1,
                                "f".repeat(64),
                                "1".repeat(64),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `V3 request text parses a rung block and matches direct object construction byte for byte`() {
        val keyPair = keyPair()
        val v3Text = requestText(
            sequence = "1",
            header = "MIMIR_QUALIFICATION_REQUEST_V3",
            profileLines = listOf(
                "profile=quiet",
                "cpu.0=100000",
                "gpuMaxPowerLevel=1",
                "fanMode=1",
                "evidenceIndexSha256=${"b".repeat(64)}",
                "guardianRollbackEvidenceSha256=${"c".repeat(64)}",
                "manualFanMinimumDutyPercent=null",
                "manualFanEvidenceIndexSha256=null",
                "manualFanGuardianRollbackEvidenceSha256=null",
                "rung=1",
                "cpu.0=90000",
                "gpuMaxPowerLevel=2",
                "evidenceIndexSha256=${"d".repeat(64)}",
                "guardianRollbackEvidenceSha256=${"e".repeat(64)}",
                "endRung",
                "rung=2",
                "cpu.0=80000",
                "gpuMaxPowerLevel=2",
                "evidenceIndexSha256=${"d".repeat(64)}",
                "guardianRollbackEvidenceSha256=${"e".repeat(64)}",
                "endRung",
                "endProfile",
            ),
        )
        val requestFile = temporaryFolder.newFile("v3-request.txt").toPath()
        Files.write(requestFile, v3Text.toByteArray(StandardCharsets.UTF_8))
        val parsed = QualificationManifestGenerator.parseRequest(requestFile)
        assertEquals(QualificationManifestGenerator.MANIFEST_SCHEMA, parsed.manifestSchema())
        assertEquals(2, parsed.profiles().single().derateRungs().size)

        val direct = QualificationManifestGenerator.Request(
            QualificationManifestGenerator.MANIFEST_SCHEMA,
            1L,
            "a".repeat(64),
            "synthetic-device",
            listOf(
                QualificationManifestGenerator.ProfilePlan(
                    "quiet",
                    linkedMapOf(0 to 100_000),
                    1,
                    1,
                    "b".repeat(64),
                    "c".repeat(64),
                    null,
                    null,
                    null,
                    listOf(
                        QualificationManifestGenerator.RungPlan(linkedMapOf(0 to 90_000), 2, "d".repeat(64), "e".repeat(64)),
                        QualificationManifestGenerator.RungPlan(linkedMapOf(0 to 80_000), 2, "d".repeat(64), "e".repeat(64)),
                    ),
                ),
            ),
        )
        val parsedGenerated = QualificationManifestGenerator.generate(parsed, keyPair.private, keyPair.public)
        val directGenerated = QualificationManifestGenerator.generate(direct, keyPair.private, keyPair.public)
        assertArrayEquals(
            QualificationManifestCodec.encodePayload(runtimeManifest(direct, directGenerated.keyId())),
            parsedGenerated.payload(),
        )

        // Missing the trailing endRung, and a rung sequence that does not start at 1, are both
        // rejected.
        assertRequestError(
            "REQUEST_ORDER_INVALID",
            v3Text.replace("endRung\nrung=2", "rung=2"),
        )
        assertRequestError(
            "DERATE_RUNG_ORDER_INVALID",
            v3Text.replaceFirst("rung=1", "rung=2"),
        )
    }

    @Test
    fun `strict request grammar rejects ambiguity duplicates overflow and noncanonical text`() {
        val valid = requestText(
            sequence = "1",
            profileLines = listOf(
                "profile=quiet",
                "cpu.0=100000",
                "gpuMaxPowerLevel=null",
                "fanMode=null",
                "evidenceIndexSha256=${"b".repeat(64)}",
                "guardianRollbackEvidenceSha256=${"c".repeat(64)}",
                "endProfile",
            ),
        )
        val requestFile = temporaryFolder.newFile("request.txt").toPath()
        Files.write(requestFile, valid.toByteArray(StandardCharsets.UTF_8))
        assertEquals(1L, QualificationManifestGenerator.parseRequest(requestFile).sequence())

        assertRequestError("SEQUENCE_INVALID", valid.replace("sequence=1", "sequence=01"))
        assertRequestError(
            "SEQUENCE_INVALID",
            valid.replace("sequence=1", "sequence=9223372036854775808"),
        )
        assertRequestError(
            "CPU_TARGET_DUPLICATE",
            valid.replace("cpu.0=100000", "cpu.0=100000\ncpu.0=200000"),
        )
        assertRequestError(
            "PROFILE_ID_INVALID",
            valid.replace("profile=quiet", "profile=turbo"),
        )
        assertRequestError(
            "HARDWARE_FINGERPRINT_INVALID",
            valid.replace("hardwareFingerprint=${"a".repeat(64)}", "hardwareFingerprint=${"A".repeat(64)}"),
        )
        assertRequestError("REQUEST_TEXT_INVALID", valid.replace("\n", "\r\n"))
        assertRequestError("REQUEST_TEXT_INVALID", valid.dropLast(1))

        val manualFan = valid
            .replace("deviceProfile=synthetic-device", "deviceProfile=${CpuCeilingTransport.ODIN_3_PROFILE}")
            .replace("fanMode=null", "fanMode=6")
        assertRequestError("FAN_TARGET_INVALID", manualFan)
        assertRequestError("FAN_TARGET_INVALID", valid.replace("fanMode=null", "fanMode=6"))
        assertRequestError("FAN_TARGET_INVALID", valid.replace("fanMode=null", "fanMode=7"))

        assertGeneratorError("PROFILE_ORDER_INVALID") {
            QualificationManifestGenerator.Request(
                1,
                "a".repeat(64),
                "device",
                listOf(profile("quiet"), profile("performance")),
            )
        }
    }

    @Test
    fun `tamper wrong key and non Ed25519 input fail closed`() {
        val capabilities = capabilities()
        val keyPair = keyPair()
        val generated = QualificationManifestGenerator.generate(request(capabilities), keyPair.private, keyPair.public)
        val tampered = generated.signedManifest().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val anchor = QualificationTrustAnchor(mapOf(generated.keyId() to generated.publicKeySpki()))
        val rejection = QualificationManifestVerifier.verify(
            tampered,
            anchor,
            QualificationSequenceStore { _, _ -> QualificationSequenceAdmission.ACCEPTED_NEW },
            capabilities,
        )
        assertEquals(
            QualificationRejection.INVALID_SIGNATURE,
            (rejection as QualificationVerification.Rejected).reason,
        )

        val other = keyPair()
        val wrongAnchor = QualificationTrustAnchor(mapOf(qualificationKeyId(other.public.encoded) to other.public.encoded))
        val wrongKey = QualificationManifestVerifier.verify(
            generated.signedManifest(),
            wrongAnchor,
            QualificationSequenceStore { _, _ -> QualificationSequenceAdmission.ACCEPTED_NEW },
            capabilities,
        )
        assertEquals(QualificationRejection.UNKNOWN_KEY, (wrongKey as QualificationVerification.Rejected).reason)

        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertGeneratorError("PRIVATE_KEY_NOT_ED25519") {
            QualificationManifestGenerator.generate(request(capabilities), rsa.private, rsa.public)
        }
    }

    @Test
    fun `owner local publication is atomic collision safe and contains no private bytes`() {
        val keyPair = keyPair()
        val generated = QualificationManifestGenerator.generate(
            request(capabilities()),
            keyPair.private,
            keyPair.public,
        )
        val repository = temporaryFolder.newFolder("repository").toPath()
        Files.write(repository.resolve(".git"), "gitdir: synthetic\n".toByteArray(StandardCharsets.UTF_8))
        val owner = temporaryFolder.newFolder("owner").toPath()
        val output = owner.resolve("issued-v1")

        QualificationManifestGenerator.writeOwnerLocal(generated, output, repository)
        assertEquals(
            listOf(
                "qualification-manifest.base64",
                "qualification-manifest.mqms",
                "qualification-trust-anchor.base64",
            ),
            Files.list(output).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() },
        )
        assertArrayEquals(generated.signedManifest(), Files.readAllBytes(output.resolve("qualification-manifest.mqms")))
        assertFalse(
            String(Files.readAllBytes(output.resolve("qualification-manifest.base64")), StandardCharsets.US_ASCII)
                .contains('\n'),
        )
        assertFalse(
            String(Files.readAllBytes(output.resolve("qualification-trust-anchor.base64")), StandardCharsets.US_ASCII)
                .contains('\n'),
        )

        val published = Files.list(output).use { paths ->
            paths.sorted().toList().flatMap { Files.readAllBytes(it).asIterable() }.toByteArray()
        }
        assertFalse(published.containsSequence(requireNotNull(keyPair.private.encoded)))
        assertGeneratorError("OUTPUT_EXISTS") {
            QualificationManifestGenerator.writeOwnerLocal(generated, output, repository)
        }
        assertTrue(Files.exists(output.resolve("qualification-manifest.mqms")))

        val racedOutput = owner.resolve("race-created")
        assertGeneratorError("OUTPUT_EXISTS") {
            QualificationManifestGenerator.writeOwnerLocal(
                generated,
                racedOutput,
                repository,
            ) { reserved -> Files.createDirectory(reserved) }
        }
        assertTrue(Files.isDirectory(racedOutput))
        assertEquals(0L, Files.list(racedOutput).use { it.count() })
        assertGeneratorError("OUTPUT_INSIDE_REPOSITORY") {
            QualificationManifestGenerator.writeOwnerLocal(generated, repository.resolve("issued"), repository)
        }
    }

    @Test
    fun `noninteractive CLI refuses before reading any key material and leaks no arguments`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val secretMarker = "owner-secret-marker"
        val result = QualificationManifestGenerator.runCli(
            arrayOf(
                "--request", "/tmp/request-$secretMarker",
                "--keystore", "/tmp/key-$secretMarker",
                "--output-dir", "/tmp/output-$secretMarker",
            ),
            null,
            temporaryFolder.root.toPath(),
            PrintStream(stdout),
            PrintStream(stderr),
        )
        assertEquals(2, result)
        assertEquals("", stdout.toString(StandardCharsets.UTF_8))
        assertEquals("QUALIFICATION_GENERATOR_ERROR=CONSOLE_REQUIRED\n", stderr.toString(StandardCharsets.UTF_8))
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains(secretMarker))
    }

    private fun request(
        capabilities: HardwareCapabilities,
        sequence: Long = 7L,
    ) = QualificationManifestGenerator.Request(
        sequence,
        HardwareFingerprint.create(capabilities),
        capabilities.deviceProfileId,
        listOf(
            profile("balanced", linkedMapOf(0 to 1_500_000, 6 to 2_000_000), gpu = 1, fan = 4),
            profile("performance", linkedMapOf(0 to 2_000_000, 6 to 3_000_000), gpu = 0, fan = 5),
            profile("quiet", linkedMapOf(0 to 1_000_000, 6 to 1_500_000), gpu = 1, fan = 1),
        ),
    )

    private fun profile(
        id: String,
        cpu: Map<Int, Int> = linkedMapOf(0 to 100_000),
        gpu: Int? = null,
        fan: Int? = null,
    ) = QualificationManifestGenerator.ProfilePlan(
        id,
        cpu,
        gpu,
        fan,
        "b".repeat(64),
        "c".repeat(64),
    )

    private fun runtimeManifest(
        request: QualificationManifestGenerator.Request,
        keyId: String,
    ) = QualificationManifest(
        schemaVersion = request.manifestSchema(),
        keyId = keyId,
        sequence = request.sequence(),
        hardwareFingerprint = request.hardwareFingerprint(),
        deviceProfile = request.deviceProfile(),
        servicePlanSchema = when (request.manifestSchema()) {
            QualificationManifestGenerator.LEGACY_MANIFEST_SCHEMA -> LEGACY_QUALIFICATION_SERVICE_PLAN_SCHEMA
            QualificationManifestGenerator.MANIFEST_SCHEMA_V2 -> QUALIFICATION_SERVICE_PLAN_SCHEMA_V2
            else -> QUALIFICATION_SERVICE_PLAN_SCHEMA
        },
        profiles = request.profiles().map { profile ->
            QualifiedProfilePlan.create(
                profileId = profile.profileId(),
                cpuMaxKhz = profile.cpuMaxKhz(),
                gpuMaxPowerLevel = profile.gpuMaxPowerLevel(),
                fanMode = profile.fanMode(),
                evidenceIndexSha256 = profile.evidenceIndexSha256(),
                guardianRollbackEvidenceSha256 = profile.guardianRollbackEvidenceSha256(),
                manualFanOverride = profile.manualFanMinimumDutyPercent()?.let { floor ->
                    QualifiedManualFanOverride.create(
                        cpuMaxKhz = profile.cpuMaxKhz(),
                        gpuMaxPowerLevel = profile.gpuMaxPowerLevel(),
                        minimumDutyPercent = floor,
                        evidenceIndexSha256 = profile.manualFanEvidenceIndexSha256(),
                        guardianRollbackEvidenceSha256 =
                            profile.manualFanGuardianRollbackEvidenceSha256(),
                    )
                },
                derateRungs = profile.derateRungs().map { rung ->
                    QualifiedDerateRung.create(
                        cpuMaxKhz = rung.cpuMaxKhz(),
                        gpuMaxPowerLevel = rung.gpuMaxPowerLevel(),
                        fanMode = profile.fanMode(),
                        evidenceIndexSha256 = rung.evidenceIndexSha256(),
                        guardianRollbackEvidenceSha256 = rung.guardianRollbackEvidenceSha256(),
                    )
                },
            )
        },
    )

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "Synthetic",
        model = "SingleScreen",
        socModel = "TEST-SOC",
        buildFingerprint = "mimir/test/device:1/release-keys",
        deviceProfileId = "synthetic-single-screen-v1",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/policy0", listOf(0, 1, 2, 3), listOf(1_000_000, 1_500_000, 2_000_000), 1_000_000, 2_000_000, 1_000_000),
            CpuPolicy(6, "/policy6", listOf(6, 7), listOf(1_500_000, 2_000_000, 3_000_000), 1_500_000, 3_000_000, 1_500_000),
        ),
        gpuFrequenciesKhz = listOf(900_000, 600_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
    )

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun requestText(
        sequence: String,
        profileLines: List<String>,
        header: String = "MIMIR_QUALIFICATION_REQUEST_V1",
    ): String = buildString {
        appendLine(header)
        appendLine("sequence=$sequence")
        appendLine("hardwareFingerprint=${"a".repeat(64)}")
        appendLine("deviceProfile=synthetic-device")
        profileLines.forEach(::appendLine)
        appendLine("end")
    }

    private fun assertRequestError(expected: String, text: String) {
        val file = temporaryFolder.newFile("request-${System.nanoTime()}.txt").toPath()
        Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
        assertGeneratorError(expected) { QualificationManifestGenerator.parseRequest(file) }
    }

    private fun assertGeneratorError(expected: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is QualificationManifestGenerator.GeneratorException)
        assertEquals(expected, (error as QualificationManifestGenerator.GeneratorException).code())
    }

    private fun ByteArray.containsSequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private object NoLocalCalibrationStore : CalibrationReportStore {
        override fun load(): CalibrationReport? = null
        override fun save(report: CalibrationReport): Boolean = false
        override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? = null
        override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()
    }
}
