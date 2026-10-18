package com.gruning.mimir.rewrite

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import java.time.ZoneId

/** The most recent unlocks the strip shows. The lane holds more ([ACTIVITY_RECENT_KEEP]); a row of twelve scrolls sideways and never crowds the page. */
internal const val ACTIVITY_RECENT_STRIP_MAX: Int = 12

internal const val AchievementRecentStripTag = "achievement-recent-strip"

internal fun achievementRecentTag(achievementId: Int) = "achievement-recent-$achievementId"

/** What a screen reader says for one tile of the strip. */
internal fun activityRecentLabel(unlock: ActivityRecentUnlock): String = "Recently unlocked: ${unlock.title}, ${unlock.gameTitle}"

/**
 * A badge the hub asks a card to open: the unlock's own detail travels with it, so the card shows it on the frame it lands, before its per-game answer
 * (which is asked for only once the card is composed) has come back. [serial] makes two taps on the same badge two requests.
 */
internal data class ActivityBadgeSelection(
    val raGameId: Int,
    val achievementId: Int,
    val detail: ActivityBadgeDetail,
    val serial: Int,
)

/** One unlock's detail line: its title, its description, and its points with when it was earned (in the viewer's time zone, like the card's own badge line) and whether in hardcore. */
internal fun activityRecentDetail(unlock: ActivityRecentUnlock, zone: ZoneId = ZoneId.systemDefault()): ActivityBadgeDetail {
    val date = unlock.awardedAtMillis?.let { " " + activityLocalDate(it, zone) }.orEmpty()
    val state = (if (unlock.hardcore) "Earned in hardcore" else "Earned") + date
    return ActivityBadgeDetail(unlock.title, unlock.description, "${unlock.points} points · $state")
}

/**
 * The unlocks the strip shows: newest first (the lane holds them so), only those whose game has a card on the page (a tile that opens nothing is not shown),
 * at most [ACTIVITY_RECENT_STRIP_MAX]. Empty hides the strip altogether.
 */
internal fun activityRecentShown(recent: List<ActivityRecentUnlock>, cardGameIds: Set<Int>): List<ActivityRecentUnlock> =
    recent.filter { it.raGameId in cardGameIds }.take(ACTIVITY_RECENT_STRIP_MAX)

/**
 * The page's row of the user's latest unlocked badges across every game: each tile the shared pixel-art badge at a whole-number scale (48 dp or larger), a
 * sideways scroll when they do not all fit. A tap on a tile is [onOpen]; the hub turns that into a jump to the game's card with the badge's detail open.
 * A horizontal drag on the strip is its own: the workspace behind it never pans under a finger scrolling badges.
 */
@Composable
internal fun ActivityRecentStrip(
    unlocks: List<ActivityRecentUnlock>,
    badgeSource: ActivityBadgeSource,
    onOpen: (ActivityRecentUnlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tileSize = with(density) { (RA_BADGE_SOURCE_PX * activityBadgeScale(MimirSpacing.minTarget.toPx())).toDp() }
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .companionOwnsHorizontalDrag()
            .testTag(AchievementRecentStripTag),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        unlocks.forEach { unlock ->
            key(unlock.achievementId) {
                ActivityBadgeTile(
                    path = unlock.badgePath,
                    size = tileSize,
                    selected = false,
                    source = badgeSource,
                    label = unlock.title,
                    contentDescription = activityRecentLabel(unlock),
                    tag = achievementRecentTag(unlock.achievementId),
                    onActivate = { onOpen(unlock) },
                    // The strip is dense (tiles four dp apart): the key ring, the bright stroke alone, drawn inside the tile and above its art.
                    padKey = ActivityPadKeys.recent(unlock.achievementId),
                    ringShape = MimirRingShape.Key(ActivityBadgeCorner),
                    ringInset = MimirSpacing.xs,
                )
            }
        }
    }
}
