package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFanControlTest {
    private val store = ActiveFanControlSelectionStore()

    @Test
    fun `manual curve is bounded and stepped, and survives a new session for the same profile`() {
        val active = active(generation = 7, transactionId = "tx-7")

        assertNull(store.update(active, PublicFanControlMode.MANUAL, -101))
        assertNull(store.update(active, PublicFanControlMode.MANUAL, 5))
        val accepted = store.update(active, PublicFanControlMode.MANUAL, -40)
        assertEquals(PublicFanControlMode.MANUAL, accepted?.mode)
        assertEquals(-40, accepted?.curveBias)
        assertEquals(
            FanThermalStrategy.AutoCurve(bias = FanCurveBias(-40)),
            store.current(active).strategy(),
        )

        // A new session identity (re-launch, renewed generation/transactionId) for the SAME
        // profileId ("quiet") must NOT reset the curve -- only a genuine profile change does
        // (see the per-profile round-trip test below). This used to reset to Auto; that was the
        // defect: the Manual selection should survive a profile change.
        val replacement = active(generation = 8, transactionId = "tx-8")
        val replacementSelection = store.current(replacement)
        assertEquals(PublicFanControlMode.MANUAL, replacementSelection.mode)
        assertEquals(-40, replacementSelection.curveBias)
        // But the identity half is always the CURRENT session's, never the stale one.
        assertEquals(8L, replacementSelection.generation)
        assertEquals("tx-8", replacementSelection.transactionId)
    }

    @Test
    fun `auto canonicalizes bias, and clear no longer discards the durable per-profile preference`() {
        val active = active(generation = 9, transactionId = "tx-9")
        assertNull(store.update(active, PublicFanControlMode.AUTO, 110))
        val neutral = store.update(active, PublicFanControlMode.AUTO, 100)
        assertEquals(0, neutral?.curveBias)
        assertEquals(
            FanThermalStrategy.AutoCurve(
                curve = FanCurveDefaults.forProfile("quiet"),
                bias = FanCurveBias(0),
            ),
            neutral?.strategy(),
        )
        store.update(active, PublicFanControlMode.MANUAL, 50)
        // clear() is retained only for existing fan-control-loop reset call sites (a
        // Smart-transitional tick, session teardown); it must not erase the durable per-profile
        // preference a player explicitly set.
        store.clear()
        val afterClear = store.current(active)
        assertEquals(PublicFanControlMode.MANUAL, afterClear.mode)
        assertEquals(50, afterClear.curveBias)
    }

    // --- Per-profile durability -------------------------------------------------------
    // Each profileId independently remembers its (mode, curveBias), including Auto/Manual choice.

    @Test
    fun `each profile remembers its own fan setting independently across switches`() {
        val balancedSession = active(generation = 1, transactionId = "tx-1", profileId = "balanced")
        val performanceSession =
            active(generation = 1, transactionId = "tx-1", profileId = "performance")

        store.update(balancedSession, PublicFanControlMode.MANUAL, 30)
        assertEquals(PublicFanControlMode.AUTO, store.current(performanceSession).mode)

        store.update(performanceSession, PublicFanControlMode.AUTO, 0)

        // Switch away and back: Balanced keeps Manual+30, Performance keeps Auto -- neither
        // switch disturbs the other profile's setting.
        val balancedAgain = store.current(active(generation = 2, transactionId = "tx-2", profileId = "balanced"))
        assertEquals(PublicFanControlMode.MANUAL, balancedAgain.mode)
        assertEquals(30, balancedAgain.curveBias)

        val performanceAgain =
            store.current(active(generation = 2, transactionId = "tx-2", profileId = "performance"))
        assertEquals(PublicFanControlMode.AUTO, performanceAgain.mode)
        assertEquals(0, performanceAgain.curveBias)

        // Round-trip once more, back to Balanced.
        val balancedThirdTime =
            store.current(active(generation = 3, transactionId = "tx-3", profileId = "balanced"))
        assertEquals(PublicFanControlMode.MANUAL, balancedThirdTime.mode)
        assertEquals(30, balancedThirdTime.curveBias)
    }

    @Test
    fun `a profile never customized before defaults to Auto zero`() {
        val neverSeen = active(generation = 1, transactionId = "tx-1", profileId = "performance")
        val selection = store.current(neverSeen)
        assertEquals(PublicFanControlMode.AUTO, selection.mode)
        assertEquals(0, selection.curveBias)
    }

    @Test
    fun `the returned selection always carries the CURRENT session identity, never a stale one`() {
        val first = active(generation = 1, transactionId = "tx-1", profileId = "balanced")
        store.update(first, PublicFanControlMode.MANUAL, 20)

        val second = active(generation = 2, transactionId = "tx-2", profileId = "balanced")
        val selection = store.current(second)

        // The preference (Manual/20) survived, but the identity is exactly the new session's --
        // never generation 1 / tx-1 from the dead session that originally set it.
        assertEquals(PublicFanControlMode.MANUAL, selection.mode)
        assertEquals(20, selection.curveBias)
        assertEquals(2L, selection.generation)
        assertEquals("tx-2", selection.transactionId)
    }

    @Test
    fun `a persisted store rejecting an invalid bias falls back to the Auto default rather than throwing`() {
        // validFanControlSelection would reject curveBias=41 (not a step of 10); a hand-rolled or
        // corrupted FanControlPreferenceStore that returns it anyway must never crash the caller
        // or inject the out-of-range bias into the returned selection.
        val corrupting = object : FanControlPreferenceStore {
            override fun load(): Map<String, FanControlProfilePreference> =
                mapOf("balanced" to FanControlProfilePreference(PublicFanControlMode.MANUAL, 41))

            override fun save(profileId: String, preference: FanControlProfilePreference) = Unit
        }
        val corruptedStore = ActiveFanControlSelectionStore(corrupting)
        val selection = corruptedStore.current(
            active(generation = 1, transactionId = "tx-1", profileId = "balanced"),
        )
        assertEquals(PublicFanControlMode.AUTO, selection.mode)
        assertEquals(0, selection.curveBias)
    }

    @Test
    fun `SharedPreferencesFanControlPreferenceStore rejects an out-of-range persisted bias on read`() {
        val backing = FakeSharedPreferences()
        backing.edit().putString("fan_control_mode_balanced", "MANUAL").putInt(
            "fan_control_bias_balanced",
            41,
        ).apply()
        val loaded = SharedPreferencesFanControlPreferenceStore(backing).load()
        assertNull(loaded["balanced"])
    }

    @Test
    fun `SharedPreferencesFanControlPreferenceStore rejects an unknown profileId key`() {
        val backing = FakeSharedPreferences()
        backing.edit().putString("fan_control_mode_stock", "MANUAL").putInt(
            "fan_control_bias_stock",
            20,
        ).apply()
        val loaded = SharedPreferencesFanControlPreferenceStore(backing).load()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `SharedPreferencesFanControlPreferenceStore round-trips a valid per-profile preference`() {
        val backing = FakeSharedPreferences()
        val persisted = SharedPreferencesFanControlPreferenceStore(backing)
        persisted.save("balanced", FanControlProfilePreference(PublicFanControlMode.MANUAL, -20))
        persisted.save("performance", FanControlProfilePreference(PublicFanControlMode.AUTO, 0))

        val reloaded = SharedPreferencesFanControlPreferenceStore(backing).load()
        assertEquals(
            FanControlProfilePreference(PublicFanControlMode.MANUAL, -20),
            reloaded["balanced"],
        )
        assertEquals(
            FanControlProfilePreference(PublicFanControlMode.AUTO, 0),
            reloaded["performance"],
        )
        assertNull(reloaded["quiet"])
    }

    @Test
    fun `manual capability requires exact system session signed fan plan sensor and writer`() {
        val active = active(generation = 10, transactionId = "tx-10")
        val capabilities = capabilities()
        assertTrue(
            manualFanControlAdmitted(
                active,
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
        val vendorSmart = active.copy(
            appliedTargetContract = requireNotNull(active.appliedTargetContract).copy(
                fanMode = TuningPlanner.FAN_SMART,
            ),
        )
        assertTrue(
            manualFanControlAdmitted(
                vendorSmart,
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
        // SPORT is the ordinary Odin entry default (TuningPlanner.selectedFanMode) and MANUAL
        // remains reachable via FanIntent.MANUAL_OVERRIDE -- both are
        // TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES and must be equally admitted: selecting the
        // MANUAL PublicFanControlMode curve-bias preset never requests a vendor fan_mode
        // transition (see the kdoc on manualFanControlAdmitted), so it is exactly as safe for an
        // already-SPORT-owned contract as for an already-MANUAL-owned one.
        listOf(TuningPlanner.FAN_SPORT, TuningPlanner.FAN_MANUAL).forEach { ownedMode ->
            val owned = active.copy(
                appliedTargetContract = requireNotNull(active.appliedTargetContract).copy(
                    fanMode = ownedMode,
                ),
            )
            assertTrue(
                "fanMode=$ownedMode must be admitted -- it is Mimir-duty-owned",
                manualFanControlAdmitted(
                    owned,
                    capabilities,
                    hardwareWritesCompiled = true,
                    manualPlanMatchesContract = true,
                    cpuSafetySensorAvailable = true,
                    gpuSensorAvailable = true,
                    mutationPortAvailable = true,
                    guardianJournalBound = true,
                ),
            )
        }
        // A vendor mode Mimir neither duty-owns nor stages from (SILENT) is still correctly
        // rejected: extending the set to SPORT did not loosen this into accepting anything.
        val silent = active.copy(
            appliedTargetContract = requireNotNull(active.appliedTargetContract).copy(
                fanMode = TuningPlanner.FAN_SILENT,
            ),
        )
        assertFalse(
            manualFanControlAdmitted(
                silent,
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
        assertFalse(
            manualFanControlAdmitted(
                active,
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = false,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
        assertFalse(
            manualFanControlAdmitted(
                active.copy(scope = ActiveSessionScope.LAUNCH),
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
        assertFalse(
            manualFanControlAdmitted(
                active,
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = false,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
        assertFalse(
            manualFanControlAdmitted(
                active,
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = false,
            ),
        )
        assertFalse(
            manualFanControlAdmitted(
                active,
                capabilities,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = false,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
    }

    // --- Fan control section availability (AUTO + curve bias vs. raw MANUAL entry) -------------
    //
    // The Fan control section (Auto plus curve bias) and raw PWM MANUAL entry are two separable
    // features conflated behind one flag before this lane. fanControlSectionAvailable is the
    // un-conflated gate: it must recognise every Mimir-duty-owned contract fanMode
    // (TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) on its own, independent of whether manual PWM
    // entry is separately qualified.

    @Test
    fun `fan section is offered for a Sport-owned contract even when manual entry is unqualified`() {
        assertTrue(
            fanControlSectionAvailable(
                manualEntryAvailable = false,
                autoExitAvailable = false,
                contractFanMode = TuningPlanner.FAN_SPORT,
            ),
        )
    }

    @Test
    fun `fan section is offered for a Manual-owned contract even when manual entry is unqualified`() {
        assertTrue(
            fanControlSectionAvailable(
                manualEntryAvailable = false,
                autoExitAvailable = false,
                contractFanMode = TuningPlanner.FAN_MANUAL,
            ),
        )
    }

    @Test
    fun `fan section is never offered for vendor Smart alone`() {
        assertFalse(
            fanControlSectionAvailable(
                manualEntryAvailable = false,
                autoExitAvailable = false,
                contractFanMode = TuningPlanner.FAN_SMART,
            ),
        )
    }

    @Test
    fun `fan section is never offered without an active session`() {
        assertFalse(
            fanControlSectionAvailable(
                manualEntryAvailable = false,
                autoExitAvailable = false,
                contractFanMode = null,
            ),
        )
    }

    @Test
    fun `fan section still respects manual entry and auto exit availability on their own`() {
        assertTrue(
            "qualified manual entry must still offer the section regardless of contract fanMode",
            fanControlSectionAvailable(
                manualEntryAvailable = true,
                autoExitAvailable = false,
                contractFanMode = null,
            ),
        )
        assertTrue(
            "an unconditional auto-exit must still offer the section regardless of contract fanMode",
            fanControlSectionAvailable(
                manualEntryAvailable = false,
                autoExitAvailable = true,
                contractFanMode = null,
            ),
        )
    }

    @Test
    fun `advancedCapabilitiesV6 wires the un-conflated section gate and advertises Manual whenever it is usable`() {
        // This class runs on plain JVM with android.os.Bundle stubbed to default returns, so it
        // cannot assert on an actual Bundle -- getBoolean would answer false whatever was written.
        // The marshalling half of this claim is therefore asserted as real behaviour in
        // AdvancedControlsBundleContractTest, which runs under Robolectric. What belongs here is
        // the SERVICE's wiring: that it derives the two fan inputs from the un-conflated gates
        // rather than from the raw manual/autoExit pair.
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun advancedCapabilitiesV6(): Bundle {",
            "private fun <T> onTransactionWorker(",
        )
        assertTrue(
            "the section gate must be built from fanControlSectionAvailable",
            fn.contains("val fanSectionAvailable = fanControlSectionAvailable("),
        )
        assertTrue(
            "the advertised-modes input must come from manualFanControlModeAdvertised",
            fn.contains("manualModeAdvertised = manualFanControlModeAdvertised("),
        )
        assertTrue(
            // 2026-08-25: the call moved from `manualFanControlAvailable` to
            // `manualFanControlAudit`, whose `admitted` IS that predicate's answer -- the audit
            // exists only so the same evaluation can also be logged conjunct by conjunct. The
            // qualification itself is still untouched, which is what this pins.
            "manual PWM entry qualification itself must be untouched",
            fn.contains("val audit = active?.let(::manualFanControlAudit)") &&
                fn.contains("val manualEntryAvailable = audit?.admitted == true"),
        )
        assertTrue(
            // [[instrument-the-whole-chain-not-the-leaf]]. The actuation path has logged its
            // declines since the ninth blocker; the gate that decides whether the row is OFFERED
            // logged nothing, so "fan shows unavailable" arrived with no readable cause and the
            // eleventh blocker cost a full diagnosis to name. A refusal must be readable in
            // PRODUCTION logcat.
            "the section decision must be observable, not inferred",
            fn.contains("logFanSectionAvailability(fanSectionAvailable, autoExitAvailable, audit)"),
        )
        assertTrue(
            "the section gate, not the raw manual/autoExit pair, must be what reaches the wire",
            fn.contains("fanSectionAvailable = fanSectionAvailable"),
        )
    }

    // --- Advertised MANUAL mode (advancedCapabilitiesV6's fanControlModes list) ----------------
    //
    // MANUAL here is the curve-bias preset (ActiveFanControlSelection.strategy), not raw PWM
    // entry. It is safe to advertise whenever setFanControlV6 would actually accept it: either
    // manualEntryAvailable (the SMART-staged transition path is qualified), or the active
    // contract already directly owns duty (TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES -- SPORT or
    // MANUAL), which setFanControlV6's own `requiresTransition = hardwareMode == FAN_SMART` gate
    // skips entirely.

    @Test
    fun `Manual is advertised for a Sport-owned contract even when manual entry is unqualified`() {
        assertTrue(
            manualFanControlModeAdvertised(
                manualEntryAvailable = false,
                contractFanMode = TuningPlanner.FAN_SPORT,
            ),
        )
    }

    @Test
    fun `Manual is advertised for a Manual-owned contract even when manual entry is unqualified`() {
        assertTrue(
            manualFanControlModeAdvertised(
                manualEntryAvailable = false,
                contractFanMode = TuningPlanner.FAN_MANUAL,
            ),
        )
    }

    @Test
    fun `Manual is advertised whenever manual entry is qualified even under vendor Smart`() {
        assertTrue(
            manualFanControlModeAdvertised(
                manualEntryAvailable = true,
                contractFanMode = TuningPlanner.FAN_SMART,
            ),
        )
    }

    @Test
    fun `Manual is not advertised without an active session`() {
        assertFalse(
            manualFanControlModeAdvertised(
                manualEntryAvailable = false,
                contractFanMode = null,
            ),
        )
    }

    @Test
    fun `advertising Manual for a Sport contract does not loosen the bias range or step refusal`() {
        assertTrue(
            manualFanControlModeAdvertised(
                manualEntryAvailable = false,
                contractFanMode = TuningPlanner.FAN_SPORT,
            ),
        )
        assertFalse(validFanControlSelection(PublicFanControlMode.MANUAL, 101))
        assertFalse(validFanControlSelection(PublicFanControlMode.MANUAL, 15))
        assertTrue(validFanControlSelection(PublicFanControlMode.MANUAL, -40))
    }

    // --- Fan ownership settle routing --------------------------------------------------------
    //
    // During an active session, vendor fan changes trigger renewed Mimir control. Earlier routing
    // adopted vendor SMART unconditionally for every RP/AYN device except the exact Odin id.
    // Measurements showed the vendor loop at 65% duty with the GPU at 88C. In a 2026-09-07 Odin
    // rearm window, contract fanMode=4 and hardware fan_mode=6 left duty pinned at 25000 through a
    // 28C climb and again at 30C. SMART therefore cannot remain the ordinary permanent case.
    // The SMART leg of fanOwnershipSettleRoute probes manualFanControlAvailable():
    // TRANSITION_TO_MANUAL when available, including through this router rather than only
    // TuningPlanner.FanIntent.MANUAL_OVERRIDE; ADOPT_VENDOR_SMART is the degraded case when manual
    // is unavailable or the transition is refused/fails downstream. Odin still signs mode 5
    // (SPORT) directly on its base plan (TuningPlanner.selectedFanMode), so ADOPT_ALREADY_OWNED
    // remains its ordinary case, without a staged Smart handoff or second mode-6 transition.
    // ODIN_3_VENDOR_FAN_CONTENTION_HOLDOFF remains retired; see AdvancedFanControl.kt's
    // retired-holdoff note for the device-log correction.

    @Test
    fun `fanOwnershipSettleRoute adopts an already-Sport contract exactly like Manual`() {
        val route = fanOwnershipSettleRoute(
            contractFanMode = TuningPlanner.FAN_SPORT,
            fanAxisAvailable = true,
            manualFanControlAvailable = {
                error("must not be evaluated for an already-owned contract")
            },
        )
        assertEquals(FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED, route)
    }

    @Test
    fun `fanOwnershipSettleRoute adopts an already-Manual contract exactly as before`() {
        val route = fanOwnershipSettleRoute(
            contractFanMode = TuningPlanner.FAN_MANUAL,
            fanAxisAvailable = true,
            manualFanControlAvailable = {
                error("must not be evaluated for an already-owned contract")
            },
        )
        assertEquals(FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED, route)
    }

    @Test
    fun `fanOwnershipSettleRoute rejects staging that never reached a qualified fan handoff`() {
        // A null contract mode on a device that HAS a fan axis is a genuine anomaly and still
        // rejects. This assertion used to carry no fanAxisAvailable at all, and that is precisely
        // how the Nova defect shipped: the only null-mode case anyone had written down was the
        // anomalous one, so the fan-less chassis inherited its rollback.
        assertEquals(
            FanOwnershipSettleRoute.REJECT_STAGING_INVALID,
            fanOwnershipSettleRoute(
                contractFanMode = null,
                fanAxisAvailable = true,
                manualFanControlAvailable = { error("must not be evaluated for invalid staging") },
            ),
        )
        assertEquals(
            FanOwnershipSettleRoute.REJECT_STAGING_INVALID,
            fanOwnershipSettleRoute(
                contractFanMode = TuningPlanner.FAN_SILENT,
                fanAxisAvailable = true,
                manualFanControlAvailable = { error("must not be evaluated for invalid staging") },
            ),
        )
        // A fan-BEARING contract on a fan-less chassis is the mirror anomaly, and the new route
        // must not swallow it: the added branch requires BOTH legs.
        assertEquals(
            FanOwnershipSettleRoute.REJECT_STAGING_INVALID,
            fanOwnershipSettleRoute(
                contractFanMode = TuningPlanner.FAN_SILENT,
                fanAxisAvailable = false,
                manualFanControlAvailable = { error("must not be evaluated for invalid staging") },
            ),
        )
    }

    @Test
    fun `a fan-less chassis adopts its CPU and GPU vector instead of rolling the apply back`() {
        // Nova identity line captured 2026-08-22:
        //   fan=false ... axes=[CPU, GPU] ... uncertified=[quiet, balanced, performance]
        // It advertised three profiles; selecting one made the daemon answer
        //   MimirTweakerSelection: requested=quiet prior=stock result=Rejected(FAN_PROFILE_STAGING_INVALID)
        // after applying and then rolling back. TuningPlanner.selectedFanMode returns null for
        // this chassis BY DESIGN, so the plan was correct and this router had no case for it.
        assertEquals(
            FanOwnershipSettleRoute.ADOPT_NO_FAN_AXIS,
            fanOwnershipSettleRoute(
                contractFanMode = null,
                fanAxisAvailable = false,
                manualFanControlAvailable = {
                    error("a chassis with no fan axis must never probe manual fan availability")
                },
            ),
        )
    }

    // The three-profile sweep on the REAL Nova OPP tables lives in
    // UncertifiedProfileApplyAdmissionTest, which already owns the device-truth fixture. Keeping
    // it there rather than copying the tables means one place to update when the device is
    // re-read -- the copied-fixture drift this codebase has been bitten by before.

    // Migrated from UncertifiedProfileApplyAdmissionTest.kt, 2026-08-22 (TR-05 migration): both
    // tests below directly exercise fanOwnershipSettleRoute / activeSystemSessionIsMimirOwned, the
    // settle machinery this file otherwise owns, rather than the Nova device-truth fixture that
    // file exists for. Assertions are unmoved; only the address changed.

    /**
     * The other half of the neighbour question: **a device that DOES have a live fan must be
     * unmoved.** Same fixture, one field flipped, so a pass cannot be explained by the profile
     * ids or the topology — only by the fan axis, which is the discriminator the fix added.
     *
     * A fan-bearing chassis whose contract lost its fan mode is a genuine anomaly and must still
     * reject: that is the fail-toward-cooling guard, and widening it was never the fix.
     */
    @Test
    fun `a chassis with a live fan axis keeps the staging guard exactly as it was`() {
        val fanBearing = capabilities().copy(
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
            fanNodeAvailable = true,
        )
        assertEquals(
            FanOwnershipSettleRoute.REJECT_STAGING_INVALID,
            fanOwnershipSettleRoute(
                contractFanMode = null,
                fanAxisAvailable = TuningAxis.FAN in fanBearing.supportedAxes,
                manualFanControlAvailable = { error("must not be probed for invalid staging") },
            ),
        )
        // And a real Odin-shaped owned mode still adopts, untouched.
        assertEquals(
            FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED,
            fanOwnershipSettleRoute(
                contractFanMode = TuningPlanner.FAN_SPORT,
                fanAxisAvailable = true,
                manualFanControlAvailable = { error("must not be probed for an owned contract") },
            ),
        )
    }

    /**
     * The ownership predicate itself, including the leg that must NOT be widened: a device with a
     * live fan axis still has to have Mimir owning the duty. Source-asserted for the same reason
     * as the settle-worker wiring test — the predicate is private to an Android `Service`.
     *
     * **Open coverage gap, not fixed here.** `activeSystemSessionIsMimirOwned`
     * was not changed with the fan-settle worker. Its own KDoc says it must mirror `fanOwnershipSettleRoute`'s adopt routes
     * "exactly... because they answer the same question at two moments and must not disagree" --
     * and after this wave adds a third route, [FanOwnershipSettleRoute.ADOPT_VENDOR_SMART], this
     * predicate has only two legs (duty-owned, or null-with-no-axis) and does NOT yet recognise a
     * SMART contract as Mimir-owned. On a fan-bearing SMART device (this wave's own definition of
     * done), every "re-select the currently active profile" call will read as "not mine" and pay
     * the full restore-then-reapply cost `re-selecting the active profile on a fan-less chassis
     * executes zero commands` (in `UncertifiedProfileApplyAdmissionTest`) proves was fixed for the
     * fan-LESS case. A possible production correction:
     * ```
     * if (contractFanMode == TuningPlanner.FAN_SMART) return true
     * ```
     * placed before the existing `if (contractFanMode != null) return false` line. The string
     * assertions below still pass unchanged either way -- they do not yet pin the missing leg.
     */
    @Test
    fun `the ownership predicate requires an absent fan axis, not merely an absent fan mode`() {
        val source = mainSourceText("MimirTweakerService.kt")
        val predicate = source.substringAfter("private fun activeSystemSessionIsMimirOwned(")
            .substringBefore("\n    }")
        assertTrue(
            "a Mimir-duty-owned mode must still qualify -- the Odin's answer cannot move",
            predicate.contains("contractFanMode in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES"),
        )
        assertTrue(
            "a non-null, non-owned mode must fail closed",
            predicate.contains("if (contractFanMode != null) return false"),
        )
        assertTrue(
            "and the null case must be gated on the live fan axis being absent",
            predicate.contains("TuningAxis.FAN !in resolved.supportedAxes"),
        )

        // Without this leg, every SMART-contracted fan-bearing session answers "not Mimir-owned"
        // and the caller churns restore-then-reapply at every session boundary.
        val smartLeg = "if (contractFanMode == TuningPlanner.FAN_SMART) return true"
        assertTrue(
            "a vendor-SMART contract must qualify as Mimir-owned -- ADOPT_VENDOR_SMART's mirror",
            predicate.contains(smartLeg),
        )
        // Ordering is the whole point: after the fail-closed guard, the SMART leg is dead code.
        assertTrue(
            "the SMART leg must precede the fail-closed guard or it can never be reached",
            predicate.indexOf(smartLeg) < predicate.indexOf("if (contractFanMode != null) return false"),
        )
    }

    // Before the SMART routing change, a SMART-staged contract adopted
    // ADOPT_VENDOR_SMART unconditionally, `manualFanControlAvailable` never even evaluated. Now the SMART leg now probes availability itself: this
    // is the differential for that change -- reverting the `if (manualFanControlAvailable())`
    // branch back to unconditional ADOPT_VENDOR_SMART makes the first assertion below fail
    // (SCHEDULE_RENEWAL expected, ADOPT_VENDOR_SMART returned) without ever touching the second.
    //
    // Previously, the branch this leg used to reach,
    // `TRANSITION_TO_MANUAL`, ran the mode-6 transition INLINE from the settle worker -- which is
    // exactly the "restore-and-reapply doubles every standing apply" defect D2 closes by entering
    // MANUAL directly from Stock in the SAME generation as the apply. A SMART contract reaching
    // settle now means D2's own MANUAL attempt on THIS apply already failed or was ineligible, so
    // this leg schedules a renewal for a later tick instead of transitioning inline a second time.
    @Test
    fun `fanOwnershipSettleRoute schedules a renewal when manual fan control is available`() {
        assertEquals(
            "manual fan control available -- SMART is no longer the ordinary case, it schedules " +
                "a renewal to Mimir MANUAL rather than transitioning inline a second time",
            FanOwnershipSettleRoute.SCHEDULE_RENEWAL,
            fanOwnershipSettleRoute(
                contractFanMode = TuningPlanner.FAN_SMART,
                fanAxisAvailable = true,
                manualFanControlAvailable = { true },
            ),
        )
    }

    @Test
    fun `TRANSITION_TO_MANUAL no longer exists on the settle route enum, and the settle worker no longer calls the transition`() {
        assertFalse(
            "D3 retires TRANSITION_TO_MANUAL from the settle route enum -- SCHEDULE_RENEWAL " +
                "replaces it; the transition stays reachable only from setFanControlV6 and " +
                "renewMimirFanOwnershipOnWorker",
            FanOwnershipSettleRoute.values().any { it.name == "TRANSITION_TO_MANUAL" },
        )
        val settleFn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
            "private fun rejectStagedFanOwnership(",
        )
        assertFalse(
            "the settle worker must not reference transitionFanControlModeOnWorker any more",
            settleFn.contains("transitionFanControlModeOnWorker("),
        )
    }

    @Test
    fun `fanOwnershipSettleRoute keeps adopting vendor Smart when manual fan control is unavailable`() {
        assertEquals(
            "manual fan control unavailable -- the degraded case, unchanged from the pre-09-07 default",
            FanOwnershipSettleRoute.ADOPT_VENDOR_SMART,
            fanOwnershipSettleRoute(
                contractFanMode = TuningPlanner.FAN_SMART,
                fanAxisAvailable = true,
                manualFanControlAvailable = { false },
            ),
        )
    }

    /**
     * Mode 6 is reachable, still not a one-way door -- but ONLY through the direct
     * [TuningPlanner.FanIntent.MANUAL_OVERRIDE] path, which derives `contractFanMode = FAN_MANUAL`
     * straight away (already duty-owned) rather than staging through Smart first. This route
     * ([FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED]) is unaffected by D3's
     * `TRANSITION_TO_MANUAL` -> `SCHEDULE_RENEWAL` rename -- a duty-owned contract never reaches
     * the SMART leg [fanOwnershipSettleRoute] renamed at all.
     */
    @Test
    fun `manual duty ownership is reached directly, never via a staged-Smart transition`() {
        val planner = TuningPlanner()
        val odin = capabilities().copy(deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE)
        val manualEntry = planner.plan(
            "balanced",
            "example.game",
            odin,
            TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )!!
        assertEquals(TuningPlanner.FAN_MANUAL, manualEntry.fanMode)
        assertEquals(
            "an explicit MANUAL_OVERRIDE plan is already duty-owned -- it never stages Smart first",
            FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED,
            fanOwnershipSettleRoute(
                contractFanMode = manualEntry.fanMode,
                fanAxisAvailable = true,
                manualFanControlAvailable = {
                    error("must not be probed for an already-owned contract")
                },
            ),
        )
    }

    /**
     * Settle-worker wiring for the new branch, in the same style as the ADOPT_ALREADY_OWNED wiring
     * test below: a source assertion, because the settle worker is a private Service method with
     * no callable seam.
     */
    @Test
    fun `settle worker wiring adopts a vendor-Smart contract and returns the staged result unmodified`() {
        val settleFn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
            "private fun rejectStagedFanOwnership(",
        )
        assertTrue(
            "the vendor-Smart route must return the staged result unmodified -- no transition, " +
                "no rollback, nothing to hand off",
            settleFn.contains("FanOwnershipSettleRoute.ADOPT_VENDOR_SMART -> return stagedResult"),
        )
    }

    /**
     * The
     * `SCHEDULE_RENEWAL` route's own settle-worker wiring, source-asserted for the same reason as
     * its `ADOPT_VENDOR_SMART` sibling above -- the settle worker is a private Service method with
     * no callable seam.
     *
     * **Replaces the pre-D3 `TRANSITION_TO_MANUAL` pin this test used to be**, which ran the
     * mode-6 transition inline from the settle worker and classified its result through
     * [fanOwnershipSettleOutcome]. The select path already attempted MANUAL directly
     * from Stock in the SAME generation as the apply -- a SMART contract reaching this branch means
     * that attempt already failed or was ineligible, so the settle worker no longer transitions
     * inline a second time: it returns `stagedResult` UNMODIFIED and begins a generation-keyed
     * renewal (carrying D2's own fallback code, or [SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE] when
     * D2 never attempted MANUAL at all) for a later tick to retry.
     */
    @Test
    fun `settle worker SCHEDULE_RENEWAL begins a renewal and returns the staged result unmodified, never transitioning inline`() {
        val settleFn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
            "private fun rejectStagedFanOwnership(",
        )
        val scheduleIndex = settleFn.indexOf("FanOwnershipSettleRoute.SCHEDULE_RENEWAL ->")
        assertTrue(
            "the FanOwnershipSettleRoute.SCHEDULE_RENEWAL branch must be present",
            scheduleIndex >= 0,
        )
        val branch = settleFn.substring(scheduleIndex)

        assertTrue(
            "the branch must begin a generation-keyed renewal for a later tick to retry",
            branch.contains("fanOwnershipRenewal = FanOwnershipRenewalPolicy.begin("),
        )
        assertTrue(
            "the branch must carry D2's own fallback code, defaulting to " +
                "MANUAL_NOT_ATTEMPTED when D2 never attempted MANUAL at all",
            branch.contains("selectPathFanEntryRenewalCode("),
        )
        assertTrue(
            "the branch must return the staged result UNMODIFIED, not a new failure " +
                "TransactionResult",
            branch.contains("return stagedResult"),
        )
        assertFalse(
            "the SCHEDULE_RENEWAL branch must never call the profile-rollback helper -- that " +
                "would roll a STILL-ACTIVE staged session back",
            branch.contains("rejectStagedFanOwnership("),
        )
    }

    // TR-08 retires the Odin 3 device
    // profile's automatic-entry SPORT override in TuningPlanner.selectedFanMode -- ordinary entry
    // now derives vendor Smart, same as every other RP/AYN device, so its settle route changes from
    // ADOPT_ALREADY_OWNED to ADOPT_VENDOR_SMART. The explicit MANUAL_OVERRIDE half is unmoved: mode
    // 6 remains reachable only through that direct path, unaffected by this ticket.
    @Test
    fun `Odin entry now derives vendor Smart and an explicit manual override still preserves the exact CPU and GPU vector`() {
        val planner = TuningPlanner()
        val odin = capabilities().copy(
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
            cpuPolicies = listOf(
                CpuPolicy(
                    id = 0,
                    path = "/policy0",
                    cpuIds = listOf(0, 1, 2, 3),
                    availableFrequenciesKhz = listOf(1_996_800, 2_246_400, 2_496_000, 3_072_000),
                    minKhz = 1_996_800,
                    maxKhz = 3_072_000,
                    currentKhz = 3_072_000,
                ),
                CpuPolicy(
                    id = 6,
                    path = "/policy6",
                    cpuIds = listOf(6, 7),
                    availableFrequenciesKhz = listOf(1_996_800, 2_246_400, 2_496_000, 2_841_600),
                    minKhz = 1_996_800,
                    maxKhz = 2_841_600,
                    currentKhz = 2_841_600,
                ),
            ),
            gpuFrequenciesKhz = listOf(
                305_000, 400_000, 441_600, 490_000, 525_000, 587_000, 670_000, 905_000,
            ),
        )

        val smartEntry = planner.plan("performance", "example.game", odin)!!
        val manualOverride = planner.plan(
            "performance",
            "example.game",
            odin,
            TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )!!

        assertEquals(TuningPlanner.FAN_SMART, smartEntry.fanMode)
        assertEquals(TuningPlanner.FAN_MANUAL, manualOverride.fanMode)
        assertTrue(smartEntry.cpuMaxKhz.isNotEmpty())
        assertEquals(smartEntry.cpuMaxKhz, manualOverride.cpuMaxKhz)
        assertEquals(smartEntry.gpuMaxPowerLevel, manualOverride.gpuMaxPowerLevel)

        // When manual fan control is unavailable on this Odin fixture, entry needs no settle-worker
        // transition. ADOPT_VENDOR_SMART is the degraded case; when manual becomes available,
        // TRANSITION_TO_MANUAL is the ordinary route.
        assertEquals(
            FanOwnershipSettleRoute.ADOPT_VENDOR_SMART,
            fanOwnershipSettleRoute(
                contractFanMode = smartEntry.fanMode,
                fanAxisAvailable = TuningAxis.FAN in odin.supportedAxes,
                manualFanControlAvailable = { false },
            ),
        )
    }

    @Test
    fun `settle worker wiring adopts an already-owned contract before ever considering a renewal`() {
        val settleFn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
            "private fun rejectStagedFanOwnership(",
        )
        assertTrue(
            "already-owned route must adopt Auto and return stagedResult unmodified -- no " +
                "transition, no rollback",
            settleFn.contains("FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED -> {"),
        )
        val routeIndex = settleFn.indexOf("fanOwnershipSettleRoute(")
        // D3 retires the inline transition from this worker entirely (see the SCHEDULE_RENEWAL
        // pin above) -- `transitionFanControlModeOnWorker(` is no longer present here at all, so
        // ordering is asserted against the SCHEDULE_RENEWAL branch instead, the settle worker's
        // only other route besides ADOPT_ALREADY_OWNED that does non-trivial work.
        val scheduleRenewalIndex = settleFn.indexOf("FanOwnershipSettleRoute.SCHEDULE_RENEWAL ->")
        assertTrue("fanOwnershipSettleRoute(...) must be present", routeIndex >= 0)
        assertTrue(
            "FanOwnershipSettleRoute.SCHEDULE_RENEWAL branch must be present",
            scheduleRenewalIndex >= 0,
        )
        assertTrue(
            "the route decision must be made before any branch can be reached",
            routeIndex < scheduleRenewalIndex,
        )
    }

    @Test
    fun `the ordinary already-owned settle branch never overwrites a profile's persisted Manual selection`() {
        // This is the common case post-lane (Odin 3 enters SPORT directly on every profile
        // apply). An unconditional fanControlSelections.update(staged, AUTO, 0) here would stomp
        // a profile's durable Manual selection back to Auto on every switch back to it --
        // ActiveFanControlSelectionStore.current() already derives the right answer (this
        // profile's persisted preference, or Auto/0 the first time) with no explicit call needed.
        val settleFn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
            "private fun rejectStagedFanOwnership(",
        )
        val adoptBranch = sliceBetween(
            settleFn,
            "FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED -> {",
            "FanOwnershipSettleRoute.REJECT_STAGING_INVALID ->",
        )
        assertFalse(
            "the already-owned branch must not force an explicit AUTO/0 update",
            adoptBranch.contains("fanControlSelections.update("),
        )
    }

    @Test
    fun `user fan-control command checks manual availability before declaring unavailable, and publicFailure never restores`() {
        val service = mainSourceText("MimirTweakerService.kt")
        val setFanControlFn = sliceBetween(
            service,
            "override fun setFanControlV6(",
            "override fun getFpsLimiterCapabilityV7(",
        )
        assertTrue(
            "setFanControlV6 must gate a Smart-staged contract on manual availability",
            setFanControlFn.contains("!manualFanControlAvailable(active)"),
        )
        val availabilityIndex = setFanControlFn.indexOf("!manualFanControlAvailable(active)")
        val transitionIndex = setFanControlFn.indexOf("transitionFanControlModeOnWorker(")
        assertTrue("manualFanControlAvailable(...) must be present", availabilityIndex >= 0)
        assertTrue("transitionFanControlModeOnWorker(...) must be present", transitionIndex >= 0)
        assertTrue(
            "availability must be checked -- and able to return early -- before any transition attempt",
            availabilityIndex < transitionIndex,
        )

        val publicFailureFn = sliceBetween(
            service,
            "private fun publicFailure(",
            "private fun Bundle.withApiVersion(",
        )
        assertFalse(
            "publicFailure must never roll back the active profile",
            publicFailureFn.contains("restoreBound"),
        )
    }

    // --- advancedCapabilitiesV6 / setFanControlV6 null-contract crash (// MimirTweakerService.advancedCapabilitiesV6 threw IllegalStateException "Required value was
    // null" 22 times uncaught across the Binder) ------------------------------------------------
    //
    // ActiveFanControlSelectionStore.current() requires a resolved appliedTargetContract
    // (checkNotNull below), because it needs the profileId that contract carries. A session can be
    // active (supervisor.activeSession() != null) before its target contract has been reconstructed
    // from the authenticated APPLIED journal record -- AppliedTargetContract
    // .fromAuthenticatedAppliedRecord fails closed (returns null) on several honest conditions:
    // schema mismatch, phase not yet APPLIED, plan-hash mismatch, an empty mutation projection. Two
    // MimirTweakerService call sites reached current() without checking for that window first.

    @Test
    fun `current throws Required value was null for a session whose target contract has not resolved yet`() {
        // Reproduces the exact exception observed on-device: java.lang.IllegalStateException:
        // Required value was null. This is current()'s own documented invariant (checkNotNull,
        // AdvancedFanControl.kt) and is intentionally left in place -- callers must check
        // appliedTargetContract != null before calling current(), not current() itself.
        val activeWithoutContract = active(generation = 11, transactionId = "tx-11")
            .copy(appliedTargetContract = null)
        val thrown = assertThrows(IllegalStateException::class.java) {
            store.current(activeWithoutContract)
        }
        assertEquals("Required value was null.", thrown.message)
    }

    @Test
    fun `advancedCapabilitiesV6 guards current on a resolved target contract before calling it`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun advancedCapabilitiesV6(): Bundle {",
            "private fun <T> onTransactionWorker(",
        )
        assertTrue(
            "selection must only reach fanControlSelections::current for a session whose " +
                "appliedTargetContract has resolved -- a bare active?.let(...) reaches current() " +
                "with a null contract and throws uncaught across the Binder",
            fn.contains(
                "selection = active?.takeIf { it.appliedTargetContract != null }" +
                    "?.let(fanControlSelections::current),",
            ),
        )
    }

    @Test
    fun `setFanControlV6 rejects a session whose target contract has not resolved instead of crashing`() {
        val setFanControlFn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "override fun setFanControlV6(",
            "override fun getFpsLimiterCapabilityV7(",
        )
        assertTrue(
            "a null appliedTargetContract must itself be rejected before " +
                "fanControlSelections.current(active) is reached -- the old " +
                "`activeContract != null && ...` gate short-circuited to false and fell through " +
                "into current() uncaught",
            setFanControlFn.contains("if (activeContract == null ||"),
        )
        val guardIndex = setFanControlFn.indexOf("if (activeContract == null ||")
        val currentIndex = setFanControlFn.indexOf("fanControlSelections.current(active)")
        assertTrue("both must be present", guardIndex >= 0 && currentIndex >= 0)
        assertTrue(
            "the null-contract guard must run before current() is ever called",
            guardIndex < currentIndex,
        )
    }

    // --- Restore-reason legibility (a dead privileged transport must
    // never be silent -- "the profile drops to stock mid-game and the player is told nothing") ---

    @Test
    fun `every missing-telemetry restore reason classifies as a transport death`() {
        listOf(
            "MISSING_TELEMETRY_ANDROID",
            "MISSING_TELEMETRY_CPU",
            "MISSING_TELEMETRY_GPU",
            "MISSING_TELEMETRY_BATTERY",
            "MISSING_TARGET_CONTRACT_TELEMETRY",
        ).forEach { reason ->
            assertTrue(reason, isTelemetryTransportRestoreReason(reason))
        }
    }

    @Test
    fun `non-telemetry restore reasons do not classify as a transport death`() {
        listOf(
            "TARGET_LOSS",
            "OWNER_TOKEN_DIED",
            "LEASE_EXPIRED",
            "HARD_LEASE_EXPIRED",
            "ACTIVATION_TIMEOUT",
            "ELAPSED_CLOCK_REGRESSION",
            "TARGET_CONTRACT_DRIFT_CPU",
            "TARGET_REPAIR_RATE_LIMITED",
        ).forEach { reason ->
            assertFalse(reason, isTelemetryTransportRestoreReason(reason))
        }
    }

    @Test
    fun `advancedCapabilitiesV6 forwards the last recorded restore reason`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun advancedCapabilitiesV6(): Bundle {",
            "private fun <T> onTransactionWorker(",
        )
        // Fan-fence fix F4 (2026-10-14): every restore family, not the supervisor's alone -- the
        // seam is GuardianRestoreObservability.lastRestoreReason, still never a bespoke copy.
        assertTrue(
            "the wire bundle must read GuardianRestoreObservability.lastRestoreReason, not a bespoke copy",
            fn.contains("lastRestoreReason = GuardianRestoreObservability.lastRestoreReason,"),
        )
    }

    /**
     * **The ninth manual-fan blocker, pinned at its call site because that is where it lived.**
     * 2026-08-23: `transitionFanControlModeOnWorker` carried its own
     * `productQualificationResolver.qualifiedManualFanPlan(...) ?: return publicFailure(...)` --
     * a second, independent copy of the signed-manifest dependency, separate from the one
     * `manualFanControlAvailable` used. Retiring only the availability predicate produced a build
     * where the row OFFERED Manual and the actuation silently refused it, as observed
     * on device. A pure-function pin cannot catch that: the predicate was fine, the call site
     * never asked it. Source-text is the right instrument here, and this file already uses it.
     */
    @Test
    fun `the manual fan transition asks code, not the signed manifest`() {
        // Comment lines are stripped before asserting. The retirement note left in the source
        // quotes the old `qualifiedManualFanPlan(...)` call verbatim, which is exactly the kind of
        // prose that should NOT satisfy or violate a claim about what the code does -- in either
        // direction. Judge the code.
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun transitionFanControlModeOnWorker(",
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
        ).lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "the Manual transition must not resolve through qualifiedManualFanPlan -- no signed " +
                "sequence has ever carried a manual plan, so that call refuses on every device",
            fn.contains("qualifiedManualFanPlan"),
        )
        assertTrue(
            "it must gate on the code-resident derivation instead",
            fn.contains("manualFanHandoffPlanAdmitted(derivedManualPlan, contract, operationCapabilities)"),
        )
        assertTrue(
            "and derive that plan with the explicit MANUAL_OVERRIDE intent",
            fn.contains("fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE"),
        )
        assertFalse(
            "the handoff floor must be code-resident, not read off the manifest plan",
            fn.contains("minimumDutyPercent = qualified.minimumDutyPercent"),
        )
    }

    /**
     * The production chain previously had zero `Log` calls, leaving no diagnostics for a failure.
     * Pin that every stage which can decline names itself in the log.
     */
    @Test
    fun `every fan-control decline names itself in the log`() {
        val text = mainSourceText("MimirTweakerService.kt")
        listOf(
            "decline=FAN_CONTROL_UNAVAILABLE stage=availability",
            "decline=FAN_CONTROL_UNAVAILABLE stage=manualPlan",
            "decline=FAN_HANDOFF_BUSY stage=handoffPrepare",
            "decline=FAN_HANDOFF_UNAVAILABLE stage=telemetrySample",
            "decline=FAN_HANDOFF_UNAVAILABLE stage=entryDutySettle",
            "stage=fanAmend",
        ).forEach { marker ->
            assertTrue("fan-control stage must be observable: $marker", text.contains(marker))
        }
        assertTrue("the request itself must be observable", text.contains("\"request mode=\$requestedMode"))
        assertTrue("and the apply outcome", text.contains("\"transition fan code=\${result.code}"))
    }

    /**
     * **Found in review, 2026-08-23.** `transitionFanControlModeOnWorker` never consults
     * `requestedMode` before `applyProfile(FanIntent.MANUAL_OVERRIDE)` -- it reads it only
     * afterwards, to write the selection record. So while the dispatch gated the transition on the
     * contract alone, pressing **AUTO** against a vendor-SMART contract drove the device INTO
     * Mimir-owned fan mode 6, contradicting the requested Auto mode and the row's copy.
     *
     * It was previously unreachable -- the manifest gate refused the path on every device
     * -- so retiring that gate is what turned a dormant wrong-direction branch into a live defect.
     * Pinned at the dispatch, where the decision is, rather than inside the worker, which still
     * hardcodes MANUAL_OVERRIDE by design (it is the manual entry path).
     */
    @Test
    fun `entry needs an explicit Manual request, and there is a way back out to vendor Auto`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "override fun setFanControlV6(",
            "override fun getFpsLimiterCapabilityV7(",
        ).lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")

        // ENTER: only an explicit Manual request may take a Smart contract into duty ownership.
        assertTrue(
            "entry must require an explicit Manual request, not merely a Smart contract -- " +
                "otherwise AUTO drives the player into fan mode 6",
            fn.contains("hardwareMode == TuningPlanner.FAN_SMART &&") &&
                fn.contains("requestedMode == PublicFanControlMode.MANUAL"),
        )

        // EXIT must allow returning from Manual to Auto.
        assertTrue(
            "an AUTO request against a duty-owned contract must transition back to vendor Smart",
            fn.contains("hardwareMode == TuningPlanner.FAN_MANUAL &&") &&
                fn.contains("requestedMode == PublicFanControlMode.AUTO"),
        )
        assertTrue(
            "the exit gate must mirror advancedCapabilitiesV6's autoExitAvailable -- a looser gate " +
                "offers an exit the daemon then refuses",
            fn.contains("fanTransitionJournalBound(active)"),
        )
        assertTrue(
            "and the exit must derive vendor Smart, not re-enter manual",
            fn.contains("TuningPlanner.FanIntent.PROFILE_AUTO"),
        )

        val gateIndex = fn.indexOf("requestedMode == PublicFanControlMode.MANUAL")
        val transitionIndex = fn.indexOf("transitionFanControlModeOnWorker(")
        assertTrue("the gate must be present", gateIndex >= 0)
        assertTrue("the transition call must be present", transitionIndex >= 0)
        assertTrue(
            "and the request checks must gate the call, not follow it",
            gateIndex < transitionIndex,
        )
    }

    @Test
    fun `fan transitions leave the supervisor running through every return`() {
        val fn = sliceBetween(mainSourceText("MimirTweakerService.kt"),
            "private fun transitionFanControlModeOnWorker(",
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }
        assertFalse(fn.contains("stopMonitor()"))
        assertFalse(fn.contains("supervisor.clearSession()"))
    }

    /**
     * **THE TENTH BLOCKER, pinned at its feed.** `manualFanControlAdmitted` ANDs a CPU-safety
     * sensor conjunct, and the service fed it `cpuPackageTempC` -- populated only for the
     * Snapdragon-8-Elite family. So manual fan stayed refused on the Nova and the RP5 long after
     * all nine device-id and manifest gates had moved, and the "family-wide" claim was false.
     *
     * It was invisible to the sweep that found the other nine because it is not a device-id test.
     * It is also not fixed by widening: `HardwareCapabilities`' own KDoc already reserves safety to
     * hottest `cpuTempC` and calls `cpuPackageTempC` a user-facing MEAN, so the service was
     * contradicting the model. Feeding the documented sensor closes the blocker AND makes the
     * intervention fire earlier on every device, since hottest >= mean by construction.
     *
     * Pinned at the feed, not the predicate: the predicate was always fine, it was being handed the
     * wrong sensor -- the same shape as the ninth blocker, where the predicate was fine and the call
     * site never asked it.
     */
    @Test
    fun `the CPU safety witness is the hottest zone, and admission and the controller agree on it`() {
        val service = mainSourceText("MimirTweakerService.kt").lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertFalse(
            "no safety path may read cpuPackageTempC -- it is a user-facing MEAN and is populated " +
                "only for the 8-Elite family, which is what kept manual fan off the Nova",
            service.contains("cpuSafetySensorAvailable = thermal?.cpuPackageTempC") ||
                service.contains("cpuSafetyTempC = snap.cpuPackageTempC") ||
                service.contains("cpuSafetyTempC = observed.cpuPackageTempC"),
        )
        assertTrue(
            "admission must gate on the hottest classified CPU zone",
            service.contains("cpuSafetySensorAvailable = thermal?.cpuTempC != null"),
        )
        assertTrue(
            "and the controller must be fed the same sensor admission gated on -- otherwise a " +
                "device is admitted that the first controller tick then fails safe on",
            service.contains("cpuSafetyTempC = snap.cpuTempC"),
        )
    }

    // --- E3: ownerEligibleForFanHardwareWrite ---------------------------------------------------

    @Test
    fun `ownerEligibleForFanHardwareWrite requires a live, uncancelled owner bound to the exact token`() {
        val alive = PublicOwnerSnapshot(
            tokenId = 7L,
            requestId = "r1",
            ownerUid = 1000,
            inFlight = false,
            alive = true,
            cancelled = false,
            generation = 1L,
            transactionId = "tx-1",
        )
        assertTrue(ownerEligibleForFanHardwareWrite(alive, expectedTokenId = 7L))
        assertFalse(
            "a null owner (nobody registered) must never admit a write",
            ownerEligibleForFanHardwareWrite(null, expectedTokenId = 7L),
        )
        assertFalse(
            "a dead owner must never admit a write",
            ownerEligibleForFanHardwareWrite(alive.copy(alive = false), expectedTokenId = 7L),
        )
        assertFalse(
            "a cancelled owner must never admit a write",
            ownerEligibleForFanHardwareWrite(alive.copy(cancelled = true), expectedTokenId = 7L),
        )
        assertFalse(
            "a different owner's token must never admit a write on this token's behalf",
            ownerEligibleForFanHardwareWrite(alive, expectedTokenId = 8L),
        )
    }

    // --- E4: vendorFloorLearningAllowed / suppression stamp source pins -------------------------

    @Test
    fun `vendorFloorLearningAllowed is false strictly before the suppression window elapses, true once it has`() {
        assertFalse(vendorFloorLearningAllowed(nowElapsedMs = 1_000L, suppressedUntilElapsedMs = 1_001L))
        assertTrue(vendorFloorLearningAllowed(nowElapsedMs = 1_001L, suppressedUntilElapsedMs = 1_001L))
        assertTrue(vendorFloorLearningAllowed(nowElapsedMs = 1_002L, suppressedUntilElapsedMs = 1_001L))
    }

    /**
     * E4 : every Mimir-initiated
     * `fan_mode` write must be followed by the floor-learning suppression stamp -- the transition
     * entry (`transitionFanControlModeOnWorker`), the reassert re-entry
     * (`prepareManualFanReentryForReassert`), the post-control watch's own
     * `forceCoolingSmartReassert()` call, AND the select path's own hand-to-4 write
     * (`selectSystemProfileOnWorker`) -- all four are pinned below.
     *
     * The test body also pins the fourth site, the select path's hand-to-4 write
     * (see the `selectFn` block). All four Mimir-initiated writes must stamp suppression.
     */
    @Test
    fun `every hand-to-4 write in the transition entry and the reassert re-entry stamps floor-learning suppression`() {
        val service = mainSourceText("MimirTweakerService.kt")
        val entryFn = sliceBetween(
            service,
            "private fun transitionFanControlModeOnWorker(",
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
        )
        assertTrue(
            "the entry's hand-to-4 write must be present",
            entryFn.contains("handFanToVendorSmartBound()"),
        )
        assertTrue(
            "the entry's hand-to-4 write must be followed by the suppression stamp",
            entryFn.indexOf("stampVendorFloorLearningSuppression(") >
                entryFn.indexOf("handFanToVendorSmartBound()"),
        )

        val reentryFn = sliceBetween(
            service,
            "private fun prepareManualFanReentryForReassert(",
            "private fun renewMimirFanOwnershipOnWorker(",
        )
        assertTrue(
            "the reassert re-entry's hand-to-4 write must be present",
            reentryFn.contains("directHardwareCommands?.handFanToVendorSmartForRenewal()"),
        )
        assertTrue(
            "the reassert re-entry's hand-to-4 write must be followed by the suppression stamp",
            reentryFn.indexOf("stampVendorFloorLearningSuppression(") >
                reentryFn.indexOf("directHardwareCommands?.handFanToVendorSmartForRenewal()"),
        )
        // D2's select-path hand-to-4 (executor E's own deviation 1, closed in review): the same
        // full-PWM-then-4 write, so the same 15 s learning hold.
        val selectFn = sliceBetween(
            service,
            "private fun selectSystemProfileOnWorker(",
            "private fun settleSystemWideStockRuntime(",
        )
        assertTrue(
            "the select path's hand-to-4 write must be present",
            selectFn.contains("directHardwareCommands?.handFanToVendorSmartForRenewal()"),
        )
        // indexOf(needle, from) below, not a bare selectFn.indexOf(stamp): F1 (spec §5, second
        // review) adds a SECOND, EARLIER stamp call inside this same function's `restore` lambda
        // (before the hand-to-4 write, which lives in the `apply` lambda further down) -- a bare
        // first-occurrence search would find that one instead and read as "the stamp precedes the
        // write", failing this pin for the wrong reason. Search only from the hand-to-4 call
        // itself, matching the other three sites' own (single-occurrence) pins above.
        val selectHandToFourIndex = selectFn.indexOf("directHardwareCommands?.handFanToVendorSmartForRenewal()")
        assertTrue(
            "the select path's hand-to-4 write must be followed by the suppression stamp",
            selectFn.indexOf("stampVendorFloorLearningSuppression(", selectHandToFourIndex) >
                selectHandToFourIndex,
        )

        // the post-control watch acts through PostControlHoldController,
        // built in startPostControlWatch's section on the port's forceCoolingSmartReassert() with
        // the suppression stamp as its onFanWrite. The ORDER lives in the controller's own
        // ForceCooling branch (force() first, onFanWrite() on confirmation), so that is what is
        // pinned -- not the argument order of the constructor call.
        val postControlTick = sliceBetween(
            service,
            "private fun startPostControlWatch() {",
            "private fun stopPostControlWatch() {",
        )
        assertTrue(
            "the post-control watch must be built on forceCoolingSmartReassert()",
            postControlTick.contains("directHardwareCommands?.forceCoolingSmartReassert()"),
        )
        assertTrue(
            "the post-control watch's onFanWrite must be the suppression stamp",
            postControlTick.contains("onFanWrite = { stampVendorFloorLearningSuppression() }"),
        )
        val forceBranch = sliceBetween(
            mainSourceText("PostControlHold.kt"),
            "is PostControlThermalDecision.ForceCooling -> {",
            "is PostControlThermalDecision.EnterHold ->",
        )
        val forceCall = forceBranch.indexOf("force()")
        val stamp = forceBranch.indexOf("onFanWrite()")
        assertTrue("the controller's force must be followed by the suppression stamp", forceCall in 0 until stamp)
        assertTrue("the stamp follows a CONFIRMED force only", forceBranch.indexOf("CheckedCommandStatus.CONFIRMED") in forceCall until stamp)
    }

    @Test
    fun `observeVendorSmartDuty consults vendorFloorLearningAllowed before folding a sample in`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun observeVendorSmartDuty() {",
            "private fun fanTransitionJournalBound(",
        )
        val allowedIndex = fn.indexOf("vendorFloorLearningAllowed(")
        val observeIndex = fn.indexOf("fanController.observeVendorDuty(")
        assertTrue("vendorFloorLearningAllowed(...) must be consulted", allowedIndex >= 0)
        assertTrue("fanController.observeVendorDuty(...) must still be present", observeIndex >= 0)
        assertTrue(
            "the allowed check must gate the fold, i.e. run first",
            allowedIndex < observeIndex,
        )
    }

    // --- E5: renewMimirFanOwnershipOnWorker's own scope-guard wiring ----------------------------

    /**
     * E5 : a non-SYSTEM scope
     * declines `NOT_SYSTEM_SCOPE` before any transition attempt, clears the renewal state, and
     * never reschedules via `FanOwnershipRenewalPolicy.afterAttempt`. `renewalScopeAdmitted`'s own
     * pure pins live in `FanOwnershipRenewalTest`; this is the source-text pin for the service's
     * own wiring (that function has no host-test seam for a live session today).
     */
    @Test
    fun `renewMimirFanOwnershipOnWorker declines NOT_SYSTEM_SCOPE before any transition attempt, with no reschedule`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun renewMimirFanOwnershipOnWorker(",
            "private fun transitionFanControlModeOnWorker(",
        )
        val scopeCheckIndex = fn.indexOf("renewalScopeAdmitted(")
        val transitionCallIndex = fn.indexOf("transitionFanControlModeOnWorker(")
        assertTrue("renewalScopeAdmitted(...) must be consulted", scopeCheckIndex >= 0)
        assertTrue("the transition call must still be present", transitionCallIndex >= 0)
        assertTrue(
            "the scope guard must run before the transition is ever attempted",
            scopeCheckIndex < transitionCallIndex,
        )

        // Sliced to the guard's OWN branch only (up to its own `return false`) -- not all the way
        // to the transition call, which would also sweep in the unrelated OWNER_UNAVAILABLE
        // branches below it that legitimately DO call afterAttempt.
        val guardReturnIndex = fn.indexOf("return false", scopeCheckIndex)
        assertTrue("the guard branch must return false", guardReturnIndex in scopeCheckIndex until transitionCallIndex)
        val scopeGuardBranch = fn.substring(scopeCheckIndex, guardReturnIndex)
        assertTrue(
            "the NOT_SYSTEM_SCOPE code must be present in the guard branch",
            scopeGuardBranch.contains("NOT_SYSTEM_SCOPE"),
        )
        assertTrue(
            "the guard branch must clear the renewal state",
            scopeGuardBranch.contains("fanOwnershipRenewal = null"),
        )
        assertTrue(
            "the guard branch must never reschedule via afterAttempt",
            !scopeGuardBranch.contains("afterAttempt"),
        )
    }

    // --- F1 (second adversarial review, spec §5 finding 1): the fan-first restore's own
    // floor-learning stamp must be taken in the DAEMON. In production every v5 restore routes
    // through RestoreGuardianProcessService's OWN PServerHardwareCommandPort instance, in the
    // :guardian process -- a different process whose clock this service's directHardwareCommands
    // never observes, so folding the PORT's clock in (observeVendorSmartDuty, E4) is silent dead
    // code for that path. Both call sites that run `transaction.restoreBound(...)` on THIS
    // service's own thread must stamp the service's own suppression window directly. Superseded
    // by I-2 (third review, R3-2): both sites now go through the shared `restoreBoundStamped`
    // helper instead of an inline `stampVendorFloorLearningSuppression()` call each -- the
    // helper's own stamping behaviour is pinned once, directly, in `RestoreBoundStampingTest`. --

    @Test
    fun `profile selection stamps its restore while fan-only transition performs none`() {
        val service = mainSourceText("MimirTweakerService.kt")

        val transition = sliceBetween(service, "private fun transitionFanControlModeOnWorker(",
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(")
        assertFalse("fan-only transition must not restore compute", transition.contains("restoreBoundStamped("))

        val selectRestore = sliceBetween(service, "restore = {", "verifiedStock = {")
        assertTrue(
            "selectSystemProfileOnWorker's restore lambda must go through restoreBoundStamped -- " +
                "I-2 folds the inline stamp into the shared helper",
            selectRestore.contains("restoreBoundStamped("),
        )
    }

    // --- F2 (second adversarial review, spec §5 finding 2, LAB posture): the select path must
    // decline the MANUAL entry attempt on a dry run BEFORE any live hardware read or write --
    // dryRun() defaults true on a DIAGNOSTICS build, and (pre-fix) handFanToVendorSmartForRenewal()
    // and probe.fanModeRaw() both ran before the dry-run-gated transaction.apply(dryRun = ...)
    // that would otherwise be the only thing declining a dry run. ------------------------------

    @Test
    fun `selectSystemProfileOnWorker declines the MANUAL entry attempt on a dry run before any live hardware access`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun selectSystemProfileOnWorker(",
            "private fun settleSystemWideStockRuntime(",
        )
        val eligibleIndex = fn.indexOf("if (!manualFanPlanEligible(profileId, operationCapabilities))")
        assertTrue("manualFanPlanEligible must still gate entry", eligibleIndex >= 0)

        val dryRunIndex = fn.indexOf("if (dryRun())", eligibleIndex)
        val liveReadIndex = fn.indexOf("probe.fanModeRaw()?.toIntOrNull()", eligibleIndex)
        val handToFourIndex = fn.indexOf(
            "directHardwareCommands?.handFanToVendorSmartForRenewal()",
            eligibleIndex,
        )
        assertTrue("a dry-run check must be present after the eligibility gate", dryRunIndex >= 0)
        assertTrue("the live fan-mode read must still be present", liveReadIndex >= 0)
        assertTrue("the hand-to-4 write must still be present", handToFourIndex >= 0)
        assertTrue(
            "the dry-run check must run before the live fan-mode read",
            dryRunIndex < liveReadIndex,
        )
        assertTrue(
            "the dry-run check must run before the hand-to-4 hardware write",
            dryRunIndex < handToFourIndex,
        )
    }

    // --- F3 (second adversarial review, spec §5 finding 3): fanControlDecision's SMART branch
    // must gate its renewal mint-and-dispatch sub-branch on renewalScopeAdmitted(active.scope) --
    // pre-fix it minted and dispatched a renewal for ANY scope, so a per-game (LAUNCH) session
    // with an external fan change re-minted every 1s tick, each dispatch declined
    // NOT_SYSTEM_SCOPE one function down -- two warn lines per second for a session this daemon
    // does not own the fan section of. observeVendorSmartDuty() and the rest of the branch must
    // still run for every scope. ------------------------------------------------------------------

    @Test
    fun `fanControlDecision gates its renewal mint-and-dispatch sub-branch on renewalScopeAdmitted`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun fanControlDecision(",
            "private fun ActiveSessionIdentity.fanRestore(",
        )
        val scopeCheckIndex = fn.indexOf("renewalScopeAdmitted(")
        val forActiveGenerationIndex = fn.indexOf("FanOwnershipRenewalPolicy.forActiveGeneration(")
        val dispatchIndex = fn.indexOf("executeTransactionWork")
        // Search from AFTER the dispatch, not a bare fn.indexOf: this function's own leading P3
        // comment ("Runs BEFORE the existing SMART-branch body below (observeVendorSmartDuty()
        // etc, ...") names observeVendorSmartDuty() in prose ahead of the scope-gated block, and a
        // bare first-occurrence search finds that mention instead of the real call.
        val observeIndex = fn.indexOf("observeVendorSmartDuty()", dispatchIndex)

        assertTrue("renewalScopeAdmitted(...) must be consulted", scopeCheckIndex >= 0)
        assertTrue("forActiveGeneration must still be present", forActiveGenerationIndex >= 0)
        assertTrue("the renewal dispatch must still be present", dispatchIndex >= 0)
        assertTrue(
            "observeVendorSmartDuty() must still run for every scope",
            observeIndex >= 0,
        )
        assertTrue(
            "the scope gate must run before the mint (forActiveGeneration)",
            scopeCheckIndex < forActiveGenerationIndex,
        )
        assertTrue(
            "the scope gate must run before the renewal is ever dispatched",
            scopeCheckIndex < dispatchIndex,
        )
        assertTrue(
            "observeVendorSmartDuty() must run after (outside) the scope-gated mint sub-branch, " +
                "unconditionally for every scope",
            observeIndex > dispatchIndex,
        )
    }

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }

    private fun active(generation: Long, transactionId: String, profileId: String = "quiet") = ActiveSessionIdentity(
        generation = generation,
        transactionId = transactionId,
        requestId = "mimir.system.test",
        ownerUid = 1,
        targetPackage = "com.gruning.mimir.rewrite",
        activationDeadlineMs = Long.MAX_VALUE,
        leaseDeadlineMs = Long.MAX_VALUE,
        targetEverForeground = true,
        appliedTargetContract = AppliedTargetContract(
            deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
            bootId = "boot",
            cpuQosCeilingKhz = mapOf(0 to 1_785_600),
            cpuQosTransport = CpuQosTransport.SHARED_QOS,
            gpuMinPowerLevel = null,
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SMART,
            profileId = profileId,
        ),
        scope = ActiveSessionScope.SYSTEM,
    )

    /** Minimal in-memory [android.content.SharedPreferences] fake for persistence round-trip tests. */
    private class FakeSharedPreferences : android.content.SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var cleared = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor =
                apply { pending[requireNotNull(key)] = value }
            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): android.content.SharedPreferences.Editor = apply { pending[requireNotNull(key)] = values }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor =
                apply { pending[requireNotNull(key)] = value }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor =
                apply { pending[requireNotNull(key)] = value }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor =
                apply { pending[requireNotNull(key)] = value }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor =
                apply { pending[requireNotNull(key)] = value }
            override fun remove(key: String?): android.content.SharedPreferences.Editor =
                apply { pending[requireNotNull(key)] = REMOVE_MARKER }
            override fun clear(): android.content.SharedPreferences.Editor = apply { cleared = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (cleared) values.clear()
                pending.forEach { (key, value) ->
                    if (value === REMOVE_MARKER) values.remove(key) else values[key] = value
                }
                pending.clear()
                cleared = false
            }
        }

        private companion object {
            val REMOVE_MARKER = Any()
        }
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "ayn",
        model = "odin3",
        socModel = "sm8750",
        buildFingerprint = "fp",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = emptyList(),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
    )
}
