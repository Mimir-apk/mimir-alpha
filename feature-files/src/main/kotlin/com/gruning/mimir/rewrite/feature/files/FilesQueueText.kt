package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import java.text.NumberFormat

/**
 * The Queue screen's card titles and second lines in the player's language (localization step 4b, item d).
 *
 * **Why the sentences are mapped here and not carried as a resource id in the state.** A card's title, second line and reason are written by the app store
 * (`FileBrowserStore`, `FileBrowserJobControl`, `FileBrowserCompanionCards`) as plain English strings inside `FileQueueEntry` / `FileQueueState`, which are the
 * one job model of `core-model` and are shared with the scan and scrape families. The queue is **never persisted**: it is a `MutableStateFlow` on the process-wide
 * store, written to no file, preference or bundle (the interrupted-extraction cards are rebuilt from core-files' journal on every start), so a resource id plus
 * arguments could have lived in the state. It was not chosen because it would change that shared type and each of the store's write sites (about two dozen),
 * and the store is at its source-size budget with several tests pinning its text as source. So the stored value stays English and the Queue screen maps it on
 * display, as the chips are chosen from the card's state: a card already on the queue follows a language change, and nothing in the store changes.
 *
 * **What this costs, said plainly.** A sentence the store writes in English is recognised here by its frame, and the names and counts inside it come back out as
 * arguments of ONE format string per sentence (nothing is assembled from translated pieces except the card's " · " between a line and its notes, which is the
 * resource [R.string.files_queue_line_join]). Two things follow. (1) A sentence this file does not know is drawn as written, in English: core-files' failure reasons
 * (`FileFailureReasons`, `ExtractionCancelOutcome.sentence`, `InterruptedExtraction.sentence`) are emitted outside this module and are not mapped. (2) Where a frame holds two names
 * with only " to " between them ("Moved from A to B"), a name that itself contains " to " can be split at the wrong place; the words are then still the player's own,
 * only the join between them is off. A card titled "N files" is read as a count. A rename's old name is known (it is the card's title), so that frame is exact.
 *
 * `FilesQueueCardTextTest` walks every frame against the English the store writes; `FileBrowserQueueCopyDriftTest` (app) pins the store's side, so an English edit there
 * that this file does not follow is a red test and not a silent English line.
 */

/** What the store puts between a card's line and a note added to it (`withNote`). */
private const val QueueLineJoin = " · "

private val TitleFilesPattern = Regex("""(\d+) files""")

/** A card's title: the player's own file name, or the store's "3 files" for several, which is drawn as a plural. */
internal fun fileBrowserQueueTitleText(res: Resources, title: String): String {
    val count = TitleFilesPattern.matchEntire(title)?.groupValues?.get(1)?.toIntOrNull() ?: return title
    return res.getQuantityString(R.plurals.files_queue_title_files, count, count)
}

/**
 * A card's second line: the sentence the store wrote when it queued the card (or the reason of a stopped one), then any notes it added with ` · `.
 * [title] is the card's title, which pins a rename's old name. A line, or a note, that is not known is drawn as written.
 */
internal fun fileBrowserQueueLineText(res: Resources, line: String, title: String = ""): String {
    if (line.isBlank()) return line
    val parts = line.split(QueueLineJoin)
    // The SHORTEST head that is a known sentence: a note is separated by " · " and a frame's name slot would swallow it ("Moved A to B · C kept its old name" is
    // a move to "B · C kept its old name" if the longer head were tried first). "Delete · saves and states kept" has the join inside it and is one sentence;
    // a file name that has one inside it is split early and the rest is drawn as written.
    val heads = if (parts.size >= 2 && parts[0] == "Delete" && parts[1] == "saves and states kept") listOf(2) else (1..parts.size).toList()
    for (k in heads) {
        val head = parts.subList(0, k).joinToString(QueueLineJoin)
        val localized = localizeSentence(res, head, title) ?: continue
        val notes = parts.subList(k, parts.size)
        return if (notes.isEmpty()) localized else withNotes(res, localized, notes)
    }
    return line
}

private fun withNotes(res: Resources, first: String, notes: List<String>): String {
    var result = first
    var index = 0
    while (index < notes.size) {
        var taken = 1
        var text = notes[index]
        // The shortest run of segments that is a known note: each note frame ends in its own fixed words, so a name with " · " in it is gathered up to them.
        for (end in index + 1..notes.size) {
            val candidate = notes.subList(index, end).joinToString(QueueLineJoin)
            val localized = localizeNote(res, candidate) ?: continue
            text = localized
            taken = end - index
            break
        }
        result = res.getString(R.string.files_queue_line_join, result, text)
        index += taken
    }
    return result
}

private class QueueSentence(val pattern: Regex, val render: (Resources, List<String>) -> String)

/** A count the store wrote with US grouping ("1,234"), as a number. */
private fun countOf(text: String): Int = text.replace(",", "").toIntOrNull() ?: 0

/** The same count written the way the host's language writes numbers (English keeps its comma). */
private fun Resources.groupedCount(text: String): String =
    text.replace(",", "").toLongOrNull()?.let { NumberFormat.getIntegerInstance(filesLocale()).format(it) } ?: text

/** A label that is the store's "3 files" is a count; any other is the player's file name. */
private fun movedOrCopied(res: Resources, verb: String, label: String, destination: String): String {
    val files = TitleFilesPattern.matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
    val moved = verb == "Moved"
    return when {
        files != null ->
            res.getQuantityString(if (moved) R.plurals.files_queue_line_moved_files else R.plurals.files_queue_line_copied_files, files, files, destination)
        else -> res.getString(if (moved) R.string.files_queue_line_moved else R.string.files_queue_line_copied, label, destination)
    }
}

private fun lateWithCompanions(res: Resources, verb: String, count: String, destination: String): String {
    val plural = if (verb == "moved") R.plurals.files_queue_text_late_companions_moved else R.plurals.files_queue_text_late_companions_copied
    return res.getQuantityString(plural, countOf(count), res.groupedCount(count), destination)
}

private val Sentences: List<QueueSentence> = listOf(
    QueueSentence(Regex("""Delete · saves and states kept""")) { res, _ -> res.getString(R.string.files_queue_line_delete_kept) },
    QueueSentence(Regex("""Delete""")) { res, _ -> res.getString(R.string.files_queue_line_delete) },
    QueueSentence(Regex("""Renamed (.+) to (.+)""")) { res, g -> res.getString(R.string.files_queue_line_renamed, g[0], g[1]) },
    QueueSentence(Regex("""(Moved|Copied) from (.+) to (.+)""")) { res, g ->
        res.getString(if (g[0] == "Moved") R.string.files_queue_line_moved_from else R.string.files_queue_line_copied_from, g[1], g[2])
    },
    QueueSentence(Regex("""(Moved|Copied) (.+) to (.+)""")) { res, g -> movedOrCopied(res, g[0], g[1], g[2]) },
    QueueSentence(Regex("""A file named (.+) is already there\.""")) { res, g -> res.getString(R.string.files_queue_line_name_taken, g[0]) },
    QueueSentence(Regex("""Extract → (.+)""")) { res, g -> res.getString(R.string.files_queue_line_extract, g[0]) },
    QueueSentence(Regex("""Nothing to delete""")) { res, _ -> res.getString(R.string.files_queue_text_nothing_to_delete) },
    QueueSentence(Regex("""Nothing was changed\.""")) { res, _ -> res.getString(R.string.files_queue_text_nothing_changed) },
    QueueSentence(Regex("""Nothing was moved\. Everything is still where it was\.""")) { res, _ -> res.getString(R.string.files_queue_text_nothing_moved) },
    QueueSentence(Regex("""Nothing was copied\. The unfinished copy was removed\.""")) { res, _ -> res.getString(R.string.files_queue_text_nothing_copied) },
    QueueSentence(Regex("""Too late to stop: everything you picked was already (moved|copied) to (.+)\. The (1) file that goes with it is still where it was\.""")) { res, g ->
        lateWithCompanions(res, g[0], g[2], g[1])
    },
    QueueSentence(Regex("""Too late to stop: everything you picked was already (moved|copied) to (.+)\. The ([\d,]+) files that go with it are still where they were\.""")) { res, g ->
        lateWithCompanions(res, g[0], g[2], g[1])
    },
    QueueSentence(Regex("""Too late to stop: everything was already (moved|copied) to (.+)\.""")) { res, g ->
        res.getString(if (g[0] == "moved") R.string.files_queue_text_late_moved else R.string.files_queue_text_late_copied, g[1])
    },
    QueueSentence(Regex("""([\d,]+) of ([\d,]+) files were moved to (.+) and kept\. The rest are still where they were\.""")) { res, g ->
        res.getString(R.string.files_queue_text_partial_moved, res.groupedCount(g[0]), res.groupedCount(g[1]), g[2])
    },
    QueueSentence(Regex("""([\d,]+) of ([\d,]+) files were copied to (.+) and kept\. The rest were not copied\.""")) { res, g ->
        res.getString(R.string.files_queue_text_partial_copied, res.groupedCount(g[0]), res.groupedCount(g[1]), g[2])
    },
    QueueSentence(Regex("""Too late to stop: everything was already extracted\.""")) { res, _ -> res.getString(R.string.files_queue_text_late_extracted) },
    QueueSentence(Regex("""Nothing was extracted\.""")) { res, _ -> res.getString(R.string.files_queue_text_nothing_extracted) },
)

private val Notes: List<QueueSentence> = listOf(
    QueueSentence(Regex("""The disc it lists stays\.""")) { res, _ -> res.getString(R.string.files_queue_note_disc_playlist) },
    QueueSentence(Regex("""The disc they list stays\.""")) { res, _ -> res.getString(R.string.files_queue_note_disc_playlists) },
    QueueSentence(Regex("""The ([\d,]+) discs it lists stay\.""")) { res, g -> res.getString(R.string.files_queue_note_discs_playlist, res.groupedCount(g[0])) },
    QueueSentence(Regex("""The ([\d,]+) discs they list stay\.""")) { res, g -> res.getString(R.string.files_queue_note_discs_playlists, res.groupedCount(g[0])) },
    QueueSentence(Regex("""(.+) stayed behind: its cue lists it under that name""")) { res, g -> res.getString(R.string.files_queue_note_stayed_cue, g[0]) },
    QueueSentence(Regex("""(.+) stayed behind: it only moves under its disc's name""")) { res, g -> res.getString(R.string.files_queue_note_stayed_disc, g[0]) },
    QueueSentence(Regex("""(.+) kept its old name""")) { res, g -> res.getString(R.string.files_queue_note_kept_name, g[0]) },
    QueueSentence(Regex("""The file is back\. Your library will re-add the game the next time it scans\.""")) { res, _ -> res.getString(R.string.files_queue_note_file_back) },
    QueueSentence(Regex("""That folder isn't one of your game sources, so it left your library\.""")) { res, _ -> res.getString(R.string.files_queue_note_left_sources) },
    QueueSentence(Regex("""Your library doesn't list it as that platform's game any more\.""")) { res, _ -> res.getString(R.string.files_queue_note_left_identity) },
    QueueSentence(Regex("""Mimir couldn't update your library — it'll catch up next time it scans\.""")) { res, _ -> res.getString(R.string.files_queue_note_library_unreachable) },
    QueueSentence(Regex("""It was saved under a new name, so your library will re-add it on the next scan\.""")) { res, _ -> res.getString(R.string.files_queue_note_new_name) },
)

private fun localizeSentence(res: Resources, text: String, title: String): String? {
    // A rename's old name is the card's title, so the frame is exact there: "Renamed Final Fantasy to the max.gba to Mario.gba".
    if (title.isNotEmpty()) {
        Regex("Renamed " + Regex.escape(title) + " to (.+)").matchEntire(text)?.let { match ->
            return res.getString(R.string.files_queue_line_renamed, title, match.groupValues[1])
        }
    }
    return localizeWith(Sentences, res, text) ?: localizeNote(res, text)
}

private fun localizeNote(res: Resources, text: String): String? = localizeWith(Notes, res, text)

private fun localizeWith(table: List<QueueSentence>, res: Resources, text: String): String? {
    for (sentence in table) {
        val match = sentence.pattern.matchEntire(text) ?: continue
        return sentence.render(res, match.groupValues.drop(1))
    }
    return null
}
