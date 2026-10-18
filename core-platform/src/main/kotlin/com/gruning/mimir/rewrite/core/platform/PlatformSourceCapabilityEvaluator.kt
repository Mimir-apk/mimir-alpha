package com.gruning.mimir.rewrite.core.platform

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.security.MessageDigest

/**
 * Independent source facets projected into the canonical platform registry.
 *
 * These are observations, not certification authorities. [CERTIFICATION] may project
 * [SupportClaimLevel.CERTIFIED] only after [PlatformRegistry] has already validated the certification
 * ledger and reports that level for the platform.
 */
enum class PlatformSourceAuthority(
    internal val rawIdMustResolveThroughRegistry: Boolean,
    internal val allowedClaimLevels: Set<SupportClaimLevel>,
) {
    REGISTRY(true, setOf(SupportClaimLevel.CATALOG_ONLY)),
    SCANNER(true, setOf(SupportClaimLevel.DISCOVERABLE)),
    LAUNCH(
        true,
        setOf(SupportClaimLevel.RESOLVABLE, SupportClaimLevel.LAUNCHABLE),
    ),
    RETRO_ACHIEVEMENTS(false, setOf(SupportClaimLevel.RESOLVABLE)),
    SCREEN_SCRAPER(false, setOf(SupportClaimLevel.RESOLVABLE)),
    IGDB(false, setOf(SupportClaimLevel.RESOLVABLE)),
    BUNDLED_ART(true, setOf(SupportClaimLevel.CATALOG_ONLY)),
    CERTIFICATION(true, setOf(SupportClaimLevel.CERTIFIED)),
}

/** One immutable, primitive cross-authority observation. */
data class PlatformSourceCapabilityRow(
    val authority: PlatformSourceAuthority,
    val subjectId: String,
    val rawPlatformId: String,
    val declaredCanonicalPlatformId: String,
    val claimLevel: SupportClaimLevel,
)

enum class PlatformSourceContractIssueCode {
    BLANK_FIELD,
    DUPLICATE_SUBJECT_CONFLICT,
    UNKNOWN_CANONICAL_TARGET,
    ORPHAN_RAW_ID,
    AMBIGUOUS_RAW_ID,
    CANONICAL_MISMATCH,
    AUTHORITY_OVERCLAIM,
    LAUNCHABLE_WITHOUT_DISCOVERY,
    CERTIFIED_WITHOUT_VALIDATED_LEDGER,
    CERTIFIED_WITHOUT_LAUNCHABLE,
}

data class PlatformSourceContractIssue(
    val code: PlatformSourceContractIssueCode,
    val authority: PlatformSourceAuthority?,
    val subjectId: String?,
    val canonicalPlatformId: String?,
    val detail: String,
)

data class ResolvedPlatformSourceCapabilityRow(
    val source: PlatformSourceCapabilityRow,
    val canonicalPlatformId: String?,
)

data class PlatformSourceCapabilityStage(
    val canonicalPlatformId: String,
    val highestClaimLevel: SupportClaimLevel,
    val authorities: Set<PlatformSourceAuthority>,
)

data class PlatformSourceCapabilityReport(
    val rows: List<ResolvedPlatformSourceCapabilityRow>,
    val stages: List<PlatformSourceCapabilityStage>,
    val issues: List<PlatformSourceContractIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()

    /**
     * Stable length-prefixed text. It is intentionally independent of input ordering and locale so CI,
     * evidence packaging, and an adversarial shuffled-input run bind the same bytes.
     */
    fun canonicalText(): String = buildString {
        append("mimir-platform-source-capability.v1\n")
        rows.forEach { resolved ->
            val row = resolved.source
            append("R|")
            appendField(row.authority.name)
            appendField(row.subjectId)
            appendField(row.rawPlatformId)
            appendField(row.declaredCanonicalPlatformId)
            appendField(row.claimLevel.name)
            appendField(resolved.canonicalPlatformId.orEmpty())
            append('\n')
        }
        stages.forEach { stage ->
            append("S|")
            appendField(stage.canonicalPlatformId)
            appendField(stage.highestClaimLevel.name)
            appendField(stage.authorities.map { it.name }.sorted().joinToString(","))
            append('\n')
        }
        issues.forEach { issue ->
            append("I|")
            appendField(issue.code.name)
            appendField(issue.authority?.name.orEmpty())
            appendField(issue.subjectId.orEmpty())
            appendField(issue.canonicalPlatformId.orEmpty())
            appendField(issue.detail)
            append('\n')
        }
    }

    fun sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalText().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun StringBuilder.appendField(value: String) {
        append(value.length)
        append(':')
        append(value)
        append('|')
    }
}

/**
 * Pure cross-authority evaluator. It never reads files, devices, packages, providers, or certification
 * rows; callers inject already-grounded observations and a registry loaded through the normal validator.
 */
class PlatformSourceCapabilityEvaluator(
    private val registry: PlatformRegistry,
) {
    fun evaluate(inputRows: Collection<PlatformSourceCapabilityRow>): PlatformSourceCapabilityReport {
        val issues = mutableListOf<PlatformSourceContractIssue>()
        val invalidSubjects = linkedSetOf<SubjectKey>()

        val rows = inputRows
            .distinct()
            .sortedWith(rowComparator)

        rows.groupBy { SubjectKey(it.authority, it.subjectId) }
            .filterValues { group -> group.distinct().size > 1 }
            .forEach { (key, group) ->
                invalidSubjects += key
                issues += issue(
                    code = PlatformSourceContractIssueCode.DUPLICATE_SUBJECT_CONFLICT,
                    authority = key.authority,
                    subjectId = key.subjectId,
                    detail = group.joinToString(";") {
                        "${it.rawPlatformId}->${it.declaredCanonicalPlatformId}:${it.claimLevel.name}"
                    },
                )
            }

        val resolvedRows = rows.map { row ->
            val key = SubjectKey(row.authority, row.subjectId)
            val rowIssues = validateAndResolve(row)
            if (rowIssues.issues.isNotEmpty()) {
                invalidSubjects += key
                issues += rowIssues.issues
            }
            ResolvedPlatformSourceCapabilityRow(
                source = row,
                canonicalPlatformId = rowIssues.canonicalPlatformId,
            )
        }

        val validRows = resolvedRows.filter { resolved ->
            SubjectKey(resolved.source.authority, resolved.source.subjectId) !in invalidSubjects &&
                resolved.canonicalPlatformId != null
        }
        val rowsByPlatform = validRows.groupBy { requireNotNull(it.canonicalPlatformId) }
        val stages = rowsByPlatform.entries
            .sortedBy { it.key }
            .map { (canonicalId, platformRows) ->
                val hasDiscovery = platformRows.any {
                    it.source.authority == PlatformSourceAuthority.SCANNER &&
                        it.source.claimLevel.implies(SupportClaimLevel.DISCOVERABLE)
                }
                val hasLaunch = platformRows.any {
                    it.source.authority == PlatformSourceAuthority.LAUNCH &&
                        it.source.claimLevel.implies(SupportClaimLevel.LAUNCHABLE)
                }
                val hasCertified = platformRows.any {
                    it.source.authority == PlatformSourceAuthority.CERTIFICATION &&
                        it.source.claimLevel == SupportClaimLevel.CERTIFIED
                }
                val validatedLedgerLevel = if (hasCertified) {
                    registry.supportReport(PlatformId(canonicalId)).highestClaimLevel
                } else {
                    null
                }
                val certificationAccepted =
                    hasCertified &&
                        validatedLedgerLevel == SupportClaimLevel.CERTIFIED &&
                        hasLaunch

                if (hasLaunch && !hasDiscovery) {
                    issues += issue(
                        code = PlatformSourceContractIssueCode.LAUNCHABLE_WITHOUT_DISCOVERY,
                        canonicalPlatformId = canonicalId,
                        detail = "Direct-payload dispatch has no scanner discovery row",
                    )
                }
                if (hasCertified) {
                    if (validatedLedgerLevel != SupportClaimLevel.CERTIFIED) {
                        issues += issue(
                            code = PlatformSourceContractIssueCode.CERTIFIED_WITHOUT_VALIDATED_LEDGER,
                            canonicalPlatformId = canonicalId,
                            detail = "Validated registry reports ${validatedLedgerLevel?.name}",
                        )
                    }
                    if (!hasLaunch) {
                        issues += issue(
                            code = PlatformSourceContractIssueCode.CERTIFIED_WITHOUT_LAUNCHABLE,
                            canonicalPlatformId = canonicalId,
                            detail = "Certification has no direct-payload launch observation",
                        )
                    }
                }

                // A caller-supplied certification row is only an observation. It must never promote
                // the effective stage unless the normal registry validator already accepted the
                // ledger and the platform also has exact direct-payload launch evidence.
                val effectiveRows = platformRows.filterNot {
                    it.source.authority == PlatformSourceAuthority.CERTIFICATION &&
                        it.source.claimLevel == SupportClaimLevel.CERTIFIED &&
                        !certificationAccepted
                }
                val highest = effectiveRows
                    .maxOfOrNull { it.source.claimLevel }
                    ?: SupportClaimLevel.CATALOG_ONLY
                PlatformSourceCapabilityStage(
                    canonicalPlatformId = canonicalId,
                    highestClaimLevel = highest,
                    authorities = effectiveRows.mapTo(linkedSetOf()) { it.source.authority },
                )
            }

        return PlatformSourceCapabilityReport(
            rows = resolvedRows.sortedWith(resolvedRowComparator),
            stages = stages,
            issues = issues.sortedWith(issueComparator),
        )
    }

    private fun validateAndResolve(row: PlatformSourceCapabilityRow): RowResolution {
        val issues = mutableListOf<PlatformSourceContractIssue>()
        if (
            row.subjectId.isBlank() ||
            row.rawPlatformId.isBlank() ||
            row.declaredCanonicalPlatformId.isBlank()
        ) {
            issues += issue(
                code = PlatformSourceContractIssueCode.BLANK_FIELD,
                authority = row.authority,
                subjectId = row.subjectId,
                detail = "subjectId, rawPlatformId, and declaredCanonicalPlatformId must be non-blank",
            )
            return RowResolution(null, issues)
        }
        if (row.claimLevel !in row.authority.allowedClaimLevels) {
            issues += issue(
                code = PlatformSourceContractIssueCode.AUTHORITY_OVERCLAIM,
                authority = row.authority,
                subjectId = row.subjectId,
                canonicalPlatformId = row.declaredCanonicalPlatformId,
                detail = "${row.claimLevel.name} is not allowed; expected one of " +
                    row.authority.allowedClaimLevels.map { it.name }.sorted().joinToString(","),
            )
        }

        val declared = registry.canonicalize(
            IdentityNamespace.CANONICAL_PLATFORM,
            row.declaredCanonicalPlatformId,
        )
        if (declared == null) {
            issues += issue(
                code = PlatformSourceContractIssueCode.UNKNOWN_CANONICAL_TARGET,
                authority = row.authority,
                subjectId = row.subjectId,
                canonicalPlatformId = row.declaredCanonicalPlatformId,
                detail = "Declared target is absent from canonical registry space",
            )
            return RowResolution(null, issues)
        }

        val targets = IdentityNamespace.values()
            .mapNotNullTo(linkedSetOf()) { namespace ->
                registry.canonicalize(namespace, row.rawPlatformId)?.value
            }
        when {
            targets.isEmpty() && row.authority.rawIdMustResolveThroughRegistry -> issues += issue(
                code = PlatformSourceContractIssueCode.ORPHAN_RAW_ID,
                authority = row.authority,
                subjectId = row.subjectId,
                canonicalPlatformId = declared.value,
                detail = "Raw id does not resolve in any registry namespace",
            )

            // Provider catalogs may use a provider-local id absent from every registry namespace.
            // Once an id is known to the registry, however, it cannot contradict that identity.
            targets.size > 1 -> issues += issue(
                code = PlatformSourceContractIssueCode.AMBIGUOUS_RAW_ID,
                authority = row.authority,
                subjectId = row.subjectId,
                canonicalPlatformId = declared.value,
                detail = targets.sorted().joinToString(","),
            )

            targets.size == 1 && targets.single() != declared.value -> issues += issue(
                code = PlatformSourceContractIssueCode.CANONICAL_MISMATCH,
                authority = row.authority,
                subjectId = row.subjectId,
                canonicalPlatformId = declared.value,
                detail = "Raw id resolves to ${targets.single()}",
            )
        }
        return RowResolution(declared.value, issues)
    }

    private data class SubjectKey(
        val authority: PlatformSourceAuthority,
        val subjectId: String,
    )

    private data class RowResolution(
        val canonicalPlatformId: String?,
        val issues: List<PlatformSourceContractIssue>,
    )

    private companion object {
        val rowComparator = compareBy<PlatformSourceCapabilityRow>(
            { it.authority.name },
            { it.subjectId },
            { it.rawPlatformId },
            { it.declaredCanonicalPlatformId },
            { it.claimLevel.name },
        )
        val resolvedRowComparator = compareBy<ResolvedPlatformSourceCapabilityRow>(
            { it.source.authority.name },
            { it.source.subjectId },
            { it.source.rawPlatformId },
            { it.source.declaredCanonicalPlatformId },
            { it.source.claimLevel.name },
            { it.canonicalPlatformId.orEmpty() },
        )
        val issueComparator = compareBy<PlatformSourceContractIssue>(
            { it.code.name },
            { it.authority?.name.orEmpty() },
            { it.subjectId.orEmpty() },
            { it.canonicalPlatformId.orEmpty() },
            { it.detail },
        )

        fun issue(
            code: PlatformSourceContractIssueCode,
            authority: PlatformSourceAuthority? = null,
            subjectId: String? = null,
            canonicalPlatformId: String? = null,
            detail: String,
        ): PlatformSourceContractIssue = PlatformSourceContractIssue(
            code = code,
            authority = authority,
            subjectId = subjectId,
            canonicalPlatformId = canonicalPlatformId,
            detail = detail,
        )
    }
}
