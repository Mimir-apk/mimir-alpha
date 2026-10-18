package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chipset-support stage 3, the APPLY half: [SupportedUncertifiedProfiles.admitsPlan].
 *
 * Stage 1 proved the daemon can *describe* three profiles for an uncertified device. This proves
 * the description is now backed by an admission that reaches hardware -- and, far more importantly,
 * that every way of NOT being admitted still refuses.
 *
 * **Fixtures are device truth, not approximations.** Every CPU table here was read off a
 * Retroid Pocket Nova (test unit) on 2026-08-22 via
 * `cat /sys/devices/system/cpu/cpufreq/policy{0,3,7}/scaling_available_frequencies`, and the GPU
 * table off `/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies`. The earlier QCS8550 fixture
 * was a degenerate approximation that hid formula behavior; the
 * hardware is now in hand, so these are the real 16/19/20-entry tables. Note the Nova's topology is
 * {0, 3, 7} -- NOT the RP5's {0, 4, 7} used by the earlier fixture.
 *
 * **Posture.** These tests run under Production, Lab and Safe. [SupportedUncertifiedProfiles.admitsPlan]
 * is deliberately PRODUCTION-only, so every positive assertion is gated on the compiled posture and
 * the other two postures assert the refusal instead. A test that quietly passed everywhere would be
 * proving nothing about the posture gate.
 */
class UncertifiedProfileApplyAdmissionTest {

    // ── Device truth: Retroid Pocket Nova test unit, captured 2026-08-22 ────────────────────────
    private val novaPolicy0 = listOf(
        307_200, 441_600, 556_800, 672_000, 787_200, 902_400, 1_017_600, 1_113_600,
        1_228_800, 1_344_000, 1_459_200, 1_555_200, 1_670_400, 1_785_600, 1_900_800, 2_016_000,
    )
    private val novaPolicy3 = listOf(
        499_200, 614_400, 729_600, 844_800, 940_800, 1_056_000, 1_171_200, 1_286_400, 1_401_600,
        1_536_000, 1_651_200, 1_785_600, 1_920_000, 2_054_400, 2_188_800, 2_323_200, 2_457_600,
        2_592_000, 2_707_200,
    )
    private val novaPolicy7 = listOf(
        595_200, 729_600, 864_000, 998_400, 1_132_800, 1_248_000, 1_363_200, 1_478_400, 1_593_600,
        1_708_800, 1_843_200, 1_977_600, 2_092_800, 2_227_200, 2_342_400, 2_476_800, 2_592_000,
        2_726_400, 2_841_600, 2_956_800,
    )

    /**
     * The Nova's live GPU table, ascending. Nine levels against the Odin anchor's fourteen, which
     * is exactly why the transposition works on a position fraction rather than a literal index.
     */
    private val novaGpuKhz = listOf(
        124_800, 220_000, 295_000, 348_000, 401_000, 475_000, 550_000, 615_000, 680_000,
    )

    /**
     * The derived vectors this exact hardware must produce, recomputed by hand from the tables
     * above and pinned here so a formula change cannot silently change the derived device targets. Every
     * value is a member of its own policy's live table (asserted below, not assumed).
     *
     * Performance's prime sits at 70.8% of live max against balanced's 53.9% -- the same spread
     * the Odin's own signed vector has. Stage 1's RP5 fixture collapsed that distinction; this one
     * does not, which is the whole reason for using device tables.
     */
    private val expectedVectors = mapOf(
        "quiet" to mapOf(0 to 1_017_600, 3 to 1_286_400, 7 to 1_478_400),
        "balanced" to mapOf(0 to 1_113_600, 3 to 1_401_600, 7 to 1_593_600),
        "performance" to mapOf(0 to 1_113_600, 3 to 1_651_200, 7 to 2_092_800),
    )

    /**
     * Efficiency is Odin GPU level 5 of 14 transposed onto the Nova's 9-level table; balanced and
     * performance carry no ceiling.
     *
     * On Nova, Balanced and Performance are uncapped; Efficiency uses a mild proportional cap
     * transposed from the 8-Elite family. Balanced was previously level 1 (615 of 680 MHz).
     */
    private val expectedGpuLevel = mapOf("quiet" to 3, "balanced" to 0, "performance" to 0)

    private val productionPosture: Boolean
        get() = TweakerPostureTruth.posture == TweakerPostureTruth.PRODUCTION &&
            TweakerPostureTruth.hardwareWritesCompiled &&
            !TweakerPostureTruth.diagnosticsCompiled

    private fun policy(id: Int, available: List<Int>) = CpuPolicy(
        id = id,
        path = "/sys/devices/system/cpu/cpufreq/policy$id",
        cpuIds = listOf(id),
        availableFrequenciesKhz = available,
        minKhz = available.first(),
        maxKhz = available.last(),
        currentKhz = available.last(),
    )

    /**
     * The Nova as its own daemon actually sees it. Every field matches the `MimirTweakerIdentity`
     * line captured from the device on 2026-08-22:
     * `status=CALIBRATION_REQUIRED profile=retroid-qcs8550-pserver family=qualcomm-8gen2/
     * mimir-certified-reference pserver=true cpuPolicies=3 gpu=true fan=false posture=PRODUCTION
     * axes=[CPU, GPU]`.
     */
    private fun novaCapabilities(
        status: CapabilityStatus = CapabilityStatus.CALIBRATION_REQUIRED,
        pServerAvailable: Boolean = true,
        socModel: String = "QCS8550",
        policies: List<CpuPolicy> = listOf(
            policy(0, novaPolicy0),
            policy(3, novaPolicy3),
            policy(7, novaPolicy7),
        ),
    ) = HardwareCapabilities(
        status = status,
        manufacturer = "Moorechip",
        model = "Retroid Pocket Nova",
        socModel = socModel,
        buildFingerprint = "moorechip/nova/kalama:13",
        deviceProfileId = "retroid-qcs8550-pserver",
        // Device truth: axes are [CPU, GPU]. The fan axis is withheld -- deliberately, by an
        // earlier lane. Its PWM nodes exist; the authority does not.
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
        pServerAvailable = pServerAvailable,
        cpuPolicies = policies,
        gpuFrequenciesKhz = novaGpuKhz,
        gpuNodeAvailable = true,
        fanNodeAvailable = false,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
    )

    /** The plan the planner itself derives -- the only shape admission may accept. */
    private fun derivedPlan(profileId: String, capabilities: HardwareCapabilities): TuningPlan =
        checkNotNull(TuningPlanner().plan(profileId, PACKAGE, capabilities)) {
            "planner produced no plan for $profileId"
        }

    // ── The derivation itself, pinned against device tables ───────────────────────────────────

    @Test
    fun `derived Nova vectors match the real live OPP tables and separate the three profiles`() {
        val capabilities = novaCapabilities()
        expectedVectors.forEach { (profileId, expected) ->
            val plan = derivedPlan(profileId, capabilities)
            assertEquals("$profileId CPU vector", expected, plan.cpuMaxKhz)
            assertEquals("$profileId GPU level", expectedGpuLevel[profileId], plan.gpuMaxPowerLevel)

            // The safety property, restated independently of the formula: a derived value the
            // kernel has no OPP for is what would make this dangerous rather than merely wrong.
            plan.cpuMaxKhz.forEach { (policyId, khz) ->
                val table = capabilities.cpuPolicies.first { it.id == policyId }.availableFrequenciesKhz
                assertTrue(
                    "$profileId policy$policyId target $khz is not a member of the device's own table",
                    khz in table,
                )
            }
        }

        // The three must be genuinely different, or the fixture is as coarse as stage 1's was.
        assertNotEquals(expectedVectors["balanced"], expectedVectors["performance"])
        assertNotEquals(expectedVectors["quiet"], expectedVectors["balanced"])
    }

    // this fixture's `novaCapabilities()` deliberately carries
    // `supportedAxes = [CPU, GPU]` -- device truth captured BEFORE the fan axis was wired (the
    // earlier implementation's absent fan-axis support, per that helper's own comment). It is therefore no longer true,
    // categorically, that "fan authority is never derived for an uncertified device" -- see
    // `SupportedUncertifiedProfiles.admitsPlan`'s own KDoc, narrowed the same day to admit
    // FAN_SMART. What remains true, and is what this test now asserts, is narrower and still real:
    // on THIS fan-axis-absent fixture, selectedFanMode's own `TuningAxis.FAN !in
    // capabilities.supportedAxes -> null` branch means no fan mode is derived, full stop -- proving
    // the fan-less case still resolves correctly, not that fan-bearing uncertified devices are
    // impossible. The fan-bearing case is covered separately below
    // (`a Smart-bearing uncertified plan is admitted once the fan axis is wired`).
    @Test
    fun `the Nova earns all three uncertified ids, and this fan-axis-absent fixture derives no fan mode`() {
        val capabilities = novaCapabilities()
        assertEquals(
            setOf("quiet", "balanced", "performance"),
            SupportedUncertifiedProfiles.compute(
                status = capabilities.status,
                socModel = capabilities.socModel,
                cpuPolicies = capabilities.cpuPolicies,
            ),
        )
        expectedVectors.keys.forEach { profileId ->
            assertEquals(
                "this fixture carries no fan axis, so no fan mode should be derived for it",
                null,
                derivedPlan(profileId, capabilities).fanMode,
            )
        }
    }

    // ── The positive: an uncertified profile can actually be applied ──────────────────────────

    @Test
    fun `PRODUCTION admits the exact derived plan for every uncertified id, other postures refuse`() {
        val capabilities = novaCapabilities()
        expectedVectors.keys.forEach { profileId ->
            val admitted = SupportedUncertifiedProfiles.admitsPlan(
                plan = derivedPlan(profileId, capabilities),
                capabilities = capabilities,
            )
            assertEquals(
                "posture=${TweakerPostureTruth.posture} must ${if (productionPosture) "admit" else "refuse"} $profileId",
                productionPosture,
                admitted,
            )
        }
    }

    // ── The vendor performance mode joins the plan equality (2026-10-13) ───────────────────────

    /**
     * `admitsPlan` re-derives the plan from the live capabilities and demands exact equality. The
     * vendor mode is derived there too (axis in `supportedAxes` + the family table), so a plan that
     * names a different mode, or drops the one the live device would plan, is not the derived plan.
     */
    @Test
    fun `the vendor mode must equal the derived one -- a different or missing mode is refused`() {
        val capabilities = novaCapabilities().let {
            it.copy(
                supportedAxes = it.supportedAxes + TuningAxis.VENDOR_MODE,
                vendorPerformanceModePresent = true,
            )
        }
        val derived = derivedPlan("balanced", capabilities)
        assertEquals(TuningPlanner.VENDOR_MODE_PERFORMANCE, derived.vendorMode)

        // The derived plan is admitted exactly where the posture admits any derived plan.
        assertEquals(
            productionPosture,
            SupportedUncertifiedProfiles.admitsPlan(plan = derived, capabilities = capabilities),
        )
        // A different mode and a dropped mode never are, in any posture.
        listOf(
            derived.copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE),
            derived.copy(vendorMode = TuningPlanner.VENDOR_MODE_STANDARD),
            derived.copy(vendorMode = null),
        ).forEach { forged ->
            assertFalse(
                "vendorMode=${forged.vendorMode} must not be admitted",
                SupportedUncertifiedProfiles.admitsPlan(plan = forged, capabilities = capabilities),
            )
        }
        // And the reverse: on a device WITHOUT the axis, a plan that names a mode is not the derived plan.
        val without = novaCapabilities()
        assertNull(derivedPlan("balanced", without).vendorMode)
        assertFalse(
            SupportedUncertifiedProfiles.admitsPlan(
                plan = derivedPlan("balanced", without).copy(vendorMode = TuningPlanner.VENDOR_MODE_PERFORMANCE),
                capabilities = without,
            ),
        )
    }

    // ── The half that matters: every way of refusing still refuses ────────────────────────────

    /**
     * Rewritten for the family-level admission policy after the sequence manifest ceased to be
     * admission authority. The old Odin guard
     * this test pinned (a verified signed manifest makes the uncertified path refuse unconditionally)
     * is retired along with the manifest as an admission authority. `admitsPlan` no longer takes a
     * `certifiedManifestAvailable` parameter at all -- there is no second, signed route left for an
     * uncertified derivation to "shadow, race or widen".
     *
     * This is now the positive proof for `MANIFEST_GOVERNED_DEVICE_PROFILE_IDS`'s deletion: the
     * literal Odin device-profile id -- the ONE id that same-day gate named -- is admitted through
     * this path exactly like any other, on a fixture that is otherwise real Nova device truth. No
     * device-id allow-list remains anywhere in the accept path.
     */
    @Test
    fun `the literal Odin id is admitted like any other -- MANIFEST_GOVERNED_DEVICE_PROFILE_IDS is deleted`() {
        val capabilities = novaCapabilities().copy(deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE)
        expectedVectors.keys.forEach { profileId ->
            assertEquals(
                "posture=${TweakerPostureTruth.posture}: the exact Odin id must " +
                    "${if (productionPosture) "admit" else "refuse"} $profileId exactly like any other id",
                productionPosture,
                SupportedUncertifiedProfiles.admitsPlan(
                    plan = derivedPlan(profileId, capabilities),
                    capabilities = capabilities,
                ),
            )
        }
    }

    /**
     * The single most important negative. A plan whose target is a perfectly legal member of the
     * device's own OPP table -- but NOT the value the derivation produces -- must be refused.
     *
     * Without this, "is it in the table" would be the whole gate, and any caller could ask for the
     * top OPP and get it. The instrument is proved able to fail by the positive test above, which
     * admits the same shape when the value is the derived one.
     */
    @Test
    fun `a higher in-table target that the derivation did not produce is refused`() {
        val capabilities = novaCapabilities()
        val basePlan = derivedPlan("balanced", capabilities)
        // 2,956,800 is the top of policy7's real table -- entirely valid silicon-wise, and roughly
        // double balanced's derived 1,593,600.
        val overreach = basePlan.copy(cpuMaxKhz = basePlan.cpuMaxKhz + (7 to 2_956_800))

        assertNotEquals("fixture must actually differ from the derived plan", basePlan.cpuMaxKhz, overreach.cpuMaxKhz)
        assertTrue(
            "the overreaching value must be a real OPP, or this test proves nothing",
            2_956_800 in novaPolicy7,
        )
        assertFalse(
            SupportedUncertifiedProfiles.admitsPlan(
                plan = overreach,
                capabilities = capabilities,
            ),
        )
    }

    @Test
    fun `an off-table target is refused by admission AND independently by PlanTargetValidator`() {
        val capabilities = novaCapabilities()
        val basePlan = derivedPlan("quiet", capabilities)
        val offTable = basePlan.copy(cpuMaxKhz = basePlan.cpuMaxKhz + (0 to 1_017_601))

        assertFalse(
            "1017601 must not be a real OPP, or this test proves nothing",
            1_017_601 in novaPolicy0,
        )
        assertFalse(
            "admission must refuse an off-table target",
            SupportedUncertifiedProfiles.admitsPlan(
                plan = offTable,
                capabilities = capabilities,
            ),
        )
        // The independent net: even if admission were somehow bypassed, every apply re-validates.
        assertTrue(
            "PlanTargetValidator must independently refuse an off-table target",
            PlanTargetValidator.validate(offTable, capabilities).isNotEmpty(),
        )
        // ... and it must NOT refuse the derived plan, or the check above is vacuous.
        assertEquals(
            "PlanTargetValidator must accept the genuinely derived plan",
            emptyList<String>(),
            PlanTargetValidator.validate(basePlan, capabilities),
        )
    }

    /**
     * **2026-08-23 manual-fan change: MANUAL moved out of the refused set, and this test moved with
     * it rather than being deleted.** SILENT and SPORT are still refused on every device -- they
     * stop the vendor loop and hand Mimir nothing, which is the measured Odin defect the admission guard
     * prevents. MANUAL is different in kind: it stops the loop AND hands Mimir the duty,
     * under an explicit player-initiated opt-in, so it is admitted -- but ONLY as the exact plan
     * `TuningPlanner.FanIntent.MANUAL_OVERRIDE` re-derives for this hardware. The three negative
     * assertions added below (tampered CPU vector, tampered GPU vector, no fan axis) are what keep
     * that from being a hole: they are the same fail-closed properties the blanket refusal used to
     * give for free, now asserted directly instead of inherited.
     *
     * rewritten, not deleted. `admitsPlan` no longer refuses
     * every fan-bearing plan outright -- it now admits FAN_SMART and refuses SILENT/SPORT (and, at
     * the time this was written, MANUAL). `novaCapabilities()`'s fixture
     * still carries no fan axis (device truth at the time it was captured), so it cannot exercise
     * either half of the new rule on its own; this test builds a fan-bearing variant to actually
     * drive both.
     *
     * On a Nova fixture, all three plans must carry fanMode == FAN_SMART, and compute() and
     * admitsPlan must agree. The positive half below is
     * looped over quiet/balanced/performance, not asserted only for balanced, so this test alone
     * proves that literal claim rather than one instance of it.
     */
    @Test
    fun `on a fan-bearing device, Smart is admitted, SILENT and SPORT are refused, and MANUAL is admitted only as the explicit override re-derivation`() {
        val fanBearing = novaCapabilities().copy(
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
            fanNodeAvailable = true,
        )
        listOf("quiet", "balanced", "performance").forEach { profileId ->
            val derived = derivedPlan(profileId, fanBearing)
            assertEquals(
                "profile=$profileId must actually derive Smart, or the positive assertion below " +
                    "proves nothing",
                TuningPlanner.FAN_SMART,
                derived.fanMode,
            )
            assertEquals(
                "profile=$profileId admission under posture ${TweakerPostureTruth.posture}",
                productionPosture,
                SupportedUncertifiedProfiles.admitsPlan(
                    plan = derived,
                    capabilities = fanBearing,
                ),
            )
        }

        val derivedSmart = derivedPlan("balanced", fanBearing)

        // SILENT and SPORT stop the vendor loop without handing Mimir the duty, and are still
        // refused outright on every device. Neither is what selectedFanMode derives here, so this
        // also independently exercises the re-derivation equality check, not only the fan gate.
        listOf(TuningPlanner.FAN_SILENT, TuningPlanner.FAN_SPORT).forEach { mode ->
            assertFalse(
                "fan mode $mode must still be refused",
                SupportedUncertifiedProfiles.admitsPlan(
                    plan = derivedSmart.copy(fanMode = mode),
                    capabilities = fanBearing,
                ),
            )
        }

        // 2026-08-23: MANUAL is admitted on a fan-bearing device -- this is the whole of what the
        // manual-fan lane changed here, and before it this assertion was the inverse.
        val derivedManual = derivedSmart.copy(fanMode = TuningPlanner.FAN_MANUAL)
        assertEquals(
            "MANUAL must be admitted as the MANUAL_OVERRIDE re-derivation under posture " +
                "${TweakerPostureTruth.posture}",
            productionPosture,
            SupportedUncertifiedProfiles.admitsPlan(plan = derivedManual, capabilities = fanBearing),
        )

        // ...and admitted ONLY as that exact vector. Equality-against-recompute keeps its full
        // strength for MANUAL: a hand-built manual plan that moves CPU or GPU is still refused, so
        // manual fan cannot become a back door to a clock envelope no profile derives.
        assertFalse(
            "a MANUAL plan with a tampered CPU vector must be refused",
            SupportedUncertifiedProfiles.admitsPlan(
                plan = derivedManual.copy(cpuMaxKhz = mapOf(0 to 123)),
                capabilities = fanBearing,
            ),
        )
        assertFalse(
            "a MANUAL plan with a tampered GPU vector must be refused",
            SupportedUncertifiedProfiles.admitsPlan(
                plan = derivedManual.copy(gpuMaxPowerLevel = 99),
                capabilities = fanBearing,
            ),
        )

        // ...and it fails toward cooling on a chassis with no fan to drive: no axis, no admission,
        // regardless of what the plan claims.
        assertFalse(
            "MANUAL must be refused with no live fan axis",
            SupportedUncertifiedProfiles.admitsPlan(
                plan = derivedManual,
                capabilities = fanBearing.copy(
                    supportedAxes = fanBearing.supportedAxes - TuningAxis.FAN,
                    fanNodeAvailable = false,
                ),
            ),
        )
    }

    @Test
    fun `stock is never an uncertified admission`() {
        val capabilities = novaCapabilities()
        val stock = derivedPlan("stock", capabilities)
        assertFalse(
            SupportedUncertifiedProfiles.admitsPlan(
                plan = stock,
                capabilities = capabilities,
            ),
        )
    }

    @Test
    fun `unsupported status, dead pserver and uncharacterised silicon each refuse on their own`() {
        val healthy = novaCapabilities()
        val plan = derivedPlan("balanced", healthy)

        listOf(
            "UNSUPPORTED status" to novaCapabilities(status = CapabilityStatus.UNSUPPORTED),
            "dead pserver" to novaCapabilities(pServerAvailable = false),
            "uncharacterised silicon" to novaCapabilities(socModel = "SM8450"),
        ).forEach { (label, capabilities) ->
            assertFalse(
                "$label must refuse admission on its own",
                SupportedUncertifiedProfiles.admitsPlan(
                    plan = plan,
                    capabilities = capabilities,
                ),
            )
        }
    }

    // ── mutationAvailable ─────────────────────────────────────────────────────────────────────

    @Test
    fun `mutationAvailable is earned by an uncertified set but still needs every environmental leg`() {
        val uncertified = setOf("quiet", "balanced", "performance")

        assertTrue(
            "an uncertified device with a live transport can mutate",
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.CALIBRATION_REQUIRED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_NONE,
                qualifiedProfileIds = emptySet(),
                supportedUncertifiedProfileIds = uncertified,
            ),
        )

        // Each environmental leg still refuses on its own -- the widening touched only the
        // qualification leg.
        listOf(
            "writes not compiled" to Triple(false, false, true),
            "dry run" to Triple(true, true, true),
            "dead pserver" to Triple(true, false, false),
        ).forEach { (label, legs) ->
            val (writes, dry, pserver) = legs
            assertFalse(
                "$label must still refuse mutation",
                TweakerPostureTruth.mutationAvailable(
                    hardwareWritesCompiled = writes,
                    dryRun = dry,
                    capabilityStatus = CapabilityStatus.CALIBRATION_REQUIRED,
                    pServerAvailable = pserver,
                    qualificationSource = TweakerPostureTruth.QUALIFICATION_NONE,
                    qualifiedProfileIds = emptySet(),
                    supportedUncertifiedProfileIds = uncertified,
                ),
            )
        }

        assertFalse(
            "UNSUPPORTED hardware must still refuse mutation",
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.UNSUPPORTED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_NONE,
                qualifiedProfileIds = emptySet(),
                supportedUncertifiedProfileIds = uncertified,
            ),
        )

        assertFalse(
            "no qualification of either kind means no mutation",
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.CALIBRATION_REQUIRED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_NONE,
                qualifiedProfileIds = emptySet(),
                supportedUncertifiedProfileIds = emptySet(),
            ),
        )
    }

    /**
     * The certified leg must be bit-identical to its pre-stage-3 behaviour: a signed device with
     * qualified ids and no uncertified set still mutates, exactly as before.
     */
    @Test
    fun `the signed-manifest leg of mutationAvailable is unmoved`() {
        assertTrue(
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.SUPPORTED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST,
                qualifiedProfileIds = setOf("balanced"),
                supportedUncertifiedProfileIds = emptySet(),
            ),
        )
        assertFalse(
            "a LOCAL_REPORT with no uncertified set never authorized and still does not",
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.SUPPORTED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_LOCAL_REPORT,
                qualifiedProfileIds = setOf("balanced"),
                supportedUncertifiedProfileIds = emptySet(),
            ),
        )
    }

    /**
     * Manifest-independent admission: This test used to pin "a signed
     * device is still refused if its manifest resolution transiently reads unavailable", relying on
     * the Odin deriving a non-Smart fan mode to refuse it independently of the (then same-day-added)
     * `MANIFEST_GOVERNED_DEVICE_PROFILE_IDS` gate. TR-08 (same file, same day) already made that
     * premise false -- the Odin derives `FAN_SMART` like every other device now, which `admitsPlan`
     * explicitly admits -- so this test was left RED on purpose rather than rewritten
     * against a premise about to be retired outright.
     *
     * The retirement removes the premise instead of patching it: manifest resolution -- transient,
     * permanent, or never-existed -- is no longer a parameter `admitsPlan` takes at all. This is
     * now a manifest-independent fixture: a real AYN/Retroid device on the 8-Elite family
     * (the Odin's own device truth, CPU/GPU tables from its 2026-08-22 identity line) gets all
     * three profiles admitted with no manifest consulted and no allow-list entry naming it -- the
     * SupportedUncertifiedProfiles.compute()/admitsPlan path was never keyed on deviceProfileId to
     * begin with.
     */
    @Test
    fun `an 8-Elite AYN Retroid fixture gets all three profiles admitted with no manifest consulted`() {
        val odin = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            manufacturer = "AYN",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "ayn/odin3/sun:15",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            // Device truth from the Odin's own identity line, 2026-08-22: axes=[CPU, GPU, FAN].
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
            pServerAvailable = true,
            cpuPolicies = listOf(
                policy(0, listOf(1_785_600, 1_996_800, 2_246_400, 3_532_800)),
                policy(6, listOf(2_246_400, 2_438_400, 3_072_000, 4_320_000)),
            ),
            gpuFrequenciesKhz = listOf(
                160_000, 222_000, 342_000, 389_000, 443_000, 525_000, 607_000,
                660_000, 734_000, 832_000, 900_000, 967_000, 1_050_000, 1_100_000,
            ),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60, 120),
            notes = emptyList(),
        )

        assertEquals(
            setOf("quiet", "balanced", "performance"),
            SupportedUncertifiedProfiles.compute(odin.status, odin.socModel, odin.cpuPolicies),
        )

        listOf("quiet", "balanced", "performance").forEach { profileId ->
            val plan = derivedPlan(profileId, odin)
            assertEquals(
                "profile=$profileId must derive Smart post-TR-08, or this test proves nothing " +
                    "about the fan-mode leg",
                TuningPlanner.FAN_SMART,
                plan.fanMode,
            )
            assertEquals(
                "posture=${TweakerPostureTruth.posture}: an 8-Elite AYN/Retroid fixture must " +
                    "${if (productionPosture) "admit" else "refuse"} $profileId with no manifest " +
                    "consulted at all",
                productionPosture,
                SupportedUncertifiedProfiles.admitsPlan(plan = plan, capabilities = odin),
            )
        }
    }

    /**
     * The strongest form of "no allow-list entry naming it": a chip-family member whose
     * `deviceProfileId`/manufacturer/model have never appeared anywhere else in this codebase.
     * Real Odin CPU/GPU tables (so the derivation actually resolves), a synthetic identity that
     * cannot be sitting in any allow-list because nothing here was ever written to expect it.
     */
    @Test
    fun `a chassis nobody has qualified by name is admitted purely by 8-Elite family membership`() {
        val neverSeenBefore = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            manufacturer = "AYN",
            model = "Some Future Handheld Nobody Has Named Yet",
            socModel = "CQ8725S",
            buildFingerprint = "ayn/future-handheld/never-seen:1",
            deviceProfileId = "ayn-future-handheld-cq8725s-pserver",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            pServerAvailable = true,
            cpuPolicies = listOf(
                policy(0, listOf(1_785_600, 1_996_800, 2_246_400, 3_532_800)),
                policy(6, listOf(2_246_400, 2_438_400, 3_072_000, 4_320_000)),
            ),
            gpuFrequenciesKhz = listOf(
                160_000, 222_000, 342_000, 389_000, 443_000, 525_000, 607_000,
                660_000, 734_000, 832_000, 900_000, 967_000, 1_050_000, 1_100_000,
            ),
            gpuNodeAvailable = true,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(60, 120),
            notes = emptyList(),
        )

        assertEquals(
            "family membership alone must describe all three -- id/model are unrecognisable on purpose",
            setOf("quiet", "balanced", "performance"),
            SupportedUncertifiedProfiles.compute(
                neverSeenBefore.status,
                neverSeenBefore.socModel,
                neverSeenBefore.cpuPolicies,
            ),
        )
        listOf("quiet", "balanced", "performance").forEach { profileId ->
            assertEquals(
                "posture=${TweakerPostureTruth.posture}: a never-seen 8-Elite id must " +
                    "${if (productionPosture) "admit" else "refuse"} $profileId",
                productionPosture,
                SupportedUncertifiedProfiles.admitsPlan(
                    plan = derivedPlan(profileId, neverSeenBefore),
                    capabilities = neverSeenBefore,
                ),
            )
        }
    }

    // ── The wiring seam: admission must actually BE the gate the service uses ─────────────────

    /**
     * Closes the gap between "the admission object works" and "the daemon uses it".
     *
     * `TuningPlan.calibrated` is the single thing `TransactionCoordinator.apply` consults at its
     * `!plan.calibrated -> CALIBRATION_REQUIRED` check (TransactionCoordinator.kt:259), and
     * `TransactionCoordinatorTest`'s existing `uncalibrated plan cannot cross enabled write gate`
     * already pins the negative half against a default `TuningPlanner()`. This pins the positive
     * half: composing the lookup exactly as `MimirTweakerService.planAdmittedForApply` does --
     * **[SupportedUncertifiedProfiles.admitsPlan] alone, no `planMatches`
     * leg any more** -- marks an uncertified plan calibrated, so it reaches the journal/lease/
     * Guardian machinery instead of being refused at the gate. Together the two tests span the
     * whole chain.
     */
    @Test
    fun `the service's composed lookup is what marks an uncertified plan calibrated`() {
        val capabilities = novaCapabilities()

        // Exactly the composition in MimirTweakerService.planAdmittedForApply post-retirement.
        val composedLookup: (TuningPlan, HardwareCapabilities) -> Boolean = { plan, caps ->
            SupportedUncertifiedProfiles.admitsPlan(plan = plan, capabilities = caps)
        }

        val planned = checkNotNull(TuningPlanner(composedLookup).plan("balanced", PACKAGE, capabilities))
        assertEquals(
            "posture=${TweakerPostureTruth.posture}: calibrated must track the admission decision",
            productionPosture,
            planned.calibrated,
        )

        // The differential: the identical planner WITHOUT the uncertified leg leaves it
        // uncalibrated, which is what the device does today.
        assertFalse(
            "a planner with no uncertified admission must leave the plan uncalibrated",
            checkNotNull(TuningPlanner().plan("balanced", PACKAGE, capabilities)).calibrated,
        )
    }

    /**
     * `planAdmittedForApply` and both its call sites are private members of `MimirTweakerService`
     * with no callable seam a unit test can drive -- the same obstacle
     * `TweakerAdvancedControlsContract` documents for its own two producers. Until one exists, the
     * wiring is pinned as source text, following the precedent already set by
     * `AdvancedFanControlTest`'s `advancedCapabilitiesV6 forwards the last recorded restore reason`.
     *
     * What this catches: a future edit reverting either `TuningPlanner(certifiedLookup)` site to
     * a manifest-consulting lambda. Every other test in this file would stay green while a signed
     * manifest silently became an admission authority again -- precisely the "the data is on the
     * wire; nothing reads it" class of defect stage 1 shipped and stage 3 exists to close, now
     * inverted for the 2026-08-22 (late) retirement: the wire must NOT carry `planMatches` any more.
     */
    @Test
    fun `both planner construction sites are wired to the widened admission, with no signed-manifest leg`() {
        val source = mainSourceText("MimirTweakerService.kt")

        assertEquals(
            "every certifiedLookup must be the ONE shared admission method",
            2,
            Regex(Regex.escape("val certifiedLookup: (TuningPlan, HardwareCapabilities) -> Boolean = ::planAdmittedForApply"))
                .findAll(source).count(),
        )
        assertTrue(
            "the shared admission must consult the uncertified derivation",
            source.contains("SupportedUncertifiedProfiles.admitsPlan(plan = plan, capabilities = caps)"),
        )
        assertFalse(
            "planAdmittedForApply must no longer consult the signed manifest for admission -- " +
                "qualification is not an admission authority",
            source.contains("productQualificationResolver.planMatches"),
        )
        assertFalse(
            "admitsPlan must no longer be told whether a manifest exists -- the parameter is deleted",
            source.contains("certifiedManifestAvailable"),
        )
    }

    // ── The observed apply attempt, and why it was refused ────────────────────────

    /**
     * **The stage-3 defect: Nova rejected an Efficiency profile change.** The daemon's
     * own answer, verbatim from `[V] logcat 08-22 03:37:28.771, Nova test unit`:
     * `MimirTweakerSelection: requested=quiet prior=stock result=Rejected(code=FAN_PROFILE_STAGING_INVALID)`
     * — a **fan** refusal on a chassis whose identity line the same second read `fan=false`,
     * `axes=[CPU, GPU]`.
     *
     * Admission was never the problem: [SupportedUncertifiedProfiles.admitsPlan] admitted the plan
     * and the hardware was written (the settle worker that produced that code only runs when
     * `result.applied` is already true). The refusal came *after* a successful apply, from
     * `fanOwnershipSettleRoute`, which had no branch for a device with no fan axis and dropped a
     * legitimately-null fan mode into `REJECT_STAGING_INVALID` — then rolled the apply back.
     *
     * This asserts the whole chain on all three ids, so "does balanced reject the same way" is
     * answered by the suite instead of by the next person holding the device.
     */
    @Test
    fun `every profile the Nova advertises applies and settles instead of rolling back`() {
        val capabilities = novaCapabilities()
        assertFalse(
            "device truth: the Nova exposes no fan axis",
            TuningAxis.FAN in capabilities.supportedAxes,
        )
        listOf("quiet", "balanced", "performance").forEach { profileId ->
            val plan = derivedPlan(profileId, capabilities)

            // 1. The planner is right to omit the fan: selectedFanMode returns null when
            //    TuningAxis.FAN is absent. Nothing to fix on the plan side.
            assertNull("$profileId must carry no fan mode on a fan-less chassis", plan.fanMode)

            // 2. Admission accepts it -- unchanged by this fix, restated so a later failure here
            //    is attributed to admission rather than to the settle route.
            assertEquals(
                "$profileId admission under posture ${TweakerPostureTruth.posture}",
                productionPosture,
                SupportedUncertifiedProfiles.admitsPlan(
                    plan = plan,
                    capabilities = capabilities,
                ),
            )

            // 3. The settle route -- the half that was wrong. Before 2026-08-22 every one of
            //    these returned REJECT_STAGING_INVALID and cost the player the profile.
            assertEquals(
                "$profileId must settle without a rollback",
                FanOwnershipSettleRoute.ADOPT_NO_FAN_AXIS,
                fanOwnershipSettleRoute(
                    contractFanMode = plan.fanMode,
                    fanAxisAvailable = TuningAxis.FAN in capabilities.supportedAxes,
                    manualFanControlAvailable = {
                        error("a chassis with no fan axis must never probe manual fan availability")
                    },
                ),
            )
        }
    }

    /**
     * The settle worker must actually consult the live fan axis rather than a constant. A source
     * assertion, in the same style as the admission-wiring test above, because the alternative is
     * a full Service/Binder/Guardian harness for one boolean.
     */
    @Test
    fun `the settle worker reads the live fan axis and adopts rather than rejecting`() {
        val source = mainSourceText("MimirTweakerService.kt")
        assertTrue(
            "the settle worker must pass the live fan axis into the route decision",
            source.contains("fanAxisAvailable = TuningAxis.FAN in ("),
        )
        assertTrue(
            "a fan-less chassis must return the staged result, not roll it back",
            source.contains("FanOwnershipSettleRoute.ADOPT_NO_FAN_AXIS -> return stagedResult"),
        )
    }

    /**
     * The second tap. Found while fixing `FAN_PROFILE_STAGING_INVALID`, same family, one field over.
     *
     * `selectSystemProfileOnWorker` decides "is the requested profile already the active one" by
     * asking whether Mimir owns the fan duty. On a fan-less chassis that is permanently false, so
     * a live session answers "not mine" about itself and re-selecting the active profile falls
     * through to restore-then-reapply — two Guardian transactions and roughly nine seconds of real
     * hardware writes (device-timed: the 08-22 03:37 Nova transaction ran 9.1 s) to arrive where
     * it already was, instead of `SYSTEM_PROFILE_UNCHANGED` with zero commands.
     *
     * Asserted through [SystemWideProfileTransition.execute] itself, so this pins the *outcome*
     * rather than the predicate's spelling.
     */
    @Test
    fun `re-selecting the active profile on a fan-less chassis executes zero commands`() {
        var restores = 0
        var applies = 0
        fun transition(activeSystemProfileId: String?) = SystemWideProfileTransition.execute(
            requestedProfileId = "quiet",
            activeMutation = true,
            activeSystemProfileId = activeSystemProfileId,
            restore = {
                restores += 1
                TransactionResult(true, false, true, "RESTORED", "ok")
            },
            verifiedStock = { true },
            onVerifiedStock = {},
            onUnverifiedRestore = {},
            apply = {
                applies += 1
                TransactionResult(true, true, false, "APPLIED", "ok")
            },
        )

        // What the Nova did before the fix: its own live profile read as "not Mimir-owned", so
        // the transition took the replace path.
        val churned = transition(null)
        assertEquals("APPLIED", churned.code)
        assertEquals("the pre-fix path really does restore", 1, restores)
        assertEquals("...and re-apply", 1, applies)

        // What it does now that a fan-less chassis is recognised as owning its own session.
        restores = 0
        applies = 0
        val unchanged = transition("quiet")
        assertEquals("SYSTEM_PROFILE_UNCHANGED", unchanged.code)
        assertEquals("zero restores", 0, restores)
        assertEquals("zero applies", 0, applies)
        assertFalse("and nothing reached hardware", unchanged.applied || unchanged.restored)
    }

    // Migration note, 2026-08-22: two tests formerly here -- `a chassis with a live fan axis
    // keeps the staging guard exactly as it was` (called fanOwnershipSettleRoute directly) and
    // `the ownership predicate requires an absent fan axis, not merely an absent fan mode` (source-
    // pinned activeSystemSessionIsMimirOwned) -- moved to AdvancedFanControlTest.kt, which already
    // owns fanOwnershipSettleRoute's other direct-call tests and this file's own mainSourceText
    // helper pattern. Neither test's assertions changed; only their address did.

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/tweaker"),
            java.io.File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(java.io.File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = java.io.File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private companion object {
        const val PACKAGE = "com.gruning.mimir.rewrite"
    }
}
