package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.ContentProfileId
import com.gruning.mimir.rewrite.core.model.IdMapVersion
import com.gruning.mimir.rewrite.core.model.ImportBindingId
import com.gruning.mimir.rewrite.core.model.LaunchRouteId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.PlatformDefinitionVersion
import com.gruning.mimir.rewrite.core.model.RegistryRevision
import com.gruning.mimir.rewrite.core.model.SupportSliceId
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class SupportClaimLevel {
    CATALOG_ONLY,
    DISCOVERABLE,
    RESOLVABLE,
    LAUNCHABLE,
    CERTIFIED;

    fun implies(level: SupportClaimLevel): Boolean = ordinal >= level.ordinal
}

@Serializable
enum class PublicSupportClass {
    CATALOG_ONLY,
    LIBRARY_ONLY,
    EMULATOR_HANDOFF,
    DIRECT_PLAY,
}

@Serializable
enum class CertificationContractVersion {
    MANAGE_PLATFORM_V1,
    PLAY_PLATFORM_V1,
}

@Serializable
enum class CertificationChannel {
    INTERNAL,
    LABS,
    STABLE,
}

@Serializable
data class EvidenceRef(
    val id: String,
    val sha256: String,
    val kind: EvidenceKind,
)

@Serializable
enum class EvidenceKind {
    FIXTURE_REPORT,
    DEVICE_REPORT,
    LAUNCH_TRACE,
    RETURN_TRACE,
    PERFORMANCE_REPORT,
    FAULT_REPORT,
    VISUAL_REPORT,
    EVIDENCE_INDEX,
}

@Serializable
data class SupportCertification(
    val certificateId: String,
    val supportSliceId: SupportSliceId,
    val publicClass: PublicSupportClass,
    val runtimeEnvelopeKey: RuntimeEnvelopeKey? = null,
    val fixtureEvidence: List<EvidenceRef>,
    val deviceEvidence: List<EvidenceRef>,
    val sliceDigest: String,
    val contractVersion: CertificationContractVersion,
    val engineVersions: Map<String, String>,
    @Serializable(with = InstantIso8601Serializer::class)
    val issuedAt: Instant,
    @Serializable(with = InstantIso8601Serializer::class)
    val reviewAfter: Instant,
    @Serializable(with = InstantIso8601Serializer::class)
    val labsNotAfter: Instant? = null,
    val channel: CertificationChannel = CertificationChannel.STABLE,
    val revoked: Boolean = false,
)

@Serializable
data class PlatformCertificationDocument(
    val schemaVersion: String,
    val registryRevision: RegistryRevision,
    val idMapVersion: IdMapVersion,
    val registryCanonicalDigest: String,
    val registrySchemaVersion: PlatformDefinitionVersion? = null,
    val compatibilityFloor: PlatformDefinitionVersion? = null,
    val declaredCanonicalDigest: String? = null,
    val certifications: List<SupportCertification> = emptyList(),
    val revokedRuntimeEnvelopeDigests: Set<String> = emptySet(),
)

/** Digest metadata embedded by generated consumers of the registry. */
@Serializable
data class ProjectionDigestManifest(
    val projectionId: String,
    val registryRevision: RegistryRevision,
    val registryCanonicalDigest: String,
    val supportSliceDigests: Map<SupportSliceId, String> = emptyMap(),
)

data class ProjectionDigestPolicy(
    val requiredProjectionIds: Set<String> = emptySet(),
    val manifests: List<ProjectionDigestManifest> = emptyList(),
) {
    companion object {
        val KERNEL_ONLY = ProjectionDigestPolicy()
    }
}

/** Only the registry policy implementation can construct Stable activation authority. */
class CertifiedActivation internal constructor(
    val platformId: PlatformId,
    val allowedSupportSliceIds: Set<SupportSliceId>,
    val allowedImportBindingIds: Set<ImportBindingId>,
    val allowedContentProfileIds: Set<ContentProfileId>,
    val certificationSnapshotDigest: String,
)

/** Only the registry policy implementation can construct exact Stable play authority. */
class CertifiedSupportSlice internal constructor(
    val id: SupportSliceId,
    val platformId: PlatformId,
    val contentProfileId: ContentProfileId,
    val launchRouteId: LaunchRouteId,
    val locatorKind: ContentLocatorKind,
    val runtimeEnvelopeKey: RuntimeEnvelopeKey,
    val publicClass: PublicSupportClass,
)

class PlatformSupportReport internal constructor(
    val platformId: PlatformId,
    val knownPlatform: Boolean,
    val highestClaimLevel: SupportClaimLevel,
    val publicSupportClass: PublicSupportClass,
    val certifiedClasses: Set<PublicSupportClass>,
    val coveredSupportSliceIds: Set<SupportSliceId>,
    val coveredContentProfileIds: Set<ContentProfileId>,
    val diagnostics: List<ValidationIssue>,
)

object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
