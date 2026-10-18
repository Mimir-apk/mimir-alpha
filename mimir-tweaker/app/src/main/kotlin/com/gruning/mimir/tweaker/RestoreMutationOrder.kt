package com.gruning.mimir.tweaker

/**
 * Restore normally reverses apply order, with two rules on top.
 *
 * **The vendor performance mode precedes the fan's final write** (policy 2026-10-13, "mode
 * first, then our fan", mirrored on exit). On both AYN devices SystemUI rewrites `fan_mode` from its
 * own memory on every `performance_mode` change (`is_quick_set_performance_and_fan_enable = 1`, see
 * [VendorFanCouplingSettle]). A fan restored BEFORE the mode was therefore overwritten by the mode's
 * restore moments later, and the Guardian's stock readback saw the coupled value instead of the
 * captured one (`Guardian stock unconfirmed fan1!=fan4`, Thor 2026-10-13). So after the plain
 * reversal the vendor mode is moved to sit immediately before the fan: the mode is written, its
 * coupled rewrite settles inside that write, and only then is the fan put back to what was captured.
 *
 * **Odin sessions that directly duty-drive the fan** ([TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES] --
 * SPORT and MANUAL alike, device-measured) keep their explicit exception: return fan authority to the
 * captured vendor mode FIRST, then reassert the captured compute state, so a manual duty is never
 * left running without its owner while compute goes back to Stock and heat rises. This was MANUAL-only
 * before ownership moved to SPORT; both modes stop the vendor's duty-regulation loop identically. With
 * the vendor mode in the plan that release step is then followed, in the renderer's own list only
 * ([logicalIds]), by a SECOND fan step after the mode: the release hands the duty back, the final step
 * puts the captured value behind SystemUI's rewrite. The signed envelope and MGDE ([indexed],
 * [indexedPersisted]) stay one entry per logical id -- the Guardian engine adds its own final fan
 * intent at runtime ([RestoreGuardianRunnerEngine.planRestore]) for the same reason.
 *
 * **Every order tw06 wrote still restores.** The historic apply order was fan -> vendor mode -> compute,
 * so its plain reversal already had the mode before the fan and these rules change nothing for a
 * non-duty-owned tw06 record; a duty-owned one gets the identical release-first list plus the final fan
 * step at the end, so a retained `lastCompletedRestoreBoundary` still names the same prefix.
 */
internal object RestoreMutationOrder {
    fun logicalIds(record: TransactionJournalRecord): List<String> {
        val applyOrder = HistoricApplyOrder.resolveLogicalIds(record)
        return order(
            applyOrder = applyOrder,
            manualFanOwned = record.appliedMutationProjection.any(::isManualFanMutation),
            isFan = { it == HardwareSnapshot.FAN_LOGICAL_ID },
            isVendorMode = { it == HardwareSnapshot.VENDOR_MODE_LOGICAL_ID },
            finalFanAfterVendorMode = true,
        )
    }

    fun indexed(mutations: List<LogicalMutation>): List<IndexedValue<LogicalMutation>> =
        order(
            applyOrder = mutations.withIndex().toList(),
            manualFanOwned = mutations.any(::isManualFanMutation),
            isFan = { it.value.logicalId == HardwareSnapshot.FAN_LOGICAL_ID },
            isVendorMode = { it.value.logicalId == HardwareSnapshot.VENDOR_MODE_LOGICAL_ID },
            finalFanAfterVendorMode = false,
        )

    fun indexedPersisted(
        mutations: List<PersistedAppliedMutation>,
    ): List<IndexedValue<PersistedAppliedMutation>> =
        order(
            applyOrder = mutations.withIndex().toList(),
            manualFanOwned = mutations.any(::isManualFanMutation),
            isFan = { it.value.logicalId == HardwareSnapshot.FAN_LOGICAL_ID },
            isVendorMode = { it.value.logicalId == HardwareSnapshot.VENDOR_MODE_LOGICAL_ID },
            finalFanAfterVendorMode = false,
        )

    private fun isManualFanMutation(mutation: LogicalMutation): Boolean =
        mutation.logicalId == HardwareSnapshot.FAN_LOGICAL_ID &&
            mutation.axis == TuningAxis.FAN &&
            mutation.kind == LogicalMutationKind.SETTINGS_PUT &&
            mutation.value in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES

    private fun isManualFanMutation(mutation: PersistedAppliedMutation): Boolean =
        mutation.logicalId == HardwareSnapshot.FAN_LOGICAL_ID &&
            mutation.axis == TuningAxis.FAN &&
            mutation.kind == LogicalMutationKind.SETTINGS_PUT &&
            mutation.value in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES

    /**
     * The one rule, over any element type: reverse, vendor mode immediately before the fan, then the
     * duty-owned release first, with the optional second fan step after the mode.
     */
    private fun <T> order(
        applyOrder: List<T>,
        manualFanOwned: Boolean,
        isFan: (T) -> Boolean,
        isVendorMode: (T) -> Boolean,
        finalFanAfterVendorMode: Boolean,
    ): List<T> {
        val reversed = vendorModeBeforeFan(applyOrder.asReversed(), isFan, isVendorMode)
        if (!manualFanOwned) return reversed
        val fan = reversed.singleOrNull(isFan)
            ?: error("Manual fan restore order missing unique fan mutation")
        val rest = reversed.filterNot(isFan)
        val finalFan = if (finalFanAfterVendorMode && rest.any(isVendorMode)) listOf(fan) else emptyList()
        return listOf(fan) + rest + finalFan
    }

    /** Pure reordering: the vendor-mode element moves to the slot just before the (unique) fan element. */
    fun <T> vendorModeBeforeFan(reversed: List<T>, isFan: (T) -> Boolean, isVendorMode: (T) -> Boolean): List<T> {
        val fanIndex = reversed.indexOfFirst(isFan)
        val vendorIndex = reversed.indexOfFirst(isVendorMode)
        if (fanIndex < 0 || vendorIndex < 0 || vendorIndex < fanIndex) return reversed
        val vendor = reversed[vendorIndex]
        val without = reversed.filterIndexed { index, _ -> index != vendorIndex }
        return without.take(fanIndex) + vendor + without.drop(fanIndex)
    }
}
