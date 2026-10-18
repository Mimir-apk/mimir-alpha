package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.data`; unmodified otherwise. Upstream path:
 * `core/data/src/main/java/com/explorer/fileexplorer/core/data/ArchiveEntryPathPolicy.kt`.
 *
 * The reason this fork was taken : real
 * zip-slip defence ([safeDestination], [normalizeEntryName], `canonicalFile`) and zip-bomb
 * defence ([ArchiveExtractionBudget]), consumed by [ArchiveHelper] (vendored alongside this file
 * in the same slice) and covered by the ported `ArchiveEntryPathPolicyTest` /
 * `ArchiveHelperExtractionTest`.
 *
 * `internal` visibility carried over unmodified: scoped to this module exactly as upstream scoped
 * it to `core/data`, resolvable by [ArchiveHelper] and this module's own `src/test` (same
 * compilation) but not by any future `feature-files` consumer, which is expected to go through
 * `ArchiveHelper`'s public API only.
 *
 * No Android SDK dependency, no network surface, no `Build.VERSION` branches (nothing to delete
 * for the minSdk 30 vs. upstream's 26 floor).
 */
internal object ArchiveEntryPathPolicy {
    const val MAX_ENTRIES = 10_000
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRY_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRY_PATH_BYTES = 4 * 1024
    const val MAX_ENTRY_DEPTH = 64

    fun normalizeEntryName(entryName: String): String? {
        if (entryName.isEmpty() || entryName.indexOf('\u0000') >= 0) return null

        val normalized = entryName.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty()) return null
        if (normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_ENTRY_PATH_BYTES) return null
        if (normalized.startsWith('/') || normalized.matches(Regex("^[A-Za-z]:.*"))) return null

        val segments = normalized.split('/')
        if (segments.size > MAX_ENTRY_DEPTH) return null
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return normalized
    }

    fun safeDestination(destination: String, entryName: String): File? {
        val root = File(destination).canonicalFile
        val normalized = normalizeEntryName(entryName) ?: return null
        val output = File(root, normalized).canonicalFile
        return output.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }
}

internal class ArchiveExtractionBudget {
    private val seenEntries = mutableSetOf<String>()
    var entryCount: Int = 0
        private set
    var declaredBytes: Long = 0
        private set
    var writtenBytes: Long = 0
        private set

    val progressTotal: Long
        get() = declaredBytes.takeIf { it > 0 } ?: -1L

    fun register(entryName: String, declaredSize: Long) {
        if (!seenEntries.add(entryName)) {
            throw ArchiveExtractionException("Duplicate archive entry: $entryName", ArchiveFailureKind.DUPLICATE_ENTRY)
        }
        if (entryCount >= ArchiveEntryPathPolicy.MAX_ENTRIES) {
            throw ArchiveExtractionException("Archive contains more than ${ArchiveEntryPathPolicy.MAX_ENTRIES} entries", ArchiveFailureKind.TOO_MANY_ENTRIES)
        }
        if (declaredSize < -1L) {
            throw ArchiveExtractionException("Invalid size for archive entry: $entryName", ArchiveFailureKind.DAMAGED)
        }
        if (declaredSize > ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES) {
            throw ArchiveExtractionException("Archive entry exceeds the ${ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES}-byte limit: $entryName", ArchiveFailureKind.TOO_LARGE)
        }

        val knownSize = declaredSize.coerceAtLeast(0L)
        if (declaredBytes > ArchiveEntryPathPolicy.MAX_TOTAL_UNCOMPRESSED_BYTES - knownSize) {
            throw ArchiveExtractionException("Archive exceeds the ${ArchiveEntryPathPolicy.MAX_TOTAL_UNCOMPRESSED_BYTES}-byte uncompressed limit", ArchiveFailureKind.TOO_LARGE)
        }
        entryCount++
        declaredBytes += knownSize
    }

    fun consume(entryName: String, entryBytes: Long, declaredSize: Long, delta: Int) {
        if (entryBytes > ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES) {
            throw ArchiveExtractionException("Archive entry exceeds the ${ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES}-byte limit: $entryName", ArchiveFailureKind.TOO_LARGE)
        }
        if (declaredSize >= 0L && entryBytes > declaredSize) {
            throw ArchiveExtractionException("Archive entry produced more data than declared: $entryName", ArchiveFailureKind.TOO_LARGE)
        }
        if (writtenBytes > ArchiveEntryPathPolicy.MAX_TOTAL_UNCOMPRESSED_BYTES - delta) {
            throw ArchiveExtractionException("Archive exceeds the ${ArchiveEntryPathPolicy.MAX_TOTAL_UNCOMPRESSED_BYTES}-byte uncompressed limit", ArchiveFailureKind.TOO_LARGE)
        }
        writtenBytes += delta
    }

    fun finish(entryName: String, entryBytes: Long, declaredSize: Long) {
        if (declaredSize >= 0L && entryBytes != declaredSize) {
            throw ArchiveExtractionException(
                "Archive entry size mismatch for $entryName: read $entryBytes bytes, expected $declaredSize",
                ArchiveFailureKind.DAMAGED,
            )
        }
    }
}
