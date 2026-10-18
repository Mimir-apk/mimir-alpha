package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.PlatformId

/*
 * The overlay file, schema 1. One JSON object, closed shape (an unknown key rejects the whole file):
 *
 *   { "schema": 1,
 *     "ordinal": 42,                       // monotonic, Mimir-owned; the only downgrade defence
 *     "sha256": "<64 hex>",                // SHA-256 of the CANONICAL encoding of "body" (sorted keys, no spaces)
 *     "sig": null,                         // reserved for signing; type-checked, size-capped, then IGNORED
 *     "body": { "rows": [
 *       { "type": "activity_alternate", "package": "com.flycast.emulator", "platform": "dreamcast",
 *         "activity": "com.reicast.emulator.MainActivity" },
 *       { "type": "retroarch_core", "package": "com.retroarch.aarch64", "platform": "dreamcast", "core": "flycast" },
 *       { "type": "extensions", "package": "...", "platform": "...", "extensions": ["iso", "cso"] },
 *       { "type": "filename_regex", "package": "...", "platform": "...", "regex": "^.*\\.(?:iso|cso)$" } ] } }
 *
 * There is no field for an action, a component, an extra, a path, a capability, a kill flag or a URL. Unknown keys
 * are REJECTED, not ignored: ignoring would let a newer file's meaning silently shrink on an older app, and would let
 * two different byte strings mean the same thing to a reviewer and to the app. The one reserved field is "sig".
 */

sealed interface OverlayRow {
    val packageName: String
    val platform: PlatformId
}

data class ActivityAlternateRow(
    override val packageName: String,
    override val platform: PlatformId,
    val activity: String,
) : OverlayRow

data class RetroArchCoreRow(
    override val packageName: String,
    override val platform: PlatformId,
    val core: String,
) : OverlayRow

data class ExtensionsRow(
    override val packageName: String,
    override val platform: PlatformId,
    val extensions: List<String>,
) : OverlayRow

data class FilenameRegexRow(
    override val packageName: String,
    override val platform: PlatformId,
    val regex: String,
) : OverlayRow

/** A file that passed every check. [rawSha256] is the identity of the received bytes; [canonicalSha256] of the body. */
data class ValidatedOverlay(
    val schema: Int,
    val ordinal: Long,
    val rawSha256: String,
    val canonicalSha256: String,
    val rows: List<OverlayRow>,
)

sealed interface ParseResult {
    data class Accepted(val overlay: ValidatedOverlay) : ParseResult
    data class Rejected(val code: RejectCode, val detail: String) : ParseResult
}

object OverlayParser {
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PLATFORM = Regex("^[a-z0-9_]{1,64}$")
    private val CLASS_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val CORE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val EXTENSION = Regex("^[a-z0-9]{1,10}$")
    private val SIG = Regex("^[A-Za-z0-9+/=_-]{0,256}$")

    private val ENVELOPE_KEYS = setOf("schema", "ordinal", "sha256", "sig", "body")
    private val BODY_KEYS = setOf("rows")
    private val ROW_KEYS = mapOf(
        "activity_alternate" to setOf("type", "package", "platform", "activity"),
        "retroarch_core" to setOf("type", "package", "platform", "core"),
        "extensions" to setOf("type", "package", "platform", "extensions"),
        "filename_regex" to setOf("type", "package", "platform", "regex"),
    )

    /** Names the overlay schema forbids, so a hostile file gets a precise code instead of a generic one. */
    internal val FORBIDDEN_NAMES = setOf(
        "action", "actions", "extra", "extras", "component", "components", "intent", "category", "categories",
        "pathstrategy", "path", "paths", "file", "uri", "url", "baseuri", "capability", "killpackageprocesses",
        "kill", "amstartarguments", "data", "mime", "flags", "args",
    )

    /**
     * Validate [bytes] against [bundled]. Never throws: any exception inside discards the candidate. When
     * [expectedCanonicalSha256] is given (the pin) the body must hash to exactly that.
     */
    fun parse(
        bytes: ByteArray,
        bundled: BundledDefinitions,
        expectedCanonicalSha256: String? = null,
        supportedSchema: Int = RemoteDefinitionsLimits.SUPPORTED_SCHEMA,
    ): ParseResult = try {
        ParseResult.Accepted(validate(bytes, bundled, expectedCanonicalSha256, supportedSchema))
    } catch (rejected: OverlayRejected) {
        ParseResult.Rejected(rejected.code, rejected.message.orEmpty())
    } catch (e: Exception) {
        ParseResult.Rejected(RejectCode.INTERNAL, e::class.java.simpleName)
    }

    private fun validate(
        bytes: ByteArray,
        bundled: BundledDefinitions,
        expectedCanonicalSha256: String?,
        supportedSchema: Int,
    ): ValidatedOverlay {
        if (bytes.size > RemoteDefinitionsLimits.MAX_BYTES) reject(RejectCode.SIZE, "over ${RemoteDefinitionsLimits.MAX_BYTES} bytes")
        val tree = StrictJson.parse(bytes)
        val envelope = asObject(tree, RejectCode.BAD_ENVELOPE)

        // The schema is read first: a newer file may have any shape, so it is refused before its keys are judged.
        val schema = envelope["schema"] as? Long ?: reject(RejectCode.BAD_ENVELOPE, "schema missing or not an integer")
        if (schema > supportedSchema) reject(RejectCode.SCHEMA_NEWER, "schema $schema")
        if (schema < 1) reject(RejectCode.BAD_SCHEMA, "schema $schema")

        checkKeys(envelope, ENVELOPE_KEYS)
        val ordinal = envelope["ordinal"] as? Long ?: reject(RejectCode.BAD_ENVELOPE, "ordinal missing or not an integer")
        if (ordinal < 1 || ordinal > RemoteDefinitionsLimits.MAX_ORDINAL) reject(RejectCode.BAD_ENVELOPE, "ordinal out of range")
        val declaredSha = envelope["sha256"] as? String ?: reject(RejectCode.BAD_ENVELOPE, "sha256 missing")
        if (!SHA256.matches(declaredSha)) reject(RejectCode.BAD_ENVELOPE, "sha256 is not 64 lowercase hex")
        if (envelope.containsKey("sig")) {
            val sig = envelope["sig"]
            if (sig != null && (sig !is String || !SIG.matches(sig))) reject(RejectCode.BAD_ENVELOPE, "sig malformed")
            // Reserved for signing: checked for shape, never used.
        }
        val body = asObject(envelope["body"], RejectCode.BAD_ENVELOPE)

        // Integrity first: nothing below is trusted until the body hashes to what the file and the pin say.
        val canonicalSha = sha256Hex(StrictJson.canonical(body).toByteArray(Charsets.US_ASCII))
        if (canonicalSha != declaredSha) reject(RejectCode.HASH_MISMATCH, "body does not match its sha256")
        if (expectedCanonicalSha256 != null && canonicalSha != expectedCanonicalSha256) {
            reject(RejectCode.PIN_MISMATCH, "body does not match the pinned sha256")
        }

        checkKeys(body, BODY_KEYS)
        val rawRows = body["rows"] as? List<*> ?: reject(RejectCode.BAD_ENVELOPE, "rows missing or not an array")
        if (rawRows.size > RemoteDefinitionsLimits.MAX_ROWS) reject(RejectCode.TOO_MANY_ROWS, "${rawRows.size} rows")

        val rows = ArrayList<OverlayRow>(rawRows.size)
        val seenKeys = HashSet<String>()
        val alternatesPerKey = HashMap<String, Int>()
        for (raw in rawRows) {
            val row = parseRow(asObject(raw, RejectCode.BAD_ROW_TYPE), bundled)
            val key = rowKey(row)
            if (!seenKeys.add(key)) reject(RejectCode.DUPLICATE_ROW, "repeated row")
            if (row is ActivityAlternateRow) {
                val perKey = alternatesPerKey.merge("${row.packageName}|${row.platform.value}", 1) { a, b -> a + b }!!
                if (perKey > RemoteDefinitionsLimits.MAX_ALTERNATES_PER_PACKAGE_PLATFORM) {
                    reject(RejectCode.TOO_MANY_ROWS, "too many alternates for one package and platform")
                }
            }
            rows += row
        }
        return ValidatedOverlay(
            schema = schema.toInt(),
            ordinal = ordinal,
            rawSha256 = sha256Hex(bytes),
            canonicalSha256 = canonicalSha,
            rows = rows,
        )
    }

    private fun parseRow(row: Map<String, Any?>, bundled: BundledDefinitions): OverlayRow {
        val type = row["type"] as? String ?: reject(RejectCode.BAD_ROW_TYPE, "row has no type")
        val allowed = ROW_KEYS[type] ?: reject(RejectCode.BAD_ROW_TYPE, "unknown row type")
        checkKeys(row, allowed)

        val packageName = string(row, "package")
        // The length first: the cap is the cheap test, and the pattern never runs on a string past it.
        if (packageName.length > 128 || !PACKAGE.matches(packageName)) reject(RejectCode.UNKNOWN_PACKAGE, "malformed package")
        val seed = bundled.seed(packageName) ?: reject(RejectCode.UNKNOWN_PACKAGE, "not a bundled package")
        val platformText = string(row, "platform")
        if (!PLATFORM.matches(platformText)) reject(RejectCode.BAD_PLATFORM, "malformed platform")
        val platform = PlatformId(platformText)
        if (platform !in seed.platforms) reject(RejectCode.PLATFORM_NOT_SERVED, "package does not serve this platform")

        return when (type) {
            "activity_alternate" -> {
                // An alternate only swaps the class name of an existing proven profile, so a recognise-only seed has none.
                if (seed.capability == EmulatorCapability.RECOGNIZE_ONLY) reject(RejectCode.NOT_DISPATCHABLE, "recognise-only package")
                val activity = string(row, "activity")
                if (activity.length > 160 || !CLASS_NAME.matches(activity)) reject(RejectCode.BAD_CLASS_NAME, "not a class name")
                ActivityAlternateRow(packageName, platform, activity)
            }
            "retroarch_core" -> {
                if (platform !in seed.retroArchCores.keys) reject(RejectCode.PLATFORM_NOT_SERVED, "package has no cores for this platform")
                if (seed.capability == EmulatorCapability.RECOGNIZE_ONLY) reject(RejectCode.NOT_DISPATCHABLE, "recognise-only package")
                val core = string(row, "core")
                if (!CORE.matches(core)) reject(RejectCode.BAD_CORE, "not a core stem")
                RetroArchCoreRow(packageName, platform, core)
            }
            "extensions" -> {
                val list = row["extensions"] as? List<*> ?: reject(RejectCode.BAD_EXTENSION, "extensions is not an array")
                if (list.isEmpty() || list.size > RemoteDefinitionsLimits.MAX_EXTENSIONS_PER_ROW) {
                    reject(RejectCode.BAD_EXTENSION, "extension count out of range")
                }
                val extensions = list.map { item ->
                    val text = item as? String ?: reject(RejectCode.BAD_EXTENSION, "extension is not a string")
                    if (!EXTENSION.matches(text)) reject(RejectCode.BAD_EXTENSION, "malformed extension")
                    text
                }
                if (extensions.toSet().size != extensions.size) reject(RejectCode.DUPLICATE_ROW, "repeated extension")
                ExtensionsRow(packageName, platform, extensions.sorted())
            }
            else -> {
                val regex = string(row, "regex")
                RecognitionRegex.problem(regex)?.let { reject(RejectCode.BAD_REGEX, it) }
                FilenameRegexRow(packageName, platform, regex)
            }
        }
    }

    private fun rowKey(row: OverlayRow): String = when (row) {
        is ActivityAlternateRow -> "alt|${row.packageName}|${row.platform.value}|${row.activity}"
        is RetroArchCoreRow -> "core|${row.packageName}|${row.platform.value}|${row.core}"
        is ExtensionsRow -> "ext|${row.packageName}|${row.platform.value}"
        is FilenameRegexRow -> "re|${row.packageName}|${row.platform.value}|${row.regex}"
    }

    private fun string(row: Map<String, Any?>, key: String): String =
        row[key] as? String ?: reject(RejectCode.BAD_ENVELOPE, "$key missing or not a string")

    private fun checkKeys(obj: Map<String, Any?>, allowed: Set<String>) {
        for (key in obj.keys) {
            if (key in allowed) continue
            if (key.lowercase() in FORBIDDEN_NAMES) reject(RejectCode.FORBIDDEN_FIELD, "field is outside the scope ruling")
            reject(RejectCode.UNKNOWN_KEY, "unknown key")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asObject(value: Any?, code: RejectCode): Map<String, Any?> =
        if (value is Map<*, *>) value as Map<String, Any?> else reject(code, "expected an object")

    private fun reject(code: RejectCode, detail: String): Nothing = throw OverlayRejected(code, detail)
}
