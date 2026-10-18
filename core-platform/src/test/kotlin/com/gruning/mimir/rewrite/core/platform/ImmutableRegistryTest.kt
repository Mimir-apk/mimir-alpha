package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.CatalogEntryId
import com.gruning.mimir.rewrite.core.model.FirmwarePolicyId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RetroArchRegistrationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class ImmutableRegistryTest {
    @Test
    fun indexedAndReturnedDtoGraphsAreDeeplyImmutable() {
        val fixture = certifiedFixture()
        val catalogId = CatalogEntryId("catalog_a")
        val mutableAliases = mutableListOf(
            alias(IdentityNamespace.REWRITE_LEGACY, "old_console_a", "console_a"),
        )
        val mutableSearchTerms = mutableListOf("searchable")
        val platform = fixture.document.platforms.single().copy(
            aliases = mutableAliases,
            display = fixture.document.platforms.single().display.copy(searchTerms = mutableSearchTerms),
            folder = fixture.document.platforms.single().folder.copy(
                aliases = mutableListOf(FolderMatch("console_alias")),
            ),
            presentation = PresentationContract(catalogEntryIds = mutableListOf(catalogId)),
        )
        val catalog = CatalogEntryDefinition(
            id = catalogId,
            platformId = platform.id,
            relation = CatalogRelation.CANONICAL,
            displayName = "Console A",
            folder = FolderContract(
                canonical = FolderMatch("catalog_a"),
                aliases = mutableListOf(FolderMatch("catalog_alias")),
            ),
            searchTerms = mutableListOf("catalog-search"),
        )
        val profileA = fixture.document.contentProfiles.first { it.id == fixture.manageSliceA.contentProfileId }
            .copy(
                extensionRules = fixture.document.contentProfiles
                    .first { it.id == fixture.manageSliceA.contentProfileId }
                    .extensionRules
                    .toMutableList(),
                acceptedLocators = mutableListOf(
                    ContentLocatorKind.FILE_PATH,
                    ContentLocatorKind.DOCUMENT_URI,
                ),
            )
        val routePackageIds = fixture.document.launchRoutes.single().packageVariantIds.toMutableList()
        val route = fixture.document.launchRoutes.single().copy(packageVariantIds = routePackageIds)
        val requirement = RuntimeRequirement(
            id = "bios_a",
            kind = RequirementKind.BIOS,
            required = false,
            acceptableNames = mutableListOf("bios.bin"),
            acceptableSha256 = mutableListOf(SHA_A),
            detectionMethods = mutableListOf(DetectionMethod.APP_READABLE_PATH),
            validationAuthority = ValidationAuthority.MIMIR_PROBE,
            displayCopy = "Optional BIOS",
        )
        val variant = fixture.document.packageVariants.single().let { source ->
            source.copy(
                signerEnvelope = source.signerEnvelope.copy(
                    allowedCertificateSha256 = source.signerEnvelope.allowedCertificateSha256.toMutableList(),
                ),
                versionComponentEnvelope = source.versionComponentEnvelope.copy(
                    activityCandidates = source.versionComponentEnvelope.activityCandidates.toMutableList(),
                ),
                intent = source.intent.copy(
                    categories = mutableListOf("android.intent.category.DEFAULT"),
                    extras = mutableListOf(
                        IntentExtraContract("content", IntentValueSource.CONTENT_LOCATOR, true),
                    ),
                    flags = source.intent.flags.toMutableList(),
                ),
                acceptedLocators = source.acceptedLocators.toMutableList(),
                requirements = mutableListOf(requirement),
            )
        }
        val family = fixture.document.emulatorFamilies.single().copy(
            packageVariantIds = fixture.document.emulatorFamilies.single().packageVariantIds.toMutableList(),
        )
        val registration = RetroArchRegistrationDefinition(
            id = RetroArchRegistrationId("retro_a"),
            platformId = platform.id,
            contentProfileId = profileA.id,
            label = "Console A",
            coreRegistrations = mutableListOf(
                RetroArchCoreRegistrationDefinition("core_a", "core_a", "Core A", true),
            ),
            recommendedCore = "core_a",
            packageVariantIds = mutableListOf(variant.id),
        )
        val policy = FirmwarePolicyDefinition(
            id = FirmwarePolicyId("firmware_a"),
            platformId = platform.id,
            launchRouteId = route.id,
            packageVariantId = variant.id,
            requirements = mutableListOf(requirement),
        )
        val manageSlice = fixture.manageSliceA.copy(
            importBindingIds = fixture.manageSliceA.importBindingIds.toMutableSet(),
            displayPolicies = mutableSetOf(DeviceDisplayPolicy.SINGLE_SCREEN),
        )
        val playSlice = fixture.playSlice.copy(
            requirementPolicyIds = mutableSetOf(policy.id),
            displayPolicies = mutableSetOf(DeviceDisplayPolicy.SINGLE_SCREEN),
        )
        val document = fixture.document.copy(
            platforms = mutableListOf(platform),
            catalogEntries = mutableListOf(catalog),
            contentProfiles = mutableListOf(
                profileA,
                fixture.document.contentProfiles.first { it.id == fixture.manageSliceB.contentProfileId },
            ),
            retroArchRegistrations = mutableListOf(registration),
            emulatorFamilies = mutableListOf(family),
            launchRoutes = mutableListOf(route),
            packageVariants = mutableListOf(variant),
            firmwarePolicies = mutableListOf(policy),
            supportSlices = mutableListOf(manageSlice, fixture.manageSliceB, playSlice),
        )
        val certificateEvidence = mutableListOf(EvidenceRef("fixtures", SHA_B, EvidenceKind.FIXTURE_REPORT))
        val certificateEngines = linkedMapOf("resolver" to "1")
        val manageCertificate = manageCertificate(document, manageSlice).copy(
            fixtureEvidence = certificateEvidence,
            engineVersions = certificateEngines,
        )
        val runtimeEnvelope = fixture.runtimeEnvelope.copy(
            intentContractDigest = PlatformCanonicalDigest.intentContract(variant.intent),
            signerVersionComponentDigest = PlatformCanonicalDigest.signerVersionComponent(
                variant.signerEnvelope,
                variant.versionComponentEnvelope,
            ),
        )
        val ledger = certificationDocument(
            document,
            mutableListOf(
                manageCertificate,
                playCertificate(document, playSlice, runtimeEnvelope),
            ),
        )

        val result = ImmutablePlatformRegistry.create(document, ledger)
        val registry = result.requireRegistry()

        assertTrue(result.validationReport.issues.joinToString(), result.validationReport.isValid)
        assertUnmodifiable { (registry.definition(platform.id)!!.aliases as MutableList).clear() }
        assertUnmodifiable { (registry.definition(platform.id)!!.display.searchTerms as MutableList).clear() }
        assertUnmodifiable { (registry.definition(platform.id)!!.folder.aliases as MutableList).clear() }
        assertUnmodifiable { (registry.definition(platform.id)!!.presentation.catalogEntryIds as MutableList).clear() }
        assertUnmodifiable { (registry.catalogEntry(catalogId)!!.searchTerms as MutableList).clear() }
        assertUnmodifiable { (registry.catalogEntry(catalogId)!!.folder!!.aliases as MutableList).clear() }
        assertUnmodifiable { (registry.contentProfile(profileA.id)!!.extensionRules as MutableList).clear() }
        assertUnmodifiable { (registry.contentProfile(profileA.id)!!.acceptedLocators as MutableList).clear() }
        assertUnmodifiable {
            (registry.importBinding(fixture.manageSliceA.importBindingIds.single())!!.folderMatches as MutableList).clear()
        }
        assertUnmodifiable { (registry.retroArchRegistration(registration.id)!!.coreRegistrations as MutableList).clear() }
        assertUnmodifiable { (registry.retroArchRegistration(registration.id)!!.packageVariantIds as MutableList).clear() }
        assertUnmodifiable { (registry.emulatorFamily(family.id)!!.packageVariantIds as MutableList).clear() }
        assertUnmodifiable { (registry.route(route.id)!!.packageVariantIds as MutableList).clear() }
        assertUnmodifiable {
            (registry.packageVariant(variant.id)!!.signerEnvelope.allowedCertificateSha256 as MutableList).clear()
        }
        assertUnmodifiable {
            (registry.packageVariant(variant.id)!!.intent.extras as MutableList).clear()
        }
        assertUnmodifiable {
            (registry.packageVariant(variant.id)!!.requirements.single().detectionMethods as MutableList).clear()
        }
        assertUnmodifiable { (registry.firmwarePolicy(policy.id)!!.requirements as MutableList).clear() }
        assertUnmodifiable { (registry.supportSlice(manageSlice.id)!!.importBindingIds as MutableSet).clear() }

        val activation = registry.activation(PlatformId("console_a"))
        assertNotNull(activation)
        assertUnmodifiable { (activation!!.allowedSupportSliceIds as MutableSet).clear() }
        assertUnmodifiable {
            (registry.supportReport(PlatformId("console_a")).coveredSupportSliceIds as MutableSet).clear()
        }
        assertEquals(playSlice.id, registry.certifiedSlices(PlatformId("console_a")).single().id)

        val frozenCertificate = ledger.immutableSnapshot().certifications.first()
        assertUnmodifiable { (frozenCertificate.fixtureEvidence as MutableList).clear() }
        assertUnmodifiable { (frozenCertificate.deviceEvidence as MutableList).clear() }
        assertUnmodifiable { (frozenCertificate.engineVersions as MutableMap).clear() }

        val registryDigest = registry.canonicalDigest
        val certificationDigest = activation!!.certificationSnapshotDigest
        mutableAliases.clear()
        mutableSearchTerms.clear()
        routePackageIds.clear()
        certificateEvidence.clear()
        certificateEngines.clear()

        assertEquals(registryDigest, registry.canonicalDigest)
        assertEquals(certificationDigest, registry.activation(PlatformId("console_a"))!!.certificationSnapshotDigest)
        assertEquals(
            PlatformId("console_a"),
            registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, "old_console_a"),
        )
        assertEquals(listOf(variant.id), registry.route(route.id)!!.packageVariantIds)
    }

    private fun assertUnmodifiable(action: () -> Unit) {
        assertThrows(UnsupportedOperationException::class.java, action)
    }
}
