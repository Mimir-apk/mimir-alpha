package com.gruning.mimir.rewrite.core.resolver.migration

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementAccount
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleV1
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.migration.MigrationTheme

/**
 * The apply-time WRITE seams the importer needs — each already validated by [MigrationImporter.preview],
 * so implementations only persist (they don't re-decide). The app wires these to the real
 * `LibraryRepository` / `LaunchController` / `ThemeStore`; tests pass recording fakes. Keeping the writes
 * behind this interface is what makes [MigrationImporter.apply] unit-testable off-device.
 */
interface MigrationSeams {
    suspend fun setFavorite(gameId: GameId)
    suspend fun setHidden(gameId: GameId)
    suspend fun setEmulatorOverride(gameId: GameId, emulatorId: String)

    /** Apply a theme by its `MimirThemeId.storageKey`. Preview already confirmed the key is known. */
    fun applyTheme(themeStorageKey: String)

    /**
     * Keep each account's saved achievement progress for when that account signs in. Returns how many games were taken. The default keeps nothing, so a seam that
     * predates achievements is unchanged; an implementation never overwrites progress the device already holds for the account (a restore is additive).
     */
    suspend fun restoreAchievements(accounts: List<MigrationAchievementAccount>): Int = 0
}

/** How a per-game emulator override resolved during preview. */
sealed interface OverrideOutcome {
    val record: MigrationGameRecord
    val emulatorId: String

    /** Game found AND the emulator is a recognized candidate — will be written on apply. */
    data class Applicable(val gameId: GameId, override val emulatorId: String, override val record: MigrationGameRecord) : OverrideOutcome

    /** Game found, but the chosen emulator isn't one the rewrite recognizes for it — skipped (never dangling). */
    data class UnknownEmulator(val gameId: GameId, override val emulatorId: String, override val record: MigrationGameRecord) : OverrideOutcome

    /** The game itself isn't in the rewrite library — skipped and reported. */
    data class GameNotFound(override val emulatorId: String, override val record: MigrationGameRecord) : OverrideOutcome
}

/**
 * The dry-run plan: exactly what an apply WOULD do, computed without writing. The UI shows this so the user
 * confirms before anything changes.
 */
data class ImportPlan(
    val favorites: List<MatchOutcome>,
    val hidden: List<MatchOutcome>,
    val overrides: List<OverrideOutcome>,
    /** The theme to apply, ONLY if its key is known; null otherwise. */
    val theme: MigrationTheme?,
    /** The raw themeId that was in the bundle but NOT a known theme (for honest reporting); null if fine/absent. */
    val ignoredThemeId: String?,
    val sourceRootHints: List<String>,
    val profileDisplayName: String?,
    /** The achievement progress the bundle carries, checked: a row with no name, an impossible count or a bad id is gone. */
    val achievements: List<MigrationAchievementAccount> = emptyList(),
) {
    val favoritesMatched: List<MatchOutcome.Matched> get() = favorites.filterIsInstance<MatchOutcome.Matched>()
    val favoritesUnmatched: List<MatchOutcome.Unmatched> get() = favorites.filterIsInstance<MatchOutcome.Unmatched>()
    val hiddenMatched: List<MatchOutcome.Matched> get() = hidden.filterIsInstance<MatchOutcome.Matched>()
    val hiddenUnmatched: List<MatchOutcome.Unmatched> get() = hidden.filterIsInstance<MatchOutcome.Unmatched>()
    val overridesApplicable: List<OverrideOutcome.Applicable> get() = overrides.filterIsInstance<OverrideOutcome.Applicable>()

    /** Titles the importer couldn't find in the library — the honest "not found" list for the result screen. */
    fun notFoundTitles(): List<String> {
        val titles = buildList {
            favoritesUnmatched.forEach { add(it.record.displayTitle()) }
            hiddenUnmatched.forEach { add(it.record.displayTitle()) }
            overrides.filterIsInstance<OverrideOutcome.GameNotFound>().forEach { add(it.record.displayTitle()) }
        }
        return titles.distinct()
    }
}

/** The honest tally after an apply, for the result screen. */
data class ImportResult(
    val favoritesRestored: Int,
    val favoritesNotFound: Int,
    val hiddenRestored: Int,
    val hiddenNotFound: Int,
    val overridesRestored: Int,
    val overridesSkipped: Int,
    val themeApplied: Boolean,
    val ignoredThemeId: String?,
    val sourceRootsToRegrant: List<String>,
    val notFoundTitles: List<String>,
    /** How many games of saved achievement progress were taken. */
    val achievementGamesRestored: Int = 0,
)

private fun MigrationGameRecord.displayTitle(): String =
    title.ifBlank { fileName }.let { base ->
        if (platformLabel.isNotBlank()) "$base · $platformLabel" else base
    }

/**
 * Applies a migration bundle onto the ALREADY-SCANNED rewrite library. The contract is "re-apply my markers
 * onto games I already have" — it never builds the library, never re-scans, and never fails on a per-record
 * miss (a game the rewrite doesn't have is reported, not an error). Idempotent/additive: the write seams are
 * upserts, so re-importing is safe.
 *
 * SAF source-folder grants CANNOT be transferred across packages, so [ImportPlan.sourceRootHints] are surfaced
 * to the user as "re-pick these folders", never auto-granted.
 */
class MigrationImporter(private val matcher: MigrationMatcher) {

    /**
     * Compute the exact plan without writing.
     * @param isKnownTheme is this `themeId` a real `MimirThemeId.storageKey`? (app supplies; core-resolver
     *   can't see core-ui). Unknown themes are ignored + reported rather than silently forced to DEFAULT.
     * @param isEmulatorRecognized is [emulatorId] a candidate the rewrite knows for this matched game?
     *   (app supplies via the launch pipeline: does the game's Open-With list contain it?). Unrecognized →
     *   skipped, never a dangling override.
     */
    suspend fun preview(
        bundle: MigrationBundleV1,
        isKnownTheme: (String) -> Boolean,
        isEmulatorRecognized: suspend (gameId: GameId, record: MigrationGameRecord, emulatorId: String) -> Boolean,
    ): ImportPlan {
        val favorites = bundle.favorites.map { matcher.resolve(it) }
        val hidden = bundle.hidden.map { matcher.resolve(it) }
        val overrides = bundle.emulatorOverrides.map { ovr ->
            when (val outcome = matcher.resolve(ovr.game)) {
                is MatchOutcome.Matched ->
                    if (isEmulatorRecognized(outcome.gameId, ovr.game, ovr.emulatorId)) {
                        OverrideOutcome.Applicable(outcome.gameId, ovr.emulatorId, ovr.game)
                    } else {
                        OverrideOutcome.UnknownEmulator(outcome.gameId, ovr.emulatorId, ovr.game)
                    }
                is MatchOutcome.Unmatched -> OverrideOutcome.GameNotFound(ovr.emulatorId, ovr.game)
            }
        }
        val themeKey = bundle.theme?.themeId
        val knownTheme = themeKey?.takeIf(isKnownTheme)
        return ImportPlan(
            favorites = favorites,
            hidden = hidden,
            overrides = overrides,
            theme = knownTheme?.let { MigrationTheme(it) },
            ignoredThemeId = themeKey?.takeIf { knownTheme == null },
            sourceRootHints = bundle.sourceRoots.map { it.friendlyLabel },
            profileDisplayName = bundle.profile?.displayName,
            achievements = bundle.achievements.mapNotNull { checkedAchievements(it) }.take(MAX_ACHIEVEMENT_ACCOUNTS),
        )
    }

    /** Execute the plan via [seams]; returns the honest tally. Never throws on a per-record basis. */
    suspend fun apply(plan: ImportPlan, seams: MigrationSeams): ImportResult {
        plan.favoritesMatched.forEach { seams.setFavorite(it.gameId) }
        plan.hiddenMatched.forEach { seams.setHidden(it.gameId) }
        plan.overridesApplicable.forEach { seams.setEmulatorOverride(it.gameId, it.emulatorId) }
        val themeApplied = plan.theme?.let { seams.applyTheme(it.themeId); true } ?: false
        val achievementGames = if (plan.achievements.isEmpty()) 0 else seams.restoreAchievements(plan.achievements)
        return ImportResult(
            favoritesRestored = plan.favoritesMatched.size,
            favoritesNotFound = plan.favoritesUnmatched.size,
            hiddenRestored = plan.hiddenMatched.size,
            hiddenNotFound = plan.hiddenUnmatched.size,
            overridesRestored = plan.overridesApplicable.size,
            overridesSkipped = plan.overrides.size - plan.overridesApplicable.size,
            themeApplied = themeApplied,
            ignoredThemeId = plan.ignoredThemeId,
            sourceRootsToRegrant = plan.sourceRootHints,
            notFoundTitles = plan.notFoundTitles(),
            achievementGamesRestored = achievementGames,
        )
    }

    /**
     * An account's progress as the importer will hand it on, or null when nothing in it is usable. A game keeps only a positive RA id, a title, and counts that
     * can be true (some earned, never more than the set holds, and a hardcore count never past the earned one); an image path stays only in RA's one relative shape; an
     * award word is kept only as a short string (the restoring app reads it, and a word it does not know is no award). Scores are never negative. Duplicate ids keep the first.
     *
     * Bounded, because a bundle is a file from elsewhere: a name longer than RetroAchievements allows is no account, an account keeps at most
     * [MAX_ACHIEVEMENT_GAMES] games, a title and a console name are cut to [MAX_TITLE_CHARS] and [MAX_CONSOLE_CHARS]. (The ledger bounds what it writes the same
     * way; these keep an oversized bundle from ever reaching it.)
     */
    private fun checkedAchievements(account: MigrationAchievementAccount): MigrationAchievementAccount? {
        val name = account.username.trim()
        if (name.isEmpty() || name.length > MAX_USERNAME_CHARS) return null
        val games = account.games
            .filter { it.raGameId > 0 && it.title.isNotBlank() && it.total > 0 && it.earned in 0..it.total }
            .distinctBy { it.raGameId }
            .take(MAX_ACHIEVEMENT_GAMES)
            .map {
                it.copy(
                    title = it.title.take(MAX_TITLE_CHARS),
                    consoleName = it.consoleName.take(MAX_CONSOLE_CHARS),
                    iconPath = it.iconPath?.takeIf { path -> isRelativeRaImagePath(path) },
                    earnedHardcore = it.earnedHardcore.coerceIn(0, it.earned),
                    awardWord = it.awardWord?.trim()?.takeIf { word -> word.isNotEmpty() && word.length <= MAX_AWARD_WORD_CHARS },
                )
            }
        if (games.isEmpty()) return null
        return account.copy(
            username = name,
            points = account.points?.takeIf { it >= 0 },
            softcorePoints = account.softcorePoints?.takeIf { it >= 0 },
            games = games,
        )
    }

    private fun isRelativeRaImagePath(path: String): Boolean = RA_IMAGE_PATH.matches(path)

    private companion object {
        val RA_IMAGE_PATH = Regex("/Images/[0-9]{1,12}[.]png")
        const val MAX_ACHIEVEMENT_ACCOUNTS = 8
        const val MAX_ACHIEVEMENT_GAMES = 10_000

        /** RetroAchievements' longest award word is "beaten-softcore" (15 characters); a longer string is no award word. */
        const val MAX_AWARD_WORD_CHARS = 32
        const val MAX_USERNAME_CHARS = 64
        const val MAX_TITLE_CHARS = 256
        const val MAX_CONSOLE_CHARS = 64
    }
}
