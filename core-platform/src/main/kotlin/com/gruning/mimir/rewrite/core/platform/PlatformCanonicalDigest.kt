package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.IdMapVersion
import com.gruning.mimir.rewrite.core.model.PlatformDefinitionVersion
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.SupportSliceId
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal val RegistryJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
}

/** Deterministic SHA-256 contracts used by source, support-slice, envelope, and projection validation. */
object PlatformCanonicalDigest {
    private val Sha256Pattern = Regex("^[0-9a-f]{64}$")

    fun isSha256(value: String): Boolean = Sha256Pattern.matches(value)

    fun canonicalJson(rawJson: String): String =
        renderCanonical(RegistryJson.parseToJsonElement(rawJson))

    fun json(rawJson: String): String = sha256(canonicalJson(rawJson))

    fun registry(document: PlatformRegistryDocument): String =
        digest(PlatformRegistryDocument.serializer(), normalize(document))

    fun supportSlice(document: PlatformRegistryDocument, id: SupportSliceId): String? {
        val normalized = normalize(document)
        val slice = normalized.supportSlices.firstOrNull { it.id == id } ?: return null
        val platform = normalized.platforms.firstOrNull { it.id == slice.platformId } ?: return null
        val contentProfile = normalized.contentProfiles.firstOrNull { it.id == slice.contentProfileId }
            ?: return null
        val route = slice.launchRouteId?.let { routeId ->
            normalized.launchRoutes.firstOrNull { it.id == routeId }
        }
        val packageVariantIds = route?.packageVariantIds.orEmpty().toSet()
        val registrationIds = route?.retroArchRegistrationIds.orEmpty().toSet()
        val registrations = normalized.retroArchRegistrations
            .filter { it.id in registrationIds }
            .sortedBy { it.id.value }

        val material = SupportSliceDigestMaterial(
            schemaVersion = normalized.schemaVersion,
            idMapVersion = normalized.idMapVersion,
            compatibilityFloor = normalized.compatibilityFloor,
            platform = PlatformSliceIdentity(
                id = platform.id,
                aliases = platform.aliases,
                folder = platform.folder,
                identity = platform.identity,
            ),
            contentProfile = contentProfile,
            importBindings = normalized.importBindings
                .filter { it.id in slice.importBindingIds }
                .sortedBy { it.id.value },
            route = route,
            packageVariants = normalized.packageVariants
                .filter { it.id in packageVariantIds }
                .sortedBy { it.id.value },
            retroArchRegistrations = registrations,
            routeContentProfiles = normalized.contentProfiles
                .filter { profile -> registrations.any { it.contentProfileId == profile.id } }
                .sortedBy { it.id.value },
            emulatorFamily = route?.let { selectedRoute ->
                normalized.emulatorFamilies.firstOrNull { it.id == selectedRoute.emulatorFamilyId }
            },
            firmwarePolicies = normalized.firmwarePolicies
                .filter { it.id in slice.requirementPolicyIds }
                .sortedBy { it.id.value },
            supportSlice = slice,
        )
        return digest(SupportSliceDigestMaterial.serializer(), material)
    }

    fun intentContract(contract: IntentContract): String =
        digest(IntentContract.serializer(), normalize(contract))

    fun signerVersionComponent(
        signerEnvelope: SignerEnvelope,
        versionComponentEnvelope: VersionComponentEnvelope,
    ): String = digest(
        SignerVersionComponentMaterial.serializer(),
        SignerVersionComponentMaterial(
            signerEnvelope = normalize(signerEnvelope),
            versionComponentEnvelope = versionComponentEnvelope,
        ),
    )

    fun runtimeEnvelope(envelope: RuntimeEnvelopeKey): String =
        digest(RuntimeEnvelopeKey.serializer(), envelope)

    fun certification(document: PlatformCertificationDocument): String =
        digest(PlatformCertificationDocument.serializer(), normalize(document))

    private fun normalize(document: PlatformRegistryDocument): PlatformRegistryDocument = document.copy(
        declaredCanonicalDigest = null,
        platforms = document.platforms.map(::normalize).sortedBy { it.id.value },
        catalogEntries = document.catalogEntries.map(::normalize).sortedBy { it.id.value },
        contentProfiles = document.contentProfiles.map(::normalize).sortedBy { it.id.value },
        importBindings = document.importBindings.map(::normalize).sortedBy { it.id.value },
        retroArchRegistrations = document.retroArchRegistrations.sortedBy { it.id.value },
        emulatorFamilies = document.emulatorFamilies.sortedBy { it.id.value },
        launchRoutes = document.launchRoutes.sortedBy { it.id.value },
        packageVariants = document.packageVariants.map(::normalize).sortedBy { it.id.value },
        firmwarePolicies = document.firmwarePolicies.map(::normalize).sortedBy { it.id.value },
        visualAssets = document.visualAssets.sortedBy { it.key.value },
        supportSlices = document.supportSlices.map(::normalize).sortedBy { it.id.value },
    )

    private fun normalize(platform: PlatformDefinition): PlatformDefinition = platform.copy(
        aliases = platform.aliases.sortedWith(
            compareBy({ it.namespace.name }, { it.normalizedValue }, { it.idMapVersion.value }),
        ),
        display = platform.display.copy(searchTerms = platform.display.searchTerms.sorted()),
        folder = platform.folder.copy(
            aliases = platform.folder.aliases.sortedBy { it.normalizedSegment },
        ),
        contentProfileIds = platform.contentProfileIds.sortedBy { it.value },
        identity = platform.identity.sortedWith(compareBy({ it.precedence }, { it.kind.name })),
        presentation = platform.presentation.copy(
            catalogEntryIds = platform.presentation.catalogEntryIds.sortedBy { it.value },
            visualAssetKeys = platform.presentation.visualAssetKeys.sortedBy { it.value },
        ),
        launchRouteIds = platform.launchRouteIds.sortedBy { it.value },
    )

    private fun normalize(catalog: CatalogEntryDefinition): CatalogEntryDefinition = catalog.copy(
        searchTerms = catalog.searchTerms.sorted(),
        visualAssetKeys = catalog.visualAssetKeys.sortedBy { it.value },
    )

    private fun normalize(profile: ContentProfileDefinition): ContentProfileDefinition = profile.copy(
        extensionRules = profile.extensionRules.sortedWith(
            compareBy({ normalizeExtension(it.extension) }, { it.role.name }, { it.launchEligible }),
        ),
    )

    private fun normalize(binding: ImportBindingDefinition): ImportBindingDefinition = binding.copy(
        folderMatches = binding.folderMatches.sortedBy { it.normalizedSegment },
    )

    private fun normalize(variant: PackageVariantDefinition): PackageVariantDefinition = variant.copy(
        signerEnvelope = normalize(variant.signerEnvelope),
        intent = normalize(variant.intent),
        requirements = variant.requirements.sortedBy { it.id },
    )

    private fun normalize(signer: SignerEnvelope): SignerEnvelope = signer.copy(
        allowedCertificateSha256 = signer.allowedCertificateSha256.sorted(),
    )

    private fun normalize(intent: IntentContract): IntentContract = intent.copy(
        categories = intent.categories.sorted(),
        extras = intent.extras.sortedBy { it.name },
        flags = intent.flags.sorted(),
    )

    private fun normalize(policy: FirmwarePolicyDefinition): FirmwarePolicyDefinition = policy.copy(
        requirements = policy.requirements.sortedBy { it.id },
    )

    private fun normalize(slice: SupportSliceDefinition): SupportSliceDefinition = slice.copy(
        importBindingIds = slice.importBindingIds.sortedBy { it.value }.toCollection(linkedSetOf()),
        requirementPolicyIds = slice.requirementPolicyIds.sortedBy { it.value }.toCollection(linkedSetOf()),
        displayPolicies = slice.displayPolicies.sortedBy { it.name }.toCollection(linkedSetOf()),
    )

    private fun normalize(document: PlatformCertificationDocument): PlatformCertificationDocument =
        document.copy(
            declaredCanonicalDigest = null,
            certifications = document.certifications.map { certification ->
                certification.copy(
                    fixtureEvidence = certification.fixtureEvidence.sortedBy { it.id },
                    deviceEvidence = certification.deviceEvidence.sortedBy { it.id },
                    engineVersions = certification.engineVersions.toSortedMap(),
                )
            }.sortedBy { it.certificateId },
            revokedRuntimeEnvelopeDigests = document.revokedRuntimeEnvelopeDigests
                .sorted()
                .toCollection(linkedSetOf()),
        )

    private fun <T> digest(serializer: KSerializer<T>, value: T): String {
        val element = RegistryJson.encodeToJsonElement(serializer, value)
        return sha256(renderCanonical(element))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun renderCanonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${RegistryJson.encodeToString(JsonPrimitive(key))}:${renderCanonical(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
            renderCanonical(it)
        }
        else -> element.toString()
    }
}

@Serializable
private data class PlatformSliceIdentity(
    val id: PlatformId,
    val aliases: List<ScopedAlias>,
    val folder: FolderContract,
    val identity: List<IdentityStrategy>,
)

@Serializable
private data class SupportSliceDigestMaterial(
    val schemaVersion: PlatformDefinitionVersion,
    val idMapVersion: IdMapVersion,
    val compatibilityFloor: PlatformDefinitionVersion,
    val platform: PlatformSliceIdentity,
    val contentProfile: ContentProfileDefinition,
    val importBindings: List<ImportBindingDefinition>,
    val route: RuntimeRouteDefinition?,
    val packageVariants: List<PackageVariantDefinition>,
    val retroArchRegistrations: List<RetroArchRegistrationDefinition>,
    val routeContentProfiles: List<ContentProfileDefinition>,
    val emulatorFamily: EmulatorFamilyDefinition?,
    val firmwarePolicies: List<FirmwarePolicyDefinition>,
    val supportSlice: SupportSliceDefinition,
)

@Serializable
private data class SignerVersionComponentMaterial(
    val signerEnvelope: SignerEnvelope,
    val versionComponentEnvelope: VersionComponentEnvelope,
)

internal fun normalizeIdentity(value: String): String = normalizeToken(value, separator = "_")

internal fun normalizeFolder(value: String): String = normalizeToken(
    value.trim().trim('/', '\\'),
    separator = "_",
)

internal fun normalizeExtension(value: String): String =
    value.trim().removePrefix(".").lowercase()

internal fun searchTokens(value: String): Set<String> =
    normalizeToken(value, separator = " ")
        .split(' ')
        .filterTo(linkedSetOf()) { it.isNotBlank() }

private fun normalizeToken(value: String, separator: String): String {
    val lowered = java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC).lowercase()
    return lowered
        .replace(Regex("[^a-z0-9]+"), separator)
        .trim(separator.single())
}
