package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A loose CHD is identified by name or header only; its header digest is admitted as declared evidence. It is admitted on exactly the recomputed class's terms, with its own parser and validation level.
 */
class AthenaChdHeaderDeclaredTest {
    private val engine = AthenaIdentityEngine()

    private val declared = AthenaIdentityCandidate(
        canonicalTitle = "Fixture Disc",
        collapseKey = null,
        origin = MetadataOrigin.REDUMP,
        evidence = AthenaIdentityEvidence.CHD_HEADER_DECLARED_SHA1,
        artifact = AthenaArtifactEvidence(
            parser = AthenaParserKind.CHD_V5_HEADER,
            parserVersion = RomFingerprinter.CHD_HEADER_PARSER_VERSION,
            catalogStamp = BundledDatManifest.catalogueStamp,
            validation = AthenaValidationLevel.HEADER_DECLARED_DIGEST,
            physicalRole = AthenaPhysicalRole.COMPLETE_DISC,
            rawKey = "c".repeat(40),
            complete = true,
            unique = true,
        ),
    )

    @Test
    fun `a well formed header declaration identifies`() {
        assertEquals(AthenaIdentityEvidence.CHD_HEADER_DECLARED_SHA1.authority, AthenaIdentityAuthority.AUTHORITATIVE)
        assertEquals(AthenaIdentityDecision.Identified(declared), engine.decide(listOf(declared)))
    }

    @Test
    fun `it cannot borrow the recompute's parser, level or catalogue and still pass`() {
        val artifact = requireNotNull(declared.artifact)
        listOf(
            artifact.copy(parser = AthenaParserKind.LIBCHDR_RAW_AND_METADATA),
            artifact.copy(validation = AthenaValidationLevel.RAW_AND_METADATA_RECOMPUTED),
            artifact.copy(parserVersion = RomFingerprinter.CHD_HEADER_PARSER_VERSION + 1),
            artifact.copy(catalogStamp = "wrong"),
            artifact.copy(rawKey = "not-a-sha1"),
            artifact.copy(physicalRole = AthenaPhysicalRole.MAME_DISK_MEMBER),
        ).forEach { malformed ->
            assertTrue(
                "$malformed must fail closed",
                engine.decide(listOf(declared.copy(artifact = malformed))) is AthenaIdentityDecision.NeedsReview,
            )
        }
    }

    @Test
    fun `admitting a new authoritative class moved the policy stamp, so the latch fires`() {
        assertTrue(AthenaIdentityPolicy.STAMP, AthenaIdentityPolicy.STAMP.contains("-v6-"))
        assertTrue(AthenaIdentityPolicy.STAMP, AthenaIdentityPolicy.STAMP.contains("chd-header"))
    }

    @Test
    fun `the archive-read header claim is still only a candidate`() {
        assertEquals(
            AthenaIdentityAuthority.PROVISIONAL,
            AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE.authority,
        )
    }

    @Test
    fun `a recompute outranks the header, and the header outranks a name`() {
        val recomputed = declared.copy(
            evidence = AthenaIdentityEvidence.CHD_RECOMPUTED_OVERALL_SHA1,
            artifact = declared.artifact?.copy(
                parser = AthenaParserKind.LIBCHDR_RAW_AND_METADATA,
                parserVersion = ChdPayloadVerifier.PARSER_VERSION,
                validation = AthenaValidationLevel.RAW_AND_METADATA_RECOMPUTED,
            ),
        )
        // Agreeing evidence resolves to the strongest class, whichever order it arrives in.
        assertEquals(
            AthenaIdentityDecision.Identified(recomputed),
            engine.decide(listOf(declared, recomputed)),
        )
        // Disagreeing evidence is a review, never a silent overwrite.
        assertTrue(
            engine.decide(listOf(declared, recomputed.copy(canonicalTitle = "Another Disc"))) is
                AthenaIdentityDecision.NeedsReview,
        )
    }
}
