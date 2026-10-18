package com.gruning.mimir.tweaker

/**
 * Admission-before-handoff ordering for a fan-control transition. Original device proof, Odin 3,
 * 2026-09-07 02:58:26: `.000` the guardian's own stock-confirmed restore receipt, `.223`
 * `TELEMETRY_ADMISSION_FAILED reason=THERMAL_ANDROID androidThermalStatus=3` from
 * [ApplyAdmission.evaluateSystemWide], `.227` `session=absent` -- the running generation had
 * already been exact-restored before that admission ran, so its refusal landed on a bare Stock
 * device instead of leaving the prior profile in place. The profile persists until the player explicitly changes it: a fan toggle a player did not ask to change their profile must not be
 * able to cost them their profile.
 *
 * [run] checks [ApplyAdmission] before any handoff write. The current caller uses a signed fan
 * ownership amendment within the active transaction; it no longer restores the profile or runs
 * a successor apply. A rejection leaves the running generation untouched.
 */
internal object FanTransitionOrchestrator {
    /** Admission first. A rejection returns [onRejected]'s value and never runs [proceed]. */
    inline fun <R> run(
        admit: () -> ApplyAdmissionResult,
        onRejected: (ApplyAdmissionResult.Rejected) -> R,
        proceed: () -> R,
    ): R {
        return when (val admission = admit()) {
            is ApplyAdmissionResult.Rejected -> onRejected(admission)
            ApplyAdmissionResult.Admitted -> proceed()
        }
    }
}
