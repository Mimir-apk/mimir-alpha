package com.gruning.mimir.rewrite.feature.library

import android.content.res.Resources
import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent

/**
 * The Library's words that are chosen in code (localization step 3), kept out of the screen's file so its size budget does not grow.
 * Each takes the [Resources] of the host (`libraryResources()` in a composable, any locale in a test) and returns a whole string from
 * a resource: nothing here joins translated pieces.
 */

/** The Add pill's words on the shelf; [NativeAddOwnerCopy.addLabel] is the same sentence in English for the picker (a later step). */
@StringRes
internal fun LibraryShelfMode.addLabelRes(): Int = when (this) {
    LibraryShelfMode.EMULATION -> R.string.library_shelf_add_games
    LibraryShelfMode.ANDROID_GAMES -> R.string.library_shelf_add_game
}

/** The view-mode hint: the token, then the glyph and the name of the view L/R switches to. */
internal fun gamelistViewModeHintLabel(res: Resources, mode: GamelistViewMode): String =
    res.getString(if (mode == GamelistViewMode.CAROUSEL) R.string.library_gamelist_view_list else R.string.library_gamelist_view_shelf)

/** What a game list counts: games, or the Android apps of Favorites' last tab. */
internal enum class GamelistItemKind { GAME, APP }

/**
 * The title a game list wears: Favorites, the platform's own name (platform names are not translated), or "Games" for a platform the
 * list does not know.
 */
internal fun platformLabel(res: Resources, platforms: List<PlatformRow>, id: PlatformId?): String =
    when (id) {
        LibraryViewModel.FAVORITES_PLATFORM_ID -> res.getString(R.string.library_gamelist_title_favorites)
        else -> platforms.firstOrNull { it.platformId == id }?.label ?: res.getString(R.string.library_gamelist_title_fallback)
    }

private fun itemCountLabel(res: Resources, count: Int, kind: GamelistItemKind): String = when (kind) {
    GamelistItemKind.GAME ->
        if (count == 0) res.getString(R.string.library_gamelist_count_games_none)
        else res.getQuantityString(R.plurals.library_gamelist_count_games, count, count)
    GamelistItemKind.APP ->
        if (count == 0) res.getString(R.string.library_gamelist_count_apps_none)
        else res.getQuantityString(R.plurals.library_gamelist_count_apps, count, count)
}

/**
 * @param countSettled false while a platform switch is still resolving. The count is then OMITTED rather
 * than printed, because a zero count renders **"No games"** — and during a switch the list is
 * legitimately empty for a beat, so the header stated that a three-game platform had none. Same defect as
 * the body's empty message, same fix: say nothing until there is something true to say. Suppressing the
 * whole entry rather than substituting a placeholder keeps the header from reflowing twice.
 *
 * The line is a list of whole phrases (the quoted query, Favorites, the active filters, the count) set apart by a middle dot; the dot is
 * punctuation, not words, so no translated sentence is joined here.
 */
internal fun gamelistHeaderMeta(
    res: Resources,
    searchQuery: String,
    favoritesOnly: Boolean,
    count: Int,
    kind: GamelistItemKind,
    countSettled: Boolean = true,
    /** The Search filters that are on besides the query (a genre, a platform), by their player-facing names: the count line says so the way it says "Favorites". */
    filterLabels: List<String> = emptyList(),
): String = buildList {
    val trimmed = searchQuery.trim()
    if (trimmed.isNotBlank()) add("“$trimmed”")
    if (favoritesOnly) add(res.getString(R.string.library_gamelist_title_favorites))
    addAll(filterLabels)
    if (countSettled) add(itemCountLabel(res, count, kind))
}.joinToString(" · ")

/** Test seam for [gamelistHeaderMeta]: the composable's own call site is not reachable from a unit test. */
internal fun gamelistHeaderMetaForTest(res: Resources, count: Int, countSettled: Boolean): String =
    gamelistHeaderMeta(
        res = res,
        searchQuery = "",
        favoritesOnly = false,
        count = count,
        kind = GamelistItemKind.GAME,
        countSettled = countSettled,
    )

/**
 * A sentence with the player's own words in the middle, such as the no-match line. [format] holds ONE `%1$s`: the text before and after it
 * is chrome (the shared seam may draw it in caps), [authored] is drawn as typed. The translated sentence is cut at the slot, never built
 * from pieces, so each language puts the query where its grammar wants it.
 */
internal fun libraryAroundAuthored(res: Resources, @StringRes format: Int, authored: String): MimirTextIntent {
    val parts = res.getString(format, AuthoredSlot).split(AuthoredSlot)
    if (parts.size != 2) return MimirTextIntent.Body(res.getString(format, authored))
    return MimirTextIntent.Mixed(
        listOf(
            MimirTextFragment.Chrome(parts[0]),
            MimirTextFragment.Authored(authored),
            MimirTextFragment.Chrome(parts[1]),
        ),
    )
}

/** A private-use character no translation contains: it stands where the authored text goes while the sentence is cut. */
private const val AuthoredSlot = "\uE000"
