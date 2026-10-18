package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AthenaIdentityEngineTest {
    private val engine = AthenaIdentityEngine()

    @Test
    fun `validated content sha1 is authoritative identity`() {
        val candidate = candidate(
            title = "Chrono Cross (USA)",
            collapseKey = "Chrono Cross",
            origin = MetadataOrigin.REDUMP,
            evidence = AthenaIdentityEvidence.CONTENT_SHA1,
        )

        assertEquals(AthenaIdentityDecision.Identified(candidate), engine.decide(listOf(candidate)))
    }

    @Test
    fun `validated title catalog sha1 shard remains authoritative`() {
        val candidate = candidate(
            title = "The Legend of Zelda: Breath of the Wild",
            origin = MetadataOrigin.TITLE_CATALOG,
            evidence = AthenaIdentityEvidence.CONTENT_SHA1,
        )

        assertEquals(AthenaIdentityDecision.Identified(candidate), engine.decide(listOf(candidate)))
    }

    @Test
    fun `every currently shipped dat provenance is admitted deliberately`() {
        val rejected = BundledDatManifest.assets
            .map(BundledDatAsset::origin)
            .distinct()
            .filter { origin ->
                engine.decide(
                    listOf(
                        candidate(
                            title = "Fixture",
                            origin = origin,
                            evidence = AthenaIdentityEvidence.CONTENT_SHA1,
                        ),
                    ),
                ) !is AthenaIdentityDecision.Identified
            }

        assertEquals(emptyList<MetadataOrigin>(), rejected)
    }

    @Test
    fun `payload bound local catalog key is authoritative identity`() {
        val candidate = candidate(
            title = "Metroid Prime",
            origin = MetadataOrigin.TITLE_CATALOG,
            evidence = AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
        )

        assertEquals(AthenaIdentityDecision.Identified(candidate), engine.decide(listOf(candidate)))
    }

    @Test
    fun `every currently shipped id catalog provenance is admitted deliberately`() {
        val rejected = BundledIdManifest.assets
            .map(BundledIdAsset::origin)
            .distinct()
            .filter { origin ->
                engine.decide(
                    listOf(
                        candidate(
                            title = "Fixture",
                            origin = origin,
                            evidence = AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
                        ),
                    ),
                ) !is AthenaIdentityDecision.Identified
            }

        assertEquals(emptyList<MetadataOrigin>(), rejected)
    }

    @Test
    fun `filename catalog key remains provisional`() {
        val candidate = candidate(
            title = "Plausible filename title",
            origin = MetadataOrigin.TITLE_CATALOG,
            evidence = AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE,
        )

        assertEquals(
            AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY),
            engine.decide(listOf(candidate)),
        )
    }

    @Test
    fun `header claimed CHD digest remains provisional until raw payload verification`() {
        val candidate = candidate(
            title = "Header claim",
            origin = MetadataOrigin.REDUMP,
            evidence = AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE,
        )

        assertEquals(
            AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY),
            engine.decide(listOf(candidate)),
        )
    }

    @Test
    fun `MAME direct payload is authoritative while Switch ticket remains provisional`() {
        val mame = AthenaIdentityCandidate(
            canonicalTitle = "Fixture Arcade",
            collapseKey = "mame:fixture",
            origin = MetadataOrigin.MAME,
            evidence = AthenaIdentityEvidence.MAME_MACHINE_PAYLOAD_SHA1,
            artifact = AthenaArtifactEvidence(
                parser = AthenaParserKind.MAME_ZIP_SET,
                parserVersion = 1,
                catalogStamp = MamePayloadManifest.POLICY_STAMP,
                validation = AthenaValidationLevel.DIRECT_MACHINE_PAYLOAD_MULTISET,
                physicalRole = AthenaPhysicalRole.MAME_MACHINE_PAYLOAD,
                rawKey = "a".repeat(64),
                complete = false,
                unique = true,
            ),
        )
        val update = AthenaIdentityCandidate(
            canonicalTitle = "Fixture Switch",
            collapseKey = null,
            origin = MetadataOrigin.TITLE_CATALOG,
            evidence = AthenaIdentityEvidence.SWITCH_TICKET_CATALOG_KEY,
            artifact = AthenaArtifactEvidence(
                parser = AthenaParserKind.SWITCH_PFS0_TICKET,
                parserVersion = SwitchPfs0IdentityReader.PARSER_VERSION,
                catalogStamp = BundledIdManifest.assets.single {
                    it.platformId.value == "switch"
                }.contentSha256,
                validation = AthenaValidationLevel.STRUCTURED_EMBEDDED_FIELD,
                physicalRole = AthenaPhysicalRole.UPDATE_PACKAGE,
                rawKey = "0100ABCD12345800",
                complete = true,
                unique = true,
            ),
        )

        assertEquals(AthenaIdentityDecision.Identified(mame), engine.decide(listOf(mame)))
        assertEquals(
            AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY),
            engine.decide(listOf(update)),
        )
        assertTrue(
            engine.decide(listOf(mame.copy(artifact = mame.artifact?.copy(complete = true)))) is
                AthenaIdentityDecision.NeedsReview,
        )
    }

    @Test
    fun `raw-verified CHD requires the exact catalog provenance for its origin`() {
        val redump = AthenaIdentityCandidate(
            canonicalTitle = "Fixture Disc",
            collapseKey = null,
            origin = MetadataOrigin.REDUMP,
            evidence = AthenaIdentityEvidence.CHD_RECOMPUTED_OVERALL_SHA1,
            artifact = AthenaArtifactEvidence(
                parser = AthenaParserKind.LIBCHDR_RAW_AND_METADATA,
                parserVersion = ChdPayloadVerifier.PARSER_VERSION,
                catalogStamp = BundledDatManifest.catalogueStamp,
                validation = AthenaValidationLevel.RAW_AND_METADATA_RECOMPUTED,
                physicalRole = AthenaPhysicalRole.COMPLETE_DISC,
                rawKey = "b".repeat(40),
                complete = true,
                unique = true,
            ),
        )

        assertEquals(AthenaIdentityDecision.Identified(redump), engine.decide(listOf(redump)))
        assertTrue(
            engine.decide(
                listOf(redump.copy(artifact = redump.artifact?.copy(catalogStamp = "wrong"))),
            ) is AthenaIdentityDecision.NeedsReview,
        )
    }

    @Test
    fun `chain-verified CHD admits with its own parser version and never cross-wires`() {
        val chain = AthenaIdentityCandidate(
            canonicalTitle = "Fixture Differential Disc",
            collapseKey = null,
            origin = MetadataOrigin.REDUMP,
            evidence = AthenaIdentityEvidence.CHD_RECOMPUTED_OVERALL_SHA1,
            artifact = AthenaArtifactEvidence(
                parser = AthenaParserKind.LIBCHDR_PARENT_CHAIN,
                parserVersion = ChdChainPayloadVerifier.PARSER_VERSION,
                catalogStamp = BundledDatManifest.catalogueStamp,
                validation = AthenaValidationLevel.RAW_AND_METADATA_RECOMPUTED,
                physicalRole = AthenaPhysicalRole.COMPLETE_DISC,
                rawKey = "c".repeat(40),
                complete = true,
                unique = true,
            ),
        )

        assertEquals(AthenaIdentityDecision.Identified(chain), engine.decide(listOf(chain)))
        // A chain artifact under the standalone verifier's version, or the standalone parser under the
        // chain's version, is malformed evidence — the parser and its recomputation policy travel together.
        assertTrue(
            engine.decide(
                listOf(
                    chain.copy(
                        artifact = chain.artifact?.copy(
                            parserVersion = ChdPayloadVerifier.PARSER_VERSION,
                        ),
                    ),
                ),
            ) is AthenaIdentityDecision.NeedsReview,
        )
        assertTrue(
            engine.decide(
                listOf(
                    chain.copy(
                        artifact = chain.artifact?.copy(
                            parser = AthenaParserKind.LIBCHDR_RAW_AND_METADATA,
                            parserVersion = ChdChainPayloadVerifier.PARSER_VERSION,
                        ),
                    ),
                ),
            ) is AthenaIdentityDecision.NeedsReview,
        )
    }

    @Test
    fun `crc size and lexical candidates never become durable identity`() {
        val candidates = listOf(
            candidate(
                title = "Candidate from CRC",
                origin = MetadataOrigin.NO_INTRO,
                evidence = AthenaIdentityEvidence.CRC32_SIZE_CANDIDATE,
            ),
            candidate(
                title = "Candidate from name",
                origin = MetadataOrigin.TITLE_CATALOG,
                evidence = AthenaIdentityEvidence.NORMALIZED_TITLE_CANDIDATE,
            ),
        )

        assertEquals(
            AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY),
            engine.decide(candidates),
        )
    }

    @Test
    fun `connected metadata remains presentation only`() {
        val candidate = candidate(
            title = "Provider title",
            origin = MetadataOrigin.SCREENSCRAPER,
            evidence = AthenaIdentityEvidence.CONNECTED_METADATA_CANDIDATE,
        )

        assertEquals(
            AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY),
            engine.decide(listOf(candidate)),
        )
    }

    @Test
    fun `conflicting authoritative identities require review in stable order`() {
        val catalog = candidate(
            title = "Resident Evil 2",
            origin = MetadataOrigin.TITLE_CATALOG,
            evidence = AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
        )
        val hash = candidate(
            title = "Biohazard 2",
            origin = MetadataOrigin.REDUMP,
            evidence = AthenaIdentityEvidence.CONTENT_SHA1,
        )

        val forward = engine.decide(listOf(catalog, hash))
        val reverse = engine.decide(listOf(hash, catalog))

        assertEquals(forward, reverse)
        assertTrue(forward is AthenaIdentityDecision.NeedsReview)
        forward as AthenaIdentityDecision.NeedsReview
        assertEquals(AthenaIdentityDecisionReason.AUTHORITATIVE_CONFLICT, forward.reason)
        assertEquals(listOf(hash, catalog), forward.candidates)
    }

    @Test
    fun `agreeing authoritative evidence deterministically prefers content sha1`() {
        val catalog = candidate(
            title = "Metroid Prime",
            origin = MetadataOrigin.TITLE_CATALOG,
            evidence = AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
        )
        val hash = candidate(
            title = "Metroid Prime",
            origin = MetadataOrigin.REDUMP,
            evidence = AthenaIdentityEvidence.CONTENT_SHA1,
        )

        val expected = AthenaIdentityDecision.Identified(hash)
        assertEquals(expected, engine.decide(listOf(catalog, hash)))
        assertEquals(expected, engine.decide(listOf(hash, catalog)))
    }

    @Test
    fun `malformed or source-incompatible authoritative evidence fails closed`() {
        val blank = candidate(
            title = " ",
            origin = MetadataOrigin.NO_INTRO,
            evidence = AthenaIdentityEvidence.CONTENT_SHA1,
        )
        val connectedMasqueradingAsHash = candidate(
            title = "Plausible title",
            origin = MetadataOrigin.IGDB,
            evidence = AthenaIdentityEvidence.CONTENT_SHA1,
        )

        val decision = engine.decide(listOf(connectedMasqueradingAsHash, blank))

        assertTrue(decision is AthenaIdentityDecision.NeedsReview)
        decision as AthenaIdentityDecision.NeedsReview
        assertEquals(AthenaIdentityDecisionReason.MALFORMED_AUTHORITATIVE_EVIDENCE, decision.reason)
        assertEquals(2, decision.candidates.size)
    }

    @Test
    fun `empty evidence abstains explicitly`() {
        assertEquals(
            AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.NO_EVIDENCE),
            engine.decide(emptyList()),
        )
    }

    private fun candidate(
        title: String,
        collapseKey: String? = null,
        origin: MetadataOrigin,
        evidence: AthenaIdentityEvidence,
    ) = AthenaIdentityCandidate(
        canonicalTitle = title,
        collapseKey = collapseKey,
        origin = origin,
        evidence = evidence,
    )
}
