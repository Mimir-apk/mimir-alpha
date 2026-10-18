package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import java.util.Locale

/**
 * The words of a file's kind, its size line and its date (localization step 4b), kept out of the big screen files so their size budgets do not grow. Each takes the
 * [Resources] of the host and returns a whole string; a file's own name, its extension and a platform code are the player's and arrive as arguments.
 *
 * **BIOS, ZIP, 7Z and a file's extension are format identifiers, not words**, and stay in code exactly as they were: they are what the file system calls the thing, the
 * same in every language.
 */

/** The identifier an emulator BIOS wears as its badge and its chip. */
internal const val FileBrowserBiosWord = "BIOS"

/** The locale the Files dates are written in: the host's, so a month name follows the player's language. English stays on `Locale.US` whatever the region. */
internal fun Resources.filesLocale(): Locale {
    val locale = configuration.locales[0] ?: Locale.US
    return if (locale.language == "en") Locale.US else locale
}

/**
 * The line a file or folder row shows for its size, on display. It reads as core-files' `FileItem.displaySize` word for word in English (`FilesKindTextTest` and
 * `FilesSizeTextTest` walk the same cases): a drive row's capacity ([fileBrowserVolumeCapacityText]), a folder's count as a plural ("1 item", "12 items", nothing for
 * a folder that cannot be read), a file's size as its own figure in bytes and units (B, KB, MB and GB are unit symbols, the same in every language).
 */
internal fun fileBrowserSizeText(res: Resources, item: FileItem): String {
    fileBrowserVolumeCapacityText(res, item, compact = false)?.let { return it }
    return if (item.isDirectory) {
        item.childCount?.let { count -> res.getQuantityString(R.plurals.files_item_count, count, count) }.orEmpty()
    } else {
        FileItem.formatSize(item.size)
    }
}

private const val FileBrowserGibibyte = 1024L * 1024L * 1024L

/**
 * A drive-chooser row's free space, on display, or null for a row that is not a drive. It reads as core-files' `FileItem.volumeCapacity` ("119.4 GB free of 238.3 GB")
 * and, with [compact], `volumeCapacityCompact` ("317 GB free": whole gigabytes, no total, for the tile's caption lane) word for word in English. Like core-files' it needs
 * both halves and a positive total: a volume whose `StatFs` failed reports zeros, and "0 B free of 0 B" is worse than saying nothing.
 */
internal fun fileBrowserVolumeCapacityText(res: Resources, item: FileItem, compact: Boolean): String? {
    val free = item.volumeFreeBytes ?: return null
    val total = item.volumeTotalBytes ?: return null
    if (total <= 0L || free < 0L) return null
    return when {
        !compact -> res.getString(R.string.files_volume_free_of, FileItem.formatSize(free), FileItem.formatSize(total))
        free < FileBrowserGibibyte -> res.getString(R.string.files_volume_free, FileItem.formatSize(free))
        else -> res.getString(R.string.files_volume_free_gb, Math.round(free / FileBrowserGibibyte.toDouble()))
    }
}

/**
 * The words of a Search filter chip, on display. `core-files` writes the chips' English in `FileSearchFilter.label` (the underlying engine source, which the width model of `FileBrowserSearchLayout` was measured against); the chip draws this instead. BIOS is a format identifier and reads the same everywhere.
 */
internal fun fileBrowserSearchFilterLabel(res: Resources, filter: FileSearchFilter): String = when (filter) {
    FileSearchFilter.ALL -> res.getString(R.string.files_search_filter_all)
    FileSearchFilter.GAMES -> res.getString(R.string.files_search_filter_games)
    FileSearchFilter.ARCHIVES -> res.getString(R.string.files_search_filter_archives)
    FileSearchFilter.BIOS -> FileBrowserBiosWord
    FileSearchFilter.SAVES -> res.getString(R.string.files_search_filter_saves)
}
