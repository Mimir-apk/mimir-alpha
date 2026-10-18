package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [AthenaIdentityEvidence.DAT_NAME_UNIQUE] is allowed to do, and what protects the product from it.
 *
 * Admitting a name as AUTHORITATIVE reverses a rule this engine was built on — that only payload-bound
 * evidence may write durable identity. It was admitted on 2026-09-17 to remove the
 * multi-GB first-run read, and the trade only holds while two things stay true:
 *
 *  1. the artifact gate is real, so a name candidate cannot be conjured without a digest-locked shard
 *     vouching for it and the current [DatNameKey.VERSION] having produced it;
 *  2. content evidence still wins — a later hash that AGREES confirms, and one that DISAGREES raises a
 *     conflict for review instead of losing to the name that got there first.
 *
 * These are the tests that make (2) an assertion rather than an expectation.
 */
class DatNameAuthorityTest {

    private fun artifact(
        version: Int = DatNameKey.VERSION,
        stamp: String = STAMP,
        validation: AthenaValidationLevel = AthenaValidationLevel.UNIQUE_DUMP_NAME_IN_SHARD,
        complete: Boolean = true,
        unique: Boolean = true,
        rawKey: String = "xenogears|1",
    ) = AthenaArtifactEvidence(
        parser = AthenaParserKind.DAT_DUMP_NAME,
        parserVersion = version,
        catalogStamp = stamp,
        validation = validation,
        physicalRole = AthenaPhysicalRole.COMPLETE_DISC,
        rawKey = rawKey,
        complete = complete,
        unique = unique,
    )

    private fun named(
        title: String = "Xenogears (USA) (Disc 1)",
        origin: MetadataOrigin = MetadataOrigin.REDUMP,
        artifact: AthenaArtifactEvidence? = artifact(),
    ) = AthenaIdentityCandidate(
        canonicalTitle = title,
        collapseKey = title,
        origin = origin,
        evidence = AthenaIdentityEvidence.DAT_NAME_UNIQUE,
        artifact = artifact,
    )

    private fun hashed(title: String = "Xenogears (USA) (Disc 1)") = AthenaIdentityCandidate(
        canonicalTitle = title,
        collapseKey = title,
        origin = MetadataOrigin.REDUMP,
        evidence = AthenaIdentityEvidence.CONTENT_SHA1,
    )

    private val engine = AthenaIdentityEngine()

    @Test
    fun `a well formed name candidate resolves on its own`() {
        // The entire point: no payload was read, and the game still gets a durable identity.
        val decision = engine.decide(listOf(named()))
        assertTrue("a lone name candidate must identify", decision is AthenaIdentityDecision.Identified)
        assertEquals(
            "Xenogears (USA) (Disc 1)",
            (decision as AthenaIdentityDecision.Identified).candidate.canonicalTitle,
        )
    }

    @Test
    fun `a content hash that agrees confirms the name, and is the candidate that is kept`() {
        val decision = engine.decide(listOf(named(), hashed()))
        assertTrue(decision is AthenaIdentityDecision.Identified)
        assertEquals(
            "content proof outranks a name, so the retained evidence must be the SHA-1",
            AthenaIdentityEvidence.CONTENT_SHA1,
            (decision as AthenaIdentityDecision.Identified).candidate.evidence,
        )
    }

    /**
     * The safety net, and the reason AUTHORITATIVE is defensible for a name at all.
     *
     * A mislabelled file resolves to the wrong title until something reads it. When something does, the two
     * authoritative identities disagree and the engine escalates rather than letting either win silently —
     * so the wrong answer is recoverable instead of permanent.
     */
    @Test
    fun `a content hash that disagrees raises a conflict rather than overwriting or being overwritten`() {
        val decision = engine.decide(listOf(named(), hashed("Chrono Cross (USA, Canada) (Disc 1)")))
        assertTrue(decision is AthenaIdentityDecision.NeedsReview)
        assertEquals(
            AthenaIdentityDecisionReason.AUTHORITATIVE_CONFLICT,
            (decision as AthenaIdentityDecision.NeedsReview).reason,
        )
    }

    // ── The artifact gate ────────────────────────────────────────────────────────────────────────────

    /**
     * Each of these is a way the candidate could be fabricated or stale. None may identify.
     *
     * An inadmissible AUTHORITATIVE candidate is [AthenaIdentityDecisionReason.MALFORMED_AUTHORITATIVE_EVIDENCE],
     * which is a review rather than an abstention — and [LibraryIdentityEnricher.resolveByDumpName] treats
     * any non-Identified outcome as "fall through and hash", so none of these can strand a game either.
     */
    @Test
    fun `a name candidate without an intact artifact never identifies`() {
        val rejected = mapOf(
            "no artifact at all" to named(artifact = null),
            "a normalizer version this build did not produce" to
                named(artifact = artifact(version = DatNameKey.VERSION + 1)),
            "a catalog stamp that is not a shard digest" to named(artifact = artifact(stamp = "not-a-digest")),
            "a validation level claiming something weaker" to
                named(artifact = artifact(validation = AthenaValidationLevel.STRUCTURED_EMBEDDED_FIELD)),
            "a match that was not unique in its shard" to named(artifact = artifact(unique = false)),
            "a partial match" to named(artifact = artifact(complete = false)),
            "an empty matched key" to named(artifact = artifact(rawKey = "")),
            "an origin outside the curated corpora" to named(origin = MetadataOrigin.TITLE_CATALOG),
        )
        rejected.forEach { (why, candidate) ->
            assertTrue(
                "$why must not identify",
                engine.decide(listOf(candidate)) !is AthenaIdentityDecision.Identified,
            )
        }
    }

    @Test
    fun `the name class is the lowest ranked authoritative class`() {
        // Ordering is what makes the agreement case above keep the SHA-1. Asserted through the public
        // decision rather than the private comparator: every authoritative class must beat a name.
        val stronger = listOf(
            hashed(),
            AthenaIdentityCandidate(
                canonicalTitle = "Xenogears (USA) (Disc 1)",
                collapseKey = "Xenogears (USA) (Disc 1)",
                origin = MetadataOrigin.TITLE_CATALOG,
                evidence = AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY,
            ),
        )
        stronger.forEach { strong ->
            val decision = engine.decide(listOf(named(), strong))
            assertEquals(
                "${strong.evidence} must outrank a name when both agree",
                strong.evidence,
                (decision as AthenaIdentityDecision.Identified).candidate.evidence,
            )
        }
    }

    @Test
    fun `the authority policy stamp moved, so stored identities are re-evaluated`() {
        // IdentityAuthorityPolicyLatch reopens legacy projections when this changes. Adding an
        // authoritative class without moving it would leave existing libraries on the old policy.
        // Later authority changes move it again (v6, the CHD header declaration, 2026-09-27), so this pins
        // that it has moved PAST v4, not that it still reads v5.
        val version = Regex("""-v(\d+)-""").find(AthenaIdentityPolicy.STAMP)?.groupValues?.get(1)?.toInt()
        assertTrue(
            "the stamp must have moved past v4, or the latch will not fire; it is ${AthenaIdentityPolicy.STAMP}",
            version != null && version >= 5,
        )
    }

    private companion object {
        /** Any valid SHA-256; the gate checks the SHAPE of the stamp, the shard supplies the real one. */
        const val STAMP = "3b06912bbffb32ba5ee58201f6cd90f71662326b5632d3d6b3a942b8714b0f48"
    }
}
