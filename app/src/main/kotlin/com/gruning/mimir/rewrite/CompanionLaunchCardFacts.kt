package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.feature.companion.collectAsStateWhileLive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardFacts

/*
 * What the Bottom's launch card (slices L5 and L6) shows in its Journal look that the library row does not carry: the elapsed total the app measured and the
 * achievement counts it last checked. The card lives in the library module, which cannot reach either, so they cross as plain values.
 *
 * Nothing here starts a request or a measurement: the total is what [HomeExternalSessionElapsedStore] already holds, and the counts are the last snapshot the
 * achievement lane published ([CompanionActivityAchievements]); with nothing held the facts are null and the card says so in words.
 */

/**
 * The facts for [gameId], pure so the rules are tests:
 *  - the session total is the elapsed store's sum under the CANONICAL id the writer used (a game's variants are launch-card versions of one identity),
 *    and a missing key is no total at all (null), which the card words as "Not tracked", never as zero;
 *  - the achievements are the last snapshot published for this game (and only for the connected account: see [companionActivityAchievementSnapshotsFor]),
 *    else null for both counts.
 */
internal fun companionLaunchCardFacts(
    gameId: GameId,
    canonicalGameId: GameId,
    elapsedTotals: Map<HomeShelfItemId, Long>,
    achievementSnapshots: List<CompanionActivityAchievementSnapshot>,
): CompanionLaunchCardFacts {
    val snapshot = achievementSnapshots.firstOrNull { it.gameId == gameId.value }
    return CompanionLaunchCardFacts(
        sessionTotalMillis = elapsedTotals[HomeShelfItemId.Emulated(canonicalGameId)],
        achievementsDone = snapshot?.unlocked,
        achievementsTotal = snapshot?.total,
    )
}

/**
 * [companionLaunchCardFacts] for [game] from the live stores. Composed only while the Journal look is on screen, so the elapsed store is read (and the
 * canonical id resolved) only then. The achievement hub's own gate decides whether a snapshot counts: connected services, an accepted key and an account.
 */
@Composable
internal fun rememberCompanionLaunchCardFacts(game: GameRow): CompanionLaunchCardFacts {
    val context = LocalContext.current
    val totals by remember(context) { HomeExternalSessionElapsedStore.observe(context) }.collectAsStateWhileLive()
    // Seeded from the process's synchronous alias cache, so an aliased game's total is read under its canonical id on the very first frame: seeded with
    // the raw id it said "Not tracked" for a moment and then cut to the real figure. The Room read below stays the authority. Keyed on the game, so a card
    // for another game never wears the previous game's id while its own read runs.
    val canonicalGameId by key(game.gameId) {
        produceState(GameId(GameIdentityAliasCache.resolve(game.gameId.value))) {
            value = runCatching { GameIdentityAliasResolver.resolve(context, game.gameId) }.getOrDefault(value)
        }
    }
    // The checked games for the connected account, already gated and ordered by the lane's hub view (derived once per change, not per mount).
    val hub by companionActivityHubViewState.collectAsStateWhileLive()
    return remember(game.gameId, canonicalGameId, totals, hub) {
        companionLaunchCardFacts(
            gameId = game.gameId,
            canonicalGameId = canonicalGameId,
            elapsedTotals = totals,
            achievementSnapshots = hub.snapshots,
        )
    }
}

/** The platform's name for the card's meta line and eyebrow, from the library's platform rows (the same helper the takeover uses). */
internal fun companionLaunchCardPlatformLabel(game: GameRow, platforms: List<PlatformRow>): String? =
    platformLabelForCompanion(game.platformId, platforms)
