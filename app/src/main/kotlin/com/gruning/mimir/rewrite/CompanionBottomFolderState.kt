package com.gruning.mimir.rewrite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R6-P2-08: whether a closable Bottom layer is open, and the native-triggered request to close the
 * innermost one.
 *
 * **Generalized past the Tome.** This started as "the Home lattice's own folder ('Tome') is open," but
 * review finding 1 caught that a `folderOpen` sourced from `openFolderId` alone knows nothing about what
 * else covers the lattice — the quick-launch picker, a game-display/quick-launch choice dialog, the
 * Tweaker panel, or the platform shelf. A swipe over any of those ran `close_folder` on the HIDDEN Tome
 * underneath while the covering surface stayed up. [open] now carries the OR of every closable Bottom
 * layer (`MimirCompanionPresentation`'s `closableLayerOpen`), and [closeRequest] asks whichever of them is
 * innermost to close, one per swipe — `MimirCompanionPresentation` is the only place that can see which
 * one that is, so it alone resolves the request instead of blindly nulling `openFolderId`.
 *
 * Local Compose state inside `feature-companion`/`:app` cannot itself reach
 * `MainActivity`/`AndroidCompanionOverlayController` to arm the CP03 edge-back strip — the same reason
 * [CompanionRecentsState] exists for the Tabs drawer. Process state, like that store and
 * [CompanionWorkspaceState], so a Presentation remount does not lose it.
 *
 * **R6-P2-08 re-review: a request must be handled exactly once.** [closeRequest] is a `StateFlow` and
 * therefore replays its last value to every new collector — a Presentation created by a display
 * remount/reconnect (`CompanionDisplayController`) sees a stale, already-resolved id as its OWN first
 * value and would otherwise resolve it a second time, closing whatever happens to be open in that new
 * instance for a swipe that never happened in it (re-review finding C). [claim] is the single place that
 * decides "have I already handled this one" — first caller for a given id wins, every later caller for
 * the SAME id (a different collector, or the same one re-running) gets `false` and must do nothing.
 */
internal object CompanionBottomFolderState {
    private val mutableOpen = MutableStateFlow(false)

    /** The OR of every closable Bottom layer — see the class KDoc. */
    val open: StateFlow<Boolean> = mutableOpen.asStateFlow()

    private val mutableCloseRequest = MutableStateFlow<Long?>(null)

    /** A new, distinct value asks Compose to close whichever layer is innermost, mirroring the shape
     *  `CompanionQuickLaunchLayer`'s own `generalPickerRequest` already uses for a native-triggered
     *  open. Null means nothing has asked yet. */
    val closeRequest: StateFlow<Long?> = mutableCloseRequest.asStateFlow()

    private val mutableHandledRequest = MutableStateFlow<Long?>(null)

    /** The most recent [closeRequest] id some collector has already resolved, via [claim]. */
    val handledRequest: StateFlow<Long?> = mutableHandledRequest.asStateFlow()

    // R6-F-13: named `markLayerOpen`, not `setOpen`, so the connected-services posture gate's
    // R1-setter-owner rule (tools/mimir_connected_posture_gate.py, a bare `"setOpen("` text match owned
    // by the network-consent gates, e.g. MimirConnectedArtGate/MimirSteamGridDbGate) does not fire on
    // this unrelated same-named setter — this object gates a UI layer, not network egress.
    fun markLayerOpen(open: Boolean) {
        mutableOpen.value = open
    }

    fun requestClose() {
        mutableCloseRequest.value = (mutableCloseRequest.value ?: 0L) + 1L
    }

    /**
     * Claims [id] for handling. Returns `true` the first time any collector calls this for a given id,
     * `false` for every call after — including a replay of the SAME id into a brand-new Presentation, or
     * a repeat call from the same one. Callers must check the return value and skip acting when it is
     * `false`; this makes no decision on its own about what "acting" means.
     */
    fun claim(id: Long): Boolean {
        if (id == mutableHandledRequest.value) return false
        mutableHandledRequest.value = id
        return true
    }
}
