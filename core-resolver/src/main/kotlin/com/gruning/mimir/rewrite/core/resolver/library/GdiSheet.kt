package com.gruning.mimir.rewrite.core.resolver.library

import java.io.File

/**
 * Reads which files a Dreamcast `.gdi` track list names, for one purpose: so deleting or moving a `.cue` never takes a
 * track a `.gdi` that STAYS BEHIND still needs.
 *
 * A `.gdi` is a count and then one line per track -- `3 0 4 2352 track03.bin 0` -- with the file name a bare or quoted
 * token. This is the **protecting** reader (see [CueSheet]): it never decides what to take, only what to keep, so it
 * reads every token on every line as a possible name and gives up safe -- null, "needs everything in its folder" -- on a
 * sheet that is not a readable file or is over [CueSheet.MAX_BYTES]. Read as UTF-8 and as Latin-1, like the cue.
 */
object GdiSheet {

    private val TOKEN = Regex(""""([^"]*)"|(\S+)""")

    /** Every token on every line of [gdi], lowercase, or null when the sheet cannot be read with confidence. */
    fun protectedNames(gdi: File): Set<String>? {
        val bodies = CueSheet.readBodies(gdi) ?: return null
        val names = HashSet<String>()
        for (body in bodies) {
            for (line in body.removePrefix("\uFEFF").lineSequence()) {
                TOKEN.findAll(line).forEach { match ->
                    val token = match.groups[1]?.value ?: match.groups[2]?.value
                    if (!token.isNullOrBlank()) names += token.lowercase()
                }
            }
        }
        return names
    }
}
