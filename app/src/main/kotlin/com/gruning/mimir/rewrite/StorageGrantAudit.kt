package com.gruning.mimir.rewrite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **The SAF grant a sibling package is holding instead of us.**
 *
 * Device truth, Retroid Pocket Nova, 2026-09-11. The library's only `source_roots` row is
 * `rootUri=content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs`,
 * `locationKind=SAF_TREE_GRANT`, `friendlyLabel="SD card › ROMs"`, and all 19 games hang off it.
 * `dumpsys activity permissions` showed that exact tree grant persisted to the sibling debug package
 * `com.gruning.mimir.rewrite.devlab`, with production `com.gruning.mimir.rewrite` holding NO persisted
 * document grant at all. The result was 255 `Permission Denial: opening provider
 * com.android.externalstorage.ExternalStorageProvider … requires that you obtain access using
 * ACTION_OPEN_DOCUMENT` lines in 25 minutes: every SD-card art lookup failed (logos, SD heroes, SD
 * covers), the launch intro showed a hero out of the app-private SteamGridDB store with no logo beside
 * it, and **nothing on screen said why** — the shelf looked like a half-arted library rather than a
 * revoked grant.
 *
 * This file is the app noticing that for itself. It is deliberately split in two:
 *
 * - [auditStorageGrants] and its helpers are **pure** — no `Context`, no `ContentResolver`, no Room, no
 *   `android.net.Uri` (whose static parser is a throwing stub in a JVM unit test). Every rule about what
 *   "covered" means is decided here and is testable off-device in `StorageGrantAuditTest`.
 * - [StorageGrantRecovery] owns lifecycle work and reads the live lists through its Android adapter.
 *   [StorageGrantAuditCoordinator] retains the process prompt state and routes to the current owner.
 *
 * Posture: this NEVER touches the network and never logs a credential. Document URIs are logged, which
 * is the same class of value `MimirLibraryProbe` already prints (a volume id and folder name the player
 * chose themselves), and is exactly what makes a `lost=[…]` line actionable.
 */

/** One `source_roots` row, reduced to the three columns the audit needs. */
data class StoredRoot(
    /** `SourceRootEntity.rootUri` — a SAF tree URI, or an absolute path for a managed root. */
    val rootUri: String,
    /** `SourceRootEntity.locationKind` — a `ResidentLocationKind` name; `SAF_TREE_GRANT` or `MANAGED_PATH`. */
    val locationKind: String,
    /** `SourceRootEntity.friendlyLabel` — the human string the prompt shows ("SD card › ROMs"). */
    val friendlyLabel: String,
)

/** One entry of `ContentResolver.getPersistedUriPermissions()`, reduced the same way. */
data class PersistedGrant(
    val uri: String,
    /** `UriPermission.isReadPermission`. A write-only grant cannot open a document and never counts. */
    val read: Boolean,
)

/**
 * The `ResidentLocationKind` name the audit is about. `MANAGED_PATH` roots live inside Mimir's own
 * storage and need no grant, so they can never be "lost" this way and are never reported.
 */
internal const val SafTreeGrantLocationKind = "SAF_TREE_GRANT"

/** Logcat tag for every line this subsystem writes. */
internal const val StorageGrantLogTag = "MimirStorageGrant"

/**
 * The SAF tree roots in [roots] that no READ grant in [grants] covers — i.e. the roots whose games the
 * app can no longer open.
 *
 * Order is the caller's: the returned list preserves [roots]' order so the prompt shows the primary
 * source first, exactly as `SourceRootDao.all()` already sorts it.
 *
 * A root whose `rootUri` cannot be parsed as a SAF tree URI is reported as LOST rather than silently
 * skipped. A `SAF_TREE_GRANT` row that does not name a tree is already broken, and "I could not tell"
 * must never render as "everything is fine" .
 */
fun auditStorageGrants(roots: List<StoredRoot>, grants: List<PersistedGrant>): List<StoredRoot> {
    val readTrees = grants.filter(PersistedGrant::read).mapNotNull { safTreeIdentity(it.uri) }
    return roots
        .filter { it.locationKind == SafTreeGrantLocationKind }
        .filter { root ->
            val rootTree = safTreeIdentity(root.rootUri) ?: return@filter true
            readTrees.none { grant -> safTreeCovers(grant, rootTree) }
        }
}

/** A SAF tree URI reduced to the two things ancestry depends on. */
internal data class SafTreeIdentity(val authority: String, val documentId: String)

/**
 * Parse `content://<authority>/tree/<encoded document id>[/document/…]` into [SafTreeIdentity], or null
 * when [uri] is not a SAF tree URI at all (a `file:` path, a managed absolute path, junk).
 *
 * Hand-rolled rather than `Uri.parse` + `DocumentsContract.getTreeDocumentId` on purpose: both are
 * throwing stubs under plain JUnit, and the whole point of this function is that the covering rule can be
 * tested without a device. Splitting the raw string on `/` is safe because a document id's own `/`
 * separators are always `%2F`-encoded inside a single path segment — that is precisely why the
 * comparison below must be made on the DECODED id (see [safTreeCovers]).
 */
internal fun safTreeIdentity(uri: String): SafTreeIdentity? {
    val withoutScheme = uri.removePrefix("content://")
    if (withoutScheme.length == uri.length) return null
    val path = withoutScheme.substringBefore('?').substringBefore('#')
    val segments = path.split('/')
    if (segments.size < 3) return null
    val authority = segments[0]
    if (authority.isEmpty()) return null
    val treeIndex = segments.subList(1, segments.size).indexOf("tree").takeIf { it >= 0 } ?: return null
    val documentSegment = segments.getOrNull(treeIndex + 2) ?: return null
    if (documentSegment.isEmpty()) return null
    return SafTreeIdentity(
        // Authorities are case-insensitive; document ids are NOT (a FAT volume id is upper-case and a
        // folder name is whatever the player typed), so only this half is normalised.
        authority = authority.lowercase(),
        documentId = decodePercentEncoded(documentSegment),
    )
}

/**
 * Does the persisted grant [grant] let us open everything under the registered root [root]?
 *
 * **The ancestor rule, stated exactly.** Same authority, and the grant's decoded document id is the
 * root's own id or a proper ancestor of it:
 * - `…:ROMs` covers `…:ROMs` — the exact match, the ordinary case;
 * - `…:ROMs` covers `…:ROMs/gamecube` — an ancestor grant covers a root nested under it;
 * - `…:ROMs/ps2` does NOT cover `…:ROMs` — a grant on a CHILD is not access to the parent, which is the
 *   direction that actually fails on device;
 * - `…:ROMsBackup` is NOT covered by `…:ROMs` — ancestry is by path SEGMENT, never by raw string prefix,
 *   so the separator is required;
 * - `7BF4-F932:` (the volume root) covers `7BF4-F932:ROMs`, because on external storage the `:` after the
 *   volume id is itself the separator and a volume-root document id carries no trailing `/`.
 *
 * The comparison is on DECODED ids, so `tree/7BF4-F932%3AROMs` and `tree/7BF4-F932:ROMs` — the same grant
 * written two ways, which is exactly what a hand-built probe URI vs. a picker result look like — compare
 * equal instead of reading as two unrelated trees.
 */
internal fun safTreeCovers(grant: SafTreeIdentity, root: SafTreeIdentity): Boolean =
    grant.authority == root.authority && isDocumentAncestorOrSame(grant.documentId, root.documentId)

/** The pure path-segment ancestry test behind [safTreeCovers]. */
internal fun isDocumentAncestorOrSame(ancestor: String, descendant: String): Boolean = when {
    ancestor == descendant -> true
    ancestor.isEmpty() -> false
    // A document id that already ends in its own separator ("7BF4-F932:" volume root, or a trailing "/")
    // needs no second one appended, or the required separator would be doubled and never match.
    ancestor.endsWith(':') || ancestor.endsWith('/') -> descendant.startsWith(ancestor)
    else -> descendant.startsWith("$ancestor/")
}

/**
 * Percent-decode one URI path segment to the string the provider actually keyed on.
 *
 * Deliberately not `java.net.URLDecoder`: that decodes `+` as a space, which is an
 * `application/x-www-form-urlencoded` rule and not a path rule — a folder literally named `Mario+Luigi`
 * would decode to `Mario Luigi` and stop matching its own grant. Bytes are accumulated before decoding so
 * a multi-byte UTF-8 folder name (`%C3%A9`) reassembles as one character rather than two mojibake ones.
 */
internal fun decodePercentEncoded(value: String): String {
    if (!value.contains('%')) return value
    val bytes = java.io.ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high >= 0 && low >= 0) {
                bytes.write((high shl 4) or low)
                index += 3
                continue
            }
        }
        bytes.write(char.toString().toByteArray(Charsets.UTF_8))
        index += 1
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

/**
 * Which lost root the prompt should currently show, or null for "show nothing".
 *
 * Pure so both halves of the rule are testable: the prompt is silent when nothing is lost (it must never
 * cover a healthy shelf), and silent once the player has pressed LATER in this process.
 */
internal fun storageGrantPromptRoot(lost: List<StoredRoot>, deferred: Boolean): StoredRoot? =
    if (deferred) null else lost.firstOrNull()

/** Process prompt state; each Activity supplies one explicitly attached recovery owner. */
internal object StorageGrantAuditCoordinator {
    private val _lostRoots = MutableStateFlow<List<StoredRoot>>(emptyList())
    val lostRoots: StateFlow<List<StoredRoot>> = _lostRoots.asStateFlow()
    private val _promptDeferred = MutableStateFlow(false)
    val promptDeferred: StateFlow<Boolean> = _promptDeferred.asStateFlow()
    private var recovery: StorageGrantRecovery? = null
    private var lastLoggedLine: String? = null

    fun attach(owner: StorageGrantRecovery) {
        if (recovery === owner) return
        recovery?.close()
        recovery = owner
    }

    fun detach(owner: StorageGrantRecovery) {
        owner.close()
        if (recovery === owner) recovery = null
    }

    internal fun publish(roots: List<StoredRoot>, grants: List<PersistedGrant>): List<StoredRoot> {
        val lost = auditStorageGrants(roots, grants)
        _lostRoots.value = lost
        val line = "audit roots=${roots.size} grants=${grants.size} lost=[${lost.joinToString { it.friendlyLabel }}]"
        if (line != lastLoggedLine) {
            lastLoggedLine = line
            logStorageGrantRecovery(line)
        }
        return lost
    }

    fun requestRegrant(root: StoredRoot) {
        recovery?.requestRegrant(root.rootUri)
    }

    /** LATER hides the prompt until the next process, without changing the audit verdict. */
    fun deferForProcess() {
        _promptDeferred.value = true
    }

    internal fun resetForTest() {
        recovery?.close()
        recovery = null
        _lostRoots.value = emptyList()
        _promptDeferred.value = false
        lastLoggedLine = null
    }
}
