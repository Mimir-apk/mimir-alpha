package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.time.Instant

internal data class RegistryValidationState(
    val issues: List<ValidationIssue>,
)

internal data class CertificationValidationState(
    val issues: List<ValidationIssue>,
    val eligibleCertifications: List<SupportCertification>,
    val globallyConsistent: Boolean,
    val snapshotDigest: String,
)

internal data class ProjectionValidationState(
    val issues: List<ValidationIssue>,
    val consistent: Boolean,
)

internal object RegistryValidator {
    fun validate(document: PlatformRegistryDocument): RegistryValidationState {
        val issues = mutableListOf<ValidationIssue>()
        val platforms = document.platforms.associateBy { it.id }
        val catalogs = document.catalogEntries.associateBy { it.id }
        val profiles = document.contentProfiles.associateBy { it.id }
        val bindings = document.importBindings.associateBy { it.id }
        val registrations = document.retroArchRegistrations.associateBy { it.id }
        val families = document.emulatorFamilies.associateBy { it.id }
        val routes = document.launchRoutes.associateBy { it.id }
        val variants = document.packageVariants.associateBy { it.id }
        val policies = document.firmwarePolicies.associateBy { it.id }
        val visuals = document.visualAssets.associateBy { it.key }

        duplicateValues(issues, "platforms", document.platforms) { it.id.value }
        duplicateValues(issues, "catalogEntries", document.catalogEntries) { it.id.value }
        duplicateValues(issues, "contentProfiles", document.contentProfiles) { it.id.value }
        duplicateValues(issues, "importBindings", document.importBindings) { it.id.value }
        duplicateValues(issues, "retroArchRegistrations", document.retroArchRegistrations) { it.id.value }
        duplicateValues(issues, "emulatorFamilies", document.emulatorFamilies) { it.id.value }
        duplicateValues(issues, "launchRoutes", document.launchRoutes) { it.id.value }
        duplicateValues(issues, "packageVariants", document.packageVariants) { it.id.value }
        duplicateValues(issues, "firmwarePolicies", document.firmwarePolicies) { it.id.value }
        duplicateValues(issues, "visualAssets", document.visualAssets) { it.key.value }
        duplicateValues(issues, "supportSlices", document.supportSlices) { it.id.value }

        document.declaredCanonicalDigest?.let { declared ->
            validateDigest(issues, ValidationScope.REGISTRY, "declaredCanonicalDigest", declared)
            val actual = PlatformCanonicalDigest.registry(document)
            if (declared != actual) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.REGISTRY_DIGEST_MISMATCH,
                    "declaredCanonicalDigest",
                    "Declared digest $declared does not match canonical digest $actual",
                )
            }
        }

        validatePlatforms(document, platforms, catalogs, profiles, routes, visuals, issues)
        validateAliases(document, platforms, issues)
        validateCatalogs(document, platforms, visuals, issues)
        validateProfiles(document, platforms, issues)
        validateBindings(document, platforms, profiles, issues)
        validateFolders(document, issues)
        validateRegistrations(document, platforms, profiles, families, variants, issues)
        validateFamilies(document, variants, issues)
        validateRoutes(document, platforms, families, variants, registrations, issues)
        validateVariants(document, issues)
        validatePolicies(document, platforms, routes, variants, issues)
        validateVisuals(document, platforms, catalogs, issues)
        validateSlices(document, platforms, profiles, bindings, routes, variants, policies, issues)

        return RegistryValidationState(issues)
    }

    private fun validatePlatforms(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        catalogs: Map<com.gruning.mimir.rewrite.core.model.CatalogEntryId, CatalogEntryDefinition>,
        profiles: Map<com.gruning.mimir.rewrite.core.model.ContentProfileId, ContentProfileDefinition>,
        routes: Map<com.gruning.mimir.rewrite.core.model.LaunchRouteId, RuntimeRouteDefinition>,
        visuals: Map<com.gruning.mimir.rewrite.core.model.VisualAssetKey, VisualAssetDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.platforms.forEachIndexed { index, platform ->
            val path = "platforms[$index]"
            if (platform.id.value == "unknown") {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.RESERVED_PLATFORM_ID,
                    "$path.id",
                    "unknown is a recognition state, never a PlatformId",
                )
            }
            if (platform.id.value != normalizeIdentity(platform.id.value)) {
                issues += invalidNormalization("$path.id", platform.id.value, issues)
            }
            requireNonBlank(platform.display.name, "$path.display.name", issues)
            duplicateValues(issues, "$path.contentProfileIds", platform.contentProfileIds) { it.value }
            duplicateValues(issues, "$path.launchRouteIds", platform.launchRouteIds) { it.value }
            platform.contentProfileIds.forEach { profileId ->
                val profile = profiles[profileId]
                if (profile == null) {
                    issues += brokenReference("$path.contentProfileIds", "content profile $profileId", issues)
                } else if (profile.platformId != platform.id) {
                    issues += mismatch(
                        "$path.contentProfileIds",
                        "Content profile $profileId belongs to ${profile.platformId}, not ${platform.id}",
                    )
                }
            }
            platform.launchRouteIds.forEach { routeId ->
                val route = routes[routeId]
                if (route == null) {
                    issues += brokenReference("$path.launchRouteIds", "launch route $routeId", issues)
                } else if (route.platformId != platform.id) {
                    issues += mismatch(
                        "$path.launchRouteIds",
                        "Launch route $routeId belongs to ${route.platformId}, not ${platform.id}",
                    )
                }
            }
            platform.presentation.visualAssetKeys.forEach { key ->
                val visual = visuals[key]
                if (visual == null) {
                    issues += brokenReference("$path.presentation.visualAssetKeys", "visual asset $key", issues)
                } else {
                    val visualPlatform = visual.platformId
                        ?: visual.catalogEntryId?.let(catalogs::get)?.platformId
                    if (visualPlatform != null && visualPlatform != platform.id) {
                        issues += mismatch(
                            "$path.presentation.visualAssetKeys",
                            "Visual asset $key belongs to $visualPlatform, not ${platform.id}",
                        )
                    }
                }
            }
            platform.presentation.catalogEntryIds.forEach { catalogId ->
                val catalog = catalogs[catalogId]
                if (catalog == null) {
                    issues += brokenReference(
                        "$path.presentation.catalogEntryIds",
                        "catalog entry $catalogId",
                        issues,
                    )
                } else if (catalog.platformId != platform.id) {
                    issues += mismatch(
                        "$path.presentation.catalogEntryIds",
                        "Catalog entry $catalogId belongs to ${catalog.platformId}, not ${platform.id}",
                    )
                }
            }
        }

        document.contentProfiles.forEachIndexed { index, profile ->
            if (profile.platformId in platforms && profile.id !in platforms.getValue(profile.platformId).contentProfileIds) {
                issues += mismatch(
                    "contentProfiles[$index].platformId",
                    "Platform ${profile.platformId} does not link back to content profile ${profile.id}",
                )
            }
        }
        document.launchRoutes.forEachIndexed { index, route ->
            if (route.platformId in platforms && route.id !in platforms.getValue(route.platformId).launchRouteIds) {
                issues += mismatch(
                    "launchRoutes[$index].platformId",
                    "Platform ${route.platformId} does not link back to launch route ${route.id}",
                )
            }
        }
    }

    private fun validateAliases(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        data class AliasKey(
            val namespace: IdentityNamespace,
            val normalizedValue: String,
            val idMapVersion: String,
        )

        val targets = mutableMapOf<AliasKey, PlatformId>()
        document.platforms.forEach { platform ->
            targets[AliasKey(IdentityNamespace.CANONICAL_PLATFORM, platform.id.value, document.idMapVersion.value)] =
                platform.id
        }

        document.platforms.forEachIndexed { platformIndex, platform ->
            duplicateValues(issues, "platforms[$platformIndex].aliases", platform.aliases) {
                "${it.namespace}:${it.normalizedValue}:${it.idMapVersion.value}"
            }
            platform.aliases.forEachIndexed { aliasIndex, alias ->
                val path = "platforms[$platformIndex].aliases[$aliasIndex]"
                requireNonBlank(alias.normalizedValue, "$path.normalizedValue", issues)
                if (alias.normalizedValue != normalizeIdentity(alias.normalizedValue)) {
                    issues += invalidNormalization("$path.normalizedValue", alias.normalizedValue, issues)
                }
                if (alias.idMapVersion != document.idMapVersion) {
                    issues += mismatch(
                        "$path.idMapVersion",
                        "Alias uses ${alias.idMapVersion}; registry uses ${document.idMapVersion}",
                    )
                }
                if (alias.targetId !in platforms) {
                    issues += brokenReference("$path.targetId", "platform ${alias.targetId}", issues)
                } else if (alias.targetId != platform.id) {
                    issues += mismatch(
                        "$path.targetId",
                        "Alias is stored on ${platform.id} but targets ${alias.targetId}",
                    )
                }
                val key = AliasKey(alias.namespace, alias.normalizedValue, alias.idMapVersion.value)
                val existing = targets.putIfAbsent(key, alias.targetId)
                if (existing != null && existing != alias.targetId) {
                    issues += error(
                        ValidationScope.REGISTRY,
                        ValidationCode.ALIAS_COLLISION,
                        path,
                        "${alias.namespace}:${alias.normalizedValue} targets both $existing and ${alias.targetId}",
                    )
                }
            }
        }
        document.retroArchRegistrations.forEachIndexed { index, registration ->
            val normalized = normalizeIdentity(registration.id.value)
            val key = AliasKey(
                IdentityNamespace.RETROARCH_REGISTRATION,
                normalized,
                document.idMapVersion.value,
            )
            val existing = targets.putIfAbsent(key, registration.platformId)
            if (existing != null && existing != registration.platformId) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.ALIAS_COLLISION,
                    "retroArchRegistrations[$index].id",
                    "RetroArch registration '$normalized' targets both $existing and ${registration.platformId}",
                )
            }
        }
    }

    private fun validateCatalogs(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        visuals: Map<com.gruning.mimir.rewrite.core.model.VisualAssetKey, VisualAssetDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.catalogEntries.forEachIndexed { index, catalog ->
            val path = "catalogEntries[$index]"
            if (catalog.platformId !in platforms) {
                issues += brokenReference("$path.platformId", "platform ${catalog.platformId}", issues)
            }
            requireNonBlank(catalog.displayName, "$path.displayName", issues)
            catalog.visualAssetKeys.forEach { key ->
                if (key !in visuals) {
                    issues += brokenReference("$path.visualAssetKeys", "visual asset $key", issues)
                }
            }
        }
    }

    private fun validateProfiles(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.contentProfiles.forEachIndexed { index, profile ->
            val path = "contentProfiles[$index]"
            if (profile.platformId !in platforms) {
                issues += brokenReference("$path.platformId", "platform ${profile.platformId}", issues)
            }
            requireNonBlank(profile.parserVersion, "$path.parserVersion", issues)
            duplicateValues(issues, "$path.acceptedLocators", profile.acceptedLocators) { it.name }
            duplicateValues(issues, "$path.extensionRules", profile.extensionRules) {
                "${normalizeExtension(it.extension)}:${it.role}"
            }
            profile.extensionRules.forEachIndexed { ruleIndex, rule ->
                val normalized = normalizeExtension(rule.extension)
                if (normalized.isBlank() || rule.extension != normalized) {
                    issues += invalidNormalization(
                        "$path.extensionRules[$ruleIndex].extension",
                        rule.extension,
                        issues,
                    )
                }
            }
        }
    }

    private fun validateBindings(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        profiles: Map<com.gruning.mimir.rewrite.core.model.ContentProfileId, ContentProfileDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.importBindings.forEachIndexed { index, binding ->
            val path = "importBindings[$index]"
            if (binding.platformId !in platforms) {
                issues += brokenReference("$path.platformId", "platform ${binding.platformId}", issues)
            }
            val profile = profiles[binding.contentProfileId]
            if (profile == null) {
                issues += brokenReference("$path.contentProfileId", "content profile ${binding.contentProfileId}", issues)
            } else if (profile.platformId != binding.platformId) {
                issues += mismatch(
                    "$path.contentProfileId",
                    "Binding platform ${binding.platformId} differs from profile platform ${profile.platformId}",
                )
            }
            if (binding.folderMatches.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_CONTENT_PROFILE,
                    "$path.folderMatches",
                    "An import binding must declare at least one exact folder match",
                )
            }
        }
    }

    private fun validateFolders(
        document: PlatformRegistryDocument,
        issues: MutableList<ValidationIssue>,
    ) {
        data class OwnerMatch(val owner: PlatformId, val match: FolderMatch, val path: String)

        val matches = buildList {
            document.platforms.forEachIndexed { platformIndex, platform ->
                add(OwnerMatch(platform.id, platform.folder.canonical, "platforms[$platformIndex].folder.canonical"))
                platform.folder.aliases.forEachIndexed { aliasIndex, match ->
                    add(OwnerMatch(platform.id, match, "platforms[$platformIndex].folder.aliases[$aliasIndex]"))
                }
            }
            document.importBindings.forEachIndexed { bindingIndex, binding ->
                binding.folderMatches.forEachIndexed { matchIndex, match ->
                    add(OwnerMatch(binding.platformId, match, "importBindings[$bindingIndex].folderMatches[$matchIndex]"))
                }
            }
            document.catalogEntries.forEachIndexed { catalogIndex, catalog ->
                catalog.folder?.let { folder ->
                    add(OwnerMatch(catalog.platformId, folder.canonical, "catalogEntries[$catalogIndex].folder.canonical"))
                    folder.aliases.forEachIndexed { aliasIndex, match ->
                        add(
                            OwnerMatch(
                                catalog.platformId,
                                match,
                                "catalogEntries[$catalogIndex].folder.aliases[$aliasIndex]",
                            ),
                        )
                    }
                }
            }
        }

        matches.forEach { ownerMatch ->
            val normalized = normalizeFolder(ownerMatch.match.normalizedSegment)
            if (normalized.isBlank() || normalized != ownerMatch.match.normalizedSegment) {
                issues += invalidNormalization(
                    "${ownerMatch.path}.normalizedSegment",
                    ownerMatch.match.normalizedSegment,
                    issues,
                )
            }
            if (ownerMatch.match.ambiguityGroup != null && ownerMatch.match.ambiguityGroup.isBlank()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.BLANK_FIELD,
                    "${ownerMatch.path}.ambiguityGroup",
                    "ambiguityGroup must be null or non-blank",
                )
            }
        }

        matches.groupBy { it.match.normalizedSegment }.forEach { (segment, group) ->
            val ownerGroups = group.groupBy { it.owner }
            if (ownerGroups.size <= 1) return@forEach
            val ambiguityGroups = group.map { it.match.ambiguityGroup }.toSet()
            val explicitlyAmbiguous = ambiguityGroups.size == 1 && ambiguityGroups.single() != null
            if (!explicitlyAmbiguous) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.FOLDER_COLLISION,
                    group.first().path,
                    "Folder '$segment' is claimed by ${ownerGroups.keys.sortedBy { it.value }} without one shared ambiguityGroup",
                )
            }
        }
    }

    private fun validateRegistrations(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        profiles: Map<com.gruning.mimir.rewrite.core.model.ContentProfileId, ContentProfileDefinition>,
        families: Map<com.gruning.mimir.rewrite.core.model.EmulatorFamilyId, EmulatorFamilyDefinition>,
        variants: Map<com.gruning.mimir.rewrite.core.model.PackageVariantId, PackageVariantDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.retroArchRegistrations.forEachIndexed { index, registration ->
            val path = "retroArchRegistrations[$index]"
            if (registration.platformId !in platforms) {
                issues += brokenReference("$path.platformId", "platform ${registration.platformId}", issues)
            }
            val profile = profiles[registration.contentProfileId]
            if (profile == null) {
                issues += brokenReference("$path.contentProfileId", "content profile ${registration.contentProfileId}", issues)
            } else if (profile.platformId != registration.platformId) {
                issues += mismatch(
                    "$path.contentProfileId",
                    "Registration platform ${registration.platformId} differs from profile platform ${profile.platformId}",
                )
            }
            val family = registration.emulatorFamilyId?.let(families::get)
            if (registration.emulatorFamilyId != null && family == null) {
                issues += brokenReference("$path.emulatorFamilyId", "emulator family ${registration.emulatorFamilyId}", issues)
            } else if (family != null && family.kind != EmulatorFamilyKind.RETROARCH) {
                issues += mismatch("$path.emulatorFamilyId", "RetroArch registration references non-RetroArch family ${family.id}")
            }
            requireNonBlank(registration.label, "$path.label", issues)
            if (registration.coreRegistrations.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_RUNTIME_ROUTE,
                    "$path.coreRegistrations",
                    "A RetroArch registration must preserve at least one core registration",
                )
            }
            duplicateValues(issues, "$path.coreRegistrations", registration.coreRegistrations) { it.id }
            registration.coreRegistrations.forEachIndexed { coreIndex, core ->
                requireNonBlank(core.id, "$path.coreRegistrations[$coreIndex].id", issues)
                requireNonBlank(core.stem, "$path.coreRegistrations[$coreIndex].stem", issues)
                requireNonBlank(core.label, "$path.coreRegistrations[$coreIndex].label", issues)
            }
            registration.recommendedCore?.let { recommended ->
                if (registration.coreRegistrations.none { it.stem == recommended }) {
                    issues += mismatch(
                        "$path.recommendedCore",
                        "Recommended core '$recommended' is absent from core registrations",
                    )
                }
            }
            registration.packageVariantIds.forEach { variantId ->
                if (variantId !in variants) {
                    issues += brokenReference("$path.packageVariantIds", "package variant $variantId", issues)
                } else if (family != null && variantId !in family.packageVariantIds) {
                    issues += mismatch(
                        "$path.packageVariantIds",
                        "Package variant $variantId is not part of emulator family ${family.id}",
                    )
                }
            }
        }
    }

    private fun validateFamilies(
        document: PlatformRegistryDocument,
        variants: Map<com.gruning.mimir.rewrite.core.model.PackageVariantId, PackageVariantDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.emulatorFamilies.forEachIndexed { index, family ->
            val path = "emulatorFamilies[$index]"
            requireNonBlank(family.displayName, "$path.displayName", issues)
            duplicateValues(issues, "$path.packageVariantIds", family.packageVariantIds) { it.value }
            family.packageVariantIds.forEach { variantId ->
                if (variantId !in variants) {
                    issues += brokenReference("$path.packageVariantIds", "package variant $variantId", issues)
                }
            }
        }
    }

    private fun validateRoutes(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        families: Map<com.gruning.mimir.rewrite.core.model.EmulatorFamilyId, EmulatorFamilyDefinition>,
        variants: Map<com.gruning.mimir.rewrite.core.model.PackageVariantId, PackageVariantDefinition>,
        registrations: Map<com.gruning.mimir.rewrite.core.model.RetroArchRegistrationId, RetroArchRegistrationDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.launchRoutes.forEachIndexed { index, route ->
            val path = "launchRoutes[$index]"
            if (route.platformId !in platforms) {
                issues += brokenReference("$path.platformId", "platform ${route.platformId}", issues)
            }
            val family = families[route.emulatorFamilyId]
            if (family == null) {
                issues += brokenReference("$path.emulatorFamilyId", "emulator family ${route.emulatorFamilyId}", issues)
            } else {
                val expectedKind = when (route.kind) {
                    RuntimeRouteKind.RETROARCH -> EmulatorFamilyKind.RETROARCH
                    RuntimeRouteKind.STANDALONE -> EmulatorFamilyKind.STANDALONE
                    RuntimeRouteKind.EMULATOR_HANDOFF -> EmulatorFamilyKind.EMULATOR_HANDOFF
                }
                if (family.kind != expectedKind) {
                    issues += mismatch(
                        "$path.emulatorFamilyId",
                        "Route kind ${route.kind} cannot use family kind ${family.kind}",
                    )
                }
            }
            if (route.packageVariantIds.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_RUNTIME_ROUTE,
                    "$path.packageVariantIds",
                    "A route must contain at least one exact package variant",
                )
            }
            duplicateValues(issues, "$path.packageVariantIds", route.packageVariantIds) { it.value }
            route.packageVariantIds.forEach { variantId ->
                if (variantId !in variants) {
                    issues += brokenReference("$path.packageVariantIds", "package variant $variantId", issues)
                } else if (family != null && variantId !in family.packageVariantIds) {
                    issues += mismatch(
                        "$path.packageVariantIds",
                        "Package variant $variantId is not part of emulator family ${family.id}",
                    )
                }
            }
            if (route.kind == RuntimeRouteKind.RETROARCH && route.retroArchRegistrationIds.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_RUNTIME_ROUTE,
                    "$path.retroArchRegistrationIds",
                    "A RetroArch route must reference an exact registration",
                )
            }
            if (route.kind != RuntimeRouteKind.RETROARCH && route.retroArchRegistrationIds.isNotEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_RUNTIME_ROUTE,
                    "$path.retroArchRegistrationIds",
                    "Only RetroArch routes may reference RetroArch registrations",
                )
            }
            route.retroArchRegistrationIds.forEach { registrationId ->
                val registration = registrations[registrationId]
                if (registration == null) {
                    issues += brokenReference(
                        "$path.retroArchRegistrationIds",
                        "RetroArch registration $registrationId",
                        issues,
                    )
                } else if (registration.platformId != route.platformId) {
                    issues += mismatch(
                        "$path.retroArchRegistrationIds",
                        "Registration $registrationId belongs to ${registration.platformId}, not ${route.platformId}",
                    )
                }
            }
        }
    }

    private fun validateVariants(
        document: PlatformRegistryDocument,
        issues: MutableList<ValidationIssue>,
    ) {
        document.packageVariants.forEachIndexed { index, variant ->
            val path = "packageVariants[$index]"
            requireNonBlank(variant.packageName, "$path.packageName", issues)
            if (variant.signerEnvelope.allowedCertificateSha256.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_PACKAGE_VARIANT,
                    "$path.signerEnvelope.allowedCertificateSha256",
                    "At least one exact signer digest is required",
                )
            }
            duplicateValues(
                issues,
                "$path.signerEnvelope.allowedCertificateSha256",
                variant.signerEnvelope.allowedCertificateSha256,
            ) { it }
            variant.signerEnvelope.allowedCertificateSha256.forEachIndexed { signerIndex, digest ->
                validateDigest(
                    issues,
                    ValidationScope.REGISTRY,
                    "$path.signerEnvelope.allowedCertificateSha256[$signerIndex]",
                    digest,
                )
            }
            val version = variant.versionComponentEnvelope
            if (version.minimumVersionCode < 0 ||
                (version.maximumVersionCode != null && version.maximumVersionCode < version.minimumVersionCode)
            ) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_PACKAGE_VARIANT,
                    "$path.versionComponentEnvelope",
                    "Version bounds are invalid",
                )
            }
            if (version.componentPolicy != ComponentPolicy.PACKAGE_DEFAULT && version.activityCandidates.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_PACKAGE_VARIANT,
                    "$path.versionComponentEnvelope.activityCandidates",
                    "The component policy requires declared activity candidates",
                )
            }
            if (variant.acceptedLocators.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_PACKAGE_VARIANT,
                    "$path.acceptedLocators",
                    "At least one ordered locator kind is required",
                )
            }
            duplicateValues(issues, "$path.acceptedLocators", variant.acceptedLocators) { it.name }
            validateIntent(variant, path, issues)
            validateRequirements(variant.requirements, "$path.requirements", issues)
        }
    }

    private fun validateIntent(
        variant: PackageVariantDefinition,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        val intent = variant.intent
        requireNonBlank(intent.action, "$path.intent.action", issues)
        intent.mimeType?.let { mime -> requireNonBlank(mime, "$path.intent.mimeType", issues) }
        duplicateValues(issues, "$path.intent.extras", intent.extras) { it.name }
        intent.extras.forEachIndexed { index, extra ->
            requireNonBlank(extra.name, "$path.intent.extras[$index].name", issues)
        }
        val onlyNoContent = variant.acceptedLocators.all { it == ContentLocatorKind.NONE }
        if (intent.dataMode == IntentDataMode.NO_CONTENT && !onlyNoContent) {
            issues += error(
                ValidationScope.REGISTRY,
                ValidationCode.INVALID_PACKAGE_VARIANT,
                "$path.intent.dataMode",
                "NO_CONTENT requires acceptedLocators=[NONE]",
            )
        }
        if (intent.dataMode != IntentDataMode.NO_CONTENT && ContentLocatorKind.NONE in variant.acceptedLocators) {
            issues += error(
                ValidationScope.REGISTRY,
                ValidationCode.INVALID_PACKAGE_VARIANT,
                "$path.acceptedLocators",
                "NONE cannot be mixed into a content-dispatch locator contract",
            )
        }
        if (onlyNoContent && intent.uriGrantPolicy != UriGrantPolicy.NONE) {
            issues += error(
                ValidationScope.REGISTRY,
                ValidationCode.INVALID_PACKAGE_VARIANT,
                "$path.intent.uriGrantPolicy",
                "A no-content handoff cannot grant a content URI",
            )
        }
    }

    private fun validatePolicies(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        routes: Map<com.gruning.mimir.rewrite.core.model.LaunchRouteId, RuntimeRouteDefinition>,
        variants: Map<com.gruning.mimir.rewrite.core.model.PackageVariantId, PackageVariantDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.firmwarePolicies.forEachIndexed { index, policy ->
            val path = "firmwarePolicies[$index]"
            if (policy.platformId !in platforms) {
                issues += brokenReference("$path.platformId", "platform ${policy.platformId}", issues)
            }
            policy.launchRouteId?.let { routeId ->
                val route = routes[routeId]
                if (route == null) {
                    issues += brokenReference("$path.launchRouteId", "launch route $routeId", issues)
                } else if (route.platformId != policy.platformId) {
                    issues += mismatch(
                        "$path.launchRouteId",
                        "Policy platform ${policy.platformId} differs from route platform ${route.platformId}",
                    )
                }
            }
            policy.packageVariantId?.let { variantId ->
                if (variantId !in variants) {
                    issues += brokenReference("$path.packageVariantId", "package variant $variantId", issues)
                }
                policy.launchRouteId?.let { routeId ->
                    val route = routes[routeId]
                    if (route != null && variantId !in route.packageVariantIds) {
                        issues += mismatch(
                            "$path.packageVariantId",
                            "Package variant $variantId is not part of route $routeId",
                        )
                    }
                }
            }
            if (policy.requirements.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_FIRMWARE_POLICY,
                    "$path.requirements",
                    "A firmware policy must contain at least one requirement",
                )
            }
            validateRequirements(policy.requirements, "$path.requirements", issues)
        }
    }

    private fun validateRequirements(
        requirements: List<RuntimeRequirement>,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        duplicateValues(issues, path, requirements) { it.id }
        requirements.forEachIndexed { index, requirement ->
            val requirementPath = "$path[$index]"
            requireNonBlank(requirement.id, "$requirementPath.id", issues)
            requireNonBlank(requirement.displayCopy, "$requirementPath.displayCopy", issues)
            if (requirement.detectionMethods.isEmpty()) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_FIRMWARE_POLICY,
                    "$requirementPath.detectionMethods",
                    "A requirement must declare how presence is detected or why it is unverifiable",
                )
            }
            if (requirement.validationAuthority == ValidationAuthority.TARGET_AT_DISPATCH &&
                DetectionMethod.UNVERIFIABLE_PRIVATE_STORAGE !in requirement.detectionMethods
            ) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_FIRMWARE_POLICY,
                    "$requirementPath.validationAuthority",
                    "TARGET_AT_DISPATCH requires an explicit unverifiable-storage detection limit",
                )
            }
            requirement.acceptableSha256.forEachIndexed { digestIndex, digest ->
                validateDigest(
                    issues,
                    ValidationScope.REGISTRY,
                    "$requirementPath.acceptableSha256[$digestIndex]",
                    digest,
                )
            }
        }
    }

    private fun validateVisuals(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        catalogs: Map<com.gruning.mimir.rewrite.core.model.CatalogEntryId, CatalogEntryDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.visualAssets.forEachIndexed { index, visual ->
            val path = "visualAssets[$index]"
            requireNonBlank(visual.resourceName, "$path.resourceName", issues)
            requireNonBlank(visual.provenance, "$path.provenance", issues)
            if (visual.platformId != null && visual.catalogEntryId != null) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.NAMESPACE_TARGET_MISMATCH,
                    path,
                    "A visual asset has one owner namespace, not both platform and catalog",
                )
            }
            visual.platformId?.let { platformId ->
                if (platformId !in platforms) {
                    issues += brokenReference("$path.platformId", "platform $platformId", issues)
                }
            }
            visual.catalogEntryId?.let { catalogId ->
                if (catalogId !in catalogs) {
                    issues += brokenReference("$path.catalogEntryId", "catalog entry $catalogId", issues)
                }
            }
        }
    }

    private fun validateSlices(
        document: PlatformRegistryDocument,
        platforms: Map<com.gruning.mimir.rewrite.core.model.PlatformId, PlatformDefinition>,
        profiles: Map<com.gruning.mimir.rewrite.core.model.ContentProfileId, ContentProfileDefinition>,
        bindings: Map<com.gruning.mimir.rewrite.core.model.ImportBindingId, ImportBindingDefinition>,
        routes: Map<com.gruning.mimir.rewrite.core.model.LaunchRouteId, RuntimeRouteDefinition>,
        variants: Map<com.gruning.mimir.rewrite.core.model.PackageVariantId, PackageVariantDefinition>,
        policies: Map<com.gruning.mimir.rewrite.core.model.FirmwarePolicyId, FirmwarePolicyDefinition>,
        issues: MutableList<ValidationIssue>,
    ) {
        document.supportSlices.forEachIndexed { index, slice ->
            val path = "supportSlices[$index]"
            if (slice.platformId !in platforms) {
                issues += brokenReference("$path.platformId", "platform ${slice.platformId}", issues)
            }
            val profile = profiles[slice.contentProfileId]
            if (profile == null) {
                issues += brokenReference("$path.contentProfileId", "content profile ${slice.contentProfileId}", issues)
            } else if (profile.platformId != slice.platformId) {
                issues += mismatch(
                    "$path.contentProfileId",
                    "Slice platform ${slice.platformId} differs from profile platform ${profile.platformId}",
                )
            }
            if (profile != null && profile.groupingKind == null) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_SUPPORT_SLICE,
                    "$path.contentProfileId",
                    "An active support slice requires an explicit grouping contract",
                )
            }
            if (slice.claimLevel == SupportClaimLevel.CERTIFIED) {
                issues += error(
                    ValidationScope.REGISTRY,
                    ValidationCode.INVALID_SUPPORT_SLICE,
                    "$path.claimLevel",
                    "CERTIFIED is minted by a certificate and cannot be declared in registry data",
                )
            }
            slice.importBindingIds.forEach { bindingId ->
                val binding = bindings[bindingId]
                if (binding == null) {
                    issues += brokenReference("$path.importBindingIds", "import binding $bindingId", issues)
                } else if (binding.platformId != slice.platformId || binding.contentProfileId != slice.contentProfileId) {
                    issues += mismatch(
                        "$path.importBindingIds",
                        "Binding $bindingId does not match this exact platform/content profile",
                    )
                }
            }
            slice.requirementPolicyIds.forEach { policyId ->
                val policy = policies[policyId]
                if (policy == null) {
                    issues += brokenReference("$path.requirementPolicyIds", "firmware policy $policyId", issues)
                } else if (policy.platformId != slice.platformId) {
                    issues += mismatch(
                        "$path.requirementPolicyIds",
                        "Firmware policy $policyId belongs to ${policy.platformId}, not ${slice.platformId}",
                    )
                }
            }

            when (slice.kind) {
                SupportSliceKind.MANAGE -> {
                    if (slice.launchRouteId != null || slice.locatorKind != null) {
                        issues += error(
                            ValidationScope.REGISTRY,
                            ValidationCode.INVALID_SUPPORT_SLICE,
                            path,
                            "MANAGE slices have no launch route or locator",
                        )
                    }
                    if (slice.importBindingIds.isEmpty()) {
                        issues += error(
                            ValidationScope.REGISTRY,
                            ValidationCode.INVALID_SUPPORT_SLICE,
                            "$path.importBindingIds",
                            "A MANAGE slice must authorize exact import bindings",
                        )
                    }
                }
                SupportSliceKind.PLAY -> {
                    val routeId = slice.launchRouteId
                    val locator = slice.locatorKind
                    if (routeId == null || locator == null) {
                        issues += error(
                            ValidationScope.REGISTRY,
                            ValidationCode.INVALID_SUPPORT_SLICE,
                            path,
                            "PLAY slices require one exact route and locator",
                        )
                    } else {
                        if (profile != null && locator !in profile.acceptedLocators) {
                            issues += error(
                                ValidationScope.REGISTRY,
                                ValidationCode.INVALID_SUPPORT_SLICE,
                                "$path.locatorKind",
                                "Content profile ${profile.id} does not accept locator $locator",
                            )
                        }
                        val route = routes[routeId]
                        if (route == null) {
                            issues += brokenReference("$path.launchRouteId", "launch route $routeId", issues)
                        } else {
                            if (route.platformId != slice.platformId) {
                                issues += mismatch(
                                    "$path.launchRouteId",
                                    "Route $routeId belongs to ${route.platformId}, not ${slice.platformId}",
                                )
                            }
                            if (route.kind == RuntimeRouteKind.RETROARCH) {
                                val matchingRegistration = route.retroArchRegistrationIds
                                    .mapNotNull { registrationId ->
                                        document.retroArchRegistrations.firstOrNull { it.id == registrationId }
                                    }
                                    .any { it.contentProfileId == slice.contentProfileId }
                                if (!matchingRegistration) {
                                    issues += error(
                                        ValidationScope.REGISTRY,
                                        ValidationCode.INVALID_SUPPORT_SLICE,
                                        "$path.contentProfileId",
                                        "RetroArch route $routeId has no registration for exact profile ${slice.contentProfileId}",
                                    )
                                }
                            }
                            val acceptsLocator = route.packageVariantIds
                                .mapNotNull(variants::get)
                                .any { locator in it.acceptedLocators }
                            if (!acceptsLocator) {
                                issues += error(
                                    ValidationScope.REGISTRY,
                                    ValidationCode.INVALID_SUPPORT_SLICE,
                                    "$path.locatorKind",
                                    "No package variant on route $routeId accepts $locator",
                                )
                            }
                            if (route.kind == RuntimeRouteKind.EMULATOR_HANDOFF && locator != ContentLocatorKind.NONE) {
                                issues += error(
                                    ValidationScope.REGISTRY,
                                    ValidationCode.INVALID_SUPPORT_SLICE,
                                    "$path.locatorKind",
                                    "EMULATOR_HANDOFF requires locator NONE",
                                )
                            }
                            if (route.kind != RuntimeRouteKind.EMULATOR_HANDOFF && locator == ContentLocatorKind.NONE) {
                                issues += error(
                                    ValidationScope.REGISTRY,
                                    ValidationCode.INVALID_SUPPORT_SLICE,
                                    "$path.locatorKind",
                                    "Content-dispatch routes cannot use locator NONE",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requireNonBlank(
        value: String,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (value.isBlank()) {
            issues += error(
                ValidationScope.REGISTRY,
                ValidationCode.BLANK_FIELD,
                path,
                "Value must not be blank",
            )
        }
    }

    private fun mismatch(path: String, message: String): ValidationIssue = error(
        ValidationScope.REGISTRY,
        ValidationCode.NAMESPACE_TARGET_MISMATCH,
        path,
        message,
    )

    private fun brokenReference(
        path: String,
        target: String,
        @Suppress("UNUSED_PARAMETER") issues: MutableList<ValidationIssue>,
    ): ValidationIssue = error(
        ValidationScope.REGISTRY,
        ValidationCode.BROKEN_REFERENCE,
        path,
        "Missing $target",
    )

    private fun invalidNormalization(
        path: String,
        value: String,
        @Suppress("UNUSED_PARAMETER") issues: MutableList<ValidationIssue>,
    ): ValidationIssue = error(
        ValidationScope.REGISTRY,
        ValidationCode.INVALID_NORMALIZATION,
        path,
        "'$value' is not in canonical normalized form",
    )
}

internal object CertificationValidator {
    fun validate(
        registry: PlatformRegistryDocument,
        registryDigest: String,
        document: PlatformCertificationDocument?,
        now: Instant = Instant.now(),
    ): CertificationValidationState {
        if (document == null) {
            return CertificationValidationState(
                issues = emptyList(),
                eligibleCertifications = emptyList(),
                globallyConsistent = true,
                snapshotDigest = PlatformCanonicalDigest.json("[]"),
            )
        }

        val issues = mutableListOf<ValidationIssue>()
        var globallyConsistent = true
        if (document.registryRevision != registry.registryRevision ||
            document.idMapVersion != registry.idMapVersion ||
            document.registryCanonicalDigest != registryDigest ||
            (document.registrySchemaVersion != null && document.registrySchemaVersion != registry.schemaVersion) ||
            (document.compatibilityFloor != null && document.compatibilityFloor != registry.compatibilityFloor)
        ) {
            globallyConsistent = false
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.CERTIFICATION_LEDGER_MISMATCH,
                "certification",
                "Certification metadata does not match the loaded registry revision, ID map, and digest",
            )
        }
        validateDigest(
            issues,
            ValidationScope.CERTIFICATION,
            "certification.registryCanonicalDigest",
            document.registryCanonicalDigest,
        )
        document.declaredCanonicalDigest?.let { declared ->
            validateDigest(
                issues,
                ValidationScope.CERTIFICATION,
                "certification.declaredCanonicalDigest",
                declared,
            )
            val actual = PlatformCanonicalDigest.certification(document)
            if (actual != declared) {
                globallyConsistent = false
                issues += error(
                    ValidationScope.CERTIFICATION,
                    ValidationCode.CERTIFICATION_DIGEST_MISMATCH,
                    "certification.declaredCanonicalDigest",
                    "Declared certification digest $declared does not match $actual",
                )
            }
        }

        val duplicateCertificateIds = document.certifications
            .groupBy { it.certificateId }
            .filterValues { it.size > 1 }
            .keys
        duplicateCertificateIds.forEach { duplicate ->
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.DUPLICATE_ID,
                "certification.certifications",
                "Duplicate certificate ID '$duplicate'",
            )
        }

        document.revokedRuntimeEnvelopeDigests.forEachIndexed { index, digest ->
            validateDigest(
                issues,
                ValidationScope.CERTIFICATION,
                "certification.revokedRuntimeEnvelopeDigests[$index]",
                digest,
            )
            if (!PlatformCanonicalDigest.isSha256(digest)) globallyConsistent = false
        }

        val slices = registry.supportSlices.associateBy { it.id }
        val routes = registry.launchRoutes.associateBy { it.id }
        val variants = registry.packageVariants.associateBy { it.id }
        val eligible = mutableListOf<SupportCertification>()

        document.certifications.forEachIndexed { index, certification ->
            val localIssues = mutableListOf<ValidationIssue>()
            val path = "certification.certifications[$index]"
            if (certification.certificateId.isBlank()) {
                localIssues += certificationError("$path.certificateId", "Certificate ID must not be blank")
            }
            if (certification.certificateId in duplicateCertificateIds) {
                localIssues += certificationError("$path.certificateId", "Certificate ID is duplicated")
            }
            validateDigest(localIssues, ValidationScope.CERTIFICATION, "$path.sliceDigest", certification.sliceDigest)
            certification.fixtureEvidence.forEachIndexed { evidenceIndex, evidence ->
                validateEvidence(evidence, "$path.fixtureEvidence[$evidenceIndex]", localIssues)
            }
            certification.deviceEvidence.forEachIndexed { evidenceIndex, evidence ->
                validateEvidence(evidence, "$path.deviceEvidence[$evidenceIndex]", localIssues)
            }
            if (certification.fixtureEvidence.isEmpty() || certification.deviceEvidence.isEmpty()) {
                localIssues += certificationError(
                    path,
                    "A support certificate requires fixture and device evidence",
                )
            }
            if (certification.engineVersions.isEmpty() || certification.engineVersions.any { it.key.isBlank() || it.value.isBlank() }) {
                localIssues += certificationError(
                    "$path.engineVersions",
                    "A support certificate must bind non-blank engine versions",
                )
            }
            if (certification.reviewAfter < certification.issuedAt) {
                localIssues += certificationError(
                    "$path.reviewAfter",
                    "reviewAfter cannot precede issuedAt",
                )
            }
            if (certification.channel == CertificationChannel.STABLE && certification.labsNotAfter != null) {
                localIssues += certificationError(
                    "$path.labsNotAfter",
                    "Stable support has no offline wall-clock expiry",
                )
            }
            if (certification.channel == CertificationChannel.LABS &&
                certification.labsNotAfter != null &&
                !certification.labsNotAfter.isAfter(now)
            ) {
                localIssues += certificationError(
                    "$path.labsNotAfter",
                    "Labs certificate has expired",
                )
            }

            val slice = slices[certification.supportSliceId]
            if (slice == null) {
                localIssues += error(
                    ValidationScope.CERTIFICATION,
                    ValidationCode.BROKEN_REFERENCE,
                    "$path.supportSliceId",
                    "Missing support slice ${certification.supportSliceId}",
                )
            } else {
                val expectedSliceDigest = PlatformCanonicalDigest.supportSlice(registry, slice.id)
                if (certification.sliceDigest != expectedSliceDigest) {
                    localIssues += error(
                        ValidationScope.CERTIFICATION,
                        ValidationCode.CERTIFICATION_DIGEST_MISMATCH,
                        "$path.sliceDigest",
                        "Certificate digest does not match exact support slice ${slice.id}",
                    )
                }
                when (slice.kind) {
                    SupportSliceKind.MANAGE -> validateManageCertificate(certification, slice, path, localIssues)
                    SupportSliceKind.PLAY -> validatePlayCertificate(
                        certification,
                        slice,
                        routes,
                        variants,
                        document.revokedRuntimeEnvelopeDigests,
                        path,
                        localIssues,
                    )
                }
            }

            issues += localIssues
            if (localIssues.isEmpty() &&
                globallyConsistent &&
                certification.channel == CertificationChannel.STABLE &&
                !certification.revoked
            ) {
                eligible += certification
            }
        }

        return CertificationValidationState(
            issues = issues,
            eligibleCertifications = if (globallyConsistent) eligible else emptyList(),
            globallyConsistent = globallyConsistent,
            snapshotDigest = PlatformCanonicalDigest.certification(document),
        )
    }

    private fun validateManageCertificate(
        certification: SupportCertification,
        slice: SupportSliceDefinition,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (certification.contractVersion != CertificationContractVersion.MANAGE_PLATFORM_V1 ||
            certification.publicClass != PublicSupportClass.LIBRARY_ONLY ||
            certification.runtimeEnvelopeKey != null
        ) {
            issues += certificationError(
                path,
                "MANAGE slices require MANAGE_PLATFORM_V1, LIBRARY_ONLY, and no runtime envelope",
            )
        }
        if (!slice.claimLevel.implies(SupportClaimLevel.RESOLVABLE)) {
            issues += certificationError(
                "$path.supportSliceId",
                "A MANAGE certificate requires RESOLVABLE closure",
            )
        }
    }

    private fun validatePlayCertificate(
        certification: SupportCertification,
        slice: SupportSliceDefinition,
        routes: Map<com.gruning.mimir.rewrite.core.model.LaunchRouteId, RuntimeRouteDefinition>,
        variants: Map<com.gruning.mimir.rewrite.core.model.PackageVariantId, PackageVariantDefinition>,
        revokedRuntimeEnvelopeDigests: Set<String>,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (certification.contractVersion != CertificationContractVersion.PLAY_PLATFORM_V1 ||
            certification.publicClass !in setOf(PublicSupportClass.EMULATOR_HANDOFF, PublicSupportClass.DIRECT_PLAY)
        ) {
            issues += certificationError(
                path,
                "PLAY slices require PLAY_PLATFORM_V1 and a handoff or direct public class",
            )
        }
        if (!slice.claimLevel.implies(SupportClaimLevel.LAUNCHABLE)) {
            issues += certificationError(
                "$path.supportSliceId",
                "A PLAY certificate requires LAUNCHABLE closure",
            )
        }
        val envelope = certification.runtimeEnvelopeKey
        if (envelope == null) {
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.INVALID_RUNTIME_ENVELOPE,
                "$path.runtimeEnvelopeKey",
                "PLAY certificates require an exact runtime envelope",
            )
            return
        }
        validateDigest(
            issues,
            ValidationScope.CERTIFICATION,
            "$path.runtimeEnvelopeKey.intentContractDigest",
            envelope.intentContractDigest,
        )
        validateDigest(
            issues,
            ValidationScope.CERTIFICATION,
            "$path.runtimeEnvelopeKey.signerVersionComponentDigest",
            envelope.signerVersionComponentDigest,
        )
        val route = slice.launchRouteId?.let(routes::get)
        val variant = variants[envelope.packageVariantId]
        if (route == null || envelope.packageVariantId !in route.packageVariantIds) {
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.INVALID_RUNTIME_ENVELOPE,
                "$path.runtimeEnvelopeKey.packageVariantId",
                "Package variant ${envelope.packageVariantId} is not on exact route ${slice.launchRouteId}",
            )
        }
        if (variant == null) {
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.BROKEN_REFERENCE,
                "$path.runtimeEnvelopeKey.packageVariantId",
                "Missing package variant ${envelope.packageVariantId}",
            )
            return
        }
        val expectedIntent = PlatformCanonicalDigest.intentContract(variant.intent)
        val expectedSignerVersion = PlatformCanonicalDigest.signerVersionComponent(
            variant.signerEnvelope,
            variant.versionComponentEnvelope,
        )
        if (envelope.intentContractDigest != expectedIntent ||
            envelope.signerVersionComponentDigest != expectedSignerVersion ||
            envelope.hostAbi != variant.hostAbi ||
            envelope.coreArtifactEnvelopeId != variant.coreArtifactEnvelopeId
        ) {
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.INVALID_RUNTIME_ENVELOPE,
                "$path.runtimeEnvelopeKey",
                "Runtime envelope does not exactly match package signer/version/component, intent, ABI, and core",
            )
        }
        if (slice.locatorKind !in variant.acceptedLocators) {
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.INVALID_RUNTIME_ENVELOPE,
                "$path.runtimeEnvelopeKey",
                "Package variant ${variant.id} does not accept locator ${slice.locatorKind}",
            )
        }
        if (certification.publicClass == PublicSupportClass.EMULATOR_HANDOFF &&
            (route?.kind != RuntimeRouteKind.EMULATOR_HANDOFF || slice.locatorKind != ContentLocatorKind.NONE)
        ) {
            issues += certificationError(
                "$path.publicClass",
                "EMULATOR_HANDOFF requires an exact handoff route with locator NONE",
            )
        }
        if (certification.publicClass == PublicSupportClass.DIRECT_PLAY &&
            (route?.kind == RuntimeRouteKind.EMULATOR_HANDOFF || slice.locatorKind == ContentLocatorKind.NONE)
        ) {
            issues += certificationError(
                "$path.publicClass",
                "DIRECT_PLAY requires an exact content-dispatch route and locator",
            )
        }
        val runtimeDigest = PlatformCanonicalDigest.runtimeEnvelope(envelope)
        if (runtimeDigest in revokedRuntimeEnvelopeDigests) {
            issues += error(
                ValidationScope.CERTIFICATION,
                ValidationCode.REVOKED_RUNTIME_ENVELOPE,
                "$path.runtimeEnvelopeKey",
                "Runtime envelope $runtimeDigest is revoked",
            )
        }
    }

    private fun validateEvidence(
        evidence: EvidenceRef,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (evidence.id.isBlank()) {
            issues += certificationError("$path.id", "Evidence ID must not be blank")
        }
        validateDigest(issues, ValidationScope.CERTIFICATION, "$path.sha256", evidence.sha256)
    }

    private fun certificationError(path: String, message: String): ValidationIssue = error(
        ValidationScope.CERTIFICATION,
        ValidationCode.INVALID_CERTIFICATION,
        path,
        message,
    )
}

internal object ProjectionValidator {
    fun validate(
        registry: PlatformRegistryDocument,
        registryDigest: String,
        policy: ProjectionDigestPolicy,
    ): ProjectionValidationState {
        val issues = mutableListOf<ValidationIssue>()
        var consistent = true
        val grouped = policy.manifests.groupBy { it.projectionId }
        grouped.filterValues { it.size > 1 }.forEach { (projectionId, _) ->
            consistent = false
            issues += error(
                ValidationScope.PROJECTION,
                ValidationCode.PROJECTION_DUPLICATE,
                "projections.$projectionId",
                "Projection '$projectionId' was reported more than once",
            )
        }
        val observed = grouped.keys
        (policy.requiredProjectionIds - observed).sorted().forEach { projectionId ->
            consistent = false
            issues += error(
                ValidationScope.PROJECTION,
                ValidationCode.PROJECTION_MISSING,
                "projections.$projectionId",
                "Required projection '$projectionId' is missing",
            )
        }

        val expectedSliceDigests = registry.supportSlices.associate { slice ->
            slice.id to checkNotNull(PlatformCanonicalDigest.supportSlice(registry, slice.id))
        }
        val sliceIds = expectedSliceDigests.keys
        policy.manifests.forEach { manifest ->
            val path = "projections.${manifest.projectionId}"
            if (manifest.projectionId.isBlank()) {
                consistent = false
                issues += error(
                    ValidationScope.PROJECTION,
                    ValidationCode.BLANK_FIELD,
                    "$path.projectionId",
                    "Projection ID must not be blank",
                )
            }
            if (manifest.registryRevision != registry.registryRevision ||
                manifest.registryCanonicalDigest != registryDigest
            ) {
                consistent = false
                issues += error(
                    ValidationScope.PROJECTION,
                    ValidationCode.PROJECTION_DIGEST_MISMATCH,
                    path,
                    "Projection revision/digest does not match the loaded registry",
                )
            }
            if (manifest.projectionId in policy.requiredProjectionIds) {
                (sliceIds - manifest.supportSliceDigests.keys).sortedBy { it.value }.forEach { sliceId ->
                    consistent = false
                    issues += error(
                        ValidationScope.PROJECTION,
                        ValidationCode.PROJECTION_MISSING,
                        "$path.supportSliceDigests.$sliceId",
                        "Required projection '${manifest.projectionId}' has no digest for support slice $sliceId",
                    )
                }
            }
            manifest.supportSliceDigests.forEach { (sliceId, observedDigest) ->
                if (sliceId !in sliceIds) {
                    consistent = false
                    issues += error(
                        ValidationScope.PROJECTION,
                        ValidationCode.UNKNOWN_PROJECTION_SLICE,
                        "$path.supportSliceDigests",
                        "Projection references unknown support slice $sliceId",
                    )
                } else {
                    val expected = expectedSliceDigests.getValue(sliceId)
                    if (observedDigest != expected) {
                        consistent = false
                        issues += error(
                            ValidationScope.PROJECTION,
                            ValidationCode.PROJECTION_SLICE_DIGEST_MISMATCH,
                            "$path.supportSliceDigests.$sliceId",
                            "Projection slice digest $observedDigest does not match $expected",
                        )
                    }
                }
            }
        }
        return ProjectionValidationState(issues, consistent)
    }
}

private fun <T> duplicateValues(
    issues: MutableList<ValidationIssue>,
    path: String,
    values: Collection<T>,
    key: (T) -> String,
) {
    values.groupBy(key).filterValues { it.size > 1 }.keys.sorted().forEach { duplicate ->
        issues += error(
            ValidationScope.REGISTRY,
            ValidationCode.DUPLICATE_ID,
            path,
            "Duplicate value '$duplicate'",
        )
    }
}

private fun validateDigest(
    issues: MutableList<ValidationIssue>,
    scope: ValidationScope,
    path: String,
    digest: String,
) {
    if (!PlatformCanonicalDigest.isSha256(digest)) {
        issues += error(
            scope,
            ValidationCode.INVALID_DIGEST,
            path,
            "Expected a lowercase SHA-256 digest",
        )
    }
}
