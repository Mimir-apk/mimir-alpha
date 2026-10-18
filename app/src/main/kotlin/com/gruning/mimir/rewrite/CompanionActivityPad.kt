package com.gruning.mimir.rewrite

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.companionnative.BottomPadInput
import com.gruning.mimir.rewrite.core.companionnative.PadIdleGate
import com.gruning.mimir.rewrite.core.companionnative.idleWakeSamples
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The pad on the Activity achievement page.
 *
 * The page is touch-first and its windows are not focusable; the pad's keys land on the Bottom floor, which hands them (after the open menu, before Main) to
 * [CompanionActivityPadRoute] while the page is live. From there one [InputRouter] turns raw keys and hat samples into [ControllerAction]s (it also keeps the
 * hat-then-A order: an A that arrives ahead of the hat press before it waits one frame for it), and [ActivityHubPad] walks the page's keys through a
 * [FocusAuthority] (the page's own instance, as the music panel keeps its own: the one engine, never the platform's focus). The authority holds what is focused,
 * the rings read it, and every move is the explicit rule below, because a geometric search cannot cross a scrolling chip row, a pager and a lazy badge row.
 *
 * ## The walk
 *
 * Rows, top to bottom: the one top row (the search pill, the Show chip and the console chip); the recent-unlocks tiles (when there are any); the card; the card's
 * badges (when its detail has landed). Left and right walk a row and stop at its ends. Up and down change row and keep the column the player was walking in
 * (a row with fewer keys takes its last); down from the card enters the badges at the first, up from them returns to the card. **Left and right on the card page
 * the cards** (and L1 and R1 do from anywhere). A activates the focused key: the chip or tile does what its tap does, a badge opens its details, the card does
 * nothing. B steps back out: from a badge to the card, otherwise out of the page (the Back pill's own path). With the console list open the pad walks its items
 * and B closes it; with the keyboard open (opened from the search pill) the pad is the keyboard's, and B closes it.
 *
 * The ring is dark until the first pad press, and dark again after a touch; a press on a dark ring only lights it (at the search pill the first time, where it
 * was after a touch) and does nothing else, as the ᛗ menu's ring does.
 */
internal val ActivityPadScope = FocusScope("activity/hub")

/** Every key the page's walk knows. A key names a control, never an index, so a ring and its animation follow the control across a re-list. */
internal object ActivityPadKeys {
    val Search = FocusKey("activity/search")
    val Show = FocusKey("activity/show")
    val Console = FocusKey("activity/console")
    val ClearFilters = FocusKey("activity/clear-filters")
    val Card = FocusKey("activity/card")

    fun showItem(status: ActivityAchievementStatus) = FocusKey("activity/show-item/${status.name}")

    fun recent(achievementId: Int) = FocusKey("activity/recent/$achievementId")

    fun badge(achievementId: Int) = FocusKey("activity/badge/$achievementId")

    fun consoleItem(name: String?) = FocusKey("activity/console-item/${name ?: "all"}")
}

/**
 * What the pad can walk right now. [header] are the rows above the tiles, [extra] rows below them (the empty state's one action), [recent] the tiles, [card] the page's
 * card (null when no card is shown), [badges] the card's badges. A [modal] ring is a list (each key of [header] its own row): left and right do nothing, B closes it.
 */
internal data class ActivityPadRing(
    val header: List<List<FocusKey>> = emptyList(),
    val recent: List<FocusKey> = emptyList(),
    val extra: List<List<FocusKey>> = emptyList(),
    val card: FocusKey? = null,
    val badges: List<FocusKey> = emptyList(),
    /** Where the first press lands; the first key of the first row when null. */
    val entry: FocusKey? = null,
    val modal: Boolean = false,
) {
    val rows: List<List<FocusKey>> = buildList {
        header.filter { it.isNotEmpty() }.forEach { add(it) }
        if (recent.isNotEmpty()) add(recent)
        extra.filter { it.isNotEmpty() }.forEach { add(it) }
        card?.let { add(listOf(it)) }
        if (card != null && badges.isNotEmpty()) add(badges)
    }

    val firstKey: FocusKey? get() = entry ?: rows.firstOrNull()?.firstOrNull()
}

/** What the page does when the pad acts; the hub implements it over its own state, so the walk can be tested without a screen. */
internal interface ActivityPadHost {
    /** False while the dock holds navigation off: a press is then consumed and does nothing, as a touch on the Back pill does. */
    fun navigationEnabled(): Boolean

    /** A on [key]. Returns where focus goes next, or null to leave it. */
    fun activate(key: FocusKey): FocusKey?

    /** Pages the cards by [delta] (-1 or 1); false at an end of the list. */
    fun page(delta: Int): Boolean

    /** B past the page: leaves it, as the Back pill does. */
    fun leave()

    /** B on a modal list: closes it. Returns where focus goes next, or null to leave it. */
    fun dismissModal(): FocusKey?

    /** Whether A on the CARD itself does something now (a card whose badges failed to load: A tries again). False leaves the card as a place to stand, as it always was. */
    fun cardActs(): Boolean = false
}

/**
 * The walk itself: pure over a [FocusAuthority] and an [ActivityPadHost]. [setRing] is called by the page whenever what is walkable changes; [dispatch] by the
 * router for each pad action; [onTouch] when a finger lands.
 */
internal class ActivityHubPad(
    val authority: FocusAuthority,
    private val host: ActivityPadHost,
    val scope: FocusScope = ActivityPadScope,
    /**
     * Main's page (a controller-first modal): the ring is lit from the first frame, where the Bottom's is dark until its first press. A touch still darkens it, and the press
     * after a touch only lights it again.
     */
    startEngaged: Boolean = false,
    /**
     * True when [authority] is this pad's own (the Bottom's page): the scope is pushed here, once. False when the authority is shared (Main's): the page pushes the scope
     * when it is committed and discards it when it leaves, and [close] lets go of what the pad registered.
     */
    pushesScope: Boolean = true,
) {
    /** True from the first pad press until the next touch: the rings draw only while it is. Snapshot state, read in composition. */
    var engaged: Boolean by mutableStateOf(startEngaged)
        private set

    private var ring: ActivityPadRing = ActivityPadRing()
    private var registered: Set<FocusKey> = emptySet()
    private var column: Int = 0
    private var everLit: Boolean = startEngaged
    private val startsLit: Boolean = startEngaged
    private var landed: Boolean = false

    init {
        if (pushesScope) authority.pushScope(scope)
    }

    /** Lets go of every slot this pad registered (a shared authority keeps nothing of a page that has left). */
    fun close() {
        if (registered.isNotEmpty()) authority.unregisterVirtualAll(registered)
        registered = emptySet()
        ring = ActivityPadRing()
    }

    /** A finger landed: the pad's ring goes dark, and its place is kept for the next press. */
    fun onTouch() {
        if (engaged) engaged = false
    }

    /**
     * Takes what is walkable now. Every key gets a virtual slot (a monotone grid, so a badge the lazy row has not composed can still take focus and the
     * authority's own fallback is sane); a focused key that is no longer walkable is moved to the card (else the first key) BEFORE its slot goes, so the
     * authority never re-homes it on its own.
     */
    fun setRing(next: ActivityPadRing) {
        if (next == ring && registered.isNotEmpty()) return
        ring = next
        next.rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, key ->
                authority.registerVirtual(key, scope, FocusBounds(c * 100f, r * 100f, c * 100f + 90f, r * 100f + 90f))
            }
        }
        val now = next.rows.flatten().toSet()
        val focused = ownFocus()
        if (focused != null && focused !in now) {
            (next.card ?: next.firstKey)?.let { authority.requestFocus(it) }
        }
        val gone = registered - now
        if (gone.isNotEmpty()) authority.unregisterVirtualAll(gone)
        registered = now
        // Main's page lands lit on its first badge, once (the shared authority's own landing on a scope may pick any registered key), and again whenever nothing of this scope is focused.
        if (startsLit && (!landed || ownFocus() == null)) {
            next.firstKey?.let {
                authority.requestFocus(it)
                landed = true
            }
        }
    }

    /** One pad action. True when the page took it (the router consumes every pad action either way). */
    fun dispatch(action: ControllerAction): Boolean {
        if (!host.navigationEnabled()) return true
        return when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN, ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                litFocus()?.let { move(it, action) }
                true
            }
            ControllerAction.CONFIRM -> {
                val focused = litFocus()
                if (focused != null && (focused != ring.card || host.cardActs())) host.activate(focused)?.let { request(it) }
                true
            }
            ControllerAction.CANCEL -> {
                back()
                true
            }
            ControllerAction.PAGE_LEFT -> {
                host.page(-1)
                true
            }
            ControllerAction.PAGE_RIGHT -> {
                host.page(1)
                true
            }
            else -> false
        }
    }

    private fun ownFocus(): FocusKey? = authority.state.value.takeIf { it.scope == scope }?.focused

    private fun request(key: FocusKey) = authority.requestFocus(key)

    /** The key a press acts on, or null. A press on a dark ring only lights it (at the first key the first time, where it was after a touch) and acts on nothing. */
    private fun litFocus(): FocusKey? {
        if (!engaged) {
            engaged = true
            val held = ownFocus()
            if (!everLit || held == null || held !in registered) {
                column = 0
                ring.firstKey?.let { request(it) }
            }
            everLit = true
            return null
        }
        val held = ownFocus()
        if (held != null && held in registered) return held
        column = 0
        ring.firstKey?.let { request(it) }
        return null
    }

    private fun move(focused: FocusKey, action: ControllerAction) {
        val rows = ring.rows
        val r = rows.indexOfFirst { focused in it }
        if (r < 0) return
        val row = rows[r]
        val c = row.indexOf(focused)
        val badgeRow = ring.badges.isNotEmpty() && row === ring.badges
        when (action) {
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
                val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
                if (!ring.modal && focused == ring.card) {
                    host.page(delta)
                } else if (c + delta in row.indices) {
                    if (!badgeRow) column = c + delta
                    request(row[c + delta])
                }
            }
            else -> {
                val target = r + (if (action == ControllerAction.NAV_UP) -1 else 1)
                if (target in rows.indices) {
                    // The column the player is walking in goes with them; a lone key (the card) and the badges (own order) do not set it.
                    if (row.size > 1 && !badgeRow) column = c
                    val next = rows[target]
                    val toBadges = ring.badges.isNotEmpty() && next === ring.badges
                    request(if (toBadges) next.first() else next[column.coerceIn(0, next.size - 1)])
                }
            }
        }
    }

    private fun back() {
        if (!host.navigationEnabled()) return
        if (ring.modal) {
            host.dismissModal()?.let { request(it) }
            return
        }
        val focused = if (engaged) ownFocus() else null
        if (focused != null && focused in ring.badges) {
            ring.card?.let { request(it) }
            return
        }
        host.leave()
    }
}

/**
 * The floor's way to the page's pad. The floor asks it after the open menu and before Main's sink, so a pad event that lands there while the page is live walks the
 * page instead of Main's shelf. Registered only while the page can be driven ([CompanionActivityPadRouteEffect]: a parked pane, a parked or dark window gives the pad back), with the exact lambdas released, so a stale release
 * never unplugs a successor. Main thread only; each call reads its `@Volatile` field once.
 */
internal object CompanionActivityPadRoute {
    @Volatile
    private var keys: ((android.view.KeyEvent) -> Boolean)? = null

    @Volatile
    private var motion: ((android.view.MotionEvent) -> Boolean)? = null

    /**
     * Taking or giving back the pad moves [BottomPadInput.holdEpoch], the idle hold cap's boundary: a held key or stick another lane (Main's) has been earning activity credit for starts
     * fresh after it. It is not a change of pad owner ([BottomPadInput.ownerEpoch] stays put), so a wake press Main has in progress is unaffected.
     */
    fun register(keys: (android.view.KeyEvent) -> Boolean, motion: (android.view.MotionEvent) -> Boolean) {
        this.keys = keys
        this.motion = motion
        BottomPadInput.noteHoldBoundary()
    }

    fun release(keys: (android.view.KeyEvent) -> Boolean, motion: (android.view.MotionEvent) -> Boolean) {
        if (this.keys === keys || this.motion === motion) BottomPadInput.noteHoldBoundary()
        if (this.keys === keys) this.keys = null
        if (this.motion === motion) this.motion = null
    }

    /** True when the page consumed [event]; false (and nothing done) when no live page is registered. */
    fun routeKey(event: android.view.KeyEvent): Boolean = route(keys, event)

    fun routeMotion(event: android.view.MotionEvent): Boolean = route(motion, event)

    /** The rule itself, generic over the event so it can be tested without a window. */
    fun <E : Any> route(sink: ((E) -> Boolean)?, event: E): Boolean = sink?.invoke(event) ?: false
}

/**
 * Holds the page's route for [router] for exactly as long as the page can really be driven, and gives it back the moment it cannot: the route released and the router
 * reset (the held keys, the pending A, the hat repeat and the shared directional pace), so the floor's next pad event reaches Main and nothing the page was in the middle of
 * runs late. The page can be driven while all of these hold, and they are the whole list:
 *
 * - its pane is the shown one, not parked ([LocalMimirMotionLive], D24);
 * - it is the scene being shown, not one the swap is carrying away ([pageLive]);
 * - the Bottom's window is up and lit: not parked or hidden behind another app, not dismissed, the screen not off ([CompanionWindowLive]).
 *
 * The window parks WITHOUT disposing the hub or parking its pane (`parkBehindSecondaryHost` hides the retained window and moves its lifecycle down, which stops its
 * frame clock), so neither of the first two can see it; the third is read from the Presentation's own parked state. This effect follows it through flows, which need
 * no recomposition, so it still runs for a window whose frame clock is stopped; and the lambdas it registers ask [CompanionWindowLive.isLive] again on every event, so
 * the event that follows a park is already Main's, before the effect has run. Coming back is the same flip the other way: the route is taken again on the first
 * live moment (a reveal), never before. A touch needs none of this: it reaches the window itself.
 */
@Composable
internal fun CompanionActivityPadRouteEffect(router: InputRouter, pageLive: Boolean, window: CompanionWindowLive) {
    val paneLive = LocalMimirMotionLive.current
    val pageLiveState = rememberUpdatedState(pageLive)
    LaunchedEffect(router, paneLive, window) {
        val shown = snapshotFlow { paneLive.value && pageLiveState.value }
        combine(shown, window.liveFlow) { shownNow, windowLive -> shownNow && windowLive }
            .distinctUntilChanged()
            .collectLatest { live -> if (live) holdActivityPadRoute(router, window) }
    }
}

/** Registers the route with the exact lambdas it later releases (a stale release never unplugs a successor), until cancelled; then releases and resets. */
private suspend fun holdActivityPadRoute(router: InputRouter, window: CompanionWindowLive) {
    // This route sits ahead of Main's sink, so Main's idle rule never sees what it consumes: every press it is offered counts as activity there (the existing idle controller
    // re-arms, and is the only thing that wakes or dims), and the press that wakes a dimmed screen is the wake press and nothing else (PadIdleGate, Main's own rule).
    val idle = PadIdleGate(interaction = MimirMainInputSink::noteInteraction, onWake = router::reset, ownership = { BottomPadInput.ownerEpoch })
    val keys: (android.view.KeyEvent) -> Boolean = { event ->
        window.isLive &&
            idle.key(
                down = event.action == android.view.KeyEvent.ACTION_DOWN,
                up = event.action == android.view.KeyEvent.ACTION_UP,
                keyCode = event.keyCode,
            ) { router.onKeyEvent(event) }
    }
    val motion: (android.view.MotionEvent) -> Boolean = { event ->
        window.isLive &&
            idle.motion(event.idleWakeSamples()) { permitted -> router.onMotionEvent(event, permitted) }
    }
    CompanionActivityPadRoute.register(keys, motion)
    try {
        awaitCancellation()
    } finally {
        CompanionActivityPadRoute.release(keys, motion)
        router.reset()
    }
}

/** The router the page's pad runs through: Main's own pattern on the Bottom (hat repeat, the frame-ordered A, the motion gate), for [InteractionRoot.COMPANION]. */
internal fun newActivityPadRouter(): InputRouter {
    val main = Handler(Looper.getMainLooper())
    return InputRouter(
        interactionRoot = InteractionRoot.COMPANION,
        interactionMotionGate = CompanionProcessRuntime.motionGate,
        hatRepeatTicker = HatRepeatTicker { delayMs, tick ->
            val task = Runnable(tick)
            main.postDelayed(task, delayMs)
            val cancel: () -> Unit = { main.removeCallbacks(task) }
            cancel
        },
        hatRepeatClock = SystemClock::uptimeMillis,
        // An A waits for the frame's pad input, so a hat press that really came first still moves the ring before the A acts (InputRouter.confirmWaitsForPadFrame).
        padFrameScheduler = PadFrameScheduler { block -> Choreographer.getInstance().postFrameCallback { block() } },
    )
}

/** The page's pad, for the composables inside the hub; null everywhere else (previews, the card on its own), where every target below is a no-op. */
internal val LocalActivityHubPad = compositionLocalOf<ActivityHubPad?> { null }

/**
 * Makes this element a stop on the page's walk: registers it with the page's authority under [key] (never the platform's focus) and draws the Mimir ring on it
 * while it is the pad's focus. Goes FIRST in the element's chain, so the ring is drawn outside its clip. [inset] draws the ring inside the bounds by that much,
 * for an element a scrolling or clipping parent would cut. [bringIntoView] false for the card, which the pager places itself.
 */
@Composable
internal fun Modifier.activityPadTarget(
    key: FocusKey,
    shape: MimirRingShape,
    inset: Dp = 0.dp,
    bringIntoView: Boolean = true,
): Modifier {
    val pad = LocalActivityHubPad.current ?: return this
    val state = pad.authority.state.collectAsState()
    val focused by remember(state, key, pad) {
        derivedStateOf { pad.engaged && state.value.scope == pad.scope && state.value.focused == key }
    }
    return this
        .mimirFocusable(pad.authority, key, pad.scope, autoBringIntoView = bringIntoView)
        .mimirSelectionRing(focused = focused, identity = key, shape = shape, inset = inset)
}

/**
 * Whether [key] is the pad's focus right now (false with no page pad: previews, the card on its own). For a control whose focus is registered on a BIGGER box than the plate it
 * shows (a 48 dp hit box around a shorter etched plate, registered with [activityPadStop]): the ring goes on the PLATE itself with no inset, BEFORE the plate's etched surface
 * (an inset ring on the 48 dp box floated a few dp off the metal, a double rim), and the surface gets the same value as `focused`, so its rim hands over to the ring.
 */
@Composable
internal fun activityPadFocused(key: FocusKey): Boolean {
    val pad = LocalActivityHubPad.current ?: return false
    val state = pad.authority.state.collectAsState()
    val focused by remember(state, key, pad) {
        derivedStateOf { pad.engaged && state.value.scope == pad.scope && state.value.focused == key }
    }
    return focused
}

/**
 * The registering half of [activityPadTarget]: makes this element a stop on the page's walk under [key] (the page's authority, never the platform's focus) and
 * draws nothing. For a tile that draws its own ring ABOVE its art ([ActivityBadgeTile]): a ring painted by [activityPadTarget] sits behind the element's content, so
 * it is hidden by any opaque art the element holds. A null [key], or a host with no page pad (previews, the card on its own), adds nothing. [revealOutset] grows
 * the rect the walk brings into view, so a ring that reaches past the element is not left at a scroll edge.
 */
@Composable
internal fun Modifier.activityPadStop(key: FocusKey?, bringIntoView: Boolean = true, revealOutset: Dp = 0.dp): Modifier {
    val pad = LocalActivityHubPad.current
    if (key == null || pad == null) return this
    return this.mimirFocusable(pad.authority, key, pad.scope, autoBringIntoView = bringIntoView, revealOutset = revealOutset)
}

/** Whether the pad's ring is lit on [key] right now: engaged (no touch since the last press), this page's scope, and that key focused. False for a null key or no pad. */
@Composable
internal fun rememberActivityPadFocused(key: FocusKey?): Boolean {
    val pad = LocalActivityHubPad.current
    if (key == null || pad == null) return false
    val state = pad.authority.state.collectAsState()
    val focused by remember(state, key, pad) {
        derivedStateOf { pad.engaged && state.value.scope == pad.scope && state.value.focused == key }
    }
    return focused
}

/** True while the pad's ring is showing anywhere on the page (a press since the last touch); false with no pad. A touch darkens it, and the touch's selection takes its place. */
@Composable
internal fun rememberActivityPadEngaged(): Boolean = LocalActivityHubPad.current?.engaged ?: false

/** The key the pad has lit (the ring is on), or null: for a lazy row that scrolls its focused tile into view. */
@Composable
internal fun rememberActivityPadLit(): FocusKey? {
    val pad = LocalActivityHubPad.current ?: return null
    val state = pad.authority.state.collectAsState()
    val lit = state.value
    return if (pad.engaged && lit.scope == pad.scope) lit.focused else null
}
