package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.MoveFollower
import com.gruning.mimir.rewrite.core.resolver.library.DiscCompanionFiles
import java.io.File

/**
 * How the files that travel with a picked file are moved: each one is a **follower** of the file it
 * belongs to, so the engine lands it under the right name or leaves it where it was -- never under a name of its own
 * choosing.
 *
 *  - an `.sbi` / `.sub` takes the name its disc landed under (`Game (copy).chd` brings `Game (copy).sbi`);
 *  - a `.cue`'s track keeps the name the sheet lists it under, because the sheet is not edited: a track that cannot land
 *    under that name stays behind rather than become `Game (copy).bin`, which no sheet names.
 *
 * Pulled out of [FileBrowserStore] (an `object` over a `Context` no JVM test can drive) so a move of a disc can be run
 * against real files.
 */
internal fun moveFollowersOf(set: DiscCompanionFiles.CompanionSet): Map<String, MoveFollower> =
    set.followers.mapValues { (companion, follower) ->
        MoveFollower(follower.leader) { leaderLanded ->
            if (follower.keepsOwnName) File(companion).name else DiscCompanionFiles.renamedTo(companion, leaderLanded)
        }
    }
