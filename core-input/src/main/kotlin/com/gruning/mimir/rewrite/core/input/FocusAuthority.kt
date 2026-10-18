package com.gruning.mimir.rewrite.core.input

import kotlinx.coroutines.flow.StateFlow

/**
 * A stable, app-assigned identity for a focusable element. Deliberately a typed value class over
 * [String] so a raw string can never be mistaken for a focus key (same discipline as the domain ids
 * in `core-model`). The app mints these — e.g. `"shelf/ps1"`, `"settings/rail/library"`,
 * `"picker/option/3"` — and they are stable across recompositions so focus survives a re-render.
 */
@JvmInline
value class FocusKey(val value: String) {
    init {
        require(value.isNotBlank()) { "FocusKey must not be blank" }
    }
}

/**
 * A named region of the focus graph — the scope within which directional navigation resolves. Only
 * ONE scope is active at a time (a modal picker pushes a new scope; closing it pops back). This is the
 * structural fix for the old product's "stale `.is-focused` in a hidden view traps the D-pad" class of
 * bugs : focus can only ever land inside the active scope.
 */
@JvmInline
value class FocusScope(val value: String) {
    init {
        require(value.isNotBlank()) { "FocusScope must not be blank" }
    }
    companion object {
        /** The always-present root scope (the app shell). */
        val Root = FocusScope("root")
    }
}

/**
 * An immutable snapshot of "what is focused, right now" — the single truth every surface renders from.
 *
 * @property scope the currently active focus scope; directional moves resolve only within it.
 * @property focused the focused element in the active scope, or null when the scope is momentarily empty.
 */
data class FocusState(
    val scope: FocusScope,
    val focused: FocusKey?,
)

/**
 * **THE single source of focus truth for the entire app.**
 *
 * Mimir owns focus on every surface through exactly one authority. In the old product this was
 * CSS `.is-focused` managed by JS, forever
 * fighting the WebView's own native DOM-focus nav. In Mimir Alpha there is no second focus engine to
 * fight: this authority is the only thing that decides what's focused, the platform is never asked to
 * move focus directionally, and [InputAuthority] guarantees directional input is never passed through
 * to a platform focus engine (rule 4 of its contract).
 *
 * ### Invariants an implementation guarantees
 * - **Single truth.** [state] is the only place focus is read from. No Composable holds a competing
 *   "is this focused" boolean; each asks the authority (via `Modifier.mimirFocusable`, below).
 * - **Scoped.** Focus always sits inside the active [FocusScope]. Pushing a modal scope
 *   ([pushScope]) makes it the resolution universe; [popScope] restores the previous scope AND the
 *   element that was focused there (focus memory — the old product's per-shelf-mode focus memory).
 * - **Registration-bounded.** Only registered ([register]) keys in the active scope can receive focus;
 *   directional resolution ([moveFocus]) can only land on a registered neighbor. A key that leaves the
 *   tree ([unregister]) can never remain focused — the structural cure for the hidden-view D-pad trap.
 * - **Directional resolution is the app's job, not the platform's.** [moveFocus] takes a logical
 *   [ControllerAction] direction (already normalized by [InputAuthority]) and picks the geometric
 *   neighbor among registered keys. The platform focus system is never consulted.
 */
interface FocusAuthority {

    /** Observable single source of focus truth. Composables collect this; nothing else stores focus. */
    val state: StateFlow<FocusState>

    /**
     * Register a focusable element with the authority under [key], in [scope], with its on-screen
     * [bounds] (used by [moveFocus] to pick geometric neighbors). Re-registering the same key updates
     * its bounds (e.g. after a scroll/layout). Call from the element's composition; pair with [unregister].
     */
    fun register(key: FocusKey, scope: FocusScope, bounds: FocusBounds)

    /**
     * Register a persistent, virtual focus slot for a lazy item that may not currently be composed.
     *
     * Lazy containers only compose the visible window, but controller focus still needs to traverse the
     * whole ordered list. A virtual registration keeps that item in the focus graph; when it becomes
     * focused, the UI scrolls it into view and the normal [register] call from the composed element can
     * update its physical bounds. [unregister] removes only the physical registration, while
     * [unregisterVirtual] removes this virtual slot.
     */
    fun registerVirtual(key: FocusKey, scope: FocusScope, bounds: FocusBounds) {
        register(key, scope, bounds)
    }

    /** Remove [key] from the focus graph. If it was focused, focus falls to the nearest sibling (or null). */
    fun unregister(key: FocusKey)

    /** Remove a virtual slot registered by [registerVirtual]. */
    fun unregisterVirtual(key: FocusKey) {
        unregister(key)
    }

    /**
     * Remove many virtual slots at once, as [unregisterVirtual] does for each. If the focused key is among them,
     * focus falls ONCE, after every removal, to the surviving sibling nearest to it (or null) -- never through a
     * chain of siblings that are themselves about to go. A grid of 5,500 slots clearing one by one re-homed focus
     * on each removal, and each re-home scanned every entry (measured on RG Rotate).
     */
    fun unregisterVirtualAll(keys: Collection<FocusKey>) {
        keys.forEach(::unregisterVirtual)
    }

    /** Explicitly focus [key] (e.g. a tap, or route-landing "focus the top item"). No-op if unregistered. */
    fun requestFocus(key: FocusKey)

    /**
     * Move focus in the given normalized direction ([ControllerAction.isDirectional] must be true) by
     * choosing the best-scoring registered neighbor within the active scope. Returns true if focus
     * moved. Returns false at an edge — the caller decides whether an edge means "wrap", "scroll the
     * container", or "hand to the shell" (e.g. rail ↔ grid), it is NOT delegated to the platform.
     */
    fun moveFocus(direction: ControllerAction): Boolean

    /**
     * The keys currently registered in [scope], in registration (insertion) order. The render registers the
     * full navigable row (even the off-screen wrap peeks, virtually), so this is the source of truth for an
     * ordered focus RING when a caller's own copy of the list may be empty or stale. Default empty — test
     * doubles that don't track registration may ignore it.
     */
    fun registeredKeysInScope(scope: FocusScope): List<FocusKey> = emptyList()

    /** Push a new active scope (a modal/picker). Remembers the outgoing scope's focused key. */
    fun pushScope(scope: FocusScope)

    /** Pop back to the previous scope and restore the element that was focused there (focus memory). */
    fun popScope()

    /**
     * Remove [scope] from the scope stack WITHOUT transferring focus, for a binding whose surface was
     * dismissed while a DIFFERENT scope pushed after it is still active.
     *
     * The named case: `MimirKeyboardFocusBinding.exit()` (core-input/MimirKeyboardFocus.kt). The keyboard
     * pushes `MimirKeyboardFocusScope` on [enter][com.gruning.mimir.rewrite.core.input]; if a
     * GLOBAL_UTILITY/GLOBAL_QUICK_LAUNCH press is intercepted before the keyboard's own dispatch sees it,
     * the surface it opens (e.g. Utility/Brightness) can push ITS scope on top of the still-open
     * keyboard, and only afterwards does the host's `DisposableEffect` dispose and call `exit()`. At that
     * point the keyboard's scope is no longer active, and [popScope] cannot remove it without also
     * popping the covering scope out from under whatever is now using it. Calling this instead removes
     * exactly the keyboard's entry and nothing else.
     *
     * If [scope] IS the active scope, this is exactly [popScope] — focus transfers per that contract.
     * If [scope] is buried under another active scope, that active scope and its focus are left
     * completely alone: no [state] emission happens at all, not even one carrying the same values,
     * because nothing above [scope] should ever observe a transition for a scope it never knew was
     * underneath it. If [scope] is not on the stack (e.g. `exit()` races a `DisposableEffect` teardown
     * and runs twice), this is a no-op, not an error — a leaked scope must never become a crash.
     *
     * NOT a general "reach into the middle of the stack" tool. The one sanctioned caller is a binding
     * discarding its OWN scope after its surface is already gone; anything else wanting this is a sign
     * that surface should have popped its own scope before letting another one get pushed, which is the
     * actual invariant to restore. Default implementation only covers the active-scope case (so an
     * implementation or test double that hasn't opted in keeps today's behavior and keeps compiling);
     * [DefaultFocusAuthority] overrides it with the real mid-stack removal.
     */
    fun discardScope(scope: FocusScope) {
        if (state.value.scope == scope) popScope()
    }

    /**
     * Set the remembered focus for [scope] WITHOUT changing the ACTIVE focus, so a later [popScope] back into
     * [scope] lands on [key]. Used when a child scope changes what its parent should return to while the child
     * is active — e.g. switching platform inside the gamelist updates where the shelf lands on Back, so the
     * shelf comes back on the platform you were VIEWING, not the one you opened from (no land-then-snap jank).
     * Default no-op for doubles that don't track focus memory.
     */
    fun rememberFocusForScope(scope: FocusScope, key: FocusKey) {}

    /**
     * The key focus memory would land on for [scope], or null (HP-9b): lets a surface render its FIRST
     * frame already showing the category focus will seed post-layout, instead of painting the default and
     * visibly swapping 1-2 frames later. Default null for doubles that don't track focus memory.
     */
    fun rememberedFocusIn(scope: FocusScope): FocusKey? = null

    /**
     * Drop the active scope's focus to null WITHOUT unregistering anything. Used before a surface swaps its
     * whole focus ring (e.g. switching platform inside the gamelist) so the incoming list lands cleanly on its
     * first item — instead of the geometric fallback re-landing on the SAME index in the new ring, which
     * flashes the wrong item then snaps . Default no-op.
     */
    fun clearFocus() {}
}

/**
 * ## The two-channel focus law (policy 2026-08-15, refined the same day on device)
 *
 * State and ring visibility use separate channels when a pushed scope takes over —
 * Utility/Brightness, a picker, or the quick-rail dock:
 *
 * - **STATE is conserved.** Scale, seat/centering, hero role, alpha, which item the surface considers
 *   selected, which pane is shown. The covered surface must not re-derive these from the now-inactive
 *   scope, or it collapses to its unfocused rest pose mid-transition. That collapse WAS the reported bug.
 *   → [renderedFocusIn] / [isRenderedFocusIn] (this pair).
 * - **The RING dies with the scope.** The focus ring, lit dock seat, focus background/border, focus accent
 *   — anything whose whole job is to say *"input lands here"*. It goes when input leaves and returns when
 *   input returns. → [litFocusIn] / [isLitFocusIn].
 *
 * The first cut drove both channels and lit a ring on a surface that no longer had input.
 * The resulting two-ring ghost trail was reported on device. Hence the split.
 * **Classify by CONSUMER, never by call site.** The question is never "is this surface covered" — it is
 * "what does this boolean drive". One `focused` flag feeding both a scale and a ring is not one channel
 * with two effects; it is two channels sharing a variable, and it must be split (see
 * `PlatformHeroTile.ringLit`, `SettingsHome.activeRailKey` vs `litRailKey`).
 *
 * ---
 *
 * [renderedFocusIn] returns the live focused key while [scope] is active, else
 * [FocusAuthority.rememberedFocusIn] — so a surface that stays composed under a pushed child scope keeps
 * its geometry exactly as it was the frame before the push. Prior art already in the tree before this
 * helper existed: `MimirAppHost.homeContentFocusKey`, `LibraryScreen.gamelistRenderedFocus`'s
 * `authority.rememberedFocusIn(GamelistScope)` fallback, `SettingsHome.activeRailKey`.
 *
 * [current] must be the CALLER's own already-collected [FocusAuthority.state] (`by state.collectAsState()`
 * at the top of the surface) — reading [FocusAuthority.state] fresh inside this function would not be a
 * Compose read the caller subscribed to, so the value would never update. There is no defaulted overload
 * on purpose: a silent default reading `.value` would compile and then go stale on-device.
 *
 * RENDER USE ONLY. [FocusAuthority.rememberedFocusIn] is a plain map read, not the live dispatch truth —
 * this must never decide whether input routes somewhere, whether a modal is "open", or gate a `popScope`/
 * `LaunchedEffect` side effect. Those stay on the live [FocusState.scope] check they already use.
 */
fun FocusAuthority.renderedFocusIn(scope: FocusScope, current: FocusState): FocusKey? =
    if (current.scope == scope) current.focused else rememberedFocusIn(scope)

/** True when [key] is the key [scope] should currently paint in its CONSERVED state — see [renderedFocusIn]. */
fun FocusAuthority.isRenderedFocusIn(scope: FocusScope, key: FocusKey, current: FocusState): Boolean =
    renderedFocusIn(scope, current) == key

/**
 * The ring channel of the two-channel law documented on [renderedFocusIn]: the key [scope] should paint a
 * focus RING around, and **null the instant [scope] stops being the active scope** — no remembered
 * fallback, by design. A ring says "input lands here"; a scope that does not own input must not claim it.
 *
 * Deliberately NOT `renderedFocusIn` with a flag, and deliberately a named helper rather than an inline
 * `current.scope == scope && current.focused == key`: the inline form is what a future edit "fixes" into
 * the conserved form while chasing the snap bug, which is how the ghost trail comes back. Pick the helper
 * whose name matches the channel and the choice is documented at the call site for free.
 *
 * There is no motion here on purpose. The ring simply stops being painted, exactly as it did before focus
 * conservation existed. The reported defect was the TILE collapsing with the ring, not the ring
 * leaving. Softening the exit would be a separate motion decision, not the focus-conservation fix.
 *
 * [current] is the caller's own collected [FocusAuthority.state], same contract as [renderedFocusIn].
 */
fun FocusAuthority.litFocusIn(scope: FocusScope, current: FocusState): FocusKey? =
    if (current.scope == scope) current.focused else null

/** True when [key] is the key [scope] should currently paint a focus RING around — see [litFocusIn]. */
fun FocusAuthority.isLitFocusIn(scope: FocusScope, key: FocusKey, current: FocusState): Boolean =
    litFocusIn(scope, current) == key

/**
 * On-screen bounds of a focusable element, in the same pixel space for every element in a scope, used
 * by [FocusAuthority.moveFocus] to score directional neighbors (project onto the travel axis, prefer
 * the closest element in the requested direction with the smallest cross-axis offset). A plain data
 * class (no Compose types) so the neighbor-picking geometry is JVM-unit-testable off-device.
 */
data class FocusBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}
