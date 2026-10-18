package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.CoreArtifactEnvelopeId
import com.gruning.mimir.rewrite.core.model.DeviceEnvelopeId
import com.gruning.mimir.rewrite.core.model.EmulatorFamilyId
import com.gruning.mimir.rewrite.core.model.FirmwarePolicyId
import com.gruning.mimir.rewrite.core.model.LaunchRouteId
import com.gruning.mimir.rewrite.core.model.PackageVariantId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RetroArchRegistrationId
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeRouteDefinition(
    val id: LaunchRouteId,
    val platformId: PlatformId,
    val kind: RuntimeRouteKind,
    val emulatorFamilyId: EmulatorFamilyId,
    val packageVariantIds: List<PackageVariantId>,
    val retroArchRegistrationIds: List<RetroArchRegistrationId> = emptyList(),
    val precedence: Int,
    val autoSelectable: Boolean,
)

@Serializable
enum class RuntimeRouteKind {
    RETROARCH,
    STANDALONE,
    EMULATOR_HANDOFF,
}

@Serializable
data class PackageVariantDefinition(
    val id: PackageVariantId,
    val packageName: String,
    val signerEnvelope: SignerEnvelope,
    val versionComponentEnvelope: VersionComponentEnvelope,
    val hostAbi: HostAbi,
    val coreArtifactEnvelopeId: CoreArtifactEnvelopeId? = null,
    val intent: IntentContract,
    val acceptedLocators: List<ContentLocatorKind>,
    val requirements: List<RuntimeRequirement> = emptyList(),
)

@Serializable
data class SignerEnvelope(
    val policy: SignerPolicy,
    val allowedCertificateSha256: List<String>,
)

@Serializable
enum class SignerPolicy {
    EXACT_ANY_ALLOWED,
    EXACT_ALL_ALLOWED,
}

@Serializable
data class VersionComponentEnvelope(
    val minimumVersionCode: Long,
    val maximumVersionCode: Long? = null,
    val versionNamePattern: String? = null,
    val componentPolicy: ComponentPolicy,
    val activityCandidates: List<String>,
)

@Serializable
enum class ComponentPolicy {
    EXPLICIT_ONLY,
    FIRST_RESOLVABLE_DECLARED,
    PACKAGE_DEFAULT,
}

@Serializable
enum class HostAbi {
    ARM64_V8A,
    ARMEABI_V7A,
    X86_64,
    X86,
    UNIVERSAL_PROBED,
}

@Serializable
data class IntentContract(
    val action: String,
    val componentPolicy: ComponentPolicy,
    val categories: List<String>,
    val dataMode: IntentDataMode,
    val mimeType: String? = null,
    val extras: List<IntentExtraContract>,
    val flags: List<String>,
    val uriGrantPolicy: UriGrantPolicy,
)

@Serializable
enum class IntentDataMode {
    LOCATOR_AS_DATA,
    LOCATOR_AS_URI_EXTRA,
    LOCATOR_AS_PATH_EXTRA,
    NO_CONTENT,
}

@Serializable
data class IntentExtraContract(
    val name: String,
    val valueSource: IntentValueSource,
    val required: Boolean,
)

@Serializable
enum class IntentValueSource {
    CONTENT_LOCATOR,
    RETROARCH_CORE_PATH,
    FIXED_VALUE,
}

@Serializable
enum class UriGrantPolicy {
    NONE,
    READ,
    READ_AND_PERSISTABLE,
}

@Serializable
data class RuntimeRequirement(
    val id: String,
    val kind: RequirementKind,
    val required: Boolean,
    val acceptableNames: List<String> = emptyList(),
    val acceptableSha256: List<String> = emptyList(),
    val relativePlacementHint: String? = null,
    val detectionMethods: List<DetectionMethod>,
    val validationAuthority: ValidationAuthority,
    val displayCopy: String,
)

@Serializable
enum class RequirementKind {
    BIOS,
    FIRMWARE,
    KEY_MATERIAL,
    RETROARCH_CORE,
    EMULATOR_SETUP,
}

@Serializable
enum class ValidationAuthority {
    MIMIR_PROBE,
    TARGET_AT_DISPATCH,
}

@Serializable
enum class DetectionMethod {
    APP_READABLE_PATH,
    USER_GRANTED_TREE,
    PACKAGE_PROVIDER,
    EMULATOR_PROBE,
    UNVERIFIABLE_PRIVATE_STORAGE,
}

@Serializable
enum class Presence {
    PRESENT,
    MISSING,
    UNKNOWN,
}

@Serializable
enum class RequirementReadiness {
    ACTIVE_CONFIRMED,
    MISSING_CONFIRMED,
    STAGED,
    IMPORT_REQUIRED,
    TARGET_VALIDATES,
    UNKNOWN,
    OPTIONAL_MISSING,
    NOT_APPLICABLE,
}

@Serializable
data class FirmwarePolicyDefinition(
    val id: FirmwarePolicyId,
    val platformId: PlatformId,
    val launchRouteId: LaunchRouteId? = null,
    val packageVariantId: PackageVariantId? = null,
    val requirements: List<RuntimeRequirement>,
)

/** The complete runtime envelope to which a PLAY certificate is bound. */
@Serializable
data class RuntimeEnvelopeKey(
    val packageVariantId: PackageVariantId,
    val intentContractDigest: String,
    val signerVersionComponentDigest: String,
    val hostAbi: HostAbi,
    val coreArtifactEnvelopeId: CoreArtifactEnvelopeId? = null,
    val deviceEnvelopeId: DeviceEnvelopeId,
)
