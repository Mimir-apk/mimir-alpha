package com.gruning.mimir.rewrite.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlatformSourceCapabilityEvaluatorTest {
    @Test
    fun validCrossAuthorityRowsAreOrderIndependentAndMonotone() {
        val platform = testPlatform(
            id = "console_a",
            aliases = listOf(alias(IdentityNamespace.REWRITE_LEGACY, "old_a", "console_a")),
        )
        val registry = ImmutablePlatformRegistry.create(testDocument(listOf(platform))).requireRegistry()
        val rows = listOf(
            row(PlatformSourceAuthority.REGISTRY, "catalog", "console_a", SupportClaimLevel.CATALOG_ONLY),
            row(PlatformSourceAuthority.SCANNER, "scanner", "old_a", SupportClaimLevel.DISCOVERABLE),
            row(PlatformSourceAuthority.RETRO_ACHIEVEMENTS, "ra", "provider-a", SupportClaimLevel.RESOLVABLE),
            row(PlatformSourceAuthority.LAUNCH, "launch", "old_a", SupportClaimLevel.LAUNCHABLE),
        )
        val evaluator = PlatformSourceCapabilityEvaluator(registry)

        val ordered = evaluator.evaluate(rows)
        val shuffled = evaluator.evaluate(rows.shuffled(Random(1234)))

        assertTrue(ordered.issues.toString(), ordered.isValid)
        assertEquals(SupportClaimLevel.LAUNCHABLE, ordered.stages.single().highestClaimLevel)
        assertTrue(ordered.stages.single().highestClaimLevel.implies(SupportClaimLevel.RESOLVABLE))
        assertTrue(ordered.stages.single().highestClaimLevel.implies(SupportClaimLevel.DISCOVERABLE))
        assertEquals(ordered.canonicalText(), shuffled.canonicalText())
        assertEquals(ordered.sha256(), shuffled.sha256())
    }

    @Test
    fun invalidRowsExposeOrphanAmbiguityConflictAndOverclaimWithoutThrowing() {
        val first = testPlatform(
            id = "first",
            aliases = listOf(alias(IdentityNamespace.REWRITE_LEGACY, "shared", "first")),
        )
        val second = testPlatform(
            id = "second",
            aliases = listOf(alias(IdentityNamespace.EXTERNAL_FRONTEND, "shared", "second")),
        )
        val registry = ImmutablePlatformRegistry.create(testDocument(listOf(first, second))).requireRegistry()
        val report = PlatformSourceCapabilityEvaluator(registry).evaluate(
            listOf(
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.SCANNER,
                    subjectId = "ambiguous",
                    rawPlatformId = "shared",
                    declaredCanonicalPlatformId = "first",
                    claimLevel = SupportClaimLevel.DISCOVERABLE,
                ),
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.LAUNCH,
                    subjectId = "orphan",
                    rawPlatformId = "missing",
                    declaredCanonicalPlatformId = "first",
                    claimLevel = SupportClaimLevel.LAUNCHABLE,
                ),
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.SCANNER,
                    subjectId = "overclaim",
                    rawPlatformId = "first",
                    declaredCanonicalPlatformId = "first",
                    claimLevel = SupportClaimLevel.LAUNCHABLE,
                ),
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.REGISTRY,
                    subjectId = "duplicate",
                    rawPlatformId = "first",
                    declaredCanonicalPlatformId = "first",
                    claimLevel = SupportClaimLevel.CATALOG_ONLY,
                ),
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.REGISTRY,
                    subjectId = "duplicate",
                    rawPlatformId = "second",
                    declaredCanonicalPlatformId = "second",
                    claimLevel = SupportClaimLevel.CATALOG_ONLY,
                ),
            ),
        )

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == PlatformSourceContractIssueCode.AMBIGUOUS_RAW_ID })
        assertTrue(report.issues.any { it.code == PlatformSourceContractIssueCode.ORPHAN_RAW_ID })
        assertTrue(report.issues.any { it.code == PlatformSourceContractIssueCode.AUTHORITY_OVERCLAIM })
        assertTrue(report.issues.any { it.code == PlatformSourceContractIssueCode.DUPLICATE_SUBJECT_CONFLICT })
    }

    @Test
    fun certificationIsAcceptedOnlyThroughTheValidatedRegistryAndWithLaunchEvidence() {
        val fixture = certifiedFixture()
        val certification = certificationDocument(
            fixture.document,
            listOf(playCertificate(fixture.document, fixture.playSlice, fixture.runtimeEnvelope)),
        )
        val registry = ImmutablePlatformRegistry.create(
            document = fixture.document,
            certificationDocument = certification,
        ).requireRegistry()
        val rows = listOf(
            row(PlatformSourceAuthority.REGISTRY, "catalog", "console_a", SupportClaimLevel.CATALOG_ONLY),
            row(PlatformSourceAuthority.SCANNER, "scanner", "console_a", SupportClaimLevel.DISCOVERABLE),
            row(PlatformSourceAuthority.LAUNCH, "launch", "console_a", SupportClaimLevel.LAUNCHABLE),
            row(PlatformSourceAuthority.CERTIFICATION, "certificate", "console_a", SupportClaimLevel.CERTIFIED),
        )

        val valid = PlatformSourceCapabilityEvaluator(registry).evaluate(rows)
        val unvalidatedRegistry = ImmutablePlatformRegistry.create(fixture.document).requireRegistry()
        val invalid = PlatformSourceCapabilityEvaluator(unvalidatedRegistry).evaluate(rows)

        assertTrue(valid.issues.toString(), valid.isValid)
        assertEquals(SupportClaimLevel.CERTIFIED, valid.stages.single().highestClaimLevel)
        assertTrue(
            invalid.issues.any {
                it.code == PlatformSourceContractIssueCode.CERTIFIED_WITHOUT_VALIDATED_LEDGER
            },
        )
        assertEquals(SupportClaimLevel.LAUNCHABLE, invalid.stages.single().highestClaimLevel)
        assertFalse(
            PlatformSourceAuthority.CERTIFICATION in invalid.stages.single().authorities,
        )

        val missingLaunch = PlatformSourceCapabilityEvaluator(registry).evaluate(
            rows.filterNot { it.authority == PlatformSourceAuthority.LAUNCH },
        )
        assertTrue(
            missingLaunch.issues.any {
                it.code == PlatformSourceContractIssueCode.CERTIFIED_WITHOUT_LAUNCHABLE
            },
        )
        assertEquals(SupportClaimLevel.DISCOVERABLE, missingLaunch.stages.single().highestClaimLevel)
        assertFalse(
            PlatformSourceAuthority.CERTIFICATION in missingLaunch.stages.single().authorities,
        )
    }

    @Test
    fun launchableRowsWithoutScannerDiscoveryFailClosed() {
        val registry = ImmutablePlatformRegistry.create(
            testDocument(listOf(testPlatform("console_a"))),
        ).requireRegistry()

        val report = PlatformSourceCapabilityEvaluator(registry).evaluate(
            listOf(
                row(PlatformSourceAuthority.REGISTRY, "catalog", "console_a", SupportClaimLevel.CATALOG_ONLY),
                row(PlatformSourceAuthority.LAUNCH, "launch", "console_a", SupportClaimLevel.LAUNCHABLE),
            ),
        )

        assertTrue(
            report.issues.any { it.code == PlatformSourceContractIssueCode.LAUNCHABLE_WITHOUT_DISCOVERY },
        )
    }

    @Test
    fun providerLocalIdsAreAllowedButKnownRegistryIdsCannotContradictTheirTarget() {
        val first = testPlatform(
            id = "first",
            aliases = listOf(alias(IdentityNamespace.REWRITE_LEGACY, "known_alias", "first")),
        )
        val second = testPlatform(id = "second")
        val registry = ImmutablePlatformRegistry.create(
            testDocument(listOf(first, second)),
        ).requireRegistry()
        val evaluator = PlatformSourceCapabilityEvaluator(registry)

        val local = evaluator.evaluate(
            listOf(
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.RETRO_ACHIEVEMENTS,
                    subjectId = "provider-local",
                    rawPlatformId = "provider_only_id",
                    declaredCanonicalPlatformId = "second",
                    claimLevel = SupportClaimLevel.RESOLVABLE,
                ),
            ),
        )
        val contradiction = evaluator.evaluate(
            listOf(
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.SCREEN_SCRAPER,
                    subjectId = "known-contradiction",
                    rawPlatformId = "known_alias",
                    declaredCanonicalPlatformId = "second",
                    claimLevel = SupportClaimLevel.RESOLVABLE,
                ),
            ),
        )

        assertTrue(local.issues.toString(), local.isValid)
        assertEquals("second", local.stages.single().canonicalPlatformId)
        assertTrue(
            contradiction.issues.any {
                it.code == PlatformSourceContractIssueCode.CANONICAL_MISMATCH
            },
        )
    }

    @Test
    fun certificationAuthorityCannotInjectAWeakerStageOutsideTheCertificationGate() {
        val registry = ImmutablePlatformRegistry.create(
            testDocument(listOf(testPlatform("console_a"))),
        ).requireRegistry()

        val report = PlatformSourceCapabilityEvaluator(registry).evaluate(
            listOf(
                row(
                    PlatformSourceAuthority.CERTIFICATION,
                    "forged-launchable-certificate",
                    "console_a",
                    SupportClaimLevel.LAUNCHABLE,
                ),
            ),
        )

        assertTrue(
            report.issues.any { it.code == PlatformSourceContractIssueCode.AUTHORITY_OVERCLAIM },
        )
        assertTrue(report.stages.isEmpty())
        assertFalse(
            report.stages.any {
                PlatformSourceAuthority.CERTIFICATION in it.authorities
            },
        )
    }

    private fun row(
        authority: PlatformSourceAuthority,
        subject: String,
        raw: String,
        claim: SupportClaimLevel,
    ): PlatformSourceCapabilityRow = PlatformSourceCapabilityRow(
        authority = authority,
        subjectId = subject,
        rawPlatformId = raw,
        declaredCanonicalPlatformId = "console_a",
        claimLevel = claim,
    )
}
