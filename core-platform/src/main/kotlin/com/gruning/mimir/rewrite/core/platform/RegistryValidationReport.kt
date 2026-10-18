package com.gruning.mimir.rewrite.core.platform

import java.util.Collections
import kotlinx.serialization.Serializable

@Serializable
enum class ValidationSeverity {
    ERROR,
    WARNING,
}

@Serializable
enum class ValidationScope {
    INPUT,
    REGISTRY,
    CERTIFICATION,
    PROJECTION,
}

@Serializable
enum class ValidationCode {
    PARSE_ERROR,
    DUPLICATE_ID,
    DUPLICATE_VALUE,
    RESERVED_PLATFORM_ID,
    BLANK_FIELD,
    INVALID_NORMALIZATION,
    INVALID_DIGEST,
    REGISTRY_DIGEST_MISMATCH,
    BROKEN_REFERENCE,
    NAMESPACE_TARGET_MISMATCH,
    ALIAS_COLLISION,
    FOLDER_COLLISION,
    INVALID_CONTENT_PROFILE,
    INVALID_RUNTIME_ROUTE,
    INVALID_PACKAGE_VARIANT,
    INVALID_FIRMWARE_POLICY,
    INVALID_SUPPORT_SLICE,
    CERTIFICATION_LEDGER_MISMATCH,
    CERTIFICATION_DIGEST_MISMATCH,
    INVALID_CERTIFICATION,
    INVALID_RUNTIME_ENVELOPE,
    REVOKED_RUNTIME_ENVELOPE,
    PROJECTION_MISSING,
    PROJECTION_DUPLICATE,
    PROJECTION_DIGEST_MISMATCH,
    PROJECTION_SLICE_DIGEST_MISMATCH,
    UNKNOWN_PROJECTION_SLICE,
}

@Serializable
data class ValidationIssue(
    val severity: ValidationSeverity,
    val scope: ValidationScope,
    val code: ValidationCode,
    val path: String,
    val message: String,
)

class RegistryValidationReport(issues: List<ValidationIssue>) {
    val issues: List<ValidationIssue> = Collections.unmodifiableList(
        issues.sortedWith(
            compareBy<ValidationIssue>(
                { it.severity.ordinal },
                { it.scope.ordinal },
                { it.code.name },
                { it.path },
                { it.message },
            ),
        ),
    )

    val errors: List<ValidationIssue> =
        Collections.unmodifiableList(this.issues.filter { it.severity == ValidationSeverity.ERROR })

    val warnings: List<ValidationIssue> =
        Collections.unmodifiableList(this.issues.filter { it.severity == ValidationSeverity.WARNING })

    val isValid: Boolean get() = errors.isEmpty()

    operator fun contains(code: ValidationCode): Boolean = issues.any { it.code == code }

    fun withAdditional(additional: List<ValidationIssue>): RegistryValidationReport =
        RegistryValidationReport(issues + additional)

    companion object {
        val VALID = RegistryValidationReport(emptyList())
    }
}

data class PlatformRegistryLoadResult(
    val registry: PlatformRegistry?,
    val validationReport: RegistryValidationReport,
) {
    fun requireRegistry(): PlatformRegistry = registry ?: throw RegistryValidationException(validationReport)
}

class RegistryValidationException(
    val report: RegistryValidationReport,
) : IllegalArgumentException(
    report.errors.joinToString(
        prefix = "Platform registry validation failed: ",
        separator = "; ",
    ) { "${it.code} at ${it.path}: ${it.message}" },
)

internal fun error(
    scope: ValidationScope,
    code: ValidationCode,
    path: String,
    message: String,
): ValidationIssue = ValidationIssue(ValidationSeverity.ERROR, scope, code, path, message)

internal fun warning(
    scope: ValidationScope,
    code: ValidationCode,
    path: String,
    message: String,
): ValidationIssue = ValidationIssue(ValidationSeverity.WARNING, scope, code, path, message)
