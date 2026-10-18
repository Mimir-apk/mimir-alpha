package com.gruning.mimir.rewrite.core.model.recovery

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private const val RECOVERY_MAX_JSON_DEPTH = 16
private val RECOVERY_CANONICAL_INTEGER = Regex("-?(0|[1-9][0-9]*)")

/**
 * Validates the compact canonical JSON subset and returns top-level member names in source order.
 *
 * kotlinx.serialization intentionally applies last-member-wins semantics. Recovery archives cannot:
 * duplicate names would create a parser-differential channel around the signed/hash ledger.
 */
fun validateCanonicalRecoveryJsonObject(text: String): List<String> =
    CanonicalRecoveryJsonScanner(text).parseRootObject()

private class CanonicalRecoveryJsonScanner(
    private val text: String,
) {
    private var offset = 0

    fun parseRootObject(): List<String> {
        val keys = parseObject(depth = 0, captureKeys = true)
        if (offset != text.length) fail()
        return keys
    }

    private fun parseValue(depth: Int) {
        if (depth > RECOVERY_MAX_JSON_DEPTH || offset >= text.length) fail()
        when (text[offset]) {
            '{' -> parseObject(depth, captureKeys = false)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> expect("true")
            'f' -> expect("false")
            'n' -> throw RecoveryArchiveException(
                "Recovery canonical JSON must omit null members.",
            )
            '-', in '0'..'9' -> parseNumber()
            else -> fail()
        }
    }

    private fun parseObject(
        depth: Int,
        captureKeys: Boolean,
    ): List<String> {
        expect('{')
        val ordered = mutableListOf<String>()
        val seen = HashSet<String>()
        if (consumeIf('}')) return ordered
        while (true) {
            val key = parseString()
            if (!seen.add(key)) {
                throw RecoveryArchiveException("Recovery canonical JSON contains a duplicate member.")
            }
            if (captureKeys) ordered += key
            expect(':')
            parseValue(depth + 1)
            when {
                consumeIf(',') -> Unit
                consumeIf('}') -> return ordered
                else -> fail()
            }
        }
    }

    private fun parseArray(depth: Int) {
        expect('[')
        if (consumeIf(']')) return
        while (true) {
            parseValue(depth + 1)
            when {
                consumeIf(',') -> Unit
                consumeIf(']') -> return
                else -> fail()
            }
        }
    }

    private fun parseString(): String {
        val start = offset
        expect('"')
        var escaped = false
        while (offset < text.length) {
            val value = text[offset++]
            if (!escaped && value == '"') {
                val token = text.substring(start, offset)
                val primitive = try {
                    Json.parseToJsonElement(token) as? JsonPrimitive
                } catch (error: SerializationException) {
                    throw RecoveryArchiveException("Recovery canonical JSON has a malformed string.", error)
                } ?: fail()
                if (!primitive.isString) fail()
                if (JsonPrimitive(primitive.content).toString() != token) {
                    throw RecoveryArchiveException(
                        "Recovery canonical JSON contains a non-canonical string encoding.",
                    )
                }
                return primitive.content
            }
            if (!escaped && value.code < 0x20) fail()
            escaped = if (escaped) false else value == '\\'
        }
        fail()
    }

    private fun parseNumber() {
        val start = offset
        while (offset < text.length && text[offset] !in charArrayOf(',', '}', ']')) {
            if (text[offset].isWhitespace()) fail()
            offset++
        }
        val token = text.substring(start, offset)
        if (!RECOVERY_CANONICAL_INTEGER.matches(token) || token == "-0") {
            throw RecoveryArchiveException("Recovery canonical JSON contains a non-canonical integer.")
        }
        try {
            val primitive = Json.parseToJsonElement(token) as? JsonPrimitive ?: fail()
            if (primitive.isString) fail()
        } catch (error: SerializationException) {
            throw RecoveryArchiveException("Recovery canonical JSON has a malformed number.", error)
        }
    }

    private fun expect(literal: String) {
        if (!text.regionMatches(offset, literal, 0, literal.length)) fail()
        offset += literal.length
    }

    private fun expect(value: Char) {
        if (offset >= text.length || text[offset] != value) fail()
        offset++
    }

    private fun consumeIf(value: Char): Boolean =
        if (offset < text.length && text[offset] == value) {
            offset++
            true
        } else {
            false
        }

    private fun fail(): Nothing =
        throw RecoveryArchiveException("Recovery canonical JSON is malformed or non-canonical.")
}
