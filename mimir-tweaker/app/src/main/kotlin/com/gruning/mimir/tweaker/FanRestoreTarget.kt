package com.gruning.mimir.tweaker

/**
 * TR-08 fan restore-target policy, in effect since
 * 2026-10-13: **a captured SILENT (1) or SPORT (5) `fan_mode` restores to Smart (4), and Smart then
 * COUNTS AS RESTORED.** Pure, shared by the daemon port, the daemon's readbacks and the Guardian's
 * plain-JVM restore policy, so the three cannot disagree about what "restored" means for the fan.
 *
 * Why not the literal captured value: SILENT's and SPORT's vendor loops both stop regulating duty
 * (device-measured, `TweakerVendorFanModes.java`, commit 87f02042; SMART's keeps running). A session
 * that captured one of them -- a pre-TR-08 build left the device in SPORT, a stale snapshot, or the
 * AYN Thor's own Standard tile, which sets `fan_mode` 1 -- would otherwise write it straight back on
 * exit and park the device exactly as the Odin was measured: 23% duty, `speed = 0`, CPUs at
 * 89.9C. Redirecting to SMART is also the honest vendor default: the reference Nova reads 4 on a boot
 * Mimir never wrote to, and `CalibrationModels` expects 4 for the `stock` profile on any device.
 *
 * Why the comparison is STRICT (`observed == of(captured)`), never "captured or Smart": a lenient
 * match would let a loop-stopper still live on the device read as ALREADY_STOCK, which is the one
 * outcome this redirect exists to prevent. The captured value itself stays what the journal and the
 * signed envelope carry (MGDE compares it against the node unchanged); only the value a restore
 * WRITES and the value a readback DEMANDS are redirected.
 *
 * Scope stays narrow: only [LOOP_STOPPING_MODES]. Every other captured value -- SMART, and the
 * undocumented 0, 2, 3, 7 the vendor range admits -- is restored and verified unchanged; asserting
 * those stop the loop too would be exactly the ungrounded claim this project's rules forbid. MANUAL
 * (6) is refused as a restore target before this is consulted (`restoreCapturedFanMode`).
 */
internal object FanRestoreTarget {
    /** Vendor `fan_mode` values documented, in this codebase, to stop the vendor's duty-regulation loop. */
    val LOOP_STOPPING_MODES: Set<Int> = setOf(TuningPlanner.FAN_SILENT, TuningPlanner.FAN_SPORT)

    /** What a restore of [captured] writes, and the one value a restore readback accepts for it. */
    fun of(captured: Int): Int = if (captured in LOOP_STOPPING_MODES) TuningPlanner.FAN_SMART else captured

    /** The strict comparison every restore readback of a captured fan uses. */
    fun restored(captured: Int?, observed: Int?): Boolean = captured != null && observed == of(captured)
}
