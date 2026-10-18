package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.launch.PreDispatchOutcome
import com.gruning.mimir.rewrite.core.launch.LaunchAttemptId
import com.gruning.mimir.rewrite.core.tweaker.TweakerLaunchOverlayMessage
import com.gruning.mimir.rewrite.core.tweaker.TweakerPreparationResult
import com.gruning.mimir.rewrite.core.tweaker.formatTweakerLaunchConfirmation
import com.gruning.mimir.rewrite.core.tweaker.formatTweakerLaunchReadyLine
import com.gruning.mimir.rewrite.core.tweaker.toLaunchOverlayOrNull
import com.gruning.mimir.rewrite.core.tweaker.tweakerPreDispatchDegradedWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HomeTweakerPreDispatch(
    val attemptId: LaunchAttemptId,
    val presentation: CrossAppLaunchStatusShowOutcome,
)

internal suspend fun presentHomeTweakerLaunchStatus(
    context: Context,
    attemptId: LaunchAttemptId,
    workloadPackage: String,
    gameTitle: String,
): HomeTweakerPreDispatch {
    // Logged BEFORE the bridge call, not after. prepareForLaunch reaches another app over Binder, and
    // LaunchController contains this whole hook in a time-bounded, throwable-catching guard
    // (runContainedPreDispatch) -- so if it throws or overruns, a log placed after it never runs and
    // the hook looks as though it was never called. That is exactly what happened on 2026-08-08: a
    // Home-shelf launch produced no line at all, and it was read as the launch path being skipped.
    // Entry and result are separate events because the gap between them is where the failure lives.
    logLaunchDiagnostic(workloadPackage, "preflight=entered")
    val preparation = TweakerBridge.prepareForLaunch(attemptId, workloadPackage)
    // Preflight starts no tuning session and never proves one started (it may, separately, hold the game
    // away from audio focus so other music keeps playing: TweakerBridge.holdGameAudioFocusForLaunch), but it decides whether one is even
    // scheduled -- so a launch that quietly does nothing is explained here or nowhere.
    logLaunchDiagnostic(
        workloadPackage,
        "prepared=${preparation.result.diagnosticLabel()} selection=${preparation.selection}",
    )
    val message = preparation.result.toLaunchOverlayOrNull(preparation.selection, gameTitle)
    val presentation = withContext(Dispatchers.Main.immediate) {
        CrossAppLaunchStatusPresenter.instance(context).showSync(message)
    }
    return HomeTweakerPreDispatch(preparation.attemptId, presentation)
}

/**
 * The pre-dispatch Tweaker preparation threw or was abandoned at its budget, and the game was dispatched
 * anyway. Preflight starts no tuning session (its one other side effect, the audio-focus hold, degrades
 * silently and is released on return), so say that no new tuning session started without asserting broader
 * Stock truth about an older service/process state.
 */
internal suspend fun presentHomeTweakerLaunchDegraded(
    context: Context,
    attemptId: LaunchAttemptId,
    outcome: PreDispatchOutcome,
): CrossAppLaunchStatusShowOutcome {
    // The other half of the gap: when the hook throws or overruns its budget, THIS runs instead of the
    // result log, and it was silent too.
    logLaunchDiagnostic(
        TweakerBridge.preparedLaunchSelectionContext().selectedProfileId,
        "preflight=degraded outcome=${outcome::class.simpleName}",
    )
    TweakerBridge.abandonPendingLaunch(attemptId)
    val warning = tweakerPreDispatchDegradedWarning(
        context = TweakerBridge.preparedLaunchSelectionContext(),
        timedOut = outcome is PreDispatchOutcome.TimedOut,
    )
    val message = warning?.let(TweakerLaunchOverlayMessage::Warning)
    return withContext(Dispatchers.Main.immediate) {
        CrossAppLaunchStatusPresenter.instance(context).showSync(message)
    }
}

/**
 * Diagnostic identity of a launch-time handoff. Never shown to the player.
 *
 * The player-facing copy states which tuning is in force and deliberately says nothing about the
 * operation ([warningOrNull]), which is right for them and leaves a bug report with nothing to go on.
 * `MimirTweakerSelection` solved that for the PANEL's apply path; this is the same instrument for the
 * launch path, which had none — so a reported launch-time warning required a device investigation to attribute to a profile
 * whose CPU target sits below the live floor while charging.
 */
internal fun TweakerV4LaunchHandoff.diagnosticLabel(): String = when (this) {
    TweakerV4LaunchHandoff.NotScheduled -> "NotScheduled"
    is TweakerV4LaunchHandoff.Started -> "Started(profile=$profileId)"
    is TweakerV4LaunchHandoff.Rejected -> "Rejected(reason=$reason)"
}

/** As [TweakerV4LaunchHandoff.diagnosticLabel], for the post-dispatch runtime outcome. */
internal fun TweakerV4LaunchRuntimeOutcome.diagnosticLabel(): String = when (this) {
    is TweakerV4LaunchRuntimeOutcome.Active -> "Active(profile=$profileId)"
    is TweakerV4LaunchRuntimeOutcome.DebugLabStarted -> "DebugLabStarted(profile=$profileId)"
    is TweakerV4LaunchRuntimeOutcome.Failed ->
        "Failed(profile=$profileId, phase=$phase, stockConfirmed=$stockConfirmed, " +
            "noNewSession=$noNewSessionConfirmed, reason=$reason)"
}

private fun logLaunchDiagnostic(packageName: String, event: String) {
    runCatching { android.util.Log.i("MimirTweakerLaunch", "package=$packageName $event") }
}

/**
 * Diagnostic identity of a preflight result, WITH the reason a skip carries.
 *
 * Logging the class name alone was not enough: `Skipped` is the branch that decides whether a launch
 * gets tuned, and its reason is the only field saying why it did not. Observed on device as
 * `prepared=Skipped` with no way to tell a deliberate Stock no-op from an owner that had not settled
 * yet — two very different facts, one of which is lost tuning.
 *
 * `reason` stays diagnostic-only: `TweakerLaunchSelectionContext.allowsLaunchWarningFor`
 * (`core-tweaker-client/.../TweakerLaunchContext.kt`) returns `false` for every `Skipped`, so
 * `TweakerPreparationResult.launchWarning` never interpolates this string into player copy — an
 * internal reason like "Tweaker owner is still settling" reaches only this log line, never the
 * screen. (An earlier version of this comment claimed the leak still existed; it did not hold
 * against the current source and has been corrected.)
 */
internal fun TweakerPreparationResult.diagnosticLabel(): String = when (this) {
    TweakerPreparationResult.Unavailable -> "Unavailable"
    TweakerPreparationResult.StockNoOp -> "StockNoOp"
    is TweakerPreparationResult.Applied -> "Applied(profile=$profileId)"
    is TweakerPreparationResult.Scheduled -> "Scheduled(profile=$profileId)"
    is TweakerPreparationResult.Skipped -> "Skipped(reason=$reason)"
}

/**
 * Name the profile a dispatched game is running under, without involving the launch pipeline.
 *
 * the launch card should say which profile is active, nothing else. The
 * pipeline could not deliver that. `LaunchController` skips `preDispatchHook` entirely when
 * `prepared.dispatchable` is false and still reports `PreDispatchOutcome.Completed`, so a game can
 * dispatch and run tuned with no Tweaker code executing at all — measured on Odin, a Home-shelf launch
 * produced zero `MimirTweakerLaunch` lines while `balanced` was in force. Tying the card to hooks that
 * may never fire is why nothing appeared.
 *
 * Source is [TweakerUtilityRowState.effectiveProfileId], the same value the Tweaker panel shows as the
 * current setting: it yields a profile only when availability is READY and that profile is one the
 * row still offers, so it cannot name one the device would not actually apply.
 *
 * On the confirmation treatment: [toPostDispatchOverlayOrNull] reserves it for authoritative
 * post-dispatch active truth, and this is not that — it is the system-wide selection, which the daemon
 * holds independently of any launch. Admitted deliberately, because the launch card must not narrate operations. A profile that fails to apply is still reported by the
 * post-dispatch path when the pipeline does run.
 *
 * Stock is silent: there is no tuning to name.
 */
internal suspend fun presentHomeTweakerActiveProfile(context: Context, gameTitle: String) {
    val profileId = TweakerBridge.utilityRowState().effectiveProfileId
        .takeIf { it != TweakerBridge.STOCK_PROFILE }
        ?: return
    val appContext = context.applicationContext
    withContext(Dispatchers.Main.immediate) {
        CrossAppLaunchStatusPresenter.instance(appContext).showSync(
            TweakerLaunchOverlayMessage.Confirmation(
                headline = formatTweakerLaunchConfirmation(profileId),
                supportingText = formatTweakerLaunchReadyLine(gameTitle),
            ),
        )
    }
}

/** Exact post-IntentSent local handoff. Stock/no-ticket is intentionally silent. */
internal suspend fun activateHomeTweakerAfterDispatch(
    context: Context,
    attemptId: LaunchAttemptId,
    workloadPackage: String,
    gameTitle: String,
) {
    val appContext = context.applicationContext
    val handoff = TweakerBridge.activateScheduledProfileAfterDispatch(
        attemptId = attemptId,
        packageName = workloadPackage,
        onRuntimeOutcome = { outcome ->
            logLaunchDiagnostic(workloadPackage, "outcome=${outcome.diagnosticLabel()}")
            val message = outcome.toPostDispatchOverlayOrNull(gameTitle)
            message?.let {
                withContext(Dispatchers.Main.immediate) {
                    CrossAppLaunchStatusPresenter.instance(appContext).showSync(
                        it,
                    )
                }
            }
        },
    )
    logLaunchDiagnostic(workloadPackage, "handoff=${handoff.diagnosticLabel()}")
    if (handoff is TweakerV4LaunchHandoff.NotScheduled) {
        TweakerBridge.confirmPreparedActiveProfileAfterDispatch(attemptId)?.let { profileId ->
            withContext(Dispatchers.Main.immediate) {
                CrossAppLaunchStatusPresenter.instance(appContext).showSync(
                    TweakerLaunchOverlayMessage.Confirmation(
                        headline = formatTweakerLaunchConfirmation(profileId),
                        supportingText = formatTweakerLaunchReadyLine(gameTitle),
                    ),
                )
            }
        }
    }
    handoff.warningOrNull()?.let { copy ->
        withContext(Dispatchers.Main.immediate) {
            CrossAppLaunchStatusPresenter.instance(appContext).showSync(
                TweakerLaunchOverlayMessage.Warning(copy),
            )
        }
    }
}

/** Only authoritative post-dispatch active truth may use the confirmation treatment. */
internal fun TweakerV4LaunchRuntimeOutcome.toPostDispatchOverlayOrNull(
    gameTitle: String,
): TweakerLaunchOverlayMessage? = when (this) {
    is TweakerV4LaunchRuntimeOutcome.Active ->
        if (profileId == TweakerBridge.STOCK_PROFILE) {
            null
        } else {
            TweakerLaunchOverlayMessage.Confirmation(
                headline = formatTweakerLaunchConfirmation(profileId),
                supportingText = formatTweakerLaunchReadyLine(gameTitle),
            )
        }
    else -> warningOrNull()?.let(TweakerLaunchOverlayMessage::Warning)
}

/**
 * Native Android games bypass [com.gruning.mimir.rewrite.core.launch.LaunchController], so keep its
 * exact post-dispatch rule here: only a completed preparation may activate Tweaker. A degraded
 * preparation retires its attempt; a synchronous dispatch failure retires a completed attempt.
 */
internal suspend fun settleHomeTweakerAfterNativeDispatch(
    started: Boolean,
    preDispatch: PreDispatchOutcome,
    activate: suspend () -> Unit,
    presentDegraded: suspend (PreDispatchOutcome) -> Unit,
    abandon: () -> Unit,
) {
    when {
        started && preDispatch is PreDispatchOutcome.Completed -> activate()
        preDispatch !is PreDispatchOutcome.Completed -> presentDegraded(preDispatch)
        else -> abandon()
    }
}

/**
 * These report the STATE the game is running in, not the operation that failed to produce it.
 *
 * "TWEAKER DIDN'T START" named an internal event the player has no stake in, and read as an error
 * against Mimir when the honest content is simply which tuning is in force. * say what is true rather than what failed. The confirmation treatment is still reserved for
 * authoritative ACTIVE truth (see [toPostDispatchOverlayOrNull]), so these keep the warning
 * treatment — the copy changed, not the styling.
 *
 * The UNCERTAIN case below deliberately keeps its wording: there the state is genuinely unknown and a
 * Stock restore is unconfirmed, so stating a state would be the one thing this rewrite is against.
 */
internal fun TweakerV4LaunchHandoff.warningOrNull(): String? = when (this) {
    TweakerV4LaunchHandoff.NotScheduled,
    is TweakerV4LaunchHandoff.Started,
    -> null
    is TweakerV4LaunchHandoff.Rejected -> "TUNING UNCHANGED · GAME CONTINUES"
}

internal fun TweakerV4LaunchRuntimeOutcome.warningOrNull(): String? = when (this) {
    is TweakerV4LaunchRuntimeOutcome.Active,
    is TweakerV4LaunchRuntimeOutcome.DebugLabStarted,
    -> null
    is TweakerV4LaunchRuntimeOutcome.Failed -> when {
        // A session was running and has ended. That history is worth keeping — the end state is Stock
        // either way, but "returned" and "never started" are different facts about the run.
        phase == TweakerV4LaunchFailurePhase.RENEWAL && stockConfirmed ->
            "STOCK · TUNING ENDED, GAME CONTINUES"
        // Confirmed at Stock: name the state, since it is known.
        phase == TweakerV4LaunchFailurePhase.ACTIVATION && stockConfirmed ->
            "STOCK · GAME CONTINUES UNTUNED"
        // Confirmed that no NEW session began, but not what is in force. Do not name a profile.
        phase == TweakerV4LaunchFailurePhase.ACTIVATION && noNewSessionConfirmed ->
            "TUNING UNCHANGED · GAME CONTINUES"
        // Genuinely unknown, and a Stock restore is unconfirmed. Stays a warning about uncertainty.
        else ->
            "TWEAKER STATUS UNCERTAIN — GAME CONTINUES; STOCK RESTORE IS UNCONFIRMED"
    }
}
