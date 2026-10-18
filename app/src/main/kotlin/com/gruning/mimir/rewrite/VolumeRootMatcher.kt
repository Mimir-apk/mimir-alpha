package com.gruning.mimir.rewrite

import java.net.URLDecoder

/**
 * Which storage volume a library source root lives on.
 * Pure string work (JVM-pinned) so the monitor's reconcile logic is testable without Android.
 *
 * Recognized shapes:
 *  - SAF tree/document URIs on the externalstorage provider — the volume is the first tree
 *    segment's `<volume>:` prefix (`primary` = internal, never a card).
 *  - Plain `/storage/<volume>/…` paths (`emulated`/`self` = internal).
 * Anything else (unknown providers, malformed) returns null = "not tied to a removable volume";
 * the monitor treats those roots as always-present rather than guessing.
 */
internal object VolumeRootMatcher {
    private const val SAF_PREFIX = "content://com.android.externalstorage.documents/"
    private const val TREE_MARKER = "/tree/"

    fun volumeUuidOf(rootUri: String): String? {
        if (rootUri.startsWith(SAF_PREFIX)) {
            val at = rootUri.indexOf(TREE_MARKER)
            if (at < 0) return null
            val segment = rootUri.substring(at + TREE_MARKER.length).substringBefore('/')
            if (segment.isEmpty()) return null
            val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrNull() ?: return null
            val volume = decoded.substringBefore(':')
            return volume.takeUnless { it.isEmpty() || it.equals("primary", ignoreCase = true) }
        }
        if (rootUri.startsWith("/storage/")) {
            val volume = rootUri.removePrefix("/storage/").substringBefore('/')
            return volume.takeUnless {
                it.isEmpty() || it.equals("emulated", ignoreCase = true) || it.equals("self", ignoreCase = true)
            }
        }
        return null
    }

    /**
     * The reconcile decision: a root is present unless it names a removable volume that is NOT in
     * [mountedUuids] (uppercase). Volume-less roots (internal storage, unknown providers) never
     * "leave" — flipping those on a bad parse would strand a whole internal library.
     */
    fun rootPresent(rootUri: String, mountedUuids: Set<String>): Boolean {
        val uuid = volumeUuidOf(rootUri)?.uppercase() ?: return true
        return uuid in mountedUuids
    }
}
