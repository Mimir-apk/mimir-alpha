package com.gruning.mimir.rewrite.core.resolver.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.model.library.PreviewVideoSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Android storage operations kept behind a tiny seam so candidate resolution stays offline-unit-testable. */
internal interface LocalArtAccess {
    fun existingFileUri(path: String): String?
    fun documentDisplayName(documentUri: String): String?
    fun parentDocumentUri(documentUri: String): String?
    fun existingChildUri(parentDocumentUri: String, displayName: String, requiredMimeType: String? = null): String?

    /** Drop any storage-enumeration caches so newly written media becomes discoverable. Default no-op. */
    fun invalidateCaches() {}

    /**
     * Whether this directory was actually READ, as opposed to having failed to enumerate.
     *
     * The distinction is the whole point: a directory that would not enumerate and a directory that
     * genuinely holds no matching file both produce "no art" today, and the caller cannot tell them
     * apart. One is a fact; the other is an unanswered question wearing a fact's clothes.
     *
     * Default true — a plain-file probe cannot be inconclusive, and a test double answering with a
     * literal map has nothing to fail at.
     */
    fun enumerationSettled(parentDocumentUri: String): Boolean = true
}

private class AndroidLocalArtAccess(private val context: Context) : LocalArtAccess {

    /**
     * Measured on a 4,420-game SNES directory: resolving art per game used to re-enumerate the
     * WHOLE parent directory through MediaProvider for every probe — parent confirmation + one
     * enumeration per extension per media kind. At 5,529 games that is tens of millions of cursor
     * rows and the gamelist never painted. Each directory is now enumerated ONCE per process and
     * indexed (by display name for probes, by document id for parent confirmation); every probe
     * after that is a map hit. [invalidateCaches] flushes so batch-fetched art stays discoverable.
     */
    private data class DirIndex(
        val byName: Map<String, List<SafChild>>,
        val ids: Set<String>,
    )

    private val dirCache = ConcurrentHashMap<String, DirIndex>()
    private val displayNameCache = ConcurrentHashMap<String, String>()
    private val parentCache = ConcurrentHashMap<String, String>()

    override fun invalidateCaches() {
        dirCache.clear()
        displayNameCache.clear()
        parentCache.clear()
    }

    /**
     * A directory listing that is only CACHED when it actually succeeded.
     *
     * `children` returns null when the provider query threw or handed back no cursor. That used to be
     * flattened to an empty list and then memoized, which is the defect: one transient SAF failure marked
     * every game in that folder art-less **for the life of the process**, with the files sitting on disk
     * the whole time. Nothing retried, because the empty answer looked settled.
     *
     * Now a failure is not cached, so the very next probe tries again, and [enumerationSettled] can tell
     * the caller that "no art" was never established for that folder.
     */
    private fun indexedChildren(parent: Uri): DirIndex {
        val key = parent.toString()
        dirCache[key]?.let { return it }
        val listed = children(parent) ?: return EmptyDirIndex
        if (dirCache.size > MaxCachedDirectories) dirCache.clear()
        val index = DirIndex(
            byName = listed.groupBy { it.displayName },
            ids = listed.mapTo(HashSet()) { it.documentId },
        )
        dirCache[key] = index
        return index
    }

    // A directory is settled exactly when a successful listing of it is in hand. A cache clear makes it
    // briefly unsettled again, which is honest: at that moment we genuinely have not read it.
    override fun enumerationSettled(parentDocumentUri: String): Boolean =
        dirCache.containsKey(parentDocumentUri)

    override fun existingFileUri(path: String): String? {
        val file = File(path)
        return if (file.isFile) Uri.fromFile(file).toString() else null
    }

    override fun documentDisplayName(documentUri: String): String? {
        displayNameCache[documentUri]?.let { return it.ifEmpty { null } }
        if (displayNameCache.size > MaxCachedNames) displayNameCache.clear()
        val uri = runCatching { Uri.parse(documentUri) }.getOrNull() ?: return null
        val resolved = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val name = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (name >= 0 && cursor.moveToFirst()) cursor.getString(name) else null
            }
        }.getOrNull()
        displayNameCache[documentUri] = resolved.orEmpty()
        return resolved
    }

    override fun parentDocumentUri(documentUri: String): String? {
        parentCache[documentUri]?.let { return it.ifEmpty { null } }
        if (parentCache.size > MaxCachedNames) parentCache.clear()
        val resolved = resolveParentDocumentUri(documentUri)
        parentCache[documentUri] = resolved.orEmpty()
        return resolved
    }

    private fun resolveParentDocumentUri(documentUri: String): String? {
        val romUri = runCatching { Uri.parse(documentUri) }.getOrNull() ?: return null
        val romId = runCatching { DocumentsContract.getDocumentId(romUri) }.getOrNull() ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(romUri) }.getOrNull() ?: return null
        val treeUri = DocumentsContract.buildTreeDocumentUri(romUri.authority, treeId)

        val hierarchicalParent = romId.substringBeforeLast('/', missingDelimiterValue = "")
        if (hierarchicalParent.isNotEmpty()) {
            val candidate = DocumentsContract.buildDocumentUriUsingTree(treeUri, hierarchicalParent)
            if (romId in indexedChildren(candidate).ids) return candidate.toString()
        }

        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val pending = ArrayDeque<Uri>().apply { add(root) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && visited.size < MaxParentSearchNodes) {
            val parent = pending.removeFirst()
            val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull() ?: continue
            if (!visited.add(parentId)) continue
            val index = indexedChildren(parent)
            if (romId in index.ids) return parent.toString()
            index.byName.values.asSequence().flatten()
                .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
                .mapTo(pending) { DocumentsContract.buildDocumentUriUsingTree(parent, it.documentId) }
        }
        return null
    }

    override fun existingChildUri(
        parentDocumentUri: String,
        displayName: String,
        requiredMimeType: String?,
    ): String? {
        val parent = runCatching { Uri.parse(parentDocumentUri) }.getOrNull() ?: return null
        val child = indexedChildren(parent).byName[displayName]?.firstOrNull {
            requiredMimeType == null || it.mimeType == requiredMimeType
        } ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(parent, child.documentId).toString()
    }

    /** Null means "could not read this directory" — never confuse it with an empty one. */
    private fun children(parent: Uri): List<SafChild>? = runCatching {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (id < 0 || name < 0 || mime < 0) return@use emptyList()
            buildList {
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(id) ?: continue
                    val childName = cursor.getString(name) ?: continue
                    add(SafChild(childId, childName, cursor.getString(mime)))
                }
            }
        }
    }.getOrNull()

    private data class SafChild(
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
    )

    private companion object {
        val EmptyDirIndex = DirIndex(byName = emptyMap(), ids = emptySet())
        const val MaxParentSearchNodes = 4_096
        // Real libraries hold a handful of per-platform dirs (+ media_art each); the caps only guard
        // against pathological trees. Clearing on overflow is coarse but self-heals in one listing.
        const val MaxCachedDirectories = 64
        const val MaxCachedNames = 32_768
    }
}

/**
 * The production [ArtSource]: OFFLINE, LOCAL-ONLY cover, clear-logo, Hero, and physical-media resolution.
 * Looks for image files next to a game's primary ROM — `<base>.<ext>` for covers and distinct suffixed
 * siblings for every other media role. Zero network: it only ever returns a URI for a file that ALREADY
 * EXISTS on the device; a miss returns null so the UI can use its honest fallback. Sibling of
 * [BundledGenreSource].
 *
 * Two source shapes (mirroring `ScanRecognizer`): a SAF tree grant stores the ROM's `sourceDocumentUri`
 * (`content://…/tree/…/document/…`) — the sibling doc is derived via [DocumentsContract] and probed with a
 * cheap `ContentResolver` query; a plain-file source stores `managedPath` — the sibling is a [File] on disk.
 *
 * Existence probes are MEMOIZED independently by media kind (one probe per unique ROM locator for the
 * process life) so re-emissions of the gamelist Flow never re-hit storage. [invalidate] makes newly written
 * local media visible without waiting for another process.
 */
class LocalArtSource internal constructor(private val access: LocalArtAccess) : ArtSource, PreviewVideoSource {

    constructor(context: Context) : this(AndroidLocalArtAccess(context))

    // ROM locator (documentUri or managedPath) -> resolved media URI, or "" when known-absent. "" avoids the
    // ConcurrentHashMap null-value restriction while still memoizing misses.
    private val coverCache = ConcurrentHashMap<String, String>()
    private val logoCache = ConcurrentHashMap<String, String>()
    private val heroCache = ConcurrentHashMap<String, String>()
    private val physicalMediaCache = ConcurrentHashMap<String, String>()
    private val audioCache = ConcurrentHashMap<String, String>()

    // Per ROM locator: did the last probe actually READ the folder? Written by [resolve] as a by-product of
    // work it already did, so asking the question later is free.
    private val settledCache = ConcurrentHashMap<String, Boolean>()

    override fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String? =
        resolve(sourceDocumentUri, managedPath, IMAGE_EXTS, nameSuffix = "", into = coverCache)

    /**
     * False when the ROM's own folder could not be READ, so a null from any probe above is an unanswered
     * question rather than an established absence. See [ArtSource.artResolutionSettled].
     *
     * A plain-file source is always settled — [java.io.File.isFile] cannot be inconclusive. A SAF source
     * whose display name or parent will not resolve is NOT settled: we never got far enough to look.
     */
    override fun artResolutionSettled(sourceDocumentUri: String?, managedPath: String?): Boolean {
        val key = sourceDocumentUri ?: managedPath ?: return true
        // Answered from what the probes already learned. Recomputing would cost an extra
        // parentDocumentUri call per row, and SD01's probe budget -- one probe per locator per media kind,
        // never a re-enumeration -- is a contract with a test that counts the calls.
        settledCache[key]?.let { return it }
        if (sourceDocumentUri == null || !sourceDocumentUri.startsWith("content://")) return true
        val parent = access.parentDocumentUri(sourceDocumentUri) ?: return false
        return access.enumerationSettled(parent)
    }

    /** A distinct LOCAL `<base>-logo.<image-ext>` clear logo beside the ROM or in `media_art`. */
    override fun logoUriFor(sourceDocumentUri: String?, managedPath: String?): String? =
        resolve(sourceDocumentUri, managedPath, IMAGE_EXTS, nameSuffix = "-logo", into = logoCache)

    /** A distinct LOCAL `<base>-hero.<image-ext>` title-screen/fan-art backdrop. */
    override fun heroUriFor(sourceDocumentUri: String?, managedPath: String?): String? =
        resolve(sourceDocumentUri, managedPath, IMAGE_EXTS, nameSuffix = "-hero", into = heroCache)

    /** A real provider-scraped cartridge/disc/card render, never a shaped cover substitute. */
    override fun physicalMediaUriFor(sourceDocumentUri: String?, managedPath: String?): String? =
        resolve(sourceDocumentUri, managedPath, IMAGE_EXTS, nameSuffix = "-support", into = physicalMediaCache)

    /**
     * The game's own sound, `media_art/<base>-audio.m4a`, for the launch animation. Only the audio slot is read here.
     *
     * A full wipe erases install-local provenance records. For this audio slot, Mimir
     * trusts a `-audio.m4a` it finds in `media_art` the way it already trusts the card's images (by name), so the
     * sound survives a wipe on the card.
     *
     * Why that is safe for a sound and was not for a clip: nothing paints this. It has no picture, so a stranger's file
     * in that folder can at worst be a wrong SOUND under a launch animation, never a moving backdrop on a screen
     * someone is reading. It is searched in `media_art` ONLY ([managedOnly]) and never beside the ROM, where another
     * frontend's leftovers live. Its own extension (`m4a`) and suffix (`-audio`) cannot collide with an image or a clip.
     *
     * It does NOT touch [videoUriFor]: that stays null, below, for every input. The video tier and `.mp4` remain excluded.
     */
    override fun audioUriFor(sourceDocumentUri: String?, managedPath: String?): String? =
        resolve(
            sourceDocumentUri,
            managedPath,
            AUDIO_EXTS,
            nameSuffix = "-audio",
            into = audioCache,
            managedOnly = true,
        )

    /**
     * **The clip tier is dropped: this always answers null**; the AUDIO slot ([audioUriFor]) is independent. A `-audio.m4a`
     * never makes this answer anything but null.
     *
     * The previous implementation dropped the sibling probe but kept `media_art`, assuming that `media_art` was exclusively written by Mimir
     * and could therefore supply managed clips. Two things turned out to
     * be wrong with that.
     *
     * Nothing produces one. The library-wide download was retired, and the only
     * remaining function that would have written a clip into `media_art` had no callers at all; it is
     * deleted with this change.
     *
     * And `media_art` is not exclusively Mimir's. A Retroid Pocket Nova carrying a previous install's
     * files had `.mp4`s sitting INSIDE `media_art`, and they played — adopted as a deliberate choice
     * purely because of which folder they were in. Location was standing in for provenance, and on any
     * device that has run another frontend, an older build, or a restored card, it does not hold. That is
     * an unrequested moving backdrop adopted from a folder alone.
     *
     * A slot with no producer and one known false-positive source should not have a consumer. An explicit
     * pick is untouched and never came through here: Customize Media writes to `CustomHeroStore` under the
     * CLIP selection marker, which this has never read.
     *
     * **This is the re-entry point.** If a managed clip download is ever reintroduced, it comes back
     * through this KDoc, and it needs to bring provenance with it — a record that THIS install wrote the
     * file — not just a path. The `managedOnly` plumbing below is kept for that day; it is currently
     * reached by nothing, which is deliberate and is why this comment exists.
     */
    override fun videoUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

    /**
     * Evict the memoized cover + clear-logo + video probes for one ROM locator. Called after a per-game
     * rescrape writes new local media so it can be resolved without waiting for the next process.
     */
    override fun invalidate(sourceDocumentUri: String?, managedPath: String?) {
        val key = sourceDocumentUri ?: managedPath ?: return
        coverCache.remove(key)
        logoCache.remove(key)
        heroCache.remove(key)
        physicalMediaCache.remove(key)
        audioCache.remove(key)
        settledCache.remove(key)
        // A rescrape just WROTE new media next to this ROM — the enumeration cache must re-list the
        // directory or the fresh file stays invisible until process death.
        access.invalidateCaches()
    }

    /**
     * Evict every memoized probe (all locators, all media kinds). The batch fetch writes covers/logos/clips
     * for arbitrarily many games in one run; the misses it just filled are memoized as "known-absent" here,
     * so a batch-completion refresh must drop the whole memo, not one locator.
     */
    override fun invalidateAll() {
        coverCache.clear()
        logoCache.clear()
        heroCache.clear()
        physicalMediaCache.clear()
        audioCache.clear()
        settledCache.clear()
        access.invalidateCaches()
    }

    private fun resolve(
        sourceDocumentUri: String?,
        managedPath: String?,
        exts: List<String>,
        nameSuffix: String,
        into: ConcurrentHashMap<String, String>,
        /** True searches `media_art` ONLY, never the ROM's own folder. See [videoUriFor]. */
        managedOnly: Boolean = false,
    ): String? {
        val key = sourceDocumentUri ?: managedPath ?: return null
        into[key]?.let { return it.ifEmpty { null } }
        val resolved = when {
            sourceDocumentUri != null && sourceDocumentUri.startsWith("content://") ->
                // Records settledness from the parent it has already resolved -- no second lookup. SD01's
                // probe budget is one probe per locator per media kind, and a test counts the calls.
                resolveSaf(sourceDocumentUri, exts, nameSuffix, key, managedOnly)
            managedPath != null -> {
                settledCache[key] = true
                resolveFile(managedPath, exts, nameSuffix, managedOnly)
            }
            else -> {
                settledCache[key] = true
                null
            }
        }?.takeIf(::isLocalRenderUri).orEmpty()
        val settled = resolved.isNotEmpty() || settledCache[key] != false
        settledCache[key] = settled
        // A MISS is only memoized when the folder was actually read. Memoizing an unsettled miss is what
        // turned one transient SAF failure into "this game has no art" for the whole process life --
        // nothing retried, because the empty answer looked like a settled one.
        if (settled) into[key] = resolved
        return resolved.ifEmpty { null }
    }

    /**
     * Art on the local filesystem (a plain-file / demo source). Tries `media_art/<name>.<ext>` in the
     * subfolder beside the ROM, then a SIBLING image in the ROM\'s own folder — the two conventions
     * emulation frontends use, managed one first. See [artBases].
     */
    private fun resolveFile(
        romPath: String,
        exts: List<String>,
        nameSuffix: String,
        managedOnly: Boolean = false,
    ): String? {
        val base = baseWithoutExtension(romPath) ?: return null
        for (candidateBase in artBases(base, managedOnly)) {
            for (ext in exts) {
                access.existingFileUri("$candidateBase$nameSuffix.$ext")?.let { return it }
            }
        }
        return null
    }

    /** Art inside the granted SAF tree, resolved by provider-reported names (document IDs may be opaque). */
    private fun resolveSaf(
        romDocUri: String,
        exts: List<String>,
        nameSuffix: String,
        cacheKey: String,
        managedOnly: Boolean = false,
    ): String? {
        // Never got far enough to look: not an absence, an unanswered question.
        val romDisplayName = access.documentDisplayName(romDocUri) ?: return unsettled(cacheKey)
        val base = baseWithoutExtension(romDisplayName) ?: return unsettled(cacheKey)
        val parent = access.parentDocumentUri(romDocUri) ?: return unsettled(cacheKey)

        // Managed directory FIRST -- see [artBases] for the ordering rationale and measurement. This branch
        // spells the order out by hand rather than walking that list (SAF resolves by provider-reported
        // display name, not by path), so the two must be changed together or the same library answers
        // differently depending on how its root was added.
        val managedDirectory = access.existingChildUri(
            parentDocumentUri = parent,
            displayName = ManagedArtDirectory,
            requiredMimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        )
        if (managedDirectory != null) {
            for (ext in exts) {
                access.existingChildUri(managedDirectory, "$base$nameSuffix.$ext")?.let {
                    settledCache[cacheKey] = true
                    return it
                }
            }
        }
        if (!managedOnly) {
            for (ext in exts) {
                access.existingChildUri(parent, "$base$nameSuffix.$ext")?.let {
                    settledCache[cacheKey] = true
                    return it
                }
            }
        }
        // Asked AFTER the probes, so the directory index this reads is the one they just warmed.
        settledCache[cacheKey] = access.enumerationSettled(parent)
        return null
    }

    private fun unsettled(cacheKey: String): String? {
        settledCache[cacheKey] = false
        return null
    }

    companion object {
        // Common cover-art extensions, tried in order. PNG first (the shipping media_art convention); WebP is
        // the common modern cover format. All decode via the local-only Coil loader — never network.
        private val IMAGE_EXTS = listOf("png", "jpg", "jpeg", "webp")
        // The launch sound is written by Enrich as an audio-only MP4 and has exactly one name; no other audio container
        // is probed, so a stray .mp3/.ogg beside the art is never adopted.
        private val AUDIO_EXTS = listOf("m4a")
        private const val ManagedArtDirectory = "media_art"

        /** The path/id up to (not including) the LAST dot, but only when that dot is in the final segment. */
        private fun baseWithoutExtension(pathOrId: String): String? {
            val dot = pathOrId.lastIndexOf('.')
            val slash = pathOrId.lastIndexOf('/')
            return if (dot > slash + 1) pathOrId.substring(0, dot) else null
        }

        /**
         * The candidate art bases for a ROM base (path or SAF doc-id, extension already stripped): the
         * `media_art/<name>` subfolder beside it, THEN the SIBLING in the same folder.
         *
         * **`media_art` first.** It read sibling-first until then, and that is
         * why fetched 3D box art was not shown. Measured on the Thor the same day, one
         * 3DS game, two files:
         *
         *  - `ROMs/3ds/<name>.png` -- 400x352, mtime 2026-07-08, from the art pack the emulator supplied;
         *  - `ROMs/3ds/media_art/<name>.png` -- 554x705, mtime 2026-09-17 17:38, which is exactly
         *    ScreenScraper\'s box-3D render size for 3ds.
         *
         * 106 such siblings across the two sampled volumes. Every one of them won. The scrape had worked
         * perfectly and nothing on any surface ever read its output -- Library, Home shelf and the
         * onboarding favourites grid all resolve a cover through this list.
         *
         * `media_art` is the ONLY directory Mimir writes art into, so a sibling is by construction
         * foreign: art some other frontend left behind, which Mimir cannot version, replace or refresh.
         * Preferring it meant Mimir\'s own fetch could never win on any library that had ever been
         * scraped by something else.
         *
         * This does NOT demote a cover the player chose. An explicit pick rides `GameRow.wiredFace` and
         * outranks both tiers (C4) -- that is the whole reason it is a separate tier. Both entries here
         * are AUTOMATIC sources, and between two automatic sources the managed one wins.
         *
         * Accepted cost, stated plainly: someone who hand-drops a cover beside a ROM and never opens
         * Customize Media now loses it to a scrape. Customize Media is the supported way to say
         * "this one", and it still wins.
         *
         * Pure string manipulation -- works for both file paths and SAF document ids.
         */
        private fun artBases(base: String, managedOnly: Boolean = false): List<String> {
            val slash = base.lastIndexOf('/')
            // No directory to put a `media_art` beside. A bare name has only ever had one candidate, and
            // with [managedOnly] it has none -- returning the sibling here would be the exact leak the
            // flag exists to close.
            if (slash < 0) return if (managedOnly) emptyList() else listOf(base)
            val dir = base.substring(0, slash)
            val name = base.substring(slash + 1)
            val managed = "$dir/media_art/$name"
            return if (managedOnly) listOf(managed) else listOf(managed, base)
        }

        private fun isLocalRenderUri(uri: String): Boolean =
            uri.startsWith("file://", ignoreCase = true) || uri.startsWith("content://", ignoreCase = true)
    }
}
