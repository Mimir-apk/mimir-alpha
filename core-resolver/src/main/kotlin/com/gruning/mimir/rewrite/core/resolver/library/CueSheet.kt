package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import java.io.File

/**
 * Reads the files a `.cue` sheet lists -- its `FILE "Game (Track 1).bin" BINARY` lines.
 *
 * A disc split into tracks is a `.cue` plus the `.bin`/`.wav` files it names, and the sheet is the only record of which
 * files those are. The file manager reads it so a delete or a move of the sheet can take its tracks with it. Rename does not use it: renaming the sheet changes nothing a track's name has to agree with.
 *
 * ## Two readers, because the two questions fail in opposite directions
 *
 * **Taking** ([listedNames], [listedFiles]) decides what a delete or move adds, so it is strict: a doubtful entry is
 * simply not taken. **Protecting** ([protectedNames]) decides what a sheet that STAYS BEHIND still needs, so it fails
 * the other way: anything it cannot read with confidence -- a sheet that is oversized, unreadable, or has a `FILE` line it
 * cannot parse -- is treated as needing every file in its folder, and it reads leniently (`./shared.bin` is
 * `shared.bin`; a sheet saved in a Windows code page is read that way too as well as as UTF-8).
 *
 * ## What counts as a track, and what never does
 *
 * Only a **bare file name in the sheet's own folder**. An entry with a path separator (either kind), a `..`, a drive
 * or scheme colon, or an absolute path is refused, so a hostile or merely odd sheet can never point a delete at a
 * file outside the folder the player chose. A listed file that is not there is ignored, and so is a sheet that lists
 * another sheet. Reading is bounded: [MAX_BYTES] of text, [MAX_FILES] entries (a CD has 99 tracks).
 */
object CueSheet {

    const val MAX_BYTES = 1024L * 1024L

    private const val MAX_FILES = 99

    // The data types a FILE line may end with. A bare (unquoted) name may contain spaces, so the type is only cut off
    // when it is one of these -- never "the last word".
    private val FILE_TYPES = setOf("BINARY", "MOTOROLA", "AIFF", "WAVE", "MP3")

    /**
     * The safe, bare file names [body] lists, in order, each once (compared without case), at most [MAX_FILES].
     *
     * Accepts a quoted name (`FILE "My Game.bin" BINARY`) or a bare one (`FILE game.bin BINARY`, `FILE My Game.bin
     * BINARY`, `FILE game.bin`), any case of the keyword, LF / CRLF / CR line ends, and a leading byte-order mark.
     */
    fun listedNames(body: String): List<String> {
        val names = LinkedHashMap<String, String>()
        for (raw in body.removePrefix("\uFEFF").lineSequence()) {
            if (names.size >= MAX_FILES) break
            val rest = fileLineRest(raw) ?: continue
            val name = nameIn(rest) ?: continue
            if (isBareName(name)) names.putIfAbsent(name.lowercase(), name)
        }
        return names.values.toList()
    }

    /**
     * The lowercase file names, in [cue]'s own folder, that a sheet which STAYS BEHIND still needs -- or null when the
     * sheet cannot be read with confidence, which the caller must read as "needs everything in its folder".
     *
     * Null for a sheet that is not a readable file, is over [MAX_BYTES], or has a `FILE` line this reader cannot parse.
     * Lenient where [listedNames] is strict: `./shared.bin` and `sub/../shared.bin` name `shared.bin`, and the sheet is
     * read as UTF-8 AND as Latin-1, so a name saved in a Windows code page still matches the file on the card. Entries
     * that leave the folder cannot name a track beside the sheet and are not counted. A `REM` line is a comment, not a
     * listing, whatever it says.
     */
    fun protectedNames(cue: File): Set<String>? {
        val bodies = readBodies(cue) ?: return null
        val names = HashSet<String>()
        for (body in bodies) {
            for (raw in body.removePrefix("\uFEFF").lineSequence()) {
                val rest = fileLineRest(raw) ?: continue
                val name = nameIn(rest) ?: return null
                val bare = M3uPlaylistResolver.normalizeRelative(name)
                if (bare != null && '/' !in bare) names += bare.lowercase()
            }
        }
        return names
    }

    /** [file]'s text decoded both ways (UTF-8, Latin-1), or null when it is not a readable file within [MAX_BYTES]. */
    internal fun readBodies(file: File): List<String>? {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return listOf(String(bytes, Charsets.UTF_8), String(bytes, Charsets.ISO_8859_1))
    }

    /** What follows `FILE` on a FILE line, or null for any other line (a `REM` that mentions FILE included). */
    private fun fileLineRest(raw: String): String? {
        val line = raw.trim()
        if (line.length <= 4 || !line.startsWith("FILE", ignoreCase = true) || !line[4].isWhitespace()) return null
        return line.substring(4).trim()
    }

    /**
     * The files [cue] lists that exist in its own folder, as paths built from the sheet's own path. Empty for a sheet that
     * is missing, over [MAX_BYTES] or unreadable. Never the sheet itself, and never another `.cue`.
     */
    fun listedFiles(cue: File): List<File> {
        if (!cue.isFile || cue.length() > MAX_BYTES) return emptyList()
        val folder = cue.parentFile ?: return emptyList()
        val body = runCatching { cue.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return listedNames(body)
            .map { File(folder, it) }
            .filter { it.isFile && !it.extension.equals("cue", ignoreCase = true) }
    }

    /** The name on a FILE line's remainder, or null when the line is malformed. */
    private fun nameIn(rest: String): String? {
        if (rest.isEmpty()) return null
        if (rest.first() == '"') {
            val close = rest.indexOf('"', 1)
            return if (close > 1) rest.substring(1, close) else null
        }
        val lastSpace = rest.indexOfLast { it.isWhitespace() }
        if (lastSpace > 0 && rest.substring(lastSpace + 1).uppercase() in FILE_TYPES) {
            return rest.substring(0, lastSpace).trim().ifEmpty { null }
        }
        return rest
    }

    private fun isBareName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
}
