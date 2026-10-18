package com.gruning.mimir.rewrite.core.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Reference in-memory implementation of the [FocusAuthority] single-source-of-truth contract.
 *
 * It is intentionally simple and dependency-free (a map of registered elements + a scope stack + a
 * [StateFlow]) so the invariants in [FocusAuthority] are demonstrable and the geometry in [moveFocus]
 * is unit-testable off-device. This is the shell the feature surfaces build on; a production version
 * may add scroll-into-view and richer edge policies, but the authority-of-truth semantics are here.
 */
/** Parked as a scope's remembered focus by [DefaultFocusAuthority.clearFocus] to suppress auto-seed; matches no real element. */
private val ClearedFocusSentinel = FocusKey("__mimir_focus_cleared__")

class DefaultFocusAuthority : FocusAuthority {

    private data class Entry(
        val scope: FocusScope,
        val physicalBounds: FocusBounds? = null,
        val virtualBounds: FocusBounds? = null,
    ) {
        val bounds: FocusBounds?
            get() = virtualBounds ?: physicalBounds
    }

    private val entries = LinkedHashMap<FocusKey, Entry>()

    /** Scope stack. The last element is the ACTIVE scope. Never empty (seeded with Root). */
    private val scopeStack = ArrayDeque<FocusScope>().apply { addLast(FocusScope.Root) }

    /** Remembered focus per scope so popScope() restores where you were (focus memory). */
    private val focusMemory = HashMap<FocusScope, FocusKey>()

    /**
     * What [scope] remembers -- **never** [ClearedFocusSentinel].
     *
     * The sentinel is an internal marker parked by [clearFocus] so [maybeSeedFocusOnRegister] cannot hand
     * focus to a re-registering card. Its own contract is that it *"matches no real element"*, and handing
     * it out here contradicted that: callers receive it as a `FocusKey` and cannot tell it from a real one.
     *
     * Most survived by accident, guarding with `takeIf { it in someKeys }`. `LibraryScreen`'s
     * `rememberedGamelistFocusOnShelf` did not -- it SNAPSHOTS this value on the way out to the shelf and
     * carries it into the next gamelist open, where a sentinel silently degrades the landing to tile one.
     * The window widened on 2026-08-27, when the gamelist's registration tracker began calling [clearFocus]
     * on every platform switch: focus then stays null until the landing effect resolves against the
     * destination's Room query, and leaving to the shelf inside that beat takes the sentinel along.
     *
     * Filtering here does not weaken the marker. [maybeSeedFocusOnRegister] and [popScope] read
     * [focusMemory] directly, so the suppression it exists for is untouched -- pinned by
     * `ClearedFocusSentinelLeakTest`.
     */
    override fun rememberedFocusIn(scope: FocusScope): FocusKey? =
        focusMemory[scope]?.takeUnless { it == ClearedFocusSentinel }

    private val _state = MutableStateFlow(FocusState(FocusScope.Root, null))
    override val state: StateFlow<FocusState> = _state.asStateFlow()

    private val activeScope: FocusScope get() = scopeStack.last()

    override fun register(key: FocusKey, scope: FocusScope, bounds: FocusBounds) {
        val current = entries[key]
        entries[key] = Entry(scope = scope, physicalBounds = bounds, virtualBounds = current?.virtualBounds)
        maybeSeedFocusOnRegister(key, scope)
    }

    override fun registerVirtual(key: FocusKey, scope: FocusScope, bounds: FocusBounds) {
        val current = entries[key]
        entries[key] = Entry(scope = scope, physicalBounds = current?.physicalBounds, virtualBounds = bounds)
        maybeSeedFocusOnRegister(key, scope)
    }

    /**
     * Seed focus when a newly-registered element belongs to the active scope and nothing is focused yet — but
     * PREFER the scope's remembered focus (return-jump fix). When a specific key is remembered
     * for this scope (e.g. the platform whose gamelist we just left) and it has not re-registered yet, do NOT seed
     * a sibling; wait for the remembered key so the surface lands where you left it instead of flashing tile 0.
     * A scope re-registers its whole ring in one composition pass, so the remembered key arrives the same pass; if
     * it is genuinely gone (removed platform) it never registers, focus stays null, and the screen's own
     * land-focus effect seeds the fallback.
     */
    private fun maybeSeedFocusOnRegister(key: FocusKey, scope: FocusScope) {
        if (scope != activeScope || _state.value.focused != null) return
        val remembered = focusMemory[activeScope]
        if (remembered == null || remembered == key) {
            setFocus(key)
        }
    }

    override fun unregister(key: FocusKey) {
        val current = entries[key] ?: return
        if (current.virtualBounds != null) {
            entries[key] = current.copy(physicalBounds = null)
            return
        }
        removeEntryAndFallback(key, current)
    }

    override fun unregisterVirtual(key: FocusKey) {
        val current = entries[key] ?: return
        if (current.physicalBounds != null) {
            entries[key] = current.copy(virtualBounds = null)
            return
        }
        removeEntryAndFallback(key, current)
    }

    override fun unregisterVirtualAll(keys: Collection<FocusKey>) {
        val focused = _state.value.focused
        var lost: Entry? = null
        for (key in keys) {
            val current = entries[key] ?: continue
            if (current.physicalBounds != null) {
                entries[key] = current.copy(virtualBounds = null)
                continue
            }
            entries.remove(key)
            if (key == focused) lost = current
        }
        // One fall, from where focus WAS, onto what survives the whole batch (see the interface).
        lost?.let { setFocus(nearestSibling(it)) }
    }

    private fun removeEntryAndFallback(key: FocusKey, removed: Entry) {
        entries.remove(key)
        // NOTE: focusMemory is intentionally NOT wiped here (return-jump fix). A surface that
        // unmounts and re-mounts (e.g. shelf → gamelist → Back) must remember where focus was so it lands back on
        // the same tile, not tile 0. Wiping it made popScope/register fall through to the first re-registered tile,
        // which flashed the wrong platform for a couple of frames until the return effect corrected it (the "jump").
        // Every read of focusMemory guards with `entries[it]?.scope == scope`, so a genuinely-removed key is
        // ignored, and setFocus() overwrites the entry once focus lands elsewhere — so stale entries are harmless.
        if (_state.value.focused == key) {
            // Focus falls to the nearest remaining sibling in the same scope, else null.
            setFocus(nearestSibling(removed))
        }
    }

    /**
     * The remaining entry in [removed]'s scope nearest to it; the first in registration order on a tie; null when
     * either side has no bounds. One pass and no lists, because a registry clear can call it thousands of times.
     */
    private fun nearestSibling(removed: Entry): FocusKey? {
        val from = removed.bounds ?: return null
        var best: FocusKey? = null
        var bestDistance = Float.MAX_VALUE
        for ((key, entry) in entries) {
            if (entry.scope != removed.scope) continue
            val bounds = entry.bounds ?: continue
            val d = distance(from, bounds)
            if (best == null || d < bestDistance) {
                best = key
                bestDistance = d
            }
        }
        return best
    }

    override fun requestFocus(key: FocusKey) {
        val entry = entries[key] ?: return
        if (entry.scope != activeScope) return
        setFocus(key)
    }

    override fun registeredKeysInScope(scope: FocusScope): List<FocusKey> =
        entries.entries.filter { it.value.scope == scope }.map { it.key }

    override fun moveFocus(direction: ControllerAction): Boolean {
        require(direction.isDirectional) { "moveFocus requires a directional action, got $direction" }
        val current = _state.value.focused ?: return false
        val from = entries[current]?.bounds ?: return false

        val candidate = entries.entries
            .asSequence()
            .filter { it.value.scope == activeScope && it.key != current }
            .mapNotNull { (key, entry) -> entry.bounds?.let { bounds -> key to bounds } }
            .filter { (_, bounds) -> inDirection(from, bounds, direction) }
            .minByOrNull { (_, bounds) -> directionalScore(from, bounds, direction) }
            ?.first
            ?: run {
                // SC01 instrument (debug phase, 2026-07-17): a directional dead-end names its scope and
                // the full active ring, so missing registrations vs scope mismatches are readable
                // straight off logcat on-device. The scoring itself has NO viewport filter — a dead-end
                // means the target row is genuinely absent from this scope's ring. runCatching: on the
                // JVM (unit tests) android.util.Log is unmocked and throws — an instrument must never
                // change behavior anywhere.
                runCatching {
                    android.util.Log.d(
                        "MimirFocusMove",
                        "dead-end dir=$direction from=${current.value} scope=${activeScope.value} ring=[" +
                            entries.entries
                                .filter { it.value.scope == activeScope }
                                .joinToString {
                                    val b = it.value.bounds
                                    "${it.key.value}@${b?.top?.toInt()}..${b?.bottom?.toInt()}"
                                } + "]",
                    )
                }
                return false
            }

        setFocus(candidate)
        return true
    }

    override fun pushScope(scope: FocusScope) {
        // Remember where focus was before we leave this scope.
        _state.value.focused?.let { focusMemory[activeScope] = it }
        scopeStack.addLast(scope)
        // Land on the remembered element of the new scope, else its first registered element, else null.
        val landing = focusMemory[scope]?.takeIf { entries[it]?.scope == scope }
            ?: entries.entries.firstOrNull { it.value.scope == scope }?.key
        setFocus(landing)
    }

    override fun popScope() {
        if (scopeStack.size <= 1) return // never pop Root
        _state.value.focused?.let { focusMemory[activeScope] = it }
        scopeStack.removeLast()
        val restored = focusMemory[activeScope]?.takeIf { entries[it]?.scope == activeScope }
            ?: entries.entries.firstOrNull { it.value.scope == activeScope }?.key
        setFocus(restored)
    }

    override fun discardScope(scope: FocusScope) {
        if (scope == FocusScope.Root) return // Root is seeded once and never re-pushed; it is always
        // the bottom of the deque, never "buried" in the sense this method exists for. Removing it here
        // would bypass popScope()'s own "never pop Root" guard and leave the deque without a floor.
        if (activeScope == scope) {
            popScope()
            return
        }
        // Not active: remove the entry directly. No setFocus/_state write happens on this path, so
        // whatever scope IS active (and everything focused inside it) sees no emission at all -- the
        // removal is invisible to it, by design (see the interface kdoc for why that matters here).
        // ArrayDeque.remove(element) takes the FIRST matching entry; this project never pushes the same
        // scope value twice while it is still active (each pushScope call site guards on
        // `state.value.scope != X` before pushing), so there is at most one to find.
        scopeStack.remove(scope)
    }

    override fun rememberFocusForScope(scope: FocusScope, key: FocusKey) {
        // Update ONLY the remembered focus for another scope; never touch the active scope or _state, so a
        // later popScope() into `scope` lands on `key`. gamelist platform-switch return jank.
        focusMemory[scope] = key
    }

    override fun clearFocus() {
        // Null the active focus (so removeEntryAndFallback can't geometric-fallback on the ring swap) AND park a
        // SENTINEL as the scope's remembered key so maybeSeedFocusOnRegister can't auto-seed ANY re-registering
        // card — no card matches the sentinel. Removing the memory instead let the FIRST card to register grab
        // focus (a wrong/tail cover), which flashed before the caller pinned item 0..
        focusMemory[activeScope] = ClearedFocusSentinel
        if (_state.value.focused != null) _state.value = FocusState(activeScope, null)
    }

    private fun setFocus(key: FocusKey?) {
        _state.value = FocusState(activeScope, key)
        if (key != null) focusMemory[activeScope] = key
    }

    // ── Directional geometry (pure; unit-testable off-device) ──
    private fun inDirection(from: FocusBounds, to: FocusBounds, dir: ControllerAction): Boolean = when (dir) {
        ControllerAction.NAV_UP -> to.centerY < from.centerY
        ControllerAction.NAV_DOWN -> to.centerY > from.centerY
        ControllerAction.NAV_LEFT -> to.centerX < from.centerX
        ControllerAction.NAV_RIGHT -> to.centerX > from.centerX
        else -> false
    }

    /**
     * Lower is better: primary distance along the travel axis + a heavy penalty for cross-axis drift,
     * so directional nav prefers the element straight ahead over one that is closer but off to the side.
     */
    private fun directionalScore(from: FocusBounds, to: FocusBounds, dir: ControllerAction): Float {
        val alongAxis: Float
        val crossAxis: Float
        when (dir) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                alongAxis = abs(to.centerY - from.centerY)
                crossAxis = abs(to.centerX - from.centerX)
            }
            else -> {
                alongAxis = abs(to.centerX - from.centerX)
                crossAxis = abs(to.centerY - from.centerY)
            }
        }
        return alongAxis + crossAxis * 3f
    }

    private fun distance(a: FocusBounds, b: FocusBounds): Float =
        abs(a.centerX - b.centerX) + abs(a.centerY - b.centerY)
}
