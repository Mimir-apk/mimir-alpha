package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.text.TextStyle

/**
 * Everything a book title's fit depends on ([mimirFitBookTitle]): the title, the room (width in px and the height the leather leaves), the size ladder and
 * line budget, the density and font scale that turn sp into px, the base text style, the locale the hyphenator reads, and the [hyphenator] that builds the
 * hyphenation (compared by identity: the production one is a single instance).
 */
internal data class MimirBookTitleFitKey(
    val title: String,
    val widthPx: Int,
    val availableHeightPx: Float,
    val maxSp: Float,
    val minSp: Float,
    val maxLines: Int,
    val density: Float,
    val fontScale: Float,
    val style: TextStyle,
    val locale: String,
    val hyphenator: Any,
)

/**
 * A small least-recently-used memory of the fits decided ([MimirBookTitleFit]: size, lines, hyphenated text), so a shelf that is paged back to, or two books
 * with the same name in the same room, do not lay the title out again. A coverless title that no size holds whole costs a few layouts per size to settle
 * (the hyphenated pass probes each too-wide word at each size), about seventy for a long name; a decided fit costs one lookup.
 *
 * Pure (no Android types besides the style in the key) and meant for the main thread, where compositions run; the lock only keeps a stray background
 * composition from corrupting the access order, and is never held while a fit is computed.
 */
internal class MimirBookTitleFitCache(private val capacity: Int) {
    private val entries = object : LinkedHashMap<MimirBookTitleFitKey, MimirBookTitleFit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MimirBookTitleFitKey, MimirBookTitleFit>?): Boolean = size > capacity
    }

    /** The fit remembered under [key], or [compute]d now and remembered. */
    fun getOrCompute(key: MimirBookTitleFitKey, compute: () -> MimirBookTitleFit): MimirBookTitleFit {
        synchronized(entries) { entries[key] }?.let { return it }
        val fit = compute()
        synchronized(entries) { entries[key] = fit }
        return fit
    }

    val size: Int get() = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }
}

/** The process-wide memory of decided book-title fits: 256 of them, a few kilobytes. */
internal val MimirBookTitleFits = MimirBookTitleFitCache(capacity = 256)
