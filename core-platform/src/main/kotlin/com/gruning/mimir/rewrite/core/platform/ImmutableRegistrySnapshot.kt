package com.gruning.mimir.rewrite.core.platform

import java.util.Collections

internal fun PlatformRegistryDocument.immutableSnapshot(): PlatformRegistryDocument = copy(
    platforms = frozenList(platforms.map(PlatformDefinition::immutableSnapshot)),
    catalogEntries = frozenList(catalogEntries.map(CatalogEntryDefinition::immutableSnapshot)),
    contentProfiles = frozenList(contentProfiles.map(ContentProfileDefinition::immutableSnapshot)),
    importBindings = frozenList(importBindings.map(ImportBindingDefinition::immutableSnapshot)),
    retroArchRegistrations = frozenList(
        retroArchRegistrations.map(RetroArchRegistrationDefinition::immutableSnapshot),
    ),
    emulatorFamilies = frozenList(emulatorFamilies.map(EmulatorFamilyDefinition::immutableSnapshot)),
    launchRoutes = frozenList(launchRoutes.map(RuntimeRouteDefinition::immutableSnapshot)),
    packageVariants = frozenList(packageVariants.map(PackageVariantDefinition::immutableSnapshot)),
    firmwarePolicies = frozenList(firmwarePolicies.map(FirmwarePolicyDefinition::immutableSnapshot)),
    visualAssets = frozenList(visualAssets),
    supportSlices = frozenList(supportSlices.map(SupportSliceDefinition::immutableSnapshot)),
)

internal fun PlatformCertificationDocument.immutableSnapshot(): PlatformCertificationDocument = copy(
    certifications = frozenList(certifications.map(SupportCertification::immutableSnapshot)),
    revokedRuntimeEnvelopeDigests = frozenSet(revokedRuntimeEnvelopeDigests),
)

internal fun ProjectionDigestPolicy.immutableSnapshot(): ProjectionDigestPolicy = copy(
    requiredProjectionIds = frozenSet(requiredProjectionIds),
    manifests = frozenList(manifests.map(ProjectionDigestManifest::immutableSnapshot)),
)

private fun PlatformDefinition.immutableSnapshot(): PlatformDefinition = copy(
    aliases = frozenList(aliases),
    display = display.copy(searchTerms = frozenList(display.searchTerms)),
    folder = folder.immutableSnapshot(),
    contentProfileIds = frozenList(contentProfileIds),
    identity = frozenList(identity),
    presentation = presentation.copy(
        catalogEntryIds = frozenList(presentation.catalogEntryIds),
        visualAssetKeys = frozenList(presentation.visualAssetKeys),
    ),
    launchRouteIds = frozenList(launchRouteIds),
)

private fun FolderContract.immutableSnapshot(): FolderContract = copy(
    aliases = frozenList(aliases),
)

private fun CatalogEntryDefinition.immutableSnapshot(): CatalogEntryDefinition = copy(
    folder = folder?.immutableSnapshot(),
    searchTerms = frozenList(searchTerms),
    visualAssetKeys = frozenList(visualAssetKeys),
)

private fun ContentProfileDefinition.immutableSnapshot(): ContentProfileDefinition = copy(
    extensionRules = frozenList(extensionRules),
    acceptedLocators = frozenList(acceptedLocators),
)

private fun ImportBindingDefinition.immutableSnapshot(): ImportBindingDefinition = copy(
    folderMatches = frozenList(folderMatches),
)

private fun RetroArchRegistrationDefinition.immutableSnapshot(): RetroArchRegistrationDefinition = copy(
    coreRegistrations = frozenList(coreRegistrations),
    packageVariantIds = frozenList(packageVariantIds),
)

private fun EmulatorFamilyDefinition.immutableSnapshot(): EmulatorFamilyDefinition = copy(
    packageVariantIds = frozenList(packageVariantIds),
)

private fun RuntimeRouteDefinition.immutableSnapshot(): RuntimeRouteDefinition = copy(
    packageVariantIds = frozenList(packageVariantIds),
    retroArchRegistrationIds = frozenList(retroArchRegistrationIds),
)

private fun PackageVariantDefinition.immutableSnapshot(): PackageVariantDefinition = copy(
    signerEnvelope = signerEnvelope.copy(
        allowedCertificateSha256 = frozenList(signerEnvelope.allowedCertificateSha256),
    ),
    versionComponentEnvelope = versionComponentEnvelope.copy(
        activityCandidates = frozenList(versionComponentEnvelope.activityCandidates),
    ),
    intent = intent.copy(
        categories = frozenList(intent.categories),
        extras = frozenList(intent.extras),
        flags = frozenList(intent.flags),
    ),
    acceptedLocators = frozenList(acceptedLocators),
    requirements = frozenList(requirements.map(RuntimeRequirement::immutableSnapshot)),
)

private fun RuntimeRequirement.immutableSnapshot(): RuntimeRequirement = copy(
    acceptableNames = frozenList(acceptableNames),
    acceptableSha256 = frozenList(acceptableSha256),
    detectionMethods = frozenList(detectionMethods),
)

private fun FirmwarePolicyDefinition.immutableSnapshot(): FirmwarePolicyDefinition = copy(
    requirements = frozenList(requirements.map(RuntimeRequirement::immutableSnapshot)),
)

private fun SupportSliceDefinition.immutableSnapshot(): SupportSliceDefinition = copy(
    importBindingIds = frozenSet(importBindingIds),
    requirementPolicyIds = frozenSet(requirementPolicyIds),
    displayPolicies = frozenSet(displayPolicies),
)

private fun SupportCertification.immutableSnapshot(): SupportCertification = copy(
    fixtureEvidence = frozenList(fixtureEvidence),
    deviceEvidence = frozenList(deviceEvidence),
    engineVersions = frozenMap(engineVersions),
)

private fun ProjectionDigestManifest.immutableSnapshot(): ProjectionDigestManifest = copy(
    supportSliceDigests = frozenMap(supportSliceDigests),
)

private fun <T> frozenList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> frozenSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
