package com.gruning.mimir.rewrite.core.platform

import java.io.InputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Parses either the canonical generated envelope or the flat programmatic kernel document. */
object PlatformRegistryLoader {
    fun load(
        registryJson: String,
        certificationJson: String? = null,
        projectionPolicy: ProjectionDigestPolicy = ProjectionDigestPolicy.KERNEL_ONLY,
    ): PlatformRegistryLoadResult {
        val root = parseObject(registryJson, "registry")
        if (root.first == null) {
            return PlatformRegistryLoadResult(null, RegistryValidationReport(root.second))
        }

        val registryDocument: PlatformRegistryDocument
        val authoritativeDigest: String?
        val inputIssues: List<ValidationIssue>
        if (GeneratedRegistryAdapter.isGeneratedEnvelope(root.first!!)) {
            val adaptation = GeneratedRegistryAdapter.adapt(registryJson, root.first!!)
            if (adaptation.document == null) {
                return PlatformRegistryLoadResult(
                    registry = null,
                    validationReport = RegistryValidationReport(root.second + adaptation.issues),
                )
            }
            registryDocument = adaptation.document
            authoritativeDigest = adaptation.authoritativeDigest
            inputIssues = root.second + adaptation.issues
        } else {
            try {
                registryDocument = RegistryJson.decodeFromString(registryJson)
                authoritativeDigest = null
                inputIssues = root.second
            } catch (exception: Exception) {
                return PlatformRegistryLoadResult(
                    registry = null,
                    validationReport = RegistryValidationReport(
                        root.second + parseIssue("registry", exception),
                    ),
                )
            }
        }

        val certification = parseCertification(certificationJson)
        return ImmutablePlatformRegistry.build(
            document = registryDocument,
            certificationDocument = certification.document,
            projectionPolicy = projectionPolicy,
            authoritativeDigest = authoritativeDigest,
            externalIssues = inputIssues + certification.issues,
            forceCertificationClosed = certificationJson != null && certification.document == null,
        )
    }

    fun load(
        registryInput: InputStream,
        certificationInput: InputStream? = null,
        projectionPolicy: ProjectionDigestPolicy = ProjectionDigestPolicy.KERNEL_ONLY,
    ): PlatformRegistryLoadResult = load(
        registryJson = registryInput.readBytes().toString(Charsets.UTF_8),
        certificationJson = certificationInput?.readBytes()?.toString(Charsets.UTF_8),
        projectionPolicy = projectionPolicy,
    )

    private data class ParsedCertification(
        val document: PlatformCertificationDocument?,
        val issues: List<ValidationIssue>,
    )

    private fun parseCertification(rawJson: String?): ParsedCertification {
        if (rawJson == null) return ParsedCertification(null, emptyList())
        val root = parseObject(rawJson, "certification")
        val objectRoot = root.first ?: return ParsedCertification(null, root.second)
        if (GeneratedRegistryAdapter.isGeneratedCertification(objectRoot)) {
            val adapted = GeneratedRegistryAdapter.adaptCertification(rawJson, objectRoot)
            return ParsedCertification(adapted.first, root.second + adapted.second)
        }
        return try {
            ParsedCertification(
                document = RegistryJson.decodeFromString(rawJson),
                issues = root.second,
            )
        } catch (exception: Exception) {
            ParsedCertification(
                document = null,
                issues = root.second + certificationParseIssue(exception),
            )
        }
    }

    private fun parseObject(
        rawJson: String,
        path: String,
    ): Pair<JsonObject?, List<ValidationIssue>> = try {
        RegistryJson.parseToJsonElement(rawJson).jsonObject to emptyList()
    } catch (exception: Exception) {
        null to listOf(parseIssue(path, exception))
    }

    private fun parseIssue(path: String, exception: Exception): ValidationIssue = error(
        ValidationScope.INPUT,
        ValidationCode.PARSE_ERROR,
        path,
        exception.message ?: exception::class.java.simpleName,
    )

    private fun certificationParseIssue(exception: Exception): ValidationIssue = error(
        ValidationScope.CERTIFICATION,
        ValidationCode.PARSE_ERROR,
        "certification",
        exception.message ?: exception::class.java.simpleName,
    )
}
