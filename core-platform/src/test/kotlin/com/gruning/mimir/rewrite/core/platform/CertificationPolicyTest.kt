package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.ContentProfileId
import com.gruning.mimir.rewrite.core.model.ImportBindingId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.SupportSliceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificationPolicyTest {
    @Test
    fun activationAuthorizesOnlyTheExactCertifiedManageSlice() {
        val fixture = certifiedFixture()
        val certification = manageCertificate(fixture.document, fixture.manageSliceA)
        val ledger = certificationDocument(fixture.document, listOf(certification))

        val registry = ImmutablePlatformRegistry.create(fixture.document, ledger).requireRegistry()
        val activation = registry.activation(PlatformId("console_a"))

        assertNotNull(activation)
        assertEquals(setOf(SupportSliceId("manage_a")), activation!!.allowedSupportSliceIds)
        assertEquals(setOf(ImportBindingId("binding_a")), activation.allowedImportBindingIds)
        assertEquals(setOf(ContentProfileId("profile_a")), activation.allowedContentProfileIds)
        assertTrue(SupportSliceId("manage_b") !in activation.allowedSupportSliceIds)
        assertTrue(ImportBindingId("binding_b") !in activation.allowedImportBindingIds)
    }

    @Test
    fun exactRuntimeEnvelopeMintsOnlyItsPlaySlice() {
        val fixture = certifiedFixture()
        val certification = playCertificate(
            fixture.document,
            fixture.playSlice,
            fixture.runtimeEnvelope,
        )
        val ledger = certificationDocument(fixture.document, listOf(certification))

        val result = ImmutablePlatformRegistry.create(fixture.document, ledger)
        val certified = result.requireRegistry().certifiedSlices(PlatformId("console_a"))

        assertTrue(result.validationReport.isValid)
        assertEquals(1, certified.size)
        assertEquals(fixture.playSlice.id, certified.single().id)
        assertEquals(fixture.runtimeEnvelope, certified.single().runtimeEnvelopeKey)
    }

    @Test
    fun siblingOrAlteredRuntimeEnvelopeCannotBorrowCertification() {
        val fixture = certifiedFixture()
        val alteredEnvelope = fixture.runtimeEnvelope.copy(intentContractDigest = SHA_A)
        val certification = playCertificate(fixture.document, fixture.playSlice, alteredEnvelope)
        val ledger = certificationDocument(fixture.document, listOf(certification))

        val result = ImmutablePlatformRegistry.create(fixture.document, ledger)
        val registry = result.requireRegistry()

        assertTrue(ValidationCode.INVALID_RUNTIME_ENVELOPE in result.validationReport)
        assertTrue(registry.certifiedSlices(PlatformId("console_a")).isEmpty())
    }

    @Test
    fun mismatchedProjectionDigestFailsClosedWithoutHidingRegistryFacts() {
        val fixture = certifiedFixture()
        val certification = manageCertificate(fixture.document, fixture.manageSliceA)
        val ledger = certificationDocument(fixture.document, listOf(certification))
        val projectionPolicy = ProjectionDigestPolicy(
            requiredProjectionIds = setOf("resolver"),
            manifests = listOf(
                ProjectionDigestManifest(
                    projectionId = "resolver",
                    registryRevision = fixture.document.registryRevision,
                    registryCanonicalDigest = SHA_A,
                    supportSliceDigests = mapOf(
                        fixture.manageSliceA.id to PlatformCanonicalDigest.supportSlice(
                            fixture.document,
                            fixture.manageSliceA.id,
                        )!!,
                    ),
                ),
            ),
        )

        val result = ImmutablePlatformRegistry.create(fixture.document, ledger, projectionPolicy)
        val registry = result.requireRegistry()

        assertNotNull(registry.definition(PlatformId("console_a")))
        assertNull(registry.activation(PlatformId("console_a")))
        assertTrue(registry.certifiedSlices(PlatformId("console_a")).isEmpty())
        assertTrue(ValidationCode.PROJECTION_DIGEST_MISMATCH in result.validationReport)
    }

    @Test
    fun mismatchedPerSliceProjectionDigestAlsoFailsClosed() {
        val fixture = certifiedFixture()
        val certification = manageCertificate(fixture.document, fixture.manageSliceA)
        val ledger = certificationDocument(fixture.document, listOf(certification))
        val projectionPolicy = ProjectionDigestPolicy(
            requiredProjectionIds = setOf("resolver"),
            manifests = listOf(
                ProjectionDigestManifest(
                    projectionId = "resolver",
                    registryRevision = fixture.document.registryRevision,
                    registryCanonicalDigest = PlatformCanonicalDigest.registry(fixture.document),
                    supportSliceDigests = mapOf(fixture.manageSliceA.id to SHA_A),
                ),
            ),
        )

        val result = ImmutablePlatformRegistry.create(fixture.document, ledger, projectionPolicy)

        assertNull(result.requireRegistry().activation(PlatformId("console_a")))
        assertTrue(ValidationCode.PROJECTION_SLICE_DIGEST_MISMATCH in result.validationReport)
    }

    @Test
    fun requiredProjectionWithEmptySliceCoverageFailsClosed() {
        val fixture = certifiedFixture()
        val ledger = certificationDocument(
            fixture.document,
            listOf(manageCertificate(fixture.document, fixture.manageSliceA)),
        )
        val projectionPolicy = ProjectionDigestPolicy(
            requiredProjectionIds = setOf("resolver"),
            manifests = listOf(
                ProjectionDigestManifest(
                    projectionId = "resolver",
                    registryRevision = fixture.document.registryRevision,
                    registryCanonicalDigest = PlatformCanonicalDigest.registry(fixture.document),
                    supportSliceDigests = emptyMap(),
                ),
            ),
        )

        val result = ImmutablePlatformRegistry.create(fixture.document, ledger, projectionPolicy)
        val registry = result.requireRegistry()

        assertNull(registry.activation(PlatformId("console_a")))
        assertTrue(registry.certifiedSlices(PlatformId("console_a")).isEmpty())
        assertTrue(ValidationCode.PROJECTION_MISSING in result.validationReport)
    }

    @Test
    fun requiredProjectionMissingOneCertifiedSliceFailsClosed() {
        val fixture = certifiedFixture()
        val ledger = certificationDocument(
            fixture.document,
            listOf(
                manageCertificate(fixture.document, fixture.manageSliceA),
                playCertificate(fixture.document, fixture.playSlice, fixture.runtimeEnvelope),
            ),
        )
        val projectionPolicy = ProjectionDigestPolicy(
            requiredProjectionIds = setOf("resolver"),
            manifests = listOf(
                ProjectionDigestManifest(
                    projectionId = "resolver",
                    registryRevision = fixture.document.registryRevision,
                    registryCanonicalDigest = PlatformCanonicalDigest.registry(fixture.document),
                    supportSliceDigests = mapOf(
                        fixture.manageSliceA.id to PlatformCanonicalDigest.supportSlice(
                            fixture.document,
                            fixture.manageSliceA.id,
                        )!!,
                    ),
                ),
            ),
        )

        val result = ImmutablePlatformRegistry.create(fixture.document, ledger, projectionPolicy)
        val registry = result.requireRegistry()

        assertNull(registry.activation(PlatformId("console_a")))
        assertTrue(registry.certifiedSlices(PlatformId("console_a")).isEmpty())
        assertTrue(
            result.validationReport.issues.any { issue ->
                issue.code == ValidationCode.PROJECTION_MISSING &&
                    issue.path.endsWith("supportSliceDigests.${fixture.playSlice.id}")
            },
        )
    }

    @Test
    fun emptyCertificateLedgerProducesCatalogTruthAndNoAuthority() {
        val fixture = certifiedFixture()
        val ledger = certificationDocument(fixture.document, emptyList())

        val result = ImmutablePlatformRegistry.create(fixture.document, ledger)
        val registry = result.requireRegistry()
        val report = registry.supportReport(PlatformId("console_a"))

        assertTrue(result.validationReport.isValid)
        assertNull(registry.activation(PlatformId("console_a")))
        assertTrue(registry.certifiedSlices(PlatformId("console_a")).isEmpty())
        assertEquals(PublicSupportClass.CATALOG_ONLY, report.publicSupportClass)
        assertEquals(SupportClaimLevel.LAUNCHABLE, report.highestClaimLevel)
        assertTrue(report.coveredSupportSliceIds.isEmpty())
    }
}
