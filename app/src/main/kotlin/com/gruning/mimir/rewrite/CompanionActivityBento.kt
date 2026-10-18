package com.gruning.mimir.rewrite

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.emberPlateStrength
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.rememberEmberLife
import com.gruning.mimir.rewrite.core.ui.GlassMenuCapsule
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlate
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirMetalRestAlpha
import com.gruning.mimir.rewrite.core.ui.mimirMetalPlate
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirRaisedPressNudge
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeader
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderClearanceDp
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirProgressRing
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTimeCopy
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.PlainMenuSeparator
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirFadeMount
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl
import com.gruning.mimir.rewrite.feature.library.LibraryGameFace
import com.gruning.mimir.rewrite.feature.library.LibraryGameHeroZone
import java.time.ZoneId
import kotlin.math.min

/*
 * The Activity page's resting layout (Thor Bottom redesign, slice L2): a header, then four tiles on a bento, then (below the fold) the
 * achievements strip.
 *
 *     header   [ᛗ] Activity ........................ N games  [Trackpad]
 *     ┌ Last played / Now playing ┬ This week (bars) ┐
 *     ├ Media / Been a while      ┼ Most launched    ┤
 *     └───────────────────────────┴──────────────────┘
 *
 * Everything is a LAUNCH, never playtime. Sizes come from [solveActivityBento]; text is sentence case through `Text` (chrome caps only on the
 * one accent eyebrow per tile); nothing here is smaller than the 11 sp label step; every control is a `mimirActivatable` touch target of at
 * least 48 dp. The page takes no focus and never holds the process-wide pad gate: the Bottom is touch-first.
 *
 * Art is the library's own ladder ([libraryListPreviewMedia]) so a game wears the face it wears on the shelf. Wired art is drawn bare and
 * nothing is drawn while it loads or after it fails; a deleted file is no art, so the book; art that is still being looked up draws nothing at
 * all (never the book); and no tile borrows another game's art.
 */

const val ActivityPageTag = "activity-page"
const val ActivityTileLastPlayedTag = "activity-tile-last-played"
const val ActivityTileNowPlayingTag = "activity-tile-now-playing"
const val ActivityTileWeekTag = "activity-tile-week"
const val ActivityTileRankedTag = "activity-tile-ranked"
const val ActivityTileLowerLeftTag = "activity-tile-lower-left"
const val ActivityTileAchievementsTag = "activity-tile-achievements"
const val ActivitySortChipTag = "activity-sort-chip"
const val ActivityWeekBarsTag = "activity-week-bars"

/** A game's face seat (the art, the book, or nothing) and the media tile's art seat: the book draws its own title text inside, which is art, not copy. */
const val ActivityGameFaceTag = "activity-game-face"
const val ActivityMediaStateTag = "activity-media-state"
const val ActivityMediaArtTag = "activity-media-art"

/** Everything the page reads, as plain values: no repository, no flow. The Presentation gathers it. */
internal data class ActivityBentoData(
    /** The library's game count, or null until it has been read (the header then says no count). */
    val gameCount: Int?,
    /**
     * Every game that has been launched, most recently played first; null until the library has first answered. The page is mounted afresh on every entry and its
     * data restarts at null, so null is "not read yet" and draws nothing, never the empty copy (an empty list is the library's answer and does).
     */
    val launched: List<GameRow>?,
    /**
     * Games to draw from when nothing has ever been launched ("From your shelf"); null while that read is pending, which is only ever asked when the launched
     * list left nothing for Been a while (a pending pool draws nothing, like a pending [launched], rather than the empty copy that the pool would then replace).
     */
    val shelfPool: List<GameRow>?,
    /** Launches per day, oldest first, today last; null until read. */
    val week: List<DailyLaunchCount>?,
    /** The game that is running now, if any: its id and title (from the session), and its library row for the art ladder (null while that read is pending). */
    val liveGameId: GameId?,
    val liveTitle: String?,
    val liveRow: GameRow?,
    val nowMillis: Long,
    val zone: ZoneId,
)

/** The page: header, then the scrolling body. [mediaTile] is the media tile when something plays (null: Been a while stands there). */
@Composable
internal fun ActivityPage(
    data: ActivityBentoData,
    navigationEnabled: Boolean,
    mediaTile: (@Composable (Modifier) -> Unit)?,
    achievementSnapshots: List<CompanionActivityAchievementSnapshot>,
    achievementsAccessEnabled: Boolean,
    achievementTotalPoints: Int? = null,
    achievementSoftcorePoints: Int = 0,
    achievementsChecking: Boolean = false,
    onOpenAchievements: () -> Unit,
    onOpenGame: (GameRow) -> Unit,
    onOpenLiveGame: (() -> Unit)?,
    trailingReserve: Dp,
    modifier: Modifier = Modifier,
    onReturnToShelf: (() -> Boolean)? = null,
) {
    val palette = LocalMimirPalette.current
    val latestEnabled by rememberUpdatedState(navigationEnabled)
    // The Trackpad plate (policy pad4: on every Bottom header) where this device can host one, then the recent-app icon's
    // reserve, so the plate never sits under the icon.
    val openTrackpad = LocalBottomTrackpadOpen.current
    val headerTrailing: (@Composable () -> Unit)? = if (openTrackpad != null) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BottomTrackpadPlate(onOpen = { if (latestEnabled) openTrackpad() })
                if (trailingReserve > 0.dp) Spacer(Modifier.width(trailingReserve))
            }
        }
    } else if (trailingReserve > 0.dp) {
        { Spacer(Modifier.width(trailingReserve)) }
    } else {
        null
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = ActivityPageInsetDp.dp, top = ActivityPageTopDp.dp, end = ActivityPageInsetDp.dp)
            .testTag(ActivityPageTag)
            .activityReturnAction(onReturnToShelf),
    ) {
        MimirCompactHeader(
            title = "Activity",
            palette = palette,
            stat = data.gameCount?.let { if (it == 1) "1 game" else "$it games" },
            // pad8 : no Shelf button. The page's swipe right is the way back to the Shelf (the workspace strip), and the
            // room the button had goes to the title and the Trackpad plate.
            trailing = headerTrailing,
            ruleBleed = ActivityPageInsetDp.dp,
            // The title and the stat are text only: the band an "Achievement earned" toast stands in on this page, as tall as the header's row (never over the plate).
            titleGroupModifier = Modifier
                .heightIn(min = com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowDp.dp)
                .achievementToastBand(),
        )
        Spacer(Modifier.height(MimirCompactHeaderClearanceDp.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val zones = solveActivityBento(
                bodyWidthDp = maxWidth.value,
                bodyHeightDp = (maxHeight - ActivityDockReserveDp.dp).value.coerceAtLeast(0f),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ActivityDockReserveDp.dp),
                verticalArrangement = Arrangement.spacedBy(ActivityBentoGapDp.dp),
            ) {
                ActivityBento(
                    data = data,
                    zones = zones,
                    navigationEnabled = navigationEnabled,
                    mediaTile = mediaTile,
                    onOpenGame = onOpenGame,
                    onOpenLiveGame = onOpenLiveGame,
                )
                // Below the fold, and always there: with data it is the strip, without it is the compact entry carrying the honest setup state
                // the old entry showed, so the hub (and its `activity.open_achievements` action) stays reachable.
                ActivityAchievementsStrip(
                    snapshots = achievementSnapshots,
                    accessEnabled = achievementsAccessEnabled,
                    totalPoints = achievementTotalPoints,
                    softcorePoints = achievementSoftcorePoints,
                    checking = achievementsChecking,
                    enabled = navigationEnabled,
                    onOpen = { if (latestEnabled) onOpenAchievements() },
                )
            }
        }
    }
}

@Composable
internal fun ActivityBento(
    data: ActivityBentoData,
    zones: ActivityBentoZones,
    navigationEnabled: Boolean,
    mediaTile: (@Composable (Modifier) -> Unit)?,
    onOpenGame: (GameRow) -> Unit,
    onOpenLiveGame: (() -> Unit)?,
) {
    val live = data.liveGameId != null
    // A row for the game that is running opens that game's session details (its launch card would offer to launch it again); with no
    // details to open it does nothing rather than offer a second launch.
    val openGame: (GameRow) -> Unit = { game ->
        if (game.gameId == data.liveGameId) onOpenLiveGame?.invoke() else onOpenGame(game)
    }
    val launched = data.launched.orEmpty()
    val shelfPool = data.shelfPool.orEmpty()
    // Not read yet: the tiles that would say "nothing here" stand empty instead (their shells keep their height), so the copy is never drawn only to be replaced.
    val pending = data.launched == null || data.shelfPool == null
    val lastPlayed = launched.firstOrNull()
    // Most launched's order is held here, not in the tile, because the games it shows decide which games Been a while may not repeat.
    var sort by remember { mutableStateOf(ActivitySort.LAUNCHES) }
    // The two Been a while slots never show one game twice, and neither shows a game another tile already has: the top-left tile's and the
    // ranked rows the player is looking at (the review of L2 found a third launched game repeated in both).
    val exclude = remember(launched, data.liveGameId, sort) { activityVisibleGameIds(launched, data.liveGameId, sort) }
    val t1Pick = remember(launched, shelfPool, data.nowMillis, data.zone, live, exclude) {
        if (lastPlayed == null && !live) {
            pickBeenAWhile(launched, shelfPool, exclude, data.nowMillis, data.zone)
        } else {
            null
        }
    }
    val t4Pick = remember(launched, shelfPool, data.nowMillis, data.zone, t1Pick, exclude) {
        if (mediaTile == null) {
            pickBeenAWhile(launched, shelfPool, exclude + listOfNotNull(t1Pick?.game?.gameId), data.nowMillis, data.zone)
        } else {
            null
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(zones.gapDp.dp)) {
        Column(Modifier.width(zones.leftWidthDp.dp), verticalArrangement = Arrangement.spacedBy(zones.gapDp.dp)) {
            when {
                live -> NowPlayingTile(
                    title = data.liveTitle.orEmpty(),
                    row = data.liveRow,
                    zones = zones,
                    onOpen = onOpenLiveGame?.takeIf { navigationEnabled },
                )
                lastPlayed != null -> LastPlayedTile(
                    game = lastPlayed,
                    zones = zones,
                    nowMillis = data.nowMillis,
                    zone = data.zone,
                    enabled = navigationEnabled,
                    onOpen = openGame,
                )
                else -> BeenAWhileTile(
                    pick = t1Pick,
                    pending = pending,
                    nowMillis = data.nowMillis,
                    zone = data.zone,
                    minHeightDp = zones.lastPlayedHeightDp,
                    enabled = navigationEnabled,
                    onOpen = openGame,
                    tag = ActivityTileLastPlayedTag,
                )
            }
            if (mediaTile != null) {
                mediaTile(Modifier.testTag(ActivityTileLowerLeftTag).heightIn(min = zones.lowerLeftHeightDp.dp))
            } else {
                BeenAWhileTile(
                    pick = t4Pick,
                    pending = pending,
                    nowMillis = data.nowMillis,
                    zone = data.zone,
                    minHeightDp = zones.lowerLeftHeightDp,
                    enabled = navigationEnabled,
                    onOpen = openGame,
                    tag = ActivityTileLowerLeftTag,
                )
            }
        }
        Column(Modifier.width(zones.rightWidthDp.dp), verticalArrangement = Arrangement.spacedBy(zones.gapDp.dp)) {
            WeekTile(week = data.week, minHeightDp = zones.weekHeightDp)
            RankedTile(
                games = data.launched,
                sort = sort,
                onSortChange = { sort = it },
                minHeightDp = zones.rankedHeightDp,
                nowMillis = data.nowMillis,
                zone = data.zone,
                enabled = navigationEnabled,
                onOpen = openGame,
            )
        }
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActivityTile(
    modifier: Modifier,
    minHeight: Dp,
    spacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    MimirLiquidGlassPanel(
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        contentPadding = 10.dp,
        verticalArrangement = Arrangement.spacedBy(spacing),
        radius = MimirSpacing.radiusCard,
        gradientBody = false,
        content = content,
    )
}

/** The tile's one accent eyebrow. Chrome caps by intent: this is the only caps on the page that is not a title. */
@Composable
private fun TileEyebrow(text: String, modifier: Modifier = Modifier) {
    MimirChromeText(
        text = text,
        style = MimirTypeScale.RunicEyebrow,
        color = LocalMimirPalette.current.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A game's face (see [LibraryGameFace]: the one art law), in a seat the caller shapes. */
@Composable
private fun ActivityGameFace(game: GameRow, modifier: Modifier) {
    LibraryGameFace(game, modifier.testTag(ActivityGameFaceTag))
}

/** The landscape art zone of Last played and Now playing (see [LibraryGameHeroZone]): hero, else logo, else the fitted cover. */
@Composable
private fun ActivityHeroZone(game: GameRow, modifier: Modifier) {
    LibraryGameHeroZone(game, modifier.testTag(ActivityGameFaceTag))
}

/**
 * Everything in Last played / Now playing but the art zone: pad (20), the eyebrow line (18), two gaps (12), the title line (19), a gap (2) and the
 * meta line (15). The art zone takes the rest of the tile, which is 83 dp tall at the solved 169.
 */
private const val HeroTileChromeDp = 20f + 18f + 12f + 19f + 2f + 15f

private fun heroZoneHeight(zones: ActivityBentoZones): Dp = (zones.lastPlayedHeightDp - HeroTileChromeDp).coerceAtLeast(60f).dp

// ── Last played / Now playing ────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LastPlayedTile(
    game: GameRow,
    zones: ActivityBentoZones,
    nowMillis: Long,
    zone: ZoneId,
    enabled: Boolean,
    onOpen: (GameRow) -> Unit,
) {
    val control = rememberBottomUiControl(BottomSurface.ACTIVITY)
    val latestOpen by rememberUpdatedState(onOpen)
    val source = remember { MutableInteractionSource() }
    ActivityTile(
        modifier = Modifier
            .testTag(ActivityTileLastPlayedTag)
            .mimirActivatable(
                label = "Open launch card",
                contentDescription = "Open ${game.title} launch card",
                enabled = enabled,
                interactionSource = source,
                onActivate = {
                    control("activity.open_launch_card", BottomUiReceiptFormat.opaque("game", game.gameId.value))
                    latestOpen(game)
                },
            )
            .mimirPressResponse(source),
        minHeight = zones.lastPlayedHeightDp.dp,
    ) {
        val palette = LocalMimirPalette.current
        key(game.gameId.value) {
            TileEyebrow("Last played")
            ActivityHeroZone(game, Modifier.fillMaxWidth().height(heroZoneHeight(zones)))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = game.title,
                    style = MimirTypeScale.Row,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = (game.lastPlayedMillis?.let { activityCapitalised(MimirTimeCopy.daysAgo(it, nowMillis, zone)) } ?: "Not played yet") +
                        " · " + activityLaunchesLabel(game.playCount),
                    style = MimirTypeScale.SupportCompact,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingTile(
    title: String,
    row: GameRow?,
    zones: ActivityBentoZones,
    onOpen: (() -> Unit)?,
) {
    val shown = title.ifBlank { "Game" }
    val source = remember { MutableInteractionSource() }
    ActivityTile(
        modifier = Modifier
            .testTag(ActivityTileNowPlayingTag)
            .mimirActivatable(
                label = "Open current game details",
                contentDescription = "Open $shown game details",
                enabled = onOpen != null,
                interactionSource = source,
                onActivate = { onOpen?.invoke() },
            )
            .mimirPressResponse(source),
        minHeight = zones.lastPlayedHeightDp.dp,
    ) {
        val palette = LocalMimirPalette.current
        TileEyebrow("Now playing")
        // The running game's own row (observeGame): never another game's art, and nothing while that read is pending.
        if (row != null) {
            ActivityHeroZone(row, Modifier.fillMaxWidth().height(heroZoneHeight(zones)))
        } else {
            Spacer(Modifier.fillMaxWidth().height(heroZoneHeight(zones)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = shown,
                style = MimirTypeScale.Row,
                color = palette.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Session details",
                style = MimirTypeScale.SupportCompact,
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Been a while ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun BeenAWhileTile(
    pick: BeenAWhilePick?,
    /** The library has not answered yet: with no pick the tile stands empty (its shell keeps its height) rather than saying there is nothing. */
    pending: Boolean,
    nowMillis: Long,
    zone: ZoneId,
    minHeightDp: Float,
    enabled: Boolean,
    onOpen: (GameRow) -> Unit,
    tag: String,
) {
    val control = rememberBottomUiControl(BottomSurface.ACTIVITY)
    val latestOpen by rememberUpdatedState(onOpen)
    val game = pick?.game
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    ActivityTile(
        modifier = Modifier
            .testTag(tag)
            .mimirActivatable(
                label = "Open launch card",
                contentDescription = game?.let { "Open ${it.title} launch card" } ?: "Nothing to open yet",
                enabled = enabled && game != null,
                interactionSource = source,
                onActivate = {
                    if (game != null) {
                        control("activity.open_launch_card", BottomUiReceiptFormat.opaque("game", game.gameId.value))
                        latestOpen(game)
                    }
                },
            )
            .mimirPressResponse(source),
        minHeight = minHeightDp.dp,
    ) {
        val palette = LocalMimirPalette.current
        val metal = remember(palette) { platformConsoleIconBrush(palette) }
        if (pick == null || game == null) {
            if (!pending) {
                TileEyebrow("Your games")
                Text(
                    text = "Nothing here yet",
                    style = MimirTypeScale.Row,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Launch a game and it will show up here.",
                    style = MimirTypeScale.Support,
                    color = palette.textSecondary,
                )
            }
        } else {
            key(game.gameId.value) {
                TileEyebrow(pick.kind.eyebrow)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityGameFace(game, Modifier.size(width = 84.dp, height = 112.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = game.title,
                            style = MimirTypeScale.Row,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        pick.line(nowMillis, zone).takeIf { it.isNotEmpty() }?.let { line ->
                            Text(
                                text = line,
                                style = MimirTypeScale.SupportCompact,
                                color = palette.textMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // The tile's look of an action; the whole tile is the one 48 dp+ target. Struck metal, as the pad's
                        // header plates -- the hero leather, the metal rim, the label inked in the same metal, raised, the press warming the top
                        // and sinking it. Static: no idle animation (no ember life), only the press.
                        Box(
                            modifier = Modifier.height(48.dp).testTag(ActivityOpenPlateTag),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .mimirMetalPlate(palette, MimirSpacing.radiusCard, lit = false, life = null, pressed = pressed, raised = true)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Open",
                                    style = MimirTypeScale.Support.copy(brush = metal, alpha = MimirMetalRestAlpha),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    modifier = Modifier.mimirRaisedPressNudge(pressed),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── This week ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Everything in the tile but the bars: pad, the header line, two gaps, the day letters, pad. */
private const val WeekTileChromeDp = 66f

@Composable
private fun WeekTile(week: List<DailyLaunchCount>?, minHeightDp: Float) {
    val barsHeight = (minHeightDp - WeekTileChromeDp).coerceAtLeast(40f).dp
    // One tween on the house overlay clock, on first arrival only: it starts when the week is first read, never again for a later
    // change of data (those redraw at the settled height), and a system that animates nothing snaps.
    val reduced = mimirReducedMotion()
    val progress = remember { Animatable(0f) }
    val loaded = week != null
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        if (reduced) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        }
    }
    ActivityTile(modifier = Modifier.testTag(ActivityTileWeekTag), minHeight = minHeightDp.dp) {
        val palette = LocalMimirPalette.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            TileEyebrow("This week", Modifier.weight(1f))
            if (week != null) {
                Text(
                    text = activityWeekHeadline(week),
                    style = MimirTypeScale.Support,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val days = week.orEmpty()
        val max = (days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
        // Molten bars in the Rune Pad Forge. Today's glow breathes on the shared ember life: bounded to a short
        // window after the page arrives, then steady at its mid level; read only in the draw block below and stood down while parked (D24) or reduced.
        val life = rememberEmberLife(LocalMimirMotionLive.current)
        // The ONE node a screen reader gets for the bars: the total and each day, not seven shapes and seven letters.
        Column(
            modifier = Modifier
                .testTag(ActivityWeekBarsTag)
                .clearAndSetSemantics { contentDescription = activityWeekSpoken(days) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Canvas(Modifier.fillMaxWidth().height(barsHeight)) {
                if (days.isEmpty()) return@Canvas
                val slot = size.width / days.size
                val barWidth = min(20.dp.toPx(), slot * 0.6f)
                val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                val settled = progress.value
                val tip = palette.accentBright
                val body = palette.accent
                val base = activityWeekBase(palette)
                val ingot = activityWeekIngot(palette)
                val capInk = palette.iconGradLight
                val cap = 1.5.dp.toPx()
                days.forEachIndexed { index, day ->
                    val left = slot * index + (slot - barWidth) / 2f
                    val h = activityWeekBarHeight(day.count, max, size.height, settled, minBar = 3.dp.toPx(), ingot = ActivityWeekIngotDp.dp.toPx())
                    val top = size.height - h
                    if (activityWeekBarKind(day.count) == ActivityWeekBarKind.INGOT) {
                        // A day with no launch is a short dark ingot, not a missing bar.
                        drawRoundRect(
                            brush = Brush.verticalGradient(listOf(ingot.first, ingot.second), startY = top, endY = size.height),
                            topLeft = Offset(left, top),
                            size = Size(barWidth, h),
                            cornerRadius = radius,
                        )
                        drawLine(
                            color = palette.iconGradDeep,
                            start = Offset(left + radius.x, top + cap / 2f),
                            end = Offset(left + barWidth - radius.x, top + cap / 2f),
                            strokeWidth = cap,
                        )
                    } else {
                        if (index == days.lastIndex) {
                            // Today's bar glows, softly breathing: rings round it in the theme's ember.
                            val strength = emberPlateStrength(life.wave(), lit = false, pressed = false) * settled
                            for (ring in 3 downTo 1) {
                                val grow = ring * 1.8.dp.toPx()
                                drawRoundRect(
                                    color = palette.tileGlow.copy(alpha = (palette.tileGlow.alpha * strength * (1f - (ring - 1) / 3f)).coerceIn(0f, 1f)),
                                    topLeft = Offset(left - grow, top - grow),
                                    size = Size(barWidth + 2f * grow, h + 2f * grow),
                                    cornerRadius = CornerRadius(radius.x + grow),
                                )
                            }
                        }
                        // Bright at the tip, cooling to the deep ember at the base, with a hot metal cap line on top.
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(0f to tip, ActivityWeekTipFraction to body, 1f to base),
                                startY = top,
                                endY = size.height,
                            ),
                            topLeft = Offset(left, top),
                            size = Size(barWidth, h),
                            cornerRadius = radius,
                        )
                        drawLine(
                            color = capInk,
                            start = Offset(left + radius.x, top + cap / 2f),
                            end = Offset(left + barWidth - radius.x, top + cap / 2f),
                            strokeWidth = cap,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                days.forEachIndexed { index, day ->
                    Text(
                        text = activityDayLetter(day.date),
                        style = MimirTypeScale.SupportCompact,
                        color = if (index == days.lastIndex) palette.accentBright else palette.textMuted,
                        fontWeight = if (index == days.lastIndex) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The accessible way back to the Shelf, now that the page has no Shelf button: a custom action, never a visible control. */
const val ActivityReturnToShelfLabel = "Return to Shelf"

/** Adds the "Return to Shelf" custom action when [onReturn] is given; [onReturn] answers true when the transition began. Nothing is drawn. */
internal fun Modifier.activityReturnAction(onReturn: (() -> Boolean)?): Modifier =
    if (onReturn == null) this else semantics { customActions = listOf(CustomAccessibilityAction(ActivityReturnToShelfLabel) { onReturn() }) }

/** pad8: a day with launches is a bar, one with none is a short dark ingot stub (never a missing bar). */
internal enum class ActivityWeekBarKind { BAR, INGOT }

internal fun activityWeekBarKind(count: Int): ActivityWeekBarKind = if (count <= 0) ActivityWeekBarKind.INGOT else ActivityWeekBarKind.BAR

/**
 * A day's bar height in px: [count] over the week's [max], on a [track] tall, scaled by the arrival's [settled] (0 to 1); never under [minBar].
 * A zero day is the fixed [ingot] stub, which does not wait for the arrival (the old two-dp stub never did).
 */
internal fun activityWeekBarHeight(count: Int, max: Int, track: Float, settled: Float, minBar: Float, ingot: Float): Float =
    if (count <= 0) ingot else (track * (count.toFloat() / max.coerceAtLeast(1)) * settled).coerceAtLeast(minBar)

/**
 * The deep, cooled base of a bar: the theme's accent sunk toward black (the absence of light, as every shadow is), so it is an ember-dark on a dark
 * theme and on a bright one alike. A token mix, no colour literal.
 */
internal fun activityWeekBase(palette: MimirThemePalette): Color = lerp(palette.accent, Color.Black, ActivityWeekBaseMix)

/** The ingot's two tones, top to bottom: darker than a bar's base. */
internal fun activityWeekIngot(palette: MimirThemePalette): Pair<Color, Color> =
    lerp(palette.accent, Color.Black, ActivityWeekIngotMixTop) to lerp(palette.accent, Color.Black, ActivityWeekIngotMixBottom)

private const val ActivityWeekBaseMix = 0.55f
private const val ActivityWeekIngotMixTop = 0.72f
private const val ActivityWeekIngotMixBottom = 0.86f

/** Where the bright tip has cooled to the accent: the top fifth of a bar. */
private const val ActivityWeekTipFraction = 0.2f

/** The ingot stub's height. */
internal const val ActivityWeekIngotDp = 4f

/** Most launched's body before the library answers: the 48 dp header row and the three 50 dp rows it fills with (the empty copy holds the same 198 dp). */
internal const val ActivityRankedBodyDp = 198f

// ── Most launched ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RankedTile(
    /** Null until the library has answered: the tile then stands empty at its settled height instead of saying nothing was launched. */
    games: List<GameRow>?,
    sort: ActivitySort,
    onSortChange: (ActivitySort) -> Unit,
    minHeightDp: Float,
    nowMillis: Long,
    zone: ZoneId,
    enabled: Boolean,
    onOpen: (GameRow) -> Unit,
) {
    val control = rememberBottomUiControl(BottomSurface.ACTIVITY)
    val latestOpen by rememberUpdatedState(onOpen)
    var menuOpen by remember { mutableStateOf(false) }
    // Composed from the frame it opens until its fade out has finished; the fade is read in the layer, so the tile does not recompose on each frame of it.
    val menuFade = rememberMimirFadeMount(visible = menuOpen, reducedMotion = mimirReducedMotion())
    val ranked = remember(games, sort) { activityRank(games.orEmpty(), sort) }
    ActivityTile(modifier = Modifier.testTag(ActivityTileRankedTag), minHeight = minHeightDp.dp, spacing = 0.dp) {
        val palette = LocalMimirPalette.current
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                if (games == null) {
                    // Not read yet: no copy and no chip, but the height the answer takes (the header row and the three rows), so nothing moves when it lands.
                    Spacer(Modifier.fillMaxWidth().height(ActivityRankedBodyDp.dp))
                } else if (games.isEmpty()) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        TileEyebrow(ActivitySort.LAUNCHES.headerLabel)
                    }
                    Column(Modifier.height(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Nothing launched yet",
                            style = MimirTypeScale.Row,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "The games you open most will show up here.",
                            style = MimirTypeScale.Support,
                            color = palette.textSecondary,
                        )
                    }
                } else {
                    val sortChipSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .testTag(ActivitySortChipTag)
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .mimirActivatable(
                                label = "Sort most launched",
                                contentDescription = "Sort order: ${sort.optionLabel}",
                                enabled = enabled,
                                interactionSource = sortChipSource,
                                onActivate = {
                                    control("activity.sort_menu", null)
                                    menuOpen = !menuOpen
                                },
                            )
                            .mimirPressResponse(sortChipSource),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TileEyebrow(sort.headerLabel, Modifier.weight(1f, fill = false))
                            MimirChromeText("▾", style = MimirTypeScale.RunicEyebrow, color = palette.accent, maxLines = 1)
                        }
                    }
                    ranked.forEachIndexed { index, game ->
                        key(game.gameId.value) {
                            val rowSource = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 50.dp)
                                    .mimirActivatable(
                                        label = "Open launch card",
                                        contentDescription = "Open ${game.title} launch card",
                                        enabled = enabled && !menuOpen,
                                        interactionSource = rowSource,
                                        onActivate = {
                                            control("activity.open_launch_card", BottomUiReceiptFormat.opaque("game", game.gameId.value))
                                            latestOpen(game)
                                        },
                                    )
                                    .mimirPressResponse(rowSource),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MimirTypeScale.SupportCompact,
                                    color = palette.textMuted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(14.dp),
                                )
                                ActivityGameFace(game, Modifier.size(36.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = game.title,
                                        style = MimirTypeScale.Row,
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = activityRankedValue(game, sort, nowMillis, zone),
                                        style = MimirTypeScale.SupportCompact,
                                        color = palette.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (menuOpen || menuFade.mounted) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 48.dp)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = menuFade.alpha }
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                        .background(palette.surfaceHigh)
                        .padding(vertical = 4.dp),
                ) {
                    ActivitySort.entries.forEachIndexed { index, option ->
                        if (index > 0) PlainMenuSeparator()
                        val optionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .mimirActivatable(
                                    label = "Sort by ${option.optionLabel}",
                                    contentDescription = "Sort by ${option.optionLabel}",
                                    enabled = enabled && menuOpen,
                                    interactionSource = optionSource,
                                    onActivate = {
                                        control("activity.sort_${option.name.lowercase()}", null)
                                        onSortChange(option)
                                        menuOpen = false
                                    },
                                )
                                .mimirPressResponse(optionSource),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassMenuCapsule(focused = option == sort, identity = option) {
                                Text(
                                    text = option.optionLabel,
                                    style = MimirTypeScale.Row,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Achievements ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActivityAchievementsStrip(
    snapshots: List<CompanionActivityAchievementSnapshot>,
    accessEnabled: Boolean,
    totalPoints: Int? = null,
    softcorePoints: Int = 0,
    /** Nothing is known yet: not "0 earned" and not "nothing checked", but the neutral line, until the ledger or a live read has said something. */
    checking: Boolean = false,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    // The counts are a sum over every checked game: once per change of the list (the page hands the same list while nothing changed), not per recomposition.
    val (unlocked, total) = remember(snapshots) { snapshots.sumOf { it.unlocked } to snapshots.sumOf { it.total } }
    val source = remember { MutableInteractionSource() }
    MimirLiquidGlassPanel(
        modifier = Modifier
            .testTag(ActivityTileAchievementsTag)
            .fillMaxWidth()
            .heightIn(min = if (snapshots.isEmpty()) 64.dp else 72.dp)
            .mimirActivatable(
                label = "Open achievements",
                contentDescription = "Open achievements",
                enabled = enabled,
                interactionSource = source,
                onActivate = onOpen,
            )
            .mimirPressResponse(source),
        contentPadding = 12.dp,
        radius = MimirSpacing.radiusCard,
        gradientBody = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (snapshots.isNotEmpty()) {
                MimirProgressRing(done = unlocked, total = total, size = 40.dp, stroke = 4.dp, palette = palette)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TileEyebrow("Achievements")
                if (snapshots.isNotEmpty()) {
                    Text(
                        text = activityAchievementsStripText(unlocked, total, snapshots.size, totalPoints, softcorePoints),
                        style = MimirTypeScale.Support,
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (checking) {
                    Text(
                        text = ACTIVITY_ACHIEVEMENTS_CHECKING_LINE,
                        style = MimirTypeScale.Support,
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // The old entry's honest states, in the same words: nothing checked yet, and what it takes to check.
                    Text(
                        text = MimirAchievementCopy.NothingCheckedYet,
                        style = MimirTypeScale.Support,
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (accessEnabled) {
                            MimirAchievementCopy.CheckGamesToLoad
                        } else {
                            "Connect RetroAchievements in Settings to load achievements"
                        },
                        style = MimirTypeScale.SupportCompact,
                        color = palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(text = "›", style = MimirTypeScale.Title, color = palette.textMuted)
        }
    }
}

/** The strip's line while nothing is known about the account's achievements yet: neither a count nor a claim that nothing was checked. */
internal const val ACTIVITY_ACHIEVEMENTS_CHECKING_LINE: String = "Checking achievements…"

/** What the page reads before anything has been collected: no count, no games, a week not yet read, nothing running. */
internal val EmptyActivityBentoData = ActivityBentoData(
    gameCount = null,
    launched = null,
    shelfPool = null,
    week = null,
    liveGameId = null,
    liveTitle = null,
    liveRow = null,
    nowMillis = 0L,
    zone = ZoneId.of("UTC"),
)

/**
 * How far the header's stat stops short of the page's end when the Presentation lays the recent-app icon over the top end (48 dp icon, 24 dp from
 * the edge, 8 dp clear) less the page's own 18 dp inset. The icon was 44 dp (so 58) until the 48 dp touch minimum (audit C2, Thor Bottom revamp V8).
 */
internal const val ActivityRecentAppReserveDp = 62f

/** A score with its thousands grouped the way RetroAchievements prints it ("1,234"), whatever the device's language. */
internal fun activityPointsText(points: Int): String = String.format(java.util.Locale.US, "%,d", points)

/**
 * The strip's line: the earned count across the checked games, and the user's two scores when the cold-start read had them, the way RetroAchievements shows them: the
 * hardcore points are the main score ("1,500 points") and the softcore points stand beside them ("Softcore 1,234"), never added together. A player with softcore points only
 * is told just the softcore score rather than a bare "0 points". Pure, so the words are a test.
 */
internal fun activityAchievementsStripText(unlocked: Int, total: Int, games: Int, totalPoints: Int?, softcorePoints: Int = 0): String {
    val base = if (games == 1) "$unlocked of $total earned in 1 game" else "$unlocked of $total earned across $games games"
    if (totalPoints == null) return base
    val scores = ArrayList<String>(2)
    if (totalPoints > 0 || softcorePoints <= 0) scores += "${activityPointsText(totalPoints)} points"
    if (softcorePoints > 0) scores += "Softcore ${activityPointsText(softcorePoints)}"
    return (listOf(base) + scores).joinToString(" · ")
}

/** The metal plate on the "Been a while" / "Recently played" tile, for tests. */
internal const val ActivityOpenPlateTag = "activity-open-plate"
