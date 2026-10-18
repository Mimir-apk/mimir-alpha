package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope

/** Shipping gamelist renders a ~60-row moving window; keep the focus graph in the same order of magnitude. */
internal const val DefaultVirtualFocusWindowRadius = 30
private const val VirtualFocusRowPitch = 1000f
private const val VirtualFocusColumnPitch = 1000f

internal data class VirtualFocusSlot(
    val index: Int,
    val key: FocusKey,
    val bounds: FocusBounds,
)

internal data class VirtualFocusWindow(
    val anchorIndex: Int,
    val startIndex: Int,
    val endIndexExclusive: Int,
    val slots: List<VirtualFocusSlot>,
) {
    val keys: List<FocusKey> = slots.map { it.key }
}

internal fun boundedVirtualFocusWindow(
    keys: List<FocusKey>,
    focusedIndex: Int,
    selectedIndex: Int = focusedIndex,
    radius: Int = DefaultVirtualFocusWindowRadius,
): VirtualFocusWindow {
    require(radius >= 0) { "radius must be >= 0" }
    if (keys.isEmpty()) {
        return VirtualFocusWindow(
            anchorIndex = -1,
            startIndex = 0,
            endIndexExclusive = 0,
            slots = emptyList(),
        )
    }

    val anchorIndex = when {
        focusedIndex in keys.indices -> focusedIndex
        selectedIndex in keys.indices -> selectedIndex
        else -> 0
    }
    val startIndex = (anchorIndex - radius).coerceAtLeast(0)
    val endIndexExclusive = (anchorIndex + radius + 1).coerceAtMost(keys.size)
    return VirtualFocusWindow(
        anchorIndex = anchorIndex,
        startIndex = startIndex,
        endIndexExclusive = endIndexExclusive,
        slots = (startIndex until endIndexExclusive).map { index ->
            val top = index * VirtualFocusRowPitch
            VirtualFocusSlot(
                index = index,
                key = keys[index],
                bounds = FocusBounds(
                    left = 0f,
                    top = top,
                    right = VirtualFocusRowPitch,
                    bottom = top + VirtualFocusRowPitch / 2f,
                ),
            )
        },
    )
}

internal fun virtualFocusRowSlots(keys: List<FocusKey>): List<VirtualFocusSlot> =
    keys.mapIndexed { index, key ->
        val left = index * VirtualFocusColumnPitch
        VirtualFocusSlot(
            index = index,
            key = key,
            bounds = FocusBounds(
                left = left,
                top = 0f,
                right = left + VirtualFocusColumnPitch / 2f,
                bottom = VirtualFocusColumnPitch,
            ),
        )
    }

/**
 * Keeps every slot in its authored geometry while letting an exact-return target register first.
 * Registration order may seed focus; visual/navigation order must never be rewritten to achieve that.
 */
internal fun preferredVirtualFocusRegistrationOrder(
    slots: List<VirtualFocusSlot>,
    preferredKey: FocusKey?,
): List<VirtualFocusSlot> {
    val preferred = slots.firstOrNull { it.key == preferredKey } ?: return slots
    if (slots.firstOrNull()?.key == preferred.key) return slots
    return buildList(slots.size) {
        add(preferred)
        slots.forEach { slot ->
            if (slot.key != preferred.key) add(slot)
        }
    }
}

/**
 * Whether an incoming slot set REPLACES this ring's identity rather than sliding its window.
 *
 * A moving window always overlaps what it replaces -- that overlap is exactly what makes the
 * register-before-unregister order in [VirtualFocusRegistrationTracker.update] safe. A gamelist platform
 * switch shares nothing: every incoming key belongs to a different platform's games.
 */
internal fun virtualFocusReplacesIdentity(
    registeredKeys: Set<FocusKey>,
    desiredKeys: Set<FocusKey>,
): Boolean = registeredKeys.isNotEmpty() &&
    desiredKeys.isNotEmpty() &&
    desiredKeys.none { it in registeredKeys }

internal class VirtualFocusRegistrationTracker {
    private val registeredKeys = linkedSetOf<FocusKey>()

    fun update(
        authority: FocusAuthority,
        scope: FocusScope,
        slots: List<VirtualFocusSlot>,
        clearsFocusOnIdentityReplacement: Boolean = false,
    ) {
        val desiredKeys = slots.mapTo(linkedSetOf()) { it.key }

        // A wholesale identity REPLACEMENT is not a moving window, and the per-key geometric fallback in
        // `removeEntryAndFallback` is meaningless across one. [virtualFocusRowSlots] derives bounds from the
        // index alone (`left = index * VirtualFocusColumnPitch`, `top = 0f`), so tile N of the OUTGOING ring
        // and tile N of the INCOMING ring have byte-identical bounds -- Manhattan distance 0. Unregistering
        // the departed key therefore re-homed focus onto the same INDEX in a different platform's games, and
        // [resolvePendingGamelistLanding] could not tell that silent move from a deliberate one: its
        // `currentIsUserLanding` check reads only WHERE focus is, so it suppressed its own correction --
        // for every index except 0, where the fallback happens to equal `first` and the correction survives.
        //
        // Measured on the Odin 2026-08-27 (APK sha
        // 73df9d10): departed lane on tile 1 -> 11 of 17 switches landed on the wrong game; departed lane on
        // tile 0 -> 0 of 4. The carousel painted the CORRECT remembered tile first, held it 7-23ms, then this
        // second focus event retargeted it and the glide chased faithfully (a 1.5-tile trail-clamp snap plus
        // a 1.5-tile cruise, 345ms measured against 347ms predicted from its own constants). That travel is
        // the observed resettling animation -- the motion code was never at fault.
        //
        // [FocusAuthority.clearFocus] is the primitive built for exactly this. Its own contract says it nulls
        // focus "so removeEntryAndFallback can't geometric-fallback on the ring swap", and it parks the
        // cleared sentinel so `maybeSeedFocusOnRegister` cannot hand focus to a re-registering card either.
        // The surface's own landing effect then owns the landing -- the path that already measured correct
        // with ZERO tile travel on every trial where it ran.
        if (clearsFocusOnIdentityReplacement &&
            virtualFocusReplacesIdentity(registeredKeys, desiredKeys)
        ) {
            // NOT just `focused in registeredKeys`. Device probe 2026-08-27: a switch taken from the
            // Enrich/Hero pill leaves focus on `GamelistGetArtKey`, which this tracker never registers --
            // so the narrow test misses it, nothing is cleared, and a SECOND auto-seed does the damage.
            // `maybeSeedFocusOnRegister` seeds the scope's remembered key the instant that key re-registers,
            // and `focusMemory[GamelistScope]` is ONE slot shared by every platform: it still held the
            // previous visit's tile. Focus landed there before the landing effect ran, and
            // `resolvePendingGamelistLanding` read it as a user landing and returned `target = null`.
            //
            //   swap  focused=library/gamelist/getart inRegistered=false
            //   arm   laneMemory=metroid prime 2 echoes          <- the memory was correct
            //   resolve pendingRemembered=metroid prime 2 focusedAtResolve=twilight princess target=null
            //
            // Anything focused in this scope that the incoming ring does not contain is stale across the
            // swap, so clear it. The sentinel `clearFocus` parks is what also blocks the auto-seed.
            val live = authority.state.value
            if (live.scope == scope && live.focused != null && live.focused !in desiredKeys) {
                authority.clearFocus()
            }
        }

        // Add/update the incoming window before removing stale slots so a moving focus target never
        // disappears between adjacent windows.
        slots.forEach { slot ->
            authority.registerVirtual(
                key = slot.key,
                scope = scope,
                bounds = slot.bounds,
            )
        }

        registeredKeys
            .filterNot { it in desiredKeys }
            .forEach(authority::unregisterVirtual)
        registeredKeys.clear()
        registeredKeys.addAll(desiredKeys)
    }

    fun clear(authority: FocusAuthority) {
        registeredKeys.forEach(authority::unregisterVirtual)
        registeredKeys.clear()
    }
}

/**
 * Bulk-tears-down every key this tracker registered, then re-stamps [scope]'s remembered focus back to
 * whatever was truly focused right before the clear -- undoing a cascade that [clear] alone lets happen.
 *
 *  Root cause: [clear] unregisters every key in registration order (ascending index for a
 * shelf row). `DefaultFocusAuthority.removeEntryAndFallback` (core-input) is correct for a SINGLE live
 * removal from an otherwise-stable ring -- it reassigns focus to the nearest still-registered sibling so
 * a removed game/platform doesn't strand focus on nothing. But during a BULK clear (the whole scope
 * unmounting, e.g. `HomeShelfScreen`/`LibraryScreen` disposing when Settings opens) that same one-at-a-
 * time reassignment cascades: unregistering the tile the player was actually on reassigns focus to its
 * still-registered neighbour, then the very next unregister call removes THAT neighbour and reassigns
 * again, repeating all the way to the last key in the ring. The final removal has no sibling left, so
 * focus lands null -- but `DefaultFocusAuthority.setFocus(null)` deliberately never writes
 * `focusMemory` (so a genuinely-cleared scope isn't stamped with a fake key), which means the LAST
 * non-null stop of the cascade (the ring's last tile) is what remains recorded when the scope's owning
 * screen later calls `popScope()`/`pushScope()` to restore where the player left from.
 *
 * This is a Compose-effect-disposal-order hazard, not a timing race: `RegisterVirtualFocusSlots`'s own
 * `DisposableEffect` is declared AFTER the owning screen's scope-push `DisposableEffect` (e.g.
 * `HomeShelfScreen.kt`, `LibraryScreen.kt`), and Compose disposes effects in reverse declaration order,
 * so this bulk clear always runs BEFORE the scope actually pops -- there is no frame boundary that could
 * paper over it the way a `withFrameNanos {}` yield fixed the sibling Settings -> Library scope race.
 *
 * The fix does not change [clear]'s per-key semantics (a live single-item removal still gets its correct
 * geometric fallback) -- it only protects the SCOPE-LEVEL truth once the whole ring is gone, by
 * snapshotting the real pre-clear focus and writing it directly into `focusMemory` via
 * [FocusAuthority.rememberFocusForScope], bypassing the cascade's clobbered value entirely.
 */
internal fun VirtualFocusRegistrationTracker.clearAndRestoreFocus(authority: FocusAuthority, scope: FocusScope) {
    val trueFocus = authority.state.value.takeIf { it.scope == scope }?.focused
    clear(authority)
    trueFocus?.let { authority.rememberFocusForScope(scope, it) }
}

@Composable
internal fun RegisterVirtualFocusSlots(
    authority: FocusAuthority,
    scope: FocusScope,
    slots: List<VirtualFocusSlot>,
    clearsFocusOnIdentityReplacement: Boolean = false,
) {
    val tracker = remember(authority, scope) { VirtualFocusRegistrationTracker() }

    SideEffect {
        tracker.update(
            authority = authority,
            scope = scope,
            slots = slots,
            clearsFocusOnIdentityReplacement = clearsFocusOnIdentityReplacement,
        )
    }
    DisposableEffect(authority, scope, tracker) {
        onDispose {
            tracker.clearAndRestoreFocus(authority, scope)
        }
    }
}

@Composable
internal fun RegisterVirtualFocusRow(
    authority: FocusAuthority,
    scope: FocusScope,
    keys: List<FocusKey>,
    preferredRegistrationKey: FocusKey? = null,
    /** Opt in where this row's whole key set can be swapped while it stays mounted. See [update]. */
    clearsFocusOnIdentityReplacement: Boolean = false,
) {
    val slots = remember(keys, preferredRegistrationKey) {
        preferredVirtualFocusRegistrationOrder(
            slots = virtualFocusRowSlots(keys),
            preferredKey = preferredRegistrationKey,
        )
    }
    RegisterVirtualFocusSlots(
        authority = authority,
        scope = scope,
        slots = slots,
        clearsFocusOnIdentityReplacement = clearsFocusOnIdentityReplacement,
    )
}

@Composable
internal fun RegisterBoundedVirtualFocusColumn(
    authority: FocusAuthority,
    scope: FocusScope,
    keys: List<FocusKey>,
    focusedIndex: Int,
    selectedIndex: Int = focusedIndex,
    radius: Int = DefaultVirtualFocusWindowRadius,
    /** Opt in where this column's whole key set can be swapped while it stays mounted. See [update]. */
    clearsFocusOnIdentityReplacement: Boolean = false,
) {
    val window = remember(keys, focusedIndex, selectedIndex, radius) {
        boundedVirtualFocusWindow(
            keys = keys,
            focusedIndex = focusedIndex,
            selectedIndex = selectedIndex,
            radius = radius,
        )
    }
    val tracker = remember(authority, scope) { VirtualFocusRegistrationTracker() }

    SideEffect {
        tracker.update(
            authority = authority,
            scope = scope,
            slots = window.slots,
            clearsFocusOnIdentityReplacement = clearsFocusOnIdentityReplacement,
        )
    }
    DisposableEffect(authority, scope, tracker) {
        onDispose {
            tracker.clearAndRestoreFocus(authority, scope)
        }
    }
}
