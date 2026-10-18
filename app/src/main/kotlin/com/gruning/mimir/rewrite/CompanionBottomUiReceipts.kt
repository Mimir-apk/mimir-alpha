package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuOrigin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * App-side glue for the Bottom receipts (grammar: [com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat], tag
 * `MimirBottomUi`). Everything here that is a decision is a pure function, so what a receipt says is unit-pinned.
 */

/** Why a Presentation window is being made or reused, as a receipt `reason=`: the disposition that asked for it. */
internal fun companionPresentationReceiptReason(disposition: SecondaryDisplayDisposition): String = when (disposition) {
    is SecondaryDisplayDisposition.ReclaimPending -> "reclaim_" + disposition.lease.reason
    is SecondaryDisplayDisposition.Presenting -> "present_" + disposition.cause.name.lowercase()
    is SecondaryDisplayDisposition.MirrorPresented -> "mirror"
    is SecondaryDisplayDisposition.Yielded -> "yielded"
    is SecondaryDisplayDisposition.LaunchPending -> "launch_pending"
    is SecondaryDisplayDisposition.RecoveryNeeded -> "recovery"
    SecondaryDisplayDisposition.NoDisplay -> "no_display"
}

/**
 * Who holds display 4's screen and input, as a receipt `owner=`.
 *
 * The Bottom is touch-only and takes no key focus (its windows are FLAG_NOT_FOCUSABLE and FocusAuthority governs Main's
 * controls only), so the only owner that changes on it is the runtime's disposition: Mimir's own mirror, a launching
 * app, a foreign host that covers it, or a reclaim in flight. `host:<package>` names the foreign app.
 */
internal fun companionDisplayOwnerLabel(disposition: SecondaryDisplayDisposition): String = when (disposition) {
    SecondaryDisplayDisposition.NoDisplay -> "none"
    is SecondaryDisplayDisposition.Presenting -> "mimir"
    is SecondaryDisplayDisposition.MirrorPresented -> "mimir"
    is SecondaryDisplayDisposition.LaunchPending -> "launching:" + disposition.lease.packageName
    is SecondaryDisplayDisposition.Yielded -> "host:" + (disposition.lease?.packageName ?: "unknown")
    is SecondaryDisplayDisposition.ReclaimPending -> "reclaiming"
    is SecondaryDisplayDisposition.RecoveryNeeded -> "recovery"
}

/**
 * The Bottom surfaces that hide the ᛗ coin while they are up : Mimir's own Utility, Tweaker, Tabs and Quick Launch picker panels, whether an overlay window carries them
 * ([BottomUtilityPanelHost], the Tabs drawer's window) or the Presentation does. On the Thor the coin covered the Utility's "Bottom screen 60%" value. A foreign app on the
 * Bottom, the lattice and every other surface are not here, so the coin behaves exactly as it did over them.
 */
internal val CompanionCoinHidingSurfaces: Set<String> = setOf(BottomSurface.UTILITY, BottomSurface.TWEAKER, BottomSurface.TABS, BottomSurface.PICKER)

/** Whether a Bottom surface stack (bottom to top, [BottomUiReceiptLog.surfaceStack]) holds a panel that hides the coin. */
internal fun companionCoinHiddenByStack(stack: List<String>): Boolean = stack.any { it in CompanionCoinHidingSurfaces }

/**
 * Drives the Bottom's coin from the Bottom's surface stack: [setCoinHidden] is told `true` while the stack on the Bottom's display holds Utility, Tweaker or Tabs, and `false`
 * otherwise (the panel closed, the display gone, no Bottom target, or a target that is not the Bottom's). The stack is the one `surface.state` writes, re-read on every surface edge
 * ([BottomUiReceiptLog.surfaceEdges]) and when the target changes, and only a change of the answer is passed on, so the coin is told once per open and once per close.
 */
internal fun observeBottomPanelCoin(
    scope: CoroutineScope,
    activeTarget: StateFlow<CompanionOverlayTarget?>,
    setCoinHidden: (Boolean) -> Unit,
    log: BottomUiReceiptLog = BottomUiReceipts.log,
): Job = scope.launch {
    combine(activeTarget, log.surfaceEdges.onStart { emit(BottomUiReceiptFormat.NO_DISPLAY) }) { target, _ ->
        val bottom = target?.takeIf { it.role == CompanionOverlayRole.SECONDARY_COMPANION } ?: return@combine false
        companionCoinHiddenByStack(log.surfaceStack(bottom.displayId))
    }
        .distinctUntilChanged()
        .collect(setCoinHidden)
}

/** The stable control id of a ᛗ menu row. */
internal fun companionMenuControlId(item: QuickMenuItem): String = when (item) {
    QuickMenuItem.SYSTEM_TABS -> "m-menu.system_tabs"
    QuickMenuItem.QUICK_LAUNCH -> "m-menu.quick_launch"
    QuickMenuItem.UTILITY -> "m-menu.utility"
    QuickMenuItem.TWEAKER -> "m-menu.tweaker"
    QuickMenuItem.SECONDARY_TABS -> "m-menu.tabs"
    QuickMenuItem.SECONDARY_RETURN -> "m-menu.return_to_shelf"
    QuickMenuItem.SECONDARY_SWAP -> "m-menu.swap"
    QuickMenuItem.TRACKPAD -> "m-menu.trackpad"
}

/**
 * The control id of a coin tap that opens Recents . Its own id, not `m-menu.system_tabs`: no menu is open when the
 * coin is tapped, so it is not a menu control and carries no surface.
 */
internal const val CompanionCoinRecentsControlId = "m-button.recents"

/** One `control.activate` receipt's id, surface and `target=` for a ᛗ request: the menu's row, or the coin's own tap. */
internal data class CompanionQuickMenuReceipt(val id: String, val surface: String?, val target: String? = null)

/**
 * The coin tap's receipt, written by the router once it knows what the tap did: `m-button.recents` with `target=open` (Recents was
 * not up, or could not be told), `close` (the prior top came back) or `toggle` (up, but nothing said what it covered).
 */
internal fun companionCoinRecentsReceipt(action: RecentsTapAction): CompanionQuickMenuReceipt =
    CompanionQuickMenuReceipt(CompanionCoinRecentsControlId, null, action.token)

internal fun companionQuickMenuReceipt(action: CompanionOverlayAction.QuickMenu): CompanionQuickMenuReceipt = when (action.origin) {
    QuickMenuOrigin.MENU_ROW -> CompanionQuickMenuReceipt(companionMenuControlId(action.item), BottomSurface.M_MENU)
    QuickMenuOrigin.COIN_TAP -> CompanionQuickMenuReceipt(CompanionCoinRecentsControlId, null)
}

/** The display the companion runtime currently targets, for a receipt written where no display is at hand; null if none. */
internal fun companionBottomDisplayId(): Int? = CompanionReturnCoordinator.runtime().snapshot.value.displayId

/** The session id a game receipt carries: the session authority's operation value (`home:<uuid>` or `library:<n>`). */
internal fun companionSessionReceiptId(session: MimirGameSession?): String? = session?.operation?.value

/**
 * The ᛗ menu is a native overlay window, so no composition can see it; the controller publishes three flows. The visual one
 * flips when the menu's swap STARTS, the in-flight one is true from then until the INCOMING window has finished its own
 * animation (the swap tier: the outgoing fades 180 ms, and at 130 ms the incoming is added and fades in over 180 ms, about 310 ms
 * in all), and the mounted one lands at that 130 ms hand-over, so it is only read to tell a swap that landed from one that was
 * cancelled: `surface.enter surface=m-menu` + `transition.begin name=m-menu.swap`, then
 * `transition.end ... ms= result=done|cancelled` when the swap really stops moving.
 *
 * Only the Bottom's menu (role SECONDARY_COMPANION) is reported; a single-display device's menu on Main is not a Bottom.
 */
internal fun observeBottomMenuReceipts(
    scope: CoroutineScope,
    controller: CompanionOverlayController,
    log: BottomUiReceiptLog = BottomUiReceipts.log,
): Job = observeBottomMenuReceipts(
    scope = scope,
    log = log,
    visualOpen = controller.quickMenuVisualOpen,
    open = controller.quickMenuOpen,
    swapInFlight = controller.quickMenuSwapInFlight,
    bottomDisplay = {
        controller.activeTarget.value
            ?.takeIf { it.role == CompanionOverlayRole.SECONDARY_COMPANION }
            ?.displayId
    },
)

/**
 * [observeBottomMenuReceipts] over plain flows, so what it writes is unit-pinned.
 *
 *  - The display is captured when the menu OPENS, or when a swap BEGINS (which can be the first thing an observer that attaches
 *    mid-close ever sees), and used for its exit and for the swap's end: a teardown clears the controller's active target before
 *    it publishes the close, so reading the target at close time loses the exit.
 *  - The capture is cleared once the menu has settled closed, so the NEXT open resolves its display fresh. A Main menu that opens
 *    after the Bottom lost eligibility is then not reported as the Bottom's.
 *  - A swap that is still running when this observer is cancelled is ended `result=cancelled` in a `finally`, on the display its
 *    begin named, so a begin is never left behind to be reported `transition.unfinished` after the observing scope has gone.
 */
internal fun observeBottomMenuReceipts(
    scope: CoroutineScope,
    log: BottomUiReceiptLog,
    visualOpen: StateFlow<Boolean>,
    open: StateFlow<Boolean>,
    swapInFlight: StateFlow<Boolean>,
    bottomDisplay: () -> Int?,
): Job = scope.launch {
    var menuDisplay: Int? = null
    var running = false
    var destination = false
    try {
        combine(visualOpen, swapInFlight) { visual, swapping -> visual to swapping }.collect { (visual, swapping) ->
            // A captured display wins over the live target (a teardown clears the target first); with none captured it is resolved
            // now. It is held for as long as the menu is open or swapping and dropped once the menu has settled closed.
            val display = menuDisplay ?: bottomDisplay() ?: return@collect
            if (visual || swapping) menuDisplay = display
            log.surface(BottomSurface.M_MENU, display, visual, transition = if (swapping) M_MENU_SWAP else null)
            if (swapping) {
                destination = visual
                if (!running) {
                    running = true
                    log.transitionBegin(M_MENU_SWAP, display)
                }
            } else if (running) {
                running = false
                // Done only if the menu landed where the swap was heading; a cancelled or failed swap settles back.
                val landed = visual == destination && open.value == destination
                log.transitionEndIfRunning(M_MENU_SWAP, display, if (landed) "done" else "cancelled")
            }
            if (!visual && !swapping) menuDisplay = null
        }
    } finally {
        if (running) menuDisplay?.let { log.transitionEndIfRunning(M_MENU_SWAP, it, "cancelled") }
    }
}

private const val M_MENU_SWAP = "m-menu.swap"

/**
 * A press on the Bottom's ᛗ menu, or the right-edge back swipe, as a `control.activate` receipt. Written at the head of the
 * overlay action stream, so every row is reported once whichever handler (Utility host, Quick launch picker, MainActivity)
 * ends up answering it. A single-display device's menu (role PRIMARY_SINGLE) is not a Bottom control and writes nothing.
 */
internal fun recordBottomMenuControl(controller: CompanionOverlayController, action: CompanionOverlayAction) {
    val target = controller.activeTarget.value
        ?.takeIf { it.role == CompanionOverlayRole.SECONDARY_COMPANION }
        ?: return
    when (action) {
        is CompanionOverlayAction.QuickMenu -> {
            if (action.role != CompanionOverlayRole.SECONDARY_COMPANION) return
            // The coin's tap is written by the router after it decides open or close ([recordCoinRecentsAction]).
            if (action.origin == QuickMenuOrigin.COIN_TAP) return
            val receipt = companionQuickMenuReceipt(action)
            BottomUiReceipts.log.control(receipt.id, target.displayId, receipt.surface)
        }
        CompanionOverlayAction.EdgeBack -> BottomUiReceipts.log.control("edge.back", target.displayId, null)
        else -> Unit
    }
}

/**
 * The instruments host (Utility and Tweaker) as receipts: one panel, two faces. Opening or closing it is
 * `instruments.fade`; moving between the faces while it stays open is `instruments.swap`.
 *
 * Pure of Compose, so what it writes is unit-pinned. [onFace] takes the face that is showing (`null` = the panel is closed)
 * and writes the surface edges; [end] closes whatever is still open when the observing composition goes away.
 */
internal class BottomUiInstrumentsReceipts(
    private val log: BottomUiReceiptLog,
    private val display: Int,
) {
    private var face: UtilitySurface? = null

    fun onFace(next: UtilitySurface?) {
        val transition = if ((next != null) == (face != null)) "instruments.swap" else "instruments.fade"
        face = next
        log.surface(BottomSurface.UTILITY, display, next == UtilitySurface.UTILITY, transition = transition)
        log.surface(BottomSurface.TWEAKER, display, next == UtilitySurface.TWEAKER, transition = transition)
    }

    fun end() {
        face = null
        for (surface in listOf(BottomSurface.UTILITY, BottomSurface.TWEAKER)) {
            if (log.isSurfaceVisible(surface, display)) log.surface(surface, display, false)
        }
    }
}

/**
 * Reports the instruments host's surface edges WITHOUT adding anything to the scope that calls it.
 *
 * The face is read inside [snapshotFlow] in an effect, never in composition, so a change of face (or of open) invalidates this
 * collector and not the enclosing composable. [face] is a lambda for that reason: handing the caller's State READ to a child as
 * a value would put the read back in the caller. [face] returns the face that is showing on the Bottom, `null` when closed.
 */
@Composable
internal fun BottomUiInstrumentsReceipt(
    display: Int,
    log: BottomUiReceiptLog = BottomUiReceipts.log,
    face: () -> UtilitySurface?,
) {
    val latestFace by rememberUpdatedState(face)
    LaunchedEffect(display) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return@LaunchedEffect
        val receipts = BottomUiInstrumentsReceipts(log, display)
        try {
            snapshotFlow { latestFace() }.collect { receipts.onFace(it) }
        } finally {
            receipts.end()
        }
    }
}

/** The coin tap's `m-button.recents` receipt with what it did, on the Bottom the overlay is mounted on; nothing when it is not. */
internal fun recordCoinRecentsAction(controller: CompanionOverlayController, action: RecentsTapAction) {
    val target = controller.activeTarget.value
        ?.takeIf { it.role == CompanionOverlayRole.SECONDARY_COMPANION }
        ?: return
    val receipt = companionCoinRecentsReceipt(action)
    BottomUiReceipts.log.control(receipt.id, target.displayId, receipt.surface, receipt.target)
}
