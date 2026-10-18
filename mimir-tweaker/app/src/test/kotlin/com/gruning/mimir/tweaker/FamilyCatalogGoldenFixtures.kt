package com.gruning.mimir.tweaker

/**
 * The planner fixtures of `TuningPlannerTest`, copied so the catalog-driven
 * planner can replay exactly the capabilities the compiled planner was pinned against. Each
 * fixture names the `TuningPlannerTest.kt` line it comes from; the helpers at the bottom are
 * verbatim copies of that file's private `capabilities()`, `policy()`, `policyWithOpps()` and
 * `odinCapabilities()` (lines 1042-1112). Do not "improve" a fixture: a changed number is a changed
 * golden hash.
 *
 * Every fixture is replayed with the vendor key absent and present, for the four profiles `stock`,
 * `quiet`, `balanced`, `performance`. "Present" is modelled the way the probe and
 * `TuningPlannerTest.withVendorMode` model it, see [withVendorKey]: the capability flag AND the
 * `VENDOR_MODE` axis together, because the planner (since the vendor-mode transport lane) plans a
 * vendor mode only when the axis is in `supportedAxes`. The first version of this replay set only
 * the flag, which that gating makes a no-op, so every `true` row would have lost its `|vmode=`.
 * The rows were not regenerated: the replay was corrected and the committed hashes still hold,
 * which keeps the vendor-mode row of the catalog under the golden test.
 */
internal data class GoldenFixture(
    val id: String,
    /** Where the capabilities come from in `TuningPlannerTest.kt`. */
    val source: String,
    val capabilities: HardwareCapabilities,
)

internal data class GoldenRow(
    val fixture: String,
    /** Today's compiled `TweakerHardwareFamilies.resolve(socModel).id`. */
    val familyId: String,
    val vendorKeyPresent: Boolean,
    val profile: String,
    val planHash: String,
    /** The string `PlanHash` digests, rebuilt independently so a drift in either is visible. */
    val canonical: String,
) {
    fun toLine(): String = listOf(fixture, familyId, vendorKeyPresent, profile, planHash, canonical).joinToString(";")

    companion object {
        fun fromLine(line: String): GoldenRow {
            val parts = line.split(";")
            require(parts.size == 6) { "malformed golden row: $line" }
            return GoldenRow(parts[0], parts[1], parts[2].toBooleanStrict(), parts[3], parts[4], parts[5])
        }
    }
}

internal object FamilyCatalogGoldenFixtures {
    const val PACKAGE = "example.game"
    val PROFILES = listOf("stock", "quiet", "balanced", "performance")

    /** The 14-entry live GPU table read from the Odin 3 (`TuningPlannerTest.kt:164`, `:129`, `:1066`). */
    private val ODIN_GPU_14 = listOf(
        160_000, 222_000, 342_000, 389_000, 443_000, 525_000, 607_000, 660_000,
        734_000, 832_000, 900_000, 967_000, 1_050_000, 1_100_000,
    )
    private val SMALL_GPU = listOf(200_000, 450_000, 700_000, 1_000_000)
    private val CPU_GPU = setOf(TuningAxis.CPU, TuningAxis.GPU)

    fun all(): List<GoldenFixture> {
        val rp5 = capabilities()
        val odinChassis = rp5.copy(
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "qti/sun/test",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            supportedAxes = CPU_GPU,
        )
        val eliteOpps = listOf(
            policyWithOpps(0, listOf(998_400, 1_785_600, 1_996_800, 2_227_200, 2_745_600)),
            policyWithOpps(6, listOf(1_075_200, 1_958_400, 2_246_400, 2_438_400, 3_072_000)),
        )
        val fixtures = ArrayList<GoldenFixture>()
        fun add(id: String, source: String, capabilities: HardwareCapabilities) {
            fixtures += GoldenFixture(id, source, capabilities)
        }

        add("rp5-default", "TuningPlannerTest.kt:26,701,1073 capabilities() (SM8250, RP5)", rp5)
        add("rp5-no-fan-axis", "TuningPlannerTest.kt:35", rp5.copy(supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU)))
        add(
            "elite-general-fallback-opps",
            "TuningPlannerTest.kt:43 (CQ8725S with OPPs the certified row cannot match)",
            odinChassis.copy(
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(1_000_000, 2_200_000, 3_000_000, 4_000_000)),
                    policyWithOpps(6, listOf(500_000, 1_100_000, 1_600_000, 2_000_000)),
                ),
                gpuFrequenciesKhz = SMALL_GPU,
                fanNodeAvailable = false,
            ),
        )
        listOf("CQ8725S", "SM8750").forEach { soc ->
            add(
                "elite-alias-$soc",
                "TuningPlannerTest.kt:77",
                odinChassis.copy(
                    socModel = soc,
                    cpuPolicies = eliteOpps,
                    gpuFrequenciesKhz = SMALL_GPU,
                    fanNodeAvailable = false,
                ),
            )
        }
        add(
            "elite-seq9-real-gpu",
            "TuningPlannerTest.kt:129",
            rp5.copy(
                manufacturer = "ayn",
                model = "Odin3",
                socModel = "CQ8725S",
                buildFingerprint = "ayn/sun/odin3-seq9-candidate",
                deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
                supportedAxes = CPU_GPU,
                cpuPolicies = eliteOpps,
                gpuFrequenciesKhz = ODIN_GPU_14,
            ),
        )
        val odinGpuCeilings = rp5.copy(
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "ayn/sun/odin3-qualified-candidate",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            supportedAxes = CPU_GPU,
            gpuFrequenciesKhz = ODIN_GPU_14,
        )
        add("elite-rp5-policies-odin-gpu", "TuningPlannerTest.kt:164", odinGpuCeilings)
        add(
            "sm8250-rp5-policies-odin-gpu",
            "TuningPlannerTest.kt:164 (the 'genuinely different chip family' copy at :233)",
            odinGpuCeilings.copy(deviceProfileId = "qualcomm-8-elite-pserver", socModel = "SM8250"),
        )
        add(
            "elite-78-percent-fallback",
            "TuningPlannerTest.kt:374",
            rp5.copy(
                socModel = "CQ8725S",
                supportedAxes = CPU_GPU,
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(1_000_000, 2_000_000, 2_500_000, 3_000_000)),
                    policyWithOpps(6, listOf(1_100_000, 1_800_000, 2_300_000, 3_100_000)),
                ),
                gpuFrequenciesKhz = SMALL_GPU,
            ),
        )
        add(
            "elite-one-policy-target-absent",
            "TuningPlannerTest.kt:400",
            rp5.copy(
                socModel = "CQ8725S",
                supportedAxes = setOf(TuningAxis.CPU),
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(998_400, 1_785_600, 2_745_600)),
                    policyWithOpps(6, listOf(1_075_200, 1_900_000, 3_072_000)),
                ),
                gpuFrequenciesKhz = emptyList(),
                gpuNodeAvailable = false,
            ),
        )
        add(
            "elite-extra-policy",
            "TuningPlannerTest.kt:419",
            rp5.copy(
                socModel = "CQ8725S",
                supportedAxes = setOf(TuningAxis.CPU),
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(998_400, 1_785_600, 2_745_600)),
                    policyWithOpps(6, listOf(1_075_200, 1_958_400, 3_072_000)),
                    policyWithOpps(8, listOf(500_000, 1_000_000, 1_500_000)),
                ),
                gpuFrequenciesKhz = emptyList(),
                gpuNodeAvailable = false,
            ),
        )
        add(
            "sm8250-raised-floor",
            "TuningPlannerTest.kt:439",
            rp5.copy(
                socModel = "SM8250",
                supportedAxes = setOf(TuningAxis.CPU),
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(1_171_200, 1_344_000, 1_708_800, 1_804_800)).copy(minKhz = 1_344_000),
                    policyWithOpps(4, listOf(710_400, 1_286_400, 1_862_400, 2_342_400, 2_419_200)),
                    policyWithOpps(7, listOf(844_800, 1_516_800, 2_169_600, 2_745_600, 2_841_600)),
                ),
                gpuFrequenciesKhz = emptyList(),
                gpuNodeAvailable = false,
                fanNodeAvailable = false,
            ),
        )
        add(
            "rp5-stale-gpu-cpu-only",
            "TuningPlannerTest.kt:478",
            rp5.copy(
                supportedAxes = setOf(TuningAxis.CPU),
                gpuFrequenciesKhz = listOf(305_000, 670_000, 905_000),
                gpuNodeAvailable = false,
            ),
        )
        listOf("QCS8550", "SM8550").forEach { soc ->
            add(
                "gen2-alias-$soc",
                "TuningPlannerTest.kt:500",
                rp5.copy(
                    socModel = soc,
                    supportedAxes = CPU_GPU,
                    cpuPolicies = listOf(
                        policyWithOpps(0, listOf(1_459_200, 1_555_200, 1_785_600, 2_150_400)),
                        policyWithOpps(3, listOf(1_785_600, 2_054_400, 2_323_200, 2_841_600)),
                        policyWithOpps(7, listOf(1_843_200, 2_092_800, 2_476_800, 3_187_200)),
                    ),
                    gpuFrequenciesKhz = SMALL_GPU,
                ),
            )
        }
        add(
            "sm8250-rp5-real-opps",
            "TuningPlannerTest.kt:543",
            rp5.copy(
                socModel = "SM8250",
                supportedAxes = CPU_GPU,
                cpuPolicies = listOf(
                    policyWithOpps(
                        0,
                        listOf(
                            300_000, 403_200, 518_400, 614_400, 691_200, 787_200, 883_200, 979_200,
                            1_075_200, 1_171_200, 1_248_000, 1_344_000, 1_420_800, 1_516_800, 1_612_800,
                            1_708_800, 1_804_800,
                        ),
                    ),
                    policyWithOpps(4, listOf(710_400, 1_286_400, 1_862_400, 2_342_400, 2_419_200)),
                    policyWithOpps(7, listOf(844_800, 1_516_800, 2_169_600, 2_745_600, 2_841_600)),
                ),
            ),
        )
        add(
            "nova-qcs8550-nine-gpu-levels",
            "TuningPlannerTest.kt:614",
            rp5.copy(
                manufacturer = "Moorechip",
                model = "Retroid Pocket Nova",
                socModel = "QCS8550",
                buildFingerprint = "kalama/test",
                deviceProfileId = "retroid-qcs8550-pserver",
                supportedAxes = CPU_GPU,
                gpuFrequenciesKhz = listOf(680_000, 615_000, 550_000, 475_000, 401_000, 348_000, 295_000, 220_000, 124_800),
            ),
        )
        add("elite-odin-default-policies", "TuningPlannerTest.kt:652", odinChassis)
        add(
            "general-mt9999",
            "TuningPlannerTest.kt:667",
            rp5.copy(
                socModel = "MT9999",
                supportedAxes = setOf(TuningAxis.CPU),
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(300_000, 500_000, 700_000, 900_000, 1_100_000, 1_200_000)),
                    policyWithOpps(4, listOf(500_000, 800_000, 1_100_000, 1_400_000, 1_700_000, 2_000_000)),
                ),
                gpuFrequenciesKhz = emptyList(),
                gpuNodeAvailable = false,
                fanNodeAvailable = false,
            ),
        )
        add("odin-real-tables", "TuningPlannerTest.kt:942,1042 odinCapabilities()", odinCapabilities())
        // Catalog fixture (not a TuningPlannerTest fixture): the normalisation the compiled
        // `resolve` performs (trim, upper case) on the same real tables.
        add("odin-real-tables-padded-lower-soc", "L1 addition: socModel \" cq8725s \"", odinCapabilities().copy(socModel = " cq8725s "))
        // TuningPlannerTest.kt:812: the vendor-mode mapping across families, each as capabilities().copy(socModel).
        // SM8250 itself is rp5-default above.
        listOf("CQ8725S", "SM8750", "QCS8550", "SM8550", "SM8650", "MT6893").forEach { soc ->
            add("rp5-soc-$soc", "TuningPlannerTest.kt:812", rp5.copy(socModel = soc))
        }
        return fixtures
    }

    /** Every fixture x vendor-key presence x profile, planned by [planner] (today's compiled one). */
    fun rows(planner: TuningPlanner = TuningPlanner()): List<GoldenRow> = buildList {
        all().forEach { fixture ->
            listOf(false, true).forEach { vendorKeyPresent ->
                val capabilities = withVendorKey(fixture.capabilities, vendorKeyPresent)
                PROFILES.forEach { profile ->
                    val plan = checkNotNull(planner.plan(profile, PACKAGE, capabilities)) {
                        "${fixture.id}: no plan for $profile"
                    }
                    add(
                        GoldenRow(
                            fixture = fixture.id,
                            familyId = TweakerHardwareFamilies.resolve(fixture.capabilities.socModel).id,
                            vendorKeyPresent = vendorKeyPresent,
                            profile = profile,
                            planHash = PlanHash.of(plan),
                            canonical = canonical(plan),
                        ),
                    )
                }
            }
        }
    }

    /**
     * The fixture with the vendor `performance_mode` key absent or present on this unit. Present means what the
     * probe reports: the flag and the `VENDOR_MODE` axis together (the planner gates on the axis alone).
     */
    fun withVendorKey(capabilities: HardwareCapabilities, present: Boolean): HardwareCapabilities =
        if (present) {
            capabilities.copy(
                supportedAxes = capabilities.supportedAxes + TuningAxis.VENDOR_MODE,
                vendorPerformanceModePresent = true,
            )
        } else {
            capabilities.copy(vendorPerformanceModePresent = false)
        }

    /** `PlanHash`'s canonical string, rebuilt here so the golden file also pins the format. */
    fun canonical(plan: TuningPlan): String = buildString {
        append(plan.profileId.trim())
        append('|').append(plan.packageName.trim())
        plan.cpuMaxKhz.toSortedMap().forEach { (policyId, khz) ->
            append("|cpu").append(policyId).append('=').append(khz)
        }
        append("|gpu=").append(plan.gpuMaxPowerLevel ?: "null")
        append("|fan=").append(plan.fanMode ?: "null")
        append("|calibrated=").append(plan.calibrated)
        plan.vendorMode?.let { append("|vmode=").append(it) }
    }

    // ---- verbatim copies of TuningPlannerTest.kt's private helpers (lines 1042-1112) ----

    private fun odinCapabilities() = capabilities().copy(
        manufacturer = "ayn",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "ayn/sun/odin3-rung-test",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
        cpuPolicies = listOf(
            policyWithOpps(
                0,
                listOf(
                    384_000, 556_800, 748_800, 960_000, 1_152_000, 1_363_200, 1_555_200, 1_785_600,
                    1_996_800, 2_227_200, 2_400_000, 2_745_600, 2_918_400, 3_072_000, 3_321_600, 3_532_800,
                ),
            ),
            policyWithOpps(
                6,
                listOf(
                    1_017_600, 1_209_600, 1_401_600, 1_689_600, 1_958_400, 2_246_400, 2_438_400,
                    2_649_600, 2_841_600, 3_072_000, 3_283_200, 3_513_600, 3_801_600, 4_089_600,
                    4_204_800, 4_320_000,
                ),
            ),
        ),
        gpuFrequenciesKhz = ODIN_GPU_14,
        fanNodeAvailable = false,
    )

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "rp5/test",
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            policy(0, 1_804_800),
            policy(4, 2_419_200),
            policy(7, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 400_000, 441_600, 490_000, 525_000, 587_000, 670_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private fun policy(id: Int, max: Int) = CpuPolicy(
        id = id,
        path = "/policy$id",
        cpuIds = listOf(id),
        availableFrequenciesKhz = listOf(max / 2, max * 3 / 4, max),
        minKhz = max / 2,
        maxKhz = max,
        currentKhz = max,
    )

    private fun policyWithOpps(id: Int, available: List<Int>) = CpuPolicy(
        id = id,
        path = "/policy$id",
        cpuIds = listOf(id),
        availableFrequenciesKhz = available,
        minKhz = available.first(),
        maxKhz = available.last(),
        currentKhz = available.last(),
    )
}
