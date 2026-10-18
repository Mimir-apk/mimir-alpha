package com.gruning.mimir.rewrite

/**
 * SD03 (after a Move, rescan only the folders touched, so the moved ROM
 * turns up in the example library). Which registered library source root(s) contain a plain filesystem
 * path, so a coordinator can call `LibraryRepository.rescan(rootUri)` on just the affected root(s)
 * instead of the whole library.
 *
 * Library sources are registered as SAF **tree** URI strings —
 * `MimirSettingsCoordinator.acceptSource` does `library.addSource(treeUri.toString(), role)` from
 * an `OpenDocumentTree` result, so a root looks like
 * `content://com.android.externalstorage.documents/tree/primary%3ARoms` — while the file manager
 * deals in plain paths like `/storage/emulated/0/Roms/Game.iso`. A naive `path.startsWith(rootUri)`
 * matches a tree-URI root against nothing, ever, so this decodes SAF tree roots to their real path
 * via [ExternalStorageDocPath.resolveTreeRoot] before comparing anything.
 *
 * Containment is by path **segment**, never raw string prefix: `/storage/emulated/0/RomsBackup`
 * must NOT match a registered root `/storage/emulated/0/Roms` even though the string is a
 * character-prefix of it — that is exactly the naive bug this object exists to avoid.
 *
 * **Nested roots are not collapsed to one.** If both `/storage/emulated/0/Roms` and
 * `/storage/emulated/0/Roms/PS2` are registered, [rootsContaining] returns BOTH, most-specific
 * first: whether a rescan of the parent root also covers the child's files is a
 * `LibraryRepositoryImpl` scanning detail this object has no evidence about, so silently dropping
 * a containing parent risks the exact silent-no-op class this feature exists to avoid. A caller
 * after full correctness rescans the whole list; a caller that only wants the closest root can
 * take `firstOrNull()`.
 *
 * Pure string work (JVM-testable, no android.net.Uri, no Android APIs): unrecognized root shapes
 * (other content providers, malformed URIs, anything not a `/`-rooted plain path) are skipped
 * rather than guessed at, matching [VolumeRootMatcher]'s posture for the unknown case.
 */
internal object LibraryRootMatcher {

    /**
     * Every registered root in [roots] that contains [path], most-specific (deepest) root first.
     * [path] is required to already be a plain, `/`-rooted filesystem path — that is the only
     * shape the file manager ever hands in — so anything else short-circuits to no match rather
     * than guessing at a meaning for it. Returns an empty list when nothing contains [path],
     * including when [roots] itself is empty.
     */
    fun rootsContaining(path: String, roots: List<String>): List<String> {
        if (!path.startsWith("/")) return emptyList()
        val pathSegments = segmentsOf(path)
        return roots
            .mapNotNull { root -> decodedRootPath(root)?.let { decoded -> root to segmentsOf(decoded) } }
            .filter { (_, rootSegments) -> isContained(rootSegments, pathSegments) }
            .sortedByDescending { (_, rootSegments) -> rootSegments.size }
            .map { (root, _) -> root }
    }

    /** A registered root string, decoded to a real path — or null for a shape we don't recognize. */
    private fun decodedRootPath(root: String): String? = when {
        root.startsWith("content://") -> ExternalStorageDocPath.resolveTreeRoot(root)
        root.startsWith("/") -> root
        else -> null
    }

    /**
     * [rootSegments] must be a segment-wise prefix of [pathSegments] — same length (the path IS
     * the root) or shorter (the path is a descendant). A shorter [pathSegments] (the root is a
     * descendant of the path, not the other way round) is never contained.
     */
    private fun isContained(rootSegments: List<String>, pathSegments: List<String>): Boolean =
        pathSegments.size >= rootSegments.size &&
            pathSegments.subList(0, rootSegments.size) == rootSegments

    /** Splits on `/` and drops empty segments, so leading/trailing/doubled slashes never matter. */
    private fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
}
