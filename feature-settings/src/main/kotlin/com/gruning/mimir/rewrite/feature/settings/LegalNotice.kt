package com.gruning.mimir.rewrite.feature.settings

import android.content.Context

/**
 * Where Mimir's source code is published. GPL-3.0 section 6 asks for the corresponding source to be offered with the app, and the
 * notice in Settings > About > Credits & licenses shows this address.
 *
 * This is the one place the address is written for the Mimir app. The
 * Tweaker is a separate build and carries its own copy of the constant (`mimir-tweaker/.../LegalNotice.kt`); `LegalNoticeFilesTest`
 * fails if the two differ.
 */
const val MimirSourceUrl = "https://github.com/Mimir-apk/mimir-alpha"

/** The row that opens [MimirSourceUrl] in the browser. The host maps it to the URL in `settingsExternalUrlFor`. */
const val AboutLegalSourceRowId = "about/legal/source"

/** Every row of the legal reader starts with this. They exist only while Credits & licenses is open. */
internal const val AboutLegalRowPrefix = "about/legal/"

/** The Credits & licenses row that opens the reader: where a persisted focus on a reader row lands. */
internal const val AboutCreditsRowId = "about/credits/sources"

/** The two texts the reader shows, read from the app's assets. */
internal enum class LegalDoc(val assetPath: String, val rowId: String) {
    /** Third-party code, data and art, and their terms: the repository's `NOTICE`. */
    Notices("legal/NOTICE", "about/legal/notice"),

    /** The GNU General Public License, version 3: the repository's `LICENSE`. */
    License("legal/LICENSE", "about/legal/license"),
}

/** Row id of the [index]th reading block of [doc]. */
internal fun legalBlockRowId(doc: LegalDoc, index: Int): String = "${doc.rowId}/$index"

/** A reading block holds at most this many characters (about ten lines in the pane), so a focused block fits the screen and one D-pad press moves a readable step. */
internal const val LegalBlockMaxChars = 700

/**
 * Splits a legal text into reading blocks. Each block is a focus stop in the reader, so the D-pad (the only way to scroll a pane that
 * has no touch) steps through the text in readable pieces. Paragraphs (runs of lines separated by a blank line) are kept whole and
 * joined, blank line between, until the next paragraph would take a block past [maxChars]. A paragraph longer than that is split
 * between its lines. A single line is never cut. The words and their order are unchanged: only whitespace at the line ends and
 * between paragraphs is normalized, and indentation is kept.
 */
internal fun legalTextBlocks(text: String, maxChars: Int = LegalBlockMaxChars): List<String> {
    require(maxChars > 0) { "maxChars must be positive" }
    val paragraphs = ArrayList<List<String>>()
    var lines = ArrayList<String>()
    for (raw in text.replace("\r\n", "\n").split('\n')) {
        val line = raw.trimEnd()
        if (line.isEmpty()) {
            if (lines.isNotEmpty()) paragraphs.add(lines)
            lines = ArrayList()
        } else {
            lines.add(line)
        }
    }
    if (lines.isNotEmpty()) paragraphs.add(lines)

    val blocks = ArrayList<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) blocks.add(current.toString())
        current.clear()
    }
    for (paragraph in paragraphs) {
        val joined = paragraph.joinToString("\n")
        if (joined.length > maxChars) {
            flush()
            val part = StringBuilder()
            for (line in paragraph) {
                if (part.isNotEmpty() && part.length + 1 + line.length > maxChars) {
                    blocks.add(part.toString())
                    part.clear()
                }
                if (part.isNotEmpty()) part.append('\n')
                part.append(line)
            }
            if (part.isNotEmpty()) blocks.add(part.toString())
            continue
        }
        if (current.isNotEmpty() && current.length + 2 + joined.length > maxChars) flush()
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(joined)
    }
    flush()
    return blocks
}

/** The text of one packaged legal asset, or null when it cannot be read. Does file I/O: call it off the main thread. */
internal fun readLegalAsset(context: Context, path: String): String? =
    runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
