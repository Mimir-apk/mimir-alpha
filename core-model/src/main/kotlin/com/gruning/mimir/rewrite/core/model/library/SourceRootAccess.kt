package com.gruning.mimir.rewrite.core.model.library

/**
 * The reason a scan records when it declines to read a registered folder because Mimir has no right to list it
 * right now. Diagnostic only: the player reads the Settings sentence, never this.
 */
const val SourceRootUnreadableReason = "Mimir does not have access to read this folder right now"

/**
 * Whether listing [rootUri] depends on Android's "All files access".
 *
 * **Why a scan has to ask.** Without that grant, Android does not refuse a listing of a shared-storage folder: the
 * directory still `exists()`, still `canRead()`, and the listing simply comes back filtered, empty or showing only the
 * media files the app is allowed to see. A scan cannot tell that from a folder that really holds nothing, and the index
 * prune that follows a walk would delete every game under it (observed: 195 rows to 0 after the grant was revoked). So for these roots the grant itself is the proof that a listing can be trusted.
 *
 * A SAF tree (`content://`) is not covered here: its provider throws when the grant is gone, which already ends the
 * walk as unavailable. The only shared-storage folders that are readable without the grant are the app's OWN
 * `Android/data/<ownPackage>`, `Android/media/<ownPackage>` and `Android/obb/<ownPackage>`, and only when they sit
 * directly under a storage volume's root: another app's folder, `Android/data` itself, and a look-alike nested deeper in
 * the path all need the grant. With no [ownPackage] nothing is exempt. Everything that is not shared storage (a private
 * app path) is readable without it.
 */
fun sourceRootNeedsAllFilesAccess(rootUri: String, ownPackage: String? = null): Boolean {
    if (!rootUri.startsWith("/")) return false
    if (SharedStorageRoots.none { root -> rootUri == root || rootUri.startsWith("$root/") }) return false
    if (ownPackage.isNullOrBlank()) return true
    val ownFolder = Regex(
        "^" + VolumeRootPattern + "/Android/(?:data|media|obb)/" + Regex.escape(ownPackage) + "(?:/|\$)",
    )
    return !ownFolder.containsMatchIn(rootUri)
}

/**
 * Whether [rootUri] sits on a removable volume (an SD card or USB drive), whose mount state can change under a scan.
 * Internal storage (`/storage/emulated/<n>`, `/sdcard`) is never mid-mount and is not one.
 */
fun sourceRootIsOnRemovableVolume(rootUri: String): Boolean = RemovableVolume.containsMatchIn(rootUri)

private val SharedStorageRoots = listOf(
    "/storage",
    "/sdcard",
    "/mnt/sdcard",
    "/mnt/media_rw",
    "/mnt/runtime",
    "/data/media",
)

/** A storage volume's root, as the paths this app is handed spell it. */
private const val VolumeRootPattern =
    "(?:/storage/emulated/\\d+|/storage/self/primary|/storage/[^/]+|/sdcard|/mnt/sdcard|/mnt/media_rw/[^/]+" +
        "|/data/media/\\d+|/mnt/runtime/[^/]+/(?:emulated/\\d+|[^/]+))"

private val RemovableVolume = Regex(
    "^(?:/storage/(?!emulated(?:/|\$)|self(?:/|\$))[^/]+|/mnt/media_rw/[^/]+" +
        "|/mnt/runtime/[^/]+/(?!emulated(?:/|\$))[^/]+)(?:/|\$)",
)
