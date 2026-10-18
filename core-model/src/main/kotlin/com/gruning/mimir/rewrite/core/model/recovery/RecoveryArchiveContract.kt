package com.gruning.mimir.rewrite.core.model.recovery

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Required
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val RECOVERY_ARCHIVE_KIND: String = "mimir.recovery.archive"
const val CURRENT_RECOVERY_FORMAT_VERSION: Int = 1
const val RECOVERY_MANIFEST_PATH: String = "manifest.json"
const val RECOVERY_MANIFEST_HASH_PATH: String = "manifest.sha256"

const val RECOVERY_CONTENT_CORE: String = "core"
const val RECOVERY_CONTENT_DERIVED: String = "derived"
const val RECOVERY_CONTENT_COMPAT: String = "compat"

const val RECOVERY_MAX_MANIFEST_BYTES: Long = 4L * 1024L * 1024L
const val RECOVERY_MANIFEST_HASH_BYTES: Long = 65L
const val RECOVERY_MAX_PAYLOAD_ENTRIES: Int = 256
const val RECOVERY_MAX_ENTRY_BYTES: Long = 512L * 1024L * 1024L
const val RECOVERY_MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 2L * 1024L * 1024L * 1024L
const val RECOVERY_MAX_ARCHIVE_BYTES: Long = 1024L * 1024L * 1024L
const val RECOVERY_MAX_JSONL_LINE_BYTES: Int = 1024 * 1024
const val RECOVERY_MAX_TABLE_ROWS: Long = 2_000_000L
const val RECOVERY_MAX_PATH_CHARS: Int = 200
const val RECOVERY_MAX_PATH_DEPTH: Int = 4

/**
 * Recovery Archive V1 is a corruption-detecting, inspectable local export. It is deliberately not an
 * authenticity or secrecy envelope: credentials and content bytes are excluded, and V1 is not signed.
 */
@kotlinx.serialization.Serializable
data class RecoveryArchiveManifestV1(
    @Required val kind: String = RECOVERY_ARCHIVE_KIND,
    @Required val formatVersion: Int = CURRENT_RECOVERY_FORMAT_VERSION,
    @Required val minReaderFormatVersion: Int = CURRENT_RECOVERY_FORMAT_VERSION,
    val createdAtUtcMillis: Long,
    val producedByApp: String,
    val producedByVersionName: String,
    val producedByVersionCode: Long,
    val schemaVersion: Int,
    val entries: List<RecoveryArchiveEntryV1>,
    val excluded: List<RecoveryArchiveExclusionV1>,
    val snapshot: RecoveryArchiveSnapshotV1,
)

@kotlinx.serialization.Serializable
data class RecoveryArchiveEntryV1(
    val path: String,
    val uncompressedSize: Long,
    val sha256: String,
    val rowCount: Long? = null,
    /** Open string by design: additive V1.x producers may introduce a new integrity-valid class. */
    val contentClass: String,
)

@kotlinx.serialization.Serializable
data class RecoveryArchiveExclusionV1(
    val what: String,
    val reason: String,
)

@kotlinx.serialization.Serializable
data class RecoveryArchiveSnapshotV1(
    /** True only for the 14 Room table dumps and the DB-derived part of the compatibility bundle. */
    val transactional: Boolean,
    /** False until a concrete scan/import/identity-maintenance idle authority is wired and proven. */
    val quiescent: Boolean,
    /** Observed Room/SQLite journal mode; never used as a substitute for the transaction. */
    val journalMode: String,
)

object RecoveryArchiveCoverageV1 {
    const val ROM_BIOS_FIRMWARE_BYTES = "rom-bios-firmware-bytes"
    const val SOURCE_STORAGE_BYTES = "source-root-and-managed-storage-bytes"
    const val DOWNLOADED_ART_MEDIA = "downloaded-art-and-media-caches"
    const val CREDENTIALS = "provider-credentials-and-account-secrets"
    const val SAF_GRANTS = "saf-permission-grants"
    const val RAW_PREFERENCES = "raw-shared-preferences"
    const val RAW_DATABASE = "raw-database-wal-shm-files"
    const val LOGS = "logs-and-diagnostics"
    const val HOME_COLLECTIONS = "home-collections-and-tomes"
    const val QUICK_LAUNCH = "quick-launch-slots"
    const val CUSTOM_MEDIA = "custom-hero-and-media-state"
    const val APPEARANCE_BRIGHTNESS = "backdrop-brightness-and-device-state"

    val requiredExclusions: Set<String> = linkedSetOf(
        ROM_BIOS_FIRMWARE_BYTES,
        SOURCE_STORAGE_BYTES,
        DOWNLOADED_ART_MEDIA,
        CREDENTIALS,
        SAF_GRANTS,
        RAW_PREFERENCES,
        RAW_DATABASE,
        LOGS,
        HOME_COLLECTIONS,
        QUICK_LAUNCH,
        CUSTOM_MEDIA,
        APPEARANCE_BRIGHTNESS,
    )
}

object RecoveryArchiveTablesV1 {
    val paths: List<String> = listOf(
        "db/games.jsonl",
        "db/platforms.jsonl",
        "db/source_roots.jsonl",
        "db/hidden_items.jsonl",
        "db/emulator_overrides.jsonl",
        "db/favorites.jsonl",
        "db/home_shelf_items.jsonl",
        "db/launch_proofs.jsonl",
        "db/bios_profiles.jsonl",
        "db/title_metadata_cache.jsonl",
        "db/logical_game_groups.jsonl",
        "db/game_id_aliases.jsonl",
        "db/remap_journal.jsonl",
        "db/identity_maintenance.jsonl",
    )

    /**
     * Payloads added **after** V1 shipped: written by builds that have them, absent from every archive
     * written before, and **never required**.
     *
     * The distinction exists because [requiredPaths] is enforced on read
     * (*"Recovery archive omits required V1 payloads"*). Putting a new table in [paths] would therefore
     * reach backwards and invalidate archives that were perfectly valid when they were written — the
     * player's existing backups, which are precisely what a recovery format exists to honour. A reader
     * that meets one of these and does not find it has met an older archive, not a broken one.
     */
    val optionalPaths: List<String> = listOf(
        // Room v13's cosmetic renames -- policy 2026-08-18.
        "db/title_overrides.jsonl",
    )

    /** Every table payload this build understands, required or not. Use for classification, never for presence. */
    val allPaths: List<String> = paths + optionalPaths

    const val COMPATIBILITY_BUNDLE_PATH: String = "settings/migration-bundle.json"
    val requiredPaths: Set<String> = (paths + COMPATIBILITY_BUNDLE_PATH).toSet()
}

open class RecoveryArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

object RecoveryArchiveCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(manifest: RecoveryArchiveManifestV1): ByteArray {
        validateManifest(manifest).getOrThrow()
        return json.encodeToString(RecoveryArchiveManifestV1.serializer(), manifest)
            .toByteArray(StandardCharsets.UTF_8)
            .also {
                if (it.size.toLong() > RECOVERY_MAX_MANIFEST_BYTES) {
                    throw RecoveryArchiveException("Recovery manifest exceeds the ${RECOVERY_MAX_MANIFEST_BYTES}-byte limit.")
                }
            }
    }

    fun parse(bytes: ByteArray): Result<RecoveryArchiveManifestV1> = runCatching {
        if (bytes.isEmpty() || bytes.size.toLong() > RECOVERY_MAX_MANIFEST_BYTES) {
            throw RecoveryArchiveException("Recovery manifest size is outside the supported range.")
        }
        val manifestText = decodeRecoveryUtf8(bytes)
        validateCanonicalRecoveryJsonObject(manifestText)
        val manifest = try {
            json.decodeFromString(
                RecoveryArchiveManifestV1.serializer(),
                manifestText,
            )
        } catch (error: SerializationException) {
            throw RecoveryArchiveException("Recovery manifest is malformed.", error)
        }
        validateManifest(manifest).getOrThrow()
        manifest
    }

    fun validateManifest(manifest: RecoveryArchiveManifestV1): Result<Unit> = runCatching {
        if (manifest.kind != RECOVERY_ARCHIVE_KIND) {
            throw RecoveryArchiveException("Unsupported recovery archive kind.")
        }
        if (manifest.formatVersion < 1) {
            throw RecoveryArchiveException("Recovery archive format version must be positive.")
        }
        if (manifest.minReaderFormatVersion !in 1..CURRENT_RECOVERY_FORMAT_VERSION) {
            throw RecoveryArchiveException(
                "Recovery archive needs reader format ${manifest.minReaderFormatVersion}; " +
                    "this reader supports $CURRENT_RECOVERY_FORMAT_VERSION.",
            )
        }
        if (manifest.minReaderFormatVersion > manifest.formatVersion) {
            throw RecoveryArchiveException("Recovery archive reader floor exceeds its format version.")
        }
        if (manifest.createdAtUtcMillis <= 0L ||
            manifest.producedByApp.isBlank() ||
            manifest.producedByVersionName.isBlank() ||
            manifest.producedByVersionCode <= 0L ||
            manifest.schemaVersion <= 0
        ) {
            throw RecoveryArchiveException("Recovery archive producer metadata is incomplete.")
        }
        if (!manifest.snapshot.transactional || manifest.snapshot.journalMode.isBlank()) {
            throw RecoveryArchiveException("Recovery archive does not describe a transactional Room snapshot.")
        }
        if (manifest.entries.isEmpty() || manifest.entries.size > RECOVERY_MAX_PAYLOAD_ENTRIES) {
            throw RecoveryArchiveException("Recovery archive payload count is outside the supported range.")
        }
        val paths = manifest.entries.map(RecoveryArchiveEntryV1::path)
        if (paths != paths.sorted()) {
            throw RecoveryArchiveException("Recovery archive payload ledger is not in canonical path order.")
        }
        val foldedPaths = HashSet<String>()
        var totalBytes = 0L
        manifest.entries.forEach { entry ->
            validateRecoveryPath(entry.path)
            if (!foldedPaths.add(entry.path.lowercase(Locale.ROOT))) {
                throw RecoveryArchiveException("Recovery archive contains a case-folded duplicate path.")
            }
            val isTable = entry.path.startsWith("db/") && entry.path.endsWith(".jsonl")
            val minimumSize = if (isTable) 0L else 1L
            if (entry.uncompressedSize !in minimumSize..RECOVERY_MAX_ENTRY_BYTES) {
                throw RecoveryArchiveException("Recovery payload size is outside the supported range: ${entry.path}")
            }
            totalBytes = Math.addExact(totalBytes, entry.uncompressedSize)
            if (totalBytes > RECOVERY_MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw RecoveryArchiveException("Recovery archive declares too much uncompressed data.")
            }
            if (!entry.sha256.matches(Regex("[0-9a-f]{64}"))) {
                throw RecoveryArchiveException("Recovery payload hash is malformed: ${entry.path}")
            }
            if (entry.contentClass.isBlank()) {
                throw RecoveryArchiveException("Recovery payload class is blank: ${entry.path}")
            }
            val requiredClass = when (entry.path) {
                "db/title_metadata_cache.jsonl" -> RECOVERY_CONTENT_DERIVED
                RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH -> RECOVERY_CONTENT_COMPAT
                in RecoveryArchiveTablesV1.allPaths -> RECOVERY_CONTENT_CORE
                else -> null
            }
            if (requiredClass != null && entry.contentClass != requiredClass) {
                throw RecoveryArchiveException("Recovery payload class is invalid: ${entry.path}")
            }
            if (isTable) {
                if (entry.rowCount == null || entry.rowCount !in 0..RECOVERY_MAX_TABLE_ROWS) {
                    throw RecoveryArchiveException("Recovery table row count is invalid: ${entry.path}")
                }
            } else if (entry.rowCount != null) {
                throw RecoveryArchiveException("Only JSONL table payloads may declare a row count.")
            }
        }
        val missingPaths = RecoveryArchiveTablesV1.requiredPaths - paths.toSet()
        if (missingPaths.isNotEmpty()) {
            throw RecoveryArchiveException("Recovery archive omits required V1 payloads: ${missingPaths.sorted()}")
        }

        val excludedPaths = manifest.excluded.map(RecoveryArchiveExclusionV1::what)
        if (excludedPaths != excludedPaths.sorted()) {
            throw RecoveryArchiveException("Recovery archive exclusion ledger is not canonical.")
        }
        val excludedKeys = HashSet<String>()
        manifest.excluded.forEach { exclusion ->
            if (exclusion.what.isBlank() || exclusion.reason.isBlank()) {
                throw RecoveryArchiveException("Recovery archive exclusion ledger contains an empty row.")
            }
            if (!excludedKeys.add(exclusion.what.lowercase(Locale.ROOT))) {
                throw RecoveryArchiveException("Recovery archive exclusion ledger contains a duplicate row.")
            }
        }
        val missingExclusions = RecoveryArchiveCoverageV1.requiredExclusions -
            manifest.excluded.map(RecoveryArchiveExclusionV1::what).toSet()
        if (missingExclusions.isNotEmpty()) {
            throw RecoveryArchiveException(
                "Recovery archive omits required exclusion disclosures: ${missingExclusions.sorted()}",
            )
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
}

fun validateRecoveryPath(path: String) {
    if (path.length !in 1..RECOVERY_MAX_PATH_CHARS ||
        !path.matches(Regex("[a-z0-9][a-z0-9._/-]{0,199}")) ||
        path.startsWith("/") ||
        path.contains('\\') ||
        path.contains('\u0000')
    ) {
        throw RecoveryArchiveException("Recovery archive path is not canonical: $path")
    }
    val segments = path.split('/')
    if (segments.size > RECOVERY_MAX_PATH_DEPTH ||
        segments.any { it.isEmpty() || it == "." || it == ".." }
    ) {
        throw RecoveryArchiveException("Recovery archive path depth or segment is invalid: $path")
    }
}

internal fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}

private fun decodeRecoveryUtf8(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: Exception) {
        throw RecoveryArchiveException("Recovery manifest contains invalid UTF-8.", error)
    }
}
