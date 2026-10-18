package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.gruning.mimir.rewrite.core.ui.mimirPagerSettlePage
import kotlin.math.roundToInt

/**
 * The Bottom's workspace as a two-pane strip that follows the finger (Thor Bottom revamp B4): [Activity | lattice], one page wide each.
 *
 * Coordinates: [x] is the strip's position in px, 0 with the lattice at rest and [widthPx] with the Activity page at rest, so a finger moving
 * right moves [x] up by the same amount. Both scenes translate with the finger and nothing else (no alpha, no scale).
 *
 * This is the whole of the strip's logic and none of its drawing or animation, so it runs on a plain JVM: what it takes of a finger delta, which
 * page a release lands on and whether that is a switch, and the little state machine around a settle. The Presentation owns the animation (it runs
 * the settle on core-ui's pager clock) and the receipts; the lattice (feature-companion) knows only three generic callbacks and none of this.
 *
 * A drag [drag]s the strip from the page it began on; a release decides ([release]) and a settle runs to the decided page; the page is switched
 * (and the control receipt written) only when the settle reaches a page other than the one it began on ([finish] answers true). A drag that
 * settles back, or is cancelled, switches nothing. The strip then holds its position until the host has shown the page it landed on
 * ([reconcile]), so the frame that swaps which scene is "shown" is the same frame the strip stops driving: nothing flashes between.
 */
internal class CompanionWorkspaceStrip {

    /** One page's width in px; 0 until the host has measured. Nothing drags the strip while it is 0. */
    var widthPx: Float = 0f

    /** The strip's position while it [active]; at rest it is the page's own ([restX]). */
    var x: Float by mutableFloatStateOf(0f)
        private set

    /** The strip is off its rest: both scenes are composed and drawn at [x]. True from the first px a drag moves it until the host shows where it landed. */
    var active: Boolean by mutableStateOf(false)
        private set

    /** What the host runs to settle the strip, null when none is running. A new request replaces (and so cancels) the one before it. */
    var settle: CompanionWorkspaceSettle? by mutableStateOf(null)
        private set

    /** The scene change the host is about to show is one the finger carried (the strip already settled on it): that change takes no handoff of its own. */
    var carried: Boolean by mutableStateOf(false)
        private set

    private var from = CompanionWorkspacePage.SHELF
    private var dragging = false
    private var holdUntil: CompanionWorkspacePage? = null
    private var serial = 0

    /** A touch on the page is ignored while the settle runs: the strip is moving on its own clock and the scene under the finger is moving with it. */
    val settling: Boolean get() = settle != null

    /** Where the strip sits at rest on [page]. */
    fun restX(page: CompanionWorkspacePage): Float = if (page == CompanionWorkspacePage.ACTIVITY) widthPx else 0f

    /** The strip's position on [page]: its own while it is off its rest, else the page's rest (which needs the measured width). */
    fun xFor(page: CompanionWorkspacePage): Float = if (active) x else restX(page)

    /**
     * The finger moved by [deltaPx] (positive is rightward) from a scene showing [shown]. Returns the part of the delta the strip did not use, so the
     * caller can hand it back to whatever else follows the finger.
     *
     * The lattice offers what its pager could not use at page 0's left edge, and it hands back what the strip returns, so once the strip is back on
     * the lattice's rest a leftward finger turns the lattice's pages again. The Activity page offers its whole drag. Nothing is taken while the strip
     * is settling or unmeasured, and a delta that moves nothing does not start a drag.
     */
    fun drag(shown: CompanionWorkspacePage, deltaPx: Float): Float {
        // Held on a landing the host has not shown yet: the strip is the scene the finger already carried in, not a new drag's.
        if (widthPx <= 0f || settling || holdUntil != null || deltaPx == 0f) return deltaPx
        if (dragging && x == restX(from) && clamp(x + deltaPx) == x) {
            // The finger came back through the strip's rest and is going on past it: the strip hands the finger back and stands down, so nothing is left
            // composed, owned and idle off-screen.
            letGo()
            return deltaPx
        }
        if (!dragging) {
            if (active && from != shown) return deltaPx
            val start = if (active) x else restX(shown)
            if (clamp(start + deltaPx) == start) return deltaPx
            from = shown
            x = start
            dragging = true
            active = true
        }
        val next = clamp(x + deltaPx)
        val used = next - x
        x = next
        // Part of the delta took the strip home to its rest and the rest of it is still going that way: the same hand-back, in one delta.
        if (used != deltaPx && next == restX(from)) {
            letGo()
        }
        return deltaPx - used
    }

    /** The strip stands down mid-drag, at its rest: no drag held, nothing active. */
    private fun letGo() {
        dragging = false
        active = false
        carried = false
    }

    /**
     * The finger lifted at [velocityPx] px/s (positive is rightward). Null when the strip did not hold the finger (the host does not own this release);
     * otherwise the settle the host is to run, which this also records as [settle]. The decision is core-ui's pager rule over two pages (Activity is
     * page 0, the lattice page 1): the nearer page, unless the release flings at [flingPx] or more, which turns exactly one page in its direction.
     */
    fun release(velocityPx: Float, flingPx: Float): CompanionWorkspaceSettle? {
        if (!dragging) return null
        dragging = false
        val current = (1f - x / widthPx).roundToInt().coerceIn(0, 1)
        val landed = mimirPagerSettlePage(pageIndex(from), current, velocityPx, flingPx).coerceIn(0, 1)
        return request(target = pageOf(landed), announces = true)
    }

    /**
     * The accessible twin of the swipe from the Activity page (the Shelf button is gone, so a screen-reader user needs another way
     * back than a raw drag): the same drag and the same release, in one go, so it is the SAME settle with the SAME receipts (`workspace.settle`, then the
     * `activity.swipe_back` control receipt at the landing). It is a short flick, not a cut: the strip is dragged a tenth of a page and released at twice the
     * fling threshold, so the settle ANIMATES the rest of the way, as a real swipe's does. Null when the strip cannot take it (unmeasured, already settling,
     * held on a landing).
     */
    fun returnToShelf(flingPx: Float): CompanionWorkspaceSettle? {
        if (widthPx <= 0f || settling) return null
        drag(CompanionWorkspacePage.ACTIVITY, -widthPx * ReturnFlickFraction)
        return release(-flingPx * 2f, flingPx)
    }

    /** The drag ended without a release (a cover, a second pointer, a display that went away): back to where it began, silently. Null when none held. */
    fun cancel(): CompanionWorkspaceSettle? {
        if (!dragging) return null
        dragging = false
        return request(target = from, announces = false)
    }

    /** The settle's animation reached [position]. Ignored for a settle that has since been replaced. */
    fun moveTo(request: CompanionWorkspaceSettle, position: Float) {
        if (settle?.id == request.id) x = clamp(position)
    }

    /**
     * Something took the workspace away (a platform shelf opened): a held drag settles back, silently; a settle that was on its way to the other page is
     * dropped where it stands, so it can never commit the page over what opened.
     */
    fun abandon() {
        if (dragging) cancel() else if (settle?.commits == true) clear()
    }

    /**
     * The settle [request] ran to its end. True exactly when it is still the current one, landed on a page other than the one it began on, and no cover ([coverUp]) is up: the host
     * then switches the page and writes the control receipt. The strip stays where it landed until [reconcile] sees the page shown.
     */
    fun finish(request: CompanionWorkspaceSettle, coverUp: Boolean = false): Boolean {
        if (settle?.id != request.id) return false
        settle = null
        if (coverUp && request.commits && request.target == CompanionWorkspacePage.ACTIVITY) {
            // A cover (the platform shelf, or any other in companionWorkspaceCoverUp) is up at the moment of landing, read live by the caller, not as of when the settle began, and
            // not left to the cancel its opening fires (a settle has no held drag to cancel): the strip does not commit ONTO Activity over it. It goes back to where it began, with
            // no receipt and no switch. A landing back on the lattice always commits: the shelf is only drawn on the lattice page, so a retained route that is admitted while
            // Activity is shown is not a cover over anything the landing would hide, and the other covers stand over either page.
            clear()
            return false
        }
        x = restX(request.target)
        if (request.target == request.from) {
            clear()
            return false
        }
        holdUntil = request.target
        carried = true
        return true
    }

    /**
     * The page the host is showing now. The strip lets go once that is the page it landed on, and stands down if the page was changed under a drag by
     * something else (a launch, a back press): a strip left driving a scene that is no longer there would hold the wrong one on screen.
     */
    fun reconcile(shown: CompanionWorkspacePage) {
        val hold = holdUntil
        if (hold != null) {
            if (shown == hold) clear()
            return
        }
        if (active && from != shown) clear()
    }

    /** Forget everything: the strip is at rest on whatever the host shows. */
    fun clear() {
        dragging = false
        holdUntil = null
        settle = null
        carried = false
        active = false
    }

    private fun request(target: CompanionWorkspacePage, announces: Boolean): CompanionWorkspaceSettle {
        val next = CompanionWorkspaceSettle(
            id = ++serial,
            from = from,
            target = target,
            fromX = x,
            toX = restX(target),
            announces = announces,
        )
        settle = next
        return next
    }

    private companion object {
        /** How far the accessible return nudges the strip before its release (a fraction of a page): the rest is the settle's animation. */
        const val ReturnFlickFraction = 0.1f
    }

    private fun clamp(value: Float): Float = value.coerceIn(0f, widthPx)

    private fun pageIndex(page: CompanionWorkspacePage): Int = if (page == CompanionWorkspacePage.ACTIVITY) 0 else 1

    private fun pageOf(index: Int): CompanionWorkspacePage = if (index == 0) CompanionWorkspacePage.ACTIVITY else CompanionWorkspacePage.SHELF
}

/**
 * One settle of the strip, for the host to run: from [fromX] to [toX] on core-ui's pager clock. [announces] is true for a release and false for a
 * cancel, which writes no receipt. It is a switch when [target] is not [from].
 */
internal data class CompanionWorkspaceSettle(
    val id: Int,
    val from: CompanionWorkspacePage,
    val target: CompanionWorkspacePage,
    val fromX: Float,
    val toX: Float,
    val announces: Boolean,
) {
    val commits: Boolean get() = target != from

    /** The control receipt a committed switch writes: the swipe that made it (the receipt names are unchanged by B4). Null for a settle back. */
    val controlReceipt: String?
        get() = when {
            !commits -> null
            target == CompanionWorkspacePage.ACTIVITY -> "lattice.swipe_activity"
            else -> "activity.swipe_back"
        }
}

/**
 * Where the Activity PANE sits, in px, for the one thing that is drawn on the Presentation's surface layer but belongs to that pane: the recent-app
 * icon. [stripX] is the strip's position while it is off its rest, NaN at rest (the shape the scenes already read); [shown] is the page the host shows.
 *
 * It is a pure function of what is being DRAWN, never of what was last composed: at rest on Activity the pane is at 0, at rest on the lattice it is a full
 * [paneWidthPx] to the left (off the canvas), and off its rest it follows the strip (its x less one pane). The landing on the lattice and the cancel back to
 * it let go of the strip a frame before the surface's composition catches up, and for that frame the icon was still composed: it must already be off the
 * canvas, so the answer cannot lean on "the strip is active" alone.
 */
internal fun companionActivityPaneTranslationX(stripX: Float, shown: CompanionWorkspacePage, paneWidthPx: Float): Float = when {
    !stripX.isNaN() -> stripX - paneWidthPx
    shown == CompanionWorkspacePage.ACTIVITY -> 0f
    else -> -paneWidthPx
}

/** Rides the Activity pane (see [companionActivityPaneTranslationX]): both inputs are read in the draw phase, so the layer follows the state of the frame it is drawn in. */
internal fun Modifier.companionActivityPane(stripX: () -> Float, shown: () -> CompanionWorkspacePage): Modifier =
    graphicsLayer { translationX = companionActivityPaneTranslationX(stripX(), shown(), size.width) }
