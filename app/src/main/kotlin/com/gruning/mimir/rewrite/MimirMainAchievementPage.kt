package com.gruning.mimir.rewrite

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirEtchedProgressBar
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.mimirAchievementScrollStep
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlinx.coroutines.Dispatchers

internal const val MainAchievementPageTag = "main-achievement-page"
internal const val MainAchievementGridTag = "main-achievement-grid"
internal const val MainAchievementHeaderTag = "main-achievement-header"
internal const val MainAchievementDetailPaneTag = "main-achievement-detail-pane"

/** The page's own scope on Main's shared focus authority: pushed while the page is up, discarded when it leaves, so focus goes back where it was. */
internal val MainAchievementPadScope = FocusScope("achievement/main-card")

/** The width from which the page lays out as two panes (the grid, and beside it the detail); a narrower page is one column. */
internal val MainAchievementWideBreakpoint: Dp = 560.dp

/**
 * How far the badge grid reaches past its bounds, up and down: [MimirSpacing.xs], well short of the page's own gap to its neighbours ([MimirSpacing.md]). A scrolling grid cuts
 * its rows at its viewport edge, and with the viewport reaching the whole gap that edge was flush with the progress bar's bottom: a row going up was sliced against the bar
 *. Now the rows are cut [MimirSpacing.md] less this (8 dp) below the bar and above the detail, so there is air at the
 * cut. The rest of the ring's paint at the peak of its arrival flare ([ActivityBadgePaintReach], 20 dp) is the grid's own content padding ([MainAchievementGridPaintRoom]), so
 * the flare is whole at the very top and bottom of the list, and the pad's walk keeps the lit ring that far inside the viewport ([FollowLitBadge]).
 */
internal val MainAchievementGridRingRoom: Dp = MimirSpacing.xs

/** The grid's content padding above the first row and below the last: the whole ring at the peak of its flare, so a viewport that clips at [MainAchievementGridRingRoom] cuts none of it. */
internal val MainAchievementGridPaintRoom: Dp = ActivityBadgePaintReach

/**
 * The grid's content padding at each side. A vertical grid clips on its scroll axis only, so across it the flare would paint on: past the page's 16 dp padding at its left (into the
 * card's rounded clip) and past the 16 dp gap into the detail pane at its right. The padding is what the flare needs beyond that 16 dp (4 dp).
 */
internal val MainAchievementGridSideRoom: Dp = ActivityBadgePaintReach - MimirSpacing.lg

/** Below this height (inside the page's padding) the title takes one line, so the grid keeps its room. */
internal val MainAchievementShortHeight: Dp = 400.dp

internal const val MainAchievementSelectHint = "Select a badge for its details."

/** Thor Main's width (832 dp) less the overlay's own padding fits; a wider screen keeps the page this wide rather than stretching a row of badges across it. */
internal val MainAchievementPageMaxWidth: Dp = 880.dp

/**
 * Whether a pad A waits one frame for the hat press that really came first (Android hands a button over at once and holds the hat for the frame): while the on-screen
 * keyboard owns focus, and on Main's achievement page, where A on a badge must act on the badge the hat moved to.
 */
internal fun mainPadAWaitsForItsFrame(scope: FocusScope): Boolean = scope == MimirKeyboardFocusScope || scope == MainAchievementPadScope

/** What the pad does on Main's page: A selects the focused badge, L1 and R1 scroll the detail, B (handled by the overlay's own dispatch) leaves. */
private class MainAchievementPadHost : ActivityPadHost {
    var onSelect: (FocusKey) -> Unit = {}
    var onLeave: () -> Unit = {}
    var onPage: (Int) -> Boolean = { false }

    override fun navigationEnabled(): Boolean = true

    override fun activate(key: FocusKey): FocusKey? {
        onSelect(key)
        return null
    }

    override fun page(delta: Int): Boolean = onPage(delta)

    override fun leave() = onLeave()

    override fun dismissModal(): FocusKey? = null
}

/**
 * Main's achievement card, once the lane has loaded the game: the same rich page the Bottom's hub shows, on the room Main has (single-display devices need it
 * as much as the dual-screen one). The game's hero art full-bleed under the palette scrim (else RA's own title image, else its icon, else the theme), the title and count with
 * the points, the hardcore mark and the award RetroAchievements gave the game (the Bottom's own words and source), the metal-etched progress bar, and EVERY badge of the set in a grid: colour art when earned, its `_lock` art when not, 64 px pixel art at a
 * whole-number scale (the Bottom's tile, 48 dp or larger). A tap or the pad's A on a badge shows its title, description, points and when it was earned.
 *
 * Wide pages (Thor Main, the 700 x 350 floor, any landscape handheld) are two panes: header, bar and grid on the left, the detail beside them. A narrow page is one column
 * with the detail under the grid. The grid scrolls (it is lazy, and only the tiles it composes ask for their art, from the bounded shared cache); the detail scrolls; nothing is
 * cut off at font 1.0, 1.3 or 2.0.
 *
 * Controller first, through the ONE focus authority (never Compose focus): the page pushes its own scope on [authority] and walks the grid by the Bottom page's own rule
 * ([ActivityHubPad]: left and right along a row, up and down by row keeping the column, a lit badge that is not composed is scrolled to). The ring is lit from the first frame
 * and goes dark under a touch. [installGrid] hands the overlay's dispatcher the pad's entry point (D-pad moves, A selects) and [releaseGrid] takes it back; B and the OS Back
 * stay the overlay's own ([onBack]). Reduced motion and every animation are the shared pieces': the bar's fill happens once, and nothing runs at rest.
 */
@Composable
internal fun MimirMainAchievementPage(
    progress: RetroAchievementsProgress,
    artSnapshot: CompanionActivityAchievementSnapshot,
    artResolver: ActivityAchievementArtResolver,
    badgeSource: ActivityBadgeSource,
    reducedMotion: Boolean,
    authority: FocusAuthority,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    installGrid: ((ControllerAction) -> Boolean) -> Unit = {},
    releaseGrid: ((ControllerAction) -> Boolean) -> Unit = {},
    artContext: CoroutineContext = Dispatchers.IO,
    /** The badge grid's state; a test seam (handed in to read the scroll position), the product leaves the default. */
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val palette = LocalMimirPalette.current
    val latestBack = rememberUpdatedState(onBack)
    val padHost = remember { MainAchievementPadHost() }
    val pad = remember(authority) { ActivityHubPad(authority, padHost, MainAchievementPadScope, startEngaged = true, pushesScope = false) }
    val badges = remember(progress) { activityBadgeOrder(progress.achievements) }
    var selectedId by remember(progress.gameId) { mutableStateOf<Int?>(null) }
    var columns by remember { mutableStateOf(1) }
    // The detail pane's own scroll: the pad's L1 and R1 move it by the ledger's bounded step (most of the pane's height), so a long description can be read without a finger.
    val detailScroll = rememberScrollState()
    var detailViewportPx by remember { mutableIntStateOf(0) }
    // A key names a badge, never a position; the rows are the grid's own (as many to a row as it lays out).
    val ring = remember(badges, columns) { ActivityPadRing(header = badges.chunked(columns).map { row -> row.map { ActivityPadKeys.badge(it.id) } }) }
    // The scope is Main's shared authority's: pushed once this page is committed (before the ring registers below), discarded with it.
    DisposableEffect(pad) {
        authority.pushScope(MainAchievementPadScope)
        onDispose {
            pad.close()
            authority.discardScope(MainAchievementPadScope)
        }
    }
    val dispatch = remember(pad) { { action: ControllerAction -> pad.dispatch(action) } }
    DisposableEffect(dispatch) {
        installGrid(dispatch)
        onDispose { releaseGrid(dispatch) }
    }
    SideEffect {
        padHost.onSelect = { key -> badges.firstOrNull { ActivityPadKeys.badge(it.id) == key }?.let { selectedId = it.id } }
        padHost.onLeave = { latestBack.value() }
        padHost.onPage = { delta -> detailScroll.dispatchRawDelta(delta * mimirAchievementScrollStep(detailViewportPx).toFloat()) != 0f }
        pad.setRing(ring)
    }
    // A different badge's detail starts at its top.
    LaunchedEffect(selectedId) { detailScroll.scrollTo(0) }
    val density = LocalDensity.current
    val badgeSize = with(density) { (RA_BADGE_SOURCE_PX * activityBadgeScale(MimirSpacing.minTarget.toPx())).toDp() }
    val selected = badges.firstOrNull { it.id == selectedId }?.let { activityBadgeDetail(it) }
    val unlocked = progress.unlockedCount
    val total = progress.totalCount
    // The Bottom's own sources and rule (the shared header): the hardcore count is the card's rows, else RA's figure from the bulk read (unlocks only grow, so the larger is the
    // newer), and the award is RA's word for the game (a per-game read names none; the Activity page's row does) or what the counts prove.
    val hardcore = max(progress.achievements.count { it.unlockedHardcore }, artSnapshot.earnedHardcore)
    val award = activityAwardLabel(unlocked, total, hardcore, artSnapshot.awardKind)
    CompositionLocalProvider(LocalActivityHubPad provides pad) {
        Box(
            modifier
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(palette.surfaceBase.copy(alpha = 0.94f))
                .pointerInput(pad) {
                    // A finger lands: the ring goes dark until the pad's next press. Watched on the way down and never consumed, so no touch changes meaning.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed }) pad.onTouch()
                        }
                    }
                }
                .testTag(MainAchievementPageTag),
        ) {
            ActivityAchievementArt(artSnapshot, artResolver, Modifier.fillMaxSize(), workContext = artContext)
            BoxWithConstraints(Modifier.fillMaxSize().padding(MimirSpacing.lg)) {
                val wide = maxWidth >= MainAchievementWideBreakpoint
                val shortPage = maxHeight < MainAchievementShortHeight
                MainAchievementPaneLayout(
                    wide = wide,
                    maxHeight = maxHeight,
                    header = {
                        ActivityAchievementHeader(
                            title = progress.title,
                            unlocked = unlocked,
                            total = total,
                            progress = progress,
                            hardcore = hardcore,
                            award = award,
                            titleMinLines = 1,
                            titleMaxLines = if (shortPage) 1 else 2,
                            statsMaxLines = Int.MAX_VALUE,
                            statsFillWidth = true,
                            modifier = Modifier.testTag(MainAchievementHeaderTag),
                        )
                    },
                    bar = {
                        MimirEtchedProgressBar(
                            fraction = if (total > 0) unlocked.toFloat() / total else 0f,
                            reducedMotion = reducedMotion,
                            modifier = Modifier.fillMaxWidth().height(MimirSpacing.lg).testTag(AchievementPanelProgressTag),
                        )
                    },
                    grid = { gridModifier ->
                        MainAchievementGrid(
                            badges = badges,
                            badgeSize = badgeSize,
                            selectedId = selectedId,
                            badgeSource = badgeSource,
                            onSelect = { id ->
                                selectedId = id
                                authority.requestFocus(ActivityPadKeys.badge(id))
                            },
                            onColumns = { count -> if (count != columns) columns = count },
                            state = gridState,
                            reducedMotion = reducedMotion,
                            modifier = gridModifier,
                        )
                    },
                    detail = { detailModifier -> MainAchievementDetail(selected, detailScroll, { detailViewportPx = it }, detailModifier) },
                )
            }
        }
    }
}

/**
 * The four pieces in one column: the header (title, count, points, hardcore) and the bar across the page's whole width, where the numbers have the most room to stay whole,
 * then the grid and the detail, side by side on a wide page and one under the other on a narrow one.
 */
@Composable
private fun MainAchievementPaneLayout(
    wide: Boolean,
    maxHeight: Dp,
    header: @Composable () -> Unit,
    bar: @Composable () -> Unit,
    grid: @Composable (Modifier) -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        header()
        bar()
        MainAchievementBody(wide, maxHeight, grid, detail)
    }
}

/** What is under the header: two panes on a wide page, one column on a narrow one; its own function so each branch has a full body. */
@Composable
private fun ColumnScope.MainAchievementBody(
    wide: Boolean,
    maxHeight: Dp,
    grid: @Composable (Modifier) -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    if (wide) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg)) {
            grid(Modifier.weight(1.7f).fillMaxHeight())
            detail(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        grid(Modifier.weight(1f).fillMaxWidth())
        detail(Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.3f))
    }
}

/**
 * Every badge of the set, as many to a row as the width holds at the shared tile size. Lazy: only the rows in view compose their tiles, and a tile asks the bounded shared cache
 * for its art only then. The pad's lit badge is followed by ONE scroller ([FollowLitBadge]): composed or not, it is glided to and shown whole with its ring's air around it,
 * and a held or fast-repeated move only retargets the glide.
 */
@Composable
private fun MainAchievementGrid(
    badges: List<RetroAchievementsAchievement>,
    badgeSize: Dp,
    selectedId: Int?,
    badgeSource: ActivityBadgeSource,
    onSelect: (Int) -> Unit,
    onColumns: (Int) -> Unit,
    state: LazyGridState,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.testTag(MainAchievementGridTag)) {
        val gap = MimirSpacing.sm
        val columns = max(1, ((maxWidth - MainAchievementGridSideRoom * 2 + gap) / (badgeSize + gap)).toInt())
        SideEffect { onColumns(columns) }
        FollowFocusedBadge(state, badges, columns, gap, reducedMotion)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            // A grid clips on its scroll axis. It reaches [MainAchievementGridRingRoom] past its bounds (short of the page's own gap to the bar above and the detail, so the cut has air), and
            // its content is padded by the whole ring at the peak of its flare (D-ledger leftover, 10-07): the ring on a badge in the first or the last row is whole, never paints over the
            // bar or the detail, and across the grid the side padding keeps the flare off the card's edge and out of the detail pane.
            modifier = Modifier.reachingBeyondItsParentVerticallyBy(MainAchievementGridRingRoom).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MainAchievementGridSideRoom, vertical = MainAchievementGridPaintRoom),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(badges, key = { it.id }) { achievement ->
                // The grid hands every cell the cell's whole width as a fixed one, which would stretch the tile past its own size and break the whole-number scale of its art: the
                // tile sits centred in its cell at exactly the shared size.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ActivityBadge(
                        achievement,
                        badgeSize,
                        selected = achievement.id == selectedId,
                        source = badgeSource,
                        onSelect = { onSelect(achievement.id) },
                        // The grid's one scroller is FollowLitBadge above; a tile that asked its scroll parent to reveal it as well was the second scroller that made a burst jump.
                        revealOnFocus = false,
                    )
                }
            }
        }
    }
}

/**
 * Hands [FollowLitBadge] the badge the page's focus is on. Its own function so the read of the focus authority recomposes only this and not the grid.
 */
@Composable
private fun FollowFocusedBadge(state: LazyGridState, badges: List<RetroAchievementsAchievement>, columns: Int, gap: Dp, reducedMotion: Boolean) {
    val focused = rememberPageFocusedKey()
    val litIndex = remember(badges, focused) { if (focused == null) -1 else badges.indexOfFirst { ActivityPadKeys.badge(it.id) == focused } }
    val density = LocalDensity.current
    FollowLitBadge(
        state = state,
        litIndex = litIndex,
        columns = columns,
        gapPx = with(density) { gap.roundToPx() },
        outsetPx = with(density) { ActivityBadgePaintReach.toPx() },
        reducedMotion = reducedMotion,
    )
}

/**
 * The key the page's scope holds focus on, lit or not: a badge the pad moved to and a badge a finger tapped are both revealed (the tile used to ask its scroll parent for it on
 * either), and only a CHANGE of it moves the grid, so a finger scrolling the grid away from the focused badge is never pulled back.
 */
@Composable
private fun rememberPageFocusedKey(): FocusKey? {
    val pad = LocalActivityHubPad.current ?: return null
    val state = pad.authority.state.collectAsState().value
    return if (state.scope == pad.scope) state.focused else null
}

/** The selected badge's detail beside (or under) the grid, with the card's honesty line: Mimir cannot unlock anything, the emulator does. */
@Composable
private fun MainAchievementDetail(selected: ActivityBadgeDetail?, scrollState: ScrollState, onViewportPx: (Int) -> Unit, modifier: Modifier) {
    val palette = LocalMimirPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(palette.surfaceBase.copy(alpha = 0.55f))
            .onSizeChanged { onViewportPx(it.height) }
            .padding(MimirSpacing.md)
            .testTag(MainAchievementDetailPaneTag),
    ) {
        ActivityBadgeDetailBlock(
            selected = selected,
            hint = MainAchievementSelectHint,
            modifier = Modifier.fillMaxSize(),
            footer = MimirAchievementCopy.EarnedInEmulator,
            scrollState = scrollState,
        )
    }
}

/**
 * What the shared art resolves from for Main's card: the library game's id (its hero, with the Customize-media override, comes first), then RA's own title or in-game image
 * for the set, then its icon (the same order as the Bottom's card). It also carries the game's award and hardcore count from [activityRow] (the Activity page's row for the game,
 * see [mainAchievementActivityRow]), the one source the Bottom's card reads them from; the art does not depend on either.
 */
internal fun mainAchievementArtSnapshot(
    progress: RetroAchievementsProgress,
    gameIdValue: String,
    activityRow: CompanionActivityAchievementSnapshot? = null,
): CompanionActivityAchievementSnapshot =
    CompanionActivityAchievementSnapshot(
        gameId = gameIdValue,
        account = "main",
        unlocked = progress.unlockedCount,
        total = progress.totalCount,
        gameTitle = progress.title,
        iconPath = progress.imageIconPath,
        raGameId = progress.gameId,
        artPath = progress.imageTitlePath ?: progress.imageIngamePath,
        earnedHardcore = activityRow?.earnedHardcore ?: 0,
        awardKind = activityRow?.awardKind,
    )

/**
 * The Activity page's row for the game Main's card is open for, where RA's award word and hardcore count live (the bulk read gives them; a per-game read gives neither): the
 * signed-in [account]'s row for THIS set, the one RA's own id [raGameId] names, whichever library game it is keyed under (a game the library could not be matched to sits under
 * `ra:<id>`). It is never found by the library game alone: a ROM replaced by another set keeps its library game, and the old set's award and hardcore count are not the new set's.
 * A row that carries an award is preferred to one that does not, so a card check's row, which has none, never hides the bulk read's. No account or no RA id is no row.
 */
internal fun mainAchievementActivityRow(
    activity: List<CompanionActivityAchievementSnapshot>,
    account: String?,
    raGameId: Int,
): CompanionActivityAchievementSnapshot? {
    if (account.isNullOrBlank() || raGameId <= 0) return null
    val rows = activity.filter { it.account == account && it.raGameId == raGameId }
    return rows.firstOrNull { it.awardKind != null } ?: rows.firstOrNull()
}

/**
 * Main's overlay mount of the page: builds the art sources from what Main already has (the game's hero, with its Customize-media override, from the game the card was opened
 * for; RA's title image and icon from the lane's own answer, staged by the lane's keyless, gated, bounded fetch into the one shared icon store) and hands the page the
 * overlay's authority and the lane's controller seam. Nothing here asks the network: the answer is the card probe's, and it already warmed the cache the Bottom reads.
 */
@Composable
internal fun MimirMainAchievementCard(
    achievements: MimirAchievementsState,
    progress: RetroAchievementsProgress,
    authority: FocusAuthority,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val game = achievements.homeProbeGame
    val gameIdValue = achievements.openForGameId.orEmpty()
    val icons = remember(context) { CompanionActivityIcons.storeFor(context) }
    val artResolver = remember(context, gameIdValue) {
        LibraryActivityAchievementArtResolver(
            heroUriFor = { id -> CustomHeroStore.overrideUri(context, GameId(id)) ?: game?.heroArtUri },
            icons = icons,
        )
    }
    val badgeSource = remember(icons) { StoreActivityBadgeSource(icons) }
    // The award and the hardcore count come from the Activity page's row for this game; they arrive with the bulk read, so the card follows that flow.
    val activity by CompanionActivityAchievements.snapshots.collectAsState()
    val activityRow = mainAchievementActivityRow(activity, achievements.signedInAccount(), progress.gameId)
    val artSnapshot = remember(progress, gameIdValue, activityRow?.awardKind, activityRow?.earnedHardcore) {
        mainAchievementArtSnapshot(progress, gameIdValue, activityRow)
    }
    MimirMainAchievementPage(
        progress = progress,
        artSnapshot = artSnapshot,
        artResolver = artResolver,
        badgeSource = badgeSource,
        reducedMotion = reducedMotion,
        authority = authority,
        onBack = achievements::dismissCard,
        modifier = modifier,
        installGrid = achievements::installCardGrid,
        releaseGrid = achievements::releaseCardGrid,
    )
}
