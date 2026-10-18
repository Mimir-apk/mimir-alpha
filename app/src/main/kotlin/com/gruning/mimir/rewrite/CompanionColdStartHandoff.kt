package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat

/**
 * Who follows the secondary-home floor's cold-start cover, as explicit state rather than a read of the receipt log.
 *
 * **What it decides.** On a cold start the floor ([CompanionSecondaryHomeActivity]) wears Main's return cover (the Mannaz badge) beneath the Presentation, and the
 * Presentation's first frame wears the same cover and fades it out on `OverlayMs` ([MimirCompanionPresentation.coldStartHandoff]), so the lattice arrives through a
 * fade and not a hard cut. A Presentation that comes back over a yielded app has no cover beneath it and must wear none.
 *
 * **Why the receipt log was the wrong place.** The decision was one read of `isSurfaceVisible(COLD_START)` at Presentation construction. That log line is written by the
 * floor's composition (a `LaunchedEffect` enter), which nothing orders against the controller's construction: read first, the cover was skipped on a real cold start;
 * entered late, the surface stayed visible in the log until some later first frame and a LATER reclaim over a yielded app was handed a cover it had no floor under.
 * The log is a witness. It is not the control. Now the state is its own, and the receipt stream is untouched (same lines, same order).
 *
 * **The latch, per display** (every call is on the main thread; the lock is only for the JVM tests that call from elsewhere):
 *  - [floorCreated], called by the floor SYNCHRONOUSLY in `onCreate`, before `setContent`, arms the latch only when no Presentation is attached on that display. A
 *    Presentation that is already there is on top of the floor and replaces it, so the floor's cover is not what the player is leaving.
 *  - [forNewPresentation], called at the controller's one construction site, reports the latch and records the Presentation as attached.
 *  - [firstPresentationFrame] disarms it UNCONDITIONALLY, in the controller's frame-commit handler, whether or not the window showed the cover. A latch that survives a
 *    first frame could reach a Presentation that is not a cold start, so nothing here lets one.
 *  - [presentationGone] records that the window left (dismissed, replaced, activity stop); [floorDestroyed] disarms, because a floor that is gone has no cover up.
 *
 * Ordering, both ways: floor first, the Presentation takes the latch and follows the cover; Presentation first, the floor does not arm, and the window wears no cover
 * (nothing is beneath it to hand off from). A reclaim afterwards finds the latch disarmed either way.
 */
internal object CompanionColdStartHandoff {
    private val lock = Any()
    private val armed = HashSet<Int>()
    private val attached = HashSet<Int>()

    /** The floor on [display] is up and covering. Arms the latch unless a Presentation is already attached there. Returns whether it armed. */
    fun floorCreated(display: Int): Boolean = synchronized(lock) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY || display in attached) return false
        armed.add(display)
        true
    }

    /** The floor on [display] is destroyed: its cover is no longer beneath anything. */
    fun floorDestroyed(display: Int) {
        synchronized(lock) { armed.remove(display) }
    }

    /** A new Presentation is being built for [display]: records it as attached and returns whether it follows the floor's cold-start cover. */
    fun forNewPresentation(display: Int): Boolean = synchronized(lock) {
        attached.add(display)
        display in armed
    }

    /** The Presentation on [display] is gone (dismissed, replaced, or its host stopped). */
    fun presentationGone(display: Int) {
        synchronized(lock) { attached.remove(display) }
    }

    /** The first Presentation frame on [display] committed: the cold start is over whether or not this window wore the cover. */
    fun firstPresentationFrame(display: Int) {
        synchronized(lock) { armed.remove(display) }
    }

    fun isArmed(display: Int): Boolean = synchronized(lock) { display in armed }

    fun isAttached(display: Int): Boolean = synchronized(lock) { display in attached }

    /** Test seam: forget every display. */
    fun reset() {
        synchronized(lock) {
            armed.clear()
            attached.clear()
        }
    }
}
