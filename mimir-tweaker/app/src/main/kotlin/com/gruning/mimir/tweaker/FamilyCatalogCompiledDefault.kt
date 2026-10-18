package com.gruning.mimir.tweaker

/**
 * The catalog that is in force when nothing has been installed: the content of the bundled
 * `assets/tweaker-families.json`, written out as Kotlin so it needs no `org.json`.
 *
 * Why it exists as code rather than as a parse of the asset:
 * - plain JVM unit tests (`HardwareClassifierTest`, `TuningPlannerTest`) run with
 *   `isReturnDefaultValues = true`, where `org.json` silently returns nulls, so a parse there would
 *   produce garbage instead of failing;
 * - a process that has installed no catalog (a failed or unreadable store, or the very start of
 *   the `:guardian` process, which installs the live catalog like the main process, see
 *   [FamilyCatalogProcess.usesLiveCatalogIn]) resolves families from exactly these numbers,
 *   the very numbers the compiled `when` and the compiled planner tables carried before families
 *   became data;
 * - a process whose store could not be read at all still plans, because "offline = bundled, nothing
 *   ever gates running".
 *
 * `FamilyCatalogGoldenTest` pins `catalog == FamilyCatalogTestSupport.bundledCatalog()`, so this
 * file and the asset cannot drift apart: change one and the other test reddens.
 *
 * The numbers below are the ones the planner used to hold in private constants (all of this is
 * history worth keeping, so it moved here rather than being dropped):
 * - **8 Elite (CQ8725S / SM8750), the Odin 3 anchor.** The literal `khz` row is Sequence 10's signed
 *   vector (ClusterTune values, except `performance`, which is a Mimir-owned thermal allocation;
 *   the kHz values are hardware frequencies, see the root `NOTICE`, section 7).
 *   The GPU levels are explicit `max_pwrlevel` indices, level 5 = 734 MHz on the Odin's 14-entry
 *   table. Balanced and performance are level 0 (uncapped): the current profile setting (balanced used to be level 2, 967 MHz).
 *   Provenance of the `khz` row: CQ8725S values are from ClusterTune commit
 *   ddb589deeb507aec70fa6f3ecfbd43c62f9ceb8a, except `performance`, a Mimir-owned thermal
 *   allocation (sequence 6 moved its efficiency cluster to 2,227,200 kHz and sequence 8 one rung
 *   further down to 1,996,800 kHz, rung 9 of policy0's 16 live OPPs; the hardware minimum is
 *   `cpuinfo_min_freq` = 384,000 kHz, and `scaling_min_freq`, a mutable governor/QoS floor, never
 *   feeds planning). Sequence 9  retuned `balanced` to match
 *   `performance`'s six-core clamp (policy0 2,227,200 -> 1,996,800) and spent the freed envelope
 *   on the prime cluster (policy6 2,246,400 -> 2,438,400), and dropped `quiet`'s policy0 one live
 *   rung (1,996,800 -> 1,785,600, the battery QoS floor, so an already-exercised rung). That is,
 *   unchanged, the Sequence 10 signed vector the anchor ratios of the next row are derived from.
 * - **8 Gen 2 and SM8250, transposed from the anchor.** Each profile is a little/prime pair of
 *   integer ratios, the anchor's own target over the anchor's own live maximum (3,532,800 and
 *   4,320,000 kHz). They are integer pairs, never decimals, because `roundToInt` flips on a
 *   truncated fraction; [Ratio.toDouble] is the same IEEE division the compiled constant was.
 *   The GPU position fractions are `level / (anchorLevels - 1)` against the Odin's real 14-entry
 *   table (160/222/342/389/443/525/607/660/734/832/900/967/1050/1100 MHz; the design spec's "6
 *   levels" was a spec error). Only quiet does arithmetic (level 5); balanced and performance are
 *   uncapped and short-circuit before any fraction.
 * - **Vendor mode** is quiet 0 (Standard), balanced 1 (Performance), performance 2 (High
 *   performance), the profile defaults, stated per family so a catalog edit can differ per family.
 */
internal object FamilyCatalogCompiledDefault {
    /** The bundled catalog's `revision`. */
    const val REVISION = 1

    private const val ANCHOR_LITTLE_MAX_KHZ = 3_532_800
    private const val ANCHOR_PRIME_MAX_KHZ = 4_320_000
    private const val ANCHOR_GPU_LEVELS = 14

    private val vendorModes = mapOf(
        "quiet" to TuningPlanner.VENDOR_MODE_STANDARD,
        "balanced" to TuningPlanner.VENDOR_MODE_PERFORMANCE,
        "performance" to TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE,
    )

    private val eliteCpu = FamilyCpuTable.Khz(
        byProfile = mapOf(
            "quiet" to mapOf(0 to 1_785_600, 6 to 2_246_400),
            "balanced" to mapOf(0 to 1_996_800, 6 to 2_438_400),
            "performance" to mapOf(0 to 1_996_800, 6 to 3_072_000),
        ),
    )

    private val eliteGpu = FamilyGpuTable.Levels(
        byProfile = mapOf(
            "quiet" to TuningPlanner.ODIN_3_QUIET_GPU_MAX_POWER_LEVEL,
            "balanced" to TuningPlanner.UNCAPPED_GPU_MAX_POWER_LEVEL,
            "performance" to TuningPlanner.ODIN_3_PERFORMANCE_GPU_MAX_POWER_LEVEL,
        ),
    )

    private val anchoredCpu = FamilyCpuTable.Anchor(
        byProfile = mapOf(
            "quiet" to AnchorRow(
                little = Ratio(1_785_600, ANCHOR_LITTLE_MAX_KHZ),
                prime = Ratio(2_246_400, ANCHOR_PRIME_MAX_KHZ),
            ),
            "balanced" to AnchorRow(
                little = Ratio(1_996_800, ANCHOR_LITTLE_MAX_KHZ),
                prime = Ratio(2_438_400, ANCHOR_PRIME_MAX_KHZ),
            ),
            "performance" to AnchorRow(
                little = Ratio(1_996_800, ANCHOR_LITTLE_MAX_KHZ),
                prime = Ratio(3_072_000, ANCHOR_PRIME_MAX_KHZ),
            ),
        ),
    )

    private val transposedGpu = FamilyGpuTable.Transposed(
        anchorLevels = ANCHOR_GPU_LEVELS,
        capped = mapOf("quiet" to TuningPlanner.ODIN_3_QUIET_GPU_MAX_POWER_LEVEL),
        uncapped = setOf("balanced", "performance"),
    )

    val catalog: FamilyCatalog = FamilyCatalog(
        revision = REVISION,
        families = listOf(
            FamilyEntry(
                id = TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID,
                label = "Qualcomm SM8750 Snapdragon 8 Elite / CQ8725S Q-8750 / AYN Odin 3",
                treatment = TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE,
                socAliases = listOf("CQ8725S", "SM8750"),
                platformAliases = listOf("sun"),
                tables = FamilyTables(
                    cpu = eliteCpu,
                    gpu = eliteGpu,
                    vendorMode = vendorModes,
                    transports = linkedSetOf(
                        FamilyTransport.CPU_MSM_PERFORMANCE_QOS,
                        FamilyTransport.GPU_KGSL_MAX_PWRLEVEL,
                        FamilyTransport.VENDOR_PERFORMANCE_MODE,
                        FamilyTransport.FAN_PWM,
                    ),
                ),
            ),
            FamilyEntry(
                id = TweakerHardwareFamilies.SNAPDRAGON_8_GEN_2_FAMILY_ID,
                label = "Qualcomm QCS8550 / SM8550 / Snapdragon 8 Gen 2",
                treatment = TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE,
                socAliases = listOf("QCS8550", "SM8550"),
                platformAliases = listOf("kalama"),
                tables = FamilyTables(
                    cpu = anchoredCpu,
                    gpu = transposedGpu,
                    vendorMode = vendorModes,
                    transports = linkedSetOf(
                        FamilyTransport.CPU_MSM_PERFORMANCE_QOS,
                        FamilyTransport.GPU_KGSL_MAX_PWRLEVEL,
                        FamilyTransport.VENDOR_PERFORMANCE_MODE,
                        FamilyTransport.FAN_PWM,
                    ),
                ),
            ),
            FamilyEntry(
                id = TweakerHardwareFamilies.SM8250_FAMILY_ID,
                label = "Qualcomm SM8250 / Snapdragon 865",
                treatment = TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE,
                socAliases = listOf("SM8250"),
                platformAliases = listOf("kona"),
                tables = FamilyTables(
                    cpu = anchoredCpu,
                    gpu = transposedGpu,
                    vendorMode = vendorModes,
                    transports = linkedSetOf(
                        FamilyTransport.CPU_SCALING_MAX,
                        FamilyTransport.GPU_KGSL_MAX_PWRLEVEL,
                        FamilyTransport.FAN_PWM,
                    ),
                ),
            ),
        ),
    )
}
