package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.CatalogEntryId
import com.gruning.mimir.rewrite.core.model.ContentProfileId
import com.gruning.mimir.rewrite.core.model.EmulatorFamilyId
import com.gruning.mimir.rewrite.core.model.FirmwarePolicyId
import com.gruning.mimir.rewrite.core.model.IdMapVersion
import com.gruning.mimir.rewrite.core.model.ImportBindingId
import com.gruning.mimir.rewrite.core.model.LaunchRouteId
import com.gruning.mimir.rewrite.core.model.PackageVariantId
import com.gruning.mimir.rewrite.core.model.PlatformDefinitionVersion
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RegistryRevision
import com.gruning.mimir.rewrite.core.model.RetroArchRegistrationId
import com.gruning.mimir.rewrite.core.model.SupportSliceId
import com.gruning.mimir.rewrite.core.model.VisualAssetKey
import kotlinx.serialization.Serializable

/** The canonical linked registry. Every collection owns a distinct identifier namespace. */
@Serializable
data class PlatformRegistryDocument(
    val schemaVersion: PlatformDefinitionVersion,
    val registryRevision: RegistryRevision,
    val idMapVersion: IdMapVersion,
    val compatibilityFloor: PlatformDefinitionVersion,
    val declaredCanonicalDigest: String? = null,
    val platforms: List<PlatformDefinition> = emptyList(),
    val catalogEntries: List<CatalogEntryDefinition> = emptyList(),
    val contentProfiles: List<ContentProfileDefinition> = emptyList(),
    val importBindings: List<ImportBindingDefinition> = emptyList(),
    val retroArchRegistrations: List<RetroArchRegistrationDefinition> = emptyList(),
    val emulatorFamilies: List<EmulatorFamilyDefinition> = emptyList(),
    val launchRoutes: List<RuntimeRouteDefinition> = emptyList(),
    val packageVariants: List<PackageVariantDefinition> = emptyList(),
    val firmwarePolicies: List<FirmwarePolicyDefinition> = emptyList(),
    val visualAssets: List<VisualAssetDefinition> = emptyList(),
    val supportSlices: List<SupportSliceDefinition> = emptyList(),
)

/** Identity aliases are one-to-one only within this explicit namespace and ID-map version. */
@Serializable
enum class IdentityNamespace {
    CANONICAL_PLATFORM,
    SHIPPING_RUNTIME,
    SHIPPING_CATALOG,
    REWRITE_LEGACY,
    MIGRATION_BUNDLE,
    RETROARCH_REGISTRATION,
    EXTERNAL_FRONTEND,
}

@Serializable
data class ScopedAlias(
    val namespace: IdentityNamespace,
    val normalizedValue: String,
    val idMapVersion: IdMapVersion,
    val targetId: PlatformId,
)

@Serializable
data class PlatformDefinition(
    val id: PlatformId,
    val aliases: List<ScopedAlias>,
    val display: PlatformDisplayDefinition,
    val folder: FolderContract,
    val definitionStatus: PlatformDefinitionStatus = PlatformDefinitionStatus.COMPLETE,
    val contentProfileIds: List<ContentProfileId> = emptyList(),
    val identity: List<IdentityStrategy> = emptyList(),
    val presentation: PresentationContract = PresentationContract(),
    val launchRouteIds: List<LaunchRouteId> = emptyList(),
)

@Serializable
enum class PlatformDefinitionStatus {
    PROVISIONAL_SOURCE_ONLY,
    COMPLETE,
}

@Serializable
data class PlatformDisplayDefinition(
    val name: String,
    val shortName: String? = null,
    val manufacturer: String? = null,
    val searchTerms: List<String> = emptyList(),
)

@Serializable
data class FolderContract(
    val canonical: FolderMatch,
    val aliases: List<FolderMatch> = emptyList(),
    val flatDumpPolicy: FlatDumpPolicy = FlatDumpPolicy.USER_ASSIGNMENT_REQUIRED,
)

/**
 * A folder match is always an exact normalized segment. Shared segments must opt into the same
 * non-blank [ambiguityGroup], which preserves ambiguity instead of silently choosing a platform.
 */
@Serializable
data class FolderMatch(
    val normalizedSegment: String,
    val ambiguityGroup: String? = null,
)

@Serializable
enum class FlatDumpPolicy {
    UNIQUE_EXTENSION_ONLY,
    HEADER_OR_ID_REQUIRED,
    USER_ASSIGNMENT_REQUIRED,
}

@Serializable
data class IdentityStrategy(
    val kind: IdentityStrategyKind,
    val namespace: String? = null,
    val precedence: Int,
)

@Serializable
enum class IdentityStrategyKind {
    CONTENT_HASH,
    TITLE_ID,
    SERIAL,
    SOFTWARE_LIST_KEY,
    HEADER,
    EXACT_FILENAME,
    EXPLICIT_USER_CHOICE,
}

@Serializable
data class PresentationContract(
    val catalogEntryIds: List<CatalogEntryId> = emptyList(),
    val visualAssetKeys: List<VisualAssetKey> = emptyList(),
    val genericFallbackAllowed: Boolean = false,
)

@Serializable
data class CatalogEntryDefinition(
    val id: CatalogEntryId,
    val platformId: PlatformId,
    val relation: CatalogRelation,
    val displayName: String,
    val externalId: ExternalIdentity? = null,
    val folder: FolderContract? = null,
    val searchTerms: List<String> = emptyList(),
    val visualAssetKeys: List<VisualAssetKey> = emptyList(),
    val sourceOrdinal: Int? = null,
)

@Serializable
data class ExternalIdentity(
    val namespace: String,
    val value: String,
)

@Serializable
enum class CatalogRelation {
    CANONICAL,
    HARDWARE_VARIANT,
    REGIONAL_VARIANT,
    SOFTWARE_FAMILY,
    CONTENT_VARIANT,
    PRESENTATION_ALIAS,
}

@Serializable
data class ContentProfileDefinition(
    val id: ContentProfileId,
    val platformId: PlatformId,
    val extensionRules: List<ContentExtensionRule> = emptyList(),
    val groupingKind: GroupingKind? = null,
    val acceptedLocators: List<ContentLocatorKind> = emptyList(),
    val parserVersion: String,
    val discoveryStatus: ContentProfileStatus = ContentProfileStatus.BOUND,
    val externalId: ExternalIdentity? = null,
)

@Serializable
enum class ContentProfileStatus {
    BOUND,
    GAP,
}

@Serializable
data class ContentExtensionRule(
    val extension: String,
    val role: ContentRole,
    val launchEligible: Boolean,
)

@Serializable
enum class ContentRole {
    BASE_GAME,
    PLAYLIST,
    TRACK_MANIFEST,
    TRACK_MEMBER,
    DISC_MEMBER,
    UPDATE,
    DLC,
    ADD_ON,
    PATCH,
    BIOS,
    FIRMWARE,
    SAVE,
    MEDIA,
    METADATA,
    INVALID,
}

@Serializable
enum class GroupingKind {
    SINGLE_FILE,
    PLAYLIST_SET,
    TRACK_SET,
    DISC_SET,
    DIRECTORY_GAME,
    INSTALLED_TITLE,
    SOFTWARE_LIST_ITEM,
}

@Serializable
enum class ContentLocatorKind {
    FILE_PATH,
    DOCUMENT_URI,
    DIRECTORY_PATH,
    TREE_URI,
    INSTALLED_TITLE_ID,
    NONE,
}

@Serializable
data class ImportBindingDefinition(
    val id: ImportBindingId,
    val platformId: PlatformId,
    val contentProfileId: ContentProfileId,
    val folderMatches: List<FolderMatch>,
    val sourceExternalId: ExternalIdentity? = null,
    val catalogEntryId: CatalogEntryId? = null,
)

@Serializable
data class RetroArchRegistrationDefinition(
    val id: RetroArchRegistrationId,
    val sourceRecordId: String? = null,
    val platformId: PlatformId,
    val contentProfileId: ContentProfileId,
    val emulatorFamilyId: EmulatorFamilyId? = null,
    val externalId: ExternalIdentity? = null,
    val label: String,
    val coreRegistrations: List<RetroArchCoreRegistrationDefinition>,
    val recommendedCore: String? = null,
    val packageVariantIds: List<PackageVariantId> = emptyList(),
)

@Serializable
data class RetroArchCoreRegistrationDefinition(
    val id: String,
    val stem: String,
    val label: String,
    val recommended: Boolean,
    val sourceOrdinal: Int? = null,
)

@Serializable
data class EmulatorFamilyDefinition(
    val id: EmulatorFamilyId,
    val displayName: String,
    val kind: EmulatorFamilyKind,
    val packageVariantIds: List<PackageVariantId>,
)

@Serializable
enum class EmulatorFamilyKind {
    RETROARCH,
    STANDALONE,
    EMULATOR_HANDOFF,
}

@Serializable
data class VisualAssetDefinition(
    val key: VisualAssetKey,
    val role: VisualAssetRole,
    val resourceName: String,
    val platformId: PlatformId? = null,
    val catalogEntryId: CatalogEntryId? = null,
    val provenance: String,
    val genericFallback: Boolean = false,
    val sourceFile: String? = null,
)

@Serializable
enum class VisualAssetRole {
    PLATFORM_LOGO,
    PLATFORM_HERO,
    CONTROLLER,
    DEVICE,
    GENERIC_FALLBACK,
}

@Serializable
data class SupportSliceDefinition(
    val id: SupportSliceId,
    val kind: SupportSliceKind,
    val platformId: PlatformId,
    val contentProfileId: ContentProfileId,
    val launchRouteId: LaunchRouteId? = null,
    val locatorKind: ContentLocatorKind? = null,
    val importBindingIds: Set<ImportBindingId> = emptySet(),
    val requirementPolicyIds: Set<FirmwarePolicyId> = emptySet(),
    val displayPolicies: Set<DeviceDisplayPolicy> = emptySet(),
    val claimLevel: SupportClaimLevel,
)

@Serializable
enum class SupportSliceKind {
    MANAGE,
    PLAY,
}

@Serializable
enum class DeviceDisplayPolicy {
    SINGLE_SCREEN,
    DUAL_SCREEN_PRIMARY,
    DUAL_SCREEN_COMPANION,
    EXTERNAL_DISPLAY,
}
