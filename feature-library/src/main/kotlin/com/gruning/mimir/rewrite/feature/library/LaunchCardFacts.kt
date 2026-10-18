package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow

/**
 * The launch card's "what is this file" facts, in the order the card lists them: REGION, VERSION, SIZE, DISCS. ONE derivation for both doors (Home's card and
 * the Library's), lifted from the Library adapter so the two cannot list different facts for the same game. Each is left out when the game does not have it: no
 * blank region, no zero size, no "DISCS 1".
 *
 * Pure: the caller resolves [sizeBytes] ([launchCardSizeBytes]) and [discs] ([libraryDetailDiscs]) from the game's members, which only the app can read.
 */
fun launchCardFacts(
    game: GameRow?,
    sizeBytes: Long?,
    discs: List<LibraryDetailDisc>,
): List<Pair<String, String>> = buildList {
    game?.region?.takeIf(String::isNotBlank)?.let { add("REGION" to it) }
    game?.version?.takeIf(String::isNotBlank)?.let { add("VERSION" to it) }
    sizeBytes?.let(::formatSizeLabel)?.let { add("SIZE" to it) }
    discs.size.takeIf { it > 1 }?.let { add("DISCS" to it.toString()) }
}

/** The game's size on disk: the sum of its members when they are known (a multi-disc game), else the row's own size. */
fun launchCardSizeBytes(game: GameRow, members: List<GameMemberRow>): Long =
    members.takeIf { it.isNotEmpty() }?.sumOf { it.sizeBytes } ?: game.sizeBytes

/**
 * Whether a resolved launch-card face is physical media (a disc or cartridge render) rather than a cover. [physical] is null when there is no resolved face at
 * all. One answer for both doors: no face is not physical media (Home's rule; the Library read it the other way, `!= false`, which had no visible effect because a
 * null face carries no uri to square off, and is now the same).
 */
fun launchCardFaceIsPhysical(physical: Boolean?): Boolean = physical == true
