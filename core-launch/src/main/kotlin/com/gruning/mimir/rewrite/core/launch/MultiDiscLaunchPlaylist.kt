package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.File
import java.security.MessageDigest

/** Writes Mimir-owned, regenerable `.m3u` files; source ROM folders remain read-only. */
class GeneratedM3uWriter(private val directory: File) {

    fun write(gameId: String, orderedDiscPaths: List<String>): String? {
        return runCatching {
            val safePaths = orderedDiscPaths
                .map(String::trim)
                .filter { it.isNotEmpty() && '\n' !in it && '\r' !in it }
                .distinct()
            if (safePaths.size < 2 || safePaths.any { !File(it).isAbsolute }) return@runCatching null
            if (!directory.exists() && !directory.mkdirs()) return@runCatching null
            if (!directory.isDirectory) return@runCatching null

            val stem = MessageDigest.getInstance("SHA-256")
                .digest(gameId.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
            val target = File(directory, "$stem.m3u")
            val temporary = File(directory, "$stem.m3u.tmp")
            temporary.writeText(
                buildString {
                    append("#EXTM3U\n")
                    safePaths.forEach { append(it).append('\n') }
                },
                Charsets.UTF_8,
            )
            if (target.exists() && !target.delete()) return@runCatching null
            if (!temporary.renameTo(target)) return@runCatching null
            target.absolutePath
        }.getOrNull()
    }
}

/** One launchable root per physical disc; split `.bin/.img` tracks never become playlist entries. */
internal fun multiDiscLaunchMembers(members: List<GameEntity>): List<GameEntity> = members
    .asSequence()
    .filter { it.discNumber > 0 }
    .groupBy { it.discNumber }
    .toSortedMap()
    .values
    .map { sameDisc ->
        sameDisc.minWith(
            compareBy<GameEntity>(
                { if (it.contentKind == "TRACK_MANIFEST") 0 else 1 },
                { if (it.displayName.substringAfterLast('.', "").lowercase() in setOf("bin", "img")) 1 else 0 },
                { it.displayName },
                { it.sourceRootUri },
                { it.relativePath },
                { it.entryKey },
            ),
        )
    }

/**
 * A logical identity can contain multiple region/version sets. Without a persisted set selector, two
 * launch roots claiming the same disc number are ambiguous and must never be mixed into one M3U.
 * Cue/bin track bundles remain unambiguous when exactly one manifest owns the disc.
 */
internal fun hasAmbiguousDiscVariants(members: List<GameEntity>): Boolean = members
    .asSequence()
    .filter { it.discNumber > 0 }
    .groupBy { it.discNumber }
    .values
    .any { sameDisc ->
        val manifests = sameDisc.filter { it.contentKind == "TRACK_MANIFEST" }
        if (manifests.isNotEmpty()) {
            manifests.size > 1 ||
                sameDisc.any { member ->
                    member.contentKind != "TRACK_MANIFEST" &&
                        member.displayName.substringAfterLast('.', "").lowercase() !in setOf("bin", "img")
                }
        } else {
            sameDisc.count { member ->
                member.displayName.substringAfterLast('.', "").lowercase() !in setOf("bin", "img")
            } > 1
        }
    }

internal fun filePathForPlaylistMember(member: GameEntity): String? =
    member.managedPath?.takeIf(String::isNotBlank)
        ?: member.sourceDocumentUri?.let(::externalStoragePathFromDocumentUri)
