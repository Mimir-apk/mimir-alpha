package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability

/**
 * One route into the Tweaker.
 *
 * Until now `UtilitySurface.TWEAKER` was opened only by `MButtonPrimaryCommand.TWEAKER` — the
 * physical M button — so a player without that button, or one already inside Utility, had no way
 * across and the Tweaker read as missing. The request specified for a route from Utility that raises the
 * same grant request, so both entrances behave identically.
 *
 * The policy is that the Tweaker never waits for certification or privileged evidence. The
 * policy is still shared by the M button, macros, and the Utility row so every entrance agrees.
 */
internal enum class TweakerRouteDecision {
    /** Built-in access is held. Open the Tweaker surface. */
    OPEN,

    /** No Tweaker to route to. Say so rather than opening an empty surface. */
    UNAVAILABLE,
}

/**
 * Where an activation should land.
 *
 * [tweakerPaired] is whether a Tweaker is actually there to talk to. An unpaired device is
 * unavailable; a paired Tweaker opens without waiting for privileged certification.
 */
internal fun tweakerRouteDecision(tweakerPaired: Boolean): TweakerRouteDecision = when {
    !tweakerPaired -> TweakerRouteDecision.UNAVAILABLE
    else -> TweakerRouteDecision.OPEN
}

/**
 * Whether there is a Tweaker on the other end of the route at all.
 *
 * `RESPONDING_STOCK_ONLY` counts: the service is there and answering, it simply has nothing
 * certified yet. Routing to it is honest — that surface is where the player learns why. Only a
 * missing or non-answering Tweaker has no destination.
 */
internal fun tweakerRoutePaired(availability: TweakerUtilityAvailability): Boolean =
    when (availability) {
        TweakerUtilityAvailability.READY,
        TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
        // **DISCOVERED_CEILING routes too, and this is the story's own gate.** The name of this
        // function is about a PAIR, but its stated question is whether the route has a destination
        // worth arriving at, and on the discovered path it does: the Utility row reads its ceiling through
        // `readTunerAvailability()` with no daemon dependency at all, so it shows a live reading on
        // exactly the devices this branch describes. Refusing the route here
        // would leave the rail naming a real ceiling while the door to the surface explaining it was
        // shut — the rail and the TWEAKER surface disagreeing on one screen, which is the defect E3
        // exists to close.
        TweakerUtilityAvailability.DISCOVERED_CEILING,
        -> true

        TweakerUtilityAvailability.NOT_INSTALLED,
        TweakerUtilityAvailability.UNAVAILABLE,
        -> false
    }

/** Row value for the Utility route, so the row states its outcome before it is pressed. */
internal fun tweakerRouteValueLabel(decision: TweakerRouteDecision): String = when (decision) {
    TweakerRouteDecision.OPEN -> "OPEN"
    TweakerRouteDecision.UNAVAILABLE -> "UNAVAILABLE"
}

/**
 * Supporting copy. It must not promise control the row cannot deliver.
 */
internal fun tweakerRouteSupportingText(decision: TweakerRouteDecision): String = when (decision) {
    TweakerRouteDecision.OPEN -> "Performance profiles, fan and frame cap."
    TweakerRouteDecision.UNAVAILABLE -> "No supported Tweaker on this device."
}
