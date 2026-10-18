package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.CatalogEntryId
import com.gruning.mimir.rewrite.core.model.ContentProfileId
import com.gruning.mimir.rewrite.core.model.IdMapVersion
import com.gruning.mimir.rewrite.core.model.ImportBindingId
import com.gruning.mimir.rewrite.core.model.LaunchRouteId
import com.gruning.mimir.rewrite.core.model.PlatformDefinitionVersion
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RegistryRevision
import com.gruning.mimir.rewrite.core.model.RetroArchRegistrationId
import com.gruning.mimir.rewrite.core.model.VisualAssetKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

private val GeneratedRegistryJson = Json(RegistryJson) {
    ignoreUnknownKeys = true
}

private val GENERATED_COLLECTION_KEYS = linkedSetOf(
    "catalogEntries",
    "catalogRelationFixtures",
    "contentProfiles",
    "emulatorFamilies",
    "firmwarePolicies",
    "importBindings",
    "launchRoutes",
    "parityFixtures",
    "platforms",
    "recognitionStates",
    "retroArchRegistrations",
    "shippingRuntimeSystems",
    "standaloneTargetObservations",
    "supportSlices",
    "visualAssets",
)
private val GENERATED_COUNT_KEYS = GENERATED_COLLECTION_KEYS + "retroArchCoreRegistrations"
private val GENERATED_DIGEST_KEYS = setOf(
    "algorithm",
    "canonicalization",
    "collections",
    "registryPayloadSha256",
)

internal data class GeneratedRegistryAdaptation(
    val document: PlatformRegistryDocument?,
    val authoritativeDigest: String?,
    val issues: List<ValidationIssue>,
)

internal object GeneratedRegistryAdapter {
    fun isGeneratedEnvelope(root: JsonObject): Boolean = "collections" in root

    fun adapt(rawJson: String, root: JsonObject): GeneratedRegistryAdaptation {
        val wireValidation = validateWireEnvelope(root)
        if (wireValidation.issues.isNotEmpty()) {
            return GeneratedRegistryAdaptation(
                document = null,
                authoritativeDigest = wireValidation.authoritativeDigest,
                issues = wireValidation.issues,
            )
        }
        val envelope = try {
            GeneratedRegistryJson.decodeFromString<GeneratedRegistryEnvelope>(rawJson)
        } catch (exception: Exception) {
            return GeneratedRegistryAdaptation(
                document = null,
                authoritativeDigest = wireValidation.authoritativeDigest,
                issues = listOf(parseIssue("registry", exception)),
            )
        }

        val unsupportedCollections = buildList {
            if (envelope.collections.emulatorFamilies.isNotEmpty()) add("emulatorFamilies")
            if (envelope.collections.firmwarePolicies.isNotEmpty()) add("firmwarePolicies")
            if (envelope.collections.launchRoutes.isNotEmpty()) add("launchRoutes")
            if (envelope.collections.packageVariants.isNotEmpty()) add("packageVariants")
            if (envelope.collections.supportSlices.isNotEmpty()) add("supportSlices")
            if (envelope.collections.platforms.any { it.identity.isNotEmpty() }) add("platforms.identity")
        }
        if (unsupportedCollections.isNotEmpty()) {
            return GeneratedRegistryAdaptation(
                document = null,
                authoritativeDigest = envelope.digests.registryPayloadSha256,
                issues = listOf(
                    error(
                        ValidationScope.INPUT,
                        ValidationCode.PARSE_ERROR,
                        "collections",
                        "Generated runtime collection shapes need an explicit adapter: ${unsupportedCollections.joinToString()}",
                    ),
                ),
            )
        }

        return try {
            GeneratedRegistryAdaptation(
                document = toKernelDocument(envelope),
                authoritativeDigest = envelope.digests.registryPayloadSha256,
                issues = emptyList(),
            )
        } catch (exception: Exception) {
            GeneratedRegistryAdaptation(
                document = null,
                authoritativeDigest = envelope.digests.registryPayloadSha256,
                issues = listOf(parseIssue("collections", exception)),
            )
        }
    }

    fun adaptCertification(
        rawJson: String,
        root: JsonObject,
    ): Pair<PlatformCertificationDocument?, List<ValidationIssue>> {
        return try {
            val envelope = GeneratedRegistryJson.decodeFromString<GeneratedCertificationEnvelope>(rawJson)
            require(envelope.schemaVersion == "mimirPlatformCertification.v1") {
                "Unsupported generated certification schema ${envelope.schemaVersion}"
            }
            require(envelope.behaviorPosture == "BEHAVIOR_DARK") {
                "Generated certification posture ${envelope.behaviorPosture} needs an explicit activation policy"
            }
            require(envelope.revocations.isEmpty()) {
                "Generated support-slice revocations need an explicit certification adapter"
            }
            val certifications = envelope.certifications.mapIndexed { index, element ->
                try {
                    GeneratedRegistryJson.decodeFromJsonElement<SupportCertification>(element)
                } catch (exception: Exception) {
                    throw IllegalArgumentException(
                        "certifications[$index] does not match SupportCertification: ${exception.message}",
                        exception,
                    )
                }
            }
            PlatformCertificationDocument(
                schemaVersion = envelope.schemaVersion,
                registryRevision = RegistryRevision(envelope.registryRevision),
                idMapVersion = IdMapVersion(envelope.idMapVersion),
                registryCanonicalDigest = envelope.registryPayloadSha256,
                registrySchemaVersion = PlatformDefinitionVersion(envelope.registrySchemaVersion),
                compatibilityFloor = PlatformDefinitionVersion(envelope.compatibilityFloor),
                certifications = certifications,
                revokedRuntimeEnvelopeDigests = emptySet(),
            ) to emptyList()
        } catch (exception: Exception) {
            null to listOf(
                error(
                    ValidationScope.CERTIFICATION,
                    ValidationCode.PARSE_ERROR,
                    "certification",
                    exception.message ?: exception::class.java.simpleName,
                ),
            )
        }
    }

    fun isGeneratedCertification(root: JsonObject): Boolean = "registryPayloadSha256" in root

    private data class WireValidation(
        val authoritativeDigest: String?,
        val issues: List<ValidationIssue>,
    )

    private fun validateWireEnvelope(root: JsonObject): WireValidation {
        val issues = mutableListOf<ValidationIssue>()
        val collections = objectValue(root, "collections", "collections", issues)
        val counts = objectValue(root, "counts", "counts", issues)
        val digests = objectValue(root, "digests", "digests", issues)
        val collectionDigests = digests?.let {
            objectValue(it, "collections", "digests.collections", issues)
        }
        val authoritativeDigest = digests?.let {
            stringValue(it, "registryPayloadSha256", "digests.registryPayloadSha256", issues)
        }

        collections?.let { validateExactKeys("collections", it.keys, GENERATED_COLLECTION_KEYS, issues) }
        counts?.let { validateExactKeys("counts", it.keys, GENERATED_COUNT_KEYS, issues) }
        digests?.let { validateExactKeys("digests", it.keys, GENERATED_DIGEST_KEYS, issues) }
        collectionDigests?.let {
            validateExactKeys("digests.collections", it.keys, GENERATED_COLLECTION_KEYS, issues)
        }

        val collectionArrays = linkedMapOf<String, JsonArray>()
        collections?.let { values ->
            GENERATED_COLLECTION_KEYS.forEach { name ->
                val value = values[name]
                if (value !is JsonArray) {
                    issues += structuralIssue("collections.$name", "Expected an array")
                } else {
                    collectionArrays[name] = value
                }
            }
        }

        val declaredCounts = linkedMapOf<String, Int>()
        counts?.let { values ->
            GENERATED_COUNT_KEYS.forEach { name ->
                val value = values[name]
                val parsed = (value as? JsonPrimitive)
                    ?.takeUnless { it.isString }
                    ?.content
                    ?.toIntOrNull()
                if (parsed == null || parsed < 0) {
                    issues += structuralIssue("counts.$name", "Expected a non-negative integer")
                } else {
                    declaredCounts[name] = parsed
                }
            }
        }

        val declaredCollectionDigests = linkedMapOf<String, String>()
        collectionDigests?.let { values ->
            GENERATED_COLLECTION_KEYS.forEach { name ->
                val value = stringValue(
                    values,
                    name,
                    "digests.collections.$name",
                    issues,
                )
                if (value != null) {
                    if (!PlatformCanonicalDigest.isSha256(value)) {
                        issues += error(
                            ValidationScope.INPUT,
                            ValidationCode.INVALID_DIGEST,
                            "digests.collections.$name",
                            "Expected a lowercase SHA-256 digest",
                        )
                    } else {
                        declaredCollectionDigests[name] = value
                    }
                }
            }
        }

        val algorithm = digests?.let { stringValue(it, "algorithm", "digests.algorithm", issues) }
        val canonicalization = digests?.let {
            stringValue(it, "canonicalization", "digests.canonicalization", issues)
        }
        if (algorithm != null && canonicalization != null &&
            (algorithm != "SHA-256" || canonicalization != "mimir-canonical-json-v1")
        ) {
            issues += error(
                ValidationScope.INPUT,
                ValidationCode.INVALID_DIGEST,
                "digests",
                "Unsupported generated digest contract $algorithm/$canonicalization",
            )
        }

        if (authoritativeDigest != null) {
            if (!PlatformCanonicalDigest.isSha256(authoritativeDigest)) {
                issues += error(
                    ValidationScope.INPUT,
                    ValidationCode.INVALID_DIGEST,
                    "digests.registryPayloadSha256",
                    "Expected a lowercase SHA-256 digest",
                )
            } else {
                val payload = JsonObject(root.filterKeys { it != "digests" })
                val actualPayload = PlatformCanonicalDigest.json(payload.toString())
                if (authoritativeDigest != actualPayload) {
                    issues += error(
                        ValidationScope.INPUT,
                        ValidationCode.REGISTRY_DIGEST_MISMATCH,
                        "digests.registryPayloadSha256",
                        "Generated registry payload digest is $actualPayload, declared $authoritativeDigest",
                    )
                }
            }
        }

        collectionArrays.forEach { (name, collection) ->
            val expected = declaredCollectionDigests[name] ?: return@forEach
            val actual = PlatformCanonicalDigest.json(collection.toString())
            if (expected != actual) {
                issues += error(
                    ValidationScope.INPUT,
                    ValidationCode.REGISTRY_DIGEST_MISMATCH,
                    "digests.collections.$name",
                    "Collection '$name' digest is $actual, declared $expected",
                )
            }
        }

        collectionArrays.forEach { (name, collection) ->
            val expected = declaredCounts[name] ?: return@forEach
            val actual = collection.size
            if (expected != actual) {
                issues += error(
                    ValidationScope.INPUT,
                    ValidationCode.DUPLICATE_VALUE,
                    "counts.$name",
                    "Collection '$name' count is $actual, declared $expected",
                )
            }
        }
        val coreCount = collectionArrays["retroArchRegistrations"]?.let { registrations ->
            retroArchCoreCount(registrations, issues)
        }
        val expectedCoreCount = declaredCounts["retroArchCoreRegistrations"]
        if (coreCount != null && expectedCoreCount != null && coreCount != expectedCoreCount) {
            issues += error(
                ValidationScope.INPUT,
                ValidationCode.DUPLICATE_VALUE,
                "counts.retroArchCoreRegistrations",
                "RetroArch core registration count is $coreCount, declared $expectedCoreCount",
            )
        }
        return WireValidation(authoritativeDigest, issues)
    }

    private fun objectValue(
        parent: JsonObject,
        key: String,
        path: String,
        issues: MutableList<ValidationIssue>,
    ): JsonObject? {
        val value = parent[key]
        if (value !is JsonObject) {
            issues += structuralIssue(path, "Expected an object")
            return null
        }
        return value
    }

    private fun stringValue(
        parent: JsonObject,
        key: String,
        path: String,
        issues: MutableList<ValidationIssue>,
    ): String? {
        val value = parent[key]
        if (value !is JsonPrimitive || !value.isString) {
            issues += structuralIssue(path, "Expected a string")
            return null
        }
        return value.content
    }

    private fun validateExactKeys(
        path: String,
        actual: Set<String>,
        expected: Set<String>,
        issues: MutableList<ValidationIssue>,
    ) {
        val missing = (expected - actual).sorted()
        val unknown = (actual - expected).sorted()
        if (missing.isNotEmpty()) {
            issues += structuralIssue(path, "Missing keys: ${missing.joinToString()}")
        }
        if (unknown.isNotEmpty()) {
            issues += structuralIssue(path, "Unknown keys: ${unknown.joinToString()}")
        }
    }

    private fun retroArchCoreCount(
        registrations: JsonArray,
        issues: MutableList<ValidationIssue>,
    ): Int? {
        var count = 0
        var valid = true
        registrations.forEachIndexed { index, element ->
            val registration = element as? JsonObject
            if (registration == null) {
                issues += structuralIssue(
                    "collections.retroArchRegistrations[$index]",
                    "Expected an object",
                )
                valid = false
                return@forEachIndexed
            }
            val cores = registration["coreRegistrations"] as? JsonArray
            if (cores == null) {
                issues += structuralIssue(
                    "collections.retroArchRegistrations[$index].coreRegistrations",
                    "Expected an array",
                )
                valid = false
            } else {
                count += cores.size
            }
        }
        return count.takeIf { valid }
    }

    private fun structuralIssue(path: String, message: String): ValidationIssue = error(
        ValidationScope.INPUT,
        ValidationCode.PARSE_ERROR,
        path,
        message,
    )

    private fun toKernelDocument(envelope: GeneratedRegistryEnvelope): PlatformRegistryDocument {
        require(envelope.schemaVersion == "mimirPlatformRegistry.v2") {
            "Unsupported generated registry schema ${envelope.schemaVersion}"
        }
        require(envelope.behaviorPosture == "BEHAVIOR_DARK") {
            "Generated behavior posture ${envelope.behaviorPosture} needs an explicit activation policy"
        }
        val collections = envelope.collections
        val idMapVersion = IdMapVersion(envelope.idMapVersion)
        val catalogById = collections.catalogEntries.associateBy { it.id }
        val catalogsByPlatform = collections.catalogEntries.groupBy { it.platformId }

        val allFoldersByPlatform = linkedMapOf<String, MutableSet<String>>()
        collections.catalogEntries.forEach { catalog ->
            val folder = catalog.folderContract
            val values = allFoldersByPlatform.getOrPut(catalog.platformId) { linkedSetOf() }
            values += folder.canonicalFolder
            values += folder.aliases
        }
        val recognitionFoldersByPlatform = linkedMapOf<String, MutableSet<String>>()
        collections.platforms.forEach { platform ->
            val values = recognitionFoldersByPlatform.getOrPut(platform.id) { linkedSetOf() }
            values += platform.folder.canonicalFolder
            values += platform.folder.aliases
            val allValues = allFoldersByPlatform.getOrPut(platform.id) { linkedSetOf() }
            allValues += platform.folder.canonicalFolder
            allValues += platform.folder.aliases
        }
        val normalizedOwners = buildMap<String, Set<String>> {
            allFoldersByPlatform.forEach { (platformId, values) ->
                values.map(::normalizeFolder).filter { it.isNotBlank() }.forEach { segment ->
                    put(segment, get(segment).orEmpty() + platformId)
                }
            }
        }
        fun folderMatch(value: String): FolderMatch {
            val normalized = normalizeFolder(value)
            val owners = normalizedOwners[normalized].orEmpty()
            return FolderMatch(
                normalizedSegment = normalized,
                ambiguityGroup = if (owners.size > 1) "generated-shared:$normalized" else null,
            )
        }
        fun flatDumpPolicy(value: String): FlatDumpPolicy = when (value) {
            "unique_extension_only" -> FlatDumpPolicy.UNIQUE_EXTENSION_ONLY
            "header_or_id_required" -> FlatDumpPolicy.HEADER_OR_ID_REQUIRED
            "ambiguous_until_folder_or_user_choice", "user_assignment_required" ->
                FlatDumpPolicy.USER_ASSIGNMENT_REQUIRED
            else -> throw IllegalArgumentException("Unsupported flatDumpPolicy '$value'")
        }
        collections.catalogEntries.forEach { catalog ->
            require(!catalog.folderContract.caseSensitive) {
                "Case-sensitive generated folder contracts need an explicit kernel policy: ${catalog.id}"
            }
            require(catalog.folderContract.schemaVersion == "mimirPlatformFolderContract.v1") {
                "Unsupported generated folder contract ${catalog.folderContract.schemaVersion}: ${catalog.id}"
            }
        }
        collections.platforms.forEach { platform ->
            require(!platform.folder.caseSensitive) {
                "Case-sensitive generated platform folder needs an explicit kernel policy: ${platform.id}"
            }
            require(platform.folder.schemaVersion == "mimirPlatformFolderContract.v1") {
                "Unsupported generated folder contract ${platform.folder.schemaVersion}: ${platform.id}"
            }
            require(platform.folder.platformId == platform.id) {
                "Generated platform folder ${platform.folder.platformId} does not belong to ${platform.id}"
            }
        }

        val platforms = collections.platforms.map { source ->
            val canonicalRaw = source.folder.canonicalFolder
            val allFolders = recognitionFoldersByPlatform[source.id]
                .orEmpty()
                .map(::normalizeFolder)
                .filter { it.isNotBlank() }
                .distinct()
            val canonical = normalizeFolder(canonicalRaw)
            PlatformDefinition(
                id = PlatformId(source.id),
                aliases = source.aliases.map { alias ->
                    ScopedAlias(
                        namespace = identityNamespace(alias.namespace),
                        normalizedValue = normalizeIdentity(alias.normalizedValue),
                        idMapVersion = IdMapVersion(alias.idMapVersion),
                        targetId = PlatformId(alias.targetId),
                    )
                },
                display = PlatformDisplayDefinition(
                    name = source.display.displayName,
                    shortName = source.display.shortName,
                    manufacturer = source.display.manufacturer,
                    searchTerms = catalogsByPlatform[source.id].orEmpty().flatMap { it.searchTokens }.distinct(),
                ),
                folder = FolderContract(
                    canonical = folderMatch(canonical),
                    aliases = allFolders.filter { it != canonical }.map(::folderMatch),
                    flatDumpPolicy = flatDumpPolicy(source.folder.flatDumpPolicy),
                ),
                definitionStatus = when (source.definitionStatus) {
                    "PROVISIONAL_SOURCE_ONLY" -> PlatformDefinitionStatus.PROVISIONAL_SOURCE_ONLY
                    else -> PlatformDefinitionStatus.COMPLETE
                },
                contentProfileIds = source.contentProfileIds.map(::ContentProfileId),
                presentation = PresentationContract(
                    catalogEntryIds = source.presentation.catalogEntryIds.map(::CatalogEntryId),
                    visualAssetKeys = source.presentation.visualAssetKeys.map(::VisualAssetKey),
                ),
                launchRouteIds = source.launchRouteIds.map(::LaunchRouteId),
            )
        }

        val catalogEntries = collections.catalogEntries.map { source ->
            CatalogEntryDefinition(
                id = CatalogEntryId(source.id),
                platformId = PlatformId(source.platformId),
                relation = generatedCatalogRelation(source.relation, source.id),
                displayName = source.display.displayName,
                externalId = source.externalId.toKernel(),
                folder = FolderContract(
                    canonical = folderMatch(source.folderContract.canonicalFolder),
                    aliases = source.folderContract.aliases
                        .map(::normalizeFolder)
                        .distinct()
                        .filter { it != normalizeFolder(source.folderContract.canonicalFolder) }
                        .map(::folderMatch),
                    flatDumpPolicy = flatDumpPolicy(source.folderContract.flatDumpPolicy),
                ),
                searchTerms = source.searchTokens,
                visualAssetKeys = listOfNotNull(source.visualAssetId?.let(::VisualAssetKey)),
                sourceOrdinal = source.sourceOrdinal,
            )
        }

        val contentProfiles = collections.contentProfiles.map { source ->
            val contract = source.contentContract
            val rules = buildList {
                contract?.baseGameExtensions.orEmpty().forEach { extension ->
                    add(ContentExtensionRule(normalizeExtension(extension), ContentRole.BASE_GAME, true))
                }
                contract?.sidecarRules.orEmpty().flatMap { it.extensions }.forEach { extension ->
                    add(ContentExtensionRule(normalizeExtension(extension), ContentRole.TRACK_MEMBER, false))
                }
                contract?.conditionalContent.orEmpty().forEach { conditional ->
                    val role = when (conditional.kind.lowercase()) {
                        "update" -> ContentRole.UPDATE
                        "dlc" -> ContentRole.DLC
                        "addon", "add_on" -> ContentRole.ADD_ON
                        "patch" -> ContentRole.PATCH
                        else -> ContentRole.INVALID
                    }
                    conditional.extensions.forEach { extension ->
                        add(ContentExtensionRule(normalizeExtension(extension), role, false))
                    }
                }
                contract?.supportExtensions.orEmpty().forEach { extension ->
                    add(ContentExtensionRule(normalizeExtension(extension), ContentRole.METADATA, false))
                }
            }.distinctBy { it.extension to it.role }
            ContentProfileDefinition(
                id = ContentProfileId(source.id),
                platformId = PlatformId(source.platformId),
                extensionRules = rules,
                groupingKind = null,
                acceptedLocators = emptyList(),
                parserVersion = contract?.schemaVersion ?: "source-gap",
                discoveryStatus = if (source.discoveryStatus == "BOUND") {
                    ContentProfileStatus.BOUND
                } else {
                    ContentProfileStatus.GAP
                },
                externalId = source.externalId.toKernel(),
            )
        }

        val importBindings = collections.importBindings.map { source ->
            val catalog = source.catalogEntryId?.let(catalogById::get)
            val rawFolders = catalog?.folderContract?.let { listOf(it.canonicalFolder) + it.aliases }
                ?: recognitionFoldersByPlatform[source.platformId].orEmpty().toList()
            ImportBindingDefinition(
                id = ImportBindingId(source.id),
                platformId = PlatformId(source.platformId),
                contentProfileId = ContentProfileId(source.contentProfileId),
                folderMatches = rawFolders.map(::normalizeFolder).distinct().map(::folderMatch),
                sourceExternalId = source.sourceExternalId.toKernel(),
                catalogEntryId = source.catalogEntryId?.let(::CatalogEntryId),
            )
        }

        val registrations = collections.retroArchRegistrations.map { source ->
            RetroArchRegistrationDefinition(
                id = RetroArchRegistrationId(source.externalId.value),
                sourceRecordId = source.id,
                platformId = PlatformId(source.platformId),
                contentProfileId = ContentProfileId(source.contentProfileId),
                externalId = source.externalId.toKernel(),
                label = source.label,
                coreRegistrations = source.coreRegistrations.map { core ->
                    RetroArchCoreRegistrationDefinition(
                        id = core.id,
                        stem = core.stem,
                        label = core.label,
                        recommended = core.isRecommended,
                        sourceOrdinal = core.sourceOrdinal,
                    )
                },
                recommendedCore = source.recommendedCore,
            )
        }

        val visualAssets = collections.visualAssets.map { source ->
            VisualAssetDefinition(
                key = VisualAssetKey(source.id),
                role = VisualAssetRole.PLATFORM_LOGO,
                resourceName = source.relativePath,
                catalogEntryId = CatalogEntryId(source.catalogEntryId),
                provenance = source.assetLicenseRef,
                sourceFile = source.sourceFile,
            )
        }

        return PlatformRegistryDocument(
            schemaVersion = PlatformDefinitionVersion(envelope.schemaVersion),
            registryRevision = RegistryRevision(envelope.registryRevision),
            idMapVersion = idMapVersion,
            compatibilityFloor = PlatformDefinitionVersion(envelope.compatibilityFloor),
            platforms = platforms,
            catalogEntries = catalogEntries,
            contentProfiles = contentProfiles,
            importBindings = importBindings,
            retroArchRegistrations = registrations,
            visualAssets = visualAssets,
        )
    }

    private fun identityNamespace(namespace: String): IdentityNamespace = when (namespace) {
        "shipping-runtime" -> IdentityNamespace.SHIPPING_RUNTIME
        "shipping-catalog" -> IdentityNamespace.SHIPPING_CATALOG
        "rewrite-v1" -> IdentityNamespace.REWRITE_LEGACY
        "shipping-retroarch-registration" -> IdentityNamespace.RETROARCH_REGISTRATION
        else -> IdentityNamespace.EXTERNAL_FRONTEND
    }

    private fun WireExternalIdentity.toKernel(): ExternalIdentity = ExternalIdentity(namespace, value)

    private fun parseIssue(path: String, exception: Exception): ValidationIssue = error(
        ValidationScope.INPUT,
        ValidationCode.PARSE_ERROR,
        path,
        exception.message ?: exception::class.java.simpleName,
    )
}

internal fun generatedCatalogRelation(value: String, recordId: String): CatalogRelation = when (value) {
    "CANONICAL" -> CatalogRelation.CANONICAL
    "HARDWARE_VARIANT" -> CatalogRelation.HARDWARE_VARIANT
    "CONTENT_VARIANT" -> CatalogRelation.CONTENT_VARIANT
    "PRESENTATION_ALIAS" -> CatalogRelation.PRESENTATION_ALIAS
    "REGIONAL_VARIANT" -> CatalogRelation.REGIONAL_VARIANT
    "SOFTWARE_FAMILY" -> CatalogRelation.SOFTWARE_FAMILY
    else -> throw IllegalArgumentException(
        "Unsupported generated catalog relation '$value' for $recordId",
    )
}

@Serializable
private data class GeneratedRegistryEnvelope(
    val behaviorPosture: String,
    val collections: GeneratedCollections,
    val compatibilityFloor: String,
    val counts: Map<String, Int>,
    val digests: GeneratedDigests,
    val generatedBy: String,
    val idMapVersion: String,
    val registryRevision: String,
    val schemaVersion: String,
)

@Serializable
private data class GeneratedCollections(
    val catalogEntries: List<WireCatalogEntry>,
    val contentProfiles: List<WireContentProfile>,
    val emulatorFamilies: List<JsonObject>,
    val firmwarePolicies: List<JsonObject>,
    val importBindings: List<WireImportBinding>,
    val launchRoutes: List<JsonObject>,
    val packageVariants: List<JsonObject> = emptyList(),
    val platforms: List<WirePlatform>,
    val retroArchRegistrations: List<WireRetroArchRegistration>,
    val shippingRuntimeSystems: List<WireRuntimeSystem>,
    val supportSlices: List<JsonObject>,
    val visualAssets: List<WireVisualAsset>,
)

@Serializable
private data class GeneratedDigests(
    val algorithm: String,
    val canonicalization: String,
    val collections: Map<String, String>,
    val registryPayloadSha256: String,
)

@Serializable
private data class WirePlatform(
    val aliases: List<WireAlias>,
    val contentProfileIds: List<String>,
    val definitionStatus: String,
    val display: WireDisplay,
    val folder: WireFolderContract,
    val id: String,
    val identity: List<JsonObject>,
    val launchRouteIds: List<String>,
    val presentation: WirePresentation,
)

@Serializable
private data class WirePresentation(
    val catalogEntryIds: List<String>,
    val visualAssetKeys: List<String>,
)

@Serializable
private data class WireAlias(
    val idMapVersion: String,
    val namespace: String,
    val normalizedValue: String,
    val targetId: String,
)

@Serializable
private data class WireDisplay(
    val displayName: String,
    val manufacturer: String? = null,
    val shortName: String? = null,
    val type: String? = null,
)

@Serializable
private data class WireExternalIdentity(
    val namespace: String,
    val value: String,
)

@Serializable
private data class WireFolderContract(
    val aliases: List<String>,
    val canonicalFolder: String,
    val caseSensitive: Boolean,
    val flatDumpPolicy: String,
    val platformId: String,
    val schemaVersion: String,
    val truth: String,
)

@Serializable
private data class WireCatalogEntry(
    val display: WireDisplay,
    val externalId: WireExternalIdentity,
    val folderContract: WireFolderContract,
    val id: String,
    val platformId: String,
    val relation: String,
    val searchTokens: List<String>,
    val sourceOrdinal: Int? = null,
    val visualAssetId: String? = null,
)

@Serializable
private data class WireContentProfile(
    val contentContract: WireContentContract? = null,
    val discoveryStatus: String,
    val externalId: WireExternalIdentity,
    val id: String,
    val platformId: String,
)

@Serializable
private data class WireContentContract(
    val baseGameExtensions: List<String> = emptyList(),
    val conditionalContent: List<WireConditionalContent> = emptyList(),
    val schemaVersion: String,
    val sidecarRules: List<WireSidecarRule> = emptyList(),
    val supportExtensions: List<String> = emptyList(),
)

@Serializable
private data class WireConditionalContent(
    val extensions: List<String> = emptyList(),
    val kind: String,
)

@Serializable
private data class WireSidecarRule(
    val extensions: List<String> = emptyList(),
    val kind: String,
)

@Serializable
private data class WireImportBinding(
    val catalogEntryId: String? = null,
    val contentProfileId: String,
    val id: String,
    val platformId: String,
    val sourceExternalId: WireExternalIdentity,
)

@Serializable
private data class WireRetroArchRegistration(
    val contentProfileId: String,
    val coreRegistrations: List<WireRetroArchCore>,
    val externalId: WireExternalIdentity,
    val id: String,
    val label: String,
    val platformId: String,
    val recommendedCore: String? = null,
)

@Serializable
private data class WireRetroArchCore(
    val id: String,
    val isRecommended: Boolean,
    val label: String,
    val sourceOrdinal: Int? = null,
    val stem: String,
)

@Serializable
private data class WireRuntimeSystem(
    val externalId: WireExternalIdentity,
    val extensions: List<String>,
    val folder: String,
    val folderAliases: List<String>,
    val id: String,
    val label: String,
    val platformId: String,
)

@Serializable
private data class WireVisualAsset(
    val assetLicenseRef: String,
    val catalogEntryId: String,
    val id: String,
    val relativePath: String,
    val sourceFile: String? = null,
)

@Serializable
private data class GeneratedCertificationEnvelope(
    val behaviorPosture: String,
    val certifications: List<JsonElement>,
    val compatibilityFloor: String,
    val idMapVersion: String,
    val registryPayloadSha256: String,
    val registryRevision: String,
    val registrySchemaVersion: String,
    val revocations: List<JsonElement>,
    val schemaVersion: String,
)
