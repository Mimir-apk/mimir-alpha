package com.gruning.mimir.tweaker

/**
 * When the charger state changes, the vendor re-initialises the fan and duty jumps to 50% of the
 * period. Mimir cannot stop that write. This decides how hard to look for it afterwards.
 *
 * **Backstop, not primary, under the SMART fan architecture.** This class
 * exists to shorten how long a Mimir-DUTY-OWNED session (the Odin's SPORT, unaffected by this architecture) sits at the vendor's 50% re-init duty before an ordinary control cycle corrects it. It
 * has no fan-mode input and never gated on one -- its scheduling is a pure function of elapsed
 * time since the power-transition broadcast, so R2/R3's "the loop is never stopped" for SMART
 * devices does not change what this class does; it changes only which devices ever schedule extra
 * cycles that reach a live [SmartFanThermalController] tick at all (only a duty-owned session's
 * `fanControlDecision` polling loop does; a SMART contract's own early-return there means these
 * scheduled cycles find nothing to reassert and cost nothing -- see
 * `MimirTweakerService.fanControlDecision`'s `contract.fanMode == FAN_SMART` branch). Kept wired,
 * unchanged, for the one device this wave still leaves duty-owned.
 *
 * DEVICE EVIDENCE (Odin 3, `ayn-odin3-cq8725s-pserver`, 2026-08-13, five consecutive trials,
 * every trial validated as a Mimir-duty-owned vendor mode before and after):
 *
 * THE TRIGGER IS STARTING TO CHARGE, not the plug. Measured five ways: `ac=1` alone does nothing,
 * and so does every unplug edge; `ac=1 + status=charging` and `usb=1 + status=charging` both spike
 * every time. This difference explains why an earlier capture that toggled only the AC bit came
 * back clean against a live defect.
 *
 * | | onset after the transition | excursion duration |
 * |---|---|---|
 * | trial 1..5 | 142 / 430 / 585 / 798 / 1069 ms | 1922 / 1928 / 1928 / 1934 / 1939 ms |
 *
 * Two different distributions, and the difference is the whole point:
 *
 * - **Onset is scattered** across ~0.1-1.1 s: whatever the vendor does, it does not land at a fixed
 *   offset, so a single well-timed cycle would miss it. The window has to cover the range.
 * - **Duration is fixed** at ~1930 ms (sigma ~7 ms) and does NOT vary with onset. That flatness is
 *   ours: the excursion sits there until control cycles clear it, so it is the part the player
 *   hears and the part this class can shorten.
 *
 * So the fix is not to prevent the excursion -- nothing in this process can -- but to stop waiting
 * up to a full second to notice it. Extra control cycles are scheduled across the window where the
 * excursion is known to land, and each one is the ORDINARY cycle: same sampling, same
 * [AppliedTargetContract] drift checks, same Guardian fencing, same
 * [SmartFanThermalController.admitDutyReassert] budget. Nothing here writes hardware directly, and nothing
 * here can assert a duty the ordinary path would refuse.
 *
 * Budget cost is deliberately near-zero: a cycle that finds no drift consumes no reassert budget at
 * all, so an entire window costs the single attempt that actually repairs the excursion -- well
 * inside [SmartFanThermalController.MAX_DUTY_REASSERT_ATTEMPTS_PER_BURST].
 */
internal object PowerTransitionFanRecovery {

    /**
     * How long to keep looking after the transition.
     *
     * Must outlast the measured worst case (onset 1069 ms + duration 1939 ms = 3008 ms) so the
     * window cannot expire while the fan is still at 50%.
     */
    const val WINDOW_MS = 3_200L

    /** Spacing between extra cycles. Bounds the worst-case time-to-notice to one spacing. */
    const val SPACING_MS = 200L

    /** The tightest onset measured on hardware. The window is worthless if it starts after this. */
    const val MEASURED_MIN_ONSET_MS = 142L

    /** The longest excursion measured on hardware, from transition to recovery. */
    const val MEASURED_MAX_EXCURSION_END_MS = 3_008L

    /**
     * Delays, relative to receiving the power broadcast, at which to run an extra control cycle.
     *
     * Deliberately a fixed schedule rather than "poll until clean": a bounded list cannot spin, and
     * cannot outlive the window if the excursion never appears.
     */
    fun recoveryOffsetsMs(): List<Long> =
        generateSequence(SPACING_MS) { it + SPACING_MS }
            .takeWhile { it <= WINDOW_MS }
            .toList()
}
