package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCard
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.MimirGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSheetHost
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirHold
import com.gruning.mimir.rewrite.core.ui.mimirPageTouchShield
import com.gruning.mimir.rewrite.core.ui.rememberMimirFadeMount
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val AchievementHubCardTag = "achievement-hub-card"
internal const val AchievementHubListTag = "achievement-hub-list"
internal const val AchievementHubRunesTag = "achievement-hub-runes"

/**
 * The Bottom's achievements (Thor Bottom revamp V10, policy 9): one [ActivityAchievementPanel] per checked game, drawn from the last-checked snapshot (its counts) and, once
 * the game's detail has landed, its whole badge set (earned and locked) and its points. A card that is composed asks for its game's detail through [requestDetail] (at most one call
 * per game per five minutes, saved to the ledger); a card that is not composed asks nothing, and the page, its filter and its strip start no request of their own. With no snapshot
 * the page shows [MimirAchievementCard]'s honest Unknown state with [unknownState]'s reason, never an empty Loaded.
 *
 * Touch first, and the pad walks it too (see [ActivityHubPad]): the page keeps its own focus authority, and while it can be driven (its pane shown, it the scene on show, the window up and lit) the Bottom floor hands it the pad
 * ahead of Main, through a router of its own (the hat-then-A order, held-hat repeat). The card's ledger scrolls by touch (its own list state). With more than one checked game each is a card of its own, stacked top to bottom, that snaps into place, with a small "more below" cue at its foot and the place in the list
 * ("Game 3 of 12") beside the count. Back is the Activity pages' shared 48 dp pill, so the Back receipt path is unchanged: the strip's tap wrote
 * `activity.open_achievements`, and the scene swap into and out of the hub writes `scene.crossfade` / `scene.glide`, as before.
 *
 * With two or more checked games the page's one top row holds the filter: the compact Back pill, a short search that opens the Mimir keyboard and filters by title as it is typed, a "Show: All" chip (All, In progress, Beaten,
 * Completed, Mastered, Recent) and the console chip, both opening the one glass picker; the result count follows. The games are in ONE order: the newest activity (the later of the last
 * unlock and the last play) first. It works on the held snapshot only: no request starts from it, and a card asks for its detail only while it is composed, so a filter that leaves
 * one game asks for at most that one. The filter lives in [filterHolder] (the process's, by default).
 *
 * [recent] is the user's latest unlocked badges across every game, one read with the cold-start bulk: a row of up to twelve crisp pixel-art
 * tiles on the count row (or its own row for a single game), hidden when there are none. A tap on a tile jumps the pager to that game's card, clearing the filters first
 * when they hide it, and opens that badge's details; the card is not asked for anything the strip's tap did not already carry. A tile whose game has no card is not shown.
 */
@Composable
internal fun CompanionActivityAchievementHub(
    snapshots: List<CompanionActivityAchievementSnapshot>,
    unknownState: MimirAchievementCardState,
    navigationEnabled: Boolean,
    onBack: () -> Unit,
    /** Where a card's art comes from; the default draws none. Resolved lazily, off the main thread, for the pages the list composes. */
    artResolver: ActivityAchievementArtResolver = NoActivityAchievementArt,
    /** The badge pixel art source; the default draws none (previews, tests). */
    badgeSource: ActivityBadgeSource = NoActivityBadgeArt,
    /** What each composed card knows about its game (its badges), by RA game id. */
    details: StateFlow<Map<Int, ActivityCardDetail>> = CompanionActivityCardDetails.state,
    /** A card that is composed asks for its game's detail through this; a card that is not composed asks nothing. */
    requestDetail: (raGameId: Int) -> Unit = CompanionActivityCardDetails::request,
    /** A tap on a card whose badges failed to load asks for them again, once, outside the five-minute window; the client's pacer and any Retry-After still apply. */
    retryDetail: (raGameId: Int) -> Unit = CompanionActivityCardDetails::retry,
    /** A card that is composed holds its game's answer while it is, so no other game's ask evicts the badges it is showing; the release runs when the card leaves. */
    holdDetail: (raGameId: Int) -> (() -> Unit) = CompanionActivityCardDetails::hold,
    /** The page's filter, held for the process so a swipe, a parked pane and a re-entered hub keep it; a cold start begins at its default. */
    filterHolder: ActivityAchievementFilterHolder = ActivityAchievementFilterHolder.process,
    /** The clock "Recent" is judged by: read when the filter is applied, never per frame. */
    nowMillis: () -> Long = System::currentTimeMillis,
    /** The user's latest unlocks across every game, newest first (the strip at the top of the page); empty hides the strip. Only unlocks of a game with a card are shown. */
    recent: List<ActivityRecentUnlock> = emptyList(),
    /** The Back pill's words: "Activity", or "Session" while a game session is running (the host then returns Back to that game's Now Playing page). */
    backLabel: String = "Activity",
    /** The game (by the id its snapshot carries) the hub opens on when the host knows it (Now Playing › Achievements: the running game); null opens at the first card. */
    focusGameId: String? = null,
    /** When each game (by the id its snapshot carries) was last played, from the library: with the last unlock it puts the newest activity first. */
    lastPlayed: Map<String, Long> = emptyMap(),
    /** The router the page's pad runs through and registers with the floor while live; null builds the Bottom's own ([newActivityPadRouter]). Tests hand one in. */
    padRouter: InputRouter? = null,
    /** False while the workspace's swap is carrying this page away (another scene is the one on show): the pad is given back then, not after the page has left composition. */
    padLive: Boolean = true,
    /** Whether the Bottom's window is up and lit; the pad's route follows it ([CompanionWindowLive]). The default is a host that is always live (Main, previews, tests). */
    windowLive: CompanionWindowLive = CompanionWindowLive.Always,
    /** A test seam: handed the page's glass progress (0 = no blur, 1 = the picker's blur) so a test can read it. Nothing in the product passes one. */
    glassProbe: ((() -> Float) -> Unit)? = null,
    /** A test seam: called as each play of the "more below" cue begins. Nothing in the product passes one. */
    onCuePlay: () -> Unit = {},
) {
    val allDetails by details.collectAsState()
    val latestNavigationEnabled = rememberUpdatedState(navigationEnabled)
    val reducedMotion = remember { bottomUiReducedMotion() }
    val palette = LocalMimirPalette.current
    // The filter is derived from the held snapshot and the filter state ONCE per change of either (a keystroke, a chip, a new game), never per frame and never
    // from the network. With fewer than two games there is nothing to choose between: no header, the filter is ignored, and the page keeps its old layout.
    // Opened on a game (Now Playing's Achievements chip: the running game): whatever an earlier visit left in the filter (a Show word, a console, a query) must not hide that game from
    // the list it is to open on, so the filter is cleared first, as a tapped recent unlock clears the one that hides its game. Once per opening, before the list is derived.
    var entryFilterPending by remember(focusGameId) {
        mutableStateOf(focusGameId != null && snapshots.any { it.gameId == focusGameId })
    }
    val heldFilter by filterHolder.state.collectAsState()
    val filter = if (entryFilterPending) ActivityAchievementFilter() else heldFilter
    LaunchedEffect(focusGameId) {
        if (entryFilterPending) {
            filterHolder.clearFilters()
            entryFilterPending = false
        }
    }
    val showHeader = snapshots.size >= ACTIVITY_FILTER_MIN_GAMES
    // The library's last play is folded into each game once per change of either list; a game it does not know keeps only its last unlock to be ordered by.
    val ordered = remember(snapshots, lastPlayed) {
        if (lastPlayed.isEmpty()) snapshots else snapshots.map { game -> lastPlayed[game.gameId]?.let { played -> game.copy(lastPlayedAtMillis = played) } ?: game }
    }
    val index = remember(ordered) { ActivityAchievementIndex(ordered) }
    val result = remember(index, filter, showHeader) {
        activityAchievementResult(index, if (showHeader) filter else ActivityAchievementFilter(), nowMillis())
    }
    val shown = result.visible
    val countText = remember(result) { activityAchievementCountText(result) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var consoleListOpen by remember { mutableStateOf(false) }
    var showListOpen by remember { mutableStateOf(false) }
    // The keyboard exists only with the header: fewer than two games leaves nothing to filter, and the keyboard goes with it. Everything that follows the keyboard (the pad's
    // host, the pad's routing, what is drawn) follows THIS and not the raw flag, so a keyboard that is gone is never what the pad is talking to; the effect below then
    // lets go of the flags, so a header that comes back does not bring a keyboard back with it.
    val keyboardShown = showHeader && keyboardOpen
    // The console picker blurs the page behind it (the Bottom's glass grammar). The ramp is the house OverlayMs clock, opening and closing: a close is the page arriving, never the
    // panel leaving. Under reduced motion it is a settled state from the first frame.
    val consoleOpen = showHeader && consoleListOpen
    val showOpen = showHeader && showListOpen
    val pickerOpen = consoleOpen || showOpen
    // A picker closes on a tap outside it, so the recent-app tile (drawn by the Presentation over this page) steps aside while one is open.
    BottomRecentAppTileCoverEffect(covered = pickerOpen)
    // A new epoch is a new ramp, born at zero: a picker closed because the page stopped being live (nothing was drawing to ease the blur away) leaves no blur behind.
    var pickerEpoch by remember { mutableIntStateOf(0) }
    // 08-15: a modal dissolves fully before the glass under it clears. The picker leaves on the sheet clock ([ActivityPickerFadeHost]); the page's blur and scrim hold for the
    // dissolve lead ([MimirMotion.OverlayDissolveCoverMs]) and then clear on their own clock, so the picker is never half legible over a half-unblurred page. A new epoch
    // holds nothing (its ramp is born at zero and must stay there).
    val pickerGlassHeld = remember(pickerEpoch) { mutableStateOf(false) }
    LaunchedEffect(pickerOpen, pickerEpoch) {
        if (pickerOpen) {
            pickerGlassHeld.value = true
        } else if (pickerGlassHeld.value) {
            // On the animation clock, like the fades it covers: a coroutine delay does not scale with the animator, so under a slowed one the glass would clear under a picker still
            // mostly opaque.
            mimirHold(MimirMotion.OverlayDissolveCoverMs)
            pickerGlassHeld.value = false
        }
    }
    val glassRamp = rememberMimirGlassProgress(visible = pickerOpen || pickerGlassHeld.value, identity = pickerEpoch)
    val glass: () -> Float = if (reducedMotion) {
        { if (pickerOpen) 1f else 0f }
    } else {
        glassRamp
    }
    SideEffect {
        glassProbe?.invoke(glass)
    }
    // The recent-unlocks strip shows only tiles that open a card, newest first, at most twelve; none at all hides it (it then takes no room).
    val cardGameIds = remember(snapshots) { snapshots.mapNotNullTo(HashSet<Int>()) { it.raGameId } }
    val stripUnlocks = remember(recent, cardGameIds) { activityRecentShown(recent, cardGameIds) }
    // A tapped tile: the jump is pending until the pager's list holds its game (a filter that hides it is cleared first), and the badge it opens rides to that card.
    var pendingJump by remember { mutableStateOf<ActivityRecentUnlock?>(null) }
    var badgeSelection by remember { mutableStateOf<ActivityBadgeSelection?>(null) }
    val selectionSerial = remember { IntArray(1) }
    val openRecent: (ActivityRecentUnlock) -> Unit = { unlock ->
        // An unlock whose game has no card (the lane changed under the strip) opens nothing, and breaks nothing.
        if (unlock.raGameId in cardGameIds) {
            keyboardOpen = false
            consoleListOpen = false
            showListOpen = false
            selectionSerial[0] += 1
            badgeSelection = ActivityBadgeSelection(unlock.raGameId, unlock.achievementId, activityRecentDetail(unlock), selectionSerial[0])
            pendingJump = unlock
            if (shown.none { it.raGameId == unlock.raGameId }) filterHolder.clearFilters()
        }
    }
    // The pad's walk: the page's own authority, and the host the pad acts through (refreshed every composition; read only when a press arrives).
    val padHost = remember { ActivityHubPadHost() }
    val pad = remember { ActivityHubPad(DefaultFocusAuthority(), padHost) }
    // Whether a finger is on the page right now: the card settle waits for it to lift (a list a finger holds between cards is the finger's to place).
    val fingerDown = remember { mutableStateOf(false) }
    // A count of the input the page takes (a finger landing or lifting, a pad press it consumed): the "more below" cue restarts its idle wait on every change.
    val interactionTick = remember { mutableIntStateOf(0) }
    var keyboardByPad by remember { mutableStateOf(false) }
    val keyboardHost = rememberMimirKeyboardHost(pad.authority, active = keyboardShown && keyboardByPad)
    LaunchedEffect(showHeader) {
        if (!showHeader) {
            keyboardOpen = false
            consoleListOpen = false
            showListOpen = false
            keyboardByPad = false
        }
    }
    val router = padRouter ?: remember { newActivityPadRouter() }
    val padScope = rememberCoroutineScope()
    CompositionLocalProvider(LocalActivityHubPad provides pad) {
    Box(
        Modifier.fillMaxSize().pointerInput(pad) {
            // A finger lands: the pad's ring goes dark until its next press. Watched on the way down and never consumed, so no touch changes meaning.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val down = event.changes.any { it.pressed }
                    if (down != fingerDown.value) interactionTick.intValue += 1
                    fingerDown.value = down
                    if (down) pad.onTouch()
                }
            }
        },
    ) {
        val pages = if (shown.isEmpty()) 1 else shown.size
        // Opened from Now Playing the hub stands on the running game's card from its first frame (the list is built at that index, not scrolled to it a frame later); any
        // other way in, or a game with no card, opens at the first.
        val focusStart = remember { focusGameId?.let { id -> shown.indexOfFirst { it.gameId == id } }?.takeIf { it > 0 } ?: 0 }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusStart)
        // Whatever the fling does, the list comes to rest with a card whole and aligned, whichever way it was swiped (see [ActivityCardSettleEffect]).
        ActivityCardSettleEffect(listState = listState, enabled = pages > 1, touching = { fingerDown.value })
        // The count is read through a state, not captured: the derived page is created once, and a game that arrives (or leaves) while the hub is open must change
        // what it clamps to. It is the NEAREST page, so it is the settled page whenever the snap has settled, and a shrink clamps it.
        val latestPages = rememberUpdatedState(pages)
        val current by remember {
            derivedStateOf {
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                val past = if (first != null && first.size > 0 && -first.offset * 2 >= first.size) 1 else 0
                (listState.firstVisibleItemIndex + past).coerceIn(0, latestPages.value - 1)
            }
        }
        // The ONE owner of the list's position. A different filter or order is a different list and opens at its first game; a tapped recent unlock takes the pager
        // to its game's card instead (once the filter lets that card be in the list), and the filter change it caused must not scroll the pager back to the top.
        val handledFilter = remember { arrayOfNulls<ActivityAchievementFilter>(1).also { if (focusStart > 0) it[0] = result.filter } }
        LaunchedEffect(result.filter, pendingJump, shown, showHeader) {
            // While the empty state stands in the pager's place there is no list to scroll, and a scroll asked of it would wait for a first layout and then run
            // late, after (and over) a jump made once the filter lets the pager back: so none is asked, and the list opens at the right place when it returns.
            val pagerComposed = !(showHeader && shown.isEmpty())
            val jump = pendingJump
            if (jump != null) {
                val target = shown.indexOfFirst { it.raGameId == jump.raGameId }
                if (target >= 0) {
                    listState.scrollToItem(target)
                    handledFilter[0] = result.filter
                    pendingJump = null
                } else if (jump.raGameId !in cardGameIds) {
                    pendingJump = null
                }
            } else if (pagerComposed && handledFilter[0] != result.filter) {
                handledFilter[0] = result.filter
                listState.scrollToItem(0)
            }
        }
        // Leaving the card the badge was opened for lets go of the request, so coming back finds the card as the player left it.
        LaunchedEffect(current) {
            val held = badgeSelection
            if (held != null && pendingJump == null && shown.getOrNull(current)?.raGameId != held.raGameId) badgeSelection = null
        }
        // What the pad can walk now, from what is on the page. A key names a control, never an index, so a re-list moves nothing it should not.
        val currentGame = shown.getOrNull(current)
        val currentBadges = remember(allDetails, currentGame?.raGameId) {
            val loaded = currentGame?.raGameId?.let { allDetails[it] } as? ActivityCardDetail.Loaded
            loaded?.progress?.achievements?.let { activityBadgeOrder(it) }.orEmpty()
        }
        val consoleItems = index.consoles
        val modalOpen = showHeader && (consoleListOpen || showListOpen)
        val ring = if (modalOpen && showListOpen) {
            ActivityPadRing(
                header = ActivityAchievementStatus.entries.map { listOf(ActivityPadKeys.showItem(it)) },
                entry = ActivityPadKeys.showItem(result.filter.status),
                modal = true,
            )
        } else if (modalOpen) {
            val selectedConsole = result.filter.console
            ActivityPadRing(
                header = listOf(listOf(ActivityPadKeys.consoleItem(null))) + consoleItems.map { listOf(ActivityPadKeys.consoleItem(it.name)) },
                entry = ActivityPadKeys.consoleItem(selectedConsole).takeIf { selectedConsole == null || consoleItems.any { it.name == selectedConsole } },
                modal = true,
            )
        } else {
            ActivityPadRing(
                header = if (showHeader) {
                    listOf(listOf(ActivityPadKeys.Search, ActivityPadKeys.Show, ActivityPadKeys.Console))
                } else {
                    emptyList()
                },
                recent = stripUnlocks.map { ActivityPadKeys.recent(it.achievementId) },
                extra = if (showHeader && shown.isEmpty()) listOf(listOf(ActivityPadKeys.ClearFilters)) else emptyList(),
                card = if (shown.isEmpty()) null else ActivityPadKeys.Card,
                badges = currentBadges.map { ActivityPadKeys.badge(it.id) },
            )
        }
        // A tap outside a picker, or a pick by touch, closes it the way B does: focus is handed back to the chip that opened it (the pad's own B returns the same key through the host).
        val closePicker: () -> Unit = {
            val back = if (showListOpen) ActivityPadKeys.Show else ActivityPadKeys.Console
            consoleListOpen = false
            showListOpen = false
            pad.authority.requestFocus(back)
        }
        // A on a key does what the key's tap does; where focus goes next is returned (a closed list hands it back to its chip).
        val activatePad: (FocusKey) -> FocusKey? = { key ->
            val status = ActivityAchievementStatus.entries.firstOrNull { ActivityPadKeys.showItem(it) == key }
            val unlock = stripUnlocks.firstOrNull { ActivityPadKeys.recent(it.achievementId) == key }
            val badge = currentBadges.firstOrNull { ActivityPadKeys.badge(it.id) == key }
            val console = consoleItems.firstOrNull { ActivityPadKeys.consoleItem(it.name) == key }
            val gameForBadge = currentGame?.raGameId
            when {
                key == ActivityPadKeys.Search -> {
                    consoleListOpen = false
                    showListOpen = false
                    keyboardByPad = true
                    keyboardOpen = true
                    null
                }
                key == ActivityPadKeys.Show -> {
                    keyboardOpen = false
                    consoleListOpen = false
                    showListOpen = !showListOpen
                    null
                }
                key == ActivityPadKeys.Console -> {
                    keyboardOpen = false
                    showListOpen = false
                    consoleListOpen = !consoleListOpen
                    null
                }
                key == ActivityPadKeys.ClearFilters -> {
                    filterHolder.clearFilters()
                    null
                }
                status != null -> {
                    filterHolder.setStatus(status)
                    showListOpen = false
                    ActivityPadKeys.Show
                }
                unlock != null -> {
                    openRecent(unlock)
                    null
                }
                badge != null && gameForBadge != null -> {
                    selectionSerial[0] += 1
                    badgeSelection = ActivityBadgeSelection(gameForBadge, badge.id, activityBadgeDetail(badge), selectionSerial[0])
                    null
                }
                // A on a card whose badges failed to load asks again, as a tap on its strip does.
                key == ActivityPadKeys.Card -> {
                    gameForBadge?.let(retryDetail)
                    null
                }
                key == ActivityPadKeys.consoleItem(null) -> {
                    filterHolder.setConsole(null)
                    consoleListOpen = false
                    ActivityPadKeys.Console
                }
                console != null -> {
                    filterHolder.setConsole(console.name)
                    consoleListOpen = false
                    ActivityPadKeys.Console
                }
                else -> null
            }
        }
        val padHandler: (ControllerAction) -> Boolean = { action ->
            interactionTick.intValue += 1
            if (keyboardShown) {
                val typing = keyboardHost
                if (typing != null) {
                    val handled = dispatchMimirKeyboardInput(pad.authority, MimirKeyboardFocusScope, action, typing.binding) { keyboardOpen = false }
                    // The keyboard answers only while its own scope is the authority's (its first frame has not pushed it yet, or it has just been taken away): B still closes
                    // it then, so the pad is never left talking to a keyboard that cannot hear it.
                    if (!handled && action == ControllerAction.CANCEL) keyboardOpen = false
                    handled || action == ControllerAction.CANCEL
                } else {
                    // Opened by touch: the keyboard is not the pad's. B closes it, and nothing else reaches the page behind it.
                    if (action == ControllerAction.CANCEL) keyboardOpen = false
                    true
                }
            } else {
                pad.dispatch(action)
            }
        }
        SideEffect {
            padHost.navigation = { latestNavigationEnabled.value }
            padHost.cardActionable = {
                when (currentGame?.raGameId?.let { allDetails[it] }) {
                    ActivityCardDetail.Unavailable, ActivityCardDetail.Stalled, ActivityCardDetail.Throttled -> true
                    else -> false
                }
            }
            padHost.onActivate = activatePad
            padHost.onPage = { delta ->
                val target = (current + delta).coerceIn(0, pages - 1)
                if (shown.isEmpty() || target == current) {
                    false
                } else {
                    padScope.launch { listState.scrollToItem(target) }
                    true
                }
            }
            padHost.onLeave = { if (latestNavigationEnabled.value) onBack() }
            padHost.onDismissModal = {
                val back = if (showListOpen) ActivityPadKeys.Show else ActivityPadKeys.Console
                consoleListOpen = false
                showListOpen = false
                back
            }
            router.handler = padHandler
            // The A waits one frame for the hat press that really came first (the router's own rule); the nav tick fires only when the focus really moved.
            router.confirmWaitsForPadFrame = { true }
            router.navMoveProbe = { pad.authority.state.value }
            pad.setRing(ring)
        }
        // While the page can really be driven (its pane shown, it the scene on show, the window up and lit) the Bottom floor hands it the pad, ahead of Main; otherwise the
        // route is released and the router reset, and the floor's next event is Main's (see [CompanionActivityPadRouteEffect]).
        CompanionActivityPadRouteEffect(router, padLive, windowLive)
        // The picker is the page's while the page can be driven. When it cannot (the pane parked, the scene swapped away, the window parked or dark) the picker is closed the way a B closes it, the blur
        // is cleared at once (a new ramp, born at zero) and focus goes back to the chip, so the page comes back as it was and not under a veil.
        ActivityLivenessLostEffect(padLive, windowLive) {
            if (consoleListOpen || showListOpen) {
                pickerEpoch += 1
                closePicker()
            }
        }
        // The page is the SCENE the console picker blurs (never the picker itself: a blur filters its own layer's content), so the blur sits on this column and the picker is its
        // sibling below. Only a page with the header has a picker, so only that page pays for the layer.
        Column(Modifier.fillMaxSize().then(if (showHeader) Modifier.mimirGlassBackdropProgress(glass) else Modifier)) {
            // The room above the card is 80 dp when there is no filter (the Back pill stands in it, and the ᛗ coin's corner stays clear of the card's count); with
            // the filter the pill leads a row that also holds the search and the sort, and the chips and the count follow.
            // The row also stops short of the recent-app tile laid over the page's top end (the slot's reserve, zero with no app leased).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 16.dp, end = 20.dp)
                    .bottomHeaderTrailingReserve()
                    .then(if (showHeader) Modifier.testTag(AchievementFilterHeaderTag) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlayingBackPill(enabled = navigationEnabled, onBack = { if (latestNavigationEnabled.value) onBack() }, label = backLabel)
                if (showHeader) {
                    ActivityFilterControls(
                        filter = result.filter,
                        keyboardOpen = keyboardOpen,
                        onOpenSearch = {
                            consoleListOpen = false
                            showListOpen = false
                            keyboardByPad = false
                            keyboardOpen = true
                        },
                        onClearSearch = { filterHolder.setQuery("") },
                        onShow = {
                            keyboardOpen = false
                            consoleListOpen = false
                            showListOpen = !showListOpen
                        },
                        onConsole = {
                            keyboardOpen = false
                            showListOpen = false
                            consoleListOpen = !consoleListOpen
                        },
                        consoleLook = result.filter.console?.let { index.consoleLooks[it] },
                    )
                }
            }
            if (showHeader) {
                ActivityFilterCountRow(
                    countText = countText,
                    pages = pages,
                    current = { current },
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = MimirSpacing.xs),
                    recent = stripUnlocks,
                    badgeSource = badgeSource,
                    onOpenRecent = openRecent,
                )
            } else if (stripUnlocks.isNotEmpty()) {
                // One game has no filter and no count row, but its unlocks still lead the page.
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = MimirSpacing.xs, bottom = MimirSpacing.xs)) {
                    ActivityRecentStrip(unlocks = stripUnlocks, badgeSource = badgeSource, onOpen = openRecent, modifier = Modifier.weight(1f))
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                if (showHeader && shown.isEmpty()) {
                    ActivityFilterEmpty(onClear = filterHolder::clearFilters, modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag(AchievementHubListTag),
                        flingBehavior = rememberSnapFlingBehavior(listState),
                        userScrollEnabled = pages > 1,
                    ) {
                        if (shown.isEmpty()) {
                            item(key = "unknown") {
                                Box(Modifier.fillParentMaxSize()) {
                                    MimirAchievementCard(
                                        state = unknownState,
                                        modifier = Modifier.fillMaxWidth().testTag(AchievementHubCardTag),
                                        reducedMotion = reducedMotion,
                                        // No Bottom route to Settings › Profile exists, so the card offers no setup action here.
                                        onOpenProfile = null,
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(shown, key = { _, snapshot -> "${snapshot.account}\u001f${snapshot.gameId}" }) { page, snapshot ->
                                val raGameId = snapshot.raGameId
                                // Only a card that is composed asks, once per composition of that card, and only after it has stayed ACTIVITY_CARD_ASK_DEBOUNCE_MS: a flick through many cards cancels the
                // cards it passes before they ask. The loader itself asks at most once per game per five minutes.
                                DisposableEffect(raGameId) {
                                    val release = raGameId?.let(holdDetail)
                                    onDispose { release?.invoke() }
                                }
                                LaunchedEffect(snapshot.account, raGameId) {
                                    delay(ACTIVITY_CARD_ASK_DEBOUNCE_MS)
                                    raGameId?.let(requestDetail)
                                }
                                Box(Modifier.fillParentMaxSize()) {
                                    ActivityAchievementPanel(
                                        snapshot = snapshot,
                                        detail = raGameId?.let { allDetails[it] },
                                        artResolver = artResolver,
                                        badgeSource = badgeSource,
                                        reducedMotion = reducedMotion,
                                        // The ring on the card is the page's one key for the card, drawn on the page that is showing; the others are only composed beside it.
                                        modifier = if (page == current) {
                                            Modifier.fillMaxSize().activityPadTarget(
                                                ActivityPadKeys.Card,
                                                MimirRingShape.Card(MimirSpacing.radiusCard),
                                                inset = MimirSpacing.xs,
                                                bringIntoView = false,
                                            )
                                        } else {
                                            Modifier.fillMaxSize()
                                        },
                                        badgeSelection = badgeSelection?.takeIf { it.raGameId == raGameId },
                                        onRetry = { raGameId?.let(retryDetail) },
                                    )
                                }
                            }
                        }
                    }
                }
                // The cards stack TOP TO BOTTOM and a sideways swipe goes nowhere: while there is a card below, a small chevron says so (and plays once as the page opens, and
                // again after the page has idled). It takes no touch, and the last card has none.
                ActivityMoreBelowCue(
                    visible = activityCueVisible(current, pages) && !(showHeader && shown.isEmpty()),
                    page = current,
                    reducedMotion = reducedMotion,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    activity = { interactionTick.intValue },
                    busy = { fingerDown.value || listState.isScrollInProgress },
                    onPlay = onCuePlay,
                )
            }
        }
        // The pickers' scrim, ONE for both, at page level on the page's own glass: it darkens and clears with the blur it sits on, whatever the picker above it is doing. It used
        // to live inside each picker and so was removed with it on the frame the picker closed, a luminance step under a page that was still blurred.
        if (showHeader) MimirGlassScrim(progress = glass)
        // The console picker, over the whole page: a glass panel on the blurred scene above (see [ActivityConsolePicker]). After the page so it is above it, before the keyboard (the two
        // are never open together: each closes the other).
        ActivityConsolePickerHost(
            open = consoleOpen,
            consoles = index.consoles,
            looks = index.consoleLooks,
            selected = result.filter.console,
            onPick = { picked ->
                filterHolder.setConsole(picked)
                closePicker()
            },
            onDismiss = closePicker,
            glass = glass,
            reducedMotion = reducedMotion,
        )
        ActivityShowPickerHost(
            open = showOpen,
            selected = result.filter.status,
            onPick = { picked ->
                filterHolder.setStatus(picked)
                closePicker()
            },
            onDismiss = closePicker,
            glass = glass,
            reducedMotion = reducedMotion,
        )
        ActivityFilterKeyboardHost(
            open = keyboardShown,
            reducedMotion = reducedMotion,
            query = result.filter.query,
            onQuery = filterHolder::setQuery,
            onDone = { keyboardOpen = false },
            keyboard = if (keyboardByPad) keyboardHost else null,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
}

/** The console picker, drawn over the whole page while it is open. Its own function so the condition has a full body. */
@Composable
private fun ActivityConsolePickerHost(
    open: Boolean,
    consoles: List<ActivityConsoleCount>,
    looks: Map<String, ActivityConsoleLook>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    glass: () -> Float,
    reducedMotion: Boolean,
) {
    ActivityPickerFadeHost(open = open, reducedMotion = reducedMotion) {
        ActivityConsolePicker(
            consoles = consoles,
            looks = looks,
            selected = selected,
            onPick = onPick,
            onDismiss = onDismiss,
            glass = glass,
            reducedMotion = reducedMotion,
            drawScrim = false,
        )
    }
}

/** The Show picker, drawn over the whole page while it is open. Its own function so the condition has a full body. */
@Composable
private fun ActivityShowPickerHost(
    open: Boolean,
    selected: ActivityAchievementStatus,
    onPick: (ActivityAchievementStatus) -> Unit,
    onDismiss: () -> Unit,
    glass: () -> Float,
    reducedMotion: Boolean,
) {
    ActivityPickerFadeHost(open = open, reducedMotion = reducedMotion) {
        ActivityShowPicker(selected = selected, onPick = onPick, onDismiss = onDismiss, glass = glass, reducedMotion = reducedMotion, drawScrim = false)
    }
}

/**
 * A picker that is composed while it is open and for the length of its fade out, on the house overlay clock ([rememberMimirFadeMount], which owns the mount, the fade and the
 * reduced-motion snap: this is its host with a touch shield). It arrives as it always has (its own panel entry; the page's scrim and blur ride the glass), so there is no second
 * fade on the way in ([MimirFadeMount]'s `restsWhole`); on the way out the panel dissolves instead of being cut off the commit frame (the picker had an entry ramp and no exit).
 * While it leaves it takes no touch and says nothing to a screen reader, as [MimirSheetHost] does for the sheets: a shield is laid over it, and the page behind is reached again
 * the moment the fade is done. A picker asked for again while it leaves fades back in from where it is.
 */
@Composable
internal fun ActivityPickerFadeHost(
    open: Boolean,
    reducedMotion: Boolean,
    /** Hands the test the host's fade (read in the layer, never in composition). */
    alphaProbe: (() -> Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val fade = rememberMimirFadeMount(visible = open, reducedMotion = reducedMotion, restsWhole = true)
    alphaProbe { fade.alpha }
    if (!open && !fade.mounted) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                // The fade is read in the layer, never in composition.
                .graphicsLayer { this.alpha = fade.alpha }
                .then(if (open) Modifier else Modifier.clearAndSetSemantics { }),
        ) { content() }
        if (!open) Box(Modifier.matchParentSize().mimirPageTouchShield())
    }
}

/**
 * The Mimir keyboard on the Bottom: no legend, anchored to the surface's bottom edge, over the cards. The search pill above it shows the query as it is typed and the
 * count the filter leaves. A keyboard the pad opened (from the search pill) is given its [keyboard] host, so the pad walks its keys and the focused one wears the
 * ring; one opened by touch has none, and looks as it always did. Its own function so the condition has a full body.
 */
@Composable
private fun ActivityFilterKeyboardHost(
    open: Boolean,
    reducedMotion: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onDone: () -> Unit,
    keyboard: MimirKeyboardHost?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    // The sheet tier: the keyboard slides in from its edge and fades on the one overlay clock, and leaves the way it came with its touches shielded, where it used to appear and
    // vanish in a single frame. The pad is given back the frame it closes (a null host drops the keyboard's focus binding), so the leaving sheet is never a second live focus graph.
    MimirSheetHost(visible = open, reducedMotion = reducedMotion, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(palette.surfaceBase.copy(alpha = 0.98f))
                .companionOwnsHorizontalDrag()
                .testTag(AchievementFilterKeyboardTag),
        ) {
            val host = keyboard.takeIf { open }
            MimirKeyboard(
                value = query,
                onValueChange = onQuery,
                onDone = onDone,
                controller = host?.controller,
                keyDecorator = host?.keyDecorator,
                targetLabel = "Search achievements",
                showControllerLegend = false,
            )
        }
    }
}

/** What the pad does on this page. The hub refreshes it every composition and the pad reads it only when a press arrives, so a press acts on the page as last drawn. */
private class ActivityHubPadHost : ActivityPadHost {
    var navigation: () -> Boolean = { true }
    var cardActionable: () -> Boolean = { false }
    var onActivate: (FocusKey) -> FocusKey? = { null }
    var onPage: (Int) -> Boolean = { false }
    var onLeave: () -> Unit = {}
    var onDismissModal: () -> FocusKey? = { null }

    override fun navigationEnabled(): Boolean = navigation()

    override fun cardActs(): Boolean = cardActionable()

    override fun activate(key: FocusKey): FocusKey? = onActivate(key)

    override fun page(delta: Int): Boolean = onPage(delta)

    override fun leave() = onLeave()

    override fun dismissModal(): FocusKey? = onDismissModal()
}

/**
 * Calls [onLost] each time the page stops being drivable: its pane parked ([LocalMimirMotionLive]), the workspace's swap carrying it away ([pageLive]), or the Bottom's window
 * parked behind the host or dark ([window]). These are the same three signals [CompanionActivityPadRouteEffect] holds the pad's route by, so whatever lets the route go also
 * lets the page's overlays go: a picker left open across a park would otherwise come back with its panel and blur still on screen and nothing to drive them.
 */
@Composable
private fun ActivityLivenessLostEffect(pageLive: Boolean, window: CompanionWindowLive, onLost: () -> Unit) {
    val paneLive = LocalMimirMotionLive.current
    val pageLiveState = rememberUpdatedState(pageLive)
    val latestOnLost = rememberUpdatedState(onLost)
    LaunchedEffect(paneLive, window) {
        val shown = snapshotFlow { paneLive.value && pageLiveState.value }
        combine(shown, window.liveFlow) { shownNow, windowLive -> shownNow && windowLive }
            .distinctUntilChanged()
            .collect { live -> if (!live) latestOnLost.value() }
    }
}
