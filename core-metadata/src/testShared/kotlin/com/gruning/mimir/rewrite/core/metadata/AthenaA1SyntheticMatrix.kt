package com.gruning.mimir.rewrite.core.metadata

/**
 * One adjudicated, byte-free Athena A1 scenario.
 *
 * [expectedCurrent] records the behavior of the current engine under test. [oracle] records the result
 * that the synthetic known answer permits. Keeping both values is intentional: a characterization test
 * must stay green when it faithfully exposes an unsafe current behavior, while the scorecard must still
 * fail the release target instead of laundering that behavior into a pass.
 */
internal data class AthenaA1Scenario(
    val id: String,
    val platform: String,
    val evidenceBinding: AthenaA1EvidenceBinding,
    val candidates: List<AthenaIdentityCandidate>,
    val expectedCurrent: AthenaA1Disposition,
    val expectedReason: AthenaIdentityDecisionReason? = null,
    val expectedSelected: AthenaIdentityCandidate? = null,
    val oracle: AthenaA1Disposition = expectedCurrent,
    val oracleSelected: AthenaIdentityCandidate? = expectedSelected,
    val legacyAccepted: AthenaIdentityCandidate? = null,
    val unreadableInput: Boolean = false,
)

internal enum class AthenaA1Disposition {
    IDENTIFIED,
    ABSTAINED,
    NEEDS_REVIEW,
}

internal enum class AthenaA1EvidenceBinding {
    CONTENT_HASH,
    EMBEDDED_HEADER,
    FILENAME_CARRIED,
    PROVISIONAL,
    CONNECTED,
    NONE,
    MIXED,
}

internal data class AthenaA1Bucket(
    val attempted: Int,
    val identified: Int,
    val abstained: Int,
    val needsReview: Int,
    val wrongDurableIdentities: Int,
    val unreadable: Int,
)

internal data class AthenaA1SyntheticReport(
    val matrixVersion: String,
    val scenarioCount: Int,
    val identified: Int,
    val abstained: Int,
    val needsReview: Int,
    val knownWrongDurableIdentities: Int,
    val wrongScenarioIds: List<String>,
    val acceptedEqualityChecks: Int,
    val acceptedEqualityFailures: Int,
    val acceptedEqualityScenarioIds: List<String>,
    val permutationChecks: Int,
    val duplicateCandidateScenarios: Int,
    val candidateConflictScenarios: Int,
    val unreadableScenarios: Int,
    val filenameCarriedScenarios: Int,
    val filenameCarriedWrongIdentities: Int,
    val embeddedHeaderScenarios: Int,
    val embeddedHeaderWrongIdentities: Int,
    val byPlatform: Map<String, AthenaA1Bucket>,
    val byEvidenceBinding: Map<String, AthenaA1Bucket>,
) {
    fun toCanonicalJson(): String = buildString {
        append('{')
        append("\"schema\":\"mimir.athena.a1.synthetic-scorecard.v1\",")
        append("\"matrixVersion\":\"").append(matrixVersion).append("\",")
        append("\"scenarioCount\":").append(scenarioCount).append(',')
        append("\"identified\":").append(identified).append(',')
        append("\"abstained\":").append(abstained).append(',')
        append("\"needsReview\":").append(needsReview).append(',')
        append("\"knownWrongDurableIdentities\":").append(knownWrongDurableIdentities).append(',')
        append("\"wrongScenarioIds\":[")
        append(wrongScenarioIds.joinToString(",") { "\"$it\"" })
        append("],")
        append("\"acceptedEqualityChecks\":").append(acceptedEqualityChecks).append(',')
        append("\"acceptedEqualityFailures\":").append(acceptedEqualityFailures).append(',')
        append("\"acceptedEqualityScenarioIds\":[")
        append(acceptedEqualityScenarioIds.joinToString(",") { "\"$it\"" })
        append("],")
        append("\"permutationChecks\":").append(permutationChecks).append(',')
        append("\"duplicateCandidateScenarios\":").append(duplicateCandidateScenarios).append(',')
        append("\"candidateConflictScenarios\":").append(candidateConflictScenarios).append(',')
        append("\"unreadableScenarios\":").append(unreadableScenarios).append(',')
        append("\"filenameCarriedScenarios\":").append(filenameCarriedScenarios).append(',')
        append("\"filenameCarriedWrongIdentities\":").append(filenameCarriedWrongIdentities).append(',')
        append("\"embeddedHeaderScenarios\":").append(embeddedHeaderScenarios).append(',')
        append("\"embeddedHeaderWrongIdentities\":").append(embeddedHeaderWrongIdentities).append(',')
        append("\"byPlatform\":")
        appendBuckets(byPlatform)
        append(',')
        append("\"byEvidenceBinding\":")
        appendBuckets(byEvidenceBinding)
        append('}')
    }

    private fun StringBuilder.appendBuckets(buckets: Map<String, AthenaA1Bucket>) {
        append('{')
        append(
            buckets.toSortedMap().entries.joinToString(",") { (key, bucket) ->
                "\"$key\":{" +
                    "\"attempted\":${bucket.attempted}," +
                    "\"identified\":${bucket.identified}," +
                    "\"abstained\":${bucket.abstained}," +
                    "\"needsReview\":${bucket.needsReview}," +
                    "\"wrongDurableIdentities\":${bucket.wrongDurableIdentities}," +
                    "\"unreadable\":${bucket.unreadable}}"
            },
        )
        append('}')
    }
}

/**
 * A1's public-safe synthetic denominator. It contains no ROM bytes, private names, provider material or
 * private-library hashes. Scenarios are small evidence objects that exercise the exact production authority
 * decision class on both the host JVM and Android.
 */
internal object AthenaA1SyntheticMatrix {
    const val VERSION = "athena-a1b-synthetic-2026-07-23.v2"

    private fun candidate(
        title: String,
        origin: MetadataOrigin,
        evidence: AthenaIdentityEvidence,
        collapseKey: String? = null,
    ) = AthenaIdentityCandidate(
        canonicalTitle = title,
        collapseKey = collapseKey,
        origin = origin,
        evidence = evidence,
    )

    private val noIntroHash = candidate(
        "Metroid Fusion (USA)",
        MetadataOrigin.NO_INTRO,
        AthenaIdentityEvidence.CONTENT_SHA1,
        "Metroid Fusion",
    )
    private val redumpHash = candidate(
        "Chrono Cross (USA) (Disc 1)",
        MetadataOrigin.REDUMP,
        AthenaIdentityEvidence.CONTENT_SHA1,
        "Chrono Cross",
    )
    private val mameHash = candidate(
        "Neo-Geo",
        MetadataOrigin.MAME,
        AthenaIdentityEvidence.CONTENT_SHA1,
    )
    private val titleCatalogHash = candidate(
        "The Legend of Zelda: Breath of the Wild",
        MetadataOrigin.TITLE_CATALOG,
        AthenaIdentityEvidence.CONTENT_SHA1,
    )
    private val gameCubeHeader = candidate(
        "The Legend of Zelda: The Wind Waker",
        MetadataOrigin.TITLE_CATALOG,
        AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
    )
    private val wiiHeader = candidate(
        "Mario Kart Wii",
        MetadataOrigin.TITLE_CATALOG,
        AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
    )
    private val ps3Sfo = candidate(
        "Demon's Souls",
        MetadataOrigin.TITLE_CATALOG,
        AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
    )
    private val arcadeName = candidate(
        "Pac-Man (Midway)",
        MetadataOrigin.MAME,
        AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE,
    )
    private val switchName = candidate(
        "The Legend of Zelda: Breath of the Wild",
        MetadataOrigin.TITLE_CATALOG,
        AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE,
    )
    private val ps3Name = candidate(
        "Demon's Souls",
        MetadataOrigin.TITLE_CATALOG,
        AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE,
    )

    val scenarios: List<AthenaA1Scenario> = listOf(
        authoritative("sha1_no_intro", "gba", AthenaA1EvidenceBinding.CONTENT_HASH, noIntroHash),
        authoritative("sha1_redump", "ps1", AthenaA1EvidenceBinding.CONTENT_HASH, redumpHash),
        authoritative("sha1_mame", "arcade", AthenaA1EvidenceBinding.CONTENT_HASH, mameHash),
        authoritative("sha1_title_catalog", "switch", AthenaA1EvidenceBinding.CONTENT_HASH, titleCatalogHash),
        authoritative(
            "embedded_gamecube_game_id",
            "gamecube",
            AthenaA1EvidenceBinding.EMBEDDED_HEADER,
            gameCubeHeader,
        ),
        authoritative("embedded_wii_game_id", "wii", AthenaA1EvidenceBinding.EMBEDDED_HEADER, wiiHeader),
        authoritative("embedded_ps3_sfo_serial", "ps3", AthenaA1EvidenceBinding.EMBEDDED_HEADER, ps3Sfo),
        abstains("filename_arcade_honest", "arcade", AthenaA1EvidenceBinding.FILENAME_CARRIED, arcadeName),
        abstains("filename_switch_honest", "switch", AthenaA1EvidenceBinding.FILENAME_CARRIED, switchName),
        abstains("filename_ps3_honest", "ps3", AthenaA1EvidenceBinding.FILENAME_CARRIED, ps3Name),
        AthenaA1Scenario(
            id = "ps3_embedded_sfo_beats_conflicting_filename",
            platform = "ps3",
            evidenceBinding = AthenaA1EvidenceBinding.MIXED,
            candidates = listOf(
                ps3Sfo,
                ps3Name.copy(canonicalTitle = "Different filename candidate"),
            ),
            expectedCurrent = AthenaA1Disposition.IDENTIFIED,
            expectedSelected = ps3Sfo,
            oracleSelected = ps3Sfo,
        ),
        AthenaA1Scenario(
            id = "agreeing_hash_and_key_prefers_hash",
            platform = "gamecube",
            evidenceBinding = AthenaA1EvidenceBinding.MIXED,
            candidates = listOf(
                gameCubeHeader,
                gameCubeHeader.copy(evidence = AthenaIdentityEvidence.CONTENT_SHA1, origin = MetadataOrigin.REDUMP),
            ),
            expectedCurrent = AthenaA1Disposition.IDENTIFIED,
            expectedSelected = gameCubeHeader.copy(
                evidence = AthenaIdentityEvidence.CONTENT_SHA1,
                origin = MetadataOrigin.REDUMP,
            ),
        ),
        AthenaA1Scenario(
            id = "duplicate_authority_is_idempotent",
            platform = "gba",
            evidenceBinding = AthenaA1EvidenceBinding.CONTENT_HASH,
            candidates = listOf(noIntroHash, noIntroHash),
            expectedCurrent = AthenaA1Disposition.IDENTIFIED,
            expectedSelected = noIntroHash,
        ),
        abstains(
            id = "crc_size_remains_provisional",
            platform = "snes",
            binding = AthenaA1EvidenceBinding.PROVISIONAL,
            candidate = candidate(
                "Candidate CRC title",
                MetadataOrigin.NO_INTRO,
                AthenaIdentityEvidence.CRC32_SIZE_CANDIDATE,
            ),
        ),
        abstains(
            id = "normalized_title_remains_provisional",
            platform = "gba",
            binding = AthenaA1EvidenceBinding.PROVISIONAL,
            candidate = candidate(
                "Candidate lexical title",
                MetadataOrigin.TITLE_CATALOG,
                AthenaIdentityEvidence.NORMALIZED_TITLE_CANDIDATE,
            ),
        ),
        abstains(
            id = "connected_metadata_remains_presentation_only",
            platform = "ps2",
            binding = AthenaA1EvidenceBinding.CONNECTED,
            candidate = candidate(
                "Provider title",
                MetadataOrigin.SCREENSCRAPER,
                AthenaIdentityEvidence.CONNECTED_METADATA_CANDIDATE,
            ),
        ),
        AthenaA1Scenario(
            id = "empty_evidence_abstains",
            platform = "unknown",
            evidenceBinding = AthenaA1EvidenceBinding.NONE,
            candidates = emptyList(),
            expectedCurrent = AthenaA1Disposition.ABSTAINED,
            expectedReason = AthenaIdentityDecisionReason.NO_EVIDENCE,
        ),
        AthenaA1Scenario(
            id = "unreadable_adapter_yields_no_evidence",
            platform = "ps2",
            evidenceBinding = AthenaA1EvidenceBinding.NONE,
            candidates = emptyList(),
            expectedCurrent = AthenaA1Disposition.ABSTAINED,
            expectedReason = AthenaIdentityDecisionReason.NO_EVIDENCE,
            unreadableInput = true,
        ),
        AthenaA1Scenario(
            id = "mixed_non_authoritative_evidence_abstains",
            platform = "snes",
            evidenceBinding = AthenaA1EvidenceBinding.MIXED,
            candidates = listOf(
                candidate(
                    "CRC candidate",
                    MetadataOrigin.NO_INTRO,
                    AthenaIdentityEvidence.CRC32_SIZE_CANDIDATE,
                ),
                candidate(
                    "Provider candidate",
                    MetadataOrigin.SCREENSCRAPER,
                    AthenaIdentityEvidence.CONNECTED_METADATA_CANDIDATE,
                ),
            ),
            expectedCurrent = AthenaA1Disposition.ABSTAINED,
            expectedReason = AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY,
        ),
        reviewConflict(
            id = "authoritative_title_conflict",
            first = redumpHash,
            second = redumpHash.copy(canonicalTitle = "Biohazard 2"),
        ),
        reviewConflict(
            id = "authoritative_collapse_conflict",
            first = redumpHash,
            second = redumpHash.copy(collapseKey = "Chrono Cross Alternate"),
        ),
        AthenaA1Scenario(
            id = "blank_authoritative_title_fails_closed",
            platform = "gba",
            evidenceBinding = AthenaA1EvidenceBinding.CONTENT_HASH,
            candidates = listOf(noIntroHash.copy(canonicalTitle = " ")),
            expectedCurrent = AthenaA1Disposition.NEEDS_REVIEW,
            expectedReason = AthenaIdentityDecisionReason.MALFORMED_AUTHORITATIVE_EVIDENCE,
        ),
        AthenaA1Scenario(
            id = "connected_source_cannot_masquerade_as_hash",
            platform = "ps2",
            evidenceBinding = AthenaA1EvidenceBinding.CONNECTED,
            candidates = listOf(
                candidate(
                    "Plausible provider title",
                    MetadataOrigin.IGDB,
                    AthenaIdentityEvidence.CONTENT_SHA1,
                ),
            ),
            expectedCurrent = AthenaA1Disposition.NEEDS_REVIEW,
            expectedReason = AthenaIdentityDecisionReason.MALFORMED_AUTHORITATIVE_EVIDENCE,
        ),
        filenameSpoof("filename_arcade_spoof", "arcade", arcadeName),
        filenameSpoof("filename_switch_spoof", "switch", switchName),
        filenameSpoof("filename_ps3_spoof", "ps3", ps3Name),
    )

    fun evaluate(): AthenaA1SyntheticReport {
        val results = scenarios.map { scenario ->
            val forward = AthenaIdentityEngine().decide(scenario.candidates)
            val reverse = AthenaIdentityEngine().decide(scenario.candidates.reversed())
            check(forward == reverse) { "${scenario.id}: candidate ordering changed the decision" }

            val disposition = dispositionOf(forward)
            check(disposition == scenario.expectedCurrent) {
                "${scenario.id}: expected current ${scenario.expectedCurrent}, got $disposition"
            }
            check(reasonOf(forward) == scenario.expectedReason) {
                "${scenario.id}: expected reason ${scenario.expectedReason}, got ${reasonOf(forward)}"
            }
            scenario.expectedSelected?.let { expected ->
                check((forward as? AthenaIdentityDecision.Identified)?.candidate == expected) {
                    "${scenario.id}: selected candidate drifted"
                }
            }

            val equalityFailed = scenario.legacyAccepted?.let { legacy ->
                (forward as? AthenaIdentityDecision.Identified)?.candidate != legacy
            } ?: false
            val oracleWrong =
                disposition != scenario.oracle ||
                    (
                        scenario.oracleSelected != null &&
                            (forward as? AthenaIdentityDecision.Identified)?.candidate != scenario.oracleSelected
                    )
            ScenarioResult(scenario, disposition, equalityFailed, oracleWrong)
        }

        val wrong = results.filter(ScenarioResult::oracleWrong)
        val filename = results.filter { it.scenario.evidenceBinding == AthenaA1EvidenceBinding.FILENAME_CARRIED }
        val embedded = results.filter { it.scenario.evidenceBinding == AthenaA1EvidenceBinding.EMBEDDED_HEADER }
        return AthenaA1SyntheticReport(
            matrixVersion = VERSION,
            scenarioCount = results.size,
            identified = results.count { it.disposition == AthenaA1Disposition.IDENTIFIED },
            abstained = results.count { it.disposition == AthenaA1Disposition.ABSTAINED },
            needsReview = results.count { it.disposition == AthenaA1Disposition.NEEDS_REVIEW },
            knownWrongDurableIdentities = wrong.count {
                it.disposition == AthenaA1Disposition.IDENTIFIED
            },
            wrongScenarioIds = wrong.map { it.scenario.id },
            acceptedEqualityChecks = results.count { it.scenario.legacyAccepted != null },
            acceptedEqualityFailures = results.count(ScenarioResult::equalityFailed),
            acceptedEqualityScenarioIds = results
                .filter { it.scenario.legacyAccepted != null }
                .map { it.scenario.id },
            permutationChecks = results.size,
            duplicateCandidateScenarios = results.count {
                it.scenario.id == "duplicate_authority_is_idempotent"
            },
            candidateConflictScenarios = results.count {
                it.scenario.expectedReason == AthenaIdentityDecisionReason.AUTHORITATIVE_CONFLICT
            },
            unreadableScenarios = results.count { it.scenario.unreadableInput },
            filenameCarriedScenarios = filename.size,
            filenameCarriedWrongIdentities = filename.count(ScenarioResult::oracleWrong),
            embeddedHeaderScenarios = embedded.size,
            embeddedHeaderWrongIdentities = embedded.count(ScenarioResult::oracleWrong),
            byPlatform = results
                .groupBy { it.scenario.platform }
                .mapValues { (_, platformResults) -> platformResults.toBucket() },
            byEvidenceBinding = results
                .groupBy { it.scenario.evidenceBinding.name }
                .mapValues { (_, evidenceResults) -> evidenceResults.toBucket() },
        )
    }

    private fun authoritative(
        id: String,
        platform: String,
        binding: AthenaA1EvidenceBinding,
        candidate: AthenaIdentityCandidate,
    ) = AthenaA1Scenario(
        id = id,
        platform = platform,
        evidenceBinding = binding,
        candidates = listOf(candidate),
        expectedCurrent = AthenaA1Disposition.IDENTIFIED,
        expectedSelected = candidate,
        legacyAccepted = candidate,
    )

    private fun abstains(
        id: String,
        platform: String,
        binding: AthenaA1EvidenceBinding,
        candidate: AthenaIdentityCandidate,
    ) = AthenaA1Scenario(
        id = id,
        platform = platform,
        evidenceBinding = binding,
        candidates = listOf(candidate),
        expectedCurrent = AthenaA1Disposition.ABSTAINED,
        expectedReason = AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY,
    )

    private fun reviewConflict(
        id: String,
        first: AthenaIdentityCandidate,
        second: AthenaIdentityCandidate,
    ) = AthenaA1Scenario(
        id = id,
        platform = "synthetic",
        evidenceBinding = AthenaA1EvidenceBinding.MIXED,
        candidates = listOf(first, second),
        expectedCurrent = AthenaA1Disposition.NEEDS_REVIEW,
        expectedReason = AthenaIdentityDecisionReason.AUTHORITATIVE_CONFLICT,
    )

    private fun filenameSpoof(
        id: String,
        platform: String,
        candidate: AthenaIdentityCandidate,
    ) = AthenaA1Scenario(
        id = id,
        platform = platform,
        evidenceBinding = AthenaA1EvidenceBinding.FILENAME_CARRIED,
        candidates = listOf(candidate),
        expectedCurrent = AthenaA1Disposition.ABSTAINED,
        expectedReason = AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY,
        oracle = AthenaA1Disposition.ABSTAINED,
        oracleSelected = null,
    )

    private fun dispositionOf(decision: AthenaIdentityDecision): AthenaA1Disposition = when (decision) {
        is AthenaIdentityDecision.Identified -> AthenaA1Disposition.IDENTIFIED
        is AthenaIdentityDecision.Abstained -> AthenaA1Disposition.ABSTAINED
        is AthenaIdentityDecision.NeedsReview -> AthenaA1Disposition.NEEDS_REVIEW
    }

    private fun reasonOf(decision: AthenaIdentityDecision): AthenaIdentityDecisionReason? = when (decision) {
        is AthenaIdentityDecision.Identified -> null
        is AthenaIdentityDecision.Abstained -> decision.reason
        is AthenaIdentityDecision.NeedsReview -> decision.reason
    }

    private fun List<ScenarioResult>.toBucket() = AthenaA1Bucket(
        attempted = size,
        identified = count { it.disposition == AthenaA1Disposition.IDENTIFIED },
        abstained = count { it.disposition == AthenaA1Disposition.ABSTAINED },
        needsReview = count { it.disposition == AthenaA1Disposition.NEEDS_REVIEW },
        wrongDurableIdentities = count {
            it.oracleWrong && it.disposition == AthenaA1Disposition.IDENTIFIED
        },
        unreadable = count { it.scenario.unreadableInput },
    )

    private data class ScenarioResult(
        val scenario: AthenaA1Scenario,
        val disposition: AthenaA1Disposition,
        val equalityFailed: Boolean,
        val oracleWrong: Boolean,
    )
}
