package com.gruning.mimir.rewrite.core.storage.scan

/**
 * Renders a human-friendly source path from a SAF tree URI or a plain file path — NEVER the raw UUID /
 * codename (the design constraint; the shipping product's `friendlyStoragePath()`). The
 * user should read "SD card › Mimir › Roms", not `content://…/tree/1234-5678:Mimir%2FRoms`.
 *
 * Pure string work and NO Android types, so it is trivially correct and testable.
 *
 * It used to decode through `android.net.Uri.decode` while claiming in this very sentence to be
 * testable -- and `Uri.decode` throws "not mocked" under plain JUnit, so every SAF-shaped case here was
 * unreachable from a JVM test. It now shares [SourceRootIdentity]'s percent decoder, which was written
 * pure for exactly this reason.
 */
object FriendlyPath {

    private const val SEP = " › "

    /**
     * Best-effort friendly label for any [rootUri] (SAF `content://…/tree/…` or a plain absolute path).
     * Falls back to the last path segment, then the raw string, but always strips a leading storage UUID.
     */
    fun of(rootUri: String): String = when {
        rootUri.startsWith("content://") -> fromSafTree(rootUri)
        else -> fromFilePath(rootUri)
    }

    /**
     * SAF tree URI → friendly label. A tree URI encodes the volume + a `:`-separated relative path, e.g.
     * `content://com.android.externalstorage.documents/tree/1234-5678%3AMimir%2FRoms`. We decode it,
     * translate the volume token to a friendly volume name ("SD card" / "Internal storage"), and join
     * the path segments with " › ".
     */
    private fun fromSafTree(rootUri: String): String {
        val raw = rootUri.substringAfter("/tree/", rootUri)
        val treePart = SourceRootIdentity.percentDecodeUtf8(raw) ?: raw
        // treePart looks like "1234-5678:Mimir/Roms" or "primary:Mimir/Roms".
        val volumeToken = treePart.substringBefore(':', "")
        val relative = treePart.substringAfter(':', treePart)
        val volumeLabel = friendlyVolume(volumeToken)
        val segments = relative.split('/').filter { it.isNotBlank() }
        return (listOfNotNull(volumeLabel.takeIf { it.isNotBlank() }) + segments).joinToString(SEP)
            .ifBlank { volumeLabel.ifBlank { rootUri } }
    }

    /** Plain absolute path → friendly label ("SD card › Mimir › Roms"), stripping the Android storage prefix. */
    private fun fromFilePath(path: String): String {
        var p = path.trim().trimEnd('/')
        // Common Android storage roots → a friendly volume name.
        val (volumeLabel, rest) = when {
            p.startsWith("/storage/emulated/0") -> "Internal storage" to p.removePrefix("/storage/emulated/0")
            p.startsWith("/sdcard") -> "Internal storage" to p.removePrefix("/sdcard")
            STORAGE_UUID.containsMatchIn(p) -> "SD card" to STORAGE_UUID.replaceFirst(p, "")
            p.startsWith("/storage") -> "SD card" to p.removePrefix("/storage")
            else -> "" to p
        }
        val segments = rest.split('/').filter { it.isNotBlank() }
        return (listOfNotNull(volumeLabel.takeIf { it.isNotBlank() }) + segments).joinToString(SEP)
            .ifBlank { segments.lastOrNull() ?: path }
    }

    /** A SAF volume token → friendly volume name. "primary" = internal; a UUID = a removable SD card. */
    private fun friendlyVolume(token: String): String = when {
        token.isBlank() -> ""
        token.equals("primary", ignoreCase = true) -> "Internal storage"
        token.equals("home", ignoreCase = true) -> "Internal storage"
        else -> "SD card" // a "1234-5678" volume UUID is a removable card; never show the raw UUID
    }

    // "/storage/1234-5678" style removable-volume prefix.
    private val STORAGE_UUID = Regex("""^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}""")
}
