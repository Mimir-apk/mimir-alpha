package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.library.launchCardEmulatorLabel

/**
 * Main's Home launch card EMULATOR name (round 5b): the emulator the launch would start, from the options the card
 * loads when it opens. With nothing marked it is [fallback], the platform's resolved emulator name the card showed
 * before options landed, exactly as before, so the row never changes layout. One rule with the Library detail:
 * [launchCardEmulatorLabel].
 */
internal fun homeLaunchCardEmulatorLabel(options: OpenWithOptions?, fallback: String?, overridePending: Boolean = false): String? =
    if (options == null && overridePending) HomeLaunchCardEmulatorLoadingLabel else launchCardEmulatorLabel(options, whenUnknown = fallback)

/**
 * What the EMULATOR row says for a game with an override whose options have not loaded and are not cached: not "Automatic" and not the platform's
 * default, both untrue for it, and not a name the card cannot know yet. "Checking…" (a bare ellipsis read as a truncated name), for the moment the load takes.
 */
internal const val HomeLaunchCardEmulatorLoadingLabel = "Checking…"

/** What a reload leaves on the card: the loaded options, or, when the load failed, the options the card already had. */
internal fun reloadedOpenWithOptions(current: OpenWithOptions?, loaded: OpenWithOptions?): OpenWithOptions? = loaded ?: current

/**
 * The last options loaded for each game this process has shown a card for, newest [capacity] kept. A card seeds its options from here when it opens, so
 * the EMULATOR fact names the emulator at once instead of falling to "Automatic" (or the platform's default, for a game with an override) until the
 * load lands. The load still runs and replaces them.
 */
internal class HomeLaunchCardOptionsCache(private val capacity: Int = 32) {
    private val entries = object : LinkedHashMap<GameId, OpenWithOptions>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GameId, OpenWithOptions>?): Boolean = size > capacity
    }

    operator fun get(gameId: GameId): OpenWithOptions? = entries[gameId]

    operator fun set(gameId: GameId, options: OpenWithOptions) {
        entries[gameId] = options
    }
}
