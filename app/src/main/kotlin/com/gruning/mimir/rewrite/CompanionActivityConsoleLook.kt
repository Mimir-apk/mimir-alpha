package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.retroAchievementsPlatformIdForConsoleName
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.resolver.library.PlatformLabels

/**
 * How a console is called and drawn on the achievements page (a platform's name is the same wherever a player reads it). [display] is the name the shelf's lattice
 * shows for that platform ("Genesis / Mega Drive", not RetroAchievements' "Genesis/Mega Drive"); [artId] is the id the lattice draws its line-art by, or null for a console
 * the lattice has no platform for (a hub, an event: it keeps RetroAchievements' own words and no glyph of its own). The filter itself still holds RetroAchievements' name:
 * that is what the games are matched by.
 */
internal class ActivityConsoleLook(val display: String, val artId: String?)

/** The look of one RetroAchievements console [raConsoleName], by the lattice's own name function ([PlatformLabels]) and the library's id for the platform. */
internal fun activityConsoleLook(raConsoleName: String): ActivityConsoleLook {
    val platform = retroAchievementsPlatformIdForConsoleName(raConsoleName) ?: return ActivityConsoleLook(raConsoleName, null)
    val library = PlatformLabels.libraryIdFor(PlatformId(platform))
    return ActivityConsoleLook(PlatformLabels.labelFor(library), library.value)
}

/**
 * The looks of the consoles a page holds, by RetroAchievements' name. Two RetroAchievements consoles that are ONE platform on the lattice ("NES/Famicom" and "Famicom Disk System")
 * would read as the same word twice in the list and could not be told apart, so those keep RetroAchievements' own words (their glyph stays the platform's).
 */
internal fun activityConsoleLooks(names: Collection<String>): Map<String, ActivityConsoleLook> {
    val looks = names.associateWith(::activityConsoleLook)
    val taken = looks.values.groupingBy { it.display.lowercase() }.eachCount()
    return looks.mapValues { (name, look) -> if ((taken[look.display.lowercase()] ?: 0) > 1) ActivityConsoleLook(name, look.artId) else look }
}
