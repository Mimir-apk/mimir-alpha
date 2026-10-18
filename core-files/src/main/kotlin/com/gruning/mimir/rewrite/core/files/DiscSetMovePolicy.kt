package com.gruning.mimir.rewrite.core.files

/**
 * A multi-disc game is several files plus an `.m3u` that names them. The playlist resolves its entries
 * against its own folder, so moving one disc out leaves the playlist pointing at a file that is not
 * there — the game stops working, on disk, for every emulator that reads the playlist, and the library
 * cannot repair it because the breakage is in a text file the library does not own.
 *
 * **Warn before anything moves, and offer to take the whole set.** Two cheaper alternatives were rejected for safety:
 *
 * - *Tell them afterwards on the card* — nearly free, since `LibraryRelocation.partOfSet` is already
 *   reported. Refused as a post-mortem: by the time it is read the game is already broken.
 * - *Refuse the move* — never breaks anything, and stops the player reorganising their own card by hand
 *   over a set Mimir only inferred.
 *
 * ## Why this is a policy and not just a screen
 *
 * The options are not fixed. "Move all N discs" is only offered when moving them together actually
 * repairs the set, and that is a checked fact about the files ([DiscSetWarning.wholeSetCanTravel]), not a
 * design intention. **"Repairs the playlist" was the whole story until 2026-08-18 and is now only part of
 * it:** a set with no `.m3u` at all — the ordinary GameCube/PS2 shape that this
 * feature previously missed — has nothing to repair and so always travels; a playlist naming fixed paths, or one
 * the library holds only as a SAF document, never does. A screen that always drew three rows
 * would be advertising a repair it cannot perform whenever the playlist names fixed paths — the same
 * wrong-answer-is-worse-than-no-answer rule the extract destination already follows.
 */
data class DiscSetWarning(
    /** The game's title, for the warning's own sentence. */
    val title: String,
    /** Which disc the subject is, 1-based. 0 when the library never numbered it — say "one of" instead. */
    val discNumber: Int,
    /** How many discs are in the set. Always >= 2, or there is no warning to give. */
    val discCount: Int,
    /** The file the player asked to move. */
    val subjectPath: String,
    /** Playlist first, then every disc — what *move all of them* moves. */
    val setPaths: List<String>,
    /**
     * Whether this set has an `.m3u` at all.
     *
     * **Carried because the sentence changes, not the behaviour.** "Its playlist points at the other
     * discs by name" is the truth for a PS1 `.chd` set and a fabrication for a GameCube `.iso` pair,
     * which has no playlist and breaks anyway because the emulator asks for the next disc and it is
     * somewhere else. Defaulted so every existing caller and test keeps describing the playlist case.
     */
    val hasPlaylist: Boolean = true,
    /**
     * Moving the whole set together really does keep it playable: there is a playlist this process can
     * move, and every entry in it is a bare sibling filename that survives landing in a new folder.
     *
     * **False does not mean "no warning".** It means the only honest offers left are *move it anyway* and
     * *cancel* — the set is going to break either way, and saying so is the point.
     */
    val wholeSetCanTravel: Boolean,
) {
    /**
     * Whether the set's files are already spread across more than one folder.
     *
     * Derived rather than plumbed, because [setPaths] already carries the fact and a second field would
     * be a second thing to keep true. It exists for one reason: **the warning sentence "The other discs
     * stay in this folder" is false when they do not**, and a warning screen that states a checkable
     * falsehood teaches the player that the whole screen is guessing.
     *
     * A set in this state is **already broken** — the emulator cannot reach the other discs today — so
     * the co-move here is not a precaution, it is the repair.
     */
    val spansFolders: Boolean
        get() = setPaths.map { it.substringBeforeLast('/', "") }.distinct().size > 1
}

/** What the warning screen can offer, in the order it draws them. */
enum class DiscSetMoveOption {
    /** Take the playlist and every disc to the destination. Only when [DiscSetWarning.wholeSetCanTravel]. */
    MOVE_WHOLE_SET,

    /** Move only the file the player picked, and accept that the set breaks. Always offered. */
    MOVE_SUBJECT_ONLY,

    /** Change nothing. Always offered, and always last — see [discSetMoveOptions]. */
    CANCEL,
}

/**
 * The options for [warning], best first.
 *
 * **[DiscSetMoveOption.CANCEL] is last, not first, and that is a deliberate departure from "the safe
 * option leads".** Focus lands on the first row, and a screen that opens on Cancel makes the common
 * answer — *yes, take them all* — cost an extra press every time while making the destructive answer
 * exactly as cheap as it was before. Leading with the repair puts the safest *action* under the
 * player's thumb; B still cancels from anywhere, which is what actually protects the press.
 */
fun discSetMoveOptions(warning: DiscSetWarning): List<DiscSetMoveOption> = buildList {
    if (warning.wholeSetCanTravel) add(DiscSetMoveOption.MOVE_WHOLE_SET)
    add(DiscSetMoveOption.MOVE_SUBJECT_ONLY)
    add(DiscSetMoveOption.CANCEL)
}

/**
 * The paths [option] moves, or empty when it moves nothing.
 *
 * Lives beside the options rather than in the caller so that "what does this row do" is answered once,
 * by the same file that decided the row exists. [DiscSetMoveOption.MOVE_WHOLE_SET] answers empty when the
 * set cannot travel — it should never have been offered, and a policy that would still hand back a path
 * list for it is one mis-wired dispatch away from moving a set it just said it could not.
 */
fun discSetMovePaths(warning: DiscSetWarning, option: DiscSetMoveOption): List<String> = when (option) {
    DiscSetMoveOption.MOVE_WHOLE_SET -> if (warning.wholeSetCanTravel) warning.setPaths else emptyList()
    DiscSetMoveOption.MOVE_SUBJECT_ONLY -> listOf(warning.subjectPath)
    DiscSetMoveOption.CANCEL -> emptyList()
}
