package com.gruning.mimir.tweaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Device-protected / direct-boot aware orphan recovery. Starts foreground recovery only
 * when the durable journal is non-empty or corrupt in every posture. Never performs
 * PServer/readback/restore work on the main thread — the service owns recovery or SAFE hold.
 */
class TweakerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = TweakerRecoveryPolicy.triggerForAction(intent.action) ?: return
        val protectedContext = context.createDeviceProtectedStorageContext()
        val load = createAuthenticatedTransactionJournal(protectedContext).load()
        val fpsLoad = createAuthenticatedFpsLimiterJournal(protectedContext).load()
        // An app-op held at ignore survives the reboot, so a game audio-focus journal is a recovery obligation too.
        val audioJournalPresent = gameAudioFocusJournalFilesPresent(protectedContext.filesDir)
        // A post-control fan hold's `fan_mode 6` is a persistent setting :
        // its marker is a recovery obligation the daemon releases on its own action.
        val postControlHoldPresent = postControlHoldMarkerPresent(protectedContext.filesDir)
        val plan = TweakerBootDispatchPolicy.plan(
            trigger = trigger,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            load = load,
            fpsLoad = fpsLoad,
            audioJournalPresent = audioJournalPresent,
            postControlHoldPresent = postControlHoldPresent,
        ) ?: return
        if (plan.guardianRecoveryRequired) {
            context.startForegroundService(
                Intent(context, RestoreGuardianProcessService::class.java)
                    .setAction(RestoreGuardianProcessService.ACTION_RECOVER),
            )
        }
        val serviceIntent = Intent(context, MimirTweakerService::class.java)
            .setAction(plan.serviceAction)
        context.startForegroundService(serviceIntent)
    }
}

internal data class TweakerBootDispatchPlan(
    val serviceAction: String,
    val guardianRecoveryRequired: Boolean,
)

internal object TweakerBootDispatchPolicy {
    fun plan(
        trigger: RecoveryTrigger,
        hardwareWritesCompiled: Boolean,
        load: JournalLoadResult,
        fpsLoad: FpsLimiterJournalLoad = FpsLimiterJournalLoad.Empty,
        audioJournalPresent: Boolean = false,
        postControlHoldPresent: Boolean = false,
    ): TweakerBootDispatchPlan? {
        val hardwareSelection = TweakerRecoveryPolicy.selectRecovery(
            trigger,
            hardwareWritesCompiled,
            load,
        )
        if (hardwareSelection == RecoverySelection.NONE && fpsLoad is FpsLimiterJournalLoad.Empty) {
            // Nothing else to recover. A game audio-focus journal alone still starts the daemon, on its own
            // action; so does a post-control hold marker alone (any started daemon releases the hold at
            // create, so the action only matters when nothing else would have started one) -- but only
            // for a build that can write the release, and only once the user is unlocked: the service
            // reads nothing before unlock and defers to USER_UNLOCKED, which comes on its own.
            return when {
                audioJournalPresent -> TweakerBootDispatchPlan(
                    serviceAction = MimirTweakerService.ACTION_GAME_AUDIO_FOCUS_RECOVERY_RESTORE,
                    guardianRecoveryRequired = false,
                )
                postControlHoldPresent && hardwareWritesCompiled &&
                    trigger != RecoveryTrigger.LOCKED_BOOT_COMPLETED -> TweakerBootDispatchPlan(
                    serviceAction = MimirTweakerService.ACTION_POST_CONTROL_HOLD_RELEASE,
                    guardianRecoveryRequired = false,
                )
                else -> null
            }
        }
        return when (hardwareSelection) {
        RecoverySelection.NONE -> TweakerBootDispatchPlan(
            serviceAction = MimirTweakerService.ACTION_FPS_RECOVERY_RESTORE,
            guardianRecoveryRequired = false,
        )
        RecoverySelection.HOLD_CORRUPT_JOURNAL,
        RecoverySelection.START_FOREGROUND_RECOVERY,
        -> TweakerBootDispatchPlan(
            serviceAction = MimirTweakerService.ACTION_RECOVERY_RESTORE,
            guardianRecoveryRequired = hardwareWritesCompiled &&
                (load as? JournalLoadResult.Loaded)?.record?.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5,
        )
        }
    }
}
