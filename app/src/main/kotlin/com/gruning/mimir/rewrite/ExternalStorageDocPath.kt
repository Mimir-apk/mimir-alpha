package com.gruning.mimir.rewrite

import java.net.URLDecoder

/**
 * Maps an
 * `externalstorage.documents` SAF document URI to its real filesystem path so bulk reads can use a
 * plain [java.io.File] when all-files access is granted. Every `ContentResolver.openInputStream`
 * on that provider pays a Binder round-trip plus MediaProvider bookkeeping per OPEN ("Open with
 * lower FS" + a lazy MediaStore insert) — ~11k opens at dirty-card scale was minutes of pure tax.
 *
 * Pure string work (JVM-testable, no android.net.Uri): the document id is `<volume>:<relative>`,
 * URL-encoded as the URI's last `/document/` segment; `primary` maps to `/storage/emulated/0`,
 * any other volume to `/storage/<volume>`. Returns null for anything that is not a plain,
 * traversal-free externalstorage document — callers must fall back to the ContentResolver.
 *
 * [resolveTreeRoot] decodes the same `<volume>:<relative>` doc-id shape from a SAF **tree** URI's
 * `/tree/` segment instead — added for SD03 (library-source-root → real-path matching), it shares
 * [splitDocId]/[pathFor] with [resolve] rather than re-deriving the volume/traversal rules.
 */
internal object ExternalStorageDocPath {
    private const val PREFIX = "content://com.android.externalstorage.documents/"
    private const val DOCUMENT_MARKER = "/document/"
    private const val TREE_MARKER = "/tree/"

    fun resolve(documentUri: String): String? {
        if (!documentUri.startsWith(PREFIX)) return null
        val marker = documentUri.lastIndexOf(DOCUMENT_MARKER)
        if (marker < 0) return null
        val encoded = documentUri.substring(marker + DOCUMENT_MARKER.length)
        // The doc id must be exactly one URI segment; anything else is not a shape we understand.
        if (encoded.isEmpty() || encoded.contains('/')) return null
        val docId = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        val (volume, relative) = splitDocId(docId) ?: return null
        // A /document/ URI names a specific file or folder; an empty relative half (bare volume
        // root) is a shape only a /tree/ root can mean, so resolve() itself still rejects it.
        if (relative.isEmpty()) return null
        return pathFor(volume, relative)
    }

    /**
     * A SAF tree root — e.g. `content://.../tree/primary%3ARoms` — decoded to its real path.
     * Unlike [resolve], an empty relative half is valid here (the user granted the whole volume,
     * doc id `primary:` with nothing after the colon) and resolves to the bare volume root.
     * Uses the first `/tree/` segment (stopping at the next `/`), so a combined tree+document
     * grant URI still resolves from its tree half, matching [VolumeRootMatcher]'s convention of
     * treating the tree segment as the identity-bearing one.
     */
    fun resolveTreeRoot(treeUri: String): String? {
        if (!treeUri.startsWith(PREFIX)) return null
        val marker = treeUri.indexOf(TREE_MARKER)
        if (marker < 0) return null
        val encoded = treeUri.substring(marker + TREE_MARKER.length).substringBefore('/')
        if (encoded.isEmpty()) return null
        val docId = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        val (volume, relative) = splitDocId(docId) ?: return null
        return pathFor(volume, relative)
    }

    /**
     * `<volume>:<relative>` → validated `(volume, relative)`, rejecting traversal/empty path
     * segments and a volume containing `/` or `.`. `relative` may come back empty — a bare volume
     * root is a meaningful tree shape; callers that require a real child (like [resolve]) reject
     * emptiness themselves.
     */
    private fun splitDocId(docId: String): Pair<String, String>? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1)
        if (volume.any { it == '/' || it == '.' }) return null
        if (relative.isNotEmpty()) {
            val segments = relative.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        }
        return volume to relative
    }

    private fun pathFor(volume: String, relative: String): String {
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isEmpty()) root else "$root/$relative"
    }
}
