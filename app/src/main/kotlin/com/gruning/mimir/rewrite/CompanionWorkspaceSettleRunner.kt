package com.gruning.mimir.rewrite

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.bottomUiTransition
import com.gruning.mimir.rewrite.core.ui.mimirPagerSettleSpec
import java.util.UUID

/**
 * Runs the workspace strip's settle (Thor Bottom revamp B4) in a scope of its own.
 *
 * D37: the Presentation used to read `workspaceStrip.settle` in its own root scope, so the strip's release and its landing each recomposed the whole
 * Presentation body on the Main thread both screens share (the first of them on the first frame of the settle's animation). The read lives here now: a
 * settle recomposes this one scope, which draws nothing, and the Presentation's root is not recomposed by the strip at all. What the settle does is
 * exactly what the effect in the Presentation did: it animates the strip on core-ui's pager clock (OverlayMs on the house easing; reduced motion snaps),
 * names a release's settle `workspace.settle` (a cancel's runs unnamed), and only a settle that landed on the other page with no cover up (read live,
 * at the landing, through [coverUp]) calls [commit], which switches the page and writes the control receipt.
 */
@Composable
internal fun CompanionWorkspaceSettleRunner(
    strip: CompanionWorkspaceStrip,
    receiptDisplayId: Int,
    coverUp: () -> Boolean,
    commit: (CompanionWorkspaceSettle) -> Unit,
) {
    val workspaceSettle = strip.settle
    val latestCoverUp by rememberUpdatedState(coverUp)
    val latestCommit by rememberUpdatedState(commit)
    LaunchedEffect(workspaceSettle) {
        val request = workspaceSettle ?: return@LaunchedEffect
        val run: suspend () -> Unit = {
            Animatable(request.fromX).animateTo(request.toX, mimirPagerSettleSpec(bottomUiReducedMotion())) {
                strip.moveTo(request, value)
            }
        }
        if (request.announces) bottomUiTransition("workspace.settle", receiptDisplayId) { run() } else run()
        // The route is read LIVE here, at the moment of landing: an `abandon` effect for a shelf that opened a frame ago may not have run yet.
        if (strip.finish(request, coverUp = latestCoverUp())) latestCommit(request)
    }
}

/**
 * D27: the covers that stand over the Bottom's workspace and take its touch: the recents drawer drawn on this window, the Bottom Quick launch window, the
 * Bottom Tweaker, the quick-launch picker, a pending quick-launch choice and a pending game-display choice. ONE definition with two readers: the page takes
 * no touch under any of them (`workspaceNavigationBase` in the Presentation), and a settle that lands re-reads them ([companionLiveWorkspaceCoverUp]).
 *
 * The cancel a cover's opening fires reaches only a drag that is still held. A cover that opens after the finger has lifted (the settle runs for a while)
 * or without touching the page at all (a chooser a launch raises, the drawer opened from elsewhere) fires none, so the landing must ask.
 */
internal fun companionWorkspaceCoverUp(
    drawerOnPresentation: Boolean,
    bottomQuickLaunchCovering: Boolean,
    tweakerOnBottom: Boolean,
    quickPickerOpen: Boolean,
    quickLaunchChoicePending: Boolean,
    gameDisplayChoicePending: Boolean,
): Boolean = drawerOnPresentation || bottomQuickLaunchCovering || tweakerOnBottom || quickPickerOpen || quickLaunchChoicePending || gameDisplayChoicePending

/**
 * Whether any cover is up RIGHT NOW, at the moment a settle lands: the platform shelf ([companionLivePlatformShelfDestination]) and the covers of
 * [companionWorkspaceCoverUp]. Each is read off the process state it is drawn from, never off what the Presentation last composed, so a cover that opened
 * in the frame before the landing is seen. [quickPickerOpen] is the Presentation's own state (a state read, live); [bottomQuickLaunchCovering] is the Bottom
 * Quick launch window's flag.
 */
internal fun companionLiveWorkspaceCoverUp(renderOperationId: UUID, quickPickerOpen: Boolean, bottomQuickLaunchCovering: Boolean): Boolean =
    companionLivePlatformShelfDestination() != null ||
        companionWorkspaceCoverUp(
            drawerOnPresentation = CompanionRecentsState.ui.value.isVisibleOnPresentation(renderOperationId),
            bottomQuickLaunchCovering = bottomQuickLaunchCovering,
            tweakerOnBottom = CompanionProcessRuntime.sharedBrightness.value
                ?.let { it.isOpen && it.presentationTarget == AppBrightnessPresentationTarget.SECONDARY } == true,
            quickPickerOpen = quickPickerOpen,
            quickLaunchChoicePending = CompanionQuickLaunchChoiceState.pending.value != null,
            gameDisplayChoicePending = CompanionGameDisplayChoiceState.pending.value != null,
        )
