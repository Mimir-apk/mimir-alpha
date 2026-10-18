package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.input.litFocusIn
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRail
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope

internal val LibraryQuickRailScope = FocusScope("library/rail")

internal fun libraryQuickRailKey(action: MimirQuickRailAction): FocusKey =
    FocusKey("library/rail/${action.name.lowercase()}")

// EXPLICIT per-surface list (never entries.filter): a new enum entry must OPT IN to each rail.
// Quick Launch appears on the Library rail to match Home's dock. The handler
// (onShelfQuickAction → openHomeQuickLaunch) already supported it.
internal fun libraryQuickRailActions(
    homeEnabled: Boolean,
    /**
     *  while a game runs the Files seat is withdrawn, from the rail, the focus order and the press alike, and it
     * comes back when the game ends. Absence, the way the Utility row does it (`brightnessControls`): the remaining seats recentre.
     * Fed from the session ledger ([filesRouteGameRunning]) by the host and the input dispatch, so the rail and the D-pad agree.
     */
    filesSeatOffered: Boolean = true,
): List<MimirQuickRailAction> =
    listOfNotNull(
        MimirQuickRailAction.HOME.takeIf { homeEnabled },
        MimirQuickRailAction.LIBRARY,
        MimirQuickRailAction.BRIGHTNESS,
        MimirQuickRailAction.SETTINGS,
        MimirQuickRailAction.QUICK_LAUNCH,
        // D-1 : terminal end-cap, after QUICK LAUNCH, so no
        // existing action's index moves. Opts the Library rail in; the Home rail (HomeShelfModels.kt /
        // HomeShelfScreen.kt, feature-library) is NOT opted in by this lane -- out of this fence.
        MimirQuickRailAction.FILES.takeIf { filesSeatOffered },
    )

/**
 * What activating an action on the Library rail does. Extracted out of the Composable (mirrors
 * `activateHomeShelfQuickAction`'s existing HOME-rail shape in HomeShelfModels.kt) so it is plain
 * JUnit-testable without a Compose harness -- neither `app` nor `core-ui` has one.
 *
 * LIBRARY is the rail's own self-referential toggle: closes the rail (pops back to the shelf) ONLY
 * when both action==LIBRARY AND the rail currently owns focus; every other case -- including a cold
 * LIBRARY tap while the rail does NOT own focus -- defers to [onAction], matching
 * `activateHomeShelfQuickAction`'s single combined condition exactly.
 *
 * **A real latent defect this extraction surfaced, not introduced:** the inherited shape was a NESTED
 * `if (action == LIBRARY) { if (scope == LibraryQuickRailScope) popScope() }` with no `else` on the
 * inner check -- a silent no-op for a cold LIBRARY tap (neither pop nor onAction). Dead code under the
 * old two-tap grammar: `onActionActivate` only ever fired while the tapped item was already the FOCUSED
 * one, which required scope == [LibraryQuickRailScope] already, so the gap could never actually be
 * reached. The one-tap grammar (MimirRunicQuickRail.kt's clickable now calls onActivate() unconditionally)
 * makes a cold activation reachable for the first time, which is what made a JUnit differential pin
 * (AppShelfQuickRailTest) catch it before device. Flattened to one combined condition, HOME's own shape,
 * to close the gap: never pushes [LibraryQuickRailScope] itself either way, so a cold tap can never
 * push-then-immediately-pop that scope within the same tap.
 */
internal fun libraryQuickRailActivate(
    authority: FocusAuthority,
    action: MimirQuickRailAction,
    onAction: (MimirQuickRailAction) -> Unit,
) {
    if (action == MimirQuickRailAction.LIBRARY && authority.state.value.scope == LibraryQuickRailScope) {
        authority.popScope()
    } else {
        onAction(action)
    }
}

@Composable
internal fun LibraryShelfQuickRail(
    authority: FocusAuthority,
    onAction: (MimirQuickRailAction) -> Unit,
    homeEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
    modifier: Modifier = Modifier,
    filesSeatOffered: Boolean = true,
) {
    val focus by authority.state.collectAsState()
    val actions = libraryQuickRailActions(homeEnabled, filesSeatOffered)

    DisposableEffect(authority) {
        onDispose {
            if (authority.state.value.scope == LibraryQuickRailScope) authority.popScope()
        }
    }
    val homeModifier = Modifier.mimirFocusable(
        authority = authority,
        key = libraryQuickRailKey(MimirQuickRailAction.HOME),
        scope = LibraryQuickRailScope,
        autoBringIntoView = false,
    )
    val libraryModifier = Modifier.mimirFocusable(
        authority = authority,
        key = libraryQuickRailKey(MimirQuickRailAction.LIBRARY),
        scope = LibraryQuickRailScope,
        autoBringIntoView = false,
    )
    val brightnessModifier = Modifier.mimirFocusable(
        authority = authority,
        key = libraryQuickRailKey(MimirQuickRailAction.BRIGHTNESS),
        scope = LibraryQuickRailScope,
        autoBringIntoView = false,
    )
    val settingsModifier = Modifier.mimirFocusable(
        authority = authority,
        key = libraryQuickRailKey(MimirQuickRailAction.SETTINGS),
        scope = LibraryQuickRailScope,
        autoBringIntoView = false,
    )
    val quickLaunchModifier = Modifier.mimirFocusable(
        authority = authority,
        key = libraryQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH),
        scope = LibraryQuickRailScope,
        autoBringIntoView = false,
    )
    //  registered only while the seat is offered, as on the Home dock (HomeShelfScreen): a withdrawn seat that stayed
    // registered kept a dead focus. Unregistering drops a lit Files onto its neighbour, the authority's own fall.
    val filesModifier = if (filesSeatOffered) {
        Modifier.mimirFocusable(
            authority = authority,
            key = libraryQuickRailKey(MimirQuickRailAction.FILES),
            scope = LibraryQuickRailScope,
            autoBringIntoView = false,
        )
    } else {
        Modifier
    }

    MimirRunicQuickRail(
        selectedAction = MimirQuickRailAction.LIBRARY,
        // RING channel (core-input's two-channel law), and identical to the Home rail's gate for the same
        // reason: one dock behaving two ways depending on which shelf you came from is exactly the drift
        // the one-language rule forbids. This briefly read through renderedFocusIn, which left the last
        // visited seat lit after focus returned to the shelf. The dock is visible chrome, not a covered
        // surface; it has no ring to conserve, only one to light while it owns input.
        focusedAction = authority.litFocusIn(LibraryQuickRailScope, focus)
            ?.let { key -> actions.firstOrNull { libraryQuickRailKey(it) == key } },
        actions = actions,
        onActionFocus = { action ->
            if (authority.state.value.scope != LibraryQuickRailScope) {
                authority.pushScope(LibraryQuickRailScope)
            }
            authority.requestFocus(libraryQuickRailKey(action))
        },
        onActionActivate = { action -> libraryQuickRailActivate(authority, action, onAction) },
        actionModifier = { action ->
            when (action) {
                MimirQuickRailAction.HOME -> homeModifier
                MimirQuickRailAction.LIBRARY -> libraryModifier
                MimirQuickRailAction.BRIGHTNESS -> brightnessModifier
                MimirQuickRailAction.SETTINGS -> settingsModifier
                MimirQuickRailAction.QUICK_LAUNCH -> quickLaunchModifier
                MimirQuickRailAction.FILES -> filesModifier
            }
        },
        modifier = modifier,
    )
}

/** Handles only the rail hand-off; the Library reducer remains authoritative for every other action. */
internal fun dispatchLibraryQuickRail(
    authority: FocusAuthority,
    action: ControllerAction,
    homeEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
    filesSeatOffered: Boolean = true,
    onAction: (MimirQuickRailAction) -> Unit,
): Boolean {
    val actions = libraryQuickRailActions(homeEnabled, filesSeatOffered)
    val focus = authority.state.value
    if (focus.scope == LibraryShelfScope && action == ControllerAction.NAV_DOWN) {
        val key = focus.focused?.value
        // The Add pill lives in the shelf scope and its key ("library/shelf/add") shares the tile prefix, so it
        // used to be caught here and DOWN jumped straight to the dock — skipping the content row (item 6). The
        // Add pill sits ABOVE the tiles: DOWN from it must fall to the centered tile (owned by the Library
        // reducer), never the dock, while a tile exists. Only a real content tile hands DOWN off to the quick rail.
        val onLibraryRootControl = key != null && key != "library/shelf/add" &&
            (key.startsWith("library/shelf/") || key.startsWith("library/native/"))
        // The one exception (cert 2026-10-08, A2): an EMPTY ring. When the shelf scope registers nothing but the pill
        // there is no centred tile for the reducer to fall to, so DOWN from the pill used to be a dead end (the geometric
        // move found nothing). The dock is then the only thing below it: hand off to it exactly as a tile does, and UP/B
        // pops back to the pill (the pop restores the scope's remembered key, which the push stamped as the pill).
        val pillOverEmptyRing = key == "library/shelf/add" &&
            authority.registeredKeysInScope(LibraryShelfScope).none { it.value != "library/shelf/add" }
        if (onLibraryRootControl || pillOverEmptyRing) {
            authority.pushScope(LibraryQuickRailScope)
            authority.requestFocus(libraryQuickRailKey(MimirQuickRailAction.LIBRARY))
            return true
        }
    }
    if (focus.scope != LibraryQuickRailScope) return false

    return when (action) {
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
            val current = actions.indexOfFirst { libraryQuickRailKey(it) == focus.focused }.coerceAtLeast(0)
            val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
            authority.requestFocus(libraryQuickRailKey(actions[(current + delta).floorMod(actions.size)]))
            true
        }
        ControllerAction.NAV_UP, ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
            authority.popScope()
            true
        }
        ControllerAction.NAV_DOWN -> true
        ControllerAction.CONFIRM -> {
            val selected = actions.firstOrNull {
                libraryQuickRailKey(it) == focus.focused
            }
            if (selected == MimirQuickRailAction.LIBRARY) authority.popScope()
            else selected?.let(onAction)
            true
        }
        else -> false
    }
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
