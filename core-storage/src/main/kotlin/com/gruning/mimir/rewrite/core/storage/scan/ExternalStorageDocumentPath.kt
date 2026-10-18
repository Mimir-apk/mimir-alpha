package com.gruning.mimir.rewrite.core.storage.scan

/**
 * The `/storage/...` path an ExternalStorageProvider **document** uri names, or null when it names none.
 *
 * `core-launch` hands this path to a file-path emulator for a SAF-indexed file (its `externalStoragePathFromDocumentUri`
 * delegates here, so the string a launch records as its `locator` and the string a launch-history move looks for are one
 * function's output, never two copies that could drift). A tree-plus-document uri
 * (`.../tree/<id>/document/<id>`) and a bare document uri both read the `/document/` id; `home:` has no filesystem path.
 *
 * Unlike [SourceRootIdentity.canonicalOf] this does not normalise `.`/`..`/empty segments: a launch recorded the path as
 * spelled, so the match has to be the path as spelled.
 */
object ExternalStorageDocumentPath {

    private const val AUTHORITY = "content://com.android.externalstorage.documents/"

    fun pathOf(uri: String): String? {
        if (!uri.startsWith(AUTHORITY)) return null
        val pathOnly = uri.substringBefore('#').substringBefore('?')
        val encodedDocumentId = pathOnly.substringAfter("/document/", missingDelimiterValue = "")
            .substringBefore('/')
            .takeIf { it.isNotBlank() } ?: return null
        val documentId = SourceRootIdentity.percentDecodeUtf8(encodedDocumentId) ?: return null
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator).takeIf { it.isNotBlank() } ?: return null
        val relativePath = documentId.substring(separator + 1).trimStart('/')
        val root = when (volume.lowercase()) {
            "primary" -> "/storage/emulated/0"
            "home" -> return null
            else -> "/storage/$volume"
        }
        return if (relativePath.isBlank()) root else "$root/$relativePath"
    }
}
