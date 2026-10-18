package com.gruning.mimir.tweaker

/** Signed fan targets for a session that admits ownership changes without restoring compute. */
internal val FAN_OWNERSHIP_TARGETS = listOf(TuningPlanner.FAN_SMART, TuningPlanner.FAN_MANUAL)

internal fun RestoreGuardianRestoreEntry.mayOwnManualFan(): Boolean =
    this is RestoreGuardianFanPutEntry &&
        (rendererAppliedIntermediates.singleOrNull() in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES ||
            rendererAppliedIntermediates == FAN_OWNERSHIP_TARGETS)

/** No other field, axis, authority, or lifecycle state can change through this journal entry point. */
internal object FanOwnershipAmendment {
    fun authorized(record: TransactionJournalRecord, envelope: RestoreGuardianEnvelope): Boolean =
        record.guardianBinding?.envelopeSha256 == envelope.digestSha256() &&
            GuardianArmExpectedIdentityDerivation.from(record)?.matches(envelope) == true &&
            envelope.restoreEntries.filterIsInstance<RestoreGuardianFanPutEntry>().singleOrNull()
                ?.rendererAppliedIntermediates == FAN_OWNERSHIP_TARGETS

    fun candidate(record: TransactionJournalRecord, target: Int): TransactionJournalRecord? {
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5 || record.phase != TransactionPhase.APPLIED) return null
        val plan = record.appliedPlan ?: return null
        if (target !in FAN_OWNERSHIP_TARGETS || plan.fanMode !in FAN_OWNERSHIP_TARGETS) return null
        val fan = record.appliedMutationProjection.singleOrNull { it.axis == TuningAxis.FAN } ?: return null
        if (fan.logicalId != HardwareSnapshot.FAN_LOGICAL_ID || fan.kind != LogicalMutationKind.SETTINGS_PUT ||
            fan.value != plan.fanMode || !fan.expectPresent
        ) return null
        val nextPlan = plan.copy(fanMode = target)
        return record.copy(
            planHash = PlanHash.ofPersisted(nextPlan),
            appliedPlan = nextPlan,
            appliedMutationProjection = record.appliedMutationProjection.map {
                if (it == fan) it.copy(value = target) else it
            },
        )
    }

    fun admits(existing: TransactionJournalRecord, next: TransactionJournalRecord): Boolean =
        next.appliedPlan?.fanMode?.let { candidate(existing, it) == next } == true
}
