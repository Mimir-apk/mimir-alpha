package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MANIFEST_HASH_PATH
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MANIFEST_PATH
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_JSONL_LINE_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCancelledException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveValidator
import com.gruning.mimir.rewrite.core.model.recovery.ValidatedRecoveryArchive
import com.gruning.mimir.rewrite.core.model.recovery.validateCanonicalRecoveryJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class RecoveryArchiveSemanticReport(
    val schemaVersion: Int,
    val newerSchema: Boolean,
)

/**
 * Schema-aware second pass for a structurally valid archive. It keeps O(one JSONL line) memory by
 * requiring canonical primary-key order; duplicates and out-of-order rows therefore fail together.
 */
object RecoveryArchiveV1SemanticValidator {
    private val json = Json {
        isLenient = false
        explicitNulls = false
    }

    fun validate(
        archive: File,
        shouldContinue: () -> Boolean = { true },
    ): Result<RecoveryArchiveSemanticReport> =
        RecoveryArchiveValidator.validate(archive, shouldContinue).mapCatching {
            validateStructurallyValid(it, shouldContinue)
        }

    /**
     * Avoids hashing the whole archive twice when the caller has just completed structural validation.
     * The archive must remain in caller-owned storage for the duration of this second, typed pass.
     */
    fun validate(
        structural: ValidatedRecoveryArchive,
        shouldContinue: () -> Boolean = { true },
    ): Result<RecoveryArchiveSemanticReport> = runCatching {
        validateStructurallyValid(structural, shouldContinue)
    }

    private fun validateStructurallyValid(
        structural: ValidatedRecoveryArchive,
        shouldContinue: () -> Boolean,
    ): RecoveryArchiveSemanticReport {
        checkSemanticContinue(shouldContinue)
        val archive = structural.archive
        val schemaVersion = structural.manifest.schemaVersion
        // Reads the ceiling instead of restating it. Three places encoded "12" independently -- this one,
        // the range below, and the exporter's own gate -- and when the database moved to 13 only a human
        // could have kept them in step. Nobody did, so export threw for a week and this validator would
        // then have called Mimir's own fresh backup "from a newer build" if it had ever produced one.
        if (schemaVersion > RECOVERY_SCHEMA_MAX_VERSION_V1) {
            return RecoveryArchiveSemanticReport(
                schemaVersion = schemaVersion,
                newerSchema = true,
            )
        }
        if (schemaVersion !in RECOVERY_SCHEMA_MIN_VERSION_V1..RECOVERY_SCHEMA_MAX_VERSION_V1) {
            throw RecoveryArchiveException("Recovery Archive V1 has an unsupported Room schema.")
        }
        val specs = recoveryTableSpecsForSchema(schemaVersion).associateBy(RecoveryTableSpec::path)
        ZipInputStream(BufferedInputStream(FileInputStream(archive), 64 * 1024)).use { zip ->
            checkSemanticContinue(shouldContinue)
            requireEntry(zip, RECOVERY_MANIFEST_PATH)
            drainEntry(zip, shouldContinue)
            requireEntry(zip, RECOVERY_MANIFEST_HASH_PATH)
            drainEntry(zip, shouldContinue)
            structural.manifest.entries.forEach { expected ->
                checkSemanticContinue(shouldContinue)
                requireEntry(zip, expected.path)
                val spec = specs[expected.path]
                if (expected.path == RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH) {
                    RecoveryCompatibilityBundleValidator.validate(zip, shouldContinue)
                    zip.closeEntry()
                } else if (spec == null) {
                    drainEntry(zip, shouldContinue)
                } else {
                    validateTable(
                        zip = zip,
                        spec = spec,
                        expectedRows = expected.rowCount
                            ?: error("validated table row count absent"),
                        shouldContinue = shouldContinue,
                    )
                }
            }
            if (zip.nextEntry != null) {
                throw RecoveryArchiveException("Recovery archive contains an unlisted semantic payload.")
            }
        }
        return RecoveryArchiveSemanticReport(schemaVersion = schemaVersion, newerSchema = false)
    }

    private fun validateTable(
        zip: ZipInputStream,
        spec: RecoveryTableSpec,
        expectedRows: Long,
        shouldContinue: () -> Boolean,
    ) {
        val line = ByteArrayOutputStream()
        var rows = 0L
        var previousKey: List<PrimaryKeyValue>? = null
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkSemanticContinue(shouldContinue)
            val read = zip.read(buffer)
            if (read < 0) break
            repeat(read) { index ->
                val value = buffer[index].toInt() and 0xff
                line.write(value)
                if (line.size() > RECOVERY_MAX_JSONL_LINE_BYTES) {
                    throw RecoveryArchiveException(
                        "Recovery table ${spec.tableName} contains an oversized row.",
                    )
                }
                if (value == '\n'.code) {
                    checkSemanticContinue(shouldContinue)
                    val bytes = line.toByteArray()
                    val row = parseRow(bytes, bytes.size - 1, spec)
                    val currentKey = primaryKey(row, spec)
                    val prior = previousKey
                    if (prior != null && comparePrimaryKeys(prior, currentKey) >= 0) {
                        throw RecoveryArchiveException(
                            "Recovery table ${spec.tableName} has a duplicate or non-canonical primary key order.",
                        )
                    }
                    previousKey = currentKey
                    rows++
                    if (rows > expectedRows) {
                        throw RecoveryArchiveException(
                            "Recovery table ${spec.tableName} exceeds its declared row count.",
                        )
                    }
                    line.reset()
                }
            }
        }
        if (line.size() != 0 || rows != expectedRows) {
            throw RecoveryArchiveException(
                "Recovery table ${spec.tableName} row framing or count is invalid.",
            )
        }
        zip.closeEntry()
    }

    private fun parseRow(
        bytes: ByteArray,
        length: Int,
        spec: RecoveryTableSpec,
    ): JsonObject {
        val text = strictUtf8(bytes, length)
        val rawKeys = validateCanonicalRecoveryJsonObject(text)
        val row = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (error: SerializationException) {
            throw RecoveryArchiveException("Recovery table ${spec.tableName} has malformed JSON.", error)
        } ?: throw RecoveryArchiveException("Recovery table ${spec.tableName} row is not an object.")
        val allowed = spec.columns.mapTo(HashSet(), RecoveryColumnSpec::name)
        val unknown = rawKeys.toSet() - allowed
        if (unknown.isNotEmpty()) {
            throw RecoveryArchiveException(
                "Recovery table ${spec.tableName} contains unknown columns: ${unknown.sorted()}",
            )
        }
        val canonicalKeys = spec.columns
            .map(RecoveryColumnSpec::name)
            .filter(row::containsKey)
        if (rawKeys != canonicalKeys) {
            throw RecoveryArchiveException(
                "Recovery table ${spec.tableName} columns are not in canonical order.",
            )
        }
        spec.columns.forEach { column ->
            val value = row[column.name]
            if (value == null) {
                if (!column.nullable) {
                    throw RecoveryArchiveException(
                        "Recovery table ${spec.tableName} omits ${column.name}.",
                    )
                }
                return@forEach
            }
            if (value is JsonNull) {
                throw RecoveryArchiveException(
                    "Recovery table ${spec.tableName} uses a non-canonical explicit null.",
                )
            }
            val primitive = runCatching { value.jsonPrimitive }.getOrNull()
                ?: throw RecoveryArchiveException(
                    "Recovery table ${spec.tableName}.${column.name} is not a scalar.",
                )
            val valid = when (column.kind) {
                RecoveryColumnKind.TEXT -> primitive.isString
                RecoveryColumnKind.INTEGER -> !primitive.isString && primitive.longOrNull != null
                RecoveryColumnKind.BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null
            }
            if (!valid) {
                throw RecoveryArchiveException(
                    "Recovery table ${spec.tableName}.${column.name} has the wrong type.",
                )
            }
        }
        return row
    }

    private fun primaryKey(row: JsonObject, spec: RecoveryTableSpec): List<PrimaryKeyValue> =
        spec.primaryKey.map { name ->
            val column = spec.columns.first { it.name == name }
            val primitive = row[name]?.jsonPrimitive
                ?: throw RecoveryArchiveException("Recovery table ${spec.tableName} omits a primary key.")
            when (column.kind) {
                RecoveryColumnKind.TEXT -> PrimaryKeyValue.Text(
                    primitive.content.toByteArray(StandardCharsets.UTF_8),
                )
                RecoveryColumnKind.INTEGER -> PrimaryKeyValue.Integer(
                    primitive.longOrNull
                        ?: throw RecoveryArchiveException("Recovery table ${spec.tableName} has a bad primary key."),
                )
                RecoveryColumnKind.BOOLEAN ->
                    throw RecoveryArchiveException("Boolean recovery primary keys are unsupported.")
            }
        }

    private fun comparePrimaryKeys(
        left: List<PrimaryKeyValue>,
        right: List<PrimaryKeyValue>,
    ): Int {
        left.indices.forEach { index ->
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun requireEntry(zip: ZipInputStream, path: String) {
        val entry = zip.nextEntry
            ?: throw RecoveryArchiveException("Recovery archive is missing $path.")
        if (entry.name != path) {
            throw RecoveryArchiveException("Recovery archive semantic order mismatch.")
        }
    }

    private fun drainEntry(
        zip: ZipInputStream,
        shouldContinue: () -> Boolean,
    ) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkSemanticContinue(shouldContinue)
            if (zip.read(buffer) < 0) break
        }
        zip.closeEntry()
    }
}

private sealed interface PrimaryKeyValue : Comparable<PrimaryKeyValue> {
    data class Text(val bytes: ByteArray) : PrimaryKeyValue {
        override fun compareTo(other: PrimaryKeyValue): Int {
            if (other !is Text) {
                throw RecoveryArchiveException("Recovery table primary-key types are inconsistent.")
            }
            val length = minOf(bytes.size, other.bytes.size)
            repeat(length) { index ->
                val left = bytes[index].toInt() and 0xff
                val right = other.bytes[index].toInt() and 0xff
                if (left != right) return left.compareTo(right)
            }
            return bytes.size.compareTo(other.bytes.size)
        }

        override fun equals(other: Any?): Boolean =
            other is Text && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Integer(val value: Long) : PrimaryKeyValue {
        override fun compareTo(other: PrimaryKeyValue): Int =
            if (other is Integer) {
                value.compareTo(other.value)
            } else {
                throw RecoveryArchiveException("Recovery table primary-key types are inconsistent.")
            }
    }
}

private fun strictUtf8(bytes: ByteArray, length: Int): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    } catch (error: Exception) {
        throw RecoveryArchiveException("Recovery table row contains invalid UTF-8.", error)
    }
}

private fun checkSemanticContinue(shouldContinue: () -> Boolean) {
    if (!shouldContinue()) throw RecoveryArchiveCancelledException()
}
