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
import java.util.Collections

interface PlatformRegistry {
    val version: PlatformDefinitionVersion
    val registryRevision: RegistryRevision
    val idMapVersion: IdMapVersion
    val canonicalDigest: String
    val validationReport: RegistryValidationReport

    fun platforms(): List<PlatformDefinition>
    fun definition(id: PlatformId): PlatformDefinition?
    fun catalogEntry(id: CatalogEntryId): CatalogEntryDefinition?
    fun contentProfile(id: ContentProfileId): ContentProfileDefinition?
    fun importBinding(id: ImportBindingId): ImportBindingDefinition?
    fun retroArchRegistration(id: RetroArchRegistrationId): RetroArchRegistrationDefinition?
    fun emulatorFamily(id: EmulatorFamilyId): EmulatorFamilyDefinition?
    fun route(id: LaunchRouteId): RuntimeRouteDefinition?
    fun packageVariant(id: PackageVariantId): PackageVariantDefinition?
    fun firmwarePolicy(id: FirmwarePolicyId): FirmwarePolicyDefinition?
    fun visualAsset(key: VisualAssetKey): VisualAssetDefinition?
    fun supportSlice(id: SupportSliceId): SupportSliceDefinition?

    fun canonicalize(namespace: IdentityNamespace, rawId: String): PlatformId?
    fun search(query: String): List<PlatformSearchHit>
    fun folderCandidates(segment: String): List<PlatformId>
    fun extensionCandidates(extension: String): List<PlatformId>
    fun routes(id: PlatformId): List<RuntimeRouteDefinition>
    fun supportReport(id: PlatformId): PlatformSupportReport
    fun activation(id: PlatformId): CertifiedActivation?
    fun certifiedSlices(id: PlatformId): List<CertifiedSupportSlice>
    fun supportSliceDigest(id: SupportSliceId): String?
}

data class PlatformSearchHit(
    val platformId: PlatformId,
    val displayName: String,
    val matchedTokens: Set<String>,
)

class ImmutablePlatformRegistry private constructor(
    private val document: PlatformRegistryDocument,
    override val canonicalDigest: String,
    override val validationReport: RegistryValidationReport,
    eligibleCertifications: List<SupportCertification>,
    private val certificationSnapshotDigest: String,
) : PlatformRegistry {
    override val version: PlatformDefinitionVersion = document.schemaVersion
    override val registryRevision: RegistryRevision = document.registryRevision
    override val idMapVersion: IdMapVersion = document.idMapVersion

    private val platformList = immutableList(document.platforms.sortedBy { it.id.value })
    private val platformById = immutableMap(document.platforms.associateBy { it.id })
    private val catalogById = immutableMap(document.catalogEntries.associateBy { it.id })
    private val profileById = immutableMap(document.contentProfiles.associateBy { it.id })
    private val bindingById = immutableMap(document.importBindings.associateBy { it.id })
    private val registrationById = immutableMap(document.retroArchRegistrations.associateBy { it.id })
    private val familyById = immutableMap(document.emulatorFamilies.associateBy { it.id })
    private val routeById = immutableMap(document.launchRoutes.associateBy { it.id })
    private val variantById = immutableMap(document.packageVariants.associateBy { it.id })
    private val policyById = immutableMap(document.firmwarePolicies.associateBy { it.id })
    private val visualById = immutableMap(document.visualAssets.associateBy { it.key })
    private val sliceById = immutableMap(document.supportSlices.associateBy { it.id })

    private data class AliasKey(
        val namespace: IdentityNamespace,
        val normalizedValue: String,
    )

    private val aliases: Map<AliasKey, PlatformId> = buildMap {
        document.platforms.forEach { platform ->
            put(AliasKey(IdentityNamespace.CANONICAL_PLATFORM, platform.id.value), platform.id)
            platform.aliases.forEach { alias ->
                put(AliasKey(alias.namespace, alias.normalizedValue), alias.targetId)
            }
        }
        document.retroArchRegistrations.forEach { registration ->
            put(
                AliasKey(IdentityNamespace.RETROARCH_REGISTRATION, normalizeIdentity(registration.id.value)),
                registration.platformId,
            )
        }
    }.let(::immutableMap)

    private val folders: Map<String, List<PlatformId>> = buildMap {
        val values = linkedMapOf<String, MutableSet<PlatformId>>()
        document.platforms.forEach { platform ->
            (listOf(platform.folder.canonical) + platform.folder.aliases).forEach { match ->
                values.getOrPut(match.normalizedSegment) { linkedSetOf() } += platform.id
            }
        }
        document.importBindings.forEach { binding ->
            binding.folderMatches.forEach { match ->
                values.getOrPut(match.normalizedSegment) { linkedSetOf() } += binding.platformId
            }
        }
        values.forEach { (segment, platformIds) ->
            put(segment, immutableList(platformIds.sortedBy { it.value }))
        }
    }.let(::immutableMap)

    private val extensions: Map<String, List<PlatformId>> = buildMap {
        val values = linkedMapOf<String, MutableSet<PlatformId>>()
        document.importBindings.forEach { binding ->
            val profile = profileById[binding.contentProfileId] ?: return@forEach
            profile.extensionRules.filter { it.launchEligible }.forEach { rule ->
                values.getOrPut(normalizeExtension(rule.extension)) { linkedSetOf() } += binding.platformId
            }
        }
        values.forEach { (extension, platformIds) ->
            put(extension, immutableList(platformIds.sortedBy { it.value }))
        }
    }.let(::immutableMap)

    private val routesByPlatform: Map<PlatformId, List<RuntimeRouteDefinition>> = document.launchRoutes
        .groupBy { it.platformId }
        .mapValues { (_, routes) ->
            immutableList(routes.sortedWith(compareBy({ it.precedence }, { it.id.value })))
        }
        .let(::immutableMap)

    private val slicesByPlatform: Map<PlatformId, List<SupportSliceDefinition>> = document.supportSlices
        .groupBy { it.platformId }
        .mapValues { (_, slices) -> immutableList(slices.sortedBy { it.id.value }) }
        .let(::immutableMap)

    private val searchIndex: Map<String, Set<PlatformId>>
    private val searchTermsByPlatform: Map<PlatformId, Set<String>>

    private val stableCertifications = immutableList(
        eligibleCertifications.sortedWith(
            compareBy({ it.supportSliceId.value }, { it.certificateId }),
        ),
    )
    private val certificationsByPlatform: Map<PlatformId, List<SupportCertification>> = stableCertifications
        .mapNotNull { certification ->
            sliceById[certification.supportSliceId]?.platformId?.let { it to certification }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, certifications) -> immutableList(certifications) }
        .let(::immutableMap)

    init {
        val tokenOwners = linkedMapOf<String, MutableSet<PlatformId>>()
        val termsByPlatform = linkedMapOf<PlatformId, MutableSet<String>>()
        fun index(platformId: PlatformId, value: String?) {
            value ?: return
            searchTokens(value).forEach { token ->
                tokenOwners.getOrPut(token) { linkedSetOf() } += platformId
                termsByPlatform.getOrPut(platformId) { linkedSetOf() } += token
            }
        }

        document.platforms.forEach { platform ->
            index(platform.id, platform.id.value)
            index(platform.id, platform.display.name)
            index(platform.id, platform.display.shortName)
            index(platform.id, platform.display.manufacturer)
            platform.display.searchTerms.forEach { index(platform.id, it) }
            platform.aliases.forEach { index(platform.id, it.normalizedValue) }
        }
        document.catalogEntries.forEach { catalog ->
            index(catalog.platformId, catalog.displayName)
            catalog.searchTerms.forEach { index(catalog.platformId, it) }
        }
        searchIndex = immutableMap(
            tokenOwners.mapValues { (_, platformIds) -> immutableSet(platformIds) },
        )
        searchTermsByPlatform = immutableMap(
            termsByPlatform.mapValues { (_, tokens) -> immutableSet(tokens) },
        )
    }

    override fun platforms(): List<PlatformDefinition> = platformList
    override fun definition(id: PlatformId): PlatformDefinition? = platformById[id]
    override fun catalogEntry(id: CatalogEntryId): CatalogEntryDefinition? = catalogById[id]
    override fun contentProfile(id: ContentProfileId): ContentProfileDefinition? = profileById[id]
    override fun importBinding(id: ImportBindingId): ImportBindingDefinition? = bindingById[id]
    override fun retroArchRegistration(id: RetroArchRegistrationId): RetroArchRegistrationDefinition? =
        registrationById[id]
    override fun emulatorFamily(id: EmulatorFamilyId): EmulatorFamilyDefinition? = familyById[id]
    override fun route(id: LaunchRouteId): RuntimeRouteDefinition? = routeById[id]
    override fun packageVariant(id: PackageVariantId): PackageVariantDefinition? = variantById[id]
    override fun firmwarePolicy(id: FirmwarePolicyId): FirmwarePolicyDefinition? = policyById[id]
    override fun visualAsset(key: VisualAssetKey): VisualAssetDefinition? = visualById[key]
    override fun supportSlice(id: SupportSliceId): SupportSliceDefinition? = sliceById[id]

    override fun canonicalize(namespace: IdentityNamespace, rawId: String): PlatformId? {
        if (rawId.isBlank()) return null
        val normalized = normalizeIdentity(rawId)
        if (normalized.isBlank()) return null
        return aliases[AliasKey(namespace, normalized)]
    }

    override fun search(query: String): List<PlatformSearchHit> {
        val tokens = searchTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val candidateSets = tokens.map { searchIndex[it].orEmpty() }
        if (candidateSets.any { it.isEmpty() }) return emptyList()
        val candidates = candidateSets.drop(1).fold(candidateSets.first().toSet()) { matches, next ->
            matches intersect next
        }
        return immutableList(
            candidates.sortedWith(
                compareBy(
                    { platformById[it]?.display?.name?.lowercase().orEmpty() },
                    { it.value },
                ),
            ).map { platformId ->
                PlatformSearchHit(
                    platformId = platformId,
                    displayName = platformById.getValue(platformId).display.name,
                    matchedTokens = immutableSet(tokens intersect searchTermsByPlatform[platformId].orEmpty()),
                )
            },
        )
    }

    override fun folderCandidates(segment: String): List<PlatformId> =
        folders[normalizeFolder(segment)].orEmpty()

    override fun extensionCandidates(extension: String): List<PlatformId> =
        extensions[normalizeExtension(extension)].orEmpty()

    override fun routes(id: PlatformId): List<RuntimeRouteDefinition> = routesByPlatform[id].orEmpty()

    override fun supportReport(id: PlatformId): PlatformSupportReport {
        val known = id in platformById
        val definitions = slicesByPlatform[id].orEmpty()
        val certifications = certificationsByPlatform[id].orEmpty()
        val coveredSlices = certifications.mapTo(linkedSetOf()) { it.supportSliceId }
        val coveredProfiles = coveredSlices.mapNotNullTo(linkedSetOf()) { sliceById[it]?.contentProfileId }
        val classes = certifications.mapTo(linkedSetOf()) { it.publicClass }
        val summaryClass = classes.maxByOrNull(::publicClassRank) ?: PublicSupportClass.CATALOG_ONLY
        val highestClaim = if (certifications.isNotEmpty()) {
            SupportClaimLevel.CERTIFIED
        } else {
            definitions.maxByOrNull { it.claimLevel.ordinal }?.claimLevel ?: SupportClaimLevel.CATALOG_ONLY
        }
        val diagnostics = validationReport.issues.filter {
            it.scope == ValidationScope.CERTIFICATION || it.scope == ValidationScope.PROJECTION
        }
        return PlatformSupportReport(
            platformId = id,
            knownPlatform = known,
            highestClaimLevel = highestClaim,
            publicSupportClass = summaryClass,
            certifiedClasses = immutableSet(classes),
            coveredSupportSliceIds = immutableSet(coveredSlices),
            coveredContentProfileIds = immutableSet(coveredProfiles),
            diagnostics = immutableList(diagnostics),
        )
    }

    override fun activation(id: PlatformId): CertifiedActivation? {
        val manageCertifications = certificationsByPlatform[id]
            .orEmpty()
            .filter { certification ->
                sliceById[certification.supportSliceId]?.kind == SupportSliceKind.MANAGE
            }
        if (manageCertifications.isEmpty()) return null
        val slices = manageCertifications.mapNotNull { sliceById[it.supportSliceId] }
        return CertifiedActivation(
            platformId = id,
            allowedSupportSliceIds = immutableSet(slices.mapTo(linkedSetOf()) { it.id }),
            allowedImportBindingIds = immutableSet(slices.flatMapTo(linkedSetOf()) { it.importBindingIds }),
            allowedContentProfileIds = immutableSet(slices.mapTo(linkedSetOf()) { it.contentProfileId }),
            certificationSnapshotDigest = certificationSnapshotDigest,
        )
    }

    override fun certifiedSlices(id: PlatformId): List<CertifiedSupportSlice> {
        val seen = linkedSetOf<String>()
        val slices = certificationsByPlatform[id].orEmpty().mapNotNull { certification ->
            val definition = sliceById[certification.supportSliceId]
            val routeId = definition?.launchRouteId
            val locator = definition?.locatorKind
            val envelope = certification.runtimeEnvelopeKey
            if (definition?.kind != SupportSliceKind.PLAY || routeId == null || locator == null || envelope == null) {
                return@mapNotNull null
            }
            val key = "${definition.id}:${PlatformCanonicalDigest.runtimeEnvelope(envelope)}"
            if (!seen.add(key)) return@mapNotNull null
            CertifiedSupportSlice(
                id = definition.id,
                platformId = definition.platformId,
                contentProfileId = definition.contentProfileId,
                launchRouteId = routeId,
                locatorKind = locator,
                runtimeEnvelopeKey = envelope,
                publicClass = certification.publicClass,
            )
        }
        return immutableList(slices)
    }

    override fun supportSliceDigest(id: SupportSliceId): String? =
        PlatformCanonicalDigest.supportSlice(document, id)

    companion object {
        fun create(
            document: PlatformRegistryDocument,
            certificationDocument: PlatformCertificationDocument? = null,
            projectionPolicy: ProjectionDigestPolicy = ProjectionDigestPolicy.KERNEL_ONLY,
        ): PlatformRegistryLoadResult = build(
            document = document,
            certificationDocument = certificationDocument,
            projectionPolicy = projectionPolicy,
        )

        internal fun build(
            document: PlatformRegistryDocument,
            certificationDocument: PlatformCertificationDocument?,
            projectionPolicy: ProjectionDigestPolicy,
            authoritativeDigest: String? = null,
            externalIssues: List<ValidationIssue> = emptyList(),
            forceCertificationClosed: Boolean = false,
        ): PlatformRegistryLoadResult {
            val snapshot = document.immutableSnapshot()
            val certificationSnapshot = certificationDocument?.immutableSnapshot()
            val projectionSnapshot = projectionPolicy.immutableSnapshot()
            val registryState = RegistryValidator.validate(snapshot)
            val fatalIssues = externalIssues.filter { it.scope == ValidationScope.INPUT } + registryState.issues
            if (fatalIssues.any { it.severity == ValidationSeverity.ERROR }) {
                val report = RegistryValidationReport(externalIssues + registryState.issues)
                return PlatformRegistryLoadResult(null, report)
            }

            val digest = authoritativeDigest ?: PlatformCanonicalDigest.registry(snapshot)
            val certificationState = CertificationValidator.validate(
                registry = snapshot,
                registryDigest = digest,
                document = certificationSnapshot,
            )
            val projectionState = ProjectionValidator.validate(snapshot, digest, projectionSnapshot)
            val certificationClosed = forceCertificationClosed || !projectionState.consistent
            val eligible = if (certificationClosed) emptyList() else certificationState.eligibleCertifications
            val report = RegistryValidationReport(
                externalIssues + registryState.issues + certificationState.issues + projectionState.issues,
            )
            val registry = ImmutablePlatformRegistry(
                document = snapshot,
                canonicalDigest = digest,
                validationReport = report,
                eligibleCertifications = eligible,
                certificationSnapshotDigest = certificationState.snapshotDigest,
            )
            return PlatformRegistryLoadResult(registry, report)
        }

    }
}

private fun publicClassRank(value: PublicSupportClass): Int = when (value) {
    PublicSupportClass.CATALOG_ONLY -> 0
    PublicSupportClass.LIBRARY_ONLY -> 1
    PublicSupportClass.EMULATOR_HANDOFF -> 2
    PublicSupportClass.DIRECT_PLAY -> 3
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(values.toList())

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
