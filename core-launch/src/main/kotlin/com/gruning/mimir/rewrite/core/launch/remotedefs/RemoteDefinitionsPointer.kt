package com.gruning.mimir.rewrite.core.launch.remotedefs

/**
 * `main/pointer.json` in the data repository: the one mutable file, updated when a new release
 * is tagged. It names the release and the checksum that release must have.
 *
 *   { "schema": 1, "ordinal": 2, "tag": "defs-2", "sha256": "<64 hex>", "sig": null }
 *
 * - `ordinal` is the same Mimir-owned monotonic number the release file carries; the tag is always `defs-<ordinal>`.
 * - `sha256` is the SHA-256 of the exact bytes of `<tag>/definitions.json` (what `sha256sum definitions.json` prints).
 * - `sig` is reserved for signing later (checksum validation is active; signing remains future work): type-checked, size-capped, ignored.
 *
 * Same closed-shape rule as the release file: an unknown key rejects the whole pointer, and there is no field that
 * could name a URL, so the data cannot choose where the app fetches next.
 */
data class DefinitionsPointer(val ordinal: Long, val tag: String, val sha256: String) {
    val pin: RemoteDefinitionsPin get() = RemoteDefinitionsPin(tag, sha256)
}

sealed interface PointerParseResult {
    data class Accepted(val pointer: DefinitionsPointer) : PointerParseResult
    data class Rejected(val code: RejectCode, val detail: String) : PointerParseResult
}

object PointerParser {
    /** A pointer is a few lines; anything larger is not one. */
    const val MAX_BYTES: Int = 4 * 1024

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val SIG = Regex("^[A-Za-z0-9+/=_-]{0,256}$")
    private val KEYS = setOf("schema", "ordinal", "tag", "sha256", "sig")

    /** Never throws: any failure is a [PointerParseResult.Rejected] and the caller keeps what it has. */
    fun parse(
        bytes: ByteArray,
        supportedSchema: Int = RemoteDefinitionsLimits.SUPPORTED_SCHEMA,
    ): PointerParseResult = try {
        PointerParseResult.Accepted(validate(bytes, supportedSchema))
    } catch (rejected: OverlayRejected) {
        PointerParseResult.Rejected(rejected.code, rejected.message.orEmpty())
    } catch (e: Exception) {
        PointerParseResult.Rejected(RejectCode.INTERNAL, e::class.java.simpleName)
    }

    private fun validate(bytes: ByteArray, supportedSchema: Int): DefinitionsPointer {
        if (bytes.size > MAX_BYTES) throw OverlayRejected(RejectCode.SIZE, "pointer over $MAX_BYTES bytes")
        @Suppress("UNCHECKED_CAST")
        val tree = StrictJson.parse(bytes) as? Map<String, Any?>
            ?: throw OverlayRejected(RejectCode.BAD_ENVELOPE, "pointer is not an object")

        // The schema first, as for the release file: a newer pointer may have any shape.
        val schema = tree["schema"] as? Long
            ?: throw OverlayRejected(RejectCode.BAD_ENVELOPE, "schema missing or not an integer")
        if (schema > supportedSchema) throw OverlayRejected(RejectCode.SCHEMA_NEWER, "schema $schema")
        if (schema < 1) throw OverlayRejected(RejectCode.BAD_SCHEMA, "schema $schema")

        for (key in tree.keys) {
            if (key in KEYS) continue
            if (key.lowercase() in OverlayParser.FORBIDDEN_NAMES) {
                throw OverlayRejected(RejectCode.FORBIDDEN_FIELD, "field is outside the scope ruling")
            }
            throw OverlayRejected(RejectCode.UNKNOWN_KEY, "unknown key")
        }
        val ordinal = tree["ordinal"] as? Long
            ?: throw OverlayRejected(RejectCode.BAD_ENVELOPE, "ordinal missing or not an integer")
        if (ordinal < 1 || ordinal > RemoteDefinitionsLimits.MAX_ORDINAL) {
            throw OverlayRejected(RejectCode.BAD_ENVELOPE, "ordinal out of range")
        }
        val tag = tree["tag"] as? String ?: throw OverlayRejected(RejectCode.BAD_PIN, "tag missing")
        if (tag != "defs-$ordinal" || RemoteDefinitionsEndpoint.urlFor(tag) == null) {
            throw OverlayRejected(RejectCode.BAD_PIN, "tag is not defs-<ordinal>")
        }
        val sha = tree["sha256"] as? String ?: throw OverlayRejected(RejectCode.BAD_PIN, "sha256 missing")
        if (!SHA256.matches(sha)) throw OverlayRejected(RejectCode.BAD_PIN, "sha256 is not 64 lowercase hex")
        if (tree.containsKey("sig")) {
            val sig = tree["sig"]
            if (sig != null && (sig !is String || !SIG.matches(sig))) {
                throw OverlayRejected(RejectCode.BAD_ENVELOPE, "sig malformed")
            }
        }
        return DefinitionsPointer(ordinal, tag, sha)
    }
}
