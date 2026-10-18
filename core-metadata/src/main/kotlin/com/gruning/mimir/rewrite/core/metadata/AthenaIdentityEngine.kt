package com.gruning.mimir.rewrite.core.metadata

/**
 * The authority carried by one identity observation.
 *
 * Athena v0 deliberately separates a useful candidate from evidence that is strong enough to mutate
 * durable library identity. This is a release boundary, not a confidence score.
 */
internal enum class AthenaIdentityAuthority {
    AUTHORITATIVE,
    PROVISIONAL,
    PRESENTATION_ONLY,
}

/**
 * Grounded evidence kinds understood by Athena v0.
 *
 * Legacy loose-content [CONTENT_SHA1]/[EMBEDDED_CATALOG_KEY] and the versioned container evidence kinds may
 * produce an identified result:
 * - [CONTENT_SHA1] is a hit in a validated, SHA-1-only bundled DAT shard over bytes Mimir actually hashed.
 *   [CHD_RECOMPUTED_OVERALL_SHA1] requires libchdr to recompute both logical payload and checksum-bearing
 *   metadata before catalog authority. A loose CHD's own header digest is admitted as
 *   [CHD_HEADER_DECLARED_SHA1] by policy (2026-09-27); the same digest read from inside an archive stays
 *   the provisional [CHD_HEADER_SHA1_CANDIDATE].
 * - [EMBEDDED_CATALOG_KEY] is a Game ID or serial read from payload-bound header/SFO bytes and found in a
 *   validated bundled catalog.
 * - container evidence additionally requires a parser/version/catalog/role/completeness descriptor.
 *
 * The remaining kinds are named now so future resolver work cannot accidentally promote them merely
 * because it found a plausible title. CRC32 plus size can narrow known catalog collisions but cannot prove
 * an unknown payload is that catalog entry. A catalogued MAME shortname, Switch Title ID or PS3 serial that
 * exists only in a filename has the same limitation: it is useful candidate evidence, not proof about bytes.
 * Lexical and connected-provider matches are likewise candidates, not durable ROM identity.
 */
internal enum class AthenaIdentityEvidence(
    val authority: AthenaIdentityAuthority,
) {
    CONTENT_SHA1(AthenaIdentityAuthority.AUTHORITATIVE),
    EMBEDDED_CATALOG_KEY(AthenaIdentityAuthority.AUTHORITATIVE),
    MAME_MACHINE_PAYLOAD_SHA1(AthenaIdentityAuthority.AUTHORITATIVE),
    // A ticket is self-declared package metadata until cryptographically/content-bound to NCA/CNMT.
    SWITCH_TICKET_CATALOG_KEY(AthenaIdentityAuthority.PROVISIONAL),
    CHD_RECOMPUTED_OVERALL_SHA1(AthenaIdentityAuthority.AUTHORITATIVE),

    /**
     * A loose `.chd`'s identity from the combined SHA-1 its own v5 header declares, looked up in exactly the
     * catalogue, by exactly the key ([ContentHash] of that SHA-1 and the header's logical size), that a
     * libchdr recompute would have used. So it can never name a disc the recompute would not have named; it
     * only skips the decompression that confirms the header.
     *
     * Name/header-only identification skips whole-file decompression. A reference RG Rotate carried
     * twelve PS2 CHDs (~24 GB). Most of the cost was modified images
     * -- undubs, widescreen patches, RetroAchievements subsets -- that no catalogue carries: libchdr decompressed
     * every byte of each, and then they abstained anyway. By header they abstain at once.
     *
     * The trade is the one [DAT_NAME_UNIQUE] made on 2026-09-17, one rung up: a header is a claim about THIS
     * file's bytes, where a name is not. A CHD whose payload is damaged behind an intact header keeps its
     * catalogue title. Ranked below the recomputed class and above the name, so a later recompute that
     * disagrees becomes an [AthenaIdentityDecisionReason.AUTHORITATIVE_CONFLICT] -- a review, never a silent
     * overwrite. Unlike the name class, nothing re-reads a header-identified CHD yet; adding that
     * background verification remains a design decision.
     */
    CHD_HEADER_DECLARED_SHA1(AthenaIdentityAuthority.AUTHORITATIVE),

    /**
     * The one authoritative class that does not read the payload: a ROM whose FILE NAME matches exactly one
     * dump in a curated, digest-locked shard once both sides are normalized by [DatNameKey].
     *
     * It is admitted because identifying a disc image by content means reading every byte of it — 19.5 GB
     * for the fifteen discs on the reference device, and worse for CHD, which must be decompressed to confirm
     * its own header. That cost is the product's single largest first-run stall, and the corpus this matches
     * against is the same one the hash would have consulted.
     *
     * Its authority rests entirely on the narrowing being exhaustive rather than best-effort: stem AND disc
     * must match, a plainly-named file never matches a beta/prototype dump, and anything short of exactly
     * one survivor resolves nothing and falls back to the hash. It is ranked BELOW every content-derived
     * class, so when a hash later arrives it sorts first and, if it disagrees, the decision becomes
     * [AthenaIdentityDecisionReason.AUTHORITATIVE_CONFLICT] — a review, not a silent overwrite.
     *
     * Still not proof about bytes. A deliberately mislabelled file gets a wrong title until something reads
     * it. That trade is made knowingly (2026-09-17), and the conflict path above
     * is what makes it recoverable.
     */
    DAT_NAME_UNIQUE(AthenaIdentityAuthority.AUTHORITATIVE),
    CHD_HEADER_SHA1_CANDIDATE(AthenaIdentityAuthority.PROVISIONAL),
    FILENAME_CATALOG_KEY_CANDIDATE(AthenaIdentityAuthority.PROVISIONAL),
    CRC32_SIZE_CANDIDATE(AthenaIdentityAuthority.PROVISIONAL),
    NORMALIZED_TITLE_CANDIDATE(AthenaIdentityAuthority.PROVISIONAL),
    CONNECTED_METADATA_CANDIDATE(AthenaIdentityAuthority.PRESENTATION_ONLY),
}

/** Moves only when a production evidence class changes durable-identity authority. */
object AthenaIdentityPolicy {
    const val STAMP: String = "athena-identity-authority-v6-chd-header-declared-authoritative"
}

internal enum class AthenaParserKind {
    MAME_ZIP_SET,
    SWITCH_PFS0_TICKET,
    LIBCHDR_RAW_AND_METADATA,
    LIBCHDR_PARENT_CHAIN,
    DAT_DUMP_NAME,
    CHD_V5_HEADER,
}

internal enum class AthenaValidationLevel {
    DIRECT_MACHINE_PAYLOAD_MULTISET,
    STRUCTURED_EMBEDDED_FIELD,
    RAW_AND_METADATA_RECOMPUTED,
    UNIQUE_DUMP_NAME_IN_SHARD,
    HEADER_DECLARED_DIGEST,
}

internal enum class AthenaPhysicalRole {
    MAME_MACHINE_PAYLOAD,
    BASE_PACKAGE,
    UPDATE_PACKAGE,
    COMPLETE_DISC,
    MAME_DISK_MEMBER,
}

/** Versioned payload proof retained through Athena's decision boundary. */
internal data class AthenaArtifactEvidence(
    val parser: AthenaParserKind,
    val parserVersion: Int,
    val catalogStamp: String,
    val validation: AthenaValidationLevel,
    val physicalRole: AthenaPhysicalRole,
    val rawKey: String,
    val complete: Boolean,
    val unique: Boolean,
)

/** One candidate presented to the pure Athena v0 identity decision boundary. */
internal data class AthenaIdentityCandidate(
    val canonicalTitle: String,
    val collapseKey: String?,
    val origin: MetadataOrigin,
    val evidence: AthenaIdentityEvidence,
    val artifact: AthenaArtifactEvidence? = null,
)

internal enum class AthenaIdentityDecisionReason {
    NO_EVIDENCE,
    INSUFFICIENT_AUTHORITY,
    MALFORMED_AUTHORITATIVE_EVIDENCE,
    AUTHORITATIVE_CONFLICT,
}

/** A typed, deterministic result. Callers can never mistake a candidate for an accepted identity. */
internal sealed interface AthenaIdentityDecision {
    data class Identified(val candidate: AthenaIdentityCandidate) : AthenaIdentityDecision

    data class NeedsReview(
        val reason: AthenaIdentityDecisionReason,
        val candidates: List<AthenaIdentityCandidate>,
    ) : AthenaIdentityDecision

    data class Abstained(val reason: AthenaIdentityDecisionReason) : AthenaIdentityDecision
}

/**
 * Athena v0: Mimir's pure, deterministic and offline identity-authority engine.
 *
 * It performs no I/O, owns no clock, writes no state and has no score thresholds. Existing resolvers gather
 * evidence; Athena decides whether that evidence is allowed to become durable canonical identity:
 *
 * 1. malformed or source-incompatible authoritative evidence fails closed;
 * 2. zero authoritative candidates abstains;
 * 3. conflicting authoritative identities require review;
 * 4. agreeing authoritative candidates identify deterministically, preferring content SHA-1.
 *
 * This intentionally does not import the experimental Athena database, scorer, session observer, review
 * queue, CRC packs, or connected-provider claims. Those remain separate feasibility gates.
 */
internal class AthenaIdentityEngine {

    fun decide(candidates: Collection<AthenaIdentityCandidate>): AthenaIdentityDecision {
        if (candidates.isEmpty()) {
            return AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.NO_EVIDENCE)
        }

        val malformedAuthoritative = candidates
            .filter { it.evidence.authority == AthenaIdentityAuthority.AUTHORITATIVE }
            .filterNot(::isAdmissible)
            .sortedWith(CANDIDATE_ORDER)
        if (malformedAuthoritative.isNotEmpty()) {
            return AthenaIdentityDecision.NeedsReview(
                reason = AthenaIdentityDecisionReason.MALFORMED_AUTHORITATIVE_EVIDENCE,
                candidates = malformedAuthoritative,
            )
        }

        val authoritative = candidates
            .filter { it.evidence.authority == AthenaIdentityAuthority.AUTHORITATIVE }
            .filter(::isAdmissible)
            .sortedWith(CANDIDATE_ORDER)
        if (authoritative.isEmpty()) {
            return AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY)
        }

        val identities = authoritative.groupBy { candidate ->
            CanonicalIdentity(candidate.canonicalTitle, candidate.collapseKey)
        }
        if (identities.size > 1) {
            return AthenaIdentityDecision.NeedsReview(
                reason = AthenaIdentityDecisionReason.AUTHORITATIVE_CONFLICT,
                candidates = authoritative,
            )
        }

        return AthenaIdentityDecision.Identified(authoritative.first())
    }

    private fun isAdmissible(candidate: AthenaIdentityCandidate): Boolean {
        if (candidate.canonicalTitle.isBlank()) return false
        if (candidate.collapseKey != null && candidate.collapseKey.isBlank()) return false
        return when (candidate.evidence) {
            AthenaIdentityEvidence.CONTENT_SHA1 ->
                candidate.origin == MetadataOrigin.NO_INTRO ||
                    candidate.origin == MetadataOrigin.REDUMP ||
                    candidate.origin == MetadataOrigin.MAME ||
                    candidate.origin == MetadataOrigin.TITLE_CATALOG

            AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY ->
                candidate.origin == MetadataOrigin.TITLE_CATALOG ||
                    candidate.origin == MetadataOrigin.MAME

            AthenaIdentityEvidence.MAME_MACHINE_PAYLOAD_SHA1 ->
                candidate.origin == MetadataOrigin.MAME &&
                    candidate.artifact?.let {
                        it.parser == AthenaParserKind.MAME_ZIP_SET &&
                            it.parserVersion == 1 &&
                            it.catalogStamp == MamePayloadManifest.POLICY_STAMP &&
                            it.validation == AthenaValidationLevel.DIRECT_MACHINE_PAYLOAD_MULTISET &&
                            it.physicalRole == AthenaPhysicalRole.MAME_MACHINE_PAYLOAD &&
                            !it.complete &&
                            it.unique &&
                            SHA256.matches(it.rawKey)
                    } == true

            AthenaIdentityEvidence.SWITCH_TICKET_CATALOG_KEY ->
                candidate.origin == MetadataOrigin.TITLE_CATALOG &&
                    candidate.artifact?.let {
                        it.parser == AthenaParserKind.SWITCH_PFS0_TICKET &&
                            it.parserVersion == SwitchPfs0IdentityReader.PARSER_VERSION &&
                            it.catalogStamp == BundledIdManifest.assets.single { asset ->
                                asset.platformId.value == "switch"
                            }.contentSha256 &&
                            it.validation == AthenaValidationLevel.STRUCTURED_EMBEDDED_FIELD &&
                            it.physicalRole in setOf(
                                AthenaPhysicalRole.BASE_PACKAGE,
                                AthenaPhysicalRole.UPDATE_PACKAGE,
                            ) &&
                            it.complete &&
                            it.unique &&
                            TITLE_ID.matches(it.rawKey)
                    } == true

            AthenaIdentityEvidence.CHD_RECOMPUTED_OVERALL_SHA1 ->
                candidate.origin in setOf(MetadataOrigin.REDUMP, MetadataOrigin.MAME) &&
                    candidate.artifact?.let {
                        (
                            it.parser == AthenaParserKind.LIBCHDR_RAW_AND_METADATA &&
                                it.parserVersion == ChdPayloadVerifier.PARSER_VERSION ||
                                // A parent chain admits identically: every node, root included, was
                                // independently recomputed before the target digest was exposed.
                                it.parser == AthenaParserKind.LIBCHDR_PARENT_CHAIN &&
                                it.parserVersion == ChdChainPayloadVerifier.PARSER_VERSION
                            ) &&
                            (
                                candidate.origin == MetadataOrigin.MAME &&
                                    it.catalogStamp == MamePayloadManifest.POLICY_STAMP ||
                                    candidate.origin == MetadataOrigin.REDUMP &&
                                    it.catalogStamp == BundledDatManifest.catalogueStamp
                            ) &&
                            it.validation == AthenaValidationLevel.RAW_AND_METADATA_RECOMPUTED &&
                            (
                                candidate.origin == MetadataOrigin.REDUMP &&
                                    it.physicalRole == AthenaPhysicalRole.COMPLETE_DISC &&
                                    it.complete ||
                                    candidate.origin == MetadataOrigin.MAME &&
                                    it.physicalRole == AthenaPhysicalRole.MAME_DISK_MEMBER &&
                                    !it.complete
                            ) &&
                            it.unique &&
                            SHA1.matches(it.rawKey)
                    } == true

            AthenaIdentityEvidence.CHD_HEADER_DECLARED_SHA1 ->
                // The recomputed class's own shape, with the header parser and level in place of libchdr's.
                candidate.origin in setOf(MetadataOrigin.REDUMP, MetadataOrigin.MAME) &&
                    candidate.artifact?.let {
                        it.parser == AthenaParserKind.CHD_V5_HEADER &&
                            it.parserVersion == RomFingerprinter.CHD_HEADER_PARSER_VERSION &&
                            (
                                candidate.origin == MetadataOrigin.MAME &&
                                    it.catalogStamp == MamePayloadManifest.POLICY_STAMP ||
                                    candidate.origin == MetadataOrigin.REDUMP &&
                                    it.catalogStamp == BundledDatManifest.catalogueStamp
                            ) &&
                            it.validation == AthenaValidationLevel.HEADER_DECLARED_DIGEST &&
                            (
                                candidate.origin == MetadataOrigin.REDUMP &&
                                    it.physicalRole == AthenaPhysicalRole.COMPLETE_DISC &&
                                    it.complete ||
                                    candidate.origin == MetadataOrigin.MAME &&
                                    it.physicalRole == AthenaPhysicalRole.MAME_DISK_MEMBER &&
                                    !it.complete
                            ) &&
                            it.unique &&
                            SHA1.matches(it.rawKey)
                    } == true

            AthenaIdentityEvidence.DAT_NAME_UNIQUE ->
                // Deliberately does NOT require a collapse key. A multi-disc group's candidate passes
                // through `forExistingGroup`, which nulls it on purpose -- the members already share one
                // authoritative gameId and a per-disc DAT key would manufacture a second fold axis. The
                // artifact below is the real gate; a null collapse key is the shared blank-check above.
                (candidate.origin == MetadataOrigin.NO_INTRO || candidate.origin == MetadataOrigin.REDUMP) &&
                    candidate.artifact?.let {
                        it.parser == AthenaParserKind.DAT_DUMP_NAME &&
                            it.parserVersion == DatNameKey.VERSION &&
                            it.validation == AthenaValidationLevel.UNIQUE_DUMP_NAME_IN_SHARD &&
                            it.physicalRole == AthenaPhysicalRole.COMPLETE_DISC &&
                            it.complete &&
                            it.unique &&
                            it.rawKey.isNotBlank() &&
                            SHA256.matches(it.catalogStamp)
                    } == true

            AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE,
            AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE,
            AthenaIdentityEvidence.CRC32_SIZE_CANDIDATE,
            AthenaIdentityEvidence.NORMALIZED_TITLE_CANDIDATE,
            AthenaIdentityEvidence.CONNECTED_METADATA_CANDIDATE,
            -> true
        }
    }

    private data class CanonicalIdentity(
        val canonicalTitle: String,
        val collapseKey: String?,
    )

    private companion object {
        val CANDIDATE_ORDER = compareBy<AthenaIdentityCandidate>(
            { evidencePriority(it.evidence) },
            { it.canonicalTitle },
            { it.collapseKey ?: "" },
            { it.origin.name },
            { it.artifact?.physicalRole?.name ?: "" },
            { it.artifact?.rawKey ?: "" },
        )

        fun evidencePriority(evidence: AthenaIdentityEvidence): Int = when (evidence) {
            AthenaIdentityEvidence.CONTENT_SHA1 -> 0
            AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY -> 1
            AthenaIdentityEvidence.MAME_MACHINE_PAYLOAD_SHA1 -> 2
            AthenaIdentityEvidence.SWITCH_TICKET_CATALOG_KEY -> 3
            AthenaIdentityEvidence.CHD_RECOMPUTED_OVERALL_SHA1 -> 4
            // A header's declaration about this file's bytes: below their recompute, above any name.
            AthenaIdentityEvidence.CHD_HEADER_DECLARED_SHA1 -> 5
            // Last of the authoritative classes: any content proof outranks a name.
            AthenaIdentityEvidence.DAT_NAME_UNIQUE -> 6
            AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE -> 7
            AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE -> 8
            AthenaIdentityEvidence.CRC32_SIZE_CANDIDATE -> 9
            AthenaIdentityEvidence.NORMALIZED_TITLE_CANDIDATE -> 10
            AthenaIdentityEvidence.CONNECTED_METADATA_CANDIDATE -> 11
        }

        val SHA1 = Regex("[0-9a-f]{40}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val TITLE_ID = Regex("[0-9A-F]{16}")
    }
}
