package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.GameGroup
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.model.identity.logicalGameLaunchRank

/**
 * Groups a batch of resident files into console-clean [GameGroup]s — the dedup stage that turns dumps
 * into games . This is an INTERFACE (+ a first concrete
 * [KeyingDedupGrouper]) so a later catalog-title-id slice can swap in a stronger keyer without
 * disturbing callers.
 *
 * Grouping rules:
 *  - Only a HARD identity key auto-collapses (exact hash, catalog title-id/serial, multi-disc group).
 *    Filename/title alone NEVER collapses two files (avoids merging different games).
 *  - Multi-disc → one group; `.m3u` primary when present, else lowest disc.
 *  - Cross-storage dupes → one group, SD/primary-source preferred.
 *  - Byte-identical dumps always merge regardless of storage.
 */
interface DedupGrouper {
    /** Fold a batch of files into groups. Every input file lands in exactly one group. */
    fun group(files: List<ResidentFile>): List<GameGroup>
}

/**
 * The grouping key a file contributes — the strongest HARD identity available, in precedence order.
 * Two files share a group iff they produce the same [GroupingKey]. A file with no hard key gets a
 * SINGLETON key (its own entry key) so it never accidentally collapses with anything.
 *
 * Precedence (strongest first) mirrors [DuplicateGroupKind]:
 *  1. EXACT_HASH — same content hash.
 *  2. CATALOG_TITLE_ID — same catalog title-id / serial.
 *  3. MULTI_DISC — same platform + normalized base title (disc token stripped).
 *  4. singleton — nothing hard; stands alone.
 */
sealed interface GroupingKey {
    val kind: DuplicateGroupKind?

    data class ExactHash(val hash: String) : GroupingKey {
        override val kind = DuplicateGroupKind.EXACT_HASH
    }

    data class CatalogTitleId(val platform: String, val catalogKey: String) : GroupingKey {
        override val kind = DuplicateGroupKind.CATALOG_TITLE_ID
    }

    data class MultiDiscBase(val platform: String, val baseTitle: String) : GroupingKey {
        override val kind = DuplicateGroupKind.MULTI_DISC
    }

    /**
     * A single disc split into tracks: a `.cue`/`.gdi` manifest + its `.bin`/`.img` tracks that
     * share ONE folder and the same normalized base title. Folded to one tile with the manifest as the
     * launch primary. Folder-scoped so two games' track sets in one folder never cross-collapse.
     */
    data class TrackSetBase(val platform: String, val baseTitle: String, val folder: String) : GroupingKey {
        override val kind = DuplicateGroupKind.MULTI_DISC
    }

    /** No hard identity → its own group (never auto-collapses). */
    data class Singleton(val entryKey: String) : GroupingKey {
        override val kind: DuplicateGroupKind? = null
    }
}

/**
 * Computes the [GroupingKey] for a single file. Extracted so the keying policy (which the catalog
 * slice will strengthen) is independent of the folding mechanics in [KeyingDedupGrouper].
 */
interface GroupingKeyer {
    fun keyFor(file: ResidentFile): GroupingKey
}

/**
 * Default keyer: strongest-first over the evidence already on the [ResidentFile]. Multi-disc detection
 * is filename-token based (disc pattern) + platform + normalized base title — the reliable-NOW slice
 * (filename-based grouping). Region/version collapse relies on a catalog key
 * being present in `identity.titleCatalogKey`; without one, region siblings intentionally do NOT
 * collapse (that lands with the catalog resolver, not filename normalization).
 */
class DefaultGroupingKeyer : GroupingKeyer {

    override fun keyFor(file: ResidentFile): GroupingKey {
        file.contentHash?.takeIf { it.isNotBlank() }?.let { return GroupingKey.ExactHash(it) }

        val catalogKey = file.identity?.titleCatalogKey ?: file.identity?.serial
        if (!catalogKey.isNullOrBlank()) {
            return GroupingKey.CatalogTitleId(file.platformId.value, catalogKey)
        }

        if (isMultiDiscMember(file)) {
            val base = normalizedBaseTitle(baseNameOf(file))
            if (base.isNotBlank()) {
                return GroupingKey.MultiDiscBase(file.platformId.value, base)
            }
        }

        if (isTrackSetMember(file)) {
            val base = normalizedBaseTitle(baseNameOf(file))
            if (base.isNotBlank()) {
                return GroupingKey.TrackSetBase(file.platformId.value, base, folderOf(file))
            }
        }

        return GroupingKey.Singleton(file.entryKey.value)
    }

    /** True when the file is a disc member or an `.m3u` playlist, or its name carries a disc token. */
    private fun isMultiDiscMember(file: ResidentFile): Boolean =
        file.contentKind == ContentKind.DISC_MEMBER ||
            file.contentKind == ContentKind.MULTI_DISC_PLAYLIST ||
            file.discNumber > 0 ||
            DISC_TOKEN.containsMatchIn(file.displayName)

    /**
     * True when the file belongs to a single-disc TRACK SET: a `.cue`/`.gdi` manifest
     * ([ContentKind.TRACK_MANIFEST]) or a `.bin`/`.img` track. Folded per folder + base title so the
     * manifest and its tracks become one tile. `(Track N)` is a parenthetical, so [normalizedBaseTitle]
     * already strips it — the manifest and its tracks share a base.
     */
    private fun isTrackSetMember(file: ResidentFile): Boolean =
        file.contentKind == ContentKind.TRACK_MANIFEST || extensionOf(file) in TRACK_FILE_EXTS

    private fun extensionOf(file: ResidentFile): String =
        file.displayName.substringAfterLast('.', "").lowercase()

    /** Parent folder of the file's relative path — track sets fold ONLY within a single folder. */
    private fun folderOf(file: ResidentFile): String =
        file.relativePath.substringBeforeLast('/', "")

    private fun baseNameOf(file: ResidentFile): String =
        file.displayName.substringBeforeLast('.', file.displayName)

    /**
     * Normalize a base title for multi-disc matching: strip disc/side tokens and region/version
     * parenthetical tokens, lower-case, collapse whitespace. Deliberately conservative — it only has
     * to make the DISCS of one game share a key, not merge different games.
     */
    fun normalizedBaseTitle(name: String): String {
        var s = name
        s = DISC_TOKEN.replace(s, " ")
        s = PAREN_TOKEN.replace(s, " ")
        s = BRACKET_TOKEN.replace(s, " ")
        s = NON_ALNUM.replace(s, " ")
        return s.trim().lowercase().replace(WHITESPACE, " ")
    }

    companion object {
        // "(Disc 1)", "Disc II", "CD1", "Side A", "(Disk 3)"
        private val DISC_TOKEN =
            Regex(
                """[\(\[]?\s*(disc|disk|cd|side)(?:\s*[-_]?\s*([0-9]+|[ab])|[\s_-]+(viii|vii|vi|iv|iii|ii|ix|x|v|i)\b)\s*[\)\]]?""",
                RegexOption.IGNORE_CASE,
            )
        private val PAREN_TOKEN = Regex("""\([^)]*\)""")
        private val BRACKET_TOKEN = Regex("""\[[^\]]*\]""")
        private val NON_ALNUM = Regex("""[^A-Za-z0-9 ]""")
        private val WHITESPACE = Regex("""\s+""")

        /** The disc-track file extensions of a track set (the manifests are caught by content kind). */
        private val TRACK_FILE_EXTS = setOf("bin", "img")
    }
}

/**
 * Concrete grouper: buckets files by [GroupingKeyer.keyFor], then materializes each bucket into a
 * [GameGroup] with a chosen primary + hidden losers.
 *
 * Primary selection:
 *  - Multi-disc: the `.m3u` playlist if present, else the lowest disc number, else the first file.
 *  - Cross-storage / region: the primary-source (SD) copy if [PrimarySourcePreference] marks one,
 *    else the first file.
 *
 * @property keyer the grouping-key policy.
 * @property primarySourcePreference decides which of two storage copies wins (SD over internal).
 */
class KeyingDedupGrouper(
    private val keyer: GroupingKeyer = DefaultGroupingKeyer(),
    private val primarySourcePreference: PrimarySourcePreference = PrimarySourcePreference.SD_OVER_INTERNAL,
) : DedupGrouper {

    override fun group(files: List<ResidentFile>): List<GameGroup> {
        return group(files, emptyList())
    }

    /** Explicit `.m3u` membership outranks filename inference and binds every referenced resident. */
    fun group(files: List<ResidentFile>, playlists: List<M3uPlaylistGroup>): List<GameGroup> {
        val filesByEntry = files.associateBy { it.entryKey }
        val playlistKeys = mutableMapOf<EntryKey, GroupingKey.MultiDiscBase>()
        playlists.sortedWith(
            compareBy<M3uPlaylistGroup>(
                { filesByEntry[it.playlistEntryKey]?.sourceRootUri.orEmpty() },
                { filesByEntry[it.playlistEntryKey]?.relativePath.orEmpty() },
                { it.playlistEntryKey.value },
            ),
        ).forEach { playlist ->
            val playlistFile = filesByEntry[playlist.playlistEntryKey] ?: return@forEach
            val base = DefaultGroupingKeyer().normalizedBaseTitle(
                playlistFile.displayName.substringBeforeLast('.', playlistFile.displayName),
            ).ifBlank { playlistFile.entryKey.value }
            val key = GroupingKey.MultiDiscBase(playlistFile.platformId.value, base)
            (listOf(playlist.playlistEntryKey) + playlist.orderedMemberEntryKeys).forEach { entryKey ->
                playlistKeys.putIfAbsent(entryKey, key)
            }
        }
        val keyed = files.map { file -> file to (playlistKeys[file.entryKey] ?: keyer.keyFor(file)) }
        // A manifest/track set can describe a disc inside an already-detected multi-disc game. When the
        // platform + normalized base are identical, bridge the track-set key into that hard multi-disc
        // bucket instead of minting a second visible tile. The bridge is batch-local and exact; unrelated
        // track sets, folders, platforms, hashes, and catalog identities retain their original keys.
        val multiDiscByBase = keyed
            .mapNotNull { (_, key) -> (key as? GroupingKey.MultiDiscBase)?.let { (it.platform to it.baseTitle) to it } }
            .toMap()
        val buckets = LinkedHashMap<GroupingKey, MutableList<ResidentFile>>()
        for ((file, key) in keyed) {
            val effectiveKey = if (key is GroupingKey.TrackSetBase) {
                multiDiscByBase[key.platform to key.baseTitle] ?: key
            } else {
                key
            }
            buckets.getOrPut(effectiveKey) { mutableListOf() }.add(file)
        }
        return buckets.map { (key, members) -> materialize(key, members) }
    }

    private fun materialize(key: GroupingKey, members: List<ResidentFile>): GameGroup {
        val kind = key.kind ?: DuplicateGroupKind.EXACT_HASH // singleton: a lone file; kind is moot (not collapsed)
        val isMultiDisc = key is GroupingKey.MultiDiscBase ||
            key is GroupingKey.TrackSetBase ||
            members.any {
                it.contentKind == ContentKind.MULTI_DISC_PLAYLIST ||
                    it.contentKind == ContentKind.TRACK_MANIFEST ||
                    it.discNumber > 0
            }

        val primary = choosePrimary(members, isMultiDisc)
        val hidden = members.filter { it.entryKey != primary.entryKey }
        val identity = requireNotNull(primary.identity) {
            "primary member ${primary.entryKey} must carry a resolved identity"
        }
        return GameGroup(
            gameId = identity.gameId,
            groupKind = if (members.size == 1) kind else effectiveKind(key, isMultiDisc),
            identity = identity,
            primary = primary,
            hiddenMembers = hidden,
        )
    }

    private fun effectiveKind(key: GroupingKey, isMultiDisc: Boolean): DuplicateGroupKind = when {
        isMultiDisc -> DuplicateGroupKind.MULTI_DISC
        key is GroupingKey.ExactHash -> DuplicateGroupKind.EXACT_HASH
        key is GroupingKey.CatalogTitleId -> DuplicateGroupKind.CATALOG_TITLE_ID
        else -> DuplicateGroupKind.EXACT_HASH
    }

    private fun choosePrimary(members: List<ResidentFile>, isMultiDisc: Boolean): ResidentFile {
        if (isMultiDisc) {
            val bestRank = members.minOf { member ->
                logicalGameLaunchRank(member.contentKind.name, member.discNumber)
            }
            if (bestRank != Int.MAX_VALUE) {
                return primarySourcePreference.pick(
                    members.filter { member ->
                        logicalGameLaunchRank(member.contentKind.name, member.discNumber) == bestRank
                    },
                )
            }
        }
        return primarySourcePreference.pick(members)
    }
}

/**
 * Decides which of several storage copies is the surviving primary (cross-storage collapse prefers the
 * SD/primary-source copy — the design constraint).
 */
fun interface PrimarySourcePreference {
    fun pick(members: List<ResidentFile>): ResidentFile

    companion object {
        /** Prefer a SAF (SD-card) copy over a managed/internal copy; else the first member. */
        val SD_OVER_INTERNAL = PrimarySourcePreference { members ->
            members.minWith(
                compareBy<ResidentFile>(
                    {
                        if (it.location ==
                            com.gruning.mimir.rewrite.core.model.ResidentLocationKind.SAF_TREE_GRANT
                        ) {
                            0
                        } else {
                            1
                        }
                    },
                    { it.sourceRootUri.orEmpty() },
                    ResidentFile::relativePath,
                    { it.entryKey.value },
                ),
            )
        }
    }
}
