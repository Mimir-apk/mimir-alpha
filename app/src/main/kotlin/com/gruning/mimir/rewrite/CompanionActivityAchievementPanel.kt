package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsIcons
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.retroAchievementsAwardMillis
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirEtchedProgressBar
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirMemoryCacheWarm
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.rememberMimirColdArtReveal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val AchievementPanelProgressTag = "achievement-panel-progress"
internal const val AchievementPanelBadgeStripTag = "achievement-panel-badges"
internal const val AchievementPanelDetailTag = "achievement-panel-detail"
internal const val AchievementPanelHardcoreTag = "achievement-panel-hardcore"
internal const val AchievementPanelAwardTag = "achievement-panel-award"
internal fun achievementPanelBadgeTag(achievementId: Int) = "achievement-badge-$achievementId"

/** RA's badges are 64 x 64 pixel art. */
internal const val RA_BADGE_SOURCE_PX = 64

/** The whole-number scale that draws a 64 px badge at least [targetPx] across: pixel art stays crisp only at integer multiples. */
internal fun activityBadgeScale(targetPx: Float): Int = ceil(targetPx / RA_BADGE_SOURCE_PX).toInt().coerceAtLeast(1)

/** What a screen reader says for the card: the game, the count, once the detail is read the points, and the award when the game has one. */
internal fun activityPanelLabel(title: String, unlocked: Int, total: Int, points: Int?, award: String? = null): String =
    "$title, $unlocked of $total achievements" + (points?.let { ", $it points" } ?: "") + (award?.let { ", $it" } ?: "")

/** What a screen reader says for one badge. */
internal fun activityBadgeLabel(title: String, earned: Boolean): String = "$title, ${if (earned) "earned" else "locked"}"

/** The relative media path of a badge: its colour art when earned, its `_lock` art when not. Null when the badge name is not digits only. */
internal fun activityBadgePath(achievement: RetroAchievementsAchievement): String? =
    RetroAchievementsIcons.badgePath(achievement.badgeName, locked = !achievement.unlocked)

/** The date style the Bottom prints an unlock in (year-month-day, then hour:minute:second); the zone is the viewer's own and is applied per use. */
private val BadgeDateStyle: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** [millis] as a date in [zone] (the device's own by default), in the badge date style. */
internal fun activityLocalDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    BadgeDateStyle.withZone(zone).format(Instant.ofEpochMilli(millis))

/**
 * When [achievement] was earned as an instant (the newer of its softcore and hardcore dates), or null when it is locked or RA's date string cannot be read. RA gives
 * its dates as UTC strings, so they are only ever compared as instants, never as text.
 */
internal fun activityEarnedAtMillis(achievement: RetroAchievementsAchievement): Long? =
    listOf(achievement.earnedAt, achievement.earnedHardcoreAt).mapNotNull { retroAchievementsAwardMillis(it) }.maxOrNull()

/** Earned badges first, the newest unlock (by its instant) leading, then the locked ones in set order: the strip reads as progress made. An earned badge whose date is unreadable follows the dated ones. */
internal fun activityBadgeOrder(achievements: List<RetroAchievementsAchievement>): List<RetroAchievementsAchievement> {
    val earned = achievements.filter { it.unlocked }
        .map { it to activityEarnedAtMillis(it) }
        .sortedWith(compareByDescending<Pair<RetroAchievementsAchievement, Long?>> { it.second ?: Long.MIN_VALUE }.thenBy { it.first.id })
        .map { it.first }
    val locked = achievements.filter { !it.unlocked }.sortedBy { it.id }
    return earned + locked
}

/** One badge's detail line: its title, its description, and its points with when it was earned (in the viewer's time zone) or that it is locked. */
internal data class ActivityBadgeDetail(val title: String, val description: String, val meta: String)

/** " <date>" for RA's date string [raw] in [zone]; the string as given when it cannot be read (an odd date is shown rather than guessed at); nothing for none. */
private fun activityEarnedOn(raw: String?, zone: ZoneId): String =
    raw?.let { text -> " " + (retroAchievementsAwardMillis(text)?.let { activityLocalDate(it, zone) } ?: text) }.orEmpty()

internal fun activityBadgeDetail(achievement: RetroAchievementsAchievement, zone: ZoneId = ZoneId.systemDefault()): ActivityBadgeDetail {
    val state = when {
        achievement.unlocked && achievement.unlockedHardcore ->
            "Earned in hardcore" + activityEarnedOn(achievement.earnedHardcoreAt ?: achievement.earnedAt, zone)
        achievement.unlocked -> "Earned" + activityEarnedOn(achievement.earnedAt, zone)
        else -> "Locked"
    }
    return ActivityBadgeDetail(achievement.title, achievement.description, "${achievement.points} points · $state")
}

/** The numbers a card prints: the earned count and the set's size. */
internal data class ActivityPanelCounts(val unlocked: Int, val total: Int)

/**
 * Whether a count read LATER ([newerAt], [newerUnlocked] of [newerTotal]) should stand in for an earlier one. Only when it really is later; a set of another size is
 * the set as it is now; otherwise only when more is earned, because unlocks only grow: a later count that is LOWER is RetroAchievements' denormalised counts still
 * catching up after a play (they are rewritten by a queued job), not an unlock undone, and the earlier read stands.
 */
internal fun activityLaterCountWins(
    olderAt: Long?,
    olderUnlocked: Int,
    olderTotal: Int,
    newerAt: Long?,
    newerUnlocked: Int,
    newerTotal: Int,
): Boolean {
    if (newerAt == null || newerAt <= (olderAt ?: 0L)) return false
    if (newerTotal != olderTotal) return true
    return newerUnlocked > olderUnlocked
}

/**
 * The counts a hub card prints. The card's own per-game answer ([detail]) is the more exact one, but it can be up to five minutes old: when the bulk read ([snapshot])
 * has since counted more for the game, the bulk read's count is the newer and is the one shown (the badges below it catch up when the detail does). Without a loaded
 * detail the bulk count is all there is.
 */
internal fun activityPanelCounts(snapshot: CompanionActivityAchievementSnapshot, detail: ActivityCardDetail?): ActivityPanelCounts {
    val loaded = detail as? ActivityCardDetail.Loaded ?: return ActivityPanelCounts(snapshot.unlocked, snapshot.total)
    val progress = loaded.progress
    val bulkWins = activityLaterCountWins(
        olderAt = loaded.atMillis,
        olderUnlocked = progress.unlockedCount,
        olderTotal = progress.totalCount,
        newerAt = snapshot.checkedAtEpochMillis,
        newerUnlocked = snapshot.unlocked,
        newerTotal = snapshot.total,
    )
    return if (bulkWins) ActivityPanelCounts(snapshot.unlocked, snapshot.total) else ActivityPanelCounts(progress.unlockedCount, progress.totalCount)
}

/** The panel's secondary line: the count, and the points once they are known. */
internal fun activityPanelPointsText(progress: RetroAchievementsProgress?): String? =
    progress?.let { "${it.earnedPoints} of ${it.totalPoints} points" }

/** Where a badge's pixel art comes from: a lease on a staged file, or null. Released when the badge leaves composition. */
internal fun interface ActivityBadgeSource {
    suspend fun acquire(path: String): ActivityIconLease?

    /** The badge's file when it is already staged on disk (no fetch, no lease), so a badge can start painting on the frame it is composed rather than after the lease lands. */
    fun staged(path: String): java.io.File? = null
}

/** The real source: the process's bounded icon store (the same eviction rules as the game icons). */
internal class StoreActivityBadgeSource(private val store: ActivityIconStore) : ActivityBadgeSource {
    override suspend fun acquire(path: String): ActivityIconLease? = store.acquire(path)

    override fun staged(path: String): java.io.File? = store.staged(path)
}

/** No badge art at all (previews and tests). */
internal val NoActivityBadgeArt = ActivityBadgeSource { null }

/**
 * One Activity card, every card the SAME size (it fills the page the hub gives it): the game's hero art full-bleed under the palette scrim, the title and
 * count, a metal-etched progress bar, a strip of the game's real RetroAchievements badges (64 px pixel art at a whole-number scale, colour when earned and the
 * `_lock` art when not, each a 48 dp-or-larger target), and a detail line that shows the tapped badge's title, description, points and earned date.
 *
 * [detail] is the game's per-game answer (badges, dates, title art), asked for ONLY because this card is composed (the hub does the asking). The card always shows the
 * bulk count, and the strip under it always says where the badges are: loading while the read is due or under way, the badges themselves, or (a read that failed) a plain
 * sentence that a tap on it ([onRetry]) tries again. A key RetroAchievements refuses says that instead, in the key's own words. There is no state with nothing to say.
 *
 * Accessibility: the header announces "<game>, X of Y achievements, P points" and each badge "<title>, earned" or "<title>, locked". The art is decorative.
 */
@Composable
internal fun ActivityAchievementPanel(
    snapshot: CompanionActivityAchievementSnapshot,
    detail: ActivityCardDetail?,
    artResolver: ActivityAchievementArtResolver,
    badgeSource: ActivityBadgeSource,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    artContext: CoroutineContext = Dispatchers.IO,
    /** A badge the hub asked this card to open (a recent-unlocks tap, or a controller A on a badge); null leaves the card as the player left it. */
    badgeSelection: ActivityBadgeSelection? = null,
    /** The player's tap on a failed strip: one more read of this game, outside the window. */
    onRetry: () -> Unit = {},
) {
    val palette = LocalMimirPalette.current
    val progress = (detail as? ActivityCardDetail.Loaded)?.progress
    val artPath = progress?.imageTitlePath ?: progress?.imageIngamePath
    // RA's own title or in-game image joins the art chain once the detail is read; a library hero still comes first in the resolver.
    val artSnapshot = remember(snapshot, artPath) { if (artPath == null || artPath == snapshot.artPath) snapshot else snapshot.copy(artPath = artPath) }
    val counts = activityPanelCounts(snapshot, detail)
    val unlocked = counts.unlocked
    val total = counts.total
    val title = snapshot.gameTitle?.takeIf { it.isNotBlank() } ?: progress?.title ?: snapshot.gameId
    // The hardcore count is the card's rows when they are read, else RA's own figure from the bulk read; unlocks only grow, so the larger is the newer.
    val hardcore = maxOf(progress?.achievements?.count { it.unlockedHardcore } ?: 0, snapshot.earnedHardcore)
    val award = activityAwardLabel(unlocked, total, hardcore, snapshot.awardKind)
    val density = LocalDensity.current
    val badgeSize = with(density) { (RA_BADGE_SOURCE_PX * activityBadgeScale(MimirSpacing.minTarget.toPx())).toDp() }
    // Born with the badge the hub asked this card to open (only when the request is for this card's game), so a card opened from a badge tap shows its
    // detail on the first frame instead of one frame later.
    var selectedId by remember(snapshot.gameId) {
        mutableStateOf<Int?>(badgeSelection?.takeIf { it.raGameId == snapshot.raGameId }?.achievementId)
    }
    // The hub's request wins over what the player last tapped: the same object again changes nothing, a new one (a different tap) selects its badge.
    LaunchedEffect(badgeSelection) { badgeSelection?.let { selectedId = it.achievementId } }

    Box(
        modifier
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(palette.surfaceBase.copy(alpha = 0.94f))
            .testTag(AchievementHubCardTag),
    ) {
        ActivityAchievementArt(artSnapshot, artResolver, Modifier.fillMaxSize(), workContext = artContext)
        Column(
            Modifier.fillMaxSize().padding(MimirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            // The header: one node for a screen reader, with the numbers it stands for (shared with Main's page).
            ActivityAchievementHeader(title, unlocked, total, progress, hardcore, titleMinLines = 2, titleMaxLines = 2, award = award)
            MimirEtchedProgressBar(
                fraction = if (total > 0) unlocked.toFloat() / total else 0f,
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxWidth().height(MimirSpacing.lg).testTag(AchievementPanelProgressTag),
            )
            Box(Modifier.fillMaxWidth().padding(vertical = ActivityBadgeFlareRoom).height(badgeSize), contentAlignment = Alignment.CenterStart) {
                val badges = progress?.achievements?.let(::activityBadgeOrder)
                when {
                    badges != null -> BadgeStrip(badges, badgeSize, selectedId, badgeSource) { selectedId = it }
                    detail == ActivityCardDetail.Unavailable -> ActivityBadgeRetry(MimirAchievementCopy.BadgesFailed, onRetry)
                    detail == ActivityCardDetail.Stalled -> ActivityBadgeRetry(MimirAchievementCopy.BadgesStalled, onRetry)
                    detail == ActivityCardDetail.Throttled -> ActivityBadgeRetry(MimirAchievementCopy.BadgesHeld, onRetry)
                    detail == ActivityCardDetail.Rejected ->
                        Text(MimirAchievementCopy.CredentialsRejected, style = MimirTypeScale.Support, color = palette.textMuted, maxLines = 2, modifier = Modifier.testTag(AchievementCardRejectedTag))
                    // A game with no RetroAchievements id has no read coming, so it never says it is loading.
                    snapshot.raGameId == null ->
                        Text(MimirAchievementCopy.BadgesNotLoaded, style = MimirTypeScale.Support, color = palette.textMuted, modifier = Modifier.testTag(AchievementCardLoadingTag))
                    else -> Text(MimirAchievementCopy.BadgesLoading, style = MimirTypeScale.Support, color = palette.textMuted, modifier = Modifier.testTag(AchievementCardLoadingTag))
                }
            }
            // The card's own badge when its detail has landed; until then (the card was only just composed) the unlock the hub opened it from.
            val selected = progress?.achievements?.firstOrNull { it.id == selectedId }?.let { activityBadgeDetail(it) }
                ?: badgeSelection?.takeIf { it.achievementId == selectedId }?.detail
            ActivityBadgeDetailBlock(
                selected = selected,
                hint = if (progress != null) "Tap a badge for its details." else "",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/** The strip's words after a read that failed, ran long or was told to wait, as one 48 dp-or-taller target: a tap asks again. */
internal const val AchievementCardRetryTag = "achievement-card-retry"

/** The strip's words while the read is due or under way. */
internal const val AchievementCardLoadingTag = "achievement-card-loading"

/** The strip's words when RetroAchievements is not accepting the saved key. */
internal const val AchievementCardRejectedTag = "achievement-card-rejected"

/**
 * What the strip says after a read that got no answer, and the way out: the whole strip is the target (it is at least one badge tall, 48 dp), it reads as one button to a
 * screen reader, and a tap runs [onRetry] once. The press shows through the Bottom's one press response; nothing here takes focus.
 */
@Composable
private fun ActivityBadgeRetry(text: String, onRetry: () -> Unit) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .mimirActivatable(
                label = "try loading the badges again",
                contentDescription = text,
                interactionSource = source,
                onActivate = onRetry,
            )
            .mimirPressResponse(source)
            .testTag(AchievementCardRetryTag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MimirTypeScale.Support, color = palette.accentBright, maxLines = 2)
    }
}

/**
 * The card's header, one node for a screen reader ("<game>, X of Y achievements, P points"): the game's title, its count with the points once they are known, and
 * the hardcore mark. Shared by the Bottom's card (a two-line title, so every card is the same height) and Main's page (which sizes its own lines).
 */
@Composable
internal fun ActivityAchievementHeader(
    title: String,
    unlocked: Int,
    total: Int,
    progress: RetroAchievementsProgress?,
    hardcore: Int,
    titleMinLines: Int,
    titleMaxLines: Int,
    /** Lines the count and points may take: the Bottom's card is one (fixed height); Main's page lets them wrap, so the numbers are never cut. */
    statsMaxLines: Int = 1,
    /** Whether the stats line takes the row's whole remaining width (Main: the hardcore mark sits at the row's end) instead of just its text's; the Bottom's one-line card does not. */
    statsFillWidth: Boolean = false,
    /** RetroAchievements' word for how far the game has been taken ("Mastered", "Completed", "Beaten"), or null for none: a small mark beside the count. */
    award: String? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val points = progress?.earnedPoints
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = activityPanelLabel(title, unlocked, total, points, award) },
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        Text(
            text = title,
            style = MimirTypeScale.Title,
            color = palette.textPrimary,
            maxLines = titleMaxLines,
            minLines = titleMinLines,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$unlocked of $total achievements" + (activityPanelPointsText(progress)?.let { " · $it" } ?: ""),
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
                maxLines = statsMaxLines,
                overflow = TextOverflow.Ellipsis,
                // The count and the marks beside it sit on ONE baseline (the marks are smaller type on a padded plate: centred, they rode 2 dp above the count's line).
                modifier = Modifier.weight(1f, fill = statsFillWidth).alignByBaseline(),
            )
            HardcoreMark(hardcore)
            AwardMark(award)
        }
    }
}

/**
 * The selected badge's detail (its title, description, and its points with when it was earned or that it is locked), or [hint] when none is selected: scrolls when it
 * does not fit and announces a change politely. Shared by the Bottom's card and Main's page; [modifier] carries the caller's size (a weight, a width).
 */
@Composable
internal fun ActivityBadgeDetailBlock(
    selected: ActivityBadgeDetail?,
    hint: String,
    modifier: Modifier = Modifier,
    /** A last, quiet line inside the scroll (Main's honesty line); null for none, which is the Bottom's. */
    footer: String? = null,
    /** The pane's scroll, held by a caller that scrolls it from elsewhere (Main's page does, from the pad's shoulders). */
    scrollState: ScrollState = rememberScrollState(),
) {
    val palette = LocalMimirPalette.current
    Column(
        modifier
            .verticalScroll(scrollState)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(AchievementPanelDetailTag),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        if (selected != null) {
            Text(selected.title, style = MimirTypeScale.Support, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            selected.description.takeIf { it.isNotBlank() }?.let { Text(it, style = MimirTypeScale.Support, color = palette.textSecondary) }
            Text(selected.meta, style = MimirTypeScale.MicroStatus, color = palette.accentBright)
        } else {
            Text(hint, style = MimirTypeScale.Support, color = palette.textMuted)
        }
        footer?.let { Text(it, style = MimirTypeScale.MicroStatus, color = palette.textMuted) }
    }
}

@Composable
private fun BadgeStrip(
    badges: List<RetroAchievementsAchievement>,
    badgeSize: androidx.compose.ui.unit.Dp,
    selectedId: Int?,
    source: ActivityBadgeSource,
    onSelect: (Int) -> Unit,
) {
    // The pad walks the whole row, composed or not: a lit badge that the lazy row has not composed is scrolled to (one that is already shown is left to its own reveal).
    val listState = rememberLazyListState()
    val lit = rememberActivityPadLit()
    val litIndex = remember(badges, lit) { if (lit == null) -1 else badges.indexOfFirst { ActivityPadKeys.badge(it.id) == lit } }
    LaunchedEffect(litIndex) {
        if (litIndex >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == litIndex }) listState.scrollToItem(litIndex)
    }
    // A horizontal drag on the strip is the strip's own: the workspace behind it never pans under a finger scrolling badges. The row reaches the ring's room past the
    // column's own padding on both sides and pads its content by the same, so the badges keep their place while the ring on the first or the last one is not cut by the
    // row's viewport (a row clips on its scroll axis; its cross axis has room to spare).
    LazyRow(
        state = listState,
        modifier = Modifier.reachingBeyondItsParentBy(ActivityBadgeRingRoom).fillMaxSize().companionOwnsHorizontalDrag().testTag(AchievementPanelBadgeStripTag),
        contentPadding = PaddingValues(horizontal = ActivityBadgeRingRoom),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        items(badges, key = { it.id }) { achievement ->
            ActivityBadge(achievement, badgeSize, selected = achievement.id == selectedId, source = source, onSelect = { onSelect(achievement.id) })
        }
    }
}

/** One of the game's own badges in the card's strip or Main's grid: the shared tile, with this achievement's art, label and tag. */
@Composable
internal fun ActivityBadge(
    achievement: RetroAchievementsAchievement,
    size: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    source: ActivityBadgeSource,
    onSelect: () -> Unit,
    /** False when the host scrolls to the lit badge itself (Main's grid: one scroller, [FollowLitBadge]); true asks the badge's scroll parent to reveal it on focus. */
    revealOnFocus: Boolean = true,
) {
    val path = remember(achievement.id, achievement.badgeName, achievement.unlocked) { activityBadgePath(achievement) }
    ActivityBadgeTile(
        path = path,
        size = size,
        selected = selected,
        source = source,
        label = achievement.title,
        contentDescription = activityBadgeLabel(achievement.title, achievement.unlocked),
        tag = achievementPanelBadgeTag(achievement.id),
        onActivate = onSelect,
        padKey = ActivityPadKeys.badge(achievement.id),
        revealOnFocus = revealOnFocus,
    )
}

/** The badge tile's corner: the clip of its art and the ring around it take this one symbol (the box-art ruling R-1: a ring and the art inside it are one object). */
internal val ActivityBadgeCorner: Dp = MimirSpacing.xs

/**
 * The clear air a scrolling row or grid leaves around a badge so its ring is not cut by the viewport: the inner and middle strokes (8.5 dp) and most of the faint outer
 * one. It is the card's own padding, so a row that reaches this far past its parent stays inside the card.
 */
internal val ActivityBadgeRingRoom: Dp = MimirSpacing.lg

/**
 * How far the badge ring paints past its tile at the PEAK of its arrival flare: the widest stroke stands 10 dp out and is 7 dp wide (3.5 dp more), and the flare pushes every stroke
 * [MimirFocusFlareReach] further, 19.5 dp in all. [MimirFocusTileRingReach] (14 dp) plus the flare is the 20 dp this rounds it up to.
 */
internal val ActivityBadgePaintReach: Dp = MimirFocusTileRingReach + MimirFocusFlareReach

/**
 * The air the card keeps above and below its badge strip, beyond the column's own [MimirSpacing.md] gap, so the flare at its peak paints on air and never on the progress bar above
 * or the detail below (a row allows its cross axis to overflow, so nothing else would stop it).
 */
internal val ActivityBadgeFlareRoom: Dp = ActivityBadgePaintReach - MimirSpacing.md

/**
 * What the ring on one badge does: [lit] draws the product's focus ring (the pad's focus, or - with no pad ring showing - the badge a touch selected), [plate] is
 * the quiet "open but not focused" plate for a selected badge while the pad's ring is on another one (the Settings rail's rule: a plate, never a second ring).
 */
internal data class ActivityBadgeRing(val lit: Boolean, val plate: Boolean)

/**
 * The one rule for both surfaces and every badge alike (earned, locked or still loading): the ring follows the pad while the pad's ring is showing, and rests on the
 * selected badge when it is not (after a touch, or on a page with no pad), so a selected badge always shows the ring and a tap never leaves a stale box.
 */
internal fun activityBadgeRing(selected: Boolean, padFocused: Boolean, padEngaged: Boolean): ActivityBadgeRing {
    val lit = padFocused || (selected && !padEngaged)
    return ActivityBadgeRing(lit = lit, plate = selected && !lit)
}

/** Test seams, read by nothing in the product: whether the ring is lit on a badge, whether its plate is, and the colour the shared ring draws in (the palette's `tileFocusRing`). */
internal val ActivityBadgeRingLit = SemanticsPropertyKey<Boolean>("ActivityBadgeRingLit")
internal val ActivityBadgeRingPlate = SemanticsPropertyKey<Boolean>("ActivityBadgeRingPlate")
internal val ActivityBadgeRingColor = SemanticsPropertyKey<Int>("ActivityBadgeRingColor")

/** The test tag of a badge tile's art layer and of its ring layer: siblings inside the tile, the ring drawn after (above) the art. */
internal fun activityBadgeArtTag(tag: String) = "art-of-$tag"

internal fun activityBadgeRingTag(tag: String) = "ring-of-$tag"

/**
 * Lays this element out as wide as its parent plus [room] on each side, placed so its content keeps the parent's own edges; the part past the parent only gives
 * painted overflow (a ring) somewhere to be, and takes no touch.
 */
private fun Modifier.reachingBeyondItsParentBy(room: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedWidth) room.roundToPx() else 0
    val wider = if (extra > 0) constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + 2 * extra) else constraints
    val placeable = measurable.measure(wider)
    layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
}

/**
 * The same, up and down: this element is laid out as tall as its parent plus [room] above and below, placed so its content keeps the parent's own top and bottom (the host pads
 * its content by [room] to match). For a vertical lazy list or grid, which clips on its scroll axis: the ring on a row at either end of the content is whole, while the parent
 * (the bounds a layout test and the neighbours see) is the size it always was.
 */
internal fun Modifier.reachingBeyondItsParentVerticallyBy(room: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedHeight) room.roundToPx() else 0
    val taller = if (extra > 0) constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight + 2 * extra) else constraints
    val placeable = measurable.measure(taller)
    layout(placeable.width, (placeable.height - 2 * extra).coerceAtLeast(0)) { placeable.place(0, -extra) }
}

/**
 * One badge tile, shared by the card's strip, Main's grid and the page's recent-unlocks strip: its pixel art at a whole-number scale with no filtering, the product's
 * focus ring, and a tap that activates it. Its art is fetched only when it is composed.
 *
 * **The ring is the shelf's own** ([mimirSelectionRing]: the three strokes, the settle gleam and the flare on the ring clock), keyed by the badge's identity (its [padKey],
 * which names the achievement, else its tag - never an index). It is a SIBLING of the art, drawn after it: above the art, outside the art's clip, and in none of the art's
 * layers, so a badge that is earned, locked (its grey `_lock` art) or still loading shows the same full-colour ring. (It used to be painted behind the tile's content by the
 * pad's target modifier, so any opaque art hid it: the selection vanished the moment the art loaded.) The ring reaches past the tile, so a host leaves it room
 * ([ActivityBadgeRingRoom]); a dense tile ([ringShape] a key, as the recent strip's) keeps its ring inside its bounds at [ringInset].
 *
 * [padKey] makes the tile a stop on the page's walk (see [activityPadStop]); the pad's focus and a touch's selection share this one ring ([activityBadgeRing]).
 */
@Composable
internal fun ActivityBadgeTile(
    path: String?,
    size: Dp,
    selected: Boolean,
    source: ActivityBadgeSource,
    label: String,
    contentDescription: String,
    tag: String,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    padKey: FocusKey? = null,
    ringShape: MimirRingShape = MimirRingShape.Card(ActivityBadgeCorner),
    ringInset: Dp = 0.dp,
    revealOnFocus: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    // The hold is tied to THIS producer's life: the producer acquires it, publishes it and then waits to be cancelled (the card left composition, or its
    // badge changed), and its finally gives it back exactly once, whatever the timing. A hold taken in the last instant before a cancellation is released
    // too, because it is recorded the moment the acquire returns, inside the same call.
    // Runtime 1.7.3 lint misses this inherited setter; badge lease render tests cover publication and release.
    @SuppressLint("ProduceStateDoesNotAssignValue")
    val lease by produceState<ActivityIconLease?>(null, path, source) {
        var made: ActivityIconLease? = null
        try {
            if (path != null) {
                withContext(Dispatchers.IO) {
                    try {
                        made = source.acquire(path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                        made = null
                    }
                }
            }
            value = made
            awaitDispose { }
        } finally {
            made?.release()
        }
    }
    val held = lease
    val shape = RoundedCornerShape(ActivityBadgeCorner)
    val dense = ringShape is MimirRingShape.Key
    val ring = activityBadgeRing(
        selected = selected,
        padFocused = rememberActivityPadFocused(padKey),
        padEngaged = rememberActivityPadEngaged(),
    )
    val isSelected = selected
    val ringColor = palette.tileFocusRing.toArgb()
    Box(
        modifier
            .activityPadStop(padKey, bringIntoView = revealOnFocus, revealOutset = if (dense) 0.dp else MimirFocusTileRingReach)
            .size(size)
            .semantics { this.selected = isSelected }
            .mimirActivatable(
                label = label,
                contentDescription = contentDescription,
                onActivate = onActivate,
            )
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        // The art, clipped to the tile. Whatever the art is (colour, the grey lock art, nothing yet), it is a layer of its own beneath the ring.
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(palette.surfaceBase.copy(alpha = 0.55f))
                .testTag(activityBadgeArtTag(tag)),
            contentAlignment = Alignment.Center,
        ) {
            held?.file?.let { BadgeArt(it) }
        }
        // The ring: after the art, so above it; not clipped; in no alpha or colour layer. Paint only - the tap, the focus and the label are the tile's.
        Box(
            Modifier
                .fillMaxSize()
                .mimirSelectionRing(
                    focused = ring.lit,
                    identity = padKey ?: tag,
                    shape = ringShape,
                    flare = !dense,
                    active = ring.plate,
                    inset = ringInset,
                )
                .semantics {
                    this[ActivityBadgeRingLit] = ring.lit
                    this[ActivityBadgeRingPlate] = ring.plate
                    this[ActivityBadgeRingColor] = ringColor
                }
                .testTag(activityBadgeRingTag(tag)),
        )
    }
}

/** The hardcore mark: shown only when the player earned something in hardcore. Its own function so the condition sits in a full composable body. */
@Composable
private fun RowScope.HardcoreMark(count: Int) {
    if (count > 0) {
        val palette = LocalMimirPalette.current
        Text(
            text = "HC $count",
            style = MimirTypeScale.MicroStatus,
            color = palette.accentBright,
            maxLines = 1,
            modifier = Modifier
                .mimirEtchedChipSurface(palette)
                .padding(horizontal = MimirSpacing.sm, vertical = MimirSpacing.xs)
                .alignByBaseline()
                .testTag(AchievementPanelHardcoreTag),
        )
    }
}

/**
 * The award mark: RetroAchievements' word for the game ("Mastered", "Completed", "Beaten"), shown only when the game has one. An etched chip like the hardcore mark beside it
 * and the filter chips (never a filled pill); the word keeps the accent so it still reads as the card's mark.
 */
@Composable
private fun RowScope.AwardMark(label: String?) {
    if (label != null) {
        val palette = LocalMimirPalette.current
        Text(
            text = label,
            style = MimirTypeScale.MicroStatus,
            color = palette.accentBright,
            maxLines = 1,
            modifier = Modifier
                .mimirEtchedChipSurface(palette)
                .padding(horizontal = MimirSpacing.sm, vertical = MimirSpacing.xs)
                .alignByBaseline()
                .testTag(AchievementPanelAwardTag),
        )
    }
}

/**
 * A badge's pixel art, unfiltered: drawn at a whole multiple of its 64 px source it stays crisp. It asks for its bitmap under an explicit cache key (the same for the
 * request and its placeholder), so a badge the grid or an earlier toast already decoded paints whole on the first frame, and the key is also how this site knows it is
 * warm. Policy Q13 (2026-10-14): a badge that arrives COLD draws nothing while it loads and then fades in from nothing on FocusMs; a warm one never fades.
 */
@Composable
internal fun BadgeArt(file: java.io.File) {
    val context = LocalContext.current
    val key = remember(file) { "activity-badge:${file.path}" }
    val request = remember(file, context) {
        ImageRequest.Builder(context).data(file).size(Size.ORIGINAL).memoryCacheKey(key).placeholderMemoryCacheKey(key).crossfade(false).build()
    }
    val warm = remember(file, context) { mimirMemoryCacheWarm(context, key) }
    val painter = rememberAsyncImagePainter(model = request, filterQuality = FilterQuality.None)
    val reveal = rememberMimirColdArtReveal(identity = file, warm = warm, state = painter.state, request = request)
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal() },
    )
}
