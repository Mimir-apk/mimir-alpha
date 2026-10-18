package com.gruning.mimir.rewrite.core.model.recovery

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface RecoveryJsonValue {
    data class Text(val value: String) : RecoveryJsonValue
    data class Integer(val value: Long) : RecoveryJsonValue
    data class BooleanValue(val value: Boolean) : RecoveryJsonValue
}

data class RecoveryJsonField(
    val name: String,
    /** Null fields are omitted, matching Recovery Archive V1's explicitNulls=false canonical form. */
    val value: RecoveryJsonValue?,
)

object RecoveryJsonLineCodec {
    fun encode(fields: List<RecoveryJsonField>): ByteArray {
        if (fields.isEmpty()) throw RecoveryArchiveException("Recovery JSON row has no fields.")
        preflight(fields)
        val values = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        fields.forEach { field ->
            if (field.name.isBlank() || values.containsKey(field.name)) {
                throw RecoveryArchiveException("Recovery JSON row contains a blank or duplicate field.")
            }
            val value = field.value ?: return@forEach
            values[field.name] = when (value) {
                is RecoveryJsonValue.Text -> JsonPrimitive(value.value)
                is RecoveryJsonValue.Integer -> JsonPrimitive(value.value)
                is RecoveryJsonValue.BooleanValue -> JsonPrimitive(value.value)
            }
        }
        return (JsonObject(values).toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            .also { bytes ->
                if (bytes.size > RECOVERY_MAX_JSONL_LINE_BYTES) {
                    throw RecoveryArchiveException("Recovery JSON row exceeds the supported line limit.")
                }
            }
    }

    private fun preflight(fields: List<RecoveryJsonField>) {
        val names = HashSet<String>()
        var encodedBytes = 3L // opening + closing brace + newline
        var emitted = 0
        fields.forEach { field ->
            if (field.name.isBlank() || !names.add(field.name)) {
                throw RecoveryArchiveException("Recovery JSON row contains a blank or duplicate field.")
            }
            val value = field.value ?: return@forEach
            if (emitted++ > 0) encodedBytes = Math.addExact(encodedBytes, 1L)
            encodedBytes = Math.addExact(
                encodedBytes,
                recoveryJsonStringEncodedUpperBound(field.name),
            )
            encodedBytes = Math.addExact(encodedBytes, 1L) // colon
            encodedBytes = Math.addExact(
                encodedBytes,
                when (value) {
                    is RecoveryJsonValue.Text ->
                        recoveryJsonStringEncodedUpperBound(value.value)
                    is RecoveryJsonValue.Integer -> value.value.toString().length.toLong()
                    is RecoveryJsonValue.BooleanValue -> if (value.value) 4L else 5L
                },
            )
            if (encodedBytes > RECOVERY_MAX_JSONL_LINE_BYTES) {
                throw RecoveryArchiveException("Recovery JSON row exceeds the supported line limit.")
            }
        }
    }
}

/** Conservative UTF-8 byte ceiling for one kotlinx JSON string, including its two quote bytes. */
fun recoveryJsonStringEncodedUpperBound(value: String): Long {
    var bytes = 2L
    var index = 0
    while (index < value.length) {
        val character = value[index]
        bytes = Math.addExact(
            bytes,
            when {
                character == '"' || character == '\\' -> 2L
                character.code < 0x20 -> 6L
                character.code <= 0x7f -> 1L
                character.code <= 0x7ff -> 2L
                Character.isHighSurrogate(character) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4L
                }
                Character.isSurrogate(character) -> 6L
                else -> 3L
            },
        )
        if (bytes > RECOVERY_MAX_JSONL_LINE_BYTES) {
            return bytes
        }
        index++
    }
    return bytes
}
