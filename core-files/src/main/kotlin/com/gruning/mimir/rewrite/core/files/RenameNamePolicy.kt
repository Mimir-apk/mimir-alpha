package com.gruning.mimir.rewrite.core.files

/**
 * Why a typed name cannot be used to rename a file or folder, in the words the keyboard shows under the
 * name as the player types.
 *
 * Every sentence names the rule, not the code: "A name can't contain / or \", not "invalid name".
 */
enum class RenameProblem(val sentence: String) {
    EMPTY("Type a name."),
    SEPARATOR("A name can't contain / or \\."),
    DOTS("A name can't be just dots."),
    BAD_CHARACTER("A name can't contain : * ? \" < > or |."),
    CONTROL("A name can't contain control characters."),
    TRAILING_DOT("A name can't end with a dot."),
    RESERVED("That name is reserved by the system. Pick another."),
    WORKING_FOLDER("That name is Mimir's own working folder. Pick another."),
    TOO_LONG("That name is too long."),
    UNCHANGED("That is already its name."),
}

/**
 * What a new name for a file or folder may be. **Pure**, so the rule is one place both the keyboard (live
 * feedback) and the engine (the last line of defence) read.
 *
 * ## The rule is the strictest of the filesystems a card can carry
 *
 * An Android phone's own storage forbids only `/`, but the SD cards players put games on are usually
 * exFAT or FAT32, which also refuse `: * ? " < > |`, a trailing dot, and the old DOS device names. A name
 * that the internal drive would accept and the card would silently mangle (or refuse with an unhelpful
 * IOException half way through a rename) is rejected up front, with a sentence, instead.
 *
 * ## What this does NOT decide
 *
 * Whether the name is already taken. That depends on the folder and is the engine's
 * [ManagedFileOperations.rename] job, answered with the same Keep both / Replace / Skip card every other
 * collision gets.
 */
object RenameNamePolicy {

    /** The longest a name may be on the drives Mimir writes to (ext4 / f2fs / exFAT all allow 255 bytes). */
    const val MAX_NAME_BYTES = 255

    private val BAD_CHARACTERS = charArrayOf(':', '*', '?', '"', '<', '>', '|')

    private val RESERVED_STEMS: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (n in 1..9) {
            add("COM$n")
            add("LPT$n")
        }
    }

    /**
     * What the player typed, tidied: leading and trailing spaces are not part of a name anyone means, and a
     * trailing space is refused by FAT-family cards, so they are dropped rather than reported.
     */
    fun normalise(typed: String): String = typed.trim()

    /**
     * The first rule [typed] breaks, or null when it is a usable name. [current] is the name the item has
     * now: typing it back unchanged is a problem of its own (nothing to rename), while changing only the
     * letter case is allowed.
     */
    fun check(typed: String, current: String? = null): RenameProblem? {
        val name = normalise(typed)
        return when {
            name.isEmpty() -> RenameProblem.EMPTY
            name.any { it == '/' || it == '\\' } -> RenameProblem.SEPARATOR
            name.all { it == '.' } && name.length <= 2 -> RenameProblem.DOTS
            name.any { it.isISOControl() } -> RenameProblem.CONTROL
            name.any { it in BAD_CHARACTERS } -> RenameProblem.BAD_CHARACTER
            name.endsWith('.') -> RenameProblem.TRAILING_DOT
            isReserved(name) -> RenameProblem.RESERVED
            // `.mimir-staging` and the `.extract-123` folders in it are hidden from the listing and swept away
            // by the next recovery pass: a folder or file given such a name would vanish.
            FileOperationStaging.isWorkingFolderName(name) -> RenameProblem.WORKING_FOLDER
            name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> RenameProblem.TOO_LONG
            current != null && name == current -> RenameProblem.UNCHANGED
            else -> null
        }
    }

    /** `con`, `NUL.txt`, `com3.tar.gz`: the device names are reserved with or without an extension. */
    private fun isReserved(name: String): Boolean =
        name.substringBefore('.').trimEnd().uppercase() in RESERVED_STEMS
}
