package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.ContentProfileId
import com.gruning.mimir.rewrite.core.model.DeviceEnvelopeId
import com.gruning.mimir.rewrite.core.model.EmulatorFamilyId
import com.gruning.mimir.rewrite.core.model.IdMapVersion
import com.gruning.mimir.rewrite.core.model.ImportBindingId
import com.gruning.mimir.rewrite.core.model.LaunchRouteId
import com.gruning.mimir.rewrite.core.model.PackageVariantId
import com.gruning.mimir.rewrite.core.model.PlatformDefinitionVersion
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RegistryRevision
import com.gruning.mimir.rewrite.core.model.SupportSliceId
import java.time.Instant

internal const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
internal const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

internal val TEST_SCHEMA = PlatformDefinitionVersion("mimirPlatformRegistry.v2")
internal val TEST_REVISION = RegistryRevision("test-revision-1")
internal val TEST_ID_MAP = IdMapVersion("test-id-map-1")

internal fun testDocument(
    platforms: List<PlatformDefinition>,
    contentProfiles: List<ContentProfileDefinition> = emptyList(),
    importBindings: List<ImportBindingDefinition> = emptyList(),
    retroArchRegistrations: List<RetroArchRegistrationDefinition> = emptyList(),
    emulatorFamilies: List<EmulatorFamilyDefinition> = emptyList(),
    launchRoutes: List<RuntimeRouteDefinition> = emptyList(),
    packageVariants: List<PackageVariantDefinition> = emptyList(),
    supportSlices: List<SupportSliceDefinition> = emptyList(),
    visualAssets: List<VisualAssetDefinition> = emptyList(),
): PlatformRegistryDocument = PlatformRegistryDocument(
    schemaVersion = TEST_SCHEMA,
    registryRevision = TEST_REVISION,
    idMapVersion = TEST_ID_MAP,
    compatibilityFloor = PlatformDefinitionVersion("pk00-test-floor"),
    platforms = platforms,
    contentProfiles = contentProfiles,
    importBindings = importBindings,
    retroArchRegistrations = retroArchRegistrations,
    emulatorFamilies = emulatorFamilies,
    launchRoutes = launchRoutes,
    packageVariants = packageVariants,
    supportSlices = supportSlices,
    visualAssets = visualAssets,
)

internal fun testPlatform(
    id: String,
    name: String = id,
    manufacturer: String? = null,
    aliases: List<ScopedAlias> = emptyList(),
    folder: String = id,
    contentProfileIds: List<ContentProfileId> = emptyList(),
    launchRouteIds: List<LaunchRouteId> = emptyList(),
): PlatformDefinition = PlatformDefinition(
    id = PlatformId(id),
    aliases = aliases,
    display = PlatformDisplayDefinition(name, manufacturer = manufacturer),
    folder = FolderContract(FolderMatch(folder)),
    contentProfileIds = contentProfileIds,
    launchRouteIds = launchRouteIds,
)

internal fun alias(
    namespace: IdentityNamespace,
    value: String,
    target: String,
): ScopedAlias = ScopedAlias(namespace, value, TEST_ID_MAP, PlatformId(target))

internal fun testProfile(
    id: String,
    platformId: String,
    extensions: List<String>,
    acceptedLocators: List<ContentLocatorKind> = listOf(ContentLocatorKind.FILE_PATH),
    parserVersion: String = "parser-1",
): ContentProfileDefinition = ContentProfileDefinition(
    id = ContentProfileId(id),
    platformId = PlatformId(platformId),
    extensionRules = extensions.map { ContentExtensionRule(it, ContentRole.BASE_GAME, true) },
    groupingKind = GroupingKind.SINGLE_FILE,
    acceptedLocators = acceptedLocators,
    parserVersion = parserVersion,
)

internal fun testBinding(
    id: String,
    platformId: String,
    profileId: String,
    folder: String = platformId,
): ImportBindingDefinition = ImportBindingDefinition(
    id = ImportBindingId(id),
    platformId = PlatformId(platformId),
    contentProfileId = ContentProfileId(profileId),
    folderMatches = listOf(FolderMatch(folder)),
)

internal data class CertifiedFixture(
    val document: PlatformRegistryDocument,
    val manageSliceA: SupportSliceDefinition,
    val manageSliceB: SupportSliceDefinition,
    val playSlice: SupportSliceDefinition,
    val runtimeEnvelope: RuntimeEnvelopeKey,
)

internal fun certifiedFixture(): CertifiedFixture {
    val platformId = PlatformId("console_a")
    val profileA = testProfile(
        id = "profile_a",
        platformId = platformId.value,
        extensions = listOf("rom"),
        acceptedLocators = listOf(ContentLocatorKind.FILE_PATH, ContentLocatorKind.DOCUMENT_URI),
    )
    val profileB = testProfile("profile_b", platformId.value, listOf("alt"))
    val bindingA = testBinding("binding_a", platformId.value, profileA.id.value, "console_a")
    val bindingB = testBinding("binding_b", platformId.value, profileB.id.value, "console_a_alt")
    val variantId = PackageVariantId("variant_a")
    val familyId = EmulatorFamilyId("family_a")
    val routeId = LaunchRouteId("route_a")
    val intent = IntentContract(
        action = "android.intent.action.VIEW",
        componentPolicy = ComponentPolicy.EXPLICIT_ONLY,
        categories = emptyList(),
        dataMode = IntentDataMode.LOCATOR_AS_DATA,
        mimeType = "application/octet-stream",
        extras = emptyList(),
        flags = listOf("FLAG_GRANT_READ_URI_PERMISSION"),
        uriGrantPolicy = UriGrantPolicy.READ,
    )
    val variant = PackageVariantDefinition(
        id = variantId,
        packageName = "example.emulator",
        signerEnvelope = SignerEnvelope(SignerPolicy.EXACT_ANY_ALLOWED, listOf(SHA_A)),
        versionComponentEnvelope = VersionComponentEnvelope(
            minimumVersionCode = 1,
            maximumVersionCode = 10,
            componentPolicy = ComponentPolicy.EXPLICIT_ONLY,
            activityCandidates = listOf("example.emulator.MainActivity"),
        ),
        hostAbi = HostAbi.ARM64_V8A,
        intent = intent,
        acceptedLocators = listOf(ContentLocatorKind.DOCUMENT_URI),
    )
    val family = EmulatorFamilyDefinition(
        id = familyId,
        displayName = "Example Emulator",
        kind = EmulatorFamilyKind.STANDALONE,
        packageVariantIds = listOf(variantId),
    )
    val route = RuntimeRouteDefinition(
        id = routeId,
        platformId = platformId,
        kind = RuntimeRouteKind.STANDALONE,
        emulatorFamilyId = familyId,
        packageVariantIds = listOf(variantId),
        precedence = 0,
        autoSelectable = true,
    )
    val manageA = SupportSliceDefinition(
        id = SupportSliceId("manage_a"),
        kind = SupportSliceKind.MANAGE,
        platformId = platformId,
        contentProfileId = profileA.id,
        importBindingIds = setOf(bindingA.id),
        claimLevel = SupportClaimLevel.RESOLVABLE,
    )
    val manageB = SupportSliceDefinition(
        id = SupportSliceId("manage_b"),
        kind = SupportSliceKind.MANAGE,
        platformId = platformId,
        contentProfileId = profileB.id,
        importBindingIds = setOf(bindingB.id),
        claimLevel = SupportClaimLevel.RESOLVABLE,
    )
    val play = SupportSliceDefinition(
        id = SupportSliceId("play_a"),
        kind = SupportSliceKind.PLAY,
        platformId = platformId,
        contentProfileId = profileA.id,
        launchRouteId = routeId,
        locatorKind = ContentLocatorKind.DOCUMENT_URI,
        claimLevel = SupportClaimLevel.LAUNCHABLE,
    )
    val platform = testPlatform(
        id = platformId.value,
        name = "Console A",
        contentProfileIds = listOf(profileA.id, profileB.id),
        launchRouteIds = listOf(routeId),
    )
    val document = testDocument(
        platforms = listOf(platform),
        contentProfiles = listOf(profileA, profileB),
        importBindings = listOf(bindingA, bindingB),
        emulatorFamilies = listOf(family),
        launchRoutes = listOf(route),
        packageVariants = listOf(variant),
        supportSlices = listOf(manageA, manageB, play),
    )
    val envelope = RuntimeEnvelopeKey(
        packageVariantId = variantId,
        intentContractDigest = PlatformCanonicalDigest.intentContract(intent),
        signerVersionComponentDigest = PlatformCanonicalDigest.signerVersionComponent(
            variant.signerEnvelope,
            variant.versionComponentEnvelope,
        ),
        hostAbi = variant.hostAbi,
        deviceEnvelopeId = DeviceEnvelopeId("thor-api35-dual"),
    )
    return CertifiedFixture(document, manageA, manageB, play, envelope)
}

internal fun certificationDocument(
    registry: PlatformRegistryDocument,
    certifications: List<SupportCertification>,
): PlatformCertificationDocument = PlatformCertificationDocument(
    schemaVersion = "mimirPlatformCertification.v1",
    registryRevision = registry.registryRevision,
    idMapVersion = registry.idMapVersion,
    registryCanonicalDigest = PlatformCanonicalDigest.registry(registry),
    certifications = certifications,
)

internal fun manageCertificate(
    registry: PlatformRegistryDocument,
    slice: SupportSliceDefinition,
    certificateId: String = "manage-cert",
): SupportCertification = SupportCertification(
    certificateId = certificateId,
    supportSliceId = slice.id,
    publicClass = PublicSupportClass.LIBRARY_ONLY,
    fixtureEvidence = listOf(EvidenceRef("fixtures", SHA_B, EvidenceKind.FIXTURE_REPORT)),
    deviceEvidence = listOf(EvidenceRef("device", SHA_C, EvidenceKind.DEVICE_REPORT)),
    sliceDigest = PlatformCanonicalDigest.supportSlice(registry, slice.id)!!,
    contractVersion = CertificationContractVersion.MANAGE_PLATFORM_V1,
    engineVersions = mapOf("resolver" to "1"),
    issuedAt = Instant.parse("2026-07-13T00:00:00Z"),
    reviewAfter = Instant.parse("2027-07-13T00:00:00Z"),
)

internal fun playCertificate(
    registry: PlatformRegistryDocument,
    slice: SupportSliceDefinition,
    envelope: RuntimeEnvelopeKey,
): SupportCertification = SupportCertification(
    certificateId = "play-cert",
    supportSliceId = slice.id,
    publicClass = PublicSupportClass.DIRECT_PLAY,
    runtimeEnvelopeKey = envelope,
    fixtureEvidence = listOf(EvidenceRef("fixtures", SHA_B, EvidenceKind.FIXTURE_REPORT)),
    deviceEvidence = listOf(EvidenceRef("device", SHA_C, EvidenceKind.DEVICE_REPORT)),
    sliceDigest = PlatformCanonicalDigest.supportSlice(registry, slice.id)!!,
    contractVersion = CertificationContractVersion.PLAY_PLATFORM_V1,
    engineVersions = mapOf("dispatcher" to "1"),
    issuedAt = Instant.parse("2026-07-13T00:00:00Z"),
    reviewAfter = Instant.parse("2027-07-13T00:00:00Z"),
)
