package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.ConflictResolution
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry
import com.gruning.mimir.rewrite.core.resolver.library.DiscCompanionFiles
import com.gruning.mimir.rewrite.core.resolver.library.PlaylistMembers
import java.io.File
import java.nio.file.Paths

/**
 * What a delete or a move of a disc says on its card, decided here rather than inside [FileBrowserStore] -- an `object`
 * over a `Context` that no JVM test can drive -- so each sentence can be run against real files .
 *
 * The rule they share: **the player picked some files, and the card speaks about those.** A disc's tracks and
 * libcrypt files ride along; they are named only where leaving one behind is something the player would want to know.
 */

/**
 * What a delete will stage and what it will say. Reads the filesystem (the companions of a disc, the tracks of a cue, the
 * discs a playlist lists), so build it off Main.
 *
 * @param everything the picked paths plus what goes with them -- a cue's tracks, a disc's `.sbi` -- and **nothing a
 *   playlist lists**: an `.m3u` brings none of its discs.
 * @param stays the discs listed by a playlist in the delete that the delete leaves, or null when there are none.
 */
internal class DeletePlan(val everything: List<String>, val stays: PlaylistMembers.Staying?) {

    /** The lines the finished delete's card carries: "The 3 discs it lists stay." */
    val notes: List<String>
        get() = listOfNotNull(stays?.let { playlistStaysNote(it.playlists, it.members) })
}

internal fun planDelete(picked: List<String>): DeletePlan {
    val everything = DiscCompanionFiles.withCompanions(picked)
    return DeletePlan(everything, PlaylistMembers.staying(everything))
}

/**
 * How many of [entries] the player picked: the number the delete toast announces. A cue's tracks and a disc's `.sbi`
 * are in the held delete (one Undo returns them) but are not "4 items" to the player who picked one file.
 */
internal fun deletedPickedCount(entries: List<StagedDeletionEntry>, picked: List<String>): Int {
    val pickedKeys = picked.mapTo(HashSet()) { pathKey(it) }
    return entries.count { pathKey(it.originalPath) in pickedKeys }
}

// The engine records a path as `Paths.get(path).toString()`; compare in that spelling so a separator or a redundant
// segment cannot make a picked file look like a companion.
private fun pathKey(path: String): String = runCatching { Paths.get(path).toString() }.getOrDefault(path)

/**
 * [entries] with [note] appended to card [id]'s second line -- once, and never in place of what the card already said.
 * A transfer of several files calls this once per file, and three identical clauses on one card is noise the player has
 * to parse to find the fact; the card's first half is still true, and a note that replaced it would erase the only record
 * of what the operation was.
 */
internal fun withNote(entries: List<FileQueueEntry>, id: Long, note: String): List<FileQueueEntry> =
    entries.map { entry ->
        if (entry.id != id || entry.subtitle.contains(note)) entry else entry.copy(subtitle = "${entry.subtitle} · $note")
    }

/**
 * The lines a finished move adds for the files that were left behind ([leftBehind], with who they followed in
 * [followers]). **None under Skip**: Skip was the player's own choice, a follower stays with the file it follows, and
 * there is nothing to explain -- the sentence "it only moves under its disc's name" would read as an error they did not make.
 */
internal fun leftBehindNotes(
    resolution: ConflictResolution,
    leftBehind: List<String>,
    followers: Map<String, DiscCompanionFiles.CompanionFollower>,
): List<String> {
    if (resolution == ConflictResolution.SKIP) return emptyList()
    return leftBehind.map { left ->
        val why = if (followers[left]?.keepsOwnName == true) {
            "its cue lists it under that name"
        } else {
            "it only moves under its disc's name"
        }
        "${File(left).name} stayed behind: $why"
    }
}

/**
 * The card for a copy or move the player stopped. It counts the files the player **picked** -- [picked] -- and how many
 * of those landed or were skipped; the companions that rode along ([moving] minus [picked]) are not "files" to them. If
 * the stop came before some companions were reached (neither landed nor left behind on purpose) the card says they are
 * still where they were, rather than claiming a clean finish.
 *
 * @param landings where each source that really landed ended up; @param skipped the sources left alone.
 */
internal fun cancelledTransferCard(
    operation: FileOperation,
    picked: List<String>,
    moving: List<String>,
    destination: String,
    landings: Map<String, String>,
    skipped: List<String>,
): FileQueueState.Cancelled {
    val pickedSet = picked.toSet()
    val skippedSet = skipped.toSet()
    return cancelledTransferState(
        operation,
        picked.size,
        landings.keys.count { it in pickedSet },
        skipped.count { it in pickedSet },
        File(destination).name,
        companionsLeft = moving.count { it !in pickedSet && it !in landings && it !in skippedSet },
    )
}
