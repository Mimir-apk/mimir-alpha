package com.gruning.mimir.rewrite.core.files

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.model`. Upstream path:
 * `core/model/src/main/java/com/explorer/fileexplorer/core/model/ConflictNamePolicy.kt`.
 *
 * **MIMIR CHANGE: Keep both uses numbered names instead of hashes.**
 * Upstream named the arriving file `name (copy-e33b4f5f40fe).ext`, a SHA-256 stub chosen so a retried
 * transfer would land on the same artifact. Numbered suffixes communicate the duplicate without exposing a hash in the file name. A Keep-both name is now `name (copy).ext`, then `name (copy 2).ext`,
 * `name (copy 3).ext` and so on: the first one that is free.
 *
 * **Collision safety is unchanged and is the caller's half of the contract.** [firstFree] is handed the
 * caller's own "is this name taken" test, so a name another entry of the same operation has already
 * claimed (see `ArchiveHelper`'s `claimed` set) is skipped exactly like a name already on disk. The
 * walk is bounded by [MAX_ATTEMPTS]; running out answers null rather than looping, and the caller turns
 * that into a refusal.
 */
object ConflictNamePolicy {
    /** How far the `(copy N)` walk will count before giving up. */
    const val MAX_ATTEMPTS = 1_000

    /**
     * The Keep-both name for [originalName]: attempt 1 is `name (copy).ext`, attempt N > 1 is
     * `name (copy N).ext`. The extension is the text after the LAST dot, and a leading dot (a dotfile) is
     * not an extension, so `.gitignore` becomes `.gitignore (copy)`.
     */
    fun keepBothName(originalName: String, attempt: Int): String {
        require(attempt >= 1) { "attempt starts at 1" }
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val extension = if (dot > 0) originalName.substring(dot) else ""
        val tag = if (attempt == 1) "(copy)" else "(copy $attempt)"
        return "$base $tag$extension"
    }

    /**
     * The first Keep-both name for [originalName] that [taken] does not claim, or null when the first
     * [MAX_ATTEMPTS] are all taken.
     */
    fun firstFree(originalName: String, taken: (String) -> Boolean): String? {
        for (attempt in 1..MAX_ATTEMPTS) {
            val candidate = keepBothName(originalName, attempt)
            if (!taken(candidate)) return candidate
        }
        return null
    }
}
