package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam for [PostControlThermalWatch], mirroring [GuardianRestoreObservability]'s
 * own shape and rationale exactly: a real safety-relevant event on a shipping build must never be
 * unattributable from logcat. Post-control thermal visibility: whether or not the bounded budget in
 * [PostControlThermalWatch] admits a [PostControlThermalDecision.ForceCooling] write THIS tick,
 * every tick that finds the device hot with an unresponsive fan and no active session is logged,
 * every posture, including Production. Restores are rare and this condition should be rarer
 * still, so there is no throttling -- except the held state itself
 * ([PostControlThermalDecision.Holding]), which is by design the steady state for minutes and is
 * reported by [PostControlHoldController]'s own rate-limited `POST_CONTROL_HOLD holding` line.
 *
 * Deliberately shares the `restore reason=`-style leading-subsystem-word convention
 * [GuardianRestoreObservability.describe]/[onServiceRestore] already use, so
 * `logcat | grep "reason="` catches this alongside every restore family. The hold's own lines
 * (`POST_CONTROL_HOLD enter|exit|correct|holding ...`) go through [holdLine] under the same tag.
 */
internal object PostControlThermalObservability {
    private const val TAG = "MimirTweakerService"

    /** Test seam, same reasoning as [GuardianRestoreObservability.lastRecorded]. */
    @Volatile
    var lastRecorded: PostControlThermalDecision? = null
        private set

    fun onDecision(decision: PostControlThermalDecision) {
        if (decision is PostControlThermalDecision.Idle || decision is PostControlThermalDecision.Holding) return
        lastRecorded = decision
        runCatching { Log.i(TAG, describe(decision)) }
    }

    /** The hold's enter/exit/correct/holding lines, same tag, same always-on posture. */
    fun holdLine(line: String) {
        runCatching { Log.i(TAG, line) }
    }

    /** Pure formatting -- reason only, no owner-library content. Unit-testable without [Log]. */
    fun describe(decision: PostControlThermalDecision): String = when (decision) {
        PostControlThermalDecision.Idle -> "POST_CONTROL idle"
        PostControlThermalDecision.Holding -> "POST_CONTROL holding"
        is PostControlThermalDecision.Escalate ->
            "POST_CONTROL reason=${decision.reason} action=BUDGET_EXHAUSTED"
        is PostControlThermalDecision.ForceCooling ->
            "POST_CONTROL reason=${decision.reason} action=FORCE_COOLING_REASSERT"
        is PostControlThermalDecision.EnterHold ->
            "POST_CONTROL reason=${decision.reason} action=ENTER_HOLD"
        is PostControlThermalDecision.CorrectHold ->
            "POST_CONTROL reason=${decision.reason} action=CORRECT_HOLD"
        is PostControlThermalDecision.ReleaseHold ->
            "POST_CONTROL reason=POST_CONTROL_HOLD_${decision.reason.uppercase()} action=RELEASE_HOLD"
        is PostControlThermalDecision.HoldLost ->
            "POST_CONTROL reason=POST_CONTROL_HOLD_LOST action=HOLD_DROPPED mode=${decision.liveMode}"
        is PostControlThermalDecision.LeftToVendor ->
            "POST_CONTROL reason=${decision.reason} action=LEFT_TO_VENDOR remainingMs=${decision.remainingMs}"
    }
}
