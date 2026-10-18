package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.migration.BUNDLE_KIND
import com.gruning.mimir.rewrite.core.model.migration.CURRENT_BUNDLE_VERSION
import com.gruning.mimir.rewrite.core.model.migration.MigrationEmulatorOverride
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.migration.MigrationSourceRoot
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_JSONL_LINE_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_TABLE_ROWS
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCancelledException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.model.recovery.validateCanonicalRecoveryJsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bounded semantic validation for the required MigrationBundle compatibility payload.
 *
 * The top-level document and array order are canonical, while each record is decoded independently.
 * This keeps memory at O(one record) even when a recovery archive carries a very large library.
 */
internal object RecoveryCompatibilityBundleValidator {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun validate(
        input: InputStream,
        shouldContinue: () -> Boolean,
    ) {
        val reader = CanonicalRecoveryJsonReader(input, shouldContinue)
        reader.expect("{\"bundleVersion\":")
        if (reader.readCanonicalLong() != CURRENT_BUNDLE_VERSION.toLong()) {
            throw RecoveryArchiveException("Recovery compatibility bundle version is unsupported.")
        }
        reader.expect(",\"kind\":")
        if (reader.readString() != BUNDLE_KIND) {
            throw RecoveryArchiveException("Recovery compatibility bundle kind is invalid.")
        }
        reader.expect(",\"producedByApp\":")
        if (reader.readString().isBlank()) {
            throw RecoveryArchiveException("Recovery compatibility producer app is blank.")
        }
        reader.expect(",\"producedByVersion\":")
        if (reader.readString().isBlank()) {
            throw RecoveryArchiveException("Recovery compatibility producer version is blank.")
        }
        reader.expect(",\"producedAtMillis\":")
        if (reader.readCanonicalLong() <= 0L) {
            throw RecoveryArchiveException("Recovery compatibility timestamp is invalid.")
        }
        reader.expect(",\"sourceRoots\":")
        reader.readObjectArray(::validateSourceRoot)
        reader.expect(",\"favorites\":")
        reader.readObjectArray(::validateGame)
        reader.expect(",\"hidden\":")
        reader.readObjectArray(::validateGame)
        reader.expect(",\"emulatorOverrides\":")
        reader.readObjectArray(::validateOverride)
        reader.expect("}")
        reader.expectEnd()
    }

    private fun validateSourceRoot(bytes: ByteArray) {
        val row = parseObject(bytes, "source root")
        rejectExplicitNull(row, "source root")
        val expected = buildList {
            add("friendlyLabel")
            if ("treeUriHint" in row) add("treeUriHint")
            add("isPrimary")
        }
        requireKeys(row, expected, "source root")
        decode("source root") {
            json.decodeFromJsonElement(MigrationSourceRoot.serializer(), row)
        }
    }

    private fun validateGame(bytes: ByteArray) {
        validateGameObject(parseObject(bytes, "game"))
    }

    private fun validateGameObject(row: JsonObject): MigrationGameRecord {
        rejectExplicitNull(row, "game")
        val expected = buildList {
            add("platformId")
            add("fileName")
            add("sizeBytes")
            if ("relativePath" in row) add("relativePath")
            if ("contentHash" in row) add("contentHash")
            if ("serial" in row) add("serial")
            add("title")
            add("platformLabel")
        }
        requireKeys(row, expected, "game")
        val game = decode("game") {
            json.decodeFromJsonElement(MigrationGameRecord.serializer(), row)
        }
        if (game.platformId.isBlank() || game.fileName.isBlank() || game.sizeBytes < 0L) {
            throw RecoveryArchiveException("Recovery compatibility game identity is invalid.")
        }
        return game
    }

    private fun validateOverride(bytes: ByteArray) {
        val row = parseObject(bytes, "emulator override")
        requireKeys(row, listOf("game", "emulatorId"), "emulator override")
        val nested = row["game"] as? JsonObject
            ?: throw RecoveryArchiveException("Recovery compatibility override game is invalid.")
        validateGameObject(nested)
        val override = decode("emulator override") {
            json.decodeFromJsonElement(MigrationEmulatorOverride.serializer(), row)
        }
        if (override.emulatorId.isBlank()) {
            throw RecoveryArchiveException("Recovery compatibility emulator override is blank.")
        }
    }

    private fun parseObject(bytes: ByteArray, label: String): JsonObject {
        val text = strictCompatibilityUtf8(bytes)
        validateCanonicalRecoveryJsonObject(text)
        val element = try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw RecoveryArchiveException(
                "Recovery compatibility $label is malformed.",
                error,
            )
        }
        return element as? JsonObject
            ?: throw RecoveryArchiveException("Recovery compatibility $label is not an object.")
    }

    private fun rejectExplicitNull(row: JsonObject, label: String) {
        if (row.values.any { it is JsonNull }) {
            throw RecoveryArchiveException(
                "Recovery compatibility $label uses a non-canonical explicit null.",
            )
        }
    }

    private fun requireKeys(
        row: JsonObject,
        expected: List<String>,
        label: String,
    ) {
        if (row.keys.toList() != expected) {
            throw RecoveryArchiveException(
                "Recovery compatibility $label fields are missing, unknown, or out of order.",
            )
        }
    }

    private inline fun <T> decode(label: String, block: () -> T): T = try {
        block()
    } catch (error: SerializationException) {
        throw RecoveryArchiveException("Recovery compatibility $label has invalid field types.", error)
    } catch (error: IllegalArgumentException) {
        throw RecoveryArchiveException("Recovery compatibility $label has invalid values.", error)
    }
}

private class CanonicalRecoveryJsonReader(
    private val input: InputStream,
    private val shouldContinue: () -> Boolean,
) {
    private val buffer = ByteArray(64 * 1024)
    private var offset = 0
    private var limit = 0
    private var peeked = Unset

    fun expect(text: String) {
        text.toByteArray(StandardCharsets.US_ASCII).forEach { expected ->
            val actual = readByte()
            if (actual != (expected.toInt() and 0xff)) {
                throw RecoveryArchiveException("Recovery compatibility bundle is not canonical.")
            }
        }
    }

    fun readString(): String {
        val token = ByteArrayOutputStream()
        val opening = readByte()
        if (opening != '"'.code) {
            throw RecoveryArchiveException("Recovery compatibility string is malformed.")
        }
        token.write(opening)
        var escaped = false
        while (true) {
            val value = readByte()
            if (value < 0) {
                throw RecoveryArchiveException("Recovery compatibility string is truncated.")
            }
            token.write(value)
            if (token.size() > RECOVERY_MAX_JSONL_LINE_BYTES) {
                throw RecoveryArchiveException("Recovery compatibility string is oversized.")
            }
            if (!escaped && value == '"'.code) break
            if (!escaped && value < 0x20) {
                throw RecoveryArchiveException("Recovery compatibility string has a control byte.")
            }
            escaped = if (escaped) false else value == '\\'.code
        }
        val raw = strictCompatibilityUtf8(token.toByteArray())
        val element = try {
            Json.parseToJsonElement(raw)
        } catch (error: SerializationException) {
            throw RecoveryArchiveException("Recovery compatibility string is malformed.", error)
        }
        val primitive = element as? JsonPrimitive
            ?: throw RecoveryArchiveException("Recovery compatibility string is malformed.")
        if (!primitive.isString) {
            throw RecoveryArchiveException("Recovery compatibility string is malformed.")
        }
        if (JsonPrimitive(primitive.content).toString() != raw) {
            throw RecoveryArchiveException("Recovery compatibility string is not canonical.")
        }
        return primitive.content
    }

    fun readCanonicalLong(): Long {
        val token = StringBuilder()
        if (peekByte() == '-'.code) token.append(readByte().toChar())
        while (peekByte() in '0'.code..'9'.code) {
            token.append(readByte().toChar())
            if (token.length > 20) {
                throw RecoveryArchiveException("Recovery compatibility integer is oversized.")
            }
        }
        val raw = token.toString()
        val digits = raw.removePrefix("-")
        if (digits.isEmpty() || (digits.length > 1 && digits.startsWith('0')) || raw == "-0") {
            throw RecoveryArchiveException("Recovery compatibility integer is not canonical.")
        }
        return raw.toLongOrNull()
            ?: throw RecoveryArchiveException("Recovery compatibility integer is outside Long range.")
    }

    fun readObjectArray(consumer: (ByteArray) -> Unit) {
        if (readByte() != '['.code) {
            throw RecoveryArchiveException("Recovery compatibility array is malformed.")
        }
        if (peekByte() == ']'.code) {
            readByte()
            return
        }
        var count = 0L
        while (true) {
            checkContinue()
            consumer(readObject())
            count++
            if (count > RECOVERY_MAX_TABLE_ROWS) {
                throw RecoveryArchiveException("Recovery compatibility array exceeds the row limit.")
            }
            when (readByte()) {
                ','.code -> Unit
                ']'.code -> return
                else -> throw RecoveryArchiveException("Recovery compatibility array is malformed.")
            }
        }
    }

    fun expectEnd() {
        if (readByte() >= 0) {
            throw RecoveryArchiveException("Recovery compatibility bundle has trailing bytes.")
        }
    }

    private fun readObject(): ByteArray {
        val output = ByteArrayOutputStream()
        val first = readByte()
        if (first != '{'.code) {
            throw RecoveryArchiveException("Recovery compatibility array item is not an object.")
        }
        output.write(first)
        var depth = 1
        var inString = false
        var escaped = false
        while (depth > 0) {
            val value = readByte()
            if (value < 0) {
                throw RecoveryArchiveException("Recovery compatibility object is truncated.")
            }
            output.write(value)
            if (output.size() > RECOVERY_MAX_JSONL_LINE_BYTES) {
                throw RecoveryArchiveException("Recovery compatibility object is oversized.")
            }
            if (inString) {
                if (!escaped && value == '"'.code) {
                    inString = false
                }
                escaped = if (escaped) false else value == '\\'.code
            } else {
                when (value) {
                    '"'.code -> inString = true
                    '{'.code, '['.code -> depth++
                    '}'.code, ']'.code -> depth--
                    ' '.code, '\t'.code, '\r'.code, '\n'.code ->
                        throw RecoveryArchiveException(
                            "Recovery compatibility object contains non-canonical whitespace.",
                        )
                }
            }
        }
        return output.toByteArray()
    }

    private fun peekByte(): Int {
        if (peeked == Unset) peeked = nextByte()
        return peeked
    }

    private fun readByte(): Int {
        if (peeked != Unset) {
            val value = peeked
            peeked = Unset
            return value
        }
        return nextByte()
    }

    private fun nextByte(): Int {
        if (offset >= limit) {
            checkContinue()
            limit = input.read(buffer)
            offset = 0
            if (limit < 0) return -1
        }
        return buffer[offset++].toInt() and 0xff
    }

    private fun checkContinue() {
        if (!shouldContinue()) throw RecoveryArchiveCancelledException()
    }

    private companion object {
        const val Unset = -2
    }
}

private fun strictCompatibilityUtf8(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: Exception) {
        throw RecoveryArchiveException("Recovery compatibility payload contains invalid UTF-8.", error)
    }
}
