package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One locally-read playlist and the exact resident members it names, in playlist order. */
data class M3uPlaylistGroup(
    val playlistEntryKey: EntryKey,
    val orderedMemberEntryKeys: List<EntryKey>,
)

data class M3uPlaylistResolution(
    val files: List<ResidentFile>,
    val groups: List<M3uPlaylistGroup>,
)

/**
 * Reads bounded local `.m3u` manifests and turns their explicit file references into hard grouping evidence.
 * No path is opened from playlist text: entries only resolve against files the source walk already returned.
 */
object M3uPlaylistResolver {
    const val MAX_PLAYLIST_BYTES = 64L * 1024L
    private const val MAX_ENTRIES = 128

    suspend fun resolve(
        walked: List<WalkedFile>,
        recognized: List<ResidentFile>,
        readUtf8: suspend (WalkedFile, Long) -> String?,
    ): M3uPlaylistResolution {
        if (recognized.none { it.contentKind == ContentKind.MULTI_DISC_PLAYLIST }) {
            return M3uPlaylistResolution(recognized, emptyList())
        }
        val walkedByRelative = walked.associateBy { normalizeRelative(it.relativePath) ?: it.relativePath }
        val residentByRelative = recognized.associateBy { normalizeRelative(it.relativePath) ?: it.relativePath }
        val residentByManagedPath = recognized.mapNotNull { file -> file.managedPath?.let { it to file } }.toMap()
        val residentByDocumentUri = recognized.mapNotNull { file -> file.sourceDocumentUri?.let { it to file } }.toMap()
        val discOrdinals = mutableMapOf<EntryKey, Int>()
        val groups = mutableListOf<M3uPlaylistGroup>()

        val playlists = recognized.asSequence()
            .filter { it.contentKind == ContentKind.MULTI_DISC_PLAYLIST }
            .sortedWith(PHYSICAL_RESIDENT_ORDER)
            .toList()
        for (playlist in playlists) {
            val walkedPlaylist = walkedByRelative[normalizeRelative(playlist.relativePath) ?: playlist.relativePath]
                ?: continue
            // A revoked/quirky provider must not turn one optional playlist into a failed library
            // scan. The resident files still group by ordinary filename/track evidence below.
            val body = try {
                readUtf8(walkedPlaylist, MAX_PLAYLIST_BYTES)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                null
            } ?: continue
            val parent = playlist.relativePath.replace('\\', '/').substringBeforeLast('/', "")
            val resolved = parseEntries(body).mapNotNull { entry ->
                val member = when {
                    entry.startsWith("content://") -> residentByDocumentUri[entry]
                    entry.startsWith("file://") -> residentByManagedPath[entry.removePrefix("file://")]
                    entry.startsWith('/') -> residentByManagedPath[entry]
                    else -> normalizeRelative(if (parent.isBlank()) entry else "$parent/$entry")
                        ?.let(residentByRelative::get)
                }
                member?.takeIf {
                    it.entryKey != playlist.entryKey &&
                        it.platformId == playlist.platformId &&
                        it.contentKind != ContentKind.MULTI_DISC_PLAYLIST
                }
            }.distinctBy { it.entryKey }
            if (resolved.size < 2) continue

            resolved.forEachIndexed { index, member ->
                if (member.discNumber == 0) discOrdinals.putIfAbsent(member.entryKey, index + 1)
            }
            groups += M3uPlaylistGroup(
                playlistEntryKey = playlist.entryKey,
                orderedMemberEntryKeys = resolved.map { it.entryKey },
            )
        }

        val annotated = recognized.map { file ->
            val ordinal = discOrdinals[file.entryKey] ?: return@map file
            file.copy(
                discNumber = ordinal,
                contentKind = if (file.contentKind == ContentKind.TRACK_MANIFEST) {
                    ContentKind.TRACK_MANIFEST
                } else {
                    ContentKind.DISC_MEMBER
                },
            )
        }
        return M3uPlaylistResolution(annotated, groups)
    }

    /** Bounded parser shared with read-only consumers that must honor scan-time M3U syntax. */
    fun parseEntries(body: String): List<String> = body
        .removePrefix("\uFEFF")
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .map(::unquote)
        .filter { it.isNotEmpty() && '\n' !in it && '\r' !in it }
        .take(MAX_ENTRIES)
        .toList()

    /** Normalizes one resident-relative path without permitting traversal above its source root. */
    fun normalizeRelative(raw: String): String? {
        val stack = ArrayDeque<String>()
        raw.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/").takeIf(String::isNotEmpty)
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        ) {
            value.substring(1, value.lastIndex).trim()
        } else {
            value
        }

    private val PHYSICAL_RESIDENT_ORDER =
        compareBy<ResidentFile>(
            { it.sourceRootUri.orEmpty() },
            ResidentFile::relativePath,
            { it.entryKey.value },
        )
}
