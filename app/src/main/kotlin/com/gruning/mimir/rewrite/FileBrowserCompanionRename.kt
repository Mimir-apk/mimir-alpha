package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.ConflictResolution
import com.gruning.mimir.rewrite.core.files.ManagedFileOperations
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import com.gruning.mimir.rewrite.core.resolver.library.DiscCompanionFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What a rename of a disc did, companions included.
 *
 * @param landed where the renamed file stands now, or null when the player chose Skip and nothing was renamed.
 * @param companionRenames the companions that really were renamed with it -- where each stands now, mapped to the name
 *   it had. This is what goes into [ReversibleMove.companionRenames], and what Undo reverses.
 * @param stayed the names of companions that could not follow (the new name is taken, the file is held), left as they were.
 */
internal class RenamedWithCompanions(
    val landed: String?,
    val companionRenames: Map<String, String>,
    val stayed: List<String>,
)

/**
 * Rename [path] to [newName] and, with it, the libcrypt `.sbi` / `.sub` the emulator finds by the disc's name .
 *
 * Lives outside [FileBrowserStore] -- an `object` over a `Context` no JVM test can drive -- so the rename and its Undo
 * can be run against real files.
 *
 * **Undo reverses exactly what was done.** With [undoing] set this is the rename-back: the companions are the ones the
 * forward rename recorded ([ReversibleMove.companionRenames]), each going back to the name it had, and nothing else is
 * looked for. Looking again by the disc's current name would find a `Better.sbi` that already belonged to a
 * `Better.chd` the rename never touched, and rename that to `Game.sbi`.
 *
 * The companions are read before the disc moves (afterwards nothing stands at [path]) and off Main. They are renamed
 * after it, and not cancellably: a Cancel that arrived between the two would settle the card as "Nothing was changed"
 * over a disc that already has its new name.
 */
internal suspend fun renameWithCompanions(
    ops: ManagedFileOperations,
    path: String,
    newName: String,
    resolution: ConflictResolution,
    undoing: ReversibleMove? = null,
): Result<RenamedWithCompanions> {
    val companions = withContext(Dispatchers.IO) {
        undoing?.companionRenames?.keys?.toList() ?: DiscCompanionFiles.companionsOf(path)
    }
    val landed = ops.rename(path, newName, resolution).getOrElse { return Result.failure(it) }
        ?: return Result.success(RenamedWithCompanions(landed = null, companionRenames = emptyMap(), stayed = emptyList()))
    if (companions.isEmpty()) return Result.success(RenamedWithCompanions(landed, emptyMap(), emptyList()))
    return Result.success(
        withContext(NonCancellable) {
            val renamed = LinkedHashMap<String, String>()
            val stayed = mutableListOf<String>()
            for (companion in companions) {
                val currentName = File(companion).name
                val targetName = undoing?.companionRenames?.get(companion) ?: DiscCompanionFiles.renamedTo(companion, landed)
                // Only the disc's extension changed: the companion is already named for it.
                if (targetName == currentName) continue
                val companionLanded = ops.rename(companion, targetName, ConflictResolution.ASK).getOrNull()
                if (companionLanded != null) renamed[companionLanded] = currentName else stayed += currentName
            }
            RenamedWithCompanions(landed, renamed, stayed)
        },
    )
}
