package com.gruning.mimir.rewrite.core.model

private const val PHYSICAL_RESIDENT_KEY_PREFIX = "p2:"

/**
 * Collision-free location identity for one resident file.
 *
 * Lengths are Unicode code-point counts: Kotlin's [String.codePointCount] and SQLite's
 * `LENGTH(TEXT)` agree for the well-formed, NUL-free strings admitted here. Components are not
 * normalized; provider paths that differ only by Unicode normalization remain distinct locations.
 */
fun physicalResidentKey(sourceRootUri: String, relativePath: String): String {
    require(sourceRootUri.isNotBlank()) { "Physical resident source root must not be blank" }
    require(relativePath.isNotEmpty()) { "Physical resident relative path must not be empty" }
    sourceRootUri.requirePhysicalResidentText("source root")
    relativePath.requirePhysicalResidentText("relative path")
    val rootLength = sourceRootUri.codePointCount(0, sourceRootUri.length)
    val relativeLength = relativePath.codePointCount(0, relativePath.length)
    return "$PHYSICAL_RESIDENT_KEY_PREFIX$rootLength:$relativeLength:$sourceRootUri$relativePath"
}

internal data class DecodedPhysicalResidentKey(
    val sourceRootUri: String,
    val relativePath: String,
)

/** Strict inverse used by contract tests and migration preflight validation. */
internal fun decodePhysicalResidentKey(value: String): DecodedPhysicalResidentKey {
    require(value.startsWith(PHYSICAL_RESIDENT_KEY_PREFIX)) {
        "Physical resident key must use the p2 codec"
    }
    val rootLengthEnd = value.indexOf(':', PHYSICAL_RESIDENT_KEY_PREFIX.length)
    require(rootLengthEnd >= 0) { "Physical resident key is missing the root length terminator" }
    val relativeLengthEnd = value.indexOf(':', rootLengthEnd + 1)
    require(relativeLengthEnd >= 0) {
        "Physical resident key is missing the relative length terminator"
    }
    val rootLength = canonicalCount(
        value.substring(PHYSICAL_RESIDENT_KEY_PREFIX.length, rootLengthEnd),
        "root",
    )
    val relativeLength = canonicalCount(
        value.substring(rootLengthEnd + 1, relativeLengthEnd),
        "relative",
    )
    val payloadStart = relativeLengthEnd + 1
    val rootEnd = value.offsetByCodePointsOrReject(payloadStart, rootLength)
    val payloadEnd = value.offsetByCodePointsOrReject(rootEnd, relativeLength)
    require(payloadEnd == value.length) { "Physical resident key has trailing payload" }
    val sourceRootUri = value.substring(payloadStart, rootEnd)
    val relativePath = value.substring(rootEnd, payloadEnd)
    require(physicalResidentKey(sourceRootUri, relativePath) == value) {
        "Physical resident key is not canonical"
    }
    return DecodedPhysicalResidentKey(sourceRootUri, relativePath)
}

private fun canonicalCount(value: String, label: String): Int {
    require(value.isNotEmpty()) { "Physical resident $label length is missing" }
    require(value == "0" || value.first() != '0') {
        "Physical resident $label length has a leading zero"
    }
    require(value.all { it in '0'..'9' }) {
        "Physical resident $label length is not canonical decimal"
    }
    return requireNotNull(value.toIntOrNull()) {
        "Physical resident $label length exceeds the supported range"
    }
}

private fun String.offsetByCodePointsOrReject(index: Int, count: Int): Int = try {
    offsetByCodePoints(index, count)
} catch (failure: IndexOutOfBoundsException) {
    throw IllegalArgumentException("Physical resident key payload is shorter than declared", failure)
}

private fun String.requirePhysicalResidentText(label: String) {
    require('\u0000' !in this) { "Physical resident $label must not contain NUL" }
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            Character.isHighSurrogate(character) -> {
                require(index + 1 < length && Character.isLowSurrogate(this[index + 1])) {
                    "Physical resident $label contains an unpaired high surrogate"
                }
                index += 2
            }
            Character.isLowSurrogate(character) ->
                throw IllegalArgumentException(
                    "Physical resident $label contains an unpaired low surrogate",
                )
            else -> index++
        }
    }
}
