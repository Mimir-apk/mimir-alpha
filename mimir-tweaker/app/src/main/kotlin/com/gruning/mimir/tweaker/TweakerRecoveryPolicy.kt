package com.gruning.mimir.tweaker

import android.content.Context
import android.content.Intent

/** Pure recovery-selection truth for boot/package/service recreation paths. */
internal enum class RecoveryTrigger {
    LOCKED_BOOT_COMPLETED,
    BOOT_COMPLETED,
    USER_UNLOCKED,
    MY_PACKAGE_REPLACED,
    SERVICE_CREATE,
}

internal enum class RecoverySelection {
    /** Journal empty and healthy — do nothing. */
    NONE,
    /** Start foreground recovery on the owned worker; never restore on main. */
    START_FOREGROUND_RECOVERY,
    /** Corrupt journal blocks clear/busy-loop; enter durable recovery holding pattern. */
    HOLD_CORRUPT_JOURNAL,
}

internal object TweakerRecoveryPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun shouldStartRecovery(hardwareWritesCompiled: Boolean, hasActiveJournal: Boolean): Boolean =
        hasActiveJournal

    fun shouldStartRecovery(context: Context): Boolean {
        val protectedContext = context.createDeviceProtectedStorageContext()
        return shouldStartRecovery(
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            hasActiveJournal = when (createAuthenticatedTransactionJournal(protectedContext).load()) {
                JournalLoadResult.Empty -> false
                is JournalLoadResult.Loaded, is JournalLoadResult.Corrupt -> true
            },
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun selectRecovery(
        trigger: RecoveryTrigger,
        hardwareWritesCompiled: Boolean,
        load: JournalLoadResult,
    ): RecoverySelection {
        return when (load) {
            JournalLoadResult.Empty -> RecoverySelection.NONE
            is JournalLoadResult.Corrupt -> RecoverySelection.HOLD_CORRUPT_JOURNAL
            is JournalLoadResult.Loaded -> when (trigger) {
                RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                RecoveryTrigger.BOOT_COMPLETED,
                RecoveryTrigger.USER_UNLOCKED,
                RecoveryTrigger.MY_PACKAGE_REPLACED,
                RecoveryTrigger.SERVICE_CREATE,
                -> RecoverySelection.START_FOREGROUND_RECOVERY
            }
        }
    }

    fun triggerForAction(action: String?): RecoveryTrigger? = when (action) {
        Intent.ACTION_LOCKED_BOOT_COMPLETED -> RecoveryTrigger.LOCKED_BOOT_COMPLETED
        Intent.ACTION_BOOT_COMPLETED -> RecoveryTrigger.BOOT_COMPLETED
        Intent.ACTION_USER_UNLOCKED -> RecoveryTrigger.USER_UNLOCKED
        Intent.ACTION_MY_PACKAGE_REPLACED -> RecoveryTrigger.MY_PACKAGE_REPLACED
        MimirTweakerService.ACTION_RECOVERY_RESTORE -> RecoveryTrigger.SERVICE_CREATE
        else -> null
    }
}


/**
 * Whether a *creation* of [MimirTweakerService] must dispatch its own durable-recovery obligation.
 *
 * `onCreate` has always been deliberately query-scoped -- "sticky recreation and explicit
 * boot/package starts own foreground recovery in onStart". That is right for the ordinary case and
 * wrong for exactly one: **a durable journal record that outlives the started lifetime that owned
 * it has no route to an owner at all.**
 *
 * `am force-stop com.gruning.mimir.tweaker` is precisely how a device gets there, which is why it
 * is not merely useless against this shape but actively creates it: force-stop destroys the
 * `START_STICKY` obligation that would otherwise have re-run [MimirTweakerService.onStartCommand],
 * and every later creation of this service is a *bind* from Mimir -- which reaches `onCreate` and
 * never `onStartCommand`. The record then survives forever, and
 * [PublicSessionTruthResolver] publishes it as `RESTORING` (an APPLIED record with no live
 * `active` session resolves to RESTORING by design), which Mimir's client reads as
 * `availability=UNAVAILABLE profiles=[stock]` because `authoritativeV4Capabilities` discards the
 * whole capability bundle when session truth is not canonical.
 *
 * Device-observed on the Retroid Pocket Nova, 2026-08-30: generation 662, transaction
 * `c6eff393-543d-4daf-94f5-c7407ddf8f83`, profile `quiet`, whose 12-hour hard lease expired
 * ~47 hours of uptime before it was measured, still publishing RESTORING; the daemon served a
 * PERFECT capability bundle (`supportedUncertifiedProfileIds=[quiet, balanced, performance]`,
 * `posture=PRODUCTION`, `mutationAvailable=true`) that Mimir threw away every 15 seconds. Two
 * force-stops across three sessions could not clear it.
 *
 * This introduces **no new recovery path**. It reuses [TweakerBootDispatchPolicy.plan] -- the exact
 * plan the boot receiver already runs, including its guardian-process leg and its FPS-journal leg
 * -- so a creation that owes recovery dispatches the same work a reboot would. The self-start
 * carries the same canonical trigger the service already documents at
 * `startCalibrationObservation`: re-starting this service is *interpreted* as a durable-journal
 * recovery trigger.
 *
 * **The idle gate is load-bearing, not defensive padding.** A started lifetime that is PENDING,
 * APPLYING, ACTIVE or in RECOVERY already owns the record, and dispatching a second recovery on
 * top of it would restore a profile out from under a live apply -- the same hazard
 * `startCalibrationObservation` names when it refuses to re-start the service while foreground.
 * At `onCreate` the phase is IDLE by construction, so today this only documents the invariant;
 * it is what keeps a future second call site from turning this into that bug.
 *
 * Recovery clears or advances the journal and leaves foreground, so a healed device plans null on
 * its next creation: this cannot loop. A recovery that genuinely fails retains the record and is
 * retried on the next process creation, which is the intended retry, not a spin.
 */
internal object CreateTimeRecoveryDispatchPolicy {
    fun plan(
        startedLifetime: StartedServiceLifetime.Snapshot,
        hardwareWritesCompiled: Boolean,
        load: JournalLoadResult,
        fpsLoad: FpsLimiterJournalLoad = FpsLimiterJournalLoad.Empty,
    ): TweakerBootDispatchPlan? {
        if (!startedLifetime.isIdle) return null
        return TweakerBootDispatchPolicy.plan(
            trigger = RecoveryTrigger.SERVICE_CREATE,
            hardwareWritesCompiled = hardwareWritesCompiled,
            load = load,
            fpsLoad = fpsLoad,
        )
    }
}
