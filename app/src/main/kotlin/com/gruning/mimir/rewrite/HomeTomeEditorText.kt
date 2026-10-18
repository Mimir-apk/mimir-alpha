package com.gruning.mimir.rewrite

import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * What the Tome editor says when a save or a delete fails (localization step 3). The Tome store (`HomeCollectionStore`), the Home
 * coordinator and the app coordinator hand the failure over as an English sentence in [HomeTomeEditResult.Failed.message] (and the
 * library's `draftError`), and that sentence is what they compare and pass around, so it stays the key. The editor resolves a sentence it
 * knows to the string resource of the player's language and shows a sentence it does not know exactly as it came.
 *
 * `HomeTomeEditorStoredTextTest` asserts every key here equals the English resource it resolves to, and that the code that writes it still
 * does, so editing one without the other fails a test.
 */
internal object HomeTomeEditorStoredText {
    /** The English sentence the editor is handed, and the resource that says it in the player's language. */
    internal class Entry(val stored: String, @StringRes val textRes: Int)

    /** The `%1$d` in a resource is the name limit; a resource without one ignores it. */
    val messages: List<Entry> = listOf(
        Entry("Couldn’t save Tome", R.string.app_tome_error_save_failed),
        Entry("Couldn’t delete Tome", R.string.app_tome_error_delete_failed),
        Entry("This Tome is no longer available", R.string.app_tome_error_gone),
        Entry(requireNotNull(homeTomeLabelError("", emptyList())), R.string.app_tome_error_name_empty),
        Entry(requireNotNull(homeTomeLabelError("x".repeat(HomeTomeLabelMax + 1), emptyList())), R.string.app_tome_error_name_long),
        Entry("That Tome name is already in use", R.string.app_tome_error_name_taken),
    )

    /** [stored] in the language of [res]: the matching resource, or [stored] itself when no entry matches. */
    fun messageText(res: Resources, stored: String): String =
        messages.firstOrNull { it.stored == stored }?.let { res.getString(it.textRes, HomeTomeLabelMax) } ?: stored
}

/** The editor's status and legend in the language of [res]; a failure's sentence is the store's English one, resolved by [HomeTomeEditorStoredText]. */
internal fun homeTomeEditorCopy(
    res: Resources,
    draftError: String?,
    deletePending: Boolean,
    confirmDelete: Boolean,
    editing: Boolean,
): HomeTomeEditorCopy = when {
    draftError != null -> HomeTomeEditorCopy(
        status = HomeTomeEditorStoredText.messageText(res, draftError),
        legend = res.getString(if (editing) R.string.app_tome_editor_legend_delete else R.string.app_tome_editor_legend_cancel),
    )
    deletePending -> HomeTomeEditorCopy(status = res.getString(R.string.app_tome_editor_status_deleting), legend = null)
    confirmDelete -> HomeTomeEditorCopy(
        status = res.getString(R.string.app_tome_editor_status_confirm_delete),
        legend = res.getString(R.string.app_tome_editor_legend_confirm),
    )
    editing -> HomeTomeEditorCopy(
        status = res.getString(R.string.app_tome_editor_status_limit, HomeTomeLabelMax),
        legend = res.getString(R.string.app_tome_editor_legend_delete),
    )
    else -> HomeTomeEditorCopy(
        status = res.getString(R.string.app_tome_editor_status_limit, HomeTomeLabelMax),
        legend = res.getString(R.string.app_tome_editor_legend_cancel),
    )
}
