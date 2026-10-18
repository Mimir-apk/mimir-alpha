package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.physicalResidentKey

internal data class LegacyPhysicalIdentityRow(
    val entryKey: String,
    val sourceRootUri: String?,
    val relativePath: String,
    val locationKind: String,
    val managedPath: String?,
)

internal data class LegacyPhysicalIdentityMapping(
    val sourceRootUri: String,
    val relativePath: String,
    val physicalEntryKey: String,
)

/**
 * Maps only legacy rows whose exact physical owner can be proven from persisted facts.
 *
 * A missing source registry row is not itself unsafe: removing a source deliberately leaves indexed
 * history. Historical null-root rows are accepted only for managed paths whose old key suffix and full
 * path both prove the same root. Every other ambiguity is refused rather than guessed.
 */
internal fun mapLegacyPhysicalIdentity(
    row: LegacyPhysicalIdentityRow,
): LegacyPhysicalIdentityMapping? {
    if (row.entryKey.isEmpty() || row.relativePath.isEmpty()) return null
    val root = row.sourceRootUri?.takeIf(String::isNotBlank) ?: run {
        if (row.sourceRootUri != null || row.locationKind != "MANAGED_PATH") return null
        val managedPath = row.managedPath ?: return null
        val suffix = "::${row.relativePath}"
        if (!row.entryKey.endsWith(suffix)) return null
        val derived = row.entryKey.dropLast(suffix.length)
        if (derived.isBlank()) return null
        val expectedManagedPath =
            if (derived.endsWith('/')) derived + row.relativePath else "$derived/${row.relativePath}"
        if (managedPath != expectedManagedPath) return null
        derived
    }
    if (row.entryKey != "$root::${row.relativePath}") return null
    val physicalEntryKey = try {
        physicalResidentKey(root, row.relativePath)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return LegacyPhysicalIdentityMapping(
        sourceRootUri = root,
        relativePath = row.relativePath,
        physicalEntryKey = physicalEntryKey,
    )
}
