package com.gruning.mimir.rewrite.core.input

/**
 * Navigation sound and haptics fire only for actual movement. Repeated input on the 5-item Settings rail played 24 ticks for 24 presses, confirming movement the screen
 * never made. Nav feedback (tick sound + haptic) now fires only when a directional press visibly
 * did something. The router detects focus moves itself by snapshotting [FocusAuthority.state]
 * around the handler; surfaces that legitimately act on a directional WITHOUT moving app focus
 * record it here instead:
 *
 *  - value steppers (Utility sliders, the color dial),
 *  - overlays whose focus is reducer state rather than a FocusAuthority key
 *    (tile menu, platform-art menu, context picker),
 *  - the Settings edge-dwell reveal (a scroll, not a focus move).
 *
 * A dead-end press (clamped rail edge, single-item ring, inert direction) changes neither, and
 * stays silent. Main-thread only, like the rest of the input chain; compared by delta, so it
 * never needs resetting.
 *
 * Only a DIRECTIONAL press is heard through [consumed]. A value that a shoulder or a face button
 * steps (the Mimir-button picker's ADJUST: L1/R1 size the image, X resets it) reports through
 * [stepped], which the router listens to for non-directional actions
 * and answers with the very same tick. It is a separate ledger on purpose: several surfaces already
 * call [consumed] from handlers of shoulder and face-button presses (the Tome folio's page moves, the
 * equalizer's X reset), and hearing those would put a click on presses nobody asked to sound.
 */
object MimirNavFeedback {
    var epoch: Long = 0L
        private set

    /** The non-directional ledger: see [stepped]. */
    var stepEpoch: Long = 0L
        private set

    /** A directional press changed something on screen that a focus snapshot can't see. */
    fun consumed() {
        epoch++
    }

    /**
     * A non-directional press (a shoulder, X) changed a value on screen. The router ticks it like a D-pad step; call it only
     * when the value really changed, so a press at a limit stays silent (BM03).
     */
    fun stepped() {
        stepEpoch++
    }
}
