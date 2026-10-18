package com.gruning.mimir.rewrite.core.model

/**
 * Where a resident file physically lives / how Mimir reaches it. Grounds the native
 * `library_entry.resolver_kind` + `source_kind` columns and the SAF-vs-managed-path distinction.
 */
enum class ResidentLocationKind {
    /** File is inside Mimir's own managed inbox/library folder, reachable by a plain absolute path. */
    MANAGED_PATH,

    /** File is reached through a persisted SAF tree grant (content:// URI); the common SD-card case. */
    SAF_TREE_GRANT,

    /** A durable external reference: the file lives in the user's designated library outside the inbox. */
    EXTERNAL_REFERENCE,
}

/**
 * Soft availability of a resident file. Ground truth: the native "resident verifier" uses soft
 * states and NEVER deletes (see `placement-ready-in-place`). `MISSING_PENDING` /
 * `STORAGE_UNAVAILABLE` mean "we can't see it right now", not "gone".
 */
enum class ResidentAvailability {
    /** The file is present and reachable now. */
    AVAILABLE,

    /** Not seen on the last scan but not confirmed deleted (e.g. SD temporarily unmounted). */
    MISSING_PENDING,

    /** The whole storage volume is unavailable; treat entries on it as unknown, not gone. */
    STORAGE_UNAVAILABLE,
}

/**
 * The evidence used to establish a file's game identity — mirrors `library_entry.identity_source` /
 * `title_source`. Drives which [DuplicateGroupKind] a group may use.
 */
enum class IdentitySource {
    /** No identity resolved beyond extension → platform. */
    NONE,

    /** Identity from the file's content hash (byte-exact). */
    CONTENT_HASH,

    /** Identity from an offline catalog title-id / serial match. */
    CATALOG,

    /** Identity read directly from the image (e.g. PS `SYSTEM.CNF` serial, header). */
    NATIVE_IDENTITY,

    /** Identity inferred from filename (disc-pattern / region tokens) — the weakest, never sole basis for cross-title collapse. */
    FILENAME,
}

/**
 * The resolved identity of a game group. Mimir Alpha keeps the *evidence* around so the UI and gates
 * can be honest about WHY two files collapsed (or didn't).
 *
 * @property gameId stable group id (see [GameId]).
 * @property title display title (deduped, human-facing).
 * @property region optional region token (e.g. "USA", "Europe") — a region variant collapses into a group.
 * @property version optional version/revision token (e.g. "Rev 5").
 * @property titleCatalogKey optional catalog key (catalog title-id / serial), when [source] proves it.
 * @property serial optional platform serial (e.g. PlayStation `SLUS-xxxxx`).
 * @property source how this identity was established.
 */
data class GameIdentity(
    val gameId: GameId,
    val title: String,
    val region: String? = null,
    val version: String? = null,
    val titleCatalogKey: String? = null,
    val serial: String? = null,
    val source: IdentitySource = IdentitySource.NONE,
)

/**
 * One physical file Mimir has indexed — the per-file truth, keyed by [entryKey]. Multiple
 * [ResidentFile]s can share one [GameIdentity.gameId] (multi-disc / cross-storage / region variants).
 *
 * This is the typed re-expression of the current native `library_entry` row (see
 * `MimirInboxStore.java` schema). It intentionally stays a pure data record: no behavior, no launch
 * logic — that lives in `core-launch`.
 *
 * @property entryKey stable per-file key (native `entry_key`).
 * @property platformId classified platform.
 * @property contentKind what this file is ([ContentKind]).
 * @property displayName raw file/display name.
 * @property location how Mimir reaches it.
 * @property availability soft availability (never "deleted").
 * @property sizeBytes file size; part of the multi-disc distinctness (discs differ in size).
 * @property contentHash optional content hash (for [DuplicateGroupKind.EXACT_HASH]); null if not yet hashed.
 * @property sourceRootUri exact owning source root when the resident came from a library scan. It may
 * be absent only on transient, pre-persistence launch fixtures; storage rejects a missing owner.
 * @property sourceDocumentUri optional exact SAF *document* `content://` URI for this file (DS/3DS/Switch need the exact document, not the tree root).
 * @property managedPath optional plain absolute path (for [ResidentLocationKind.MANAGED_PATH]).
 * @property relativePath path relative to the source root (used for stable cross-root keying).
 * @property discNumber 1-based disc number when part of a multi-disc set; 0 when not applicable.
 * @property identity resolved game identity/evidence, if any.
 */
data class ResidentFile(
    val entryKey: EntryKey,
    val platformId: PlatformId,
    val contentKind: ContentKind,
    val displayName: String,
    val location: ResidentLocationKind,
    val availability: ResidentAvailability = ResidentAvailability.AVAILABLE,
    val sizeBytes: Long = 0L,
    val contentHash: String? = null,
    val sourceRootUri: String? = null,
    val sourceDocumentUri: String? = null,
    val managedPath: String? = null,
    val relativePath: String = "",
    val discNumber: Int = 0,
    val identity: GameIdentity? = null,
) {
    /**
     * The best absolute-ish locator string available for this file, preferring a managed path, then
     * the exact SAF document URI, then (last resort) the SAF tree root. Callers in `core-launch` that
     * know the emulator's form should prefer [locatorFor] instead of this default order.
     */
    val bestLocator: String?
        get() = managedPath ?: sourceDocumentUri ?: sourceRootUri

    /**
     * The locator string to hand the emulator for the requested [form]:
     *  - [LaunchLocatorForm.CONTENT_URI] → the exact [sourceDocumentUri] (NEVER the tree root — DS/3DS/Switch need the document).
     *  - [LaunchLocatorForm.FILE_PATH]   → [managedPath] / the direct resident path.
     *  - [LaunchLocatorForm.EITHER]      → cheapest valid form: a direct path first, else the exact document URI.
     *  - [LaunchLocatorForm.NONE]        → no locator (open-emulator-only); "" so the caller supplies an empty locator.
     *
     * Returns null when no locator satisfies the requested form (the file is unreachable in that form
     * → the launch engine treats it as [LaunchReadiness.MissingFile]).
     */
    fun locatorFor(form: LaunchLocatorForm): String? = when (form) {
        LaunchLocatorForm.CONTENT_URI -> sourceDocumentUri
        LaunchLocatorForm.FILE_PATH -> managedPath
        LaunchLocatorForm.EITHER -> managedPath ?: sourceDocumentUri
        LaunchLocatorForm.NONE -> ""
    }
}
