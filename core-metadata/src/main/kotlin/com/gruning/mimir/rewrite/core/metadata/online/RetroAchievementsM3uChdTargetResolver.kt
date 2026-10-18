package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ContentOpener
import com.gruning.mimir.rewrite.core.metadata.withContentCancellation
import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Current physical location of the focused logical game's M3U primary. */
data class RetroAchievementsM3uResident(
    val entryKey: String,
    val sourceRootUri: String,
    val relativePath: String,
    val documentUri: String?,
    val managedPath: String?,
    val sizeBytes: Long,
)

/** What a fresh bounded read established about the playlist's first target. */
sealed interface RetroAchievementsM3uChdResolution {
    data class Selected(val target: RetroAchievementsChdResident) :
        RetroAchievementsM3uChdResolution

    /** The first target is readable by the existing non-CHD rcheevos route. */
    data object NotChdTarget : RetroAchievementsM3uChdResolution

    /** The current playlist cannot name exactly one available CHD resident honestly. */
    data object Unavailable : RetroAchievementsM3uChdResolution
}

/**
 * Resolves only entry one, matching RetroAchievements' playlist identity rule. The playlist text never opens
 * a path directly: it may select only one resident already present in [availableGroupChds].
 */
class RetroAchievementsM3uChdTargetResolver(
    private val opener: ContentOpener,
) {
    suspend fun resolve(
        playlist: RetroAchievementsM3uResident,
        availableGroupChds: List<RetroAchievementsChdResident>,
    ): RetroAchievementsM3uChdResolution = withContentCancellation { scope ->
        val bound = opener.boundTo(scope)
        resolveWith(bound, playlist, availableGroupChds)
    }

    private suspend fun resolveWith(
        opener: ContentOpener,
        playlist: RetroAchievementsM3uResident,
        availableGroupChds: List<RetroAchievementsChdResident>,
    ): RetroAchievementsM3uChdResolution {
        currentCoroutineContext().ensureActive()
        val body = readPlaylist(opener, playlist) ?: return RetroAchievementsM3uChdResolution.Unavailable
        val entry = M3uPlaylistResolver.parseEntries(body).firstOrNull()
            ?: return RetroAchievementsM3uChdResolution.Unavailable
        val extension = entry
            .substringBefore('#')
            .substringBefore('?')
            .substringAfterLast('.')
            .lowercase()
        if (extension == "m3u") return RetroAchievementsM3uChdResolution.Unavailable
        if (extension != "chd") return RetroAchievementsM3uChdResolution.NotChdTarget

        val matches = availableGroupChds
            .asSequence()
            .filter(::isChdShaped)
            .filter { candidate -> entrySelects(playlist, entry, candidate) }
            .distinctBy(RetroAchievementsChdResident::entryKey)
            .toList()
        return if (matches.size == 1) {
            RetroAchievementsM3uChdResolution.Selected(matches.single())
        } else {
            RetroAchievementsM3uChdResolution.Unavailable
        }
    }

    private suspend fun readPlaylist(
        opener: ContentOpener,
        playlist: RetroAchievementsM3uResident,
    ): String? {
        val stream = try {
            opener.open(playlist.documentUri, playlist.managedPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        } ?: return null
        return try {
            stream.use { opened -> readBoundedUtf8(opened) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private suspend fun readBoundedUtf8(stream: InputStream): String? {
        val limit = M3uPlaylistResolver.MAX_PLAYLIST_BYTES.toInt()
        val bytes = ByteArray(limit + 1)
        var total = 0
        while (total < bytes.size) {
            currentCoroutineContext().ensureActive()
            val read = try {
                stream.read(bytes, total, bytes.size - total)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return null
            }
            if (read < 0) break
            if (read == 0) return null
            total += read
        }
        if (total > limit) return null
        currentCoroutineContext().ensureActive()
        return String(bytes, 0, total, Charsets.UTF_8)
    }

    private fun entrySelects(
        playlist: RetroAchievementsM3uResident,
        entry: String,
        candidate: RetroAchievementsChdResident,
    ): Boolean {
        return when {
            entry.startsWith("content://") -> candidate.documentUri == entry
            entry.startsWith("file://") -> candidate.managedPath == entry.removePrefix("file://")
            entry.startsWith('/') || WINDOWS_ABSOLUTE.matches(entry) ->
                candidate.managedPath == entry
            else -> {
                if (candidate.sourceRootUri != playlist.sourceRootUri) return false
                val parent = playlist.relativePath
                    .replace('\\', '/')
                    .substringBeforeLast('/', "")
                val target = M3uPlaylistResolver.normalizeRelative(
                    if (parent.isBlank()) entry else "$parent/$entry",
                ) ?: return false
                M3uPlaylistResolver.normalizeRelative(candidate.relativePath) == target
            }
        }
    }

    private fun isChdShaped(candidate: RetroAchievementsChdResident): Boolean =
        sequenceOf(candidate.relativePath, candidate.documentUri, candidate.managedPath)
            .filterNotNull()
            .any { it.substringBefore('#').substringBefore('?').endsWith(".chd", ignoreCase = true) }

    private companion object {
        val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
